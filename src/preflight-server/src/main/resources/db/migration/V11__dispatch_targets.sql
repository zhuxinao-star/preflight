-- 第 10 轮：分发式框架（金证 KJDP 等）的服务字典
-- 一个入口 URL（/redis_ajax）承载上千个业务服务码，参数契约写在 metaData XML 与前端调用点里。
-- 这里只存"查表用的字典"，不生成接口卡、不进知识库列表；entry_path 只是默认建议入口，测试台可改。
CREATE TABLE dispatch_targets (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id  INTEGER NOT NULL,
    repo_id     INTEGER,
    kind        TEXT NOT NULL,
    code        TEXT NOT NULL,
    name        TEXT,
    entry_path  TEXT NOT NULL,
    params      TEXT,
    evidence    TEXT,
    sources     TEXT,
    updated_at  TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    UNIQUE (project_id, kind, code)
);
CREATE INDEX idx_dispatch_lookup ON dispatch_targets(project_id, kind, code);
