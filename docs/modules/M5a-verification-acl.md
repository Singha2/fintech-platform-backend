# M5a · Verification ACL (BC17) — stubbed aggregator behind a real port

> **Lean module spec** — *foundation* (an anti-corruption port with a deterministic stub adapter; low
> rigor — no real vendor, no money). See `docs/spec/Spec_Driven_Build_Plan.md` §C/§D.
> Spec before code; invariant test before rule.

| | |
|---|---|
| **Module** | M5 — Integration ACLs, stubbed (BC15/17/18/19) |
| **Slice** | M5a — Verification ACL (BC17): real `VerificationPort`, fake auto-pass in-process adapter |
| **Tier** | Foundation (low rigor — stub; the port contract is what matters) |
| **Status** | Done (impl test-first + tests green; `/code-review` findings fixed; DL-BE-024) |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-21 |

> **Scope call (flagged):** M5 bundles four integration ACLs. They're independent integrations, so I'm
> slicing M5 **by bounded context** — **M5a Verification (BC17)** (this) → **M5b Banking/Escrow (BC18)**
> → **M5c Signing (BC19)** → **M5d Notifications-full (BC15)** (M3a already shipped a thin
> `NotificationPort`/`StubNotifier`). M5a is the simplest — no money, auto-pass — so it **establishes
> the ACL pattern** (domain-meaningful port + deterministic fake adapter + `gate_*` persistence +
> audit) that the others reuse. Say the word to regroup.
>
> **The point of an ACL (A1/B1):** business contexts call **domain operations** (`verifyPan`,
> `verifyGstin`), never a vendor API; the vendor model never leaks inward. M5a ships the **port +
> a fake in-process adapter**; the real aggregator (Perfios/Karza/…) is swapped in **only the adapter**
> at the Production gate (DL-026, DL-047).

## 1. Scope
**Owns:**
- **`VerificationPort`** — the domain-meaningful operations BC1/BC7/BC8/BC9/BC11 will call (DL-026):
  `verifyPan`, `verifyAadhaarEkyc`, `verifyGstin`, `fetchMca21`, `fetchGstReturns`, `fetchBureau`,
  `fetchAaBankStatement`, `verifyPennyDrop`, `verifyIrn`, `verifyEwayBill`, `screenAmlPep` (the 11
  aggregator APIs, `verification_api_enum`). Each takes a `subjectId` + inputs, returns a
  `verificationId` (= the vendor `client_request_id`).
- **`StubVerificationAdapter`** — the fake in-process adapter: **auto-passes**, returns deterministic
  `extracted_fields` per `api_name`, sets `ttl_until` per the A2 §1.4 matrix (PAN 12m, Aadhaar-eKYC
  12m, MCA21 18m, bureau 30d, GST-returns 90d, AA-stmt 90d, penny-drop 12m; one-shot `irn`/`eway_bill`/
  `aml_pep` → null TTL), and **completes deterministically in-process** (no real callback).
- **`gate_verification` persistence + the cache rule (V.1/V.4)** — `verification_id` PK =
  `client_request_id`; a **non-stale `completed`** row for `(subject_id, api_name)` is **reused**
  instead of re-calling the vendor.
- **Result lifecycle** — `requested → completed | failed`; `markStale` for expired completed rows
  (the scheduled sweep itself is ops/M5).
- **Audit** — `verification.Verification.Requested` / `.Completed` / `.Failed` envelopes via `AuditLog`
  (M2). Stub stamps `signature_verified_at` at completion (V.2) — the real HMAC ingress is the real
  adapter's concern (§9.4).

**Does NOT own (deferred / other slice):**
- **The real aggregator adapter** (sandbox → production credentials) → **Production gate**; M5a is the
  fake adapter behind the fixed port.
- **HTTP webhook ingress** — the `/webhooks/verification/{vendor}/{event}` routes, HMAC verification,
  the **5-min replay window** (A2 §1.2), and `vendor_event_id` dedup → **the real adapter / Walking
  Skeleton** (the stub completes in-process, so there is no inbound HTTP callback yet).
- **Verbatim vendor-payload archival** into `sys_document_object` (BC16 Documents) → **BC16/later**;
  M5a stores only `vendor_payload_hash` (V.3).
- **Manual-fallback workflow** (G8 — Ops + Compliance co-sign on real vendor failure) → later (the stub
  never fails, so there's nothing to escalate yet).
- **AML/PEP adjudication** — BC17 only makes the `screen_aml_pep` vendor call; the SAR/adjudication
  engine is **BC11 / M15**.
- **Video-KYC / V-CIP** (DL-050, G16) → Phase 2 (reserved, unrouted).
- **The other ACLs** — Banking/Escrow (BC18), Signing (BC19), Notifications-full (BC15) → **M5b/c/d**.

## 2. Upstream dependencies
- **M2 Audit Log**, **M1a/b** (`Ids`, errors) — Done. **No state-changing-command path** (verification
  is system-triggered, not a maker-checker command), so M4 is not a dependency.
- **M0 schema** — Done: `gate_verification`, `verification_api_enum`, `verification_status_enum` (V4).
  **No new migration.**
- **Consumers** (BC1/7/8/9/11) — **not built yet**; M5a ships the port they will call. No real subject
  rows required — M5a tests use synthetic subject ids.

## 3. Invariants & rules
- **INV-1 — `verification_id` = `client_request_id`, never reused for a different request (V.1).** It is
  the vendor-side idempotency key. _(ref: DB `gate_verification` PK; A2 §1.3)_
- **INV-2 — Cache reuse (V.1).** A non-stale `completed` verification for `(subject_id, api_name)` is
  returned instead of issuing a new vendor call. _(ref: `idx_verification_subject_api`; A2 §1)_
- **INV-3 — TTL per data type (V.4).** `completed` rows carry `ttl_until` per the A2 §1.4 matrix;
  one-shot APIs (`irn`/`eway_bill`/`aml_pep`) have `null` TTL; a passed `ttl_until` ⇒ `stale` (not reused).
- **INV-4 — Verbatim payload by hash (V.3).** `vendor_payload_hash` holds the SHA-256 of the response;
  the full payload is archived in BC16 (deferred). _(ref: schema; C24)_
- **INV-5 — The vendor model never leaks (ACL rule).** Callers use domain ops; no vendor type crosses
  the port. _(ref: A1/B1 context map)_
- **INV-6 — Every request/completion is audited.** _(ref: non-negotiable #5; C1)_

## 4. API / type surface
- **Port:** `VerificationPort` with the 11 ops; each returns a `VerificationResult`
  (`verificationId`, `status`, `extractedFields`, `ttlUntil`). A typed `VerificationApi` enum mirrors
  `verification_api_enum`.
- **Adapter:** `StubVerificationAdapter implements VerificationPort` (deterministic auto-pass) — the
  only Phase-1 bean; a real adapter is a drop-in later.
- **Query:** `findActive(subjectId, api) → Optional<VerificationResult>` (cache lookup, non-stale).
- **Maintenance:** `markStale(verificationId)` (the sweep scheduler is ops).
- **Types:** `VerificationApi`, `VerificationResult`, `VerificationStatus`.

## 5. Five non-negotiables — applicability
| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | no | verification is system-triggered, not a maker-checker command (manual-fallback co-sign is later, G8) |
| 2 | MFA-fresh | no | the triggering onboarding/admin command carries MFA; the ACL call does not |
| 3 | SoD-checked | no | — |
| 4 | Idempotent | **yes (ACL key)** | on `client_request_id` (= `verification_id`) + the `(subject, api)` cache — **not** the M4a `command_id` store |
| 5 | Audit-logged envelope | **yes** | Requested / Completed / Failed via `AuditLog` |

## 6. Events (audit envelopes; no bus yet)
`verification.Verification.Requested`, `verification.Verification.Completed`,
`verification.Verification.Failed`. Subscribers (BC1/7/8/9/11) wire up when those modules land; the
in-process event bus is a Walking-Skeleton concern. _AggregatorOutage.Declared / ManualFallback.Invoked
/ WebhookSignature.Invalid → with the real adapter._

## 7. Test scenarios (write these first) — integration, Testcontainers
- [ ] `verifyPan(subject, …)` → a `gate_verification` row, `status=completed`, deterministic
      `extracted_fields`, `ttl_until` ≈ now+12m, a `Verification.Completed` envelope (INV-1, INV-3, INV-6).
- [ ] Cache reuse: a second `verifyPan` for the same `(subject, api)` within TTL returns the **same**
      `verification_id` and issues no new row (INV-2).
- [ ] Stale: age `ttl_until` into the past → `markStale` flips to `stale`; the next `verifyPan` issues a
      **fresh** verification (INV-3).
- [ ] One-shot APIs (`verifyIrn`) → `ttl_until` is null and are not cache-reused across distinct subjects (INV-3).
- [ ] `vendor_payload_hash` is a hash, never the raw payload; no vendor-specific type is exposed on the
      port (INV-4, INV-5).

## 8. Definition of Done (foundation, low rigor)
- [x] §7 tests green — `VerificationAclTest` (6, written test-first: red → green); 116 total green.
- [x] `/code-review` on the diff; findings fixed (stub payload now folds in `inputs` so one-shot
      hashes vary with the request; the failed-path / concurrency notes recorded as real-adapter watch-fors).
- [x] `DL-BE-024` entry (the ACL-port + deterministic-stub pattern, the fixed-Service/swappable-VendorClient
      split, cache-by-(subject,api), TTL matrix, stub-in-process vs deferred HMAC ingress, by-BC slicing).
- [x] Status flipped to **Done**.

> **As-built refinement:** the cache/persistence/audit logic is the **fixed** `VerificationService`
> (implements `VerificationPort`); the swappable seam is a separate `VerificationVendorClient`
> (`StubVerificationVendorClient` now, real adapter later) — cleaner than the draft's "adapter implements
> port", and the correct ACL shape (swap only the vendor client). The migrated column is
> `hmac_verified_at` (V4 renamed `signature_verified_at`). Typed convenience covers 4 common ops;
> `verify(VerificationRequest)` supports all 11 (the rest add as consumers land).

## 9. Self-review resolutions (DoR-green)
1. **Slicing — RESOLVED: M5 by bounded context** (M5a Verification → M5b Banking → M5c Signing → M5d
   Notifications-full). M5a first because it's the simplest (no money, auto-pass) and sets the pattern.
2. **Migration — RESOLVED: none.** `gate_verification` + enums exist (V4).
3. **Stub shape — RESOLVED: deterministic auto-pass.** Real interface, fake adapter; only the adapter is
   swapped at Production. Deterministic `extracted_fields` per api; TTLs per A2 §1.4.
4. **Webhook ingress — RESOLVED: deferred to the real adapter.** The stub completes **in-process**, so
   there is no inbound HTTP callback; HMAC + 5-min replay (A2 §1.2) + `vendor_event_id` dedup + the
   `/webhooks/verification/...` routes land when the real adapter does. The stub stamps
   `signature_verified_at` at completion to satisfy V.2.
5. **Verbatim payload — RESOLVED: hash only now.** Full archival into `sys_document_object` is **BC16
   Documents** (not built); M5a stores `vendor_payload_hash`.
6. **Not a gateway command — RESOLVED.** Verification is system-triggered; idempotency is the ACL key
   (`client_request_id`) + the cache, not the M4a `command_id` store. Audited via `AuditLog` directly.
7. **AML/PEP — RESOLVED:** BC17 makes the `screen_aml_pep` call only; adjudication is **BC11/M15**.

## 10. Watch-for (carry forward)
- **Real-adapter swap** brings the deferred webhook stack: `/webhooks/verification/{vendor}/{event}`
  routes, HMAC over `(timestamp||body)` (C10), the 5-min replay window (A2 §1.2), `vendor_event_id`
  dedup → `Webhook.DuplicateDropped` (C22/G1), and the verbatim-payload archival into BC16.
- **Manual-fallback (G8)** — when real vendor failures exist, the Ops+Compliance co-sign escalation; the
  stub never fails so it's latent.
- **TTL-sweep scheduler** (mark completed→stale on expiry) → ops/M5; M5a ships the `markStale` logic.
- **Outage banner** (`AggregatorOutage.Declared` → BC15) — with the real adapter.
- **Shared ACL substrate** — if M5b/M5c repeat much of this pattern, extract a small shared base
  (vendor-instruction aggregate + idempotency), but don't pre-abstract before the second consumer.
