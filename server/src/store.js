// Data layer. Two interchangeable stores with the same async interface:
//   SpacetimeStore: the real thing. All VeriText data lives in a SpacetimeDB database
//                   (module in /spacetimedb). Writes are reducer calls, reads are SQL.
//   MemoryStore:    same rules, in memory. For unit tests and offline local dev only.
//
// The server holds the database owner's token, which is the only identity the module's
// reducers accept. Phones and the dashboard never talk to SpacetimeDB directly.

export class StoreError extends Error {
  constructor(message, status = 409) {
    super(message);
    this.status = status;
  }
}

// ───────────────────────── SpacetimeDB ─────────────────────────

const lit = (v) => {
  if (typeof v === 'number') {
    if (!Number.isFinite(v)) throw new StoreError('bad number', 400);
    return String(Math.trunc(v));
  }
  const s = String(v ?? '');
  if (s.includes('\0')) throw new StoreError('bad string', 400);
  return `'${s.replace(/'/g, "''")}'`; // SQL string literal: quotes doubled
};

export class SpacetimeStore {
  kind = 'spacetimedb';

  constructor({ host, db, token }) {
    this.host = host.replace(/\/$/, '');
    this.name = db;
    this.token = token;
  }

  get base() {
    return `${this.host}/v1/database/${encodeURIComponent(this.name)}`;
  }

  async #reducer(name, args) {
    const r = await fetch(`${this.base}/call/${name}`, {
      method: 'POST',
      headers: { authorization: `Bearer ${this.token}`, 'content-type': 'application/json' },
      body: JSON.stringify(args),
      signal: AbortSignal.timeout(10_000),
    });
    if (r.ok) return;
    const text = (await r.text()).trim();
    if (r.status === 530 || r.status === 400) throw new StoreError(text || `${name} rejected`, text === 'admin only' ? 500 : 409);
    throw new StoreError(`SpacetimeDB ${name} failed (${r.status}): ${text.slice(0, 120)}`, 502);
  }

  async #sql(query) {
    const r = await fetch(`${this.base}/sql`, {
      method: 'POST',
      headers: { authorization: `Bearer ${this.token}`, 'content-type': 'text/plain' },
      body: query,
      signal: AbortSignal.timeout(10_000),
    });
    if (!r.ok) throw new StoreError(`SpacetimeDB sql failed (${r.status}): ${(await r.text()).slice(0, 120)}`, 502);
    const first = (await r.json())[0];
    if (!first) return [];
    const names = first.schema.elements.map((e) => e.name.some);
    return first.rows.map((row) => Object.fromEntries(row.map((v, i) => [names[i], v])));
  }

  // writes
  insertOrg(o) {
    return this.#reducer('apply_org', [o.name, o.domain, o.contact, o.color, o.apiKey, o.addresses, o.norms, o.createdAt]);
  }
  decide(id, action, at) {
    return this.#reducer('decide_org', [id, action, at]);
  }
  insertToken(t) {
    return this.#reducer('insert_token', [t.code, t.orgId, t.bodyHash, t.issuedAt, t.expiresAt]);
  }
  logEvent(e) {
    return this.#reducer('log_event', [e.at, e.kind, e.decision ?? '', e.reason ?? '', e.org_name ?? '', e.sender ?? '', e.detail ?? '']);
  }
  resetAll() {
    return this.#reducer('reset_all', []);
  }

  // reads
  async orgByKey(key) {
    return (await this.#sql(`SELECT * FROM org WHERE api_key = ${lit(key)}`))[0] ?? null;
  }
  async orgById(id) {
    return (await this.#sql(`SELECT * FROM org WHERE id = ${lit(Number(id))}`))[0] ?? null;
  }
  async sendersOf(orgId) {
    return this.#sql(`SELECT * FROM sender WHERE org_id = ${lit(Number(orgId))}`);
  }
  async listOrgs() {
    const [orgs, senders] = await Promise.all([this.#sql('SELECT * FROM org'), this.#sql('SELECT * FROM sender')]);
    return orgs
      .sort((a, b) => b.id - a.id)
      .map((o) => ({ ...o, senders: senders.filter((s) => s.org_id === o.id).map((s) => s.address) }));
  }
  /** Approved or revoked business that owns this normalized number (approved wins). */
  async ownerOfNorm(norm) {
    const senders = await this.#sql(`SELECT * FROM sender WHERE norm = ${lit(norm)}`);
    let found = null;
    for (const s of senders) {
      const o = await this.orgById(s.org_id);
      if (!o || (o.status !== 'approved' && o.status !== 'revoked')) continue;
      if (!found || (o.status === 'approved' && found.status !== 'approved')) found = o;
    }
    return found;
  }
  async tokenByCode(code) {
    return (await this.#sql(`SELECT * FROM token WHERE code = ${lit(code)}`))[0] ?? null;
  }
  async eventsSince(since, limit) {
    const rows = await this.#sql(`SELECT * FROM event WHERE at >= ${lit(Number(since))}`);
    return rows.sort((a, b) => b.at - a.at || b.id - a.id).slice(0, limit);
  }
  async directoryRows() {
    const [orgs, senders] = await Promise.all([this.#sql('SELECT * FROM org'), this.#sql('SELECT * FROM sender')]);
    const byId = new Map(orgs.map((o) => [o.id, o]));
    return senders
      .map((s) => ({ norm: s.norm, org: byId.get(s.org_id) }))
      .filter((r) => r.org && (r.org.status === 'approved' || r.org.status === 'revoked'))
      .map((r) => ({ norm: r.norm, name: r.org.name, status: r.org.status }));
  }
  async statusCounts() {
    const counts = {};
    for (const o of await this.#sql('SELECT * FROM org')) counts[o.status] = (counts[o.status] || 0) + 1;
    return counts;
  }
  async close() {}
}

// ───────────────────────── in-memory twin (tests / offline dev) ─────────────────────────

export class MemoryStore {
  kind = 'memory';
  name = 'in-memory';
  #orgs = [];
  #senders = [];
  #tokens = new Map();
  #events = [];
  #seq = { org: 1, sender: 1, event: 1 };

  #approvedOwner(norm, exceptOrg = 0) {
    for (const s of this.#senders.filter((x) => x.norm === norm)) {
      if (s.org_id === exceptOrg) continue;
      const o = this.#orgs.find((x) => x.id === s.org_id);
      if (o && o.status === 'approved') return o;
    }
    return null;
  }

  async insertOrg(o) {
    if (this.#orgs.some((x) => x.status === 'approved' && x.name.toLowerCase() === o.name.toLowerCase())) {
      throw new StoreError(`"${o.name}" is already a verified business`);
    }
    for (let i = 0; i < o.norms.length; i++) {
      if (this.#approvedOwner(o.norms[i])) throw new StoreError(`Sender ${o.addresses[i]} already belongs to a verified business`);
    }
    const row = {
      id: this.#seq.org++, name: o.name, domain: o.domain, contact: o.contact, brand_color: o.color,
      status: 'pending', api_key: o.apiKey, created_at: o.createdAt, decided_at: 0,
    };
    this.#orgs.push(row);
    o.addresses.forEach((address, i) => this.#senders.push({ id: this.#seq.sender++, org_id: row.id, address, norm: o.norms[i] }));
  }
  async decide(id, action, at) {
    const o = this.#orgs.find((x) => x.id === id);
    if (!o) throw new StoreError('No such business');
    const next = { approve: 'approved', reject: 'rejected', revoke: 'revoked' }[action];
    if (!next) throw new StoreError('Unknown action');
    if (next === 'approved') {
      for (const s of this.#senders.filter((x) => x.org_id === id)) {
        const other = this.#approvedOwner(s.norm, id);
        if (other) throw new StoreError(`A sender number already belongs to ${other.name}`);
      }
    }
    o.status = next;
    o.decided_at = at;
  }
  async insertToken(t) {
    this.#tokens.set(t.code, { code: t.code, org_id: t.orgId, body_hash: t.bodyHash, issued_at: t.issuedAt, expires_at: t.expiresAt });
  }
  async logEvent(e) {
    this.#events.push({ id: this.#seq.event++, at: e.at, kind: e.kind, decision: e.decision ?? '', reason: e.reason ?? '', org_name: e.org_name ?? '', sender: e.sender ?? '', detail: e.detail ?? '' });
  }
  async resetAll() {
    this.#orgs = []; this.#senders = []; this.#tokens.clear(); this.#events = [];
  }

  async orgByKey(key) { return this.#orgs.find((o) => o.api_key === key) ?? null; }
  async orgById(id) { return this.#orgs.find((o) => o.id === Number(id)) ?? null; }
  async sendersOf(orgId) { return this.#senders.filter((s) => s.org_id === Number(orgId)); }
  async listOrgs() {
    return [...this.#orgs].sort((a, b) => b.id - a.id).map((o) => ({ ...o, senders: this.#senders.filter((s) => s.org_id === o.id).map((s) => s.address) }));
  }
  async ownerOfNorm(norm) {
    let found = null;
    for (const s of this.#senders.filter((x) => x.norm === norm)) {
      const o = this.#orgs.find((x) => x.id === s.org_id);
      if (!o || (o.status !== 'approved' && o.status !== 'revoked')) continue;
      if (!found || (o.status === 'approved' && found.status !== 'approved')) found = o;
    }
    return found;
  }
  async tokenByCode(code) { return this.#tokens.get(code) ?? null; }
  async eventsSince(since, limit) {
    return this.#events.filter((e) => e.at >= since).sort((a, b) => b.at - a.at || b.id - a.id).slice(0, limit);
  }
  async directoryRows() {
    return this.#senders
      .map((s) => ({ norm: s.norm, org: this.#orgs.find((o) => o.id === s.org_id) }))
      .filter((r) => r.org && (r.org.status === 'approved' || r.org.status === 'revoked'))
      .map((r) => ({ norm: r.norm, name: r.org.name, status: r.org.status }));
  }
  async statusCounts() {
    const counts = {};
    for (const o of this.#orgs) counts[o.status] = (counts[o.status] || 0) + 1;
    return counts;
  }
  async close() {}
}

/** SpacetimeDB when SPACETIMEDB_TOKEN is set, otherwise an in-memory store (offline dev only). */
export async function openStore(env = process.env) {
  if (env.SPACETIMEDB_TOKEN) {
    return new SpacetimeStore({
      host: env.SPACETIMEDB_HOST || 'https://maincloud.spacetimedb.com',
      db: env.SPACETIMEDB_DB || 'veritext-registry',
      token: env.SPACETIMEDB_TOKEN,
    });
  }
  console.warn('SPACETIMEDB_TOKEN not set: using an in-memory store (data is lost on restart).');
  return new MemoryStore();
}
