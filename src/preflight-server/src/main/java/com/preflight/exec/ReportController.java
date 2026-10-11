package com.preflight.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提测结论单（第卅三轮第一批）：按受影响目标出行、每行点回执行留痕。
 * 与 {@link ReleaseController}（起飞检查单，按文件）并存，同一张 release_checklists 表靠 kind 分流。
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService report;
    /**
     * 导出专用的 mapper：全站是 non_null（页面少发 null 键没影响），但机器可读出口的合同就是
     * "字段名固定"——没有留痕的行必须发出 "evidence_id": null，而不是把键整个丢掉。
     */
    private final ObjectMapper nullsMapper;

    public ReportController(ReportService report, ObjectMapper mapper) {
        this.report = report;
        this.nullsMapper = mapper.copy()
                .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS);
    }

    /** 生成草稿：draft 状态，行与缺口当场固化，之后不随知识变化而变 */
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        try {
            long projectId = ((Number) body.get("projectId")).longValue();
            long from = ((Number) body.get("fromCommitId")).longValue();
            long to = ((Number) body.get("toCommitId")).longValue();
            Long envId = body.get("envId") == null ? null : ((Number) body.get("envId")).longValue();
            Long supersedes = body.get("supersedesId") == null ? null : ((Number) body.get("supersedesId")).longValue();
            return report.generate(projectId, from, to, envId, String.valueOf(body.getOrDefault("title", "")), supersedes);
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam long projectId) {
        return Map.of("ok", true, "reports", report.list(projectId));
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        try {
            return report.detail(id);
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    /** 明细行分页（页面用；导出与 JSON 出口走 detail，一次取全，口径同一份 report_rows） */
    @GetMapping("/{id}/rows")
    public Map<String, Object> rows(@PathVariable long id,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(required = false) String basis,
                                    @RequestParam(required = false) String evidence,
                                    @RequestParam(required = false) String q) {
        try {
            return report.rows(id, page, Math.min(200, Math.max(1, size)), basis, evidence, q);
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    /** 出单：draft → issued，之后不可改（要改结论只能再出一份 v2 指向它） */
    @PostMapping("/{id}/issue")
    public Map<String, Object> issue(@PathVariable long id) {
        try {
            return report.issue(id);
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    /**
     * 逐行确认缺口（只认草稿）：decision=confirmed 已核对 / skipped 这一行本次不测（必须写理由）。
     * 一条签字盖住这一行的全部缺口——同一个目标挂三条缺口时，判断只有一遍，不该点三次。
     */
    @PostMapping("/{id}/sign")
    public Map<String, Object> sign(@PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            Object uid = body.get("unitId");
            if (body.get("unitKind") == null || uid == null) {
                return Map.of("ok", false, "error", "缺 unitKind 或 unitId：签字要指到结论单上的某一行");
            }
            return report.sign(id, String.valueOf(body.get("unitKind")),
                    Long.parseLong(String.valueOf(uid).trim()),
                    String.valueOf(body.getOrDefault("decision", "")),
                    body.get("reason") == null ? null : String.valueOf(body.get("reason")));
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    /**
     * 机器可读出口（给 CI / 别的工具消费）：字段名固定，逐行带 evidence_id（无留痕时是 null，不是缺键）。
     * 与页面同一份 report_rows，不做二次口径。
     */
    @GetMapping("/{id}/json")
    public ResponseEntity<byte[]> json(@PathVariable long id) throws Exception {
        Map<String, Object> d = report.detail(id);
        Map<String, Object> rep = asMap(d.get("report"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", "preflight_test_report");
        out.put("schema_version", 1);
        out.put("report_id", rep.get("id"));
        out.put("title", rep.get("title"));
        out.put("status", rep.get("status"));
        out.put("project_id", rep.get("project_id"));
        out.put("commit_range", Map.of("from_id", rep.get("from_revision"), "to_id", rep.get("to_revision")));
        out.put("scope", d.get("scope"));
        out.put("fingerprint", d.get("fingerprint"));
        out.put("totals", d.get("totals"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : asList(d.get("rows"))) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("evidence_id", r.get("run_id"));
            x.put("unit_kind", r.get("unit_kind"));
            x.put("unit_id", r.get("unit_id"));
            x.put("unit_name", r.get("unit_name"));
            x.put("attribution_basis", r.get("basis"));
            x.put("commit_ids", r.get("commit_ids"));
            x.put("case_id", r.get("case_id"));
            x.put("case_name", r.get("case_name"));
            x.put("run_status", r.get("run_status"));
            x.put("run_env_id", r.get("run_env_id"));
            x.put("run_commit_id", r.get("run_commit_id"));
            x.put("run_at", r.get("run_at"));
            x.put("note", r.get("note"));
            // 出参证据：数组（没核对上是 null，不是缺键）；frozen=true 表示这是出单那刻固化的留痕
            x.put("out_params", r.get("out_params"));
            x.put("out_params_frozen", r.get("out_params_frozen"));
            x.put("sign_decision", r.get("sign_decision"));
            x.put("sign_by", r.get("sign_by"));
            x.put("sign_at", r.get("sign_at"));
            x.put("sign_reason", r.get("sign_reason"));
            rows.add(x);
        }
        out.put("rows", rows);
        out.put("gaps", d.get("gaps"));
        out.put("signoffs", d.get("signoffs"));
        out.put("out_params_partial", d.get("out_params_partial"));
        byte[] bytes = nullsMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(out);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=test-report-" + id + ".json")
                .body(bytes);
    }

    /** 人可读出口（打印即 PDF）：与页面同一份 report_rows 渲染，缺证据的行必须出现在风险声明里 */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> exportHtml(@PathVariable long id) throws Exception {
        Map<String, Object> d = report.detail(id);
        Map<String, Object> rep = asMap(d.get("report"));
        Map<String, Object> totals = asMap(d.get("totals"));
        Map<String, Object> fp = asMap(d.get("fingerprint"));
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>")
                .append(esc(rep.get("title"))).append(" · 提测结论单</title><style>")
                .append("body{font-family:'Microsoft YaHei',sans-serif;margin:40px;color:#1e293b}")
                .append("h1{border-bottom:3px solid #4f46e5;padding-bottom:8px}")
                .append("table{border-collapse:collapse;width:100%;margin-top:12px}")
                .append("th,td{border:1px solid #cbd5e1;padding:6px 10px;font-size:13px;text-align:left;vertical-align:top}")
                .append(".ok{color:#059669;font-weight:700}.bad{color:#dc2626;font-weight:700}")
                .append(".muted{color:#64748b;font-size:12px}code{font-size:12px}</style></head><body>");
        html.append("<h1>📑 ").append(esc(rep.get("title"))).append(" · 提测结论单</h1>");
        html.append("<p class='muted'>状态：").append(esc(rep.get("status")))
                .append(" ｜ 范围：提交 #").append(esc(rep.get("from_revision"))).append(" → #")
                .append(esc(rep.get("to_revision"))).append(" ｜ 生成人：").append(esc(rep.get("created_by")))
                .append(" ｜ 生成时间：").append(esc(rep.get("created_at"))).append("</p>");

        html.append("<h3>指纹</h3><table><tr><th>要素</th><th>值</th></tr>");
        html.append("<tr><td>代码版本</td><td>").append(esc(fp.get("code"))).append("</td></tr>");
        html.append("<tr><td>库结构版本</td><td>")
                .append(fp.get("schema") == null ? "<span class='bad'>缺：" + esc(fp.get("schema_missing")) + "</span>"
                        : esc(fp.get("schema"))).append("</td></tr>");
        html.append("<tr><td>通道版本</td><td>")
                .append(fp.get("channel") == null ? "<span class='bad'>缺：" + esc(fp.get("channel_missing")) + "</span>"
                        : esc(fp.get("channel"))).append("</td></tr>");
        html.append("<tr><td>人</td><td>").append(esc(fp.get("by"))).append("</td></tr></table>");

        html.append("<h3>结论</h3><p>共 ").append(esc(totals.get("targets"))).append(" 个受影响目标：")
                .append("<span class='ok'>有执行留痕 ").append(esc(totals.get("with_evidence"))).append(" 个</span>，")
                .append("<span class='bad'>缺留痕 ").append(esc(totals.get("without_evidence"))).append(" 个</span>；")
                .append("其中归因宽（整文件 / 调用链）").append(esc(totals.get("coarse_basis"))).append(" 个、")
                .append("<span class='bad'>出参有变动 ").append(esc(totals.get("out_param_changed"))).append(" 个</span>。</p>");
        if (Boolean.TRUE.equals(d.get("out_params_partial"))) {
            html.append("<p class='bad'>出参这一列没核对：区间提交数超过扫描上限，把区间拆小再出一份才有。</p>");
        }
        html.append("<p class='muted'>只出证据、不做自动定性：能不能提测由上面这些事实 + 下面的缺口清单 + 逐行签字共同决定，")
                .append("系统不替人下「通过」结论。</p>");

        html.append("<h3>逐目标证据</h3><table><tr><th>类型</th><th>目标</th><th>归因</th><th>涉及提交</th>")
                .append("<th>用例</th><th>最近执行</th><th>结果</th><th>环境</th><th>出参/入参变动</th>")
                .append("<th>核对签字</th><th>备注</th></tr>");
        for (Map<String, Object> r : asList(d.get("rows"))) {
            html.append("<tr><td>").append("interface".equals(r.get("unit_kind")) ? "接口" : "SQL").append("</td>")
                    .append("<td><code>").append(esc(r.get("unit_name"))).append("</code></td>")
                    .append("<td>").append(esc(ReportService.BASIS_LABELS.getOrDefault(
                            String.valueOf(r.get("basis")), String.valueOf(r.get("basis"))))).append("</td>")
                    .append("<td>").append(esc(r.get("commit_ids"))).append("</td>")
                    .append("<td>").append(esc(r.get("case_name"))).append("</td>")
                    .append("<td>").append(r.get("run_id") == null ? "<span class='bad'>无留痕</span>"
                            : "#" + esc(r.get("run_id")) + " " + esc(r.get("run_at"))).append("</td>")
                    .append("<td class='").append("pass".equals(r.get("run_status")) ? "ok" : "bad").append("'>")
                    .append(esc(r.get("run_status"))).append("</td>")
                    .append("<td>").append(esc(r.get("run_env_id"))).append("</td>")
                    .append("<td>").append(outParamCell(r.get("out_params"), r.get("basis"))).append("</td>")
                    .append("<td>").append(r.get("sign_decision") == null ? "<span class='bad'>未确认</span>"
                            : esc(signCn(r.get("sign_decision"))) + "：" + esc(r.get("sign_by"))
                                    + " " + esc(r.get("sign_at"))
                                    + (r.get("sign_reason") == null || String.valueOf(r.get("sign_reason")).isBlank()
                                            ? "" : "（" + esc(r.get("sign_reason")) + "）")).append("</td>")
                    .append("<td>").append(esc(r.get("note"))).append("</td></tr>");
        }
        html.append("</table>");

        List<Map<String, Object>> gaps = asList(d.get("gaps"));
        html.append("<h3>风险声明（证据不足处，共 ").append(gaps.size()).append(" 条）</h3>");
        if (gaps.isEmpty()) {
            html.append("<p class='ok'>本区间每个目标都有执行留痕。</p>");
        } else {
            html.append("<table><tr><th>目标</th><th>缺口</th><th>说明</th></tr>");
            for (Map<String, Object> g : gaps) {
                html.append("<tr><td><code>").append(esc(g.get("unit_name"))).append("</code></td><td>")
                        .append(esc(g.get("gap_type"))).append("</td><td>").append(esc(g.get("detail")))
                        .append("</td></tr>");
            }
            html.append("</table>");
        }

        List<Map<String, Object>> signoffs = asList(d.get("signoffs"));
        html.append("<h3>签字记录（共 ").append(signoffs.size()).append(" 次，改判历史全留）</h3>");
        if (signoffs.isEmpty()) {
            html.append("<p class='muted'>这份单没有逐行签字。</p>");
        } else {
            html.append("<table><tr><th>目标</th><th>决定</th><th>理由</th><th>签字人</th><th>时间</th></tr>");
            for (Map<String, Object> s : signoffs) {
                html.append("<tr><td><code>").append(esc(s.get("unit_name"))).append("</code></td><td>")
                        .append(esc(signCn(s.get("decision")))).append("</td><td>").append(esc(s.get("reason")))
                        .append("</td><td>").append(esc(s.get("by"))).append("</td><td>")
                        .append(esc(s.get("at"))).append("</td></tr>");
            }
            html.append("</table>");
        }
        html.append("</body></html>");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=test-report-" + id + ".html")
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .body(html.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 出参一格：null＝没核对（照实说），空表＝核对过没变动，有内容＝按字段列出增删改 */
    private String outParamCell(Object v, Object basis) {
        if (v == null) return "<span class='bad'>未核对</span>";
        List<Map<String, Object>> fs = asList(v);
        if (fs.isEmpty()) return "<span class='muted'>无变动</span>";
        boolean coarse = !"line".equals(String.valueOf(basis)); // 整文件与调用链都算"归因宽"，字段级判定同 ReportService.ownScope
        StringBuilder sb = new StringBuilder();
        for (String io : List.of("out", "in")) {
            List<Map<String, Object>> part = fs.stream().filter(f -> io.equals(f.get("io"))
                    && (coarse || !"file".equals(f.get("scope")))).toList();
            if (part.isEmpty()) continue;
            sb.append(io.equals("out") ? "<b class='bad'>出参 " : "<b>入参 ").append(part.size()).append("：</b>");
            for (int i = 0; i < part.size(); i++) {
                Map<String, Object> f = part.get(i);
                if (i > 0) sb.append("、");
                sb.append("<code>").append(esc(f.get("field"))).append("</code>")
                        .append(esc(com.preflight.exec.ReportService.changeCn(String.valueOf(f.get("change")))))
                        .append("<span class='muted'>（").append(esc(f.get("via"))).append("｜")
                        .append(esc(f.get("file"))).append("｜#").append(esc(joinIds(f.get("commit_ids"))))
                        .append(coarse ? "" : "｜本条语句").append("）</span>");
            }
            sb.append("<br>");
        }
        long sibling = fs.size() - fs.stream().filter(f -> coarse || !"file".equals(f.get("scope"))).count();
        if (sibling > 0) {
            sb.append("<span class='muted'>另有 ").append(sibling)
                    .append(" 个字段改在同一文件的别的语句里，没算在这一行头上</span>");
        }
        return sb.toString();
    }

    private String signCn(Object decision) {
        return switch (String.valueOf(decision)) {
            case "confirmed" -> "已核对";
            case "skipped" -> "跳过";
            default -> String.valueOf(decision);
        };
    }

    /** 出参证据里的提交号：草稿期是 List&lt;Long&gt;，固化后读回来还是数组，两种都要能拼 */
    private String joinIds(Object v) {
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        }
        return String.valueOf(v == null ? "" : v);
    }

    /**
     * /json 与 /export 是直接吐文件的出口，没有 {ok:false} 那层壳：单据不存在时必须照实说清并给 404，
     * 不许把 JDBC 的管道话（Incorrect result size: expected 1, actual 0）漏到 CI 或浏览器里。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<byte[]> unreadable(Exception e) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", String.valueOf(e.getMessage()));
        return ResponseEntity
                .status(e instanceof IllegalStateException ? org.springframework.http.HttpStatus.NOT_FOUND
                        : org.springframework.http.HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(nullsMapper.writeValueAsBytes(body));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asList(Object o) {
        return o instanceof List ? (List<Map<String, Object>>) o : List.of();
    }

    private String esc(Object o) {
        return String.valueOf(o == null ? "" : o).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
