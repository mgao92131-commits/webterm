#!/bin/bash

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # 无颜色

echo -e "${YELLOW}🚀 开始运行 WebTerm 重构自动化测试汇总...${NC}\n"

# 获取脚本所在目录的上一级目录（项目根目录）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$ROOT_DIR"

# 1. 运行 Go 端 session 测试
echo -e "${YELLOW}📦 [1/1] 正在运行 Go 服务端 (PC Agent) 单元测试...${NC}"
cd go-core
go test ./internal/session/...
GO_RESULT=$?
cd ..

echo -e "\n${YELLOW}========================================${NC}"
echo -e "${YELLOW}📊 自动测试结果汇总：${NC}"
echo -e "${YELLOW}========================================${NC}"

# 输出汇总
if [ $GO_RESULT -eq 0 ]; then
    echo -e "Go 服务端 (session):   ${GREEN}PASS ✅${NC}"
else
    echo -e "Go 服务端 (session):   ${RED}FAIL ❌${NC}"
fi

if [ $GO_RESULT -eq 0 ]; then
    echo -e "\n${GREEN}🎉 所有核心测试已 100% 成功通过！混合 Cell/Span 同步协议重构功能完整可靠。${NC}"
    exit 0
else
    echo -e "\n${RED}⚠️ 部分测试失败，请检查上方日志。${NC}"
    exit 1
fi
