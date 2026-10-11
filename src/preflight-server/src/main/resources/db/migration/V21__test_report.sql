-- 第卅三轮：提测结论单第一批（数据层）。
-- 设计见 docs/08-提测结论单设计.md §3：结论单不是新子系统，而是把「起飞检查单」那张
-- release_checklists 从"整份 blob + 按文件出行"升级成"kind 分流 + 按目标出行 + 每行带 run_id 证据"。
--
-- 这一批只做骨架：行 + 证据 + 缺口 + 指纹 + 版本状态。自动定性（classification）与逐行改判
-- （overrides_json）留到第二批，所以这里**不建**那两列——空列会诱导界面装懂。
-- 老行靠新列的常量默认值落位为 checklist/issued，不写任何 UPDATE 回填（留痕绝不回填）。

ALTER TABLE release_checklists ADD COLUMN kind           TEXT NOT NULL DEFAULT 'checklist';  -- checklist | report
ALTER TABLE release_checklists ADD COLUMN status         TEXT NOT NULL DEFAULT 'issued';     -- draft | issued | superseded
ALTER TABLE release_checklists ADD COLUMN env_id         INTEGER;
ALTER TABLE release_checklists ADD COLUMN fingerprint    TEXT;                               -- 指纹四要素，缺哪部分存 null
ALTER TABLE release_checklists ADD COLUMN supersedes_id  INTEGER;
ALTER TABLE release_checklists ADD COLUMN issued_at      TEXT;

-- 一行 = 一个受影响目标（接口卡 / SQL 卡），basis 如实标出是行级命中还是只能到整文件
CREATE TABLE report_rows (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    report_id     INTEGER NOT NULL REFERENCES release_checklists(id),
    unit_kind     TEXT NOT NULL,
    unit_id       INTEGER NOT NULL,
    unit_name     TEXT,
    basis         TEXT NOT NULL,
    commit_ids    TEXT,
    case_id       INTEGER,
    case_name     TEXT,
    run_id        INTEGER,
    run_status    TEXT,
    run_env_id    INTEGER,
    run_commit_id INTEGER,
    run_at        TEXT,
    note          TEXT,
    UNIQUE (report_id, unit_kind, unit_id)
);
-- 详情页整表按 report_id 读；反查"这张卡被哪些结论单提到"要有索引（读路径必须有索引）
CREATE INDEX idx_report_rows_report ON report_rows(report_id, unit_kind, unit_id);
CREATE INDEX idx_report_rows_unit   ON report_rows(unit_kind, unit_id);

-- 缺口 = 证据不足的地方。系统不许猜，只把"缺什么"挂在脸上
CREATE TABLE report_gaps (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    report_id INTEGER NOT NULL REFERENCES release_checklists(id),
    unit_kind TEXT,
    unit_id   INTEGER,
    unit_name TEXT,
    gap_type  TEXT NOT NULL,
    detail    TEXT
);
CREATE INDEX idx_report_gaps_report ON report_gaps(report_id);
CREATE INDEX idx_report_gaps_unit   ON report_gaps(unit_kind, unit_id);
