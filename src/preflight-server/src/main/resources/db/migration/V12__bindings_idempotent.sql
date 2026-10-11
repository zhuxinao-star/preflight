-- 第 13 轮：文件绑定表既要"重复导入/重复学习不涨行"，也要能被按单元反查
-- file_bindings 是唯一没有唯一键的知识表，导入侧的 INSERT OR IGNORE 对它等于普通 INSERT：
-- 同一份知识包导 4 次就多 4 倍行（实测 5411 → 21644），而读路径全是按 unit_id 的相关子查询，
-- 表一大就把知识库页与"按仓库筛选"拖成几十秒（实测 40s）。
DELETE FROM file_bindings
WHERE id NOT IN (SELECT MIN(id) FROM file_bindings
                 GROUP BY project_id, repo_id, unit_kind, unit_id, file_path, line_start, line_end);

CREATE UNIQUE INDEX uq_bindings_key
    ON file_bindings(project_id, repo_id, unit_kind, unit_id, file_path, line_start, line_end);

CREATE INDEX idx_bindings_unit ON file_bindings(unit_kind, unit_id, repo_id);
