import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';

const args = parseArgs(process.argv.slice(2));
const timeoutMs = Number(args.timeout || 60000);
const externalRelayURL = args['relay-url'] ? normalizeBaseURL(String(args['relay-url'])) : '';
const externalWebURL = args['web-url'] ? normalizeBaseURL(String(args['web-url'])) : externalRelayURL;
const externalMode = Boolean(externalRelayURL);
const debugBaseURL = args['debug-url'] ? normalizeBaseURL(String(args['debug-url'])) : (externalMode ? '' : null);
const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const goCoreRoot = path.join(repoRoot, 'go-core');
const webRoot = path.join(repoRoot, 'web');
const deadline = Date.now() + timeoutMs;

const user = {
  email: String(args.email || process.env.WEBTERM_SMOKE_EMAIL || `web-smoke-${Date.now()}@example.com`),
  password: String(args.password || process.env.WEBTERM_SMOKE_PASSWORD || 'smoke-password-123'),
};
const registerArg = args.register;
const shouldRegister = registerArg === true || (!externalMode && registerArg !== false) || (externalMode && !args.email && !process.env.WEBTERM_SMOKE_EMAIL && registerArg !== false);

const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'webterm-web-go-relay-smoke-'));
const storePath = path.join(tempDir, 'relay-store.json');
const gocache = path.join(goCoreRoot, '.gocache');

let relayProcess;
let agentProcess;
let webServer;
let browser;
let currentStep = 'initializing';
let observedWebSocketURLs = [];
let browserDiagnostics = { console: [], errors: [], pageURL: '' };

class CookieJar {
  #values = new Map();

  set(cookie) {
    const [pair] = cookie.split(';');
    const index = pair.indexOf('=');
    if (index <= 0) return;
    this.#values.set(pair.slice(0, index).trim(), pair.slice(index + 1).trim());
  }

  addSetCookie(values) {
    if (!values) return;
    for (const value of Array.isArray(values) ? values : [values]) {
      this.set(value);
    }
  }

  header() {
    return Array.from(this.#values.entries())
      .map(([key, value]) => `${key}=${value}`)
      .join('; ');
  }
}

try {
  if (!externalMode) {
    await ensureBuiltWeb();
  }

  const relayPort = externalMode ? 0 : await freePort();
  const webPort = externalMode ? 0 : await freePort();
  const relayBaseURL = externalMode ? externalRelayURL : `http://127.0.0.1:${relayPort}`;
  const webBaseURL = externalMode ? externalWebURL : `http://127.0.0.1:${webPort}`;

  if (!externalMode) {
    relayProcess = spawn('go', ['run', './cmd/webterm-relay'], {
      cwd: goCoreRoot,
      env: {
        ...process.env,
        GOCACHE: gocache,
        WEBTERM_RELAY_ADDR: `127.0.0.1:${relayPort}`,
        WEBTERM_RELAY_STORE_PATH: storePath,
        WEBTERM_RELAY_ALLOW_REGISTRATION: '1',
        WEBTERM_RELAY_REQUIRE_EMAIL_OTP: '0',
      },
      detached: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    const relayOutput = captureOutput(relayProcess);
    await waitForText(relayOutput, `WebTerm Go Relay listening on http://127.0.0.1:${relayPort}`, 'go relay');
  }
  if (externalMode) {
    await waitForHTTP(`${relayBaseURL}/api/auth/me`, [200, 401]);
  } else {
    await waitForHTTP(`${relayBaseURL}/healthz`);
  }

  const apiJar = new CookieJar();
  if (shouldRegister) {
    await requestJSON({
      method: 'POST',
      url: `${relayBaseURL}/api/auth/register`,
      cookieJar: apiJar,
      body: { email: user.email, password: user.password },
      expected: 201,
    });
  }
  await requestJSON({
    method: 'POST',
    url: `${relayBaseURL}/api/auth/login`,
    cookieJar: apiJar,
    body: { email: user.email, password: user.password },
    expected: 200,
  });
  const createdDevice = await requestJSON({
    method: 'POST',
    url: `${relayBaseURL}/api/devices`,
    cookieJar: apiJar,
    body: { deviceName: 'Web Smoke Go Agent' },
    expected: 201,
  });
  if (!createdDevice.deviceId || !createdDevice.agentSecret) {
    throw new Error(`device create returned invalid payload: ${JSON.stringify(createdDevice)}`);
  }

  agentProcess = spawn('go', ['run', './cmd/webterm-agent', '--mode', 'relay'], {
    cwd: goCoreRoot,
    env: {
      ...process.env,
      GOCACHE: gocache,
      RELAY_URL: relayBaseURL,
      RELAY_SECRET: createdDevice.agentSecret,
      DEVICE_NAME: 'Web Smoke Go Agent',
      WEBTERM_RELAY_PROTOCOL: 'v2',
      WEBTERM_CONTROL_ADDR: '127.0.0.1:0',
      WEBTERM_SHELL: '/bin/sh',
    },
    detached: true,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const agentOutput = captureOutput(agentProcess);
  await waitForDeviceOnline(relayBaseURL, apiJar, createdDevice.deviceId);

  if (!externalMode) {
    webServer = await startWebServer({ port: webPort, relayPort });
  }

  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext();
  await context.addInitScript(() => {
    window.localStorage.setItem('webtermDebug', '1');
  });
  const page = await context.newPage();
  const websocketURLs = [];
  observedWebSocketURLs = websocketURLs;
  page.on('websocket', (ws) => {
    websocketURLs.push(ws.url());
  });
  page.on('console', (msg) => {
    browserDiagnostics.console.push(`${msg.type()}: ${msg.text()}`);
  });
  page.on('pageerror', (error) => {
    browserDiagnostics.errors.push(error.stack || error.message);
  });

  currentStep = 'open login page';
  await page.goto(joinURL(webBaseURL, '/login'), { waitUntil: 'networkidle' });
  currentStep = 'submit login form';
  await page.fill('input[name="email"]', user.email);
  await page.fill('input[name="password"]', user.password);
  await page.click('button[type="submit"]');
  await page.waitForURL((url) => isHomeURL(url, webBaseURL), { timeout: remaining() });
  currentStep = 'wait for online device';
  await page.getByRole('button', { name: 'Web Smoke Go Agent' }).waitFor({ timeout: remaining() });
  await page.getByRole('button', { name: /新建终端/ }).waitFor({ state: 'visible', timeout: remaining() });

  currentStep = 'create first terminal';
  await page.getByRole('button', { name: /新建终端/ }).click();
  await page.waitForURL(/\/terminal\//, { timeout: remaining() });
  currentStep = 'wait for first terminal websocket';
  await page.waitForFunction(() => window.__webtermDebug?.wsState?.()?.readyState === WebSocket.OPEN, null, { timeout: remaining() });

  currentStep = 'verify first terminal output';
  const marker = `WEBTERM_WEB_GO_RELAY_SMOKE_${Date.now()}`;
  await page.evaluate((value) => window.__webtermDebug.input(`printf ${value}\\n`), marker);
  await page.waitForFunction((value) => {
    return window.__webtermDebug?.termState?.()?.text?.includes(value);
  }, marker, { timeout: remaining() });

  currentStep = 'create second terminal';
  await page.getByRole('link', { name: /返回/ }).click();
  await page.waitForURL((url) => isHomeURL(url, webBaseURL), { timeout: remaining() });
  await page.getByRole('button', { name: /新建终端/ }).click();
  await page.waitForURL(/\/terminal\//, { timeout: remaining() });
  await page.waitForFunction(() => window.__webtermDebug?.wsState?.()?.readyState === WebSocket.OPEN, null, { timeout: remaining() });

  currentStep = 'assert mux websocket shape';
  await assertNoLegacyWebSockets(websocketURLs);
  await assertMuxOnlyWebSockets(websocketURLs);
  if (debugBaseURL !== '') {
    await assertRelayStreams(debugBaseURL || relayBaseURL, apiJar);
  }

  console.log(`web go-relay pc-agent smoke ok${externalMode ? ' (external)' : ''}`);

  await browser.close();
  browser = null;
  await stopAll();

  if (agentOutput.text.includes('webterm-agent failed')) {
    throw new Error(`agent reported failure:\n${agentOutput.text}`);
  }
} catch (error) {
  try {
    if (browser) {
      const pages = browser.contexts().flatMap((context) => context.pages());
      browserDiagnostics.pageURL = pages[0]?.url() || '';
      browserDiagnostics.debugState = await pages[0]?.evaluate(() => ({
        wsState: window.__webtermDebug?.wsState?.() || null,
        termState: window.__webtermDebug?.termState?.() || null,
      })).catch((err) => ({ error: err.message }));
    }
  } catch {
    // best-effort diagnostics only
  }
  const details = [];
  if (relayProcess) details.push(['relay', relayProcess.__output?.text]);
  if (agentProcess) details.push(['agent', agentProcess.__output?.text]);
  await stopAll();
  console.error(`web go-relay pc-agent smoke failed at ${currentStep}: ${error.message}`);
  console.error(`page url: ${browserDiagnostics.pageURL || '(unknown)'}`);
  console.error(`websockets: ${observedWebSocketURLs.join(', ') || '(none)'}`);
  if (browserDiagnostics.errors.length) {
    console.error(`page errors:\n${browserDiagnostics.errors.join('\n')}`);
  }
  if (browserDiagnostics.console.length) {
    console.error(`browser console:\n${browserDiagnostics.console.slice(-20).join('\n')}`);
  }
  if (browserDiagnostics.debugState) {
    console.error(`debug state:\n${JSON.stringify(browserDiagnostics.debugState, null, 2)}`);
  }
  for (const [label, output] of details) {
    if (output) console.error(`\n--- ${label} output ---\n${output}`);
  }
  process.exit(1);
} finally {
  await fs.rm(tempDir, { recursive: true, force: true });
}

async function ensureBuiltWeb() {
  const indexPath = path.join(webRoot, 'index.html');
  try {
    await fs.access(indexPath);
  } catch {
    throw new Error('web/index.html not found; run npm run build before smoke');
  }
}

async function startWebServer({ port, relayPort }) {
  const server = http.createServer(async (req, res) => {
    try {
      if (!req.url) {
        res.writeHead(400).end('bad request');
        return;
      }
      if (req.url.startsWith('/api/') || req.url.startsWith('/debug/') || req.url === '/healthz' || req.url === '/readyz') {
        proxyHTTP(req, res, relayPort);
        return;
      }
      await serveStatic(req, res);
    } catch (error) {
      res.writeHead(500, { 'content-type': 'text/plain; charset=utf-8' });
      res.end(String(error?.stack || error));
    }
  });
  server.on('upgrade', (req, socket, head) => {
    if (!req.url?.startsWith('/ws/')) {
      socket.destroy();
      return;
    }
    proxyUpgrade(req, socket, head, relayPort);
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', resolve);
  });
  return server;
}

function proxyHTTP(req, res, relayPort) {
  const headers = { ...req.headers, host: `127.0.0.1:${relayPort}` };
  const proxyReq = http.request({
    method: req.method,
    hostname: '127.0.0.1',
    port: relayPort,
    path: req.url,
    headers,
  }, (proxyRes) => {
    res.writeHead(proxyRes.statusCode || 502, proxyRes.headers);
    proxyRes.pipe(res);
  });
  proxyReq.on('error', (error) => {
    res.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' });
    res.end(error.message);
  });
  req.pipe(proxyReq);
}

function proxyUpgrade(req, socket, head, relayPort) {
  const upstream = net.connect(relayPort, '127.0.0.1', () => {
    const lines = [
      `${req.method} ${req.url} HTTP/${req.httpVersion}`,
      ...Object.entries(req.headers)
        .filter(([name]) => {
          const lower = name.toLowerCase();
          return lower !== 'host' && lower !== 'origin';
        })
        .map(([name, value]) => `${name}: ${Array.isArray(value) ? value.join(', ') : value}`),
      `Host: 127.0.0.1:${relayPort}`,
      `Origin: http://127.0.0.1:${relayPort}`,
      '',
      '',
    ];
    upstream.write(lines.join('\r\n'));
    if (head.length > 0) upstream.write(head);
    upstream.pipe(socket);
    socket.pipe(upstream);
  });
  upstream.on('error', () => socket.destroy());
  socket.on('error', () => upstream.destroy());
}

async function serveStatic(req, res) {
  const parsed = new URL(req.url, 'http://127.0.0.1');
  let pathname = decodeURIComponent(parsed.pathname);
  if (pathname === '/') pathname = '/index.html';
  const candidate = path.normalize(path.join(webRoot, pathname));
  const rootPrefix = webRoot.endsWith(path.sep) ? webRoot : webRoot + path.sep;
  let filePath = candidate.startsWith(rootPrefix) ? candidate : path.join(webRoot, 'index.html');
  let data;
  try {
    const stat = await fs.stat(filePath);
    if (stat.isDirectory()) filePath = path.join(webRoot, 'index.html');
    data = await fs.readFile(filePath);
  } catch {
    if (pathname.startsWith('/assets/')) {
      res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
      res.end('asset not found');
      return;
    }
    filePath = path.join(webRoot, 'index.html');
    data = await fs.readFile(filePath);
  }
  res.writeHead(200, { 'content-type': contentType(filePath) });
  res.end(data);
}

function contentType(filePath) {
  if (filePath.endsWith('.html')) return 'text/html; charset=utf-8';
  if (filePath.endsWith('.js')) return 'text/javascript; charset=utf-8';
  if (filePath.endsWith('.css')) return 'text/css; charset=utf-8';
  if (filePath.endsWith('.json')) return 'application/json; charset=utf-8';
  if (filePath.endsWith('.svg')) return 'image/svg+xml';
  if (filePath.endsWith('.png')) return 'image/png';
  return 'application/octet-stream';
}

async function waitForDeviceOnline(baseURL, cookieJar, deviceId) {
  while (Date.now() < deadline) {
    const devices = await requestJSON({
      method: 'GET',
      url: `${baseURL}/api/devices`,
      cookieJar,
      expected: 200,
    });
    if (Array.isArray(devices) && devices.some((device) => device.deviceId === deviceId && device.online)) {
      return;
    }
    await delay(150);
  }
  throw new Error(`device ${deviceId} did not become online`);
}

async function assertMuxOnlyWebSockets(urls) {
  await assertNoLegacyWebSockets(urls);
  const sessionURLs = urls.filter((url) => url.includes('/ws/sessions'));
  const physicalMux = sessionURLs.filter((url) => {
    const parsed = new URL(url);
    return parsed.pathname === '/ws/sessions' && parsed.searchParams.has('deviceId');
  });
  if (physicalMux.length === 0) {
    throw new Error(`no relay mux websocket opened; observed: ${urls.join(', ')}`);
  }
  const unique = new Set(physicalMux);
  if (unique.size !== 1) {
    throw new Error(`expected one physical relay mux websocket, observed: ${Array.from(unique).join(', ')}`);
  }
}

async function assertNoLegacyWebSockets(urls) {
  await delay(500);
  const sessionURLs = urls.filter((url) => url.includes('/ws/sessions'));
  const legacy = sessionURLs.filter((url) => /\/ws\/sessions\/[^?]/.test(new URL(url).pathname));
  if (legacy.length > 0) {
    throw new Error(`legacy physical terminal websocket opened: ${legacy.join(', ')}`);
  }
}

async function assertRelayStreams(baseURL, cookieJar) {
  const streams = await requestJSON({
    method: 'GET',
    url: `${baseURL}/debug/streams`,
    cookieJar,
    expected: 200,
  });
  const text = JSON.stringify(streams);
  if (text.includes('/ws/sessions/')) {
    throw new Error(`/debug/streams contains legacy physical session path: ${text}`);
  }
  if (!text.includes('/ws/sessions')) {
    throw new Error(`/debug/streams does not show relay mux stream: ${text}`);
  }
}

async function waitForHTTP(url, expected = [200, 503]) {
  while (Date.now() < deadline) {
    try {
      const response = await requestText({ method: 'GET', url, expected });
      if (response.statusCode) return;
    } catch {
      await delay(100);
    }
  }
  throw new Error(`${url} did not become reachable`);
}

async function requestJSON({ expected = 200, ...options }) {
  const response = await requestText({ ...options, expected });
  if (!response.body) return null;
  try {
    return JSON.parse(response.body);
  } catch (error) {
    throw new Error(`invalid json from ${options.url}: ${response.body}`);
  }
}

function requestText({ method, url, body, cookieJar, headers = {}, expected = 200 }) {
  const parsed = new URL(url);
  const data = body === undefined ? null : Buffer.from(JSON.stringify(body));
  const expectedStatuses = Array.isArray(expected) ? expected : [expected];
  return new Promise((resolve, reject) => {
    const request = http.request({
      method,
      hostname: parsed.hostname,
      port: parsed.port,
      path: parsed.pathname + parsed.search,
      headers: {
        ...(data ? { 'Content-Type': 'application/json', 'Content-Length': data.length } : {}),
        ...(cookieJar?.header() ? { Cookie: cookieJar.header() } : {}),
        ...headers,
      },
    }, (response) => {
      cookieJar?.addSetCookie(response.headers['set-cookie']);
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => {
        const payload = {
          statusCode: response.statusCode,
          headers: response.headers,
          body: Buffer.concat(chunks).toString('utf8'),
        };
        if (!expectedStatuses.includes(response.statusCode || 0)) {
          reject(new Error(`${method} ${url} returned ${response.statusCode}: ${payload.body}`));
          return;
        }
        resolve(payload);
      });
    });
    request.on('error', reject);
    if (data) request.write(data);
    request.end();
  });
}

function captureOutput(child) {
  const state = { text: '' };
  child.__output = state;
  for (const stream of [child.stdout, child.stderr]) {
    stream?.on('data', (chunk) => {
      state.text += chunk.toString('utf8');
    });
  }
  return state;
}

async function waitForText(output, text, label) {
  while (Date.now() < deadline) {
    if (output.text.includes(text)) return;
    await delay(100);
  }
  throw new Error(`${label} did not print ${text}; output:\n${output.text}`);
}

function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      server.close(() => resolve(address.port));
    });
    server.on('error', reject);
  });
}

async function stopAll() {
  if (browser) {
    await browser.close().catch(() => {});
    browser = null;
  }
  if (webServer) {
    await new Promise((resolve) => webServer.close(resolve));
    webServer = null;
  }
  stopProcess(agentProcess);
  stopProcess(relayProcess);
  await Promise.allSettled([waitProcess(agentProcess), waitProcess(relayProcess)]);
}

function waitProcess(child) {
  if (!child) return Promise.resolve();
  return new Promise((resolve) => {
    if (child.exitCode !== null || child.signalCode !== null) {
      resolve();
      return;
    }
    child.once('exit', resolve);
  });
}

function stopProcess(child) {
  if (child && child.exitCode === null && child.signalCode === null) {
    try {
      process.kill(-child.pid, 'SIGTERM');
    } catch {
      child.kill('SIGTERM');
    }
  }
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function remaining() {
  return Math.max(1, deadline - Date.now());
}

function normalizeBaseURL(value) {
  const url = new URL(value);
  url.pathname = url.pathname.replace(/\/+$/, '');
  url.search = '';
  url.hash = '';
  return url.toString().replace(/\/$/, '');
}

function joinURL(baseURL, pathname) {
  return new URL(pathname, `${baseURL}/`).toString();
}

function isHomeURL(url, baseURL) {
  const target = new URL(baseURL);
  return url.origin === target.origin && normalizePath(url.pathname) === normalizePath(target.pathname || '/');
}

function normalizePath(pathname) {
  const normalized = pathname.replace(/\/+$/, '');
  return normalized || '/';
}

function parseArgs(items) {
  const parsed = {};
  for (let index = 0; index < items.length; index += 1) {
    const item = items[index];
    if (!item.startsWith('--')) continue;
    const key = item.slice(2);
    const next = items[index + 1];
    const rawValue = next?.startsWith('--') ? true : next;
    const value = rawValue === 'true' ? true : rawValue === 'false' ? false : rawValue;
    parsed[key] = value ?? true;
    if (value !== true) index += 1;
  }
  return parsed;
}
