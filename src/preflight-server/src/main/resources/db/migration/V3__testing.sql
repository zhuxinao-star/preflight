-- M2/M3 测试闭环：环境、凭据、登录流、用例、执行记录、汇总字段
ALTER TABLE file_changes ADD COLUMN impacts_json TEXT;
ALTER TABLE commits ADD COLUMN summary TEXT;
ALTER TABLE commits ADD COLUMN risk_level TEXT;

CREATE TABLE credentials (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL UNIQUE,
    kind        TEXT NOT NULL DEFAULT 'password',
    secret_enc  TEXT NOT NULL,
    created_at  TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

CREATE TABLE environments (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id  INTEGER NOT NULL REFERENCES projects(id),
    name        TEXT NOT NULL,
    base_url    TEXT,
    db_url      TEXT,
    db_user     TEXT,
    db_password_enc TEXT,
    vars        TEXT,
    UNIQUE (project_id, name)
);

CREATE TABLE login_flows (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER NOT NULL REFERENCES projects(id),
    name       TEXT NOT NULL,
    steps      TEXT NOT NULL,
    apply      TEXT,
    cred_id    INTEGER,
    UNIQUE (project_id, name)
);

CREATE TABLE test_cases (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    target_type TEXT NOT NULL,
    target_id   INTEGER NOT NULL,
    name        TEXT NOT NULL,
    scenario    TEXT,
    params      TEXT,
    expect      TEXT,
    origin      TEXT NOT NULL DEFAULT 'llm',
    enabled     INTEGER NOT NULL DEFAULT 1,
    created_at  TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    updated_at  TEXT
);
CREATE INDEX idx_cases_target ON test_cases(target_type, target_id);

CREATE TABLE test_runs (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    case_id          INTEGER,
    target_type      TEXT,
    target_id        INTEGER,
    mode             TEXT NOT NULL,
    env_id           INTEGER,
    commit_id        INTEGER,
    request_snapshot TEXT,
    response_snapshot TEXT,
    status           TEXT,
    latency_ms       INTEGER,
    executed_by      TEXT DEFAULT 'tester',
    executed_at      TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX idx_runs_target ON test_runs(target_type, target_id);
CREATE INDEX idx_runs_commit ON test_runs(commit_id);

CREATE TABLE settings (
    key   TEXT PRIMARY KEY,
    value TEXT
);
