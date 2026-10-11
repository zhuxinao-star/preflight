-- 第 15 轮方案 A：持久登录 token。会话原本只存在 Tomcat 内存里，
-- 每次重启（升级部署）或 30 分钟空闲都会把人踢回登录框，正在填的表单随之丢失。
-- 这里只存 token 的 SHA-256（库被拷走也换不到可用凭据），明文只进 HttpOnly cookie。
CREATE TABLE IF NOT EXISTS login_tokens (
  token_hash TEXT PRIMARY KEY,
  username   TEXT NOT NULL,
  role       TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime')),
  expires_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_login_tokens_expires ON login_tokens(expires_at);
