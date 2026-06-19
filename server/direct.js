import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';
import { AuthManager, setAuthCookie } from './auth.js';
import { json, readJSON, sameHostOrigin, serveStatic, text } from './http-utils.js';
import { selectWebSocketProtocol } from './protocol-binary.js';
import { SessionManager } from './session-manager.js';
import { delay } from '../shared/utils.js';

export class DirectServer {
  constructor() {
    const password = process.env.WEBTERM_PASSWORD;
    if (!password) {
      console.error('WEBTERM_PASSWORD must be set');
      process.exit(1);
    }
    this.username = process.env.WEBTERM_USER || 'admin';
    const addr = process.env.WEBTERM_ADDR || '127.0.0.1:8080';
    const [h, p] = DirectServer.splitAddress(addr);
    this.host = h;
    this.port = Number(p);
    this.root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'web');
    this.auth = new AuthManager({ username: this.username, password });
    this.sessions = new SessionManager();
  }

  start() {
    this.server = http.createServer((req, res) => this.route(req, res).catch((err) => {
      console.error(err);
      text(res, 500, 'internal server error');
    }));
    this.wss = new WebSocketServer({
      noServer: true,
      handleProtocols: selectWebSocketProtocol,
      perMessageDeflate: {
        threshold: 1024,
        serverNoContextTakeover: true,
        clientNoContextTakeover: true,
        zlibDeflateOptions: { level: 3 },
      },
    });

    this.server.on('upgrade', (req, socket, head) => this.handleUpgrade(req, socket, head));

    this.server.listen(this.port, this.host, () => {
      console.log(`webterm node listening on http://${this.host}:${this.port}`);
    });
  }

  handleUpgrade(req, socket, head) {
    const url = new URL(req.url, `http://${req.headers.host}`);
    if (url.pathname === '/ws/sessions') {
      if (!this.auth.authenticate(req) || !sameHostOrigin(req)) {
        socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
        socket.destroy();
        return;
      }
      this.wss.handleUpgrade(req, socket, head, (ws) => this.sessions.attachManager(ws));
      return;
    }

    const match = url.pathname.match(/^\/ws\/sessions\/([^/]+)$/);
    if (!match || !this.auth.authenticate(req) || !sameHostOrigin(req)) {
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
      socket.destroy();
      return;
    }
    const session = this.sessions.get(decodeURIComponent(match[1]));
    if (!session) {
      socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
      socket.destroy();
      return;
    }
    this.wss.handleUpgrade(req, socket, head, (ws) => session.attach(ws, {
      protocolHint: ws.protocol,
    }));
  }

  async route(req, res) {
    const url = new URL(req.url, `http://${req.headers.host}`);
    if (req.method === 'POST' && url.pathname === '/api/login') {
      const body = await readJSON(req);
      if (!this.auth.verify(body.username, body.password, req.socket.remoteAddress)) {
        await delay(this.auth.failureDelay(req.socket.remoteAddress));
        text(res, 401, 'invalid credentials');
        return;
      }
      setAuthCookie(res, this.auth.token(), req.socket.encrypted || process.env.WEBTERM_COOKIE_SECURE === '1');
      json(res, 200, { username: this.username, mode: 'direct' });
      return;
    }

    if (url.pathname.startsWith('/api/')) {
      if (!this.auth.authenticate(req)) {
        text(res, 401, 'unauthorized');
        return;
      }
      await this.routeAPI(req, res, url);
      return;
    }

    await serveStatic(req, res, this.root);
  }

  async routeAPI(req, res, url) {
    if (req.method === 'GET' && url.pathname === '/api/me') {
      json(res, 200, { username: this.username, mode: 'direct' });
      return;
    }
    if (req.method === 'GET' && url.pathname === '/api/sessions') {
      json(res, 200, this.sessions.list());
      return;
    }
    if (req.method === 'POST' && url.pathname === '/api/sessions') {
      try {
        const body = await readJSON(req);
        const session = this.sessions.create({ name: body.name, cwd: body.cwd });
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
        const session = this.sessions.rename(decodeURIComponent(sessionMatch[1]), body.name);
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
      if (!this.sessions.close(decodeURIComponent(sessionMatch[1]))) {
        text(res, 404, 'session not found');
        return;
      }
      res.writeHead(204, { 'Cache-Control': 'no-store' });
      res.end();
      return;
    }
    text(res, 404, 'not found');
  }

  static splitAddress(value) {
    const index = value.lastIndexOf(':');
    if (index <= 0) return [value, '8080'];
    return [value.slice(0, index), value.slice(index + 1)];
  }
}
