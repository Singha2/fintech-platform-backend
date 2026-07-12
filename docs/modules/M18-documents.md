# M18 · Documents (BC16) — unified document service, API + storage abstraction

> **Module spec** (Spec_Driven_Build_Plan §C register, Wave-2 addendum; per-module loop §D).
> Stands up **BC16 Documents** as one **unified** document service: **every** document — invoice PDF,
> KYC/KYB scan, and (after M18d) the generated Form 16A — is one row in **`sys_document`** with a `kind`
> label, created/stored/retrieved by **one** metadata-first, two-phase API. Bytes sit behind a storage
> port (**local = DB table, production = GCS, direct-to-blob**). Domain entities reference a surrogate
> **`document_id`**; domain *meaning* stays in the consuming context.
> Spec before code; **invariant test before rule**. Decisions: **DL-BE-070, 072, 074, 075**.

| | |
|---|---|
| **Module** | M18 — Documents (BC16), unified core |
| **Tier** | Heavy (the shared custody + upload layer for KYC, KYB, invoice, signed-legal, tax docs) |
| **Status** | **M18a done** (store core green, 7 tests; full suite 326) — M18b next |
| **Owner** | Amit + Claude |
| **Created** | 2026-07-12 (rev. — DL-BE-075 unified model + DoR resolutions) |

---

## 0. The principle (DL-BE-075)

**Separate *how a document is handled* (uniform for all) from *what a document means* (domain-specific).**
Every document is created, stored, and retrieved the **same way** through `sys_document` + the `/documents`
API; only its **`kind`** label differs. Lifecycle, invariants, and authorization that legitimately differ
stay in the **consuming context** and are untouched by this module.

| Concern | Where | Same / differs |
|---|---|---|
| create / store bytes / retrieve | `sys_document` + `sys_document_blob` + `/documents` API (**M18**) | **same for all** |
| coarse `kind` label (`invoice`/`kyc`/`buyer_kyb`/`form_16a`) | `sys_document.kind` — **opaque to the generic layer** | differs (just a label) |
| fine doc vocabulary (`pan_card`, `gst_certificate`, …), lifecycle, invariants, download authZ | domain link tables (M19 `deal_invoice_document`, M20 `kyc_document`/`buyer_document`) | **domain-specific — unchanged** |

The generic layer never learns what a "PAN card" is — it stores bytes + an opaque label. Domains keep meaning.

---

## 1. Scope — four layers

| Layer | What | Owner |
|---|---|---|
| **1. Blob** | raw document bytes (plaintext for now — DL-BE-074), keyed by `document_id` | **M18** — `DocumentStorePort` (DB local / GCS prod) |
| **2. Handle / registry** | the addressable universal row: `document_id`, `kind`, status, `doc_hash`, `owner_ref` | **M18** — **`sys_document`** + the `/documents` API |
| **3. Generic service** | `DocumentPort` / `DocumentService` other contexts call | **M18** |
| **4. Linkage + lifecycle + authZ** | *this invoice's PDF* / *this KYC file's PAN card* | **consuming context** (M19, M20) — **NOT M18** |

**Owns:**
- `com.arthvritt.platform.document` package — **BC16**.
- **`sys_document`** (universal handle) + **`sys_document_blob`** (local bytes) — migration **`V10__document_service.sql`**.
- The generic HTTP **`/documents` API** (§2) + `DocumentPort`/`DocumentService`.
- `DocumentStorePort` + `DbTableDocumentStore` (`@Profile("dev")`).

**Does NOT own (deferred / other context):**
- **Layer 4** — link tables, fine `doc_kind`, lifecycle, **download authorization** (STORE-1). First consumers M19/M20.
- **`GcsDocumentStore` + presigned URLs + upload token** — **M18c, deferred to the Production gate** (see I3/I4). Local runs entirely on the session-bearer + DB backend.
- **`sys_document_object` and Form 16A** — left **exactly as-is** (legacy, Form-16A-only). Their convergence into `sys_document` is **M18d** (§10) — a separate, isolated slice so the tax domain is never at risk inside this build.
- **Encryption at rest** — deferred to Production gate (DL-BE-074).
- **Orphan sweep** of `pending_upload` rows never finalized → scheduler era (flagged).
- **Virus/malware scan** → future `DocumentScanPort` (flagged).

---

## 2. The generic document API (metadata-first, two-phase)

**Server-generated docs skip upload** — `DocumentPort.storeGenerated` (§4), one-shot; the M18d path for Form 16A.

| Step | Endpoint | Auth | Effect |
|---|---|---|---|
| **1. Initiate** | `POST /documents` `{kind, content_type, declared_size}` | session bearer | insert `sys_document` (`status=pending_upload`, `created_by`); `document_id` **derived deterministically from `X-Command-Id`** (replay-safe). Returns `{document_id, upload_url}`. |
| **2. Upload** | `PUT /documents/{id}/content` (raw `application/pdf` body) | **session bearer** (local) | stream into `sys_document_blob`; enforce ≤ **20 MB** + content-type. *(Prod: `upload_url` is a presigned GCS URL — M18c.)* |
| **3. Finalize** | `POST /documents/{id}/finalize` | session bearer | verify bytes present, compute `SHA-256`, set `byte_size`+`doc_hash`, flip `status=stored`. Idempotent on `document_id`. |
| **Resolve** | `GET /documents/{id}` | consumer-scoped | metadata only (kind, status, size) — no bytes |
| **Retrieve** | `GET /documents/{id}/content` | **consumer-authorized** | local: stream; prod: `302` → presigned GCS GET. **AuthZ delegated to the consuming context** (STORE-1); audit-logged. |

**Attach is a separate, domain-owned command** referencing `document_id` (M19/M20). A `document_id` is
attachable before its bytes land; *completeness/validity* requires `status=stored` (STATUS-1).

## 3. DoR-settled integration decisions (DL-BE-075)

The DoR investigation surfaced five real integration points with existing infrastructure; all resolved:

- **I1 — `sys_document` is THE registry; uploads never write `sys_document_object`.** The old table
  (`V4:365`) is content-hash-PK, `originating_aggregate_ref`/`encryption_key_ref` **NOT NULL**, and has no
  bytes column — hostile to two-phase upload. We do **not** reuse it: `sys_document` has a surrogate PK, a
  **nullable** `owner_ref`, and no encryption column. `sys_document_object` stays Form-16A-only → **M18d**.
- **I2 — upload plumbing uses the operational path, not `CommandGateway`/`CommandResponse`.** `CommandRequest`
  demands `aggregateType`/`aggregateId`/`expectedVersion` up front and `CommandResponseAssembler` rebuilds a
  response from exactly one audit row — neither fits a 3-step upload. Initiate/PUT/finalize follow the
  **webhook idiom** (`SettlementService.reconcile`: direct `jdbc` + `AuditLog.append`) with **bespoke response
  bodies**. **Attach** (M19/M20) is a normal gateway command.
- **I3 — local upload uses the normal session bearer; no separate upload token now.** The PUT hits our own
  endpoint and the caller already holds a session bearer (`SessionBearerAuthFilter`). The presigned-URL +
  short-lived upload-token mechanism is only needed for **direct-to-GCS** → deferred to **M18c**.
- **I4 — raw streaming `PUT` (not multipart), explicit 20 MB cap.** No multipart config exists; a raw
  `application/pdf` body avoids Spring's 1 MB multipart default. Add Tomcat `maxSwallowSize` + an in-handler
  size guard (20 MB; config-tunable). Filters (`RequestIdFilter`, `SessionBearerAuthFilter`) don't buffer the
  body — safe for streaming.
- **I5 — reuse existing primitives.** `Ids.newId()` (UUIDv7) for `document_id`; `AuditLog.append(AuditEnvelopes.seed(…))`
  for chained audit (standalone, as the settlement/webhook paths already do). `/documents/**` is auto-authenticated
  by SecurityConfig's `anyRequest().authenticated()` — no security change to register it.

## 4. Invariants & rules
- **HASH-1 — content hash for integrity/dedup.** `doc_hash = SHA256(rawBytes)` at finalize; retrieval MAY
  re-verify. Dedup (if needed) is a lookup on the `sys_document.doc_hash` index — **not** a shared-registry
  upsert. **No encryption at rest** (DL-BE-074).
- **STATUS-1 — a `stored` row has its bytes.** `status='stored' ⇒ byte_size AND doc_hash present` (DB CHECK);
  `pending_upload` rows carry neither and are non-attachable-as-complete.
- **STORE-1 — M18 authorizes neither `retrieve` nor `attach`.** The consuming context is the policy point.
- **DO.2 — binary never inlined into audit payloads** — envelopes carry `document_id`/`doc_hash` only.
- **DO.3 India residency / DO.4 10-yr retention / DO.6 Aadhaar-restricted** — DO.6 is a layer-4 (consumer)
  authZ concern (M20). **DO.5 encryption at rest — deferred to Production gate** (DL-BE-074).

## 5. API / type surface (in-process)
- `DocumentPort.initiate(kind, contentType, declaredSize, createdBy, commandId) → UploadTicket{document_id, upload_url}`.
- `DocumentPort.finalize(document_id) → DocMeta`.
- `DocumentPort.storeGenerated(rawBytes, kind, contentType, ownerContext, ownerRef) → DocMeta` — one-shot for
  server-generated bytes (the **M18d** Form-16A path); creates a `sys_document` already `stored`.
- `DocumentPort.resolve(document_id) → DocMeta?` · `DocumentPort.retrieve(document_id) → bytes | RedirectUrl` (no authZ).
- **Storage abstraction:** `DocumentStorePort.put/get(document_id, bytes)`, `exists(document_id)`; prod adds
  `presignPut/presignGet` (M18c). `DbTableDocumentStore` (dev) / `GcsDocumentStore` (prod, M18c).
- **Types:** `UploadTicket`, `DocMeta{document_id, kind, status, content_type, byte_size, doc_hash}`.

## 6. Five non-negotiables — applicability

Custody + transport infrastructure; upload is not a domain state change (the consumer's **attach** carries controls).

| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | **no** | consumer's attach carries it (e.g. M19 uploader ≠ ops-check recorder) |
| 2 | MFA-fresh | **inherited** | attach runs in the consumer's MFA-fresh command; initiate/finalize session-scoped |
| 3 | SoD-checked | **inherited** | at the consumer's command boundary |
| 4 | Idempotent | **yes** | initiate → `document_id` derived from `X-Command-Id`; finalize idempotent on `document_id` |
| 5 | Audit-logged | **yes** | initiate/finalize/**retrieve** emit envelopes (ids only, DO.2) via `AuditLog.append` |

## 7. Test scenarios (write these first)
- [ ] **Two-phase round-trip (local):** initiate → PUT bytes → finalize → `resolve` shows `stored`; `retrieve` returns exact bytes.
- [ ] **STATUS-1:** `pending_upload` row has no bytes; retrieve/finalize-without-upload → clean error, not 500.
- [ ] **Idempotency:** replay initiate with same `X-Command-Id` → same `document_id`, no dup row; replay finalize → no-op.
- [ ] **HASH-1:** `SHA256(retrieve) == doc_hash`; dedup lookup finds an identical prior hash.
- [ ] **I4 size guard:** > 20 MB body → rejected; non-PDF content-type → rejected.
- [ ] **storeGenerated:** server bytes → a `stored` row in one call (the M18d Form-16A shape) — no upload endpoint used.
- [ ] **Audit:** initiate/finalize/retrieve write `sys_audit_event` with ids only, never the binary (DO.2).
- [ ] **Isolation:** `sys_document_object` + Form 16A untouched (no writes to the old table from M18).
- [ ] **Backend-swap harness:** suite green on `DbTableDocumentStore`; `GcsDocumentStore` (M18c) unit-tested against a fake GCS.

## 8. Definition of Done (heavy tier)
- [ ] §7 tests green (Testcontainers Postgres).
- [ ] `V10__document_service.sql` applied; `ddl-auto=validate` green; **ArchUnit** clean (consumers reach BC16 only via `DocumentPort`/API).
- [ ] **Form 16A (M16/DL-BE-069) still green and untouched** — `sys_document_object` not written by M18.
- [ ] `/code-review`; **DL-BE-070/072/074/075** finalized; Status → **Done**.

## 9. Slicing
- **M18a — store core (local, no HTTP): ✅ DONE.** `sys_document` + `sys_document_blob` (V10);
  `DocumentStorePort` + `DbTableDocumentStore` (`@Profile("!prod")`); `DocumentPort.storeGenerated/resolve/retrieve`.
  7 RED tests → green (`DocumentStoreTest`); full suite 326 green, ArchUnit clean, Form 16A untouched.
- **M18b — two-phase API:** `POST /documents` → `PUT …/content` → finalize; size guard; audit; deterministic
  `document_id` from `X-Command-Id`. **End of M18b = M19/M20 unblocked.**
- **M18c — GCS adapter + presign + upload token — DEFERRED to the Production gate** (written/fake-GCS-tested, not wired).

## 10. M18d — Form 16A convergence (separate follow-up slice)
Migrate M16 Form 16A onto `sys_document` (`kind='form_16a'`, via `storeGenerated`), stamp
`tax_year_profile.form_16a_document_id`, then **retire `sys_document_object`**. Done **after** M18 core is
proven, in isolation, with **all M16 tests kept green** (issuance command, reissue-rejected, download bytes/hash
identical). This is the step that makes `sys_document` the single registry — deliberately, not big-bang, so the
tax domain is never broken. Own DoR/DoD; claims its own `DL-BE-076`.

## 11. Migration sketch — `V10__document_service.sql`
```sql
CREATE TYPE sys_document_status AS ENUM ('pending_upload', 'stored', 'failed');

-- Universal document handle. Every document (uploaded now; Form 16A after M18d) is one row here.
CREATE TABLE sys_document (
    document_id   UUID PRIMARY KEY,                 -- UUIDv7 (Ids.newId) — surrogate everyone references
    kind          TEXT NOT NULL,                    -- opaque coarse label: 'invoice'|'kyc'|'buyer_kyb'|'form_16a'
    owner_context TEXT,                             -- 'bc1_listing'|'bc11_compliance'|… (nullable until known)
    owner_ref     TEXT,                             -- 'Invoice:<id>'… NULLABLE (set at attach, not upload)
    content_type  TEXT NOT NULL,
    status        sys_document_status NOT NULL DEFAULT 'pending_upload',
    byte_size     BIGINT,                           -- set at finalize
    doc_hash      BYTEA,                            -- SHA-256(bytes); set at finalize (integrity/dedup)
    created_by    UUID NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    stored_at     TIMESTAMPTZ,
    CONSTRAINT sys_document_stored_has_bytes
        CHECK (status <> 'stored' OR (byte_size IS NOT NULL AND doc_hash IS NOT NULL))
);
CREATE INDEX idx_sys_document_hash  ON sys_document (doc_hash) WHERE doc_hash IS NOT NULL;
CREATE INDEX idx_sys_document_owner ON sys_document (owner_context, owner_ref) WHERE owner_ref IS NOT NULL;

-- Local backend only: raw bytes keyed by the handle. Prod stores in GCS (M18c); this stays empty there.
CREATE TABLE sys_document_blob (
    document_id   UUID PRIMARY KEY REFERENCES sys_document(document_id),
    content_bytes BYTEA NOT NULL                     -- plaintext for now (DL-BE-074)
);

COMMENT ON TABLE sys_document IS
    'BC16 unified document registry. Every document is one row keyed by a surrogate document_id, with an '
    'opaque coarse kind. owner_ref is nullable (set at attach, not upload) — supports two-phase upload. '
    'sys_document_object is legacy (Form-16A only) and converges here in M18d.';
```
> `sys_document_object` (V4) is **untouched** by M18 and remains Form-16A-only until **M18d** retires it.
