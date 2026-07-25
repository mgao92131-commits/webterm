package com.webterm.terminal.renderer;

import androidx.annotation.NonNull;

/**
 * {@link TerminalLineRenderNodeCache} 创建行缓存节点的工厂。生产环境使用
 * {@link RenderNodeTerminalRowNode}，测试可注入 fake 实现以隔离真实 Android 绘制调用。
 */
interface TerminalRowNodeFactory {
  @NonNull
  TerminalRowNode create(@NonNull String name);
}
