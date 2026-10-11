-- PreFlight 元数据库初始化（SQLite）
-- 2026-09 定稿补充：repos 支持 git/svn 双 VCS；file_changes 支持已测/未测标记，更新点全部留痕。

CREATE TABLE projects (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL UNIQUE,
    description TEXT,
    created_at  TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

CREATE TABLE repos (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id      INTEGER NOT NULL REFERENCES projects(id),
    vcs_type        TEXT NOT NULL DEFAULT 'git',   -- git | svn
    repo_type       TEXT NOT NULL,                 -- frontend | backend | database
    name            TEXT,
    url             TEXT,                          -- 远程地址（git https://... / svn://...），空 = 仅监听本地目录
    local_path      TEXT NOT NULL,
    branch          TEXT,                          -- git 分支 / svn 相对路径
    vcs_revision    TEXT,                          -- 最近一次同步记录到的 revision（git sha / svn r123）
    last_synced_at  TEXT,
    watch_enabled   INTEGER NOT NULL DEFAULT 1,
    created_at      TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

-- 更新点留痕表：每个提交/版本一条，只增不改
CREATE TABLE commits (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    repo_id      INTEGER NOT NULL REFERENCES repos(id),
    revision     TEXT NOT NULL,                    -- git sha 或 svn r123
    author       TEXT,
    message      TEXT,
    committed_at TEXT,
    processed    INTEGER NOT NULL DEFAULT 0,       -- 是否已完成增量学习
    synced_at    TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    UNIQUE (repo_id, revision)
);
CREATE INDEX idx_commits_repo ON commits(repo_id, committed_at);

-- 文件级改动：一条不留痕覆盖，带测试标记
CREATE TABLE file_changes (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    commit_id    INTEGER NOT NULL REFERENCES commits(id),
    path         TEXT NOT NULL,
    change_type  TEXT,                             -- A | M | D | R
    patch_file   TEXT,                             -- diff 快照文件
    summary      TEXT,                             -- LLM 改动摘要
    risk_level   TEXT,                             -- high | medium | low
    test_status  TEXT NOT NULL DEFAULT 'untested', -- untested | partial | tested | skipped
    tested_at    TEXT,
    tested_by    TEXT,
    UNIQUE (commit_id, path)
);
CREATE INDEX idx_file_changes_status ON file_changes(test_status);

CREATE TABLE llm_cache (
    fingerprint TEXT PRIMARY KEY,                   -- purpose|model|prompt 的 sha256
    purpose     TEXT,
    model       TEXT,
    response    TEXT,
    created_at  TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

CREATE TABLE tasks (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    type        TEXT NOT NULL,
    payload     TEXT,
    status      TEXT NOT NULL DEFAULT 'pending',   -- pending | running | done | failed | dead
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_error  TEXT,
    created_at  TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    updated_at  TEXT
);

CREATE TABLE audit_logs (
    id     INTEGER PRIMARY KEY AUTOINCREMENT,
    actor  TEXT NOT NULL DEFAULT 'system',
    action TEXT NOT NULL,
    target TEXT,
    detail TEXT,
    at     TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
