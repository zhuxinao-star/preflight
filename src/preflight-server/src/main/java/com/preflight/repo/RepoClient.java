package com.preflight.repo;

import java.util.List;

/**
 * 仓库客户端抽象：Git（JGit）/ SVN（SVNKit）双实现。
 * 一次同步 = 拉取远程更新 + 返回"自上次记录以来"的更新点（含文件级变更与差异快照）。
 */
public interface RepoClient {

    record FileDiff(String path, char type, String patch) {}   // type: A/M/D/R

    record Revision(String revision, String author, String message, String committedAt, List<FileDiff> changes) {}

    record SyncResult(String note, List<Revision> revisions) {}

    /** repoId 用于加载/回写同步位点 */
    SyncResult sync(RepoRow repo) throws Exception;

    /** repos 表行（避免服务层重复查询） */
    record RepoRow(long id, long projectId, String vcsType, String repoType, String name,
                   String url, String localPath, String branch, String vcsRevision) {}
}
