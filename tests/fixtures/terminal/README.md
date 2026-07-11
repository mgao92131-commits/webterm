# Terminal Screen Fixtures

这些 fixture 用于验证 `webterm.screen.v1` 在 Go 与 Android 两端生成/解析的一致性。

## 文件约定

每个子目录代表一个场景，包含：

- `input.ansi`：写入 PTY 的原始 ANSI/UTF-8 字节流。
- `expected-debug.json`：人类可读的期望屏幕状态，供协议审查和手写用例参考。
- `expected.pb`：由 Go canonical frame 序列化得到的 `ScreenSnapshot` Protobuf 二进制，待 Task 3 完成后批量生成。

## 当前场景

| 目录 | 覆盖点 |
|---|---|
| `plain-text` | 普通 ASCII、默认样式、空行 |
| `all-sgr` | bold/italic/underline、indexed/RGB 颜色、style 字典 |
| `wide-chars` | CJK 双宽字符、emoji、spacer cells |
| `combined-chars` | 组合字符、CJK 字符簇宽度 |
| `emoji-zwj` | ZWJ emoji 序列（待补充） |
| `soft-wrap` | 列溢出导致的软换行与 wrapped 标志 |
| `main-scroll` | 主屏输出滚动进入 scrollback，稳定 lineId |
| `alternate` | 进入/退出 alternate buffer，不污染主屏历史 |
| `mouse` | mouse tracking/encoding 模式（待补充） |
| `resize` | reflow 与 layout epoch 变化（待补充） |
| `clipboard` | OSC 52 读写（待补充） |
| `bell` | bell 副作用事件（待补充） |
| `title` | title/cwd 副作用事件（待补充） |

## 生成 expected.pb

Task 3（canonical frame 导出）完成后，运行：

```bash
go test ./internal/terminalengine/ -run TestGenerateFixtures -update
```

该命令读取每个 `input.ansi`，通过 fork 后的 headless engine 导出 `ScreenFrame`，再序列化为 `expected.pb`。
