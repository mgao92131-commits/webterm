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

export class Agent {
  constructor() {
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

    this.relayUrl = relayUrl;
    this.relaySecret = relaySecret;
    this.deviceName = process.env.DEVICE_NAME || os.hostname();
    this.ws = null;
    this.reconnectTimer = null;
    this.reconnectDelay = 1000;
    this.sessions = new SessionManager();
    this.virtualSockets = new Map();
    this.p2p = new P2PManager((m) => sendJSON(this.ws, m));
    this.p2p.setMessageHandlers({
      onTextMessage: (parsed, transport) => this.#handleP2pMessage(parsed, transport),
      onBinaryMessage: (data) => this.#handleBinaryFrame(data),
    });
  }

  start() {
    console.log(`[Agent] Initializing in-memory Terminal Session Manager...`);
    this.#connectToRelay();
  }

  // --- Relay Connection ---
  #connectToRelay() {
    console.log(`[Agent] Connecting to Relay Server at ${this.relayUrl}...`);
    this.ws = new WebSocket(`${this.relayUrl}/ws/agent`);

    this.ws.on('open', () => {
      console.log('[Agent] Connected to Relay Server.');
      this.reconnectDelay = 1000;
      if (this.reconnectTimer) {
        clearTimeout(this.reconnectTimer);
        this.reconnectTimer = null;
      }
      sendJSON(this.ws, {
        type: AGENT_REGISTER,
        deviceName: this.deviceName,
        secret: this.relaySecret
      });
    });

    this.ws.on('message', async (data, isBinary) => {
      if (isBinary) {
        this.#handleBinaryFrame(data);
        return;
      }

      let msg;
      try { msg = JSON.parse(data.toString('utf8')); } catch { return; }

      switch (msg.type) {
        case REGISTERED:
          console.log(`[Agent] Device registered successfully with ID: ${msg.deviceId}`);
          this.#cleanupConnections();
          break;
        case HTTP_REQUEST:
          this.#handleHttpRequest(msg);
          break;
        case WS_CONNECT:
          this.#handleWsConnect(msg);
          break;
        case WS_CLOSE:
          this.#handleWsClose(msg);
          break;
        case 'p2p-offer':
          this.p2p.handleOffer(msg);
          break;
        case 'p2p-ice':
          this.p2p.handleIce(msg);
          break;
        case ERROR:
          console.error(`[Agent] Received error from relay server: ${msg.message}`);
          break;
        default:
          console.warn(`[Agent] Unknown control message type: ${msg.type}`);
      }
    });

    this.ws.on('close', () => {
      console.log('[Agent] Relay Server connection closed.');
      this.#cleanupConnections();
      this.#scheduleReconnect();
    });

    this.ws.on('error', (err) => {
      console.error('[Agent] WebSocket connection error:', err.message);
      this.ws.close();
    });
  }

  // --- HTTP Tunneling ---
  #handleHttpRequest(msg, transport = this.#defaultTransport()) {
    const { requestId, method, path } = msg;

    if (method === 'GET' && path === '/api/sessions') {
      const jsonPayload = JSON.stringify(this.sessions.list());
      this.#sendMemoryResponse(requestId, 200, jsonPayload, transport);
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
        const session = this.sessions.create({ name, cwd });
        const jsonPayload = JSON.stringify(session.info());
        this.#sendMemoryResponse(requestId, 201, jsonPayload, transport);
      } catch (err) {
        this.#sendMemoryError(requestId, 400, err.message, transport);
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
        const session = this.sessions.rename(sessionId, name);
        if (!session) {
          this.#sendMemoryError(requestId, 404, 'session not found', transport);
        } else {
          const jsonPayload = JSON.stringify(session.info());
          this.#sendMemoryResponse(requestId, 200, jsonPayload, transport);
        }
        return;
      }

      if (method === 'DELETE') {
        if (!this.sessions.close(sessionId)) {
          this.#sendMemoryError(requestId, 404, 'session not found', transport);
        } else {
          this.#sendMemoryResponse(requestId, 204, '', transport);
        }
        return;
      }
    }

    this.#sendMemoryError(requestId, 404, 'Not Found', transport);
  }

  #sendMemoryResponse(requestId, statusCode, jsonPayload, transport) {
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

  #sendMemoryError(requestId, statusCode, message, transport) {
    transport.sendJSON({
      type: HTTP_ERROR,
      requestId,
      error: statusCode === 404 ? 'not_found' : 'failed',
      message: message
    });
  }

  // --- WebSocket Tunneling ---
  #handleWsConnect(msg, transport = this.#defaultTransport()) {
    const { tunnelConnectionId, path } = msg;

    const match = path.match(/^\/ws\/sessions\/([^/]+)$/);
    if (!match) {
      transport.sendJSON({ type: WS_ERROR, tunnelConnectionId, code: 404, message: 'Session path match failed' });
      return;
    }

    const sessionId = decodeURIComponent(match[1]);
    const session = this.sessions.get(sessionId);
    if (!session) {
      transport.sendJSON({ type: WS_ERROR, tunnelConnectionId, code: 404, message: `Session ${sessionId} not found` });
      return;
    }

    const virtualSocket = new VirtualSocket(tunnelConnectionId, transport, () => {
      this.virtualSockets.delete(tunnelConnectionId);
    });
    this.virtualSockets.set(tunnelConnectionId, virtualSocket);

    transport.sendJSON({ type: WS_CONNECTED, tunnelConnectionId });

    session.attach(virtualSocket, { protocolHint: 'binary' });
  }

  #handleWsClose(msg) {
    const { tunnelConnectionId } = msg;
    const virtualSocket = this.virtualSockets.get(tunnelConnectionId);
    if (virtualSocket) {
      virtualSocket.close(1000, 'Closed by relay server');
      this.virtualSockets.delete(tunnelConnectionId);
    }
  }

  // --- Binary Stream Demuxer ---
  #handleBinaryFrame(data) {
    const frame = decodeTunnelFrame(data);
    if (!frame) return;

    const { msgType, id, extraByte, payload } = frame;

    if (msgType === MSG_TYPE_WS_DATA) {
      const virtualSocket = this.virtualSockets.get(id);
      if (virtualSocket && virtualSocket.readyState === 1) {
        const isBinary = (extraByte === WS_DATA_BINARY);
        virtualSocket.emitMessage(payload, isBinary);
      }
    }
  }

  // --- P2P DataChannel Message Delegation ---
  #handleP2pMessage(parsed, transport) {
    if (parsed.type === HTTP_REQUEST) {
      this.#handleHttpRequest(parsed, transport);
    } else if (parsed.type === WS_CONNECT) {
      this.#handleWsConnect(parsed, transport);
    } else if (parsed.type === WS_CLOSE) {
      this.#handleWsClose(parsed);
    }
  }

  // --- Lifecycle ---
  #cleanupConnections() {
    for (const virtualSocket of this.virtualSockets.values()) {
      try { virtualSocket.close(1001, 'PC Agent offline'); } catch {}
    }
    this.virtualSockets.clear();
    if (this.p2p) this.p2p.cleanup();
  }

  #scheduleReconnect() {
    if (this.reconnectTimer) return;
    console.log(`[Agent] Reconnecting in ${this.reconnectDelay}ms...`);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.#connectToRelay();
      this.reconnectDelay = Math.min(10000, this.reconnectDelay * 2);
    }, this.reconnectDelay);
  }

  #defaultTransport() {
    return {
      sendJSON: (m) => sendJSON(this.ws, m),
      sendBinary: (f) => sendBinary(this.ws, f)
    };
  }
}
