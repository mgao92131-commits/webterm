import { createRequire } from 'node:module';
import { randomUUID } from 'node:crypto';
import { createPty, validateCWD } from './pty-host.js';
import { EventRing } from './event-ring.js';
import { createCWDTracker, currentCWD } from './session-state.js';
import { AutoDetectClientConnection } from './client-connections.js';
import {
  MSG_HELLO,
  MSG_INPUT,
  MSG_RESIZE,
  MSG_PING,
  MSG_TITLE,
  decodeJSONPayload,
} from './protocol-binary.js';

const require = createRequire(import.meta.url);
const { Terminal } = require('@xterm/headless');
const { SerializeAddon } = require('@xterm/addon-serialize');

const DEFAULT_COLS = 100;
const DEFAULT_ROWS = 30;
const SCROLLBACK = 10000;
const FALLBACK_TITLE = 'Terminal';
const MAX_RECENT_INPUT_CHARS = 2000;

export class TerminalSession {
  constructor({ id, name, cwd, onExit, onInfo }) {
    this.id = id;
    this.instanceId = randomUUID();
    this.name = normalizeName(name);
    this.termTitle = '';
    this.cwd = validateCWD(cwd);
    this.createdAt = new Date();
    this.lastActiveAt = new Date();
    this.status = 'running';
    this.clients = new Set();
    this.onExit = onExit;
    this.onInfo = onInfo;
    this.cols = DEFAULT_COLS;
    this.rows = DEFAULT_ROWS;
    this.ring = new EventRing();
    this.inputBuffer = '';
    this.recentInputLines = [];
    this.recentInputHidden = false;
    this.term = new Terminal({
      cols: this.cols,
      rows: this.rows,
      scrollback: SCROLLBACK,
      allowProposedApi: true,
      windowsMode: process.platform === 'win32',
    });
    this.term.onTitleChange((title) => this.updateTermTitle(title));
    this.serializeAddon = new SerializeAddon();
    this.term.loadAddon(this.serializeAddon);
    const pty = createPty({ cwd: this.cwd, cols: this.cols, rows: this.rows });
    this.command = [pty.shell.command, ...pty.shell.args].join(' ');
    this.pty = pty.process;
    this.cwdTracker = createCWDTracker({ pid: this.pty.pid, initialCWD: this.cwd });
    this.pty.onData((data) => this.handleOutput(data));
    this.pty.onExit(({ exitCode }) => this.handleExit(exitCode));
  }

  info() {
    return {
      id: this.id,
      instanceId: this.instanceId,
      name: this.name,
      termTitle: this.termTitle,
      displayTitle: sessionDisplayTitle(this.name, this.termTitle),
      cwd: currentCWD(this.cwdTracker),
      recentInputLines: this.recentInputLines,
      recentInputHidden: this.recentInputHidden,
      command: this.command,
      status: this.status,
      clients: this.clients.size,
      cols: this.cols,
      rows: this.rows,
      createdAt: this.createdAt,
      lastActiveAt: this.lastActiveAt,
    };
  }

  rename(name) {
    this.name = normalizeName(name);
    this.touch();
    this.broadcastInfo();
  }

  updateTermTitle(title) {
    const nextTitle = normalizeTitle(title);
    if (nextTitle === this.termTitle) return;
    this.termTitle = nextTitle;
    this.touch();
    this.broadcastInfo();
  }

  attach(ws, options = {}) {
    const client = new AutoDetectClientConnection(ws, this, options);
    this.clients.add(client);
    this.touch();
    client.send({ type: 'info', data: this.info() });
    this.broadcastInfo({ exclude: client });

    ws.on('message', (raw, isBinary) => client.handleMessage(raw, isBinary));
    ws.on('close', () => {
      this.clients.delete(client);
      this.touch();
      this.broadcastInfo();
    });
    ws.on('error', () => {
      this.clients.delete(client);
    });
  }

  attachClient(client) {
    this.clients.add(client);
    this.touch();
  }

  detachClient(client) {
    this.clients.delete(client);
    this.touch();
  }

  hasClient(client) {
    return this.clients.has(client);
  }

  handleClientMessage(client, msg) {
    this.touch();
    if (msg.type === 'hello') {
      const lastSeq = Number(msg.lastSeq || 0);
      this.resizeFromHello(msg);
      const latestSeq = this.ring.latestSeq();
      if (lastSeq > 0 && lastSeq <= latestSeq && this.ring.canReplayFrom(lastSeq)) {
        client.send({ type: 'replay', from: lastSeq, frames: this.ring.after(lastSeq), seq: latestSeq });
      } else {
        client.send({ type: 'state', seq: latestSeq, data: this.serialize() });
      }
      client.send({ type: 'info', data: this.info() });
      client.ready = true;
      return;
    }
    if (msg.type === 'input' && typeof msg.data === 'string') {
      this.writeInput(msg.data);
      return;
    }
    if (msg.type === 'resize' && msg.visible !== false) {
      const cols = clampInt(msg.cols, 10, 500);
      const rows = clampInt(msg.rows, 5, 200);
      if (cols && rows) this.resize(cols, rows);
      return;
    }
    if (msg.type === 'ping') {
      client.send({ type: 'pong', seq: this.ring.latestSeq() });
    }
  }

  handleBinaryClientMessage(client, raw) {
    const frame = Buffer.from(raw);
    if (!frame.length) return;
    this.touch();
    const type = frame[0];
    const payload = frame.subarray(1);
    if (type === MSG_HELLO) {
      let lastSeq = 0;
      let hello = {};
      try {
        hello = decodeJSONPayload(payload);
        lastSeq = Number(hello.lastSeq || 0);
      } catch {
        lastSeq = 0;
      }
      client.send({ type: 'info', data: this.info() });
      this.resizeFromHello(hello);
      const latestSeq = this.ring.latestSeq();
      if (lastSeq > 0 && lastSeq <= latestSeq && this.ring.canReplayFrom(lastSeq)) {
        const frames = this.ring.after(lastSeq);
        if (frames.length > 0) {
          const MAX_BATCH_BYTES = 64 * 1024;
          let batchBytes = [];
          let batchSeq = 0;
          let currentSize = 0;
          for (const outputFrame of frames) {
            const frameBytes = outputFrame.bytes || Buffer.from(outputFrame.text || '', 'utf8');
            if (currentSize > 0 && currentSize + frameBytes.length > MAX_BATCH_BYTES) {
              client.send({
                type: 'output',
                seq: batchSeq,
                bytes: Buffer.concat(batchBytes),
              });
              batchBytes = [];
              currentSize = 0;
            }
            batchBytes.push(frameBytes);
            batchSeq = outputFrame.seq;
            currentSize += frameBytes.length;
          }
          if (batchBytes.length > 0) {
            client.send({
              type: 'output',
              seq: batchSeq,
              bytes: Buffer.concat(batchBytes),
            });
          }
        }
      } else {
        const clearScreen = '\x1b[3J\x1b[2J\x1b[H';
        const stateData = this.serialize() || '';
        const snapshotBytes = Buffer.from(clearScreen + stateData, 'utf8');
        client.send({
          type: 'state',
          seq: latestSeq,
          bytes: snapshotBytes,
        });
      }
      client.ready = true;
      return;
    }
    if (type === MSG_INPUT) {
      this.writeInput(payload);
      return;
    }
    if (type === MSG_RESIZE) {
      try {
        const resize = decodeJSONPayload(payload);
        const cols = clampInt(resize.cols, 10, 500);
        const rows = clampInt(resize.rows, 5, 200);
        if (cols && rows) this.resize(cols, rows);
      } catch {
        // Ignore malformed resize frames.
      }
      return;
    }
    if (type === MSG_PING) {
      client.send({ type: 'pong', seq: this.ring.latestSeq() });
      return;
    }
    if (type === MSG_TITLE) {
      this.updateTermTitle(payload.toString('utf8'));
    }
  }

  handleOutput(data) {
    this.touch();
    this.term.write(data);
    const frame = this.ring.push(data);
    this.broadcast({ type: 'output', seq: frame.seq, data: frame.text, bytes: frame.bytes, batch: true });
  }

  handleExit(code) {
    if (this.status === 'closed') return;
    this.status = 'closed';
    this.broadcast({ type: 'exit', code });
    for (const client of this.clients) client.close();
    this.clients.clear();
    this.onExit?.(this.id);
  }

  resize(cols, rows) {
    this.cols = cols;
    this.rows = rows;
    this.term.resize(cols, rows);
    this.pty.resize(cols, rows);
    this.touch();
  }

  resizeFromHello(hello) {
    const cols = clampInt(hello?.cols, 10, 500);
    const rows = clampInt(hello?.rows, 5, 200);
    if (cols && rows && (cols !== this.cols || rows !== this.rows)) this.resize(cols, rows);
  }

  writeInput(data) {
    const text = Buffer.isBuffer(data) ? data.toString('utf8') : String(data || '');
    if (!text) return;
    this.recordInput(text);
    this.pty.write(text);
  }

  close() {
    if (this.status === 'closed') return;
    this.status = 'closed';
    this.pty.kill();
    this.broadcast({ type: 'exit' });
    for (const client of this.clients) client.close();
    this.clients.clear();
  }

  serialize() {
    return this.serializeAddon.serialize();
  }

  broadcast(message) {
    for (const client of [...this.clients]) {
      if (message.exclude === client) {
        continue;
      }
      if (message.type === 'output' && !client.ready) {
        continue;
      }
      const outbound = message.exclude ? withoutInternalFields(message) : message;
      if (!client.send(outbound)) {
        client.close();
        this.clients.delete(client);
      }
    }
  }

  touch() {
    this.lastActiveAt = new Date();
  }

  recordInput(data) {
    for (const char of String(data || '')) {
      if (char === '\r') {
        this.commitInputBuffer();
      } else if (char === '\n') {
        this.inputBuffer = (this.inputBuffer + char).slice(-MAX_RECENT_INPUT_CHARS);
      } else if (char === '\x03' || char === '\x1b') {
        this.inputBuffer = '';
      } else if (char === '\x7f' || char === '\b') {
        this.inputBuffer = this.inputBuffer.slice(0, -1);
      } else if (isPrintableInputChar(char)) {
        this.inputBuffer = (this.inputBuffer + char).slice(-MAX_RECENT_INPUT_CHARS);
      }
    }
  }

  commitInputBuffer() {
    const text = this.inputBuffer.trim();
    this.inputBuffer = '';
    if (!text) return;
    this.recentInputHidden = isSensitiveInput(text);
    this.recentInputLines = this.recentInputHidden ? [] : lastInputLines(text, 2);
    this.touch();
    this.broadcastInfo();
  }

  broadcastInfo({ exclude = null } = {}) {
    const info = this.info();
    this.broadcast({ type: 'info', data: info, exclude });
    this.onInfo?.(info);
  }
}

function withoutInternalFields(message) {
  const { exclude, ...wireMessage } = message;
  return wireMessage;
}

function normalizeName(name) {
  return String(name || '').trim();
}

function normalizeTitle(title) {
  return String(title || '').trim();
}

function isPrintableInputChar(char) {
  return char >= ' ' && char !== '\x7f';
}

export function isSensitiveInput(value) {
  return /\b(pass(word|wd)?|token|secret|api[_-]?key|authorization|bearer|credential|private[_-]?key)\b/i.test(String(value || ''));
}

export function lastInputLines(value, count = 2) {
  return String(value || '')
    .split(/\r?\n/)
    .map((line) => line.trimEnd())
    .filter((line) => line.trim())
    .slice(-count);
}

export function sessionDisplayTitle(name, termTitle) {
  const cleanName = normalizeName(name);
  const cleanTermTitle = normalizeTitle(termTitle) || FALLBACK_TITLE;
  return cleanName ? `${cleanName} - ${cleanTermTitle}` : cleanTermTitle;
}

function clampInt(value, min, max) {
  const number = Number(value);
  if (!Number.isInteger(number)) return 0;
  return Math.max(min, Math.min(max, number));
}
