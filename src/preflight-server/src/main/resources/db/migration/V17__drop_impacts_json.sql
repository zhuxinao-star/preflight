-- 第 17 轮收尾：删掉 file_changes.impacts_json 这个孤儿列。
-- 它原本缓存"单条文件改动的影响面"，写入方 ImpactService.forChange() 在本轮统一归因后已无调用者，
-- 详情弹屏改为按 commit_targets 同一套口径现算；残留的 277 行是旧口径（整文件 = 该文件所有卡）的结果，
-- 继续留着只会在下次被误当缓存读出来，与列表口径矛盾。纯删列，不丢业务数据。
ALTER TABLE file_changes DROP COLUMN impacts_json;
