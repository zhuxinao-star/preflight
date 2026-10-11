package com.preflight.repo;

import com.preflight.common.FileTexts;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.api.Git;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Git 实现：JGit fetch + log + 逐文件 diff（纯 Java，无需目标机安装 git）。 */
@Component
public class GitRepoClient implements RepoClient, FilePatchReader {

    private static final int MAX_NEW_REVISIONS = 50;

    /** 整份读取的上限（与 SVN 通道、语句归因读历史版同值） */
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;

    /** 点开某个文件时才补快照（正常同步已生成，这里是磁盘文件丢了/根提交这类漏网的） */
    @Override
    public String filePatch(RepoClient.RepoRow repo, String revision, String path, char changeType, int context) throws Exception {
        Repository repository = open(repo);
        try (org.eclipse.jgit.revwalk.RevWalk rw = new org.eclipse.jgit.revwalk.RevWalk(repository);
             DiffFormatter scanner = new DiffFormatter(org.eclipse.jgit.util.io.DisabledOutputStream.INSTANCE);
             ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DiffFormatter fmt = new DiffFormatter(bos)) {
            org.eclipse.jgit.lib.ObjectId id = repository.resolve(revision);
            if (id == null) throw new IllegalStateException("在这个 Git 库里找不到该提交：" + revision);
            RevCommit commit = rw.parseCommit(id);
            if (commit.getParentCount() == 0) {
                throw new IllegalStateException("这是仓库的第一个提交（首次导入），没有上一版可比对，只能看文件本身");
            }
            RevCommit parent = rw.parseCommit(commit.getParent(0).getId());
            scanner.setRepository(repository);
            scanner.setDetectRenames(true);
            fmt.setRepository(repository);
            fmt.setContext(context);   // 上下文是评审视角的开关，不是仓库的属性：换档就地重算
            for (DiffEntry e : scanner.scan(parent.getTree(), commit.getTree())) {
                if (!path.equals(e.getNewPath()) && !path.equals(e.getOldPath())) continue;
                fmt.format(e);
                return bos.toString(StandardCharsets.UTF_8);
            }
            throw new IllegalStateException("该提交里没有这个文件的改动记录（路径可能后来被改名/移动）：" + path);
        } finally {
            repository.close();
        }
    }

    /** 看全文：直接读该提交那一版的 blob（后续提交改过工作副本也不影响，行号才与 diff 对得上） */
    @Override
    public byte[] readVersion(RepoClient.RepoRow repo, String revision, String path, char side) throws Exception {
        Repository repository = open(repo);
        try (org.eclipse.jgit.revwalk.RevWalk rw = new org.eclipse.jgit.revwalk.RevWalk(repository)) {
            org.eclipse.jgit.lib.ObjectId id = repository.resolve(side == 'P' ? revision + "^" : revision);
            if (id == null) throw new IllegalStateException("在这个 Git 库里找不到该提交：" + revision);
            RevCommit commit = rw.parseCommit(id);
            try (org.eclipse.jgit.treewalk.TreeWalk tw =
                         org.eclipse.jgit.treewalk.TreeWalk.forPath(repository, path, commit.getTree())) {
                if (tw == null) return null;   // 该版本里没有这个文件（新增项的上一版、或后来被改名/删掉）
                byte[] bytes = repository.open(tw.getObjectId(0)).getBytes();
                if (bytes.length > MAX_FILE_BYTES) {
                    throw new IllegalStateException("文件 " + (bytes.length / 1024) + " KB，超过 "
                            + (MAX_FILE_BYTES / 1024) + " KB 上限，不整份读取：" + path);
                }
                if (FileTexts.binary(bytes)) {
                    throw new IllegalStateException(FileTexts.binaryFile(path));
                }
                return bytes;
            }
        } finally {
            repository.close();
        }
    }

    private Repository open(RepoClient.RepoRow repo) throws java.io.IOException {
        if (repo.localPath() == null || repo.localPath().isBlank()) {
            throw new IllegalArgumentException("该仓库没有本地工作副本，读不到文件内容");
        }
        Path dir = Path.of(repo.localPath()).toAbsolutePath().normalize();
        if (!Files.exists(dir.resolve(".git"))) {
            throw new IllegalStateException("该目录不是 Git 工作副本（未找到 .git）：" + dir);
        }
        return new FileRepositoryBuilder().findGitDir(dir.toFile()).build();
    }

    @Override
    public SyncResult sync(RepoRow repo) throws Exception {
        Path dir = Path.of(repo.localPath()).toAbsolutePath().normalize();
        try (Repository repository = new FileRepositoryBuilder().findGitDir(dir.toFile()).build()) {
            String note;
            try (Git git = Git.open(repository.getWorkTree())) {
                git.fetch().setRemote("origin").call();
                note = "fetch 成功";
            } catch (Exception e) {
                note = "未执行远程拉取（无 origin 或不可达）：仅扫描本地提交";
            }

            List<Revision> revisions = new ArrayList<>();
            List<RevCommit> pending = new ArrayList<>();
            try (Git git = Git.open(repository.getWorkTree())) {
                for (RevCommit c : git.log().setMaxCount(200).call()) {
                    if (c.getId().getName().equals(repo.vcsRevision())) break; // 同步位点
                    pending.add(c);
                    if (pending.size() >= MAX_NEW_REVISIONS) break;
                }
            }
            // 老的在前
            for (int i = pending.size() - 1; i >= 0; i--) {
                RevCommit c = pending.get(i);
                List<FileDiff> changes;
                if (c.getParentCount() > 0) {
                    try (org.eclipse.jgit.revwalk.RevWalk rw = new org.eclipse.jgit.revwalk.RevWalk(repository)) {
                        RevCommit parent = rw.parseCommit(c.getParent(0).getId());
                        changes = diffAgainst(repository, parent, c);
                    }
                } else {
                    // 根提交：遍历提交树，全部记为新增
                    changes = new ArrayList<>();
                    try (org.eclipse.jgit.revwalk.RevWalk rw2 = new org.eclipse.jgit.revwalk.RevWalk(repository);
                         org.eclipse.jgit.treewalk.TreeWalk tw = new org.eclipse.jgit.treewalk.TreeWalk(repository)) {
                        RevCommit self = rw2.parseCommit(c.getId());
                        tw.addTree(self.getTree());
                        tw.setRecursive(true);
                        while (tw.next()) {
                            changes.add(new FileDiff(tw.getPathString(), 'A', null));
                        }
                    }
                }
                revisions.add(new Revision(c.getId().getName(), c.getAuthorIdent().getName(),
                        c.getShortMessage(), java.time.Instant.ofEpochSecond(c.getCommitTime()).toString(), changes));
            }
            return new SyncResult(note, revisions);
        }
    }

    private List<FileDiff> diffAgainst(Repository repository, RevCommit parent, RevCommit commit) throws Exception {
        List<FileDiff> changes = new ArrayList<>();
        List<DiffEntry> entries;
        try (DiffFormatter scanner = new DiffFormatter(org.eclipse.jgit.util.io.DisabledOutputStream.INSTANCE)) {
            scanner.setRepository(repository);
            scanner.setDetectRenames(true);
            entries = scanner.scan(parent.getTree(), commit.getTree());
        }
        for (DiffEntry e : entries) {
            String patch;
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                 DiffFormatter fmt = new DiffFormatter(bos)) {
                fmt.setRepository(repository);
                fmt.setContext(UnifiedDiff.DEFAULT_CONTEXT);   // 同步只生成默认档；评审要看更多上下文走按需重算
                fmt.format(e);
                patch = bos.toString(StandardCharsets.UTF_8);
            }
            patch = PatchStore.truncate(patch);
            String path = e.getChangeType() == DiffEntry.ChangeType.DELETE ? e.getOldPath() : e.getNewPath();
            char type = switch (e.getChangeType()) {
                case ADD -> 'A';
                case DELETE -> 'D';
                case RENAME -> 'R';
                default -> 'M';
            };
            changes.add(new FileDiff(path, type, patch));
        }
        return changes;
    }
}
