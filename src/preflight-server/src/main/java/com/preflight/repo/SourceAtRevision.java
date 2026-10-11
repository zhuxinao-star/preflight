package com.preflight.repo;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 读"某个提交那一版"的文件内容（纯 JGit，不需要目标机装 git 命令）。
 * 用途：把 diff 的行号翻译成"改了哪条语句"必须用**该提交自己的文件版本**——
 * 用工作副本或学习版的行号会因后续改动而错位，宁可退回整文件也不能猜。
 *
 * <p>仓库句柄按本地路径缓存（改动流一页 50 条提交，逐条重开 .git 会把读路径拖到十几秒）；
 * 缓存有上限，且 resolve 认不出对象时丢开重开一次——外部 git fetch 后旧句柄的 pack 列表可能是陈的。
 */
@Component
public class SourceAtRevision {

    /** 超过这个大小的文件不读（语句归因用不上巨型文件，别让读路径吃掉几百 MB） */
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CACHED_REPOS = 8;

    private final Map<String, Repository> repos = new LinkedHashMap<>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Repository> eldest) {
            if (size() <= MAX_CACHED_REPOS) return false;
            eldest.getValue().close();
            return true;
        }
    };

    public Optional<String> read(String localPath, String revision, String filePath) {
        if (localPath == null || localPath.isBlank() || revision == null || revision.isBlank()
                || filePath == null || filePath.isBlank()) {
            return Optional.empty();
        }
        Path dir = Path.of(localPath);
        if (!Files.exists(dir.resolve(".git"))) return Optional.empty();
        try {
            return readWith(dir, revision, filePath, true);
        } catch (Exception e) {
            return Optional.empty(); // 仓库不可读 / 对象丢失 / 编码问题：交回上层按整文件兜底
        }
    }

    private Optional<String> readWith(Path dir, String revision, String filePath, boolean allowRetry) throws IOException {
        Repository repository = open(dir);
        try {
            ObjectId commitId = repository.resolve(revision);
            if (commitId == null) {
                // 认不出这个对象：可能是别的进程刚 fetch 进来的新 pack，丢开缓存句柄重开一次再判
                if (!allowRetry) return Optional.empty();
                drop(dir);
                return readWith(dir, revision, filePath, false);
            }
            try (RevWalk rw = new RevWalk(repository);
                 TreeWalk tw = TreeWalk.forPath(repository, filePath, rw.parseCommit(commitId).getTree())) {
                if (tw == null) return Optional.empty();
                ObjectId blob = tw.getObjectId(0);
                ObjectLoader loader = repository.open(blob);
                if (loader.getSize() > MAX_BYTES) return Optional.empty();
                return Optional.of(new String(loader.getBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            if (!allowRetry) throw e;
            drop(dir); // 句柄失效（目录被动过）：重开一次，仍失败就交给上层兜底
            return readWith(dir, revision, filePath, false);
        }
    }

    private synchronized Repository open(Path dir) throws IOException {
        String key = dir.toString();
        Repository r = repos.get(key);
        if (r != null) return r;
        r = new FileRepositoryBuilder().findGitDir(dir.toFile()).build();
        repos.put(key, r);
        return r;
    }

    private synchronized void drop(Path dir) {
        Repository r = repos.remove(dir.toString());
        if (r != null) r.close();
    }
}
