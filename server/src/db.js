// Tiny async DB adapter. Supabase/Postgres when DATABASE_URL is set, otherwise a local SQLite file.
// SQL is written with `?` placeholders and `RETURNING id`, which both engines accept.
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';
import pg from 'pg';

pg.types.setTypeParser(20, (v) => Number(v)); // BIGINT (epoch ms) -> number

const SQLITE_DDL = `
  PRAGMA journal_mode = WAL;
  CREATE TABLE IF NOT EXISTS orgs (
    id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, domain TEXT NOT NULL, contact TEXT,
    brand_color TEXT NOT NULL DEFAULT '#E20074', status TEXT NOT NULL DEFAULT 'pending',
    api_key TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL, decided_at INTEGER
  );
  CREATE TABLE IF NOT EXISTS senders (
    id INTEGER PRIMARY KEY AUTOINCREMENT, org_id INTEGER NOT NULL REFERENCES orgs(id),
    address TEXT NOT NULL, norm TEXT NOT NULL
  );
  CREATE INDEX IF NOT EXISTS senders_norm ON senders(norm);
  CREATE TABLE IF NOT EXISTS tokens (
    code TEXT PRIMARY KEY, org_id INTEGER NOT NULL REFERENCES orgs(id),
    body_hash TEXT NOT NULL, issued_at INTEGER NOT NULL, expires_at INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, kind TEXT NOT NULL,
    decision TEXT, reason TEXT, org_name TEXT, sender TEXT, detail TEXT
  );
`;

// RLS is enabled with no policies: Supabase's public (anon) API can't read these tables.
// Only this server (connecting as the database owner) can.
const PG_DDL = `
  CREATE TABLE IF NOT EXISTS orgs (
    id BIGSERIAL PRIMARY KEY, name TEXT NOT NULL, domain TEXT NOT NULL, contact TEXT,
    brand_color TEXT NOT NULL DEFAULT '#E20074', status TEXT NOT NULL DEFAULT 'pending',
    api_key TEXT NOT NULL UNIQUE, created_at BIGINT NOT NULL, decided_at BIGINT
  );
  CREATE TABLE IF NOT EXISTS senders (
    id BIGSERIAL PRIMARY KEY, org_id BIGINT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
    address TEXT NOT NULL, norm TEXT NOT NULL
  );
  CREATE INDEX IF NOT EXISTS senders_norm ON senders(norm);
  CREATE TABLE IF NOT EXISTS tokens (
    code TEXT PRIMARY KEY, org_id BIGINT NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
    body_hash TEXT NOT NULL, issued_at BIGINT NOT NULL, expires_at BIGINT NOT NULL
  );
  CREATE TABLE IF NOT EXISTS events (
    id BIGSERIAL PRIMARY KEY, at BIGINT NOT NULL, kind TEXT NOT NULL,
    decision TEXT, reason TEXT, org_name TEXT, sender TEXT, detail TEXT
  );
  ALTER TABLE orgs ENABLE ROW LEVEL SECURITY;
  ALTER TABLE senders ENABLE ROW LEVEL SECURITY;
  ALTER TABLE tokens ENABLE ROW LEVEL SECURITY;
  ALTER TABLE events ENABLE ROW LEVEL SECURITY;
`;

async function sqliteAdapter(path) {
  const { DatabaseSync } = await import('node:sqlite');
  if (path !== ':memory:') mkdirSync(dirname(path), { recursive: true });
  const db = new DatabaseSync(path);
  db.exec(SQLITE_DDL);
  return {
    kind: 'sqlite',
    async all(sql, p = []) { return db.prepare(sql).all(...p); },
    async get(sql, p = []) { return db.prepare(sql).get(...p); },
    async run(sql, p = []) { db.prepare(sql).run(...p); },
    async exec(sql) { db.exec(sql); },
    async close() { db.close(); },
  };
}

function pgAdapter(url) {
  const toPg = (sql) => { let i = 0; return sql.replace(/\?/g, () => `$${++i}`); };
  const clean = url.replace(/\?.*$/, ''); // drop sslmode/supa params; we set ssl below
  const pool = new pg.Pool({
    connectionString: clean,
    ssl: /localhost|127\.0\.0\.1/.test(url) ? false : { rejectUnauthorized: false },
    max: 5,
    idleTimeoutMillis: 20_000,
  });
  return {
    kind: 'postgres',
    async init() { await pool.query(PG_DDL); },
    async all(sql, p = []) { return (await pool.query(toPg(sql), p)).rows; },
    async get(sql, p = []) { return (await pool.query(toPg(sql), p)).rows[0]; },
    async run(sql, p = []) { await pool.query(toPg(sql), p); },
    async exec(sql) { await pool.query(sql); },
    async close() { await pool.end(); },
  };
}

export async function openDb({ url = process.env.DATABASE_URL || process.env.POSTGRES_URL, path = process.env.DB_PATH || './data/veritext.db' } = {}) {
  if (path === ':memory:') return sqliteAdapter(path);
  if (url) {
    const db = pgAdapter(url);
    await db.init();
    return db;
  }
  return sqliteAdapter(path);
}
