import test from 'node:test';
import assert from 'node:assert/strict';
import { SessionManager } from './session-manager.js';

test('manager websocket receives initial sessions list', () => {
  const manager = new SessionManager();
  const ws = fakeWebSocket();
  manager.attachManager(ws);
  assert.deepEqual(ws.sent, [{ type: 'sessions', data: [] }]);
});

test('manager websocket receives session updates and close notices', () => {
  const manager = new SessionManager();
  const ws = fakeWebSocket();
  manager.attachManager(ws);
  manager.broadcastManager({ type: 'session', data: { id: 's1', termTitle: 'vim' } });
  manager.broadcastManager({ type: 'session-closed', id: 's1' });
  assert.deepEqual(ws.sent.slice(1), [
    { type: 'session', data: { id: 's1', termTitle: 'vim' } },
    { type: 'session-closed', id: 's1' },
  ]);
});

test('manager websocket is removed on close', () => {
  const manager = new SessionManager();
  const ws = fakeWebSocket();
  manager.attachManager(ws);
  ws.emitClose();
  manager.broadcastManager({ type: 'session', data: { id: 's1' } });
  assert.equal(ws.sent.length, 1);
});

function fakeWebSocket() {
  const handlers = new Map();
  return {
    readyState: 1,
    sent: [],
    send(data) {
      this.sent.push(JSON.parse(data));
    },
    on(event, handler) {
      handlers.set(event, handler);
    },
    emitClose() {
      handlers.get('close')?.();
    },
  };
}
