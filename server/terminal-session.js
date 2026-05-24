import { createRequire } from 'node:module';
import { createPty, validateCWD } from './pty-host.js';
import { EventRing } from './event-ring.js';
import { createCWDTracker, currentCWD } from './session-state.js';

const require = createRequire(import.meta.url);
const { Terminal } = require('@xterm/headless');
const { SerializeAddon } = require('@xterm/addon-serialize');

const DEFAULT_COLS = 100;
const DEFAULT_ROWS = 30;
const SCROLLBACK = 20000;
const FALLBACK_TITLE = 'Terminal';
const MAX_RECENT_INPUT_CHARS = 2000;

export class TerminalSession {
  constructor({ id, name, cwd, onExit, onInfo }) {
    this.id = id;
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
      name: this.name,
      termTitle: this.termTitle,
      displayTitle: sessionDisplayTitle(this.name, this.termTitle),
      cwd: currentCWD(this.cwdTracker),
      recentInputLines: this.recentInputLines,
      recentInputHidden: this.recentInputHidden,
      command: this.command,
      status: this.status,
      clients: this.clients.size,
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

  attach(ws) {
    const client = new ClientConnection(ws);
    this.clients.add(client);
    this.touch();
    client.send({ type: 'info', data: this.info() });
    this.broadcastInfo();

    ws.on('message', (raw) => {
      let msg;
      try {
        msg = JSON.parse(raw.toString('utf8'));
      } catch {
        return;
      }
      this.handleClientMessage(client, msg);
    });
    ws.on('close', () => {
      this.clients.delete(client);
      this.touch();
      this.broadcastInfo();
    });
    ws.on('error', () => {
      this.clients.delete(client);
    });
  }

  handleClientMessage(client, msg) {
    this.touch();
    if (msg.type === 'hello') {
      const lastSeq = Number(msg.lastSeq || 0);
      const latestSeq = this.ring.latestSeq();
      if (lastSeq > 0 && lastSeq <= latestSeq && this.ring.canReplayFrom(lastSeq)) {
        client.send({ type: 'replay', from: lastSeq, frames: this.ring.after(lastSeq), seq: latestSeq });
      } else {
        client.send({ type: 'state', seq: latestSeq, data: this.serialize() });
      }
      client.send({ type: 'info', data: this.info() });
      return;
    }
    if (msg.type === 'input' && typeof msg.data === 'string') {
      this.recordInput(msg.data);
      this.pty.write(msg.data);
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

  handleOutput(data) {
    this.touch();
    this.term.write(data);
    const frame = this.ring.push(data);
    this.broadcast({ type: 'output', seq: frame.seq, data });
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
      if (!client.send(message)) {
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

  broadcastInfo() {
    const info = this.info();
    this.broadcast({ type: 'info', data: info });
    this.onInfo?.(info);
  }
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

class ClientConnection {
  constructor(ws) {
    this.ws = ws;
    this.queue = [];
    this.sending = false;
    this.maxQueue = 200;
  }

  send(message) {
    if (this.ws.readyState !== 1) return false;
    this.queue.push(JSON.stringify(message));
    if (this.queue.length > this.maxQueue) return false;
    this.flush();
    return true;
  }

  flush() {
    if (this.sending || !this.queue.length) return;
    if (this.ws.readyState !== 1) return;
    this.sending = true;
    const data = this.queue.shift();
    this.ws.send(data, (err) => {
      this.sending = false;
      if (err) {
        this.close();
        return;
      }
      this.flush();
    });
  }

  close() {
    try {
      this.ws.close();
    } catch {
      // already closed
    }
  }
}

function clampInt(value, min, max) {
  const number = Number(value);
  if (!Number.isInteger(number)) return 0;
  return Math.max(min, Math.min(max, number));
}
