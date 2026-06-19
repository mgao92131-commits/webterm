// WebSocket handler logic for the relay server.
// All external dependencies are injected via context to avoid circular imports.

import crypto from 'node:crypto';
import { json } from '../server/http-utils.js';
import {
  AGENT_REGISTER, REGISTERED, ERROR,
  HTTP_REQUEST, HTTP_RESPONSE, HTTP_ERROR,
  WS_CONNECT, WS_CONNECTED, WS_ERROR, WS_CLOSE,
  MSG_TYPE_WS_DATA, MSG_TYPE_HTTP_CHUNK,
  WS_DATA_TEXT, WS_DATA_BINARY,
  HTTP_CHUNK_DATA, HTTP_CHUNK_FIN,
  encodeTunnelFrame, decodeTunnelFrame,
  sendJSON, sendBinary
} from '../shared/tunnel-protocol.js';

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
        const pending = pendingHttpResponses.get(msg.requestId);
        if (pending) {
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
        return;
      }

      if (msg.tunnelConnectionId) {
        const tunnel = activeWsTunnels.get(msg.tunnelConnectionId);
        if (tunnel) {
          if (msg.type === WS_CONNECTED) {
            if (tunnel.isConnected) return;
            tunnel.isConnected = true;
            const currentAgent = registry.getAgent(tunnel.deviceId);
            if (currentAgent && currentAgent.ws.readyState === 1) {
              for (const item of tunnel.queue) {
                const typeByte = item.clientIsBinary ? WS_DATA_BINARY : WS_DATA_TEXT;
                const frame = encodeTunnelFrame(MSG_TYPE_WS_DATA, msg.tunnelConnectionId, typeByte, item.clientData);
                sendBinary(currentAgent.ws, frame);
              }
            }
            tunnel.queue = [];
          } else if (msg.type === WS_ERROR || msg.type === WS_CLOSE) {
            tunnel.clientWs.close(msg.code || 1000, String(msg.message || msg.reason || ''));
            activeWsTunnels.delete(msg.tunnelConnectionId);
          }
        }
      }
    });

    ws.on('close', () => {
      if (registeredDeviceId) {
        console.log(`[Relay] Agent disconnected: ${registeredDeviceId}`);
        registry.removeAgent(registeredDeviceId);
        if (registeredUserId) registry.pushDevicesToUser(registeredUserId, sendJSON);

        activeWsTunnels.forEach((tunnel, tunnelId) => {
          if (tunnel.deviceId === registeredDeviceId) {
            tunnel.clientWs.close(4000, 'PC Agent offline');
            activeWsTunnels.delete(tunnelId);
          }
        });
        pendingHttpResponses.forEach((pending, reqId) => {
          if (pending.deviceId === registeredDeviceId) {
            clearTimeout(pending.timer);
            text(pending.res, 503, '目标 PC Agent 离线，请先在电脑端启动 PC Agent。');
            pendingHttpResponses.delete(reqId);
          }
        });
        pendingP2pOffers.forEach((pending, clientId) => {
          if (pending.deviceId === registeredDeviceId) {
            clearTimeout(pending.timer);
            text(pending.res, 503, '目标 PC Agent 离线，请先在电脑端启动 PC Agent。');
            pendingP2pOffers.delete(clientId);
          }
        });
      }
    });

    ws.on('error', () => { ws.close(); });
  }

  function handleClientWsTunnel(clientWs, agent, targetPath, req) {
    const tunnelConnectionId = 'tc_' + Math.random().toString(36).substring(2, 15);
    const queue = [];

    activeWsTunnels.set(tunnelConnectionId, {
      clientWs, deviceId: agent.deviceId, isConnected: false, queue
    });

    const relayHeaders = { ...req.headers };
    sendJSON(agent.ws, {
      type: WS_CONNECT, tunnelConnectionId, path: targetPath,
      headers: relayHeaders,
      protocols: req.headers['sec-websocket-protocol']
        ? req.headers['sec-websocket-protocol'].split(',').map(s => s.trim())
        : []
    });

    clientWs.on('message', (clientData, clientIsBinary) => {
      const tunnel = activeWsTunnels.get(tunnelConnectionId);
      if (!tunnel) return;
      if (!tunnel.isConnected) {
        if (tunnel.queue.length >= 256) {
          clientWs.close(1013, 'Too many pending messages');
          activeWsTunnels.delete(tunnelConnectionId);
          return;
        }
        tunnel.queue.push({ clientData, clientIsBinary });
        return;
      }
      const currentAgent = registry.getAgent(tunnel.deviceId);
      if (currentAgent && currentAgent.ws.readyState === 1) {
        const typeByte = clientIsBinary ? WS_DATA_BINARY : WS_DATA_TEXT;
        const frame = encodeTunnelFrame(MSG_TYPE_WS_DATA, tunnelConnectionId, typeByte, clientData);
        sendBinary(currentAgent.ws, frame);
      }
    });

    clientWs.on('close', (code, reason) => {
      if (activeWsTunnels.has(tunnelConnectionId)) {
        activeWsTunnels.delete(tunnelConnectionId);
        const currentAgent = registry.getAgent(agent.deviceId);
        if (currentAgent && currentAgent.ws.readyState === 1) {
          sendJSON(currentAgent.ws, {
            type: WS_CLOSE, tunnelConnectionId, code,
            reason: Buffer.isBuffer(reason) ? reason.toString('utf8') : String(reason || '')
          });
        }
      }
    });

    clientWs.on('error', () => { clientWs.close(); });
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

  return { handleAgentConnection, handleClientWsTunnel, handleBinaryDemux };
}
