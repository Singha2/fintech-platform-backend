# WS-6 · Assignment single-leg signed → `all_signed` (BC5 = M12 min) — the C27 disbursement gate

> **Lean sub-slice spec** (Walking Skeleton §4; = M12 min). Light tier. On `fully_funded`, the single
> investor's master agreement (MIA) is signed via the M5c signing ACL (inline), the assignment set reaches
> `all_signed`, and `deal_listing.all_signed` flips TRUE — the **C27 gate** WS-7's disbursement depends on.
> Spec before code; invariant test before rule.

| | |
|---|---|
| **Module** | M1 — Walking Skeleton · WS-6 (= M12 Assignment & Signing, min) |
| **Slice** | WS-6 — assignment set → single-leg MIA signed → `all_signed` → `deal_listing.all_signed` (BC5 + M5c) |
| **Tier** | Light (skeleton-thin — single-leg happy path; multi-leg + the 24h time-box are Milestone 2) |
| **Status** | Done |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-24 |

> **DoR decisions (settled at the gate):**
> 1. **Inline completion** — a `complete-signing` ops step calls `SignatureAclService.completeSignature`
>    (the M5c stub completes in-process); the signing *webhook* is deferred to M12-full (WS-5 already proved
>    the inbound-webhook mechanism — HMAC/dedup/verify-before-mutate — so a signing webhook is the same
>    mechanism, different vendor). WS-6 focuses on the AssignmentSet lifecycle + the C27 gate.
> 2. **Single leg** — `total_count = 1` (the one confirmed subscription, AS.1); multi-leg + the 24h
>    `sign_deadline` *incomplete* path (AS.4, G13) are deferred (the skeleton signs promptly).
> 3. **Admin-on-behalf** — investor signs the MIA (`legal_signer_type='investor'`) but ops initiates +
>    completes (investor login is M11-full). The request is an explicit ops command (no event bus — real
>    BC5 subscribes to `Listing.FullyFunded`).

---

## 1. Scope
**Owns:** a new `assignment` package — `AssignmentService` (commands via `CommandGateway`) +
`AssignmentController`. Tables: `legal_assignment_set`, `legal_master_agreement`, `legal_signature_request`;
flips `deal_listing.all_signed`. Reuses M5c `SignatureAclService` (BC19 ACL — `gate_signature_session`).

**Does NOT own (deferred):** multi-leg assignment (multiple investors); the 24h `sign_deadline` *incomplete*
path + the scheduler (AS.4/G13); the signing *webhook* (M12-full); per-invoice stamping (AS.7/G2,
parked-legal); MAA (supplier-side agreement — that's BC8/onboarding); signing retry/failure paths. No new
migration (schema is V1–V5).

## 2. Upstream dependencies
- **WS-4/WS-5** produce the `fully_funded` listing + the confirmed subscription (seeded in tests).
- **M5c** `SignatureAclService` (`initiateSignature`/`completeSignature`). Done.
- **M4a–d** roles (`ops_executive`); **M2** `AuditLog`.

## 3. Invariants & rules
- **INV-1 — One assignment set per listing (AS.1).** `legal_assignment_set` UNIQUE(listing_id);
  `total_count = ` count of confirmed subscriptions at request time (skeleton: 1). _(ref: AS.1, C27)_
- **INV-2 — Counts balance (AS.5).** `signed_count + unsigned_count = total_count` at all times (DB CHECK);
  request sets `unsigned_count = total_count`, completion moves one to `signed_count`. _(ref: AS.5)_
- **INV-3 — AllSigned at exact equality (AS.3).** `signed_count == total_count` ⇒ set `→ all_signed` ⇒
  flip `deal_listing.all_signed = TRUE` (the C27 gate; listing stays `fully_funded`). _(ref: AS.3, C27, L.5)_
- **INV-4 — Cert/status shape (MA.3/SR.3).** The MIA `signature_cert_serial` is set only on `signed`, the
  signature request `cert_serial` only on `completed` (DB CHECKs) — both written together with the status
  transition. The cert_serial flows from the M5c ACL's `completeSignature`. _(ref: MA.3, SR.3)_
- **INV-5 — Idempotent + audited.** Request idempotent on `X-Command-Id` (+ the listing UNIQUE); the M5c ACL
  is idempotent on its own keys (re-initiate/re-complete safe). One+ envelope per command. _(ref: G18, X13, VS.1)_

## 4. API / type surface (intent-shaped, B4 §2.1)

| Endpoint | Role | Effect |
|---|---|---|
| `POST /listings/{id}/assignment-set/request` → 201 | ops_executive | listing `fully_funded` ⇒ create `legal_assignment_set` (`requested`→`in_progress`, total_count=1) + the investor's MIA (`initiated`) + `legal_signature_request` (`initiated`); **initiate** the signature via M5c |
| `POST /listings/{id}/assignment-set/complete-signing` | ops_executive | M5c `completeSignature` → signature_request `completed` + MIA `signed` (cert_serial) + leg `signed` + `signed_count→1` → set `all_signed` → `deal_listing.all_signed = TRUE` |
| `GET /listings/{id}/assignment-set` | (any authenticated) | read (status, signed_count, total_count, listing all_signed) |

- **Types:** the leg is a JSONB value object `{investor_id, allocation_paise, agreement_id, signature_request_id, vsr_id, status}`.

## 5. Five non-negotiables — applicability
| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | no | assignment/signing is not a propose→approve; the disbursement maker-checker is WS-7 |
| 2 | MFA-fresh | yes | gateway `isMfaFresh` on the ops actor |
| 3 | SoD-checked | yes | `ops_executive` role gate on both commands |
| 4 | Idempotent | yes | `X-Command-Id` (+ listing UNIQUE) on request; M5c ACL keys on signing |
| 5 | Audit-logged | yes | gateway envelope + `signing.SignatureSession.Initiated`/`SignatureCompleted` (ACL) + `AssignmentSet.AllSigned` |

## 6. Events
- **Publishes:** `assignment.AssignmentSet.Requested` / `.AllSigned`; `listing.Listing.DisbursementGateOpened`
  (the `all_signed` flip); (`signing.SignatureSession.Initiated`/`.SignatureCompleted` from the M5c ACL).
- **Subscribes:** none (request is an explicit command; real BC5 subscribes to `Listing.FullyFunded`).

## 7. Test scenarios (write these first) — `AbstractEdgeHttpTest` (seed fully_funded listing + confirmed subscription + active investor)
- [ ] **Happy path:** request → set `in_progress`, total_count=1; complete-signing → set `all_signed`,
      `deal_listing.all_signed = true`, MIA `signed` with `signature_cert_serial`, signature_request `completed`.
- [ ] **C27 gate:** `deal_listing.all_signed` is FALSE after request, TRUE only after complete-signing.
- [ ] **SoD:** a non-ops actor on request (or complete) → 403 `role_not_held`.
- [ ] **One set per listing (AS.1):** a second `request` (different command_id) → rejected; a same-command_id
      replay → original `emitted_events`, one set.
- [ ] **GET** returns the set + the listing's `all_signed`.

## 8. Definition of Done (light tier)
- [x] §7 tests green; whole suite green — `AssignmentAllSignedTest` **4/4**, full suite **191**.
- [x] `/code-review` on the diff (C27 gate, counts/cert CHECKs, idempotency focus); findings fixed —
      3 findings (synthetic aggregate-id on complete → resolve real set id; C27 flip rowcount ignored →
      assert == 1; single-leg guard) + a regression assertion (AllSigned envelope chained to the real set).
- [x] `DL-BE-036` added (the AssignmentSet lifecycle, the inline-signing decision, the C27 flip, the
      deferred multi-leg / time-box / signing webhook).
- [x] This spec flipped to **Status: Done**.
