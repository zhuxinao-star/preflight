-- 第 44 轮：调用链可达的中间层文件（Service / DAO 实现）单独记一张派生表。
--
-- 为什么要它：改动归因原先只认 file_bindings（"这张卡是从哪个文件的哪几行学出来的"），
-- 而改了服务层代码时，那个文件自己不产出任何卡片、也就没有绑定行 —— 真实项目一次只改
-- ZSXBHcglKhmddyService.java 的提交，归因结果只能是"没命中任何已学习目标"，生成用例 0 条。
-- 学习时 BFS 本来就把这些中间层文件走了一遍（CallChainLinker），只是把结果丢了。
--
-- 为什么不塞进 file_bindings：那张表是"卡片的来源文件"，被知识包导出、仓库归属统计、
-- 用例列表的仓库过滤共用；按调用链挂上来的文件不是来源，混进去会把这三处口径一起撑大。
-- 派生数据、可重建（重学按接口覆盖，删仓库整仓清掉），与 commit_targets 同一生命周期。
CREATE TABLE IF NOT EXISTS chain_files (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER NOT NULL,
    repo_id    INTEGER NOT NULL,
    file_path  TEXT NOT NULL,     -- 仓库根相对路径，与 file_bindings / file_changes 同一坐标系
    unit_kind  TEXT NOT NULL,
    unit_id    INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_chain_files
    ON chain_files(project_id, repo_id, unit_kind, unit_id, file_path);
CREATE INDEX IF NOT EXISTS idx_chain_files_path ON chain_files(project_id, file_path);
