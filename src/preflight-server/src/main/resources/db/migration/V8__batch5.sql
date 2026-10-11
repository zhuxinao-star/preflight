-- 批次5：RBAC + 起飞检查单 + 表元数据缓存
CREATE TABLE users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL DEFAULT 'tester',  -- admin | tester | viewer
    created_at    TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

CREATE TABLE release_checklists (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id    INTEGER NOT NULL,
    title         TEXT NOT NULL,
    from_revision TEXT,
    to_revision   TEXT,
    snapshot_json TEXT,
    signoffs_json TEXT,
    created_by    TEXT,
    created_at    TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

CREATE TABLE table_meta (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id  INTEGER NOT NULL,
    table_name  TEXT NOT NULL,
    columns_json TEXT,
    pulled_at   TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    UNIQUE (project_id, table_name)
);
