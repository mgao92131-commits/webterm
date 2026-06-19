import crypto from 'node:crypto';
import db from '../db.js';

// Insert a new verification record
const insertVerificationStmt = db.prepare(`
  INSERT INTO email_verifications (user_id, purpose, code_hash, target_device_id, ip_address, expires_at)
  VALUES (?, ?, ?, ?, ?, datetime('now', ?))
`);

// Get active verification record by user and purpose
const findActiveVerificationStmt = db.prepare(`
  SELECT id, user_id AS userId, purpose, code_hash AS codeHash, target_device_id AS targetDeviceId,
         expires_at AS expiresAt, consumed_at AS consumedAt, ip_address AS ipAddress, failed_attempts AS failedAttempts
  FROM email_verifications
  WHERE user_id = ? AND purpose = ? AND consumed_at IS NULL AND expires_at > datetime('now')
  ORDER BY id DESC
  LIMIT 1
`);

// Update consumed_at (consume OTP)
const consumeVerificationStmt = db.prepare(`
  UPDATE email_verifications
  SET consumed_at = datetime('now')
  WHERE id = ?
`);

// Increment failed attempts
const incrementFailedAttemptsStmt = db.prepare(`
  UPDATE email_verifications
  SET failed_attempts = failed_attempts + 1
  WHERE id = ?
`);

// Rate limit checks
const lastVerificationStmt = db.prepare(`
  SELECT created_at AS createdAt
  FROM email_verifications
  WHERE user_id = ?
  ORDER BY id DESC
  LIMIT 1
`);

const dailyUserCountStmt = db.prepare(`
  SELECT COUNT(*) AS count
  FROM email_verifications
  WHERE user_id = ? AND created_at > datetime('now', '-1 day')
`);

const dailyIpCountStmt = db.prepare(`
  SELECT COUNT(*) AS count
  FROM email_verifications
  WHERE ip_address = ? AND created_at > datetime('now', '-1 day')
`);

function sha256(text) {
  return crypto.createHash('sha256').update(text).digest('hex');
}

export function createVerification(userId, purpose, code, targetDeviceId = null, ipAddress = null, expiresMinutes = 10) {
  const codeHash = sha256(code);
  const expiresModifier = `+${expiresMinutes} minutes`;
  const info = insertVerificationStmt.run(userId, purpose, codeHash, targetDeviceId, ipAddress, expiresModifier);
  return {
    id: info.lastInsertRowid,
    userId,
    purpose,
    codeHash,
    targetDeviceId,
    ipAddress
  };
}

/**
 * Verifies an OTP code for a user and purpose.
 * Handles failed attempts locking, timingSafeEqual, and device binding check.
 * Returns: { valid: boolean, verification: object | null }
 */
export function verifyOtp(userId, purpose, code, targetDeviceId = null) {
  const verification = findActiveVerificationStmt.get(userId, purpose);
  if (!verification) {
    return { valid: false, reason: 'expired_or_not_found' };
  }

  const expectedHash = sha256(code);
  const left = Buffer.from(verification.codeHash, 'hex');
  const right = Buffer.from(expectedHash, 'hex');
  const isMatch = left.length === right.length && crypto.timingSafeEqual(left, right);

  if (!isMatch) {
    incrementFailedAttemptsStmt.run(verification.id);
    const updated = findActiveVerificationStmt.get(userId, purpose);
    // Limit to 5 attempts, consume/lock OTP if reached
    if (updated && updated.failedAttempts >= 5) {
      consumeVerificationStmt.run(updated.id);
      return { valid: false, reason: 'too_many_failed_attempts' };
    }
    return { valid: false, reason: 'incorrect_code' };
  }

  // P1: Check device binding if verification record has a targetDeviceId bound
  if (verification.targetDeviceId && verification.targetDeviceId !== targetDeviceId) {
    // Consume and invalidate OTP immediately on mismatch to prevent replay and leakage
    consumeVerificationStmt.run(verification.id);
    return { valid: false, reason: 'device_mismatch' };
  }

  consumeVerificationStmt.run(verification.id);
  return { valid: true, verification };
}

// Get the last verification creation time for rate limit checking
export function getLastVerificationTime(userId) {
  const row = lastVerificationStmt.get(userId);
  return row ? new Date(row.createdAt + 'Z').getTime() : 0; // SQLite stores datetime in UTC without Z
}

// Get total verifications sent to a user in the last 24h
export function getDailyUserVerificationCount(userId) {
  const row = dailyUserCountStmt.get(userId);
  return row ? row.count : 0;
}

// Get total verifications sent from an IP in the last 24h
export function getDailyIpVerificationCount(ipAddress) {
  if (!ipAddress) return 0;
  const row = dailyIpCountStmt.get(ipAddress);
  return row ? row.count : 0;
}
