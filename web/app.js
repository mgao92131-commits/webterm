import { DisposableStore } from "./lib/disposable.js";
import { TerminalInputController } from "./lib/terminal-input-controller.js";
import { TerminalLayoutController } from "./lib/terminal-layout.js";
import { TerminalSelectionController } from "./lib/terminal-selection.js";
import { TerminalView } from "./lib/terminal-view.js";

  const app = document.getElementById("app");
  const THEMES = {
    solarized: {
      label: "Solarized",
      next: "dracula",
      terminal: {
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
    },
    dracula: {
      label: "Dracula",
      next: "solarized",
      terminal: {
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
      },
    },
  };
  const storedTheme = localStorage.getItem("webterm-theme");
  const state = {
    user: null,
    sessions: [],
    theme: THEMES[storedTheme] ? storedTheme : "solarized",
    ws: null,
    term: null,
    managerTimer: null,
    managerWS: null,
    managerReconnectTimer: null,
    managerReconnectAttempts: 0,
    managerManualClose: false,
    terminalID: null,
    terminalDisposables: null,
    inputController: null,
    layoutController: null,
    selectionController: null,
    terminalView: null,
    reconnectTimer: null,
    reconnectAttempts: 0,
    manualClose: false,
    lastSeq: 0,
    restored: false,
    currentSessionName: "",
    currentTermTitle: "",
    currentDisplayTitle: "Terminal",
    titleBeforeEdit: "",
    titleEditing: false,
    skipTitleCommit: false,
  };

  document.documentElement.dataset.theme = state.theme;

  async function api(path, options = {}) {
    const res = await fetch(path, {
      credentials: "same-origin",
      headers: { "Content-Type": "application/json", ...(options.headers || {}) },
      ...options,
    });
    if (!res.ok) {
      const text = await res.text();
      const err = new Error(text || res.statusText);
      err.status = res.status;
      throw err;
    }
    if (res.status === 204) return null;
    return res.json();
  }

  async function boot() {
    try {
      state.user = await api("/api/me");
      if (location.pathname.startsWith("/terminal/")) {
        const id = location.pathname.split("/").filter(Boolean).pop();
        if (!id || id === "terminal") {
          history.replaceState(null, "", "/");
          renderManager();
          return;
        }
        renderTerminal(id);
      } else {
        renderManager();
      }
    } catch (err) {
      if (err.status === 401) {
        renderLogin();
      } else {
        renderFatal(err);
      }
    }
  }

  function renderFatal(err) {
    document.title = "WebTerm";
    app.innerHTML = `
      <section class="login-shell">
        <div class="login-card">
          <h1>WebTerm</h1>
          <p class="error">${escapeHTML(err.message || String(err))}</p>
          <a class="button" href="/">返回主界面</a>
        </div>
      </section>`;
  }

  function renderLogin() {
    document.title = "WebTerm";
    app.innerHTML = `
      <section class="login-shell">
        <form class="login-card">
          <h1>WebTerm</h1>
          <label>用户<input name="username" autocomplete="username" value="admin" /></label>
          <label>密码<input name="password" type="password" autocomplete="current-password" autofocus /></label>
          <button type="submit">登录</button>
          <p class="error" hidden></p>
        </form>
      </section>`;
    app.querySelector("form").addEventListener("submit", async (event) => {
      event.preventDefault();
      const form = new FormData(event.currentTarget);
      const error = app.querySelector(".error");
      error.hidden = true;
      try {
        state.user = await api("/api/login", {
          method: "POST",
          body: JSON.stringify({
            username: form.get("username"),
            password: form.get("password"),
          }),
        });
        const next = location.pathname && location.pathname !== "/terminal/" ? location.pathname : "/";
        location.href = next;
      } catch (err) {
        error.textContent = err.message.trim();
        error.hidden = false;
      }
    });
  }

  async function refreshSessions() {
    state.sessions = await api("/api/sessions");
  }

  async function renderManager() {
    document.title = "WebTerm";
    document.body.classList.remove("terminal-mode");
    disposeTerminalPage();
    await refreshSessions();
    app.innerHTML = `
      <section class="manager">
        <header class="topbar">
          <div>
            <h1>WebTerm</h1>
            <span>${escapeHTML(state.user.username)}</span>
          </div>
          <div class="actions">
            <button id="theme">${themeButtonLabel()}</button>
            <button id="new">新建终端</button>
          </div>
        </header>
        <p class="error" id="managerError" hidden></p>
        <section class="session-list"></section>
      </section>`;
    app.querySelector("#new").addEventListener("click", createDefaultSession);
    app.querySelector("#theme").addEventListener("click", toggleTheme);
    drawSessionList();
    startManagerRefresh();
    connectManagerWS();
  }

  function startManagerRefresh() {
    stopManagerRefresh();
    state.managerTimer = setInterval(async () => {
      if (location.pathname !== "/") {
        stopManagerRefresh();
        return;
      }
      try {
        await refreshSessions();
        drawSessionList();
      } catch (err) {
        setManagerError(err.message.trim());
      }
    }, 3000);
  }

  function stopManagerRefresh() {
    if (state.managerTimer) {
      clearInterval(state.managerTimer);
      state.managerTimer = null;
    }
  }

  function connectManagerWS() {
    if (location.pathname !== "/") return;
    if (state.managerWS && (state.managerWS.readyState === WebSocket.OPEN || state.managerWS.readyState === WebSocket.CONNECTING)) {
      return;
    }
    const proto = location.protocol === "https:" ? "wss" : "ws";
    state.managerManualClose = false;
    state.managerWS = new WebSocket(`${proto}://${location.host}/ws/sessions`);
    state.managerWS.addEventListener("open", () => {
      state.managerReconnectAttempts = 0;
      clearManagerReconnect();
      stopManagerRefresh();
      setManagerError("");
    });
    state.managerWS.addEventListener("message", (event) => {
      if (location.pathname !== "/") return;
      const msg = JSON.parse(event.data);
      if (msg.type === "sessions") {
        state.sessions = Array.isArray(msg.data) ? msg.data : [];
        drawSessionList();
      } else if (msg.type === "session") {
        upsertSession(msg.data);
        drawSessionList();
      } else if (msg.type === "session-closed") {
        removeSession(msg.id);
        drawSessionList();
      }
    });
    state.managerWS.addEventListener("close", () => {
      state.managerWS = null;
      if (state.managerManualClose || location.pathname !== "/") return;
      startManagerRefresh();
      scheduleManagerReconnect();
    });
    state.managerWS.addEventListener("error", () => {
      startManagerRefresh();
    });
  }

  function closeManagerWS() {
    state.managerManualClose = true;
    clearManagerReconnect();
    if (state.managerWS) {
      state.managerWS.close();
      state.managerWS = null;
    }
  }

  function scheduleManagerReconnect() {
    clearManagerReconnect();
    const delay = Math.min(1000 * Math.pow(1.6, state.managerReconnectAttempts++), 8000);
    state.managerReconnectTimer = setTimeout(() => connectManagerWS(), delay);
  }

  function clearManagerReconnect() {
    if (state.managerReconnectTimer) {
      clearTimeout(state.managerReconnectTimer);
      state.managerReconnectTimer = null;
    }
  }

  function upsertSession(session) {
    if (!session?.id) return;
    const index = state.sessions.findIndex((item) => item.id === session.id);
    if (index >= 0) {
      state.sessions.splice(index, 1, session);
    } else {
      state.sessions.push(session);
    }
  }

  function removeSession(id) {
    state.sessions = state.sessions.filter((session) => session.id !== id);
  }

  function drawSessionList() {
    const list = app.querySelector(".session-list");
    if (!state.sessions.length) {
      list.innerHTML = `<div class="empty">还没有终端</div>`;
      return;
    }
    list.innerHTML = state.sessions.map((s) => {
      const displayTitle = sessionDisplayTitle(s);
      const nameLine = sessionNameHTML(s);
      const termTitle = sessionTermTitle(s);
      const recentInput = recentInputHTML(s);
      return `
        <article class="session-card">
          <button class="session-close" data-close="${escapeHTML(s.id)}" title="关闭会话" aria-label="关闭会话">x</button>
          <a class="session-link" href="/terminal/${encodeURIComponent(s.id)}" title="打开终端" aria-label="打开 ${escapeHTML(displayTitle)} 终端">
            <div class="session-title">${escapeHTML(termTitle)}</div>
            ${nameLine}
            ${recentInput}
            <div class="session-cwd">${escapeHTML(s.cwd)}</div>
          </a>
        </article>`;
    }).join("");
    list.querySelectorAll("[data-close]").forEach((btn) => {
      btn.addEventListener("click", async () => {
        if (!confirm("关闭这个会话？")) return;
        await api(`/api/sessions/${encodeURIComponent(btn.dataset.close)}`, { method: "DELETE" });
        await renderManager();
      });
    });
  }

  function sessionNameHTML(session) {
    const name = String(session.name || "").trim();
    return name ? `<div class="session-name">${escapeHTML(name)}</div>` : "";
  }

  function sessionTermTitle(session) {
    return String(session.termTitle || "").trim() || "Terminal";
  }

  function recentInputHTML(session) {
    if (session.recentInputHidden) {
      return `<div class="recent-input"><pre>敏感输入已隐藏</pre></div>`;
    }
    const lines = Array.isArray(session.recentInputLines) ? session.recentInputLines.filter(Boolean).slice(-2) : [];
    if (!lines.length) return "";
    return `<div class="recent-input"><pre>${lines.map((line) => escapeHTML(line)).join("\n")}</pre></div>`;
  }

  async function createDefaultSession() {
    stopManagerRefresh();
    try {
      setManagerError("");
      const session = await api("/api/sessions", {
        method: "POST",
      });
      location.href = `/terminal/${encodeURIComponent(session.id)}`;
    } catch (err) {
      setManagerError(err.message.trim());
      startManagerRefresh();
    }
  }

  function setManagerError(message) {
    const error = app.querySelector("#managerError");
    if (!error) return;
    error.textContent = message;
    error.hidden = !message;
  }

  function renderTerminal(id) {
    document.title = "Terminal - WebTerm";
    document.body.classList.add("terminal-mode");
    stopManagerRefresh();
    closeManagerWS();
    if (!window.Terminal || !window.FitAddon) {
      renderFatal(new Error("终端组件加载失败，请刷新页面或检查 /vendor/xterm.js 是否可访问"));
      return;
    }
    app.innerHTML = `
      <section class="terminal-page">
        <header class="terminal-bar">
          <a class="button" href="/">返回</a>
          <div class="terminal-title">
            <input id="sessionName" autocomplete="off" maxlength="80" value="" placeholder="Terminal" aria-label="会话名称" title="会话名称，留空时显示终端标题" />
          </div>
          <button id="copySelection" class="selection-copy" type="button" hidden>拷贝</button>
          <button id="selectMode" type="button">选择</button>
        </header>
        <div id="terminal-container">
          <div id="terminal"></div>
        </div>
        <nav class="quickbar">
          ${["Ctrl", "Ctrl C", "Shift Tab", "Esc", "Tab", "/", "←", "↓", "↑", "→"].map((k) => `<button type="button" data-key="${k}">${k}</button>`).join("")}
        </nav>
      </section>`;
    setupTerminal(id);
  }

  function disposeTerminalPage() {
    clearReconnect();
    if (state.ws) {
      state.manualClose = true;
      state.ws.close();
      state.ws = null;
    }
    if (state.terminalDisposables) {
      state.terminalDisposables.dispose();
      state.terminalDisposables = null;
    }
    if (state.terminalView) {
      state.terminalView.dispose();
    }
    state.terminalView = null;
    state.inputController = null;
    state.layoutController = null;
    state.selectionController = null;
    state.term = null;
  }

  function setupTerminal(id) {
    disposeTerminalPage();
    state.terminalDisposables = new DisposableStore();
    state.terminalID = id;
    state.manualClose = false;
    state.lastSeq = Number(sessionStorage.getItem(`webterm:${id}:lastSeq`) || 0);
    state.restored = false;
    state.currentSessionName = "";
    state.currentTermTitle = "";
    state.currentDisplayTitle = "Terminal";
    state.titleBeforeEdit = "";
    state.titleEditing = false;
    state.skipTitleCommit = false;
    state.terminalView = new TerminalView({
      TerminalCtor: window.Terminal,
      FitAddonCtor: window.FitAddon,
      WebglAddonCtor: window.WebglAddon,
      element: document.getElementById("terminal"),
      options: {
        cursorBlink: true,
        fontFamily: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace',
        fontSize: 10,
        convertEol: true,
        scrollback: 20000,
        overviewRuler: { width: 4 },
        theme: termTheme(),
      },
    });
    state.term = state.terminalView.term;
    setupTerminalDebugHooks();
    state.inputController = new TerminalInputController({
      store: state.terminalDisposables,
      root: app,
      terminalElement: document.getElementById("terminal"),
      sendInput,
      focusTerminal: () => state.term?.focus(),
    });
    state.inputController.attach();
    setupMobileIMEBounds();
    setupTerminalFocusBottom();
    setupTerminalTouchScroll();
    state.selectionController = new TerminalSelectionController({
      store: state.terminalDisposables,
      root: app,
      terminalElement: document.getElementById("terminal"),
      terminalView: state.terminalView,
      clearPendingInput: () => state.inputController?.clearPendingModifier(),
    });
    state.selectionController.attach();
    state.layoutController = new TerminalLayoutController({
      store: state.terminalDisposables,
      terminalView: state.terminalView,
      container: document.getElementById("terminal-container"),
      documentElement: document.documentElement,
      sendResizeMessage: (size) => send({ type: "resize", ...size }),
      isVisible: () => !document.hidden,
    });
    state.layoutController.attach();
    state.terminalDisposables.add(state.layoutController);
    state.terminalDisposables.add(state.terminalView.onScroll(() => {
      if (!state.layoutController?.resizingTerminal) {
        state.layoutController.lastScrollAnchor = state.layoutController.captureScrollAnchor();
      }
    }));
    connectWS(id);
    state.terminalDisposables.add(state.terminalView.onData(handleTerminalData));
    state.terminalDisposables.addEventListener(document, "visibilitychange", () => {
      if (!document.hidden) {
        ensureConnected();
        state.terminalDisposables?.addTimeout(setTimeout(() => state.layoutController?.sendResize({ reason: "visibility" }), 150));
      }
    });
    setupTerminalTitleEditor();
  }

  function setupTerminalTitleEditor() {
    const titleInput = document.getElementById("sessionName");
    if (!titleInput) return;

    state.terminalDisposables.addEventListener(titleInput, "focus", () => {
      state.titleEditing = true;
      state.titleBeforeEdit = state.currentSessionName || "";
      titleInput.select();
    });
    state.terminalDisposables.addEventListener(titleInput, "blur", (event) => commitTerminalTitle(event));
    state.terminalDisposables.addEventListener(titleInput, "keydown", (event) => {
      if (event.key === "Enter") {
        event.preventDefault();
        titleInput.blur();
      } else if (event.key === "Escape") {
        event.preventDefault();
        cancelTerminalTitleEdit();
      }
    });
  }

  function setupMobileIMEBounds() {
    const terminal = document.getElementById("terminal");
    if (!terminal) return;

    const clampCompositionView = () => {
      const composition = terminal.querySelector(".composition-view");
      if (!composition) return;
      const terminalRect = terminal.getBoundingClientRect();
      const compositionRect = composition.getBoundingClientRect();
      const available = Math.max(24, terminalRect.right - compositionRect.left - 8);
      composition.style.maxWidth = `${Math.min(available, terminalRect.width - 16)}px`;
    };

    const scheduleClamp = () => requestAnimationFrame(clampCompositionView);
    const bindTextarea = () => {
      const textarea = terminal.querySelector("textarea.xterm-helper-textarea");
      if (!textarea || textarea.dataset.imeBounds === "1") return;
      textarea.dataset.imeBounds = "1";
      state.terminalDisposables.addEventListener(textarea, "compositionstart", scheduleClamp);
      state.terminalDisposables.addEventListener(textarea, "compositionupdate", scheduleClamp);
      state.terminalDisposables.addEventListener(textarea, "input", scheduleClamp);
      state.terminalDisposables.addEventListener(textarea, "focus", scheduleClamp);
    };

    bindTextarea();
    state.terminalDisposables.addTimeout(setTimeout(bindTextarea, 0));
    state.terminalDisposables.addEventListener(window, "resize", scheduleClamp);
    if (state.terminalView) state.terminalDisposables.add(state.terminalView.onRender(scheduleClamp));
  }

  function setupTerminalFocusBottom() {
    const terminal = document.getElementById("terminal");
    if (!terminal) return;
    const scrollInputIntoView = () => {
      if (state.selectionController?.selectionMode) return;
      state.term?.scrollToBottom();
      requestAnimationFrame(() => state.term?.scrollToBottom());
    };
    state.terminalDisposables.addEventListener(terminal, "focusin", (event) => {
      if (event.target?.matches?.("textarea.xterm-helper-textarea")) {
        scrollInputIntoView();
      }
    }, true);
    state.terminalDisposables.addEventListener(terminal, "click", scrollInputIntoView);
  }

  function setupTerminalDebugHooks() {
    if (!debugEnabled()) return;
    window.__webtermDebug = {
      scroll() {
        const buffer = state.term?.buffer?.active;
        const viewport = document.querySelector("#terminal .xterm-viewport");
        return {
          viewportY: buffer?.viewportY ?? null,
          baseY: buffer?.baseY ?? null,
          bufferType: buffer?.type ?? null,
          rows: state.term?.rows ?? null,
          cols: state.term?.cols ?? null,
          scrollTop: viewport?.scrollTop ?? null,
          scrollHeight: viewport?.scrollHeight ?? null,
          clientHeight: viewport?.clientHeight ?? null,
          keyboardOpen: state.layoutController?.keyboardOpen ?? false,
          visualViewport: window.visualViewport ? {
            height: window.visualViewport.height,
            offsetTop: window.visualViewport.offsetTop,
          } : null,
          innerHeight: window.innerHeight,
        };
      },
      scrollToLine(line) {
        state.term?.scrollToLine(Number(line) || 0);
        return this.scroll();
      },
      scrollLines(lines) {
        state.term?.scrollLines(Number(lines) || 0);
        return this.scroll();
      },
      focus() {
        state.term?.focus();
        return this.scroll();
      },
      input(data) {
        sendInput(String(data || ""));
        return this.scroll();
      },
      selectText(text) {
        const needle = String(text || "");
        const buffer = state.term?.buffer?.active;
        if (!needle || !buffer) return "";
        for (let index = 0; index < buffer.length; index += 1) {
          const line = buffer.getLine(index)?.translateToString(true) || "";
          const col = line.indexOf(needle);
          if (col >= 0) {
            state.term.select(col, index, needle.length);
            return state.term.getSelection();
          }
        }
        return "";
      },
      termState() {
        const buffer = state.term?.buffer?.active;
        return {
          cols: state.term?.cols ?? null,
          rows: state.term?.rows ?? null,
          viewportY: buffer?.viewportY ?? null,
          baseY: buffer?.baseY ?? null,
          text: terminalBufferText(),
        };
      },
      wsState() {
        return {
          readyState: state.ws?.readyState ?? null,
          restored: state.restored,
          lastSeq: state.lastSeq,
          manualClose: state.manualClose,
        };
      },
      layoutState() {
        return state.layoutController?.stats?.() || null;
      },
      lifecycleState() {
        return {
          disposables: state.terminalDisposables?.size ?? 0,
          hasTerminalView: Boolean(state.terminalView),
          hasInputController: Boolean(state.inputController),
          hasLayoutController: Boolean(state.layoutController),
          hasSelectionController: Boolean(state.selectionController),
          wsReadyState: state.ws?.readyState ?? null,
        };
      },
      writeQueue() {
        return state.terminalView?.stats?.() || null;
      },
    };
  }

  function debugEnabled() {
    return new URLSearchParams(location.search).has("debug")
      || localStorage.getItem("webtermDebug") === "1";
  }

  function terminalBufferText() {
    const buffer = state.term?.buffer?.active;
    if (!buffer?.length) return "";
    const lines = [];
    for (let index = 0; index < buffer.length; index += 1) {
      lines.push(buffer.getLine(index)?.translateToString(true) || "");
    }
    return lines.join("\n");
  }

  async function commitTerminalTitle(event) {
    if (state.skipTitleCommit) {
      state.skipTitleCommit = false;
      state.titleEditing = false;
      return;
    }
    const restoreFocus = shouldRestoreTerminalFocusAfterTitleEdit(event);
    const titleInput = document.getElementById("sessionName");
    const nextName = (titleInput?.value || "").trim();
    const oldName = state.titleBeforeEdit || "";
    state.titleEditing = false;
    if (nextName === oldName || !state.terminalID) {
      setTerminalInfo({ name: oldName });
      if (restoreFocus) state.term?.focus();
      return;
    }
    setTerminalInfo({ name: nextName });
    try {
      const session = await api(`/api/sessions/${encodeURIComponent(state.terminalID)}`, {
        method: "PATCH",
        body: JSON.stringify({ name: nextName }),
      });
      setTerminalInfo(session);
    } catch (err) {
      setTerminalInfo({ name: oldName });
      alert(err.message.trim() || "修改标题失败");
    } finally {
      if (restoreFocus) state.term?.focus();
    }
  }

  function shouldRestoreTerminalFocusAfterTitleEdit(event) {
    return !state.selectionController?.selectionMode
      && !state.selectionController?.enteringSelectionMode
      && event?.relatedTarget?.id !== "selectMode";
  }

  function cancelTerminalTitleEdit() {
    state.skipTitleCommit = true;
    state.titleEditing = false;
    setTerminalInfo({ name: state.titleBeforeEdit || "" });
    if (!state.selectionController?.selectionMode) state.term?.focus();
  }

  function setTerminalInfo(session = {}) {
    if (Object.prototype.hasOwnProperty.call(session, "name")) {
      state.currentSessionName = String(session.name || "").trim();
    }
    if (Object.prototype.hasOwnProperty.call(session, "termTitle")) {
      state.currentTermTitle = String(session.termTitle || "").trim();
    }
    state.currentDisplayTitle = session.displayTitle || sessionDisplayTitle({
      name: state.currentSessionName,
      termTitle: state.currentTermTitle,
    });
    const titleInput = document.getElementById("sessionName");
    if (titleInput) {
      titleInput.placeholder = state.currentTermTitle || "Terminal";
      if (!state.titleEditing) titleInput.value = state.currentSessionName;
    }
    document.title = `${state.currentDisplayTitle} - WebTerm`;
  }

  function setupTerminalTouchScroll() {
    const terminal = document.getElementById("terminal");
    if (!terminal || !state.term) return;

    let lastY = 0;
    let pendingPixels = 0;
    let active = false;

    state.terminalDisposables.addEventListener(terminal, "touchstart", (event) => {
      if (state.selectionController?.selectionMode || !shouldUseTouchScroll() || event.touches.length !== 1) {
        active = false;
        return;
      }
      active = true;
      lastY = event.touches[0].clientY;
      pendingPixels = 0;
    }, { passive: true });

    state.terminalDisposables.addEventListener(terminal, "touchmove", (event) => {
      if (!active || !state.term || event.touches.length !== 1) return;

      // 无论是否触发滚动行，都第一步无条件阻止浏览器默认原生滚动，以完全阻断事件竞争导致跳变顶部的 Bug
      event.preventDefault();

      const y = event.touches[0].clientY;
      const delta = lastY - y;
      lastY = y;
      if (!delta) return;

      pendingPixels += delta;
      const cellHeight = getTerminalCellHeight();
      const lines = Math.trunc(pendingPixels / cellHeight);
      if (!lines) return;

      const buffer = state.term.buffer.active;
      const canScrollUp = lines < 0 && buffer.viewportY > 0;
      const canScrollDown = lines > 0 && buffer.viewportY < buffer.baseY;
      if (canScrollUp || canScrollDown) {
        state.term.scrollLines(lines);
        pendingPixels -= lines * cellHeight;
      }
    }, { passive: false });

    state.terminalDisposables.addEventListener(terminal, "touchend", () => {
      active = false;
      pendingPixels = 0;
    }, { passive: true });
    state.terminalDisposables.addEventListener(terminal, "touchcancel", () => {
      active = false;
      pendingPixels = 0;
    }, { passive: true });
  }

  function getTerminalCellHeight() {
    return state.term?._core?._renderService?.dimensions?.css?.cell?.height
      || Math.max(10, Number(state.term?.options?.fontSize || 10) * 1.4);
  }

  function shouldUseTouchScroll() {
    return window.matchMedia("(pointer: coarse), (max-width: 720px)").matches;
  }

  function connectWS(id) {
    if (state.ws && (state.ws.readyState === WebSocket.OPEN || state.ws.readyState === WebSocket.CONNECTING)) {
      return;
    }
    const proto = location.protocol === "https:" ? "wss" : "ws";
    state.manualClose = false;
    state.ws = new WebSocket(`${proto}://${location.host}/ws/sessions/${encodeURIComponent(id)}`);
    state.terminalDisposables.addEventListener(state.ws, "open", () => {
      state.reconnectAttempts = 0;
      clearReconnect();
      send({ type: "hello", lastSeq: state.restored ? state.lastSeq : 0 });
      state.layoutController?.sendResize({ reason: "ws-open" });
    });
    state.terminalDisposables.addEventListener(state.ws, "message", (event) => {
      let msg;
      try {
        msg = JSON.parse(event.data);
      } catch {
        return;
      }
      if (msg.type === "state") {
        if (!state.terminalView) return;
        state.terminalView.reset();
        if (msg.data) state.terminalView.enqueueWrite(msg.data);
        state.restored = true;
        setLastSeq(msg.seq);
      } else if (msg.type === "replay") {
        if (!state.terminalView) return;
        if (!state.restored || msg.from === 0) {
          state.terminalView.reset();
        }
        for (const frame of msg.frames || []) {
          state.terminalView.enqueueWrite(frame.data || "");
          rememberSeq(frame.seq);
        }
        state.restored = true;
        rememberSeq(msg.seq);
      } else if (msg.type === "output") {
        if (!state.terminalView) return;
        state.terminalView.enqueueWrite(msg.data);
        rememberSeq(msg.seq);
      } else if (msg.type === "info") {
        setTerminalInfo(msg.data);
      } else if (msg.type === "exit") {
        state.manualClose = true;
      }
    });
    state.terminalDisposables.addEventListener(state.ws, "close", () => {
      if (!state.manualClose) scheduleReconnect();
    });
  }

  function scheduleReconnect() {
    clearReconnect();
    const delay = Math.min(1000 * Math.pow(1.6, state.reconnectAttempts++), 8000);
    state.reconnectTimer = setTimeout(() => {
      state.ws = null;
      ensureConnected();
    }, delay);
  }

  function clearReconnect() {
    if (state.reconnectTimer) {
      clearTimeout(state.reconnectTimer);
      state.reconnectTimer = null;
    }
  }

  function ensureConnected() {
    if (!state.terminalID || state.manualClose) return;
    if (!state.ws || state.ws.readyState === WebSocket.CLOSED || state.ws.readyState === WebSocket.CLOSING) {
      connectWS(state.terminalID);
    }
  }

  function send(msg) {
    ensureConnected();
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
      state.ws.send(JSON.stringify(msg));
    }
  }

  function sendInput(data) {
    state.term?.scrollToBottom();
    send({ type: "input", data });
  }

  function handleTerminalData(data) {
    if (!state.inputController?.pendingModifier) {
      sendInput(data);
      return;
    }
    if (!state.inputController.sendModifiedInput(data)) sendInput(data);
  }

  function rememberSeq(seq) {
    const value = Number(seq || 0);
    if (!value || value < state.lastSeq) return;
    setLastSeq(value);
  }

  function setLastSeq(seq) {
    const value = Number(seq || 0);
    state.lastSeq = value;
    if (state.terminalID) {
      sessionStorage.setItem(`webterm:${state.terminalID}:lastSeq`, String(value));
    }
  }

  function toggleTheme() {
    state.theme = currentTheme().next;
    localStorage.setItem("webterm-theme", state.theme);
    document.documentElement.dataset.theme = state.theme;
    const btn = document.getElementById("theme");
    if (btn) btn.textContent = themeButtonLabel();
    applyTermTheme();
  }

  function applyTermTheme() {
    if (state.term) state.term.options.theme = termTheme();
  }

  function termTheme() {
    return currentTheme().terminal;
  }

  function currentTheme() {
    return THEMES[state.theme] || THEMES.solarized;
  }

  function themeButtonLabel() {
    return currentTheme().next === "dracula" ? "Dracula" : "Solarized";
  }

  function sessionDisplayTitle(session = {}) {
    if (session.displayTitle) return String(session.displayTitle);
    const name = String(session.name || "").trim();
    const termTitle = String(session.termTitle || "").trim() || "Terminal";
    return name ? `${name} - ${termTitle}` : termTitle;
  }

  function escapeHTML(value) {
    return String(value || "").replace(/[&<>"']/g, (c) => ({
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#39;",
    }[c]));
  }

  boot().catch((err) => {
    app.innerHTML = `<pre class="fatal">${escapeHTML(err.message)}</pre>`;
  });
