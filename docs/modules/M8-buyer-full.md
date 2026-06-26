# M8 · Buyer Management (BC9) — **full rigor** (Milestone 2, widen WS-2)

> **Module spec** (Spec_Driven_Build_Plan §H Milestone 2; register §C M8). Takes BC9 from the WS-2 skeleton
> (linear happy path to `active`, identity admin-recorded) to its full-rigor TDS-free addition: **BC17-verified
> identity** (BA.4 — CIN + GSTIN). The buyer has **no KYC flow** (BA.3 = credit-assessment + ack user +
> payment instructions), so unlike M7/M10 there is no KYC-rejected branch — M8-full is a single slice.
> Decision: **DL-BE-058**.

| | |
|---|---|
| **Module** | M8 — Buyer Management (BC9), full rigor |
| **Predecessor** | WS-2 ([[DL-BE-032]]) — `nominated → … → active`, admin-on-behalf, identity recorded (not verified), ack user + payment rule |
| **Tier** | Full-rigor slice (narrow — single BC17 identity step; see §1) |
| **Status** | Done |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-26 |

---

## DoR decisions (precedents applied from M7/M10)
1. **Admin-on-behalf retained** (no buyer login Phase 1, DL-012).
2. **Identity (GSTIN + CIN) verified through the BC17 ACL** (BA.4/C24 — `verify_gstin` + `fetch_mca21`), not
   self-attested. The buyer has **no PAN** (a corporate payer identified by GSTIN + CIN) and **no KYC file**,
   so M7's `verify_pan` + KYC-rejected branch have no analog here.
3. **Suspend (BA.1, Credit+Treasury maker-checker) DEFERRED** (mirrors M7/M10's suspend deferral; *pull
   forward on request*); **RecordLimitReduced (BA.5)** is a BC3 (M6 Credit) subscriber — deferred with M6;
   the **real ack-user OTP login** is deferred with the buyer portal.
4. **(Derived) No new migration; no new ArchUnit boundary** (BC17 `VerificationPort` is the only new seam).

## 1. Scope

**Owns:** the `buyer` package (`BuyerService` + `BuyerController`). The single change: `record-identity-verified`
verifies the buyer's stored **GSTIN** (`verify_gstin`) + **CIN** (`fetch_mca21`) through BC17 before the
`nominated → identity_verified` transition (replacing WS-2's admin-attested trust).

**State machine (DB-enum-true, `buyer_account_status`):** `nominated → identity_verified → credit_assessed →
engagement_started → active` (unchanged; only the identity step gains verification).

**Does NOT own (deferred, documented):** Suspend (BA.1 maker-checker, *flagged*); `RecordLimitReduced` (BA.5
→ BC3/M6); the buyer/ack-user self-service portal + login; `BuyerLimit.Reduced` snapshot reflection.

## 2. Upstream dependencies
- **WS-2** buyer skeleton + edge. Done.
- **M5a Verification ACL (BC17)** — `verify_gstin` / `fetch_mca21` exposed (M7-A added `fetchMca21`). Done.
- **M4** role `ops_executive`.

## 3. Invariants & rules (BC9, B3 §2.9 — M8 scope)
- **BA.1 — Linear forward transitions** (Spec §6.2); status-guarded. — built WS-2.
- **BA.3 — Activation completeness** (credit-assessment + ≥1 ack user + payment instructions) — built WS-2.
- **BA.4 — Identity (CIN, GSTIN) BC17-verified, not self-attested.** _(C24, DL-018)_ — **the M8 headline.**

## 4. API / type surface — changed vs WS-2

| Endpoint | Role | Effect |
|---|---|---|
| `POST /buyers/{id}/record-identity-verified` | ops_executive | **now calls BC17** `verify_gstin` + `fetch_mca21` on the buyer's stored GSTIN + CIN; on pass → `identity_verified`; fail → 422 `verification_failed` |

(All other WS-2 commands unchanged.)

## 5. Five non-negotiables — applicability
SoD (per-command role gate), idempotent (`X-Command-Id`), audit (one envelope per command), MFA via the
gateway. PII (gstin/cin) on columns, never in an audit payload (WS-2 rule retained). No floats.

## 6. Sub-slice (single)
- **M8-A · BC17-verified identity** ([[DL-BE-058]]) — `record-identity-verified` verifies GSTIN + CIN via the
  ACL; fail-closed → 422. Mirrors M7-A (GSTIN+CIN, no PAN). **Red:** valid ids advance + issue BC17
  verifications; a forced-invalid GSTIN blocks the transition (422); the edge GSTIN format-check still fires
  first. Needs a new `FAIL_GSTIN` stub sentinel.

## 7. Test scenarios — `BuyerIdentityVerificationTest extends AbstractEdgeHttpTest` (reuse the WS-2
`BuyerOnboardingTest` harness). Each invariant proves the app guard.

## 8. Definition of Done
- [x] §7 tests green; whole suite green — **267** (was 265 at M7 close; +2).
- [x] `/code-review` on the diff — **clean** (CIN boolean compare sound on fresh + cache-hit paths).
- [x] SoD, idempotency, audit — enforced + tested incl. the reject path.
- [x] No new migration.
- [x] `DL-BE-058` added; spec flipped to **Status: Done**.

## 9. Remaining gaps after M8-full (documented, with owner)
- **Suspend** (BA.1, Credit+Treasury maker-checker) → post-active lifecycle slice *(flagged — pull forward)*.
- **RecordLimitReduced / BuyerLimit.Reduced** (BA.5) → BC3 (M6 Credit).
- **Buyer / ack-user self-service portal + login** → portal slice.
