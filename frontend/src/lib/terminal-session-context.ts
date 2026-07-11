import { CONFIG } from '../config';
import { renameSession } from '../services/session.service';
import { getTerminalTheme } from '../config/themes';
import { connectionService } from '../services/connection.service';
import type { RelayMuxChannel } from './relay-mux-session';
import { decodeTerminalMessage, encodeTerminalMessage } from './terminal-binary-protocol';
import { DisposableStore, IDisposable } from './disposable';
import { TerminalView } from './terminal-view';
import { TerminalInputController } from './terminal-input-controller';
import { TerminalLayoutController } from './terminal-layout';
import { TerminalSelectionController } from './terminal-selection';

function getInitialFontSize(): number {
  if (typeof window !== 'undefined') {
    const saved = localStorage.getItem(CONFIG.storageKeys.fontSize);
    if (saved) {
      const size = parseInt(saved, 10);
      if (!isNaN(size) && size >= CONFIG.fontSize.min && size <= CONFIG.fontSize.max) {
        return size;
      }
    }
    return window.innerWidth < 768 ? CONFIG.fontSize.defaultMobile : CONFIG.fontSize.defaultDesktop;
  }
  return CONFIG.fontSize.defaultMobile;
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
  mode: 'direct' | 'relay';
  deviceId: string | null;
  setDocumentTitle?: boolean;
  onStateChange?: (state: Partial<TerminalSessionState>) => void;
  onExit?: () => void;
}

export class TerminalSessionContext implements IDisposable {

  private disposables = new DisposableStore();
  public terminalView!: TerminalView;
  public inputController!: TerminalInputController;
  public layoutController!: TerminalLayoutController;
  public selectionController!: TerminalSelectionController;

  private ws: WebSocket | RelayMuxChannel | null = null;
  // 每条连接自己的监听器容器：换连接时整体丢弃，避免旧连接的监听器残留
  private wsDisposables: DisposableStore | null = null;
  // 连接代际（号码牌）：拨新连接时 +1。旧连接迟到的 close 事件靠它识别并忽略
  private wsGeneration = 0;
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
    this.lastSeq = Number(sessionStorage.getItem(`${CONFIG.storageKeys.lastSeqPrefix}${options.sessionId}:lastSeq`) || 0);
    
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

    // 清理上一条连接：解绑它的监听器并关闭，避免旧连接迟到的 close 事件回调误把新连接指针抹空
    if (this.wsDisposables) {
      this.wsDisposables.dispose();
      this.wsDisposables = null;
    }
    if (this.ws) {
      try { (this.ws as any).close(); } catch (e) {}
      this.ws = null;
    }

    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    this.manualClose = false;

    const id = this.options.sessionId;
    const deviceId = this.options.deviceId || '';

    // mux 传输（direct/relay）都走二进制帧；只有 P2P mock 保持 JSON
    this.binaryTransport = true;
    this.ws = connectionService.openTerminalChannel(deviceId, id);

    // 本条连接专属的监听器容器 + 代际号码牌
    const gen = ++this.wsGeneration;
    this.wsDisposables = new DisposableStore();

    this.wsDisposables.addEventListener(this.ws, 'open', () => {
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

    this.wsDisposables.addEventListener(this.ws, 'message', (event: any) => {
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
      } else if (msg.type === 'hook') {
        this.handleHookEvent(msg.data);
      } else if (msg.type === 'exit') {
        this.manualClose = true;
        this.options.onExit?.();
      }
    });

    this.wsDisposables.addEventListener(this.ws, 'close', (event: any) => {
      // 旧连接迟到的 close：号码牌对不上，说明已经被新连接取代，直接忽略，不要去抹空 this.ws
      if (gen !== this.wsGeneration) return;
      // relay 模式下，底层 transport 掉线时 mux 会把 channel 置回 CONNECTING 并自动重连。
      // 此时 close 不是"真死"，若清空 this.ws 自行重连，会与 mux 抢恢复、错过 channel 重新 open 的信号，
      // 导致 hello 不再发送、画面乱序卡住。所以这里保持原样，交给 mux 恢复即可。
      if (this.binaryTransport && this.ws && this.ws.readyState === WebSocket.CONNECTING) {
        return;
      }
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
    // 已有可用连接（已连上或正在连），不重复拨号
    if (this.ws && this.ws.readyState !== WebSocket.CLOSED && this.ws.readyState !== WebSocket.CLOSING) {
      return;
    }
    // 已经排了重连闹钟，就让它来连，避免每次按键/可见性切换都抢着重拨、触发服务端全量回放
    if (this.reconnectTimer) {
      return;
    }
    this.connectWS();
  }

  private scheduleReconnect() {
    this.clearReconnect();
    const backoff = CONFIG.reconnectBackoff;
    const cap = Math.min(backoff.baseMs * Math.pow(backoff.multiplier, this.reconnectAttempts++), backoff.capMs);
    const delay = Math.max(backoff.minDelayMs, Math.random() * cap);
    this.reconnectTimer = setTimeout(() => {
      // 闹钟响了，但如果在这期间已经有人把连接重建好（且正在通），就不要再掐掉重拨
      if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
        return;
      }
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
      && !CONFIG.reconnectBlockedCloseCodes.includes((this.lastCloseCode || 0) as 1000 | 1008 | 1011);
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
    sessionStorage.setItem(`${CONFIG.storageKeys.lastSeqPrefix}${this.options.sessionId}:lastSeq`, String(value));
  }

  // --- 状态与广播机制 ---

  private handleHookEvent(data: any) {
    if (!data || data.type !== 'notify') return;
    window.dispatchEvent(new CustomEvent('webterm:hook', {
      detail: { sessionId: this.options.sessionId, ...data },
    }));
  }

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
    if (this.options.setDocumentTitle !== false) {
      document.title = displayTitle;
    }

    this.updateState({
      sessionName: this.currentSessionName,
      sessionPlaceholder,
      displayTitle,
    });
  }

  // --- 字体大小动态调整 ---

  public changeFontSize(delta: number): void {
    const current = this.terminalView.options.fontSize || CONFIG.fontSize.defaultDesktop;
    const next = Math.min(CONFIG.fontSize.max, Math.max(CONFIG.fontSize.min, current + delta));
    if (this.layoutController) {
      this.layoutController.setFontSize(next);
      localStorage.setItem(CONFIG.storageKeys.fontSize, String(next));
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
      const session = await renameSession(this.options.sessionId, nextName);
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
    return getTerminalTheme(theme);
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
    if (this.wsDisposables) {
      this.wsDisposables.dispose();
      this.wsDisposables = null;
    }
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
