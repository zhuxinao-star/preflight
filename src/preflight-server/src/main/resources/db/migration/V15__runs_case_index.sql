-- 第 15 轮：用例目录要按用例回看「最近一次执行结果」，test_runs 原先只有 target/commit 索引，
-- case_id 上的关联子查询会全表扫（读路径必须有索引）。
CREATE INDEX IF NOT EXISTS idx_runs_case ON test_runs(case_id);
