import test from 'node:test';
import assert from 'node:assert/strict';
import db, { runMigrations } from './db.js';
import { createUser, setDisabled } from './stores/user-store.js';
import { addTrustedDevice } from './stores/trusted-device-store.js';
import { AuthManager, hashPassword, verifyPassword, signJwt, verifyJwt, COOKIE_NAME } from './auth.js';

// Setup database once
process.env.NODE_ENV = 'test';
runMigrations();

function clearDb() {
  db.exec('DELETE FROM users');
  db.exec('DELETE FROM devices');
  db.exec('DELETE FROM refresh_tokens');
  db.exec('DELETE FROM trusted_devices');
  db.exec('DELETE FROM email_verifications');
}

test('password hashing and verification works', async () => {
  clearDb();
  const pass = 'super-secret-password-123';
  const hash = await hashPassword(pass);
  assert.match(hash, /^scrypt\$[a-f0-9]+\$[a-f0-9]+$/);
  
  assert.equal(await verifyPassword(pass, hash), true);
  assert.equal(await verifyPassword('wrong-password', hash), false);
});

test('JWT signing and verifying works', () => {
  clearDb();
  const secret = 'my_test_secret_key_123';
  const payload = { sub: 1, username: 'testuser', role: 'admin' };
  
  const token = signJwt(payload, secret);
  const decoded = verifyJwt(token, secret);
  
  assert.ok(decoded);
  assert.equal(decoded.sub, 1);
  assert.equal(decoded.username, 'testuser');
  assert.equal(decoded.role, 'admin');

  // Verify failure on wrong secret
  assert.equal(verifyJwt(token, 'wrong_secret'), null);

  // Verify failure on expired token
  const expiredPayload = { ...payload, exp: Math.floor(Date.now() / 1000) - 10 };
  const expiredToken = signJwt(expiredPayload, secret);
  assert.equal(verifyJwt(expiredToken, secret), null);
});

test('AuthManager login flow works', async () => {
  clearDb();
  const auth = new AuthManager();
  
  const passHash = await hashPassword('password123');
  // Create email verified user
  const user = createUser('john', passHash, 'user', new Date().toISOString());
  // Trust test device
  addTrustedDevice(user.id, 'test_device_1');
  
  // Successful login
  const res = await auth.login('john', 'password123', 'test_device_1');
  assert.ok(res);
  assert.equal(res.user.username, 'john');
  assert.ok(res.accessToken);
  assert.ok(res.refreshToken);
  
  // Failed login (wrong password)
  const failedRes = await auth.login('john', 'wrongpass', 'test_device_1');
  assert.equal(failedRes, null);
  
  // Disabled user login fails
  setDisabled(user.id, true);
  const disabledRes = await auth.login('john', 'password123', 'test_device_1');
  assert.equal(disabledRes, null);
});

test('AuthManager refresh and token reuse detection works', async () => {
  clearDb();
  const auth = new AuthManager();
  
  const passHash = await hashPassword('password123');
  const user = createUser('bob', passHash, 'user', new Date().toISOString());
  addTrustedDevice(user.id, 'test_device_2');
  
  const loginRes = await auth.login('bob', 'password123', 'test_device_2');
  assert.ok(loginRes);
  
  // Refresh successfully
  const refreshRes = auth.refresh(loginRes.refreshToken, 'test_device_2');
  assert.ok(refreshRes);
  assert.ok(refreshRes.accessToken);
  assert.ok(refreshRes.refreshToken);
  
  // Attempt token reuse (replay attack)
  // Consuming the old loginRes.refreshToken again should trigger revocation for all tokens
  const reuseRes = auth.refresh(loginRes.refreshToken, 'test_device_2');
  assert.equal(reuseRes, null);
  
  // The newly generated refreshRes.refreshToken should now also be revoked
  const afterRevokeRes = auth.refresh(refreshRes.refreshToken, 'test_device_2');
  assert.equal(afterRevokeRes, null);
});

test('AuthManager authenticate verifies cookie and disabled status', async () => {
  clearDb();
  const auth = new AuthManager();
  
  const passHash = await hashPassword('password123');
  const user = createUser('alice', passHash, 'user', new Date().toISOString());
  addTrustedDevice(user.id, 'test_device_3');
  
  const loginRes = await auth.login('alice', 'password123', 'test_device_3');
  assert.ok(loginRes);
  
  // Mock request
  const req = {
    headers: {
      cookie: `${COOKIE_NAME}=${loginRes.accessToken}`
    }
  };
  
  const authedUser = auth.authenticate(req);
  assert.ok(authedUser);
  assert.equal(authedUser.username, 'alice');
  assert.equal(authedUser.id, user.id);
  
  // Disable user and verify authentication fails
  setDisabled(user.id, true);
  const authedUserAfterDisable = auth.authenticate(req);
  assert.equal(authedUserAfterDisable, null);
});

