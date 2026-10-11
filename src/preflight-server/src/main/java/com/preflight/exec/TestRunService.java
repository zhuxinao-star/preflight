package com.preflight.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用例运行服务：按 target 类型路由 HTTP/DB 执行器；断言分层评估（L1/L2/L4）；
 * 基线对比 diff；执行记录留痕；若关联提交则自动回写测试标记。
 */
@Service
public class TestRunService {

    private final JdbcTemplate jdbc;
    private final HttpExecutorService http;
    private final DbExecutorService db;
    private final ObjectMapper mapper;
    private final com.preflight.common.AuditService audit;

    public TestRunService(JdbcTemplate jdbc, HttpExecutorService http, DbExecutorService db,
                          ObjectMapper mapper, com.preflight.common.AuditService audit) {
        this.jdbc = jdbc;
        this.http = http;
        this.db = db;
        this.mapper = mapper;
        this.audit = audit;
    }

    public record RunResult(boolean ok, String error, Map<String, Object> run) {}

    @SuppressWarnings("unchecked")
    public RunResult runCase(long caseId, Long envId, Long commitId, Map<String, Object> paramOverrides) {
        return runCase(caseId, envId, commitId, paramOverrides, null);
    }

    /** adHocAssertions：测试台临时断言（未保存进用例也即时生效），为空则用用例已存断言 */
    public RunResult runCase(long caseId, Long envId, Long commitId, Map<String, Object> paramOverrides,
                             List<Map<String, Object>> adHocAssertions) {
        return runCase(caseId, envId, commitId, paramOverrides, adHocAssertions, false);
    }

    /** repoDirect=true：仓库直连访问方式（第八轮）——打到接口所属仓库的 base_url，跳过登录流；环境与钩子照常 */
    public RunResult runCase(long caseId, Long envId, Long commitId, Map<String, Object> paramOverrides,
                             List<Map<String, Object>> adHocAssertions, boolean repoDirect) {
        return runCase(caseId, envId, commitId, paramOverrides, adHocAssertions, repoDirect, null);
    }

    /** loginFlowId：测试台显式选定的登录流（第 10 轮，一个项目可能有多条：不同系统/不同账号） */
    public RunResult runCase(long caseId, Long envId, Long commitId, Map<String, Object> paramOverrides,
                             List<Map<String, Object>> adHocAssertions, boolean repoDirect, Long loginFlowId) {
        return runCase(caseId, envId, commitId, paramOverrides, adHocAssertions, repoDirect, loginFlowId, null);
    }

    /**
     * pathMode="internal"：本次请求手动改按卡片学到的内部路径发（第五十轮，只活这一次，不落库）。
     * 默认（null）由 {@code GatewayRoutingService} 单点决定要发内部还是对外那一份。
     */
    @SuppressWarnings("unchecked")
    public RunResult runCase(long caseId, Long envId, Long commitId, Map<String, Object> paramOverrides,
                             List<Map<String, Object>> adHocAssertions, boolean repoDirect, Long loginFlowId,
                             String pathMode) {
        Map<String, Object> testCase;
        try {
            testCase = jdbc.queryForMap("SELECT * FROM test_cases WHERE id=?", caseId);
        } catch (Exception e) {
            return new RunResult(false, "用例不存在", null);
        }
        return execute(caseId, testCase, envId, commitId, paramOverrides, adHocAssertions, repoDirect, loginFlowId,
                pathMode);
    }

    /**
     * 第 20 轮：测试台上没挂用例时的直测执行。走同一个执行内核，但 case_id 记空、mode=direct——
     * 不再像以前那样随便挑一条"承载用例"把结果记到别的用例头上。
     */
    public RunResult runDirect(String targetType, long targetId, Long envId, Long commitId,
                               Map<String, Object> params, List<Map<String, Object>> assertions,
                               boolean repoDirect, Long loginFlowId) {
        return runDirect(targetType, targetId, envId, commitId, params, assertions, repoDirect, loginFlowId, null);
    }

    public RunResult runDirect(String targetType, long targetId, Long envId, Long commitId,
                               Map<String, Object> params, List<Map<String, Object>> assertions,
                               boolean repoDirect, Long loginFlowId, String pathMode) {
        Map<String, Object> virtual = new LinkedHashMap<>();
        virtual.put("target_type", targetType);
        virtual.put("target_id", targetId);
        virtual.put("name", "直测（未挂用例）");
        virtual.put("params", null);
        virtual.put("assertions", null);
        virtual.put("ignore_fields", null);
        return executeDirect(virtual, envId, commitId, params, assertions, repoDirect, loginFlowId, pathMode);
    }

    private RunResult execute(long caseId, Map<String, Object> testCase, Long envId, Long commitId,
                              Map<String, Object> paramOverrides, List<Map<String, Object>> adHocAssertions,
                              boolean repoDirect, Long loginFlowId, String pathMode) {
        return execute(caseId, testCase, envId, commitId, paramOverrides, adHocAssertions, repoDirect, loginFlowId,
                null, false, pathMode);
    }

    private RunResult executeDirect(Map<String, Object> testCase, Long envId, Long commitId,
                                    Map<String, Object> paramOverrides, List<Map<String, Object>> adHocAssertions,
                                    boolean repoDirect, Long loginFlowId, String pathMode) {
        return execute(null, testCase, envId, commitId, paramOverrides, adHocAssertions, repoDirect, loginFlowId,
                "direct", true, pathMode);
    }

    /** 执行内核：caseId=null 即直测（只留痕，不算"已测"、不回写测试标记） */
    private RunResult execute(Long caseId, Map<String, Object> testCase, Long envId, Long commitId,
                              Map<String, Object> paramOverrides, List<Map<String, Object>> adHocAssertions,
                              boolean repoDirect, Long loginFlowId, String mode, boolean direct, String pathMode) {
        String targetType = (String) testCase.get("target_type");
        long targetId = ((Number) testCase.get("target_id")).longValue();
        Map<String, Object> params = new LinkedHashMap<>();
        try {
            if (testCase.get("params") != null) {
                params.putAll(mapper.readValue((String) testCase.get("params"), Map.class));
            }
        } catch (Exception ignore) {
        }
        if (paramOverrides != null) {
            params.putAll(paramOverrides);
        }
        List<Map<String, Object>> assertions = adHocAssertions != null && !adHocAssertions.isEmpty()
                ? adHocAssertions : parseList(testCase.get("assertions"));
        Set<String> ignoreFields = parseSet(testCase.get("ignore_fields"));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("case", testCase.get("name"));
        boolean ok;
        String error = null;
        long latency = 0;
        Long envUsed = envId;

        if ("interface".equals(targetType)) {
            Long eId = resolveEnvForInterface(targetId, envId);
            if (eId == null) return new RunResult(false, "未配置测试环境，请先在[系统设置→测试环境]添加", null);
            envUsed = eId;
            Map<String, Object> itf = jdbc.queryForMap("SELECT * FROM interfaces WHERE id=?", targetId);

            // 仓库直连：解析接口所属仓库的服务地址（第八轮）；解析失败即诚实报错，不静默退回环境地址
            String directBaseUrl = null;
            if (repoDirect) {
                Object repoIdObj = itf.get("repo_id");
                if (repoIdObj == null) {
                    return new RunResult(false, "该接口没有仓库归属（手工建卡或未学习），无法走仓库直连，请改用「项目环境」访问", null);
                }
                List<Map<String, Object>> repos = jdbc.queryForList("SELECT name, base_url FROM repos WHERE id=?", repoIdObj);
                if (repos.isEmpty()) {
                    return new RunResult(false, "该接口归属的仓库已被删除，无法走仓库直连，请改用「项目环境」访问", null);
                }
                Map<String, Object> repo = repos.get(0);
                String rb = repo.get("base_url") == null ? "" : String.valueOf(repo.get("base_url")).trim();
                if (rb.isEmpty()) {
                    return new RunResult(false, "所属仓库「" + repo.get("name") + "」未配置服务地址（Base URL），请到 [项目与仓库→编辑仓库] 填写，或改用「项目环境」访问", null);
                }
                directBaseUrl = rb;
            }

            // 钩子（04-补齐 §3.2）：前置/后置 SQL 独立提交执行，与主测试回滚保护分离
            List<String> preSql = parseStringList(itf.get("pre_sql"));
            List<String> postSql = parseStringList(itf.get("post_sql"));
            List<Map<String, Object>> hookResults = new ArrayList<>();
            for (String sql : preSql) {
                DbExecutorService.DbResult hr = db.runSql(eId, sql, List.of(), true, false);
                audit.log("hook.pre", sql, "case=" + caseId + ", env=" + eId + ", "
                        + (hr.ok() ? (hr.committed() ? "ok(已提交)" : "ok(已回滚)") : "失败: " + hr.error()));
                Map<String, Object> hm = new LinkedHashMap<>();
                hm.put("phase", "pre");
                hm.put("sql", sql);
                hm.put("ok", hr.ok());
                hm.put("committed", hr.committed());
                hm.put("error", hr.error());
                hookResults.add(hm);
                if (!hr.ok()) {
                    return new RunResult(false, "前置钩子执行失败（" + sql + "）：" + hr.error(), null);
                }
            }

            HttpExecutorService.HttpResult r = http.run(targetId, eId, loginFlowId, params, directBaseUrl, pathMode);
            latency = r.latencyMs();
            response.put("httpStatus", r.statusCode());
            response.put("body", r.body());
            response.put("request", r.requestSnapshot());

            for (String sql : postSql) {
                DbExecutorService.DbResult hr = db.runSql(eId, sql, List.of(), true, false);
                audit.log("hook.post", sql, "case=" + caseId + ", env=" + eId + ", "
                        + (hr.ok() ? (hr.committed() ? "ok(已提交)" : "ok(已回滚)") : "失败: " + hr.error()));
                Map<String, Object> hm = new LinkedHashMap<>();
                hm.put("phase", "post");
                hm.put("sql", sql);
                hm.put("ok", hr.ok());
                hm.put("committed", hr.committed());
                hm.put("error", hr.error());
                hookResults.add(hm);
            }
            if (!hookResults.isEmpty()) response.put("hooks", hookResults);

            // 脏数据留痕：写接口且未配 post 清理钩子 → 醒目警告
            if ("write".equals(itf.get("access")) || "mixed".equals(itf.get("access"))) {
                response.put("writesExpected", true);
                if (postSql.isEmpty()) response.put("dirtyWarning", true);
            }

            // 断言分层评估（L1/L2/L4）；无断言时退回"2xx 即通过"
            if (!assertions.isEmpty()) {
                JsonNode bodyNode = parseJson(r.body());
                List<AssertionEngine.AssertionResult> results = AssertionEngine.evaluate(assertions,
                        new AssertionEngine.Context(r.statusCode(), r.latencyMs(), bodyNode, db, eId), mapper);
                response.put("assertions", results);
                ok = r.ok() && results.stream().allMatch(AssertionEngine.AssertionResult::pass);
            } else {
                ok = r.ok() && r.statusCode() >= 200 && r.statusCode() < 300;
            }
            if (!r.ok() && error == null) error = r.error();
        } else {
            Long eId = resolveEnvForDbObject(targetId, envId);
            if (eId == null) return new RunResult(false, "未配置带数据库连接的环境，请先在[系统设置→测试环境]添加", null);
            envUsed = eId;
            Map<String, Object> dbo = jdbc.queryForMap("SELECT * FROM db_objects WHERE id=?", targetId);
            List<Object> bindParams = new ArrayList<>();
            if (params.get("values") instanceof List<?> l) {
                bindParams.addAll(l);
            } else if (!params.isEmpty()) {
                bindParams.addAll(params.values());
            }
            String sql = params.get("sql") != null ? String.valueOf(params.get("sql")) : (String) dbo.get("sql_template");
            // databaseId 变体按环境库类型选择（04-补齐 §3.1.1）
            Map<String, Object> envRow = jdbc.queryForMap("SELECT db_type FROM environments WHERE id=?", eId);
            String envDbType = (String) envRow.get("db_type");
            String variantNote = null;
            String dboName = String.valueOf(dbo.get("name"));
            if (dboName.contains("[db:") && envDbType != null && !envDbType.isBlank()) {
                String variant = dboName.replaceAll("^.*\\[db:", "").replace("]", "");
                if (!variant.equals(envDbType)) {
                    Map<String, Object> sibling = null;
                    try {
                        String baseName = dboName.replaceAll("\\[db:[^]]*]$", "");
                        sibling = jdbc.queryForMap("SELECT * FROM db_objects WHERE project_id=? AND name=?",
                                dbo.get("project_id"), baseName + "[db:" + envDbType + "]");
                    } catch (Exception ignore) {
                    }
                    if (sibling != null) {
                        sql = (String) sibling.get("sql_template");
                        variantNote = "已按环境库类型 " + envDbType + " 自动选择语句变体";
                    } else {
                        variantNote = "环境库类型 " + envDbType + " 无匹配的 databaseId 变体，使用当前版本";
                    }
                }
            }
            boolean isProc = "procedure".equals(dbo.get("obj_type")) || "function".equals(dbo.get("obj_type"));
            long needed = sql == null ? 0 : sql.chars().filter(c -> c == '?').count();
            if (bindParams.size() < needed) {
                return new RunResult(false, "该 SQL 需要 " + needed + " 个绑定参数，用例当前只提供 " + bindParams.size()
                        + " 个。请在页面上补全参数值后再执行", null);
            }
            DbExecutorService.DbResult r = db.runSql(eId, sql, bindParams, false, isProc);
            ok = r.ok();
            error = r.error();
            response.put("columns", r.columns());
            response.put("rows", r.rows());
            response.put("updateCount", r.updateCount());
            response.put("rolledBack", !r.committed());
            // 第 26 轮：用例执行与直测用同一个判定谓词，否则列表里两条记录会给出两种结论
            List<BizErrorScanner.Hit> biz = ok ? BizErrorScanner.scanRun(r, BizErrorScanner.whitelistFrom(jdbc))
                    : List.of();
            response.put("bizErrors", BizErrorScanner.toJson(biz));
            if (!biz.isEmpty()) response.put("bizErrorCode", biz.get(0).value());
            if (variantNote != null) response.put("variantNote", variantNote);
            latency = r.latencyMs();
        }

        // 基线对比（纯工程 diff，忽略字段可配置）
        String baselineDiff = null;
        Number baseRunId = (Number) testCase.get("baseline_run_id");
        if (baseRunId != null) {
            try {
                Map<String, Object> baseRun = jdbc.queryForMap(
                        "SELECT response_snapshot FROM test_runs WHERE id=?", baseRunId.longValue());
                Set<String> ignore = new HashSet<>(ignoreFields);
                ignore.addAll(Set.of("timestamp", "traceId", "requestId", "elapsed", "cost"));
                // R15：body 解析成 JSON 后做字段级 diff（忽略规则才能真正生效）
                JsonNode baseNode = parseJson((String) baseRun.get("response_snapshot"));
                JsonNode curNode = mapper.valueToTree(response);
                JsonNode baseBody = baseNode == null ? null : baseNode.path("body");
                JsonNode curBody = parseJson(String.valueOf(curNode.path("body").asText("")));
                List<Map<String, String>> diffs;
                if (baseBody != null && baseBody.isObject() && curBody != null && curBody.isObject()) {
                    diffs = AssertionEngine.diffJson(baseBody, curBody, ignore, "body");
                } else {
                    diffs = AssertionEngine.diffJson(baseNode, curNode, ignore, "");
                }
                baselineDiff = mapper.writeValueAsString(diffs);
                response.put("baselineDiff", baselineDiff);
                response.put("baselineDiffCount", diffs.size());
            } catch (Exception e) {
                baselineDiff = "[]";
            }
        }

        String status = ok ? "pass" : "fail";
        if (!ok && error != null) response.put("error", error);
        long runId = insertRun(new RunRow(caseId, targetType, targetId,
                mode == null ? ("interface".equals(targetType) ? "http" : "db") : mode,
                envUsed, commitId,
                (String) response.get("request"), toJson(response), status, latency,
                response.containsKey("assertions") ? toJson(response.get("assertions")) : null,
                baselineDiff, response.get("writesExpected") != null,
                response.containsKey("hooks") ? toJson(response.get("hooks")) : null));

        Map<String, Object> run = new LinkedHashMap<>(response);
        run.put("runId", runId);
        run.put("status", status);
        run.put("error", error);
        run.put("latencyMs", latency);
        run.put("direct", direct);
        // 只有挂在用例上的执行才算"已测"（回写覆盖率）；纯直测只留痕（第 20 轮口径）
        if (commitId != null && !direct) {
            autoMark(commitId);
        }
        return new RunResult(true, null, run);
    }

    /** test_runs 的一行（挂用例的正式执行与目标级直测共用同一写入点）；caseId=null 即未挂用例的直测 */
    private record RunRow(Long caseId, String targetType, long targetId, String mode, Long envId, Long commitId,
                          String request, String responseJson, String status, long latencyMs,
                          String assertions, String baselineDiff, boolean writesExpected, String hooks) {}

    private long insertRun(RunRow row) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO test_runs(case_id, target_type, target_id, mode, env_id, commit_id, request_snapshot, response_snapshot, status, latency_ms, assertions_result, baseline_diff, writes_expected, hooks_result, executed_by) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            if (row.caseId() == null) ps.setNull(1, java.sql.Types.INTEGER); else ps.setLong(1, row.caseId());
            ps.setString(2, row.targetType);
            ps.setLong(3, row.targetId);
            ps.setString(4, row.mode);
            if (row.envId == null) ps.setNull(5, java.sql.Types.INTEGER); else ps.setLong(5, row.envId);
            if (row.commitId == null) ps.setNull(6, java.sql.Types.INTEGER); else ps.setLong(6, row.commitId);
            ps.setString(7, row.request);
            ps.setString(8, row.responseJson);
            ps.setString(9, row.status);
            ps.setLong(10, row.latencyMs);
            ps.setString(11, row.assertions);
            ps.setString(12, row.baselineDiff);
            if (row.writesExpected) ps.setInt(13, 1); else ps.setNull(13, java.sql.Types.INTEGER);
            ps.setString(14, row.hooks);
            String user = com.preflight.common.UserContext.username();
            ps.setString(15, user == null ? "system" : user);
            return ps;
        }, kh);
        return kh.getKey() == null ? 0 : kh.getKey().longValue();
    }

    /**
     * 第 20 轮：直测留痕统一入口——SQL 直测、以及测试台上没带用例的执行都走这里。
     * case_id 为空 = 只作标记（不计入用例通过率、不翻覆盖率）；带 case_id = 从用例点进来的，按该用例算。
     */
    public long recordDirect(String targetType, long targetId, Long caseId, Long envId, Long commitId,
                             String request, Map<String, Object> snapshot, boolean ok, long latencyMs) {
        long runId = insertRun(new RunRow(caseId, targetType, targetId, "direct", envId, commitId,
                request, toJson(snapshot), ok ? "pass" : "fail", latencyMs, null, null,
                Boolean.TRUE.equals(snapshot.get("committed")), null));
        if (caseId != null && commitId != null) autoMark(commitId);
        return runId;
    }

    private Long resolveEnvForInterface(long interfaceId, Long envId) {
        if (envId != null) return envId;
        Long projectId = jdbc.queryForObject("SELECT project_id FROM interfaces WHERE id=?", Long.class, interfaceId);
        List<Map<String, Object>> envs = jdbc.queryForList(
                "SELECT id FROM environments WHERE project_id=? ORDER BY id LIMIT 1", projectId);
        return envs.isEmpty() ? null : ((Number) envs.get(0).get("id")).longValue();
    }

    private Long resolveEnvForDbObject(long dbObjectId, Long envId) {
        if (envId != null) return envId;
        Long projectId = jdbc.queryForObject("SELECT project_id FROM db_objects WHERE id=?", Long.class, dbObjectId);
        List<Map<String, Object>> envs = jdbc.queryForList(
                "SELECT id FROM environments WHERE project_id=? AND db_url IS NOT NULL ORDER BY id LIMIT 1", projectId);
        return envs.isEmpty() ? null : ((Number) envs.get(0).get("id")).longValue();
    }

    private List<Map<String, Object>> parseList(Object json) {
        if (json == null) return List.of();
        try {
            return mapper.readValue(json.toString(), List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> parseStringList(Object json) {
        List<String> out = new ArrayList<>();
        for (Object o : parseList(json)) out.add(String.valueOf(o));
        return out;
    }

    private Set<String> parseSet(Object json) {
        if (json == null) return Set.of();
        try {
            return new HashSet<>(mapper.readValue(json.toString(), List.class));
        } catch (Exception e) {
            return Set.of();
        }
    }

    private JsonNode parseJson(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 执行结果回写测试标记：全部 pass → tested；有 pass 有 fail → partial；全 fail 不改标记（由人判断）。
     * 第 20 轮口径：只看挂了用例的执行。直测（case_id 为空）按用户拍板"只作标记，不计入通过率/覆盖率"，
     * 所以既不能自己触发这里，也不能在这里被算进去——否则一次随手直测就能把整条提交刷成"已测"。
     */
    private void autoMark(long commitId) {
        List<Map<String, Object>> runs = jdbc.queryForList(
                "SELECT status FROM test_runs WHERE commit_id=? AND case_id IS NOT NULL", commitId);
        if (runs.isEmpty()) return;
        long pass = runs.stream().filter(r -> "pass".equals(r.get("status"))).count();
        long fail = runs.stream().filter(r -> "fail".equals(r.get("status"))).count();
        String mark = pass > 0 && fail > 0 ? "partial" : (pass > 0 ? "tested" : null);
        if (mark != null) {
            jdbc.update("UPDATE file_changes SET test_status=?, tested_at=datetime('now','localtime') WHERE commit_id=? AND test_status='untested'",
                    mark, commitId);
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
