// Core business logic. Async functions over a db adapter (see db.js) so they work on
// Supabase Postgres and local SQLite alike.
import { randomBytes } from 'node:crypto';
import { newTokenCode, splitToken, bodyHash, appendMarker, normalizeAddress } from './canon.js';

export const TOKEN_TTL_MS = Number(process.env.TOKEN_TTL_MIN || 60) * 60_000;

export class HttpError extends Error {
  constructor(status, message, code) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

const now = () => Date.now();

async function logEvent(db, e) {
  await db.run(
    `INSERT INTO events (at, kind, decision, reason, org_name, sender, detail) VALUES (?, ?, ?, ?, ?, ?, ?)`,
    [now(), e.kind, e.decision ?? null, e.reason ?? null, e.org_name ?? null, e.sender ?? null, e.detail ?? null],
  );
}

export function publicOrg(o) {
  return { id: Number(o.id), name: o.name, domain: o.domain, color: o.brand_color, status: o.status };
}

// ---------- enterprise onboarding ("verification" of the enterprise) ----------

const HEX = /^#[0-9a-fA-F]{6}$/;
const DOMAIN = /^(?!-)([a-z0-9-]+\.)+[a-z]{2,}$/i;

export async function applyForVerification(db, input) {
  const name = String(input?.name ?? '').trim();
  const domain = String(input?.domain ?? '').trim().toLowerCase().replace(/^https?:\/\//, '').replace(/\/.*$/, '');
  const contact = String(input?.contact ?? '').trim().slice(0, 200);
  const color = HEX.test(input?.color ?? '') ? input.color : '#E20074';
  const senders = [...new Set((Array.isArray(input?.senders) ? input.senders : []).map((s) => String(s).trim()).filter(Boolean))];

  if (name.length < 2 || name.length > 60) throw new HttpError(400, 'Business name must be 2-60 characters', 'bad_name');
  if (!DOMAIN.test(domain)) throw new HttpError(400, 'A valid business domain is required (e.g. t-mobile.com)', 'bad_domain');
  if (senders.length < 1 || senders.length > 5) throw new HttpError(400, 'Provide 1-5 sender numbers', 'bad_senders');

  // Anti-impersonation: an approved org already owns this name or number.
  const dupName = await db.get(`SELECT id FROM orgs WHERE lower(name) = lower(?) AND status = 'approved'`, [name]);
  if (dupName) throw new HttpError(409, `"${name}" is already a verified business`, 'name_taken');
  for (const s of senders) {
    const taken = await db.get(
      `SELECT o.id FROM senders s JOIN orgs o ON o.id = s.org_id WHERE s.norm = ? AND o.status = 'approved'`,
      [normalizeAddress(s)],
    );
    if (taken) throw new HttpError(409, `Sender ${s} already belongs to a verified business`, 'sender_taken');
  }

  const apiKey = 'vt_' + randomBytes(18).toString('hex');
  const row = await db.get(
    `INSERT INTO orgs (name, domain, contact, brand_color, status, api_key, created_at) VALUES (?,?,?,?,?,?,?) RETURNING id`,
    [name, domain, contact, color, 'pending', apiKey, now()],
  );
  const orgId = Number(row.id);
  for (const s of senders) await db.run(`INSERT INTO senders (org_id, address, norm) VALUES (?,?,?)`, [orgId, s, normalizeAddress(s)]);
  await logEvent(db, { kind: 'apply', org_name: name, detail: `applied for verification (${domain})` });
  return { orgId, apiKey, status: 'pending' };
}

export async function orgByKey(db, apiKey) {
  if (!apiKey) throw new HttpError(401, 'Missing API key', 'no_key');
  const org = await db.get(`SELECT * FROM orgs WHERE api_key = ?`, [apiKey]);
  if (!org) throw new HttpError(401, 'Unknown API key', 'bad_key');
  return org;
}

export async function orgStatus(db, org) {
  const rows = await db.all(`SELECT address FROM senders WHERE org_id = ?`, [org.id]);
  return { ...publicOrg(org), senders: rows.map((r) => r.address) };
}

export async function decideOrg(db, id, action) {
  const org = await db.get(`SELECT * FROM orgs WHERE id = ?`, [id]);
  if (!org) throw new HttpError(404, 'No such business', 'not_found');
  const next = { approve: 'approved', reject: 'rejected', revoke: 'revoked' }[action];
  if (!next) throw new HttpError(400, 'Unknown action', 'bad_action');
  if (next === 'approved') {
    const conflict = await db.get(
      `SELECT o.name FROM senders s JOIN orgs o ON o.id = s.org_id
       WHERE o.status = 'approved' AND o.id != ? AND s.norm IN (SELECT norm FROM senders WHERE org_id = ?)`,
      [id, id],
    );
    if (conflict) throw new HttpError(409, `A sender number already belongs to ${conflict.name}`, 'sender_taken');
  }
  await db.run(`UPDATE orgs SET status = ?, decided_at = ? WHERE id = ?`, [next, now(), id]);
  await logEvent(db, { kind: 'admin', org_name: org.name, detail: `${action}: ${org.name}` });
  return publicOrg({ ...org, status: next });
}

// ---------- token issuance (enterprise side) ----------

export async function issueToken(db, org, text) {
  if (org.status !== 'approved') {
    throw new HttpError(403, `Business is ${org.status}; only approved businesses can send verified messages`, 'not_approved');
  }
  const clean = String(text ?? '').trim();
  if (clean.length < 1) throw new HttpError(400, 'Message text required', 'empty');
  if (clean.length > 900) throw new HttpError(400, 'Message too long (max 900 chars)', 'too_long');
  if (splitToken(clean).code) throw new HttpError(400, 'Message already contains a token', 'has_token');

  const code = newTokenCode();
  const issued = now();
  await db.run(`INSERT INTO tokens (code, org_id, body_hash, issued_at, expires_at) VALUES (?,?,?,?,?)`, [
    code, org.id, bodyHash(clean), issued, issued + TOKEN_TTL_MS,
  ]);
  await logEvent(db, { kind: 'issue', org_name: org.name, detail: `token ${code} issued` });
  return { code, expiresAt: issued + TOKEN_TTL_MS, smsBody: appendMarker(clean, code) };
}

// ---------- verification (consumer side) ----------

/**
 * Decision table (strict mode; in 'sender' mode a registered number without a code is simply verified):
 *   registered sender + valid token for this exact text      -> verified
 *   registered sender (or revoked) + anything else           -> suspicious
 *   token present but sent from an unregistered number       -> suspicious
 *   no relation to the registry                              -> unknown
 */
export async function verify(db, { from, body }, opts = {}) {
  // 'sender' (default): a registered number alone is enough. 'strict': also needs a valid per-message code.
  const mode = opts.mode ?? process.env.VERIFY_MODE ?? 'sender';
  const addr = normalizeAddress(from);
  const { text, code } = splitToken(body);

  const owner = addr
    ? await db.get(
        `SELECT o.* FROM senders s JOIN orgs o ON o.id = s.org_id
         WHERE s.norm = ? AND o.status IN ('approved','revoked')
         ORDER BY (o.status = 'approved') DESC LIMIT 1`,
        [addr],
      )
    : null;

  const token = code
    ? await db.get(`SELECT t.*, o.name AS org_name FROM tokens t JOIN orgs o ON o.id = t.org_id WHERE t.code = ?`, [code])
    : null;

  const result = async (decision, reason, org) => {
    await logEvent(db, { kind: 'verify', decision, reason, org_name: (org ?? owner)?.name ?? token?.org_name ?? null, sender: String(from ?? '') });
    return { decision, reason, org: org ? publicOrg(org) : null };
  };

  if (owner) {
    if (owner.status === 'revoked') return result('suspicious', 'org_revoked', owner);
    if (!code && mode === 'sender') return result('verified', 'sender_match', owner);
    if (!code) return result('suspicious', 'no_token', owner);
    if (!token) return result('suspicious', 'unknown_token', owner);
    if (Number(token.org_id) !== Number(owner.id)) return result('suspicious', 'token_from_other_org', owner);
    if (token.expires_at < now()) return result('suspicious', 'token_expired', owner);
    if (token.body_hash !== bodyHash(text)) return result('suspicious', 'body_mismatch', owner);
    return result('verified', 'ok', owner);
  }
  if (code) {
    // Token from a number that isn't registered to anyone: likely replayed/forwarded.
    return result(token ? 'suspicious' : 'unknown', token ? 'sender_not_registered' : 'unknown_token', null);
  }
  return result('unknown', 'not_in_registry', null);
}

export async function directory(db) {
  const rows = await db.all(
    `SELECT s.norm, s.address, o.name, o.status FROM senders s JOIN orgs o ON o.id = s.org_id
     WHERE o.status IN ('approved','revoked')`,
  );
  return { generatedAt: now(), senders: rows.map((r) => ({ norm: r.norm, name: r.name, status: r.status })) };
}

export async function resetAll(db) {
  await db.exec(`DELETE FROM tokens; DELETE FROM senders; DELETE FROM orgs; DELETE FROM events;`);
  await logEvent(db, { kind: 'admin', detail: 'demo data reset' });
}

export async function listOrgs(db) {
  const orgs = await db.all(`SELECT * FROM orgs ORDER BY id DESC`);
  const out = [];
  for (const o of orgs) {
    const senders = await db.all(`SELECT address FROM senders WHERE org_id = ?`, [o.id]);
    out.push({
      ...publicOrg(o),
      contact: o.contact,
      createdAt: o.created_at,
      decidedAt: o.decided_at,
      senders: senders.map((s) => s.address),
    });
  }
  return out;
}

/** Aggregates for the dashboard, computed in JS so it is engine-agnostic. */
export async function stats(db) {
  const since = now() - 24 * 3600_000;
  const rows = await db.all(`SELECT at, kind, decision, reason, org_name FROM events WHERE at >= ? ORDER BY at DESC LIMIT 5000`, [since]);
  const totals = { verified: 0, suspicious: 0, unknown: 0, issued: 0 };
  const reasons = {};
  const byOrg = {};
  const BUCKET = 60_000, N = 30;
  const end = Math.floor(now() / BUCKET) * BUCKET;
  const series = Array.from({ length: N }, (_, i) => ({ t: end - (N - 1 - i) * BUCKET, verified: 0, suspicious: 0, unknown: 0 }));
  for (const r of rows) {
    if (r.kind === 'issue') totals.issued++;
    if (r.kind !== 'verify') continue;
    totals[r.decision] = (totals[r.decision] || 0) + 1;
    if (r.decision === 'suspicious') reasons[r.reason] = (reasons[r.reason] || 0) + 1;
    if (r.org_name) {
      const o = (byOrg[r.org_name] ??= { verified: 0, suspicious: 0 });
      if (r.decision in o) o[r.decision]++;
    }
    const b = series[Math.floor((r.at - (end - (N - 1) * BUCKET)) / BUCKET)];
    if (b) b[r.decision]++;
  }
  const orgCounts = await db.all(`SELECT status, COUNT(*) AS n FROM orgs GROUP BY status`);
  return {
    totals,
    reasons,
    byOrg,
    series,
    orgs: Object.fromEntries(orgCounts.map((r) => [r.status, Number(r.n)])),
    engine: db.kind,
  };
}

export async function listEvents(db, limit = 60) {
  return db.all(`SELECT * FROM events ORDER BY id DESC LIMIT ?`, [limit]);
}
