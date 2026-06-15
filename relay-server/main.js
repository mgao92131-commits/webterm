import http from 'node:http';
import path from 'node:path';
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';
import { AuthManager, setAuthCookie } from '../server/auth.js';
import { serveStatic, json, text, readJSON } from '../server/http-utils.js';
import {
  AGENT_REGISTER, REGISTERED, CONNECT_REQUEST, CONNECT_ACCEPT, CONNECT_REJECT,
  CLIENT_PAIRED, CLIENT_UNPAIRED,
  LIST_SESSIONS, CREATE_SESSION, CLOSE_SESSION, RENAME_SESSION,
  SESSIONS, SESSION_UPDATE, SESSION_CLOSED, SESSION_CREATED,
  LIST_DEVICES, CONNECT_DEVICE, DEVICES, DEVICE_CONNECTED, DEVICE_DISCONNECTED,
  ERROR,
  encodeRelayFrame, decodeRelayFrame,
  sendJSON, sendBinary
} from '../shared/relay-protocol.js';

loadLocalEnv();

const usersJson = process.env.RELAY_USERS;
let users = [];
if (usersJson) {
  try {
    users = JSON.parse(usersJson);
  } catch (err) {
    console.error('Failed to parse RELAY_USERS:', err.message);
    process.exit(1);
  }
} else {
  const password = process.env.RELAY_PASSWORD;
  const username = process.env.RELAY_USER || 'admin';
  const agentSecret = process.env.RELAY_SECRET;
  if (!password || !agentSecret) {
    console.error('Either RELAY_USERS or (RELAY_PASSWORD and RELAY_SECRET) must be set');
    process.exit(1);
  }
  users = [{ username, password, agentSecret }];
}

// 检查 agentSecret 的唯一性
const secrets = new Set();
for (const u of users) {
  if (u.agentSecret) {
    if (secrets.has(u.agentSecret)) {
      console.error(`Duplicate agentSecret config found for user ${u.username}. All agentSecrets must be unique.`);
      process.exit(1);
    }
    secrets.add(u.agentSecret);
  }
}

const port = Number(process.env.RELAY_PORT || '9000');

// 复用官方 Web 的认证机制与静态资源目录（支持多用户）
const auth = new AuthManager({ users });
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'web');

// 内存状态
const agents = new Map(); // deviceId -> { ws, deviceId, deviceName, username }
const pendingRequests = new Map(); // requestId -> { clientId, deviceId, timer, onAccept, onReject }
const pendingAgentActions = new Map(); // actionId -> { resolve, reject, timer }
const sessionClientsMap = new Map(); // globalSessionId -> Set of Client WebSocket

// 控制通道客户端映射：ws -> { username, clientId }
const managerClients = new Map();

// 客户端与设备的配对记录：clientId -> deviceId
const clientPairings = new Map();

// 正在配对的 Promise 缓存：pairingKey (clientId + '_' + deviceId) -> Promise
const pendingPairingPromises = new Map();

let nextDeviceId = 1;
let nextRequestId = 1;
let nextActionId = 1;

// 广播给某个用户名下的所有控制通道客户端
function broadcastToUser(username, message, excludeClientId = null) {
  for (const [ws, info] of managerClients.entries()) {
    if (info.username === username && ws.readyState === 1) {
      if (excludeClientId && info.clientId === excludeClientId) continue;
      sendJSON(ws, message);
    }
  }
}

// 发送给特定的客户端
function sendJSONToClient(clientId, message) {
  for (const [ws, info] of managerClients.entries()) {
    if (info.clientId === clientId && ws.readyState === 1) {
      sendJSON(ws, message);
      return true;
    }
  }
  return false;
}

// 推送设备列表给指定用户
function pushDevicesToUser(username) {
  const userDevices = [];
  for (const agent of agents.values()) {
    if (agent.username === username && agent.ws.readyState === 1) {
      userDevices.push({
        deviceId: agent.deviceId,
        deviceName: agent.deviceName,
        status: 'online'
      });
    }
  }
  broadcastToUser(username, {
    type: 'devices',
    devices: userDevices
  });
}

// 获取配对的 Agent
function getPairedAgent(username, clientId, headerDeviceId) {
  if (headerDeviceId) {
    const agent = agents.get(headerDeviceId);
    if (agent && agent.username === username && agent.ws.readyState === 1) {
      return agent;
    }
    return null;
  }
  
  if (clientId) {
    const pairedId = clientPairings.get(clientId);
    if (pairedId) {
      const agent = agents.get(pairedId);
      if (agent && agent.username === username && agent.ws.readyState === 1) {
        return agent;
      }
    }
  }
  
  // 自动配对：若该用户仅有一个在线设备，自动绑定它
  const userAgents = Array.from(agents.values()).filter(a => a.username === username && a.ws.readyState === 1);
  if (userAgents.length === 1) {
    const agent = userAgents[0];
    if (clientId) {
      clientPairings.set(clientId, agent.deviceId);
    }
    return agent;
  }
  
  return null;
}

// 确保已成功配对 PC，返回在线的 Agent
function ensurePaired(username, clientId, headerDeviceId) {
  const agent = getPairedAgent(username, clientId, headerDeviceId);
  if (agent) {
    return Promise.resolve(agent);
  }

  const userAgents = Array.from(agents.values()).filter(a => a.username === username && a.ws.readyState === 1);
  if (userAgents.length === 0) {
    return Promise.reject(new Error('PC Agent 离线，请先在电脑端启动 PC Agent。'));
  }

  const targetDeviceId = headerDeviceId || clientPairings.get(clientId);
  if (!targetDeviceId) {
    return Promise.reject(new Error('您有多台电脑在线，请在界面上选择要连接的设备。'));
  }

  const targetAgent = agents.get(targetDeviceId);
  if (!targetAgent || targetAgent.username !== username || targetAgent.ws.readyState !== 1) {
    return Promise.reject(new Error('指定的设备离线或不可用。'));
  }

  const pairingKey = `${clientId}_${targetDeviceId}`;
  let pPromise = pendingPairingPromises.get(pairingKey);
  if (pPromise) return pPromise;

  pPromise = new Promise((resolve, reject) => {
    const requestId = 'r_' + nextRequestId++;
    const timer = setTimeout(() => {
      pendingRequests.delete(requestId);
      pendingPairingPromises.delete(pairingKey);
      reject(new Error('配对请求超时，请及时在电脑屏幕上点击 Allow 确认。'));
    }, 30000);

    pendingRequests.set(requestId, {
      clientId,
      deviceId: targetDeviceId,
      timer,
      onAccept: () => {
        pendingPairingPromises.delete(pairingKey);
        clientPairings.set(clientId, targetDeviceId);
        
        // 绑定成功后，通知 Agent 配对成立以开启数据通道订阅
        sendJSON(targetAgent.ws, { type: CLIENT_PAIRED, clientId });
        resolve(targetAgent);

        // 广播连接成功
        broadcastToUser(username, {
          type: 'device-connected',
          deviceId: targetDeviceId,
          deviceName: targetAgent.deviceName
        });

        // 主动拉取会话列表
        sendAgentAction(targetAgent, { type: LIST_SESSIONS })
          .then(sessions => {
            const globalSessions = sessions.map(s => ({ ...s, id: `${targetDeviceId}:${s.id}` }));
            sendJSONToClient(clientId, {
              type: 'sessions',
              data: globalSessions
            });
          })
          .catch(err => {
            console.warn('Failed to broadcast sessions after pairing:', err.message);
          });
      },
      onReject: () => {
        pendingPairingPromises.delete(pairingKey);
        reject(new Error('配对请求被电脑端拒绝。'));
        
        sendJSONToClient(clientId, {
          type: 'device-rejected',
          deviceId: targetDeviceId,
          message: '连接被电脑端拒绝'
        });
      }
    });

    console.log(`Pairing triggered: sending CONNECT_REQUEST (${requestId}) to agent ${targetDeviceId}`);
    sendJSON(targetAgent.ws, {
      type: CONNECT_REQUEST,
      requestId,
      clientInfo: `网页/App 客户端 (${username})`
    });
  });

  pendingPairingPromises.set(pairingKey, pPromise);
  return pPromise;
}

// 发送指令给 Agent 并等待回复（异步 WS 转同步 Promise）
function sendAgentAction(agent, action) {
  return new Promise((resolve, reject) => {
    const actionId = nextActionId++;
    const timer = setTimeout(() => {
      pendingAgentActions.delete(actionId);
      reject(new Error('等待 PC Agent 响应超时'));
    }, 8000);

    pendingAgentActions.set(actionId, { resolve, reject, timer });
    sendJSON(agent.ws, { ...action, actionId });
  });
}

// ---------------------------------------------------------------------------
// HTTP 路由处理
// ---------------------------------------------------------------------------
const server = http.createServer((req, res) => {
  route(req, res).catch((err) => {
    console.error(err);
    text(res, 500, 'internal server error');
  });
});

async function route(req, res) {
  const url = new URL(req.url, `http://${req.headers.host}`);

  // 1. 登录路由
  if (req.method === 'POST' && url.pathname === '/api/login') {
    const body = await readJSON(req);
    if (!auth.verify(body.username, body.password, req.socket.remoteAddress)) {
      await delay(auth.failureDelay(req.socket.remoteAddress));
      text(res, 401, 'invalid credentials');
      return;
    }
    setAuthCookie(res, auth.token(body.username), req.socket.encrypted || process.env.WEBTERM_COOKIE_SECURE === '1');
    json(res, 200, { username: body.username });
    return;
  }

  // 2. 身份状态获取
  if (req.method === 'GET' && url.pathname === '/api/me') {
    const username = auth.authenticated(req);
    if (!username) {
      text(res, 401, 'unauthorized');
      return;
    }
    json(res, 200, { username });
    return;
  }

  // 3. API 转发区 (需要通过 auth 校验且依赖 PC Agent 的响应)
  if (url.pathname.startsWith('/api/')) {
    const username = auth.authenticated(req);
    if (!username) {
      text(res, 401, 'unauthorized');
      return;
    }

    const headerDeviceId = req.headers['x-device-id'];
    const reqClientId = req.headers['x-client-id'] || `http_${username}`;

    try {
      // 获取当前会话列表
      if (req.method === 'GET' && url.pathname === '/api/sessions') {
        const agent = getPairedAgent(username, reqClientId, headerDeviceId);
        if (!agent) {
          // 触发一次后台异步配对申请
          ensurePaired(username, reqClientId, headerDeviceId).catch(err => console.log('Auto pairing bg check:', err.message));
          json(res, 200, []);
          return;
        }

        try {
          const sessions = await sendAgentAction(agent, { type: LIST_SESSIONS });
          const globalSessions = sessions.map(s => ({ ...s, id: `${agent.deviceId}:${s.id}` }));
          json(res, 200, globalSessions);
        } catch (err) {
          json(res, 200, []);
        }
        return;
      }

      // 新建终端会话
      if (req.method === 'POST' && url.pathname === '/api/sessions') {
        const body = await readJSON(req);
        const agent = await ensurePaired(username, reqClientId, headerDeviceId);
        const session = await sendAgentAction(agent, { type: CREATE_SESSION, name: body.name, cwd: body.cwd });
        const globalSession = { ...session, id: `${agent.deviceId}:${session.id}` };
        json(res, 201, globalSession);
        return;
      }

      // 重命名终端会话
      const sessionMatch = url.pathname.match(/^\/api\/sessions\/([^/]+)$/);
      if (sessionMatch && req.method === 'PATCH') {
        const body = await readJSON(req);
        const globalSessionId = decodeURIComponent(sessionMatch[1]);
        const [targetDeviceId, localSessionId] = globalSessionId.split(':');
        if (!targetDeviceId || !localSessionId) {
          text(res, 400, 'invalid session id');
          return;
        }
        const agent = agents.get(targetDeviceId);
        if (!agent || agent.username !== username || agent.ws.readyState !== 1) {
          text(res, 503, 'PC Agent offline');
          return;
        }
        const session = await sendAgentAction(agent, {
          type: RENAME_SESSION,
          sessionId: localSessionId,
          name: body.name
        });
        const globalSession = { ...session, id: `${agent.deviceId}:${session.id}` };
        json(res, 200, globalSession);
        return;
      }

      // 杀死/关闭终端会话
      if (sessionMatch && req.method === 'DELETE') {
        const globalSessionId = decodeURIComponent(sessionMatch[1]);
        const [targetDeviceId, localSessionId] = globalSessionId.split(':');
        if (!targetDeviceId || !localSessionId) {
          text(res, 400, 'invalid session id');
          return;
        }
        const agent = agents.get(targetDeviceId);
        if (!agent || agent.username !== username || agent.ws.readyState !== 1) {
          text(res, 503, 'PC Agent offline');
          return;
        }
        await sendAgentAction(agent, {
          type: CLOSE_SESSION,
          sessionId: localSessionId
        });
        res.writeHead(204, { 'Cache-Control': 'no-store' });
        res.end();
        return;
      }

    } catch (err) {
      console.warn('API Proxy Error:', err.message);
      text(res, 503, err.message);
      return;
    }

    text(res, 404, 'not found');
    return;
  }

  // 4. 其它所有静态网页
  await serveStatic(req, res, root);
}

// ---------------------------------------------------------------------------
// WebSocket 升级处理
// ---------------------------------------------------------------------------
const wssAgent = new WebSocketServer({ noServer: true });
const wssManager = new WebSocketServer({ noServer: true }); // 控制通道 /ws/sessions
const wssSession = new WebSocketServer({ noServer: true }); // 数据通道 /ws/sessions/<sessionId>

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, `http://${req.headers.host}`);

  // A. 本地 PC Agent 通信注册端点
  if (url.pathname === '/ws/agent') {
    wssAgent.handleUpgrade(req, socket, head, (ws) => {
      handleAgentConnection(ws);
    });
    return;
  }

  // 网页端的请求，需要进行 auth Cookie 验证
  const username = auth.authenticated(req);
  if (!username) {
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    socket.destroy();
    return;
  }

  // B. 网页的会话管理控制 WebSocket
  if (url.pathname === '/ws/sessions') {
    wssManager.handleUpgrade(req, socket, head, (ws) => {
      handleManagerClientConnection(ws, username, url);
    });
    return;
  }

  // C. 网页的会话终端 PTY 传输 WebSocket
  const sessionMatch = url.pathname.match(/^\/ws\/sessions\/([^/]+)$/);
  if (sessionMatch) {
    const globalSessionId = decodeURIComponent(sessionMatch[1]);
    wssSession.handleUpgrade(req, socket, head, (ws) => {
      handleSessionClientConnection(ws, globalSessionId, username);
    });
    return;
  }

  socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
  socket.destroy();
});

// 处理 PC Agent 连接
function handleAgentConnection(ws) {
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

  let registeredDeviceId = null;

  ws.on('message', (data, isBinary) => {
    if (isBinary) {
      // 收到来自 PC Agent 的终端二进制输出流
      const relayFrame = decodeRelayFrame(data);
      if (relayFrame) {
        const { sessionId: localSessionId, terminalFrame } = relayFrame;
        const globalSessionId = `${registeredDeviceId}:${localSessionId}`;
        const clientsForSession = sessionClientsMap.get(globalSessionId);
        if (clientsForSession) {
          const type = terminalFrame[0];
          const payload = terminalFrame.subarray(1);

          for (const clientWs of clientsForSession) {
            if (clientWs.readyState !== 1) continue;

            if (clientWs.mode === 'binary') {
              sendBinary(clientWs, terminalFrame);
            } else {
              if (type === 0x02) { // MSG_OUTPUT
                if (payload.length >= 8) {
                  let seq = 0;
                  for (let i = 0; i < 8; i++) {
                    seq = seq * 256 + payload[i];
                  }
                  const textData = payload.subarray(8).toString('utf8');
                  sendJSON(clientWs, {
                    type: 'output',
                    seq,
                    data: textData
                  });
                }
              } else if (type === 0x08) { // MSG_PONG
                sendJSON(clientWs, { type: 'pong' });
              }
            }
          }
        }
      }
      return;
    }

    // 收到来自 PC Agent 的 JSON 控制面消息
    let msg;
    try {
      msg = JSON.parse(data.toString('utf8'));
    } catch {
      return;
    }

    // 处理 Agent 设备注册
    if (msg.type === AGENT_REGISTER) {
      let matchedUser = null;
      for (const u of auth.users.values()) {
        if (u.agentSecret === msg.secret) {
          matchedUser = u;
          break;
        }
      }
      if (!matchedUser) {
        sendJSON(ws, { type: ERROR, message: 'Invalid secret' });
        ws.close();
        return;
      }
      registeredDeviceId = 'd' + nextDeviceId++;
      agents.set(registeredDeviceId, {
        ws,
        deviceId: registeredDeviceId,
        deviceName: msg.deviceName || 'Unknown PC',
        username: matchedUser.username
      });
      console.log(`Agent registered: ${registeredDeviceId} (${msg.deviceName}) for user ${matchedUser.username}`);
      sendJSON(ws, { type: REGISTERED, deviceId: registeredDeviceId });
      pushDevicesToUser(matchedUser.username);
      return;
    }

    if (!registeredDeviceId) return;

    // 处理配对弹窗反馈 (来自 PC Agent 允许或拒绝)
    if (msg.type === CONNECT_ACCEPT || msg.type === CONNECT_REJECT) {
      const { requestId } = msg;
      const reqInfo = pendingRequests.get(requestId);
      if (reqInfo) {
        clearTimeout(reqInfo.timer);
        pendingRequests.delete(requestId);
        if (msg.type === CONNECT_ACCEPT) {
          reqInfo.onAccept();
        } else {
          reqInfo.onReject();
        }
      }
      return;
    }

    // 处理 API 同步命令的回应，解决 pending Agent action Promise
    if (msg.actionId) {
      const actionInfo = pendingAgentActions.get(msg.actionId);
      if (actionInfo) {
        clearTimeout(actionInfo.timer);
        pendingAgentActions.delete(msg.actionId);
        if (msg.type === ERROR) {
          actionInfo.reject(new Error(msg.message || 'PC Agent operation failed'));
        } else {
          if (msg.type === SESSIONS) {
            actionInfo.resolve(msg.sessions);
          } else if (msg.type === SESSION_CREATED) {
            actionInfo.resolve(msg.session);
          } else if (msg.type === SESSION_CLOSED) {
            actionInfo.resolve(msg.sessionId);
          } else if (msg.type === SESSION_UPDATE) {
            actionInfo.resolve(msg.session);
          } else {
            actionInfo.resolve(msg);
          }
        }
      }
      return;
    }

    // 收到 PC Agent 侧主动产生的广播消息（大厅的异步同步）
    if ([SESSION_CREATED, SESSION_CLOSED, SESSION_UPDATE].includes(msg.type)) {
      const agentInfo = agents.get(registeredDeviceId);
      if (!agentInfo) return;

      let webMsg = null;
      if (msg.type === SESSION_CREATED || msg.type === SESSION_UPDATE) {
        webMsg = {
          type: 'session',
          data: {
            ...msg.session,
            id: `${registeredDeviceId}:${msg.session.id}`
          }
        };
      } else if (msg.type === SESSION_CLOSED) {
        webMsg = {
          type: 'session-closed',
          id: `${registeredDeviceId}:${msg.sessionId}`
        };
      }
      
      if (webMsg) {
        for (const [clientWs, info] of managerClients.entries()) {
          if (info.username === agentInfo.username && clientWs.readyState === 1) {
            const pairedId = clientPairings.get(info.clientId);
            if (pairedId === registeredDeviceId) {
              sendJSON(clientWs, webMsg);
            }
          }
        }
      }
    }
  });

  ws.on('close', () => {
    if (registeredDeviceId) {
      console.log(`Agent disconnected: ${registeredDeviceId}`);
      const agentInfo = agents.get(registeredDeviceId);
      agents.delete(registeredDeviceId);

      if (agentInfo) {
        pushDevicesToUser(agentInfo.username);
        // 清理配对
        for (const [cId, dId] of clientPairings.entries()) {
          if (dId === registeredDeviceId) {
            clientPairings.delete(cId);
            sendJSONToClient(cId, { type: DEVICE_DISCONNECTED, deviceId: registeredDeviceId });
          }
        }
      }

      // 关闭该设备的数据连接
      sessionClientsMap.forEach((set, globalSessionId) => {
        if (globalSessionId.startsWith(registeredDeviceId + ':')) {
          set.forEach(c => c.close(1001, 'PC Agent offline'));
          sessionClientsMap.delete(globalSessionId);
        }
      });
    }
  });

  ws.on('error', () => {
    ws.close();
  });
}

// 网页/App 端控制通道长链接 (监听大厅会话与设备变化)
function handleManagerClientConnection(ws, username, url) {
  const clientId = url.searchParams.get('clientId') || 'c_' + Math.random().toString(36).substring(2, 15);
  managerClients.set(ws, { username, clientId });
  
  ws.on('close', () => { managerClients.delete(ws); });
  ws.on('error', () => { managerClients.delete(ws); ws.close(); });

  // 1. 立即向客户端推送设备列表
  pushDevicesToUser(username);

  // 2. 若有已有在线配对的设备，下发状态并拉取会话列表
  const prevDeviceId = clientPairings.get(clientId);
  if (prevDeviceId) {
    const agent = agents.get(prevDeviceId);
    if (agent && agent.username === username && agent.ws.readyState === 1) {
      sendJSON(ws, {
        type: 'device-connected',
        deviceId: prevDeviceId,
        deviceName: agent.deviceName
      });
      sendAgentAction(agent, { type: LIST_SESSIONS })
        .then(sessions => {
          const globalSessions = sessions.map(s => ({ ...s, id: `${prevDeviceId}:${s.id}` }));
          sendJSON(ws, { type: 'sessions', data: globalSessions });
        })
        .catch(err => {
          console.warn('Failed to pull sessions on manager reconnect:', err.message);
        });
    } else {
      clientPairings.delete(clientId);
    }
  }

  // 3. 处理客户端发过来的控制指令
  ws.on('message', async (data) => {
    let msg;
    try {
      msg = JSON.parse(data.toString('utf8'));
    } catch {
      return;
    }

    if (msg.type === CONNECT_DEVICE) {
      const { deviceId } = msg;
      ensurePaired(username, clientId, deviceId).catch(err => {
        sendJSON(ws, { type: 'error', message: err.message });
      });
    } else if (msg.type === LIST_DEVICES) {
      pushDevicesToUser(username);
    }
  });
}

// 网页/App 端终端二进制数据通道长链接 (PTY 数据流中转)
function handleSessionClientConnection(ws, globalSessionId, username) {
  ws.mode = 'json';

  const [targetDeviceId, localSessionId] = globalSessionId.split(':');
  if (!targetDeviceId || !localSessionId) {
    ws.close(1008, 'Invalid Session ID');
    return;
  }

  // 接收手机发来的终端二进制指令，穿上 localSessionId 封包后投递给 PC Agent
  ws.on('message', async (data, isBinary) => {
    try {
      const agent = agents.get(targetDeviceId);
      if (!agent || agent.username !== username || agent.ws.readyState !== 1) {
        ws.close(1001, 'PC Agent offline');
        return;
      }

      if (isBinary) {
        ws.mode = 'binary';
        const frame = encodeRelayFrame(localSessionId, data);
        sendBinary(agent.ws, frame);
        return;
      }

      let msg;
      try {
        msg = JSON.parse(data.toString('utf8'));
      } catch {
        return;
      }

      ws.mode = 'json';

      let terminalFrame = null;
      if (msg.type === 'hello') {
        const lastSeq = Number(msg.lastSeq || 0);
        const helloPayload = Buffer.from(JSON.stringify({ lastSeq }), 'utf8');
        terminalFrame = Buffer.concat([Buffer.from([0x04]), helloPayload]);
      } else if (msg.type === 'input') {
        const textBytes = Buffer.from(String(msg.data || ''), 'utf8');
        terminalFrame = Buffer.concat([Buffer.from([0x01]), textBytes]);
      } else if (msg.type === 'resize') {
        const resizePayload = Buffer.from(JSON.stringify({ cols: msg.cols, rows: msg.rows }), 'utf8');
        terminalFrame = Buffer.concat([Buffer.from([0x03]), resizePayload]);
      } else if (msg.type === 'ping') {
        terminalFrame = Buffer.from([0x07]);
      }

      if (terminalFrame) {
        const frame = encodeRelayFrame(localSessionId, terminalFrame);
        sendBinary(agent.ws, frame);
      }
    } catch (err) {
      console.warn('Binary proxy error:', err.message);
    }
  });

  let clientsForSession = sessionClientsMap.get(globalSessionId);
  if (!clientsForSession) {
    clientsForSession = new Set();
    sessionClientsMap.set(globalSessionId, clientsForSession);
  }
  clientsForSession.add(ws);

  ws.on('close', () => {
    const set = sessionClientsMap.get(globalSessionId);
    if (set) {
      set.delete(ws);
      if (set.size === 0) sessionClientsMap.delete(globalSessionId);
    }
  });

  ws.on('error', () => {
    ws.close();
  });
}

// 心跳周期检测
const interval = setInterval(() => {
  wssAgent.clients.forEach((ws) => {
    if (ws.isAlive === false) return ws.terminate();
    ws.isAlive = false;
    ws.ping();
  });
}, 30000);

server.on('close', () => {
  clearInterval(interval);
});

server.listen(port, () => {
  console.log(`WebTerm Relay Server listening on port ${port}`);
});

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

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

