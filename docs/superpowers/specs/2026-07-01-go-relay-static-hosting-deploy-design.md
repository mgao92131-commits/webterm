# Go Relay 静态网页托管与部署方案

日期：2026-07-01

## 背景

当前 WebTerm 部署使用 Node 版中转服务（`relay-server/main.js`），通过 `serveStatic` 将 `web/` 目录的静态前端文件直接托管在 Node 进程中。Go 版中转服务（`go-core/internal/relayapp/app.go`）目前只注册了 API 和 WebSocket 路由，缺少静态文件托管逻辑。

本次设计目标：让 Go Relay 的生产部署能够托管前端静态网页，实现完整的"开箱可用"部署。

## 方案选型

**选择方案 A：Nginx 反向代理**

```
浏览器/客户端
    │
    ▼
┌─────────────────────────────────────┐
│  Nginx (:9000)                      │
│                                     │
│  /api/*  ──proxy──▶  Go Relay      │
│  /ws/*   ──proxy──▶  (:19090)      │
│  /*      ──static──▶  web/ 目录     │
│  (SPA fallback → index.html)       │
└─────────────────────────────────────┘
```

理由：
- 用户场景是部署到自有服务器（生产环境），Nginx 是标准做法
- Go 代码无需改动，保持 relay 职责单一
- Nginx 静态文件性能优于 Go FileServer
- 便于后续扩展（SSL 终端、限流、gzip、缓存策略）

## 架构

### Docker Compose 双容器

| 容器 | 职责 | 对外暴露 |
|------|------|---------|
| `nginx` | 静态文件托管、反向代理、SPA fallback | `:9000` |
| `go-relay` | API 鉴权、设备管理、WebSocket 中转 | 仅容器内网 |

### 数据流

```
静态资源请求:
  浏览器 → Nginx → 直接返回 web/ 文件

API 请求:
  浏览器 → Nginx → proxy_pass → Go Relay (:19090) → 响应

WebSocket 请求:
  浏览器 → Nginx (Upgrade) → proxy_pass → Go Relay (:19090) → WS 连接
```

## 需要创建/修改的文件

### 1. 新建 `nginx.conf`

```nginx
server {
    listen 9000;
    server_name _;

    root /app/web;
    index index.html;

    # Gzip 压缩
    gzip on;
    gzip_types text/css application/javascript application/json image/svg+xml;
    gzip_min_length 256;

    # API 代理
    location /api/ {
        proxy_pass http://go-relay:19090;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # WebSocket 代理（长连接）
    location /ws/ {
        proxy_pass http://go-relay:19090;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
    }

    # 静态文件 + SPA fallback
    location / {
        try_files $uri $uri/ /index.html;

        # 静态资源缓存
        location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
            expires 30d;
            add_header Cache-Control "public, immutable";
        }
    }
}
```

### 2. 新建 `Dockerfile.go-relay`

Go Relay 的多阶段构建 Dockerfile：

```dockerfile
# 阶段一：编译 Go 二进制
FROM golang:1.25-alpine AS go-builder
WORKDIR /src
COPY go-core/go.mod go-core/go.sum ./
RUN go mod download
COPY go-core/ ./
RUN CGO_ENABLED=0 go build -ldflags="-s -w" -o /webterm-relay ./cmd/webterm-relay

# 阶段二：运行镜像
FROM alpine:3.21
RUN apk add --no-cache ca-certificates tzdata
WORKDIR /app
COPY --from=go-builder /webterm-relay .
EXPOSE 19090
CMD ["./webterm-relay"]
```

### 3. 修改 `docker-compose.yml`

```yaml
services:
  go-relay:
    build:
      context: .
      dockerfile: Dockerfile.go-relay
    container_name: webterm-go-relay
    volumes:
      - ./data:/app/data
    environment:
      - WEBTERM_RELAY_ADDR=0.0.0.0:19090
      - WEBTERM_RELAY_STORE_PATH=/app/data/relay.db
      - WEBTERM_RELAY_BOOTSTRAP_USER=${RELAY_BOOTSTRAP_USER:-admin}
      - WEBTERM_RELAY_BOOTSTRAP_PASSWORD=${RELAY_BOOTSTRAP_PASSWORD:?required}
    restart: always

  nginx:
    image: nginx:alpine
    container_name: webterm-nginx
    ports:
      - "9000:9000"
    volumes:
      - ./web:/app/web:ro
      - ./nginx.conf:/etc/nginx/conf.d/default.conf:ro
    depends_on:
      - go-relay
    restart: always
```

### 4. 修改 `deploy.sh`

主要变更：
- 上传前需要先执行 `npm run build` 生成 `web/` 静态文件（或确保已存在）
- 远程服务器上不再需要 Node.js 运行时
- 环境变量中增加 `RELAY_BOOTSTRAP_USER` / `RELAY_BOOTSTRAP_PASSWORD` 的配置引导

### 5. 无需修改

- `go-core/internal/relayapp/app.go` — 不添加静态文件托管
- `go-core/cmd/webterm-relay/main.go` — 无需改动
- `web/` 目录 — 前端代码无需改动

## 路由对照表

| 请求路径 | Nginx 行为 | 到达 Go Relay |
|---------|-----------|--------------|
| `/` | 返回 `web/index.html` | 否 |
| `/assets/*.js` | 直接返回静态文件（30天缓存） | 否 |
| `/api/auth/login` | 代理 | 是 |
| `/api/devices` | 代理 | 是 |
| `/api/devices/{id}/enable` | 代理 | 是 |
| `/api/devices/{id}/rotate-credential` | 代理 | 是 |
| `/api/sessions` | 代理 | 是 |
| `/api/p2p/offer` | 代理 | 是 |
| `/ws/agent` | WebSocket 升级代理 | 是 |
| `/ws/terminal` | WebSocket 升级代理 | 是 |
| `/ws/terminal/...` | WebSocket 升级代理 | 是 |
| 任意不匹配路径 | SPA fallback → `index.html` | 否 |

## 部署流程

```bash
# 1. 构建前端（如果 web/ 不存在）
npm run build

# 2. 设置环境变量
export RELAY_BOOTSTRAP_USER=admin
export RELAY_BOOTSTRAP_PASSWORD=your-secure-password

# 3. 构建并启动
docker compose up -d --build

# 4. 验证
curl http://localhost:9000/          # 应返回 index.html
curl http://localhost:9000/api/devices  # 应返回 JSON（需要先登录）
```

## 注意事项

1. **WebSocket 代理超时**：`proxy_read_timeout` 设为 86400s（24小时），防止长时间无数据传输时 Nginx 切断 WebSocket 连接。
2. **SPA 路由**：前端使用客户端路由（如 Vue Router），Nginx 需要 `try_files $uri /index.html` 将所有非文件路径回退到 `index.html`。
3. **Go Relay 不对外暴露**：`go-relay` 容器不映射端口到宿主机，仅通过容器内网 `go-relay:19090` 被 Nginx 访问。
4. **静态资源缓存**：JS/CSS 等带 hash 的资源文件设置 30 天强缓存。
5. **SSL 终端**：当前 `deploy.sh` 中在宿主机生成自签名证书的逻辑可以移除，改为在 Nginx 层处理（后续可在 nginx.conf 中增加 SSL 配置）。

## 与现有 Node 部署的对比

| 维度 | Node 部署（旧） | Go + Nginx 部署（新） |
|------|----------------|---------------------|
| 静态文件 | Node serveStatic | Nginx 直接返回 |
| 运行时 | Node.js | Go 二进制 + Nginx |
| 容器数 | 1 | 2 |
| 内存占用 | ~80-150MB | ~20MB (Go) + ~10MB (Nginx) |
| 设备管理 API | Node routes.js | Go relaycontrol/server.go |
| P2P | Node 支持 | Go 暂不支持（返回 503） |
