-- 返工批次：B1~B3
-- B1: 无结构变更（R1~R7 均为逻辑/依赖修复）
-- B2/B3:
ALTER TABLE file_changes ADD COLUMN category TEXT;        -- 改动五分类（R12）：sql | api | ddl | frontend | config
ALTER TABLE file_changes ADD COLUMN suggested_mode TEXT;  -- 建议测法（R12）：db | http | none
ALTER TABLE findings ADD COLUMN file_change_id INTEGER;   -- R15：缺陷关联到具体文件改动
CREATE TABLE IF NOT EXISTS learned_files (                -- R11：增量学习（按文件指纹跳过未变文件）
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    repo_id INTEGER NOT NULL,
    path    TEXT NOT NULL,
    size    INTEGER,
    mtime   INTEGER,
    UNIQUE (repo_id, path)
);
CREATE TABLE IF NOT EXISTS benchmark_runs (               -- R13：评测分数落盘
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    source     TEXT,
    report     TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
