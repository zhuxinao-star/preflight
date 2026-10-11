package com.preflight.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.preflight.common.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 存量资产导入冷启动（04-补齐 §4.3）：
 * OpenAPI/Swagger → 接口卡草稿；Postman Collection / HAR → "真实参数"用例草稿。
 * 全部走既有知识管道（origin=import），人确认后生效。
 */
@RestController
@RequestMapping("/api/import")
public class ImportController {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditService audit;

    public ImportController(JdbcTemplate jdbc, ObjectMapper mapper, AuditService audit) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.audit = audit;
    }

    /** OpenAPI/Swagger JSON → 接口卡草稿 */
    @PostMapping("/openapi")
    public Map<String, Object> openapi(@RequestBody Map<String, Object> body) {
        try {
            long projectId = ((Number) body.get("projectId")).longValue();
            JsonNode spec = mapper.readTree(mapper.writeValueAsString(body.get("content")));
            JsonNode paths = spec.path("paths");
            int created = 0;
            for (var pathIt = paths.fields(); pathIt.hasNext(); ) {
                var pathEntry = pathIt.next();
                String path = pathEntry.getKey();
                for (var mIt = pathEntry.getValue().fields(); mIt.hasNext(); ) {
                    var mEntry = mIt.next();
                    String method = mEntry.getKey().toUpperCase();
                    if (!List.of("GET", "POST", "PUT", "DELETE", "PATCH").contains(method)) continue;
                    JsonNode op = mEntry.getValue();
                    String name = op.path("summary").asText(op.path("operationId").asText(path));
                    String schema = buildOpenApiParams(op);
                    if (upsertInterface(projectId, name, method, path, schema, "openapi") > 0) created++;
                }
            }
            audit.log("import.openapi", "project#" + projectId, "interfaces=" + created);
            return Map.of("ok", true, "created", created);
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    private String buildOpenApiParams(JsonNode op) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (JsonNode p : op.path("parameters")) {
            Map<String, Object> ps = new LinkedHashMap<>();
            ps.put("type", p.path("schema").path("type").asText(p.path("type").asText("string")));
            ps.put("in", p.path("in").asText("query"));
            if (p.has("default")) ps.put("default", p.get("default").asText());
            props.put(p.path("name").asText(), ps);
            if (p.path("required").asBoolean()) required.add(p.path("name").asText());
        }
        JsonNode rb = op.path("requestBody").path("content").path("application/json").path("schema");
        if (!rb.isMissingNode()) {
            Map<String, Object> ps = new LinkedHashMap<>();
            ps.put("type", "object");
            ps.put("in", "body");
            props.put("body", ps);
            required.add("body");
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", required);
        try {
            return mapper.writeValueAsString(schema);
        } catch (Exception e) {
            return null;
        }
    }

    /** Postman Collection v2.x → 用例草稿（真实参数） */
    @PostMapping("/postman")
    public Map<String, Object> postman(@RequestBody Map<String, Object> body) {
        try {
            long projectId = ((Number) body.get("projectId")).longValue();
            JsonNode root = mapper.readTree(mapper.writeValueAsString(body.get("content")));
            int[] created = new int[1];
            walkPostman(root.path("item"), projectId, created);
            audit.log("import.postman", "project#" + projectId, "cases=" + created[0]);
            return Map.of("ok", true, "created", created[0]);
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private void walkPostman(JsonNode items, long projectId, int[] created) throws Exception {
        for (JsonNode item : items) {
            if (item.has("item")) {
                walkPostman(item.path("item"), projectId, created);
                continue;
            }
            JsonNode req = item.path("request");
            if (req.isMissingNode()) continue;
            String method = req.path("method").asText("GET").toUpperCase();
            String rawUrl = req.path("url").isTextual() ? req.path("url").asText()
                    : req.path("url").path("raw").asText("");
            String path = extractPath(rawUrl);
            if (path.isBlank()) continue;
            long ifcId = ensureInterface(projectId, item.path("name").asText(path), method, path);
            Map<String, Object> params = buildCaseParams(req, rawUrl);
            insertCase(ifcId, item.path("name").asText("导入用例"), params);
            created[0]++;
        }
    }

    /** HAR（浏览器真实流量）→ 用例草稿，可按域名过滤 */
    @PostMapping("/har")
    public Map<String, Object> har(@RequestBody Map<String, Object> body) {
        try {
            long projectId = ((Number) body.get("projectId")).longValue();
            String domainFilter = body.get("domain") == null ? "" : String.valueOf(body.get("domain"));
            JsonNode root = mapper.readTree(mapper.writeValueAsString(body.get("content")));
            int[] created = new int[1];
            for (JsonNode entry : root.path("log").path("entries")) {
                JsonNode req = entry.path("request");
                String url = req.path("url").asText("");
                if (!domainFilter.isBlank() && !url.contains(domainFilter)) continue;
                String method = req.path("method").asText("GET").toUpperCase();
                String path = extractPath(url);
                if (path.isBlank()) continue;
                long ifcId = ensureInterface(projectId, path, method, path);
                Map<String, Object> params = buildCaseParams(req, url);
                insertCase(ifcId, "HAR·" + path, params);
                created[0]++;
            }
            audit.log("import.har", "project#" + projectId, "cases=" + created[0]);
            return Map.of("ok", true, "created", created[0]);
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    private String extractPath(String rawUrl) {
        // 不用 URI.create：Postman/HAR 里常见 {id}、:var 占位符会抛异常导致整条静默丢失
        String u = rawUrl == null ? "" : rawUrl.trim();
        int si = u.indexOf("://");
        if (si > 0) {
            int p = u.indexOf('/', si + 3);
            u = p < 0 ? "/" : u.substring(p);
        }
        int frag = u.indexOf('#');
        if (frag >= 0) u = u.substring(0, frag);
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        if (u.isEmpty()) u = "/";
        return u.startsWith("/") ? u : "/" + u;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildCaseParams(JsonNode req, String rawUrl) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("path", Map.of());
        Map<String, Object> query = new LinkedHashMap<>();
        JsonNode qs = req.path("url").path("query");
        if (qs.isArray()) {
            for (JsonNode q : qs) query.put(q.path("name").asText(), q.path("value").asText(""));
        } else {
            try {
                int q = rawUrl.indexOf('?');
                if (q >= 0) {
                    String qs2 = rawUrl.substring(q + 1);
                    int h = qs2.indexOf('#');
                    if (h >= 0) qs2 = qs2.substring(0, h);
                    for (String kv : qs2.split("&")) {
                        int i = kv.indexOf('=');
                        if (i > 0) query.put(java.net.URLDecoder.decode(kv.substring(0, i), "UTF-8"),
                                java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
                    }
                }
            } catch (Exception ignore) {
            }
        }
        params.put("query", query);
        String postData = req.path("postData").path("text").asText("");
        Map<String, Object> body = new LinkedHashMap<>();
        if (!postData.isBlank()) body.put("body", mapper.readValue(postData, Object.class));
        params.put("body", body);
        return params;
    }

    private long ensureInterface(long projectId, String name, String method, String path) {
        Integer exist = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interfaces WHERE project_id=? AND http_method=? AND path=?",
                Integer.class, projectId, method, path);
        if (exist != null && exist > 0) {
            return jdbc.queryForObject("SELECT id FROM interfaces WHERE project_id=? AND http_method=? AND path=?",
                    Long.class, projectId, method, path);
        }
        jdbc.update("INSERT INTO interfaces(project_id, name, http_method, path, params_schema, source, confidence, status) " +
                        "VALUES (?,?,?,?,?,?,?,?)",
                projectId, name, method, path,
                "{\"type\":\"object\",\"properties\":{\"body\":{\"type\":\"object\",\"in\":\"body\"}},\"required\":[]}",
                toJson(List.of(Map.of("file", "import"))), 0.9, "draft");
        return jdbc.queryForObject("SELECT id FROM interfaces WHERE project_id=? AND http_method=? AND path=?",
                Long.class, projectId, method, path);
    }

    private void insertCase(long interfaceId, String name, Map<String, Object> params) throws Exception {
        Integer dup = jdbc.queryForObject(
                "SELECT COUNT(*) FROM test_cases WHERE target_type='interface' AND target_id=? AND name=?",
                Integer.class, interfaceId, name);
        if (dup != null && dup > 0) return;
        jdbc.update("INSERT INTO test_cases(target_type, target_id, name, scenario, params, expect, origin, status) VALUES (?,?,?,?,?,?,?,?)",
                "interface", interfaceId, name, "来自存量资产导入", mapper.writeValueAsString(params), "", "import", "pending");
    }

    private long upsertInterface(long projectId, String name, String method, String path, String schema, String srcTag) {
        Integer exist = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interfaces WHERE project_id=? AND http_method=? AND path=?",
                Integer.class, projectId, method, path);
        if (exist != null && exist > 0) return -1;
        jdbc.update("INSERT INTO interfaces(project_id, name, http_method, path, params_schema, source, confidence, status) VALUES (?,?,?,?,?,?,?,?)",
                projectId, name, method, path, schema, toJson(List.of(Map.of("file", srcTag))), 0.95, "draft");
        return 1;
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
