import assert from "node:assert/strict";
import test from "node:test";
import { TerminalLayoutController, keyboardOffsetFor } from "./terminal-layout.js";

test("keyboardOffsetFor clamps negative offsets", () => {
  assert.equal(keyboardOffsetFor({ innerHeight: 800, viewportHeight: 500, viewportOffsetTop: 0 }), 300);
  assert.equal(keyboardOffsetFor({ innerHeight: 800, viewportHeight: 700, viewportOffsetTop: 20 }), 80);
  assert.equal(keyboardOffsetFor({ innerHeight: 600, viewportHeight: 800, viewportOffsetTop: 0 }), 0);
});

test("TerminalLayoutController keeps bottom pinned during resize when already near bottom", () => {
  const terminal = fakeTerminal({ viewportY: 99, baseY: 100, rows: 10 });
  terminal.cols = 80;
  terminal.fit = () => {
    terminal.fitCount = (terminal.fitCount || 0) + 1;
    terminal.rows = 24;
  };
  const sent = [];
  const controller = new TerminalLayoutController({
    store: lazyStore(),
    terminalView: terminal,
    container: null,
    documentElement: { style: { setProperty() {} } },
    windowObject: {
      innerHeight: 800,
      visualViewport: { height: 800, offsetTop: 0 },
      requestAnimationFrame: (callback) => callback(),
    },
    sendResizeMessage: (size) => sent.push(size),
    isVisible: () => true,
  });

  controller.sendResize({ reason: "viewport" });

  assert.equal(terminal.fitCount, 1);
  assert.equal(terminal.scrolledToBottom, true);
  assert.deepEqual(sent, [{ cols: 80, rows: 24, visible: true }]);
});

test("TerminalLayoutController preserves history scroll during resize", () => {
  const terminal = fakeTerminal({ viewportY: 0, baseY: 100, rows: 20 });
  terminal.cols = 80;
  terminal.fit = () => {
    terminal.fitCount = (terminal.fitCount || 0) + 1;
  };
  const sent = [];
  const controller = new TerminalLayoutController({
    store: lazyStore(),
    terminalView: terminal,
    container: null,
    documentElement: { style: { setProperty() {} } },
    windowObject: {
      innerHeight: 800,
      visualViewport: { height: 500, offsetTop: 0 },
      requestAnimationFrame: (callback) => callback(),
    },
    sendResizeMessage: (size) => sent.push(size),
    isVisible: () => true,
  });
  controller.sendResize({ reason: "viewport", beforeFit: () => {} });

  assert.equal(terminal.scrolledToBottom, false);
  assert.equal(terminal.fitCount, 1);
  assert.equal(terminal.refreshCount, 1);
  assert.deepEqual(sent, [{ cols: 80, rows: 20, visible: true }]);
});

test("TerminalLayoutController can force bottom pin during resize", () => {
  const terminal = fakeTerminal({ viewportY: 0, baseY: 100, rows: 20 });
  terminal.cols = 80;
  const controller = new TerminalLayoutController({
    store: lazyStore(),
    terminalView: terminal,
    container: null,
    documentElement: { style: { setProperty() {} } },
    windowObject: {
      innerHeight: 800,
      visualViewport: { height: 500, offsetTop: 0 },
      requestAnimationFrame: (callback) => callback(),
    },
    sendResizeMessage: () => {},
    isVisible: () => true,
  });

  controller.sendResize({ reason: "viewport", pinBottom: true });

  assert.equal(terminal.scrolledToBottom, true);
});

test("TerminalLayoutController keeps checking bottom pin after grow resize until stable", () => {
  let now = 0;
  const terminal = fakeTerminal({ viewportY: 100, baseY: 100, rows: 20 });
  terminal.cols = 80;
  terminal.fit = () => {
    terminal.fitCount = (terminal.fitCount || 0) + 1;
    terminal.rows = 30;
    terminal.buffer.active.viewportY = 98;
  };
  const controller = new TerminalLayoutController({
    store: lazyStore(),
    terminalView: terminal,
    container: null,
    documentElement: { style: { setProperty() {} } },
    windowObject: {
      innerHeight: 900,
      visualViewport: { height: 900, offsetTop: 0 },
      performance: { now: () => now },
      requestAnimationFrame: (callback) => callback(),
    },
    sendResizeMessage: () => {},
    isVisible: () => true,
  });

  controller.sendResize({ reason: "viewport" });
  assert.equal(terminal.scrolledToBottom, true);
  assert.equal(controller.stats().bottomPinActive, true);

  now += 16;
  controller.handleTerminalRender();
  assert.equal(controller.stats().bottomPinActive, true);

  now += 16;
  controller.handleTerminalRender();
  assert.equal(controller.stats().bottomPinActive, false);
});

test("TerminalLayoutController pins the DOM viewport to bottom during resize", () => {
  const viewport = { scrollTop: 700, scrollHeight: 1000, clientHeight: 300 };
  const terminal = fakeTerminal({ viewportY: 0, baseY: 100, rows: 20 });
  terminal.cols = 80;
  const controller = new TerminalLayoutController({
    store: lazyStore(),
    terminalView: terminal,
    container: { querySelector: (selector) => selector === ".xterm-viewport" ? viewport : null },
    documentElement: { style: { setProperty() {} } },
    windowObject: {
      innerHeight: 800,
      visualViewport: { height: 800, offsetTop: 0 },
      requestAnimationFrame: (callback) => callback(),
    },
    sendResizeMessage: () => {},
    isVisible: () => true,
  });

  controller.sendResize({ reason: "viewport" });

  assert.equal(terminal.scrolledToBottom, true);
  assert.equal(viewport.scrollTop, 1000);
});

test("TerminalLayoutController settles renderer after large writes", () => {
  const terminal = fakeTerminal({ viewportY: 0, baseY: 100, rows: 20 });
  terminal.cols = 80;
  const controller = new TerminalLayoutController({
    store: lazyStore(),
    terminalView: terminal,
    container: null,
    documentElement: { style: { setProperty() {} } },
    windowObject: {
      innerHeight: 800,
      visualViewport: { height: 800, offsetTop: 0 },
      requestAnimationFrame: (callback) => callback(),
    },
    sendResizeMessage: () => {},
    isVisible: () => true,
  });

  controller.settleAfterWrite();

  assert.equal(terminal.scrolledToBottom, true);
  assert.equal(terminal.refreshCount > 0, true);
});

test("TerminalLayoutController updates viewport height on window resize when visualViewport exists", async () => {
  const terminal = fakeTerminal({ viewportY: 10, baseY: 100, rows: 20 });
  terminal.cols = 80;
  const styles = new Map();
  const listeners = [];
  const windowObject = {
    innerHeight: 400,
    visualViewport: { height: 400, offsetTop: 0 },
    ResizeObserver: class {
      observe() {}
      disconnect() {}
    },
    requestAnimationFrame: (callback) => callback(),
  };
  const controller = new TerminalLayoutController({
    store: {
      add() {},
      addEventListener(target, type, listener) {
        listeners.push({ target, type, listener });
      },
      addTimeout() {},
      setTimeout() {},
    },
    terminalView: terminal,
    container: {},
    documentElement: { style: { setProperty: (key, value) => styles.set(key, value) } },
    windowObject,
    sendResizeMessage: () => {},
    isVisible: () => true,
  });

  controller.attach();
  assert.equal(styles.get("--viewport-height"), "400px");
  assert.equal(styles.get("--keyboard-offset"), "0px");

  windowObject.innerHeight = 800;
  windowObject.visualViewport.height = 800;
  listeners.find(({ target, type }) => target === windowObject && type === "resize").listener();
  await new Promise((resolve) => setTimeout(resolve, 25));

  assert.equal(styles.get("--viewport-height"), "800px");
  assert.equal(styles.get("--keyboard-offset"), "0px");
  assert.equal(terminal.fitCount, 1);
});

test("TerminalLayoutController updates keyboard offset when visualViewport shrinks", () => {
  const terminal = fakeTerminal({ viewportY: 10, baseY: 100, rows: 20 });
  terminal.cols = 80;
  const styles = new Map();
  const controller = new TerminalLayoutController({
    store: lazyStore(),
    terminalView: terminal,
    container: null,
    documentElement: { style: { setProperty: (key, value) => styles.set(key, value) } },
    windowObject: {
      innerHeight: 800,
      visualViewport: { height: 500, offsetTop: 0 },
      requestAnimationFrame: (callback) => callback(),
    },
    sendResizeMessage: () => {},
    isVisible: () => true,
  });

  controller.updateViewportMetrics();

  assert.equal(styles.get("--viewport-height"), "500px");
  assert.equal(styles.get("--keyboard-offset"), "300px");
});

function fakeTerminal({ viewportY, baseY, rows }) {
  return {
    rows,
    scrolledToBottom: false,
    buffer: {
      active: { viewportY, baseY },
    },
    fit() {
      this.fitCount = (this.fitCount || 0) + 1;
    },
    scrollToBottom() {
      this.scrolledToBottom = true;
      this.buffer.active.viewportY = this.buffer.active.baseY;
    },
    refreshAll() {
      this.refreshCount = (this.refreshCount || 0) + 1;
    },
  };
}

function lazyStore() {
  return {
    addTimeout() {},
    setTimeout() {
      return { dispose() {} };
    },
  };
}
