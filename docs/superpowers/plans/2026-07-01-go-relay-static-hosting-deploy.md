# Go Relay 静态网页托管与部署 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 WebTerm 生产部署从 Node 单容器切换为 Nginx + Go Relay 双容器架构，由 Nginx 托管前端静态文件并反向代理 API/WS 到 Go Relay。

**Architecture:** 两个 Docker 容器 — `nginx`（对外 :9000，托管 web/ 静态文件，代理 /api/* 和 /ws/* 到 Go）和 `go-relay`（仅容器内网可达，处理认证、设备管理、WebSocket 中转）。

**Tech Stack:** Nginx Alpine, Go 1.25, Docker Compose

## Global Constraints

- Go 代码不做任何改动（`app.go`、`main.go` 保持原样）
- 前端 `web/` 目录不做任何改动
- 保留现有 `deploy.sh` 的远程部署流程
- 环境变量 `RELAY_BOOTSTRAP_USER` / `RELAY_BOOTSTRAP_PASSWORD` 用于初始化管理员账号
- Go Relay 监听 `0.0.0.0:19090`（容器内网），不暴露到宿主机
- Nginx 监听 `9000`，对外暴露

---

### Task 1: 创建 Nginx 配置文件

**Files:**
- Create: `nginx.conf`

**Interfaces:**
- Consumes: 无
- Produces: Nginx 配置，代理规则如下：
  - `/api/*` → `http://go-relay:19090`（HTTP 代理）
  - `/ws/*` → `http://go-relay:19090`（WebSocket 升级代理，`proxy_read_timeout 86400s`）
  - `/*` → `web/` 目录静态文件，`try_files $uri /index.html`（SPA fallback）
  - `*.js|css|png|...` → 30天强缓存

- [ ] **Step 1: 创建 `nginx.conf`**

```bash
cat > nginx.conf << 'NGINX_EOF'
server {
    listen 9000;
    server_name _;

    root /app/web;
    index index.html;

    # Gzip 压缩
    gzip on;
    gzip_types text/css application/javascript application/json image/svg+xml;
    gzip_min_length 256;

    # API 代理到 Go Relay
    location /api/ {
        proxy_pass http://go-relay:19090;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # WebSocket 代理到 Go Relay（长连接，24h 超时）
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

        # 带 hash 的静态资源强缓存
        location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
            expires 30d;
            add_header Cache-Control "public, immutable";
        }
    }
}
NGINX_EOF
```

- [ ] **Step 2: 验证 nginx 配置语法**

```bash
# 用 docker 临时跑 nginx 验证配置语法
docker run --rm -v $(pwd)/nginx.conf:/etc/nginx/conf.d/default.conf:ro nginx:alpine nginx -t
```

Expected: `nginx: the configuration file ... syntax is ok` / `nginx: configuration file ... test is successful`

- [ ] **Step 3: 提交**

```bash
git add nginx.conf
git commit -m "feat: add nginx reverse proxy config for Go Relay deployment"
```

---

### Task 2: 创建 Go Relay Dockerfile

**Files:**
- Create: `Dockerfile.go-relay`

**Interfaces:**
- Consumes: `go-core/go.mod`、`go-core/go.sum`、`go-core/cmd/webterm-relay/main.go`、`go-core/internal/` 全部
- Produces: `webterm-relay` 二进制（alpine 运行镜像，暴露 19090）

- [ ] **Step 1: 创建 `Dockerfile.go-relay`**

```bash
cat > Dockerfile.go-relay << 'DOCKER_EOF'
# 阶段一：编译 Go 二进制
FROM golang:1.25-alpine AS go-builder
WORKDIR /src
COPY go-core/go.mod go-core/go.sum ./
RUN go mod download
COPY go-core/ ./
RUN CGO_ENABLED=0 go build -ldflags="-s -w" -o /webterm-relay ./cmd/webterm-relay

# 阶段二：最小运行镜像
FROM alpine:3.21
RUN apk add --no-cache ca-certificates tzdata
WORKDIR /app
COPY --from=go-builder /webterm-relay .
EXPOSE 19090
CMD ["./webterm-relay"]
DOCKER_EOF
```

- [ ] **Step 2: 验证 Docker 构建**

```bash
DOCKER_BUILDKIT=1 docker build -f Dockerfile.go-relay -t webterm-go-relay:test .
```

Expected: 构建成功，输出 `naming to docker.io/library/webterm-go-relay:test`

- [ ] **Step 3: 验证 Go 二进制可启动**

```bash
docker run --rm \
  -e WEBTERM_RELAY_ADDR=0.0.0.0:19090 \
  -e WEBTERM_RELAY_BOOTSTRAP_USER=test \
  -e WEBTERM_RELAY_BOOTSTRAP_PASSWORD=testpass \
  webterm-go-relay:test &
# 等待启动
sleep 3
# 验证 HTTP 响应
curl -s http://localhost:19090/api/devices
# 停止
docker stop $(docker ps -q --filter ancestor=webterm-go-relay:test)
```

Expected: `curl` 返回 JSON 格式的 401 或空数组响应（取决于认证），不是连接拒绝。

- [ ] **Step 4: 提交**

```bash
git add Dockerfile.go-relay
git commit -m "feat: add multi-stage Dockerfile for Go Relay binary"
```

---

### Task 3: 修改 docker-compose.yml

**Files:**
- Modify: `docker-compose.yml`

**Interfaces:**
- Consumes: `Dockerfile.go-relay`（Task 2）、`nginx.conf`（Task 1）、`web/`（前端构建产物）
- Produces: 双容器编排：`go-relay` + `nginx`

**当前内容：**
```yaml
services:
  webterm-relay:
    build: .
    container_name: webterm-relay
    ports:
      - "9000:9000"
    volumes:
      - ./data:/app/data
    environment:
      - RELAY_PORT=9000
      - JWT_SECRET=a2489c170288d1b662efccc48e8d776d9278f0a61e5cbbdffcf12e4b03cc9ad
      - NODE_ENV=production
      - SMTP_HOST=smtp.qq.com
      - SMTP_PORT=465
      - SMTP_USER=a18118053911@qq.com
      - SMTP_PASS=rckjbrwkecrucbhd
      - SMTP_FROM=a18118053911@qq.com
    restart: always
```

- [ ] **Step 1: 替换 `docker-compose.yml` 为双容器编排**

用 Write 工具将 `docker-compose.yml` 替换为以下内容：

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

- [ ] **Step 2: 验证配置语法**

```bash
docker compose config
```

Expected: 输出解析后的完整 compose 配置，无错误。

- [ ] **Step 3: 提交**

```bash
git add docker-compose.yml
git commit -m "feat: switch docker-compose to Nginx + Go Relay dual-container"
```

---

### Task 4: 修改 deploy.sh 部署脚本

**Files:**
- Modify: `deploy.sh`

**Interfaces:**
- Consumes: `nginx.conf`（Task 1）、`Dockerfile.go-relay`（Task 2）、`docker-compose.yml`（Task 3）、`web/`（前端构建产物）
- Produces: 更新后的远程部署流程

**当前 `deploy.sh` 需要变更的点：**
1. 打包时排除 `node_modules`（已有），但需要确保 `web/` 目录存在
2. 远程解压后不再需要生成自签名 SSL 证书（该逻辑属于旧 Node 部署的 HTTPS）
3. 远程执行 `docker compose up -d --build`（已有，不变）
4. 增加环境变量提示

- [ ] **Step 1: 修改 `deploy.sh`**

用 Edit 工具做以下修改：

**修改 1：在 `# 1. 部署配置` 之后增加 web/ 目录检查**

在 `SERVER_PORT="22"` 之后、`REMOTE_DIR="/opt/webterm-relay"` 之前插入：

```bash
# 检查前端构建产物是否存在
if [ ! -f "web/index.html" ]; then
    echo "❌ 错误: web/ 目录下没有找到前端构建产物。"
    echo "请先运行: npm run build"
    exit 1
fi
```

**修改 2：在 echo "📦 [1/3] 正在本地打包项目文件..." 之后，增加排除 go-core 编译产物的 tar 参数**

将：
```bash
tar --exclude='node_modules' \
    --exclude='data' \
    --exclude='.git' \
    --exclude='.npm-cache' \
    --exclude='test-results' \
    --exclude='tests' \
    -czf "$TEMP_TAR" .
```

改为：
```bash
tar --exclude='node_modules' \
    --exclude='data' \
    --exclude='.git' \
    --exclude='.npm-cache' \
    --exclude='test-results' \
    --exclude='tests' \
    --exclude='go-core/webterm-agent' \
    --exclude='go-core/webterm-flow-smoke' \
    --exclude='go-core/webterm-relay-flow-smoke' \
    -czf "$TEMP_TAR" .
```

**修改 3：替换远程执行指令中的证书生成逻辑**

将：
```bash
ssh -p "${SERVER_PORT}" -o StrictHostKeyChecking=no "${SERVER_USER}@${SERVER_IP}" "
    cd ${REMOTE_DIR} && \
    mkdir -p data/certs && \
    if [ ! -f data/certs/key.pem ] || [ ! -f data/certs/cert.pem ]; then
        echo '🔑 正在服务器生成自签名 SSL 证书...' && \
        openssl req -x509 -nodes -days 3650 -newkey rsa:2048 -keyout data/certs/key.pem -out data/certs/cert.pem -subj '/CN=${SERVER_IP}'
    fi && \
    tar -xzf webterm_deploy_temp.tar.gz && \
    rm -f webterm_deploy_temp.tar.gz && \
    echo '🐳 正在启动 Docker Compose...' && \
    docker compose down && \
    docker compose up -d --build
"
```

改为：
```bash
ssh -p "${SERVER_PORT}" -o StrictHostKeyChecking=no "${SERVER_USER}@${SERVER_IP}" "
    cd ${REMOTE_DIR} && \
    tar -xzf webterm_deploy_temp.tar.gz && \
    rm -f webterm_deploy_temp.tar.gz && \
    mkdir -p data && \
    echo '🐳 正在启动 Docker Compose (Nginx + Go Relay)...' && \
    docker compose down && \
    RELAY_BOOTSTRAP_USER=admin RELAY_BOOTSTRAP_PASSWORD=changeme docker compose up -d --build
"
```

**修改 4：更新部署完成提示信息**

将：
```bash
echo "=========================================="
echo "🎉 部署完成！"
echo "您可以尝试访问: https://${SERVER_IP}:9000"
echo "如果需要查看服务器容器日志，请运行: ssh -p ${SERVER_PORT} ${SERVER_USER}@${SERVER_IP} 'cd ${REMOTE_DIR} && docker compose logs -f'"
echo "=========================================="
```

改为：
```bash
echo "=========================================="
echo "🎉 部署完成！"
echo "您可以尝试访问: http://${SERVER_IP}:9000"
echo ""
echo "⚠️  重要：请立即修改默认管理员密码！"
echo "  ssh -p ${SERVER_PORT} ${SERVER_USER}@${SERVER_IP}"
echo "  cd ${REMOTE_DIR}"
echo "  RELAY_BOOTSTRAP_USER=admin RELAY_BOOTSTRAP_PASSWORD=你的新密码 docker compose up -d"
echo ""
echo "查看容器日志:"
echo "  ssh -p ${SERVER_PORT} ${SERVER_USER}@${SERVER_IP} 'cd ${REMOTE_DIR} && docker compose logs -f'"
echo "=========================================="
```

- [ ] **Step 2: 提交**

```bash
git add deploy.sh
git commit -m "feat: update deploy.sh for Nginx + Go Relay dual-container deployment"
```

---

### Task 5: 端到端验证

**Files:**
- 无新建/修改（验证用）

- [ ] **Step 1: 本地完整启动验证**

```bash
# 确保前端已构建
npm run build

# 设置环境变量并启动
RELAY_BOOTSTRAP_USER=admin RELAY_BOOTSTRAP_PASSWORD=test123 docker compose up -d --build

# 等待启动
sleep 5
```

- [ ] **Step 2: 验证静态文件托管**

```bash
# 验证首页返回 HTML
curl -s http://localhost:9000/ | head -1
```

Expected: `<!doctype html>`

- [ ] **Step 3: 验证 API 代理**

```bash
# 验证 API 代理（应返回 401，说明代理通了但未认证）
curl -s http://localhost:9000/api/devices
```

Expected: `{"error":"unauthorized"}`（JSON 格式，说明请求到达了 Go Relay）

- [ ] **Step 4: 验证登录 API**

```bash
# 登录
curl -s -X POST http://localhost:9000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"test123"}'
```

Expected: 返回 `{"id":"...","username":"admin","role":"admin","mode":"relay"}`

- [ ] **Step 5: 验证 SPA fallback**

```bash
# 访问一个前端路由路径（非文件）
curl -s http://localhost:9000/devices | head -1
```

Expected: `<!doctype html>`（返回 index.html，非 404）

- [ ] **Step 6: 清理**

```bash
docker compose down
```

- [ ] **Step 7: 提交验证通过标记**

```bash
git add -A
git commit -m "verify: end-to-end Nginx + Go Relay deployment smoke test passed"
```
