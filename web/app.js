(function () {
  const app = document.getElementById("app");
  const state = {
    user: null,
    sessions: [],
    theme: localStorage.getItem("webterm-theme") || "dark",
    ws: null,
    term: null,
    fit: null,
    managerTimer: null,
    terminalID: null,
    reconnectTimer: null,
    reconnectAttempts: 0,
    manualClose: false,
    lastSeq: 0,
    restored: false,
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
    document.body.classList.remove("terminal-mode");
    clearReconnect();
    if (state.ws) {
      state.manualClose = true;
      state.ws.close();
      state.ws = null;
    }
    await refreshSessions();
    app.innerHTML = `
      <section class="manager">
        <header class="topbar">
          <div>
            <h1>WebTerm</h1>
            <span>${escapeHTML(state.user.username)}</span>
          </div>
          <div class="actions">
            <button id="theme">${state.theme === "dark" ? "浅色" : "深色"}</button>
            <button id="new">新建终端</button>
          </div>
        </header>
        <p class="error" id="managerError" hidden></p>
        <section class="session-list"></section>
        <dialog id="sessionDialog" class="dialog">
          <form method="dialog">
            <h2 id="dialogTitle">新建终端</h2>
            <input type="hidden" name="id" />
            <label>名称<input name="name" autocomplete="off" /></label>
            <label class="cwd-field">工作目录<input name="cwd" autocomplete="off" placeholder="留空使用服务目录" /></label>
            <div class="dialog-actions">
              <button value="cancel">取消</button>
              <button id="dialogSubmit" value="ok">确定</button>
            </div>
          </form>
        </dialog>
      </section>`;
    app.querySelector("#new").addEventListener("click", openCreateDialog);
    app.querySelector("#sessionDialog").addEventListener("close", handleDialogClose);
    app.querySelector("#theme").addEventListener("click", toggleTheme);
    drawSessionList();
    startManagerRefresh();
  }

  function startManagerRefresh() {
    stopManagerRefresh();
    state.managerTimer = setInterval(async () => {
      if (location.pathname !== "/") {
        stopManagerRefresh();
        return;
      }
      const dialog = app.querySelector("#sessionDialog");
      if (dialog && dialog.open) return;
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

  function drawSessionList() {
    const list = app.querySelector(".session-list");
    if (!state.sessions.length) {
      list.innerHTML = `<div class="empty">还没有终端</div>`;
      return;
    }
    list.innerHTML = state.sessions.map((s) => `
      <article class="session-card">
        <div>
          <h2>${escapeHTML(s.name)}</h2>
          <p>${escapeHTML(s.cwd)}</p>
          <small>${s.clients} 个连接 · ${formatDate(s.lastActiveAt)}</small>
        </div>
        <div class="card-actions">
          <a class="button" href="/terminal/${encodeURIComponent(s.id)}">打开</a>
          <button data-rename="${escapeHTML(s.id)}">重命名</button>
          <button class="danger" data-close="${escapeHTML(s.id)}">关闭</button>
        </div>
      </article>`).join("");
    list.querySelectorAll("[data-close]").forEach((btn) => {
      btn.addEventListener("click", async () => {
        await api(`/api/sessions/${btn.dataset.close}`, { method: "DELETE" });
        await renderManager();
      });
    });
    list.querySelectorAll("[data-rename]").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const current = state.sessions.find((s) => s.id === btn.dataset.rename);
        openRenameDialog(current);
      });
    });
  }

  function openCreateDialog() {
    stopManagerRefresh();
    const dialog = app.querySelector("#sessionDialog");
    dialog.dataset.mode = "create";
    dialog.querySelector("#dialogTitle").textContent = "新建终端";
    dialog.querySelector("[name=id]").value = "";
    dialog.querySelector("[name=name]").value = "";
    dialog.querySelector("[name=cwd]").value = "";
    dialog.querySelector(".cwd-field").hidden = false;
    dialog.showModal();
    dialog.querySelector("[name=name]").focus();
  }

  function openRenameDialog(session) {
    if (!session) return;
    stopManagerRefresh();
    const dialog = app.querySelector("#sessionDialog");
    dialog.dataset.mode = "rename";
    dialog.querySelector("#dialogTitle").textContent = "重命名终端";
    dialog.querySelector("[name=id]").value = session.id;
    dialog.querySelector("[name=name]").value = session.name;
    dialog.querySelector("[name=cwd]").value = "";
    dialog.querySelector(".cwd-field").hidden = true;
    dialog.showModal();
    dialog.querySelector("[name=name]").focus();
  }

  async function handleDialogClose(event) {
    const dialog = event.currentTarget;
    if (dialog.returnValue !== "ok") {
      startManagerRefresh();
      return;
    }
    const form = new FormData(dialog.querySelector("form"));
    const mode = dialog.dataset.mode;
    try {
      setManagerError("");
      if (mode === "create") {
        const s = await api("/api/sessions", {
          method: "POST",
          body: JSON.stringify({
            name: String(form.get("name") || ""),
            cwd: String(form.get("cwd") || ""),
          }),
        });
        location.href = `/terminal/${encodeURIComponent(s.id)}`;
      } else if (mode === "rename") {
        await api(`/api/sessions/${encodeURIComponent(form.get("id"))}`, {
          method: "PATCH",
          body: JSON.stringify({ name: String(form.get("name") || "") }),
        });
        await renderManager();
      }
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
    document.body.classList.add("terminal-mode");
    if (typeof Terminal === "undefined" || typeof FitAddon === "undefined") {
      renderFatal(new Error("终端组件加载失败，请刷新页面或检查 /vendor/xterm.js 是否可访问"));
      return;
    }
    app.innerHTML = `
      <section class="terminal-page">
        <header class="terminal-bar">
          <a class="button" href="/">返回</a>
          <div>
            <strong id="sessionName">Terminal</strong>
            <span id="conn">连接中</span>
          </div>
          <button id="theme">${state.theme === "dark" ? "浅色" : "深色"}</button>
        </header>
        <div id="terminal"></div>
        <nav class="quickbar">
          ${["Esc", "Tab", "Ctrl+C", "Ctrl+D", "↑", "↓", "←", "→"].map((k) => `<button data-key="${k}">${k}</button>`).join("")}
        </nav>
      </section>`;
    app.querySelector("#theme").addEventListener("click", () => {
      toggleTheme();
      applyTermTheme();
    });
    setupTerminal(id);
  }

  function setupTerminal(id) {
    state.terminalID = id;
    state.manualClose = false;
    state.lastSeq = Number(sessionStorage.getItem(`webterm:${id}:lastSeq`) || 0);
    state.restored = false;
    state.term = new Terminal({
      cursorBlink: true,
      fontFamily: "Consolas, Menlo, Monaco, monospace",
      fontSize: 10,
      convertEol: true,
      scrollback: 20000,
      theme: termTheme(),
    });
    state.fit = new FitAddon.FitAddon();
    state.term.loadAddon(state.fit);
    state.term.open(document.getElementById("terminal"));
    setupTerminalTouchScroll();
    state.fit.fit();
    connectWS(id);
    state.term.onData((data) => sendInput(data));
    window.addEventListener("resize", debounce(() => {
      sendResize();
    }, 120));
    document.addEventListener("visibilitychange", () => {
      if (!document.hidden) {
        ensureConnected();
        setTimeout(sendResize, 150);
      }
    });
    app.querySelectorAll("[data-key]").forEach((btn) => {
      btn.addEventListener("click", () => sendKey(btn.dataset.key));
    });
    setTimeout(sendResize, 100);
  }

  function setupTerminalTouchScroll() {
    const terminal = document.getElementById("terminal");
    if (!terminal || !state.term) return;

    let lastY = 0;
    let pendingPixels = 0;
    let active = false;

    terminal.addEventListener("touchstart", (event) => {
      if (!shouldUseTouchScroll() || event.touches.length !== 1) {
        active = false;
        return;
      }
      active = true;
      lastY = event.touches[0].clientY;
      pendingPixels = 0;
    }, { passive: true });

    terminal.addEventListener("touchmove", (event) => {
      if (!active || !state.term || event.touches.length !== 1) return;
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
        event.preventDefault();
      }
    }, { passive: false });

    terminal.addEventListener("touchend", () => {
      active = false;
      pendingPixels = 0;
    }, { passive: true });
    terminal.addEventListener("touchcancel", () => {
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
    state.ws.addEventListener("open", () => {
      state.reconnectAttempts = 0;
      clearReconnect();
      document.getElementById("conn").textContent = "已连接";
      send({ type: "hello", lastSeq: state.lastSeq });
      sendResize();
    });
    state.ws.addEventListener("message", (event) => {
      const msg = JSON.parse(event.data);
      if (msg.type === "state") {
        state.term.reset();
        if (msg.data) state.term.write(msg.data);
        state.restored = true;
        rememberSeq(msg.seq);
      } else if (msg.type === "replay") {
        if (!state.restored || msg.from === 0) {
          state.term.reset();
        }
        for (const frame of msg.frames || []) {
          state.term.write(frame.data || "");
          rememberSeq(frame.seq);
        }
        state.restored = true;
        rememberSeq(msg.seq);
      } else if (msg.type === "snapshot") {
        state.term.reset();
        if (msg.data) state.term.write(msg.data);
      } else if (msg.type === "output") {
        state.term.write(msg.data);
        rememberSeq(msg.seq);
      } else if (msg.type === "info") {
        document.getElementById("sessionName").textContent = msg.data.name;
        document.getElementById("conn").textContent = `${msg.data.clients} 个连接`;
      } else if (msg.type === "exit") {
        state.manualClose = true;
        document.getElementById("conn").textContent = "已关闭";
      }
    });
    state.ws.addEventListener("close", () => {
      document.getElementById("conn").textContent = "已断开，正在重连";
      if (!state.manualClose) scheduleReconnect();
    });
    state.ws.addEventListener("error", () => {
      document.getElementById("conn").textContent = "连接异常";
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
    send({ type: "input", data });
  }

  function sendResize() {
    if (!state.fit || !state.term) return;
    requestAnimationFrame(() => {
      state.fit.fit();
      send({ type: "resize", cols: state.term.cols, rows: state.term.rows, visible: !document.hidden });
    });
  }

  function rememberSeq(seq) {
    const value = Number(seq || 0);
    if (!value || value < state.lastSeq) return;
    state.lastSeq = value;
    if (state.terminalID) {
      sessionStorage.setItem(`webterm:${state.terminalID}:lastSeq`, String(value));
    }
  }

  function sendKey(key) {
    const map = {
      Esc: "\x1b",
      Tab: "\t",
      "Ctrl+C": "\x03",
      "Ctrl+D": "\x04",
      "↑": "\x1b[A",
      "↓": "\x1b[B",
      "←": "\x1b[D",
      "→": "\x1b[C",
    };
    sendInput(map[key] || "");
    state.term.focus();
  }

  function toggleTheme() {
    state.theme = state.theme === "dark" ? "light" : "dark";
    localStorage.setItem("webterm-theme", state.theme);
    document.documentElement.dataset.theme = state.theme;
    const btn = document.getElementById("theme");
    if (btn) btn.textContent = state.theme === "dark" ? "浅色" : "深色";
  }

  function applyTermTheme() {
    if (state.term) state.term.options.theme = termTheme();
  }

  function termTheme() {
    return state.theme === "dark"
      ? { background: "#101214", foreground: "#e6edf3", cursor: "#f6c177" }
      : { background: "#fbfbfa", foreground: "#1f2328", cursor: "#0969da" };
  }

  function debounce(fn, wait) {
    let timer;
    return () => {
      clearTimeout(timer);
      timer = setTimeout(fn, wait);
    };
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

  function formatDate(value) {
    return new Date(value).toLocaleString();
  }

  boot().catch((err) => {
    app.innerHTML = `<pre class="fatal">${escapeHTML(err.message)}</pre>`;
  });
})();
