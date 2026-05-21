import test from 'node:test';
import assert from 'node:assert/strict';
import { defaultShell, validateCWD } from './pty-host.js';

test('default shell is defined', () => {
  const shell = defaultShell();
  assert.equal(typeof shell.command, 'string');
  assert.ok(shell.command.length > 0);
});

test('default shell ignores invalid unix shell env values', { skip: process.platform === 'win32' }, () => {
  const shell = defaultShell({
    SHELL: '/definitely/missing/shell',
  });
  assert.match(shell.command, /^\/bin\//);
});

test('WEBTERM_SHELL overrides the default unix shell', { skip: process.platform === 'win32' }, () => {
  const shell = defaultShell({
    WEBTERM_SHELL: '/bin/sh',
    SHELL: '/definitely/missing/shell',
  });
  assert.equal(shell.command, '/bin/sh');
});

test('validate cwd rejects missing path', () => {
  assert.throws(() => validateCWD('Z:\\webterm\\missing\\path'));
});
