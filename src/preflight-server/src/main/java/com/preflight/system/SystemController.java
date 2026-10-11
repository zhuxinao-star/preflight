package com.preflight.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统级 API：知识包导出/导入、全局检索、看板统计、审计日志、设置与通知、LLM 降级模式。
 */
@RestController
@RequestMapping("/api")
public class SystemController {

    private final JdbcTemplate jdbc;
    private final ExportService exportService;
    private final ObjectMapper mapper;
    private final com.preflight.common.ProgressRegistry progress;

    public SystemController(JdbcTemplate jdbc, ExportService exportService, ObjectMapper mapper,
                            com.preflight.common.ProgressRegistry progress) {
        this.jdbc = jdbc;
        this.exportService = exportService;
        this.mapper = mapper;
        this.progress = progress;
    }

    /**
     * 第 20 轮：长任务进度查询。前端点下去后按秒轮询这里，把"3/12 条用例"这类真实阶段写到按钮上。
     * 没登记过（该按钮不是长任务、或者服务刚重启）返回 found:false，前端自动退回秒表显示，不会卡住。
     */
    @GetMapping("/progress")
    public Map<String, Object> progress(@RequestParam(required = false) String id) {
        com.preflight.common.ProgressRegistry.Entry e = progress.get(id);
        if (e == null) return Map.of("ok", true, "found", false);
        return Map.of("ok", true, "found", true, "label", e.label(), "total", e.total(),
                "done", e.done(), "note", e.note(),
                "elapsedMs", System.currentTimeMillis() - e.startMs(), "finished", e.finished());
    }

    // ---------- 知识包 ----------
    @GetMapping("/knowledge/export")
    public ResponseEntity<byte[]> export(@RequestParam long projectId) throws Exception {
        byte[] zip = exportService.exportProject(projectId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=preflight-knowledge-" + projectId + ".zip")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(zip);
    }

    @PostMapping("/knowledge/import")
    public Map<String, Object> importPack(@RequestParam("file") MultipartFile file) throws Exception {
        return exportService.importProject(file);
    }

    // ---------- 全局检索（R-搜索返工：子串包含 + 编号 token 前缀，废除字符重叠启发式） ----------
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String q) {
        if (q == null || q.isBlank()) return Map.of("ok", true, "results", List.of());
        String needle = q.toLowerCase().trim();
        List<Map<String, Object>> primary = new java.util.ArrayList<>();   // 子串包含（最相关）
        List<Map<String, Object>> secondary = new java.util.ArrayList<>(); // token 前缀（编号类渐进提示）

        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT i.*, p.name project_name FROM interfaces i JOIN projects p ON p.id=i.project_id")) {
            String hay = (r.get("name") + " " + r.get("path") + " " + r.get("http_method")).toLowerCase();
            int rank = rankOf(hay, needle);
            if (rank == 1) primary.add(withKind(r, "接口"));
            else if (rank == 2) secondary.add(withKind(r, "接口"));
        }
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM db_objects")) {
            String hay = (r.get("name") + " " + r.get("obj_type") + " " + (r.get("tables_involved") == null ? "" : r.get("tables_involved"))).toLowerCase();
            int rank = rankOf(hay, needle);
            if (rank == 1) primary.add(withKind(r, "SQL/数据对象"));
            else if (rank == 2) secondary.add(withKind(r, "SQL/数据对象"));
        }
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM test_cases")) {
            String hay = (r.get("name") + " " + r.get("scenario")).toLowerCase();
            int rank = rankOf(hay, needle);
            if (rank == 1) primary.add(withKind(r, "用例"));
            else if (rank == 2) secondary.add(withKind(r, "用例"));
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>(primary);
        out.addAll(secondary);
        return Map.of("ok", true, "results", out.size() > 30 ? out.subList(0, 30) : out);
    }

    /** 1=子串包含；2=字母数字 token 以搜索词为前缀（hf1000961 命中 hf100096）；0=不相关 */
    private int rankOf(String hay, String needle) {
        if (hay.contains(needle)) return 1;
        for (String tok : hay.split("[^a-z0-9]+")) {
            if (!tok.isEmpty() && tok.startsWith(needle)) return 2;
        }
        return 0;
    }

    private Map<String, Object> withKind(Map<String, Object> r, String kind) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        m.put("kind", kind);
        return m;
    }

    // ---------- 看板统计 ----------
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("projects", count("SELECT COUNT(*) FROM projects"));
        s.put("repos", count("SELECT COUNT(*) FROM repos"));
        s.put("interfaces", count("SELECT COUNT(*) FROM interfaces"));
        s.put("dbObjects", count("SELECT COUNT(*) FROM db_objects"));
        s.put("testCases", count("SELECT COUNT(*) FROM test_cases"));
        s.put("runs", count("SELECT COUNT(*) FROM test_runs"));
        // 第 20 轮口径：通过率只算挂了用例的执行。直测（case_id 为空）按用户拍板"只作标记"，
        // 混进来的话一次随手直测就能把全站通过率刷好看。
        s.put("runPass", count("SELECT COUNT(*) FROM test_runs WHERE status='pass' AND case_id IS NOT NULL"));
        s.put("runFail", count("SELECT COUNT(*) FROM test_runs WHERE status='fail' AND case_id IS NOT NULL"));
        s.put("runDirect", count("SELECT COUNT(*) FROM test_runs WHERE case_id IS NULL"));
        s.put("commits", count("SELECT COUNT(*) FROM commits"));
        long totalChanges = count("SELECT COUNT(*) FROM file_changes");
        s.put("changes", totalChanges);
        long tested = count("SELECT COUNT(*) FROM file_changes WHERE test_status IN ('tested','partial')");
        s.put("tested", tested);
        s.put("untested", count("SELECT COUNT(*) FROM file_changes WHERE test_status='untested'"));
        s.put("skipped", count("SELECT COUNT(*) FROM file_changes WHERE test_status='skipped'"));
        s.put("llmCache", count("SELECT COUNT(*) FROM llm_cache"));
        s.put("coverage", totalChanges == 0 ? 0 : Math.round(100.0 * tested / totalChanges));
        return Map.of("ok", true, "stats", s);
    }

    private long count(String sql) {
        Long v = jdbc.queryForObject(sql, Long.class);
        return v == null ? 0 : v;
    }

    // ---------- 审计 ----------
    @GetMapping("/audit-logs")
    public Map<String, Object> audit(@RequestParam(defaultValue = "100") int limit) {
        return Map.of("ok", true, "logs", jdbc.queryForList(
                "SELECT * FROM audit_logs ORDER BY id DESC LIMIT ?", Math.min(limit, 500)));
    }

    // ---------- 设置 / 通知 ----------
    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return Map.of("ok", true, "settings", jdbc.queryForList("SELECT * FROM settings"));
    }

    @PostMapping("/settings")
    public Map<String, Object> saveSetting(@RequestBody Map<String, String> body) {
        jdbc.update("INSERT INTO settings(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                body.get("key"), body.get("value"));
        return Map.of("ok", true);
    }

    /** 内置默认值：界面上的【填入默认】读这里，前端不抄一份迟早会过期的副本 */
    @GetMapping("/settings/defaults")
    public Map<String, Object> settingDefaults() {
        return Map.of("ok", true, "defaults", Map.of(
                com.preflight.exec.BizErrorScanner.SETTINGS_KEY, com.preflight.exec.BizErrorScanner.DEFAULT_WHITELIST));
    }

    /** 通用 Webhook 通知（钉钉/企业微信机器人兼容文本格式） */
    @PostMapping("/notify/test")
    public Map<String, Object> notifyTest(@RequestBody(required = false) Map<String, Object> body) {
        String url = settingValue("notify_webhook_url");
        if (url == null || url.isBlank()) {
            return Map.of("ok", false, "error", "未配置 notify_webhook_url（系统设置中可配）");
        }
        String text = String.valueOf(body == null ? "PreFlight 测试通知" : body.getOrDefault("text", "PreFlight 测试通知"));
        try {
            Map<String, Object> payload = Map.of("msgtype", "text", "text", Map.of("content", "[PreFlight] " + text));
            org.springframework.web.client.RestClient.create()
                    .post().uri(url)
                    .header("Content-Type", "application/json")
                    .body(payload)
                    .retrieve().toBodilessEntity();
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    private String settingValue(String key) {
        try {
            return jdbc.queryForObject("SELECT value FROM settings WHERE key=?", String.class, key);
        } catch (Exception e) {
            return null;
        }
    }
}
