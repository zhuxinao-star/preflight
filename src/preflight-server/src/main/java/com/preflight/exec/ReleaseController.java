package com.preflight.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.preflight.common.AuditService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 起飞检查单（04-补齐 §4.1，PreFlight 的流程卡点形态）：
 * 发布范围 → 逐条改动的测试证据 → 未测项签字跳过 → 导出"敢不敢起飞"结论单（HTML/打印 PDF）。
 */
@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    /**
     * 一张表两种单据：release_checklists 里 kind='checklist' 是这里的起飞检查单，
     * kind='report' 是 {@link ReportService} 的提测结论单。<b>本类的每条读写都必须带 kind</b>——
     * 少一处就会把结论单混进老列表、甚至把签字写进结论单。
     */
    private static final String KIND = "checklist";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditService audit;

    public ReleaseController(JdbcTemplate jdbc, ObjectMapper mapper, AuditService audit) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.audit = audit;
    }

    private Long lastChecklistId() {
        return jdbc.queryForObject("SELECT id FROM release_checklists WHERE kind=? ORDER BY id DESC LIMIT 1",
                Long.class, KIND);
    }

    /** 只认本类的单据：不是 checklist 时返回 null，由调用方照实说清 */
    private Map<String, Object> checklist(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM release_checklists WHERE id=? AND kind=?", id, KIND);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 生成检查单：按提交 id 区间圈定范围（含端点） */
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        try {
            long projectId = ((Number) body.get("projectId")).longValue();
            long fromCommitId = ((Number) body.get("fromCommitId")).longValue();
            long toCommitId = ((Number) body.get("toCommitId")).longValue();
            if (fromCommitId > toCommitId) {
                long t = fromCommitId; fromCommitId = toCommitId; toCommitId = t;
            }
            final long lo = fromCommitId;
            final long hi = toCommitId;
            List<Map<String, Object>> commits = jdbc.queryForList("""
                    SELECT c.*, r.name AS repo_name FROM commits c JOIN repos r ON r.id=c.repo_id
                    WHERE r.project_id=? AND c.id BETWEEN ? AND ? ORDER BY c.id
                    """, projectId, fromCommitId, toCommitId);
            List<Map<String, Object>> snapshot = new ArrayList<>();
            for (Map<String, Object> commit : commits) {
                List<Map<String, Object>> changes = jdbc.queryForList(
                        "SELECT id, path, change_type, risk_level, test_status FROM file_changes WHERE commit_id=?",
                        commit.get("id"));
                for (Map<String, Object> ch : changes) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("commitId", commit.get("id"));
                    item.put("commitMessage", commit.get("message"));
                    item.put("fileChangeId", ch.get("id"));
                    item.put("path", ch.get("path"));
                    item.put("risk", ch.get("risk_level"));
                    item.put("testStatus", ch.get("test_status"));
                    item.put("openFindings", countFindings(commit.get("id")));
                    snapshot.add(item);
                }
            }
            String snapshotJson = mapper.writeValueAsString(snapshot);
            // 生成的 id 用驱动回给的自增键拿，不靠「最大 id 那条应该是我刚插的」——同表还有提测结论单在写
            org.springframework.jdbc.support.KeyHolder kh = new org.springframework.jdbc.support.GeneratedKeyHolder();
            jdbc.update(con -> {
                java.sql.PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO release_checklists(project_id, title, from_revision, to_revision, "
                                + "snapshot_json, signoffs_json, created_by, kind) VALUES (?,?,?,?,?,?,?,'checklist')",
                        java.sql.Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, projectId);
                ps.setString(2, String.valueOf(body.getOrDefault("title", "发布检查单")));
                ps.setString(3, String.valueOf(lo));
                ps.setString(4, String.valueOf(hi));
                ps.setString(5, snapshotJson);
                ps.setString(6, "[]");
                ps.setString(7, com.preflight.common.UserContext.username());
                return ps;
            }, kh);
            Long id = kh.getKey() == null ? lastChecklistId() : kh.getKey().longValue();
            audit.log("release.create", "project#" + projectId, "items=" + snapshot.size());
            return Map.of("ok", true, "id", id, "items", snapshot.size());
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    private long countFindings(Object commitId) {
        Long v = jdbc.queryForObject("SELECT COUNT(*) FROM findings WHERE commit_id=? AND status IN ('open','confirmed')",
                Long.class, commitId);
        return v == null ? 0 : v;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam long projectId) {
        return Map.of("ok", true, "releases", jdbc.queryForList(
                "SELECT id, project_id, title, from_revision, to_revision, created_by, created_at FROM release_checklists " +
                        "WHERE project_id=? AND kind=? ORDER BY id DESC", projectId, KIND));
    }

    /** 查看检查单（快照 + 实时签字记录 + 结论） */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        Map<String, Object> release = checklist(id);
        if (release == null) {
            return Map.of("ok", false, "error", "查不到 id=" + id + " 的起飞检查单"
                    + "（提测结论单是另一种单据，在「提测结论单」页看）");
        }
        List<Map<String, Object>> items;
        List<Map<String, Object>> signoffs;
        try {
            items = mapper.readValue((String) release.get("snapshot_json"), List.class);
            signoffs = mapper.readValue((String) release.get("signoffs_json"), List.class);
        } catch (Exception e) {
            items = List.of();
            signoffs = List.of();
        }
        // R15：签字合并回条目——已签字的项不再计入未处理
        java.util.Set<String> signedIds = new java.util.HashSet<>();
        for (Map<String, Object> sg : signoffs) {
            Object fid = sg.get("fileChangeId");
            if (fid != null) signedIds.add(String.valueOf(fid));
        }
        for (Map<String, Object> it : items) {
            it.put("signed", signedIds.contains(String.valueOf(it.get("fileChangeId"))));
        }
        long unhandled = items.stream().filter(i ->
                !"tested".equals(String.valueOf(i.get("testStatus")))
                        && !"skipped".equals(String.valueOf(i.get("testStatus")))
                        && !Boolean.TRUE.equals(i.get("signed"))).count();
        long openFindings = items.stream().filter(i -> ((Number) i.getOrDefault("openFindings", 0)).longValue() > 0).count();
        String verdict = unhandled == 0 && openFindings == 0 ? "READY" : "BLOCKED";
        return Map.of("ok", true, "release", release, "items", items, "signoffs", signoffs,
                "unhandled", unhandled, "openFindings", openFindings, "verdict", verdict);
    }

    /** 签字：通过 或 跳过（须填理由） */
    @PostMapping("/{id}/sign")
    public Map<String, Object> sign(@PathVariable long id, @RequestBody Map<String, Object> body) {
        try {
            Map<String, Object> release = checklist(id);
            if (release == null) return Map.of("ok", false, "error", "签不了：id=" + id + " 不是起飞检查单");
            List<Map<String, Object>> signoffs = mapper.readValue((String) release.get("signoffs_json"), List.class);
            Map<String, Object> sign = new LinkedHashMap<>();
            sign.put("fileChangeId", body.get("fileChangeId"));
            sign.put("decision", body.get("decision"));   // passed | skipped
            sign.put("reason", body.getOrDefault("reason", ""));
            sign.put("by", com.preflight.common.UserContext.username());
            sign.put("at", java.time.LocalDateTime.now().toString());
            signoffs.add(sign);
            // R15：跳过签字直接回写条目状态，结论随之更新
            if ("skipped".equals(sign.get("decision")) && body.get("fileChangeId") != null) {
                List<Map<String, Object>> items = mapper.readValue((String) release.get("snapshot_json"), List.class);
                for (Map<String, Object> it : items) {
                    if (String.valueOf(it.get("fileChangeId")).equals(String.valueOf(body.get("fileChangeId")))) {
                        it.put("testStatus", "skipped");
                    }
                }
                jdbc.update("UPDATE release_checklists SET snapshot_json=?, signoffs_json=? WHERE id=? AND kind=?",
                        mapper.writeValueAsString(items), mapper.writeValueAsString(signoffs), id, KIND);
            } else {
                jdbc.update("UPDATE release_checklists SET signoffs_json=? WHERE id=? AND kind=?",
                        mapper.writeValueAsString(signoffs), id, KIND);
            }
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    /** 导出 HTML 结论单（打印即 PDF） */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> exportHtml(@PathVariable long id) throws Exception {
        Map<String, Object> d = detail(id);
        if (!Boolean.TRUE.equals(d.get("ok"))) {
            return ResponseEntity.badRequest().body(String.valueOf(d.get("error")).getBytes(StandardCharsets.UTF_8));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> release = (Map<String, Object>) d.get("release");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) d.get("items");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> signoffs = (List<Map<String, Object>>) d.get("signoffs");
        String verdict = String.valueOf(d.get("verdict"));

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><title>").append(esc(release.get("title")))
                .append("</title><style>body{font-family:'Microsoft YaHei',sans-serif;margin:40px;color:#1e293b}")
                .append("h1{border-bottom:3px solid #4f46e5;padding-bottom:8px}table{border-collapse:collapse;width:100%}")
                .append("th,td{border:1px solid #cbd5e1;padding:6px 10px;font-size:13px;text-align:left}")
                .append(".ready{color:#059669;font-size:22px;font-weight:700}.blocked{color:#dc2626;font-size:22px;font-weight:700}</style></head><body>");
        html.append("<h1>🛫 ").append(esc(release.get("title"))).append(" · 起飞检查单</h1>");
        html.append("<p>生成时间：").append(java.time.LocalDateTime.now())
                .append(" ｜ 签发人：").append(esc(com.preflight.common.UserContext.username())).append("</p>");
        html.append("<p>结论：<span class='").append("READY".equals(verdict) ? "ready" : "blocked").append("'>")
                .append("READY".equals(verdict) ? "✅ 可以起飞" : "⛔ 存在未处理项，不建议起飞").append("</span></p>");
        html.append("<table><tr><th>提交</th><th>文件</th><th>风险</th><th>测试状态</th><th>未闭环缺陷</th></tr>");
        for (Map<String, Object> i : items) {
            html.append("<tr><td>").append(esc(i.get("commitMessage"))).append("</td><td>").append(esc(i.get("path")))
                    .append("</td><td>").append(esc(i.get("risk"))).append("</td><td>").append(esc(i.get("testStatus")))
                    .append("</td><td>").append(esc(i.get("openFindings"))).append("</td></tr>");
        }
        html.append("</table><h3>签字记录</h3><table><tr><th>文件</th><th>决定</th><th>理由</th><th>签字人</th><th>时间</th></tr>");
        for (Map<String, Object> s : signoffs) {
            html.append("<tr><td>").append(esc(s.get("fileChangeId"))).append("</td><td>").append(esc(s.get("decision")))
                    .append("</td><td>").append(esc(s.get("reason"))).append("</td><td>").append(esc(s.get("by")))
                    .append("</td><td>").append(esc(s.get("at"))).append("</td></tr>");
        }
        html.append("</table></body></html>");

        audit.log("release.export", "checklist#" + id, verdict);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=release-checklist-" + id + ".html")
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .body(html.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String esc(Object o) {
        return String.valueOf(o == null ? "" : o).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
