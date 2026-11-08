// Usage: npm run seed -- "+1 214 555 0100" ["T-Mobile"] ["t-mobile.com"]
// Registers and APPROVES a business for the given phone number (skips the review step).
import { openStore } from '../src/store.js';
import * as svc from '../src/service.js';

const [number, name = 'T-Mobile', domain = 't-mobile.com'] = process.argv.slice(2);
if (!number) {
  console.error('Usage: npm run seed -- "<sender phone number>" ["Business name"] ["domain"]');
  process.exit(1);
}
const db = await openStore();
const app = await svc.applyForVerification(db, { name, domain, senders: [number], color: '#E20074' });
await svc.decideOrg(db, app.orgId, 'approve');
console.log(`Approved "${name}" (${domain}) as a verified sender for ${number} on ${db.kind === 'spacetimedb' ? 'SpacetimeDB (' + db.name + ')' : db.kind}.`);
console.log(`API key: ${app.apiKey}`);
await db.close();
