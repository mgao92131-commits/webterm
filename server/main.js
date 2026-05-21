import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';
import { AuthManager, setAuthCookie } from './auth.js';
import { json, readJSON, sameHostOrigin, serveStatic, text } from './http-utils.js';
import { SessionManager } from './session-manager.js';

const password = process.env.WEBTERM_PASSWORD;
if (!password) {
  console.error('WEBTERM_PASSWORD must be set');
  process.exit(1);
}

const username = process.env.WEBTERM_USER || 'admin';
const addr = process.env.WEBTERM_ADDR || '127.0.0.1:8080';
const [host, portText] = splitAddress(addr);
const port = Number(portText);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'web');
const auth = new AuthManager({ username, password });
const sessions = new SessionManager();
const server = http.createServer((req, res) => route(req, res).catch((err) => {
  console.error(err);
  text(res, 500, 'internal server error');
}));
const wss = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const match = url.pathname.match(/^\/ws\/sessions\/([^/]+)$/);
  if (!match || !auth.authenticated(req) || !sameHostOrigin(req)) {
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    socket.destroy();
    return;
  }
  const session = sessions.get(decodeURIComponent(match[1]));
  if (!session) {
    socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => session.attach(ws));
});

server.listen(port, host, () => {
  console.log(`webterm node listening on http://${host}:${port}`);
});

async function route(req, res) {
  const url = new URL(req.url, `http://${req.headers.host}`);
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

  if (url.pathname.startsWith('/api/')) {
    if (!auth.authenticated(req)) {
      text(res, 401, 'unauthorized');
      return;
    }
    await routeAPI(req, res, url);
    return;
  }

  await serveStatic(req, res, root);
}

async function routeAPI(req, res, url) {
  if (req.method === 'GET' && url.pathname === '/api/me') {
    json(res, 200, { username });
    return;
  }
  if (req.method === 'GET' && url.pathname === '/api/sessions') {
    json(res, 200, sessions.list());
    return;
  }
  if (req.method === 'POST' && url.pathname === '/api/sessions') {
    try {
      const body = await readJSON(req);
      const session = sessions.create({ name: body.name, cwd: body.cwd });
      json(res, 201, session.info());
    } catch (err) {
      text(res, 400, err.message);
    }
    return;
  }
  const sessionMatch = url.pathname.match(/^\/api\/sessions\/([^/]+)$/);
  if (sessionMatch && req.method === 'PATCH') {
    try {
      const body = await readJSON(req);
      const session = sessions.rename(decodeURIComponent(sessionMatch[1]), body.name);
      if (!session) {
        text(res, 404, 'session not found');
        return;
      }
      json(res, 200, session.info());
    } catch (err) {
      text(res, 400, err.message);
    }
    return;
  }
  if (sessionMatch && req.method === 'DELETE') {
    if (!sessions.close(decodeURIComponent(sessionMatch[1]))) {
      text(res, 404, 'session not found');
      return;
    }
    res.writeHead(204, { 'Cache-Control': 'no-store' });
    res.end();
    return;
  }
  text(res, 404, 'not found');
}

function splitAddress(value) {
  const index = value.lastIndexOf(':');
  if (index <= 0) return [value, '8080'];
  return [value.slice(0, index), value.slice(index + 1)];
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
