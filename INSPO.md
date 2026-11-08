# INSPO — Verified SMS: a "Visa for enterprise texts"

> Working name: **VeriText** (placeholder). Origin: a conversation between two teammates, plus an AI research thread on iOS/Android SMS capabilities.
> Status of facts: platform claims below come from that research thread and my own knowledge. Items tagged **[verify]** must be checked against current Apple/Google/carrier docs before we build on them.

---

## 1. One-paragraph pitch

Enterprises (T-Mobile, banks, delivery companies, clinics) text customers over SMS. Customers cannot tell a real message from a spoofed one, so they distrust all of them, and scammers exploit that. We build an **authentication layer for SMS**: the enterprise obtains a short-lived **token** from our network, attaches it to the message, and the receiving phone checks that token with us. Valid token → message is shown as verified. Message that *claims* to be from a registered brand but carries no valid token → flagged/junked as likely fraud. The long-term ambition is to be the **trust network between enterprises, carriers and consumers**, the way Visa is between merchants, banks and cardholders, with **T-Mobile (a carrier) as the first anchor partner**, the way Bank of America anchored early credit cards.

## 2. The problem, dissected

### 2.1 Why SMS is untrustworthy
- **No sender authentication in the protocol.** SMS has no equivalent of DKIM/SPF. Alphanumeric sender IDs ("TMOBILE") can be spoofed, and spoofed messages can land in the *same thread* as the genuine ones.
- **Consumers can't tell real from fake**, so the rational response is to distrust everything, including legitimate fraud alerts and OTPs. That hurts enterprises (lower conversion, more support calls) as much as consumers.
- **Smishing is large and growing** [verify current FTC / FBI IC3 numbers before quoting any figure externally].
- **Enterprises pay for the problem twice:** carrier filtering blocks some of their legit traffic (false positives), and brand damage from impersonation lands on them.

### 2.2 What carriers already do (we are not starting from zero)
| Mechanism | What it does | Gap relative to our idea |
|---|---|---|
| **A2P 10DLC + The Campaign Registry (TCR)** | Brands/campaigns register; carriers allow or block traffic from registered 10-digit numbers | Verifies the *sender campaign*, not each *message*. Consumer sees no signal. |
| **Short codes / toll-free verification** | Vetted dedicated senders | Same: sender-level, invisible to consumer. |
| **STIR/SHAKEN** | Cryptographic caller-ID attestation for **voice** | Voice only. Closest conceptual precedent for what we want in SMS. |
| **Branded Calling / Verified calls** | Display brand name on calls | Voice only. |
| **RCS Business Messaging (RBM)** | Verified sender, brand logo, rich features | Not SMS; coverage depends on RCS adoption; iPhone support is recent. **[verify state of iOS RCS business support]** |
| **Apple Messages for Business** | Branded, verified chat | Customer-initiated; iMessage-world, not SMS. |
| **Spam filters (carrier, Google, third-party)** | Heuristic/ML classification | Probabilistic; no positive proof of authenticity. |

**So the honest answer to "did anybody do this?":** partially. Sender-level registration exists (TCR). Per-message cryptographic attestation exists for voice (STIR/SHAKEN). RCS adds verified senders on a different channel. **Nobody (to our knowledge) ships per-message, token-based verification of SMS that a consumer-side app checks.** That is either our opening or a sign that carrier-side solutions are the only practical route. We must find out which. **[verify by searching for: "SMS message authentication", "verified SMS Google Messages", "message signing SMS carrier", Twilio/Sinch/Infobip "verified messaging"]**

> **Closest technical analogy: DKIM for email.** Sender signs → receiver verifies against a published key. SMS lacks it because the transport has no header space and no receiver-side hook. Our token-in-body is a workaround for that.

## 3. The Visa analogy — what transfers and what doesn't

Visa did **not** lend money or issue cards (originally BankAmericard did, then it spun out as a bank-owned network). Visa is the **network + rules + trust mark** connecting parties who didn't know each other.

| Credit card world | Our world |
|---|---|
| Cardholder | Phone owner / consumer |
| Merchant | Enterprise sending messages (T-Mobile's customers, banks, retailers) |
| Issuer bank | **Carrier** (T-Mobile) or our app on the consumer's phone: the party that trusts/serves the consumer |
| Acquirer bank | **Aggregator / CPaaS** (Twilio, Sinch…) or our own gateway: the party that onboards enterprises |
| Network (Visa) | **Us**: registry + token issuance + verification + rules + dispute handling |
| Authorization request | `verify(token)` call |
| Visa mark at checkout | "Verified" indicator on the message |

**What transfers well**
- **Two-sided network effects.** Value for consumers rises with verified enterprises; value for enterprises rises with consumers who check.
- **A neutral rulebook** (who may register, what gets revoked, how disputes work) is the real product, as much as the code.
- **Anchor-partner bootstrapping.** Bank of America's 1958 Fresno mailing seeded cards *and* merchants in one city at the same time to beat chicken-and-egg. **Our equivalent: one geography, one vertical, or one campus**, not "everyone".

**What does not transfer**
- Visa sits where money moves and charges per transaction. We sit where *messages* move, and enterprises already pay ~$0.005–$0.01 per SMS. Our fee must be a small add-on justified by deliverability gain + fraud reduction. **[verify pricing]**
- **Payment authorization is mandatory in the flow; SMS verification is optional.** A consumer who doesn't have our app/extension is simply unprotected. Visa never had that problem at the checkout.
- **We don't control the endpoints** (see §4). Visa controlled the terminals and issuer rails through contracts. We'd need the carrier to play that role, which is why **T-Mobile is the real strategic target**, not an app-store workaround.

## 4. Platform reality (the hard constraints)

### 4.1 iMessage vs SMS
- iMessage is a **separate, Apple-closed system** (internet + Apple servers, E2E encrypted). SMS/MMS runs through the carrier. The Messages app is one UI over both (blue = iMessage, green = SMS/MMS/RCS).
- Enterprises mostly use **SMS** (via short code, toll-free, or 10DLC). Blue-bubble outbound from enterprises is not officially possible. Gray-market workarounds exist but Apple is hostile to them.
- **Consequence: our target is the SMS side only.** iMessage is out of scope and cannot be reached.
- "Monopoly?" is legally contested; irrelevant to our design. Treat iMessage as a closed system we route around.

### 4.2 Android

| Option | Gives us | Cost |
|---|---|---|
| **Default SMS app** | Receive, send, store everything; can render our own "Verified" UI | Huge user ask: replace their messaging app. Google Play policy allows this only for real SMS apps. |
| **`RECEIVE_SMS` / `READ_SMS` without being default** | Read incoming SMS | Google Play restricts these to a short list of approved use cases; we'd have to apply and justify. Sideload (own site / APK) works for prototyping. **[verify current Play SMS/Call Log policy]** |
| **Notification listener access** | See SMS notifications from any app | Easier approval, less reliable (notifications can be grouped/truncated/dismissed). |
| **SMS Retriever / User Consent APIs** | OTP-only flows, no broad permission | Limited to OTP formats; useful as a narrow wedge later. |

**Phase 1 stance:** sideloaded APK with `RECEIVE_SMS` for the demo; plan the Play approval path (or notification-listener fallback) separately.

### 4.3 iPhone

- **No access to the SMS inbox. Cannot read, receive, intercept.**
- **The only hook: Message Filter extension** (`ILMessageFilterExtension`, IdentityLookup framework). User enables it in *Settings → Messages → Unknown & Spam*.
  - Only runs for **SMS/MMS from senders NOT in the user's contacts**. Fine for brands.
  - Extension sees **sender + body**; returns an action: allow / junk / promotion / transaction (+ sub-actions on newer iOS). **[verify current action set]**
  - It can **defer to a server** (`ILMessageFilterQueryRequest` → network URL); the system performs the request, and the extension itself cannot make arbitrary network calls. **[verify privacy handling and what the server sees]**
  - **Cannot** show a verified badge, modify the message, or affect iMessage.
- **Consequence for product design on iPhone: we can only offer a *negative* signal** (push unverified/fraudulent messages to Junk) and categorization (promotion / transaction). A positive "✓ Verified" UI must live in **our own app** for messages we can see (e.g., via share sheet, or because the user opens a link from the message).
- **Another iOS wrinkle:** the extension cannot learn the user's own phone number, and the system-built server request carries only what Apple defines. So **recipient-binding of tokens cannot be enforced on iOS** (see §5.3).
- **Dev constraint for us:** building/signing an iOS extension needs **Xcode on macOS** and an Apple Developer account. The repo's author is on Windows → we need a Mac (teammate's, a cloud Mac, or CI) for the iOS side.

### 4.4 Carrier-level (the "Visa" endgame)
- A carrier can do what apps can't: verify at the **network edge** before delivery, and mark/route/block accordingly, for every customer, with no app install. This is how STIR/SHAKEN works.
- This is the real pitch to T-Mobile: *"you already verify callers; extend it to messages, with an enterprise-facing registry and token service we operate."*
- Until a carrier signs on, we are an **overlay network** reliant on apps/extensions, with limited reach and distribution friction.

### 4.5 Virtual-number route (fallback)
Customers give enterprises a number we control; we receive on their behalf and relay into a verified inbox. Works on any phone, but customers must hand out a different number. Poor consumer adoption; keep as a niche (e.g., high-privacy users), not the main path.

## 5. The core mechanism

### 5.1 Flow (happy path)
```
Enterprise            VeriText Network                 Consumer phone
    |  1. onboard/KYC → registry entry (org, numbers)       |
    |------------------------------>|                       |
    |  2. POST /tokens {org, to, body_hash}                 |
    |------------------------------>| issue token (TTL)     |
    |<------------------------------| token T               |
    |  3. send SMS "Your bill is ready… ✓T" via any SMS provider
    |------------------------------------------------------>|
    |                               |  4. phone extracts T, computes body_hash,
    |                               |     POST /verify {T, from, body_hash}
    |                               |<----------------------|
    |                               |  5. {valid, org: "T-Mobile", logo, category}
    |                               |---------------------->|
    |                               |  6. Android: show ✓ Verified  /  iOS filter: allow
```
Unhappy path: message from a number the registry says belongs to a brand **but** no/invalid token → **fraud suspected → Junk (iOS) / red warning (Android)**. Message from a sender *not in the registry* → leave alone in v1 (avoid false positives; we can't claim it's a scam).

> Key insight: the "no token = scam" rule must be scoped to **senders that claim a registered identity** (the registered sender number/ID). Applying it to all texts would junk every unenrolled-but-legit sender and make the product unusable.

### 5.2 Token design options

| Option | Pros | Cons |
|---|---|---|
| **A. Short opaque ID + server lookup** (e.g., 8 base32 chars) | Tiny in body; server holds the binding (org, recipient, body_hash, expiry); easy revoke | Needs network at verify time; server sees body_hash and sender |
| **B. Full digital signature** (Ed25519 = 64 bytes ≈ 86 chars) | Offline verification on device; no per-message server call; strong privacy | Eats ~half an SMS segment; ugly; harder UX |
| **C. Truncated HMAC/signature** | Short | Truncated signatures are weak without a secret shared with the verifier; not safe for public-key verification |
| **D. Link-style token** (`vt.ly/AB12CD34`) | Familiar; tappable; works with no app (landing page confirms) | Looks like phishing links; URL-shortener fatigue |

**Phase 1 pick: Option A**, with the token bound to `(org_id, sender, body_hash)`; and Option B documented as the privacy-preserving future.

**Why bind to the body hash:** it makes the token *content-specific*. A replayed token on a different body fails, and a replay of the identical body is harmless (it's the original legit content). That removes the need for single-use semantics (which don't work anyway, since iOS may query the filter more than once for a message).

### 5.3 Known weaknesses (write these into the threat model, not under the rug)
1. **Body normalization.** SMS can alter text in transit (GSM-7 vs UCS-2, smart quotes, trimmed whitespace, segment concatenation). A hash mismatch = false "invalid". Needs a canonicalization function and a test corpus across carriers/phones. **Highest technical risk for Phase 1.**
2. **Forwarding a real message to a different victim.** Token valid, content legit, wrong recipient. On Android we can bind recipient; **on iOS we cannot** (extension doesn't know the user's number). Low harm but real.
3. **Token in the body is visible** to every observer; fine given content binding + TTL, but means tokens are "public proof", not secrets.
4. **Privacy:** on iOS deferred queries, our server receives the message (sender + body). Mitigations: send only what's needed, minimize/ephemeral retention, publish a data policy, move to Option B (offline) later. **[verify what Apple's relay hides from the server]**
5. **Registry integrity = whole system.** If the registry accepts a fraudster, the badge becomes a weapon. Needs KYC, domain/phone proof of control, revocation, and an abuse process.
6. **Opt-in only.** Users without the app/extension get no protection. Unprotected users are *more* exposed if scammers pivot to them.
7. **Sender spoof of a registered number** is exactly what we detect, but the **attacker can still send a message with no token** and rely on user not having us. We improve only the protected population.
8. **Fail-open vs fail-closed.** If our server is down, filter must not junk legit mail. Default to *allow/none* on errors; log it.
9. **Latency / battery:** a verify call per inbound message; the iOS filter has tight time budgets. **[verify limits]**

## 6. Cold start and go-to-market

- **Don't try to be "Facebook".** A network can feel inevitable in hindsight; Facebook started at one campus, with one use. Same lesson as Visa/Fresno: **dense, narrow, then expand.**
- **Candidate wedges** (pick one for Phase 1 demo narrative):
  1. **OTP / security alerts** from banks and carriers (highest trust value, tightest format, Android SMS Retriever synergy).
  2. **Carrier-originated messages** (T-Mobile → its own customers): one partner controls both ends; best story for "carrier as issuer".
  3. **Campus/local pilot** (e.g., UTD) with a few real senders and a few hundred users.
- **Order of persuasion:** (a) prove the mechanism works end-to-end with real phones → (b) get 1–2 enterprise pilots or a carrier conversation → (c) only then invest in the Play Store approval / App Store release.
- **Revenue ideas (later):** per-token fee to enterprises; premium "verified + branded" tiers; fraud analytics; carrier revenue share.

## 7. Legal / policy checklist (not legal advice, to be reviewed by someone qualified)
- **TCPA / CTIA guidelines / opt-in & opt-out** apply to the senders, but we'd inherit reputational risk if we verify non-compliant senders.
- **Google Play SMS permission policy** and **Apple App Review (Message Filter)** rules, which gate distribution.
- **Privacy:** we handle message content and metadata (CCPA/state laws; GDPR if EU). Prefer hashes, short retention, no content storage.
- **Use of "Visa/T-Mobile" names and logos** in demos: fine as inspiration in our docs; do **not** imply endorsement or ship their branding without permission.
- **Liability** when a verified message is later found fraudulent (dispute process, revocation SLA).

## 8. Open questions (resolve before/while building)
1. Is there an **existing** per-message SMS authentication product or standard? (Search before investing.) 
2. Does T-Mobile (or any carrier/aggregator) have a **partner/innovation intake**? What would they need to see?
3. What does the iOS filter **server request** contain exactly, and what are the time/size limits?
4. Can we get a **stable canonical body** through real carriers (Twilio → T-Mobile → iPhone/Android)?
5. Do we pivot the wedge to **OTP/alerts** or **carrier-originated**?
6. Android distribution: Play approval likelihood vs sideload/notification-listener fallback.
7. Team: who has a Mac for iOS? Who owns backend vs Android vs iOS?

## 9. Decisions we're locking in (for now)
- Target the **SMS** path only; iMessage is out of scope.
- **Token = short server-issued ID bound to the exact message content**; offline signatures are a later upgrade.
- **Negative-only enforcement on iOS**; **full verified UI on Android** (sideload first).
- "No token ⇒ suspicious" applies **only to registered sender identities**.
- Build the **mechanism and a demo first**; go-to-market claims come after evidence.
