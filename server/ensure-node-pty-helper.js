import { chmodSync, existsSync } from 'node:fs';
import path from 'node:path';

if (process.platform !== 'darwin' && process.platform !== 'linux') {
  process.exit(0);
}

const helper = path.join(
  process.cwd(),
  'node_modules',
  'node-pty',
  'prebuilds',
  `${process.platform}-${process.arch}`,
  'spawn-helper',
);

if (existsSync(helper)) {
  chmodSync(helper, 0o775);
}
