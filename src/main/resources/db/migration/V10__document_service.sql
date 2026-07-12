-- V10 (M18a, DL-BE-075) — BC16 Documents: the unified document registry + local blob backend.
--
-- Every document — invoice PDF, KYC/KYB scan, and (after M18d) the generated Form 16A — is one row in
-- sys_document, addressed by a surrogate document_id (UUIDv7), with an opaque coarse `kind` label. Bytes sit
-- behind a storage port: local runs entirely on sys_document_blob (this migration); production swaps in GCS
-- (M18c) and leaves this table empty. The legacy sys_document_object (V4, Form-16A-only) is UNTOUCHED here —
-- it converges into sys_document only at the dedicated M18d slice.

CREATE TYPE sys_document_status AS ENUM ('pending_upload', 'stored', 'failed');

-- Universal document handle. Every document (uploaded now; Form 16A after M18d) is one row here.
CREATE TABLE sys_document (
    document_id   UUID PRIMARY KEY,                 -- UUIDv7 (Ids.newId) — surrogate everyone references
    kind          TEXT NOT NULL,                     -- opaque coarse label: 'invoice'|'kyc'|'buyer_kyb'|'form_16a'
    owner_context TEXT,                              -- 'bc1_listing'|'bc11_compliance'|… (nullable until known)
    owner_ref     TEXT,                              -- 'Invoice:<id>'… NULLABLE (set at attach, not upload)
    content_type  TEXT NOT NULL,
    status        sys_document_status NOT NULL DEFAULT 'pending_upload',
    byte_size     BIGINT,                            -- set at finalize / storeGenerated
    doc_hash      BYTEA,                             -- SHA-256(bytes); set at finalize / storeGenerated (integrity/dedup)
    created_by    UUID NOT NULL,                      -- admin/identity that initiated (or the reserved SYSTEM principal)
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    stored_at     TIMESTAMPTZ,
    CONSTRAINT sys_document_stored_has_bytes
        CHECK (status <> 'stored' OR (byte_size IS NOT NULL AND doc_hash IS NOT NULL))
);

CREATE INDEX idx_sys_document_hash  ON sys_document (doc_hash) WHERE doc_hash IS NOT NULL;
CREATE INDEX idx_sys_document_owner ON sys_document (owner_context, owner_ref) WHERE owner_ref IS NOT NULL;

COMMENT ON TABLE sys_document IS
    'BC16 unified document registry (M18a). Every document is one row keyed by a surrogate document_id, with '
    'an opaque coarse kind. owner_ref is nullable (set at attach, not upload) — supports two-phase upload '
    '(M18b). sys_document_object is legacy (Form-16A only) and converges here in M18d. DL-BE-075.';
COMMENT ON COLUMN sys_document.kind IS
    'Opaque coarse label the generic layer never interprets — fine vocabulary + lifecycle stay in the consumer.';
COMMENT ON COLUMN sys_document.owner_ref IS
    'e.g. Invoice:<id> — set by the consuming context''s attach command, NOT at upload/store time.';
COMMENT ON COLUMN sys_document.doc_hash IS
    'SHA-256(rawBytes), set once status=stored (HASH-1). Indexed for dedup lookups; no auto-dedup upsert.';
COMMENT ON CONSTRAINT sys_document_stored_has_bytes ON sys_document IS
    'STATUS-1: a stored row always carries its bytes'' size + hash; pending_upload/failed rows need neither.';

-- Local backend only: raw bytes keyed by the handle. Prod stores in GCS (M18c); this stays empty there.
CREATE TABLE sys_document_blob (
    document_id   UUID PRIMARY KEY REFERENCES sys_document(document_id),
    content_bytes BYTEA NOT NULL                      -- plaintext for now (no encryption at rest — DL-BE-074)
);

COMMENT ON TABLE sys_document_blob IS
    'BC16 local blob backend (DbTableDocumentStore, M18a). Raw bytes keyed by document_id. Swapped for GCS '
    'direct-to-blob at the Production gate (M18c); this table stays empty in that backend.';
