-- 第 38 轮：YApi 文档草稿的"人工维护"覆盖层。
-- 背景：草稿每次都是按服务字典 + 盘上代码现算的（推导值不落库这条铁律仍然成立），
-- 但用户要拿它当接口文档的准稿维护——字段名、类型、中文说明要能改，改完要留得住，下次打开还是他维护过的那份。
-- 只改这三列是因为它们恰好就是推给 YApi 的那三样（Schema 的属性名 / type / description）；
-- 「示例值」在预览里只是给人核对用的，压根不进请求体，做成可编辑就是在骗人，所以那列保持只读。
-- 所以这里**只存差异**：与自动算出来的那一条完全相同的行不落（等于"没人工改过"），
-- 恢复自动＝删掉这一行，重学/代码变了也不会把人工维护的行冲掉（按 slot 逐行合并，新算出来的字段照样出现）。
-- slot 用自动行的字段名（小写）；人工新增的行 slot 就是它自己的名字并置 added=1；人工删掉的行 hidden=1（不消失，界面上可恢复）。
CREATE TABLE yapi_doc_overrides (
    project_id  INTEGER NOT NULL,
    kind        TEXT    NOT NULL,           -- service | bex
    code        TEXT    NOT NULL,           -- 功能号
    io          TEXT    NOT NULL,           -- in | out
    slot        TEXT    NOT NULL,           -- 自动行的字段名（小写）；人工新增行＝自己名字（小写）
    added       INTEGER NOT NULL DEFAULT 0, -- 1＝代码里根本没有这一位，人加的
    hidden      INTEGER NOT NULL DEFAULT 0, -- 1＝人删掉了，不进文档但留着可恢复
    ov_field    TEXT,                       -- 改名后的字段名（空＝没改）
    ov_type     TEXT,                       -- 覆盖类型
    ov_title    TEXT,                       -- 覆盖中文说明
    edited_at   TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    edited_by   TEXT,
    PRIMARY KEY (project_id, kind, code, io, slot)
);
-- 待同步列表要一眼看出"哪几条已经被人工维护过"，按整张字典反查覆盖数
CREATE INDEX idx_yapi_ov_lookup ON yapi_doc_overrides(project_id, kind, code);

-- 标题与请求路径也是文档的一部分：字典里的 name 学不来中文名，标题就会是"名称+功能号"那种机械拼法。
-- 这一层与字段层分开，是因为它一个功能号只有一份、且没有"逐行合并"的语义。
CREATE TABLE yapi_doc_meta (
    project_id  INTEGER NOT NULL,
    kind        TEXT    NOT NULL,
    code        TEXT    NOT NULL,
    ov_title    TEXT,                       -- 覆盖后的文档标题（空＝用自动算的那条）
    ov_path     TEXT,                       -- 覆盖后的请求路径
    edited_at   TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
    edited_by   TEXT,
    PRIMARY KEY (project_id, kind, code)
);
