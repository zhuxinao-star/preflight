package com.preflight.repo;

import com.preflight.common.FileTexts;
import org.springframework.stereotype.Component;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNLogEntry;
import org.tmatesoft.svn.core.SVNLogEntryPath;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.BasicAuthenticationManager;
import org.tmatesoft.svn.core.internal.io.dav.DAVRepositoryFactory;
import org.tmatesoft.svn.core.internal.io.fs.FSRepositoryFactory;
import org.tmatesoft.svn.core.internal.io.svn.SVNRepositoryFactoryImpl;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.tmatesoft.svn.core.wc.SVNClientManager;
import org.tmatesoft.svn.core.wc.SVNRevision;
import org.tmatesoft.svn.core.wc.SVNUpdateClient;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * SVN 实现：SVNKit 自动拉取（checkout/update 本地工作副本）+ log 增量。
 * 同步只记录"变更路径清单"（不逐个文件拉内容），文件级差异在点开时按需生成（见 {@link #filePatch}）。
 * 认证：支持 URL 内嵌用户名 + 凭据保险库（凭据名 = repo.name + "-svn"）。
 */
@Component
public class SvnRepoClient implements RepoClient, FilePatchReader {

    static {
        DAVRepositoryFactory.setup();
        SVNRepositoryFactoryImpl.setup();
        FSRepositoryFactory.setup();
    }

    /** 超过这个大小的文件不拉（二进制、生成物、jar 占了 SVN 变更记录的绝大多数） */
    private static final int MAX_PATCH_BYTES = 2 * 1024 * 1024;

    /** 看全文的上限：整文件比 diff 大得多，这里放宽到 8 MB（与 Git 通道读历史版同值） */
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;

    @Override
    public SyncResult sync(RepoRow repo) throws Exception {
        if (repo.url() == null || repo.url().isBlank()) {
            throw new IllegalArgumentException("SVN 仓库必须填写远程 URL");
        }
        SVNURL url = SVNURL.parseURIEncoded(repo.url());
        SVNRepository repository = SVNRepositoryFactory.create(url);
        applyAuth(repository, repo);

        // 1. 自动拉取：已有工作副本则 update，否则 checkout
        String pullNote = "未拉取（未配置本地路径）";
        File wc = repo.localPath() == null || repo.localPath().isBlank() ? null : new File(repo.localPath());
        if (wc != null) {
            SVNClientManager cm = SVNClientManager.newInstance();
            if (repo.branch() != null && !repo.branch().isBlank()) {
                cm.setOptions(null);
            }
            SVNUpdateClient uc = cm.getUpdateClient();
            if (new File(wc, ".svn").exists()) {
                uc.doUpdate(wc, SVNRevision.HEAD, SVNDepth.INFINITY, false, false);
                pullNote = "svn update 完成";
            } else {
                uc.doCheckout(url, wc, SVNRevision.UNDEFINED, SVNRevision.HEAD, SVNDepth.INFINITY, false);
                pullNote = "svn checkout 完成";
            }
        }

        // 2. 增量 log
        long head = repository.getLatestRevision();
        long from = 0;
        if (repo.vcsRevision() != null && !repo.vcsRevision().isBlank()) {
            from = Long.parseLong(repo.vcsRevision().replaceFirst("(?i)^r", ""));
        }
        Collection<SVNLogEntry> entries = repository.log(new String[]{""}, null, from + 1, head, true, true);
        // svn log 给的是「仓库根相对路径」，而学习到的 file_bindings 与本地工作副本都按登记的
        // 那个子目录为基准。两边统一成后者，否则归因永远对不上（未映射）。
        String prefix = locationPrefix(repository);
        List<Revision> revisions = new ArrayList<>();
        for (SVNLogEntry e : entries) {
            List<FileDiff> changes = new ArrayList<>();
            for (SVNLogEntryPath p : e.getChangedPaths().values()) {
                String path = p.getPath();
                if (path.startsWith("/")) path = path.substring(1);
                if (!prefix.isEmpty() && path.startsWith(prefix.substring(1) + "/")) {
                    path = path.substring(prefix.length() + 1);
                }   // 不在登记目录下的（跨目录 copy 来源）保持原样，至少看得见
                changes.add(new FileDiff(path, p.getType(), null));
            }
            revisions.add(new Revision("r" + e.getRevision(), e.getAuthor() == null ? "?" : e.getAuthor(),
                    e.getMessage() == null ? "" : e.getMessage(),
                    java.time.Instant.ofEpochMilli(e.getDate().getTime())
                            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(), changes));
        }
        return new SyncResult(pullNote, revisions);
    }

    /** 登记 URL 相对仓库根的路径，如 "/2.Trunk/五矿证券/.../WEB/work_app"；拿不到就当没有前缀 */
    private static String locationPrefix(SVNRepository repository) {
        try {
            String loc = repository.getLocation().getPath();
            String root = repository.getRepositoryRoot(true).getPath();
            return loc.startsWith(root) && loc.length() > root.length() ? loc.substring(root.length()) : "";
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String filePatch(RepoRow repo, String revision, String path, char changeType, int context) throws Exception {
        if (repo.url() == null || repo.url().isBlank()) {
            throw new IllegalArgumentException("该仓库没填远程 URL，拉不了改动快照（只有本地目录时无法按版本号取那两个版本的文件）");
        }
        long rev = revOf(revision);
        SVNRepository location = SVNRepositoryFactory.create(SVNURL.parseURIEncoded(repo.url()));
        applyAuth(location, repo);

        // 删除项要看正是"上一版里有什么"：不能跟着新增项一起跳过旧版读取，否则只会得到一份空 diff
        byte[] oldBytes = changeType == 'A' || rev <= 1 ? new byte[0] : readEither(location, repo, path, rev - 1);
        byte[] newBytes = changeType == 'D' ? new byte[0] : readEither(location, repo, path, rev);
        if (oldBytes == null && newBytes == null) {
            throw new IllegalStateException("SVN 里这两个版本都没有该文件内容，取不到差异：" + path);
        }
        return UnifiedDiff.render(path, "r" + (rev - 1), oldBytes == null ? new byte[0] : oldBytes,
                "r" + rev, newBytes == null ? new byte[0] : newBytes, context);
    }

    @Override
    public byte[] readVersion(RepoRow repo, String revision, String path, char side) throws Exception {
        if (repo.url() == null || repo.url().isBlank()) {
            throw new IllegalArgumentException("该仓库没填远程 URL，按版本号取不到文件原文（只有本地工作副本时读不了历史版本）");
        }
        long rev = revOf(revision);
        long want = side == 'P' ? rev - 1 : rev;
        if (want < 1) return null;   // 根提交之前没有版本：不存在，不是读失败
        SVNRepository location = SVNRepositoryFactory.create(SVNURL.parseURIEncoded(repo.url()));
        applyAuth(location, repo);
        byte[] bytes = readEither(location, repo, path, want, MAX_FILE_BYTES);
        if (bytes == null) return null;
        if (FileTexts.binary(bytes)) {
            throw new IllegalStateException(FileTexts.binaryFile(path));
        }
        return bytes;
    }

    /** 先按登记目录取；取不到再按仓库根取一次（回填前的历史行是这个口径），都取不到返回 null */
    private byte[] readEither(SVNRepository location, RepoRow repo, String path, long rev) throws Exception {
        return readEither(location, repo, path, rev, MAX_PATCH_BYTES);
    }

    private byte[] readEither(SVNRepository location, RepoRow repo, String path, long rev, int maxBytes) throws Exception {
        byte[] b = readAt(location, path, rev, maxBytes);
        if (b != null) return b;
        SVNRepository root = SVNRepositoryFactory.create(location.getRepositoryRoot(true));
        applyAuth(root, repo);
        return readAt(root, path, rev, maxBytes);
    }

    /** @return 文件内容；该版本里不存在这个文件时返回 null（不抛，留给调用方换另一种路径基准再试） */
    private byte[] readAt(SVNRepository repository, String path, long rev, int maxBytes) throws Exception {
        // 变更清单里的路径不带前导斜杠，但 SVNKit 对两种形式的容忍度不一致，各认一次
        SVNDirEntry entry = dirEntry(repository, path, rev);
        String form = path;
        if (entry == null) {
            entry = dirEntry(repository, "/" + path, rev);
            form = "/" + path;
        }
        if (entry == null) return null;
        if (entry.getKind() != SVNNodeKind.FILE) {
            throw new IllegalStateException("变更清单里的这一项不是文件（" + entry.getKind() + "），没有内容可比对：" + path);
        }
        if (entry.getSize() > maxBytes) {
            throw new IllegalStateException("文件 " + (entry.getSize() / 1024) + " KB，超过 "
                    + (maxBytes / 1024) + " KB 上限，不拉取：" + path);
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        repository.getFile(form, rev, null, bos);
        byte[] bytes = bos.toByteArray();
        if (maxBytes == MAX_PATCH_BYTES && FileTexts.binary(bytes)) {
            throw new IllegalStateException("二进制文件（jar/class/png 这类），SVN 里没有文本差异可看，只能在版本库端整版对比：" + path);
        }
        return bytes;
    }

    private SVNDirEntry dirEntry(SVNRepository repository, String path, long rev) {
        try {
            return repository.info(path, rev);
        } catch (Exception e) {
            return null;
        }
    }

    private long revOf(String revision) {
        if (revision == null || revision.isBlank()) throw new IllegalArgumentException("该提交没有记录版本号，无法定位 SVN 版本");
        try {
            return Long.parseLong(revision.trim().replaceFirst("(?i)^r", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("版本号不是 SVN 的修订号：" + revision);
        }
    }

    private void applyAuth(SVNRepository repository, RepoRow repo) {
        // 凭据保险库约定：凭据名为 "<仓库名>-svn"，secret 格式 "user:password"
        String auth = System.getenv("PREFLIGHT_SVN_AUTH"); // 备选：user:password
        String vault = null;
        try {
            vault = VaultBridge.svnCredentialSecret(repo.name());
        } catch (Exception ignore) {
            // 无凭据则匿名
        }
        String authLine = vault != null ? vault : auth;
        if (authLine != null && authLine.contains(":")) {
            int i = authLine.indexOf(':');
            repository.setAuthenticationManager(BasicAuthenticationManager.newInstance(
                    authLine.substring(0, i), authLine.substring(i + 1).toCharArray()));
        }
    }

    /** 桥接凭据保险库，避免循环依赖（由 SyncService 注入静态引用） */
    public static class VaultBridge {
        private static java.util.function.Function<String, String> lookup;

        public static void register(java.util.function.Function<String, String> fn) {
            lookup = fn;
        }

        static String svnCredentialSecret(String repoName) {
            return lookup == null ? null : lookup.apply(svnCredentialName(repoName));
        }
    }

    /** 凭据保险库的 SVN 命名约定（第卅二轮收口：判定"这条凭据是 SVN 账号"与取密码共用这一条规则） */
    public static String svnCredentialName(String repoName) {
        return repoName + "-svn";
    }
}
