// WebSocket handler logic for the relay server.
// All external dependencies are injected via context to avoid circular imports.

import crypto from 'node:crypto';
import { json } from '../server/http-utils.js';
import {
  AGENT_REGISTER, REGISTERED, ERROR,
  HTTP_RESPONSE, HTTP_ERROR,
  MSG_TYPE_WS_DATA, MSG_TYPE_HTTP_CHUNK,
  WS_DATA_TEXT, WS_DATA_BINARY,
  HTTP_CHUNK_DATA, HTTP_CHUNK_FIN,
  encodeTunnelFrame, decodeTunnelFrame,
  sendJSON, sendBinary
} from '../shared/tunnel-protocol.js';
import { createClientWsTunnel, handleAgentTunnelMessage } from './client-tunnel.js';

export function createWsHandlers(ctx) {
  const {
    auth, registry, pendingHttpResponses, activeWsTunnels,
    pendingP2pOffers, findBySecretHash, updateLastSeen,
    text
  } = ctx;

  function handleAgentConnection(ws) {
    ws.isAlive = true;
    ws.on('pong', () => { ws.isAlive = true; });

    let registeredDeviceId = null;
    let registeredUserId = null;

    ws.on('message', (data, isBinary) => {
      if (isBinary) {
        handleBinaryDemux(data);
        return;
      }

      let msg;
      try { msg = JSON.parse(data.toString('utf8')); } catch { return; }

      if (msg.type === AGENT_REGISTER) {
        const secret = msg.secret;
        if (!secret) {
          sendJSON(ws, { type: ERROR, message: 'Secret is missing' });
          ws.close();
          return;
        }
        const secretHash = crypto.createHash('sha256').update(secret).digest('hex');
        const device = findBySecretHash(secretHash);
        if (!device || device.disabled === 1) {
          sendJSON(ws, { type: ERROR, message: 'Invalid secret or user disabled' });
          ws.close();
          return;
        }
        registeredDeviceId = 'd' + device.id;
        registeredUserId = device.userId;
        registry.registerAgent(registeredDeviceId, {
          ws, deviceId: registeredDeviceId,
          deviceName: device.deviceName || msg.deviceName || 'Unknown PC',
          userId: device.userId, username: device.username,
          lastSeenDbUpdated: Date.now()
        });
        console.log(`[Relay] Agent registered: ${registeredDeviceId} (${device.deviceName}) for user ${device.username}`);
        updateLastSeen(device.id);
        sendJSON(ws, { type: REGISTERED, deviceId: registeredDeviceId });
        registry.pushDevicesToUser(device.userId, sendJSON);
        return;
      }

      if (!registeredDeviceId) return;

      if (msg.type === 'p2p-answer') {
        const pending = pendingP2pOffers.get(msg.to);
        if (pending) {
          clearTimeout(pending.timer);
          pendingP2pOffers.delete(msg.to);
          json(pending.res, 200, { sdp: msg.sdp, candidates: msg.candidates || [] });
        }
        return;
      }

      if (msg.type === 'p2p-ice') {
        for (const [clientWs, info] of registry.getManagerClients()) {
          if (info.clientId === msg.to && clientWs.readyState === 1) {
            sendJSON(clientWs, { type: 'p2p-ice', candidate: msg.candidate });
            break;
          }
        }
        return;
      }

      if (msg.requestId) {
        handleHttpResponse(msg, pendingHttpResponses);
        return;
      }

      if (msg.tunnelConnectionId) {
        handleAgentTunnelMessage(msg, registry, activeWsTunnels);
      }
    });

    ws.on('close', () => {
      if (registeredDeviceId) {
        console.log(`[Relay] Agent disconnected: ${registeredDeviceId}`);
        registry.removeAgent(registeredDeviceId);
        if (registeredUserId) registry.pushDevicesToUser(registeredUserId, sendJSON);
        registry.cleanupAgentState(registeredDeviceId, {
          activeWsTunnels, pendingHttpResponses, pendingP2pOffers, text
        });
      }
    });

    ws.on('error', () => { ws.close(); });
  }

  function handleBinaryDemux(data) {
    const frame = decodeTunnelFrame(data);
    if (!frame) return;
    const { msgType, id, extraByte, payload } = frame;

    if (msgType === MSG_TYPE_WS_DATA) {
      const tunnel = activeWsTunnels.get(id);
      if (tunnel && tunnel.clientWs.readyState === 1) {
        tunnel.clientWs.send(payload);
      }
    } else if (msgType === MSG_TYPE_HTTP_CHUNK) {
      const pending = pendingHttpResponses.get(id);
      if (pending) {
        if (extraByte === HTTP_CHUNK_DATA) {
          pending.res.write(payload);
        } else if (extraByte === HTTP_CHUNK_FIN) {
          pending.res.end();
          pendingHttpResponses.delete(id);
        }
      }
    }
  }

  // Bound client WS tunnel handler for use by main.js
  function handleClientWsTunnel(clientWs, agent, targetPath, req) {
    createClientWsTunnel(registry, activeWsTunnels, clientWs, agent, targetPath, req);
  }

  return { handleAgentConnection, handleClientWsTunnel, handleBinaryDemux };
}

function handleHttpResponse(msg, pendingHttpResponses) {
  const pending = pendingHttpResponses.get(msg.requestId);
  if (!pending) return;

  clearTimeout(pending.timer);
  pendingHttpResponses.delete(msg.requestId);

  if (msg.type === HTTP_RESPONSE) {
    if (!msg.hasChunks && msg.body) {
      let payload = msg.bodyEncoding === 'base64'
        ? Buffer.from(msg.body, 'base64')
        : Buffer.from(msg.body, 'utf8');
      const isGetSessions = (pending.method === 'GET' && pending.path === '/api/sessions');
      const isPostSessions = (pending.method === 'POST' && pending.path === '/api/sessions');
      if (isGetSessions || isPostSessions) {
        try {
          const jsonObj = JSON.parse(payload.toString('utf8'));
          if (isGetSessions && Array.isArray(jsonObj)) {
            for (const s of jsonObj) {
              if (s.id && !s.id.includes(':')) s.id = `${pending.deviceId}:${s.id}`;
            }
            payload = Buffer.from(JSON.stringify(jsonObj), 'utf8');
            msg.headers['content-length'] = String(payload.length);
          } else if (isPostSessions && jsonObj && jsonObj.id) {
            if (!jsonObj.id.includes(':')) jsonObj.id = `${pending.deviceId}:${jsonObj.id}`;
            payload = Buffer.from(JSON.stringify(jsonObj), 'utf8');
            msg.headers['content-length'] = String(payload.length);
          }
        } catch (err) {
          console.error('[Relay] Error rewriting sessions response:', err.message);
        }
      }
      pending.res.writeHead(msg.statusCode, msg.headers);
      pending.res.end(payload);
    } else {
      pending.res.writeHead(msg.statusCode, msg.headers);
      pending.res.end();
    }
  } else if (msg.type === HTTP_ERROR) {
    pending.res.writeHead(msg.error === 'timeout' ? 504 : 502, { 'content-type': 'text/plain; charset=utf-8' });
    pending.res.end(msg.message || 'PC Agent request failed');
  }
}
