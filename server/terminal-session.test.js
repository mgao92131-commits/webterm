import test from 'node:test';
import assert from 'node:assert/strict';
import { isSensitiveInput, lastInputLines, TerminalSession, sessionDisplayTitle } from './terminal-session.js';

test('session display title follows terminal title when name is empty', () => {
  assert.equal(sessionDisplayTitle('', 'zsh'), 'zsh');
  assert.equal(sessionDisplayTitle('  ', ''), 'Terminal');
});

test('session display title prefixes terminal title with custom name', () => {
  assert.equal(sessionDisplayTitle('work', 'vim README.md'), 'work - vim README.md');
});

test('last input lines keeps the final two non-empty lines', () => {
  assert.deepEqual(lastInputLines('one\n\ntwo\nthree', 2), ['two', 'three']);
});

test('sensitive input detection hides secret-like commands', () => {
  assert.equal(isSensitiveInput('export API_KEY=abc'), true);
  assert.equal(isSensitiveInput('npm test'), false);
});

test('record input captures submitted text and backspace edits', () => {
  const session = fakeSession({ latestSeq: 0 });
  session.recordInput('npm tesx');
  session.recordInput('\x7ft\r');
  assert.deepEqual(session.recentInputLines, ['npm test']);
  assert.equal(session.recentInputHidden, false);
});

test('record input stores final two lines and hides sensitive values', () => {
  const session = fakeSession({ latestSeq: 0 });
  session.recordInput('one\ntwo\nthree\r');
  assert.deepEqual(session.recentInputLines, ['two', 'three']);
  session.recordInput('export TOKEN=abc\r');
  assert.deepEqual(session.recentInputLines, []);
  assert.equal(session.recentInputHidden, true);
});

test('attach sends info but does not send state before hello', () => {
  const session = fakeSession({ latestSeq: 2 });
  const ws = fakeWebSocket();
  TerminalSession.prototype.attach.call(session, ws);
  assert.deepEqual(messageTypes(ws), ['info', 'info']);
});

test('hello with lastSeq 0 returns state', () => {
  const session = fakeSession({ latestSeq: 2 });
  const ws = fakeWebSocket();
  TerminalSession.prototype.attach.call(session, ws);
  ws.emitMessage({ type: 'hello', lastSeq: 0 });
  assert.deepEqual(messageTypes(ws), ['info', 'info', 'state', 'info']);
});

test('hello with current lastSeq returns empty replay', () => {
  const session = fakeSession({ latestSeq: 2 });
  const ws = fakeWebSocket();
  TerminalSession.prototype.attach.call(session, ws);
  ws.emitMessage({ type: 'hello', lastSeq: 2 });
  const replay = sentMessages(ws).find((msg) => msg.type === 'replay');
  assert.deepEqual(replay, { type: 'replay', from: 2, frames: [], seq: 2 });
});

test('hello with replayable older lastSeq returns incremental replay', () => {
  const session = fakeSession({ latestSeq: 3 });
  const ws = fakeWebSocket();
  TerminalSession.prototype.attach.call(session, ws);
  ws.emitMessage({ type: 'hello', lastSeq: 1 });
  const replay = sentMessages(ws).find((msg) => msg.type === 'replay');
  assert.deepEqual(replay, { type: 'replay', from: 1, frames: [{ seq: 2, data: 'b' }, { seq: 3, data: 'c' }], seq: 3 });
});

test('hello with stale future lastSeq returns state', () => {
  const session = fakeSession({ latestSeq: 2 });
  const ws = fakeWebSocket();
  TerminalSession.prototype.attach.call(session, ws);
  ws.emitMessage({ type: 'hello', lastSeq: 99 });
  const state = sentMessages(ws).find((msg) => msg.type === 'state');
  assert.deepEqual(state, { type: 'state', seq: 2, data: 'SERIALIZED' });
});

test('broadcast closes slow clients when the send queue overflows', () => {
  const session = fakeSession({ latestSeq: 0 });
  const ws = fakeWebSocket({ autoCompleteSend: false });
  TerminalSession.prototype.attach.call(session, ws);

  for (let index = 0; index < 250; index += 1) {
    TerminalSession.prototype.broadcast.call(session, { type: 'output', seq: index + 1, data: String(index) });
  }

  assert.equal(ws.closed, true);
  assert.equal(session.clients.size, 0);
});

function fakeSession({ latestSeq }) {
  const frames = [
    { seq: 1, data: 'a' },
    { seq: 2, data: 'b' },
    { seq: 3, data: 'c' },
  ].filter((frame) => frame.seq <= latestSeq);
  return {
    clients: new Set(),
    inputBuffer: '',
    recentInputLines: [],
    recentInputHidden: false,
    touch() {},
    info() {
      return { id: 's1', name: 'terminal-1' };
    },
    serialize() {
      return 'SERIALIZED';
    },
    broadcast(message) {
      for (const client of this.clients) client.send(message);
    },
    broadcastInfo: TerminalSession.prototype.broadcastInfo,
    ring: {
      latestSeq() {
        return latestSeq;
      },
      canReplayFrom(seq) {
        return seq >= 0;
      },
      after(seq) {
        return frames.filter((frame) => frame.seq > seq);
      },
    },
    handleClientMessage: TerminalSession.prototype.handleClientMessage,
    recordInput: TerminalSession.prototype.recordInput,
    commitInputBuffer: TerminalSession.prototype.commitInputBuffer,
  };
}

function fakeWebSocket({ autoCompleteSend = true } = {}) {
  const handlers = new Map();
  return {
    readyState: 1,
    sent: [],
    closed: false,
    send(data, callback) {
      this.sent.push(JSON.parse(data));
      if (autoCompleteSend) callback?.();
    },
    close() {
      this.closed = true;
      this.readyState = 3;
      handlers.get('close')?.();
    },
    on(event, handler) {
      handlers.set(event, handler);
    },
    emitMessage(message) {
      handlers.get('message')?.(Buffer.from(JSON.stringify(message)));
    },
  };
}

function sentMessages(ws) {
  return ws.sent;
}

function messageTypes(ws) {
  return sentMessages(ws).map((msg) => msg.type);
}
