-- 0002 migration
ALTER TABLE users ADD COLUMN email_verified_at TEXT;
CREATE INDEX IF NOT EXISTS idx_users_email_verified ON users(email_verified_at);

CREATE TABLE IF NOT EXISTS email_verifications (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  purpose       TEXT NOT NULL,          -- 'register' | 'new_device'
  code_hash     TEXT NOT NULL,          -- sha256(6位码)
  target_device_id TEXT,                -- new_device 场景记要信任的 device cookie
  expires_at    TEXT NOT NULL,          -- 10 分钟
  consumed_at   TEXT,
  ip_address    TEXT,                   -- 用于同一 IP 每天限频防刷
  failed_attempts INTEGER NOT NULL DEFAULT 0, -- 防止 OTP 暴力破解
  created_at    TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_email_verif_user ON email_verifications(user_id);
CREATE INDEX IF NOT EXISTS idx_email_verif_ip ON email_verifications(ip_address);

CREATE TABLE IF NOT EXISTS trusted_devices (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id     TEXT NOT NULL,          -- cookie UUID
  device_name   TEXT,                   -- UA 摘要，如 "Chrome / macOS"
  last_seen_at  TEXT,
  created_at    TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_trusted_user_device ON trusted_devices(user_id, device_id);
