# WebTerm — AI 编码代理指南

本文档面向对项目一无所知的 AI 编码代理，概述项目架构、构建测试方式和开发约定。

## 项目概览

WebTerm 是一个**个人自用的多设备 Web 终端聚合工具**。核心能力：在被控电脑（PC Agent）上通过 PTY 启动本地终端会话，浏览器或 Android 客户端通过直连或中转服务器（Relay）远程操控这些终端；同时支持 Claude Code / Kimi Code / Codex 等 AI Agent 通过 hook 上报任务状态。

仓库是一个单仓多端项目，由三部分组成：

| 部分 | 目录 | 技术栈 |
| --- | --- | --- |
| Go 核心 | `go-core/` | Go 1.25，标准库 + `creack/pty`、`go-headless-term`、`nhooyr.io/websocket` |
| Web 前端 | `frontend/`（构建产物输出到 `web/`） | Vue 3 + TypeScript + Vite 5 + Tailwind CSS + xterm.js |
| Android 客户端 | `android-client/` | Kotlin + Gradle（复用 Termux 的 terminal-emulator/terminal-view 内核） |

仓库根目录另有 `shared/`（前端与脚本共用的 JS 协议代码）、`scripts/`（smoke 测试与部署辅助）、`docs/`（设计文档与重构计划）、部署相关文件（`Dockerfile.go-relay`、`docker-compose.yml`、`nginx.conf`、`deploy.sh`）。

## 运行时架构

### Go 二进制（`go-core/cmd/`）

- **`webterm-agent`**：PC 端 Agent，两种模式：
  - `--mode direct`：本机直接对外提供 HTTP/WebSocket 服务（默认 `127.0.0.1:18080`），同时托管 `web/` 静态前端。
  - `--mode relay`：通过 `RELAY_URL`/`RELAY_SECRET`/`DEVICE_NAME` 注册到 Relay，由 Relay 中转浏览器/手机流量。
- **`webterm-relay`**：多设备中转服务器（默认 `127.0.0.1:19090`）。负责设备注册、用户认证（JWT、可选邮箱 OTP、SMTP）、会话路由、持久化（JSON 文件 store，见 `WEBTERM_RELAY_STORE_PATH`）。
- **`webterm`**：CLI 工具，通过 Unix socket（`$HOME/.webterm/webterm.sock`）与本机 Agent 通信，`webterm agent-event` 用于 Agent hook 通知（详见 `docs/agent-hooks.md`）。
- `webterm-*-smoke`：各条链路的冒烟测试二进制。

### 关键 internal 包（`go-core/internal/`）

- `session/`：PTY 终端会话管理、终端快照（Cell/Span 同步协议，scrollback 契约与 `@xterm/headless` 保持一致）。
- `mux/`：单条 WebSocket 上的多通道复用（`VirtualSocket`、握手顺序控制）。
- `relaycore/`：Relay 帧协议（`CurrentFrameVersion = 0x01`，流打开/数据/关闭、HTTP/WS 转发帧、Ping/Pong）。
- `relaygateway/`、`relayrouter/`、`relaycontrol/`、`relaystore/`、`relaymetrics/`：Relay 的 HTTP/WS 网关、路由、认证控制、存储与指标。
- `relay/`、`relayapp/`、`direct/`：Agent 侧 relay 连接、Relay 应用组装、direct 模式服务器。
- `protocol/`：Agent 与 CLI 的本地 socket 协议。
- `agenthooks/`、`agentnotify/`、`hook/`：Agent hook 安装与事件分发。
- `config/`、`app/`、`application/`、`runtime/`、`infrastructure/`、`logs/`、`filesend/`、`fileupload/`、`control/`、`testutil/`：配置、应用组装、运行环境、日志、文件发送（Agent→端）与文件上传（端→Agent，`POST /api/sessions/{id}/upload`）等支撑代码。

### 前端（`frontend/src/`）

- `views/`：页面（`LoginView`、`RegisterView`、`DevicesView`、`ManagerView`、`TerminalView`），路由见 `router.ts`。
- `components/`：`TerminalPane`、`SessionGrid`、`DeviceDrawer`、`OtpInput` 等。
- `lib/`：终端核心逻辑 —— `relay-mux-session(-manager)`、`mux-protocol`、`terminal-binary-protocol`、`terminal-input(-controller)`、`terminal-layout`、`terminal-selection`、`terminal-write-queue` 等。
- `services/` + `api/`：认证/设备/会话服务与 HTTP 客户端；`store.ts` 为全局状态；`composables/` 为连接与主题逻辑。

路径别名：`@` → `frontend/src`，`@shared` → `shared`（`shared/` 定义了与 Go 端一致的隧道帧协议常量与编解码）。

### Android 客户端（`android-client/`）

Gradle 多模块（`compileSdk 36`，`minSdk 23`，仅 `arm64-v8a`，Hilt DI）：`app`（业务层）、`terminal-emulator`（ANSI 解析，源自 Termux）、`terminal-view`（Canvas 绘制）、`transport-api`/`transport-websocket`、`core-api`/`core-config`/`core-cache`/`core-session`/`core-relay`、`ui-common`/`terminal-ui`、`feature:{home,terminal,relay,settings}`。特色：内存-磁盘双级会话缓存、清屏时自动截断磁盘快照。`core-session` 内含 `filesend`（文件接收）与 `fileupload`（文件上传，经 `WebTermDeviceService` 持有、TRANSFER 通道通知）两套对称的传输控制器。详见 `android-client/README.md`。

## 构建与测试命令

前置：`npm install`（根目录）、Go 1.25+、Android 构建需要 Android SDK/NDK。

```sh
# 前端
npm run dev          # Vite 开发服务器 :3000，/api 与 /ws 代理到 127.0.0.1:18080
npm run build        # 构建到 web/（部署前置步骤）
npm run typecheck    # vue-tsc --noEmit
npm run test:unit    # Vitest（jsdom，匹配 frontend/src/**/*.test.ts(x)）

# Go
cd go-core && go test ./...                    # 全部单元测试（含 mux、relaycore、session 等）
go run ./cmd/webterm-agent --mode direct       # 本地起 Agent（等价于根目录 npm start）
go run ./cmd/webterm-relay                     # 本地起 Relay

# E2E（Playwright，自动以 direct 模式拉起 Agent，baseURL :18080）
npm run test:e2e

# 链路 smoke（Node 脚本，部分需要已部署的 Relay 与凭据环境变量）
npm run smoke:go-relay-server
npm run smoke:web-go-relay-pc-agent
npm run validate:deploy        # 部署配置静态校验

# Android（在 android-client/ 下）
./gradlew :app:assembleRelease
./gradlew test                 # JUnit + Mockito 单元测试
```

`scripts/run_all_tests.sh` 只跑 Go `internal/session` 测试，不是全量测试入口。

## 开发约定

- **语言**：项目文档、README、Go 代码注释、提交相关文字以**中文**为主；代码标识符、协议常量用英文。新增注释请沿用中文。
- **Go**：标准 `gofmt` 风格；包按职责细分在 `internal/` 下，协议相关常量与前后端共享逻辑需保持与 `shared/constants.js` / `shared/tunnel-protocol.js` 一致。
- **前端**：TypeScript strict 模式（`tsconfig.json`），Vue 3 `<script setup>` 风格；测试与源码同目录（`*.test.ts`）。
- **协议兼容性是硬约束**：终端快照格式、mux 帧、relay 帧在 Go 端、前端 `lib/`、Android 端三处都有实现，修改任一处必须同步其余实现；`docs/` 下的设计文档（尤其是 `docs/superpowers/specs/` 和 `*-plan.md`）记录了历次重构的契约，改动前先查阅相关文档。
- **设计先行**：较大的改动在 `docs/superpowers/plans/` 与 `docs/superpowers/specs/` 留有计划/设计文档，遵循"先写计划再实现"的惯例。

## 部署流程

目标：单台 Linux 服务器（`deploy.sh` 内硬编码了 `SERVER_IP`/`SERVER_USER`），Docker Compose 运行 `go-relay`（构建自 `Dockerfile.go-relay`）+ `nginx`（托管 `web/`，反代 `/api/`、`/ws/`，对外端口 `9001`）。

```sh
npm run build                                        # 必须先有 web/ 产物
RELAY_BOOTSTRAP_PASSWORD='强密码' ./deploy.sh         # 打包 → scp → 远程 docker compose up
./deploy.sh --dry-run                                # 只打印将执行的动作
```

- 数据持久化在服务器 `/opt/webterm-relay-go/data/`（`relay-store.json`）。
- 部署配置改动后跑 `npm run validate:deploy`（校验 `nginx.conf` 与 `docker-compose.yml` 的关键项）。
- 可选环境变量：`WEBTERM_RELAY_PUBLIC_URL`、`WEBTERM_RELAY_ALLOW_REGISTRATION`、`WEBTERM_RELAY_REQUIRE_EMAIL_OTP`、`WEBTERM_RELAY_SMTP_*`（邮箱 OTP）、`WEBTERM_DISABLE_P2P`、`WEBTERM_MAX_UPLOAD_BYTES`（上传大小上限，默认 100 MiB）。

## 安全注意事项

- `RELAY_BOOTSTRAP_PASSWORD` 为管理员初始密码，**必须显式设置**，`deploy.sh` 会拒绝空密码部署；脚本会在输出中避免打印密码与 SMTP 密码。
- 凭据只通过环境变量注入（`.env.local` 已被 gitignore，勿提交）；`data/`、`*.db*`、日志与构建产物也在 gitignore 中。
- `deploy.sh` 内包含真实服务器 IP，修改时注意不要把个人基础设施信息泄露到对外分享的内容中。
- Agent hook 的通知接口（`webterm agent-event`）只做透传，不解释任务内容；安装脚本见 `scripts/install-agent-hook-examples.sh`，约定见 `docs/agent-hooks.md`。

## 目录速查

```
go-core/cmd/            Go 入口（agent / relay / CLI / smoke）
go-core/internal/       Go 业务包（session、mux、relaycore、relay* 等）
frontend/src/           Vue 前端源码（views / components / lib / services / api）
web/                    前端构建产物（部署用，勿手改）
shared/                 前后端共享 JS（隧道协议常量与帧编解码）
android-client/         Android 多模块 Gradle 项目
scripts/                smoke 测试、hook 安装、部署校验脚本
tests/e2e/              Playwright E2E 测试
docs/                   设计文档与重构计划（superpowers/ 下为 specs 与 plans）
Dockerfile.go-relay / docker-compose.yml / nginx.conf / deploy.sh   部署文件
```
