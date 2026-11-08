// HTTP handler shared by the local dev server (src/index.js) and the Vercel function (api/[...path].js).
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { openStore } from './store.js';
import * as svc from './service.js';

const here = dirname(fileURLToPath(import.meta.url));
const ON_VERCEL = !!process.env.VERCEL;
const ADMIN_KEY = process.env.ADMIN_KEY || '';

let storePromise;
export const getStore = () => (storePromise ??= openStore().catch((e) => { storePromise = undefined; throw e; }));

const LOOPBACK = new Set(['127.0.0.1', '::1', '::ffff:127.0.0.1']);
const hits = new Map();
function rateLimited(key, perMin) {
  const t = Date.now();
  const h = hits.get(key);
  if (!h || h.reset < t) { hits.set(key, { n: 1, reset: t + 60_000 }); return false; }
  return ++h.n > perMin;
}
const clientIp = (req) => String(req.headers['x-forwarded-for'] || req.socket.remoteAddress || '?').split(',')[0].trim();

function send(res, status, body, type = 'application/json') {
  res.writeHead(status, { 'content-type': type, 'cache-control': 'no-store' });
  res.end(type === 'application/json' ? JSON.stringify(body) : body);
}
async function readJson(req) {
  if (req.body && typeof req.body === 'object' && !Buffer.isBuffer(req.body)) return req.body; // Vercel pre-parsed
  let size = 0;
  const chunks = [];
  for await (const c of req) {
    size += c.length;
    if (size > 64 * 1024) throw new svc.HttpError(413, 'Payload too large');
    chunks.push(c);
  }
  if (!chunks.length) return {};
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw new svc.HttpError(400, 'Invalid JSON', 'bad_json'); }
}
const bearer = (req) => (req.headers.authorization || '').replace(/^Bearer\s+/i, '');

function requireAdmin(req) {
  const local = !ON_VERCEL && !req.headers['x-forwarded-for'] && LOOPBACK.has(req.socket.remoteAddress);
  const keyOk = ADMIN_KEY && req.headers['x-admin-key'] === ADMIN_KEY;
  if (!local && !keyOk) throw new svc.HttpError(403, 'Admin key required', 'forbidden');
}

export async function handler(req, res) {
  const u = new URL(req.url, 'http://x');
  // On Vercel a rewrite funnels every /api/* call into api/index.js, with the original path in __p.
  const raw = u.searchParams.has('__p') ? '/api/' + u.searchParams.get('__p') : u.pathname;
  const path = raw.replace(/\/+$/, '') || '/';
  const ip = clientIp(req);
  try {
    if (!ON_VERCEL && req.method === 'GET' && (path === '/' || path === '/admin')) {
      return send(res, 200, readFileSync(join(here, '../public/index.html')), 'text/html; charset=utf-8');
    }
    if (req.method === 'GET' && path === '/api/health') return send(res, 200, { ok: true, time: Date.now() });

    const db = await getStore();

    if (req.method === 'POST' && path === '/api/orgs/apply') {
      if (rateLimited('apply:' + ip, 20)) throw new svc.HttpError(429, 'Too many requests');
      return send(res, 201, await svc.applyForVerification(db, await readJson(req)));
    }
    if (req.method === 'GET' && path === '/api/orgs/me') {
      return send(res, 200, await svc.orgStatus(db, await svc.orgByKey(db, bearer(req))));
    }
    if (req.method === 'POST' && path === '/api/tokens') {
      const org = await svc.orgByKey(db, bearer(req));
      const { text } = await readJson(req);
      const t = await svc.issueToken(db, org, text);
      return send(res, 201, { token: t.code, expiresAt: t.expiresAt, smsBody: t.smsBody });
    }
    if (req.method === 'POST' && path === '/api/verify') {
      if (rateLimited('verify:' + ip, 240)) throw new svc.HttpError(429, 'Too many requests');
      const { from, body } = await readJson(req);
      if (typeof body !== 'string' || body.length > 2000) throw new svc.HttpError(400, 'body required', 'bad_body');
      return send(res, 200, await svc.verify(db, { from, body }));
    }
    if (req.method === 'GET' && path === '/api/directory') return send(res, 200, await svc.directory(db));

    if (path.startsWith('/api/admin/')) {
      requireAdmin(req);
      if (req.method === 'GET' && path === '/api/admin/check') return send(res, 200, { ok: true, db: db.kind, dbName: db.name });
      if (req.method === 'GET' && path === '/api/admin/orgs') return send(res, 200, await svc.listOrgs(db));
      if (req.method === 'GET' && path === '/api/admin/events') return send(res, 200, await svc.listEvents(db));
      if (req.method === 'GET' && path === '/api/admin/stats') return send(res, 200, await svc.stats(db));
      if (req.method === 'POST' && path === '/api/admin/reset') { await svc.resetAll(db); return send(res, 200, { ok: true }); }
      if (req.method === 'POST' && path === '/api/admin/orgs/create-approved') {
        const b = await readJson(req);
        const app = await svc.applyForVerification(db, { name: b.name, domain: b.domain, color: b.color, senders: [b.number] });
        return send(res, 201, await svc.decideOrg(db, app.orgId, 'approve'));
      }
      const m = path.match(/^\/api\/admin\/orgs\/(\d+)\/(approve|reject|revoke)$/);
      if (req.method === 'POST' && m) return send(res, 200, await svc.decideOrg(db, Number(m[1]), m[2]));
    }
    send(res, 404, { error: 'Not found' });
  } catch (e) {
    if (e instanceof svc.HttpError) return send(res, e.status, { error: e.message, code: e.code });
    console.error(e);
    send(res, 500, { error: 'Internal error' });
  }
}
