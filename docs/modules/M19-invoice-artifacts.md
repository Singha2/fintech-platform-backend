# M19 · Invoice Artifacts (BC1) — consumes M18

> **Module spec** (Spec_Driven_Build_Plan §C register, Wave-2 addendum). Adds the **invoice PDF
> document** to the listing lifecycle: an Ops Executive uploads it (via the generic M18 `/documents`
> API), **attaches** the `document_id` to the invoice, it gates the existing `document_completeness`
> ops-check, freezes at snapshot, and **KYC'd investors download it** for due diligence.
> **Layer-4** (linkage + lifecycle + authZ) over the BC16 document service built in **M18**.
> Spec before code; **invariant test before rule**. Umbrella decision: **DL-BE-071**.

| | |
|---|---|
| **Module** | M19 — Invoice Artifacts (BC1 extension of M9) |
| **Tier** | Heavy (investor-facing PII; funds are committed against what investors review here) |
| **Status** | Draft (DoR) |
| **Owner** | Amit + Claude |
| **Created** | 2026-07-12 |
| **Depends on** | **M18 Documents (BC16)** — must land first (option A) |

---

## 0. Decisions settled at design (the three DoR forks)
1. **Invoice-PDF only (v1).** One `kind='invoice'` artifact per invoice; at most one *active*.
   Supporting docs (e-way bill, PO, GRN) are a **v2** widening, not built now.
2. **Any KYC'd investor may download on a live listing.** Download eligibility = listing status ∈
   `{live, fully_funded, disbursed, matured_payment_received, distributed}` **AND** the investor has
   completed KYC/suitability. Enables due-diligence **before** subscribing. Every download audit-logged.
3. **Maker-checker via the ops-check (no new approval step).** Uploader = maker; the
   `document_completeness` ops-check recorder = checker, enforced **recorder ≠ uploader**. Reuses M9's
   ops-check sub-machine; no bolted-on "approve document" command.

---

## 1. Scope

**Owns (BC1 layer-4 for invoice docs):**
- New table **`deal_invoice_document`** — the invoice↔document linkage + lifecycle (V11 migration),
  referencing a BC16 **`document_id`**.
- The invoice-artifact **attach** surface on the listing (attach / replace / list / **download**).
- Wiring the existing `document_completeness` check to a **real, stored document** (was a checkbox in M9).
- Download **authorization** (layer-4) — the eligibility rule of §0.2, via a read port, not a join.

**Does NOT own (deferred / other module):**
- **The upload itself** — bytes go through the generic **M18 `/documents` API** (initiate → upload →
  finalize); M19 only attaches the resulting `document_id`. PDF content-type/size validation is declared
  at initiate (M18); M19 additionally asserts the resolved document is `application/pdf` + `stored`.
- **Byte storage, encryption, dedup, backend swap** — all M18 (`DocumentPort` / `DocumentStorePort`).
- **Multi-kind artifacts** (e-way bill / PO / GRN) — v2.
- **Investor-download watermarking** — v2 if Compliance requires it.
- **Real investor login** — download eligibility is checked against investor state; the investor *portal
  auth* is the deferred investor-portal slice. In Phase 1, an authenticated admin acting for / as the
  investor exercises the endpoint (mirrors DL-019 / existing GET conventions).

**Where it sits in the M9 listing lifecycle:**
```
POST /listings                          invoice 'submitted'              (no PDF yet — allowed)
POST /documents (M18) → upload → finalize   → document_id (status=stored)
POST .../invoice-documents {document_id}    attach  ── MAKER (uploaded_by = ops A)
POST .../start-ops-checks
POST .../record-ops-check {document_completeness}
        └─ passes ONLY IF: (a) an active kind='invoice' link exists,
                           (b) its document_id resolves to a `stored` doc in M18
                               (bytes present; integrity from M18's finalize hash),
                           (c) recorder ≠ uploaded_by                 ── CHECKER
POST .../complete-ops-checks            all 7 pass → invoice 'listed'
...
snapshot-and-ready (ready_for_review)   ARTIFACT SET FROZEN  (immutable, like L.3 snapshot fields)
... live → investors download for due diligence
```

## 2. Upstream dependencies
- **M18 Documents (BC16)** — the `/documents` API + `DocumentPort` (initiate/finalize/resolve/retrieve). **Blocking.**
- **M9 Listing (BC1)** — the `deal_invoice` aggregate, ops-check sub-machine, `check_outcomes`. Done.
- **M10 Investor (BC7)** — investor KYC/suitability state, read via a new
  `InvestorQueryPort.isDownloadEligible(investorId)` (read-only port, ArchUnit-clean; mirrors M9's
  `BuyerQueryPort`/`SupplierQueryPort`). Done (state exists).

## 3. Invariants & rules
- **DOC.1 — At most one *active* `kind='invoice'` artifact per invoice.** Partial unique index. _(DL-BE-071)_
- **DOC.2 — `document_completeness` passes only against a real, stored document.** The linked `document_id`
  must resolve (via `DocumentPort`) to `status=stored`; integrity is guaranteed by M18's content-hash at
  finalize (no re-hash needed here). A `pending_upload` reference cannot pass. _(DL-BE-071, STATUS-1)_
- **DOC.3 — Maker ≠ checker.** `record-ops-check(document_completeness).actor ≠ deal_invoice_document.uploaded_by`.
  Surfaces as a clean SoD-style reject, not a 500. _(constitution #1/#3; DL-BE-071)_
- **DOC.4 — Freeze at `ready_for_review`.** After snapshot, attach/replace/delete are rejected — the
  artifact set investors fund against is immutable (parallels L.3). _(G20; DL-BE-071)_
- **DOC.5 — PDF only.** The attached document must resolve to `content_type == application/pdf` (declared +
  validated at M18 upload; asserted again at attach). _(DL-BE-071)_
- **DOC.6 — Download eligibility.** Only when listing status ∈ the live-set **AND**
  `InvestorQueryPort.isDownloadEligible` (KYC/suitability complete). Ops roles may always read. Every
  download emits an audit envelope (who / which `document_id` / when). _(§0.2; DO.2; DL-BE-071)_
- **DOC.7 — Supersede, never hard-delete pre-snapshot.** Replace sets the old link `status='superseded'`,
  `superseded_by=<new document_id>`; the audit trail keeps both. _(DL-BE-071)_

## 4. API / type surface

**Upload is the generic M18 flow** (`POST /documents` → upload → finalize → `document_id`). These endpoints
only **attach** an already-`stored` `document_id` and read.

| Endpoint | Role | Transition / effect |
|---|---|---|
| `POST /listings/{id}/invoice-documents` `{document_id}` | ops_executive | assert resolved doc is pdf + `stored` → insert `deal_invoice_document` active, `uploaded_by`=actor. Rejected after `ready_for_review` (DOC.4). |
| `PUT /listings/{id}/invoice-documents/{documentId}` `{new_document_id}` | ops_executive | supersede active (DOC.7); rejected post-snapshot |
| `DELETE /listings/{id}/invoice-documents/{documentId}` | ops_executive | soft-supersede pre-snapshot only |
| `GET /listings/{id}/invoice-documents` | ops / investor (scoped) | link metadata list (kind, status, document_id) — no bytes |
| `GET /listings/{id}/invoice-documents/{documentId}/content` | ops / **KYC'd investor on live listing** | authorize (DOC.6) → `DocumentPort.retrieve`; local: stream `attachment`; prod: `302` → GCS signed URL. **audit-logged**. |

- **Types:** reuse `DocMeta` / `document_id` from M18; `InvestorQueryPort` (new read port).
- **Commands:** attach / replace / delete are gateway commands (idempotent on `command_id`, MFA-fresh,
  SoD, audit). Maker-checker is satisfied via the ops-check (§0.3), not a per-command checker.

## 5. Five non-negotiables — applicability (attach command)

| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | **yes** | uploader = maker; `document_completeness` recorder = checker; **recorder ≠ uploaded_by** (DOC.3) |
| 2 | MFA-fresh | **yes** | attach command carries `mfa_assertion_id` |
| 3 | SoD-checked | **yes** | enforced at the command boundary; DOC.3 is an SoD-shaped guard |
| 4 | Idempotent | **yes** | `command_id` + (invoice_id, document_id) uniqueness |
| 5 | Audit-logged | **yes** | attach, replace, **and every download** emit envelopes with `document_id` only (DO.2) |

## 6. Events
- **Publishes:** `Invoice.ArtifactAttached{ invoice_id, document_id }` — optional; ops-check reads the link table directly in Phase 1.
- **Subscribes:** none.

## 7. Test scenarios (write these first)
- [ ] **Happy path:** upload PDF via M18 → attach → `document_completeness` recorded by a *different* ops user → passes → invoice `listed`; investor on the live listing downloads exact bytes.
- [ ] **DOC.3 (the load-bearing test):** `document_completeness` recorded by the **uploader** → **rejected** (maker=checker).
- [ ] **DOC.2:** attach a `pending_upload` document_id (bytes never finalized) → `document_completeness` cannot pass.
- [ ] **DOC.4:** attach/replace after `ready_for_review` → rejected.
- [ ] **DOC.1:** second active `kind='invoice'` attach → rejected (unique index).
- [ ] **DOC.5:** attaching a non-PDF document_id → rejected at attach.
- [ ] **DOC.6 — authZ matrix:** KYC'd investor + live listing → 200; non-KYC investor → 403; draft/pre-live listing → 403; every 200 writes a download audit event.
- [ ] **Idempotent attach:** same `command_id` replay → no duplicate link row.

## 8. Definition of Done (heavy tier)
- [ ] §7 tests green (HTTP + Testcontainers), DOC.3 and DOC.6 proven RED-first.
- [ ] `V11__invoice_documents.sql` applied; `ddl-auto=validate` green; ArchUnit clean (BC1 reaches BC16
      via `DocumentPort`, BC7 via `InvestorQueryPort` — no cross-BC joins).
- [ ] M9 ops-check flow still green (the checkbox→artifact wiring is backward-compatible for existing tests, or those tests updated).
- [ ] `/code-review`; findings fixed.
- [ ] **DL-BE-071** finalized (per-slice notes).
- [ ] Status flipped to **Done**.

## 9. Migration sketch — `V11__invoice_documents.sql`
```sql
CREATE TYPE deal_invoice_doc_status AS ENUM ('active', 'superseded');

CREATE TABLE deal_invoice_document (
    invoice_id     UUID NOT NULL REFERENCES deal_invoice(invoice_id),
    document_id    UUID NOT NULL,                  -- identity ref into BC16 sys_document; NO cross-BC FK
    status         deal_invoice_doc_status NOT NULL DEFAULT 'active',
    uploaded_by    UUID NOT NULL,                  -- maker; DOC.3 checks recorder ≠ this
    uploaded_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    superseded_by  UUID,                           -- replacement document_id when status='superseded'
    PRIMARY KEY (invoice_id, document_id)
);
-- DOC.1 — exactly one active invoice artifact per invoice
CREATE UNIQUE INDEX uidx_invoice_active_doc
    ON deal_invoice_document (invoice_id) WHERE status = 'active';

COMMENT ON TABLE deal_invoice_document IS
    'BC1 layer-4 linkage: binds a deal_invoice to a BC16 document (document_id) with lifecycle. '
    'document_id is an IDENTITY REFERENCE into sys_document (BC16), not an FK — bounded-context '
    'isolation (no cross-BC FK; BC16 reached via DocumentPort). Bytes/encryption/dedup live in M18.';
```

> **Note — why `document_id` here is a bare reference, not an FK to `sys_document`:** cross-BC FKs are
> forbidden (ArchUnit / B1 isolation). The listing context holds the id as an identity reference and
> resolves bytes/metadata through `DocumentPort` — the surrogate-handle equivalent of the pattern
> `sys_document_object.originating_aggregate_ref` was designed to allow (`V4__generic_acl.sql:396`).
