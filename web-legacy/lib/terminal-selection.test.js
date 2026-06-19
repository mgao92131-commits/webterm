import assert from "node:assert/strict";
import test from "node:test";
import { TerminalSelectionController, selectTerminalRange, terminalCellFromEvent } from "./terminal-selection.js";

test("selectTerminalRange supports forward and reverse ranges", () => {
  const terminal = fakeTerminal();

  selectTerminalRange(terminal, { col: 2, row: 1 }, { col: 5, row: 1 });
  assert.deepEqual(terminal.selected, { col: 2, row: 1, length: 4 });

  selectTerminalRange(terminal, { col: 5, row: 2 }, { col: 1, row: 1 });
  assert.deepEqual(terminal.selected, { col: 1, row: 1, length: 15 });
});

test("terminalCellFromEvent maps pointer coordinates to buffer cells", () => {
  const terminalElement = {
    querySelector: () => ({
      getBoundingClientRect: () => ({ left: 10, top: 20 }),
    }),
  };
  const terminal = fakeTerminal();

  const cell = terminalCellFromEvent({
    event: { clientX: 31, clientY: 51 },
    terminalElement,
    terminalView: terminal,
  });

  assert.deepEqual(cell, { col: 3, row: 7 });
});

test("TerminalSelectionController keeps selection mode active for empty copy", async () => {
  const copyButton = { textContent: "拷贝", hidden: false };
  const controller = new TerminalSelectionController({
    store: { addTimeout() {}, addEventListener() {} },
    root: {
      querySelector: (selector) => selector === "#copySelection" ? copyButton : null,
    },
    terminalElement: { querySelector: () => null },
    terminalView: {
      getSelection: () => "",
      buffer: { active: { viewportY: 0 } },
      clearSelection() {},
    },
  });
  controller.selectionMode = true;

  await controller.copySelection({ preventDefault() {} });

  assert.equal(controller.selectionMode, true);
  assert.equal(copyButton.textContent, "无选区");
});

function fakeTerminal() {
  return {
    cols: 10,
    rows: 5,
    options: { fontSize: 10 },
    term: {
      _core: {
        _renderService: {
          dimensions: {
            css: {
              cell: { width: 7, height: 10 },
            },
          },
        },
      },
    },
    buffer: {
      active: { viewportY: 4 },
    },
    select(col, row, length) {
      this.selected = { col, row, length };
    },
  };
}
