#!/usr/bin/env node
// Restores ANSI state in the pinned Node reference xterm and emits a canonical
// semantic snapshot. The Go tests keep this runner in-repo; only the pinned
// @xterm packages are resolved from WEBTERM_NODE_ROOT (or the local reference
// checkout) so test behavior cannot silently follow an external helper script.

import { createRequire } from 'node:module';
import { readFileSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';

const CLEAR_PREFIX = '\x1b[3J\x1b[2J\x1b[H';

function parseArgs() {
  const values = { cols: 100, rows: 30, referenceRoot: process.env.WEBTERM_NODE_ROOT || '/Users/gao/Documents/webterm', mode: 'restore' };
  for (const arg of process.argv.slice(2)) {
    if (arg.startsWith('--cols=')) values.cols = Number.parseInt(arg.slice(7), 10);
    else if (arg.startsWith('--rows=')) values.rows = Number.parseInt(arg.slice(7), 10);
    else if (arg.startsWith('--reference-root=')) values.referenceRoot = arg.slice('--reference-root='.length);
    else if (arg.startsWith('--mode=')) values.mode = arg.slice('--mode='.length);
  }
  return values;
}

function referenceModules(root) {
  const packagePath = join(resolve(root), 'package.json');
  if (!existsSync(packagePath)) throw new Error(`Node reference package.json not found: ${packagePath}`);
  const require = createRequire(packagePath);
  return {
    Terminal: require('@xterm/headless').Terminal,
    SerializeAddon: require('@xterm/addon-serialize').SerializeAddon,
  };
}

function makeTerminal(Terminal, SerializeAddon, cols, rows) {
  const term = new Terminal({ cols, rows, scrollback: 10000, allowProposedApi: true, windowsMode: process.platform === 'win32' });
  const serializeAddon = new SerializeAddon();
  term.loadAddon(serializeAddon);
  return { term, serializeAddon };
}

function write(term, data) {
  return new Promise((resolveWrite, reject) => {
    try {
      term.write(data, resolveWrite);
    } catch (error) {
      reject(error);
    }
  });
}

function modes(term) {
  const value = term.modes;
  return {
    applicationCursorKeysMode: Boolean(value.applicationCursorKeysMode),
    applicationKeypadMode: Boolean(value.applicationKeypadMode),
    bracketedPasteMode: Boolean(value.bracketedPasteMode),
    insertMode: Boolean(value.insertMode),
    originMode: Boolean(value.originMode),
    reverseWraparoundMode: Boolean(value.reverseWraparoundMode),
    sendFocusMode: Boolean(value.sendFocusMode),
    wraparoundMode: Boolean(value.wraparoundMode),
    mouseTrackingMode: String(value.mouseTrackingMode),
  };
}

function cellStyle(cell) {
  return [
    cell.getFgColorMode(), cell.getFgColor(), cell.getBgColorMode(), cell.getBgColor(),
    cell.isBold(), cell.isDim(), cell.isItalic(), cell.isUnderline(), cell.isOverline(),
    cell.isBlink(), cell.isInverse(), cell.isInvisible(), cell.isStrikethrough(),
  ];
}

function styleRuns(line, cols) {
  const runs = [];
  let current = null;
  for (let col = 0; col < cols; col++) {
    const cell = line.getCell(col);
    if (!cell) continue;
    const style = cellStyle(cell);
    const key = JSON.stringify(style);
    if (!current || current.key !== key) {
      if (current) runs.push({ start: current.start, end: col, style: current.style });
      current = { start: col, key, style };
    }
  }
  if (current) runs.push({ start: current.start, end: cols, style: current.style });
  return runs;
}

function capture(term) {
  const buffer = term.buffer.active;
  const lines = [];
  for (let row = 0; row < buffer.length; row++) {
    const line = buffer.getLine(row);
    if (!line) continue;
    lines.push({ index: row, text: line.translateToString(true), wrapped: line.isWrapped, styleRuns: styleRuns(line, term.cols) });
  }
  return {
    cols: term.cols,
    rows: term.rows,
    activeBuffer: buffer.type,
    cursor: { x: buffer.cursorX, y: buffer.cursorY },
    cursorStyle: term.options.cursorStyle || 'block',
    cursorBlink: Boolean(term.options.cursorBlink),
    modes: modes(term),
    lines,
  };
}

async function main() {
  const args = parseArgs();
  const { Terminal, SerializeAddon } = referenceModules(args.referenceRoot);
  const input = readFileSync(0, 'utf8');
  const { term, serializeAddon } = makeTerminal(Terminal, SerializeAddon, args.cols, args.rows);

  if (args.mode === 'snapshot') {
    const request = JSON.parse(input);
    for (const action of request.actions || []) {
      if (action.type === 'write') await write(term, String(action.data || ''));
      else if (action.type === 'resize') term.resize(Number(action.cols), Number(action.rows));
      else throw new Error(`unsupported action type: ${action.type}`);
    }
    const jsonPayload = serializeAddon.serialize();
    process.stdout.write(JSON.stringify({ jsonPayload, binaryPayload: CLEAR_PREFIX + jsonPayload, screen: capture(term) }));
    return;
  }

  await write(term, input);
  process.stdout.write(JSON.stringify(capture(term)));
}

main().catch((error) => {
  process.stderr.write(`${error.stack || error}\n`);
  process.exit(1);
});
