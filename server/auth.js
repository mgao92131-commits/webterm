import crypto from 'node:crypto';
import { promisify } from 'node:util';
import { findById, findByUsername, verifyUserEmail } from './stores/user-store.js';
import { issue, consume, revokeAllForUser } from './stores/token-store.js';
import { sendOtpEmail } from './mail.js';
import {
  createVerification,
  verifyOtp as verifyOtpInStore,
  getLastVerificationTime,
  getDailyUserVerificationCount,
  getDailyIpVerificationCount
} from './stores/email-verification-store.js';
import {
  isDeviceTrusted,
  addTrustedDevice,
  updateDeviceLastSeen
} from './stores/trusted-device-store.js';

const scrypt = promisify(crypto.scrypt);

export const COOKIE_NAME = 'webterm_token';
export const REFRESH_COOKIE_NAME = 'webterm_refresh';

const refreshCache = new Map(); // key: oldHash, value: { expiresAt: number, tokens: { accessToken: string, refreshToken: string } }

function cleanRefreshCache() {
  const now = Date.now();
  for (const [key, val] of refreshCache.entries()) {
    if (now > val.expiresAt) {
      refreshCache.delete(key);
    }
  }
}

function maskEmail(email) {
  if (!email) return '';
  const parts = email.split('@');
  if (parts.length !== 2) return '***';
  const [name, domain] = parts;
  if (name.length <= 2) return name + '***@' + domain;
  return name.substring(0, 2) + '***@' + domain;
}

let jwtSecret = null;
function getJwtSecret() {
  if (jwtSecret) return jwtSecret;
  jwtSecret = process.env.JWT_SECRET;
  if (!jwtSecret) {
    if (process.env.NODE_ENV === 'test') {
      jwtSecret = 'test_fallback_secret_only_for_unit_tests';
    } else {
      console.warn('WARNING: JWT_SECRET environment variable is not set. Generating a temporary random secret for this session.');
      jwtSecret = crypto.randomBytes(32).toString('hex');
    }
  }
  return jwtSecret;
}

// --- Password Hashing with scrypt ---
export async function hashPassword(password) {
  const salt = crypto.randomBytes(16).toString('hex');
  const derivedKey = await scrypt(password, salt, 64, { N: 16384, r: 8, p: 1 });
  return `scrypt$${salt}$${derivedKey.toString('hex')}`;
}

export async function verifyPassword(password, storedHash) {
  try {
    const parts = storedHash.split('$');
    if (parts.length !== 3 || parts[0] !== 'scrypt') {
      return false;
    }
    const salt = parts[1];
    const hash = parts[2];
    const derivedKey = await scrypt(password, salt, 64, { N: 16384, r: 8, p: 1 });
    
    const left = Buffer.from(hash, 'hex');
    const right = derivedKey;
    return left.length === right.length && crypto.timingSafeEqual(left, right);
  } catch {
    return false;
  }
}

// --- Custom JWT HS256 Implementation ---
function base64UrlEncode(strOrBuffer) {
  const buf = Buffer.isBuffer(strOrBuffer) ? strOrBuffer : Buffer.from(strOrBuffer, 'utf8');
  return buf.toString('base64url');
}

function base64UrlDecode(str) {
  return Buffer.from(str, 'base64url').toString('utf8');
}

export function signJwt(payload, secret = getJwtSecret()) {
  const header = { alg: 'HS256', typ: 'JWT' };
  const part1 = base64UrlEncode(JSON.stringify(header));
  const part2 = base64UrlEncode(JSON.stringify(payload));
  const signature = crypto.createHmac('sha256', secret)
    .update(part1 + '.' + part2)
    .digest('base64url');
  return part1 + '.' + part2 + '.' + signature;
}

export function verifyJwt(token, secret = getJwtSecret()) {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const [part1, part2, signature] = parts;
    const expectedSig = crypto.createHmac('sha256', secret)
      .update(part1 + '.' + part2)
      .digest('base64url');
    
    const sigBuf = Buffer.from(signature);
    const expBuf = Buffer.from(expectedSig);
    if (sigBuf.length !== expBuf.length || !crypto.timingSafeEqual(sigBuf, expBuf)) {
      return null;
    }
    
    const payload = JSON.parse(base64UrlDecode(part2));
    if (payload.exp && Date.now() > payload.exp * 1000) {
      return null;
    }
    return payload;
  } catch {
    return null;
  }
}

// --- AuthManager ---
export class AuthManager {
  constructor() {
    this.failures = new Map();
  }

  // Generate and send OTP with rate limits checked
  async sendVerificationOtp(userId, email, purpose, targetDeviceId = null, ipAddress = null) {
    const now = Date.now();
    const lastTime = getLastVerificationTime(userId);
    if (now - lastTime < 60 * 1000) {
      throw new Error('发送过于频繁，请等待 60 秒后再试。');
    }
    
    const dailyUserCount = getDailyUserVerificationCount(userId);
    if (dailyUserCount >= 60) {
      throw new Error('今天发送验证码的次数已达上限（60次）。');
    }
    
    const dailyIpCount = getDailyIpVerificationCount(ipAddress);
    if (dailyIpCount >= 30) {
      throw new Error('该 IP 地址今天发送验证码的次数已达上限（30次）。');
    }
    
    const code = crypto.randomInt(100000, 1000000).toString();
    createVerification(userId, purpose, code, targetDeviceId, ipAddress);
    await sendOtpEmail(email, code, purpose);
    return true;
  }

  issueSession(user) {
    const iat = Math.floor(Date.now() / 1000);
    const exp = iat + 15 * 60; // 15 minutes access token
    const payload = {
      sub: user.id,
      username: user.username,
      role: user.role,
      iat,
      exp
    };
    
    const accessToken = signJwt(payload, getJwtSecret());
    const refreshToken = issue(user.id);
    
    return {
      user: {
        id: user.id,
        username: user.username,
        role: user.role
      },
      accessToken,
      refreshToken
    };
  }

  async login(email, password, deviceId = null, ipAddress = null) {
    console.log('[Login Debug] Attempt:', { email: maskEmail(email), deviceId });
    const user = findByUsername(email);
    if (!user || user.disabled === 1) {
      console.log('[Login Debug] User not found or disabled:', maskEmail(email));
      return null;
    }
    
    const ok = await verifyPassword(password, user.passwordHash);
    if (!ok) {
      console.log('[Login Debug] Password incorrect for:', maskEmail(email));
      return null;
    }

    // Check if email has been verified/activated
    if (user.emailVerifiedAt === null) {
      console.log('[Login Debug] Email not verified:', maskEmail(email));
      return { inactive: true, user: { id: user.id, username: user.username } };
    }

    // Check if device is trusted
    const trusted = isDeviceTrusted(user.id, deviceId);
    if (!trusted) {
      try {
        console.log('[Login Debug] Device untrusted, sending OTP to:', maskEmail(email));
        await this.sendVerificationOtp(user.id, user.username, 'new_device', deviceId, ipAddress);
        return { otpRequired: true, targetDeviceId: deviceId, user: { id: user.id, username: user.username } };
      } catch (err) {
        console.log('[Login Debug] Send OTP error:', err.message);
        return { otpRequired: true, otpError: err.message, targetDeviceId: deviceId, user: { id: user.id, username: user.username } };
      }
    }

    // Device is trusted, perform direct login
    console.log('[Login Debug] Device trusted. Direct login for:', maskEmail(email));
    updateDeviceLastSeen(user.id, deviceId);
    return this.issueSession(user);
  }

  refresh(refreshToken, deviceId = null) {
    const hash = crypto.createHash('sha256').update(refreshToken).digest('hex');

    // Check concurrent refresh cache (10 seconds safety window)
    cleanRefreshCache();
    const cached = refreshCache.get(hash);
    if (cached && Date.now() <= cached.expiresAt) {
      console.log('[Auth Debug] Serving cached token pair for concurrent refresh request');
      return cached.tokens;
    }

    const consumed = consume(hash);
    if (!consumed) return null;
    
    const { userId, isExpired, isRevoked } = consumed;
    
    if (isExpired) return null;

    if (isRevoked) {
      // Re-use detection: revoke all tokens for this user
      revokeAllForUser(userId);
      return null;
    }
    
    const user = findById(userId);
    if (!user || user.disabled === 1) return null;

    // Device verification during token refresh:
    // If user has email verified (meaning using OTP auth) and deviceId is provided,
    // verify if device is still trusted. If untrusted, reject.
    if (user.emailVerifiedAt && deviceId && !isDeviceTrusted(userId, deviceId)) {
      return null;
    }

    if (deviceId) {
      updateDeviceLastSeen(userId, deviceId);
    }
    
    const iat = Math.floor(Date.now() / 1000);
    const exp = iat + 15 * 60; // 15 minutes
    const payload = {
      sub: user.id,
      username: user.username,
      role: user.role,
      iat,
      exp
    };
    
    const accessToken = signJwt(payload, getJwtSecret());
    const newRefreshToken = issue(user.id);
    
    const tokens = {
      accessToken,
      refreshToken: newRefreshToken
    };

    // Cache the issued tokens for 10 seconds to handle concurrent client requests
    refreshCache.set(hash, {
      expiresAt: Date.now() + 10 * 1000,
      tokens
    });

    return tokens;
  }

  logout(refreshToken) {
    const hash = crypto.createHash('sha256').update(refreshToken).digest('hex');
    consume(hash);
  }

  // --- Direct-mode simple auth (uses WEBTERM_PASSWORD) ---
  verify(username, password, remoteAddress) {
    const expectedUser = process.env.WEBTERM_USER || 'admin';
    const expectedPass = process.env.WEBTERM_PASSWORD;
    if (!expectedPass) return false;
    if (username !== expectedUser || password !== expectedPass) {
      this.recordFailure(remoteAddress);
      return false;
    }
    this.clearFailures(remoteAddress);
    return true;
  }

  token() {
    const payload = {
      sub: 0,
      username: process.env.WEBTERM_USER || 'admin',
      role: 'admin',
      iat: Math.floor(Date.now() / 1000),
      exp: Math.floor(Date.now() / 1000) + 24 * 60 * 60,
    };
    return signJwt(payload);
  }

  authenticate(req) {
    const token = parseCookies(req.headers.cookie || '')[COOKIE_NAME];
    if (!token) return null;
    
    const payload = verifyJwt(token, getJwtSecret());
    if (!payload) return null;

    if (payload.sub === 0) {
      const expectedUser = process.env.WEBTERM_USER || 'admin';
      if (payload.username === expectedUser) {
        return {
          id: 0,
          username: expectedUser,
          role: 'admin'
        };
      }
    }
    
    const user = findById(payload.sub);
    if (!user || user.disabled === 1) return null;
    
    return {
      id: user.id,
      username: user.username,
      role: user.role
    };
  }

  failureDelay(remoteAddress = '') {
    const count = this.failures.get(remoteAddress) || 0;
    return Math.min(1000, 250 + count * 150);
  }

  recordFailure(remoteAddress = '') {
    const count = (this.failures.get(remoteAddress) || 0) + 1;
    this.failures.set(remoteAddress, count);
  }

  clearFailures(remoteAddress = '') {
    this.failures.delete(remoteAddress);
  }
}

// --- Cookie Helpers ---
export function setAuthCookie(res, token, secure = false) {
  const cookie = [
    `${COOKIE_NAME}=${encodeURIComponent(token)}`,
    'Path=/',
    'HttpOnly',
    'SameSite=Lax',
    'Max-Age=900', // 15 minutes, matches access token expiration
  ];
  if (secure) cookie.push('Secure');
  res.setHeader('Set-Cookie', cookie.join('; '));
}

export function setRefreshCookie(res, token, secure = false) {
  const cookie = [
    `${REFRESH_COOKIE_NAME}=${encodeURIComponent(token)}`,
    'Path=/api/auth',
    'HttpOnly',
    'SameSite=Lax',
    'Max-Age=2592000', // 30 days, matches refresh token TTL
  ];
  if (secure) cookie.push('Secure');
  
  const existing = res.getHeader('Set-Cookie');
  const cookies = [];
  if (existing) {
    if (Array.isArray(existing)) {
      cookies.push(...existing);
    } else {
      cookies.push(existing);
    }
  }
  cookies.push(cookie.join('; '));
  res.setHeader('Set-Cookie', cookies);
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

export function getOrCreateDeviceId(req, res) {
  const cookies = parseCookies(req.headers.cookie || '');
  let deviceId = cookies['webterm_device_id'];
  if (!deviceId) {
    deviceId = crypto.randomUUID();
    const secureCookie = req.socket.encrypted || process.env.WEBTERM_COOKIE_SECURE === '1';
    const cookieParts = [
      `webterm_device_id=${deviceId}`,
      'Path=/',
      'HttpOnly',
      'SameSite=Lax',
      'Max-Age=31536000'
    ];
    if (secureCookie) cookieParts.push('Secure');
    
    const existing = res.getHeader('Set-Cookie');
    const cookiesList = [];
    if (existing) {
      if (Array.isArray(existing)) {
        cookiesList.push(...existing);
      } else {
        cookiesList.push(existing);
      }
    }
    cookiesList.push(cookieParts.join('; '));
    res.setHeader('Set-Cookie', cookiesList);
  }
  return deviceId;
}

export const _testRefreshCache = process.env.NODE_ENV === 'test' ? refreshCache : null;

