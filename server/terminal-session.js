import { createRequire } from 'node:module';
import { createPty, validateCWD } from './pty-host.js';
import { EventRing } from './event-ring.js';

const require = createRequire(import.meta.url);
const { Terminal } = require('@xterm/headless');
const { SerializeAddon } = require('@xterm/addon-serialize');

const DEFAULT_COLS = 100;
const DEFAULT_ROWS = 30;
const SCROLLBACK = 20000;

export class TerminalSession {
  constructor({ id, name, cwd, onExit }) {
    this.id = id;
    this.name = name || id;
    this.cwd = validateCWD(cwd);
    this.createdAt = new Date();
    this.lastActiveAt = new Date();
    this.status = 'running';
    this.clients = new Set();
    this.onExit = onExit;
    this.cols = DEFAULT_COLS;
    this.rows = DEFAULT_ROWS;
    this.ring = new EventRing();
    this.term = new Terminal({
      cols: this.cols,
      rows: this.rows,
      scrollback: SCROLLBACK,
      allowProposedApi: true,
      windowsMode: process.platform === 'win32',
    });
    this.serializeAddon = new SerializeAddon();
    this.term.loadAddon(this.serializeAddon);
    const pty = createPty({ cwd: this.cwd, cols: this.cols, rows: this.rows });
    this.command = [pty.shell.command, ...pty.shell.args].join(' ');
    this.pty = pty.process;
    this.pty.onData((data) => this.handleOutput(data));
    this.pty.onExit(({ exitCode }) => this.handleExit(exitCode));
  }

  info() {
    return {
      id: this.id,
      name: this.name,
      cwd: this.cwd,
      command: this.command,
      status: this.status,
      clients: this.clients.size,
      createdAt: this.createdAt,
      lastActiveAt: this.lastActiveAt,
    };
  }

  rename(name) {
    if (!name || !name.trim()) throw new Error('name is required');
    this.name = name.trim();
    this.touch();
    this.broadcast({ type: 'info', data: this.info() });
  }

  attach(ws) {
    const client = new ClientConnection(ws);
    this.clients.add(client);
    this.touch();
    client.send({ type: 'info', data: this.info() });
    client.send({ type: 'state', seq: this.ring.latestSeq(), data: this.serialize() });
    this.broadcast({ type: 'info', data: this.info() });

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
      this.broadcast({ type: 'info', data: this.info() });
    });
    ws.on('error', () => {
      this.clients.delete(client);
    });
  }

  handleClientMessage(client, msg) {
    this.touch();
    if (msg.type === 'hello') {
      const lastSeq = Number(msg.lastSeq || 0);
      if (this.ring.canReplayFrom(lastSeq)) {
        client.send({ type: 'replay', from: lastSeq, frames: this.ring.after(lastSeq), seq: this.ring.latestSeq() });
      } else {
        client.send({ type: 'state', seq: this.ring.latestSeq(), data: this.serialize() });
      }
      client.send({ type: 'info', data: this.info() });
      return;
    }
    if (msg.type === 'input' && typeof msg.data === 'string') {
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
