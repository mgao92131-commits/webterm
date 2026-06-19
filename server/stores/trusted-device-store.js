import db from '../db.js';

const insertTrustedDeviceStmt = db.prepare(`
  INSERT INTO trusted_devices (user_id, device_id, device_name, last_seen_at)
  VALUES (?, ?, ?, datetime('now'))
  ON CONFLICT(user_id, device_id) DO UPDATE SET
    device_name = COALESCE(excluded.device_name, trusted_devices.device_name),
    last_seen_at = datetime('now')
`);

const findTrustedDeviceStmt = db.prepare(`
  SELECT id, user_id AS userId, device_id AS deviceId, device_name AS deviceName, last_seen_at AS lastSeenAt, created_at AS createdAt
  FROM trusted_devices
  WHERE user_id = ? AND device_id = ?
`);

const listTrustedDevicesStmt = db.prepare(`
  SELECT id, device_id AS deviceId, device_name AS deviceName, last_seen_at AS lastSeenAt, created_at AS createdAt
  FROM trusted_devices
  WHERE user_id = ?
  ORDER BY id DESC
`);

const deleteTrustedDeviceStmt = db.prepare(`
  DELETE FROM trusted_devices
  WHERE user_id = ? AND id = ?
`);

const updateDeviceLastSeenStmt = db.prepare(`
  UPDATE trusted_devices
  SET last_seen_at = datetime('now')
  WHERE user_id = ? AND device_id = ?
`);

export function addTrustedDevice(userId, deviceId, deviceName = null) {
  insertTrustedDeviceStmt.run(userId, deviceId, deviceName);
  return { userId, deviceId, deviceName };
}

export function isDeviceTrusted(userId, deviceId) {
  if (!deviceId) return false;
  const row = findTrustedDeviceStmt.get(userId, deviceId);
  return !!row;
}

export function listTrustedDevices(userId) {
  return listTrustedDevicesStmt.all(userId);
}

export function deleteTrustedDevice(userId, id) {
  const result = deleteTrustedDeviceStmt.run(userId, id);
  return result.changes > 0;
}

export function updateDeviceLastSeen(userId, deviceId) {
  const result = updateDeviceLastSeenStmt.run(userId, deviceId);
  return result.changes > 0;
}
