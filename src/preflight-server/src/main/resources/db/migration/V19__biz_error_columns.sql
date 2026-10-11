-- 第 26 轮：业务错误码列白名单。库里执行成功但过程自己回了非 0 错误码时，界面要标橙。
-- 这一行留空＝用户主动关掉判定；没有这一行（老库）＝用默认词。
INSERT OR IGNORE INTO settings(key, value) VALUES
    ('biz_error_columns', 'ERRORCODE,ERRCODE,SQLCODE,RET_CODE,RETURN_CODE,F_ERRORCODE');
