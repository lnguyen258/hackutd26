# PLAN — Phase 1: Prove the verified-SMS loop end to end

> Companion to [INSPO.md](INSPO.md). Section references (§) point there.
> Durations are in **working sessions** (roughly half-day chunks) because we haven't fixed a deadline. Rescale once we do.

---

## 1. Goal

Demonstrate, on **real phones with real SMS**, that:

1. An enterprise can register and obtain a token for a specific message.
2. The message travels over ordinary SMS (not our channel) carrying the token.
3. **Android** shows it as **✓ Verified (Org name)**.
4. **iPhone** (Message Filter extension) lets the verified message through and **junks a spoofed message** that imitates the registered sender without a valid token.
5. A **forged / tampered / expired** token is rejected every time.

If this loop works reliably, we have a defensible technical core to show enterprises and carriers. If it doesn't (e.g., body normalization kills it), we learn that cheaply.

## 2. Scope

### In scope
- **Registry + token service** (backend API, Postgres).
- **Enterprise console** (minimal web UI): register org, claim sender numbers, send a test verified message, view logs.
- **Message sender** integration with an SMS provider (Twilio or equivalent) to send *real* SMS.
- **Android app** (sideloaded): receive SMS, extract token, verify, show verified inbox/notification.
- **iOS app + Message Filter extension**: deferred-query to our server; allow / junk decisions.
- **Attacker simulator**: script/page that sends spoofed and replayed messages for the demo.
- Canonical **body normalization** spec + test corpus.
- Basic **threat model** doc and **demo script**.

### Explicitly out of scope (Phase 1)
- Carrier integration / T-Mobile partnership work (we only prepare the pitch).
- Play Store or App Store release.
- Offline signature tokens (Option B), billing, multi-tenant admin roles, SOC2-style anything.
- iMessage, RCS, MMS media.
- Real KYC (we fake it with a manual "approve" button).
- Scale/perf beyond a few hundred verifications.

## 3. Success criteria (measurable)

| # | Criterion | Target |
|---|---|---|
| S1 | End-to-end happy path on Android (send → SMS → ✓ badge) | ≥ 95% of 50 test sends |
| S2 | Same message through iOS filter returns *allow* | ≥ 90% of 30 sends **[tune after body-hash results]** |
| S3 | Spoofed sender-with-no-token on iOS → Junk | 100% of 20 attempts |
| S4 | Token reuse on altered body / expired / unknown token → rejected | 100% |
| S5 | `/verify` p95 latency (server-side) | < 300 ms |
| S6 | Server failure → iOS filter fails **open** (no legit mail junked) | verified by kill test |
| S7 | Body-hash match rate across carriers/phones/charsets | measured and reported, with failure causes |

## 4. Architecture

```
┌──────────────────────┐        ┌───────────────────────────────┐
│ Enterprise Console   │──────▶ │  VeriText API (TypeScript)    │
│ (web, Next.js)       │        │  /orgs /senders /tokens       │
└──────────────────────┘        │  /verify  /send  /events      │
                                │  Postgres (orgs, senders,     │
┌──────────────────────┐        │  tokens, verifications)       │
│ Attacker simulator   │──SMS─▶ └──────────────┬────────────────┘
└──────────────────────┘                       │ send via provider
                                               ▼
                                   ┌────────────────────────┐
                                   │ SMS provider (Twilio)  │
                                   └──────────┬─────────────┘
                                              │ real SMS (carrier network)
                     ┌────────────────────────┴───────────────────────┐
                     ▼                                                ▼
        ┌─────────────────────────┐                    ┌─────────────────────────────┐
        │ Android app (Kotlin)    │                    │ iPhone Messages (SMS)       │
        │ SMS receiver → verify   │                    │ Message Filter extension    │
        │ → verified inbox/notif  │                    │ (Swift) → deferred query    │
        └─────────────────────────┘                    │ → /verify (server decides)  │
                                                       └─────────────────────────────┘
```

### Stack choices (defaults; change if the team prefers)
| Layer | Choice | Reason |
|---|---|---|
| Backend | TypeScript, Next.js route handlers or Hono on Vercel | Fast to ship; teammate familiarity likely; Fluid Compute handles bursts |
| DB | Postgres (Neon via Vercel Marketplace) | Relational fits registry; simple |
| Console UI | Next.js + Tailwind/shadcn | Speed |
| SMS provider | Twilio (trial → a verified toll-free/long number) | Well-documented; supports webhook for inbound if needed |
| Android | Kotlin, minSdk ~26, `BroadcastReceiver` for `SMS_RECEIVED` | Standard |
| iOS | Swift, `ILMessageFilterExtension` + host app | Only hook available |
| Crypto | Server-side secrets with HMAC for token IDs; hashing via SHA-256 | Simple, auditable |

> **Constraint:** the iOS part needs a **Mac + Xcode + Apple Developer account**. Decide owner in M0. A free account can run on a personal device for 7-day builds; Message Filter extensions may require checking provisioning limits **[verify]**.

## 5. Data model (initial)

```
orgs(id, name, domain, status[pending|approved|revoked], created_at)
senders(id, org_id, address[E.164 or alphanumeric], type[longcode|tollfree|shortcode|alpha], status)
tokens(id, token_code UNIQUE, org_id, sender_id, body_hash, recipient_hash NULL,
       issued_at, expires_at, revoked_at NULL)
verifications(id, token_code NULL, claimed_sender, body_hash, result[valid|invalid|expired|
              unknown_token|spoof_suspected|unregistered_sender|error],
              platform[android|ios|test], latency_ms, created_at)
devices(id, platform, enrolled_phone_hash NULL, created_at)    -- Android only in P1
```
Notes: store **hashes, not bodies**. `recipient_hash` = SHA-256(E.164 + server pepper); optional (Android only).

## 6. API contract (draft)

### Enterprise-facing (API key auth)
- `POST /api/tokens` → `{ sender, to?, body }` (server computes `body_hash` from canonicalized `body`)
  → `{ token_code, expires_at, body_with_token }` (the exact text to send)
- `POST /api/send` (convenience for demo) → issues token + sends via Twilio, returns provider SID.
- `GET /api/events` → recent verification results (powers console log).

### Device-facing (unauthenticated but rate-limited; no PII)
- `POST /api/verify` `{ from, body }` (iOS deferred query shape) **or** `{ from, token, body_hash }` (Android)
  → `{ decision: "verified" | "suspicious" | "unknown", org?: {name, logo}, category?: "transaction"|"promotion", reason }`
- iOS adapter route: Apple's deferred request/response format in `/api/ios/filter` that maps `decision` → `ILMessageFilterAction` **[verify exact JSON contract in Apple docs]**.

### Decision table (server)
| Sender in registry? | Token present & valid for this body? | Decision |
|---|---|---|
| Yes | Yes | `verified` |
| Yes | Missing / invalid / expired / mismatch | `suspicious` (iOS → junk, Android → red warning) |
| No | – | `unknown` (leave alone) |
| any | server error | `unknown` + log (**fail open**) |

## 7. Token & canonicalization spec (decide in M1; test in M2)

- **Token code:** 8 chars from Crockford base32 (≈40 bits), random, DB-unique; embedded as a trailing marker, e.g. `\n✓VT:7KQ3M9XD`. Plain-ASCII fallback `VT:7KQ3M9XD` (avoid UCS-2 upshift that halves segment capacity: **the ✓ glyph forces UCS-2, so ship ASCII-only in P1**).
- **Binding:** `(org_id, sender_id, body_hash)`; TTL default **15 min** (configurable, since SMS can be delayed).
- **Canonicalization** (applied to body *with the token marker stripped*): Unicode NFKC; normalize `\r\n`→`\n`; replace curly quotes/dashes/ellipsis with ASCII; collapse runs of whitespace; trim. Hash = SHA-256 hex of UTF-8 bytes.
- **Multi-segment messages:** verify only on the **reassembled** body (both OS reassemble before delivery; confirm in M2).
- **Test corpus:** ≥ 40 messages (ASCII, emoji, accents, curly quotes, long >160 chars, URLs, newlines). Run through Twilio → real carrier → Android + iPhone and record the received text; fix canonicalization until match ≥ target.

## 8. Workstreams and tasks

### WS-A — Backend & registry (owner: _TBD_)
- [ ] A1 Project scaffold, env config, DB migrations
- [ ] A2 Orgs/senders CRUD + manual approve endpoint
- [ ] A3 Canonicalization lib + unit tests (shared fixtures with mobile apps)
- [ ] A4 `/tokens` issue + `/send` via Twilio
- [ ] A5 `/verify` + decision table + audit log
- [ ] A6 Rate limiting, input size caps, idempotent repeat verifies
- [ ] A7 iOS adapter route `/ios/filter`
- [ ] A8 Fail-open behavior + health endpoint

### WS-B — Enterprise console (owner: _TBD_)
- [ ] B1 Register org + add sender numbers (mock KYC "approve")
- [ ] B2 "Send verified test message" form (to a phone number)
- [ ] B3 Live event log of verifications (shows valid/invalid/spoof)
- [ ] B4 Attacker simulator page: send **spoof w/o token**, **tampered body**, **replayed token on new body**

### WS-C — Android (owner: _TBD_)
- [ ] C1 Project, permissions (`RECEIVE_SMS`, `READ_SMS`), runtime grant flow
- [ ] C2 Receiver: parse token marker, canonicalize body (port of shared lib), call `/verify`
- [ ] C3 Verified inbox UI: ✓ badge + org name; red banner for `suspicious`
- [ ] C4 Local notification for each decision
- [ ] C5 Offline/failed-network handling (queue and retry; show "unverified (offline)")
- [ ] C6 Enrollment screen (optional recipient hash binding)

### WS-D — iOS (owner: _TBD_, needs Mac)
- [ ] D1 Host app + Message Filter extension target + entitlements
- [ ] D2 Deferred-query handler → our server; map response → action
- [ ] D3 Offline fallback: if no response/time out → `.none` (fail open)
- [ ] D4 Onboarding screen explaining *Settings → Messages → Unknown & Spam*
- [ ] D5 Test on physical device (simulator can't receive SMS)
- [ ] D6 Record what the server actually receives (sender/body/other fields) and document in `docs/ios-filter-findings.md`

### WS-E — Security & docs (everyone, lightweight)
- [ ] E1 `docs/threat-model.md` covering §5.3 items with mitigation status
- [ ] E2 `docs/privacy.md`: what we store (hashes), retention, what iOS sends us
- [ ] E3 `docs/demo-script.md`
- [ ] E4 `docs/findings-body-normalization.md`: results from the test corpus

## 9. Milestones

| # | Milestone | Exit criteria | Sessions |
|---|---|---|---|
| **M0** | Alignment & setup | Owners assigned; Mac/iPhone/Android devices + Twilio number secured; repo + CI skeleton; wedge narrative chosen (OTP vs carrier-originated vs campus) | 1 |
| **M1** | Spec freeze | Token format, canonicalization, API contract, decision table agreed (§§6–7) | 1 |
| **M2** | **De-risk spike (do before building UIs):** send corpus via Twilio → real phones; measure body-hash match | Report with match % and failure causes; canonicalization v1 passing ≥ target | 2 |
| **M3** | Backend core | A1–A6 done; unit + integration tests green; Postman/curl demo of issue→send→verify | 3 |
| **M4a** | Android vertical slice | C1–C3 working with live backend; S1 measured | 3 |
| **M4b** | iOS vertical slice (parallel to M4a) | D1–D3 working on a physical iPhone; S2, S3 measured | 4 |
| **M5** | Console + attacker sim | B1–B4 done; end-to-end demo from browser | 2 |
| **M6** | Hardening & evidence | S4–S7 measured; fail-open kill test; threat model + privacy docs | 2 |
| **M7** | Demo & pitch pack | Rehearsed demo, 2–3 slide narrative, "what we need from a carrier" one-pager | 1 |

**Critical path:** M2 → M3 → (M4a ∥ M4b) → M6. If M2 shows hash matching is unreliable, we pause and redesign (e.g., bind to a looser content digest, or switch to link-token Option D, or sender+recipient+time binding) **before** building mobile apps.

## 10. Risks & mitigations (Phase-1 specific)

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Body altered in transit → false invalid | Med-High | High | M2 spike first; canonicalization; fallback binding strategy |
| iOS filter server contract/limits differ from assumption | Med | High | D6 early; read Apple docs in M0; build adapter thin |
| No Mac / provisioning problems for iOS | Med | High | Resolve in M0; fallback = Android-only demo + iOS recorded design |
| Twilio trial restrictions / carrier filtering our test texts | High | Med | Verified toll-free or registered number; use few test recipients; log provider delivery status |
| Android permission friction on newer OS (restricted settings for sideloaded apps) | Med | Med | Document the sideload steps; be ready to use notification-listener fallback |
| Scope creep to "build the network" | High | High | This plan's out-of-scope list; weekly check against success criteria |
| Privacy objections to sending bodies to server (iOS) | Med | Med | Be upfront in docs; minimal retention; explain Option B roadmap |

## 11. Demo script (target ~4 minutes)

1. **Problem (30s):** show a spoofed "T-Mobile" text in a normal thread; "which is real?"
2. **Register (30s):** enterprise console registers "Demo Telecom", approves sender number.
3. **Verified send (60s):** send bill alert → Android shows **✓ Verified · Demo Telecom**; iPhone delivers normally.
4. **Attack (60s):** attacker sim sends the same look-alike text from the registered number with no token → Android red banner; iPhone sends it to **Junk**.
5. **Tamper (30s):** replay valid token on a different body → rejected in the live log.
6. **Close (30s):** "Today this is an overlay app. With a carrier it's network-level — the Visa model."

## 12. Open decisions (need an owner and answer in M0)

1. Who owns each workstream? Who has the Mac?
2. Wedge: OTP/alerts vs carrier-originated vs campus pilot?
3. SMS provider: Twilio (default) or other? Which number type for testing?
4. Android sideload vs notification-listener as the primary path?
5. Do we pursue a quick "prior art" sweep (INSPO §8.1) in M0? **Recommended: yes, half a session.**
6. Deadline/event this is aimed at (rescale the session counts accordingly).

## 13. Definition of done for Phase 1

All success criteria S1–S7 have **recorded measurements** (not just "it worked once"); docs E1–E4 exist; a teammate who hasn't touched the code can run the demo from `docs/demo-script.md`; and we can honestly answer: *"Does token-based SMS verification work on real phones with real carriers, and what exactly breaks?"*
