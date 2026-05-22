import { execFileSync } from 'node:child_process';
import { readlinkSync } from 'node:fs';

const CWD_CHECK_INTERVAL_MS = 1000;

export function createCWDTracker({ pid, initialCWD, platform = process.platform, now = Date.now }) {
  return {
    pid,
    currentCWD: initialCWD,
    checkedAt: -CWD_CHECK_INTERVAL_MS,
    platform,
    now,
  };
}

export function currentCWD(tracker) {
  if (!tracker?.pid) return tracker?.currentCWD || '';
  const timestamp = tracker.now();
  if (timestamp - tracker.checkedAt < CWD_CHECK_INTERVAL_MS) {
    return tracker.currentCWD;
  }
  tracker.checkedAt = timestamp;
  const next = readProcessCWD(tracker.pid, tracker.platform);
  if (next) tracker.currentCWD = next;
  return tracker.currentCWD;
}

export function readProcessCWD(pid, platform = process.platform) {
  try {
    if (platform === 'darwin') return readDarwinCWD(pid);
    if (platform === 'linux') return readlinkSync(`/proc/${pid}/cwd`);
  } catch {
    return '';
  }
  return '';
}

export function parseLsofCWD(output) {
  return String(output || '')
    .split(/\r?\n/)
    .find((line) => line.startsWith('n'))
    ?.slice(1) || '';
}

function readDarwinCWD(pid) {
  const output = execFileSync('lsof', ['-a', '-p', String(pid), '-d', 'cwd', '-Fn'], {
    encoding: 'utf8',
    timeout: 500,
    stdio: ['ignore', 'pipe', 'ignore'],
  });
  return parseLsofCWD(output);
}
