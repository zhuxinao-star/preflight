-- 第 25 轮：增量学习的缓存键必须带上"解析能力版本"。
-- R11 的跳过条件只有 (repo_id, path, size, mtime)，而第 14 轮才新增 dynamic_body 采集——
-- 源码文件本身没动过，就永远命中跳过，老卡的 dynamic_body 永远补不上（用户点【学习】"好像也还是不行"的真因）。
-- 加一列 cap 记下"这个文件是哪一版采集能力学出来的"：升级后旧行是 NULL，天然不等于当前值 → 恰好重扫一次。
-- 顺带删掉第 9 轮那个全局 schema_capability 开关留下的 settings 行：它只能让升级后第一个仓库全量重扫，
-- 其余仓库照样漏，现在由 per-file cap 精确替代。
ALTER TABLE learned_files ADD COLUMN cap TEXT;
DELETE FROM settings WHERE key='schema_capability';
