import assert from "node:assert/strict";
import test from "node:test";
import { TerminalLayoutController, captureScrollAnchor, keyboardOffsetFor, restoreScrollAnchor } from "./terminal-layout.js";

test("keyboardOffsetFor clamps negative offsets", () => {
  assert.equal(keyboardOffsetFor({ innerHeight: 800, viewportHeight: 600, viewportOffsetTop: 0 }), 200);
  assert.equal(keyboardOffsetFor({ innerHeight: 600, viewportHeight: 800, viewportOffsetTop: 0 }), 0);
  assert.equal(keyboardOffsetFor({ innerHeight: 800, viewportHeight: 700, viewportOffsetTop: 20 }), 80);
});

test("captureScrollAnchor records viewport center and bottom state", () => {
  assert.deepEqual(captureScrollAnchor(fakeTerminal({ viewportY: 10, baseY: 20, rows: 6 })), {
    viewportY: 10,
    centerY: 13,
    baseY: 20,
    rows: 6,
    atBottom: false,
  });
  assert.equal(captureScrollAnchor(fakeTerminal({ viewportY: 20, baseY: 20, rows: 6 })).atBottom, true);
});

test("restoreScrollAnchor keeps bottom at bottom", () => {
  const terminal = fakeTerminal({ viewportY: 20, baseY: 20, rows: 6 });
  restoreScrollAnchor(terminal, { atBottom: true });
  assert.equal(terminal.scrolledToBottom, true);
});

test("restoreScrollAnchor preserves the top visible line", () => {
  const terminal = fakeTerminal({ viewportY: 10, baseY: 100, rows: 20 });
  restoreScrollAnchor(terminal, { centerY: 50, viewportY: 40, rows: 10, atBottom: false });
  assert.equal(terminal.scrolledToLine, 40);
});

test("restoreScrollAnchor falls back to approximate center for older anchors", () => {
  const terminal = fakeTerminal({ viewportY: 10, baseY: 100, rows: 20 });
  restoreScrollAnchor(terminal, { centerY: 50, rows: 10, atBottom: false });
  assert.equal(terminal.scrolledToLine, 40);
});

test("TerminalLayoutController preserves scroll position during resize", () => {
  const terminal = fakeTerminal({ viewportY: 42, baseY: 100, rows: 10 });
  terminal.cols = 80;
  terminal.fit = () => {
    terminal.fitCount = (terminal.fitCount || 0) + 1;
    terminal.rows = 24;
  };
  const sent = [];
  const controller = new TerminalLayoutController({
    store: { addTimeout() {}, setTimeout(cb) { cb(); } },
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
  controller.keyboardOpen = false;

  controller.sendResize({ reason: "viewport" });

  assert.equal(terminal.fitCount, 1);
  assert.equal(terminal.scrolledToLine, 42);
  assert.deepEqual(sent, [{ cols: 80, rows: 24, visible: true }]);
});

test("TerminalLayoutController scrolls to bottom when the keyboard opens", () => {
  const terminal = fakeTerminal({ viewportY: 0, baseY: 100, rows: 20 });
  terminal.cols = 80;
  terminal.fit = () => {
    terminal.fitCount = (terminal.fitCount || 0) + 1;
  };
  const sent = [];
  const controller = new TerminalLayoutController({
    store: { addTimeout() {}, setTimeout(cb) { cb(); } },
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
  controller.keyboardOpen = false;

  controller.sendResize({ reason: "viewport" });

  assert.equal(controller.keyboardOpen, true);
  assert.equal(terminal.scrolledToBottom, true);
  assert.equal(terminal.fitCount, 1);
  assert.deepEqual(sent, [{ cols: 80, rows: 20, visible: true }]);
});

function fakeTerminal({ viewportY, baseY, rows }) {
  return {
    rows,
    buffer: {
      active: { viewportY, baseY },
    },
    scrollToBottom() {
      this.scrolledToBottom = true;
    },
    scrollToLine(line) {
      this.scrolledToLine = line;
    },
  };
}
