// WebTerm Relay Server — orchestrates HTTP routes, WebSocket handlers, agent registry.
import http from 'node:http';
import https from 'node:https';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';
import { runMigrations } from '../server/db.js';
import { listAdmins, createUser, findByUsername, verifyUserEmail, updatePassword } from '../server/stores/user-store.js';
import { createDevice, findBySecretHash, listByUser, updateLastSeen, deleteDevice } from '../server/stores/device-store.js';
import { revokeAllForUser, purgeExpired } from '../server/stores/token-store.js';
import {
  AuthManager, setAuthCookie, setRefreshCookie, hashPassword,
  parseCookies, getOrCreateDeviceId, COOKIE_NAME, REFRESH_COOKIE_NAME
} from '../server/auth.js';
import { verifySmtpConfig } from '../server/mail.js';
import { verifyOtp as verifyOtpInStore } from '../server/stores/email-verification-store.js';
import { addTrustedDevice, listTrustedDevices, deleteTrustedDevice } from '../server/stores/trusted-device-store.js';
import { serveStatic, text } from '../server/http-utils.js';
import { delay, loadLocalEnv } from '../shared/utils.js';
import { sendJSON } from '../shared/tunnel-protocol.js';
import { AgentRegistry, getDeviceNameFromUa } from './agent-registry.js';
import { createRoutes } from './routes.js';
import { createWsHandlers } from './ws-handlers.js';
import { prefixSessionManagerMessage } from './client-tunnel.js';

loadLocalEnv('..');

if (process.env.RELAY_USERS || process.env.RELAY_PASSWORD || process.env.RELAY_SECRET) {
  console.warn('[Deprecation Warning] Env variables RELAY_USERS, RELAY_PASSWORD, and RELAY_SECRET are deprecated and will be ignored in future versions. Please use DB-based user management.');
}

const port = Number(process.env.RELAY_PORT || '9000');
const auth = new AuthManager();
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'web');

// In-memory state
const registry = new AgentRegistry();
const pendingHttpResponses = new Map();
const activeWsTunnels = new Map();
const pendingP2pOffers = new Map();

// Build context for sub-modules
const routeCtx = {
  auth, getOrCreateDeviceId, registry, pendingHttpResponses, pendingP2pOffers,
  getDeviceNameFromUa,
  findByUsername, updatePassword, createUser, verifyUserEmail,
  createDevice, listByUser, deleteDevice,
  verifyOtpInStore, addTrustedDevice, listTrustedDevices, deleteTrustedDevice,
  hashPassword, setAuthCookie, setRefreshCookie,
  parseCookies, COOKIE_NAME, REFRESH_COOKIE_NAME,
  delay
};

const wsCtx = {
  auth, registry, pendingHttpResponses, activeWsTunnels,
  pendingP2pOffers,
  findBySecretHash, updateLastSeen, text
};

const { route } = createRoutes(routeCtx);
const { handleAgentConnection, handleClientWsTunnel } = createWsHandlers(wsCtx);

// HTTP or HTTPS server
let server;
const sslKeyPath = process.env.RELAY_SSL_KEY;
const sslCertPath = process.env.RELAY_SSL_CERT;
const useHttps = !!(sslKeyPath && sslCertPath && fs.existsSync(sslKeyPath) && fs.existsSync(sslCertPath));

const requestHandler = (req, res) => {
  route(req, res).then((handled) => {
    if (handled === false) serveStatic(req, res, root);
  }).catch((err) => {
    console.error('[Relay Server Error]', err);
    text(res, 500, 'internal server error');
  });
};

if (useHttps) {
  console.log(`[Relay] Starting HTTPS server with key: ${sslKeyPath}, cert: ${sslCertPath}`);
  const options = {
    key: fs.readFileSync(sslKeyPath),
    cert: fs.readFileSync(sslCertPath),
  };
  server = https.createServer(options, requestHandler);
} else {
  console.log('[Relay] Starting HTTP server (no SSL config found)');
  server = http.createServer(requestHandler);
}

// WebSocket servers
const wssAgent = new WebSocketServer({ noServer: true });
const wssManager = new WebSocketServer({ noServer: true });
const wssSession = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, `http://${req.headers.host}`);

  if (url.pathname === '/ws/agent') {
    wssAgent.handleUpgrade(req, socket, head, (ws) => handleAgentConnection(ws));
    return;
  }

  const user = auth.authenticate(req);
  if (!user) {
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    socket.destroy();
    return;
  }

  if (url.pathname === '/ws/sessions' && !url.searchParams.get('deviceId')) {
    wssManager.handleUpgrade(req, socket, head, (ws) => {
      const clientId = url.searchParams.get('clientId') || 'c_' + Math.random().toString(36).substring(2, 15);
      registry.addManagerClient(ws, { userId: user.id, username: user.username, clientId });
      ws.on('close', () => registry.removeManagerClient(ws));
      ws.on('error', () => ws.close());
      registry.pushDevicesToUser(user.id, sendJSON);
    });
    return;
  }

  if (url.pathname.startsWith('/ws/')) {
    const match = url.pathname.match(/^\/ws\/sessions\/([^/]+)$/);
    let deviceId = url.searchParams.get('deviceId');
    let targetPath = url.pathname;
    let transformOutboundText = null;

    if (match) {
      const globalId = decodeURIComponent(match[1]);
      const colonIndex = globalId.indexOf(':');
      if (colonIndex >= 0) {
        deviceId = globalId.substring(0, colonIndex);
        const localId = globalId.substring(colonIndex + 1);
        targetPath = `/ws/sessions/${encodeURIComponent(localId)}`;
      }
    } else if (url.pathname === '/ws/sessions' && deviceId) {
      targetPath = '/ws/sessions';
      transformOutboundText = (text) => prefixSessionManagerMessage(text, deviceId);
    }

    const agent = registry.getAgentForUser(user.id, deviceId);
    if (!agent) {
      socket.write('HTTP/1.1 503 Service Unavailable\r\n\r\n');
      socket.destroy();
      return;
    }

    wssSession.handleUpgrade(req, socket, head, (clientWs) => {
      handleClientWsTunnel(clientWs, agent, targetPath, req, { transformOutboundText });
    });
    return;
  }

  socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
  socket.destroy();
});

// 30s heartbeat + periodic DB lastSeen update
const interval = setInterval(() => {
  wssAgent.clients.forEach((ws) => {
    if (ws.isAlive === false) return ws.terminate();
    ws.isAlive = false;
    ws.ping();
  });

  const now = Date.now();
  for (const agent of registry.getAgentValues()) {
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

server.on('close', () => clearInterval(interval));

// Bootstrap
async function start() {
  verifySmtpConfig();
  console.log('[DB] Ensuring migrations are applied...');
  runMigrations();

  try {
    const deletedCount = purgeExpired();
    if (deletedCount > 0) console.log(`[DB] Purged ${deletedCount} expired/revoked refresh tokens on startup.`);
  } catch (err) {
    console.error('[DB] Failed to purge expired tokens on startup:', err.message);
  }

  setInterval(() => {
    try {
      const deletedCount = purgeExpired();
      if (deletedCount > 0) console.log(`[DB] Scheduled task purged ${deletedCount} expired/revoked refresh tokens.`);
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
      console.log('[Bootstrap] Admin user successfully created. IMPORTANT: Please remove RELAY_BOOTSTRAP_USER and RELAY_BOOTSTRAP_PASSWORD environment variables from env files now.');
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
    const protocol = useHttps ? 'https' : 'http';
    console.log(`WebTerm Relay Server listening on ${protocol}://localhost:${port}`);
  });
}

start().catch(err => {
  console.error('[Relay Fatal Startup Error]', err);
  process.exit(1);
});
