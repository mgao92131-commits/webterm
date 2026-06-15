import test from 'node:test';
import assert from 'node:assert/strict';
import { sameHostOrigin } from './http-utils.js';

test('sameHostOrigin accepts matching origins', () => {
  assert.equal(sameHostOrigin({
    headers: {
      host: 'example.test:8080',
      origin: 'http://example.test:8080',
    },
  }), true);
});

test('sameHostOrigin rejects mismatched origins', () => {
  assert.equal(sameHostOrigin({
    headers: {
      host: 'example.test:8080',
      origin: 'http://evil.test',
    },
  }), false);
});

test('sameHostOrigin allows empty origin by default for non-browser clients', () => {
  const previous = process.env.WEBTERM_STRICT_ORIGIN;
  delete process.env.WEBTERM_STRICT_ORIGIN;
  try {
    assert.equal(sameHostOrigin({ headers: { host: 'example.test:8080' } }), true);
  } finally {
    restoreEnv('WEBTERM_STRICT_ORIGIN', previous);
  }
});

test('sameHostOrigin rejects empty origin in strict mode', () => {
  const previous = process.env.WEBTERM_STRICT_ORIGIN;
  process.env.WEBTERM_STRICT_ORIGIN = '1';
  try {
    assert.equal(sameHostOrigin({ headers: { host: 'example.test:8080' } }), false);
  } finally {
    restoreEnv('WEBTERM_STRICT_ORIGIN', previous);
  }
});

function restoreEnv(name, value) {
  if (value === undefined) {
    delete process.env[name];
  } else {
    process.env[name] = value;
  }
}
