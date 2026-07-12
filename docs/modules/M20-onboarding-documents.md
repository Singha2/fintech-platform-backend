# M20 · Onboarding Documents — KYC (investor, supplier) + KYB (buyer) — consumes M18

> **Module spec** (Spec_Driven_Build_Plan §C register, Wave-2 addendum). Adds **typed, multi-document**
> handling for all three counterparty personas — investor & supplier **KYC**, buyer **KYB** — as
> **layer-4** consumers of the M18 document service. Documents are **captured, not gated** in Phase 1
> (approval still works via the auto-approve stub); real completeness enforcement lands with **M15**.
> Spec before code; **invariant test before rule**. Umbrella decision: **DL-BE-073**.

| | |
|---|---|
| **Module** | M20 — Onboarding Documents (BC11 KYC + BC9 KYB) |
| **Tier** | Heavy (regulated identity documents; PII; UIDAI/Aadhaar handling) |
| **Status** | Draft (DoR) |
| **Owner** | Amit + Claude |
| **Created** | 2026-07-12 |
| **Depends on** | **M18 Documents (BC16)** — must land first |

---

## 0. Decisions settled at design (the three DoR forks)
1. **Buyer = KYB (document management), not KYC.** Buyer stays **out** of `comp_kyc_file`;
   `comp_kyc_subject_type` remains `investor | supplier` (M8's "no buyer KYC" design untouched). Buyer
   gets its own `buyer_document` set (NOA, GST cert, agreements). _(preserves M8-buyer-full.md:5-6)_
2. **Capture-only (non-gating) in Phase 1.** Upload/attach documents, but **approval is not blocked** on
   them — the auto-approve stub + all existing onboarding tests keep passing. Completeness is *computed*
   (uploaded vs required) and surfaced read-only; **enforced at M15**. _(no test breakage; DL-BE-073)_
3. **Shared configurable checklist (option a).** One `onboarding_doc_requirement(subject_type, doc_kind,
   mandatory, active)` table spans all three personas, editable at runtime. _(DL-BE-073)_

---

## 1. Scope

**Owns:**
- **`kyc_document`** (BC11/compliance) — links a `comp_kyc_file` to its typed documents (investor, supplier).
- **`buyer_document`** (BC9/buyer) — the buyer's typed KYB documents.
- **`onboarding_doc_requirement`** (shared reference) — the per-persona required-document checklist.
- **`kyc_doc_kind`** enum (the controlled document vocabulary) + `onboarding_subject_type` enum (investor|supplier|buyer, decoupled from `comp_kyc_subject_type`).
- Attach / list endpoints on the KYC and buyer surfaces; the **computed completeness** read.
- Migration **`V12__onboarding_documents.sql`**.

**Does NOT own (deferred / other module):**
- **Byte storage, upload API, encryption, dedup** — all M18 (`DocumentPort` / `/documents`). Consumers hold `document_id`.
- **Enforcement of completeness at approval** — **M15** (the real compliance engine). Phase 1 is capture-only.
- **AML/PEP adjudication, SAR, KYC-refresh** — M15.
- **Real investor/buyer login** — attach is exercised by an authenticated admin (DL-019 conventions) until the portal slices.

**Reconciliation — the dead `comp_kyc_file.doc_hashes BYTEA[]` array (deviation):** the spec modelled KYC
docs as a bare `doc_hashes:[bytes32]` array (`09_B3_Aggregates.md:670`); no code ever wrote it. The typed
`kyc_document` table **supersedes** it (a bare array cannot express *which* document is the PAN card vs the
board resolution, nor per-document lifecycle). The array column is **left in place, marked deprecated** —
dropping it is a needless migration risk since nothing reads it. _(deviation logged in DL-BE-073)_

## 2. Upstream dependencies
- **M18 Documents (BC16)** — `DocumentPort` + `/documents` API. **Blocking.**
- **BC11 `comp_kyc_file`** (investor/supplier KYC file) + `ComplianceService` stub. Done.
- **BC9 `buyer_account`** (buyer). Done.
- **M15** — will consume this module's `kyc_document` + `onboarding_doc_requirement` to enforce completeness. Not yet built.

## 3. Invariants & rules
- **OD.1 — typed documents.** Every `kyc_document` / `buyer_document` row carries a `doc_kind` from the
  controlled `kyc_doc_kind` vocabulary and a `document_id` referencing a `sys_document` (BC16). _(DL-BE-073)_
- **OD.2 — identity reference, no cross-BC FK.** `document_id` is held as a bare reference into BC16; bytes
  resolved via `DocumentPort`. No FK to `sys_document`. _(B1 isolation, ArchUnit)_
- **OD.3 — capture-only (non-gating).** Attaching / missing documents does **not** change any onboarding
  state transition in Phase 1. `submit-kyc` / approve behave exactly as today. _(DL-BE-073; no test breakage)_
- **OD.4 — computed completeness (read-only).** `complete(subject) := every active-mandatory
  onboarding_doc_requirement row for the subject_type has ≥1 active kyc_document/buyer_document of that
  doc_kind`. Surfaced as a read; **enforced only at M15**. _(DL-BE-073)_
- **OD.5 — restricted download (opposite of invoices).** KYC/KYB documents are **NOT** investor/counterparty-
  downloadable. Read access is limited to `compliance_reviewer` / relevant ops-admin roles; Aadhaar-bearing
  docs are further UIDAI-restricted (DO.6). Every download audit-logged. _(DO.6, C15; contrast M19)_
- **OD.6 — supersede, never hard-delete.** Replacing a document sets the old link `status='superseded'`; the
  audit trail keeps both. _(DL-BE-073)_
- **OD.7 — buyer is not a KYC subject.** No `buyer` value is added to `comp_kyc_subject_type`; buyer docs
  live only in `buyer_document`. _(preserves M8)_

## 4. API / type surface

**Upload is the generic M18 flow** (`POST /documents` → upload → finalize → `document_id`). These endpoints
only **attach** an already-created `document_id` and read.

| Endpoint | Role | Effect |
|---|---|---|
| `POST /kyc/{kycFileId}/documents` `{document_id, doc_kind}` | ops/compliance (per persona onboarding role) | insert `kyc_document` active; `uploaded_by`=actor |
| `POST /buyers/{buyerId}/documents` `{document_id, doc_kind}` | ops (buyer onboarding role) | insert `buyer_document` active |
| `PUT …/documents/{docLinkId}` | same | supersede (OD.6) |
| `GET /kyc/{kycFileId}/documents` · `GET /buyers/{buyerId}/documents` | compliance/ops (OD.5) | list link metadata (kind, status, document_id) — no bytes |
| `GET …/documents/completeness` | compliance/ops | computed OD.4 view (required vs present, missing kinds) |
| `GET /onboarding-doc-requirements?subject_type=` | ops/compliance | read the checklist |
| `POST /onboarding-doc-requirements` (admin) | admin | edit the checklist (runtime-configurable, option a) |
| *(download)* `GET /documents/{document_id}/content` | **compliance/ops only** (OD.5) | delegated to M18; authorized here |

- **Types:** `kyc_doc_kind`, `onboarding_subject_type`; reuse M18 `DocMeta`/`document_id`.
- **Commands:** attach / supersede are gateway commands (idempotent on `command_id`, MFA-fresh, SoD, audit).

## 5. Five non-negotiables — applicability (attach command)

| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | **deferred** | capture-only Phase 1; the *review* of documents is M15's maker-checker (attach itself proposes no approval) |
| 2 | MFA-fresh | **yes** | attach carries `mfa_assertion_id` |
| 3 | SoD-checked | **yes** | at the command boundary |
| 4 | Idempotent | **yes** | `command_id` + (kyc_file_id/buyer_id, document_id) uniqueness |
| 5 | Audit-logged | **yes** | attach / supersede / **every download** emit envelopes with `document_id` only (DO.2) |

## 6. Events
- **Publishes:** `Onboarding.DocumentAttached{ subject_type, subject_id, doc_kind, document_id }` — optional.
- **Subscribes:** none (M15 will read the tables directly / subscribe later).

## 7. Test scenarios (write these first)
- [ ] **OD.3 (the load-bearing test):** attach zero documents → `submit-kyc → approve` still succeeds (auto-approve stub + existing onboarding tests unaffected).
- [ ] **Investor & supplier KYC:** create doc via M18 → attach with `doc_kind=pan_card` → appears in list.
- [ ] **Buyer KYB:** attach `doc_kind=gst_certificate` to a `buyer_account`; **no `comp_kyc_file` row created**; `comp_kyc_subject_type` unchanged (OD.7).
- [ ] **OD.4 completeness:** with a requirement set {pan_card, address_proof}, uploading only pan_card → completeness read reports `address_proof` missing; approval still allowed (non-gating).
- [ ] **OD.5 authZ:** compliance role downloads → 200 (audited); an investor/counterparty principal → 403.
- [ ] **OD.6 supersede:** replace a doc → old row `superseded`, both retained.
- [ ] **Configurable checklist:** editing `onboarding_doc_requirement` changes the completeness read with no deploy.
- [ ] **Idempotent attach:** same `command_id` replay → no duplicate link row.

## 8. Definition of Done (heavy tier)
- [ ] §7 tests green (HTTP + Testcontainers), OD.3 and OD.5 proven RED-first.
- [ ] `V12__onboarding_documents.sql` applied; `ddl-auto=validate` green; ArchUnit clean (BC11/BC9 reach BC16 via `DocumentPort`; no cross-BC joins).
- [ ] **All existing onboarding tests still green** (capture-only guarantee, OD.3).
- [ ] `comp_kyc_file.doc_hashes` deprecation comment applied; deviation noted.
- [ ] `/code-review`; findings fixed. **DL-BE-073** finalized. Status → **Done**.

## 9. Migration sketch — `V12__onboarding_documents.sql`
```sql
CREATE TYPE onboarding_subject_type AS ENUM ('investor', 'supplier', 'buyer');   -- decoupled from comp_kyc_subject_type
CREATE TYPE kyc_doc_kind AS ENUM (
    'pan_card', 'address_proof', 'gst_certificate', 'certificate_of_incorporation',
    'board_resolution', 'moa_aoa', 'bank_statement', 'cancelled_cheque', 'photograph', 'other');
CREATE TYPE onboarding_doc_status AS ENUM ('active', 'superseded');

-- Shared configurable checklist (option a) — one table, all three personas, runtime-editable.
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

-- KYB docs (BC9): buyer, linked to the buyer account. Buyer is NOT a KYC subject (OD.7).
CREATE TABLE buyer_document (
    buyer_document_id UUID PRIMARY KEY,
    buyer_id          UUID NOT NULL REFERENCES buyer_account(buyer_id),
    document_id       UUID NOT NULL,                  -- identity ref into BC16 (OD.2)
    doc_kind          kyc_doc_kind NOT NULL,
    status            onboarding_doc_status NOT NULL DEFAULT 'active',
    uploaded_by       UUID NOT NULL,
    uploaded_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    superseded_by     UUID,
    UNIQUE (buyer_id, document_id)
);
CREATE UNIQUE INDEX uidx_buyer_active_kind ON buyer_document (buyer_id, doc_kind) WHERE status = 'active';

COMMENT ON COLUMN comp_kyc_file.doc_hashes IS
    'DEPRECATED (DL-BE-073): superseded by the typed kyc_document table (doc_kind + lifecycle). '
    'Never written by code; retained inert to avoid a needless migration. Do not use.';
```
> **AuthZ contrast to remember:** invoice PDFs (M19) are investor-downloadable; **KYC/KYB documents are
> compliance/ops-only** (OD.5 / DO.6). Do not let the M19 download pattern leak into this module.
