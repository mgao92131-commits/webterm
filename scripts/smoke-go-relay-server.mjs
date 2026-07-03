import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const args = parseArgs(process.argv.slice(2));
const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const goCoreRoot = path.join(repoRoot, 'go-core');
const gocache = path.join(goCoreRoot, '.gocache');

const goArgs = ['run', './cmd/webterm-relay-e2e-smoke'];
forwardString('agent');
forwardString('cwd', process.cwd());
forwardString('shell');
forwardString('cycles');
forwardString('timeout', normalizeDuration(args.timeout ?? '15000'));
goArgs.push('--mux=true');

const child = spawn('go', goArgs, {
  cwd: goCoreRoot,
  env: {
    ...process.env,
    GOCACHE: process.env.GOCACHE || gocache,
  },
  stdio: 'inherit',
});

child.on('exit', (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal);
    return;
  }
  process.exit(code ?? 1);
});

child.on('error', (error) => {
  console.error(`go relay smoke failed to start: ${error.message}`);
  process.exit(1);
});

function forwardString(key, fallback) {
  const value = args[key] ?? fallback;
  if (value === undefined || value === false) return;
  goArgs.push(`--${key}`, key === 'timeout' ? normalizeDuration(value) : String(value));
}

function normalizeDuration(value) {
  const text = String(value);
  return /^\d+$/.test(text) ? `${text}ms` : text;
}

function parseArgs(items) {
  const parsed = {};
  for (let index = 0; index < items.length; index += 1) {
    const item = items[index];
    if (!item.startsWith('--')) continue;
    const key = item.slice(2);
    const next = items[index + 1];
    const value = next?.startsWith('--') ? true : next;
    parsed[key] = value ?? true;
    if (value !== true) index += 1;
  }
  return parsed;
}
