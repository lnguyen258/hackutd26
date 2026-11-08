# VeriText: verified senders for text messages (built on SpacetimeDB)

Texts from "your carrier" are impossible to trust: SMS has no sender authentication, so anyone can imitate a business. **VeriText** is a registry plus an Android app that shows a business text the way a social network shows a verified account — **"T-Mobile ✓"** — and warns about look-alikes. A red live counter shows how many scams and strangers were caught.

> This branch (`luong-2`) runs the **entire data layer on [SpacetimeDB](https://spacetimedb.com)**. The `luong` / `main` branches use Supabase Postgres; they are kept as the stable demo.

## Where SpacetimeDB is used

Every piece of VeriText state lives in one SpacetimeDB database, `veritext-registry` (Maincloud):

| Table (all private) | What it holds |
|---|---|
| `org` | businesses applying for / holding the verified badge, with status `pending → approved / rejected / revoked` |
| `sender` | the phone numbers each business owns (normalized for matching) |
| `token` | per-message codes bound to a hash of the exact text, with expiry |
| `event` | the activity log that powers the live dashboard |
| `config` | the single owner identity allowed to write |

The module is TypeScript, in [`spacetimedb/src/index.ts`](spacetimedb/src/index.ts).

### Reducers hold the rules
Business rules run **inside the database**, transactionally, so no client can bypass them:
- `apply_org`: rejects a name or phone number already owned by an *approved* business (anti-impersonation), then inserts the org and its senders atomically.
- `decide_org`: approve / reject / revoke; re-checks number ownership at approval time.
- `insert_token`, `log_event`, `reset_all`.
- Every reducer calls `requireAdmin`: only the **database owner identity** (set in `init` to whoever published the module) can write. An anonymous call is refused with `admin only`; we checked this against the live database.

### Security model
- All tables are **private**. Anonymous SQL gets `no such table … may be marked private`.
- The only writer/reader is the VeriText API server, which holds the owner token as a secret. Phones and the web dashboard never talk to SpacetimeDB directly, so an app user can never approve themselves.

### How the server talks to it
Plain HTTP, no SDK needed in the serverless function ([`server/src/store.js`](server/src/store.js)):
- writes: `POST /v1/database/veritext-registry/call/<reducer>` with a JSON argument array
- reads: `POST /v1/database/veritext-registry/sql`

`MemoryStore` implements the same interface for unit tests and offline development.

## Architecture
```
Any phone ──plain SMS──►  Android phone running VeriText ──HTTPS──► Vercel function (API + dashboard)
(no app needed to send)    shows "T-Mobile ✓" / ⚠ / counter                    │ owner token
                                                                               ▼
                                                              SpacetimeDB  (module: tables + reducers)
```
- **Android app** (`android/`): Messages-style inbox, verified check, Blocked list, scam counter, contacts rule. Only texts from registered numbers (or carrying a code) are ever sent to the registry.
- **API + dashboard** (`server/`): zero-dependency Node. Dashboard has live traffic charts, an approve/revoke queue and an activity feed.
- **Auth design** and honest limits: [TECHNIQUE.md](TECHNIQUE.md). Full build record: [CONTEXT.md](CONTEXT.md).

## Run it
```bash
# 1. publish the database module (needs the SpacetimeDB CLI and `spacetime login`)
cd spacetimedb && npm install && spacetime publish --server maincloud <your-db-name>

# 2. run the API locally
cd ../server
cp .env.example .env        # set SPACETIMEDB_TOKEN (owner token) and SPACETIMEDB_DB
npm start                   # dashboard on http://localhost:4010 (no token: in-memory store)

# tests
npm test                                            # 14 unit tests (in-memory store)
SMOKE_RESET=1 node --env-file=.env scripts/smoke-spacetime.js   # end-to-end against SpacetimeDB (wipes the DB)

# Android
cd ../android && ./gradlew assembleDebug            # JDK from Android Studio
```

## Honest status
- Verified end to end on real phones: registry lookup → "T-Mobile ✓" on an Android phone, with the dashboard approving businesses live.
- Default mode verifies the **sender number**. The per-message-code mode (content-bound, replay-resistant) is built and tested, and a signature-based design is described in [TECHNIQUE.md](TECHNIQUE.md) but **not built**.
- Android can't hide or delete another app's texts, so VeriText *flags and warns*; it doesn't truly block.
- No iPhone app yet (iOS only allows a Message Filter extension). An iPhone works fine as a *sender*.
- The dashboard polls every 2 s. Moving it onto SpacetimeDB subscriptions for push updates is the natural next step.
- Not affiliated with T-Mobile or any carrier; "T-Mobile" is a demo persona and the pink palette is only inspiration.
