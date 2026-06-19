import crypto from 'node:crypto';
import db from '../db.js';

const insertTokenStmt = db.prepare(`
  INSERT INTO refresh_tokens (user_id, token_hash, expires_at)
  VALUES (?, ?, ?)
`);

const findTokenByHashStmt = db.prepare(`
  SELECT user_id AS userId, expires_at AS expiresAt, revoked_at AS revokedAt
  FROM refresh_tokens
  WHERE token_hash = ?
`);

const revokeTokenStmt = db.prepare(`
  UPDATE refresh_tokens
  SET revoked_at = datetime('now')
  WHERE token_hash = ?
`);

const revokeAllForUserStmt = db.prepare(`
  UPDATE refresh_tokens
  SET revoked_at = datetime('now')
  WHERE user_id = ? AND revoked_at IS NULL
`);

const purgeExpiredStmt = db.prepare(`
  DELETE FROM refresh_tokens
  WHERE expires_at < datetime('now') OR revoked_at < datetime('now', '-7 days')
`);

export function issue(userId, ttlDays = 30) {
  const token = crypto.randomBytes(32).toString('hex');
  const tokenHash = crypto.createHash('sha256').update(token).digest('hex');
  
  const expiresAt = new Date(Date.now() + ttlDays * 24 * 60 * 60 * 1000).toISOString();
  
  insertTokenStmt.run(userId, tokenHash, expiresAt);
  return token;
}

export function consume(tokenHash) {
  // Use transaction to ensure safe checking and revoking
  return db.transaction(() => {
    const token = findTokenByHashStmt.get(tokenHash);
    if (!token) return null;

    // Check if already expired
    const isExpired = new Date(token.expiresAt).getTime() < Date.now();

    // Revoke the token immediately upon usage
    revokeTokenStmt.run(tokenHash);

    return {
      userId: token.userId,
      isExpired,
      isRevoked: token.revokedAt !== null
    };
  })();
}

export function revokeAllForUser(userId) {
  const result = revokeAllForUserStmt.run(userId);
  return result.changes > 0;
}

export function purgeExpired() {
  const result = purgeExpiredStmt.run();
  return result.changes;
}
