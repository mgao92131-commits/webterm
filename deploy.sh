#!/bin/bash

# ==========================================
# WebTerm 中转服务一键部署脚本
# ==========================================

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

REMOTE_DIR="/opt/webterm-relay-go"

# 检查服务器 IP 是否被修改
if [ "$SERVER_IP" = "您的服务器IP" ]; then
    echo "❌ 错误: 请先配置 deploy.sh 中的服务器 IP (SERVER_IP) 后再运行。"
    echo "您可以使用文本编辑器打开并编辑此脚本。"
    exit 1
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
ssh -p "${SERVER_PORT}" -o StrictHostKeyChecking=no "${SERVER_USER}@${SERVER_IP}" "
    cd ${REMOTE_DIR} && \
    tar -xzf webterm_deploy_temp.tar.gz && \
    rm -f webterm_deploy_temp.tar.gz && \
    mkdir -p data && \
    echo '🐳 正在启动 Docker Compose (Nginx + Go Relay)...' && \
    docker compose down && \
    RELAY_BOOTSTRAP_USER=admin RELAY_BOOTSTRAP_PASSWORD=changeme docker compose up -d --build
"

echo "=========================================="
echo "🎉 部署完成！"
echo "您可以尝试访问: http://${SERVER_IP}:9001"
echo ""
echo "⚠️  重要：请立即修改默认管理员密码！"
echo "  ssh -p ${SERVER_PORT} ${SERVER_USER}@${SERVER_IP}"
echo "  cd ${REMOTE_DIR}"
echo "  RELAY_BOOTSTRAP_USER=admin RELAY_BOOTSTRAP_PASSWORD=你的新密码 docker compose up -d"
echo ""
echo "查看容器日志:"
echo "  ssh -p ${SERVER_PORT} ${SERVER_USER}@${SERVER_IP} 'cd ${REMOTE_DIR} && docker compose logs -f'"
echo "=========================================="
