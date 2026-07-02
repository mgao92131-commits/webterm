import { api, store } from '../store';
import { p2pManager } from './p2p';
import { relayMuxSessionManager } from './relay-mux-session-manager';
import type { RelayMuxChannel } from './relay-mux-session';
import { decodeTerminalMessage, encodeTerminalMessage } from './terminal-binary-protocol';
import { DisposableStore, IDisposable } from './disposable';
import { TerminalView } from './terminal-view';
import { TerminalInputController } from './terminal-input-controller';
import { TerminalLayoutController } from './terminal-layout';
import { TerminalSelectionController } from './terminal-selection';

function getInitialFontSize(): number {
  if (typeof window !== 'undefined') {
    const saved = localStorage.getItem('webterm:fontSize');
    if (saved) {
      const size = parseInt(saved, 10);
      if (!isNaN(size) && size >= 8 && size <= 30) {
        return size;
      }
    }
    return window.innerWidth < 768 ? 12 : 14;
  }
  return 12;
}

export interface TerminalSessionState {
  isSelectionMode: boolean;
  isCtrlActive: boolean;
  isRestoring: boolean;
  sessionName: string;
  sessionPlaceholder: string;
  displayTitle: string;
}

export interface TerminalSessionContextOptions {
  element: HTMLElement;
  sessionId: string;
  theme: 'solarized' | 'dracula';
  p2pActive: boolean;
  onStateChange?: (state: Partial<TerminalSessionState>) => void;
  onExit?: () => void;
}

export class TerminalSessionContext implements IDisposable {
  private static readonly RECONNECT_BLOCKED_CLOSE_CODES = [1000, 1008, 1011];

  private disposables = new DisposableStore();
  public terminalView!: TerminalView;
  public inputController!: TerminalInputController;
  public layoutController!: TerminalLayoutController;
  public selectionController!: TerminalSelectionController;

  private ws: WebSocket | RelayMuxChannel | null = null;
  private reconnectTimer: any = null;
  private reconnectAttempts = 0;
  private manualClose = false;
  private binaryTransport = false;
  private lastCloseCode: number | null = null;
  private lastSeq = 0;
  private restored = false;

  // 内部状态维护
  private state: TerminalSessionState = {
    isSelectionMode: false,
    isCtrlActive: false,
    isRestoring: false,
    sessionName: '',
    sessionPlaceholder: 'Terminal',
    displayTitle: 'Terminal',
  };

  private currentSessionName = '';
  private currentTermTitle = '';
  private currentDisplayTitle = 'Terminal';
  public titleBeforeEdit = '';

  constructor(private options: TerminalSessionContextOptions) {
    this.lastSeq = Number(sessionStorage.getItem(`webterm:${options.sessionId}:lastSeq`) || 0);
    
    this.initControllers();
    this.attachEvents();
    this.connectWS();
  }

  private initControllers() {
    const { element, theme } = this.options;

    // 1. 实例化 TerminalView 核心显示层
    this.terminalView = new TerminalView({
      element: element,
      options: {
        cursorBlink: true,
        fontFamily: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace',
        fontSize: getInitialFontSize(),
        convertEol: true,
        scrollback: 20000,
        overviewRuler: { width: 4 },
        theme: this.getTermTheme(theme),
      }
    });

    // 2. 初始化输入控制器
    this.inputController = new TerminalInputController({
      store: this.disposables,
      root: document.body,
      terminalElement: element,
      sendInput: (data) => this.sendInput(data),
      focusTerminal: () => this.terminalView?.focus(),
      onModifierChange: (modifier) => {
        this.updateState({ isCtrlActive: modifier === 'ctrl' });
      }
    });
    this.inputController.attach();

    // 3. 初始化布局和自适应控制器
    const container = element.parentElement || element;
    this.layoutController = new TerminalLayoutController({
      store: this.disposables,
      terminalView: this.terminalView,
      container: container,
      documentElement: document.documentElement,
      sendResizeMessage: (size) => this.send({ type: 'resize', ...size }),
      isVisible: () => !document.hidden,
    });
    this.layoutController.attach();
    this.disposables.add(this.layoutController);

    // 4. 初始化选区控制器
    this.selectionController = new TerminalSelectionController({
      store: this.disposables,
      root: document.body,
      terminalElement: element,
      terminalView: this.terminalView,
      clearPendingInput: () => this.inputController?.clearPendingModifier(),
      onSelectionModeChange: (active) => {
        this.updateState({ isSelectionMode: active });
      }
    });
    this.selectionController.attach();
  }

  private attachEvents() {
    const { element } = this.options;

    // 绑定滚动和渲染以自适应底部粘滞和 IME 限制
    this.disposables.add(this.terminalView.onScroll(() => {
      this.layoutController?.handleTerminalScroll();
    }));

    this.disposables.add(this.terminalView.onRender(() => {
      this.layoutController?.handleTerminalRender();
      this.scheduleClampIME();
    }));

    // 绑定 xterm.js 原始输入数据流
    this.disposables.add(this.terminalView.onData((data) => this.handleTerminalData(data)));

    // 绑定全局浏览器标签可见性事件，恢复网络与重算视口
    this.disposables.addEventListener(document, 'visibilitychange', () => {
      if (!document.hidden) {
        this.ensureConnected();
        this.disposables.addTimeout(setTimeout(() => this.layoutController?.sendResize({ reason: 'visibility' }), 150));
      }
    });

    // 监听网络连接状态变化
    this.disposables.addEventListener(window, 'online', () => {
      if (this.canReconnect()) {
        this.reconnectAttempts = 0;
        this.ensureConnected();
      }
    });
    this.disposables.addEventListener(window, 'offline', () => {
      this.clearReconnect();
    });

    // 初始化移动端快捷栏高度动态计算
    this.setupQuickbarMetrics();
  }

  // --- WebSocket 长连接与协议控制 ---

  private connectWS() {
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return;
    }

    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    this.manualClose = false;

    const id = this.options.sessionId;
    const [parsedDeviceId, localId] = id.includes(':') ? id.split(':') : ['', id];
    const deviceId = parsedDeviceId || (store.mode === 'relay' ? store.selectedDeviceId || '' : '');
    let wsUrl = `${proto}://${window.location.host}/ws/sessions/${encodeURIComponent(localId)}`;
    if (deviceId) {
      wsUrl += `?deviceId=${encodeURIComponent(deviceId)}`;
    }

    this.binaryTransport = false;
    if (store.mode === 'relay' && deviceId) {
      this.ws = relayMuxSessionManager.openTerminalChannel(deviceId, id);
      this.binaryTransport = true;
    } else if (p2pManager.isP2PActive()) {
      this.ws = p2pManager.createWebSocketMock(wsUrl, ['binary', 'json']) as any;
    } else {
      this.ws = new WebSocket(wsUrl);
    }

    this.disposables.addEventListener(this.ws, 'open', () => {
      this.lastCloseCode = null;
      this.reconnectAttempts = 0;
      this.clearReconnect();
      try {
        this.terminalView.fit();
      } catch (e) {
        console.warn('Failed to fit terminal on ws open', e);
      }
      this.send({
        type: 'hello',
        lastSeq: this.restored ? this.lastSeq : 0,
        cols: this.terminalView.cols,
        rows: this.terminalView.rows,
      });
      this.layoutController?.sendResize({ reason: 'ws-open' });
    });

    this.disposables.addEventListener(this.ws, 'message', (event: any) => {
      let msg: any;
      if (this.binaryTransport && typeof event.data !== 'string') {
        msg = decodeTerminalMessage(event.data);
        if (!msg) return;
      } else {
        try {
          msg = JSON.parse(event.data);
        } catch {
          return;
        }
      }

      if (msg.type === 'state') {
        this.terminalView.reset();
        this.beginTerminalRestore();
        if (msg.data) {
          this.terminalView.writeSync(msg.data, () => {
            this.finishTerminalRestore({ fit: true, seq: msg.seq });
            this.terminalView.refreshAll();
            this.restored = true;
          });
        } else {
          this.finishTerminalRestore({ fit: true, seq: msg.seq });
          this.terminalView.refreshAll();
          this.restored = true;
        }
      } else if (msg.type === 'replay') {
        if (!this.restored || msg.from === 0) {
          this.terminalView.reset();
        }
        this.beginTerminalRestore();
        const frames = msg.frames || [];
        if (frames.length > 0) {
          for (let i = 0; i < frames.length; i++) {
            const frame = frames[i];
            const isLast = i === frames.length - 1;
            this.terminalView.enqueueWrite(frame.data || '', () => {
              this.rememberSeq(frame.seq);
              if (isLast) this.finishTerminalRestore({ seq: msg.seq });
            });
          }
        } else {
          this.finishTerminalRestore({ fit: true, seq: msg.seq });
        }
        this.terminalView.refreshAll();
        this.restored = true;
      } else if (msg.type === 'output') {
        this.terminalView.enqueueWrite(msg.data, () => this.rememberSeq(msg.seq));
      } else if (msg.type === 'info') {
        this.setTerminalInfo(msg.data);
      } else if (msg.type === 'exit') {
        this.manualClose = true;
        this.options.onExit?.();
      }
    });

    this.disposables.addEventListener(this.ws, 'close', (event: any) => {
      this.ws = null;
      this.lastCloseCode = typeof event.code === 'number' ? event.code : null;
      if (this.canReconnect()) {
        this.scheduleReconnect();
      }
    });
  }

  private send(msg: any) {
    this.ensureConnected();
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(this.binaryTransport ? encodeTerminalMessage(msg) : JSON.stringify(msg));
    }
  }

  private sendInput(data: string, _source?: string) {
    this.terminalView?.scrollToBottom();
    this.send({ type: 'input', data });
  }

  private handleTerminalData(data: string) {
    if (!this.inputController?.pendingModifier) {
      this.sendInput(data);
      return;
    }
    if (!this.inputController.sendModifiedInput(data)) {
      this.sendInput(data);
    }
  }

  private ensureConnected() {
    if (!this.canReconnect()) return;
    if (!this.ws || this.ws.readyState === WebSocket.CLOSED || this.ws.readyState === WebSocket.CLOSING) {
      this.connectWS();
    }
  }

  private scheduleReconnect() {
    this.clearReconnect();
    const cap = Math.min(1000 * Math.pow(1.6, this.reconnectAttempts++), 8000);
    const delay = Math.max(200, Math.random() * cap);
    this.reconnectTimer = setTimeout(() => {
      this.ws = null;
      this.ensureConnected();
    }, delay);
  }

  private clearReconnect() {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private canReconnect() {
    return !this.manualClose
      && !TerminalSessionContext.RECONNECT_BLOCKED_CLOSE_CODES.includes(this.lastCloseCode || 0);
  }

  private beginTerminalRestore() {
    this.updateState({ isRestoring: true });
  }

  private finishTerminalRestore(options: { fit?: boolean; seq?: number } = {}) {
    if (options.seq) this.rememberSeq(options.seq);
    this.updateState({ isRestoring: false });
    this.terminalView?.scrollToBottom();
    this.terminalView?.refreshAll();
    this.layoutController?.settleAfterWrite({ fit: options.fit });
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        this.updateState({ isRestoring: false });
      });
    });
  }

  private rememberSeq(seq: number) {
    const value = Number(seq || 0);
    if (!value || value < this.lastSeq) return;
    this.lastSeq = value;
    sessionStorage.setItem(`webterm:${this.options.sessionId}:lastSeq`, String(value));
  }

  // --- 状态与广播机制 ---

  private updateState(change: Partial<TerminalSessionState>) {
    this.state = { ...this.state, ...change };
    this.options.onStateChange?.(change);
  }

  private setTerminalInfo(session: any = {}) {
    if (Object.prototype.hasOwnProperty.call(session, 'name')) {
      this.currentSessionName = String(session.name || '').trim();
    }
    if (Object.prototype.hasOwnProperty.call(session, 'termTitle')) {
      this.currentTermTitle = String(session.termTitle || '').trim();
    }
    this.currentDisplayTitle = session.displayTitle || (() => {
      const term = this.currentTermTitle || 'Terminal';
      return this.currentSessionName ? `${this.currentSessionName} - ${term}` : term;
    })();

    const sessionPlaceholder = this.currentTermTitle || 'Terminal';
    const displayTitle = `${this.currentDisplayTitle} - WebTerm`;
    document.title = displayTitle;

    this.updateState({
      sessionName: this.currentSessionName,
      sessionPlaceholder,
      displayTitle,
    });
  }

  // --- 字体大小动态调整 ---

  public changeFontSize(delta: number): void {
    const current = this.terminalView.options.fontSize || 12;
    const next = Math.min(30, Math.max(8, current + delta));
    if (this.layoutController) {
      this.layoutController.setFontSize(next);
      localStorage.setItem('webterm:fontSize', String(next));
    }
  }

  // --- 标题就地编辑 API 调用 ---

  public async commitTerminalTitle(nextName: string): Promise<void> {
    const oldName = this.titleBeforeEdit || '';
    this.updateState({ sessionName: nextName });

    if (nextName === oldName) {
      this.setTerminalInfo({ name: oldName });
      this.terminalView?.focus();
      return;
    }

    try {
      const id = this.options.sessionId;
      const localId = id.includes(':') ? id.split(':')[1] : id;
      const session = await api(`/api/sessions/${encodeURIComponent(localId)}`, {
        method: 'PATCH',
        body: JSON.stringify({ name: nextName }),
      });
      this.setTerminalInfo(session);
    } catch (err: any) {
      this.setTerminalInfo({ name: oldName });
      alert(err.message?.trim() || '修改标题失败');
    } finally {
      this.terminalView?.focus();
    }
  }

  // --- UI/DOM 细节计算 ---

  private scheduleClampIME() {
    requestAnimationFrame(() => this.clampCompositionView());
  }

  private clampCompositionView() {
    const terminal = this.options.element;
    if (!terminal) return;
    const composition = terminal.querySelector('.composition-view') as HTMLElement;
    if (!composition) return;
    const terminalRect = terminal.getBoundingClientRect();
    const compositionRect = composition.getBoundingClientRect();
    const available = Math.max(24, terminalRect.right - compositionRect.left - 8);
    composition.style.maxWidth = `${Math.min(available, terminalRect.width - 16)}px`;
  }

  private setupQuickbarMetrics() {
    const quickbar = document.querySelector('.quickbar');
    if (!quickbar) return;

    const updateQuickbarHeight = () => {
      const height = Math.ceil(quickbar.getBoundingClientRect().height);
      if (height > 0) {
        document.documentElement.style.setProperty('--quickbar-height', `${height}px`);
      }
    };

    updateQuickbarHeight();
    requestAnimationFrame(updateQuickbarHeight);
    this.disposables.addEventListener(window, 'resize', updateQuickbarHeight);
  }

  // --- 主题数据获取 ---

  private getTermTheme(theme: 'solarized' | 'dracula') {
    const THEMES: any = {
      solarized: {
        background: '#002b36',
        foreground: '#839496',
        cursor: '#93a1a1',
        selectionBackground: '#073642',
        black: '#073642',
        red: '#dc322f',
        green: '#859900',
        yellow: '#b58900',
        blue: '#268bd2',
        magenta: '#d33682',
        cyan: '#2aa198',
        white: '#eee8d5',
        brightBlack: '#002b36',
        brightRed: '#cb4b16',
        brightGreen: '#586e75',
        brightYellow: '#657b83',
        brightBlue: '#839496',
        brightMagenta: '#6c71c4',
        brightCyan: '#93a1a1',
        brightWhite: '#fdf6e3',
      },
      dracula: {
        background: '#282a36',
        foreground: '#f8f8f2',
        cursor: '#f8f8f2',
        selectionBackground: '#44475a',
        black: '#21222c',
        red: '#ff5555',
        green: '#50fa7b',
        yellow: '#f1fa8c',
        blue: '#bd93f9',
        magenta: '#ff79c6',
        cyan: '#8be9fd',
        white: '#f8f8f2',
        brightBlack: '#6272a4',
        brightRed: '#ff6e6e',
        brightGreen: '#69ff94',
        brightYellow: '#ffffa5',
        brightBlue: '#d6acff',
        brightMagenta: '#ff92df',
        brightCyan: '#a4ffff',
        brightWhite: '#ffffff',
      }
    };
    return THEMES[theme] ? THEMES[theme] : THEMES.solarized;
  }

  // --- Playwright Debug 调试钩子供 E2E 测试使用 ---

  public getDebugHooks() {
    return {
      scroll: () => {
        const buffer = this.terminalView?.buffer?.active;
        const viewport = document.querySelector('#terminal .xterm-viewport');
        return {
          viewportY: buffer?.viewportY ?? null,
          baseY: buffer?.baseY ?? null,
          bufferType: buffer?.type ?? null,
          rows: this.terminalView?.rows ?? null,
          cols: this.terminalView?.cols ?? null,
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
      scrollToLine: (line: number) => {
        this.terminalView?.scrollToLine(Number(line) || 0);
        return (window as any).__webtermDebug.scroll();
      },
      scrollLines: (lines: number) => {
        this.terminalView?.scrollLines(Number(lines) || 0);
        return (window as any).__webtermDebug.scroll();
      },
      focus: () => {
        this.terminalView?.focus();
        return (window as any).__webtermDebug.scroll();
      },
      input: (data: string) => {
        this.sendInput(String(data || ''));
        return (window as any).__webtermDebug.scroll();
      },
      selectText: (text: string) => {
        const needle = String(text || '');
        const buffer = this.terminalView?.buffer?.active;
        if (!needle || !buffer) return '';
        for (let index = 0; index < buffer.length; index += 1) {
          const line = buffer.getLine(index)?.translateToString(true) || '';
          const col = line.indexOf(needle);
          if (col >= 0) {
            this.terminalView?.select(col, index, needle.length);
            return this.terminalView?.getSelection() || '';
          }
        }
        return '';
      },
      termState: () => {
        const buffer = this.terminalView?.buffer?.active;
        return {
          cols: this.terminalView?.cols ?? null,
          rows: this.terminalView?.rows ?? null,
          viewportY: buffer?.viewportY ?? null,
          baseY: buffer?.baseY ?? null,
          text: this.terminalBufferText(),
        };
      },
      wsState: () => {
        return {
          readyState: this.ws?.readyState ?? null,
          restored: this.restored,
          lastSeq: this.lastSeq,
          manualClose: this.manualClose,
        };
      },
      layoutState: () => {
        return this.layoutController?.stats() || null;
      },
      lifecycleState: () => {
        return {
          disposables: this.disposables?.size ?? 0,
          hasTerminalView: Boolean(this.terminalView),
          hasInputController: Boolean(this.inputController),
          hasLayoutController: Boolean(this.layoutController),
          hasSelectionController: Boolean(this.selectionController),
          wsReadyState: this.ws?.readyState ?? null,
        };
      },
      keyboardAvoidance: () => {
        this.layoutController?.debugKeyboardAvoidance();
        return (window as any).__webtermDebug.scroll();
      },
      writeQueue: () => {
        return this.terminalView?.stats() || null;
      },
    };
  }

  private terminalBufferText() {
    const buffer = this.terminalView?.buffer?.active;
    if (!buffer?.length) return '';
    const lines = [];
    for (let index = 0; index < buffer.length; index += 1) {
      lines.push(buffer.getLine(index)?.translateToString(true) || '');
    }
    return lines.join('\n');
  }

  public dispose() {
    this.manualClose = true;
    this.clearReconnect();
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
    this.disposables.dispose();
    if (this.terminalView) {
      this.terminalView.dispose();
    }
  }
}
