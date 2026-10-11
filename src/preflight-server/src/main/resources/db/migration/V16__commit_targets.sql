-- 第 17 轮：把"提交 → 受影响目标"的归因结果单独存一张可重建的缓存表。
-- 原先四处（改动流「本提交用例」、用例目录 commitId、影响分析、一键回归）各自现算
-- "改动文件 path 等值匹配 file_bindings"，而 MyBatis 一个 mapper 文件里几百条语句共用同一个 path，
-- 于是改 1 行 SQL 被算成命中整文件 223 张卡 / 938 条用例。这里改为按"改动行落在哪条语句"归因，
-- basis 如实标出是行级命中(line)还是只能到整文件(file)；unit_kind='none' 是"算过了但没映射到任何卡"的哨兵行。
-- 本表纯派生、可重建：重学/删仓库/删项目时按提交删掉，下次读取时重算。
CREATE TABLE commit_targets (
    commit_id   INTEGER NOT NULL,
    unit_kind   TEXT NOT NULL,
    unit_id     INTEGER NOT NULL,
    basis       TEXT NOT NULL,
    anchor      TEXT,
    computed_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    UNIQUE (commit_id, unit_kind, unit_id)
);
-- 用例目录要按提交反查目标（JOIN test_cases），读路径必须有索引
CREATE INDEX idx_commit_targets_unit ON commit_targets(unit_kind, unit_id);
