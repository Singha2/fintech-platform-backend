# M7 · Supplier Onboarding (BC8) — **full rigor** (Milestone 2, widen WS-1)

> **Module spec** (Spec_Driven_Build_Plan §H Milestone 2; register §C M7). Takes BC8 from the WS-1 skeleton
> (linear happy path to `active`, identity admin-recorded) to the complete onboarding spec for the
> TDS-free parts: **BC17-verified identity** (SA8.3) and the **KYC-rejected** branch — to DoD. Direct analog
> of M10 Investor ([[DL-BE-044]]); applies the same precedents. Umbrella decision: **DL-BE-055**;
> sub-slices claim `DL-BE-056+`.

| | |
|---|---|
| **Module** | M7 — Supplier Onboarding (BC8), full rigor |
| **Predecessor** | WS-1 ([[DL-BE-031]]) — `created → … → active`, admin-on-behalf, identity recorded (not verified) |
| **Tier** | Full (maximum rigor — Wave 1) |
| **Status** | Done |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-26 |

---

## DoR decisions (precedents applied from M10 Investor; the one flagged call)

1. **Admin-on-behalf retained.** Supplier has **no login in Phase 1** (DL-012 — suppliers act via agency/admin);
   Ops/Compliance/Credit drive every command, as WS-1. The agency/supplier portal is out of scope.
2. **Identity (PAN + GSTIN) verified through the BC17 ACL** (SA8.3/C24 — not self-attested), mirroring M10-A:
   `record-identity-verified` calls `verify_pan` + `verify_gstin`, the ACL result decides. **CIN via
   `fetch_mca21`** is verified **when present** (companies have a CIN; proprietorship/partnership/MSME do not).
3. **KYC-rejected branch IN** — `record-kyc-rejected` (Compliance) + `resubmit-kyc` (Ops), reusing the
   subject-type-generic `ComplianceService.rejectKyc`/`resubmitKyc` already built in M10-C ([[DL-BE-047]]).
4. **Suspend / Blacklist DEFERRED** (the flagged call — mirrors M10's investor-suspend deferral; SA8.1 with
   Credit+Compliance maker-checker). **Voluntary exit** (SA8.4, needs a BC1 read) and **agency-consent
   *enforcement*** (AC.1/AC.3 — only exercisable once *agency* actors exist, i.e. the deferred portal) also
   deferred. Recorded as gaps (§9).
5. **(Derived) No new migration.** `sup_account_status` already has every value; the KYC file + maker-checker
   columns exist. No schema change for M7-full.
6. **(Derived) No new ArchUnit boundary.** M7 reaches other contexts only via the BC17 `VerificationPort` +
   BC11 `ComplianceService` (existing seams).

---

## 1. Scope

**Owns (BC8 onboarding journey, TDS-free parts):** the `supplier` package (`SupplierService` +
`SupplierController`), the `sup_account` / `sup_agency_consent` / `sup_financial_profile` aggregates. KYC via
BC11 `ComplianceService` (maker-checker); identity via BC17 `VerificationPort`.

**State machine — DB-enum-true (`sup_account_status`):**

```
created → identity_verified → kyc_submitted → kyc_approved → credit_reviewed → maa_signed → active
   (alternate within the journey:)
   · record-kyc-rejected at the kyc-decision step → comp_kyc_file 'rejected'; account holds at
     kyc_submitted (cannot reach kyc_approved) until a fresh resubmit → approve cycle
   (post-active: suspended, blacklisted, voluntarily_exited — DEFERRED)
```

**Adds over WS-1:** (a) **BC17-verified** PAN + GSTIN (+ CIN when present) at `record-identity-verified`
(replacing admin-attested trust); (b) the **KYC-rejected → resubmit** branch.

**Does NOT own (deferred, documented):** supplier/agency self-service portal + login; Suspend/Blacklist
(SA8.1 maker-checker); voluntary exit (SA8.4); agency-consent *enforcement* (AC.1/AC.3 — grant is built,
enforcement awaits agency actors); KYC-refresh scheduler (SA8.5); UDYAM (B1 §5.4 dormant).

## 2. Upstream dependencies
- **WS-1** supplier skeleton + edge patterns. Done.
- **M5a Verification ACL (BC17)** — `verify_pan` / `verify_gstin` / `fetch_mca21` exposed. Done.
- **M10-C** — `ComplianceService.rejectKyc` / `resubmitKyc` (subject-type-generic). Done.
- **M4** roles `ops_executive`, `compliance_reviewer`, `credit_reviewer`.

## 3. Invariants & rules (BC8, B3 §2.8 — M7 scope)
- **SA8.1 — Linear forward transitions** (Spec §6.1); status-guarded UPDATEs. — built WS-1; reject branch new.
- **SA8.2 — Activation completeness** (kyc_approved, credit_review set, MAA signed) — guaranteed by the
  linear machine reaching `maa_signed`. _(DL-014/022/048)_
- **SA8.3 — Identity (PAN/GSTIN/CIN) BC17-verified, not self-attested.** _(C24, DL-014)_ — **the M7-A headline.**
- **KF.2 — KYC maker-checker** (approver ≠ submitter + MFA) via `ComplianceService`. _(C4)_ — built WS-1; reject path new.

## 4. API / type surface — new/changed vs WS-1

| Endpoint | Role | Effect |
|---|---|---|
| `POST /suppliers/{id}/record-identity-verified` | ops_executive | **now calls BC17** `verify_pan` + `verify_gstin` (+ `fetch_mca21` if CIN present) on the supplier's stored ids; on pass → `identity_verified`; fail → 422 `verification_failed` |
| `POST /suppliers/{id}/record-kyc-rejected` | compliance_reviewer | `ComplianceService.rejectKyc` (maker≠checker + MFA); account holds at `kyc_submitted` |
| `POST /suppliers/{id}/resubmit-kyc` | ops_executive | re-opens the rejected KYC file; account stays `kyc_submitted` |

(`create`, `grant-agency-consent`, `submit-kyc`, `record-kyc-approved`, `submit-financial-profile`,
`record-credit-review`, `record-maa-signed`, `activate` unchanged from WS-1.)

## 5. Five non-negotiables — applicability
Maker-checker (KYC approve/reject), SoD (ops vs compliance vs credit), MFA (KYC checker), idempotent
(`X-Command-Id`), audit (one envelope per command). PII (pan/gstin/cin/bank) on columns, never in an audit
payload (WS-1 rule retained).

## 6. Sub-slices (build order)

- **M7-A · BC17-verified identity** ([[DL-BE-056]]) — `record-identity-verified` verifies the supplier's
  stored PAN + GSTIN (+ CIN via `fetch_mca21` when present) through the ACL; fail-closed → 422. Mirrors M10-A.
  **Red:** valid ids advance + issue BC17 verifications; a forced-invalid PAN blocks the transition (422);
  the edge format-checks still fire first.
- **M7-B · KYC-rejected branch** ([[DL-BE-057]]) — `record-kyc-rejected` (compliance) → file rejected,
  account holds at `kyc_submitted`; `resubmit-kyc` (ops) re-opens; approve then advances. Mirrors M10-C.
  **Red:** reject → approve blocked; resubmit → approve succeeds; reject by non-compliance → 403; the
  submitter cannot reject their own file (maker≠checker, 409).

## 7. Test scenarios — `SupplierFullRigorTest extends AbstractEdgeHttpTest` (reuse the WS-1
`SupplierOnboardingTest` harness). Each invariant proves the app guard (+ the DB CHECK where present).

## 8. Definition of Done (full tier — gate F)
- [x] §7 tests green; whole suite green — **265** (was 260 at M13 close; +5).
- [x] `/code-review` (high recall) on the diff — **clean** (faithful mirror of the reviewed M10 patterns).
- [x] Maker-checker, SoD, MFA, idempotency, audit — enforced + tested incl. reject paths.
- [x] No new migration.
- [x] `DL-BE-055` umbrella + `DL-BE-056..057` per slice; spec flipped to **Status: Done**.

## 9. Remaining gaps after M7-full (documented, with owner)
- **Supplier/agency self-service portal + login** → portal slice.
- **Suspend / Blacklist** (SA8.1, Credit+Compliance maker-checker) → post-active lifecycle slice *(flagged —
  pull forward on request)*.
- **Voluntary exit** (SA8.4) → needs a BC1 `SupplierQueryPort`/read (zero non-terminal invoices).
- **Agency-consent enforcement** (AC.1 reject + AC.3 `AgencyAction.Recorded` sidecar) → with the agency portal.
- **CIN/MCA21 for all constitution types, UDYAM, KYC-refresh scheduler** (SA8.5).
