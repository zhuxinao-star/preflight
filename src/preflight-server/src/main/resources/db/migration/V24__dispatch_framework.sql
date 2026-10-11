-- 第 39 轮：服务字典里每一条是"哪个分发框架、哪个仓库"学来的。
-- 背景（用户原话："yapi这里 为什么unifiedvisit 和 unifiedvisit-instruct 接口数都是 0，这个不应该跟接口卡里面一样吗"）：
-- 字典表只有 repo_id 一列做归属，而 rebuild 把整本项目全部条目都盖在"第一个通过 detect 的入口仓"上
-- （项目 4 的 4765 条全是 repo_id=7 work_app），于是 YApi 页按仓库数条目时，其余仓库永远是 0。
-- 加上第二种分发风格（统一访问转发中心把功能号写在 interface-customer.properties 里）根本没有适配器，
-- 那 174 个功能号连学都学不进来。
-- repo_id 改成"按贡献仓库归属"是这次的行为改造；framework 列则让读取期（信封判定、请求路径、体检口径）
-- 能分清同一种 kind 下两种框架的不同事实，不必靠 evidence 字符串去猜。
-- 老行没有这个列，读取期一律按"未知框架"处理（等同既有 KJDP 口径），不回写、不冒充。
ALTER TABLE dispatch_targets ADD COLUMN framework TEXT NOT NULL DEFAULT '';
-- 归属从"整本项目一个仓"变成"按贡献仓库"，于是"按仓库数条目"（YApi 页的仓库下拉）与"按仓库取声明"
-- （出参归因的 extraBexViews）都成了 repo_id 上的常规读路径，这两条都要走索引。
CREATE INDEX idx_dispatch_repo ON dispatch_targets(project_id, repo_id);
