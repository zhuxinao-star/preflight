package com.preflight.repo;

import com.preflight.common.AuditService;
import com.preflight.common.FileTexts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 按需补"某个改动文件的差异快照"：点开文件时才去仓库取两个版本比对，生成后落盘，下次直接读缓存。
 *
 * <p>为什么不在同步时全量生成：SVN 的增量 log 一条提交就能带 1.7 万个变更项（首次导入），
 * 逐项拉文件会把同步拖死，而其中绝大多数（jar / class / 图片）根本没有文本差异可看。
 *
 * <p>上下文行数（{@link UnifiedDiff#DEFAULT_CONTEXT} 起）是评审视角的开关而不是仓库属性，
 * 所以同一份改动允许按不同档位重算；每一档各存各的缓存文件，
 * 只有默认档回写 {@code file_changes.patch_file}——那一列有归因/摘要/出参/导出四个消费方，语义不能动。
 */
@Service
public class PatchFetchService {

    /** 整份视图给前端的行数上限：再多的文件先切前若干行，说明写在 note 里 */
    static final int LINE_CAP = 20_000;

    private final JdbcTemplate jdbc;
    private final FilePatchReader git;
    private final FilePatchReader svn;
    private final PatchStore store;
    private final AuditService audit;

    public PatchFetchService(JdbcTemplate jdbc,
                             @org.springframework.beans.factory.annotation.Qualifier("gitRepoClient") FilePatchReader git,
                             @org.springframework.beans.factory.annotation.Qualifier("svnRepoClient") FilePatchReader svn,
                             PatchStore store, AuditService audit) {
        this.jdbc = jdbc;
        this.git = git;
        this.svn = svn;
        this.store = store;
        this.audit = audit;
    }

    public record Fetched(boolean ok, String patch, String error, boolean cached, long commitId) {
        static Fetched fail(String error) {
            return new Fetched(false, null, error, false, 0);
        }
    }

    /** at=rev：本提交那一版的文件原文；at=work：本地工作副本里的当前文件 */
    public record FileView(boolean ok, String error, long fileChangeId, long commitId, String path, String revision,
                           String at, String charset, List<String> lines, List<int[]> marks,
                           int totalLines, boolean capped, String note) {
        static FileView fail(long fileChangeId, String error) {
            return new FileView(false, error, fileChangeId, 0, null, null, null, null, List.of(), List.of(), 0, false, null);
        }
    }

    public Fetched fetch(long fileChangeId) {
        return fetch(fileChangeId, UnifiedDiff.DEFAULT_CONTEXT);
    }

    /**
     * @param context 上下文行数；必须是 {@link UnifiedDiff#ALLOWED_CONTEXTS} 里的档位（调用方已校验）
     */
    public Fetched fetch(long fileChangeId, int context) {
        Row row = row(fileChangeId);
        if (row == null) return Fetched.fail("这条改动记录不存在（仓库或提交可能已被删除）");
        if (context == UnifiedDiff.DEFAULT_CONTEXT) {
            // 默认档先认历史列：老 data 目录里已有的快照不能因为换了命名口径就当没有
            String cached = readColumn(row);
            if (cached != null) return new Fetched(true, cached, null, true, row.commitId());
        }
        String cached = store.read(row.projectId(), row.commitId(), row.revision(), row.path(), context);
        if (cached != null) return new Fetched(true, cached, null, true, row.commitId());
        try {
            FilePatchReader client = "svn".equals(row.vcsType()) ? svn : git;
            String patch = PatchStore.truncate(
                    client.filePatch(row.repoRow(), row.revision(), row.path(), row.changeType(), context));
            String rel = store.write(row.projectId(), row.commitId(), row.revision(), row.path(), patch, context);
            if (context == UnifiedDiff.DEFAULT_CONTEXT) {
                jdbc.update("UPDATE file_changes SET patch_file=? WHERE id=?", rel, fileChangeId);
            }
            audit.log("repo.patch-fetch", "file_change#" + fileChangeId,
                    row.vcsType() + " " + row.revision() + " " + row.path()
                            + " 上下文" + context + " → " + patch.length() + " 字符");
            return new Fetched(true, patch, null, false, row.commitId());
        } catch (Exception e) {
            String why = message(e);
            audit.log("repo.patch-fetch", "file_change#" + fileChangeId, "失败: " + why);
            return Fetched.fail(why);
        }
    }

    /**
     * 改动文件的整份内容 + 行级改动标记（评审要"看得见整体业务"，只有 diff 的几行上下文不够）。
     *
     * <p>标记口径复用 {@link PatchRanges}——与语句归因同一个单点，界面上的行号才不会和归因结论各说各话。
     */
    public FileView file(long fileChangeId, String at) {
        Row row = row(fileChangeId);
        if (row == null) return FileView.fail(fileChangeId, "这条改动记录不存在（仓库或提交可能已被删除）");
        boolean work = "work".equals(at);
        byte[] bytes;
        String note = null;
        try {
            bytes = work ? readWorkCopy(row) : readRevision(row, row.changeType() == 'D' ? 'P' : 'N');
        } catch (Exception e) {
            return FileView.fail(fileChangeId, message(e));
        }
        if (bytes == null) {
            return FileView.fail(fileChangeId, work
                    ? "工作副本里找不到这个文件：" + row.path() + "（可能被后续提交删除或改名，切【本提交那一版】看当时是什么样）"
                    : (row.changeType() == 'A'
                        ? "这个提交之前还没有这个文件（新增项），要看的是【本提交那一版】"
                        : "仓库里这个版本没有该文件内容：" + row.path()));
        }
        // 二进制判据放在这条读路径的出口而不是两个客户端里：工作副本是直接读盘的，
        // 少了这一道，同一个 .class 在【本提交那一版】被拒绝、切到【本地工作副本】就会解出一屏乱码冒充源码。
        if (FileTexts.binary(bytes)) {
            return FileView.fail(fileChangeId, FileTexts.binaryFile(row.path()));
        }
        FileTexts.Decoded decoded = FileTexts.decode(bytes);
        String[] all = FileTexts.lines(decoded.text());
        List<int[]> marks = List.of();
        String patch = cachedPatch(row);
        if (patch == null) {
            note = append(note, "改动行还没标出来：这条改动尚无差异快照，在上面的 diff 处选一档上下文即可生成，生成后这里就有标记。");
        } else {
            marks = PatchRanges.newSideRanges(patch);
            if (PatchStore.isTruncated(patch)) {
                note = append(note, "差异快照超过 200k 字符被截断，靠后的改动行可能没标上——按区间翻查或看后面的 hunk。");
            }
        }
        if (work) {
            note = append(note, "工作副本＝磁盘上的当前文件；标记按本提交那一版的行号给出，后续提交再动过这个文件就会错位。");
        } else {
            note = append(note, "本提交那一版的原文（绿色行＝这次新增或改过的行）。");
        }
        if (row.changeType() == 'D') {   // 删掉的行不在这份内容里，行级标记没有意义
            note = append(note, work
                    ? "本提交删除了该文件，工作副本里的同名文件是后续提交重新加回来的。"
                    : "本提交删除了该文件：上面是删除前的最后一版（上一版内容），整份都被删，所以没有行级标记。");
            marks = List.of();
        }
        int total = all.length;
        boolean capped = total > LINE_CAP;
        List<String> lines = new ArrayList<>(capped ? LINE_CAP : total);
        for (int i = 0; i < (capped ? LINE_CAP : total); i++) lines.add(all[i]);
        if (capped) {
            note = append(note, "文件共 " + total + " 行，只送出前 " + LINE_CAP + " 行（再多前端就卡了）。");
        }
        return new FileView(true, null, fileChangeId, row.commitId(), row.path(), row.revision(),
                work ? "work" : "rev", decoded.charset(), lines, marks, total, capped, note);
    }

    /** 工作副本读文件：登记目录为基准，前导斜杠两种形式各认一次 */
    private byte[] readWorkCopy(Row row) throws Exception {
        String local = row.repoRow().localPath();
        if (local == null || local.isBlank()) {
            throw new IllegalStateException("该仓库没有登记本地工作副本路径，看不了当前文件，只能看【本提交那一版】");
        }
        Path dir = Path.of(local).toAbsolutePath().normalize();
        String rel = row.path().startsWith("/") ? row.path().substring(1) : row.path();
        Path p = dir.resolve(rel);
        if (!Files.exists(p)) {
            throw new IllegalStateException("工作副本里找不到：" + p + "（可能被后续提交删除/改名，或这份副本没更新到最新版本）");
        }
        if (Files.isDirectory(p)) throw new IllegalStateException("变更清单里这一项是目录，没有文件内容可看：" + rel);
        long size = Files.size(p);
        if (size > 8L * 1024 * 1024) {
            throw new IllegalStateException("文件 " + (size / 1024) + " KB，超过 8192 KB 上限，不整份读取：" + rel);
        }
        return Files.readAllBytes(p);
    }

    private byte[] readRevision(Row row, char side) throws Exception {
        return ("svn".equals(row.vcsType()) ? svn : git).readVersion(row.repoRow(), row.revision(), row.path(), side);
    }

    /** 标记来自默认档快照：列在就用，落盘缓存在就用；都没有就返回 null（由调用方说清"为什么没标记"） */
    private String cachedPatch(Row row) {
        String byColumn = readColumn(row);
        return byColumn != null ? byColumn : store.read(row.projectId(), row.commitId(), row.revision(), row.path(),
                UnifiedDiff.DEFAULT_CONTEXT);
    }

    private String readColumn(Row row) {
        if (row.patchFile() == null) return null;
        try {
            return Files.readString(Path.of(row.patchFile()));
        } catch (Exception e) {
            return null;   // 路径在、文件不在盘上：往下按规则重算
        }
    }

    private Row row(long fileChangeId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT fc.id, fc.path, fc.change_type, fc.patch_file,
                       cm.id AS commit_id, cm.revision,
                       r.id AS repo_id, r.project_id, r.vcs_type, r.name, r.url, r.local_path, r.branch, r.vcs_revision
                FROM file_changes fc
                  JOIN commits cm ON cm.id = fc.commit_id
                  JOIN repos r ON r.id = cm.repo_id
                WHERE fc.id = ?""", fileChangeId);
        if (rows.isEmpty()) return null;
        Map<String, Object> m = rows.get(0);
        return new Row(m);
    }

    private static String message(Exception e) {
        String why = e.getMessage();
        return why == null || why.isBlank() ? e.getClass().getSimpleName() : why;
    }

    private static String append(String note, String add) {
        return note == null || note.isBlank() ? add : note + " " + add;
    }

    /** 一次改动 + 它所属提交 + 它所在仓库：两个入口共用的读取结果 */
    private record Row(Map<String, Object> m) {
        long commitId() {
            return ((Number) m.get("commit_id")).longValue();
        }
        long projectId() {
            return ((Number) m.get("project_id")).longValue();
        }
        String revision() {
            return String.valueOf(m.get("revision"));
        }
        String path() {
            return String.valueOf(m.get("path"));
        }
        String vcsType() {
            return (String) m.get("vcs_type");
        }
        String patchFile() {
            return m.get("patch_file") == null ? null : String.valueOf(m.get("patch_file"));
        }
        char changeType() {
            String type = m.get("change_type") == null ? "M" : String.valueOf(m.get("change_type"));
            return type.isEmpty() ? 'M' : type.charAt(0);
        }
        RepoClient.RepoRow repoRow() {
            return new RepoClient.RepoRow(
                    ((Number) m.get("repo_id")).longValue(), projectId(),
                    vcsType(), null, (String) m.get("name"), (String) m.get("url"),
                    (String) m.get("local_path"), (String) m.get("branch"), (String) m.get("vcs_revision"));
        }
    }
}
