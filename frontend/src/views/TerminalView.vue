<template>
  <section :class="['terminal-page min-h-screen flex flex-col bg-slate-950 select-none overflow-hidden relative', { 'selection-mode': isSelectionMode }]">
    <!-- 顶部状态栏 -->
    <header class="terminal-bar w-full px-4 py-2 border-b border-slate-900 bg-slate-950/60 backdrop-blur-md flex items-center justify-between z-10 gap-3">
      <router-link 
        to="/" 
        class="button font-mono text-xs px-3 py-1.5 rounded-lg border border-slate-800 text-slate-400 hover:text-white hover:border-slate-700 bg-slate-900/30 transition-all flex items-center gap-1"
      >
        <ArrowLeft class="w-3.5 h-3.5" />
        <span>返回</span>
      </router-link>

      <div class="terminal-title flex-1 flex justify-center max-w-[400px]">
        <input 
          id="sessionName" 
          autocomplete="off" 
          maxlength="80" 
          v-model="sessionNameVal"
          @focus="onTitleFocus"
          @blur="onTitleBlur"
          @keydown.enter="onTitleEnter"
          @keydown.escape="onTitleEsc"
          class="w-full text-center px-3 py-1.5 bg-slate-900/30 border border-transparent rounded-lg text-slate-200 hover:border-slate-850/60 focus:border-indigo-500 focus:bg-slate-900/80 focus:outline-none transition-all font-sans text-sm font-semibold truncate"
          :placeholder="sessionPlaceholder" 
          aria-label="会话名称" 
          title="会话名称，留空时显示终端标题" 
        />
      </div>

      <div class="flex items-center gap-2">
        <span v-if="store.mode === 'relay'" :class="['text-[10px] px-2.5 py-1 rounded-full font-semibold border font-mono tracking-normal leading-none mr-1', store.p2pActive ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20' : 'bg-amber-500/10 text-amber-400 border-amber-500/20']">
          {{ store.p2pActive ? '🟢 直连' : '🟡 中继' }}
        </span>
        <button 
          id="copySelection" 
          class="selection-copy font-mono text-xs px-3 py-1.5 rounded-lg border border-indigo-500/50 text-indigo-400 hover:text-indigo-300 hover:bg-indigo-500/10 transition-all"
          type="button" 
          v-show="isSelectionMode"
          @click="copySelection"
        >
          拷贝
        </button>
        <button 
          id="selectMode" 
          class="font-mono text-xs px-3 py-1.5 rounded-lg border border-slate-800 text-slate-400 hover:text-white hover:border-slate-700 bg-slate-900/30 transition-all"
          type="button"
        >
          选择
        </button>
      </div>
    </header>

    <!-- 终端容器 -->
    <div id="terminal-container" class="flex-1 w-full relative overflow-hidden bg-slate-950">
      <div id="terminal" ref="terminalRef" class="w-full h-full"></div>
    </div>

    <!-- 移动端快捷输入栏 -->
    <nav class="quickbar z-20 w-full border-t border-slate-900 bg-slate-950/90 py-2 px-3 select-none">
      <div class="flex items-center gap-2 overflow-x-auto whitespace-nowrap scrollbar-none py-1 w-full max-w-lg mx-auto">
        <button 
          v-for="k in ['Ctrl', 'Esc', 'Tab', 'Enter', 'Ctrl C', 'Ctrl D', 'Ctrl Z', 'Ctrl X', '/', '-', '|', '>', '\\', '$', '&', '←', '↓', '↑', '→']" 
          :key="k"
          type="button" 
          :data-key="k"
          :class="['font-mono text-xs font-semibold py-2.5 px-3 rounded-lg border transition-all active:scale-95 leading-none text-center flex-shrink-0 min-w-[42px]',
                   k === 'Ctrl' && isCtrlActive 
                     ? 'bg-indigo-600 border-indigo-500 text-white active shadow-md shadow-indigo-600/20' 
                     : 'bg-slate-900/60 border-slate-850/60 text-slate-400 hover:text-slate-200 hover:border-slate-800']"
        >
          {{ k }}
        </button>
      </div>
    </nav>
  </section>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ArrowLeft } from '@lucide/vue';
import { store, api } from '../store';
import { p2pManager } from '../lib/p2p';

// 导入重构移植后的控制器与底层类
import { DisposableStore } from '../lib/disposable';
import { TerminalView } from '../lib/terminal-view';
import { TerminalInputController } from '../lib/terminal-input-controller';
import { TerminalLayoutController } from '../lib/terminal-layout';
import { TerminalSelectionController } from '../lib/terminal-selection';

const route = useRoute();
const router = useRouter();

// 页面绑定 refs
const terminalRef = ref<HTMLElement | null>(null);
const sessionNameVal = ref('');
const sessionPlaceholder = ref('Terminal');
const isSelectionMode = ref(false);
const isCtrlActive = ref(false);

// 终端及网络局部状态变量
const sessionId = decodeURIComponent(route.params.id as string);
let terminalDisposables: DisposableStore | null = null;
let terminalView: TerminalView | null = null;
let inputController: TerminalInputController | null = null;
let layoutController: TerminalLayoutController | null = null;
let selectionController: TerminalSelectionController | null = null;

let ws: WebSocket | null = null;
let reconnectTimer: any = null;
let reconnectAttempts = 0;
let manualClose = false;
let lastSeq = Number(sessionStorage.getItem(`webterm:${sessionId}:lastSeq`) || 0);
let restored = false;
let isRestoring = false;

// 标题修改状态变量
let titleBeforeEdit = "";
let titleEditing = false;
let skipTitleCommit = false;
let currentSessionName = "";
let currentTermTitle = "";
let currentDisplayTitle = "Terminal";

onMounted(() => {
  document.body.classList.add("terminal-mode");
  
  // 校验挂载容器
  if (!terminalRef.value) {
    console.error("Terminal container not found");
    return;
  }

  // 初始化重构生命周期 DisposableStore
  terminalDisposables = new DisposableStore();
  manualClose = false;
  restored = false;
  isRestoring = false;

  // 1. 实例化 TerminalView 核心显示层
  terminalView = new TerminalView({
    element: terminalRef.value,
    options: {
      cursorBlink: true,
      fontFamily: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace',
      fontSize: 10,
      convertEol: true,
      scrollback: 20000,
      overviewRuler: { width: 4 },
      theme: termTheme(),
    }
  });

  // 2. 初始化输入控制器
  inputController = new TerminalInputController({
    store: terminalDisposables,
    root: document.body,
    terminalElement: terminalRef.value,
    sendInput,
    focusTerminal: () => terminalView?.focus(),
    onModifierChange: (modifier) => {
      isCtrlActive.value = modifier === 'ctrl';
    }
  });
  inputController.attach();

  // 3. 初始化布局和自适应控制器
  layoutController = new TerminalLayoutController({
    store: terminalDisposables,
    terminalView: terminalView,
    container: document.getElementById("terminal-container") as HTMLElement,
    documentElement: document.documentElement,
    sendResizeMessage: (size) => send({ type: "resize", ...size }),
    isVisible: () => !document.hidden,
  });
  layoutController.attach();
  terminalDisposables.add(layoutController);

  // 绑定滚动和渲染钩子以确保自适应底部粘滞
  terminalDisposables.add(terminalView.onScroll(() => {
    layoutController?.handleTerminalScroll();
  }));
  terminalDisposables.add(terminalView.onRender(() => {
    layoutController?.handleTerminalRender();
    scheduleClampIME();
  }));

  // 4. 初始化选区控制器
  selectionController = new TerminalSelectionController({
    store: terminalDisposables,
    root: document.body,
    terminalElement: terminalRef.value,
    terminalView: terminalView,
    clearPendingInput: () => inputController?.clearPendingModifier(),
    onSelectionModeChange: (active) => {
      isSelectionMode.value = active;
    }
  });
  selectionController.attach();

  // 5. 绑定 xterm.js 原始输入数据流
  terminalDisposables.add(terminalView.onData(handleTerminalData));

  // 6. 绑定全局浏览器标签可见性事件，恢复网络与重算视口
  terminalDisposables.add(terminalDisposables.addEventListener(document, "visibilitychange", () => {
    if (!document.hidden) {
      ensureConnected();
      terminalDisposables?.addTimeout(setTimeout(() => layoutController?.sendResize({ reason: "visibility" }), 150));
    }
  }));

  // 7. 初始化标题就地编辑监听
  setupTitleEditor();

  // 8. 移动端软键盘输入限制 constrain 
  setupMobileIMEBounds();

  // 9. 快捷栏高度初始化计算
  setupQuickbarMetrics();

  // 10. 激活 E2E Playwright 调试钩子
  setupTerminalDebugHooks();

  // 11. 建立长连接
  connectWS(sessionId);
});

onUnmounted(() => {
  document.body.classList.remove("terminal-mode");
  disposeTerminalPage();
});

// --- 业务方法 ---

function send(msg: any) {
  ensureConnected();
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(msg));
  }
}

function sendInput(data: string, _source?: string) {
  terminalView?.scrollToBottom();
  send({ type: "input", data });
}

function handleTerminalData(data: string) {
  if (!inputController?.pendingModifier) {
    sendInput(data);
    return;
  }
  if (!inputController.sendModifiedInput(data)) {
    sendInput(data);
  }
}

function ensureConnected() {
  if (manualClose) return;
  if (!ws || ws.readyState === WebSocket.CLOSED || ws.readyState === WebSocket.CLOSING) {
    connectWS(sessionId);
  }
}

// 建立终端 WebSocket 长连接
function connectWS(id: string) {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
    return;
  }
  
  const proto = window.location.protocol === "https:" ? "wss" : "ws";
  manualClose = false;
  
  const [deviceId, localId] = id.includes(':') ? id.split(':') : ['', id];
  let wsUrl = `${proto}://${window.location.host}/ws/sessions/${encodeURIComponent(localId)}`;
  if (deviceId) {
    wsUrl += `?deviceId=${encodeURIComponent(deviceId)}`;
  }
  
  if (p2pManager.isP2PActive()) {
    ws = p2pManager.createWebSocketMock(wsUrl, ['binary', 'json']) as any;
  } else {
    ws = new WebSocket(wsUrl);
  }
  
  terminalDisposables?.addEventListener(ws, "open", () => {
    reconnectAttempts = 0;
    clearReconnect();
    send({ type: "hello", lastSeq: restored ? lastSeq : 0 });
    layoutController?.sendResize({ reason: "ws-open" });
  });

  terminalDisposables?.addEventListener(ws, "message", (event) => {
    let msg: any;
    try {
      msg = JSON.parse(event.data);
    } catch {
      return;
    }
    
    if (msg.type === "state") {
      if (!terminalView) return;
      terminalView.reset();
      beginTerminalRestore();
      if (msg.data) {
        terminalView.enqueueWrite(msg.data, () => finishTerminalRestore({ seq: msg.seq }));
      } else {
        finishTerminalRestore({ fit: true, seq: msg.seq });
      }
      terminalView.refreshAll();
      restored = true;
    } else if (msg.type === "replay") {
      if (!terminalView) return;
      if (!restored || msg.from === 0) {
        terminalView.reset();
      }
      beginTerminalRestore();
      const frames = msg.frames || [];
      if (frames.length > 0) {
        for (let i = 0; i < frames.length; i++) {
          const frame = frames[i];
          const isLast = i === frames.length - 1;
          terminalView.enqueueWrite(frame.data || "", () => {
            rememberSeq(frame.seq);
            if (isLast) finishTerminalRestore({ seq: msg.seq });
          });
        }
      } else {
        finishTerminalRestore({ fit: true, seq: msg.seq });
      }
      terminalView.refreshAll();
      restored = true;
    } else if (msg.type === "output") {
      if (!terminalView) return;
      terminalView.enqueueWrite(msg.data, () => rememberSeq(msg.seq));
    } else if (msg.type === "info") {
      setTerminalInfo(msg.data);
    } else if (msg.type === "exit") {
      manualClose = true;
    }
  });

  terminalDisposables?.addEventListener(ws, "close", () => {
    ws = null;
    if (!manualClose) scheduleReconnect();
  });
}

function scheduleReconnect() {
  clearReconnect();
  const delay = Math.min(1000 * Math.pow(1.6, reconnectAttempts++), 8000);
  reconnectTimer = setTimeout(() => {
    ws = null;
    ensureConnected();
  }, delay);
}

function clearReconnect() {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
}

function beginTerminalRestore() {
  isRestoring = true;
  document.querySelector(".terminal-page")?.classList.add("is-restoring");
}

function finishTerminalRestore(options: { fit?: boolean; seq?: number } = {}) {
  if (options.seq) rememberSeq(options.seq);
  isRestoring = false;
  terminalView?.scrollToBottom();
  terminalView?.refreshAll();
  layoutController?.settleAfterWrite({ fit: options.fit });
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      document.querySelector(".terminal-page")?.classList.remove("is-restoring");
    });
  });
}

function rememberSeq(seq: number) {
  const value = Number(seq || 0);
  if (!value || value < lastSeq) return;
  lastSeq = value;
  sessionStorage.setItem(`webterm:${sessionId}:lastSeq`, String(value));
}

// 终端主题应用
function termTheme() {
  const THEMES: any = {
    solarized: {
      background: "#002b36",
      foreground: "#839496",
      cursor: "#93a1a1",
      selectionBackground: "#073642",
      black: "#073642",
      red: "#dc322f",
      green: "#859900",
      yellow: "#b58900",
      blue: "#268bd2",
      magenta: "#d33682",
      cyan: "#2aa198",
      white: "#eee8d5",
      brightBlack: "#002b36",
      brightRed: "#cb4b16",
      brightGreen: "#586e75",
      brightYellow: "#657b83",
      brightBlue: "#839496",
      brightMagenta: "#6c71c4",
      brightCyan: "#93a1a1",
      brightWhite: "#fdf6e3",
    },
    dracula: {
      background: "#282a36",
      foreground: "#f8f8f2",
      cursor: "#f8f8f2",
      selectionBackground: "#44475a",
      black: "#21222c",
      red: "#ff5555",
      green: "#50fa7b",
      yellow: "#f1fa8c",
      blue: "#bd93f9",
      magenta: "#ff79c6",
      cyan: "#8be9fd",
      white: "#f8f8f2",
      brightBlack: "#6272a4",
      brightRed: "#ff6e6e",
      brightGreen: "#69ff94",
      brightYellow: "#ffffa5",
      brightBlue: "#d6acff",
      brightMagenta: "#ff92df",
      brightCyan: "#a4ffff",
      brightWhite: "#ffffff",
    }
  };
  return THEMES[store.theme] ? THEMES[store.theme] : THEMES.solarized;
}

// 更新顶栏会话元数据
function setTerminalInfo(session: any = {}) {
  if (Object.prototype.hasOwnProperty.call(session, "name")) {
    currentSessionName = String(session.name || "").trim();
  }
  if (Object.prototype.hasOwnProperty.call(session, "termTitle")) {
    currentTermTitle = String(session.termTitle || "").trim();
  }
  currentDisplayTitle = session.displayTitle || (() => {
    const term = currentTermTitle || "Terminal";
    return currentSessionName ? `${currentSessionName} - ${term}` : term;
  })();
  
  sessionPlaceholder.value = currentTermTitle || "Terminal";
  if (!titleEditing) sessionNameVal.value = currentSessionName;
  document.title = `${currentDisplayTitle} - WebTerm`;
}

// --- 标题就地编辑逻辑 ---

function setupTitleEditor() {
  const titleInput = document.getElementById("sessionName") as HTMLInputElement;
  if (!titleInput) return;

  terminalDisposables?.addEventListener(titleInput, "focus", () => {
    titleEditing = true;
    titleBeforeEdit = currentSessionName || "";
    titleInput.select();
  });
}

function onTitleFocus() {
  titleEditing = true;
  titleBeforeEdit = currentSessionName || "";
}

function onTitleBlur(event: FocusEvent) {
  commitTerminalTitle(event);
}

function onTitleEnter() {
  const titleInput = document.getElementById("sessionName") as HTMLInputElement;
  titleInput?.blur();
}

function onTitleEsc() {
  cancelTerminalTitleEdit();
}

async function commitTerminalTitle(event: FocusEvent) {
  if (skipTitleCommit) {
    skipTitleCommit = false;
    titleEditing = false;
    return;
  }
  const restoreFocus = shouldRestoreTerminalFocusAfterTitleEdit(event);
  const nextName = sessionNameVal.value.trim();
  const oldName = titleBeforeEdit || "";
  titleEditing = false;

  if (nextName === oldName) {
    setTerminalInfo({ name: oldName });
    if (restoreFocus) terminalView?.focus();
    return;
  }

  setTerminalInfo({ name: nextName });
  try {
    const localId = sessionId.includes(':') ? sessionId.split(':')[1] : sessionId;
    const session = await api(`/api/sessions/${encodeURIComponent(localId)}`, {
      method: "PATCH",
      body: JSON.stringify({ name: nextName }),
    });
    setTerminalInfo(session);
  } catch (err: any) {
    setTerminalInfo({ name: oldName });
    alert(err.message?.trim() || "修改标题失败");
  } finally {
    if (restoreFocus) terminalView?.focus();
  }
}

function shouldRestoreTerminalFocusAfterTitleEdit(event: FocusEvent) {
  const relatedTarget = event.relatedTarget as HTMLElement;
  return !selectionController?.selectionMode
    && !selectionController?.enteringSelectionMode
    && relatedTarget?.id !== "selectMode";
}

function cancelTerminalTitleEdit() {
  skipTitleCommit = true;
  titleEditing = false;
  setTerminalInfo({ name: titleBeforeEdit || "" });
  if (!selectionController?.selectionMode) terminalView?.focus();
}

// 拷贝选区
function copySelection(event?: Event) {
  selectionController?.copySelection(event);
}

// --- 移动端软键盘兼容/视口限制逻辑 ---

function setupMobileIMEBounds() {
  const terminal = terminalRef.value;
  if (!terminal) return;
  terminalDisposables?.addEventListener(window, "resize", scheduleClampIME);
}

function scheduleClampIME() {
  requestAnimationFrame(clampCompositionView);
}

function clampCompositionView() {
  const terminal = terminalRef.value;
  if (!terminal) return;
  const composition = terminal.querySelector(".composition-view") as HTMLElement;
  if (!composition) return;
  const terminalRect = terminal.getBoundingClientRect();
  const compositionRect = composition.getBoundingClientRect();
  const available = Math.max(24, terminalRect.right - compositionRect.left - 8);
  composition.style.maxWidth = `${Math.min(available, terminalRect.width - 16)}px`;
}

function setupQuickbarMetrics() {
  const quickbar = document.querySelector(".quickbar");
  if (!quickbar) return;

  const updateQuickbarHeight = () => {
    const height = Math.ceil(quickbar.getBoundingClientRect().height);
    if (height > 0) {
      document.documentElement.style.setProperty("--quickbar-height", `${height}px`);
    }
  };

  updateQuickbarHeight();
  requestAnimationFrame(updateQuickbarHeight);
  terminalDisposables?.addEventListener(window, "resize", updateQuickbarHeight);
}

// --- 页面卸载清理 ---

function disposeTerminalPage() {
  clearReconnect();
  closeWS();
  if (terminalDisposables) {
    terminalDisposables.dispose();
    terminalDisposables = null;
  }
  if (terminalView) {
    terminalView.dispose();
    terminalView = null;
  }
  inputController = null;
  layoutController = null;
  selectionController = null;
}

function closeWS() {
  manualClose = true;
  if (ws) {
    ws.close();
    ws = null;
  }
}

// --- Playwright E2E 单元测试调试钩子安装 ---

function debugEnabled() {
  return new URLSearchParams(window.location.search).has("debug")
    || localStorage.getItem("webtermDebug") === "1";
}

function setupTerminalDebugHooks() {
  if (!debugEnabled()) return;
  (window as any).__webtermDebug = {
    scroll() {
      const buffer = terminalView?.buffer?.active;
      const viewport = document.querySelector("#terminal .xterm-viewport");
      return {
        viewportY: buffer?.viewportY ?? null,
        baseY: buffer?.baseY ?? null,
        bufferType: buffer?.type ?? null,
        rows: terminalView?.rows ?? null,
        cols: terminalView?.cols ?? null,
        scrollTop: viewport?.scrollTop ?? null,
        scrollHeight: viewport?.scrollHeight ?? null,
        clientHeight: viewport?.clientHeight ?? null,
        visualViewport: window.visualViewport ? {
          height: window.visualViewport.height,
          offsetTop: window.visualViewport.offsetTop,
        } : null,
        innerHeight: window.innerHeight,
      };
    },
    scrollToLine(line: number) {
      terminalView?.scrollToLine(Number(line) || 0);
      return this.scroll();
    },
    scrollLines(lines: number) {
      terminalView?.scrollLines(Number(lines) || 0);
      return this.scroll();
    },
    focus() {
      terminalView?.focus();
      return this.scroll();
    },
    input(data: string) {
      sendInput(String(data || ""));
      return this.scroll();
    },
    selectText(text: string) {
      const needle = String(text || "");
      const buffer = terminalView?.buffer?.active;
      if (!needle || !buffer) return "";
      for (let index = 0; index < buffer.length; index += 1) {
        const line = buffer.getLine(index)?.translateToString(true) || "";
        const col = line.indexOf(needle);
        if (col >= 0) {
          terminalView?.select(col, index, needle.length);
          return terminalView?.getSelection() || "";
        }
      }
      return "";
    },
    termState() {
      const buffer = terminalView?.buffer?.active;
      return {
        cols: terminalView?.cols ?? null,
        rows: terminalView?.rows ?? null,
        viewportY: buffer?.viewportY ?? null,
        baseY: buffer?.baseY ?? null,
        text: terminalBufferText(),
      };
    },
    wsState() {
      return {
        readyState: ws?.readyState ?? null,
        restored: restored,
        lastSeq: lastSeq,
        manualClose: manualClose,
      };
    },
    layoutState() {
      return layoutController?.stats() || null;
    },
    lifecycleState() {
      return {
        disposables: terminalDisposables?.size ?? 0,
        hasTerminalView: Boolean(terminalView),
        hasInputController: Boolean(inputController),
        hasLayoutController: Boolean(layoutController),
        hasSelectionController: Boolean(selectionController),
        wsReadyState: ws?.readyState ?? null,
      };
    },
    writeQueue() {
      return terminalView?.stats() || null;
    },
  };
}

function terminalBufferText() {
  const buffer = terminalView?.buffer?.active;
  if (!buffer?.length) return "";
  const lines = [];
  for (let index = 0; index < buffer.length; index += 1) {
    lines.push(buffer.getLine(index)?.translateToString(true) || "");
  }
  return lines.join("\n");
}
</script>

<style scoped>
/* 可以在此针对单屏渲染做微调 */
</style>
