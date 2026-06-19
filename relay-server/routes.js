// HTTP route handlers for the relay server.
// All external dependencies are injected via the context object to avoid
// circular imports between relay-server modules.

import { json, text, readJSON } from '../server/http-utils.js';
import {
  HTTP_REQUEST,
  MSG_TYPE_HTTP_CHUNK,
  HTTP_CHUNK_DATA, HTTP_CHUNK_FIN,
  encodeTunnelFrame, sendJSON, sendBinary
} from '../shared/tunnel-protocol.js';

export function createRoutes(ctx) {
  const {
    auth, getOrCreateDeviceId, agents, pendingHttpResponses, pendingP2pOffers,
    getAgentForUser, pushDevicesToUser, getDeviceNameFromUa,
    findByUsername, updatePassword, createUser, verifyUserEmail,
    createDevice, listByUser, deleteDevice,
    verifyOtpInStore, addTrustedDevice, listTrustedDevices, deleteTrustedDevice,
    hashPassword, setAuthCookie, setRefreshCookie,
    parseCookies, COOKIE_NAME, REFRESH_COOKIE_NAME,
    delay
  } = ctx;

  function readRequestBody(req) {
    return new Promise((resolve) => {
      let chunks = [];
      let len = 0;
      req.on('data', (c) => { chunks.push(c); len += c.length; });
      req.on('end', () => { resolve(Buffer.concat(chunks, len)); });
    });
  }

  async function route(req, res) {
    const url = new URL(req.url, `http://${req.headers.host}`);
    const deviceId = getOrCreateDeviceId(req, res);

    if (url.pathname.startsWith('/api/auth/')) {
      await handleAuthRoutes(req, res, url, deviceId);
      return;
    }

    if (req.method === 'POST' && url.pathname === '/api/login') {
      text(res, 410, 'Gone. Please use /api/auth/login instead.');
      return;
    }
    if (req.method === 'GET' && url.pathname === '/api/me') {
      const user = auth.authenticate(req);
      if (!user) { text(res, 401, 'unauthorized'); return; }
      json(res, 200, { username: user.username, mode: 'relay' });
      return;
    }

    if (url.pathname.startsWith('/api/devices')) {
      await handleDeviceRoutes(req, res, url, deviceId);
      return;
    }

    if (url.pathname.startsWith('/api/p2p/')) {
      await handleP2pRoutes(req, res, url, deviceId);
      return;
    }

    if (url.pathname.startsWith('/api/')) {
      await handleProxyRoute(req, res, url, deviceId);
      return;
    }

    return false; // fall through to static serving
  }

  async function handleAuthRoutes(req, res, url, deviceId) {
    const action = url.pathname.slice('/api/auth/'.length);

    if (req.method === 'POST' && action === 'register') {
      const body = await readJSON(req);
      const email = (body.email || body.username || '').trim();
      const password = body.password;
      const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
      if (!email || !emailRegex.test(email)) { text(res, 400, '邮箱格式不正确。'); return; }
      if (!password || typeof password !== 'string' || password.length < 6) { text(res, 400, '密码长度必须至少为 6 个字符。'); return; }

      let user = findByUsername(email);
      let isNew = false;
      if (user) {
        if (user.emailVerifiedAt !== null) { text(res, 400, '该邮箱已被注册。'); return; }
        updatePassword(user.id, await hashPassword(password));
      } else {
        user = createUser(email, await hashPassword(password), 'user', null);
        isNew = true;
      }
      try {
        const clientIp = req.socket.remoteAddress;
        await auth.sendVerificationOtp(user.id, user.username, 'register', deviceId, clientIp);
        json(res, 201, { email: user.username, status: 'otp_sent', isNew });
      } catch (err) { text(res, 400, err.message); }
      return;
    }

    if (req.method === 'POST' && action === 'verify-email') {
      const body = await readJSON(req);
      const email = (body.email || body.username || '').trim();
      const code = (body.code || '').trim();
      if (!email || !code) { text(res, 400, '邮箱和验证码不能为空。'); return; }
      const user = findByUsername(email);
      if (!user) { text(res, 400, '用户未找到。'); return; }
      const result = verifyOtpInStore(user.id, 'register', code, deviceId);
      if (!result.valid) {
        text(res, 400, result.reason === 'too_many_failed_attempts'
          ? '验证码输入错误次数过多，已废弃。请重新获取。' : '验证码不正确或已过期。');
        return;
      }
      verifyUserEmail(user.id);
      const ua = req.headers['user-agent'] || 'Browser';
      addTrustedDevice(user.id, deviceId, getDeviceNameFromUa(ua));
      const loginResult = auth.issueSession(user);
      const secureCookie = req.socket.encrypted || process.env.WEBTERM_COOKIE_SECURE === '1';
      setAuthCookie(res, loginResult.accessToken, secureCookie);
      setRefreshCookie(res, loginResult.refreshToken, secureCookie);
      json(res, 200, { email: user.username, role: user.role, status: 'verified' });
      return;
    }

    if (req.method === 'POST' && action === 'resend-otp') {
      const body = await readJSON(req);
      const email = (body.email || body.username || '').trim();
      if (!email) { text(res, 400, '邮箱不能为空。'); return; }
      const user = findByUsername(email);
      if (!user || user.emailVerifiedAt === null) { json(res, 200, { email, status: 'otp_sent' }); return; }
      try {
        const clientIp = req.socket.remoteAddress;
        await auth.sendVerificationOtp(user.id, user.username, 'new_device', deviceId, clientIp);
        json(res, 200, { email: user.username, status: 'otp_sent' });
      } catch (err) { text(res, 400, err.message); }
      return;
    }

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
      if (loginResult.inactive) { text(res, 403, '账户尚未激活，请先进行邮箱验证。'); return; }
      if (loginResult.otpRequired) {
        json(res, 200, loginResult.otpError
          ? { otp_required: true, target_device_id: deviceId, error: loginResult.otpError }
          : { otp_required: true, target_device_id: deviceId });
        return;
      }
      const secureCookie = req.socket.encrypted || process.env.WEBTERM_COOKIE_SECURE === '1';
      setAuthCookie(res, loginResult.accessToken, secureCookie);
      setRefreshCookie(res, loginResult.refreshToken, secureCookie);
      json(res, 200, { email: loginResult.user.username, role: loginResult.user.role });
      return;
    }

    if (req.method === 'POST' && action === 'verify-otp') {
      const body = await readJSON(req);
      const email = (body.email || body.username || '').trim();
      const code = (body.code || '').trim();
      const targetDeviceId = body.target_device_id || deviceId;
      if (!email || !code) { text(res, 400, '邮箱和验证码不能为空。'); return; }
      const user = findByUsername(email);
      if (!user) { text(res, 400, '用户未找到。'); return; }
      if (user.emailVerifiedAt === null) { text(res, 403, '账户尚未激活，请先进行邮箱验证。'); return; }
      const result = verifyOtpInStore(user.id, 'new_device', code, targetDeviceId);
      if (!result.valid) {
        text(res, 400, result.reason === 'too_many_failed_attempts'
          ? '验证码输入错误次数过多，已废弃。请重新获取。'
          : result.reason === 'device_mismatch'
            ? '设备识别码不匹配。'
            : '验证码不正确或已过期。');
        return;
      }
      const ua = req.headers['user-agent'] || 'Browser';
      addTrustedDevice(user.id, targetDeviceId, getDeviceNameFromUa(ua));
      const loginResult = auth.issueSession(user);
      const secureCookie = req.socket.encrypted || process.env.WEBTERM_COOKIE_SECURE === '1';
      setAuthCookie(res, loginResult.accessToken, secureCookie);
      setRefreshCookie(res, loginResult.refreshToken, secureCookie);
      json(res, 200, { email: user.username, role: user.role, status: 'verified' });
      return;
    }

    if (req.method === 'POST' && action === 'refresh') {
      const cookies = parseCookies(req.headers.cookie || '');
      const refreshToken = cookies[REFRESH_COOKIE_NAME];
      if (!refreshToken) { text(res, 401, 'missing refresh token'); return; }
      const refreshResult = auth.refresh(refreshToken, deviceId);
      if (!refreshResult) {
        res.setHeader('Set-Cookie', [
          `${COOKIE_NAME}=; Path=/; HttpOnly; Max-Age=0`,
          `${REFRESH_COOKIE_NAME}=; Path=/api/auth; HttpOnly; Max-Age=0`
        ]);
        text(res, 401, 'invalid or expired refresh token');
        return;
      }
      const secureCookie = req.socket.encrypted || process.env.WEBTERM_COOKIE_SECURE === '1';
      setAuthCookie(res, refreshResult.accessToken, secureCookie);
      setRefreshCookie(res, refreshResult.refreshToken, secureCookie);
      json(res, 200, { ok: true });
      return;
    }

    if (req.method === 'POST' && action === 'logout') {
      const cookies = parseCookies(req.headers.cookie || '');
      const refreshToken = cookies[REFRESH_COOKIE_NAME];
      if (refreshToken) auth.logout(refreshToken);
      res.setHeader('Set-Cookie', [
        `${COOKIE_NAME}=; Path=/; HttpOnly; Max-Age=0`,
        `${REFRESH_COOKIE_NAME}=; Path=/api/auth; HttpOnly; Max-Age=0`
      ]);
      json(res, 200, { ok: true });
      return;
    }

    if (req.method === 'GET' && action === 'me') {
      const user = auth.authenticate(req);
      if (!user) { text(res, 401, 'unauthorized'); return; }
      json(res, 200, { id: user.id, username: user.username, role: user.role, mode: 'relay' });
      return;
    }

    if (req.method === 'GET' && action === 'devices') {
      const user = auth.authenticate(req);
      if (!user) { text(res, 401, 'unauthorized'); return; }
      json(res, 200, listTrustedDevices(user.id));
      return;
    }

    const deviceMatch = action.match(/^devices\/(\d+)$/);
    if (req.method === 'DELETE' && deviceMatch) {
      const user = auth.authenticate(req);
      if (!user) { text(res, 401, 'unauthorized'); return; }
      const trustDbId = parseInt(deviceMatch[1], 10);
      const deleted = deleteTrustedDevice(user.id, trustDbId);
      deleted ? text(res, 204, '') : text(res, 404, 'Device trust record not found');
    }
  }

  async function handleDeviceRoutes(req, res, url, deviceId) {
    const user = auth.authenticate(req);
    if (!user) { text(res, 401, 'unauthorized'); return; }

    if (req.method === 'GET' && url.pathname === '/api/devices') {
      const devicesList = listByUser(user.id);
      const enriched = devicesList.map(d => {
        const agent = agents.get('d' + d.id);
        return { ...d, online: !!(agent && agent.ws.readyState === 1) };
      });
      json(res, 200, enriched);
      return;
    }

    if (req.method === 'POST' && url.pathname === '/api/devices') {
      const body = await readJSON(req);
      const { deviceName } = body;
      if (!deviceName || typeof deviceName !== 'string' || !deviceName.trim()) {
        text(res, 400, 'deviceName is required'); return;
      }
      const device = createDevice(user.id, deviceName.trim());
      json(res, 201, { deviceId: 'd' + device.id, deviceName: device.deviceName, agentSecret: device.agentSecret });
      return;
    }

    const match = url.pathname.match(/^\/api\/devices\/d?(\d+)$/);
    if (req.method === 'DELETE' && match) {
      const deviceDbId = parseInt(match[1], 10);
      const userDevices = listByUser(user.id);
      if (!userDevices.some(d => d.id === deviceDbId)) {
        text(res, 404, 'device not found or unauthorized'); return;
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
    }
  }

  async function handleP2pRoutes(req, res, url, deviceId) {
    const user = auth.authenticate(req);
    if (!user) { text(res, 401, 'unauthorized'); return; }
    const clientId = req.headers['x-client-id'];
    if (!clientId) { text(res, 400, 'Missing X-Client-Id header'); return; }

    if (req.method === 'POST' && url.pathname === '/api/p2p/offer') {
      const body = await readJSON(req);
      const { sdp, deviceId: targetDeviceId } = body;
      const agent = getAgentForUser(user.id, targetDeviceId);
      if (!agent) { text(res, 503, '目标 PC Agent 离线，请先在电脑端启动 PC Agent。'); return; }

      const timer = setTimeout(() => {
        if (!pendingP2pOffers.has(clientId)) return;
        pendingP2pOffers.delete(clientId);
        text(res, 504, 'PC Agent WebRTC Answer Timeout');
      }, 10000);

      pendingP2pOffers.set(clientId, { res, timer, deviceId: agent.deviceId });
      sendJSON(agent.ws, { type: 'p2p-offer', sdp, from: clientId, username: user.username });
      return;
    }

    if (req.method === 'POST' && url.pathname === '/api/p2p/ice') {
      const body = await readJSON(req);
      const { candidate, deviceId: targetDeviceId } = body;
      const agent = getAgentForUser(user.id, targetDeviceId);
      if (agent && agent.ws.readyState === 1) {
        sendJSON(agent.ws, { type: 'p2p-ice', candidate, from: clientId });
      }
      json(res, 200, { ok: true });
    }
  }

  async function handleProxyRoute(req, res, url, deviceId) {
    const user = auth.authenticate(req);
    if (!user) { text(res, 401, 'unauthorized'); return; }

    const match = url.pathname.match(/^\/api\/sessions\/([^/]+)$/);
    let targetPath = req.url;
    let targetPathname = url.pathname;
    let targetDeviceId = req.headers['x-device-id'];

    if (match) {
      const globalId = decodeURIComponent(match[1]);
      const colonIndex = globalId.indexOf(':');
      if (colonIndex >= 0) {
        targetDeviceId = globalId.substring(0, colonIndex);
        const localId = globalId.substring(colonIndex + 1);
        targetPath = `/api/sessions/${encodeURIComponent(localId)}`;
        targetPathname = `/api/sessions/${localId}`;
      }
    }

    const agent = getAgentForUser(user.id, targetDeviceId);
    if (!agent) { text(res, 503, '目标 PC Agent 离线，请先在电脑端启动 PC Agent。'); return; }

    const requestId = 'req_' + Math.random().toString(36).substring(2, 15);
    const bodyBuf = await readRequestBody(req);

    const timer = setTimeout(() => {
      if (pendingHttpResponses.has(requestId)) {
        pendingHttpResponses.delete(requestId);
        text(res, 544, 'PC Agent Gateway Timeout');
      }
    }, 30000);

    pendingHttpResponses.set(requestId, {
      res, timer, deviceId: agent.deviceId, path: targetPathname, method: req.method
    });

    const relayHeaders = { ...req.headers };
    relayHeaders['x-trusted-user'] = user.username;
    relayHeaders['x-device-id'] = agent.deviceId;

    const hasChunks = bodyBuf.length > 65536;
    sendJSON(agent.ws, {
      type: HTTP_REQUEST, requestId, method: req.method, path: targetPath,
      headers: relayHeaders,
      bodyEncoding: hasChunks ? 'none' : 'base64',
      body: hasChunks ? '' : bodyBuf.toString('base64'),
      hasChunks
    });

    if (hasChunks) {
      let offset = 0;
      const CHUNK_SIZE = 64 * 1024;
      while (offset < bodyBuf.length) {
        const end = Math.min(offset + CHUNK_SIZE, bodyBuf.length);
        const chunk = bodyBuf.subarray(offset, end);
        const isFin = end >= bodyBuf.length;
        const frame = encodeTunnelFrame(
          MSG_TYPE_HTTP_CHUNK, requestId,
          isFin ? HTTP_CHUNK_FIN : HTTP_CHUNK_DATA, chunk
        );
        sendBinary(agent.ws, frame);
        offset = end;
      }
    }
  }

  return { route, readRequestBody };
}
