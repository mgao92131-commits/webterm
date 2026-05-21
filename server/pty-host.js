import os from 'node:os';
import { constants } from 'node:fs';
import { accessSync, existsSync, statSync } from 'node:fs';
import { spawn } from 'node-pty';

export function defaultShell(env = process.env) {
  if (process.platform === 'win32') {
    return { command: env.WEBTERM_SHELL || 'pwsh.exe', args: ['-NoLogo'] };
  }
  const candidates = [
    env.WEBTERM_SHELL,
    env.SHELL,
    '/bin/zsh',
    '/bin/bash',
    '/bin/sh',
  ];
  const command = candidates.find((candidate) => isExecutableFile(candidate));
  if (!command) {
    throw new Error('no executable shell found; set WEBTERM_SHELL to a valid shell path');
  }
  return { command, args: [] };
}

export function validateCWD(cwd) {
  const resolved = cwd || process.cwd();
  if (!existsSync(resolved) || !statSync(resolved).isDirectory()) {
    throw new Error(`cwd does not exist or is not a directory: ${resolved}`);
  }
  return resolved;
}

export function createPty({ cwd, cols = 100, rows = 30 }) {
  const shell = defaultShell();
  return {
    shell,
    process: spawn(shell.command, shell.args, {
      name: 'xterm-256color',
      cols,
      rows,
      cwd,
      env: {
        ...process.env,
        TERM: 'xterm-256color',
        COLORTERM: 'truecolor',
        WEBTERM: '1',
      },
      useConpty: os.platform() === 'win32',
    }),
  };
}

function isExecutableFile(candidate) {
  if (!candidate || !candidate.startsWith('/')) return false;
  try {
    accessSync(candidate, constants.X_OK);
    return statSync(candidate).isFile();
  } catch {
    return false;
  }
}
