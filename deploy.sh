#!/bin/bash

# ==========================================
# WebTerm 中转服务一键部署脚本
# ==========================================

# 加载本地 .env.local 环境变量
if [ -f ".env.local" ]; then
    echo "💡 检测到 .env.local，正在加载本地配置的环境变量..."
    while IFS= read -r line || [ -n "$line" ]; do
        # 忽略空行和 # 开头的注释
        if [[ ! "$line" =~ ^[[:space:]]*# ]] && [[ "$line" =~ ^[a-zA-Z_][a-zA-Z0-9_]*= ]]; then
            export "$line"
        fi
    done < ".env.local"
fi

DRY_RUN=0
for arg in "$@"; do
    case "$arg" in
        --dry-run)
            DRY_RUN=1
            ;;
        --help|-h)
            echo "Usage: RELAY_BOOTSTRAP_PASSWORD='强密码' ./deploy.sh [--dry-run]"
            echo ""
            echo "Environment:"
            echo "  RELAY_BOOTSTRAP_USER            默认 admin"
            echo "  RELAY_BOOTSTRAP_PASSWORD        必填"
            echo "  WEBTERM_RELAY_PUBLIC_URL        可选，生产域名"
            echo "  WEBTERM_RELAY_ALLOW_REGISTRATION 默认 1"
            echo "  WEBTERM_RELAY_REQUIRE_EMAIL_OTP 默认 0"
            echo "  WEBTERM_RELAY_SMTP_*            可选，OTP 邮件配置"
            echo "  WEBTERM_DISABLE_P2P             默认 0"
            exit 0
            ;;
        *)
            echo "❌ 未知参数: $arg"
            echo "运行 ./deploy.sh --help 查看用法。"
            exit 1
            ;;
    esac
done

# 1. 部署配置 (请根据您的服务器实际情况进行修改)
SERVER_IP="120.46.85.237"
SERVER_USER="root"
SERVER_PORT="22"

# 检查前端构建产物是否存在
if [ ! -f "web/index.html" ]; then
    echo "❌ 错误: web/ 目录下没有找到前端构建产物。"
    echo "请先运行: npm run build"
    exit 1
fi

if [ -z "${RELAY_BOOTSTRAP_PASSWORD:-}" ]; then
    echo "❌ 错误: 请先设置 RELAY_BOOTSTRAP_PASSWORD，不能使用默认管理员密码部署。"
    echo "示例: RELAY_BOOTSTRAP_USER=admin RELAY_BOOTSTRAP_PASSWORD='你的强密码' ./deploy.sh"
    exit 1
fi

RELAY_BOOTSTRAP_USER="${RELAY_BOOTSTRAP_USER:-admin}"
WEBTERM_RELAY_ALLOW_REGISTRATION="${WEBTERM_RELAY_ALLOW_REGISTRATION:-1}"
WEBTERM_RELAY_REQUIRE_EMAIL_OTP="${WEBTERM_RELAY_REQUIRE_EMAIL_OTP:-0}"
WEBTERM_RELAY_DEV_PRINT_OTP="${WEBTERM_RELAY_DEV_PRINT_OTP:-0}"
WEBTERM_DISABLE_P2P="${WEBTERM_DISABLE_P2P:-0}"

shell_quote() {
    printf '%q' "$1"
}

Q_RELAY_BOOTSTRAP_USER="$(shell_quote "$RELAY_BOOTSTRAP_USER")"
Q_RELAY_BOOTSTRAP_PASSWORD="$(shell_quote "$RELAY_BOOTSTRAP_PASSWORD")"
Q_WEBTERM_RELAY_PUBLIC_URL="$(shell_quote "${WEBTERM_RELAY_PUBLIC_URL:-}")"
Q_WEBTERM_RELAY_ALLOW_REGISTRATION="$(shell_quote "$WEBTERM_RELAY_ALLOW_REGISTRATION")"
Q_WEBTERM_RELAY_REQUIRE_EMAIL_OTP="$(shell_quote "$WEBTERM_RELAY_REQUIRE_EMAIL_OTP")"
Q_WEBTERM_RELAY_DEV_PRINT_OTP="$(shell_quote "$WEBTERM_RELAY_DEV_PRINT_OTP")"
Q_WEBTERM_RELAY_SMTP_HOST="$(shell_quote "${WEBTERM_RELAY_SMTP_HOST:-}")"
Q_WEBTERM_RELAY_SMTP_PORT="$(shell_quote "${WEBTERM_RELAY_SMTP_PORT:-}")"
Q_WEBTERM_RELAY_SMTP_USERNAME="$(shell_quote "${WEBTERM_RELAY_SMTP_USERNAME:-}")"
Q_WEBTERM_RELAY_SMTP_PASSWORD="$(shell_quote "${WEBTERM_RELAY_SMTP_PASSWORD:-}")"
Q_WEBTERM_RELAY_SMTP_FROM="$(shell_quote "${WEBTERM_RELAY_SMTP_FROM:-}")"
Q_WEBTERM_DISABLE_P2P="$(shell_quote "$WEBTERM_DISABLE_P2P")"

REMOTE_DIR="/opt/webterm-relay-go"

# 检查服务器 IP 是否被修改
if [ "$SERVER_IP" = "您的服务器IP" ]; then
    echo "❌ 错误: 请先配置 deploy.sh 中的服务器 IP (SERVER_IP) 后再运行。"
    echo "您可以使用文本编辑器打开并编辑此脚本。"
    exit 1
fi

echo "🔎 正在校验 Go Relay 部署配置..."
npm run validate:deploy

REMOTE_COMMAND="
    cd ${REMOTE_DIR} && \
    tar -xzf webterm_deploy_temp.tar.gz && \
    rm -f webterm_deploy_temp.tar.gz && \
    mkdir -p data && \
    echo '🐳 正在启动 Docker Compose (Nginx + Go Relay)...' && \
    docker compose down && \
    export RELAY_BOOTSTRAP_USER=${Q_RELAY_BOOTSTRAP_USER} && \
    export RELAY_BOOTSTRAP_PASSWORD=${Q_RELAY_BOOTSTRAP_PASSWORD} && \
    export WEBTERM_RELAY_PUBLIC_URL=${Q_WEBTERM_RELAY_PUBLIC_URL} && \
    export WEBTERM_RELAY_ALLOW_REGISTRATION=${Q_WEBTERM_RELAY_ALLOW_REGISTRATION} && \
    export WEBTERM_RELAY_REQUIRE_EMAIL_OTP=${Q_WEBTERM_RELAY_REQUIRE_EMAIL_OTP} && \
    export WEBTERM_RELAY_DEV_PRINT_OTP=${Q_WEBTERM_RELAY_DEV_PRINT_OTP} && \
    export WEBTERM_RELAY_SMTP_HOST=${Q_WEBTERM_RELAY_SMTP_HOST} && \
    export WEBTERM_RELAY_SMTP_PORT=${Q_WEBTERM_RELAY_SMTP_PORT} && \
    export WEBTERM_RELAY_SMTP_USERNAME=${Q_WEBTERM_RELAY_SMTP_USERNAME} && \
    export WEBTERM_RELAY_SMTP_PASSWORD=${Q_WEBTERM_RELAY_SMTP_PASSWORD} && \
    export WEBTERM_RELAY_SMTP_FROM=${Q_WEBTERM_RELAY_SMTP_FROM} && \
    export WEBTERM_DISABLE_P2P=${Q_WEBTERM_DISABLE_P2P} && \
    docker compose up -d --build
"

if [ "$DRY_RUN" = "1" ]; then
    echo "🧪 dry-run: 不打包、不上传、不执行 SSH。"
    echo "目标服务器: ${SERVER_USER}@${SERVER_IP}:${SERVER_PORT}"
    echo "远程目录: ${REMOTE_DIR}"
    echo "将使用的关键配置:"
    echo "  RELAY_BOOTSTRAP_USER=${RELAY_BOOTSTRAP_USER}"
    echo "  WEBTERM_RELAY_PUBLIC_URL=${WEBTERM_RELAY_PUBLIC_URL:-}"
    echo "  WEBTERM_RELAY_ALLOW_REGISTRATION=${WEBTERM_RELAY_ALLOW_REGISTRATION}"
    echo "  WEBTERM_RELAY_REQUIRE_EMAIL_OTP=${WEBTERM_RELAY_REQUIRE_EMAIL_OTP}"
    echo "  WEBTERM_DISABLE_P2P=${WEBTERM_DISABLE_P2P}"
    echo "远程动作:"
    echo "  1. 解压 webterm_deploy_temp.tar.gz 到 ${REMOTE_DIR}"
    echo "  2. 创建 data/ 持久化目录"
    echo "  3. docker compose down"
    echo "  4. 注入 Relay 环境变量（密码和 SMTP 密码不打印）"
    echo "  5. docker compose up -d --build"
    exit 0
fi

echo "📦 [1/3] 正在本地打包项目文件..."
# 创建临时包，排除不需要传输的大目录
TEMP_TAR="/tmp/webterm_deploy_temp.tar.gz"
tar --exclude='node_modules' \
    --exclude='data' \
    --exclude='.git' \
    --exclude='.npm-cache' \
    --exclude='test-results' \
    --exclude='tests' \
    --exclude='go-core/webterm-agent' \
    --exclude='go-core/webterm-flow-smoke' \
    --exclude='go-core/webterm-relay-flow-smoke' \
    --exclude='android-client' \
    --exclude='go-core/.gocache' \
    --exclude='*.zip' \
    -czf "$TEMP_TAR" .

echo "🚀 [2/3] 正在上传压缩包到服务器 (${SERVER_USER}@${SERVER_IP}:${SERVER_PORT})..."
# 在服务器创建目标目录
ssh -p "${SERVER_PORT}" -o StrictHostKeyChecking=no "${SERVER_USER}@${SERVER_IP}" "mkdir -p ${REMOTE_DIR}"

# 上传压缩包
scp -P "${SERVER_PORT}" -o StrictHostKeyChecking=no "$TEMP_TAR" "${SERVER_USER}@${SERVER_IP}:${REMOTE_DIR}/"

# 清理本地临时文件
rm -f "$TEMP_TAR"

echo "⚙️ [3/3] 正在远程执行部署指令..."
# 远程解压、清理压缩包、构建并启动 Docker 容器
ssh -p "${SERVER_PORT}" -o StrictHostKeyChecking=no "${SERVER_USER}@${SERVER_IP}" "$REMOTE_COMMAND"

echo "=========================================="
echo "🎉 部署完成！"
echo "您可以尝试访问: http://${SERVER_IP}:9001"
echo ""
echo "⚠️  重要：请立即修改默认管理员密码！"
echo "  ssh -p ${SERVER_PORT} ${SERVER_USER}@${SERVER_IP}"
echo "  cd ${REMOTE_DIR}"
echo "  export RELAY_BOOTSTRAP_USER=admin && export RELAY_BOOTSTRAP_PASSWORD=你的新密码 && docker compose down && docker compose up -d --build"
echo ""
echo "查看容器日志:"
echo "  ssh -p ${SERVER_PORT} ${SERVER_USER}@${SERVER_IP} 'cd ${REMOTE_DIR} && docker compose logs -f'"
echo ""
echo "部署后建议在本机执行外部 smoke:"
echo "  WEBTERM_SMOKE_EMAIL=${RELAY_BOOTSTRAP_USER} WEBTERM_SMOKE_PASSWORD='你的密码' npm run smoke:web-go-relay-pc-agent -- --relay-url http://${SERVER_IP}:9001 --register false --p2p --expect-p2p --timeout 120000"
echo "=========================================="
