package com.preflight.exec;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 缺陷留痕（findings）：失败执行的轻量闭环，为质量看板"发现问题数"提供可靠数据源。 */
@RestController
@RequestMapping("/api/findings")
public class FindingsController {

    private final JdbcTemplate jdbc;

    public FindingsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @SuppressWarnings("unchecked")
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        Long runId = body.get("runId") == null ? null : ((Number) body.get("runId")).longValue();
        if (runId == null) return Map.of("ok", false, "error", "缺少 runId");
        Map<String, Object> run;
        try {
            run = jdbc.queryForMap("SELECT * FROM test_runs WHERE id=?", runId);
        } catch (Exception e) {
            return Map.of("ok", false, "error", "执行记录不存在");
        }
        Long projectId = null;
        try {
            if ("interface".equals(run.get("target_type"))) {
                projectId = jdbc.queryForObject("SELECT project_id FROM interfaces WHERE id=?",
                        Long.class, run.get("target_id"));
            } else {
                projectId = jdbc.queryForObject("SELECT project_id FROM db_objects WHERE id=?",
                        Long.class, run.get("target_id"));
            }
        } catch (Exception ignore) {
        }
        String title = body.get("title") != null ? String.valueOf(body.get("title")) :
                "用例执行失败 #" + runId;
        String severity = String.valueOf(body.getOrDefault("severity", "medium"));
        Long fileChangeId = null;
        if (run.get("commit_id") != null) {
            try {
                fileChangeId = jdbc.queryForObject(
                        "SELECT id FROM file_changes WHERE commit_id=? ORDER BY id LIMIT 1",
                        Long.class, run.get("commit_id"));
            } catch (Exception ignore) {
            }
        }
        jdbc.update("INSERT INTO findings(project_id, run_id, commit_id, file_change_id, title, severity, status, noted_by) VALUES (?,?,?,?,?,?,?,?)",
                projectId, runId, run.get("commit_id"), fileChangeId, title, severity, "open",
                com.preflight.common.UserContext.username());
        Long id = jdbc.queryForObject("SELECT id FROM findings WHERE run_id=? ORDER BY id DESC LIMIT 1", Long.class, runId);
        return Map.of("ok", true, "id", id);
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) Long projectId,
                                    @RequestParam(required = false) String status) {
        List<Map<String, Object>> rows;
        if (projectId != null && status != null) {
            rows = jdbc.queryForList("SELECT * FROM findings WHERE project_id=? AND status=? ORDER BY id DESC", projectId, status);
        } else if (projectId != null) {
            rows = jdbc.queryForList("SELECT * FROM findings WHERE project_id=? ORDER BY id DESC", projectId);
        } else if (status != null) {
            rows = jdbc.queryForList("SELECT * FROM findings WHERE status=? ORDER BY id DESC", status);
        } else {
            rows = jdbc.queryForList("SELECT * FROM findings ORDER BY id DESC LIMIT 200");
        }
        return Map.of("ok", true, "findings", rows);
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, String> body) {
        if (body.get("status") != null) {
            jdbc.update("UPDATE findings SET status=? WHERE id=?", body.get("status"), id);
        }
        if (body.get("severity") != null) {
            jdbc.update("UPDATE findings SET severity=? WHERE id=?", body.get("severity"), id);
        }
        return Map.of("ok", true);
    }
}
