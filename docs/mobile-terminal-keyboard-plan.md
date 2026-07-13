# 移动端终端页面软键盘处理方案

## 背景

当前 `frontend/src/lib/terminal-layout.ts` 对移动端软键盘的处理过于复杂，存在以下问题：

1. 软键盘弹出时终端视图会缩放（`fit()` 导致行列数变化、内容重排）
2. 底部 quickbar 有时不弹出，有时高出来一块
3. 软键盘弹出时终端视图有时不在最底部

## 目标

1. 软键盘弹出时，不让终端视图缩放
2. 保证终端视图最后一行文本正好在键盘上方
3. 大幅简化 `TerminalLayoutController` 的逻辑

## 最终方案

### 一、布局层改造

采用 **fixed 分层布局**，将页面分为三层：header、terminal-container、quickbar。

```css
body.terminal-mode {
  position: fixed;
  inset: 0;
  overflow: hidden;
}

.terminal-bar {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 40px;
  z-index: 10;
}

#terminal-container {
  position: fixed;
  top: 40px;
  left: 0;
  right: 0;
  bottom: 54px;
  z-index: 1;
}

.quickbar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  height: 54px;
  padding-bottom: env(safe-area-inset-bottom);
  z-index: 20;
}
```

> 实际实现时，header 和 quickbar 的高度应通过 `getBoundingClientRect()` 在运行时读取，而不是硬编码 40px/54px。

### 二、键盘高度检测

监听 `visualViewport resize`：

```js
const keyboardHeight = Math.max(
  0,
  window.innerHeight - visualViewport.height - visualViewport.offsetTop
);

const widthDelta = Math.abs(window.innerWidth - lastWidth);
const isKeyboardEvent =
  keyboardHeight > 80 &&
  widthDelta < 8 &&
  Math.abs(visualViewport.scale - 1) < 0.05;
```

可选增强：结合当前 `activeElement` 判断是否为 `.xterm-helper-textarea`。

### 三、软键盘弹出处理

```js
quickbar.style.bottom = `${keyboardHeight}px`;
terminalContainer.style.bottom = `${quickbarHeight + keyboardHeight}px`;
terminalView.scrollToBottom();
terminalView.refreshAll?.();
```

**关键：不调用 `fit()`**，终端保持初始化时的 cols/rows，只通过容器变小 + `scrollToBottom()` 让最后一行可见。

### 四、软键盘收起处理

```js
quickbar.style.bottom = '0px';
terminalContainer.style.bottom = `${quickbarHeight}px`;
terminalView.fit();
sendResizeMessage();
```

键盘收起后容器恢复原始高度，可以重新 fit 同步后端。

### 五、普通 resize 处理

宽度变化大、旋转屏幕、调整字体大小时：

```js
terminalView.fit();
sendResizeMessage();
```

### 六、只读标题处理

顶部标题栏现在是只读文本，移动端无需处理标题控件聚焦时的防滚动与防位移逻辑，直接渲染即可。

## 需要删除/简化的逻辑

- `bottomPin` 状态机及所有相关 timer/RAF
- `scrollPageToCursor` 的累加 `--terminal-keyboard-shift` 逻辑
- `--terminal-keyboard-shift` transform
- `initialHeight` / `heightCompression` 等复杂键盘检测状态
- `attachTouchScroll` 里取消底部粘滞的逻辑
- `scheduleKeyboardAvoidance` 的多段 timer

保留：

- `visualViewport` resize 监听
- debounce/RAF 控制更新频率
- 普通 resize 时的 `fit()` 和 resize 消息发送
- `focusin/focusout` 监听（仅用于识别键盘事件来源）

## 残余风险

1. **iOS Safari fixed 元素闪烁**：键盘弹出动画中 quickbar 可能有轻微位置跳动，可通过 `requestAnimationFrame` 更新 bottom 减轻。
2. **不 fit 时长命令换行**：后端仍按原 cols 输出，显示区域小只是 viewport 小，用户可滚动查看完整内容。
3. **Android 第三方键盘高度变化**：visualViewport 会连续触发，debounce 后应能跟上。

## 验证标准

实施后应验证：

1. 移动端点击终端区域，软键盘弹出，终端内容不缩放、不重排
2. 终端最后一行光标/文本正好位于键盘上方
3. quickbar 始终位于键盘上方，不出现"高出来一块"或"不弹出"
4. 旋转屏幕、调整字体大小后终端能正确 fit
5. 键盘收起后终端恢复正常尺寸
