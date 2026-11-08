import { test } from 'node:test';
import assert from 'node:assert/strict';
import { openDb } from '../src/db.js';
import * as svc from '../src/service.js';
import { canonicalize, splitToken, bodyHash, normalizeAddress } from '../src/canon.js';

process.env.VERIFY_MODE = 'strict'; // existing tests cover the strict, code-based mode

const MSG = 'Your data usage exceeds monthly limit, add more credit to continue using service.';

async function setup() {
  const db = await openDb({ path: ':memory:' });
  const app = await await svc.applyForVerification(db, {
    name: 'T-Mobile',
    domain: 'https://www.t-mobile.com/about',
    senders: ['+1 (214) 555-0100'],
  });
  const org = () => svc.orgByKey(db, app.apiKey);
  return { db, app, org };
}

test('canonicalization survives common SMS mangling', () => {
  const a = canonicalize('Don’t pay – it’s “free”…  now\r\n');
  const b = canonicalize("Don't pay - it's \"free\"... now");
  assert.equal(a, b);
  assert.equal(bodyHash('a  b'), bodyHash('a b\n'));
});

test('token marker is split off, case-insensitively', () => {
  assert.deepEqual(splitToken('hi there\n\nvt:7kq3m9xd'), { text: 'hi there', code: '7KQ3M9XD' });
  assert.equal(splitToken('no marker here').code, null);
  assert.equal(splitToken('vt:7KQ3M9XD in the middle of text').code, null);
});

test('address normalization', () => {
  assert.equal(normalizeAddress('+1 (214) 555-0100'), '2145550100');
  assert.equal(normalizeAddress('2145550100'), '2145550100');
  assert.equal(normalizeAddress('TMobile'), 'tmobile');
  assert.equal(normalizeAddress('22000'), '22000');
});

test('pending business cannot issue tokens', async () => {
  const { db, org } = await setup();
  await assert.rejects(async () => svc.issueToken(db, await org(), MSG), /pending/);
});

test('happy path: approved business -> verified', async () => {
  const { db, app, org } = await setup();
  await svc.decideOrg(db, app.orgId, 'approve');
  const t = await svc.issueToken(db, await org(), MSG);
  const r = await svc.verify(db, { from: '+12145550100', body: t.smsBody });
  assert.equal(r.decision, 'verified');
  assert.equal(r.org.name, 'T-Mobile');
  assert.equal(r.org.domain, 'www.t-mobile.com');
});

test('verified even after carrier mangles quotes/whitespace', async () => {
  const { db, app, org } = await setup();
  await svc.decideOrg(db, app.orgId, 'approve');
  const t = await svc.issueToken(db, await org(), 'Don\'t miss it - pay "now".');
  const mangled = t.smsBody.replace("'", '’').replace(' - ', ' – ').replace(/"/g, '”').replace(/\n\n/, '\r\n');
  assert.equal((await svc.verify(db, { from: '2145550100', body: mangled })).decision, 'verified');
});

test('spoof: registered number, no token -> suspicious', async () => {
  const { db, app } = await setup();
  await svc.decideOrg(db, app.orgId, 'approve');
  const r = await svc.verify(db, { from: '+12145550100', body: MSG });
  assert.deepEqual([r.decision, r.reason], ['suspicious', 'no_token']);
});

test('tampered body with a real token -> suspicious', async () => {
  const { db, app, org } = await setup();
  await svc.decideOrg(db, app.orgId, 'approve');
  const t = await svc.issueToken(db, await org(), MSG);
  const evil = t.smsBody.replace(MSG, 'Click http://evil.example to avoid disconnection.');
  const r = await svc.verify(db, { from: '2145550100', body: evil });
  assert.deepEqual([r.decision, r.reason], ['suspicious', 'body_mismatch']);
});

test('real token replayed from a different number -> suspicious', async () => {
  const { db, app, org } = await setup();
  await svc.decideOrg(db, app.orgId, 'approve');
  const t = await svc.issueToken(db, await org(), MSG);
  const r = await svc.verify(db, { from: '+19725559999', body: t.smsBody });
  assert.deepEqual([r.decision, r.reason], ['suspicious', 'sender_not_registered']);
});

test('random number, no token -> unknown (we do not cry scam)', async () => {
  const { db, app } = await setup();
  await svc.decideOrg(db, app.orgId, 'approve');
  assert.equal((await svc.verify(db, { from: '+19725550123', body: 'hey are we still on for lunch?' })).decision, 'unknown');
});

test('revoked business messages become suspicious', async () => {
  const { db, app, org } = await setup();
  await svc.decideOrg(db, app.orgId, 'approve');
  const t = await svc.issueToken(db, await org(), MSG);
  await svc.decideOrg(db, app.orgId, 'revoke');
  assert.equal((await svc.verify(db, { from: '2145550100', body: t.smsBody })).reason, 'org_revoked');
});

test('impersonation of an approved name or number is rejected', async () => {
  const { db, app } = await setup();
  await svc.decideOrg(db, app.orgId, 'approve');
  await assert.rejects(() => svc.applyForVerification(db, { name: 't-mobile', domain: 'evil.com', senders: ['5559990000'] }), /already a verified/);
  await assert.rejects(() => svc.applyForVerification(db, { name: 'Fake Telecom', domain: 'evil.com', senders: ['214-555-0100'] }), /already belongs/);
});

test('directory lists only approved/revoked senders', async () => {
  const { db, app } = await setup();
  assert.equal((await svc.directory(db)).senders.length, 0);
  await svc.decideOrg(db, app.orgId, 'approve');
  assert.deepEqual((await svc.directory(db)).senders.map((s) => s.norm), ['2145550100']);
});

test("sender mode: registered number alone is verified, other numbers are not", async () => {
  const { db, app } = await setup();
  await svc.decideOrg(db, app.orgId, 'approve');
  const ok = await svc.verify(db, { from: '+12145550100', body: 'no code here' }, { mode: 'sender' });
  assert.deepEqual([ok.decision, ok.reason, ok.org.name], ['verified', 'sender_match', 'T-Mobile']);
  const other = await svc.verify(db, { from: '+19725550123', body: 'no code here' }, { mode: 'sender' });
  assert.equal(other.decision, 'unknown');
  await svc.decideOrg(db, app.orgId, 'revoke');
  assert.equal((await svc.verify(db, { from: '2145550100', body: 'x' }, { mode: 'sender' })).decision, 'suspicious');
});
