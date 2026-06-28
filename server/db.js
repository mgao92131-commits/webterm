import Database from 'better-sqlite3';
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const overrideDbPath = process.env.WEBTERM_DB_PATH;
const dataDir = overrideDbPath
  ? path.dirname(path.resolve(overrideDbPath))
  : path.resolve(__dirname, '../data');
const dbPath = overrideDbPath
  ? path.resolve(overrideDbPath)
  : process.env.NODE_ENV === 'test'
    ? path.join(dataDir, 'webterm_test.db')
    : path.join(dataDir, 'webterm.db');

// Ensure data directory exists
if (!fs.existsSync(dataDir)) {
  fs.mkdirSync(dataDir, { recursive: true });
}

const db = new Database(dbPath);

// Apply SQLite configurations (PRAGMAs) on every connection startup
db.pragma('journal_mode = WAL');
db.pragma('synchronous = NORMAL');
db.pragma('foreign_keys = ON');

// Initialize schema_migrations table
db.exec(`
  CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TEXT NOT NULL DEFAULT (datetime('now'))
  );
`);

export function runMigrations() {
  const migrationsDir = path.join(__dirname, 'migrations');
  if (!fs.existsSync(migrationsDir)) return;

  const files = fs.readdirSync(migrationsDir)
    .filter(f => f.endsWith('.sql'))
    .sort();

  for (const file of files) {
    const version = file; // Use filename as version identifier, e.g. "0001_init.sql"
    const row = db.prepare('SELECT 1 FROM schema_migrations WHERE version = ?').get(version);
    if (!row) {
      console.log(`[DB] Applying migration: ${version}`);
      const sqlText = fs.readFileSync(path.join(migrationsDir, file), 'utf8');
      
      // Execute within transaction
      db.transaction(() => {
        db.exec(sqlText);
        db.prepare('INSERT OR IGNORE INTO schema_migrations (version) VALUES (?)').run(version);
      })();
    }
  }
}

// Automatically run migrations on module load to guarantee schema existence
runMigrations();

export default db;
