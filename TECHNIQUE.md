# TECHNIQUE — How to authenticate an SMS sender for real

> Scope: **only the authentication process.** What proves "this text really came from T-Mobile"? How would it work in production, can it be reverse-engineered, how does time play in, and what do others already do?
> Research date: 2026-11-08. Every external claim links to its source. Where a source is a vendor blog or I could not open the primary document, it is marked **(secondary)** or **(unconfirmed)**. Claims that come from my own reasoning (not a source) are marked **(my analysis)**.

---

## 0. Short answers to your questions

| Your question | Answer |
|---|---|
| Is the token **non-reverse-engineerable**? | Phase 1's code is a **random lookup handle**, not a computed value. There is no algorithm or secret in the APK to reverse. The real security comes from *what the code is bound to* (see §4), not from the code looking mysterious. In production, use **digital signatures** (asymmetric): the app holds only public keys, so extracting everything from the APK still can't forge a message. |
| Is it **changing**? | Yes, a new code per message. Keys rotate too (key ID, like DKIM's selector). |
| Does **time** matter? | Yes. Each seal has an issued-at and an expiry. SMS is slow and unreliable, so the window is minutes to hours, not 30 seconds (§6). |
| Is it **single-use**? | No, deliberately. A seal is bound to the exact message content and recipient, so replaying it only reproduces the same genuine message (§5). |
| Did anyone already do this? | Parts, yes. STIR/SHAKEN signs **phone calls** with public-key certificates. DKIM signs **email**. Google's **Verified SMS** did per-message checks for SMS (since folded into RCS). Nobody ships a US-wide per-message authentication for plain SMS, and the FCC chose not to require one (§7). |

---

## 1. The core problem

SMS has **no sender authentication in the protocol**. The "From" is a label, not an identity. Anyone who can inject a message can usually set it. The FCC's own record describes spoofing as *comparatively* rare in SMS/MMS, but that is a statement about how common it is today, not about whether the protocol protects you ([CRS summary of FCC action](https://www.congress.gov/crs-product/R48941): "no equivalent authentication framework exists for text messages").

So any scheme must answer three separate questions:

1. **Who is the sender?** (identity: is "T-Mobile" a real, vetted business?)
2. **Did they send *this* message?** (integrity: was the text altered or invented?)
3. **Is it fresh and meant for me?** (replay: is this an old or forwarded message?)

Industry covers (1) well, (2) and (3) barely.

---

## 2. What exists today (prior art)

### 2.1 Sender *registration* (identity only, not per message)
- **A2P 10DLC / The Campaign Registry.** Brands and campaigns are registered; TCR checks the brand's EIN, legal name and address against third-party sources, and a separate paid "vetting" review produces a trust score ([Infobip](https://www.infobip.com/docs/usa-sender-registration/brand-vetting/10dlc-vetting), [Vonage](https://developer.vonage.com/en/10-dlc/brand-vetting)) **(secondary)**. Carriers use this to allow or block traffic. The recipient sees nothing.
- **RCS verified sender / RCS Business Messaging.** Google and carriers verify the business, then the app shows name, logo and a check mark ([Google Messages help](https://support.google.com/messages/answer/9326240), [Infobip](https://www.infobip.com/blog/rcs-verification) **(secondary)**). It is a different channel from SMS, and Google's page notes verified business chats are not end-to-end encrypted and that availability varies by region and carrier.
- **Apple Messages for Business.** Brand profile plus Apple review before launch **(secondary; Apple's own page not retrieved)**.

### 2.2 Cryptographic per-message proof
**STIR/SHAKEN (voice).** The closest precedent for what you want.
1. The originating carrier creates a **PASSporT**: a signed JWT ([RFC 8225](https://www.rfc-editor.org/rfc/rfc8225.html)).
2. Header: `typ=passport`, `alg=ES256`, `x5u` = URL of the signer's certificate, optional `ppt`. Payload: `iat` (issued at, required), `orig` (calling number, exactly one), `dest` (called number(s), at least one).
3. SHAKEN adds an attestation level (`attest`: full / partial / gateway) and `origid` ([RFC 8588](https://www.rfc-editor.org/rfc/rfc8588.html), [TransNexus overview](https://transnexus.com/whitepapers/understanding-stir-shaken/)).
4. The terminating carrier fetches the certificate, checks it chains to a trusted authority, and verifies the signature.
5. RFC 8225 says `iat` "is included to protect against replay and cut-and-paste attacks" and should fall within a time "reasonable for clock drift and transmission time"; `dest` stops the token being reused toward other recipients.

**DKIM (email).** ([RFC 6376](https://www.rfc-editor.org/rfc/rfc6376.html))
1. The signer hashes the **canonicalized body** (`bh=`), then signs selected headers plus that hash (`b=`).
2. The public key lives in DNS at `<selector>._domainkey.<domain>`. An empty key value means revoked. Selectors allow several keys per domain, so keys can rotate.
3. Canonicalization (`simple` or `relaxed`) exists precisely so harmless in-transit changes (whitespace) don't break the signature.
4. `t=` is the signing time, `x=` the expiry; the RFC notes `x=` "is not intended as an anti-replay defense".

**Google Verified SMS** ((secondary) sources: [XDA](https://www.xda-developers.com/googles-messages-adds-verified-sms-feature-verify-texts-businesses/), [Plivo](https://www.plivo.com/blog/google-verified-sms/)). Reported mechanism: Messages checks the sender against a list of registered businesses; the **phone computes a hash from the user's phone number, the business and the message content and sends it to Google**, which compares it with the one it "received in parallel from the verified business". Nothing extra rides in the SMS. A mismatch shows "Message could not be verified". **Status: unclear.** Vendor sources say it was retired and folded into RCS Business Messaging, but disagree on when (2022 vs. late 2023) ([CM.com](https://www.cm.com/glossary/what-is-verified-sms/) **(secondary)**). I found no primary confirmation either way.

### 2.3 Time-based one-time passwords (different problem, useful lessons)
TOTP ([RFC 6238](https://datatracker.ietf.org/doc/html/rfc6238)): `T = floor((now − T0) / X)`, default step **X = 30 s**, code = truncation of HMAC over `T`. The RFC recommends allowing **at most one time step** of network delay, and says a verifier **must not accept the same OTP twice**. A larger step "exposes a larger window to attack."

Lesson: TOTP proves a *user* holds a shared secret. It does **not** fit sender authentication: it needs a secret shared with every verifier (impossible for millions of phones), and the code is not tied to message content.

---

## 3. Design options for SMS (my analysis, built on the sources above)

| Option | Who can forge it? | Reverse-engineerable from the app? | Works offline? | Size | Notes |
|---|---|---|---|---|---|
| **A. Random handle + server lookup** *(Phase 1)* | Only someone who can guess a valid handle **for the exact text** | Nothing to reverse: random, not computed | No | ~14 chars | Needs the registry online. Server sees the text hash. |
| **A′. Zero-footprint digest** (Google Verified SMS style) | Only the business, via an authenticated API call | Nothing in the SMS to reverse | No | 0 chars | Phone computes `H(sender ‖ canonical body [‖ recipient])`, asks registry "did this business register it?". Cleanest message; no scrapeable code. |
| **B. Public-key signature in the SMS** | Only the holder of the private key | **No**: app has only public keys | **Yes** | ~110 chars | Strongest and most private. Uses a lot of the SMS. |
| **C. HMAC with a shared secret** | Anyone with the secret | **Yes, if the secret ships in the app** | Yes | short | Rejected: any secret in a distributed app can be extracted. |
| **D. TOTP** | Anyone with the shared secret | Same problem as C | Yes | 6 digits | Wrong tool (§2.3). |

**Recommendation (my analysis):** build A′/A first (what we already have is A), keep B as the offline/privacy upgrade, and never use C or D for sender authentication.

---

## 4. Why a random handle is hard to abuse: binding is the security

A handle only means something because the registry stores **what it was issued for**:

```
token(code=7KQ3M9XD)  ->  { org: T-Mobile,  body_hash: SHA-256(canonical text),  issued_at, expires_at }
```

To pass verification an attacker must show a message where **all** of these hold:
1. It arrives from a number registered to T-Mobile,
2. its text hashes to the stored `body_hash`, and
3. the code is a valid, unexpired code for that hash.

Guessing the 40-bit code (≈ 1.1 × 10¹²) is **useless on its own**, because the code only validates against the one text the business actually sent. The attacker would need T-Mobile to issue a code for *their* text (account compromise or a malicious customer), or a SHA-256 preimage. So the practical attack surface is the business account and the registry, not the code.

> Honest caveat: Phase 1's current default is **sender-number mode** (a registered number alone is verified, no code needed), because the demo sender is an ordinary phone without the app. That mode has **no cryptographic proof**: it relies on the carrier not letting the number be spoofed. The code-based mode exists in the server (`VERIFY_MODE=strict`) and is the one to ship.

---

## 5. Replay, forwarding and "single use"

Strict single-use does not work for SMS:
- A phone may verify the same message more than once (re-open the app, iOS Message Filter may query repeatedly), so "first verification wins" would wrongly reject honest repeats.
- TOTP's single-use rule exists because a 6-digit code isn't tied to anything else. Ours is.

Instead, **make replay harmless by construction** (mirrors PASSporT's `dest` and `iat`):

| Replay attempt | Why it fails or is harmless |
|---|---|
| Re-send the same message to the same person | It *is* the genuine message; nothing gained. |
| Reuse the code on different text | `body_hash` mismatch → rejected. |
| Send the genuine message to a different victim | Production seal includes `dest` (recipient). Android knows its own number and checks it. **iOS Message Filter can't learn the user's number**, so this stays a known gap on iPhone. |
| Reuse an old seal later | `expires_at` check. |
| Spoof from another number | Sender ≠ registered number → flagged. |

---

## 6. How "time" is used

- **Per-message expiry** (`exp`), not a 30 s rolling code. SMS can be delayed minutes, so TTL is **minutes to hours**. Phase 1: 60 min (`TOKEN_TTL_MIN`).
- **Issued-at (`iat`)** lets the verifier reject anything issued in the future or too far in the past, allowing for clock drift (RFC 8225's guidance).
- Verification should happen **on receipt** and the result be **cached**. Re-checking days later would wrongly show "expired".
- If the phone is offline at receipt, show "couldn't verify yet" and retry; do not guess.

---

## 7. The step-by-step production flow (recommended)

### 7.1 One-time onboarding (identity)
1. Business applies: legal name, domain, EIN or equivalent, the sender numbers it will use.
2. Registry vets it, like TCR/RCS do (identity check against independent sources), and proves control of each number (e.g. a challenge text or call).
3. Business generates a **signing key pair** (Ed25519 or ES256, matching RFC 8032/8225). The **private key stays with the business or its provider**; the registry stores the **public key** and a **key ID (`kid`)**.
4. Public keys are published as a signed, cacheable directory (like DKIM selectors in DNS), with **revocation** and **rotation** (new `kid`, old one retired).

### 7.2 Per message, sender side
1. Business has the message text `M` for recipient `R` from sender number `S`.
2. Canonicalize `M` (Unicode NFKC, curly quotes/dashes to ASCII, collapse whitespace): same idea as DKIM `relaxed`.
3. Build the claims:
   `{ iss: org, kid, orig: S, dest: H(R), iat, exp, bh: SHA-256(canon(M)) }`
4. **Option B (offline):** sign the claims with the private key and append the compact seal to the SMS.
   **Option A / A′ (online):** send the claims to the registry over an authenticated API (mTLS or signed request) and append only a short handle, or nothing.
5. Send the SMS through any provider. The carrier path doesn't change.

### 7.3 Per message, receiver side (phone or carrier)
1. Receive the SMS from address `A`. Extract the seal/handle if present.
2. Check `A` against the **public directory of registered senders** (cached, privacy-friendly: no message leaves the phone for non-registered senders).
3. Recompute `bh` from the received text after canonicalization.
4. Verify:
   - **Signature (B):** fetch/cached public key by `kid`; check not revoked; verify signature over the claims.
   - **Lookup (A/A′):** ask the registry whether `(orig, bh[, dest])` was registered and is unexpired.
5. Check `orig == A`, `now ∈ [iat − skew, exp]`, and (where the platform allows) `dest == H(my number)`.
6. Decide:
   - registered sender + valid → **Verified** (show name, logo, check)
   - registered sender + missing/invalid/expired → **Suspicious** (warn / filter to junk)
   - not a registered sender → leave alone (we can't claim it's a scam)
7. Cache the result keyed by `H(sender ‖ text)`; never re-verify later.

```
Business                Registry (public keys,             Phone / Carrier
                        revocation, vetting)
   |  vet + register keys ------>|                              |
   |  canonicalize, build claims, sign (or POST digest) -------->|
   |================ ordinary SMS carrying seal/handle ==========>|
   |                              |<-- directory + keys (cached)-|
   |                              |   recompute body hash        |
   |                              |   verify sig / lookup        |
   |                              |   check orig, dest, time     |
   |                              |-----> Verified / Suspicious  |
```

### 7.4 Size budget for an in-band signature (my arithmetic)
Ed25519 signatures are 64 bytes ([RFC 8032](https://www.rfc-editor.org/rfc/rfc8032): "signatures (64 or 114 bytes)"). With version (1), key ID (4), issued-at (4), TTL (2) and a truncated destination hash (8), the seal is ~83 bytes ≈ **111 characters of base64url**. A plain-text SMS segment holds 160 GSM-7 characters (153 per segment when concatenated, 70/67 if any character forces UCS-2) **(general SMS knowledge, not fetched from a source)**. So a signed seal consumes most of one segment and usually pushes a message into two. That is why Option A/A′ is the practical first step and B the upgrade.

---

## 8. Attacks that remain (be honest in the pitch)

| Attack | Defense / status |
|---|---|
| **Scammer simply sends no seal** | Only helps against registered senders: we flag any text from a registered number without a valid seal. Unregistered senders are *not* flagged, so a scammer who uses an unregistered number is invisible to this system. **Biggest structural limit.** |
| **Compromised business account / API key** | Per-org rate limits, anomaly alerts, key rotation, 2-person approval, fast revocation. The registry is the crown jewel. |
| **Malicious or sloppy customer gets a seal on scam text** | Strong onboarding vetting, content policy, audit log, revocation (as TCR vetting does). |
| **Number takeover (SIM swap, number recycling)** | Re-prove number control periodically; tie seals to key possession, not just the number. |
| **Registry impersonation / typo-squatting a brand name** | Name+domain+number uniqueness checks (Phase 1 already rejects names or numbers owned by approved businesses). |
| **Canonicalization drift** (carrier alters text) | Test corpus across carriers/phones; DKIM's `relaxed` lesson. False "altered" results are the main usability risk. |
| **Verifier privacy** (server sees text/hash) | Send hashes, not text, from Android; offline signature (B) removes the call; iOS deferred queries send the full message to the server (see below). |
| **Real-time phishing with a genuine seal** | A seal proves *who sent it*, not that a link is safe. Pair with URL reputation. |
| **Users without the app/extension** | Unprotected. Real fix is carrier-side checking. |

**iPhone-specific.** The only hook is the Message Filter extension. When it defers to a server, the system sends the sender and message text as JSON to your URL and applies your reply ([Apple: `deferQueryRequestToNetwork`](https://developer.apple.com/documentation/identitylookup/ilmessagefilterextensioncontext/deferqueryrequesttonetwork(completion:)); I could not retrieve the page body, so this is from search summaries **(unconfirmed)**). It only affects senders not in Contacts, can return allow/junk/promotion/transaction, and cannot show a badge. Option B (offline signature check in the extension) would avoid sending message text to any server.

---

## 9. Path to real deployment

1. **Overlay network (what we have).** App + registry. Android shows the badge; iOS can only junk.
2. **Aggregator integration.** CPaaS providers (the ones businesses already send through) add "attach seal" at send time. This is how STIR/SHAKEN was rolled out: signing at the originating provider.
3. **Carrier verification.** A carrier verifies at network ingress and marks or blocks for *every* customer, no app needed. This is the Visa/T-Mobile endgame. Regulators haven't required it: the FCC in 2023 declined, for now, to adopt caller-ID authentication requirements for text and asked for comment on text authentication and industry standards ([FCC fact sheet](https://docs.fcc.gov/public/attachments/DOC-398661A1.pdf), [CRS report](https://www.congress.gov/crs-product/R48941)). I could not confirm which of the FCC's two 2023 orders (FCC 23-21 or FCC 23-107) carries the exact sentence, so verify before quoting. **I found no 2026 text-authentication mandate.**
4. **Governance.** Real trust needs a neutral authority for who may sign (the role STI-CA plays for calls, TCR for 10DLC), and a revocation process. This is the Visa-style rulebook, and it is a bigger job than the code.

---

## 10. What we built vs. what is recommended

| | Built in Phase 1 | Recommended for production |
|---|---|---|
| Identity vetting | Manual approve button | TCR-style KYC + number-control proof |
| Per-message proof | Random 40-bit handle bound to body hash (strict mode); **default is sender-number only** | Signed seal (B) and/or digest registration (A′) |
| Recipient binding | None | `dest = H(recipient)` where the OS allows |
| Time | Single TTL (60 min) | `iat` + `exp` + skew window |
| Keys | Server-side random codes | Per-business key pairs, `kid`, rotation, revocation |
| Replay | Content-bound; no single-use | Same, plus `dest` |
| iOS | Not built | Message Filter extension (deferred, later offline) |
| Carrier path | None | Carrier-side verification |

---

## Sources

Primary or standards documents:
- [RFC 8225: PASSporT](https://www.rfc-editor.org/rfc/rfc8225.html) (JWT structure, `iat`/`orig`/`dest`, replay protection)
- [RFC 8588: SHAKEN extension](https://www.rfc-editor.org/rfc/rfc8588.html)
- [RFC 6238: TOTP](https://datatracker.ietf.org/doc/html/rfc6238) (30 s step, ≤1 step delay, no OTP reuse)
- [RFC 6376: DKIM](https://www.rfc-editor.org/rfc/rfc6376.html) (body hash, canonicalization, DNS keys, `t=`/`x=`)
- [RFC 8032: EdDSA](https://www.rfc-editor.org/rfc/rfc8032) (64-byte Ed25519 signatures)
- [Google Messages Help: RCS chats with businesses](https://support.google.com/messages/answer/9326240)
- [FCC fact sheet on illegal texts (Nov 2023)](https://docs.fcc.gov/public/attachments/DOC-398661A1.pdf)
- [Apple: deferQueryRequestToNetwork](https://developer.apple.com/documentation/identitylookup/ilmessagefilterextensioncontext/deferqueryrequesttonetwork(completion:)) (page body not retrieved)

Secondary (vendors, press, aggregators; treat details as unconfirmed):
- [TransNexus: Understanding STIR/SHAKEN](https://transnexus.com/whitepapers/understanding-stir-shaken/)
- [XDA: Verified SMS](https://www.xda-developers.com/googles-messages-adds-verified-sms-feature-verify-texts-businesses/), [Plivo: Verified SMS](https://www.plivo.com/blog/google-verified-sms/), [CM.com: Verified SMS → RBM](https://www.cm.com/glossary/what-is-verified-sms/)
- [Infobip: RCS verification](https://www.infobip.com/blog/rcs-verification)
- [Infobip: 10DLC vetting](https://www.infobip.com/docs/usa-sender-registration/brand-vetting/10dlc-vetting), [Vonage: 10DLC brand vetting](https://developer.vonage.com/en/10-dlc/brand-vetting)
- [CRS: Combating Robocalls and Robotexts](https://www.congress.gov/crs-product/R48941)
