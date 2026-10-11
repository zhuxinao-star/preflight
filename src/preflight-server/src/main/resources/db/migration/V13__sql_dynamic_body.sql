-- 第 14 轮：保留 MyBatis 动态 SQL 原文（含 <if>/<choose>/<where>/<set>/<trim> 与 CDATA），
-- 直测时按参数条件拼装，替代"拍平 + 'DOLLAR' 假字面量"。
-- 该列是源码事实、不是推导值；且不参与任何结构变更判定，因此加它不会把历史用例转成"待复核"。
ALTER TABLE db_objects ADD COLUMN dynamic_body TEXT;
