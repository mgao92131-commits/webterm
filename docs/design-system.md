# WebTerm 设计系统 v2

## 美学方向：精致暗色 / Swiss 现代主义

**一句话概括**：像一把精密工具 —— 克制的配色、严格的层次、让功能和内容成为主角。

### 参考气质
- Linear 的暗色仪表盘：精确的间距、克制的圆角、极少但精确的强调色
- Vercel 的工具页面：高可读性、清晰的层级、功能即装饰
- iTerm2 的极简模式：终端本身就是最好的 UI

### 核心原则
1. **去装饰化**：去掉所有渐变背景、毛玻璃、光斑。背景是纯粹的黑。
2. **单一强调色**：只用一个强调色（翠绿 #10B981），表示在线/活跃/可操作。
3. **字体建立气质**：标题用 Geist Sans，代码/终端用 Geist Mono。
4. **用间距和分割线说话**：不用卡片投影和圆角来区分层级，用密度和线条。
5. **终端画布是主角**：非终端页面的设计语言向终端靠拢，保持统一。

---

## 色彩体系

### 核心调色板

```
Base (背景层):
  --bg-primary:    #0A0A0B  页面底色
  --bg-secondary:  #111113  面板/卡片底色
  --bg-tertiary:   #18181B  悬停态/hover

Border (分割层):
  --border-primary:   #27272A  主要分割线/卡片边框
  --border-secondary: #1F1F23  次要分割线
  --border-hover:     #3F3F46  悬停态边框

Text (文字层):
  --text-primary:    #FAFAFA  主文字
  --text-secondary:  #A1A1AA  次要文字/标签
  --text-tertiary:   #71717A  辅助信息/placeholder
  --text-disabled:   #52525B  禁用态

Accent (强调):
  --accent:          #10B981  主强调色（翠绿）
  --accent-hover:    #34D399  悬停态
  --accent-muted:    rgba(16, 185, 129, 0.10)  强调色背景
  --accent-text:     #6EE7B7  强调色上的文字

Status (状态色):
  --success:  #10B981  在线/成功
  --warning:  #F59E0B  警告/轮询中
  --danger:   #EF4444  离线/错误
  --info:     #3B82F6  信息

Terminal (终端区域):
  --terminal-bg:   #0A0A0B  终端背景（跟随主题）
  --terminal-fg:   #E5E7EB  终端前景文字
```

### 主题模式

WebTerm 支持两种终端配色主题，但 **UI 层（页面框架、卡片、按钮等）不受主题影响**，始终使用上述暗色调色板。主题仅切换终端画布的 ANSI 16 色调色板。

| 主题 | 影响范围 |
|------|----------|
| `solarized` | 终端画布的 ANSI 色映射 |
| `dracula` | 终端画布的 ANSI 色映射 |

主题切换按钮改为明确标注当前主题名，点击切换到另一个。

---

## 字体系统

### 字体选择

| 用途 | 字体 | 备选 |
|------|------|------|
| **UI 标题/正文** | Geist Sans | system-ui, -apple-system |
| **代码/终端/数据** | Geist Mono | ui-monospace, SF Mono |

### 引入方式

```html
<!-- index.html -->
<link rel="preconnect" href="https://cdn.jsdelivr.net">
<link href="https://cdn.jsdelivr.net/npm/geist@1.3.0/dist/fonts/geist-sans/Geist-Sans.min.css" rel="stylesheet">
<link href="https://cdn.jsdelivr.net/npm/geist@1.3.0/dist/fonts/geist-mono/Geist-Mono.min.css" rel="stylesheet">
```

### 字体层级

| 层级 | 大小 | 字重 | 用途 |
|------|------|------|------|
| `text-brand` | 16px | 600 | 品牌名（标题栏） |
| `text-heading` | 14px | 600 | 区块标题 |
| `text-body` | 14px | 400 | 正文 |
| `text-label` | 12px | 500 | 标签/徽章 |
| `text-caption` | 11px | 400 | 辅助信息 |
| `text-mono` | 13px | 400 | 代码/路径/ID |

---

## 空间系统

### 间距量表

| Token | 值 | 用途 |
|-------|-----|------|
| `space-1` | 4px | 紧凑间距（图标与文字） |
| `space-2` | 8px | 元素内间距 |
| `space-3` | 12px | 组内间距 |
| `space-4` | 16px | 区块内间距 |
| `space-5` | 24px | 区块间间距 |
| `space-6` | 32px | 页面级间距 |
| `space-8` | 48px | 大区块间距 |

### 圆角

| Token | 值 | 用途 |
|-------|-----|------|
| `radius-sm` | 4px | 小型按钮、徽章、输入框 |
| `radius-md` | 6px | 卡片、面板 |
| `radius-lg` | 8px | 大型面板（极少使用） |
| `radius-none` | 0px | 终端区域、分割线 |

### 布局

- 最大内容宽度：`max-w-5xl` (1024px)，所有页面内容居中
- 终端页面例外：全屏，无边距
- 页面内两栏布局：左侧栏 240px，右侧自适应
- 卡片网格：`grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3`

---

## 组件规范

### 按钮

```
Primary (强调操作):
  bg-accent text-black font-medium px-4 py-2 rounded-sm
  hover:bg-accent-hover
  disabled:opacity-40

Secondary (普通操作):
  bg-bg-tertiary text-text-secondary border border-border-primary px-4 py-2 rounded-sm
  hover:bg-bg-tertiary hover:text-text-primary hover:border-border-hover
  disabled:opacity-40

Ghost (弱操作):
  text-text-tertiary px-3 py-1.5 rounded-sm
  hover:text-text-primary hover:bg-bg-tertiary

Danger (危险操作):
  text-danger px-3 py-1.5 rounded-sm
  hover:bg-danger/10
```

### 输入框

```
Input:
  bg-bg-primary border border-border-primary rounded-sm px-3 py-2
  text-text-primary placeholder:text-text-disabled
  focus:border-accent focus:ring-1 focus:ring-accent
  font-mono text-[13px]
```

### 卡片

```
Card (默认):
  bg-bg-secondary border border-border-primary rounded-md p-4
  hover:border-border-hover (仅当可交互时)

Card (选中态):
  bg-bg-secondary border-accent/50 rounded-md p-4
```

### 徽章/状态指示

```
StatusBadge:
  inline-flex items-center gap-1.5 px-2 py-0.5 rounded-sm text-[11px] font-medium

  online:   text-success bg-success/10
  offline:  text-text-disabled bg-bg-tertiary
  warning:  text-warning bg-warning/10
```

### 分割线

```
Divider:
  h-px bg-border-primary w-full
  (替代 border-b 的使用场景)
```

---

## 页面设计

### 1. ManagerView（会话大厅）

**布局**：
```
┌─────────────────────────────────────────────────┐
│  WebTerm    [设备选择器]    [主题] [设备管理] [退出] │  ← 44px 顶栏
├──────────┬──────────────────────────────────────┤
│ 设备列表  │  会话列表 (grid 3列)                   │
│          │  ┌──────┐ ┌──────┐ ┌──────┐          │
│ ● MacBk  │  │会话1  │ │会话2  │ │会话3  │          │
│ ○ Mini   │  └──────┘ └──────┘ └──────┘          │
│          │  ┌──────┐                             │
│          │  │会话4  │                             │
│          │  └──────┘                             │
└──────────┴──────────────────────────────────────┘
```

**关键变化**：
- 去掉所有渐变和光斑背景 → 纯 `#0A0A0B`
- 顶栏从毛玻璃 → 纯色 `bg-bg-secondary` + 底部分割线
- 设备列表：从卡片列表 → 简洁的列表项，选中态用强调色左边框
- 会话卡片：去掉投影、hover 放大、毛玻璃 → 纯色背景 + 细边框 + hover 边框高亮
- 新建终端按钮：Primary 按钮，放在显眼位置
- 连接状态指示：从 emoji 标签 → 简洁的色点 + 文字
- 移动端：去掉弹出菜单 → 底部固定操作栏

### 2. TerminalView（终端页）

**布局**：
```
┌─────────────────────────────────────────────────┐
│  ←       终端标题          A- A+  选择          │  ← 40px 顶栏
├─────────────────────────────────────────────────┤
│                                                  │
│                 终端画布 (xterm)                   │
│                                                  │
│                                                  │
├─────────────────────────────────────────────────┤
│  Ctrl  Esc  Tab  /  -  |  ↑  ↓  ←  →  Enter     │  ← 快捷栏
└─────────────────────────────────────────────────┘
```

**关键变化**：
- 顶栏从 `sticky` 毛玻璃 → 纯色，与页面融为一体
- 标题文本：只读展示终端通过 OSC 上报的 termTitle，为空时展示 Terminal
- 字号调节：更紧凑的分段控件
- 快捷栏：去掉每个按钮的独立边框，改为扁平排列，Ctrl 激活态用强调色填充
- 移动端快捷栏增加横向滑动提示（渐变遮罩）

### 3. LoginView / RegisterView（认证页）

**布局**：
```
┌─────────────────────────────────────────────────┐
│                                                  │
│                    WebTerm                        │
│              你的远程终端入口                      │
│                                                  │
│              ┌──────────────────┐                │
│              │  邮箱             │                │
│              │  ─────────────── │                │
│              │  密码             │                │
│              │  ─────────────── │                │
│              │                  │                │
│              │  [    登录    ]  │                │
│              │                  │                │
│              │  还没有账号？注册  │                │
│              └──────────────────┘                │
│                                                  │
└─────────────────────────────────────────────────┘
```

**关键变化**：
- 去掉毛玻璃卡片、光斑 → 纯黑背景 + 居中表单
- 表单区域：不设卡片容器，直接用分割线分隔字段
- 标题：用字体本身建立识别度，不加渐变
- 错误信息：内联在对应字段下方

### 4. DevicesView（设备管理）

**布局**：
```
┌─────────────────────────────────────────────────┐
│  ← 返回    设备管理                               │
├─────────────────────────────────────────────────┤
│  PC Agent 设备                    [+ 添加设备]    │
│  ─────────────────────────────────────────────── │
│  💻  MacBook Pro        ● 在线      ...  [删除]   │
│  💻  Ubuntu Server      ○ 离线      ...  [删除]   │
│                                                  │
│  信任的浏览器/移动设备                             │
│  ─────────────────────────────────────────────── │
│  🌐  Chrome / macOS     最后活跃: ...   [撤销信任] │
│  🌐  Android Mobile     最后活跃: ...   [撤销信任] │
└─────────────────────────────────────────────────┘
```

**关键变化**：
- 去掉毛玻璃卡片 → 纯色背景区块 + 分割线
- 设备列表：从卡片 → 行式列表，hover 高亮
- 添加表单：从嵌套卡片 → 内联在区块内
- secret 展示：从琥珀色警告框 → 红色危险提示框（更符合安全语义）

### 5. OtpInput（验证码组件）

**关键变化**：
- 6 位独立输入框替代单一输入框（更好的 UX）
- 粘贴支持：自动填充 6 位
- 验证中状态：输入框逐个变绿动画

---

## 动画与微交互

### 页面过渡
- 保持现有 `fade` 过渡（0.15s，从 0.2s 缩短）

### 列表项出现
- 会话卡片：`staggered fade-in`，每个延迟 30ms
- 使用 CSS `@keyframes` + `animation-delay`

### 状态变化
- 连接状态切换：色点平滑变色 `transition: background-color 0.3s`
- 按钮 hover：`transition: all 0.15s`
- 卡片 hover：边框颜色 `transition: border-color 0.15s`

### 终端相关
- 字号变化：终端 resize 防抖，平滑过渡
- 快捷栏按钮：`active:scale-95 transition-transform`

---

## 响应式策略

### 断点
- `mobile`：< 640px（单栏，顶部可折叠面板）
- `tablet`：640px - 1024px（两栏，侧栏可折叠）
- `desktop`：>= 1024px（固定两栏）

### 移动端特殊处理
- 设备选择：顶部下拉面板 → 底部 Sheet
- 快捷栏：横向滚动 + 左右渐变遮罩指示可滚动
- 终端页面：全屏沉浸，顶栏和快捷栏自动隐藏（滚动时显示）

---

## 实现计划

### Phase 1：基础设施（不改页面）

1. **更新 `index.html`**：引入 Geist 字体 CDN
2. **重写 `tailwind.config.js`**：
   - 添加自定义颜色 tokens（映射到 CSS 变量）
   - 添加自定义字体族
   - 添加自定义间距
3. **重写 `index.css`**：
   - 重新定义 CSS 变量
   - 字体加载
   - 全局基础样式
   - 保留终端相关样式（xterm 覆盖）
   - 更新过渡动画

### Phase 2：页面重构

4. **`App.vue`**：更新过渡动画时长
5. **`LoginView.vue`**：去玻璃化、纯黑背景、字段分割线式表单
6. **`RegisterView.vue`**：同 LoginView
7. **`OtpInput.vue`**：6 独立输入框 + 粘贴支持
8. **`ManagerView.vue`**：纯色布局、设备列表重做、会话卡片重做、状态指示重做
9. **`TerminalView.vue`**：顶栏简化、快捷栏优化、主题切换修正
10. **`DevicesView.vue`**：去卡片化、行式列表

### Phase 3：打磨

11. 动画微调（staggered list、hover 过渡）
12. 移动端适配检查
13. 主题切换逻辑修正

---

## 技术约束

- **不引入新的 npm 依赖**：字体通过 CDN 加载
- **不改变 Vue 组件逻辑**：只改 template 和 style，不改 script
- **保持现有 Tailwind 类名策略**：继续使用 Tailwind，但替换具体颜色/间距值
- **保持所有现有功能**：P2P 状态、连接健康、轮询、重连等逻辑不变
- **保持双主题支持**：Solarized/Dracula 终端配色切换继续工作
