-- 第 27 轮：③ 面板候选值的只读取值缓存。
-- 这里存的是**目标库当前实际有什么值**（不是从源码派生的结论），所以它必须能被反复刷新、也必须能被一句"重新查"绕开。
-- 推导类事实（参数→列/运算符/多值/字典）一律不落这张表：那套按读取期现算，schema_hash 不能动。
-- 键里带 cap_ver：查询能力一变（换语句、换上限）旧缓存就不能冒充新结论（与 learned_files.cap 同一条铁律）。
-- 唯一键本身供"整键等值"读路径用，所以不再另建前缀索引。
-- 这张表不随知识包导出：值是某个环境某一刻的库内容，换环境就是假线索。
CREATE TABLE column_value_cache (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id  INTEGER NOT NULL,
    env_id      INTEGER NOT NULL,
    kind        TEXT NOT NULL,              -- column＝表字段真值 | dict＝数据字典项
    table_name  TEXT NOT NULL,
    column_name TEXT NOT NULL,
    dict_key    TEXT NOT NULL DEFAULT '',   -- kind=dict 时是字典键列的值，否则空串
    cap_ver     INTEGER NOT NULL,
    status      TEXT NOT NULL,              -- ok | too-many | empty | failed
    values_json TEXT NOT NULL DEFAULT '[]',
    rows_seen   INTEGER,                    -- 库里回了几行（判上限用，不含没回的情况）
    note        TEXT,
    queried_at  TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    UNIQUE (project_id, env_id, kind, table_name, column_name, dict_key, cap_ver)
);
