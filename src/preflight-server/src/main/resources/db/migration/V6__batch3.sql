-- 批次3：测试数据准备三件套 + 用例健康度
ALTER TABLE interfaces ADD COLUMN access TEXT;            -- read | write | mixed | unknown
ALTER TABLE interfaces ADD COLUMN pre_sql TEXT;           -- JSON 数组：前置钩子 SQL（独立连接、显式提交）
ALTER TABLE interfaces ADD COLUMN post_sql TEXT;          -- JSON 数组：后置清理钩子
ALTER TABLE interfaces ADD COLUMN schema_hash TEXT;       -- 参数 schema 哈希（用例健康度依据）
ALTER TABLE interfaces ADD COLUMN version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE test_cases ADD COLUMN base_schema_hash TEXT;  -- 创建时的接口 schema 哈希
ALTER TABLE test_cases ADD COLUMN status TEXT NOT NULL DEFAULT 'approved'; -- pending待评审 | approved已启用 | suspect需复核 | deprecated已废弃
ALTER TABLE test_runs ADD COLUMN writes_expected INTEGER; -- 本次执行是否预期写库
ALTER TABLE test_runs ADD COLUMN hooks_result TEXT;       -- 钩子执行明细
