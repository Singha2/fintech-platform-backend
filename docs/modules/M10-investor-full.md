# M10 · Investor Onboarding (BC7) — **full rigor** (Milestone 2, widen WS-3)

> **Module spec** (Spec_Driven_Build_Plan §H Milestone 2; register §C M10). Takes BC7 from the WS-3
> skeleton (linear happy path to `active`, `mismatch=false`) to the **complete onboarding spec**: the
> suitability mismatch + override-ack path, the KYC-rejected path, BC17-verified identity/bank, and the
> full IA.3 activation gate — to DoD. Spec before code; **invariant test before rule**.
> Umbrella decision: **DL-BE-044**; sub-slices claim `DL-BE-045+`.

| | |
|---|---|
| **Module** | M10 — Investor Onboarding (BC7), full rigor |
| **Predecessor** | WS-3 ([[DL-BE-033]]) — `signed_up → … → active` over HTTP, invite-gated, admin-on-behalf, `mismatch=false` |
| **Tier** | Full (maximum rigor — Wave 1) |
| **Status** | Done |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-26 |

---

## DoR decisions (settled at the gate — the four scope forks + derived choices)

1. **Admin-on-behalf retained; investor self-service portal/login DEFERRED.** For the pilot, Ops/Compliance
   onboard the investor: the investor shares details offline, the admin uploads documents and drives every
   command (as WS-3). The real investor login (password/Google per the auth schema) + self-service
   submission opens post-pilot. (User decision, 2026-06-26.)
2. **Identity (PAN) + bank verified through the BC17 ACL, admin-triggered.** Even admin-driven onboarding
   must not self-attest identity (C24/IA.8): `record-identity-verified` calls `verify_pan` and
   `complete-financial-profile` calls `verify_penny_drop` (BC17 `VerificationPort`), the ACL result decides
   pass/fail — mirroring the M9-C IRN pattern. **Full-Aadhaar eKYC stays out**: only `aadhaar_last4` is
   stored (IA.7/C15) and it remains admin-recorded — eKYC of the full Aadhaar needs a secure transient-input
   path, deferred with self-service. _(Interpretation of the user's "admin uploads documents" answer, flagged
   for veto: keeps the C24 non-negotiable while staying admin-driven.)_
3. **Invite Revoke DEFERRED** (no migration). The `inv_invite_status` enum is `{pending, consumed, expired}`
   — Revoke (I.5) would need a V7 `'revoked'` value. Invites die only by 14-day auto-expiry for now;
   Revoke is a documented gap.
4. **Post-active lifecycle (Suspend/Exit) DEFERRED** (documented). `Suspend` (IA.5) and `Exit` (IA.9 — needs
   a BC2 subscription read to prove zero non-terminal subscriptions) are out of M10-full; the module stops at
   `active` + the onboarding reject/alternate paths. `Blacklisted` has no enum value (migration) — also out.
5. **(Derived) No new migration.** The schema (V1–V6) already carries every `inv_account_status` value,
   `inv_suitability.override_text_hash`, and the `suspended_at`/`exited_at` columns. Suspend/Exit and Revoke
   (the only things needing schema change) are deferred, so M10-full adds no migration.
6. **(Derived) No new ArchUnit boundary.** M10 reaches other contexts only via existing seams — the BC17
   `VerificationPort` and the BC11 `ComplianceService` (in-process command coordination, allowed). No new
   cross-BC table read, so the ARCH.1 harness needs no extension here.

---

## 1. Scope

**Owns (BC7 onboarding journey + its alternate branches):** the `investor` package (`InvestorService` +
`InvestorController`), the `inv_invite` / `inv_account` / `inv_suitability` aggregates. KYC approval is
coordinated through BC11 (`ComplianceService`, maker-checker); identity/bank verification through BC17.

**State machine — DB-enum-true (`inv_account_status`):**

```
signed_up → identity_verified → kyc_submitted → suitability_assessed
          → financial_profile_completed → kyc_approved → mia_signed → active
   (alternate within the journey:)
   · suitability_assessed may carry mismatch=true → requires AcknowledgeSuitabilityOverride
     (override_text_hash) before Activate (IA.3/IA.4)
   · record-kyc-rejected at the kyc-decision step → comp_kyc_file 'rejected'; account holds at
     financial_profile_completed (cannot reach kyc_approved) until a fresh submit→approve cycle
   (post-active: suspended, exited — DEFERRED)
```

**Adds over WS-3:** (a) the **suitability mismatch + override-ack** path (IA.4/C21/G26 — WS-3 hard-coded
`mismatch=false`); (b) the **KYC-rejected** branch (compliance rejects → no advance → resubmit); (c)
**BC17-verified** PAN + penny-drop (replacing admin-recorded trust); (d) the full **IA.3 activation gate**
(mismatch ⟹ override acknowledged).

**Does NOT own (deferred, documented):** investor self-service login + portal; invite Revoke (needs V7);
Suspend/Exit + `Blacklisted` (post-active lifecycle, BC2 read for IA.9); full-Aadhaar eKYC; the KYC-refresh
scheduler (IA.6); invite Expire as an explicit scheduled command (auto-expiry is enforced at consume);
**typed KYC documents** (PAN card, address proof, …) — captured as `kyc_document` in **M20 Onboarding
Documents** (consumes M18), **capture-only** (does not gate approval until M15). Today `submit-kyc` carries
no document payload.

## 2. Upstream dependencies
- **WS-3** investor skeleton + edge patterns. Done.
- **M5a Verification ACL (BC17)** — `VerificationApi.VERIFY_PAN` / `VERIFY_PENNY_DROP` exposed (`VerificationPort`). Done.
- **M4/compliance** — `ComplianceService` (maker-checker KYC submit/approve/reject), roles `ops_executive`,
  `compliance_reviewer`. Done.

## 3. Invariants & rules (BC7, B3 §2.7 — the full set in M10 scope)

- **IA.1 — Linear forward transitions** (Spec §6.3); no skips, no backward moves. _(Spec §6.3, DL-050)_
- **IA.2 — `sub_type ∈ {resident_individual, huf}`** in Phase 1 (rejected at the handler). _(DL-006)_ — built WS-3.
- **IA.3 — Activation completeness.** `Activate` requires `kyc_approved`, a suitability outcome set (with
  override acknowledged if `mismatch`), `bank_account_last4` set, MIA signed. _(DL-050, C21)_
- **IA.4 — Mismatch ⟹ override-ack.** `suitability_outcome = mismatched_with_override` requires an
  `InvestorSuitability.OverrideAcknowledged` envelope referencing `override_text_hash` (set on
  `inv_suitability`). _(C21, G26)_
- **IA.7 — Only `aadhaar_last4` stored** on the aggregate; full Aadhaar never persisted. _(C15, DL-050)_
- **IA.8 — `pan`/`aadhaar_last4`/`bank_account_last4` set once** at their verification events; PAN + bank
  are BC17-verified (not self-attested, C24). _(C24, DL-050)_
- **SA.1 — Suitability assessment immutable;** a re-assessment creates a new `assessment_id`. _(C21)_
- **I.1/I.2/I.3 — Invite single-use, 14-day expiry, email/phone hash-bind** at consume. _(DL-008, C20, G9)_ — built WS-3.
- **KF.2 — KYC maker-checker** (approver ≠ submitter + MFA) via `ComplianceService`. _(C4)_ — built WS-3; the reject path is new.

## 4. API / type surface (intent-shaped, B4 §2.1) — new/changed vs WS-3

| Endpoint | Role | Effect |
|---|---|---|
| `POST /investors/{id}/record-identity-verified` | ops_executive | **now calls BC17 `verify_pan`**; on COMPLETED+VALID stamps `pan` + `aadhaar_last4`, → `identity_verified`; fail → reject |
| `POST /investors/{id}/assess-suitability` | compliance_reviewer | body `mismatch:bool` (+ questionnaire hash); records `inv_suitability`; → `suitability_assessed` |
| `POST /investors/{id}/acknowledge-suitability-override` | compliance_reviewer | requires the assessment `mismatch=true`; sets `override_text_hash`; emits `OverrideAcknowledged` (non-transition) |
| `POST /investors/{id}/complete-financial-profile` | ops_executive | **now calls BC17 `verify_penny_drop`**; on VALID stamps `bank_account_last4`, → `financial_profile_completed`; fail → reject |
| `POST /investors/{id}/record-kyc-rejected` | compliance_reviewer | `ComplianceService` rejects the KYC file; account holds (no advance); resubmit allowed |
| `POST /investors/{id}/activate` | ops_executive | **IA.3/IA.4 gate**: reject if suitability `mismatch=true` and no `override_text_hash`; else → `active` |

(`issue-invite`, `sign-up`, `submit-kyc`, `record-kyc-approved`, `record-mia-signed` unchanged from WS-3.)

## 5. Five non-negotiables — applicability
Maker-checker (KYC approve/reject via ComplianceService), SoD (per-command role gate: ops vs compliance),
MFA (KYC approve checker), idempotent (`X-Command-Id`), audit (one envelope per command). PII (pan/aadhaar/
bank) on columns, never in an audit payload (WS-3 rule retained).

## 6. Sub-slices (build order — each: red tests → green → /code-review → DoD → DL)

- **M10-A · BC17-verified PAN + bank** ([[DL-BE-045]]) — `record-identity-verified` → `verify_pan` (INV like
  M9-C IRN); `complete-financial-profile` → `verify_penny_drop`. Admin-triggered; ACL result decides;
  fail → clean reject. Aadhaar stays last4-recorded. **Red:** happy (stub VALID) advances; a forced ACL
  fail blocks the transition; PAN still format-validated at the edge.
- **M10-B · Suitability mismatch + override-ack + IA.3 gate** ([[DL-BE-046]]) — `assess-suitability` carries
  `mismatch`; `acknowledge-suitability-override` sets `override_text_hash`; `activate` enforces IA.3/IA.4.
  **Red:** mismatch=false happy; mismatch=true + activate-without-override → reject (stays `mia_signed`);
  mismatch=true + override → activates; override on a non-mismatch assessment → reject; SoD.
- **M10-C · KYC-rejected branch** ([[DL-BE-047]]) — `record-kyc-rejected` (compliance) rejects the
  `comp_kyc_file`; account cannot reach `kyc_approved`; a fresh submit→approve recovers. **Red:** reject →
  approve blocked; resubmit → approve succeeds; reject by non-compliance → 403; reject maker-checker vs
  submitter where applicable.

## 7. Test scenarios (write first) — `InvestorFullRigorTest extends AbstractEdgeHttpTest`
Reuse the WS-3 `InvestorOnboardingTest` harness (issueInvite/signup/send/withEnvelope/statusOf). Each
invariant test proves the app guard (and the DB CHECK where one exists — e.g. `inv_suitability` override
shape, `inv_account` sub_type). Keep PII out of asserted audit payloads.

## 8. Definition of Done (full tier — gate F)
- [x] §7 tests green; whole suite green — **236** (was 229 at M9 close; +12 across M10-A/B/C reusing seeds).
- [x] `/code-review` (high recall) on the diff; 1 fix (`rejectKyc` rowcount/TOCTOU), 2 documented (DL-BE-047).
- [x] Maker-checker (KYC approve/reject), SoD, MFA, idempotency, audit — enforced + tested incl. reject paths.
- [x] No new migration (Suspend/Exit/Revoke — the only schema-changing items — are deferred).
- [x] `DL-BE-044` umbrella + `DL-BE-045..047` per slice; spec flipped to **Status: Done**.

## 9. Remaining gaps after M10-full (documented, with owner)
- **Investor self-service login + portal** (signup/KYC/suitability/financial-profile submission) → portal slice.
- **Invite Revoke** (I.5) → needs V7 (`'revoked'` enum value) + Compliance command.
- **Suspend / Exit / Blacklisted** (IA.5/IA.9) → post-active lifecycle slice; Exit needs a BC2
  `SubscriptionQueryPort` (zero non-terminal subscriptions) — extends the M9 ports + ArchUnit pattern.
- **Full-Aadhaar eKYC** (`verify_aadhaar_ekyc`) → secure transient-input path (never persist full Aadhaar).
- **KYC-refresh scheduler** (IA.6, +12 months) → scheduler-era (with the funding-window/SLA schedulers).
