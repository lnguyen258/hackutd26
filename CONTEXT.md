# CONTEXT — VeriText: how this project happened

> Purpose: a factual record of the whole build, written so `DEVPOST.md` can be drafted from it without re-asking anyone. Everything here reflects what was actually built and observed up to **2026-11-08**. Section 12 lists what **not** to claim.
> **Branch note:** this file was written on `luong` (Supabase). On branch **`luong-2`** the whole database layer is **SpacetimeDB**; see §16. Mentions of Supabase/Postgres below describe `main`/`luong`.
> Companion docs: [INSPO.md](INSPO.md) (problem dissection), [PLAN.md](PLAN.md) (Phase 1 plan, partly superseded, see §9), [TECHNIQUE.md](TECHNIQUE.md) (authentication design and sources).

---

## 1. One-paragraph summary

**VeriText** is a "verified sender" layer for text messages. Businesses (our demo: **T-Mobile**) register with a registry and get a verified identity. When a text arrives on an Android phone, the VeriText app checks the sender against the registry and shows it like a verified social-media account: **"T-Mobile ✓"**, with a blue check. Look-alike senders are flagged. A red live counter shows how many scam or stranger texts were caught. A web dashboard (pink-themed, hosted on Vercel, backed by Supabase Postgres) lets an operator approve businesses and watch verification traffic in real time. The long-term ambition: be the trust network between enterprises, carriers and consumers, "like Visa" for business messaging, with a carrier (T-Mobile) as the anchor partner.

## 2. Origin and inspiration

- Started from a teammate conversation: an *authenticator layer for iMessage/SMS*. A business gets a token, sends the message with it, and the layer approves the token before the message "passes"; no token = flagged as scam.
- Inspiration: **Visa** (a neutral network connecting parties who don't know each other) and **Bank of America's early credit cards** (seed both sides in one place to beat the chicken-and-egg problem). Ambition stated by the team: *"letting T-Mobile handle this like Visa… every enterprise uses this service for reliable messages with consumers… feels like building Facebook."*
- Product feel borrowed from **LinkedIn/Twitter verified accounts**: a name plus a check mark.
- Research findings that shaped scope are in [INSPO.md](INSPO.md) §2–4 and [TECHNIQUE.md](TECHNIQUE.md) §2.

## 3. The problem

- SMS has no sender authentication; the sender label can be faked and spoofed messages can sit in the same thread as real ones. Consumers distrust all texts, which hurts businesses too.
- Existing fixes are partial: sender *registration* (A2P 10DLC/TCR), RCS verified senders (different channel), STIR/SHAKEN (calls only), Google's Verified SMS (reportedly folded into RCS). The FCC declined in 2023 to require text authentication for now. Details and links: [TECHNIQUE.md](TECHNIQUE.md) §2 and §9.

## 4. Platform constraints we hit (these shaped every decision)

| Platform | Reality |
|---|---|
| **iPhone** | No access to the SMS inbox. Only hook is the Message Filter extension: unknown senders only, can classify (allow/junk/promotion/transaction) but cannot badge or edit. iMessage is unreachable. Needs a Mac + Xcode, and the development machine used here is Windows. → **iOS not built**; iPhone is supported only as a *sender* (see §6). |
| **Android** | `READ_SMS`/`RECEIVE_SMS` work for a sideloaded app without being the default SMS app. Google Play restricts these permissions, so distribution is sideload for now. |
| **RCS** | RCS chats don't go into Android's SMS database. An SMS-only app never sees them (discovered during testing, §8). |
| **Cannot block** | A non-default SMS app can't delete or hide texts. We *flag and warn*, we don't truly block. |

## 5. What was built

### 5.1 Components
```
iPhone / any phone ──SMS──►  Android phone running VeriText app ──HTTPS──►  Registry API (Vercel function)
 (just sends a normal text)         │  reads inbox, checks sender,                    │
                                     │  shows ✓ / ⚠, counts scams                    ▼
                                     │                                    Supabase Postgres (+ admin dashboard)
                                     └── optional: notification access to also see RCS senders
```

| Component | Tech | Where |
|---|---|---|
| Registry API + admin dashboard | Node (no framework), `pg`, one handler shared by local server and Vercel function | `server/` |
| Database | Supabase Postgres (transaction pooler, row-level security on, no public policies); SQLite fallback for local dev | cloud |
| Hosting | Vercel (function + static dashboard), URL `https://veritext-six.vercel.app` | cloud |
| Android app | Kotlin + Jetpack Compose + Material 3, min SDK 26, target 35 | `android/` |
| Docs | INSPO / PLAN / TECHNIQUE / CONTEXT | repo root |

Repo: `github.com/lnguyen258/hackutd26`, working branch **`luong`**.

### 5.2 Server (`server/`)
- **Business onboarding:** a business applies (name, domain, sender number, brand color) → starts `pending` → an operator **approves / rejects / revokes** from the dashboard. Approved names and numbers can't be claimed by others (anti-impersonation).
- **Verification endpoint** `POST /api/verify {from, body}` → `verified | suspicious | unknown`:
  - **Sender mode (current default):** an approved registered number is verified; a revoked business is suspicious; anything else is `unknown` (we never cry scam about unregistered senders).
  - **Strict mode** (`VERIFY_MODE=strict`): additionally requires a per-message code bound to the exact text (see [TECHNIQUE.md](TECHNIQUE.md) §4). Fully built and tested but not the default.
- **Directory** `GET /api/directory`: public list of registered sender numbers, so phones only contact the server for texts that matter (privacy rule).
- **Tokens:** `POST /api/tokens` issues an 8-character code (Crockford base32, 40 bits) bound to `SHA-256(canonical text)` with a TTL (60 min). Canonicalization tolerates curly quotes, dashes and whitespace changes.
- **Admin API** (admin key required off-machine): orgs, events, stats, approve/reject/revoke, "verify this number" shortcut, reset demo data.
- **Dashboard** (`/`): admin-key gate; tiles (verified / suspicious / unrelated / codes issued / verified businesses / awaiting review); 30-minute stacked traffic chart; "why messages were flagged" breakdown; businesses list with action buttons and a "Verify this number" form; live activity feed polling every 2 s. Pink brand color, light/dark.
- **Tests:** 14 server tests (canonicalization, token flow, spoof/tamper/replay cases, revocation, anti-impersonation, sender mode) all passing.

### 5.3 Android app (`android/`)
- **Two modes in one APK** (chosen on first launch):
  - **Customer:** a Messages-style inbox ("Messages" title, round avatars, bubbles). Registered senders show their name with a **blue ✓**; the token line is hidden from the text. Flagged messages show a red warning banner and a "⚠ Not verified" chip.
  - **Business (demo):** apply for verification, wait on a live status screen, then send real SMS (verified, no-code spoof, and tampered-message simulations).
- **Privacy rule:** a text is sent to the registry only if it carries a VeriText code or comes from a number in the public directory. Friends' texts and other apps' codes never leave the phone.
- **Saved contacts come through:** a sender saved in Contacts is never flagged; the thread uses the contact's name. (Asks for Contacts permission once; lookup stays on the phone.)
- **Scam counter** (red badge next to "Messages"): +1 with a pop animation and floating "+1" when a flagged scam or a **stranger** (not a contact, not a verified business) texts. Counts only messages that arrive after install (no backfill), stores only one-way hashes and a number, never message text, and counts one burst per sender per 15 s to avoid double counting.
- **Notification listener (built, optional):** reads **only the sender title** of Google/Samsung Messages notifications so RCS texts can count toward the counter. An opt-in banner sends the user to Android's notification-access settings. 5 unit tests for the sender parser pass. **Not yet installed or tested on a phone.**
- Notifications: "✓ T-Mobile · Verified" or "⚠ Not verified — claims to be …".
- Branding: T-Mobile-style magenta (#E20074) for chrome; **verified check stays blue and warnings stay red** on purpose so trust vs. danger is unmistakable. Color only, no T-Mobile logo or wordmark.

## 6. How a demo works (verified end to end on real hardware)

1. Operator opens the dashboard (`veritext-six.vercel.app`, admin key) and verifies a number as "T-Mobile" (or the business phone applies in the app and the operator clicks **Approve**).
2. Any phone using that number sends an ordinary SMS to the customer phone. **The sender needs no app**; an iPhone works as long as it sends plain SMS (turn RCS off) and the number matches (last 10 digits).
3. The customer phone shows **"T-Mobile ✓"**, hides nothing it shouldn't, and the dashboard activity feed shows `VERIFIED · Registered sender number`.
4. Revoking the business on the dashboard makes later texts from that number show the red warning.

**Observed on real devices (2026-11-08):** a Samsung Galaxy S25 (SM-S931U1) received several SMS from the registered number; the server logged them as `verified / sender_match / T-Mobile` and the app displayed the "T-Mobile ✓" thread (e.g. a "Go pick up your phone" text at 9:43 AM and a "bro" text at 10:00 AM). A second phone, a Galaxy A22 5G (SM-A226B), also had the app installed but its Wi-Fi had no internet at the time, so it could not reach the registry.

## 7. Build timeline (what happened, in order)

1. **Research and planning:** problem dissection and a Phase 1 plan (INSPO.md, PLAN.md).
2. **Scope decision:** *Android first*; sender looks like a verified account; a second phone can act as T-Mobile. Token-based verification built first.
3. **Backend v1 (local):** zero-dependency Node server, SQLite, admin page, 13 tests.
4. **Android v1:** Compose app, SMS receiver, verified inbox, enterprise demo mode; first install on a Galaxy S25 over adb.
5. **Cloud:** moved to **Supabase Postgres + Vercel** so phones don't need the laptop's Wi-Fi. Supabase was supplied via a manually created project (the Vercel Marketplace route collided with an existing Supabase org named "Gambler").
6. **UI pass:** T-Mobile-style pink palette and a familiar Messages-app layout, in both the Android app and the dashboard.
7. **Pivot to sender-only verification:** the team had only one Android phone, and the sender would be an iPhone with no app. We reduced verification to "is this a registered sender number?" and kept the code-based mode behind a flag.
8. **Contacts rule:** saved contacts always come through.
9. **Live scam counter:** red badge with real-time +1.
10. **Debugging a "missing message":** traced to RCS bypassing the SMS database (§8), then built the notification-listener counter path.
11. **Published** code to GitHub (`main` and `luong`).

## 8. Challenges and what we learned

- **Platform walls are the real product constraint.** iOS only allows a Message Filter extension; Android needs permissions Google Play restricts. A real launch needs carrier-level support, which is why T-Mobile is the target.
- **RCS bypasses SMS apps.** A stranger's text didn't appear in the app or counter because it was delivered over RCS inside Google Messages, not the SMS database. Evidence: the phone's SMS database had no newer rows, and Google Messages was the default app with its RCS service running. Workarounds: turn RCS off for the demo, or use notification access for counting.
- **Canonicalization is the quiet risk.** Carriers and keyboards alter text (quotes, dashes, whitespace). We normalize before hashing and test it, but real-carrier testing at scale is still outstanding.
- **Security design lesson:** a code is only meaningful because of what it is *bound to* (sender, exact text, time); a random handle has nothing to reverse-engineer. Full reasoning in [TECHNIQUE.md](TECHNIQUE.md) §4–6.
- **Tooling lessons (useful "challenges" for the writeup):**
  - Android Gradle Plugin 9.x needs a newer Gradle than the one cached locally; we pinned AGP 9.0.1 with Gradle 9.3.0.
  - Windows PowerShell piping silently corrupted two secrets when setting Vercel environment variables (the database URL and admin key); piping raw bytes from Node fixed it.
  - Vercel's file-based routing only matched one-level paths, which made `/api/orgs/apply` return 404; a rewrite into a single function fixed it.
  - A real database connection string had ended up in the tracked `.env.example` template; a pre-commit secret scan caught it and it was replaced with placeholders before anything was committed or pushed.

## 9. Differences from the original plan (PLAN.md is now partly out of date)

| Plan said | What actually happened |
|---|---|
| iOS Message Filter extension | Not built (no Mac). iPhone supported as a sender only. |
| Twilio/real SMS provider | Not used; phones send real SMS directly. |
| Per-message token as the core | Built, but **sender-number mode is the default** so an app-less iPhone can be the sender. |
| Next.js + Neon | Plain Node handler on Vercel + Supabase Postgres. |
| Enterprise console as a web app | Business flow lives in the Android app; operator dashboard is a web page. |
| Android only for verified inbox | Same, plus contacts rule, scam counter and notification listener. |

## 10. Tech stack and versions
- **Server:** Node 24, `pg` 8.x, SQLite via `node:sqlite` (dev only), Vercel Functions (Node), Supabase Postgres.
- **Android:** Kotlin, Jetpack Compose (BOM 2025.09.01), Material 3, AGP 9.0.1, Gradle 9.3.0, compileSdk 36 / targetSdk 35 / minSdk 26, JDK from Android Studio. No third-party networking library (`HttpURLConnection`).
- **Security posture:** HTTPS to Vercel, Supabase row-level security enabled with no public policies, admin API behind a secret key, rate limits on public endpoints, secrets in env vars and gitignored files.

## 11. Verification status (honest scoreboard)

| Item | Status |
|---|---|
| Server logic + API on Vercel + Supabase | ✅ working, tested (14 tests; live curl checks) |
| Dashboard live traffic, approve/revoke, "verify this number" | ✅ working |
| Android inbox shows "T-Mobile ✓" for a registered sender | ✅ observed on a Galaxy S25 |
| Saved-contacts rule | ✅ built and installed; not exercised with a real contact in testing |
| Scam counter (UI) | ✅ installed; shows 0; a real +1 has **not yet been observed** |
| Counting strangers via SMS | ✅ built; not yet observed with a real stranger text |
| Counting via notification listener (RCS) | ⚠️ built and unit-tested; **not installed or tested on a device** |
| Strict (code-based) verification end to end over real SMS | ⚠️ server-tested; the Business-mode send path was never exercised between two phones |
| iOS | ❌ not built |
| Play Store / App Store | ❌ not applicable yet (sideloaded debug build) |

## 12. What NOT to claim in the Devpost
- ❌ **Any partnership, endorsement or integration with T-Mobile**, Google, Apple or any carrier. T-Mobile is a *demo persona* and a *target partner*; the color palette is inspiration only.
- ❌ "Blocks scam texts." We **flag and warn**; Android doesn't let this app hide or delete texts.
- ❌ "Cryptographically unforgeable" for the current default. Sender-number mode has no cryptographic proof. The strict code mode binds a code to the text, and the proper production design (digital signatures) is described in TECHNIQUE.md but **not built**.
- ❌ "Works on iPhone." iPhone can only be a *sender* of ordinary SMS; there is no iPhone app.
- ❌ "Works for all texts." RCS and iMessage chats are not visible to the SMS path.
- ❌ "Real-time scam detection / AI." There is no ML; verification is registry lookup.
- ❌ Any statistics we didn't measure (no accuracy, latency or adoption numbers exist).
- ✅ Safe to say: end-to-end verified texts on a real Android phone via Vercel + Supabase; real-time dashboard; privacy-minded design (only registry-relevant texts leave the phone); open design for a signed-seal upgrade.

## 13. Suggested Devpost story beats (for later)
- **Inspiration:** texts from "your carrier" you can't trust; verified checks exist everywhere except SMS; Visa as the model.
- **What it does:** the sender-check flow in §6, red scam counter, dashboard.
- **How we built it:** §5 and §10.
- **Challenges:** §8 (platform walls, RCS, canonicalization, secrets).
- **Accomplishments:** a working cloud-hosted registry and Android app verified on real phones; privacy rule; clean demo story with an app-less sender.
- **What we learned:** security comes from binding, not secrecy; carrier-level deployment is the real prize.
- **What's next:** signed seals (offline-verifiable), iOS Message Filter extension, carrier-side verification, TCR-style business vetting, notification-listener rollout. See [TECHNIQUE.md](TECHNIQUE.md) §9.

## 14. Demo assets and where things live
- **Live dashboard:** `https://veritext-six.vercel.app` (admin key in `server/.admin-key.txt`, gitignored).
- **Server env** (`server/.env`, gitignored): `DATABASE_URL`; on Vercel: `DATABASE_URL`, `ADMIN_KEY`. Optional `VERIFY_MODE=sender|strict`.
- **APK:** `android/app/build/outputs/apk/debug/app-debug.apk` (rebuild with `./gradlew assembleDebug`, JDK from Android Studio).
- **Screenshots** of the app were captured during testing in a temporary scratchpad folder and are **not saved in the repo**; retake them for Devpost.
- **Registered demo number:** the test sender number is registered as "T-Mobile" in the live database. Use the dashboard's *Reset demo data* before a judged demo, then re-verify the number.
- **Commit state:** `main` and `luong` contain the first commit; `luong` also has the contacts + counter commit. The notification-listener work, `TECHNIQUE.md` and this file are **uncommitted** at the time of writing.

## 15. Open questions / next steps
1. Observe a real **+1** on the counter (text from an unsaved number with RCS off) and record a short video.
2. Install and test the **notification listener** path, including the Android "restricted settings" prompt for sideloaded apps.
3. Run the **strict (code) mode** over real SMS between two phones and measure body-hash match rate across carriers.
4. Decide the real-world path: signed seals + aggregator integration + a carrier conversation ([TECHNIQUE.md](TECHNIQUE.md) §9).
5. Replace the manual **Approve** button with real business vetting.
6. Build the **iOS Message Filter extension** (needs a Mac).

## 16. SpacetimeDB migration (branch `luong-2`)

**Why:** to enter the SpacetimeDB track and show GitHub judges real use of it. The Supabase version on `luong`/`main` is intentionally left running as the stable demo.

**What changed**
- New module `spacetimedb/` (TypeScript, SDK 2.14): private tables `org`, `sender`, `token`, `event`, `config`; reducers `apply_org`, `decide_org`, `insert_token`, `log_event`, `reset_all`; every reducer admin-gated to the publishing identity. Published to Maincloud as **`veritext-registry`**.
- Server data layer replaced: `server/src/db.js` (SQLite/Postgres) removed; `server/src/store.js` adds `SpacetimeStore` (reducer calls + SQL over HTTP) and an in-memory `MemoryStore` for tests. `service.js` now calls the store; the rules are unchanged. The `pg` dependency is gone (the server has zero dependencies).
- Anti-impersonation moved *into the database* (reducers), so it holds even if a client misbehaves.
- Dashboard label shows "SpacetimeDB · veritext-registry".
- `scripts/smoke-spacetime.js`: end-to-end integration test against the live database (apply, approve, impersonation rejected, genuine / tampered / replay / spoof, directory, stats, revoke).

**Verified (2026-11-08)**
- Anonymous reducer call → `admin only`; anonymous SQL on a table → denied; owner calls and SQL work.
- 14 unit tests pass on `MemoryStore`; the SpacetimeDB integration script passes against the live database.
- Live API on its own Vercel project responds correctly: apply → approve → verify → directory → stats.

**Deployment (kept separate on purpose)**
- Supabase demo: Vercel project `veritext` → `https://veritext-six.vercel.app` (**unchanged**).
- SpacetimeDB version: Vercel project `veritext-spacetime` → `https://veritext-spacetime.vercel.app`, own admin key, deployed with `VERCEL_ORG_ID`/`VERCEL_PROJECT_ID` overrides so `server/.vercel` still points at the old project.

**Not done:** the Android app on the phones still talks to the Supabase deployment (only this branch's default URL was changed, no install); dashboard still polls instead of using SpacetimeDB subscriptions.
