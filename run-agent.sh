#!/bin/bash
set -e

# 获取当前脚本所在的项目根目录
PROJECT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# 重写当前 shell 的环境变量，防止它们覆盖配置文件中的独立配置
export WEBTERM_CONTROL_ADDR="127.0.0.1:18082"
export WEBTERM_SOCKET_PATH="$PROJECT_DIR/webterm-clone.sock"
export WEBTERM_MODE="direct"

echo "🔨 正在编译 Go Agent (命名为 webterm-agent-clone)..."
cd "$PROJECT_DIR/go-core"
go build -o webterm-agent-clone ./cmd/webterm-agent

echo "🚀 启动 Go Agent..."
./webterm-agent-clone \
  --mode direct \
  --config "$PROJECT_DIR/agent-config-clone.json" \
  --socket "$PROJECT_DIR/webterm-clone.sock"
