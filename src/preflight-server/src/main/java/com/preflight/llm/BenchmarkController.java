package com.preflight.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 抽取质量评测集（04-补齐 §3.7，D3 落地）：
 * 人工标注的黄金接口卡作为基线，每次更换模型/提示词先跑评测，输出字段级精确率/召回率。
 * 没有评测集，内网小模型的抽取质量就是不可知论；不达标（建议 <85% 召回）禁止自动草稿入库。
 */
@RestController
@RequestMapping("/api/llm/benchmark")
public class BenchmarkController {

    private final LlmGatewayService llm;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** 黄金基线样例（正式使用时由人工标注 20~50 条真实接口替换/扩充，存 artifacts/benchmark/） */
    private static final String SAMPLE = """
            {"cases":[
              {"name":"创建订单","code":"@PostMapping(value=\\"/orders\\")\\npublic Object create(@RequestBody CreateOrderRequest request) { return orderService.create(request); }",
               "expected":{"path":"/orders","method":"POST","params":["request"],"tables":[]}},
              {"name":"订单详情","code":"@GetMapping(value=\\"/orders/{id}\\")\\npublic Object detail(@PathVariable(\\"id\\") Long id) { return orderService.detail(id); }",
               "expected":{"path":"/orders/{id}","method":"GET","params":["id"],"tables":["t_order"]}}
            ]}
            """.replace("\\\"", "'").replace("\\n", " ");

    public BenchmarkController(LlmGatewayService llm, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.llm = llm;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping("/history")
    public Map<String, Object> history() {
        return Map.of("ok", true, "runs", jdbc.queryForList(
                "SELECT id, source, report, created_at FROM benchmark_runs ORDER BY id DESC LIMIT 10"));
    }

    @GetMapping
    public Map<String, Object> info() {
        return Map.of("ok", true, "sample", SAMPLE,
                "usage", "POST content={cases:[...]} 或空内容使用内置样例；评分=字段级精确率/召回率，召回<85% 建议禁止内网自动草稿");
    }

    /** 跑评测（R13）：黄金基线支持 data/artifacts/benchmark/benchmark.json（人工标注）覆盖内置样例；分数落盘并写入准入门槛 */
    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody(required = false) Map<String, Object> body) throws Exception {
        java.nio.file.Path golden = java.nio.file.Path.of("data", "artifacts", "benchmark", "benchmark.json");
        String source = "sample";
        String content;
        if (java.nio.file.Files.exists(golden)) {
            content = java.nio.file.Files.readString(golden);
            source = "golden-file";
        } else if (body != null && body.get("content") != null) {
            content = mapper.writeValueAsString(body.get("content"));
            source = "inline";
        } else {
            content = SAMPLE;
        }
        List<Map<String, Object>> cases;
        try {
            cases = mapper.readValue(mapper.readTree(content).path("cases").toString(), List.class);
        } catch (Exception e) {
            return Map.of("ok", false, "error", "评测集 JSON 格式错误: " + e.getMessage());
        }
        List<Map<String, Object>> details = new ArrayList<>();
        double sumPrecision = 0, sumRecall = 0;
        int evaluated = 0;
        for (Map<String, Object> c : cases) {
            Map<String, Object> expected = (Map<String, Object>) c.get("expected");
            String code = String.valueOf(c.get("code"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", c.get("name"));
            try {
                String prompt = "代码片段：\n" + code + "\n\n只输出 JSON：{\"path\":\"...\",\"method\":\"...\",\"params\":[...],\"tables\":[...]}";
                String text = llm.chat("extract", prompt).text();
                int s = text.indexOf('{'), e2 = text.lastIndexOf('}');
                if (s < 0 || e2 <= s) throw new IllegalStateException("未返回 JSON");
                Map<String, Object> actual = mapper.readValue(text.substring(s, e2 + 1), Map.class);
                row.put("actual", actual);

                double p = score(actual.get("path"), expected.get("path"));
                double m = score(actual.get("method"), expected.get("method"));
                double pr = setRecall((List<?>) actual.getOrDefault("params", List.of()), (List<?>) expected.getOrDefault("params", List.of()));
                double tr = setRecall((List<?>) actual.getOrDefault("tables", List.of()), (List<?>) expected.getOrDefault("tables", List.of()));
                double precision = (p + m) / 2.0;
                double recall = (m + pr + tr) / 3.0;
                row.put("precision", Math.round(precision * 100));
                row.put("recall", Math.round(recall * 100));
                sumPrecision += precision;
                sumRecall += recall;
            } catch (Exception e) {
                row.put("error", e.getMessage());
                row.put("precision", 0);
                row.put("recall", 0);
            }
            evaluated++;
            details.add(row);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("evaluated", evaluated);
        report.put("avgPrecision", evaluated == 0 ? 0 : Math.round(sumPrecision / evaluated * 100));
        report.put("avgRecall", evaluated == 0 ? 0 : Math.round(sumRecall / evaluated * 100));
        report.put("recommendation", (evaluated == 0 ? 0 : sumRecall / evaluated) >= 0.85
                ? "召回达标：允许内网自动草稿入库" : "召回不足 85%：建议仅降级摘要，禁止自动草稿");
        jdbc.update("INSERT INTO benchmark_runs(source, report) VALUES (?,?)", source,
                mapper.writeValueAsString(Map.of("report", report, "details", details)));
        jdbc.update("INSERT INTO settings(key, value) VALUES ('llm_benchmark_recall', ?) " +
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value", String.valueOf(report.get("avgRecall")));
        jdbc.update("INSERT INTO audit_logs(actor, action, target, detail) VALUES (?,?,?,?)",
                com.preflight.common.UserContext.username(), "llm.benchmark",
                "source=" + source, "recall=" + report.get("avgRecall") + "%");
        return Map.of("ok", true, "source", source, "report", report, "details", details);
    }

    private double score(Object actual, Object expected) {
        return String.valueOf(actual == null ? "" : actual)
                .equalsIgnoreCase(String.valueOf(expected == null ? "" : expected)) ? 1.0 : 0.0;
    }

    private double setRecall(List<?> actual, List<?> expected) {
        Set<String> a = new HashSet<>();
        for (Object o : actual) a.add(String.valueOf(o).toLowerCase());
        if (expected == null || expected.isEmpty()) return 1.0;
        long hit = expected.stream().filter(e -> a.contains(String.valueOf(e).toLowerCase())).count();
        return (double) hit / expected.size();
    }
}
