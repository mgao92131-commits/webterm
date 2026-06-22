import os from 'node:os';
import { constants } from 'node:fs';
import { accessSync, existsSync, statSync } from 'node:fs';
import { spawn } from 'node-pty';

export function defaultShell(env = process.env, platform = process.platform) {
  if (platform === 'win32') {
    const command = findWindowsShell(env);
    if (!command) {
      throw new Error('no executable shell found; set WEBTERM_SHELL to a valid shell path');
    }
    return {
      command,
      args: command.toLowerCase().endsWith('cmd.exe') ? [] : ['-NoLogo'],
    };
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

export function buildPtyEnv(source = process.env) {
  const env = {
    ...source,
    TERM: 'xterm-256color',
    COLORTERM: 'truecolor',
    WEBTERM: '1',
  };
  delete env.NO_COLOR;
  return env;
}

export function createPty({ cwd, cols = 100, rows = 30 }) {
  const shell = defaultShell();
  const env = buildPtyEnv();
  return {
    shell,
    process: spawn(shell.command, shell.args, {
      name: 'xterm-256color',
      cols,
      rows,
      cwd,
      env,
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

function findWindowsShell(env) {
  if (env.WEBTERM_SHELL) return env.WEBTERM_SHELL;
  const candidates = [
    env.ComSpec,
    'pwsh.exe',
    'powershell.exe',
    'cmd.exe',
  ];
  return candidates.find((candidate) => isWindowsCommand(candidate, env));
}

function isWindowsCommand(candidate, env) {
  if (!candidate) return false;
  if (/[\\/]/.test(candidate)) return existsSync(candidate);
  const pathValue = env.PATH || env.Path || '';
  const extensions = (env.PATHEXT || '.EXE;.CMD;.BAT;.COM')
    .split(';')
    .filter(Boolean);
  const names = /\.[^\\/]+$/.test(candidate)
    ? [candidate]
    : extensions.map((ext) => `${candidate}${ext}`);
  for (const dir of pathValue.split(';').filter(Boolean)) {
    for (const name of names) {
      if (existsSync(`${dir}\\${name}`)) return true;
      if (existsSync(`${dir}/${name}`)) return true;
    }
  }
  return false;
}
