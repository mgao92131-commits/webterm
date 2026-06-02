import assert from "node:assert/strict";
import test from "node:test";
import { keyEventData, modifiedInput, quickbarInput } from "./terminal-input.js";

test("modifiedInput maps ctrl letters to control characters", () => {
  assert.equal(modifiedInput("ctrl", "a"), "\x01");
  assert.equal(modifiedInput("ctrl", "C"), "\x03");
  assert.equal(modifiedInput("ctrl", "z"), "\x1a");
});

test("modifiedInput maps ctrl number combinations", () => {
  assert.equal(modifiedInput("ctrl", "2"), "\x00");
  assert.equal(modifiedInput("ctrl", "3"), "\x1b");
  assert.equal(modifiedInput("ctrl", "8"), "\x7f");
});

test("modifiedInput maps alt to escape-prefixed input", () => {
  assert.equal(modifiedInput("alt", "x"), "\x1bx");
});

test("quickbarInput maps terminal shortcut keys", () => {
  assert.equal(quickbarInput(null, "Ctrl C"), "\x03");
  assert.equal(quickbarInput(null, "Shift Tab"), "\x1b[Z");
  assert.equal(quickbarInput(null, "↑"), "\x1b[A");
  assert.equal(quickbarInput(null, "↓"), "\x1b[B");
  assert.equal(quickbarInput(null, "←"), "\x1b[D");
  assert.equal(quickbarInput(null, "→"), "\x1b[C");
});

test("quickbarInput consumes pending ctrl modifier", () => {
  assert.equal(quickbarInput("ctrl", "/"), "/");
  assert.equal(quickbarInput("ctrl", "Tab"), "\t");
});

test("keyEventData normalizes named keys", () => {
  assert.equal(keyEventData({ key: "x" }), "x");
  assert.equal(keyEventData({ key: "Enter" }), "\r");
  assert.equal(keyEventData({ key: "Tab" }), "\t");
  assert.equal(keyEventData({ key: "Escape" }), "\x1b");
});
