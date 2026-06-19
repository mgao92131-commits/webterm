import http from 'node:http';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';
import { runMigrations } from '../server/db.js';
import { listAdmins, createUser, findByUsername, verifyUserEmail, updatePassword } from '../server/stores/user-store.js';
import { createDevice, findBySecretHash, listByUser, updateLastSeen, deleteDevice } from '../server/stores/device-store.js';
import { revokeAllForUser, purgeExpired } from '../server/stores/token-store.js';
import {
  AuthManager,
  setAuthCookie,
  setRefreshCookie,
  hashPassword,
  parseCookies,
  getOrCreateDeviceId,
  COOKIE_NAME,
  REFRESH_COOKIE_NAME
} from '../server/auth.js';
import { verifySmtpConfig } from '../server/mail.js';
import { verifyOtp as verifyOtpInStore } from '../server/stores/email-verification-store.js';
import {
  addTrustedDevice,
  listTrustedDevices,
  deleteTrustedDevice
} from '../server/stores/trusted-device-store.js';
import { serveStatic, json, text, readJSON } from '../server/http-utils.js';
import db from '../server/db.js';
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
import { delay, loadLocalEnv } from '../shared/utils.js';

loadLocalEnv('..');

// Deprecation warnings for old env vars
if (process.env.RELAY_USERS || process.env.RELAY_PASSWORD || process.env.RELAY_SECRET) {
  console.warn('[Deprecation Warning] Env variables RELAY_USERS, RELAY_PASSWORD, and RELAY_SECRET are deprecated and will be ignored in future versions. Please use DB-based user management.');
}

const port = Number(process.env.RELAY_PORT || '9000');
const auth = new AuthManager();

function getDeviceNameFromUa(ua) {
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

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'web');

// 内存状态
const agents = new Map(); // deviceId -> { ws, deviceId, deviceName, userId, username, lastSeenDbUpdated }
const managerClients = new Map(); // ws -> { userId, username, clientId }

// 挂起的 HTTP 请求：requestId -> { res, timer, deviceId, path, method }
const pendingHttpResponses = new Map();

// 挂起的 WebSocket 隧道连接：tunnelConnectionId -> { clientWs, deviceId, isConnected, queue }
const activeWsTunnels = new Map();

// 挂起的 WebRTC P2P 握手请求：clientId -> { res, timer, deviceId }
const pendingP2pOffers = new Map();

// 获取用户在线 of Agent (支持单在线设备 Fallback)
function getAgentForUser(userId, deviceId) {
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

// 广播给某用户的大厅控制客户端
function pushDevicesToUser(userId) {
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

// ---------------------------------------------------------------------------
// HTTP 路由与反代处理
// ---------------------------------------------------------------------------
const server = http.createServer((req, res) => {
  route(req, res).catch((err) => {
    console.error('[Relay Server Error]', err);
    text(res, 500, 'internal server error');
  });
});

async function route(req, res) {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const deviceId = getOrCreateDeviceId(req, res);

  // --- 1. /api/auth/* 路由组 ---
  if (url.pathname.startsWith('/api/auth/')) {
    const action = url.pathname.slice('/api/auth/'.length);

    // POST /api/auth/register
    if (req.method === 'POST' && action === 'register') {
      const body = await readJSON(req);
      const email = (body.email || body.username || '').trim();
      const password = body.password;
      
      const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
      if (!email || !emailRegex.test(email)) {
        text(res, 400, '邮箱格式不正确。');
        return;
      }
      if (!password || typeof password !== 'string' || password.length < 6) {
        text(res, 400, '密码长度必须至少为 6 个字符。');
        return;
      }
      
      let user = findByUsername(email);
      let isNew = false;
      
      if (user) {
        if (user.emailVerifiedAt !== null) {
          text(res, 400, '该邮箱已被注册。');
          return;
        }
        // P2: 未激活账号，支持重新设置密码覆盖注册，改用 store.updatePassword 保持分层一致
        const passwordHash = await hashPassword(password);
        updatePassword(user.id, passwordHash);
      } else {
        const passwordHash = await hashPassword(password);
        user = createUser(email, passwordHash, 'user', null);
        isNew = true;
      }
      
      try {
        const clientIp = req.socket.remoteAddress;
        await auth.sendVerificationOtp(user.id, user.username, 'register', deviceId, clientIp);
        json(res, 201, { email: user.username, status: 'otp_sent', isNew });
      } catch (err) {
        text(res, 400, err.message);
      }
      return;
    }

    // POST /api/auth/verify-email
    if (req.method === 'POST' && action === 'verify-email') {
      const body = await readJSON(req);
      const email = (body.email || body.username || '').trim();
      const code = (body.code || '').trim();
      
      if (!email || !code) {
        text(res, 400, '邮箱和验证码不能为空。');
        return;
      }
      
      const user = findByUsername(email);
      if (!user) {
        text(res, 400, '用户未找到。');
        return;
      }
      
      // P1: 激活时带入 deviceId 进行校验
      const verificationResult = verifyOtpInStore(user.id, 'register', code, deviceId);
      if (!verificationResult.valid) {
        text(res, 400, verificationResult.reason === 'too_many_failed_attempts'
          ? '验证码输入错误次数过多，已废弃。请重新获取。'
          : '验证码不正确或已过期。');
        return;
      }
      
      verifyUserEmail(user.id);
      
      // P2: 设备登记使用 getDeviceNameFromUa 进行 UA 摘要化，优化展示
      const ua = req.headers['user-agent'] || 'Browser';
      const deviceName = getDeviceNameFromUa(ua);
      addTrustedDevice(user.id, deviceId, deviceName);
      
      const loginResult = auth.issueSession(user);
      const secureCookie = req.socket.encrypted || process.env.WEBTERM_COOKIE_SECURE === '1';
      setAuthCookie(res, loginResult.accessToken, secureCookie);
      setRefreshCookie(res, loginResult.refreshToken, secureCookie);
      
      json(res, 200, { email: user.username, role: user.role, status: 'verified' });
      return;
    }

    // POST /api/auth/resend-otp
    if (req.method === 'POST' && action === 'resend-otp') {
      const body = await readJSON(req);
      const email = (body.email || body.username || '').trim();
      
      if (!email) {
        text(res, 400, '邮箱不能为空。');
        return;
      }
      
      const user = findByUsername(email);
      
      // P0: 避免信息枚举。如果用户不存在或者是未激活账户，统一回 200，不进行发送（未激活者可直接重新 /register 覆盖发送）
      if (!user || user.emailVerifiedAt === null) {
        json(res, 200, { email, status: 'otp_sent' });
        return;
      }
      
      try {
        const clientIp = req.socket.remoteAddress;
        await auth.sendVerificationOtp(user.id, user.username, 'new_device', deviceId, clientIp);
        json(res, 200, { email: user.username, status: 'otp_sent' });
      } catch (err) {
        text(res, 400, err.message);
      }
      return;
    }

    // POST /api/auth/login
    if (req.method === 'POST' && action === 'login') {
      const body = await readJSON(req);
      const email = (body.email || body.username || '').trim();
      const password = body.password;
      const clientIp = req.socket.remoteAddress;

      const loginResult = await auth.login(email, password, deviceId, clientIp);
      if (!loginResult) {
        auth.recordFailure(clientIp);
        await delay(auth.failureDelay(clientIp));
        text(res, 401, 'invalid credentials');
        return;
      }
      auth.clearFailures(clientIp);
      
      if (loginResult.inactive) {
        text(res, 403, '账户尚未激活，请先进行邮箱验证。');
        return;
      }
      
      if (loginResult.otpRequired) {
        if (loginResult.otpError) {
          json(res, 200, { otp_required: true, target_device_id: deviceId, error: loginResult.otpError });
        } else {
          json(res, 200, { otp_required: true, target_device_id: deviceId });
        }
        return;
      }
      
      // P2: 清理恒等三元表达式手误
      const secureCookie = req.socket.encrypted || process.env.WEBTERM_COOKIE_SECURE === '1';
      setAuthCookie(res, loginResult.accessToken, secureCookie);
      setRefreshCookie(res, loginResult.refreshToken, secureCookie);
      
      json(res, 200, { email: loginResult.user.username, role: loginResult.user.role });
      return;
    }

    // POST /api/auth/verify-otp
    if (req.method === 'POST' && action === 'verify-otp') {
      const body = await readJSON(req);
      const email = (body.email || body.username || '').trim();
      const code = (body.code || '').trim();
      const targetDeviceId = body.target_device_id || deviceId;

      if (!email || !code) {
        text(res, 400, '邮箱和验证码不能为空。');
        return;
      }

      const user = findByUsername(email);
      if (!user) {
        text(res, 400, '用户未找到。');
        return;
      }

      // Bug Fix: 确保新设备二次验证端点仅服务于已激活（Verified）账户，未激活账户在此阶段直接拒绝，防止被利用做未授权登录
      if (user.emailVerifiedAt === null) {
        text(res, 403, '账户尚未激活，请先进行邮箱验证。');
        return;
      }

      // P1: 将 targetDeviceId 纳入校验，防止 OTP 跨设备复用
      const verificationResult = verifyOtpInStore(user.id, 'new_device', code, targetDeviceId);
      if (!verificationResult.valid) {
        text(res, 400, verificationResult.reason === 'too_many_failed_attempts'
          ? '验证码输入错误次数过多，已废弃。请重新获取。'
          : verificationResult.reason === 'device_mismatch'
            ? '设备识别码不匹配。'
            : '验证码不正确或已过期。');
        return;
      }

      // P2: 设备登记使用 getDeviceNameFromUa 进行 UA 摘要化，优化展示
      const ua = req.headers['user-agent'] || 'Browser';
      const deviceName = getDeviceNameFromUa(ua);
      addTrustedDevice(user.id, targetDeviceId, deviceName);

      const loginResult = auth.issueSession(user);
      const secureCookie = req.socket.encrypted || process.env.WEBTERM_COOKIE_SECURE === '1';
      setAuthCookie(res, loginResult.accessToken, secureCookie);
      setRefreshCookie(res, loginResult.refreshToken, secureCookie);

      json(res, 200, { email: user.username, role: user.role, status: 'verified' });
      return;
    }

    // POST /api/auth/refresh
    if (req.method === 'POST' && action === 'refresh') {
      const cookies = parseCookies(req.headers.cookie || '');
      const refreshToken = cookies[REFRESH_COOKIE_NAME];
      if (!refreshToken) {
        text(res, 401, 'missing refresh token');
        return;
      }
      
      const refreshResult = auth.refresh(refreshToken, deviceId);
      if (!refreshResult) {
        res.setHeader('Set-Cookie', [
          `${COOKIE_NAME}=; Path=/; HttpOnly; Max-Age=0`,
          `${REFRESH_COOKIE_NAME}=; Path=/api/auth; HttpOnly; Max-Age=0`
        ]);
        text(res, 401, 'invalid or expired refresh token');
        return;
      }
      
      // P2: 清理恒等三元表达式手误
      const secureCookie = req.socket.encrypted || process.env.WEBTERM_COOKIE_SECURE === '1';
      setAuthCookie(res, refreshResult.accessToken, secureCookie);
      setRefreshCookie(res, refreshResult.refreshToken, secureCookie);
      
      json(res, 200, { ok: true });
      return;
    }

    // POST /api/auth/logout
    if (req.method === 'POST' && action === 'logout') {
      const cookies = parseCookies(req.headers.cookie || '');
      const refreshToken = cookies[REFRESH_COOKIE_NAME];
      if (refreshToken) {
        auth.logout(refreshToken);
      }
      
      res.setHeader('Set-Cookie', [
        `${COOKIE_NAME}=; Path=/; HttpOnly; Max-Age=0`,
        `${REFRESH_COOKIE_NAME}=; Path=/api/auth; HttpOnly; Max-Age=0`
      ]);
      json(res, 200, { ok: true });
      return;
    }

    // GET /api/auth/me
    if (req.method === 'GET' && action === 'me') {
      const user = auth.authenticate(req);
      if (!user) {
        text(res, 401, 'unauthorized');
        return;
      }
      json(res, 200, { id: user.id, username: user.username, role: user.role, mode: 'relay' });
      return;
    }

    // GET /api/auth/devices
    if (req.method === 'GET' && action === 'devices') {
      const user = auth.authenticate(req);
      if (!user) {
        text(res, 401, 'unauthorized');
        return;
      }
      const list = listTrustedDevices(user.id);
      json(res, 200, list);
      return;
    }

    // DELETE /api/auth/devices/:id
    const deviceMatch = action.match(/^devices\/(\d+)$/);
    if (req.method === 'DELETE' && deviceMatch) {
      const user = auth.authenticate(req);
      if (!user) {
        text(res, 401, 'unauthorized');
        return;
      }
      const trustDbId = parseInt(deviceMatch[1], 10);
      const deleted = deleteTrustedDevice(user.id, trustDbId);
      if (deleted) {
        text(res, 204, '');
      } else {
        text(res, 404, 'Device trust record not found');
      }
      return;
    }
  }

  // --- P0: 兼容性旧路由废弃，返回 410 并指引迁移至新 auth 路由 ---
  if (req.method === 'POST' && url.pathname === '/api/login') {
    text(res, 410, 'Gone. Please use /api/auth/login instead.');
    return;
  }

  if (req.method === 'GET' && url.pathname === '/api/me') {
    const user = auth.authenticate(req);
    if (!user) {
      text(res, 401, 'unauthorized');
      return;
    }
    json(res, 200, { username: user.username, mode: 'relay' });
    return;
  }

  // --- 2. /api/devices/* 路由组 (需登录) ---
  if (url.pathname.startsWith('/api/devices')) {
    const user = auth.authenticate(req);
    if (!user) {
      text(res, 401, 'unauthorized');
      return;
    }

    // GET /api/devices (Enriched with dynamic online status)
    if (req.method === 'GET' && url.pathname === '/api/devices') {
      const devicesList = listByUser(user.id);
      const enriched = devicesList.map(d => {
        const agent = agents.get('d' + d.id);
        return {
          ...d,
          online: !!(agent && agent.ws.readyState === 1)
        };
      });
      json(res, 200, enriched);
      return;
    }

    // POST /api/devices
    if (req.method === 'POST' && url.pathname === '/api/devices') {
      const body = await readJSON(req);
      const { deviceName } = body;
      if (!deviceName || typeof deviceName !== 'string' || !deviceName.trim()) {
        text(res, 400, 'deviceName is required');
        return;
      }
      const device = createDevice(user.id, deviceName.trim());
      json(res, 201, {
        deviceId: 'd' + device.id,
        deviceName: device.deviceName,
        agentSecret: device.agentSecret
      });
      return;
    }

    // DELETE /api/devices/:id
    const match = url.pathname.match(/^\/api\/devices\/d?(\d+)$/);
    if (req.method === 'DELETE' && match) {
      const deviceDbId = parseInt(match[1], 10);
      const userDevices = listByUser(user.id);
      const hasDevice = userDevices.some(d => d.id === deviceDbId);
      if (!hasDevice) {
        text(res, 404, 'device not found or unauthorized');
        return;
      }

      deleteDevice(deviceDbId);
      
      const targetDeviceId = 'd' + deviceDbId;
      const onlineAgent = agents.get(targetDeviceId);
      if (onlineAgent) {
        console.log(`[Relay] Closing connection for deleted agent: ${targetDeviceId}`);
        onlineAgent.ws.close(1008, 'Device deleted');
        agents.delete(targetDeviceId);
        pushDevicesToUser(user.id);
      }
      
      text(res, 204, '');
      return;
    }
  }

  // --- 3. WebRTC P2P 信令交换 (需登录) ---
  if (req.method === 'POST' && url.pathname === '/api/p2p/offer') {
    const user = auth.authenticate(req);
    if (!user) {
      text(res, 401, 'unauthorized');
      return;
    }
    const clientId = req.headers['x-client-id'];
    if (!clientId) {
      text(res, 400, 'Missing X-Client-Id header');
      return;
    }
    const body = await readJSON(req);
    const { sdp, deviceId } = body;
    const agent = getAgentForUser(user.id, deviceId);
    if (!agent) {
      text(res, 503, '目标 PC Agent 离线，请先在电脑端启动 PC Agent。');
      return;
    }

    const timer = setTimeout(() => {
      if (!pendingP2pOffers.has(clientId)) return;
      pendingP2pOffers.delete(clientId);
      text(res, 504, 'PC Agent WebRTC Answer Timeout');
    }, 10000);

    pendingP2pOffers.set(clientId, { res, timer, deviceId: agent.deviceId });

    sendJSON(agent.ws, {
      type: 'p2p-offer',
      sdp,
      from: clientId,
      username: user.username
    });
    return;
  }

  if (req.method === 'POST' && url.pathname === '/api/p2p/ice') {
    const user = auth.authenticate(req);
    if (!user) {
      text(res, 401, 'unauthorized');
      return;
    }
    const clientId = req.headers['x-client-id'];
    if (!clientId) {
      text(res, 400, 'Missing X-Client-Id header');
      return;
    }
    const body = await readJSON(req);
    const { candidate, deviceId } = body;
    const agent = getAgentForUser(user.id, deviceId);
    if (agent && agent.ws.readyState === 1) {
      sendJSON(agent.ws, {
        type: 'p2p-ice',
        candidate,
        from: clientId
      });
    }
    json(res, 200, { ok: true });
    return;
  }

  // --- 4. 通用 HTTP 反向代理盲中转 (/api/*) (需登录) ---
  if (url.pathname.startsWith('/api/')) {
    const user = auth.authenticate(req);
    if (!user) {
      text(res, 401, 'unauthorized');
      return;
    }

    // 对路径上的 sessionId 进行全局 ID 检测和自动拆分还原
    const match = url.pathname.match(/^\/api\/sessions\/([^/]+)$/);
    let targetPath = req.url;
    let targetPathname = url.pathname;
    let deviceId = req.headers['x-device-id'];

    if (match) {
      const globalId = decodeURIComponent(match[1]);
      const colonIndex = globalId.indexOf(':');
      if (colonIndex >= 0) {
        deviceId = globalId.substring(0, colonIndex);
        const localId = globalId.substring(colonIndex + 1);
        targetPath = `/api/sessions/${encodeURIComponent(localId)}`;
        targetPathname = `/api/sessions/${localId}`;
      }
    }

    const agent = getAgentForUser(user.id, deviceId);
    if (!agent) {
      text(res, 503, '目标 PC Agent 离线，请先在电脑端启动 PC Agent。');
      return;
    }

    // 盲中转转发 HTTP 请求到对应 Agent 隧道
    const requestId = 'req_' + Math.random().toString(36).substring(2, 15);
    const bodyBuf = await readRequestBody(req);

    // 设置网关超时 (30 秒)
    const timer = setTimeout(() => {
      if (pendingHttpResponses.has(requestId)) {
        pendingHttpResponses.delete(requestId);
        text(res, 544, 'PC Agent Gateway Timeout');
      }
    }, 30000);

    pendingHttpResponses.set(requestId, { 
      res, 
      timer, 
      deviceId: agent.deviceId,
      path: targetPathname,
      method: req.method
    });

    const relayHeaders = { ...req.headers };
    relayHeaders['x-trusted-user'] = user.username;
    relayHeaders['x-device-id'] = agent.deviceId;

    const hasChunks = bodyBuf.length > 65536;
    const reqMessage = {
      type: HTTP_REQUEST,
      requestId,
      method: req.method,
      path: targetPath,
      headers: relayHeaders,
      bodyEncoding: hasChunks ? 'none' : 'base64',
      body: hasChunks ? '' : bodyBuf.toString('base64'),
      hasChunks
    };

    sendJSON(agent.ws, reqMessage);

    if (hasChunks) {
      let offset = 0;
      const CHUNK_SIZE = 64 * 1024;
      while (offset < bodyBuf.length) {
        const end = Math.min(offset + CHUNK_SIZE, bodyBuf.length);
        const chunk = bodyBuf.subarray(offset, end);
        const isFin = end >= bodyBuf.length;
        const frame = encodeTunnelFrame(
          MSG_TYPE_HTTP_CHUNK,
          requestId,
          isFin ? HTTP_CHUNK_FIN : HTTP_CHUNK_DATA,
          chunk
        );
        sendBinary(agent.ws, frame);
        offset = end;
      }
    }
    return;
  }

  // --- 5. 其它所有静态网页网页 ---
  await serveStatic(req, res, root);
}

function readRequestBody(req) {
  return new Promise((resolve) => {
    let chunks = [];
    let len = 0;
    req.on('data', (c) => {
      chunks.push(c);
      len += c.length;
    });
    req.on('end', () => {
      resolve(Buffer.concat(chunks, len));
    });
  });
}

// ---------------------------------------------------------------------------
// WebSocket 升级处理与盲中转
// ---------------------------------------------------------------------------
const wssAgent = new WebSocketServer({ noServer: true });
const wssManager = new WebSocketServer({ noServer: true });
const wssSession = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, `http://${req.headers.host}`);

  // A. PC Agent 注册端
  if (url.pathname === '/ws/agent') {
    wssAgent.handleUpgrade(req, socket, head, (ws) => {
      handleAgentConnection(ws);
    });
    return;
  }

  // 校验登录 Cookie 凭证
  const user = auth.authenticate(req);
  if (!user) {
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    socket.destroy();
    return;
  }

  // B. 大厅控制长连接
  if (url.pathname === '/ws/sessions') {
    wssManager.handleUpgrade(req, socket, head, (ws) => {
      const clientId = url.searchParams.get('clientId') || 'c_' + Math.random().toString(36).substring(2, 15);
      managerClients.set(ws, { userId: user.id, username: user.username, clientId });

      ws.on('close', () => { managerClients.delete(ws); });
      ws.on('error', () => { ws.close(); });

      pushDevicesToUser(user.id);
    });
    return;
  }

  // C. 通用 WebSocket 盲穿透转发 (/ws/*)
  if (url.pathname.startsWith('/ws/')) {
    const match = url.pathname.match(/^\/ws\/sessions\/([^/]+)$/);
    let deviceId = url.searchParams.get('deviceId');
    let targetPath = url.pathname + (url.search ? url.search : '');

    if (match) {
      const globalId = decodeURIComponent(match[1]);
      const colonIndex = globalId.indexOf(':');
      if (colonIndex >= 0) {
        deviceId = globalId.substring(0, colonIndex);
        const localId = globalId.substring(colonIndex + 1);
        targetPath = `/ws/sessions/${encodeURIComponent(localId)}`;
      }
    }

    const agent = getAgentForUser(user.id, deviceId);
    if (!agent) {
      socket.write('HTTP/1.1 503 Service Unavailable\r\n\r\n');
      socket.destroy();
      return;
    }

    wssSession.handleUpgrade(req, socket, head, (clientWs) => {
      handleClientWsTunnel(clientWs, agent, targetPath, req);
    });
    return;
  }

  socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
  socket.destroy();
});

// 处理 PC Agent 连接接入
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
    try {
      msg = JSON.parse(data.toString('utf8'));
    } catch {
      return;
    }

    // Agent 登录注册
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
      
      agents.set(registeredDeviceId, {
        ws,
        deviceId: registeredDeviceId,
        deviceName: device.deviceName || msg.deviceName || 'Unknown PC',
        userId: device.userId,
        username: device.username,
        lastSeenDbUpdated: Date.now() // Initialize heartbeat write timestamp
      });
      
      console.log(`[Relay] Agent registered: ${registeredDeviceId} (${device.deviceName}) for user ${device.username}`);
      updateLastSeen(device.id);
      
      sendJSON(ws, { type: REGISTERED, deviceId: registeredDeviceId });
      pushDevicesToUser(device.userId);
      return;
    }

    if (!registeredDeviceId) return;

    // 处理 WebRTC P2P 握手应答
    if (msg.type === 'p2p-answer') {
      const pending = pendingP2pOffers.get(msg.to);
      if (pending) {
        clearTimeout(pending.timer);
        pendingP2pOffers.delete(msg.to);
        json(pending.res, 200, { sdp: msg.sdp, candidates: msg.candidates || [] });
      }
      return;
    }

    // 处理 WebRTC P2P 候选地址转发
    if (msg.type === 'p2p-ice') {
      for (const [clientWs, info] of managerClients.entries()) {
        if (info.clientId === msg.to && clientWs.readyState === 1) {
          sendJSON(clientWs, {
            type: 'p2p-ice',
            candidate: msg.candidate
          });
          break;
        }
      }
      return;
    }

    // 处理 HTTP 代理请求结果的解挂响应
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
                    if (s.id && !s.id.includes(':')) {
                      s.id = `${pending.deviceId}:${s.id}`;
                    }
                  }
                  payload = Buffer.from(JSON.stringify(jsonObj), 'utf8');
                  msg.headers['content-length'] = String(payload.length);
                } else if (isPostSessions && jsonObj && jsonObj.id) {
                  if (!jsonObj.id.includes(':')) {
                    jsonObj.id = `${pending.deviceId}:${jsonObj.id}`;
                  }
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

    // 处理 WebSocket 隧道控制反馈
    if (msg.tunnelConnectionId) {
      const tunnel = activeWsTunnels.get(msg.tunnelConnectionId);
      if (tunnel) {
        if (msg.type === WS_CONNECTED) {
          if (tunnel.isConnected) return;
          tunnel.isConnected = true;
          
          const currentAgent = agents.get(tunnel.deviceId);
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
          activeWsTunnels.delete(tunnelConnectionId);
        }
      }
    }
  });

  ws.on('close', () => {
    if (registeredDeviceId) {
      console.log(`[Relay] Agent disconnected: ${registeredDeviceId}`);
      agents.delete(registeredDeviceId);

      if (registeredUserId) pushDevicesToUser(registeredUserId);

      // 清理活跃隧道
      activeWsTunnels.forEach((tunnel, tunnelId) => {
        if (tunnel.deviceId === registeredDeviceId) {
          tunnel.clientWs.close(4000, 'PC Agent offline');
          activeWsTunnels.delete(tunnelId);
        }
      });

      // 清理挂起的 HTTP 代理响应
      pendingHttpResponses.forEach((pending, reqId) => {
        if (pending.deviceId === registeredDeviceId) {
          clearTimeout(pending.timer);
          text(pending.res, 503, '目标 PC Agent 离线，请先在电脑端启动 PC Agent。');
          pendingHttpResponses.delete(reqId);
        }
      });

      // 清理挂起的 P2P 握手响应
      pendingP2pOffers.forEach((pending, clientId) => {
        if (pending.deviceId === registeredDeviceId) {
          clearTimeout(pending.timer);
          text(pending.res, 503, '目标 PC Agent 离线，请先在电脑端启动 PC Agent。');
          pendingP2pOffers.delete(clientId);
        }
      });
    }
  });

  ws.on('error', () => {
    ws.close();
  });
}

function handleClientWsTunnel(clientWs, agent, targetPath, req) {
  const tunnelConnectionId = 'tc_' + Math.random().toString(36).substring(2, 15);
  const queue = [];

  activeWsTunnels.set(tunnelConnectionId, {
    clientWs,
    deviceId: agent.deviceId,
    isConnected: false,
    queue
  });

  const relayHeaders = { ...req.headers };

  sendJSON(agent.ws, {
    type: WS_CONNECT,
    tunnelConnectionId,
    path: targetPath,
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
    const currentAgent = agents.get(tunnel.deviceId);
    if (currentAgent && currentAgent.ws.readyState === 1) {
      const typeByte = clientIsBinary ? WS_DATA_BINARY : WS_DATA_TEXT;
      const frame = encodeTunnelFrame(MSG_TYPE_WS_DATA, tunnelConnectionId, typeByte, clientData);
      sendBinary(currentAgent.ws, frame);
    }
  });

  clientWs.on('close', (code, reason) => {
    if (activeWsTunnels.has(tunnelConnectionId)) {
      activeWsTunnels.delete(tunnelConnectionId);
      const currentAgent = agents.get(agent.deviceId);
      if (currentAgent && currentAgent.ws.readyState === 1) {
        sendJSON(currentAgent.ws, {
          type: WS_CLOSE,
          tunnelConnectionId,
          code,
          reason: Buffer.isBuffer(reason) ? reason.toString('utf8') : String(reason || '')
        });
      }
    }
  });

  clientWs.on('error', () => {
    clientWs.close();
  });
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

// 30秒心跳检测，并定期刷新在线设备的 DB 活跃时间 (至少间隔5分钟)
const interval = setInterval(() => {
  wssAgent.clients.forEach((ws) => {
    if (ws.isAlive === false) return ws.terminate();
    ws.isAlive = false;
    ws.ping();
  });

  const now = Date.now();
  for (const agent of agents.values()) {
    if (agent.ws.readyState === 1) {
      if (!agent.lastSeenDbUpdated || now - agent.lastSeenDbUpdated > 5 * 60 * 1000) {
        try {
          const dbId = parseInt(agent.deviceId.slice(1), 10);
          updateLastSeen(dbId);
          agent.lastSeenDbUpdated = now;
        } catch (err) {
          console.error('[Relay] Failed to update agent heartbeat in DB:', err.message);
        }
      }
    }
  }
}, 30000);

server.on('close', () => {
  clearInterval(interval);
});

// Bootstrap & Start Server
async function start() {
  // 校验并初始化 SMTP 配置
  verifySmtpConfig();

  console.log('[DB] Ensuring migrations are applied...');
  runMigrations();

  // 启动时自动清理一次过期的 token
  try {
    const deletedCount = purgeExpired();
    if (deletedCount > 0) {
      console.log(`[DB] Purged ${deletedCount} expired/revoked refresh tokens on startup.`);
    }
  } catch (err) {
    console.error('[DB] Failed to purge expired tokens on startup:', err.message);
  }

  // 每天自动清理一次过期 token
  setInterval(() => {
    try {
      const deletedCount = purgeExpired();
      if (deletedCount > 0) {
        console.log(`[DB] Scheduled task purged ${deletedCount} expired/revoked refresh tokens.`);
      }
    } catch (err) {
      console.error('[DB] Scheduled task failed to purge expired tokens:', err.message);
    }
  }, 24 * 60 * 60 * 1000);

  const admins = listAdmins();
  if (admins.length === 0) {
    const bootstrapUser = process.env.RELAY_BOOTSTRAP_USER;
    const bootstrapPassword = process.env.RELAY_BOOTSTRAP_PASSWORD;
    if (bootstrapUser && bootstrapPassword) {
      console.log(`[Bootstrap] Creating default admin user: ${bootstrapUser}...`);
      const hp = await hashPassword(bootstrapPassword);
      createUser(bootstrapUser, hp, 'admin', new Date().toISOString());
      console.log(`[Bootstrap] Admin user successfully created. IMPORTANT: Please remove RELAY_BOOTSTRAP_USER and RELAY_BOOTSTRAP_PASSWORD environment variables from env files now.`);
    } else {
      console.warn(
        '\n========================================================================\n' +
        '[Bootstrap] WARNING: No admin users found in database, and RELAY_BOOTSTRAP_USER / RELAY_BOOTSTRAP_PASSWORD are not set.\n' +
        '[Bootstrap] Web terminal UI login WILL BE UNAVAILABLE!\n' +
        '[Bootstrap] Please configure these environment variables and restart the server to seed an admin account.\n' +
        '========================================================================\n'
      );
    }
  }

  server.listen(port, () => {
    console.log(`WebTerm Relay Server listening on port ${port}`);
  });
}

start().catch(err => {
  console.error('[Relay Fatal Startup Error]', err);
  process.exit(1);
});
