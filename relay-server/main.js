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
  ERROR,
  encodeRelayFrame, decodeRelayFrame,
  sendJSON, sendBinary
} from '../shared/relay-protocol.js';

loadLocalEnv();

const relaySecret = process.env.RELAY_SECRET;
if (!relaySecret) {
  console.error('RELAY_SECRET must be set');
  process.exit(1);
}

const password = process.env.RELAY_PASSWORD;
if (!password) {
  console.error('RELAY_PASSWORD must be set');
  process.exit(1);
}

const username = process.env.RELAY_USER || 'admin';
const port = Number(process.env.RELAY_PORT || '9000');

// 复用官方 Web 的认证机制与静态资源目录
const auth = new AuthManager({ username, password });
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'web');

// 内存状态
const agents = new Map(); // deviceId -> { ws, deviceName, connectedClients: Set<clientId> }
const pendingRequests = new Map(); // requestId -> { clientId, deviceId, timer, onAccept, onReject }

// 原版 API 代理的请求队列（用于异步 WS 命令转同步 HTTP 响应）
const pendingAgentActions = new Map(); 

// 数据通道映射：sessionId -> Set of Client WebSocket
const sessionClientsMap = new Map();

// 控制通道客户端集合
const managerClients = new Set();

let nextDeviceId = 1;
let nextRequestId = 1;
let nextActionId = 1;

// 自动配对状态
let pairedAgent = null; 
let pairingPromise = null;

// 获取第一个可用的在线 Agent（自用单宿主模式）
function getActiveAgent() {
  if (agents.size === 0) return null;
  return Array.from(agents.values())[0];
}

// 确保已成功配对 PC，返回在线 of Agent，若未配对则自动拉起 PC 弹窗并等待
function ensurePaired() {
  if (pairedAgent && pairedAgent.ws.readyState === 1) {
    return Promise.resolve(pairedAgent);
  }

  const agent = getActiveAgent();
  if (!agent) {
    return Promise.reject(new Error('PC Agent 离线，请先在电脑端启动 PC Agent。'));
  }

  if (pairingPromise) {
    return pairingPromise;
  }

  pairingPromise = new Promise((resolve, reject) => {
    const requestId = 'r_auto_' + nextRequestId++;
    const timer = setTimeout(() => {
      pendingRequests.delete(requestId);
      pairingPromise = null;
      reject(new Error('配对请求超时，请及时在电脑屏幕上点击 Allow 确认。'));
    }, 30000);

    pendingRequests.set(requestId, {
      clientId: 'web_auto',
      deviceId: agent.deviceId,
      timer,
      onAccept: () => {
        pairingPromise = null;
        pairedAgent = agent;
        // 绑定成功后，告诉 Agent 配对成立以开启数据通道订阅
        sendJSON(agent.ws, { type: CLIENT_PAIRED, clientId: 'web_auto' });
        resolve(agent);

        // 核心：配对成功后，主动拉取会话列表，并通过控制通道广播给大厅，实现会话卡片的瞬时弹出！
        sendAgentAction(agent, { type: LIST_SESSIONS })
          .then(sessions => {
            const webMsg = {
              type: 'sessions',
              data: sessions
            };
            for (const clientWs of managerClients) {
              if (clientWs.readyState === 1) {
                sendJSON(clientWs, webMsg);
              }
            }
          })
          .catch(err => {
            console.warn('Failed to broadcast sessions after auto-pair:', err.message);
          });
      },
      onReject: () => {
        pairingPromise = null;
        reject(new Error('配对请求被电脑端拒绝。'));
        
        // 广播错误通知给控制通道客户端
        for (const clientWs of managerClients) {
          sendJSON(clientWs, { type: 'error', message: '连接被电脑端拒绝' });
        }
      }
    });

    console.log(`Auto pairing triggered: sending CONNECT_REQUEST (${requestId}) to agent ${agent.deviceId}`);
    sendJSON(agent.ws, {
      type: CONNECT_REQUEST,
      requestId,
      clientInfo: '手机网页/App客户端 (自动请求配对)'
    });
  });

  return pairingPromise;
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
// HTTP 路由处理 (兼容原版 node-server 所有的 REST API 接口)
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
    setAuthCookie(res, auth.token(), req.socket.encrypted || process.env.WEBTERM_COOKIE_SECURE === '1');
    json(res, 200, { username });
    return;
  }

  // 2. 身份状态获取
  if (req.method === 'GET' && url.pathname === '/api/me') {
    if (!auth.authenticated(req)) {
      text(res, 401, 'unauthorized');
      return;
    }
    json(res, 200, { username });
    return;
  }

  // 3. API 转发区 (需要通过 auth 校验且依赖 PC Agent 的响应)
  if (url.pathname.startsWith('/api/')) {
    if (!auth.authenticated(req)) {
      text(res, 401, 'unauthorized');
      return;
    }

    try {
      // 获取当前会话列表
      if (req.method === 'GET' && url.pathname === '/api/sessions') {
        // 如果没有配对成功，我们直接秒回空数组，以防止网页白屏挂起！
        if (!pairedAgent || pairedAgent.ws.readyState !== 1) {
          // 触发一次后台异步配对申请（这样用户进入页面后会看到大厅，同时电脑端弹窗）
          ensurePaired().catch(err => console.log('Auto pairing bg check:', err.message));
          json(res, 200, []);
          return;
        }

        try {
          const sessions = await sendAgentAction(pairedAgent, { type: LIST_SESSIONS });
          json(res, 200, sessions);
        } catch (err) {
          json(res, 200, []);
        }
        return;
      }

      // 新建终端会话
      if (req.method === 'POST' && url.pathname === '/api/sessions') {
        const body = await readJSON(req);
        const agent = await ensurePaired();
        const session = await sendAgentAction(agent, { type: CREATE_SESSION, name: body.name, cwd: body.cwd });
        json(res, 201, session);
        return;
      }

      // 重命名终端会话
      const sessionMatch = url.pathname.match(/^\/api\/sessions\/([^/]+)$/);
      if (sessionMatch && req.method === 'PATCH') {
        const body = await readJSON(req);
        const agent = await ensurePaired();
        const session = await sendAgentAction(agent, {
          type: RENAME_SESSION,
          sessionId: decodeURIComponent(sessionMatch[1]),
          name: body.name
        });
        json(res, 200, session);
        return;
      }

      // 杀死/关闭终端会话
      if (sessionMatch && req.method === 'DELETE') {
        const agent = await ensurePaired();
        await sendAgentAction(agent, {
          type: CLOSE_SESSION,
          sessionId: decodeURIComponent(sessionMatch[1])
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

  // 4. 其它所有非 API 请求，透明托管复用官方 Web 目录的静态网页
  await serveStatic(req, res, root);
}

// ---------------------------------------------------------------------------
// WebSocket 升级处理 (桥接原版数据流与中继数据流)
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

  // 网页端/安卓端的请求，需要进行 auth Cookie 验证
  if (!auth.authenticated(req)) {
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    socket.destroy();
    return;
  }

  // B. 网页/安卓的会话管理控制 WebSocket
  if (url.pathname === '/ws/sessions') {
    wssManager.handleUpgrade(req, socket, head, (ws) => {
      handleManagerClientConnection(ws);
    });
    return;
  }

  // C. 网页/安卓的会话终端 PTY 传输 WebSocket
  const sessionMatch = url.pathname.match(/^\/ws\/sessions\/([^/]+)$/);
  if (sessionMatch) {
    const sessionId = decodeURIComponent(sessionMatch[1]);
    wssSession.handleUpgrade(req, socket, head, (ws) => {
      handleSessionClientConnection(ws, sessionId);
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
      // 收到来自 PC Agent 的终端二进制输出流：
      // 用中继数据解包器拆解，剥掉外衣提取裸终端帧。
      const relayFrame = decodeRelayFrame(data);
      if (relayFrame) {
        const { sessionId, terminalFrame } = relayFrame;
        const clientsForSession = sessionClientsMap.get(sessionId);
        if (clientsForSession) {
          const type = terminalFrame[0];
          const payload = terminalFrame.subarray(1);

          for (const clientWs of clientsForSession) {
            if (clientWs.readyState !== 1) continue;

            if (clientWs.mode === 'binary') {
              // 1. 二进制客户端模式：直接转发剥壳后的二进制裸帧
              sendBinary(clientWs, terminalFrame);
            } else {
              // 2. JSON 客户端模式（如原装 web/app.js）：将输出帧转换为 JSON payload 并广播
              if (type === 0x02) { // MSG_OUTPUT
                // payload 前 8 字节为 uint64BE seq 序号，后面是真正的文字数据
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
      if (msg.secret !== relaySecret) {
        sendJSON(ws, { type: ERROR, message: 'Invalid secret' });
        ws.close();
        return;
      }
      registeredDeviceId = 'd' + nextDeviceId++;
      agents.set(registeredDeviceId, {
        ws,
        deviceId: registeredDeviceId,
        deviceName: msg.deviceName || 'Unknown PC',
        connectedClients: new Set()
      });
      console.log(`Agent registered: ${registeredDeviceId} (${msg.deviceName})`);
      sendJSON(ws, { type: REGISTERED, deviceId: registeredDeviceId });
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
      // 转换为原装 node-server 协议格式，广播发送给所有订阅控制通道的浏览器客户端
      let webMsg = null;
      if (msg.type === SESSION_CREATED || msg.type === SESSION_UPDATE) {
        webMsg = {
          type: 'session',
          data: msg.session
        };
      } else if (msg.type === SESSION_CLOSED) {
        webMsg = {
          type: 'session-closed',
          id: msg.sessionId
        };
      }
      
      if (webMsg) {
        for (const clientWs of managerClients) {
          if (clientWs.readyState === 1) {
            sendJSON(clientWs, webMsg);
          }
        }
      }
    }
  });

  ws.on('close', () => {
    if (registeredDeviceId) {
      console.log(`Agent disconnected: ${registeredDeviceId}`);
      agents.delete(registeredDeviceId);
      if (pairedAgent && pairedAgent.deviceId === registeredDeviceId) {
        pairedAgent = null;
        // 关闭所有已升级的数据连接以触发手机端重连
        sessionClientsMap.forEach(set => {
          set.forEach(c => c.close(1001, 'PC Agent offline'));
        });
        sessionClientsMap.clear();
      }
    }
  });

  ws.on('error', () => {
    ws.close();
  });
}

// 网页/App 端控制通道长链接 (监听大厅会话变化)
function handleManagerClientConnection(ws) {
  managerClients.add(ws);
  ws.on('close', () => { managerClients.delete(ws); });
  ws.on('error', () => { managerClients.delete(ws); ws.close(); });
}

// 网页/App 端终端二进制数据通道长链接 (PTY 数据流中转)
function handleSessionClientConnection(ws, sessionId) {
  // 默认判定为 JSON 协议交互，若是收到二进制字节帧则动态降级为 binary 通讯
  ws.mode = 'json';

  // 接收手机发来的终端二进制指令，穿上 sessionId 封包后投递给 PC Agent
  ws.on('message', async (data, isBinary) => {
    try {
      const agent = await ensurePaired();
      if (!agent || agent.ws.readyState !== 1) return;

      if (isBinary) {
        // 二进制模式：直接穿上 sessionId 外衣并发往 PC Agent
        ws.mode = 'binary';
        const frame = encodeRelayFrame(sessionId, data);
        sendBinary(agent.ws, frame);
        return;
      }

      // JSON 模式：网页端或旧版 App 的指令，翻译为对应的中继二进制动作帧
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
        const frame = encodeRelayFrame(sessionId, terminalFrame);
        sendBinary(agent.ws, frame);
      }
    } catch (err) {
      console.warn('Binary proxy error:', err.message);
    }
  });

  let clientsForSession = sessionClientsMap.get(sessionId);
  if (!clientsForSession) {
    clientsForSession = new Set();
    sessionClientsMap.set(sessionId, clientsForSession);
  }
  clientsForSession.add(ws);

  ws.on('close', () => {
    const set = sessionClientsMap.get(sessionId);
    if (set) {
      set.delete(ws);
      if (set.size === 0) sessionClientsMap.delete(sessionId);
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
