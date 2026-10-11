package com.preflight.learn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.preflight.common.AuditService;
import com.preflight.config.AppProperties;
import com.preflight.llm.LlmGatewayService;
import com.preflight.parser.BodyFieldInferer;
import com.preflight.parser.DtoSchemaExpander;
import com.preflight.parser.ExtractedUnit;
import com.preflight.parser.JavaSourceIndex;
import com.preflight.parser.MybatisMapperParser;
import com.preflight.parser.SpringControllerParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 学习流水线：扫描目录 → 适配器抽取 → 接口卡/数据对象卡（草稿）+ 文件绑定入库。
 * LLM 负责增强命名与描述（失败自动降级为启发式命名，学习永不因 LLM 失败而中断）。
 */
@Service
public class LearnService {

    private static final Logger log = LoggerFactory.getLogger(LearnService.class);

    /**
     * 当前采集能力指纹，逐文件记进 learned_files.cap。
     * 加新采集项（第 9 轮 DTO 入参、第 14 轮 dynamic_body…）就在此追加一段并升版本号：
     * 老行的 cap 不等于它 → 增量跳过不再命中 → 该文件恰好被重扫一次。
     */
    static final String LEARN_CAPABILITY = "body-dto-v2+dyn-v3+chainbind-v1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SpringControllerParser controllerParser;
    private final MybatisMapperParser mapperParser;
    private final com.preflight.parser.LegacyWebXmlParser webXmlParser;
    private final LlmGatewayService llm;
    private final AppProperties props;
    private final AuditService audit;
    private final DispatchDictionaryService dispatch;
    private final com.preflight.ai.CommitTargetService commitTargets;
    private final com.preflight.common.ProgressRegistry progress;

    public LearnService(JdbcTemplate jdbc, ObjectMapper mapper, SpringControllerParser controllerParser,
                        MybatisMapperParser mapperParser, com.preflight.parser.LegacyWebXmlParser webXmlParser,
                        LlmGatewayService llm, AppProperties props, AuditService audit,
                        DispatchDictionaryService dispatch,
                        com.preflight.ai.CommitTargetService commitTargets,
                        com.preflight.common.ProgressRegistry progress) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.controllerParser = controllerParser;
        this.mapperParser = mapperParser;
        this.webXmlParser = webXmlParser;
        this.llm = llm;
        this.props = props;
        this.audit = audit;
        this.dispatch = dispatch;
        this.commitTargets = commitTargets;
        this.progress = progress;
    }

    public record LearnResult(String dir, int scannedFiles, int interfacesCreated, int interfacesUpdated,
                              int dbObjectsCreated, int bindings, boolean llmEnriched, List<String> warnings) {}

    private record ParsedFile(Path abs, String rel, List<ExtractedUnit> units) {}

    public LearnResult learn(long projectId, long repoId, String dir) throws Exception {
        return learn(projectId, repoId, dir, false, null);
    }

    /**
     * @param pid 前端按钮进度号（可空）：学习是全站最慢的一步，必须让人看到"扫到第几个文件、
     *            现在在写库还是在做 LLM 增强"，否则界面看起来像卡死（第 20 轮）。
     */
    public LearnResult learn(long projectId, long repoId, String dir, boolean force, String pid) throws Exception {
        Path root = Path.of(dir == null || dir.isBlank() ? props.getSamplesDir() : dir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("目录不存在: " + root);
        }
        // R3：绑定路径基准 = 仓库根（与 git/svn 变更路径同一坐标系）；学习目录是仓库根的子目录时同样正确
        String repoLocal = jdbc.queryForObject("SELECT local_path FROM repos WHERE id=?", String.class, repoId);
        Path repoRoot = Path.of(repoLocal).toAbsolutePath().normalize();

        List<ParsedFile> parsed = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        // 第 25 轮：采集能力升级（第 9 轮 DTO 入参、第 14 轮 dynamic_body…）后，源码没改过的文件也会被重扫一次，
        // 判据是 learned_files.cap 与当前指纹不一致——不再靠"强制全量"这种整仓重跑，也不再有"只覆盖第一个仓库"的漏洞
        int staleFiles = jdbc.queryForObject("SELECT COUNT(*) FROM learned_files WHERE repo_id=? AND (cap IS NULL OR cap<>?)",
                Integer.class, repoId, LEARN_CAPABILITY);
        if (staleFiles > 0 && !force) {
            warnings.add("本仓库有 " + staleFiles + " 个文件是旧版采集能力学出来的，本次会重扫一遍补齐（动态 SQL 原文、入参结构；" +
                    "人工编辑过的卡片不会被覆盖）；入参结构若有变化会让历史用例转为「待复核」，到用例里点【修复】即可按新骨架补齐参数并保留人工填过的值");
        }
        Map<String, String> relativePaths = new java.util.HashMap<>(); // 文件名 → 仓库相对路径（兼容旧字段）
        int scanned = 0;
        List<Path> allFiles;
        try (Stream<Path> walk = Files.walk(root)) {
            allFiles = walk.filter(Files::isRegularFile).toList();
        }
        // 第 9 轮：整仓 Java 源码索引（DTO 展开 + 自由字段取值反推 + 调用链，共用一次解析）
        JavaSourceIndex srcIndex = null;
        DtoSchemaExpander expander = null;
        BodyFieldInferer fieldInferer = null;
        List<Path> javaFiles = allFiles.stream()
                .filter(p -> p.getFileName().toString().endsWith(".java")).toList();
        if (!javaFiles.isEmpty()) {
            progress.begin(pid, "学习：Java 源码索引", 0);
            progress.step(pid, javaFiles.size() + " 个 Java 文件");
            try {
                long t0 = System.currentTimeMillis();
                srcIndex = new JavaSourceIndex(javaFiles);
                expander = new DtoSchemaExpander(srcIndex);
                fieldInferer = new BodyFieldInferer(srcIndex);
                audit.log("learn.index", root.toString(),
                        "Java 源码索引 " + javaFiles.size() + " 文件 / " + (System.currentTimeMillis() - t0) + "ms");
            } catch (Throwable t) {
                srcIndex = null;
                expander = null;
                fieldInferer = null;
                warnings.add("Java 源码索引建立失败，本次入参结构退回旧版（仅形参名）：" + t.getMessage());
                log.warn("java source index failed", t);
            }
        }
        // N22：跨文件 <include refid="ns.sqlId"> 需要全库片段索引——先轻量收集所有 mapper <sql> 片段
        Map<String, String> globalFrags = new LinkedHashMap<>();
        for (Path p : allFiles) {
            String n = p.getFileName().toString().toLowerCase();
            if (!n.endsWith(".xml") || n.equals("web.xml")) continue;
            try {
                mapperParser.collectFragments(p).forEach(globalFrags::putIfAbsent);
            } catch (Exception ignore) {
            }
        }
        progress.begin(pid, "学习：扫描文件", allFiles.size());
        for (Path p : allFiles) {
                progress.step(pid, p.getFileName().toString());
                String name = p.getFileName().toString().toLowerCase();
                String rel = relToRepo(repoRoot, root, p);
                relativePaths.put(p.getFileName().toString(), rel);
                // R11 增量学习：大小+修改时间+采集能力三者都没变才跳过（force=true 时无视缓存）
                if (!force) {
                    try {
                        var attrs = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
                        Integer seen = jdbc.queryForObject(
                                "SELECT COUNT(*) FROM learned_files WHERE repo_id=? AND path=? AND size=? AND mtime=? AND cap=?",
                                Integer.class, repoId, rel, attrs.size(), attrs.lastModifiedTime().toMillis(), LEARN_CAPABILITY);
                        if (seen != null && seen > 0) continue;
                    } catch (Exception ignore) {
                    }
                }
                try {
                    List<ExtractedUnit> found = List.of();
                    if (name.endsWith(".java")) {
                        found = controllerParser.extract(p);
                    } else if (name.endsWith(".xml")) {
                        if (name.equals("web.xml")) {
                            found = webXmlParser.extract(p);
                        } else {
                            found = mapperParser.extract(p, globalFrags);
                        }
                    } else if (name.endsWith(".sql")) {
                        found = parseSqlFile(p);
                    }
                    if (!found.isEmpty()) {
                        parsed.add(new ParsedFile(p, rel, found));
                        scanned++;
                        jdbc.update("INSERT OR REPLACE INTO learned_files(repo_id, path, size, mtime, cap) VALUES (?,?,?,?,?)",
                                repoId, rel, Files.size(p),
                                Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class).lastModifiedTime().toMillis(),
                                LEARN_CAPABILITY);
                    }
                } catch (Exception e) {
                    warnings.add(p.getFileName() + ": " + e.getMessage());
                }
        }

        boolean llmOk = llm.isAvailable();
        // N18：评测闸口与真实调用失败分开记账——闸口跳过的不算"增强失败"
        String recallGate = recallGate();
        boolean enrichGated = recallGate != null;
        if (enrichGated) llmOk = false;
        List<String> enrichErrors = new ArrayList<>(); // 逐条错误样本（最多 3 条），定位"全部增强失败"用
        int ic = 0, iu = 0, dc = 0, bindings = 0, enrichedOk = 0, httpEndpoints = 0;
        List<Map<String, Object>> httpUnits = new ArrayList<>(); // 接口 id / 仓库相对文件 / 方法名（调用链入口）
        progress.begin(pid, "学习：入库与 LLM 增强", parsed.size());
        for (ParsedFile pf : parsed) {
            progress.step(pid, pf.rel());
            String relFile = pf.rel();
            jdbc.update("DELETE FROM file_bindings WHERE repo_id=? AND file_path=?", repoId, relFile);
            for (ExtractedUnit u : pf.units()) {
            try {
                if ("HTTP_ENDPOINT".equals(u.kind())) {
                    httpEndpoints++;
                    Map<String, Object> enriched = llmOk ? enrichHttp(u, enrichErrors) : null;
                    if (enriched != null && !enriched.isEmpty()) enrichedOk++;
                    Map<String, Object> schema = buildParamsSchema(u, expander, pf.abs(), srcIndex);
                    long id = upsertInterface(projectId, repoId, u, relFile, enriched, schema);
                    ic += id < 0 ? 0 : 1;
                    iu += id < 0 ? 1 : 0;
                    bindings += bind(projectId, repoId, relFile, u, "interface", Math.abs(id));
                    if (u.name().contains("#")) {
                        Map<String, Object> hu = new HashMap<>();
                        hu.put("id", Math.abs(id));
                        hu.put("file", pf.abs().normalize().toString());
                        hu.put("method", u.name().substring(u.name().indexOf('#') + 1));
                        hu.put("schema", schema);
                        hu.put("bodies", freeFormBodyParams(u, schema));
                        httpUnits.add(hu);
                    }
                } else {
                    long id = upsertDbObject(projectId, repoId, u, relFile);
                    dc += id < 0 ? 0 : 1;
                    bindings += bind(projectId, repoId, relFile, u, "db_object", Math.abs(id));
                }
            } catch (Exception e) {
                warnings.add(u.name() + ": " + e.getMessage());
            }
            }
        }
        audit.log("learn", root.toString(), "interfaces+" + ic + " updated" + iu + " dbObjects+" + dc);
        Map<Long, List<String>> linkedObjects = linkInterfacesToSql(projectId, repoId, root, repoRoot, httpUnits, srcIndex);
        int bodyEnriched = 0;
        if (fieldInferer != null) {
            try {
                bodyEnriched = inferBodyFields(httpUnits, fieldInferer, linkedObjects);
            } catch (Exception e) {
                warnings.add("自由结构入参取值反推失败（接口卡保留已展开的 DTO 结构）: " + e.getMessage());
            }
        }
        if (bodyEnriched > 0) {
            audit.log("learn.body", "repo#" + repoId, "自由结构入参按键名反推补齐 " + bodyEnriched + " 个接口");
        }
        // 记录学习位点（新鲜度契约：知识基线对应的本地版本/时间）
        jdbc.update("UPDATE repos SET learned_at=datetime('now','localtime'), " +
                "learned_revision=COALESCE(vcs_revision, learned_revision) WHERE id=?", repoId);
        // 知识基线变了 → 旧的"改动落在哪条语句"缓存作废，下次读取按新卡集重算
        commitTargets.invalidateRepo(repoId);
        // llmEnriched 只对事实负责：有 HTTP 端点、LLM 可用、且至少一个端点增强成功
        boolean llmEnriched = !enrichGated && llm.isAvailable() && httpEndpoints > 0 && enrichedOk > 0;
        if (enrichGated && httpEndpoints > 0) {
            warnings.add("LLM 增强被评测闸口拦截（当前召回 " + recallGate + "% < 85，属预期降级而非调用失败）；" +
                    "如需启用请在系统设置重跑【▶ 运行评测】达标，或清除该设置项");
        } else if (llm.isAvailable() && httpEndpoints > 0 && enrichedOk == 0) {
            warnings.add("LLM 供应商已配置但全部增强失败，接口名称/说明为解析器原始结果" +
                    (enrichErrors.isEmpty() ? "" : "（逐条错误样本：" + String.join(" ┃ ", enrichErrors) + "）"));
        }
        // 第 10 轮：分发式框架（一个 URL 承载全部服务码的老工程）单独建服务字典，不生成接口卡。
        // 入口仓与前端调用点仓往往不是同一个仓，所以两边任一被学习时都要重算。
        if (dispatch.shouldRebuild(projectId, root)) {
            try {
                DispatchDictionaryService.BuildResult dr = dispatch.rebuild(projectId);
                if (dr.detected()) {
                    warnings.add("分发框架「" + dr.framework() + "」服务字典已重建：" + dr.targets() + " 个服务码（"
                            + dr.withParams() + " 个带入参，其中 " + dr.fromChain() + " 个由调用链补齐），"
                            + "在测试台选中分发入口卡后按服务码取用");
                }
            } catch (Exception e) {
                warnings.add("服务字典重建失败（接口卡不受影响）: " + e.getMessage());
            }
        }
        return new LearnResult(root.toString(), scanned, ic, iu, dc, bindings, llmEnriched, warnings);
    }

    /** 读取评测召回率闸口：返回触发拦截的召回率字符串，未拦截返回 null。<85 时禁自动增强草稿入库（D3 评测准入）。 */
    private String recallGate() {
        String recall = setting("llm_benchmark_recall");
        if (recall == null || recall.isBlank()) return null;
        try {
            return Integer.parseInt(recall.trim()) < 85 ? recall.trim() : null;
        } catch (Exception ignore) {
            return null;
        }
    }

    private String setting(String key) {
        try {
            return jdbc.queryForObject("SELECT value FROM settings WHERE key=?", String.class, key);
        } catch (Exception ignore) {
            return null;
        }
    }

    /** LLM 增强（R9/R13）：接口中文名 + 一句话说明 + 参数中文说明。
     *  评测召回 <85% 的闸口拦截在 learn 主循环统一处理（不算调用失败）。 */
    private Map<String, Object> enrichHttp(ExtractedUnit u, List<String> enrichErrors) {
        try {
            String prompt = "代码片段：\n" + u.snippet() + "\n\n接口 " + u.httpMethod() + " " + u.path()
                    + "。只输出 JSON：{\"name\":\"简短中文接口名\",\"desc\":\"一句话说明\",\"params\":{\"参数名\":\"中文说明\"}}";
            String text = llm.chat("extract", prompt).text();
            String json = text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1);
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.debug("LLM 增强失败（降级）: {}", e.getMessage());
            if (enrichErrors.size() < 3) {
                enrichErrors.add(u.name() + " → " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return null;
        }
    }

    private long upsertInterface(long projectId, long repoId, ExtractedUnit u, String relFile,
                                 Map<String, Object> enriched, Map<String, Object> schemaMap) {
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interfaces WHERE project_id=? AND protocol='http' AND http_method=? AND path=?",
                Integer.class, projectId, u.httpMethod() == null ? "" : u.httpMethod(), u.path() == null ? "" : u.path());
        String sourceJson = toJson(List.of(Map.of("file", relFile, "lines", u.lineStart() + "-" + u.lineEnd())));
        String schemaJson = toJson(schemaMap);
        String name = u.name();
        double confidence = 0.6;
        if (enriched != null) {
            confidence = 0.9;
            if (enriched.get("name") != null) name = enriched.get("name").toString();
            // R9：参数中文说明并入 schema.description（在哈希计算之后，保证重学哈希稳定）
            Object paramDesc = enriched.get("params");
            if (paramDesc instanceof Map<?, ?> pd && schemaMap.get("properties") instanceof Map<?, ?> props) {
                for (Map.Entry<?, ?> en : pd.entrySet()) {
                    if (props.get(en.getKey().toString()) instanceof Map<?, ?> ps) {
                        @SuppressWarnings("unchecked") Map<String, Object> ps2 = (Map<String, Object>) ps;
                        ps2.put("description", String.valueOf(en.getValue()));
                    }
                }
                schemaJson = toJson(schemaMap);
            }
        }
        String newHash = sha256(schemaJson == null ? "" : schemaJson);
        if (existing != null && existing > 0) {
            long id = jdbc.queryForObject(
                    "SELECT id FROM interfaces WHERE project_id=? AND protocol='http' AND http_method=? AND path=?",
                    Long.class, projectId, u.httpMethod(), u.path());
            Map<String, Object> old = jdbc.queryForMap("SELECT schema_hash, version FROM interfaces WHERE id=?", id);
            String oldHash = (String) old.get("schema_hash");
            boolean schemaChanged = oldHash != null && !oldHash.isBlank() && !oldHash.equals(newHash);
            jdbc.update("UPDATE interfaces SET source=?, params_schema=CASE WHEN human_edited=1 THEN params_schema ELSE ? END, " +
                            "name=CASE WHEN human_edited=1 THEN name ELSE ? END, confidence=?, schema_hash=?, version=version+1, " +
                            "updated_at=datetime('now','localtime') WHERE id=?",
                    sourceJson, schemaJson, name, confidence, newHash, id);
            if (schemaChanged) {
                markDependentCasesSuspect(id, oldHash, newHash);
            }
            return -id; // 负数表示更新
        }
        jdbc.update("INSERT INTO interfaces(project_id, repo_id, protocol, name, http_method, path, params_schema, source, confidence, schema_hash) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?)",
                projectId, repoId, "http", name, u.httpMethod(), u.path(), schemaJson, sourceJson, confidence, newHash);
        return jdbc.queryForObject("SELECT id FROM interfaces WHERE project_id=? AND protocol='http' AND http_method=? AND path=?",
                Long.class, projectId, u.httpMethod(), u.path());
    }

    /** 接口 schema 变更 → 关联用例标记 suspect（基线缺失的老用例同样标记），并附结构化提示 */
    private void markDependentCasesSuspect(long interfaceId, String oldHash, String newHash) {
        jdbc.update("UPDATE test_cases SET status='suspect' WHERE target_type='interface' AND target_id=? " +
                        "AND (base_schema_hash IS NULL OR base_schema_hash != ?) AND status != 'deprecated'",
                interfaceId, newHash);
        jdbc.update("UPDATE test_cases SET scenario = scenario || '（schema 变更，请复核参数）' " +
                        "WHERE target_type='interface' AND target_id=? AND status='suspect' AND scenario NOT LIKE '%schema 变更%'",
                interfaceId);
    }

    public static String sha256(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d).substring(0, 16);
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    private long upsertDbObject(long projectId, long repoId, ExtractedUnit u, String relFile) {
        String objType = switch (u.kind()) {
            case "SQL_STATEMENT" -> sqlKind(u);
            case "DDL" -> "table";
            case "PROC_CALL" -> "procedure";
            default -> "other";
        };
        // MyBatis databaseId 变体：同一段逻辑 SQL 的多库版本，名称加 [db:xx] 后缀区分
        String storedName = u.databaseId() == null || u.databaseId().isBlank()
                ? u.name() : u.name() + "[db:" + u.databaseId() + "]";
        // 自然键 = (project_id, name)：obj_type 是派生分类，随方言/解析改进被更新而非另插一行
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM db_objects WHERE project_id=? AND name=?",
                Integer.class, projectId, storedName);
        String sigJson = toJson(u.params().stream().map(p -> Map.of("name", (Object) p.name(), "type", p.type())).toList());
        String tablesJson = u.tables().isEmpty() ? null : toJson(u.tables());
        String exec = u.executableSql() == null ? null : u.executableSql();
        if (existing != null && existing > 0) {
            long id = jdbc.queryForObject("SELECT id FROM db_objects WHERE project_id=? AND name=?",
                    Long.class, projectId, storedName);
            jdbc.update("UPDATE db_objects SET obj_type=?, signature=?, sql_template=CASE WHEN human_edited=1 THEN sql_template ELSE ? END, " +
                            "source_sql=?, tables_involved=?, database_id=?, dynamic_body=?, updated_at=datetime('now','localtime') WHERE id=?",
                    objType, sigJson, exec, u.snippet(), tablesJson, u.databaseId(), u.dynamicBody(), id);
            return -id;
        }
        jdbc.update("INSERT INTO db_objects(project_id, name, obj_type, signature, sql_template, source_sql, tables_involved, source, confidence, database_id, dynamic_body) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                projectId, storedName, objType, sigJson, exec, u.snippet(), tablesJson,
                toJson(List.of(Map.of("file", relFile))), 0.8, u.databaseId(), u.dynamicBody());
        return jdbc.queryForObject("SELECT id FROM db_objects WHERE project_id=? AND name=?",
                Long.class, projectId, storedName);
    }

    private String sqlKind(ExtractedUnit u) {
        // 优先用 JSqlParser 的语句类型（避免注释前缀/{call/WITH 等误判为 other）
        if (u.executableSql() != null) {
            com.preflight.parser.SqlAnalyzer.SqlInfo info = com.preflight.parser.SqlAnalyzer.analyze(
                    u.executableSql().replace("{call ", "CALL "));
            String st = info.statementType() == null ? "" : info.statementType();
            if (st.contains("Select")) return "sql_select";
            if (st.contains("Insert")) return "sql_insert";
            if (st.contains("Update")) return "sql_update";
            if (st.contains("Delete")) return "sql_delete";
            if (st.contains("Call")) return "sql_call";
        }
        String s = u.snippet() == null ? "" : u.snippet().toUpperCase();
        if (s.startsWith("SELECT")) return "sql_select";
        if (s.startsWith("INSERT")) return "sql_insert";
        if (s.startsWith("UPDATE")) return "sql_update";
        if (s.startsWith("DELETE")) return "sql_delete";
        return "sql_other";
    }

    private int bind(long projectId, long repoId, String file, ExtractedUnit u, String kind, long unitId) {
        // OR IGNORE：同文件同区间抽出同一单元时唯一键(uq_bindings_key)会挡下，返回 0 而不是抛异常
        return jdbc.update("INSERT OR IGNORE INTO file_bindings(project_id, repo_id, file_path, line_start, line_end, unit_kind, unit_id) VALUES (?,?,?,?,?,?,?)",
                projectId, repoId, file, u.lineStart(), u.lineEnd(), kind, unitId);
    }

    /** 入参 schema：query/path/header 平铺；body 按 DTO 递归展开成嵌套结构（第 9 轮） */
    private Map<String, Object> buildParamsSchema(ExtractedUnit u, DtoSchemaExpander expander,
                                                  Path absFile, JavaSourceIndex index) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ExtractedUnit.ParamDef p : u.params()) {
            Map<String, Object> ps = new LinkedHashMap<>();
            ps.put("in", p.in());
            if (p.defaultValue() != null) ps.put("default", p.defaultValue());
            if ("body".equals(p.in()) && expander != null) {
                expandBody(ps, p, expander, index == null || absFile == null ? List.of() : index.importsOf(absFile));
            } else {
                ps.put("type", jsonType(p.type()));
            }
            props.put(p.name(), ps);
            if (p.required()) required.add(p.name());
        }
        schema.put("properties", props);
        schema.put("required", required);
        return schema;
    }

    /** body 形参展开：整体是 Map/JSONObject → 标记自由结构；是 DTO → 递归展开字段 */
    @SuppressWarnings("unchecked")
    private void expandBody(Map<String, Object> ps, ExtractedUnit.ParamDef p, DtoSchemaExpander expander,
                            List<String> importHints) {
        String type = p.type();
        if (type == null || type.isBlank()) {
            ps.put("type", "object");
            return;
        }
        ps.put("javaType", type);
        if (expander.looksFreeForm(type)) {
            ps.put("type", "object");
            ps.put("freeForm", true);
            return;
        }
        DtoSchemaExpander.BodySchema bs = expander.expand(type, importHints);
        ps.put("type", "object");
        if (!bs.properties().isEmpty()) ps.put("properties", bs.properties());
        if (!bs.freeFormFields().isEmpty()) ps.put("freeFormFields", bs.freeFormFields());
        // 类型自己叫 DTO、父类却是 JSONObject/Map：根上照样能挂任意键，声明字段只是其中几个的形状。
        // 不给这个标记，根层就不进取值反推的种子清单，这张卡的业务参数会一条都学不出来。
        if (expander.inheritsFreeForm(type, importHints)) {
            ps.put("freeForm", true);
            ps.put("freeFormNote", "这个类继承自自由结构（Map/JSONObject 一类）：声明里的字段只是根上的几个键，"
                    + "其余业务键由取值代码反推");
        }
    }

    /** 收集需要按键名反推的自由结构位置：顶层形参（field=""）与 DTO 内的 JSONObject/Map 字段 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> freeFormBodyParams(ExtractedUnit u, Map<String, Object> schema) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(schema.get("properties") instanceof Map<?, ?> props)) return out;
        for (ExtractedUnit.ParamDef p : u.params()) {
            if (!"body".equals(p.in()) || !(props.get(p.name()) instanceof Map<?, ?> raw)) continue;
            Map<String, Object> ps = (Map<String, Object>) raw;
            List<String> fields = new ArrayList<>();
            if (Boolean.TRUE.equals(ps.get("freeForm"))) fields.add("");
            if (ps.get("freeFormFields") instanceof List<?> ff) {
                for (Object o : ff) {
                    String f = String.valueOf(o);
                    if (f.startsWith("*[]")) continue;      // 集合元素类型，键名无法定位到字段
                    fields.add(f);
                }
            }
            if (fields.isEmpty()) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("param", p.name());
            entry.put("fields", fields);
            out.add(entry);
        }
        return out;
    }

    /**
     * 自由结构负载按键名补齐（第 9 轮）：DTO 展开只能给出 "req_comm_data 是个 JSONObject"，
     * 真正的键名要从取值代码反推（getString/get/JSON 转 Map 后 get/关联 SQL 的 #{} 占位符）。
     * 纯增强：只补属性、不改结构，故不标 suspect，也不覆盖人工编辑过的卡片。
     */
    @SuppressWarnings("unchecked")
    private int inferBodyFields(List<Map<String, Object>> httpUnits, BodyFieldInferer inferer,
                                Map<Long, List<String>> linkedObjects) {
        int touched = 0;
        for (Map<String, Object> hu : httpUnits) {
            if (!(hu.get("bodies") instanceof List<?> bodies) || bodies.isEmpty()) continue;
            if (!(hu.get("schema") instanceof Map<?, ?> rawSchema)) continue;
            Map<String, Object> schema = (Map<String, Object>) rawSchema;
            if (!(schema.get("properties") instanceof Map<?, ?> rawProps)) continue;
            Map<String, Object> props = (Map<String, Object>) rawProps;
            long id = ((Number) hu.get("id")).longValue();
            List<String> sqls = linkedSqlTexts(linkedObjects.getOrDefault(id, List.of()));
            boolean changed = false;
            for (Object b : bodies) {
                if (!(b instanceof Map<?, ?> bm)) continue;
                String param = String.valueOf(bm.get("param"));
                Object fieldVal = props.get(param);
                if (!(fieldVal instanceof Map<?, ?> rawParam)) continue;
                Map<String, Object> ps = (Map<String, Object>) rawParam;
                for (Object f : (List<?>) bm.get("fields")) {
                    String field = String.valueOf(f);
                    Map<String, Object> holder = ps;
                    if (!field.isEmpty()) {
                        if (!(ps.get("properties") instanceof Map<?, ?> rp)) continue;
                        Object fv = ((Map<String, Object>) rp).get(field);
                        if (!(fv instanceof Map<?, ?> rawField)) continue;
                        holder = (Map<String, Object>) rawField;
                    }
                    List<BodyFieldInferer.InferredKey> keys = inferer.infer(
                            Path.of(String.valueOf(hu.get("file"))), String.valueOf(hu.get("method")),
                            BodyFieldInferer.accessors(param, field), sqls);
                    if (keys.isEmpty()) continue;
                    for (BodyFieldInferer.InferredKey k : keys) {
                        placeInferred(holder, k);
                    }
                    changed = true;
                }
            }
            if (!changed) continue;
            String schemaJson = toJson(schema);
            String newHash = sha256(schemaJson);
            int updated = jdbc.update("UPDATE interfaces SET params_schema=?, schema_hash=?, " +
                            "version=version+1, updated_at=datetime('now','localtime') " +
                            "WHERE id=? AND human_edited=0", schemaJson, newHash, id);
            touched += updated;
        }
        return touched;
    }

    /**
     * 一条反推出来的键写进 schema：键路径有几层就嵌几层
     * （{@code REQUESTS → items → REQ_COMM_DATA → USER_ID}），中间缺的节点按类型补齐。
     * 浅路径的记录自己就是那一层的证据（{@code BodyFieldInferer} 按路径深度排好序返回），
     * 所以父层的类型不是由深处的键替它猜的；已经是 DTO 静态展开出来的真字段只往下走，不改它的类型与说明。
     */
    @SuppressWarnings("unchecked")
    private void placeInferred(Map<String, Object> holder, BodyFieldInferer.InferredKey k) {
        List<String> path = (k.path() == null || k.path().isEmpty()) ? List.of(k.key()) : k.path();
        Map<String, Object> node = holder;
        for (int i = 0; i < path.size(); i++) {
            String seg = path.get(i);
            boolean last = i == path.size() - 1;
            Map<String, Object> props = mutableProps(node);
            Map<String, Object> child = props.get(seg) instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : null;
            if (child == null) {
                child = new LinkedHashMap<>();
                child.put("type", last ? k.jsonType() : "object");
                child.put("inferred", true);
                props.put(seg, child);
            }
            if (last && Boolean.TRUE.equals(child.get("inferred"))) {
                child.put("type", k.jsonType());
            }
            if (Boolean.TRUE.equals(child.get("inferred"))) {
                child.putIfAbsent("confidence", k.confidence());
                child.putIfAbsent("via", k.via());
            }
            if (!last) node = descend(child);
        }
    }

    /** 数组的下一层在 items 里，对象的下一层就是它自己 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> descend(Map<String, Object> child) {
        if (!"array".equals(child.get("type"))) return child;
        if (!(child.get("items") instanceof Map<?, ?>)) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("type", "object");
            it.put("inferred", true);
            child.put("items", it);
        }
        return (Map<String, Object>) child.get("items");
    }

    private Map<String, Object> mutableProps(Map<String, Object> node) {
        if (node.get("properties") instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) node.get("properties");
            return p;
        }
        Map<String, Object> p = new LinkedHashMap<>();
        node.put("properties", p);
        return p;
    }

    /** 按关联的数据对象名取其可执行 SQL 原文（键名反推的最低档证据来源） */
    private List<String> linkedSqlTexts(List<String> objNames) {
        if (objNames == null || objNames.isEmpty()) return List.of();
        List<String> names = objNames.size() > 40 ? objNames.subList(0, 40) : objNames;
        String placeholders = String.join(",", names.stream().map(n -> "?").toList());
        return jdbc.queryForList(
                        "SELECT sql_template FROM db_objects WHERE name IN (" + placeholders + ")",
                        String.class, names.toArray()).stream()
                .filter(s -> s != null && !s.isBlank()).toList();
    }

    private String jsonType(String javaType) {
        if (javaType == null) return "string";
        return switch (javaType.toLowerCase()) {
            case "int", "integer", "long", "short", "byte" -> "integer";
            case "double", "float", "bigdecimal" -> "number";
            case "boolean" -> "boolean";
            default -> "string";
        };
    }

    /** 解析 .sql 文件中的建表与存储过程定义（兼容 CREATE OR REPLACE，达梦/Oracle 风格脚本） */
    private List<ExtractedUnit> parseSqlFile(Path p) throws Exception {
        List<ExtractedUnit> units = new ArrayList<>();
        String content = readTextSafe(p);
        java.util.regex.Pattern createPattern =
                java.util.regex.Pattern.compile("(?is)CREATE\\s+(OR\\s+REPLACE\\s+)?(TABLE|PROCEDURE|PROC|FUNCTION)\\s+([^\\s(]+)");
        java.util.regex.Matcher m = createPattern.matcher(content);
        while (m.find()) {
            String kind = m.group(2).equalsIgnoreCase("TABLE") ? "DDL" : "PROC_CALL";
            String name = m.group(3).replaceAll("[`\"\\[\\]]", "");
            int start = m.start();
            int semi = content.indexOf(';', start);
            String stmt = content.substring(start, semi > start ? Math.min(semi, start + 8000) : Math.min(start + 8000, content.length())).trim();
            units.add(new ExtractedUnit(kind, name, null, null, List.of(), List.of(),
                    p.getFileName().toString(), 0, 0,
                    List.of(), stmt.length() > 200 ? stmt.substring(0, 200) + "..." : stmt,
                    kind.equals("DDL") ? null : stmt, null));
        }
        return units;
    }

    /** R3：文件相对路径以仓库根为基准（与 git/svn 变更路径同坐标系）；学习目录在仓库外时退回学习根相对 */
    private String relToRepo(Path repoRoot, Path learnRoot, Path file) {
        try {
            return repoRoot.relativize(file).toString().replace('\\', '/');
        } catch (Exception e) {
            return learnRoot.relativize(file).toString().replace('\\', '/');
        }
    }

    /**
     * 接口↔SQL 关联与读写属性标注（04-补齐 §3.2）+ 调用链中间层文件记账（第 44 轮）：
     * 主路 = 调用链追踪（Controller→Service→Mapper 语句 id）；兜底 = 路径/方法名 token 匹配。
     * 关联后：任一写语句→write，全读→read，无关联→unknown。人工可后续修正。
     *
     * <p>同一次 BFS 顺路把沿途 Service / DAO 文件写进 {@code chain_files}：这些文件自己不产卡片，
     * 改动落在它们上时若不记这一笔，归因只能报"没命中任何已学习目标"（真实项目一次只改服务层的提交实测 0 目标）。
     */
    private Map<Long, List<String>> linkInterfacesToSql(long projectId, long repoId, Path learnRoot, Path repoRoot,
                                                        List<Map<String, Object>> httpUnits, JavaSourceIndex index) {
        List<Map<String, Object>> objs = jdbc.queryForList(
                "SELECT id, name, obj_type FROM db_objects WHERE id IN " +
                        "(SELECT unit_id FROM file_bindings WHERE repo_id=? AND unit_kind='db_object')", repoId);
        Map<String, Map<String, Object>> byIdPart = new HashMap<>();
        for (Map<String, Object> obj : objs) {
            String name = String.valueOf(obj.get("name"));
            String idPart = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
            if (idPart.length() >= 3) byIdPart.putIfAbsent(idPart.toLowerCase(), obj);
        }

        // 1) 调用链追踪
        Map<Long, List<String>> linkedByInterface = new HashMap<>();
        int chainFiles = 0;
        if (!httpUnits.isEmpty() && !byIdPart.isEmpty()) {
            try {
                CallChainLinker linker = index == null
                        ? new CallChainLinker(CallChainLinker.collectJavaFiles(learnRoot), byIdPart.keySet())
                        : new CallChainLinker(index, byIdPart.keySet());
                for (Map<String, Object> hu : httpUnits) {
                    Path entry = Path.of(String.valueOf(hu.get("file")));
                    CallChainLinker.Link link = linker.link(entry, String.valueOf(hu.get("method")).toLowerCase());
                    chainFiles += bindChainFiles(projectId, repoId, learnRoot, repoRoot,
                            ((Number) hu.get("id")).longValue(), link.chainFiles());
                    if (!link.statementNames().isEmpty()) {
                        List<String> names = new ArrayList<>();
                        for (String m : link.statementNames()) names.add(byIdPart.get(m).get("name").toString());
                        linkedByInterface.put(((Number) hu.get("id")).longValue(), names);
                    }
                }
            } catch (Exception e) {
                audit.log("learn.link", "repo#" + repoId, "调用链追踪失败（降级 token 匹配）: " + e.getMessage());
            }
        }
        if (chainFiles > 0) {
            audit.log("learn.chain", "repo#" + repoId, "调用链中间层文件记账 " + chainFiles + " 条（改动归因按调用链档）");
        }

        // 2) 读写属性计算 + 入库（调用链结果；无结果的保持 unknown）
        for (Map.Entry<Long, List<String>> en : linkedByInterface.entrySet()) {
            boolean hasWrite = false, hasRead = false;
            for (String name : en.getValue()) {
                Map<String, Object> obj = byIdPart.get(
                        name.substring(name.lastIndexOf('.') + 1).toLowerCase());
                String t = obj == null ? "" : String.valueOf(obj.get("obj_type"));
                if (t.startsWith("sql_insert") || t.startsWith("sql_update") || t.startsWith("sql_delete")) hasWrite = true;
                if (t.startsWith("sql_select")) hasRead = true;
            }
            String access = hasWrite ? (hasRead ? "mixed" : "write") : (hasRead ? "read" : "unknown");
            String joined = String.join(",", en.getValue());
            jdbc.update("UPDATE interfaces SET access=?, db_objects=? WHERE id=?", access, joined, en.getKey());        }
        if (!linkedByInterface.isEmpty()) {
            audit.log("learn.link", "repo#" + repoId, "调用链关联接口 " + linkedByInterface.size() + " 个");
        }
        return linkedByInterface;
    }

    private Path root(Path p) {
        return p;
    }

    /** 一个接口卡的调用链可达文件：按卡覆盖重写（重学不残留），返回记账条数 */
    private int bindChainFiles(long projectId, long repoId, Path learnRoot, Path repoRoot,
                               long interfaceId, List<Path> files) {
        jdbc.update("DELETE FROM chain_files WHERE unit_kind='interface' AND unit_id=?", interfaceId);
        int n = 0;
        for (Path f : files) {
            String rel = relToRepo(repoRoot, learnRoot, f);
            n += jdbc.update("INSERT OR IGNORE INTO chain_files(project_id, repo_id, file_path, unit_kind, unit_id)"
                    + " VALUES (?,?,?,?,?)", projectId, repoId, rel, "interface", interfaceId);
        }
        return n;
    }

    /** 编码安全读取：UTF-8 优先，失败回退 GBK（存量库脚本常见编码） */
    private String readTextSafe(Path p) throws Exception {
        byte[] bytes = Files.readAllBytes(p);
        try {
            String s = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (!s.contains("\uFFFD")) return s;
        } catch (Exception ignore) {
        }
        return new String(bytes, java.nio.charset.Charset.forName("GBK"));
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
