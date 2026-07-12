# M20 · Onboarding Documents — KYC (investor, supplier) + KYB (buyer) — consumes M18

> **Module spec** (Spec_Driven_Build_Plan §C register, Wave-2 addendum). Adds **typed, multi-document**
> handling for the two **KYC** personas — investor & supplier — as **layer-4** consumers of the M18
> document service; and a **minimal KYB** for the buyer: **one optional custom document + a manual
> `kyb_verified` attestation**. Documents are **captured, not gated** in Phase 1 (approval still works via
> the auto-approve stub); real completeness enforcement lands with **M15**.
> Spec before code; **invariant test before rule**. Umbrella decision: **DL-BE-073**.

| | |
|---|---|
| **Module** | M20 — Onboarding Documents (BC11 KYC + BC9 KYB) |
| **Tier** | Heavy (regulated identity documents; PII; UIDAI/Aadhaar handling) |
| **Status** | **Buyer KYB slice DONE** (`V12`, 6 tests green, full suite 350); KYC (investor/supplier) still Draft (DoR — needs the checklist input) |
| **Owner** | Amit + Claude |
| **Created** | 2026-07-12 |
| **Depends on** | **M18 Documents (BC16)** — must land first |

---

## 0. Decisions settled at design (the DoR forks)
1. **Buyer = minimal KYB, not KYC — and asymmetric to the KYC personas.** The buyer is **not** a
   `comp_kyc_file` subject and gets **no typed document set / no checklist**. Instead:
   - the buyer's existing **automated `identity_verified`** (GSTIN + CIN via BC17) is **untouched**; and
   - **on top of it**, a new **manual `kyb_verified` attestation** — a boolean an Ops Executive sets —
     with **at most one optional free-form custom document** attached (via the M18 `/documents` API).
   Two concepts coexist: automated identity proof (unchanged) + a manual human sign-off. Buyer stays
   **out** of `comp_kyc_subject_type` **and** out of `onboarding_doc_requirement` / `kyc_doc_kind`.
   _(user decision 2026-07-12: "just one custom document and a check box that verified buyer"; DL-BE-073)_
2. **Capture-only (non-gating) in Phase 1.** Upload/attach KYC documents (and set `kyb_verified`), but
   **no onboarding state transition is blocked** on them — the auto-approve stub + all existing onboarding
   tests keep passing. KYC completeness is *computed* (uploaded vs required) and surfaced read-only;
   **enforced at M15**. `kyb_verified` is likewise an independent attribute, not a gate. _(no test breakage; DL-BE-073)_
3. **Shared configurable checklist (option a) — KYC personas only.** One `onboarding_doc_requirement(
   subject_type, doc_kind, mandatory, active)` table spans **investor + supplier**, editable at runtime.
   The buyer is deliberately excluded (no per-kind checklist for KYB). _(DL-BE-073)_

---

## 1. Scope

**Owns:**
- **`kyc_document`** (BC11/compliance) — links a `comp_kyc_file` to its typed documents (investor, supplier).
- **Buyer KYB fields on `buyer_account`** (BC9) — `kyb_verified` (bool) + `kyb_verified_by` + `kyb_verified_at`
  + **`kyb_document_id`** (nullable single optional custom doc, an identity ref into BC16). **No typed
  buyer-document table, no `doc_kind` for the buyer.**
- **`onboarding_doc_requirement`** (shared reference) — the per-persona required-document checklist,
  **investor + supplier only**.
- **`kyc_doc_kind`** enum (the controlled KYC document vocabulary) + `onboarding_subject_type` enum
  (**investor | supplier**, decoupled from `comp_kyc_subject_type`).
- KYC attach / list endpoints + the **computed completeness** read; the buyer **KYB-verify attestation**
  command (+ optional doc attach).
- Migration **`V12__onboarding_documents.sql`**.

**Does NOT own (deferred / other module):**
- **Byte storage, upload API, encryption, dedup** — all M18 (`DocumentPort` / `/documents`). Consumers hold `document_id`.
- **Enforcement of completeness at approval** — **M15** (the real compliance engine). Phase 1 is capture-only.
- **AML/PEP adjudication, SAR, KYC-refresh** — M15.
- **The buyer's automated identity check** (GSTIN + CIN → `identity_verified`) — that's **M8, unchanged**.
  KYB here is only the *manual* sign-off layered on top.
- **Real investor/buyer login** — attach is exercised by an authenticated admin (DL-019 conventions) until the portal slices.

**Reconciliation — the dead `comp_kyc_file.doc_hashes BYTEA[]` array (deviation):** the spec modelled KYC
docs as a bare `doc_hashes:[bytes32]` array (`09_B3_Aggregates.md:670`); no code ever wrote it. The typed
`kyc_document` table **supersedes** it (a bare array cannot express *which* document is the PAN card vs the
board resolution, nor per-document lifecycle). The array column is **left in place, marked deprecated** —
dropping it is a needless migration risk since nothing reads it. _(deviation logged in DL-BE-073)_

## 2. Upstream dependencies
- **M18 Documents (BC16)** — `DocumentPort` + `/documents` API. **Blocking.**
- **BC11 `comp_kyc_file`** (investor/supplier KYC file) + `ComplianceService` stub. Done.
- **BC9 `buyer_account`** (buyer) + its automated `identity_verified` lifecycle. Done.
- **M15** — will consume this module's `kyc_document` + `onboarding_doc_requirement` (and may read
  `kyb_verified`) to enforce completeness. Not yet built.

## 3. Invariants & rules
- **OD.1 — typed KYC documents.** Every `kyc_document` row carries a `doc_kind` from the controlled
  `kyc_doc_kind` vocabulary and a `document_id` referencing a `sys_document` (BC16). _(DL-BE-073)_
- **OD.2 — identity reference, no cross-BC FK.** `document_id` (KYC docs *and* the buyer's
  `kyb_document_id`) is held as a bare reference into BC16; bytes resolved via `DocumentPort`. No FK to
  `sys_document`. _(B1 isolation, ArchUnit)_
- **OD.3 — capture-only (non-gating).** Attaching / missing KYC documents, and the value of `kyb_verified`,
  do **not** change any onboarding state transition in Phase 1. `submit-kyc` / approve, and the buyer's
  `identity_verified → credit_assessed → …` machine, behave exactly as today. _(DL-BE-073; no test breakage)_
- **OD.4 — computed completeness (read-only, KYC only).** `complete(subject) := every active-mandatory
  onboarding_doc_requirement row for the subject_type (investor|supplier) has ≥1 active kyc_document of
  that doc_kind`. Surfaced as a read; **enforced only at M15**. Buyer has no completeness read (no checklist). _(DL-BE-073)_
- **OD.5 — restricted download (opposite of invoices).** KYC documents **and** the buyer's KYB custom doc
  are **NOT** investor/counterparty-downloadable. Read access is limited to `compliance_reviewer` /
  relevant ops-admin roles; Aadhaar-bearing docs are further UIDAI-restricted (DO.6). Every download
  audit-logged. _(DO.6, C15; contrast M19)_
- **OD.6 — supersede, never hard-delete.** Replacing a KYC document sets the old link `status='superseded'`;
  replacing the buyer's KYB doc overwrites `kyb_document_id` but the prior value is preserved in the audit
  trail. _(DL-BE-073)_
- **OD.7 — buyer is not a KYC subject.** No `buyer` value in `comp_kyc_subject_type` or
  `onboarding_subject_type`; the buyer never gets a `comp_kyc_file` or a `kyc_document` row. _(preserves M8)_
- **OD.8 — KYB attestation is a single manual sign-off, not maker-checker.** `kyb_verified` is set by **one**
  Ops Executive (`ops_executive` role) via an MFA-fresh, audit-logged, idempotent attestation command;
  it is **distinct from and layered on top of** the automated `identity_verified` (both can be true
  independently). Setting it stamps `kyb_verified_by` + `kyb_verified_at`; it may reference the optional
  `kyb_document_id`. Maker-checker is **deferred** (this is an attestation, not a value-moving command). _(DL-BE-073)_

## 4. API / type surface

**Upload is the generic M18 flow** (`POST /documents` → upload → finalize → `document_id`). These endpoints
only **attach** an already-created `document_id`, read, or set the attestation flag.

| Endpoint | Role | Effect |
|---|---|---|
| `POST /kyc/{kycFileId}/documents` `{document_id, doc_kind}` | compliance / ops (per persona onboarding role) | insert `kyc_document` active; `uploaded_by`=actor |
| `PUT /kyc/{kycFileId}/documents/{docLinkId}` `{new_document_id}` | same | supersede (OD.6) |
| `GET /kyc/{kycFileId}/documents` | compliance/ops (OD.5) | list link metadata (kind, status, document_id) — no bytes |
| `GET /kyc/{kycFileId}/documents/completeness` | compliance/ops | computed OD.4 view (required vs present, missing kinds) |
| `POST /buyers/{buyerId}/kyb-verification` `{verified: true, document_id?}` | **ops_executive** | **OD.8** — set `kyb_verified=true`, stamp `kyb_verified_by`/`_at`; optionally set `kyb_document_id` (single custom doc). MFA + audit; idempotent on `command_id`. |
| `GET /buyers/{buyerId}/kyb-verification` | ops / compliance (OD.5) | read `kyb_verified` + `kyb_verified_by/_at` + `kyb_document_id` (no bytes) |
| `GET /onboarding-doc-requirements?subject_type=` | ops/compliance | read the checklist (investor\|supplier) |
| `POST /onboarding-doc-requirements` (admin) | admin | edit the checklist (runtime-configurable, option a) |
| *(download)* `GET /documents/{document_id}/content` | **compliance/ops only** (OD.5) | delegated to M18; authorized here (covers KYC docs *and* the KYB custom doc) |

- **Types:** `kyc_doc_kind`, `onboarding_subject_type` (investor\|supplier); reuse M18 `DocMeta`/`document_id`.
- **Commands:** KYC attach / supersede **and** the buyer KYB-verify attestation are gateway commands
  (idempotent on `command_id`, MFA-fresh, SoD, audit).

## 5. Five non-negotiables — applicability

| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | **deferred** | capture-only Phase 1; KYC *review* is M15's maker-checker. KYB is a **single attestation** by one ops exec (OD.8), not a two-party command |
| 2 | MFA-fresh | **yes** | KYC attach and the KYB-verify command carry `mfa_assertion_id` |
| 3 | SoD-checked | **yes** | at the command boundary |
| 4 | Idempotent | **yes** | `command_id` + (kyc_file_id, document_id) uniqueness for attach; `command_id` for KYB-verify |
| 5 | Audit-logged | **yes** | attach / supersede / **KYB-verify** / **every download** emit envelopes with `document_id` / buyer_id only (DO.2) |

## 6. Events
- **Publishes:** `Onboarding.DocumentAttached{ subject_type, subject_id, doc_kind, document_id }`;
  `Buyer.KybVerified{ buyer_id, verified_by, document_id? }` — both optional.
- **Subscribes:** none (M15 will read the tables directly / subscribe later).

## 7. Test scenarios (write these first)
- [ ] **OD.3 (the load-bearing test):** attach zero KYC documents → `submit-kyc → approve` still succeeds
      (auto-approve stub + existing onboarding tests unaffected); buyer machine unchanged whether or not `kyb_verified`.
- [ ] **Investor & supplier KYC:** create doc via M18 → attach with `doc_kind=pan_card` → appears in list.
- [x] **Buyer KYB verify (OD.8):** ops_executive `POST /buyers/{id}/kyb-verification {verified:true}` →
      `kyb_verified=true`, `kyb_verified_by`/`_at` stamped; **no `comp_kyc_file` row created**;
      `comp_kyc_subject_type` unchanged (OD.7); buyer's automated `identity_verified` untouched. _(BuyerKybVerificationTest)_
- [x] **Buyer KYB with optional doc:** create a custom doc via M18 → `POST …/kyb-verification {verified:true,
      document_id}` → `kyb_document_id` set. _(OD.5 restricted download deferred with the investor portal — see §OD.5 note.)_
- [x] **Buyer KYB idempotent:** replay same `command_id` → no double-stamp / stable `kyb_verified_at`.
- [x] **Buyer KYB role gate:** a non-`ops_executive` principal → 403 (`role_not_held`).
- [ ] **OD.4 completeness (KYC):** with a requirement set {pan_card, address_proof}, uploading only pan_card →
      completeness read reports `address_proof` missing; approval still allowed (non-gating).
- [ ] **OD.5 authZ:** compliance role downloads → 200 (audited); an investor/counterparty principal → 403.
- [ ] **OD.6 supersede (KYC):** replace a doc → old row `superseded`, both retained.
- [ ] **Configurable checklist:** editing `onboarding_doc_requirement` changes the completeness read with no deploy.
- [ ] **Idempotent KYC attach:** same `command_id` replay → no duplicate link row.

## 8. Definition of Done (heavy tier)
- [ ] §7 tests green (HTTP + Testcontainers), OD.3 and OD.5 proven RED-first.
- [ ] `V12__onboarding_documents.sql` applied; `ddl-auto=validate` green; ArchUnit clean (BC11/BC9 reach BC16
      via `DocumentPort`; no cross-BC joins).
- [ ] **All existing onboarding tests still green** (capture-only guarantee, OD.3 — including the buyer
      identity-verified flow, untouched).
- [ ] `comp_kyc_file.doc_hashes` deprecation comment applied; deviation noted.
- [ ] `/code-review`; findings fixed. **DL-BE-073** finalized. Status → **Done**.

## 9. Migrations

**As-built — the buyer KYB shipped on its own, ahead of KYC.** Because the KYC checklist content is still
pending business input, the module was sliced: the buyer KYB (no external input needed) landed first as its
own migration; the KYC tables become a **later migration** (`V13`, when the checklist rows are known).

### 9a. Buyer KYB — **`V12__buyer_kyb_verification.sql`** (SHIPPED)
```sql
-- M20 buyer KYB (DL-BE-073, OD.8): a manual attestation on top of the automated identity_verified.
-- No typed buyer-document table, no doc_kind: at most ONE optional free-form custom document.
ALTER TABLE buyer_account
    ADD COLUMN kyb_verified     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN kyb_verified_by  UUID,                        -- ops_executive identity id who attested (OD.8)
    ADD COLUMN kyb_verified_at  TIMESTAMPTZ,
    ADD COLUMN kyb_document_id  UUID,                         -- optional single custom doc; identity ref into BC16 (OD.2)
    ADD CONSTRAINT buyer_kyb_verified_stamped
        CHECK (kyb_verified = FALSE OR (kyb_verified_by IS NOT NULL AND kyb_verified_at IS NOT NULL));
```
Wired: `.Buyer.RecordKybVerified` (BuyerService, `OPS`-gated, version-guarded non-transition; `kyb_verified_by
= actor identity id, per the M19 uploaded_by convention) + `POST`/`GET /buyers/{id}/kyb-verification`
(BuyerController). `document_id` is a bare reference (no DocumentPort call → ArchUnit clean). Tests:
`BuyerKybVerificationTest` (6). _(DL-BE-073 as-built)_

### 9b. KYC docs (investor + supplier) — **`V13__onboarding_documents.sql`** (PENDING checklist input)
```sql
CREATE TYPE onboarding_subject_type AS ENUM ('investor', 'supplier');   -- KYC personas only; buyer excluded (KYB ≠ KYC)
CREATE TYPE kyc_doc_kind AS ENUM (
    'pan_card', 'address_proof', 'gst_certificate', 'certificate_of_incorporation',
    'board_resolution', 'moa_aoa', 'bank_statement', 'cancelled_cheque', 'photograph', 'other');
CREATE TYPE onboarding_doc_status AS ENUM ('active', 'superseded');

-- Shared configurable checklist (option a) — investor + supplier, runtime-editable. Buyer has no checklist.
CREATE TABLE onboarding_doc_requirement (
    subject_type onboarding_subject_type NOT NULL,
    doc_kind     kyc_doc_kind NOT NULL,
    mandatory    BOOLEAN NOT NULL DEFAULT TRUE,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (subject_type, doc_kind)
);

-- KYC docs (BC11): investor + supplier, linked to the KYC file.
CREATE TABLE kyc_document (
    kyc_document_id UUID PRIMARY KEY,
    kyc_file_id     UUID NOT NULL REFERENCES comp_kyc_file(kyc_file_id),
    document_id     UUID NOT NULL,                    -- identity ref into BC16 sys_document (OD.2 — no FK)
    doc_kind        kyc_doc_kind NOT NULL,
    status          onboarding_doc_status NOT NULL DEFAULT 'active',
    uploaded_by     UUID NOT NULL,
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    superseded_by   UUID,
    UNIQUE (kyc_file_id, document_id)
);
CREATE UNIQUE INDEX uidx_kyc_active_kind ON kyc_document (kyc_file_id, doc_kind) WHERE status = 'active';
-- (buyer KYB already shipped in V12 — see 9a; the buyer is NOT part of this KYC migration, OD.7)

COMMENT ON COLUMN comp_kyc_file.doc_hashes IS
    'DEPRECATED (DL-BE-073): superseded by the typed kyc_document table (doc_kind + lifecycle). '
    'Never written by code; retained inert to avoid a needless migration. Do not use.';
```
> **AuthZ contrast to remember:** invoice PDFs (M19) are investor-downloadable; **KYC documents and the
> buyer's KYB custom doc are compliance/ops-only** (OD.5 / DO.6). Do not let the M19 download pattern leak
> into this module.
>
> **KYB vs identity_verified:** `kyb_verified` is a *manual human sign-off* and is **independent of** the
> buyer's *automated* `identity_verified` (GSTIN+CIN, M8). Never conflate the two columns or gate one on the other.
