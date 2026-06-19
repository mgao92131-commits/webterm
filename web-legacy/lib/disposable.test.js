import assert from "node:assert/strict";
import test from "node:test";
import { DisposableStore } from "./disposable.js";

test("DisposableStore disposes entries once in reverse order", () => {
  const calls = [];
  const store = new DisposableStore();

  store.add({ dispose: () => calls.push("first") });
  store.add(() => calls.push("second"));
  store.dispose();
  store.dispose();

  assert.deepEqual(calls, ["second", "first"]);
});

test("DisposableStore disposes entries added after disposal immediately", () => {
  const calls = [];
  const store = new DisposableStore();

  store.dispose();
  store.add(() => calls.push("late"));

  assert.deepEqual(calls, ["late"]);
});

test("DisposableStore addEventListener removes listener", () => {
  const calls = [];
  const target = new EventTarget();
  const store = new DisposableStore();

  store.addEventListener(target, "ping", () => calls.push("ping"));
  target.dispatchEvent(new Event("ping"));
  store.dispose();
  target.dispatchEvent(new Event("ping"));

  assert.deepEqual(calls, ["ping"]);
});
