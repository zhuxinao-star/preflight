package com.preflight.repo;

/**
 * 按需取"单个文件在某一个版本的原样改动"（unified diff 文本）与该版本的文件原文。
 * 同步时只登记变更清单，点开某个文件才真去仓库取两个版本的内容比对——
 * 一次提交可能上千个文件，全量拉会把同步拖死（SVN 实测一条提交 1.7 万项）。
 */
public interface FilePatchReader {

    /**
     * changeType: A=新增（无上一版）/ D=删除（无新版）/ M=改 / R=替换
     *
     * @param context 上下文行数（{@link UnifiedDiff#DEFAULT_CONTEXT} 或评审时更大的档位）；
     *                快照是派生视图，真值在仓库里，所以这一档可以随时换着重新算
     */
    String filePatch(RepoClient.RepoRow repo, String revision, String path, char changeType, int context) throws Exception;

    /**
     * 读仓库里某一个版本的文件原文（评审要看整份文件时用，行号才与 diff 对得上）。
     *
     * @param side 'N'＝该提交之后的版本，'P'＝上一版（删除项要看被删的是什么，新增项的上一版通常不存在）
     * @return 文件字节；该版本里没有这个文件时返回 {@code null}（不抛，让上层说清"为什么没有"）
     * @throws Exception 仓库不可达 / 不是文件 / 二进制 / 超过大小上限——原因要原样透到界面上
     */
    byte[] readVersion(RepoClient.RepoRow repo, String revision, String path, char side) throws Exception;
}
