# Relay 配置

加载顺序固定为：内置默认值、配置文件、`WEBTERM_RELAY_*` 环境变量、允许的 CLI 覆盖（`--config`、`--listen`）。使用 `webterm-relay config validate` 与 `webterm-relay config show` 检查最终配置。

```json
{
  "listen": "127.0.0.1:19090",
  "storePath": "/var/lib/webterm/relay-store.json",
  "maxPendingMessages": 256,
  "maxPendingBytes": 4194304,
  "allowRegistration": true,
  "requireEmailOtp": false,
  "smtp": {
    "host": "smtp.example.com",
    "port": 587,
    "username": "webterm",
    "password": "secret",
    "from": "noreply@example.com",
    "publicUrl": "https://relay.example.com"
  }
}
```

当 `requireEmailOtp` 为 `true` 时，必须完整设置 SMTP 的 host、port、username、password 和 from；否则 `run` 与 `config validate` 都会失败。管理员由一次性的 `webterm-relay admin create --password-file ...` 创建，Relay 启动不再读取 Bootstrap 用户或密码。

`storePath` 使用相对路径时相对于配置文件所在目录解析，不依赖启动时的当前工作目录。

支持的环境变量：`WEBTERM_RELAY_CONFIG`、`WEBTERM_RELAY_LISTEN`、`WEBTERM_RELAY_STORE_PATH`、`WEBTERM_RELAY_PUBLIC_URL`、`WEBTERM_RELAY_ALLOW_REGISTRATION`、`WEBTERM_RELAY_REQUIRE_EMAIL_OTP`、`WEBTERM_RELAY_SMTP_HOST`、`WEBTERM_RELAY_SMTP_PORT`、`WEBTERM_RELAY_SMTP_USERNAME`、`WEBTERM_RELAY_SMTP_PASSWORD`、`WEBTERM_RELAY_SMTP_FROM`。`WEBTERM_RELAY_DEV_PRINT_OTP` 仅用于本地开发，不能用于生产。

旧的 `WEBTERM_RELAY_ADDR` 暂时兼容，并会输出弃用警告；请迁移为 `WEBTERM_RELAY_LISTEN`。
