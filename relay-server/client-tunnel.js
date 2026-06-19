// Client WebSocket tunnel handler for the relay server.
// Manages the lifecycle of client→agent WS tunnels, including buffering
// messages before the tunnel is connected.

import {
  WS_CONNECT, WS_CONNECTED, WS_ERROR, WS_CLOSE,
  MSG_TYPE_WS_DATA,
  WS_DATA_TEXT, WS_DATA_BINARY,
  encodeTunnelFrame,
  sendJSON, sendBinary
} from '../shared/tunnel-protocol.js';

export function createClientWsTunnel(registry, activeWsTunnels, clientWs, agent, targetPath, req) {
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

// Handle agent→client tunnel control messages (ws-connected, ws-error, ws-close)
export function handleAgentTunnelMessage(msg, registry, activeWsTunnels) {
  const tunnel = activeWsTunnels.get(msg.tunnelConnectionId);
  if (!tunnel) return;

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
