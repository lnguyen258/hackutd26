// VeriText registry: SpacetimeDB module.
//
// Data model: businesses (org), the sender numbers they own (sender), per-message codes (token)
// and an activity log (event). Everything is PRIVATE: nothing here is readable by anonymous
// clients. Only the database owner (the identity that published the module) can call reducers
// or run SQL, and the VeriText API server is that owner. Phones never talk to SpacetimeDB directly,
// so an app user can never approve themselves.
import { schema, table, t, SenderError } from 'spacetimedb/server';

// ───────────────────────────── tables ─────────────────────────────

/** Single row: who is allowed to write. Set once, in `init`, to whoever published the module. */
const config = table({ name: 'config' }, { id: t.u8().primaryKey(), admin: t.identity() });

const org = table(
  { name: 'org' },
  {
    id: t.u64().primaryKey().autoInc(),
    name: t.string(),
    domain: t.string(),
    contact: t.string(),
    brand_color: t.string(),
    status: t.string(), // pending | approved | rejected | revoked
    api_key: t.string().unique(),
    created_at: t.u64(), // epoch ms
    decided_at: t.u64(), // epoch ms, 0 = undecided
  },
);

const sender = table(
  { name: 'sender' },
  {
    id: t.u64().primaryKey().autoInc(),
    org_id: t.u64().index('btree'),
    address: t.string(), // as entered
    norm: t.string().index('btree'), // normalized for matching (last 10 digits / lowercase alias)
  },
);

const token = table(
  { name: 'token' },
  {
    code: t.string().primaryKey(),
    org_id: t.u64().index('btree'),
    body_hash: t.string(),
    issued_at: t.u64(),
    expires_at: t.u64(),
  },
);

const event = table(
  { name: 'event' },
  {
    id: t.u64().primaryKey().autoInc(),
    at: t.u64(),
    kind: t.string(), // verify | issue | apply | admin
    decision: t.string(),
    reason: t.string(),
    org_name: t.string(),
    sender: t.string(),
    detail: t.string(),
  },
);

const spacetimedb = schema({ config, org, sender, token, event });
export default spacetimedb;

// ───────────────────────────── helpers ─────────────────────────────

type Ctx = Parameters<Parameters<typeof spacetimedb.reducer>[1]>[0];

function must(cond: unknown, msg: string): asserts cond {
  if (!cond) throw new SenderError(msg);
}

/** Only the publisher (the VeriText API server) may call reducers. */
function requireAdmin(ctx: Ctx) {
  const c = ctx.db.config.id.find(0);
  must(c && c.admin.isEqual(ctx.sender), 'admin only');
}

const clean = (s: string, max: number) => (s.length > max ? s.slice(0, max) : s);

/** Does an APPROVED business (other than `exceptOrg`) already own this normalized number? */
function approvedOwnerOfNumber(ctx: Ctx, norm: string, exceptOrg: bigint) {
  for (const s of ctx.db.sender.norm.filter(norm)) {
    if (s.org_id === exceptOrg) continue;
    const o = ctx.db.org.id.find(s.org_id);
    if (o && o.status === 'approved') return o;
  }
  return null;
}

// ───────────────────────────── lifecycle ─────────────────────────────

export const init = spacetimedb.init((ctx) => {
  ctx.db.config.insert({ id: 0, admin: ctx.sender });
});

// ───────────────────────────── reducers (admin only) ─────────────────────────────

/** A business applies for verification. Anti-impersonation: an approved business already owns the name or a number. */
export const applyOrg = spacetimedb.reducer(
  {
    name: t.string(),
    domain: t.string(),
    contact: t.string(),
    brand_color: t.string(),
    api_key: t.string(),
    addresses: t.array(t.string()),
    norms: t.array(t.string()),
    created_at: t.u64(),
  },
  (ctx, a) => {
    requireAdmin(ctx);
    must(a.addresses.length >= 1 && a.addresses.length <= 5, 'Provide 1-5 sender numbers');
    must(a.addresses.length === a.norms.length, 'addresses/norms mismatch');
    const lower = a.name.toLowerCase();
    for (const o of ctx.db.org.iter()) {
      if (o.status === 'approved' && o.name.toLowerCase() === lower) {
        throw new SenderError(`"${a.name}" is already a verified business`);
      }
    }
    for (let i = 0; i < a.norms.length; i++) {
      if (approvedOwnerOfNumber(ctx, a.norms[i], 0n)) {
        throw new SenderError(`Sender ${a.addresses[i]} already belongs to a verified business`);
      }
    }
    const row = ctx.db.org.insert({
      id: 0n,
      name: clean(a.name, 60),
      domain: clean(a.domain, 120),
      contact: clean(a.contact, 200),
      brand_color: clean(a.brand_color, 9),
      status: 'pending',
      api_key: a.api_key,
      created_at: a.created_at,
      decided_at: 0n,
    });
    for (let i = 0; i < a.norms.length; i++) {
      ctx.db.sender.insert({ id: 0n, org_id: row.id, address: clean(a.addresses[i], 40), norm: a.norms[i] });
    }
  },
);

/** Operator decision: approve | reject | revoke. */
export const decideOrg = spacetimedb.reducer(
  { id: t.u64(), action: t.string(), at: t.u64() },
  (ctx, a) => {
    requireAdmin(ctx);
    const o = ctx.db.org.id.find(a.id);
    must(o, 'No such business');
    const next = ({ approve: 'approved', reject: 'rejected', revoke: 'revoked' } as Record<string, string>)[a.action];
    must(next, 'Unknown action');
    if (next === 'approved') {
      for (const s of ctx.db.sender.org_id.filter(a.id)) {
        const other = approvedOwnerOfNumber(ctx, s.norm, a.id);
        if (other) throw new SenderError(`A sender number already belongs to ${other.name}`);
      }
    }
    ctx.db.org.id.update({ ...o, status: next, decided_at: a.at });
  },
);

/** Record a per-message code (bound to the exact text via its hash). */
export const insertToken = spacetimedb.reducer(
  { code: t.string(), org_id: t.u64(), body_hash: t.string(), issued_at: t.u64(), expires_at: t.u64() },
  (ctx, a) => {
    requireAdmin(ctx);
    must(ctx.db.org.id.find(a.org_id), 'No such business');
    ctx.db.token.insert(a);
  },
);

export const logEvent = spacetimedb.reducer(
  {
    at: t.u64(),
    kind: t.string(),
    decision: t.string(),
    reason: t.string(),
    org_name: t.string(),
    sender: t.string(),
    detail: t.string(),
  },
  (ctx, a) => {
    requireAdmin(ctx);
    ctx.db.event.insert({
      id: 0n,
      at: a.at,
      kind: clean(a.kind, 20),
      decision: clean(a.decision, 20),
      reason: clean(a.reason, 40),
      org_name: clean(a.org_name, 60),
      sender: clean(a.sender, 60),
      detail: clean(a.detail, 200),
    });
  },
);

/** Demo reset: wipes everything except the admin config. */
export const resetAll = spacetimedb.reducer((ctx) => {
  requireAdmin(ctx);
  ctx.db.token.clear();
  ctx.db.sender.clear();
  ctx.db.org.clear();
  ctx.db.event.clear();
});
