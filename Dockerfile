# 阶段一：构建前端和安装依赖
FROM node:20-slim AS builder

WORKDIR /app

# 替换为国内中科大镜像源以加速 apt-get 下载
RUN sed -i 's/deb.debian.org/mirrors.ustc.edu.cn/g' /etc/apt/sources.list.d/debian.sources || true
RUN sed -i 's/security.debian.org/mirrors.ustc.edu.cn/g' /etc/apt/sources.list.d/debian.sources || true

# 安装 C++ 编译和构建工具以支持 better-sqlite3 (node-gyp) 和 node-datachannel (cmake-js) 的本地编译
RUN apt-get update && apt-get install -y \
    python3 \
    make \
    g++ \
    cmake \
    && rm -rf /var/lib/apt/lists/*

# 拷贝依赖配置
COPY package*.json ./

# 设置 npm 镜像源为淘宝镜像以加速依赖包下载
RUN npm config set registry https://registry.npmmirror.com

# 创建空的 postinstall 脚本占位文件，以防在未拷贝源码时 npm ci 触发的 postinstall 脚本报错
RUN mkdir -p server && touch server/ensure-node-pty-helper.js

# 安装完整依赖（正常执行原生 C++ 模块如 better-sqlite3 的编译脚本）
RUN npm ci

# 拷贝项目源码
COPY . .

# 执行前端打包，输出静态文件到 web/ 目录
RUN npm run build

# 阶段二：生产运行环境
FROM node:20-slim

WORKDIR /app

# 从 builder 阶段拷贝编译好的依赖和静态资源，避免在运行镜像中保留冗余的编译工具
COPY --from=builder /app/package*.json ./
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/web ./web
COPY --from=builder /app/relay-server ./relay-server
COPY --from=builder /app/server ./server
COPY --from=builder /app/shared ./shared

# 声明 SQLite 数据库持久化目录
VOLUME ["/app/data"]

# 默认中转服务端口
EXPOSE 9000

# 启动中转服务
CMD ["node", "relay-server/main.js"]
