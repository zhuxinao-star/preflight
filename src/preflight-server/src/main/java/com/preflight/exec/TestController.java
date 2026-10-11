package com.preflight.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.preflight.ai.CaseService;
import com.preflight.ai.CommitTargetService;
import com.preflight.common.AuditService;
import com.preflight.common.CredentialUsageService;
import com.preflight.common.CryptoService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 测试执行 API：用例 CRUD 与生成、执行、环境、登录流、凭据、直测。 */
@RestController
@RequestMapping("/api")
public class TestController {

    private final JdbcTemplate jdbc;
    private final CaseService caseService;
    private final TestRunService runService;
    private final DbExecutorService dbExecutor;
    private final HttpExecutorService httpExecutor;
    private final CryptoService crypto;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final CommitTargetService commitTargets;
    private final SourceLocatorService sourceLocator;
    private final com.preflight.common.CredentialUsageService credUsage;
    private final com.preflight.learn.LoginFlowDraftService loginDraft;
    private final com.preflight.learn.FrontLoginChainService frontChain;
    private final com.preflight.learn.GatewayRoutingService routing;

    public TestController(JdbcTemplate jdbc, CaseService caseService, TestRunService runService,
                          DbExecutorService dbExecutor, HttpExecutorService httpExecutor,
                          CryptoService crypto, AuditService audit, ObjectMapper mapper,
                          CommitTargetService commitTargets, SourceLocatorService sourceLocator,
                          com.preflight.common.CredentialUsageService credUsage,
                          com.preflight.learn.LoginFlowDraftService loginDraft,
                          com.preflight.learn.FrontLoginChainService frontChain,
                          com.preflight.learn.GatewayRoutingService routing) {
        this.jdbc = jdbc;
        this.caseService = caseService;
        this.runService = runService;
        this.dbExecutor = dbExecutor;
        this.httpExecutor = httpExecutor;
        this.crypto = crypto;
        this.audit = audit;
        this.mapper = mapper;
        this.commitTargets = commitTargets;
        this.sourceLocator = sourceLocator;
        this.credUsage = credUsage;
        this.loginDraft = loginDraft;
        this.frontChain = frontChain;
        this.routing = routing;
    }

    /**
     * 「这一张卡在本次环境下要发哪个路径」的只读出口（第五十轮）。
     *
     * <p>界面顶部那行、真发时的快照、复制 cURL 全部读这一个端点／这一个服务，
     * 不在前端再拼一遍前缀——拼 URL 的规则只要有两份，看到的和发出去的就早晚分家。
     */
    @GetMapping("/interfaces/{id}/call-path")
    public Map<String, Object> callPath(@PathVariable long id,
                                        @RequestParam(required = false) Long envId,
                                        @RequestParam(required = false) Boolean repoDirect,
                                        @RequestParam(required = false) String pathMode) {
        try {
            Map<String, Object> itf = jdbc.queryForMap(
                    "SELECT id,project_id,repo_id,path,source,params_schema FROM interfaces WHERE id=?", id);
            String baseUrl = resolveBaseUrl(id, envId, Boolean.TRUE.equals(repoDirect)).url();
            com.preflight.learn.GatewayRoutingService.Answer a = routing.forCard(
                    itf.get("project_id") instanceof Number n ? n.longValue() : 0L,
                    itf.get("repo_id") instanceof Number n ? n.longValue() : null,
                    String.valueOf(itf.get("path")),
                    itf.get("source") == null ? "" : String.valueOf(itf.get("source")),
                    baseUrl, "internal".equals(pathMode),
                    com.preflight.learn.GatewayRoutingService.callPathDeclared(
                            itf.get("params_schema") == null ? "" : String.valueOf(itf.get("params_schema"))));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("base_url", baseUrl);
            out.put("state", a.state());
            out.put("state_label", a.stateLabel());
            out.put("internal", a.internal());
            out.put("path", a.path());
            out.put("changed", a.changed());
            out.put("summary", a.summary());
            out.put("note", a.note());
            out.put("basis", a.basis());
            out.put("segments", a.segments().stream().map(s -> Map.of(
                    "value", s.value(), "evidence", s.evidence())).toList());
            out.put("evidence", a.evidence());
            return out;
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    /** 最近使用的参数（来自历史执行，Apifox 式快选） */
    @GetMapping("/interfaces/{id}/recent-params")
    public Map<String, Object> recentParams(@PathVariable long id) {
        return Map.of("ok", true, "items", jdbc.queryForList("""
                SELECT DISTINCT tc.id AS case_id, tc.name, tc.params
                FROM test_runs tr JOIN test_cases tc ON tc.id = tr.case_id
                WHERE tr.target_type='interface' AND tr.target_id=?
                ORDER BY tr.id DESC LIMIT 8
                """, id));
    }

    /** 生成 cURL（不执行）。未显式给 baseUrl 时按 envId→项目第一个环境解析，保证输出可直接粘贴执行 */
    @PostMapping("/interfaces/{id}/curl")
    public Map<String, Object> curl(@PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            String baseUrl = (String) body.getOrDefault("baseUrl", "");
            Long envId = body.get("envId") == null ? null : ((Number) body.get("envId")).longValue();
            boolean repoDirect = Boolean.TRUE.equals(body.get("repoDirect"));
            String pathMode = body.get("pathMode") == null ? null : String.valueOf(body.get("pathMode"));
            if (baseUrl == null || baseUrl.isBlank()) {
                ResolvedBaseUrl r = resolveBaseUrl(id, envId, repoDirect);
                if (r.error() != null) return Map.of("ok", false, "error", r.error());
                baseUrl = r.url();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) body.getOrDefault("params", Map.of());
            return Map.of("ok", true, "curl",
                    httpExecutor.buildCurl(id, baseUrl == null ? "" : baseUrl, params, pathMode));
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    /** 解析出来的地址与「为什么解析不出」——错误文案只在这一处写，cURL 与路径依据两处共用 */
    private record ResolvedBaseUrl(String url, String error) {}

    /** 本次要打的地址：仓库直连按仓库 Base URL，否则按环境（没给 envId 就取该项目第一个环境） */
    private ResolvedBaseUrl resolveBaseUrl(long interfaceId, Long envId, boolean repoDirect) {
        if (repoDirect) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT r.base_url, r.name FROM interfaces i JOIN repos r ON r.id=i.repo_id WHERE i.id=?",
                    interfaceId);
            if (rows.isEmpty()) {
                return new ResolvedBaseUrl("", "该接口没有仓库归属（手工建卡或未学习），无法按仓库地址生成 cURL，请改用项目环境");
            }
            Map<String, Object> row = rows.get(0);
            String rb = row.get("base_url") == null ? "" : String.valueOf(row.get("base_url")).trim();
            if (rb.isEmpty()) {
                return new ResolvedBaseUrl("", "所属仓库「" + row.get("name") + "」未配置服务地址（Base URL），请到 [项目与仓库] 填写");
            }
            return new ResolvedBaseUrl(rb, null);
        }
        Long eId = envId;
        if (eId == null) {
            List<Long> ids = jdbc.queryForList(
                    "SELECT e.id FROM environments e JOIN interfaces i ON i.project_id=e.project_id WHERE i.id=? ORDER BY e.id LIMIT 1",
                    Long.class, interfaceId);
            eId = ids.isEmpty() ? null : ids.get(0);
        }
        if (eId == null) return new ResolvedBaseUrl("", "");
        return new ResolvedBaseUrl(String.valueOf(
                jdbc.queryForObject("SELECT base_url FROM environments WHERE id=?", String.class, eId)), null);
    }

    // ---------- 用例 ----------
    @GetMapping("/cases")
    public Map<String, Object> cases(@RequestParam(required = false) String targetType,
                                     @RequestParam(required = false) Long targetId) {
        if (targetType != null && targetId != null) {
            return Map.of("ok", true, "cases", jdbc.queryForList(
                    "SELECT * FROM test_cases WHERE target_type=? AND target_id=? ORDER BY id", targetType, targetId));
        }
        if (targetType != null) {
            return Map.of("ok", true, "cases", jdbc.queryForList(
                    "SELECT * FROM test_cases WHERE target_type=? ORDER BY id", targetType));
        }
        if (targetId != null) {
            return Map.of("ok", true, "cases", jdbc.queryForList(
                    "SELECT * FROM test_cases WHERE target_id=? ORDER BY id", targetId));
        }
        return Map.of("ok", true, "cases", jdbc.queryForList("SELECT * FROM test_cases ORDER BY id"));
    }

    /**
     * 用例目录（第 15 轮）：知识库「🧪 用例」段的数据源。
     * 与 /cases 的分工——/cases 是测试台按单个目标取用（一条 SQL 不动）；这里是跨目标的项目级清单：
     * 带目标名 / 项目名 / 最近执行结果，支持筛选 + 服务端分页。此前 SQL 卡生成的用例在全界面无处可看，就是缺这一段。
     */
    @GetMapping("/cases/catalog")
    public Map<String, Object> caseCatalog(@RequestParam(required = false) Long projectId,
                                           @RequestParam(required = false) String targetType,
                                           @RequestParam(required = false) String origin,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) Long commitId,
                                           @RequestParam(required = false) Long repoId,
                                           @RequestParam(required = false) Boolean noRepo,
                                           @RequestParam(required = false) Long targetId,
                                           @RequestParam(required = false) String runState,
                                           @RequestParam(required = false) String group,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (projectId != null) {
            and(where, "AND COALESCE(i.project_id, d.project_id)=?");
            args.add(projectId);
        }
        if (isSet(targetType)) {
            and(where, "AND tc.target_type=?");
            args.add(targetType);
        }
        if (isSet(origin)) {
            and(where, "AND tc.origin=?");
            args.add(origin);
        }
        if (isSet(status)) {
            and(where, "AND tc.status=?");
            args.add(status);
        }
        if (repoId != null) {
            // 用例本身不存仓库，仓库来自目标的归属：接口卡有 repo_id 列，SQL 卡经 file_bindings 反查（V12 已建索引）
            and(where, """
                    AND ((tc.target_type='interface' AND i.repo_id=?)
                      OR EXISTS (SELECT 1 FROM file_bindings fb
                                 WHERE fb.unit_kind=tc.target_type AND fb.unit_id=tc.target_id AND fb.repo_id=?))""");
            args.add(repoId);
            args.add(repoId);
        }
        if (Boolean.TRUE.equals(noRepo)) {
            // 「未归属仓库」= 目标既没有 repo_id（接口卡）也没被任何 file_bindings 绑到仓库（SQL 卡）
            and(where, """
                    AND NOT EXISTS (SELECT 1 FROM file_bindings fb
                                    WHERE fb.unit_kind=tc.target_type AND fb.unit_id=tc.target_id)
                      AND (tc.target_type<>'interface' OR i.repo_id IS NULL)""");
        }
        if (targetId != null) {
            // 按目标下钻（用例目录的「按目标」视图点一行就只看这张卡的用例）；接口与 SQL 的 id 会重号，所以必须带上目标类型
            if (isSet(targetType)) {
                and(where, "AND tc.target_id=?");
                args.add(targetId);
            } else {
                and(where, "AND (tc.target_type='interface' AND tc.target_id=? OR tc.target_type='db_object' AND tc.target_id=?)");
                args.add(targetId);
                args.add(targetId);
            }
        }
        if (isSet(runState)) {
            switch (runState.trim()) {
                case "pass" -> and(where, "AND (SELECT tr.status FROM test_runs tr WHERE tr.case_id=tc.id"
                        + " ORDER BY tr.id DESC LIMIT 1)='pass'");
                case "fail" -> and(where, "AND (SELECT tr.status FROM test_runs tr WHERE tr.case_id=tc.id"
                        + " ORDER BY tr.id DESC LIMIT 1)='fail'");
                case "run" -> and(where, "AND EXISTS (SELECT 1 FROM test_runs tr WHERE tr.case_id=tc.id)");
                case "never" -> and(where, "AND NOT EXISTS (SELECT 1 FROM test_runs tr WHERE tr.case_id=tc.id)");
                default -> { }
            }
        }
        if (isSet(q)) {
            String esc = com.preflight.common.SqlLikes.ESCAPE_CLAUSE;
            and(where, "AND (lower(tc.name) LIKE ?" + esc + " OR lower(tc.scenario) LIKE ?" + esc
                    + " OR lower(COALESCE(i.name, d.name)) LIKE ?" + esc + ")");
            String needle = com.preflight.common.SqlLikes.contains(q.toLowerCase());
            args.add(needle);
            args.add(needle);
            args.add(needle);
        }
        Map<String, Object> commitScope = Map.of();
        if (commitId != null) {
            // 「本提交的用例」= 本次改动归因到的目标所挂的用例。第 17 轮起走 commit_targets（改动行 → 具体语句），
            // 与「本提交用例」统计、影响分析、一键回归同一张表，不再各自现算一遍文件级等值匹配
            commitScope = commitScope(commitId);
            and(where, """
                    AND EXISTS (SELECT 1 FROM commit_targets ct
                                WHERE ct.commit_id=? AND ct.unit_kind=tc.target_type AND ct.unit_id=tc.target_id)""");
            args.add(commitId);
        }
        String from = """
            FROM test_cases tc
            LEFT JOIN interfaces i ON tc.target_type='interface' AND i.id=tc.target_id
            LEFT JOIN db_objects d ON tc.target_type='db_object' AND d.id=tc.target_id
            LEFT JOIN projects p ON p.id=COALESCE(i.project_id, d.project_id)
            """;
        Integer total = jdbc.queryForObject("SELECT COUNT(*) " + from + where, Integer.class, args.toArray());
        int sz = Math.min(Math.max(size, 1), 200);
        // 同一目标常挂几十条用例（真实项目：125 条全在一张 SQL 卡上），逐个目标看比逐条看清楚得多
        if ("target".equals(group == null ? "" : group.trim())) {
            String tsql = """
                SELECT tc.target_type, tc.target_id,
                       COALESCE(i.name, d.name || ' [' || d.obj_type || ']') AS target_name,
                       COUNT(*) AS case_count,
                       SUM(CASE WHEN tc.status='approved' THEN 1 ELSE 0 END) AS approved,
                       SUM(CASE WHEN tc.status='pending' THEN 1 ELSE 0 END) AS pending,
                       SUM(CASE WHEN tc.status='suspect' THEN 1 ELSE 0 END) AS suspect,
                       SUM(CASE WHEN tc.status='deprecated' THEN 1 ELSE 0 END) AS deprecated,
                       SUM(CASE WHEN tc.enabled=0 THEN 1 ELSE 0 END) AS disabled,
                       SUM(CASE WHEN (SELECT tr.status FROM test_runs tr WHERE tr.case_id=tc.id ORDER BY tr.id DESC LIMIT 1)='pass' THEN 1 ELSE 0 END) AS run_pass,
                       SUM(CASE WHEN (SELECT tr.status FROM test_runs tr WHERE tr.case_id=tc.id ORDER BY tr.id DESC LIMIT 1)='fail' THEN 1 ELSE 0 END) AS run_fail,
                       SUM(CASE WHEN NOT EXISTS (SELECT 1 FROM test_runs tr WHERE tr.case_id=tc.id) THEN 1 ELSE 0 END) AS never_run,
                       (SELECT COUNT(*) FROM test_runs tr WHERE tr.case_id IS NULL
                          AND tr.target_type=tc.target_type AND tr.target_id=tc.target_id) AS direct_count,
                       (SELECT MAX(tr.executed_at) FROM test_runs tr WHERE tr.case_id IS NULL
                          AND tr.target_type=tc.target_type AND tr.target_id=tc.target_id) AS last_direct_at,
                       MAX(tc.updated_at) AS updated_at
                """ + from + where + " GROUP BY tc.target_type, tc.target_id"
                    + " ORDER BY case_count DESC, tc.target_type, tc.target_id LIMIT ? OFFSET ?";
            List<Object> targs = new java.util.ArrayList<>(args);
            targs.add(sz);
            targs.add(com.preflight.common.Paging.offset(Math.max(page, 1), sz));
            Integer targetTotal = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM (SELECT 1 " + from + where + " GROUP BY tc.target_type, tc.target_id)",
                    Integer.class, args.toArray());
            return Map.of("ok", true,
                    "targets", jdbc.queryForList(tsql, targs.toArray()),
                    "origins", jdbc.queryForList("SELECT DISTINCT origin FROM test_cases ORDER BY origin", String.class),
                    "total", targetTotal == null ? 0 : targetTotal,
                    "caseTotal", total == null ? 0 : total,
                    "page", Math.max(page, 1), "size", sz,
                    "summary", catalogSummary(from, where, args),
                    "scope", commitScope);
        }
        String sql = """
            SELECT tc.id, tc.target_type, tc.target_id, tc.name, tc.scenario, tc.origin, tc.status,
                   tc.enabled, tc.created_at,
                   COALESCE(i.name, d.name || ' [' || d.obj_type || ']') AS target_name,
                   p.name AS project_name,
                   (SELECT tr.status FROM test_runs tr WHERE tr.case_id=tc.id ORDER BY tr.id DESC LIMIT 1) AS last_run_status,
                   (SELECT tr.executed_at FROM test_runs tr WHERE tr.case_id=tc.id ORDER BY tr.id DESC LIMIT 1) AS last_run_at,
                   (SELECT COUNT(*) FROM test_runs tr WHERE tr.case_id=tc.id) AS run_count,
                   (SELECT COUNT(*) FROM test_runs tr WHERE tr.case_id IS NULL
                      AND tr.target_type=tc.target_type AND tr.target_id=tc.target_id) AS direct_count,
                   (SELECT MAX(tr.executed_at) FROM test_runs tr WHERE tr.case_id IS NULL
                      AND tr.target_type=tc.target_type AND tr.target_id=tc.target_id) AS last_direct_at
            """ + from + where + " ORDER BY tc.id DESC LIMIT ? OFFSET ?";
        List<Object> listArgs = new java.util.ArrayList<>(args);
        listArgs.add(sz);
        listArgs.add(com.preflight.common.Paging.offset(Math.max(page, 1), sz));
        return Map.of("ok", true,
                "cases", jdbc.queryForList(sql, listArgs.toArray()),
                "origins", jdbc.queryForList("SELECT DISTINCT origin FROM test_cases ORDER BY origin", String.class),
                "total", total == null ? 0 : total, "page", Math.max(page, 1), "size", sz,
                "summary", catalogSummary(from, where, args),
                "scope", commitScope);
    }

    /**
     * 这条提交的归因形状，给界面写"为什么是这些用例"：
     * basis=line 表示改动行落在具体语句上（并列出语句名），file 表示只能归因到整文件，
     * chain 表示改的是这张卡调用链上的服务层 / DAO 文件（第 44 轮，按代码可达、可能偏宽）。
     */
    private Map<String, Object> commitScope(long commitId) {
        commitTargets.ensure(List.of(commitId));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT basis, anchor, unit_kind, unit_id FROM commit_targets WHERE commit_id=?", commitId);
        int stmt = 0;
        int files = 0;
        int chain = 0;
        List<String> anchors = new java.util.ArrayList<>();
        for (Map<String, Object> r : rows) {
            if ("line".equals(r.get("basis"))) {
                stmt++;
                String a = (String) r.get("anchor");
                if (a != null && anchors.size() < 8 && !anchors.contains(a)) anchors.add(a);
            } else if ("chain".equals(r.get("basis"))) {
                chain++;
            } else if (!"none".equals(r.get("basis"))) {
                files++;
            }
        }
        return Map.of("basis", rows.isEmpty() || "none".equals(rows.get(0).get("basis")) ? "none"
                        : (stmt > 0 ? "line" : (files > 0 ? "file" : "chain")),
                "stmtTargets", stmt, "fileTargets", files, "chainTargets", chain, "anchors", anchors,
                "targetTotal", (int) rows.stream().filter(r -> !"none".equals(r.get("basis"))).count());
    }

    /** 目录顶部的规模数字：让「共 N 条、多少个目标、多少没跑过」一眼看清，而不是只给一个总数 */
    private Map<String, Object> catalogSummary(String from, CharSequence where, List<Object> args) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT COUNT(*) case_total, COUNT(DISTINCT tc.target_type || ':' || tc.target_id) target_total, "
                        + "SUM(CASE WHEN tc.status='approved' THEN 1 ELSE 0 END) approved, "
                        + "SUM(CASE WHEN tc.status='pending' THEN 1 ELSE 0 END) pending, "
                        + "SUM(CASE WHEN tc.status='suspect' THEN 1 ELSE 0 END) suspect, "
                        + "SUM(CASE WHEN tc.status='deprecated' THEN 1 ELSE 0 END) deprecated, "
                        + "SUM(CASE WHEN NOT EXISTS (SELECT 1 FROM test_runs tr WHERE tr.case_id=tc.id) THEN 1 ELSE 0 END) never_run, "
                        // 「本条没跑过，但它所属目标被直测过」——第 20 轮：这类用例最容易被人误以为没测
                        + "SUM(CASE WHEN NOT EXISTS (SELECT 1 FROM test_runs tr WHERE tr.case_id=tc.id) AND EXISTS "
                        + "(SELECT 1 FROM test_runs tx WHERE tx.case_id IS NULL AND tx.target_type=tc.target_type AND tx.target_id=tc.target_id) "
                        + "THEN 1 ELSE 0 END) direct_covered "
                        + from + where, args.toArray());
        Map<String, Object> s = rows.isEmpty() ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(rows.get(0));
        s.putIfAbsent("case_total", 0);
        s.putIfAbsent("target_total", 0);
        return s;
    }

    /**
     * 往动态 WHERE 上挂一条子句：分隔空格由这里负责，调用方怎么写都不会粘连。
     * （Java 文本块会剥掉各行共同的前导空格，" AND …" 写成多行就变成 "1=1AND …" 的语法错误，
     * 而紧跟在 ? 后面时又能碰巧跑通——这种"看运气"的拼接必须堵在入口。）
     */
    private static void and(StringBuilder where, String clause) {
        where.append(' ').append(clause.strip());
    }

    private boolean isSet(String s) {
        return s != null && !s.isBlank();
    }

    @PostMapping("/cases/generate")
    public Map<String, Object> generate(@RequestBody Map<String, Object> body) {
        String targetType = (String) body.get("targetType");
        long targetId = ((Number) body.get("targetId")).longValue();
        boolean useLlm = !Boolean.FALSE.equals(body.get("useLlm"));
        CaseService.GenResult r = caseService.generate(targetType, targetId, useLlm);
        return Map.of("ok", true, "created", r.created(), "llmUsed", r.llmUsed(), "notes", r.notes());
    }

    /** 把测试台当前入参存成具名用例（分发调用靠它让用例名带上服务码） */
    @PostMapping("/cases/manual")
    public Map<String, Object> manualCase(@RequestBody Map<String, Object> body) {
        String targetType = String.valueOf(body.getOrDefault("targetType", "interface"));
        Object tid = body.get("targetId");
        String name = body.get("name") == null ? null : String.valueOf(body.get("name")).trim();
        if (!(tid instanceof Number num) || name == null || name.isEmpty()) {
            return Map.of("ok", false, "error", "缺少 targetId 或用例名");
        }
        long targetId = num.longValue();
        Integer dup = jdbc.queryForObject(
                "SELECT COUNT(*) FROM test_cases WHERE target_type=? AND target_id=? AND name=?",
                Integer.class, targetType, targetId, name);
        if (dup != null && dup > 0) return Map.of("ok", false, "error", "同名用例已存在：" + name);
        jdbc.update("INSERT INTO test_cases(target_type, target_id, name, scenario, params, expect, origin, status) VALUES (?,?,?,?,?,?,?,?)",
                targetType, targetId, name,
                body.get("scenario") == null ? "" : String.valueOf(body.get("scenario")),
                toJson(body.getOrDefault("params", Map.of())), "",
                body.get("origin") == null ? "manual" : String.valueOf(body.get("origin")), "approved");
        Long id = jdbc.queryForObject(
                "SELECT id FROM test_cases WHERE target_type=? AND target_id=? AND name=? ORDER BY id DESC LIMIT 1",
                Long.class, targetType, targetId, name);
        audit.log("case.manual", targetType + "#" + targetId, name);
        return Map.of("ok", true, "id", id == null ? 0L : id, "name", name);
    }

    @PatchMapping("/cases/{id}")
    public Map<String, Object> updateCase(@PathVariable long id, @RequestBody Map<String, Object> body) {
        if (body.get("params") != null) {
            jdbc.update("UPDATE test_cases SET params=? WHERE id=?", toJson(body.get("params")), id);
        }
        if (body.get("name") != null) jdbc.update("UPDATE test_cases SET name=? WHERE id=?", body.get("name"), id);
        if (body.get("expect") != null) jdbc.update("UPDATE test_cases SET expect=? WHERE id=?", body.get("expect"), id);
        if (body.get("assertions") != null) {
            jdbc.update("UPDATE test_cases SET assertions=? WHERE id=?", toJson(body.get("assertions")), id);
        }
        if (body.get("ignoreFields") != null) {
            jdbc.update("UPDATE test_cases SET ignore_fields=? WHERE id=?", toJson(body.get("ignoreFields")), id);
        }
        if (body.get("enabled") != null) jdbc.update("UPDATE test_cases SET enabled=? WHERE id=?",
                Boolean.TRUE.equals(body.get("enabled")) ? 1 : 0, id);
        return Map.of("ok", true);
    }

    /** 设置/清除基线（基线对比 diff 的锚点） */
    @PostMapping("/cases/{id}/set-baseline")
    public Map<String, Object> setBaseline(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Long runId = body.get("runId") == null ? null : ((Number) body.get("runId")).longValue();
        jdbc.update("UPDATE test_cases SET baseline_run_id=? WHERE id=?", runId, id);
        return Map.of("ok", true, "baselineRunId", runId);
    }

    /** 用例复核修复（健康度 suspect → approved） */
    @PostMapping("/cases/{id}/repair")
    public Map<String, Object> repairCase(@PathVariable long id) {
        CaseService.GenResult r = caseService.repair(id);
        return Map.of("ok", true, "created", r.created(), "notes", r.notes());
    }

    /** 用例确认状态推进：pending/suspect → approved，或置 deprecated */
    @PostMapping("/cases/{id}/status")
    public Map<String, Object> caseStatus(@PathVariable long id, @RequestBody Map<String, String> body) {
        String status = body.get("status");
        if (!List.of("pending", "approved", "suspect", "deprecated").contains(status)) {
            return Map.of("ok", false, "error", "非法状态");
        }
        jdbc.update("UPDATE test_cases SET status=? WHERE id=?", status, id);
        return Map.of("ok", true);
    }

    /** 接口钩子配置（pre_sql / post_sql，JSON 数组） */
    @PatchMapping("/interfaces/{id}/hooks")
    public Map<String, Object> setHooks(@PathVariable long id, @RequestBody Map<String, Object> body) {
        if (body.get("preSql") != null) {
            jdbc.update("UPDATE interfaces SET pre_sql=? WHERE id=?", toJson(body.get("preSql")), id);
        }
        if (body.get("postSql") != null) {
            jdbc.update("UPDATE interfaces SET post_sql=? WHERE id=?", toJson(body.get("postSql")), id);
        }
        return Map.of("ok", true);
    }

    @DeleteMapping("/cases/{id}")
    public Map<String, Object> deleteCase(@PathVariable long id) {
        jdbc.update("DELETE FROM test_cases WHERE id=?", id);
        return Map.of("ok", true);
    }

    // ---------- 执行 ----------
    @PostMapping("/test-runs")
    public Map<String, Object> run(@RequestBody Map<String, Object> body) {
        Long caseId = body.get("caseId") instanceof Number n ? n.longValue() : null;
        Long envId = body.get("envId") == null ? null : ((Number) body.get("envId")).longValue();
        Long commitId = body.get("commitId") == null ? null : ((Number) body.get("commitId")).longValue();
        @SuppressWarnings("unchecked")
        Map<String, Object> overrides = (Map<String, Object>) body.get("overrides");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> adHoc = body.get("assertions") instanceof List<?> l
                ? (List<Map<String, Object>>) l : null;
        boolean repoDirect = Boolean.TRUE.equals(body.get("repoDirect"));
        Long loginFlowId = body.get("loginFlowId") instanceof Number n ? n.longValue() : null;
        // 第五十轮：本次「按内部路径发」的手动开关，只活这一条请求，不落库也不改卡片
        String pathMode = body.get("pathMode") == null ? null : String.valueOf(body.get("pathMode"));
        TestRunService.RunResult r;
        if (caseId == null) {
            // 第 20 轮：测试台上没挂用例的执行——不再挑一条"承载用例"把账记到别人头上，
            // 而是按目标级直测留痕（case_id 为空，只作标记不算已测）。
            String targetType = (String) body.get("targetType");
            Long targetId = body.get("targetId") instanceof Number n ? n.longValue() : null;
            if (targetType == null || targetId == null) {
                return Map.of("ok", false, "error", "缺少 caseId 时必须带 targetType 与 targetId（按目标直测）");
            }
            r = runService.runDirect(targetType, targetId, envId, commitId, overrides, adHoc, repoDirect,
                    loginFlowId, pathMode);
        } else {
            r = runService.runCase(caseId, envId, commitId, overrides, adHoc, repoDirect, loginFlowId, pathMode);
        }
        audit.log("test.run", caseId == null ? body.get("targetType") + "#" + body.get("targetId") : targetTypeOf(caseId),
                (repoDirect ? "[仓库直连] " : "") + (r.ok() ? "执行完成: " + r.run().get("status") : "失败: " + r.error()));
        return r.ok() ? Map.of("ok", true, "run", r.run()) : Map.of("ok", false, "error", r.error());
    }

    /**
     * 第 20 轮：执行记录列表（此前全站没有任何地方能查 test_runs，跑完东西就"消失"了）。
     * 服务端分页 + 过滤；列表刻意不返回请求/响应快照（单条可达数百 KB），点开详情再取。
     */
    @GetMapping("/test-runs")
    public Map<String, Object> runs(@RequestParam(required = false) Long projectId,
                                    @RequestParam(required = false) Long caseId,
                                    @RequestParam(required = false) String targetType,
                                    @RequestParam(required = false) Long targetId,
                                    @RequestParam(required = false) Long commitId,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String via,
                                    @RequestParam(required = false) Long envId,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        List<Object> args = new ArrayList<>();
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        if (caseId != null) { w.append(" AND tr.case_id=?"); args.add(caseId); }
        if (targetType != null && !targetType.isBlank()) { w.append(" AND tr.target_type=?"); args.add(targetType); }
        if (targetId != null) { w.append(" AND tr.target_id=?"); args.add(targetId); }
        if (commitId != null) { w.append(" AND tr.commit_id=?"); args.add(commitId); }
        if (envId != null) { w.append(" AND tr.env_id=?"); args.add(envId); }
        if (status != null && !status.isBlank()) { w.append(" AND tr.status=?"); args.add(status); }
        if ("direct".equals(via)) { w.append(" AND tr.case_id IS NULL"); }
        else if ("case".equals(via)) { w.append(" AND tr.case_id IS NOT NULL"); }
        if (projectId != null) {
            // 一次执行归属哪个项目：先看目标卡的归属，目标查不到时才按执行环境归属。
            // 手敲语句的直测（target_type='adhoc_sql'，target_id=0）从来就没有卡可查，删掉的卡同理——
            // 只按 i/d 过滤会让这些记录在任何项目下都看不见（④ 结果区点【全部直测记录】却一条都没有）。
            // COALESCE 的次序保证有卡的项目归属优先，不会被环境串到别的项目去。
            w.append(" AND COALESCE(i.project_id, d.project_id, e.project_id)=?");
            args.add(projectId);
        }
        String from = """
            FROM test_runs tr
            LEFT JOIN test_cases tc ON tc.id=tr.case_id
            LEFT JOIN interfaces i ON tr.target_type='interface' AND i.id=tr.target_id
            LEFT JOIN db_objects d ON tr.target_type='db_object' AND d.id=tr.target_id
            LEFT JOIN environments e ON e.id=tr.env_id
            """ + w;
        int sz = Math.min(Math.max(size, 1), 200);
        int p = Math.max(page, 1);
        List<Object> largs = new ArrayList<>(args);
        largs.add(sz);
        // 偏移走 Paging：int 乘会溢出成负数，SQLite 把负 OFFSET 当 0，越界页就会返回第 1 页
        largs.add(com.preflight.common.Paging.offset(p, sz));
        Integer total = jdbc.queryForObject("SELECT COUNT(*) " + from, Integer.class, args.toArray());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT tr.id, tr.case_id, tr.target_type, tr.target_id, tr.mode, tr.env_id, tr.commit_id,
                       tr.status, tr.latency_ms, tr.executed_by, tr.executed_at,
                       tc.name AS case_name,
                       COALESCE(i.name, d.name, CASE WHEN tr.target_id=0 THEN '（未选 SQL 卡的手敲语句）'
                             ELSE '#' || tr.target_id END) AS target_name,
                       COALESCE(e.name, '') AS env_name,
                       LENGTH(tr.request_snapshot) AS req_len, LENGTH(tr.response_snapshot) AS resp_len,
                       CASE WHEN json_valid(tr.response_snapshot) THEN json_extract(tr.response_snapshot,'$.error') END AS error_brief,
                       CASE WHEN json_valid(tr.response_snapshot) THEN json_extract(tr.response_snapshot,'$.bizErrorCode') END AS biz_error_code,
                       CASE WHEN json_valid(tr.response_snapshot) THEN json_array_length(json_extract(tr.response_snapshot,'$.bizErrors')) END AS biz_error_count
                """ + from + " ORDER BY tr.id DESC LIMIT ? OFFSET ?", largs.toArray());
        return Map.of("ok", true, "runs", rows, "total", total == null ? 0 : total, "page", p, "size", sz);
    }

    /** 单条执行记录详情（含请求/响应快照、断言明细、基线差异） */
    @GetMapping("/test-runs/{id}")
    public Map<String, Object> runDetail(@PathVariable long id) {
        try {
            Map<String, Object> row = new java.util.LinkedHashMap<>(
                    jdbc.queryForMap("SELECT * FROM test_runs WHERE id=?", id));
            return Map.of("ok", true, "run", row);
        } catch (Exception e) {
            return Map.of("ok", false, "error", "执行记录不存在 #" + id);
        }
    }

    private String targetTypeOf(long caseId) {
        try {
            return jdbc.queryForObject("SELECT target_type FROM test_cases WHERE id=?", String.class, caseId);
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ---------- 环境 ----------
    @GetMapping("/environments")
    public Map<String, Object> envs(@RequestParam(required = false) Long projectId) {
        List<Map<String, Object>> rows = projectId == null
                ? jdbc.queryForList("SELECT id, project_id, name, base_url, db_url, db_user, db_type, allow_ddl_commit, vars FROM environments ORDER BY id")
                : jdbc.queryForList("SELECT id, project_id, name, base_url, db_url, db_user, db_type, allow_ddl_commit, vars FROM environments WHERE project_id=? ORDER BY id", projectId);
        return Map.of("ok", true, "environments", rows);
    }

    @PostMapping("/environments")
    public Map<String, Object> saveEnv(@RequestBody Map<String, Object> body) {
        Long id = body.get("id") == null ? null : ((Number) body.get("id")).longValue();
        long projectId = ((Number) body.get("projectId")).longValue();
        String name = (String) body.get("name");
        String baseUrl = (String) body.get("baseUrl");
        String dbUrl = (String) body.get("dbUrl");
        String dbUser = (String) body.get("dbUser");
        String dbPass = (String) body.get("dbPassword");
        boolean varsSent = body.get("vars") != null;
        String vars = varsSent ? toJson(body.get("vars")) : null;
        String dbType = (String) body.getOrDefault("dbType", "");
        boolean allowDdl = Boolean.TRUE.equals(body.get("allowDdlCommit"));
        if (id == null) {
            jdbc.update("INSERT INTO environments(project_id, name, base_url, db_url, db_user, db_password_enc, vars, db_type, allow_ddl_commit) VALUES (?,?,?,?,?,?,?,?,?)",
                    projectId, name, baseUrl, dbUrl, dbUser, crypto.encrypt(dbPass), vars, dbType, allowDdl ? 1 : 0);
            return Map.of("ok", true);
        }
        List<Map<String, Object>> cur = jdbc.queryForList("SELECT project_id, vars FROM environments WHERE id=?", id);
        if (cur.isEmpty()) return Map.of("ok", false, "error", "环境不存在（可能已被别人删掉了），请刷新列表重试");
        long storedProject = ((Number) cur.get(0).get("project_id")).longValue();
        // 第 18 轮自查：环境一旦归属某项目，编辑时就不许挪走——项目下的仓库、卡片与用例统计全会跟着错位
        if (storedProject != projectId) {
            return Map.of("ok", false, "error", "不能改环境所属的项目：请到目标项目下新建环境，再删掉这条（改归属会让原项目的仓库/卡片/用例统计全部错位）");
        }
        // vars 按键合并：只覆盖这次传来的键（值留空=删掉该键），没传的键原样保留。
        // 早先是整列重建，界面上没回填的 Cookie 一保存就被静默清空。
        String mergedVars = mergeEnvVars((String) cur.get(0).get("vars"), vars);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("UPDATE environments SET name=?, base_url=?, db_url=?, db_user=?");
        args.add(name); args.add(baseUrl); args.add(dbUrl); args.add(dbUser);
        if (dbPass != null && !dbPass.isBlank()) { sql.append(", db_password_enc=?"); args.add(crypto.encrypt(dbPass)); }
        if (varsSent) { sql.append(", vars=?"); args.add(mergedVars); }
        sql.append(", db_type=?, allow_ddl_commit=? WHERE id=?");
        args.add(dbType); args.add(allowDdl ? 1 : 0); args.add(id);
        jdbc.update(sql.toString(), args.toArray());
        return Map.of("ok", true);
    }

    /** 环境变量按键合并；请求没带 vars 时压根不碰这一列（老客户端/知识包导入都不会误清空）。 */
    private String mergeEnvVars(String storedRaw, String patchJson) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (storedRaw != null && !storedRaw.isBlank()) {
            try { merged.putAll(mapper.readValue(storedRaw, Map.class)); } catch (Exception ignore) { }
        }
        try {
            Map<String, Object> patch = mapper.readValue(patchJson, Map.class);
            patch.forEach((k, v) -> {
                if (v == null || String.valueOf(v).isBlank()) merged.remove(k);
                else merged.put(k, v);
            });
        } catch (Exception ignore) {
            return patchJson;   // 传进来的不是对象：按老行为原样存，不猜测
        }
        return merged.isEmpty() ? null : toJson(merged);
    }

    @DeleteMapping("/environments/{id}")
    public Map<String, Object> deleteEnv(@PathVariable long id) {
        jdbc.update("DELETE FROM environments WHERE id=?", id);
        return Map.of("ok", true);
    }

    @PostMapping("/environments/{id}/check")
    public Map<String, Object> checkEnv(@PathVariable long id) {
        return dbExecutor.check(id);
    }

    // ---------- SQL 直测 ----------
    @PostMapping("/db-test")
    public Map<String, Object> dbTest(@RequestBody Map<String, Object> body) {
        long envId = ((Number) body.get("envId")).longValue();
        String sql = (String) body.get("sql");
        List<Object> values = body.get("values") == null ? List.of() : (List<Object>) body.get("values");
        boolean allowCommit = Boolean.TRUE.equals(body.get("allowCommit"));
        // RBAC：DB 直测"提交"通道仅管理员（04-补齐 §3.6）
        if (allowCommit && !com.preflight.common.UserContext.isAdmin()) {
            return Map.of("ok", false, "error", "提交模式需要管理员权限");
        }
        boolean isProcedure = Boolean.TRUE.equals(body.get("isProcedure"));
        // viewer 只读通道（Q-E）：仅允许单条 SELECT，禁提交、禁存储过程
        if ("viewer".equals(com.preflight.common.UserContext.role())) {
            if (isProcedure) {
                return Map.of("ok", false, "error", "只读账号不能执行存储过程");
            }
            try {
                String one = sql == null ? "" : sql.trim();
                while (one.endsWith(";")) one = one.substring(0, one.length() - 1).trim();
                if (one.contains(";")) {
                    return Map.of("ok", false, "error", "只读账号仅可执行单条 SELECT 查询（检测到多语句）");
                }
                var st = net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(one);
                if (!(st instanceof net.sf.jsqlparser.statement.select.Select)) {
                    return Map.of("ok", false, "error", "只读账号仅可执行 SELECT 查询");
                }
            } catch (Exception e) {
                return Map.of("ok", false, "error", "只读账号仅可执行单条 SELECT 查询（解析失败）");
            }
        }
        if (allowCommit) {
            audit.log("db-test.commit", "env#" + envId, "显式提交模式: " + sql);
        }
        DbExecutorService.DbResult r = dbExecutor.runSql(envId, sql, values, allowCommit, isProcedure,
                outParamsOf(body.get("outParams")));
        audit.log("db-test", "env#" + envId, r.ok() ? "ok, rows=" + (r.rows() == null ? 0 : r.rows().size()) : r.error());
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", r.ok());
        out.put("error", r.error());
        out.put("diag", diagOf(r.diag()));
        out.put("notice", r.notice());
        out.put("columns", r.columns() == null ? List.of() : r.columns());
        out.put("rows", r.rows() == null ? List.of() : r.rows());
        out.put("updateCount", r.updateCount());
        out.put("committed", r.committed());
        out.put("latencyMs", r.latencyMs());
        out.put("outCursors", cursorSets(r, false));
        // ---------- 第 26 轮：库执行成功 ≠ 过程成功 ----------
        // 过程把异常吞进 WHEN OTHERS 后自己回错误码，驱动只会说"执行成功"。这里按可配白名单认错误码列，
        // status 仍算通过（通过率口径不动），但界面上必须标橙，并顺手做一次只读定位（不重跑、不写目标库）。
        java.util.Set<String> bizCols = BizErrorScanner.whitelistFrom(jdbc);
        List<BizErrorScanner.Hit> biz = r.ok() ? BizErrorScanner.scanRun(r, bizCols) : List.of();
        out.put("bizErrors", BizErrorScanner.toJson(biz));
        out.put("bizWhitelist", new ArrayList<>(bizCols));
        Map<String, Object> locate = null;
        if (!biz.isEmpty()) {
            List<String> texts = new ArrayList<>();
            for (var h : biz) if (h.message() != null) texts.add(h.message());
            texts.addAll(r.diag().chain());
            try {
                locate = sourceLocator.locate(envId, sql, texts, r.diag().frames());
            } catch (Exception e) {
                locate = Map.of("ok", false, "note", "定位这一步自己出错了：" + e);
            }
            audit.log("db-locate", "env#" + envId,
                    "业务错误码 " + biz.get(0).value() + " → 定位候选 "
                            + (locate.get("candidates") instanceof List<?> l ? l.size() : 0) + " 行（只读，未重跑）");
        }
        out.put("locate", locate);
        // 第 20 轮：直测必须留痕。原先只写审计，用例目录/提交统计/覆盖率全看不到，用户验完仍是"未跑过"。
        // 带上 objId=知道测的是哪张 SQL 卡；带上 caseId=从用例点进来的，算该用例已测；都没有就是纯直测标记。
        Long objId = body.get("objId") instanceof Number n ? n.longValue() : null;
        Long runCaseId = body.get("caseId") instanceof Number n ? n.longValue() : null;
        Long runCommitId = body.get("commitId") instanceof Number n ? n.longValue() : null;
        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        snap.put("direct", true);
        snap.put("sql", sql);
        snap.put("values", values);
        snap.put("columns", r.columns() == null ? List.of() : r.columns());
        snap.put("rowCount", r.rows() == null ? 0 : r.rows().size());
        snap.put("rows", r.rows() == null ? List.of() : r.rows().stream().limit(20).toList());
        snap.put("updateCount", r.updateCount());
        snap.put("committed", r.committed());
        snap.put("isProcedure", isProcedure);
        snap.put("outCursors", cursorSets(r, true));
        // 留痕里存"当时用的白名单 + 判定结果 + 定位证据"：记录是历史事实，事后改白名单不该让旧记录变脸
        snap.put("bizErrors", BizErrorScanner.toJson(biz));
        snap.put("bizErrorCode", biz.isEmpty() ? null : biz.get(0).value());
        snap.put("bizWhitelist", new ArrayList<>(bizCols));
        if (locate != null) snap.put("locate", locate);
        // 留痕要留住整条报错链：只存那一句主错，事后回看又回到"只知道存储过程报错了"。
        // 成功时必须是 null 而不是字符串 "null"——Java 里 null + "" 会拼出 "null"，
        // 结果执行记录列表每条通过的直测下面都漂着一行红色 null（看着像报错，其实是没有报错）。
        String errMsg = r.error();
        if (errMsg != null) {
            errMsg = errMsg + (r.diag() == null || r.diag().chain().size() < 2
                    ? "" : "\n—— 驱动回传的完整报错链 ——\n" + String.join("\n", r.diag().chain()));
        }
        snap.put("error", errMsg);
        long runId = runService.recordDirect(objId == null ? "adhoc_sql" : "db_object",
                objId == null ? 0 : objId, runCaseId, envId, runCommitId, sql, snap, r.ok(), r.latencyMs());
        out.put("runId", runId);
        return out;
    }


    /** 报错诊断 → JSON：完整消息链 + 解析出的库内栈帧，一条都不编，驱动没给就是空数组。 */    private static Map<String, Object> diagOf(DbExecutorService.Diag d) {
        List<Map<String, Object>> frames = new ArrayList<>();
        if (d != null) for (DbExecutorService.Frame f : d.frames()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", f.path());
            m.put("line", f.line());
            m.put("raw", f.raw());
            frames.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chain", d == null ? List.of() : d.chain());
        out.put("frames", frames);
        return out;
    }

    /** 结果区【🔍 取这一行的源码】：只读查 ALL_SOURCE，用户点了才发这一条查询。 */
    @GetMapping("/db-proc-source")
    public Map<String, Object> dbProcSource(@RequestParam long envId, @RequestParam String path, @RequestParam int line) {
        return dbExecutor.sourceLines(envId, path, line);
    }

    /** 前端只在 SQL 与 ③ 面板拼出的语句一致时才带 outParams；类型认不认由于执行层判定，这里只松散解析。 */
    private static List<DbExecutorService.OutParam> outParamsOf(Object raw) {
        List<DbExecutorService.OutParam> out = new java.util.ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Object slot = m.get("slot");
            if (!(slot instanceof Number n)) continue;
            out.add(new DbExecutorService.OutParam(n.intValue(),
                    m.get("name") == null ? null : String.valueOf(m.get("name")),
                    m.get("type") == null ? null : String.valueOf(m.get("type"))));
        }
        return out;
    }

    /** 游标出参 → 可序列化结构。快照只留前 20 行（与主结果集同一口径），响应给全量（本场上限 500 行）。 */
    private static List<Map<String, Object>> cursorSets(DbExecutorService.DbResult r, boolean forSnapshot) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (var c : r.outCursors()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("name", c.name());
            m.put("slot", c.slot());
            m.put("columns", c.columns());
            m.put("rowCount", c.rows().size());
            m.put("rows", forSnapshot ? c.rows().stream().limit(20).toList() : c.rows());
            out.add(m);
        }
        return out;
    }

    // ---------- 登录流 ----------
    @GetMapping("/login-flows")
    public Map<String, Object> flows(@RequestParam(required = false) Long projectId) {
        List<Map<String, Object>> rows = projectId == null
                ? jdbc.queryForList("SELECT * FROM login_flows ORDER BY id")
                : jdbc.queryForList("SELECT * FROM login_flows WHERE project_id=? ORDER BY id", projectId);
        return Map.of("ok", true, "loginFlows", rows);
    }

    /** 一次最多摆这么多张，超了要如实说（第卅三轮口径：基数保护 + 点名截断） */
    private static final int LOGIN_HINT_CAP = 40;

    /** 前端调用点多摆的步数上限：注解那条通路已经按 cap 收过口，这一条不许把整屏刷爆 */
    private static final int FRONT_SITE_CAP = 20;

    /**
     * 第四十七轮：登录线索。建登录流那一屏原来摆的是前端写死的假模板
     * （POST /api/login + tester/123456 + $.data.accessToken），看着像学习成果，其实四个值全是编的。
     * 改成点名**真学到的**登录语义接口卡（带 文件:行号），外加一句「这个项目为什么没有自动生成的登录流」。
     *
     * <p>第四十八轮再补一半：每张卡带一份<b>可推导草稿</b>（role / chain / step / 留空点名），
     * 顶层再按「哪条链」铺成整条流。判据全在 {@link com.preflight.learn.LoginFlowDraftService}，
     * 与第 46 轮那个"该发什么值"的单点共用同一份展开；学不到的格照空串摆并把原因点名，不编示例值。
     *
     * <p>第四十九轮接第二条证据源：同一个项目登记的<b>前端仓</b>。注解学到的卡缺两样东西——
     * 对外地址那一层前缀（网关）、以及注解压根扫不到的那一步（登录入口在网关过滤器里）。
     * 这两样都写在前端的调用点上，所以这里现扫一遍盘（只读、不碰网络），把改写与多出来的步子并进同一份草稿，
     * 每一条都带"依据：学到的接口卡 / 前端调用点 文件:行号"。
     */
    @GetMapping("/login-flows/hints")
    public Map<String, Object> flowHints(@RequestParam long projectId) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM projects WHERE id=?", Integer.class, projectId);
        if (exists == null || exists == 0) {
            return Map.of("ok", false, "error", "项目 #" + projectId + " 不存在：请从项目列表里重新打开这一屏");
        }
        List<Map<String, Object>> cards = new ArrayList<>();
        List<Long> hitIds = new ArrayList<>();
        int matched = 0;
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT id, http_method, path, name, source, repo_id FROM interfaces WHERE project_id=? ORDER BY id",
                projectId)) {
            String hit = com.preflight.parser.LoginSemantics.matchedWord(
                    String.valueOf(r.getOrDefault("path", "")), String.valueOf(r.getOrDefault("name", "")));
            if (hit == null) continue;
            matched++;
            if (cards.size() < LOGIN_HINT_CAP) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", r.get("id"));
                row.put("method", r.get("http_method"));
                row.put("path", r.get("path"));
                row.put("name", r.get("name"));
                row.put("matched", hit);
                row.put("evidence", fileEvidence(r.get("source")));
                cards.add(row);
                hitIds.add(((Number) r.get("id")).longValue());
            }
        }
        // schema 只按命中的那几十张取：整项目的 params_schema 一起捞会把这一屏拖慢
        Map<Long, String> schemas = new LinkedHashMap<>();
        if (!hitIds.isEmpty()) {
            String ph = String.join(",", hitIds.stream().map(x -> "?").toList());
            for (Map<String, Object> s : jdbc.queryForList(
                    "SELECT id, params_schema FROM interfaces WHERE id IN (" + ph + ")", hitIds.toArray())) {
                schemas.put(((Number) s.get("id")).longValue(),
                        s.get("params_schema") == null ? null : String.valueOf(s.get("params_schema")));
            }
        }
        // ---- 第二条登录证据源：前端调用点（第四十九轮）------------------------------------
        // 注解扫不到的那一步（网关过滤器里的登录入口）就写在前端那一行调用里；卡片缺的那段对外前缀也是。
        // 这一趟只读盘、不碰网络，读不到就照常摆第一条通路的结论（下面 front_note 会点名为什么没有第二条）。
        List<String> frontRoots = new ArrayList<>();
        List<String> frontMissing = new ArrayList<>();
        int frontRepos = 0;
        for (Map<String, Object> rp : jdbc.queryForList(
                "SELECT name, local_path FROM repos WHERE project_id=? AND repo_type='frontend' ORDER BY id",
                projectId)) {
            frontRepos++;
            String dir = rp.get("local_path") == null ? "" : String.valueOf(rp.get("local_path"));
            if (dir.isBlank() || !java.nio.file.Files.isDirectory(java.nio.file.Path.of(dir))) {
                frontMissing.add(String.valueOf(rp.getOrDefault("name", "未命名")));
                continue;
            }
            frontRoots.add(dir);
        }
        Set<String> allPaths = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT path FROM interfaces WHERE project_id=? AND path IS NOT NULL", String.class, projectId));
        com.preflight.learn.FrontLoginChainService.Result front = null;
        String frontError = "";
        if (!frontRoots.isEmpty()) {
            try {
                front = frontChain.scan(frontRoots, allPaths);
            } catch (Exception e) {
                frontError = "这一趟前端扫描没跑完（" + e + "）：下面摆的是注解那一条通路的结论";
                front = null;
            }
        }
        List<Map<String, Object>> drafts = new ArrayList<>();
        int rewritten = 0;
        int routeRewritten = 0;
        // 地址那一段问第五十轮那个唯一出口：登录流每一步是拿环境地址直接接路径发出去的，草稿铺内部路径
        // 就等于这条流一跑就 405。没显式选环境，按 firstEnv 那一个算，并把环境名说给人听。
        com.preflight.learn.GatewayRoutingService.EnvRef hintsEnv =
                routing == null ? null : routing.firstEnv(projectId);
        String hintsBase = hintsEnv == null ? "" : hintsEnv.baseUrl();
        String hintsEnvName = hintsEnv == null ? "" : hintsEnv.name();
        for (Map<String, Object> row : cards) {
            long id = ((Number) row.get("id")).longValue();
            String path = String.valueOf(row.get("path"));
            com.preflight.learn.FrontLoginChainService.Rewrite rw =
                    front == null ? null : front.cardAsCalled.get(path);
            com.preflight.learn.GatewayRoutingService.Answer route = null;
            if (rw == null && routing != null) {
                try {
                    route = routing.forCard(projectId,
                            row.get("repo_id") instanceof Number n ? n.longValue() : null,
                            path, String.valueOf(row.getOrDefault("source", "")), hintsBase, false,
                            com.preflight.learn.GatewayRoutingService.callPathDeclared(schemas.get(id)));
                } catch (Exception e) {
                    route = null;   // 算不出就照学到的路径铺，下面 cardDraft 会按"没得问"办
                }
            }
            Map<String, Object> d = loginDraft.cardDraft(String.valueOf(row.get("method")),
                    path, schemas.get(id), rw, front, route, hintsEnvName);
            if (rw != null) rewritten++;
            else if (route != null && route.changed()) routeRewritten++;
            row.put("draft", d);
            row.put("src", rw != null ? "card+front" : "card");
            Map<String, Object> forFlow = new LinkedHashMap<>(d);
            forFlow.put("id", id);
            forFlow.put("card_name", row.get("name"));   // 铺进流的步骤名用卡片自己学到的名字，不拿地址末段现编
            drafts.add(forFlow);
        }
        int appended = 0;
        if (front != null) {
            for (com.preflight.learn.FrontLoginChainService.CallSite site : front.notLearned(allPaths)) {
                if (cards.size() >= LOGIN_HINT_CAP + FRONT_SITE_CAP) break;
                Map<String, Object> d = loginDraft.callSiteDraft(site, front);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", null);
                row.put("method", d.get("step") instanceof Map<?, ?> s ? s.get("method") : site.verb);
                row.put("path", site.absPath == null ? site.path : site.absPath);
                row.put("name", null);
                row.put("matched", site.role);
                row.put("evidence", site.evidence());
                row.put("src", "front");
                row.put("draft", d);
                cards.add(row);
                Map<String, Object> forFlow = new LinkedHashMap<>(d);
                drafts.add(forFlow);
                appended++;
            }
        }
        Map<String, Object> flowDrafts = loginDraft.flowDrafts(drafts);
        int own = jdbc.queryForObject("SELECT COUNT(*) FROM login_flows WHERE project_id=?", Integer.class, projectId);
        List<String> frameworks = jdbc.queryForList(
                "SELECT DISTINCT framework FROM dispatch_targets WHERE project_id=? AND framework IS NOT NULL",
                String.class, projectId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("cards", cards);
        out.put("matched", matched);
        // 两条证据通路的数各自报：把「卡」与「前端调用点」混成一个「张」数，报出来的就是假数
        out.put("learned_cards", cards.size() - appended);
        out.put("front_sites", appended);
        out.put("capped", matched > cards.size());
        out.put("cap", LOGIN_HINT_CAP);
        out.put("flows", own);
        out.put("frameworks", frameworks);
        out.put("keys", com.preflight.parser.LoginSemantics.ALL_WORDS);
        out.put("why", whyNoAutoFlow(own, frameworks));
        out.put("flow_drafts", flowDrafts.get("drafts"));
        // 新建弹屏默认铺哪一条、为什么是它：判据只在 LoginFlowDraftService 那一处算，界面照摆
        out.put("auto", flowDrafts.get("auto"));
        out.put("auto_why", flowDrafts.get("auto_why"));
        out.put("route_note", routeRewritten > 0
                ? "另有 " + routeRewritten + " 张卡的地址按对外路径单点改写（按环境「" + hintsEnvName
                + "」现算，与测试台真发、复制 cURL、留痕里那条是同一个答案）" : "");
        out.put("chains_without_login", flowDrafts.get("chains_without_login"));
        out.put("not_login_note", flowDrafts.get("not_login_note"));
        out.put("front_basis", front == null ? "" : front.basisNote());
        out.put("front_note", frontSourceNote(frontRepos, frontMissing, frontError, front, rewritten, appended));
        return out;
    }

    /**
     * 第二条通路这一屏自述：有没有跑、跑到几个仓、多铺出几步、补了哪几张卡的地址；
     * 没跑成要把原因点名到"该去点哪个按钮"（第卅一轮口径：0 不是不存在，要说为什么是 0）。
     */
    private static String frontSourceNote(int repos, List<String> missing, String error,
                                          com.preflight.learn.FrontLoginChainService.Result front,
                                          int rewritten, int appended) {
        if (repos == 0) {
            return "第二条证据源（前端调用点）没跑：这个项目没登记 repo_type='frontend' 的仓库。"
                    + "登录入口在网关过滤器/依赖包里时注解扫不到它，到 [项目与仓库] 把前端仓登记上再回来，"
                    + "多出来的那几步与对外地址前缀就从前端调用点读";
        }
        if (!missing.isEmpty() && front == null) {
            return "第二条证据源没跑：登记的 " + repos + " 个前端仓里有 " + missing.size()
                    + " 个在盘上找不到目录（" + String.join("、", missing) + "），先到 [项目与仓库] 同步一次";
        }
        if (front == null) return error;
        StringBuilder sb = new StringBuilder("第二条证据源（前端调用点）：按调用点补全了 ")
                .append(rewritten).append(" 张卡要发的对外地址，多铺出 ")
                .append(appended).append(" 步注解扫不到的（登录入口常在网关过滤器里，压根没有注解可扫）");
        if (rewritten == 0 && appended == 0) {
            sb.append("：这一条没读出卡片之外的新事实（前端调的地址与学到的卡完全对得上）");
        }
        if (!missing.isEmpty()) {
            sb.append("；另有 ").append(missing.size()).append(" 个前端仓目录读不到（")
                    .append(String.join("、", missing)).append("），那一部分没扫");
        }
        return sb.toString();
    }

    /** source 列形如 [{"file":"a/b.java","lines":"83-109"}]，摆成「b.java:83-109」；解析不动就原样给 */
    @SuppressWarnings("unchecked")
    private String fileEvidence(Object source) {
        if (source == null) return "";
        try {
            List<Map<String, Object>> arr = mapper.readValue(String.valueOf(source), List.class);
            List<String> bits = new ArrayList<>();
            for (Map<String, Object> e : arr) {
                Object f = e.get("file");
                if (f == null) continue;
                String name = String.valueOf(f);
                int slash = name.lastIndexOf('/');
                if (slash >= 0) name = name.substring(slash + 1);
                Object lines = e.get("lines");
                bits.add(lines == null ? name : name + ":" + lines);
            }
            return String.join(" · ", bits);
        } catch (Exception e) {
            return "";
        }
    }

    /** 自动生成登录流目前只有金证 KJDP 一条通路（DispatchDictionaryService.ensureLoginFlow），照实说 */
    private static String whyNoAutoFlow(int own, List<String> frameworks) {
        if (own > 0) return "这个项目已经有 " + own + " 条登录流，系统不会再自动加一条（第卅一轮定的口径）";
        if (frameworks.isEmpty()) return "还没学到分发框架，也没学到登录链——先对这个仓库【学习】，线索才会出现在上面";
        boolean kjdp = frameworks.stream().anyMatch(f -> f != null && f.contains("KJDP"));
        if (kjdp) return "金证 KJDP 的学习会自带一条 redis_login 自动流；现在 0 条，说明这次没学到入口，重新学习一次";
        return "【＋ 添加登录流】打开会照盘上学到的链把步骤铺好（铺不出整链时不铺、逐格点名为什么空）；"
                + "这条那一列靠上面学到的线索手工补一步（账号密码用 {{cred.username}} / {{cred.password}} 引用，别写进库）";
    }

    @PostMapping("/login-flows")
    public Map<String, Object> saveFlow(@RequestBody Map<String, Object> body) {
        Long id = body.get("id") == null ? null : ((Number) body.get("id")).longValue();
        long projectId = ((Number) body.get("projectId")).longValue();
        String name = (String) body.get("name");
        // R2 修复：前端现在直接传对象；兼容历史字符串入参（校验后归一化为对象存储，杜绝双重编码）
        Object stepsRaw = body.get("steps") == null ? List.of() : body.get("steps");
        Object applyRaw = body.get("apply") == null ? Map.of() : body.get("apply");
        try {
            if (stepsRaw instanceof String s) stepsRaw = mapper.readValue(s, List.class);
            if (applyRaw instanceof String s) applyRaw = mapper.readValue(s, Map.class);
        } catch (Exception e) {
            return Map.of("ok", false, "error", "steps/apply JSON 格式错误: " + e.getMessage());
        }
        String apply = body.get("apply") == null ? "{}" : toJson(applyRaw);
        Long credId = body.get("credId") == null ? null : ((Number) body.get("credId")).longValue();
        if (id == null) {
            jdbc.update("INSERT INTO login_flows(project_id, name, steps, apply, cred_id) VALUES (?,?,?,?,?)",
                    projectId, name, toJson(stepsRaw), apply, credId);
        } else {
            jdbc.update("UPDATE login_flows SET name=?, steps=?, apply=?, cred_id=? WHERE id=?", name, toJson(stepsRaw), apply, credId, id);
        }
        return Map.of("ok", true);
    }

    @DeleteMapping("/login-flows/{id}")
    public Map<String, Object> deleteFlow(@PathVariable long id) {
        jdbc.update("DELETE FROM login_flows WHERE id=?", id);
        return Map.of("ok", true);
    }

    /** 登录流试跑：真实执行一遍并返回提取变量（打码），失败给出原因 */
    @PostMapping("/login-flows/{id}/trial")
    public Map<String, Object> trialFlow(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Long envId = body.get("envId") == null ? null : ((Number) body.get("envId")).longValue();
        if (envId == null) return Map.of("ok", false, "error", "请选择用于试跑的环境");
        Map<String, Object> r = httpExecutor.trialLoginFlow(id, envId);
        audit.log("login-flow.trial", "flow=" + id + ", env=" + envId,
                Boolean.TRUE.equals(r.get("ok")) ? "试跑成功" : "试跑失败: " + r.get("error"));
        return r;
    }

    // ---------- 凭据保险库 ----------
    /**
     * 第三十二轮：除了名字，还要透出「这条凭据是干什么用的」。
     * 库里现在只有 SVN 拉代码账号，登录流下拉照单全收，用户以为那就是登录凭据。
     * 第三十六轮补第三种用途：YApi 文档凭据（账号密码 / 手填 token 两种），
     * 它既不是 SVN 账号也不是被测系统的登录账号，混在「业务/其它」里会被误选进登录流。
     */
    @GetMapping("/credentials")
    public Map<String, Object> creds() {
        Map<Long, String> svnRepos = credUsage.svnRepoByCredId();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> c : jdbc.queryForList("SELECT id, name, kind, created_at FROM credentials ORDER BY id")) {
            Map<String, Object> row = new LinkedHashMap<>(c);
            String repo = svnRepos.get(((Number) c.get("id")).longValue());
            String kind = String.valueOf(c.get("kind"));
            if (repo == null && CredentialUsageService.isYapiKind(kind)) {
                row.put("purpose", "yapi");
                row.put("via", CredentialUsageService.yapiVia(kind));
            } else {
                row.put("purpose", repo == null ? "other" : "svn");
            }
            row.put("svn_repo", repo);
            rows.add(row);
        }
        return Map.of("ok", true, "credentials", rows);
    }

    @PostMapping("/credentials")
    public Map<String, Object> saveCred(@RequestBody Map<String, Object> body) {
        Long id = body.get("id") == null ? null : ((Number) body.get("id")).longValue();
        String name = (String) body.get("name");
        String kind = (String) body.getOrDefault("kind", "password");
        String secret = (String) body.get("secret");
        if (id == null) {
            jdbc.update("INSERT INTO credentials(name, kind, secret_enc) VALUES (?,?,?)", name, kind, crypto.encrypt(secret));
        } else if (secret != null && !secret.isBlank()) {
            jdbc.update("UPDATE credentials SET name=?, kind=?, secret_enc=? WHERE id=?", name, kind, crypto.encrypt(secret), id);
        } else {
            jdbc.update("UPDATE credentials SET name=?, kind=? WHERE id=?", name, kind, id);
        }
        return Map.of("ok", true);
    }

    @DeleteMapping("/credentials/{id}")
    public Map<String, Object> deleteCred(@PathVariable long id) {
        jdbc.update("DELETE FROM credentials WHERE id=?", id);
        return Map.of("ok", true);
    }

    @SuppressWarnings("unchecked")
    private <T> T cast(Object o) {
        return (T) o;
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
