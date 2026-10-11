-- 批次2：断言分层模型 + 基线对比
ALTER TABLE test_cases ADD COLUMN assertions TEXT;       -- JSON 数组：[{level,type,expr,op,expected}]
ALTER TABLE test_cases ADD COLUMN baseline_run_id INTEGER; -- 基线执行记录
ALTER TABLE test_cases ADD COLUMN ignore_fields TEXT;    -- JSON 数组：diff 忽略字段（时间戳/自增ID等）
ALTER TABLE test_runs ADD COLUMN assertions_result TEXT; -- 逐条断言结果
ALTER TABLE test_runs ADD COLUMN baseline_diff TEXT;     -- 与基线的差异报告

-- 失败 → 缺陷留痕（04-补齐 §3.5 轻量版）
CREATE TABLE findings (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id     INTEGER,
    run_id         INTEGER,
    commit_id      INTEGER,
    title          TEXT,
    severity       TEXT NOT NULL DEFAULT 'medium',
    status         TEXT NOT NULL DEFAULT 'open',   -- open | confirmed | false_alarm | fixed
    noted_by       TEXT,
    created_at     TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

