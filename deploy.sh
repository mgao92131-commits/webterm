#!/bin/bash

# ==========================================
# WebTerm 中转服务一键部署脚本
# ==========================================

# 1. 部署配置 (请根据您的服务器实际情况进行修改)
SERVER_IP="120.46.85.237"
SERVER_USER="root"
SERVER_PORT="22"
REMOTE_DIR="/opt/webterm-relay"

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
    -czf "$TEMP_TAR" .

echo "🚀 [2/3] 正在上传压缩包到服务器 (${SERVER_USER}@${SERVER_IP}:${SERVER_PORT})..."
# 在服务器创建目标目录
ssh -p "${SERVER_PORT}" -o StrictHostKeyChecking=no "${SERVER_USER}@${SERVER_IP}" "mkdir -p ${REMOTE_DIR}"

# 上传压缩包
scp -P "${SERVER_PORT}" -o StrictHostKeyChecking=no "$TEMP_TAR" "${SERVER_USER}@${SERVER_IP}:${REMOTE_DIR}/"

# 清理本地临时文件
rm -f "$TEMP_TAR"

echo "⚙️ [3/3] 正在远程执行部署指令..."
# 远程解压、创建证书（如果不存在）、清理压缩包、构建并启动 Docker 容器
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

echo "=========================================="
echo "🎉 部署完成！"
echo "您可以尝试访问: https://${SERVER_IP}:9000"
echo "如果需要查看服务器容器日志，请运行: ssh -p ${SERVER_PORT} ${SERVER_USER}@${SERVER_IP} 'cd ${REMOTE_DIR} && docker compose logs -f'"
echo "=========================================="
