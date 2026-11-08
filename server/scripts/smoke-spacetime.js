// Integration test against the REAL SpacetimeDB database. DESTRUCTIVE: it resets the database first.
// Run:  SMOKE_RESET=1 node --env-file=.env scripts/smoke-spacetime.js
import assert from 'node:assert/strict';
import { openStore } from '../src/store.js';
import * as svc from '../src/service.js';

if (process.env.SMOKE_RESET !== '1') {
  console.error('This wipes the SpacetimeDB database. Re-run with SMOKE_RESET=1 to confirm.');
  process.exit(1);
}
const store = await openStore();
assert.equal(store.kind, 'spacetimedb', 'set SPACETIMEDB_TOKEN to run against SpacetimeDB');
const MSG = 'Your data usage exceeds monthly limit, add more credit to continue using service.';
const step = (n) => console.log('  ok  ' + n);

await svc.resetAll(store);
const app = await svc.applyForVerification(store, { name: 'T-Mobile', domain: 't-mobile.com', senders: ['+1 (205) 394-6375'] });
step('apply -> pending (orgId ' + app.orgId + ')');
const org = () => svc.orgByKey(store, app.apiKey);

await assert.rejects(async () => svc.issueToken(store, await org(), MSG), /pending/);
step('pending business cannot issue codes');

await svc.decideOrg(store, app.orgId, 'approve');
step('operator approves (reducer decide_org)');

await assert.rejects(() => svc.applyForVerification(store, { name: 'T-Mobile', domain: 'evil.com', senders: ['5550001111'] }), /already a verified/);
await assert.rejects(() => svc.applyForVerification(store, { name: 'Fake Telecom', domain: 'evil.com', senders: ['205-394-6375'] }), /already belongs/);
step('module rejects impersonation (name and number)');

const t = await svc.issueToken(store, await org(), MSG);
const genuine = await svc.verify(store, { from: '+1 205-394-6375', body: t.smsBody }, { mode: 'strict' });
assert.deepEqual([genuine.decision, genuine.reason, genuine.org.name], ['verified', 'ok', 'T-Mobile']);
step('genuine message verifies');

const tampered = await svc.verify(store, { from: '+12053946375', body: t.smsBody.replace('data usage', 'Pay at evil.example') }, { mode: 'strict' });
assert.deepEqual([tampered.decision, tampered.reason], ['suspicious', 'body_mismatch']);
const replay = await svc.verify(store, { from: '+19725550123', body: t.smsBody }, { mode: 'strict' });
assert.deepEqual([replay.decision, replay.reason], ['suspicious', 'sender_not_registered']);
const spoof = await svc.verify(store, { from: '2053946375', body: MSG }, { mode: 'strict' });
assert.deepEqual([spoof.decision, spoof.reason], ['suspicious', 'no_token']);
const sender = await svc.verify(store, { from: '2053946375', body: MSG }, { mode: 'sender' });
assert.deepEqual([sender.decision, sender.reason], ['verified', 'sender_match']);
const stranger = await svc.verify(store, { from: '+19725550123', body: 'hello' }, { mode: 'sender' });
assert.equal(stranger.decision, 'unknown');
step('tamper / replay / spoof flagged; sender mode + stranger behave');

const dir = await svc.directory(store);
assert.deepEqual(dir.senders.map((s) => s.norm), ['2053946375']);
const st = await svc.stats(store);
assert.ok(st.totals.verified >= 2 && st.totals.suspicious >= 3 && st.totals.issued === 1);
assert.equal((await svc.listEvents(store, 5)).length, 5);
step('directory, stats and activity feed read back from SpacetimeDB');

await svc.decideOrg(store, app.orgId, 'revoke');
assert.equal((await svc.verify(store, { from: '2053946375', body: 'x' }, { mode: 'sender' })).reason, 'org_revoked');
step('revocation takes effect');

await svc.resetAll(store);
console.log('\nAll SpacetimeDB integration checks passed.');
