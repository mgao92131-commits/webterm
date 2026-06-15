import WebSocket from 'ws';
import os from 'node:os';
import path from 'node:path';
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { showConfirmDialog } from './auth-prompt.js';
import { TerminalSession } from '../server/terminal-session.js';
import {
  AGENT_REGISTER, REGISTERED, CONNECT_REQUEST, CONNECT_ACCEPT, CONNECT_REJECT,
  CLIENT_PAIRED, CLIENT_UNPAIRED,
  LIST_SESSIONS, CREATE_SESSION, CLOSE_SESSION, RENAME_SESSION,
  SESSIONS, SESSION_UPDATE, SESSION_CLOSED, SESSION_CREATED,
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

// 当前已配对的手机客户端
const connectedClients = new Set();

let ws = null;
let reconnectTimer = null;
let reconnectDelay = 1000; // 初始重连延时 1 秒

// 虚拟 Relay 客户端类，用于 attach 到 TerminalSession 截获输出并发往中继服务器
class RelayClient {
  constructor(sessionId, relaySendFn) {
    this.sessionId = sessionId;
    this.relaySend = relaySendFn;
    this.ready = true;
  }

  send(message) {
    if (!ws || ws.readyState !== 1) return false;
    
    if (message.type === 'output') {
      const bytes = message.bytes || Buffer.from(message.data || '', 'utf8');
      const termFrame = encodeOutput(message.seq, bytes);
      const frame = encodeRelayFrame(this.sessionId, termFrame);
      return this.relaySend(frame, true); // 发送二进制
    }
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

  close() {
    // 虚拟客户端关闭时不需要做特殊处理，因为 PTY 生命周期不与单个 client 强制绑定
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
        break;

      case CONNECT_REQUEST: {
        const { requestId, clientInfo } = msg;
        console.log(`Received connection request ${requestId} from ${clientInfo}`);
        
        // 调用系统提示弹窗
        const approved = await showConfirmDialog(
          'Remote Terminal Connection',
          `Do you want to allow ${clientInfo} to connect and control your terminal?`
        );

        if (approved) {
          console.log(`Connection request ${requestId} APPROVED.`);
          sendJSON(ws, { type: CONNECT_ACCEPT, requestId });
        } else {
          console.log(`Connection request ${requestId} DENIED.`);
          sendJSON(ws, { type: CONNECT_REJECT, requestId });
        }
        break;
      }

      case CLIENT_PAIRED: {
        const { clientId } = msg;
        console.log(`Client paired: ${clientId}`);
        connectedClients.add(clientId);

        // 如果至少有一个客户端连接，确保所有的 session 都附加上虚拟 RelayClient，以向外广播输出流
        for (const [id, session] of sessions.entries()) {
          let rClient = sessionClients.get(id);
          if (!rClient) {
            rClient = new RelayClient(id, (payload, isBin) => {
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
          }
          session.clients.add(rClient);
        }

        // 发送当前的会话列表给新配对的手机端
        sendSessionList();
        break;
      }

      case CLIENT_UNPAIRED: {
        const { clientId } = msg;
        console.log(`Client unpaired: ${clientId}`);
        connectedClients.delete(clientId);

        // 如果全部手机客户端都断开，注销虚拟 RelayClient 避免无谓的数据传输，但保留 PTY 进程
        if (connectedClients.size === 0) {
          for (const [id, session] of sessions.entries()) {
            const rClient = sessionClients.get(id);
            if (rClient) {
              session.clients.delete(rClient);
            }
          }
        }
        break;
      }

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

        // 如果当前有客户端已配对在线，直接附加虚拟 RelayClient 激活数据输出
        if (connectedClients.size > 0) {
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
        }

        sendJSON(ws, { type: SESSION_CREATED, session: session.info(), actionId });
        break;
      }

      case CLOSE_SESSION: {
        const { sessionId, actionId } = msg;
        const session = sessions.get(sessionId);
        if (session) {
          console.log(`Closing session ${sessionId}`);
          session.close();
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
