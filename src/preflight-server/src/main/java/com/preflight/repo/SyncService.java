package com.preflight.repo;

import com.preflight.common.AuditService;
import com.preflight.common.CryptoService;
import com.preflight.config.AppProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 同步服务：按 vcs_type 路由到 Git/SVN 客户端；更新点（提交/版本）全部入库不可覆盖；
 * 文件级改动 + patch 快照落盘；启发式风险分级；自动同步定时任务。
 */
@Service
public class SyncService {

    private final JdbcTemplate jdbc;
    private final RepoClient git;
    private final RepoClient svn;
    private final PatchStore patches;
    private final AuditService audit;
    private final AppProperties props;
    private long lastRunAt = 0;

    public SyncService(JdbcTemplate jdbc,
                       @org.springframework.beans.factory.annotation.Qualifier("gitRepoClient") RepoClient git,
                       @org.springframework.beans.factory.annotation.Qualifier("svnRepoClient") RepoClient svn,
                       PatchStore patches, AuditService audit, AppProperties props, CryptoService crypto) {
        this.jdbc = jdbc;
        this.git = git;
        this.svn = svn;
        this.patches = patches;
        this.audit = audit;
        this.props = props;
        // SVN 凭据桥接：凭据名 "<仓库名>-svn"，secret 格式 "user:password"
        SvnRepoClient.VaultBridge.register(name -> {
            try {
                String enc = jdbc.queryForObject(
                        "SELECT secret_enc FROM credentials WHERE name=?", String.class, name);
                return enc == null ? null : crypto.decrypt(enc);
            } catch (Exception e) {
                return null;
            }
        });
    }

    public record SyncReport(long repoId, String note, int newCommits, int newChanges, String lastRevision) {}

    public SyncReport syncRepo(long repoId) throws Exception {
        Map<String, Object> repoRow = jdbc.queryForMap("SELECT * FROM repos WHERE id=?", repoId);
        long projectId = ((Number) repoRow.get("project_id")).longValue();
        RepoClient.RepoRow row = new RepoClient.RepoRow(
                repoId, projectId, (String) repoRow.get("vcs_type"), (String) repoRow.get("repo_type"),
                (String) repoRow.get("name"), (String) repoRow.get("url"), (String) repoRow.get("local_path"),
                (String) repoRow.get("branch"), (String) repoRow.get("vcs_revision"));
        RepoClient client = "svn".equals(row.vcsType()) ? svn : git;
        if (!"svn".equals(row.vcsType()) && row.localPath() != null
                && !java.nio.file.Files.exists(java.nio.file.Path.of(row.localPath()).resolve(".git"))) {
            throw new IllegalStateException("该目录不是 Git 工作副本（未找到 .git）：【学习】可直接扫描本地文件，同步仅对 Git/SVN 工作副本有效");
        }
        RepoClient.SyncResult result = client.sync(row);

        int newCommits = 0, newChanges = 0;
        String lastRevision = row.vcsRevision();
        for (RepoClient.Revision rev : result.revisions()) {
            Integer seen = jdbc.queryForObject("SELECT COUNT(*) FROM commits WHERE repo_id=? AND revision=?",
                    Integer.class, repoId, rev.revision());
            if (seen != null && seen > 0) continue;
            jdbc.update("INSERT INTO commits(repo_id, revision, author, message, committed_at, processed) VALUES (?,?,?,?,?,0)",
                    repoId, rev.revision(), rev.author(), rev.message(), rev.committedAt());
            long commitId = jdbc.queryForObject(
                    "SELECT id FROM commits WHERE repo_id=? AND revision=?", Long.class, repoId, rev.revision());
            String commitRisk = "low";
            for (RepoClient.FileDiff fd : rev.changes()) {
                String risk = riskOf(fd.path());
                if (riskLevel(risk) > riskLevel(commitRisk)) commitRisk = risk;
                String patchRel = null;
                if (fd.patch() != null) {
                    patchRel = patches.write(projectId, commitId, rev.revision(), fd.path(), fd.patch());
                }
                String[] cm = classify(fd.path());
                jdbc.update("INSERT OR IGNORE INTO file_changes(commit_id, path, change_type, patch_file, risk_level, category, suggested_mode) VALUES (?,?,?,?,?,?,?)",
                        commitId, fd.path(), String.valueOf(fd.type()), patchRel, risk, cm[0], cm[1]);
                newChanges++;
            }
            jdbc.update("UPDATE commits SET risk_level=? WHERE id=?", commitRisk, commitId);
            newCommits++;
            lastRevision = rev.revision();
        }
        if (lastRevision != null) {
            jdbc.update("UPDATE repos SET vcs_revision=?, last_synced_at=datetime('now','localtime') WHERE id=?",
                    lastRevision, repoId);
        }
        audit.log("repo.sync", "repo#" + repoId, "newCommits=" + newCommits + ", newChanges=" + newChanges + ", " + result.note());
        return new SyncReport(repoId, result.note(), newCommits, newChanges, lastRevision);
    }

    public List<SyncReport> syncAll() {
        List<SyncReport> reports = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT id FROM repos WHERE watch_enabled=1")) {
            try {
                reports.add(syncRepo(((Number) r.get("id")).longValue()));
            } catch (Exception e) {
                reports.add(new SyncReport(((Number) r.get("id")).longValue(), "同步失败: " + e.getMessage(), 0, 0, null));
            }
        }
        return reports;
    }

    /** R12 改动五分类 + 建议测法（02 §3.6）：SQL/DDL→db，Java→http，前端→摘要为主，其他→人工判断 */
    private String[] classify(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".sql")) return new String[]{"ddl", "db"};
        if (p.contains("mapper") || p.endsWith(".xml")) return new String[]{"sql", "db"};
        if (p.endsWith(".java")) return new String[]{"api", "http"};
        if (p.endsWith(".vue") || p.endsWith(".js") || p.endsWith(".ts") || p.endsWith(".html")
                || p.endsWith(".css") || p.contains("static") || p.contains("webcontent")
                || p.contains("webcontent")) return new String[]{"frontend", "http"};
        return new String[]{"config", "none"};
    }

    /** 启发式风险：DDL 高，Java 业务代码高，mapper/SQL 中，其余低 */
    private String riskOf(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".sql")) return "high";
        if (p.endsWith(".java")) return "high";
        if (p.contains("mapper") || p.endsWith(".xml")) return "medium";
        return "low";
    }

    private int riskLevel(String r) {
        return switch (r) {
            case "high" -> 2;
            case "medium" -> 1;
            default -> 0;
        };
    }

    /** 自动同步：每 30s 醒来一次，按 preflight.sync-interval-ms 节流（0 = 关闭） */
    @Scheduled(fixedDelay = 30_000)
    public void scheduledSyncAll() {
        long interval = props.getSyncIntervalMs();
        if (interval <= 0) return;
        long now = System.currentTimeMillis();
        if (now - lastRunAt < interval) return;
        lastRunAt = now;
        try {
            syncAll();
        } catch (Exception e) {
            audit.log("repo.sync", "scheduled", "失败: " + e.getMessage());
        }
    }
}
