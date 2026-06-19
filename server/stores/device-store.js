import crypto from 'node:crypto';
import db from '../db.js';

const insertDeviceStmt = db.prepare(`
  INSERT INTO devices (user_id, device_name, agent_secret_hash)
  VALUES (?, ?, ?)
`);

const findBySecretHashStmt = db.prepare(`
  SELECT d.id AS id, d.device_name AS deviceName, d.user_id AS userId, u.username, u.disabled
  FROM devices d
  JOIN users u ON d.user_id = u.id
  WHERE d.agent_secret_hash = ?
`);

const listByUserStmt = db.prepare(`
  SELECT id, device_name AS deviceName, last_seen_at AS lastSeenAt, created_at AS createdAt
  FROM devices
  WHERE user_id = ?
`);

const updateLastSeenStmt = db.prepare(`
  UPDATE devices
  SET last_seen_at = datetime('now')
  WHERE id = ?
`);

const deleteDeviceStmt = db.prepare(`
  DELETE FROM devices
  WHERE id = ?
`);

export function createDevice(userId, deviceName) {
  const agentSecret = crypto.randomBytes(32).toString('hex');
  const agentSecretHash = crypto.createHash('sha256').update(agentSecret).digest('hex');

  const info = insertDeviceStmt.run(userId, deviceName, agentSecretHash);
  return {
    id: info.lastInsertRowid,
    userId,
    deviceName,
    agentSecret // Returned only once at creation
  };
}

export function findBySecretHash(secretHash) {
  return findBySecretHashStmt.get(secretHash) || null;
}

export function listByUser(userId) {
  return listByUserStmt.all(userId);
}

export function updateLastSeen(id) {
  const result = updateLastSeenStmt.run(id);
  return result.changes > 0;
}

export function deleteDevice(id) {
  const result = deleteDeviceStmt.run(id);
  return result.changes > 0;
}
