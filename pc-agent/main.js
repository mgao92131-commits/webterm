import WebSocket from 'ws';
import os from 'node:os';
import path from 'node:path';
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { TerminalSession } from '../server/terminal-session.js';
import {
  AGENT_REGISTER, REGISTERED,
  LIST_SESSIONS, CREATE_SESSION, CLOSE_SESSION, RENAME_SESSION,
  SESSIONS, SESSION_UPDATE, SESSION_CLOSED, SESSION_CREATED, ERROR,
  MSG_INPUT, MSG_OUTPUT, MSG_RESIZE, MSG_HELLO, MSG_PING, MSG_PONG, MSG_INFO, MSG_EXIT,
  encodeRelayFrame, decodeRelayFrame,
  encodeOutput, encodeJSON, encodeEmpty, decodeJSONPayload,
  sendJSON, sendBinary
} from '../shared/relay-protocol.js';

loadLocalEnv();

const relayUrl = process.env.RELAY_URL;
if (!relayUrl) {
  console.error('RELAY_URL must be set in .env.local or environment variables');
  process.exit(1);
}

const relaySecret = process.env.RELAY_SECRET;
if (!relaySecret) {
  console.error('RELAY_SECRET must be set in .env.local or environment variables');
  process.exit(1);
}

const deviceName = process.env.DEVICE_NAME || os.hostname();

// 存储所有的 TerminalSession
const sessions = new Map();
let nextSessionId = 1;

let ws = null;
let reconnectTimer = null;
let reconnectDelay = 1000; // 初始重连延时 1 秒

// 虚拟 Relay 客户端类，用于 attach 到 TerminalSession 截获输出并发往中继服务器
class RelayClient {
  constructor(sessionId, relaySendFn) {
    this.sessionId = sessionId;
    this.relaySend = relaySendFn;
    this.ready = true;

    // 用于 12ms 攒包缓冲区
    this.pendingOutputBuffers = [];
    this.pendingOutputBytes = 0;
    this.pendingOutputSeq = 0;
    this.outputBatchTimer = null;
  }

  send(message) {
    if (!ws || ws.readyState !== 1) return false;
    
    if (message.type === 'output') {
      if (message.batch === true) {
        return this.sendBatchedOutput(message);
      }
      this.flushPendingOutput();
      const bytes = message.bytes || Buffer.from(message.data || '', 'utf8');
      const termFrame = encodeOutput(message.seq, bytes);
      const frame = encodeRelayFrame(this.sessionId, termFrame);
      return this.relaySend(frame, true); // 发送二进制
    }

    this.flushPendingOutput();

    if (message.type === 'info') {
      return this.relaySend({ type: 'session-update', session: message.data }, false); // 发送JSON
    }
    if (message.type === 'exit') {
      return this.relaySend({ type: 'session-closed', sessionId: this.sessionId }, false); // 发送JSON
    }
    if (message.type === 'pong') {
      const termFrame = encodeEmpty(MSG_PONG);
      const frame = encodeRelayFrame(this.sessionId, termFrame);
      return this.relaySend(frame, true); // 发送二进制
    }
    return true;
  }

  sendBatchedOutput(message) {
    const bytes = message.bytes || Buffer.from(message.data || '', 'utf8');
    if (!bytes.length) return true;
    this.pendingOutputBuffers.push(bytes);
    this.pendingOutputBytes += bytes.length;
    this.pendingOutputSeq = Number(message.seq || this.pendingOutputSeq || 0);

    const OUTPUT_BATCH_DELAY_MS = 12;
    const OUTPUT_BATCH_MAX_BYTES = 64 * 1024;

    if (this.pendingOutputBytes >= OUTPUT_BATCH_MAX_BYTES) {
      this.flushPendingOutput();
    } else if (!this.outputBatchTimer) {
      this.outputBatchTimer = setTimeout(() => {
        this.outputBatchTimer = null;
        this.flushPendingOutput();
      }, OUTPUT_BATCH_DELAY_MS);
    }
    return true;
  }

  flushPendingOutput() {
    if (this.outputBatchTimer) {
      clearTimeout(this.outputBatchTimer);
      this.outputBatchTimer = null;
    }
    if (!this.pendingOutputBuffers.length) return;

    const combinedBytes = Buffer.concat(this.pendingOutputBuffers, this.pendingOutputBytes);
    const termFrame = encodeOutput(this.pendingOutputSeq, combinedBytes);
    const frame = encodeRelayFrame(this.sessionId, termFrame);

    this.pendingOutputBuffers = [];
    this.pendingOutputBytes = 0;
    this.pendingOutputSeq = 0;

    this.relaySend(frame, true);
  }

  close() {
    if (this.outputBatchTimer) {
      clearTimeout(this.outputBatchTimer);
      this.outputBatchTimer = null;
    }
  }
}

// 缓存各个 session 分配的 RelayClient 实例
const sessionClients = new Map(); // sessionId -> RelayClient

function connectToRelay() {
  console.log(`Connecting to Relay Server at ${relayUrl}...`);
  ws = new WebSocket(`${relayUrl}/ws/agent`);

  ws.on('open', () => {
    console.log('Connected to Relay Server.');
    reconnectDelay = 1000; // 重置重连延迟
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    // 发送注册信息
    sendJSON(ws, {
      type: AGENT_REGISTER,
      deviceName,
      secret: relaySecret
    });
  });

  ws.on('message', async (data, isBinary) => {
    if (isBinary) {
      // 收到中继转发来的二进制终端数据
      const relayFrame = decodeRelayFrame(data);
      if (!relayFrame) return;

      const { sessionId, terminalFrame } = relayFrame;
      const session = sessions.get(sessionId);
      if (!session) return;

      // 如果尚未为这个 session 创建或关联 RelayClient，则进行创建
      let rClient = sessionClients.get(sessionId);
      if (!rClient) {
        rClient = new RelayClient(sessionId, (payload, isBin) => {
          if (ws && ws.readyState === 1) {
            if (isBin) {
              return sendBinary(ws, payload);
            } else {
              return sendJSON(ws, payload);
            }
          }
          return false;
        });
        sessionClients.set(sessionId, rClient);
      }
      if (!session.clients.has(rClient)) {
        session.clients.add(rClient);
      }

      // 直接将交互动作帧分发至现有的 session 消息分发机制
      session.handleBinaryClientMessage(rClient, terminalFrame);
      return;
    }

    // JSON 控制面消息
    let msg;
    try {
      msg = JSON.parse(data.toString('utf8'));
    } catch {
      return;
    }

    switch (msg.type) {
      case REGISTERED:
        console.log(`Device registered successfully with ID: ${msg.deviceId}`);
        // 重连时重新为所有已有 session 附加 RelayClient
        for (const [id, session] of sessions.entries()) {
          const oldClient = sessionClients.get(id);
          if (oldClient) {
            oldClient.close();
            session.clients.delete(oldClient);
          }
          const rClient = new RelayClient(id, (payload, isBin) => {
            if (ws && ws.readyState === 1) {
              if (isBin) {
                return sendBinary(ws, payload);
              } else {
                return sendJSON(ws, payload);
              }
            }
            return false;
          });
          sessionClients.set(id, rClient);
          session.clients.add(rClient);
        }
        sendSessionList();
        break;

      case LIST_SESSIONS:
        sendSessionList(msg.actionId);
        break;

      case CREATE_SESSION: {
        const { name, cwd, actionId } = msg;
        const sessionId = 's' + nextSessionId++;
        console.log(`Creating session ${sessionId}: name=${name}, cwd=${cwd}`);

        const session = new TerminalSession({
          id: sessionId,
          name,
          cwd,
          onExit: (id) => {
            console.log(`Session ${id} exited.`);
            sessions.delete(id);
            sessionClients.delete(id);
            sendJSON(ws, { type: SESSION_CLOSED, sessionId: id });
          },
          onInfo: (info) => {
            sendJSON(ws, { type: SESSION_UPDATE, session: info });
          }
        });

        sessions.set(sessionId, session);

        // 始终附加虚拟 RelayClient 以激活数据输出
        const rClient = new RelayClient(sessionId, (payload, isBin) => {
          if (ws && ws.readyState === 1) {
            if (isBin) {
              return sendBinary(ws, payload);
            } else {
              return sendJSON(ws, payload);
            }
          }
          return false;
        });
        sessionClients.set(sessionId, rClient);
        session.clients.add(rClient);

        sendJSON(ws, { type: SESSION_CREATED, session: session.info(), actionId });
        break;
      }

      case CLOSE_SESSION: {
        const { sessionId, actionId } = msg;
        const session = sessions.get(sessionId);
        if (session) {
          console.log(`Closing session ${sessionId}`);
          session.close();
          sessions.delete(sessionId);
          sessionClients.delete(sessionId);
          sendJSON(ws, { type: SESSION_CLOSED, sessionId, actionId });
        } else {
          sendJSON(ws, { type: ERROR, message: 'Session not found', actionId });
        }
        break;
      }

      case RENAME_SESSION: {
        const { sessionId, name, actionId } = msg;
        const session = sessions.get(sessionId);
        if (session) {
          console.log(`Renaming session ${sessionId} to ${name}`);
          session.rename(name);
          sendJSON(ws, { type: SESSION_UPDATE, session: session.info(), actionId });
        } else {
          sendJSON(ws, { type: ERROR, message: 'Session not found', actionId });
        }
        break;
      }

      default:
        console.warn(`Unknown message type received: ${msg.type}`);
    }
  });

  ws.on('close', () => {
    console.log('Relay Server connection closed.');
    scheduleReconnect();
  });

  ws.on('error', (err) => {
    console.error('WebSocket connection error:', err.message);
    ws.close();
  });
}

function sendSessionList(actionId) {
  const list = Array.from(sessions.values()).map(s => s.info());
  sendJSON(ws, { type: SESSIONS, sessions: list, actionId });
}

function scheduleReconnect() {
  if (reconnectTimer) return;
  console.log(`Reconnecting in ${reconnectDelay}ms...`);
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connectToRelay();
    // 指数退避增长，最大间隔 10 秒
    reconnectDelay = Math.min(10000, reconnectDelay * 2);
  }, reconnectDelay);
}

connectToRelay();

function loadLocalEnv() {
  const file = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '.env.local');
  if (!existsSync(file)) return;
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const index = trimmed.indexOf('=');
    if (index <= 0) continue;
    const key = trimmed.slice(0, index).trim();
    const value = trimmed.slice(index + 1).trim().replace(/^(['"])(.*)\1$/, '$2');
    if (!process.env[key]) process.env[key] = value;
  }
}
