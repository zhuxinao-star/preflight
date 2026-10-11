-- 批次1：项目多目录强化
ALTER TABLE repos ADD COLUMN learned_at TEXT;
ALTER TABLE repos ADD COLUMN learned_revision TEXT;  -- 知识基线对应的版本（新鲜度契约）
ALTER TABLE environments ADD COLUMN db_type TEXT;    -- 数据库类型：mysql/oracle/dm/tidb/sqlserver/opengauss/gauss-mysql
ALTER TABLE environments ADD COLUMN allow_ddl_commit INTEGER NOT NULL DEFAULT 0; -- 可弃库标记（DDL 提交闸门）

