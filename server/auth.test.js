import test from 'node:test';
import assert from 'node:assert/strict';
import { AuthManager, COOKIE_NAME } from './auth.js';

test('auth token is stable for the same credentials', () => {
  const auth = new AuthManager({ username: 'admin', password: 'secret' });
  const token = auth.token();
  assert.equal(auth.verify('admin', 'secret'), true);
  assert.equal(auth.authenticated({ headers: { cookie: `${COOKIE_NAME}=${encodeURIComponent(token)}` } }), 'admin');

  const restarted = new AuthManager({ username: 'admin', password: 'secret' });
  assert.equal(restarted.authenticated({ headers: { cookie: `${COOKIE_NAME}=${encodeURIComponent(token)}` } }), 'admin');
});

test('auth rejects wrong credentials', () => {
  const auth = new AuthManager({ username: 'admin', password: 'secret' });
  assert.equal(auth.verify('admin', 'bad'), false);
});
