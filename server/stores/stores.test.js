import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import db, { runMigrations } from '../db.js';
import * as userStore from './user-store.js';
import * as deviceStore from './device-store.js';
import * as tokenStore from './token-store.js';

// Setup database once
process.env.NODE_ENV = 'test';
runMigrations();

function clearDb() {
  db.exec('DELETE FROM users');
  db.exec('DELETE FROM devices');
  db.exec('DELETE FROM refresh_tokens');
}

test('user-store operations', () => {
  clearDb();
  
  // Create user
  const user = userStore.createUser('testuser', 'some_hash', 'user');
  assert.ok(user.id);
  assert.equal(user.username, 'testuser');
  assert.equal(user.role, 'user');
  assert.equal(user.disabled, 0);

  // Find by username
  const foundByUsername = userStore.findByUsername('testuser');
  assert.ok(foundByUsername);
  assert.equal(foundByUsername.id, user.id);
  assert.equal(foundByUsername.passwordHash, 'some_hash');

  // Find by id
  const foundById = userStore.findById(user.id);
  assert.ok(foundById);
  assert.equal(foundById.username, 'testuser');

  // Set disabled
  const disabledSuccess = userStore.setDisabled(user.id, true);
  assert.equal(disabledSuccess, true);
  const userAfterDisable = userStore.findById(user.id);
  assert.equal(userAfterDisable.disabled, 1);

  // List admins
  userStore.createUser('adminuser', 'admin_hash', 'admin');
  const admins = userStore.listAdmins();
  assert.equal(admins.length, 1);
  assert.equal(admins[0].username, 'adminuser');
});

test('device-store operations', () => {
  clearDb();
  
  const user = userStore.createUser('device_owner', 'hash', 'user');
  
  // Create device
  const device = deviceStore.createDevice(user.id, 'My Device');
  assert.ok(device.id);
  assert.equal(device.deviceName, 'My Device');
  assert.ok(device.agentSecret); // Plain secret returned once
  
  // Find by secret hash
  const secretHash = crypto.createHash('sha256').update(device.agentSecret).digest('hex');
  const foundDevice = deviceStore.findBySecretHash(secretHash);
  assert.ok(foundDevice);
  assert.equal(foundDevice.id, device.id);
  assert.equal(foundDevice.username, 'device_owner');
  assert.equal(foundDevice.disabled, 0);

  // List by user
  const devices = deviceStore.listByUser(user.id);
  assert.equal(devices.length, 1);
  assert.equal(devices[0].deviceName, 'My Device');

  // Update last seen
  const updateSuccess = deviceStore.updateLastSeen(device.id);
  assert.equal(updateSuccess, true);

  // Delete device
  const deleteSuccess = deviceStore.deleteDevice(device.id);
  assert.equal(deleteSuccess, true);
  const afterDeleteDevices = deviceStore.listByUser(user.id);
  assert.equal(afterDeleteDevices.length, 0);
});

test('token-store operations', () => {
  clearDb();
  
  const user = userStore.createUser('token_owner', 'hash', 'user');
  
  // Issue token
  const token = tokenStore.issue(user.id, 30);
  assert.ok(token);
  
  const tokenHash = crypto.createHash('sha256').update(token).digest('hex');
  
  // Consume token (first attempt: successful and revokes)
  const consumeRes = tokenStore.consume(tokenHash);
  assert.ok(consumeRes);
  assert.equal(consumeRes.userId, user.id);
  assert.equal(consumeRes.isExpired, false);
  assert.equal(consumeRes.isRevoked, false); // Not revoked *before* this consumption

  // Consume token (second attempt: now should show isRevoked = true)
  const consumeRes2 = tokenStore.consume(tokenHash);
  assert.ok(consumeRes2);
  assert.equal(consumeRes2.isRevoked, true);

  // Revoke all for user
  const token2 = tokenStore.issue(user.id, 30);
  const tokenHash2 = crypto.createHash('sha256').update(token2).digest('hex');
  tokenStore.revokeAllForUser(user.id);
  
  const consumeRes3 = tokenStore.consume(tokenHash2);
  assert.ok(consumeRes3);
  assert.equal(consumeRes3.isRevoked, true);
});
