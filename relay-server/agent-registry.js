// Agent registry — manages online PC agents and manager (lobby) clients.

export function getDeviceNameFromUa(ua) {
  if (!ua || ua === 'Browser') return 'Web Browser';
  let os = 'Unknown OS';
  if (ua.includes('Windows NT')) os = 'Windows';
  else if (ua.includes('Macintosh') || ua.includes('Mac OS X')) os = 'macOS';
  else if (ua.includes('Linux')) os = 'Linux';
  else if (ua.includes('Android')) os = 'Android';
  else if (ua.includes('iPhone') || ua.includes('iPad')) os = 'iOS';

  let browser = 'Browser';
  if (ua.includes('Edg/')) browser = 'Edge';
  else if (ua.includes('Chrome/')) browser = 'Chrome';
  else if (ua.includes('Safari/')) browser = 'Safari';
  else if (ua.includes('Firefox/')) browser = 'Firefox';

  return `${browser} / ${os}`;
}

const agents = new Map(); // deviceId -> { ws, deviceId, deviceName, userId, username, lastSeenDbUpdated }
const managerClients = new Map(); // ws -> { userId, username, clientId }

export function getAgentForUser(userId, deviceId) {
  if (deviceId) {
    const agent = agents.get(deviceId);
    if (agent && agent.userId === userId && agent.ws.readyState === 1) {
      return agent;
    }
    return null;
  }
  const userAgents = Array.from(agents.values()).filter(a => a.userId === userId && a.ws.readyState === 1);
  if (userAgents.length === 1) return userAgents[0];
  return null;
}

export function pushDevicesToUser(userId, sendJSON) {
  const userDevices = [];
  for (const agent of agents.values()) {
    if (agent.userId === userId && agent.ws.readyState === 1) {
      userDevices.push({
        deviceId: agent.deviceId,
        deviceName: agent.deviceName,
        status: 'online'
      });
    }
  }
  for (const [ws, info] of managerClients.entries()) {
    if (info.userId === userId && ws.readyState === 1) {
      sendJSON(ws, {
        type: 'devices',
        devices: userDevices
      });
    }
  }
}

export function registerAgent(deviceId, entry) {
  agents.set(deviceId, entry);
}

export function removeAgent(deviceId) {
  agents.delete(deviceId);
}

export function getAgent(deviceId) {
  return agents.get(deviceId);
}

export function getAgentValues() {
  return agents.values();
}

export function addManagerClient(ws, info) {
  managerClients.set(ws, info);
}

export function removeManagerClient(ws) {
  managerClients.delete(ws);
}

export function getManagerClients() {
  return managerClients.entries();
}

// Cleanup all tunnels/resources for a disconnected agent
export function cleanupAgentState(deviceId, {
  activeWsTunnels, pendingHttpResponses, pendingP2pOffers,
  text
}) {
  activeWsTunnels.forEach((tunnel, tunnelId) => {
    if (tunnel.deviceId === deviceId) {
      tunnel.clientWs.close(4000, 'PC Agent offline');
      activeWsTunnels.delete(tunnelId);
    }
  });

  pendingHttpResponses.forEach((pending, reqId) => {
    if (pending.deviceId === deviceId) {
      clearTimeout(pending.timer);
      text(pending.res, 503, '目标 PC Agent 离线，请先在电脑端启动 PC Agent。');
      pendingHttpResponses.delete(reqId);
    }
  });

  pendingP2pOffers.forEach((pending, clientId) => {
    if (pending.deviceId === deviceId) {
      clearTimeout(pending.timer);
      text(pending.res, 503, '目标 PC Agent 离线，请先在电脑端启动 PC Agent。');
      pendingP2pOffers.delete(clientId);
    }
  });
}
