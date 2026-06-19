import assert from "node:assert/strict";
import test from "node:test";
import { DisposableStore } from "./disposable.js";
import { TerminalInputController } from "./terminal-input-controller.js";

test("TerminalInputController sends quickbar keys through one input path", () => {
  const { controller, sent, focused } = createController();

  controller.sendKey("Ctrl C");
  controller.sendKey("Tab");

  assert.deepEqual(sent, [
    { data: "\x03", source: "quickbar" },
    { data: "\t", source: "quickbar" },
  ]);
  assert.equal(focused.count, 2);
});

test("TerminalInputController consumes pending ctrl modifier exactly once", () => {
  const { controller, sent } = createController();

  controller.sendKey("Ctrl");
  assert.equal(controller.pendingModifier, "ctrl");
  assert.equal(controller.sendModifiedInput("c"), true);
  assert.equal(controller.pendingModifier, null);
  assert.equal(controller.sendModifiedInput("d"), false);

  assert.deepEqual(sent, [{ data: "\x03", source: "modifier" }]);
});

function createController() {
  const root = new EventTarget();
  root.querySelectorAll = () => [];
  const terminalElement = new EventTarget();
  terminalElement.querySelector = () => null;
  const sent = [];
  const focused = { count: 0 };
  const controller = new TerminalInputController({
    store: new DisposableStore(),
    root,
    terminalElement,
    sendInput: (data, source) => sent.push({ data, source }),
    focusTerminal: () => {
      focused.count += 1;
    },
  });
  return { controller, sent, focused };
}
