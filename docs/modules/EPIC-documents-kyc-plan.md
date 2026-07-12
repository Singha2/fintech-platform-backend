# EPIC · Documents & KYC — coherent implementation plan (M18 → M19 ∥ M20)

> **What this is.** The single build plan tying the three document modules together with their DoR/DoD
> gates. Modules: **M18** (generic document service, BC16), **M19** (invoice artifacts, BC1), **M20**
> (onboarding documents — KYC investor/supplier + KYB buyer). Follows the per-module loop
> (`Spec_Driven_Build_Plan.md` §D) and the DoR/DoD gates (§E/§F). All three are **DoR-stage, docs-only**.
> Settled decisions: **DL-BE-070, 071, 072, 073, 074**.

---

## 1. Critical path & recommended order

```
        ┌─────────────────────────────────────────────────┐
        │  M18  Documents (BC16)   — FOUNDATION, BLOCKING   │  V10
        │  a) store core (local)   b) two-phase API         │
        │  (unified sys_document; c=GCS deferred)           │
        └───────────────┬─────────────────────────────────┘
                        │  DoD green (DocumentPort + /documents API stable)
            ┌───────────┴───────────┐
            ▼                       ▼
   M19 Invoice Artifacts ✅   M20 Onboarding Docs
   (BC1)  V11 — DONE         (BC11 KYC + BC9 KYB)  V12
   10 tests green            three personas,
   suite 344                 capture-only
            └───────────┬───────────┘
                        ▼
   M18d  Form 16A → sys_document, retire sys_document_object   (isolated cleanup)
```

- **M18 must reach DoD before M19/M20 start** — both compile against `DocumentPort` + the `/documents` API.
- **Recommended order: M18 → M19 → M20.** Rationale: M19 is the *simplest* consumer (one doc kind, one
  context, one download rule) so it proves the two-phase upload + attach + download + the
  maker≠checker-via-ops-check twist end-to-end with the least surface. M20 then **applies the proven
  pattern** across three personas with almost no new mechanism (it's capture-only).
- **Flexibility:** M19 and M20 are independent once M18 is done. If the **onboarding UI** is the higher
  priority for Phase B, swap to M18 → M20 → M19 with no rework. Do **not** run them concurrently on the
  same branch (migration-number ordering V11→V12; keep it linear).
- **Migrations:** V10 (M18) → V11 (M19) → V12 (M20). Linear, no renumbering.

---

## 2. Shared DoR — settled decisions register (green before the epic starts)

These are already decided; listed so nothing is silently re-litigated at a module gate.

| # | Decision | Ref |
|---|---|---|
| S1 | Surrogate **`document_id`** is the identity domain entities reference; content hash retained underneath | DL-BE-072 |
| S2 | **Metadata-first two-phase upload** (`POST /documents` → upload → finalize); attach is a separate domain command | DL-BE-072 |
| S3 | Storage abstracted behind `DocumentStorePort`; **local = DB table**, prod = GCS (deferred) | DL-BE-070/072 |
| S4 | **No encryption at rest now**; seam kept; DO.5/DO.6 deferred to Production gate | DL-BE-074 |
| S5 | **Buyer = KYB, not KYC**; `comp_kyc_subject_type` unchanged | DL-BE-073 |
| S6 | **Capture-only** (non-gating) — approval never blocked on documents until M15 | DL-BE-073 |
| S7 | **Shared configurable checklist** (`onboarding_doc_requirement`, all personas) | DL-BE-073 |
| S8 | KYC/KYB docs **compliance-only** download; invoice docs investor-downloadable | DL-BE-071/073 |
| S9 | Consumers hold `document_id` as an **identity reference** — no cross-BC FK (ArchUnit) | DL-BE-070 |

---

## 3. M18 · Documents — the foundation

**Recommended slicing** (M18 is heavy — cut it so each slice is independently green):

- **M18a — Store core (local, no HTTP). ✅ DONE.** Migration V10 (`sys_document`, `sys_document_blob`);
  `DocumentStorePort` + `DbTableDocumentStore` (`@Profile("!prod")`); `DocumentPort.storeGenerated / resolve / retrieve`.
  7 tests green (round-trip, HASH-1, STATUS-1, DO.2 audit, legacy-registry isolation); full suite **326** green.
- **M18b — Two-phase API. ✅ DONE.** `POST /documents` (initiate) → `PUT /documents/{id}/content` (session-bearer
  upload) → `POST /documents/{id}/finalize`; `GET /{id}` + `/content`; size + content-type guards; audit
  `Initiated`/`Stored` (ids/hash only). Deterministic `document_id` from `X-Command-Id`; finalize idempotent on
  `document_id`. 8 tests green (`DocumentApiTest`); full suite **334**. **M19/M20 now unblocked** (end of M18b).
- **M18c — GCS adapter — DEFERRED to the Production gate.** `GcsDocumentStore` + presigned PUT/GET + the
  short-lived upload token, written and unit-tested against a fake GCS, **not wired**. Not required for
  M19/M20 (they run on the local backend + session bearer). Keep only the port seam now.
- **M18d — Form 16A convergence — SEPARATE FOLLOW-UP.** Migrate M16 Form 16A onto `sys_document`
  (`kind='form_16a'`, via `storeGenerated`) and **retire `sys_document_object`**, with all M16 tests green.
  Run **after** M18 core + consumers; isolates the tax-domain refactor from the foundation build. `DL-BE-076`.

**DoR — CLOSED (DL-BE-075).** All M18 gate items are resolved:
- [x] **Unified `sys_document`** is the registry; uploads never write `sys_document_object` (Form-16A-only → M18d). *(I1)*
- [x] **Operational-path** for initiate/PUT/finalize (not `CommandGateway`/`CommandResponse`); attach is a gateway command. *(I2)*
- [x] **Session-bearer** for local upload; separate upload token deferred to M18c. *(I3)*
- [x] **Raw streaming PUT**, **20 MB** cap (config), content-type allow-list. *(I4)*
- [x] Reuse `Ids.newId()` + `AuditLog`; `/documents/**` auto-authenticated. *(I5)*
- [x] `kind` = opaque coarse label on `sys_document`; fine vocabulary stays in domain link tables.

**DoD (heavy tier — §F + M18 §8):**
- [ ] RED-first tests green: round-trip; **STATUS-1** (pending has no bytes); **DO.1 dedup**;
      `storeGenerated` one-shot; token scope/expiry; audit carries ids only (DO.2); backend-swap harness.
- [ ] **Form 16A (DL-BE-069) still green** — the derived path is not regressed.
- [ ] V10 applied; `ddl-auto=validate` green; **ArchUnit** clean (consumers reach BC16 only via port/API).
- [ ] `/code-review` clean; **DL-BE-070/072/074** finalized; M18 spec → **Done**.

**Anchor RED tests (write first):** STATUS-1 reject, DO.1 dedup, storeGenerated coexistence with Form-16A.

---

## 4. M19 · Invoice Artifacts

**DoR — open items to close:**
- [ ] **`InvestorQueryPort.isDownloadEligible` predicate** — exact investor states that count as "KYC
      complete" for download (e.g. `kyc_approved`, `active`). One-line definition.
- [ ] **Existing-M9-test integration (the real watch-out).** M9's `document_completeness` is a checkbox
      today; wiring it to a stored artifact + `recorder ≠ uploader` **will break the current M9 ops-check
      tests** unless updated. **Plan: update the M9 ops-check tests** to attach a stored `document_id`
      first and use a distinct recorder. Decide: update-in-place (recommended) vs a transitional
      feature-flag. This is the one place M19 touches green code.
- [ ] **Download response** — local stream now; prod `302 → signed URL` deferred with M18c.

**DoD (§F + M19 §8):**
- [ ] RED-first: **DOC.3** (maker=checker rejected), **DOC.2** (pending doc can't pass completeness),
      DOC.4 (freeze post-snapshot), DOC.1 (one active), DOC.5 (pdf-only), **DOC.6 authZ matrix**, idempotent attach.
- [ ] V11 applied; validate green; ArchUnit clean (BC1→BC16 via `DocumentPort`, BC1→BC7 via `InvestorQueryPort`).
- [ ] **M9 flow still green** (ops-check tests updated); full suite green.
- [ ] `/code-review`; **DL-BE-071** finalized; spec → **Done**.

**Anchor RED test:** DOC.3 — `document_completeness` recorded by the uploader → rejected.

---

## 5. M20 · Onboarding Documents (KYC + KYB)

**DoR — open items to close (one needs a business input):**
- [ ] **★ The initial required-document checklist per persona** — the seed rows of
      `onboarding_doc_requirement` (investor / supplier / buyer). **Needs compliance/founder input** — e.g.
      investor {PAN card, address proof}; supplier {PAN, GST cert, board resolution, MOA/AOA, bank
      statement}; buyer {GST cert, CIN cert}. This is the only item that isn't a pure engineering call.
- [ ] **Attach-endpoint roles per persona** — who attaches investor-KYC docs vs supplier-KYC vs buyer-KYB
      (ops_executive / compliance_reviewer split).
- [ ] **Completeness read built now?** — recommend **yes**, read-only (`GET …/completeness`), enforced at M15.

**DoD (§F + M20 §8):**
- [ ] RED-first: **OD.3** (zero-doc submit→approve still succeeds — the no-breakage guarantee), typed
      attach for investor+supplier, buyer KYB with **no `comp_kyc_file` row** (OD.7), OD.4 completeness read,
      **OD.5 authZ** (compliance 200 / investor 403), OD.6 supersede, configurable-checklist edit.
- [ ] **All existing onboarding tests still green** (capture-only guarantee).
- [ ] `comp_kyc_file.doc_hashes` deprecation comment applied.
- [ ] V12 applied; validate green; ArchUnit clean (BC11/BC9 → BC16 via `DocumentPort`).
- [ ] `/code-review`; **DL-BE-073** finalized; spec → **Done**.

**Anchor RED test:** OD.3 — attach zero documents, `submit-kyc → approve` still succeeds.

---

## 6. Epic-level DoD (integration gates, checked after M20)

- [ ] **One reference model** — invoice, KYC, KYB all reference `document_id` via the same `/documents` API.
- [ ] **Full suite green** (current 318 + new) on real Postgres; ArchUnit clean across all three BCs.
- [ ] **No control regression** — the money spine + onboarding flows behave exactly as before (capture-only).
- [ ] **Deferred register updated** — GCS/KMS/encryption (Production gate), completeness enforcement (M15),
      orphan-`pending_upload` sweeper (scheduler era) all flagged, none silently dropped.
- [ ] DECISION_LOG DL-BE-070..074 all in "finalized" state; the three module specs flipped to **Done**.

---

## 7. Recommended working method

Per the established token workflow: **Opus drives each DoR gate + writes the RED invariant tests + runs
`/code-review`**; the mechanical **red→green implementation is delegated to the local `implementer`
(Sonnet) subagent** slice by slice. Sequence: close M18 DoR → red tests → implement M18a → M18b → DoD;
then M19; then M20. One module in flight at a time (Constitution: finish to DoD before the next).

---

## 8. The short list — decisions to close before code

| Close before | Decision | Status / recommendation |
|---|---|---|
| **M18** | registry model, auth, gateway-fit, upload, size, ids | ✅ **CLOSED — DL-BE-075** (unified `sys_document`; operational path; session bearer; raw PUT 20 MB; reuse Ids/AuditLog) |
| **M18** | GCS adapter + upload token | deferred → **M18c** (Production gate) |
| **M18d** | Form 16A → `sys_document` migration | follow-up slice (`DL-BE-076`); M16 tests green |
| **M19** | `isDownloadEligible` states | `kyc_approved` ∪ `active` |
| **M19** | M9 ops-check tests break | update tests in place (attach doc + distinct recorder) |
| **M20** | **★ per-persona required-doc checklist** | **needs compliance/founder input** |
| **M20** | attach-endpoint roles | ops_executive attaches; compliance downloads |
