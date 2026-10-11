-- 批次4：databaseId 多版本 + 导入通道
ALTER TABLE db_objects ADD COLUMN database_id TEXT; -- MyBatis databaseId（多库兼容项目的语句变体）
