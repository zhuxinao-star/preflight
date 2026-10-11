package com.preflight.learn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.preflight.common.AuditService;
import com.preflight.parser.DefaultValueRules;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 分发框架服务字典 API（第 10 轮）。
 * 字典不是接口卡，只在测试台选中分发入口卡时按服务码查询，用来构建通用信封与回填业务参数。
 */
@RestController
@RequestMapping("/api/dispatch")
public class DispatchController {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final DispatchDictionaryService dictionary;
    private final OutParamService outParams;
    private final DispatchEnvelopeService envelope;
    private final DispatchBodyPlanService bodyPlan;

    public DispatchController(JdbcTemplate jdbc, ObjectMapper mapper, AuditService audit,
                              DispatchDictionaryService dictionary, OutParamService outParams,
                              DispatchEnvelopeService envelope, DispatchBodyPlanService bodyPlan) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.audit = audit;
        this.dictionary = dictionary;
        this.outParams = outParams;
        this.envelope = envelope;
        this.bodyPlan = bodyPlan;
    }

    /** 项目有没有分发框架、字典规模如何（测试台据此决定是否显示分发面板） */
    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam long projectId) {
        List<Map<String, Object>> entries = jdbc.queryForList("""
                SELECT i.id, i.http_method, i.path, i.name FROM interfaces i
                WHERE i.project_id=? AND i.params_schema LIKE '%"dispatch"%'
                ORDER BY i.path
                """, projectId);
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_targets WHERE project_id=?",
                Integer.class, projectId);
        Integer withParams = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispatch_targets WHERE project_id=? AND params NOT IN ('[]','null')",
                Integer.class, projectId);
        Integer used = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispatch_targets WHERE project_id=? AND sources LIKE '%js%'",
                Integer.class, projectId);
        Integer byChain = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispatch_targets WHERE project_id=? AND params LIKE '%\"source\":\"chain%'",
                Integer.class, projectId);
        // 框架名是字典里的事实，不再写死一种（第 39 轮：一个项目可以同时挂 KJDP 与统一访问转发中心）
        List<String> fws = jdbc.queryForList(
                "SELECT DISTINCT framework FROM dispatch_targets WHERE project_id=? AND framework<>'' ORDER BY framework",
                String.class, projectId);
        String framework = fws.isEmpty() ? null : String.join("、", fws);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("detected", framework != null);
        out.put("framework", framework);
        out.put("targets", total == null ? 0 : total);
        out.put("withParams", withParams == null ? 0 : withParams);
        out.put("calledByFrontend", used == null ? 0 : used);
        out.put("learnedByChain", byChain == null ? 0 : byChain);
        out.put("entries", entries);
        return out;
    }

    /** 服务码列表：kind=service|bex|空(全部)，repoId 按仓库过滤，q 匹配码或中文名；一律服务端分页 */
    @GetMapping("/targets")
    public Map<String, Object> targets(@RequestParam long projectId,
                                       @RequestParam(required = false) String kind,
                                       @RequestParam(required = false) Long repoId,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        StringBuilder sql = new StringBuilder("SELECT kind, code, name, entry_path, sources, evidence, params "
                + "FROM dispatch_targets WHERE project_id=?");
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        if (kind != null && (kind.equals("service") || kind.equals("bex"))) {
            sql.append(" AND kind=?");
            args.add(kind);
        }
        if (repoId != null) {   // 字典表一直带 repo_id，只是以前没露出过滤口：多仓库项目里搜不动想要的码
            sql.append(" AND repo_id=?");
            args.add(repoId);
        }
        if (q != null && !q.isBlank()) {
            String esc = com.preflight.common.SqlLikes.ESCAPE_CLAUSE;
            sql.append(" AND (lower(code) LIKE ?").append(esc)
                    .append(" OR lower(IFNULL(name,'')) LIKE ?").append(esc).append(")");
            String needle = com.preflight.common.SqlLikes.contains(q.toLowerCase());
            args.add(needle);
            args.add(needle);
        }
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + sql + ") t", Integer.class,
                args.toArray());
        // 以前是"带 page 才 LIMIT"：不带 page 时一次吐全表（4765 行 ≈ 1MB JSON），
        // 而且界面数出来的条数和 total 不是一回事。现在无论谁调都按页回，page/size 一并回传。
        int sz = Math.min(Math.max(size, 1), 200);   // 与前端【每页】上限同级；偏移与它用同一个值，否则翻页会跳格
        int p = Math.max(page, 1);
        sql.append(" ORDER BY kind, code LIMIT ? OFFSET ?");
        args.add(sz);
        args.add(com.preflight.common.Paging.offset(p, sz));
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("kind", r.get("kind"));
            t.put("code", r.get("code"));
            t.put("name", r.get("name"));
            t.put("entryPath", r.get("entry_path"));
            t.put("sources", r.get("sources"));
            t.put("paramCount", paramCount(r.get("params")));
            out.add(t);
        }
        return Map.of("ok", true, "targets", out, "total", total == null ? 0 : total, "page", p, "size", sz);
    }

    /** 单个服务码的完整参数（测试台构建信封时取用） */
    /**
     * 单个服务码的完整参数（测试台构建信封时取用）。
     *
     * <p>第四十六轮补 {@code path} 与 {@code bodyPlan}：报文头每个键到底发什么值、这张卡自己学到的
     * 入参有哪些，都由 {@link DispatchBodyPlanService} 在读取期现算一次（规则表命中就带默认值，
     * 没命中按空串）。传 {@code path} 是为了同一功能号挂两条真实路径时各读各的声明；不传退回字典
     * 那条代表路径，老的调用方一字不用改。
     */
    @GetMapping("/target")
    public Map<String, Object> target(@RequestParam long projectId, @RequestParam String kind,
                                      @RequestParam String code,
                                      @RequestParam(required = false) String path) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM dispatch_targets WHERE project_id=? AND kind=? AND code=?",
                projectId, kind, code);
        if (rows.isEmpty()) return Map.of("ok", false, "error", "字典里没有 " + kind + "「" + code
                + "」，可能还没学习该仓库，或这个服务码只存在于运行时配置里");
        Map<String, Object> r = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("kind", r.get("kind"));
        out.put("code", r.get("code"));
        out.put("name", r.get("name"));
        out.put("entryPath", r.get("entry_path"));
        out.put("sources", r.get("sources"));
        out.put("params", withRuleDefaults(readList(r.get("params")), code));
        out.put("evidence", readList(r.get("evidence")));
        // 第卅四轮补 viewConf、第卅五轮改主源：出参先按盘上代码推算，viewConf 只当补源（都是读期现算）
        out.put("outParams", outParams.byCode(projectId, kind, code));
        String cardPath = (path == null || path.isBlank()) ? String.valueOf(r.get("entry_path")) : path;
        out.put("bodyPlan", bodyPlan.plan(projectId, cardPath, code));
        return out;
    }

    /**
     * 只看某个代码的出入参与依据链（第卅五轮）：纯按工作副本里的 bexConf / mapper 语句 / 盘上
     * 存储过程源码推算，viewConf 作补源，不读字典也不写任何东西——改动流里出现的功能号常常根本
     * 没学习过，走 {@code /target} 只会得到「字典里没有」，这条端点要的就是「盘上代码到底怎么说」。
     */
    @GetMapping("/outparams")
    public Map<String, Object> outparams(@RequestParam long projectId, @RequestParam String kind,
                                         @RequestParam String code) {
        return outParams.byCode(projectId, kind, code);
    }

    /**
     * 第卅五轮：这个代码的调用点到底带哪些信封字段（service / func / func_url / bex_codes / 分页）。
     * YApi 建文档与人工核对用同一条判据，不各算各的。
     */
    @GetMapping("/envelope")
    public Map<String, Object> envelope(@RequestParam long projectId, @RequestParam String kind,
                                        @RequestParam String code) {
        return envelope.forTarget(projectId, kind, code);
    }

    /**
     * 学习给不出值的字段按跨项目规则表补：常量类（op_code=1）给常量，功能号类给<b>本次</b>业务代码，
     * 出处标签一律取 {@link DispatchBodyPlanService#ruleFrom}——和测试台请求体那份是同一套字符串，
     * 不在这张表上再判一遍规矩（判两遍就会有两套结论）。
     * 这次没有业务代码时那一格标 {@code rule-code-missing} 并照空串走，绝不标成"没这条规则"。
     */
    @SuppressWarnings("unchecked")
    private List<?> withRuleDefaults(List<?> params, String code) {
        Optional<String> fn = Optional.ofNullable(code == null ? "" : code.trim()).filter(s -> !s.isEmpty());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : params) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> p = new LinkedHashMap<>((Map<String, Object>) raw);
            Object def = p.get("def");
            if ((def == null || String.valueOf(def).isEmpty())
                    && p.get("key") instanceof String key) {
                DefaultValueRules.Rule r = DefaultValueRules.rule(key, fn);
                String from = DispatchBodyPlanService.ruleFrom(r);
                if (!DispatchBodyPlanService.FROM_NONE.equals(from)) {
                    p.put("def", r.value().orElse(""));
                    p.put("defFrom", from);
                }
            }
            out.add(p);
        }
        return out;
    }

    /** 手动重建字典（前端仓库单独更新后可用，不必重学后端） */
    @PostMapping("/rebuild")
    public Map<String, Object> rebuild(@RequestBody Map<String, Object> body) {
        Object pid = body.get("projectId");
        if (pid == null) return Map.of("ok", false, "error", "缺少 projectId");
        long projectId = ((Number) pid).longValue();
        try {
            DispatchDictionaryService.BuildResult res = dictionary.rebuild(projectId);
            audit.log("dispatch.rebuild", "project#" + projectId,
                    res.framework() + " 入口 " + res.entries() + " 字典 " + res.targets());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("detected", res.detected());
            out.put("framework", res.framework());
            out.put("entries", res.entries());
            out.put("targets", res.targets());
            out.put("withParams", res.withParams());
            out.put("fromCallSites", res.fromCallSites());
            out.put("fromChain", res.fromChain());
            out.put("warnings", res.warnings());
            if (res.loginFlowNote() != null) out.put("loginFlowNote", res.loginFlowNote());
            if (!res.detected()) {
                out.put("notice", "该项目下没有仓库被识别为分发框架（需要 WEB-INF/web.xml + metaData 目录或 kjdp 依赖包）");
            }
            return out;
        } catch (Exception e) {
            return Map.of("ok", false, "error", "重建失败: " + e.getMessage());
        }
    }

    private int paramCount(Object paramsJson) {
        return readList(paramsJson).size();
    }

    private List<?> readList(Object json) {
        if (json == null) return List.of();
        try {
            return mapper.readValue(String.valueOf(json), List.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
