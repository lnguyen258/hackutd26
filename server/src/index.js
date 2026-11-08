// Local dev server. On Vercel the same handler runs from api/[...path].js.
import { createServer } from 'node:http';
import { networkInterfaces } from 'node:os';
import { handler, getDb } from './app.js';

const PORT = Number(process.env.PORT || 4010);
const db = await getDb();
console.log(`Database: ${db.kind === 'postgres' ? 'Supabase / Postgres' : 'local SQLite file'}`);

createServer(handler).listen(PORT, '0.0.0.0', () => {
  console.log(`\nVeriText server on port ${PORT}`);
  console.log(`  Dashboard : http://localhost:${PORT}/`);
  for (const [name, addrs] of Object.entries(networkInterfaces())) {
    for (const a of addrs ?? []) if (a.family === 'IPv4' && !a.internal) console.log(`  LAN       : http://${a.address}:${PORT}   (${name})`);
  }
  console.log('');
});
