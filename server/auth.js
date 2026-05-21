import { createHmac, timingSafeEqual } from 'node:crypto';

export const COOKIE_NAME = 'webterm_token';

export class AuthManager {
  constructor({ username, password }) {
    this.username = username || 'admin';
    this.password = password;
    this.failures = new Map();
  }

  verify(username, password, remoteAddress = '') {
    const ok = safeEqual(username || '', this.username) && safeEqual(password || '', this.password);
    if (!ok) {
      const count = (this.failures.get(remoteAddress) || 0) + 1;
      this.failures.set(remoteAddress, count);
      return false;
    }
    this.failures.delete(remoteAddress);
    return true;
  }

  token() {
    return signToken(this.username, this.password);
  }

  authenticated(req) {
    const token = parseCookies(req.headers.cookie || '')[COOKIE_NAME];
    return Boolean(token) && safeEqual(token, this.token());
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
  return 'v1-' + createHmac('sha256', password).update(username).digest('base64url');
}

function safeEqual(a, b) {
  const left = Buffer.from(String(a));
  const right = Buffer.from(String(b));
  return left.length === right.length && timingSafeEqual(left, right);
}
