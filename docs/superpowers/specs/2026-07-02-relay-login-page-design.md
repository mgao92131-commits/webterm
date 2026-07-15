# 中转服务登录页面化改造

## 概述

将中转服务（relay）的登录从弹窗（Dialog）模式改为 Android 独立全屏页面模式。

## 动机

当前中转服务登录使用 `AlertDialog` 弹窗，存在以下问题：
1. 点击弹窗外部区域会导致弹窗消失，OTP 验证流程中断，用户需重新登录
2. 弹窗空间局促，不适合展示设备管理列表等复杂内容
3. 登录与设备管理缺少连贯的全屏体验

## 页面流程

```
点击"中转服务"菜单
    │
    ├─ 未登录（无有效 cookie/token）──→ 登录/注册页面
    │                                       ├─ 邮箱 + 密码登录表单
    │                                       ├─ OTP 验证码（服务端返回 otp_required 时）
    │                                       ├─ "还没有账号？注册" → 注册页面
    │                                       └─ 登录成功 → 自动跳转设备管理页面
    │
    └─ 已登录（有有效 cookie/token）──→ 设备管理页面
                                            ├─ PC Agent 设备列表
                                            ├─ 信任设备列表
                                            └─ 退出登录 → 返回登录页面
```

## 页面设计

### 页面 1：登录/注册页（RelayLoginScreenBuilder）

提供适合 Android 的完整登录、OTP 与注册流程。

**默认模式（密码登录）：**
- 标题区：WebTerm 品牌 + "登录到远程终端"
- 邮箱输入框
- 密码输入框
- "登录" 按钮（主色）
- "还没有账号？注册" 链接
- 错误提示区域

**OTP 模式（邮箱验证码）：**
- 标题切换为 OTP 相关提示
- 6 位验证码输入框
- "验证并登录" 按钮
- "← 返回重新登录" 按钮
- 提示文字："已发送验证码，请检查您的邮箱"

**注册页面：**
- 邮箱、用户名、密码输入框
- "注册" 按钮
- "已有账号？登录" 链接

### 页面 2：设备管理页（RelayDevicesScreenBuilder）

提供适合 Android 的 Agent 与信任设备管理页面。

**顶部栏：**
- 返回按钮（回到主界面）
- 标题："设备管理"
- 退出登录按钮（右侧）

**Section A：PC Agent 设备**
- 区域标题 + "添加设备" 按钮
- 添加设备表单（输入设备名称 → 生成 secret）
- Secret 展示区（一次性显示，支持复制）
- 设备列表（每项显示：在线状态指示灯、设备名、最后在线时间、删除按钮）

**Section B：信任的移动设备**
- 区域标题
- 信任设备列表（每项显示：设备名、最后活跃时间、撤销信任按钮）

**空状态：**
- 无设备时显示 "暂无 PC Agent 设备"
- 无信任设备时显示 "暂无信任设备"

## 登录状态判断

通过 Android RelayService 管理：
- 使用本地保存的 relay cookie/token
- 认证失效时调用 `/api/auth/refresh`，失败后回退到密码登录
- 状态持久化到 `ServerConfigStore`

## 文件变更

### 新增文件

| 文件 | 职责 |
|------|------|
| `RelayLoginScreenBuilder.java` | 登录/注册页面视图构建 |
| `RelayDevicesScreenBuilder.java` | 设备管理页面视图构建 |
| `RelayAuthStateManager.java` | 中转登录状态管理 |

### 修改文件

| 文件 | 变更 |
|------|------|
| `MainActivity.java` | 添加两个新页面的视图切换逻辑 |
| `HomeScreenBuilder.java` | "中转服务"菜单项：判断登录态 → 跳转对应页面 |
| `RelayCoordinator.java` | 适配页面模式：原弹窗回调改为页面内回调；暴露设备管理 API 调用方法 |

### 删除文件

| 文件 | 原因 |
|------|------|
| `RelayConfigDialogHelper.java` | 弹窗登录模式不再需要 |

## 技术要点

### 视图切换

沿用现有 `PageTransitionAnimator` 做页面过渡动画，与现有终端页面切换保持一致。

### API 调用

复用现有 `WebTermApi.java` 中的方法：
- `login()` — 登录（已支持 OTP 流程）
- `verifyOtp()` — OTP 验证
- `fetchDevices()` — 获取设备列表（已有，需确认可用）
- 新增：`registerDevice()` — 注册设备
- 新增：`deleteDevice()` — 删除设备
- 新增：`fetchTrustedDevices()` — 获取信任设备列表
- 新增：`deleteTrustedDevice()` — 撤销信任设备

### 样式

沿用现有 `DesignTokens` 和 `UIUtils`，保持与主应用一致的深色主题视觉风格。

### 数据流

```
RelayCoordinator (业务逻辑层)
    ├─ loginRelay() / verifyOtp()
    ├─ fetchDevices() / registerDevice() / deleteDevice()
    ├─ fetchTrustedDevices() / deleteTrustedDevice()
    └─ logout()
        │
        ▼
WebTermApi (HTTP 层)
    │
    ▼
中转服务器 (go-core relay)
```

## 不做的事项

- 不改变现有的 Relay mux 终端连接流程
- 不在本次页面改造中调整终端屏幕协议
- 不在设备管理页面内嵌入终端（保持终端为独立页面）
