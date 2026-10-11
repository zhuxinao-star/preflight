-- M1 学习闭环：LLM 供应商（页面可配）、接口卡、数据对象卡、文件绑定
CREATE TABLE llm_providers (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL UNIQUE,
    base_url    TEXT NOT NULL,
    api_key_enc TEXT,
    model       TEXT NOT NULL,
    purpose     TEXT,
    enabled     INTEGER NOT NULL DEFAULT 1,
    is_default  INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

INSERT OR IGNORE INTO llm_providers(name, base_url, model, purpose, enabled, is_default) VALUES
    ('ollama-local', 'http://localhost:11434/v1', 'qwen2.5:7b', NULL, 1, 1),
    ('openai-demo',  'https://api.openai.com/v1', 'gpt-4o-mini', NULL, 0, 0);

CREATE TABLE interfaces (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id   INTEGER NOT NULL REFERENCES projects(id),
    repo_id      INTEGER REFERENCES repos(id),
    protocol     TEXT NOT NULL DEFAULT 'http',
    name         TEXT NOT NULL,
    http_method  TEXT NOT NULL DEFAULT '',
    path         TEXT NOT NULL DEFAULT '',
    params_schema TEXT,
    auth_type    TEXT DEFAULT 'login_flow',
    source       TEXT,
    db_objects   TEXT,
    confidence   REAL DEFAULT 0.6,
    status       TEXT NOT NULL DEFAULT 'draft',
    human_edited INTEGER NOT NULL DEFAULT 0,
    updated_at   TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    UNIQUE (project_id, protocol, http_method, path)
);

CREATE TABLE db_objects (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id   INTEGER NOT NULL REFERENCES projects(id),
    name         TEXT NOT NULL,
    obj_type     TEXT NOT NULL,
    signature    TEXT,
    sql_template TEXT,
    source_sql   TEXT,
    source       TEXT,
    tables_involved TEXT,
    confidence   REAL DEFAULT 0.7,
    status       TEXT NOT NULL DEFAULT 'draft',
    human_edited INTEGER NOT NULL DEFAULT 0,
    updated_at   TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    UNIQUE (project_id, name, obj_type)
);

CREATE TABLE file_bindings (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id INTEGER NOT NULL,
    repo_id    INTEGER NOT NULL,
    file_path  TEXT NOT NULL,
    line_start INTEGER,
    line_end   INTEGER,
    unit_kind  TEXT NOT NULL,
    unit_id    INTEGER NOT NULL
);
CREATE INDEX idx_bindings_file ON file_bindings(project_id, file_path);
