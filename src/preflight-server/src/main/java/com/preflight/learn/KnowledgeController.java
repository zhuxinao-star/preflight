package com.preflight.learn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.preflight.common.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 知识与项目管理 API：项目/仓库 CRUD、触发学习、接口卡/数据对象卡查询与确认。 */
@RestController
@RequestMapping("/api")
public class KnowledgeController {

    private final JdbcTemplate jdbc;
    private final LearnService learn;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final TestabilityService testability;
    private final DynamicSqlService dynamicSql;
    private final com.preflight.ai.CommitTargetService commitTargets;
    private final com.preflight.common.ProgressRegistry progress;
    private final com.preflight.exec.ColumnValueCatalogService catalog;
    private final com.preflight.yapi.YapiDocOverrideService yapiDocs;
    private final DispatchDictionaryService dictionary;
    private final GatewayRoutingService routing;
    private final java.util.concurrent.ExecutorService learnPool =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "pf-learn");
                t.setDaemon(true);
                return t;
            });

    public KnowledgeController(JdbcTemplate jdbc, LearnService learn, ObjectMapper mapper, AuditService audit,
                               TestabilityService testability, DynamicSqlService dynamicSql,
                               com.preflight.ai.CommitTargetService commitTargets,
                               com.preflight.common.ProgressRegistry progress,
                               com.preflight.exec.ColumnValueCatalogService catalog,
                               com.preflight.yapi.YapiDocOverrideService yapiDocs,
                               DispatchDictionaryService dictionary, GatewayRoutingService routing) {
        this.jdbc = jdbc;
        this.learn = learn;
        this.mapper = mapper;
        this.audit = audit;
        this.testability = testability;
        this.dynamicSql = dynamicSql;
        this.commitTargets = commitTargets;
        this.progress = progress;
        this.catalog = catalog;
        this.yapiDocs = yapiDocs;
        this.dictionary = dictionary;
        this.routing = routing;
    }

    // ---------- 项目 ----------
    @GetMapping("/projects")
    public Map<String, Object> projects() {
        return Map.of("ok", true, "projects", jdbc.queryForList(
                "SELECT p.*, (SELECT COUNT(*) FROM repos r WHERE r.project_id=p.id) repo_count FROM projects p ORDER BY p.id"));
    }

    @PostMapping("/projects")
    public Map<String, Object> saveProject(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        if (name == null || name.isBlank()) return Map.of("ok", false, "error", "项目名不能为空");
        Long id = body.get("id") == null ? null : ((Number) body.get("id")).longValue();
        String desc = (String) body.getOrDefault("description", "");
        if (id == null) {
            jdbc.update("INSERT INTO projects(name, description) VALUES (?, ?)", name, desc);
        } else {
            jdbc.update("UPDATE projects SET name=?, description=? WHERE id=?", name, desc, id);
        }
        return Map.of("ok", true);
    }

    @DeleteMapping("/projects/{id}")
    public Map<String, Object> deleteProject(@PathVariable long id) {
        commitTargets.invalidateProject(id); // 派生缓存要先清（下面按项目联删时表还在）
        // 第三十一轮：仓库名下的派生数据（提交/改动明细/学习指纹/服务字典）以前没人删——
        // 这条路径只执行了 DELETE FROM repos，真实库里因此攒下 241 条孤儿提交。先逐仓库删净，再删仓库行。
        for (Map<String, Object> r : jdbc.queryForList("SELECT id FROM repos WHERE project_id=?", id)) {
            purgeRepo(((Number) r.get("id")).longValue());
        }
        jdbc.update("DELETE FROM file_bindings WHERE project_id=?", id);
        jdbc.update("DELETE FROM test_cases WHERE (target_type='interface' AND target_id IN (SELECT id FROM interfaces WHERE project_id=?))" +
                " OR (target_type='db_object' AND target_id IN (SELECT id FROM db_objects WHERE project_id=?))", id, id);
        jdbc.update("DELETE FROM interfaces WHERE project_id=?", id);
        jdbc.update("DELETE FROM db_objects WHERE project_id=?", id);
        jdbc.update("DELETE FROM login_flows WHERE project_id=?", id);
        jdbc.update("DELETE FROM environments WHERE project_id=?", id);
        jdbc.update("DELETE FROM repos WHERE project_id=?", id);
        // 第十二轮补齐：项目级表要一次删净，否则删项目后留下孤儿行（字典随知识包走后量更大，见 N25）
        jdbc.update("DELETE FROM dispatch_targets WHERE project_id=?", id);
        jdbc.update("DELETE FROM table_meta WHERE project_id=?", id);
        jdbc.update("DELETE FROM findings WHERE project_id=?", id);
        jdbc.update("DELETE FROM release_checklists WHERE project_id=?", id);
        jdbc.update("DELETE FROM column_value_cache WHERE project_id=?", id); // 第卅一轮：③ 面板取值缓存也是项目级
        yapiDocs.purge(id); // 第卅八轮：YApi 文档的人工维护层也是项目级资产，项目没了它没有归属
        // 同步目标与上次推送按项目记在 settings 里（不建表），所以删项目时要一起删：
        // 第四十一轮的验证夹具跑完就发现「yapi.target.<id>」在库上成了没人认领的孤儿行
        jdbc.update("DELETE FROM settings WHERE key IN (?, ?)", "yapi.target." + id, "yapi.last_push." + id);
        jdbc.update("DELETE FROM projects WHERE id=?", id);
        return Map.of("ok", true);
    }

    // ---------- 仓库 ----------
    /**
     * 三个口径各数各的，前端按场景取用（第十九轮：以前只有一个 iface_count，
     * 结果选 SQL 卡的下拉也写着"0 接口"，而 SQL 卡其实有几千张）：
     * iface_count = 接口卡（interfaces.repo_id 直连）；
     * db_count = SQL/数据对象卡（这张表没有 repo_id，只能走 file_bindings）；
     * commit_count = 同步进来的提交/版本数。
     */
    private static final String REPO_SELECT = """
            SELECT r.*,
              (SELECT COUNT(*) FROM interfaces i WHERE i.repo_id = r.id) iface_count,
              (SELECT COUNT(DISTINCT b.unit_id) FROM file_bindings b
                WHERE b.repo_id = r.id AND b.unit_kind = 'db_object') db_count,
              (SELECT COUNT(*) FROM commits c WHERE c.repo_id = r.id) commit_count
            FROM repos r""";

    @GetMapping("/repos")
    public Map<String, Object> repos(@RequestParam(required = false) Long projectId) {
        List<Map<String, Object>> rows;
        if (projectId == null) {
            rows = jdbc.queryForList(REPO_SELECT + " ORDER BY r.id");
        } else {
            rows = jdbc.queryForList(REPO_SELECT + " WHERE r.project_id=? ORDER BY r.id", projectId);
        }
        return Map.of("ok", true, "repos", rows);
    }

    @PostMapping("/repos")
    public Map<String, Object> saveRepo(@RequestBody Map<String, Object> body) {
        Long id = body.get("id") == null ? null : ((Number) body.get("id")).longValue();
        long projectId = ((Number) body.get("projectId")).longValue();
        String repoType = (String) body.getOrDefault("repoType", "backend");
        String vcsType = (String) body.getOrDefault("vcsType", "git");
        if (vcsType == null || vcsType.isBlank()) vcsType = "git"; // 前端偶发空值兜底：与行的 GIT 徽标保持一致
        String name = (String) body.getOrDefault("name", "");
        String url = (String) body.get("url");
        String localPath = (String) body.get("localPath");
        String branch = (String) body.get("branch");
        String baseUrl = (String) body.get("baseUrl");
        if (baseUrl != null && baseUrl.isBlank()) baseUrl = null;
        if (localPath == null || localPath.isBlank()) return Map.of("ok", false, "error", "本地路径不能为空");
        if (id == null) {
            jdbc.update("INSERT INTO repos(project_id, vcs_type, repo_type, name, url, local_path, branch, base_url) VALUES (?,?,?,?,?,?,?,?)",
                    projectId, vcsType, repoType, name, url, localPath, branch, baseUrl);
        } else {
            jdbc.update("UPDATE repos SET vcs_type=?, repo_type=?, name=?, url=?, local_path=?, branch=?, base_url=? WHERE id=?",
                    vcsType, repoType, name, url, localPath, branch, baseUrl, id);
        }
        return Map.of("ok", true);
    }

    @DeleteMapping("/repos/{id}")
    public Map<String, Object> deleteRepo(@PathVariable long id) {
        // 第十九轮：以前只删 repos 行，接口的卡就成了"哪都筛不到"的孤儿（数还是那些数，下拉却全显示 0）。
        // 现在显式解绑：卡留在知识库里，标成「未归属仓库」，并在返回体里说清解绑了几张。
        int detached = purgeRepo(id);
        jdbc.update("DELETE FROM repos WHERE id=?", id);
        audit.log("repo.delete", "repo#" + id, "解绑接口卡 " + detached + " 张");
        return Map.of("ok", true, "detachedInterfaces", detached);
    }

    /**
     * 把一个仓库名下的派生数据一次删净，返回解绑（而非删除）的接口卡张数。
     * 删仓库与删项目必须走同一个出口：第三十一轮清点，之前"删项目"只执行了 DELETE FROM repos，
     * 于是仓库的提交、改动明细、学习指纹全留在库里——真实库里已攒下 241 条孤儿提交 + 2461 条改动 + 97 条指纹。
     * 新增带 repo_id / project_id 的表时要在这里补一行；漏了会被一次性清点脚本抓出来
     * （preflight/.tmp/r31/audit2.py：逐表数孤儿行）。
     */
    private int purgeRepo(long repoId) {
        commitTargets.invalidateRepo(repoId); // commit_targets 由归因服务自己删，顺序要在 commits 之前
        int detached = jdbc.update("UPDATE interfaces SET repo_id=NULL WHERE repo_id=?", repoId);
        jdbc.update("DELETE FROM file_bindings WHERE repo_id=?", repoId);
        jdbc.update("DELETE FROM chain_files WHERE repo_id=?", repoId); // 第 44 轮：调用链记账也是仓库派生数据
        jdbc.update("DELETE FROM dispatch_targets WHERE repo_id=?", repoId);
        jdbc.update("DELETE FROM file_changes WHERE commit_id IN (SELECT id FROM commits WHERE repo_id=?)", repoId);
        jdbc.update("DELETE FROM learned_files WHERE repo_id=?", repoId);
        jdbc.update("DELETE FROM commits WHERE repo_id=?", repoId);
        return detached;
    }

    // ---------- 学习（P1-13：异步执行 + 任务进度查询，不再阻塞 HTTP 线程） ----------
    @PostMapping("/learn")
    public Map<String, Object> learn(@RequestBody Map<String, Object> body,
                                     @RequestParam(required = false) String pid) {
        long projectId = ((Number) body.get("projectId")).longValue();
        long repoId = ((Number) body.get("repoId")).longValue();
        String dir = (String) body.get("dir");
        try {
            Integer p = jdbc.queryForObject("SELECT COUNT(*) FROM projects WHERE id=?", Integer.class, projectId);
            Map<String, Object> repo = jdbc.queryForMap("SELECT * FROM repos WHERE id=?", repoId);
            if (p == null || p == 0) return Map.of("ok", false, "error", "项目不存在");
            if (dir == null || dir.isBlank()) dir = (String) repo.get("local_path");
            List<Map<String, Object>> running = jdbc.queryForList(
                    "SELECT id FROM tasks WHERE type='learn' AND status='running' ORDER BY id DESC LIMIT 5");
            for (Map<String, Object> t : running) {
                try {
                    if (mapper.readTree((String) t.get("payload")).path("repoId").asLong() == repoId) {
                        return Map.of("ok", false, "error", "该仓库正在学习中，请等待完成（任务 #" + t.get("id") + "）");
                    }
                } catch (Exception ignore) {
                }
            }
            boolean force = Boolean.TRUE.equals(body.get("force"));
            jdbc.update("INSERT INTO tasks(type, payload, status) VALUES ('learn', ?, 'running')",
                    "{\"repoId\":" + repoId + ",\"projectId\":" + projectId + ",\"dir\":\""
                            + dir.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
            long taskId = jdbc.queryForObject("SELECT id FROM tasks ORDER BY id DESC LIMIT 1", Long.class);
            final String fdir = dir;
            learnPool.submit(() -> {
                try {
                    LearnService.LearnResult r = learn.learn(projectId, repoId, fdir, force, pid);
                    jdbc.update("UPDATE tasks SET status='done', payload=?, updated_at=datetime('now','localtime') WHERE id=?",
                            mapper.writeValueAsString(r), taskId);
                    audit.log("learn.done", "repo=" + repoId, "task=" + taskId + " 接口+" + r.interfacesCreated()
                            + " SQL+" + r.dbObjectsCreated());
                    // 学完立刻给出"能不能测"的结论（第 11 轮）：不落库，只留痕，项目页那张卡实时展示明细
                    audit.log("learn.testability", "project#" + projectId, "repo=" + repoId + " " + testability.brief(projectId));
                } catch (Exception e) {
                    jdbc.update("UPDATE tasks SET status='failed', last_error=?, updated_at=datetime('now','localtime') WHERE id=?",
                            String.valueOf(e.getMessage()), taskId);
                    audit.log("learn.failed", "repo=" + repoId, "task=" + taskId + " " + e.getMessage());
                } finally {
                    // 进度条目由发起方收口：前端最后一轮轮询能看到 finished=true
                    progress.end(pid);
                }
            });
            audit.log("learn.submit", "repo=" + repoId, "task=" + taskId);
            return Map.of("ok", true, "taskId", taskId, "async", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    @GetMapping("/learn/task/{id}")
    public Map<String, Object> learnTask(@PathVariable long id) {
        try {
            Map<String, Object> t = jdbc.queryForMap("SELECT * FROM tasks WHERE id=? AND type='learn'", id);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("status", t.get("status"));
            if ("done".equals(t.get("status"))) {
                try {
                    out.put("result", mapper.readValue((String) t.get("payload"), Map.class));
                } catch (Exception ignore) {
                }
            }
            if (t.get("last_error") != null) out.put("error", t.get("last_error"));
            return out;
        } catch (Exception e) {
            return Map.of("ok", false, "error", "任务不存在");
        }
    }

    // ---------- 接口卡 ----------
    /** 列表：不带 page 时返回全量（知识库页兼容）；带 page 时服务端 搜索+仓库筛选+分页（测试台弹屏，第八轮） */
    @GetMapping("/interfaces")
    public Map<String, Object> interfaces(@RequestParam long projectId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(required = false) Long repoId,
                                          @RequestParam(required = false) Boolean noRepo,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size,
                                          @RequestParam(required = false) Boolean callPath,
                                          @RequestParam(required = false) Long envId) {
        StringBuilder from = new StringBuilder("FROM interfaces i LEFT JOIN repos r ON r.id=i.repo_id WHERE i.project_id=?");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(projectId);
        if (status != null && !status.isBlank()) {
            from.append(" AND i.status=?");
            args.add(status);
        }
        if (q != null && !q.isBlank()) {
            String esc = com.preflight.common.SqlLikes.ESCAPE_CLAUSE;
            from.append(" AND (lower(i.name) LIKE ?").append(esc)
                    .append(" OR lower(i.path) LIKE ?").append(esc).append(")");
            String needle = com.preflight.common.SqlLikes.contains(q.toLowerCase());
            args.add(needle);
            args.add(needle);
        }
        if (repoId != null) {
            from.append(" AND i.repo_id=?");
            args.add(repoId);
        }
        if (Boolean.TRUE.equals(noRepo)) {
            from.append(" AND i.repo_id IS NULL");
        }
        Integer total = jdbc.queryForObject("SELECT COUNT(*) " + from, Integer.class, args.toArray());
        String sql = "SELECT i.*, r.name AS repo_name " + from + " ORDER BY i.id";
        java.util.List<Object> listArgs = new java.util.ArrayList<>(args);
        int sz = size == null || size < 1 ? 20 : Math.min(size, 200);
        if (page != null) {
            sql += " LIMIT ? OFFSET ?";
            listArgs.add(sz);
            listArgs.add(com.preflight.common.Paging.offset(Math.max(page, 1), sz));
        }
        List<Map<String, Object> > rows = jdbc.queryForList(sql, listArgs.toArray());
        GatewayRoutingService.EnvRef env = Boolean.TRUE.equals(callPath) ? envFor(projectId, envId) : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("total", total == null ? 0 : total);
        out.put("page", page == null ? 0 : page);
        out.put("size", sz);
        out.put("interfaces", enrichCallPath(projectId, rows, env, Boolean.TRUE.equals(callPath)));
        out.put("call_env", env == null ? "" : env.name() + "（" + env.baseUrl() + "）");
        // 列头要点名"这一列按哪个环境算的"：同一张卡换个环境可能换一段前缀，不点名就是又一处暗猜
        out.put("call_note", env == null && Boolean.TRUE.equals(callPath)
                ? "这个项目还没有测试环境：「环境地址是不是网关自己的端口」这一条没法判，先把环境建起来再看这一列"
                : "");
        return out;
    }

    /** 这一屏是不是要算对外路径：算一次要读盘（项目级证据 TTL 内共用），所以只在界面点名要时才给 */
    private List<Map<String, Object>> enrichCallPath(long projectId, List<Map<String, Object>> rows,
                                                    GatewayRoutingService.EnvRef env, boolean wanted) {
        if (!wanted) return rows;
        String baseUrl = env == null ? "" : env.baseUrl();
        for (Map<String, Object> row : rows) {
            String ps = row.get("params_schema") == null ? "" : String.valueOf(row.get("params_schema"));
            if (ps.contains("\"dispatch\"")) {
                // 分发入口卡没有"自己的一张路径"：要发什么是选中服务码之后由服务字典决定的
                row.put("call_state", GatewayRoutingService.NOT_APPLICABLE);
                row.put("call_state_label", "分发入口");
                row.put("call_path", String.valueOf(row.get("path")));
                row.put("call_note_row", "这一张是分发入口卡：路径由服务字典按服务码给，不参与网关补前缀");
                continue;
            }
            GatewayRoutingService.Answer a = routing.forCard(projectId,
                    row.get("repo_id") instanceof Number n ? n.longValue() : null,
                    String.valueOf(row.get("path")),
                    row.get("source") == null ? "" : String.valueOf(row.get("source")),
                    baseUrl, false, GatewayRoutingService.callPathDeclared(ps));
            row.put("call_state", a.state());
            row.put("call_state_label", a.stateLabel());
            row.put("call_path", a.path());
            row.put("call_changed", a.changed());
            row.put("call_evidence", String.join("；", a.evidence()));
            row.put("call_note_row", a.note());
        }
        return rows;
    }

    /** 列表按哪个环境算这一列：显式给的优先，否则用与执行内核同一条规则兜的那个 */
    private GatewayRoutingService.EnvRef envFor(long projectId, Long envId) {
        if (envId != null) {
            List<Map<String, Object>> hit = jdbc.queryForList(
                    "SELECT id,name,base_url FROM environments WHERE id=? AND project_id=?", envId, projectId);
            if (!hit.isEmpty()) {
                Map<String, Object> r = hit.get(0);
                return new GatewayRoutingService.EnvRef(((Number) r.get("id")).longValue(),
                        String.valueOf(r.get("name")),
                        r.get("base_url") == null ? "" : String.valueOf(r.get("base_url")));
            }
        }
        return routing.firstEnv(projectId);
    }

    @GetMapping("/interfaces/{id}")
    public Map<String, Object> interfaceDetail(@PathVariable long id) {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM interfaces WHERE id=?", id);
        if (row.get("repo_id") != null) {
            for (Map<String, Object> r : jdbc.queryForList("SELECT name, base_url FROM repos WHERE id=?", row.get("repo_id"))) {
                row.put("repo_name", r.get("name"));
                row.put("repo_base_url", r.get("base_url"));
            }
        }
        // 第 11 轮：预填值按跨项目规则表现算（op_code、功能号类字段），不落 params_schema、不进 schema_hash
        Optional<String> functionNo = com.preflight.parser.DefaultValueRules.functionNo((String) row.get("path"));
        row.put("function_no", functionNo.orElse(""));
        row.put("function_no_known", functionNo.isPresent());
        row.put("body_sample", bodySamples(row.get("params_schema"), functionNo));
        row.put("param_note", paramNote(row, functionNo.orElse("")));
        row.put("cases", jdbc.queryForList(
                "SELECT * FROM test_cases WHERE target_type='interface' AND target_id=? ORDER BY id", id));
        row.put("runs", jdbc.queryForList(
                "SELECT * FROM test_runs WHERE target_type='interface' AND target_id=? ORDER BY id DESC LIMIT 20", id));
        return Map.of("ok", true, "interface", row);
    }

    /**
     * 「这条卡为什么没入参」的一句话：只在这张卡<b>既没打到分发标记、也没学到任何请求体字段</b>时才问，
     * 其余情况返回空串（有值就不该解释，有分发面板的话面板自己会列参数清单）。
     */
    @SuppressWarnings("unchecked")
    private String paramNote(Map<String, Object> row, String functionNo) {
        if (row.get("project_id") == null || !(row.get("params_schema") instanceof String s) || s.isBlank()) return "";
        Map<String, Object> schema;
        try {
            schema = mapper.readValue(s, Map.class);
        } catch (Exception e) {
            return "";
        }
        if (schema.get("dispatch") instanceof Map || !onlyHeaderLearned(schema)) return "";
        String note = dictionary.whyNoParams(((Number) row.get("project_id")).longValue(), functionNo);
        return note == null ? "" : note;
    }

    /**
     * 这张卡是不是「只学到了公共报文头、一条业务参数都没有」。
     *
     * <p>判据按形状而不是按名字：报文头那一段在请求体根上是一个带子字段的对象，业务参数是根上的标量／数组键
     * （这来自框架自己的声明形状，见 {@code DispatchAdapter#callSpec}：业务键要么平铺在根上，要么整体落在
     * 一个自由结构里）。所以根上除对象段之外还有别的键，就算学到过业务参数，不该再解释。
     * 刻意不写 {@code head} / {@code req_msg_hdr} 这类名字白名单——换一家框架就换一套键名。
     */
    private boolean onlyHeaderLearned(Map<String, Object> schema) {
        Object props = schema.get("properties");
        if (!(props instanceof Map<?, ?> m) || m.isEmpty()) return true;
        for (Object v : m.values()) {
            if (!(v instanceof Map<?, ?> raw)) continue;
            Object child = raw.get("properties");
            if (!(child instanceof Map<?, ?> c) || c.isEmpty()) return false;   // 请求体上连子字段都没有＝什么都没学到
            for (Object o : c.values()) {
                if (!(o instanceof Map<?, ?> leaf)) continue;
                if (!(leaf.get("properties") instanceof Map<?, ?> sub) || sub.isEmpty()) return false;
            }
        }
        return true;
    }

    /** body 形参 → 预填样例（键为形参名，与前端每个请求体文本框一一对应） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> bodySamples(Object paramsSchema, Optional<String> functionNo) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(paramsSchema instanceof String s) || s.isBlank()) return out;
        try {
            Map<String, Object> schema = mapper.readValue(s, Map.class);
            if (!(schema.get("properties") instanceof Map<?, ?> props)) return out;
            for (Map.Entry<?, ?> en : props.entrySet()) {
                if (!(en.getValue() instanceof Map<?, ?> raw)) continue;
                Map<String, Object> ps = (Map<String, Object>) raw;
                if (!"body".equals(String.valueOf(ps.getOrDefault("in", "query")))) continue;
                out.put(String.valueOf(en.getKey()), com.preflight.parser.BodyShape.skeletonOf(
                        String.valueOf(en.getKey()), ps, com.preflight.parser.BodyShape.sampler(functionNo), false));
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    /** P1-9：手工新建接口卡（学习漏掉的非常规框架/二方包端点也能进系统）。直接 confirmed、human_edited。 */
    @PostMapping("/interfaces")
    public Map<String, Object> createInterface(@RequestBody Map<String, Object> body) {
        long projectId = ((Number) body.get("projectId")).longValue();
        String method = String.valueOf(body.getOrDefault("httpMethod", "GET")).toUpperCase();
        String path = String.valueOf(body.getOrDefault("path", "")).trim();
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        if (!List.of("GET", "POST", "PUT", "DELETE", "PATCH").contains(method)) {
            return Map.of("ok", false, "error", "HTTP 方法不合法（GET/POST/PUT/DELETE/PATCH）");
        }
        if (!path.startsWith("/") || path.isBlank()) return Map.of("ok", false, "error", "路径必须以 / 开头");
        if (name.isEmpty()) return Map.of("ok", false, "error", "名称不能为空");
        try {
            jdbc.update("INSERT INTO interfaces(project_id, protocol, name, http_method, path, params_schema, source, confidence, status, human_edited) "
                            + "VALUES (?, 'http', ?, ?, ?, ?, '[]', 1.0, 'confirmed', 1)",
                    projectId, name, method, path,
                    body.get("paramsSchema") == null ? null : toJson(body.get("paramsSchema")));
            Long id = jdbc.queryForObject("SELECT id FROM interfaces WHERE project_id=? AND protocol='http' AND http_method=? AND path=?",
                    Long.class, projectId, method, path);
            audit.log("interface.create", "interface=" + id, method + " " + path + " · " + name + "（手工新建）");
            return Map.of("ok", true, "id", id);
        } catch (Exception e) {
            return Map.of("ok", false, "error", "创建失败（同方法同路径可能已存在）：" + e.getMessage());
        }
    }

    @DeleteMapping("/interfaces/{id}")
    public Map<String, Object> deleteInterface(@PathVariable long id) {
        try {
            Map<String, Object> itf = jdbc.queryForMap("SELECT * FROM interfaces WHERE id=?", id);
            jdbc.update("DELETE FROM test_cases WHERE target_type='interface' AND target_id=?", id);
            jdbc.update("DELETE FROM interfaces WHERE id=?", id);
            audit.log("interface.delete", "interface=" + id,
                    itf.get("http_method") + " " + itf.get("path"));
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", "接口不存在");
        }
    }

    @PatchMapping("/interfaces/{id}")
    public Map<String, Object> updateInterface(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String name = (String) body.getOrDefault("name", null);
        Object schema = body.get("paramsSchema");
        String status = (String) body.get("status");
        String method = (String) body.get("httpMethod");
        String path = (String) body.get("path");
        if (name != null) {
            jdbc.update("UPDATE interfaces SET name=?, human_edited=1 WHERE id=?", name, id);
        }
        if (schema != null) {
            jdbc.update("UPDATE interfaces SET params_schema=?, schema_hash=?, human_edited=1 WHERE id=?",
                    toJson(schema), LearnService.sha256(toJson(schema)), id);
        }
        if (status != null) {
            jdbc.update("UPDATE interfaces SET status=? WHERE id=?", status, id);
        }
        if (method != null || path != null) {
            // P1-9：path/method 可修正（学习解析错的端点不再无解）
            if (method != null) {
                method = method.toUpperCase();
                if (!List.of("GET", "POST", "PUT", "DELETE", "PATCH").contains(method)) {
                    return Map.of("ok", false, "error", "HTTP 方法不合法");
                }
            }
            if (path != null && !path.startsWith("/")) return Map.of("ok", false, "error", "路径必须以 / 开头");
            List<String> sets = new ArrayList<>();
            List<Object> args = new ArrayList<>();
            if (method != null) {
                sets.add("http_method=?");
                args.add(method);
            }
            if (path != null) {
                sets.add("path=?");
                args.add(path.trim());
            }
            sets.add("human_edited=1");
            args.add(id);
            try {
                jdbc.update("UPDATE interfaces SET " + String.join(",", sets) + " WHERE id=?", args.toArray());
                audit.log("interface.edit", "interface=" + id, "method/path 改为 " + method + " " + path);
            } catch (Exception e) {
                return Map.of("ok", false, "error", "修改后与同项目其他接口卡重复（方法+路径唯一）");
            }
        }
        jdbc.update("UPDATE interfaces SET updated_at=datetime('now','localtime') WHERE id=?", id);
        return Map.of("ok", true);
    }

    /** 按 id 批量确认。必须带 projectId 且只影响该项目的卡，避免跨项目误确认（第十二轮补）。 */
    @PostMapping("/interfaces/confirm")
    public Map<String, Object> confirmInterfaces(@RequestBody Map<String, Object> body) {
        List<Number> ids = (List<Number>) body.get("ids");
        if (ids == null || ids.isEmpty()) return Map.of("ok", false, "error", "ids 为空");
        if (!(body.get("projectId") instanceof Number p)) return Map.of("ok", false, "error", "缺少 projectId");
        StringBuilder in = new StringBuilder();
        for (Number n : ids) in.append(in.isEmpty() ? "" : ",").append(n.longValue());
        int done = jdbc.update("UPDATE interfaces SET status='confirmed', updated_at=datetime('now','localtime') "
                + "WHERE project_id=? AND status='draft' AND id IN (" + in + ")", p.longValue());
        audit.log("learn.confirm-batch", "project#" + p.longValue(), "按勾选确认草稿 " + done + " 张（请求 " + ids.size() + "）");
        return Map.of("ok", true, "confirmed", done);
    }

    /**
     * N26：按置信度批量确认的预览——只看草稿，返回命中总数 + 前 20 张抽查。
     * 置信度分档是学习器自报的（0.6 常规 / 0.9 强证据），所以确认前必须让人抽查，不能一键放行全部。
     */
    @GetMapping("/interfaces/confirm-preview")
    public Map<String, Object> confirmPreview(@RequestParam long projectId,
                                              @RequestParam double minConfidence) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (minConfidence < 0 || minConfidence > 1) return Map.of("ok", false, "error", "置信度阈值需在 0~1 之间");
        List<Map<String, Object>> hits = jdbc.queryForList("""
                SELECT id, name, http_method, path, confidence FROM interfaces
                WHERE project_id=? AND status='draft' AND confidence>=?
                ORDER BY confidence DESC, id LIMIT 20""", projectId, minConfidence);
        Integer total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM interfaces
                WHERE project_id=? AND status='draft' AND confidence>=?""", Integer.class, projectId, minConfidence);
        Integer drafts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interfaces WHERE project_id=? AND status='draft'", Integer.class, projectId);
        out.put("ok", true);
        out.put("total", total == null ? 0 : total);
        out.put("drafts", drafts == null ? 0 : drafts);
        out.put("preview", hits);
        return out;
    }

    /** N26：按置信度批量确认。只改 status，不置 human_edited——重学时人工编辑保护不该被一次批量确认误触发。 */
    @PostMapping("/interfaces/confirm-by-confidence")
    public Map<String, Object> confirmByConfidence(@RequestBody Map<String, Object> body) {
        if (!(body.get("projectId") instanceof Number p)) return Map.of("ok", false, "error", "缺少 projectId");
        if (!(body.get("minConfidence") instanceof Number c)) return Map.of("ok", false, "error", "缺少 minConfidence");
        double min = c.doubleValue();
        long projectId = p.longValue();
        if (min < 0 || min > 1) return Map.of("ok", false, "error", "置信度阈值需在 0~1 之间");
        int done = jdbc.update("UPDATE interfaces SET status='confirmed', updated_at=datetime('now','localtime') "
                + "WHERE project_id=? AND status='draft' AND confidence>=?", projectId, min);
        audit.log("learn.confirm-batch", "project#" + projectId,
                "按置信度≥" + min + " 确认草稿 " + done + " 张");
        return Map.of("ok", true, "confirmed", done, "minConfidence", min);
    }

    // ---------- 数据对象卡 ----------
    /** 同 /interfaces：带 page 时服务端 搜索+仓库筛选（经 file_bindings 归属）+分页（第八轮） */
    @GetMapping("/db-objects")
    public Map<String, Object> dbObjects(@RequestParam long projectId,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(required = false) Long repoId,
                                         @RequestParam(required = false) Boolean noRepo,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        StringBuilder from = new StringBuilder("FROM db_objects d WHERE d.project_id=?");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(projectId);
        if (status != null && !status.isBlank()) {
            from.append(" AND d.status=?");
            args.add(status);
        }
        if (q != null && !q.isBlank()) {
            String esc = com.preflight.common.SqlLikes.ESCAPE_CLAUSE;
            from.append(" AND (lower(d.name) LIKE ?").append(esc)
                    .append(" OR lower(d.sql_template) LIKE ?").append(esc).append(")");
            String needle = com.preflight.common.SqlLikes.contains(q.toLowerCase());
            args.add(needle);
            args.add(needle);
        }
        if (repoId != null) {
            from.append(" AND EXISTS (SELECT 1 FROM file_bindings fb WHERE fb.unit_kind='db_object' AND fb.unit_id=d.id AND fb.repo_id=?)");
            args.add(repoId);
        }
        if (Boolean.TRUE.equals(noRepo)) {
            from.append(" AND NOT EXISTS (SELECT 1 FROM file_bindings fb WHERE fb.unit_kind='db_object' AND fb.unit_id=d.id)");
        }
        Integer total = jdbc.queryForObject("SELECT COUNT(*) " + from, Integer.class, args.toArray());
        // 显式列清单：dynamic_body 最长近 2 万字符，列表/选卡弹屏都不用它（拼装按 id 现取），别把它乘 50 份发给前端
        String sql = "SELECT d.id, d.project_id, d.name, d.obj_type, d.signature, d.sql_template, d.source_sql, d.source,"
                + " d.tables_involved, d.confidence, d.status, d.human_edited, d.updated_at, d.database_id,"
                + " (SELECT GROUP_CONCAT(DISTINCT r.name) FROM file_bindings fb2 JOIN repos r ON r.id=fb2.repo_id"
                + " WHERE fb2.unit_kind='db_object' AND fb2.unit_id=d.id) AS repo_names,"
                + " CASE WHEN d.dynamic_body IS NULL OR d.dynamic_body='' THEN 0 ELSE 1 END AS has_dynamic "
                + from + " ORDER BY d.id";
        java.util.List<Object> listArgs = new java.util.ArrayList<>(args);
        int sz = size == null || size < 1 ? 20 : Math.min(size, 200);
        if (page != null) {
            sql += " LIMIT ? OFFSET ?";
            listArgs.add(sz);
            listArgs.add(com.preflight.common.Paging.offset(Math.max(page, 1), sz));
        }
        return Map.of("ok", true, "dbObjects", jdbc.queryForList(sql, listArgs.toArray()),
                "total", total == null ? 0 : total, "page", page == null ? 0 : page, "size", sz);
    }

    /** 单张数据对象卡（第 15 轮补）：用例目录点【打开目标】要按 id 深链到 SQL 直测，原先只有列表接口、GET 单卡是 405 */
    @GetMapping("/db-objects/{id}")
    public Map<String, Object> dbObject(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.*, (SELECT GROUP_CONCAT(DISTINCT r.name) FROM file_bindings fb
                               JOIN repos r ON r.id=fb.repo_id
                               WHERE fb.unit_kind='db_object' AND fb.unit_id=d.id) AS repo_names
                FROM db_objects d WHERE d.id=?""", id);
        if (rows.isEmpty()) return Map.of("ok", false, "error", "数据对象卡不存在 #" + id);
        return Map.of("ok", true, "dbObject", rows.get(0));
    }

    /**
     * 第 14 轮：按参数拼装这张卡的动态 SQL。片段表/参数表都是读取期现算，只回不落库的派生视图。
     * 只读推导、不碰数据库，所以各角色都能调；真正执行仍走 /api/db-test（护栏、审计、viewer 只读都在那边）。
     */
    @PostMapping("/db-objects/{id}/compose")
    public Map<String, Object> composeDbObject(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        var c = dynamicSql.compose(id, DynamicSqlService.inputsOf(b.get("params")),
                DynamicSqlService.overridesOf(b.get("overrides")));
        var r = c.result();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", r.errors().isEmpty() && r.sql() != null);
        out.put("name", c.name());
        out.put("hasDynamic", c.hasDynamic());
        out.put("sqlTemplate", c.sqlTemplate());
        out.put("sql", r.sql());
        out.put("values", r.values());
        out.put("fragments", r.fragments());
        out.put("params", r.params());
        out.put("outSlots", r.outSlots());
        // 第 27 轮：只参与条件判断的变量（没有占位符站点、也就没有条款）的候选值来源
        out.put("testValues", r.testValues());
        out.put("errors", r.errors());
        out.put("warnings", r.warnings());
        // 第 23 轮：① 区要显示源码原样（? 与 'DOLLAR' 换回 #{x} / ${x}）。只是展示视图，执行仍用 sqlTemplate
        out.put("namedSql", c.named().sql());
        out.put("namedReason", c.named().reason());
        out.put("hashNames", c.named().hashNames());
        out.put("dollarNames", c.named().dollarNames());
        return out;
    }

    /**
     * 第 27 轮：③ 面板的候选值。缓存优先，{@code force=1} 才回库重查；一条只读连接、每条查询带超时和行数上限。
     *
     * 只查该查的：这一位判成多值、或者本句用数据字典翻译它——单值文本框按用户拍板不查库（"那样值太多，会拖死"）。
     * 表和列一律由这张卡自己的 {@code dynamic_body} 现场推出（前端只报名字，报不出表列），推不出来的原样说明为什么。
     */
    @PostMapping("/db-objects/{id}/candidates")
    public Map<String, Object> candidates(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        if (!(b.get("projectId") instanceof Number pn)) return Map.of("ok", false, "error", "缺少 projectId");
        if (!(b.get("envId") instanceof Number en)) return Map.of("ok", false, "error", "缺少 envId");
        long projectId = pn.longValue();
        long envId = en.longValue();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT project_id FROM db_objects WHERE id=?", id);
        if (rows.isEmpty()) return Map.of("ok", false, "error", "数据对象卡不存在 #" + id);
        if (((Number) rows.get(0).get("project_id")).longValue() != projectId) {
            return Map.of("ok", false, "error", "这张卡不属于项目 #" + projectId + "，没查库");
        }
        var composed = dynamicSql.compose(id, Map.of(), Map.of()).result();

        // 参数名 → 该名字在这张卡上落到的取值目标。同一个名字落到两个不同列就是歧义，一项都不给。
        Map<String, List<com.preflight.exec.ColumnValueCatalogService.Target>> byParam = new LinkedHashMap<>();
        Map<String, String> why = new LinkedHashMap<>();
        // 参数名 → 被过滤的那一列（业务表.业务列）：列注释只认这个，字典表的取值列不算这一列的含义
        Map<String, String[]> biz = new LinkedHashMap<>();
        for (var p : composed.params()) {
            if (p.outType() != null) continue;                       // 出参位不向人要值
            var cl = p.clause();
            String name = p.name();
            if (name == null || name.isBlank()) continue;
            if (cl != null && cl.table() != null && cl.column() != null) {
                biz.putIfAbsent(name, new String[]{cl.table(), cl.column()});
            }
            if (cl == null) {
                why.putIfAbsent(name, "这一位没算出条件条款（源码里看不出它在跟哪一列比），不查库");
                continue;
            }
            boolean multi = Boolean.TRUE.equals(cl.multi());
            if (!multi && cl.dict() == null) {
                why.putIfAbsent(name, "这是单值文本框，按约定不查库（库里这样的列值太多，列出来拖死页面）");
                continue;
            }
            var t = cl.dict() != null
                    ? com.preflight.exec.ColumnValueCatalogService.Target.ofDict(cl.dict())
                    : (cl.table() == null || cl.column() == null ? null
                            : com.preflight.exec.ColumnValueCatalogService.Target.ofColumn(cl.table(), cl.column()));
            if (t == null) {
                why.putIfAbsent(name, multi ? "判成多值，但表名或列名没定下来，不查库" : "字典子查询形状不完整，不查库");
                continue;
            }
            byParam.computeIfAbsent(name, k -> new ArrayList<>()).add(t);
        }

        Map<String, com.preflight.exec.ColumnValueCatalogService.Target> keys = new LinkedHashMap<>();
        for (var e : byParam.values()) {
            for (var t : e) if (t != null) keys.putIfAbsent(fingerprint(t), t);
        }
        Map<String, com.preflight.exec.ColumnValueCatalogService.Outcome> got = new LinkedHashMap<>();
        Map<String, Object> load = catalog.load(projectId, envId, new ArrayList<>(keys.values()),
                Boolean.TRUE.equals(b.get("force")) || "1".equals(String.valueOf(b.get("force"))));
        for (Object o : (List<?>) load.getOrDefault("outcomes", List.of())) {
            var oc = (com.preflight.exec.ColumnValueCatalogService.Outcome) o;
            got.put(fingerprint(oc.target()), oc);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (String name : byParam.keySet()) {
            List<com.preflight.exec.ColumnValueCatalogService.Target> ts = byParam.get(name);
            Set<String> distinct = new LinkedHashSet<>();
            for (var t : ts) distinct.add(fingerprint(t));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("param", name);
            item.put("column", bizColumn(name, biz));
            if (distinct.size() > 1) {
                item.put("status", "ambiguous");
                item.put("note", "这个参数名在这张卡上落到 " + distinct.size() + " 个不同的列（"
                        + String.join(" / ", distinct) + "），按名字取值会把值挂到错的列上——请手工输入");
                items.add(item);
                continue;
            }
            var oc = got.get(distinct.iterator().next());
            if (oc == null) {
                item.put("status", "skipped");
                item.put("note", String.valueOf(load.get("note")));
                items.add(item);
                continue;
            }
            item.put("kind", oc.target().kind());
            item.put("where", oc.target().describe());
            item.put("status", oc.status());
            item.put("values", oc.values());
            item.put("note", oc.note());
            item.put("queriedAt", oc.queriedAt());
            item.put("fromCache", oc.fromCache());
            item.put("rowsSeen", oc.rowsSeen());
            items.add(item);
        }
        for (var e : why.entrySet()) {
            if (byParam.containsKey(e.getKey())) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("param", e.getKey());
            item.put("status", "not-queried");
            item.put("note", e.getValue());
            item.put("column", bizColumn(e.getKey(), biz));
            items.add(item);
        }
        // 列注释统一在这里挂：它是"被过滤的那一列"的属性，跟这一项查没查库无关，也不许来自字典表自己。
        // 认的是 参数→(业务表, 裸列) 这一对，不拿界面那串 "表.列" 反切——表名自己可能带属主，列名自己可能带别名。
        for (Map<String, Object> item : items) {
            String[] tc = biz.get((String) item.get("param"));
            if (tc == null) continue;
            String c = catalog.columnComment(projectId, tc[0], tc[1]);
            if (c != null) item.put("columnComment", c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.TRUE.equals(load.get("ok")));
        out.put("envId", envId);
        out.put("items", items);
        out.put("note", load.get("note"));
        out.put("queried", load.get("queried"));
        out.put("cached", load.get("cached"));
        out.put("skipped", load.get("skipped"));
        out.put("failed", load.get("failed"));
        out.put("listLimit", load.get("listLimit"));
        audit.log("db-candidates", "card#" + id + " env#" + envId,
                "只读取值 " + keys.size() + " 项（回库 " + load.get("queried") + "、命中缓存 " + load.get("cached")
                        + "、失败 " + load.get("failed") + "）");
        return out;
    }

    /** 这一位过滤的是哪一列（真实表.列，已去别名）；定不到就返回 null，界面不给假出处 */
    /** 这一项"在跟哪一列比"的展示串：业务表.裸列名。源码里的表别名（{@code c.dept_province}）不进这里——
     *  它是表的别名，不是列名的一部分，夹进去会让界面和后续查注释都指错地方。 */
    private static String bizColumn(String name, Map<String, String[]> biz) {
        String[] t = biz.get(name);
        return t == null || t[0] == null || t[1] == null ? null
                : t[0] + "." + com.preflight.exec.ColumnValueCatalogService.bareColumn(t[1]);
    }

    /** 取值目标的指纹：同一张卡上多项去重、以及把结论挂回参数名都只认这一个口径 */
    private static String fingerprint(com.preflight.exec.ColumnValueCatalogService.Target t) {
        if (t == null) return "";
        return (t.kind() + "|" + t.table() + "|" + t.column() + "|" + t.dictKey()).toLowerCase();
    }

    @PatchMapping("/db-objects/{id}")
    public Map<String, Object> updateDbObject(@PathVariable long id, @RequestBody Map<String, Object> body) {
        if (body.get("sqlTemplate") != null) {
            jdbc.update("UPDATE db_objects SET sql_template=?, human_edited=1 WHERE id=?", body.get("sqlTemplate"), id);
        }
        if (body.get("name") != null) {
            jdbc.update("UPDATE db_objects SET name=?, human_edited=1 WHERE id=?", body.get("name"), id);
        }
        if (body.get("status") != null) {
            jdbc.update("UPDATE db_objects SET status=? WHERE id=?", body.get("status"), id);
        }
        return Map.of("ok", true);
    }

    /** 同 interfaces/confirm：必须带 projectId，只影响本项目草稿（第十二轮补） */
    @PostMapping("/db-objects/confirm")
    public Map<String, Object> confirmDbObjects(@RequestBody Map<String, Object> body) {
        List<Number> ids = (List<Number>) body.get("ids");
        if (ids == null || ids.isEmpty()) return Map.of("ok", false, "error", "ids 为空");
        if (!(body.get("projectId") instanceof Number p)) return Map.of("ok", false, "error", "缺少 projectId");
        StringBuilder in = new StringBuilder();
        for (Number n : ids) in.append(in.isEmpty() ? "" : ",").append(n.longValue());
        int done = jdbc.update("UPDATE db_objects SET status='confirmed', updated_at=datetime('now','localtime') "
                + "WHERE project_id=? AND status='draft' AND id IN (" + in + ")", p.longValue());
        audit.log("learn.confirm-batch", "project#" + p.longValue(), "按勾选确认 SQL 卡草稿 " + done + " 张（请求 " + ids.size() + "）");
        return Map.of("ok", true, "confirmed", done);
    }

    private String toJson(Object o) {
        try {
            // 客户端把 JSON 当字符串传入时原样落库，避免二次编码
            if (o instanceof String s && (s.startsWith("{") || s.startsWith("["))) {
                mapper.readTree(s);
                return s;
            }
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            try {
                return mapper.writeValueAsString(o);
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
