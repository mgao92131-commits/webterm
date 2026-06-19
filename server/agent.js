import WebSocket from 'ws';
import os from 'node:os';
import { SessionManager } from './session-manager.js';
import {
  AGENT_REGISTER, REGISTERED, ERROR,
  HTTP_REQUEST, HTTP_RESPONSE, HTTP_ERROR,
  WS_CONNECT, WS_CONNECTED, WS_ERROR, WS_CLOSE,
  MSG_TYPE_WS_DATA,
  WS_DATA_TEXT, WS_DATA_BINARY,
  encodeTunnelFrame, decodeTunnelFrame,
  sendJSON, sendBinary
} from '../shared/tunnel-protocol.js';
import { VirtualSocket } from '../shared/tunnel-transport.js';
import { P2PManager } from './webrtc.js';

const sessions = new SessionManager();

let relayUrl;
let relaySecret;
let deviceName;

let ws = null;
let reconnectTimer = null;
let reconnectDelay = 1000;
let p2p = null;

// VirtualSocket 隧道 Map: tunnelConnectionId -> VirtualSocket
const virtualSockets = new Map();

export function startAgent() {
  relayUrl = process.env.RELAY_URL;
  if (!relayUrl) {
    console.error('RELAY_URL must be set in .env.local or environment variables');
    process.exit(1);
  }

  relaySecret = process.env.RELAY_SECRET;
  if (!relaySecret) {
    console.error('RELAY_SECRET must be set in .env.local or environment variables');
    process.exit(1);
  }

  deviceName = process.env.DEVICE_NAME || os.hostname();

  console.log(`[Agent] Initializing in-memory Terminal Session Manager...`);
  p2p = new P2PManager((m) => sendJSON(getAgentWs(), m));
  p2p.setMessageHandlers({
    onTextMessage: (parsed, transport) => handleP2pMessage(parsed, transport),
    onBinaryMessage: (data) => handleBinaryFrame(data),
  });
  // 建立与中转服务器的连接
  connectToRelay();
}

function connectToRelay() {
  console.log(`[Agent] Connecting to Relay Server at ${relayUrl}...`);
  ws = new WebSocket(`${relayUrl}/ws/agent`);

  ws.on('open', () => {
    console.log('[Agent] Connected to Relay Server.');
    reconnectDelay = 1000;
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    // 发送身份注册
    sendJSON(ws, {
      type: AGENT_REGISTER,
      deviceName,
      secret: relaySecret
    });
  });

  ws.on('message', async (data, isBinary) => {
    if (isBinary) {
      handleBinaryFrame(data);
      return;
    }

    let msg;
    try {
      msg = JSON.parse(data.toString('utf8'));
    } catch {
      return;
    }

    switch (msg.type) {
      case REGISTERED:
        console.log(`[Agent] Device registered successfully with ID: ${msg.deviceId}`);
        // 清理所有历史连接，重建干净状态
        cleanupConnections();
        break;

      case HTTP_REQUEST:
        handleHttpRequest(msg);
        break;

      case WS_CONNECT:
        handleWsConnect(msg);
        break;

      case WS_CLOSE:
        handleWsClose(msg);
        break;

      case 'p2p-offer':
        p2p.handleOffer(msg);
        break;

      case 'p2p-ice':
        p2p.handleIce(msg);
        break;

      case ERROR:
        console.error(`[Agent] Received error from relay server: ${msg.message}`);
        break;

      default:
        console.warn(`[Agent] Unknown control message type: ${msg.type}`);
    }
  });

  ws.on('close', () => {
    console.log('[Agent] Relay Server connection closed.');
    cleanupConnections();
    scheduleReconnect();
  });

  ws.on('error', (err) => {
    console.error('[Agent] WebSocket connection error:', err.message);
    ws.close();
  });
}

function getAgentWs() {
  return ws;
}

// --- HTTP Tunneling Handler (In-Memory Processing) ---
function handleHttpRequest(msg, transport = {
  sendJSON: (m) => sendJSON(getAgentWs(), m),
  sendBinary: (f) => sendBinary(getAgentWs(), f)
}) {
  const { requestId, method, path } = msg;

  if (method === 'GET' && path === '/api/sessions') {
    const jsonPayload = JSON.stringify(sessions.list());
    sendMemoryResponse(requestId, 200, jsonPayload, transport);
    return;
  }

  if (method === 'POST' && path === '/api/sessions') {
    let name, cwd;
    if (msg.body) {
      try {
        const bodyPayload = msg.bodyEncoding === 'base64'
          ? Buffer.from(msg.body, 'base64')
          : Buffer.from(msg.body, 'utf8');
        const data = JSON.parse(bodyPayload.toString('utf8'));
        name = data.name;
        cwd = data.cwd;
      } catch (err) {
        console.error('[Agent API] Error parsing post body:', err.message);
      }
    }
    try {
      const session = sessions.create({ name, cwd });
      const jsonPayload = JSON.stringify(session.info());
      sendMemoryResponse(requestId, 201, jsonPayload, transport);
    } catch (err) {
      sendMemoryError(requestId, 400, err.message, transport);
    }
    return;
  }

  const sessionMatch = path.match(/^\/api\/sessions\/([^/]+)$/);
  if (sessionMatch) {
    const sessionId = decodeURIComponent(sessionMatch[1]);
    
    if (method === 'PATCH') {
      let name;
      if (msg.body) {
        try {
          const bodyPayload = msg.bodyEncoding === 'base64'
            ? Buffer.from(msg.body, 'base64')
            : Buffer.from(msg.body, 'utf8');
          name = JSON.parse(bodyPayload.toString('utf8')).name;
        } catch (err) {
          console.error('[Agent API] Error parsing patch body:', err.message);
        }
      }
      const session = sessions.rename(sessionId, name);
      if (!session) {
        sendMemoryError(requestId, 404, 'session not found', transport);
      } else {
        const jsonPayload = JSON.stringify(session.info());
        sendMemoryResponse(requestId, 200, jsonPayload, transport);
      }
      return;
    }

    if (method === 'DELETE') {
      if (!sessions.close(sessionId)) {
        sendMemoryError(requestId, 404, 'session not found', transport);
      } else {
        sendMemoryResponse(requestId, 204, '', transport);
      }
      return;
    }
  }

  sendMemoryError(requestId, 404, 'Not Found', transport);
}

function sendMemoryResponse(requestId, statusCode, jsonPayload, transport) {
  const buf = Buffer.from(jsonPayload, 'utf8');
  transport.sendJSON({
    type: HTTP_RESPONSE,
    requestId,
    statusCode,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'content-length': String(buf.length)
    },
    bodyEncoding: 'base64',
    body: buf.toString('base64'),
    hasChunks: false
  });
}

function sendMemoryError(requestId, statusCode, message, transport) {
  transport.sendJSON({
    type: HTTP_ERROR,
    requestId,
    error: statusCode === 404 ? 'not_found' : 'failed',
    message: message
  });
}

// --- WebSocket Tunneling Handler (Virtual Socket Connection) ---
function handleWsConnect(msg, transport = {
  sendJSON: (m) => sendJSON(getAgentWs(), m),
  sendBinary: (f) => sendBinary(getAgentWs(), f)
}) {
  const { tunnelConnectionId, path } = msg;

  const match = path.match(/^\/ws\/sessions\/([^/]+)$/);
  if (!match) {
    transport.sendJSON({
      type: WS_ERROR,
      tunnelConnectionId,
      code: 404,
      message: 'Session path match failed'
    });
    return;
  }

  const sessionId = decodeURIComponent(match[1]);
  const session = sessions.get(sessionId);
  if (!session) {
    transport.sendJSON({
      type: WS_ERROR,
      tunnelConnectionId,
      code: 404,
      message: `Session ${sessionId} not found`
    });
    return;
  }

  // 建立 VirtualSocket
  const virtualSocket = new VirtualSocket(tunnelConnectionId, transport, () => {
    virtualSockets.delete(tunnelConnectionId);
  });
  virtualSockets.set(tunnelConnectionId, virtualSocket);

  // 通知对端 WS 连接建立成功
  transport.sendJSON({
    type: WS_CONNECTED,
    tunnelConnectionId
  });

  // attach 到内存 session
  session.attach(virtualSocket, {
    protocolHint: 'binary'
  });
}

function handleWsClose(msg) {
  const { tunnelConnectionId } = msg;
  const virtualSocket = virtualSockets.get(tunnelConnectionId);
  if (virtualSocket) {
    virtualSocket.close(1000, 'Closed by relay server');
    virtualSockets.delete(tunnelConnectionId);
  }
}

// --- Binary Stream Demuxer (to virtualSockets) ---
function handleBinaryFrame(data) {
  const frame = decodeTunnelFrame(data);
  if (!frame) return;

  const { msgType, id, extraByte, payload } = frame;

  if (msgType === MSG_TYPE_WS_DATA) {
    const virtualSocket = virtualSockets.get(id);
    if (virtualSocket && virtualSocket.readyState === 1) {
      const isBinary = (extraByte === WS_DATA_BINARY);
      virtualSocket.emitMessage(payload, isBinary);
    }
  }
}

// --- P2P DataChannel Message Delegation ---
function handleP2pMessage(parsed, transport) {
  if (parsed.type === HTTP_REQUEST) {
    handleHttpRequest(parsed, transport);
  } else if (parsed.type === WS_CONNECT) {
    handleWsConnect(parsed, transport);
  } else if (parsed.type === WS_CLOSE) {
    handleWsClose(parsed);
  }
}

// --- Lifecycle Helpers ---
function cleanupConnections() {
  for (const virtualSocket of virtualSockets.values()) {
    try { virtualSocket.close(1001, 'PC Agent offline'); } catch {}
  }
  virtualSockets.clear();
  if (p2p) p2p.cleanup();
}

function scheduleReconnect() {
  if (reconnectTimer) return;
  console.log(`[Agent] Reconnecting in ${reconnectDelay}ms...`);
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connectToRelay();
    reconnectDelay = Math.min(10000, reconnectDelay * 2);
  }, reconnectDelay);
}
