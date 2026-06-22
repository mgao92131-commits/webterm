import test from 'node:test';
import assert from 'node:assert/strict';
import { isSensitiveInput, lastInputLines, TerminalSession, sessionDisplayTitle } from './terminal-session.js';
import { BINARY_SUBPROTOCOL, JSON_SUBPROTOCOL, MSG_HELLO, MSG_INFO, MSG_INPUT, MSG_OUTPUT, MSG_PING, MSG_PONG, MSG_RESIZE, MSG_STATE, MSG_TITLE, readUint64BE } from './protocol-binary.js';

test('session display title follows terminal title when name is empty', () => {
  assert.equal(sessionDisplayTitle('', 'zsh'), 'zsh');
  assert.equal(sessionDisplayTitle('  ', ''), 'Terminal');
});

test('session display title prefixes terminal title with custom name', () => {
  assert.equal(sessionDisplayTitle('work', 'vim README.md'), 'work - vim README.md');
});

test('terminal title changes broadcast info immediately', () => {
  const infos = [];
  const session = fakeSession({ latestSeq: 0 });
  session.info = function () {
    return { id: 's1', termTitle: this.termTitle };
  };
  session.onInfo = (info) => infos.push(info);
  session.updateTermTitle = TerminalSession.prototype.updateTermTitle;
  session.broadcastInfo = TerminalSession.prototype.broadcastInfo;

  session.updateTermTitle('vim README.md');

  assert.deepEqual(infos, [{ id: 's1', termTitle: 'vim README.md' }]);
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
  assert.deepEqual(messageTypes(ws), ['info']);
});

test('hello with lastSeq 0 returns state', () => {
  const session = fakeSession({ latestSeq: 2 });
  const ws = fakeWebSocket();
  TerminalSession.prototype.attach.call(session, ws);
  ws.emitMessage({ type: 'hello', lastSeq: 0 });
  assert.deepEqual(messageTypes(ws), ['info', 'state', 'info']);
});

test('hello with dimensions resizes before serializing state', () => {
  const session = fakeSession({ latestSeq: 2 });
  session.serialize = () => {
    assert.deepEqual(session.resizes, [{ cols: 120, rows: 40 }]);
    return 'RESIZED_STATE';
  };
  const ws = fakeWebSocket();
  TerminalSession.prototype.attach.call(session, ws);
  ws.emitMessage({ type: 'hello', lastSeq: 0, cols: 120, rows: 40 });

  const state = sentMessages(ws).find((msg) => msg.type === 'state');
  assert.deepEqual(state, { type: 'state', seq: 2, data: 'RESIZED_STATE' });
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

  ws.emitMessage({ type: 'hello', lastSeq: 0 });

  for (let index = 0; index < 250; index += 1) {
    TerminalSession.prototype.broadcast.call(session, { type: 'output', seq: index + 1, data: String(index) });
  }

  assert.equal(ws.closed, true);
  assert.equal(session.clients.size, 0);
});

test('json clients batch high frequency output frames', async () => {
  const session = fakeSession({ latestSeq: 0 });
  const ws = fakeWebSocket();
  TerminalSession.prototype.attach.call(session, ws);
  ws.emitMessage({ type: 'hello', lastSeq: 0 });
  ws.sent = [];

  TerminalSession.prototype.broadcast.call(session, { type: 'output', seq: 1, data: 'a', batch: true });
  TerminalSession.prototype.broadcast.call(session, { type: 'output', seq: 2, data: 'b', batch: true });
  await new Promise((resolve) => setTimeout(resolve, 20));

  assert.deepEqual(ws.sent, [{ type: 'output', seq: 2, data: 'ab' }]);
});

test('binary hello replays output frames with seq prefix', () => {
  const session = fakeSession({ latestSeq: 3 });
  const ws = fakeWebSocket();
  TerminalSession.prototype.attach.call(session, ws);
  ws.emitBinary(MSG_HELLO_FRAME({ lastSeq: 1 }));

  const info = sentBinary(ws).find((frame) => frame[0] === MSG_INFO);
  assert.ok(info);
  const output = sentBinary(ws).filter((frame) => frame[0] === MSG_OUTPUT);
  assert.equal(output.length, 1);
  assert.equal(readUint64BE(output[0], 1), 3);
  assert.equal(output[0].subarray(9).toString('utf8'), 'bc');
});

test('binary hello with lastSeq 0 returns clear screen and state snapshot', () => {
  const session = fakeSession({ latestSeq: 3 });
  session.serialize = () => 'SNAPSHOT_DATA';
  const ws = fakeWebSocket();
  TerminalSession.prototype.attach.call(session, ws);
  ws.emitBinary(MSG_HELLO_FRAME({ lastSeq: 0 }));

  const info = sentBinary(ws).find((frame) => frame[0] === MSG_INFO);
  assert.ok(info);
  const state = sentBinary(ws).filter((frame) => frame[0] === MSG_STATE);
  assert.equal(state.length, 1);
  assert.equal(readUint64BE(state[0], 1), 3);
  assert.equal(state[0].subarray(9).toString('utf8'), '\x1b[3J\x1b[2J\x1b[HSNAPSHOT_DATA');
});

test('binary hello with dimensions resizes before state snapshot', () => {
  const session = fakeSession({ latestSeq: 3 });
  session.serialize = () => {
    assert.deepEqual(session.resizes, [{ cols: 132, rows: 43 }]);
    return 'BINARY_RESIZED_STATE';
  };
  const ws = fakeWebSocket();
  TerminalSession.prototype.attach.call(session, ws);
  ws.emitBinary(MSG_HELLO_FRAME({ lastSeq: 0, cols: 132, rows: 43 }));

  const state = sentBinary(ws).find((frame) => frame[0] === MSG_STATE);
  assert.equal(readUint64BE(state, 1), 3);
  assert.equal(state.subarray(9).toString('utf8'), '\x1b[3J\x1b[2J\x1b[HBINARY_RESIZED_STATE');
});

test('binary hello with dimensions resizes before replay', () => {
  const session = fakeSession({ latestSeq: 3 });
  const ws = fakeWebSocket();
  TerminalSession.prototype.attach.call(session, ws);
  ws.emitBinary(MSG_HELLO_FRAME({ lastSeq: 1, cols: 140, rows: 45 }));

  assert.deepEqual(session.resizes, [{ cols: 140, rows: 45 }]);
  const output = sentBinary(ws).filter((frame) => frame[0] === MSG_OUTPUT);
  assert.equal(output.length, 1);
  assert.equal(readUint64BE(output[0], 1), 3);
});

test('binary subprotocol selects binary transport before first message', () => {
  const session = fakeSession({ latestSeq: 0 });
  const ws = fakeWebSocket();
  TerminalSession.prototype.attach.call(session, ws, { protocolHint: BINARY_SUBPROTOCOL });

  assert.ok(sentBinary(ws).some((frame) => frame[0] === MSG_INFO));
});

test('raw protocol header value does not force binary mode', () => {
  const session = fakeSession({ latestSeq: 0 });
  const ws = fakeWebSocket();
  TerminalSession.prototype.attach.call(session, ws, {
    protocolHint: `${JSON_SUBPROTOCOL}, ${BINARY_SUBPROTOCOL}`,
  });

  assert.deepEqual(messageTypes(ws), ['info']);
});

test('binary input records and writes to pty', () => {
  const session = fakeSession({ latestSeq: 0 });
  const ws = fakeWebSocket();
  TerminalSession.prototype.attach.call(session, ws);
  ws.emitBinary(Buffer.concat([Buffer.from([MSG_INPUT]), Buffer.from('npm test\r')]));

  assert.deepEqual(session.recentInputLines, ['npm test']);
  assert.deepEqual(session.writes, ['npm test\r']);
});

test('binary resize and ping are handled', () => {
  const session = fakeSession({ latestSeq: 2 });
  const ws = fakeWebSocket();
  TerminalSession.prototype.attach.call(session, ws);
  ws.emitBinary(binaryFrame(MSG_RESIZE, { cols: 120, rows: 40 }));
  ws.emitBinary(Buffer.from([MSG_PING]));

  assert.deepEqual(session.resizes, [{ cols: 120, rows: 40 }]);
  assert.ok(sentBinary(ws).some((frame) => frame[0] === MSG_PONG));
});

test('binary malformed resize is ignored', () => {
  const session = fakeSession({ latestSeq: 0 });
  const ws = fakeWebSocket();
  TerminalSession.prototype.attach.call(session, ws);
  ws.emitBinary(Buffer.from([MSG_RESIZE, 123]));
  assert.deepEqual(session.resizes, []);
});

test('binary title frame broadcasts updated info', () => {
  const infos = [];
  const session = fakeSession({ latestSeq: 0 });
  session.termTitle = '';
  session.info = function () {
    return { id: 's1', termTitle: this.termTitle };
  };
  session.onInfo = (info) => infos.push(info);
  session.updateTermTitle = TerminalSession.prototype.updateTermTitle;
  const ws = fakeWebSocket();
  TerminalSession.prototype.attach.call(session, ws, { protocolHint: BINARY_SUBPROTOCOL });

  ws.emitBinary(Buffer.concat([Buffer.from([MSG_TITLE]), Buffer.from('mobile vim')]));

  assert.equal(infos.at(-1).termTitle, 'mobile vim');
});

function fakeSession({ latestSeq }) {
  const frames = [
    frame(1, 'a'),
    frame(2, 'b'),
    frame(3, 'c'),
  ].filter((frame) => frame.seq <= latestSeq);
  const session = {
    clients: new Set(),
    inputBuffer: '',
    recentInputLines: [],
    recentInputHidden: false,
    writes: [],
    resizes: [],
    pty: {
      write(data) {
        session.writes.push(data);
      },
    },
    touch() {},
    info() {
      return { id: 's1', name: 'terminal-1' };
    },
    serialize() {
      return 'SERIALIZED';
    },
    broadcast: TerminalSession.prototype.broadcast,
    broadcastInfo: TerminalSession.prototype.broadcastInfo,
    handleBinaryClientMessage: TerminalSession.prototype.handleBinaryClientMessage,
    writeInput: TerminalSession.prototype.writeInput,
    resizeFromHello: TerminalSession.prototype.resizeFromHello,
    resize(cols, rows) {
      this.resizes.push({ cols, rows });
    },
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
  return session;
}

function fakeWebSocket({ autoCompleteSend = true } = {}) {
  const handlers = new Map();
  return {
    readyState: 1,
    sent: [],
    closed: false,
    send(data, callback) {
      if (Buffer.isBuffer(data)) {
        this.sent.push(data);
      } else {
        this.sent.push(JSON.parse(data));
      }
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
    emitBinary(frame) {
      handlers.get('message')?.(Buffer.from(frame), true);
    },
  };
}

function sentMessages(ws) {
  return ws.sent;
}

function messageTypes(ws) {
  return sentMessages(ws).map((msg) => msg.type);
}

function sentBinary(ws) {
  return ws.sent.filter(Buffer.isBuffer);
}

function frame(seq, data) {
  const text = String(data);
  return { seq, data: text, text, bytes: Buffer.from(text, 'utf8') };
}

function binaryFrame(type, payload) {
  return Buffer.concat([Buffer.from([type]), Buffer.from(JSON.stringify(payload), 'utf8')]);
}

function MSG_HELLO_FRAME(payload) {
  return binaryFrame(MSG_HELLO, payload);
}
