// Core business logic over a store (SpacetimeDB in production, in-memory in tests).
import { randomBytes } from 'node:crypto';
import { newTokenCode, splitToken, bodyHash, appendMarker, normalizeAddress } from './canon.js';
import { StoreError } from './store.js';

export const TOKEN_TTL_MS = Number(process.env.TOKEN_TTL_MIN || 60) * 60_000;

export class HttpError extends Error {
  constructor(status, message, code) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

const now = () => Date.now();

/** Store rejections (e.g. the module refusing a duplicate name) become HTTP errors with the module's message. */
async function guarded(fn) {
  try {
    return await fn();
  } catch (e) {
    if (e instanceof StoreError) throw new HttpError(e.status, e.message, e.status === 409 ? 'conflict' : 'store_error');
    throw e;
  }
}

async function logEvent(store, e) {
  await store.logEvent({ at: now(), ...e });
}

export function publicOrg(o) {
  return { id: Number(o.id), name: o.name, domain: o.domain, color: o.brand_color, status: o.status };
}

// ---------- enterprise onboarding ("verification" of the enterprise) ----------

const HEX = /^#[0-9a-fA-F]{6}$/;
const DOMAIN = /^(?!-)([a-z0-9-]+\.)+[a-z]{2,}$/i;

export async function applyForVerification(store, input) {
  const name = String(input?.name ?? '').trim();
  const domain = String(input?.domain ?? '').trim().toLowerCase().replace(/^https?:\/\//, '').replace(/\/.*$/, '');
  const contact = String(input?.contact ?? '').trim().slice(0, 200);
  const color = HEX.test(input?.color ?? '') ? input.color : '#E20074';
  const senders = [...new Set((Array.isArray(input?.senders) ? input.senders : []).map((s) => String(s).trim()).filter(Boolean))];

  if (name.length < 2 || name.length > 60) throw new HttpError(400, 'Business name must be 2-60 characters', 'bad_name');
  if (!DOMAIN.test(domain)) throw new HttpError(400, 'A valid business domain is required (e.g. t-mobile.com)', 'bad_domain');
  if (senders.length < 1 || senders.length > 5) throw new HttpError(400, 'Provide 1-5 sender numbers', 'bad_senders');

  const apiKey = 'vt_' + randomBytes(18).toString('hex');
  // The SpacetimeDB module enforces anti-impersonation (name / number already owned by an approved business).
  await guarded(() =>
    store.insertOrg({ name, domain, contact, color, apiKey, addresses: senders, norms: senders.map(normalizeAddress), createdAt: now() }),
  );
  const org = await store.orgByKey(apiKey);
  await logEvent(store, { kind: 'apply', org_name: name, detail: `applied for verification (${domain})` });
  return { orgId: Number(org.id), apiKey, status: 'pending' };
}

export async function orgByKey(store, apiKey) {
  if (!apiKey) throw new HttpError(401, 'Missing API key', 'no_key');
  const org = await store.orgByKey(apiKey);
  if (!org) throw new HttpError(401, 'Unknown API key', 'bad_key');
  return org;
}

export async function orgStatus(store, org) {
  return { ...publicOrg(org), senders: (await store.sendersOf(org.id)).map((s) => s.address) };
}

export async function decideOrg(store, id, action) {
  const org = await store.orgById(id);
  if (!org) throw new HttpError(404, 'No such business', 'not_found');
  const next = { approve: 'approved', reject: 'rejected', revoke: 'revoked' }[action];
  if (!next) throw new HttpError(400, 'Unknown action', 'bad_action');
  await guarded(() => store.decide(Number(id), action, now()));
  await logEvent(store, { kind: 'admin', org_name: org.name, detail: `${action}: ${org.name}` });
  return publicOrg({ ...org, status: next });
}

// ---------- token issuance (enterprise side) ----------

export async function issueToken(store, org, text) {
  if (org.status !== 'approved') {
    throw new HttpError(403, `Business is ${org.status}; only approved businesses can send verified messages`, 'not_approved');
  }
  const clean = String(text ?? '').trim();
  if (clean.length < 1) throw new HttpError(400, 'Message text required', 'empty');
  if (clean.length > 900) throw new HttpError(400, 'Message too long (max 900 chars)', 'too_long');
  if (splitToken(clean).code) throw new HttpError(400, 'Message already contains a token', 'has_token');

  const code = newTokenCode();
  const issued = now();
  await store.insertToken({ code, orgId: Number(org.id), bodyHash: bodyHash(clean), issuedAt: issued, expiresAt: issued + TOKEN_TTL_MS });
  await logEvent(store, { kind: 'issue', org_name: org.name, detail: `token ${code} issued` });
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
export async function verify(store, { from, body }, opts = {}) {
  // 'sender' (default): a registered number alone is enough. 'strict': also needs a valid per-message code.
  const mode = opts.mode ?? process.env.VERIFY_MODE ?? 'sender';
  const addr = normalizeAddress(from);
  const { text, code } = splitToken(body);

  const owner = addr ? await store.ownerOfNorm(addr) : null;
  const token = code ? await store.tokenByCode(code) : null;
  const tokenOrg = token ? await store.orgById(token.org_id) : null;

  const result = async (decision, reason, org) => {
    await logEvent(store, {
      kind: 'verify', decision, reason,
      org_name: (org ?? owner)?.name ?? tokenOrg?.name ?? '',
      sender: String(from ?? ''),
    });
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

export async function directory(store) {
  return { generatedAt: now(), senders: await store.directoryRows() };
}

export async function resetAll(store) {
  await store.resetAll();
  await logEvent(store, { kind: 'admin', detail: 'demo data reset' });
}

export async function listOrgs(store) {
  return (await store.listOrgs()).map((o) => ({
    ...publicOrg(o),
    contact: o.contact,
    createdAt: o.created_at,
    decidedAt: o.decided_at || null,
    senders: o.senders,
  }));
}

/** Aggregates for the dashboard. */
export async function stats(store) {
  const since = now() - 24 * 3600_000;
  const rows = await store.eventsSince(since, 5000);
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
  return { totals, reasons, byOrg, series, orgs: await store.statusCounts(), engine: store.kind };
}

/** Recent activity (last 24h), newest first. Empty strings from the database become null. */
export async function listEvents(store, limit = 60) {
  const rows = await store.eventsSince(now() - 24 * 3600_000, limit);
  return rows.map((r) => ({ ...r, decision: r.decision || null, reason: r.reason || null, org_name: r.org_name || null, sender: r.sender || null, detail: r.detail || null }));
}
