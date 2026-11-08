<div align="center">

# VeriText ✓

### Verified senders for text messages. Powered by SpacetimeDB.

**SMS has no sender authentication. Anyone can pretend to be your carrier, your bank, or your delivery service.**
VeriText fixes the trust gap: business texts show up like a verified social account, **"T-Mobile ✓"**, and look-alikes get a warning.

`SpacetimeDB` · `HMAC-SHA256` · `Android` · `Node.js`

</div>

---

## Why it matters

| The problem | What VeriText does |
|---|---|
| Any sender name or number can be spoofed | Checks the sender against a registry of **approved businesses** |
| Scam texts look identical to real ones | Shows a **✓ badge** for verified senders and a **⚠ warning** for look-alikes |
| Nobody sees how big the problem is | A **live red counter** shows how many scams and strangers were caught |

---

## Our technique: HMAC + SHA-256

VeriText authenticates messages with **HMAC using SHA-256** (and comparable algorithms in the SHA-2 family).

- Each verified business holds a secret key registered with VeriText.
- For every message, the business computes an **HMAC-SHA256 tag over the exact text** it sends.
- The registry recomputes the tag and compares. If even one character was changed, the tag no longer matches.
- Tokens carry an **expiry**, so a captured code cannot be replayed later.

The sender's identity, the message content, and the time window are bound together by one keyed hash. Full auth design and limits: [TECHNIQUE.md](TECHNIQUE.md).

---

## Built on SpacetimeDB

The **entire data layer** runs on one [SpacetimeDB](https://spacetimedb.com) database, `veritext-registry`, hosted on Maincloud. The module is written in TypeScript: [`spacetimedb/src/index.ts`](spacetimedb/src/index.ts).

### Tables (all private)

| Table | What it holds |
|---|---|
| `org` | Businesses applying for or holding the badge. Status moves `pending → approved / rejected / revoked` |
| `sender` | Phone numbers each business owns, normalized for matching |
| `token` | Per-message codes bound to a hash of the exact text, with expiry |
| `event` | Activity log that powers the live dashboard |
| `config` | The single owner identity allowed to write |

### Reducers enforce the rules

Business rules run **inside the database, transactionally**. No client can bypass them.

- **`apply_org`**: rejects a name or number already owned by an *approved* business (anti-impersonation), then inserts the org and its senders atomically.
- **`decide_org`**: approve, reject, or revoke. Re-checks number ownership at approval time.
- **`insert_token`**, **`log_event`**, **`reset_all`**.
- Every reducer calls `requireAdmin`. Only the **database owner identity** (set in `init` to whoever published the module) can write. Anonymous calls fail with `admin only`. We tested this against the live database.

### Security model

- 🔒 **All tables are private.** Anonymous SQL returns `no such table … may be marked private`.
- 🔑 **One writer.** The VeriText API server holds the owner token as a secret.
- 📵 **No direct access.** Phones and the web dashboard never talk to SpacetimeDB, so an app user can never approve themselves.

### How the server talks to it

Plain HTTP, no SDK needed in the serverless function ([`server/src/store.js`](server/src/store.js)):

```text
write  POST /v1/database/veritext-registry/call/<reducer>    JSON argument array
read   POST /v1/database/veritext-registry/sql
```

`MemoryStore` implements the same interface for unit tests and offline development.

---

## Architecture

```text
Any phone ──plain SMS──► Android phone running VeriText ──HTTPS──► Vercel function (API + dashboard)
(no app needed to send)  shows "T-Mobile ✓" / ⚠ / counter                   │ owner token
                                                                            ▼
                                                           SpacetimeDB (tables + reducers)
```

- **Android app** (`android/`): Messages-style inbox, verified check, Blocked list, scam counter, contacts rule. Only texts from registered numbers (or carrying a code) are ever sent to the registry.
- **API + dashboard** (`server/`): zero-dependency Node. Live traffic charts, an approve/revoke queue, and an activity feed.
- **Docs:** [TECHNIQUE.md](TECHNIQUE.md) for auth design and honest limits, [CONTEXT.md](CONTEXT.md) for the full build record.

---

## Run it

```bash
# 1. Publish the database module (needs the SpacetimeDB CLI and `spacetime login`)
cd spacetimedb && npm install && spacetime publish --server maincloud <your-db-name>

# 2. Run the API locally
cd ../server
cp .env.example .env        # set SPACETIMEDB_TOKEN (owner token) and SPACETIMEDB_DB
npm start                   # dashboard at http://localhost:4010 (no token: in-memory store)
```

```bash
# Tests
npm test                                                        # 14 unit tests (in-memory store)
SMOKE_RESET=1 node --env-file=.env scripts/smoke-spacetime.js   # end-to-end vs SpacetimeDB (wipes the DB)

# Android
cd ../android && ./gradlew assembleDebug                        # JDK from Android Studio
```

---

<sub>Not affiliated with T-Mobile or any carrier. "T-Mobile" is a demo persona and the pink palette is only inspiration.</sub>
