import { createHmac, timingSafeEqual } from 'node:crypto';

export const COOKIE_NAME = 'webterm_token';

export class AuthManager {
  constructor({ username, password, users }) {
    this.failures = new Map();
    if (users && Array.isArray(users)) {
      this.users = new Map(users.map(u => [u.username, u]));
    } else {
      const defaultUser = username || 'admin';
      this.users = new Map([[defaultUser, { username: defaultUser, password }]]);
    }
  }

  verify(username, password, remoteAddress = '') {
    const user = this.users.get(username || '');
    const ok = user && safeEqual(password || '', user.password);
    if (!ok) {
      const count = (this.failures.get(remoteAddress) || 0) + 1;
      this.failures.set(remoteAddress, count);
      return false;
    }
    this.failures.delete(remoteAddress);
    return true;
  }

  token(username) {
    let targetUser = username;
    if (!targetUser) {
      if (this.users.size === 1) {
        targetUser = Array.from(this.users.keys())[0];
      } else {
        return '';
      }
    }
    const user = this.users.get(targetUser);
    if (!user) return '';
    return signToken(targetUser, user.password);
  }

  authenticated(req) {
    const token = parseCookies(req.headers.cookie || '')[COOKIE_NAME];
    if (!token) return null;
    
    // Support new multi-user token format: v1-${base64url(username)}-${signature}
    // As well as old single-user token format: v1-${signature}
    const match = token.match(/^v1-([-A-Za-z0-9_]+)-([-A-Za-z0-9_]{43})$/);
    if (match) {
      try {
        const username = Buffer.from(match[1], 'base64url').toString('utf8');
        const user = this.users.get(username);
        if (!user) return null;
        const expectedToken = signToken(username, user.password);
        if (safeEqual(token, expectedToken)) {
          return username;
        }
      } catch {
        return null;
      }
    } else {
      // Legacy single-user compatibility
      if (this.users.size === 1) {
        const username = Array.from(this.users.keys())[0];
        const user = this.users.get(username);
        const expectedOldToken = 'v1-' + createHmac('sha256', user.password).update(username).digest('base64url');
        if (safeEqual(token, expectedOldToken)) {
          return username;
        }
      }
    }
    return null;
  }

  failureDelay(remoteAddress = '') {
    const count = this.failures.get(remoteAddress) || 0;
    return Math.min(1000, 250 + count * 150);
  }
}

export function setAuthCookie(res, token, secure = false) {
  const cookie = [
    `${COOKIE_NAME}=${encodeURIComponent(token)}`,
    'Path=/',
    'HttpOnly',
    'SameSite=Lax',
  ];
  if (secure) cookie.push('Secure');
  res.setHeader('Set-Cookie', cookie.join('; '));
}

export function parseCookies(header) {
  const cookies = {};
  for (const part of header.split(';')) {
    const index = part.indexOf('=');
    if (index < 0) continue;
    const key = part.slice(0, index).trim();
    const value = part.slice(index + 1).trim();
    if (key) cookies[key] = decodeURIComponent(value);
  }
  return cookies;
}

function signToken(username, password) {
  const sig = createHmac('sha256', password).update(username).digest('base64url');
  const encodedUser = Buffer.from(username, 'utf8').toString('base64url');
  return `v1-${encodedUser}-${sig}`;
}

function safeEqual(a, b) {
  const left = Buffer.from(String(a));
  const right = Buffer.from(String(b));
  return left.length === right.length && timingSafeEqual(left, right);
}
