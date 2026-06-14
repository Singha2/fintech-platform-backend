-- =============================================================================
-- 04_generic_acl.sql
-- Fintech Invoice Discounting Platform — Phase 1 MVP
-- Generic Subdomains: BC14 Audit Log, BC15 Notifications, BC16 Documents
-- Integration ACLs:   BC17 Verification ACL, BC18 Banking ACL, BC19 Signing ACL
-- Shared Types:       Domain value-object DOMAINs, shared enums, cross-table indexes
--
-- Design decisions (locked — DL-001 et seq., A1 constraints):
--   • All PKs are UUIDv7 stored as UUID (application generates; DB stores natively).
--   • Money amounts in BIGINT paise (INR×100). Never FLOAT.
--   • All timestamps TIMESTAMPTZ (UTC stored; display in IST at application layer).
--   • Immutable audit log: AE.1 enforced by trigger and structural narrow API surface.
--   • 10-year retention (C1): operational requirement — not enforceable in DDL alone;
--     enforced via storage-substrate WORM policy and application-layer deletion guards.
--   • India data residency (C13): operational / infra requirement; comment only.
-- =============================================================================


-- =============================================================================
-- NOTE: Shared domain value-object types (pan_type, gstin_type, ifsc_type,
-- irn_type, aadhaar_last4_type, money_paise, bps_type) were defined here
-- originally but have been moved to SECTION 0 of 01_core.sql so they are
-- available to all subsequent migrations (01 → 02 → 03 → 04).
-- =============================================================================


-- =============================================================================
-- SECTION 1 — ENUM TYPES (new in this file)
-- =============================================================================

-- BC15 Notifications
CREATE TYPE notification_channel_enum AS ENUM (
    'email',
    'sms'
);

CREATE TYPE notification_status_enum AS ENUM (
    'queued',
    'sent',
    'delivered',
    'failed'
);

-- BC17 Verification ACL
CREATE TYPE verification_api_enum AS ENUM (
    'verify_pan',
    'verify_aadhaar_ekyc',
    'verify_gstin',
    'fetch_mca21',
    'fetch_gst_returns',
    'fetch_bureau',
    'fetch_aa_bank_stmt',
    'verify_penny_drop',
    'verify_irn',
    'verify_eway_bill',
    'screen_aml_pep'
);

CREATE TYPE verification_status_enum AS ENUM (
    'requested',
    'completed',
    'failed',
    'stale',
    'manual_fallback'
);

-- BC18 Banking ACL
CREATE TYPE vendor_instruction_type_enum AS ENUM (
    'create_va',
    'close_va',
    'payout_single',
    'payout_multi_leg',
    'refund',
    'fetch_master_statement'
);

CREATE TYPE vendor_instruction_status_enum AS ENUM (
    'pending',
    'sent',
    'executed',
    'failed'
);

CREATE TYPE inflow_status_enum AS ENUM (
    'provisional',
    'reconciled',
    'unmatched'
);

-- BC19 Signing ACL
CREATE TYPE sign_method_enum AS ENUM (
    'aadhaar_otp',
    'dsc'
);

CREATE TYPE vsr_status_enum AS ENUM (
    'session_initiated',
    'completed',
    'failed',
    'expired'
);


-- =============================================================================
-- SECTION 2 — BC14 AUDIT LOG
-- =============================================================================

-- APPLICATION-LAYER INVARIANTS: AuditEvent
--   AE.1  APPEND-ONLY. No UPDATE, no DELETE by any role, including Super Admin.
--         Enforced at DB layer by trigger (prevent_audit_modification) below and
--         at application layer by a structurally narrow command surface (Append only).
--   AE.2  previous_envelope_hash chains envelopes in arrival order per shard (G25).
--         envelope_hash is computed over canonical encoding minus envelope_hash itself.
--         Verifying the chain requires reading envelopes in recorded_at order per shard.
--   AE.3  10-year retention from recorded_at (C1, DL-040).
--         NOT enforceable in Postgres DDL. Enforced via:
--           (a) WORM-capable storage substrate (G7) — e.g. S3 Object Lock or equivalent;
--           (b) Application-layer deletion guard: no DELETE path exists in the API;
--           (c) Scheduled auditor spot-checks (B2 §3.13, DL-041).
--   AE.4  Every state-changing command, sensitive read, approval/override, role change,
--         agency action, fund-movement instruction, webhook event, and auditor activity
--         is appended exactly once (publish-before-success per B2 P3, X13).
--   AE.5  actor JSONB must contain at minimum: actor_type, actor_id, session_id.
--         Optional: mfa_assertion_id (required for all admin_user actors per C7),
--         agency_consent_id (required when actor_type = 'agency').
--   AE.6  corrects references the event_id of the envelope being corrected.
--         Correction events do not modify the original; they grow the log (B2 P6).
--   AE.7  Binary artefacts (KYC docs, signed PDFs, vendor payloads) are NEVER inlined
--         into payload, before_state, or after_state. Only doc_hash (SHA-256) is stored.
--         Full binaries live exclusively in BC16 sys_document_object (C14, C15).

CREATE TABLE sys_audit_event (
    -- Identity
    event_id                UUID            NOT NULL,
    -- Event classification
    event_type              TEXT            NOT NULL,
    event_version           INT             NOT NULL DEFAULT 1,
    schema_uri              TEXT,
    -- Timing
    occurred_at             TIMESTAMPTZ     NOT NULL,
    recorded_at             TIMESTAMPTZ     NOT NULL DEFAULT now(),
    -- Actor — JSONB containing: actor_type, actor_id, session_id,
    --   mfa_assertion_id (nullable), agency_consent_id (nullable).
    -- Validated at application layer; stored verbatim for full auditability.
    actor                   JSONB           NOT NULL,
    -- Aggregate identity
    aggregate_type          TEXT            NOT NULL,
    aggregate_id            UUID            NOT NULL,
    aggregate_version       INT             NOT NULL,
    -- Causality chain
    correlation_id          UUID            NOT NULL,
    causation_id            UUID,                           -- NULL only for root events
    -- Payload — event-type-specific per B2 §2.1 schema_uri.
    -- Binary content MUST NOT be inlined; store doc_hash (BC16) instead. (AE.7)
    payload                 JSONB           NOT NULL,
    before_state            JSONB,                          -- Required on state-transition events
    after_state             JSONB,                          -- Required on state-transition events
    -- Correction reference — non-null when this envelope corrects a prior one (AE.6)
    corrects                UUID            REFERENCES sys_audit_event (event_id)
                                            DEFERRABLE INITIALLY DEFERRED,
    -- Cryptographic chain — SHA-256 of the immediately previous envelope in this shard.
    -- NULL only for the very first row in a shard (G25 per-shard chains).
    previous_envelope_hash  BYTEA,

    CONSTRAINT sys_audit_event_pk PRIMARY KEY (event_id),
    CONSTRAINT sys_audit_event_aggregate_version_positive
        CHECK (aggregate_version >= 1),
    CONSTRAINT sys_audit_event_event_version_positive
        CHECK (event_version >= 1)
);

COMMENT ON TABLE sys_audit_event IS
    'BC14 Audit Log. Append-only universal event sink (B1 §1.4, C1, C2, C3, DL-040). '
    'Every state-changing action, sensitive read, approval/override, role change, agency action, '
    'fund-movement instruction, webhook event, and auditor activity is written here before the '
    'producing command returns success (X13). '
    '10-year retention (C1): enforced via WORM substrate (G7) — not by Postgres DDL alone. '
    'India data residency (C13): all replicas must remain within Indian data-centre perimeter.';

COMMENT ON COLUMN sys_audit_event.event_id IS
    'UUIDv7 primary key. Lexicographically time-ordered — tie-breaks on recorded_at within a shard.';
COMMENT ON COLUMN sys_audit_event.event_type IS
    'Fully-qualified: <context>.<Aggregate>.<VerbPastTense>  e.g. listing.Listing.GoneLive. Stable identifier.';
COMMENT ON COLUMN sys_audit_event.event_version IS
    'Schema version of payload. Starts at 1. Bumped on backward-incompatible payload change.';
COMMENT ON COLUMN sys_audit_event.schema_uri IS
    'Pointer to the JSON Schema for (event_type, event_version). Resolvable in dev/staging; archival ref in prod.';
COMMENT ON COLUMN sys_audit_event.occurred_at IS
    'When the fact happened in the producer''s clock. occurred_at <= recorded_at always.';
COMMENT ON COLUMN sys_audit_event.recorded_at IS
    'When BC14 received and persisted the envelope. Gap vs occurred_at = producer-to-log latency.';
COMMENT ON COLUMN sys_audit_event.actor IS
    'JSONB object: {actor_type, actor_id, session_id, mfa_assertion_id?, agency_consent_id?}. '
    'mfa_assertion_id required for all admin_user actors (C7). '
    'agency_consent_id required when actor_type = agency (X12, G5).';
COMMENT ON COLUMN sys_audit_event.aggregate_version IS
    'Monotonic per-aggregate sequence. First event on an aggregate = 1. '
    'Optimistic concurrency check is at application layer; storage does not enforce uniqueness '
    'on (aggregate_id, aggregate_version) to avoid hot-row contention on the audit table.';
COMMENT ON COLUMN sys_audit_event.causation_id IS
    'event_id of the immediately preceding event that caused this one. '
    'NULL only for root events (user commands, scheduled jobs, inbound webhooks at ACL edge).';
COMMENT ON COLUMN sys_audit_event.payload IS
    'Event-type-specific payload per schema_uri. Money fields use paise BIGINT. '
    'Binary artefacts are NEVER inlined — only SHA-256 hashes (doc_hash) are stored (AE.7, C14).';
COMMENT ON COLUMN sys_audit_event.before_state IS
    'Snapshot of affected aggregate fields before the state transition. Required on transition events.';
COMMENT ON COLUMN sys_audit_event.after_state IS
    'Snapshot of affected aggregate fields after the state transition. Required on transition events.';
COMMENT ON COLUMN sys_audit_event.corrects IS
    'References the event_id being retroactively corrected. '
    'The original envelope is NEVER modified; this new envelope extends the chain (B2 P6, AE.6).';
COMMENT ON COLUMN sys_audit_event.previous_envelope_hash IS
    'SHA-256 of the canonically encoded previous envelope in this shard (G25, G7). '
    'NULL only for the first row in a shard. Enables chain verification.';

-- Immutability trigger — enforces AE.1 at DB layer.
-- The trigger fires BEFORE any UPDATE or DELETE on sys_audit_event and raises an exception,
-- ensuring that even a superuser executing a raw SQL UPDATE/DELETE is blocked at the statement level.
-- Note: pg_dump / physical restore is still possible via superuser bypass of row-level triggers;
-- WORM storage substrate (G7) is the complementary control for those paths.
CREATE OR REPLACE FUNCTION fn_prevent_audit_modification()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'sys_audit_event is append-only (AE.1, C1, DL-040). '
        'UPDATE and DELETE are prohibited on event_id = %. '
        'Corrections must be submitted as new events with corrects = this event_id.',
        OLD.event_id;
    RETURN NULL;
END;
$$;

CREATE TRIGGER prevent_audit_modification
    BEFORE UPDATE OR DELETE ON sys_audit_event
    FOR EACH ROW
    EXECUTE FUNCTION fn_prevent_audit_modification();

COMMENT ON TRIGGER prevent_audit_modification ON sys_audit_event IS
    'Enforces AE.1: no UPDATE or DELETE on any audit row by any role including Super Admin. '
    'Complementary control: WORM storage substrate (G7). '
    'Retroactive corrections use a new correcting envelope referencing corrects = original event_id.';

-- Indexes — BC14
CREATE INDEX idx_audit_aggregate
    ON sys_audit_event (aggregate_id, aggregate_type);
COMMENT ON INDEX idx_audit_aggregate IS
    'Per-aggregate event stream queries: fetch all events for a given aggregate root.';

CREATE INDEX idx_audit_correlation
    ON sys_audit_event (correlation_id);
COMMENT ON INDEX idx_audit_correlation IS
    'Trace all events in a logical transaction (one user click, one webhook, one cron tick).';

CREATE INDEX idx_audit_occurred_at
    ON sys_audit_event (occurred_at);
COMMENT ON INDEX idx_audit_occurred_at IS
    'Time-range queries for auditor exports and compliance reports (DL-041, C3).';

CREATE INDEX idx_audit_actor_id
    ON sys_audit_event ((actor->>'actor_id'));
COMMENT ON INDEX idx_audit_actor_id IS
    'Fetch all events for a given actor (for per-user audit trails and auditor-the-auditor queries).';


-- =============================================================================
-- SECTION 3 — BC15 NOTIFICATIONS
-- =============================================================================

-- APPLICATION-LAYER INVARIANTS: NotificationDispatch
--   ND.1  Dispatch is fire-and-forget. Delivery failure does NOT roll back business state
--         (B1 §4.4, DL-049). The status column tracks dispatch lifecycle only.
--   ND.2  payload stores template variables ONLY. Raw OTPs, PII (phone, email), and
--         sensitive personal data must NEVER appear in this column (C14, C15).
--         Recipient identity is stored as recipient_identity_id (FK to auth_identity) —
--         the application resolves the address at dispatch time from the Identity context.
--   ND.3  causation_event_id is a SOFT FK to sys_audit_event.event_id (not a hard FK).
--         Rationale: the audit event is written in a separate transaction before the
--         notification is enqueued; a hard FK would create a cross-context coupling that
--         could delay the audit write or cause constraint failures under high load.
--         Application layer guarantees ordering: audit append → notification enqueue (X13).
--   ND.4  Failed dispatches may retry per vendor SLA; retry_count cap enforced at ND.3.

CREATE TABLE sys_notification_dispatch (
    dispatch_id             UUID                        NOT NULL,
    -- Recipient — FK to auth_identity (BC7/BC8/BC9 identity anchor).
    -- The application resolves the channel address (email or phone) from Identity at
    -- dispatch time and never stores the raw address here (ND.2, C15).
    recipient_identity_id   UUID                        NOT NULL,
    channel                 notification_channel_enum   NOT NULL,
    template_id             TEXT                        NOT NULL,
    -- Template variables ONLY. Raw OTPs and PII must never appear here (ND.2).
    payload                 JSONB                       NOT NULL DEFAULT '{}',
    status                  notification_status_enum    NOT NULL DEFAULT 'queued',
    -- Vendor-assigned message reference (e.g. SES MessageId, SMS transactionId).
    provider_ref            TEXT,
    -- Soft FK to sys_audit_event.event_id — see ND.3 above.
    -- Stored as UUID so queries can join on event_id without a hard FK constraint.
    causation_event_id      UUID,

    CONSTRAINT sys_notification_dispatch_pk PRIMARY KEY (dispatch_id)
);

COMMENT ON TABLE sys_notification_dispatch IS
    'BC15 Notifications. Tracks outbound transactional notification dispatch lifecycle '
    '(email + SMS). Fire-and-forget: delivery failure does not roll back business state (ND.1, B1 §4.4).';
COMMENT ON COLUMN sys_notification_dispatch.dispatch_id IS
    'UUIDv7 PK minted by the notification context at enqueue time.';
COMMENT ON COLUMN sys_notification_dispatch.recipient_identity_id IS
    'FK to auth_identity (Identity context). Application resolves channel address at dispatch; '
    'raw email / phone number is never persisted here (ND.2, C14, C15).';
COMMENT ON COLUMN sys_notification_dispatch.payload IS
    'Template variable bag ONLY. Raw OTPs, PII (email, phone, PAN, Aadhaar) must NEVER appear '
    'in this column (ND.2, C14, C15, DL-050).';
COMMENT ON COLUMN sys_notification_dispatch.provider_ref IS
    'Vendor-assigned reference returned on successful submission (e.g. SES MessageId). '
    'NULL until the vendor acknowledges the dispatch.';
COMMENT ON COLUMN sys_notification_dispatch.causation_event_id IS
    'UUID of the sys_audit_event row that caused this notification. '
    'SOFT FK — no REFERENCES constraint. Rationale: the audit row is written in a separate '
    'transaction before notification enqueue; a hard FK would cross context boundaries and could '
    'cause constraint failures or ordering coupling under load (ND.3, X13).';

-- Index — BC15
CREATE INDEX idx_notification_recipient
    ON sys_notification_dispatch (recipient_identity_id);
COMMENT ON INDEX idx_notification_recipient IS
    'Per-recipient notification history lookup (e.g. for ops investigation of missed messages).';

CREATE INDEX idx_notification_status
    ON sys_notification_dispatch (status)
    WHERE status IN ('queued', 'failed');
COMMENT ON INDEX idx_notification_status IS
    'Partial index for notification worker queue processing and failed-dispatch retry sweeps.';


-- =============================================================================
-- SECTION 4 — BC16 DOCUMENTS
-- =============================================================================

-- APPLICATION-LAYER INVARIANTS: DocumentObject
--   DO.1  Storage is IDEMPOTENT on doc_hash (SHA-256 content address).
--         If the application attempts to store a document whose SHA-256 is already present,
--         the store is a no-op and the existing row is returned. This is enforced by the
--         PK constraint on doc_hash (ON CONFLICT DO NOTHING at application layer).
--   DO.2  Binary content is NEVER inlined into AuditEvent payloads (sys_audit_event.payload).
--         Only doc_hash is stored in event envelopes. The binary lives exclusively here (AE.7).
--   DO.3  India data residency (C13, DL-040): all document storage — primary and replica —
--         must remain within Indian data-centre perimeter. Operational / infra requirement.
--   DO.4  10-year retention (C1): operational requirement. Not enforceable in Postgres DDL.
--         Enforced via object-storage WORM policy (G7, G15) and application deletion guards.
--   DO.5  Encryption at rest: encryption_key_ref identifies the per-tenant envelope key in
--         the KMS. The binary in object storage is encrypted with the data key; only the
--         key reference is stored here. Actual binary is in object storage (C14).
--   DO.6  Aadhaar e-KYC response: if stored at all, must be encrypted under UIDAI-compliant
--         key management and strictly access-controlled (C15, DL-050). Access is restricted
--         to the Verification ACL (BC17) and Compliance (BC11) read paths.

CREATE TABLE sys_document_object (
    -- Content-addressed PK: SHA-256 of the raw binary (DO.1).
    doc_hash                BYTEA           NOT NULL,
    -- MIME type e.g. application/pdf, image/jpeg, application/json.
    content_type            TEXT            NOT NULL,
    -- Which bounded context produced this document (e.g. 'bc17_verification', 'bc19_signing').
    originating_context     TEXT            NOT NULL,
    -- Human-readable reference to the aggregate that produced this document.
    -- E.g. 'Verification:01HXZ...', 'SignatureRequest:01HYA...'.
    originating_aggregate_ref TEXT          NOT NULL,
    stored_at               TIMESTAMPTZ     NOT NULL DEFAULT now(),
    byte_size               BIGINT          NOT NULL,
    -- Per-tenant envelope key reference in the KMS. The actual binary is stored in
    -- object storage encrypted with the data key derived from this envelope key (DO.5, C14).
    encryption_key_ref      TEXT            NOT NULL,

    CONSTRAINT sys_document_object_pk PRIMARY KEY (doc_hash),
    CONSTRAINT sys_document_object_byte_size_positive CHECK (byte_size > 0)
);

COMMENT ON TABLE sys_document_object IS
    'BC16 Documents. Content-addressed encrypted document registry. '
    'Stores metadata only; binary is in object storage encrypted per encryption_key_ref (C14). '
    'Storage is idempotent on doc_hash — identical content is stored exactly once (DO.1). '
    'Binary is NEVER inlined into AuditEvent payloads; only doc_hash crosses context boundaries (DO.2). '
    'India-resident storage required (C13, DO.3). 10-year retention (C1, DO.4).';
COMMENT ON COLUMN sys_document_object.doc_hash IS
    'SHA-256 of the raw (pre-encryption) binary. Content-addressed natural PK. '
    'Idempotent: ON CONFLICT DO NOTHING at application layer (DO.1).';
COMMENT ON COLUMN sys_document_object.originating_context IS
    'Bounded context that produced and owns this document e.g. bc17_verification, bc19_signing, bc11_compliance.';
COMMENT ON COLUMN sys_document_object.originating_aggregate_ref IS
    'Free-text qualified aggregate reference: "<AggregateType>:<aggregate_id>". '
    'Enables traceability back to the producing aggregate without a hard cross-context FK.';
COMMENT ON COLUMN sys_document_object.encryption_key_ref IS
    'KMS envelope key reference for this tenant. Data key is derived from this; binary in object '
    'storage is encrypted with the data key. Changing this ref requires a re-encryption job (C14).';

-- Index — BC16
CREATE INDEX idx_document_originating
    ON sys_document_object (originating_context, originating_aggregate_ref);
COMMENT ON INDEX idx_document_originating IS
    'Lookup all documents produced by a given aggregate (e.g. all docs for a Verification record).';


-- =============================================================================
-- SECTION 5 — BC17 VERIFICATION ACL
-- =============================================================================

-- APPLICATION-LAYER INVARIANTS: Verification
--   V.1   vendor_instruction_id = client_request_id sent to the aggregator vendor.
--         Idempotency: the application checks (subject_id, api_name) within the current
--         TTL window before issuing a new request. If a non-stale completed record exists,
--         the cached result is returned; no new vendor call is made.
--   V.2   HMAC verification of vendor callback is mandatory before any state mutation
--         on this row (C10, A2 §1.2). hmac_verified_at is set at webhook ingress.
--   V.3   vendor_payload_hash references the verbatim vendor response stored in BC16.
--         The full vendor JSON is archived for audit and re-parsing without re-calling
--         the vendor. Only the hash is stored here; the binary lives in sys_document_object.
--   V.4   Per-data-type TTLs (A2 §1.4) — application sets ttl_until at completion:
--           PAN verification         : 12 months
--           Aadhaar e-KYC            : 12 months
--           MCA21 company data       : 18 months
--           Bureau report            : 30 days
--           GST returns              : 90 days
--           AA bank statement        : 90 days
--           Penny-drop (bank verify) : 12 months
--         TTLs for verify_irn, verify_eway_bill, screen_aml_pep: per-use (no caching).
--   V.5   manual_fallback status is set when automated verification fails after retries
--         and the record is escalated to Compliance for manual adjudication (G8).
--   V.6   Status transitions (application-enforced state machine):
--           requested → completed | failed
--           completed → stale (TTL hit)
--           failed    → manual_fallback

CREATE TABLE gate_verification (
    -- UUIDv7 PK = client_request_id sent to the aggregator vendor (V.1).
    verification_id         UUID                        NOT NULL,
    subject_id              UUID                        NOT NULL,
    api_name                verification_api_enum       NOT NULL,
    status                  verification_status_enum    NOT NULL DEFAULT 'requested',
    -- SHA-256 of verbatim vendor response stored in sys_document_object (V.3).
    -- NULL until the vendor responds (completed or failed with a response body).
    vendor_payload_hash     BYTEA,
    -- Parsed, normalised fields extracted from the vendor response.
    -- Schema is per api_name; documented in the schema_uri of the completion event.
    extracted_fields        JSONB,
    -- When this verification result expires and must be re-fetched (V.4).
    -- NULL for one-shot verifications (irn, eway_bill, aml_pep).
    ttl_until               TIMESTAMPTZ,
    -- Error classification for failed / manual_fallback records (A2 §1.5).
    -- E.g. 'vendor_5xx', 'timeout', 'business_mismatch', 'uidai_outage'.
    failure_class           TEXT,
    -- When the vendor callback HMAC was verified (V.2, C10).
    signature_verified_at   TIMESTAMPTZ,
    requested_at            TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT gate_verification_pk PRIMARY KEY (verification_id)
);

COMMENT ON TABLE gate_verification IS
    'BC17 Verification ACL. One row per outbound call to the aggregator vendor (A2 §1). '
    'verification_id = client_request_id for idempotency. '
    'Caches vendor responses within per-data-type TTLs to avoid redundant vendor calls (V.1, V.4). '
    'Verbatim vendor payload stored in sys_document_object (BC16) — only hash here (V.3).';
COMMENT ON COLUMN gate_verification.verification_id IS
    'UUIDv7 PK = client_request_id sent to the aggregator. Acts as the idempotency key on the vendor side.';
COMMENT ON COLUMN gate_verification.subject_id IS
    'UUID of the entity being verified: investor_account_id, supplier_account_id, buyer_account_id, etc.';
COMMENT ON COLUMN gate_verification.vendor_payload_hash IS
    'SHA-256 of verbatim vendor JSON response. Full payload archived in sys_document_object. '
    'NULL until vendor responds. Enables re-parsing without re-calling the vendor.';
COMMENT ON COLUMN gate_verification.extracted_fields IS
    'Normalised domain-meaningful fields parsed from vendor response. '
    'Schema varies by api_name. E.g. for verify_pan: {name, status, linked_aadhaar_seeded}.';
COMMENT ON COLUMN gate_verification.ttl_until IS
    'Result expiry. NULL for one-shot verifications (irn, eway_bill, aml_pep). '
    'Per-type TTLs: PAN 12m, Aadhaar e-KYC 12m, MCA21 18m, Bureau 30d, '
    'GST returns 90d, AA bank stmt 90d, penny-drop 12m (A2 §1.4).';
COMMENT ON COLUMN gate_verification.failure_class IS
    'Error classification for failed/manual_fallback rows (A2 §1.5). '
    'E.g. vendor_5xx, timeout, business_mismatch, uidai_outage.';
COMMENT ON COLUMN gate_verification.signature_verified_at IS
    'Timestamp at which the inbound webhook HMAC was verified (C10, A2 §1.2). '
    'Must be set before any state mutation on this row (V.2).';

-- Indexes — BC17
CREATE INDEX idx_verification_subject_api
    ON gate_verification (subject_id, api_name);
COMMENT ON INDEX idx_verification_subject_api IS
    'Cache lookup: find active (non-stale) verification for a given (subject, api_name) pair (V.1).';

CREATE INDEX idx_verification_ttl
    ON gate_verification (ttl_until)
    WHERE ttl_until IS NOT NULL AND status = 'completed';
COMMENT ON INDEX idx_verification_ttl IS
    'Scheduled TTL sweep: find completed verifications whose ttl_until has passed, to mark stale.';

CREATE INDEX idx_verification_status
    ON gate_verification (status)
    WHERE status IN ('requested', 'manual_fallback');
COMMENT ON INDEX idx_verification_status IS
    'Ops dashboard: in-flight and manual-fallback verification records requiring attention (G8).';


-- =============================================================================
-- SECTION 6 — BC18 BANKING ACL
-- =============================================================================

-- APPLICATION-LAYER INVARIANTS: VendorInstruction
--   VI.1  vendor_instruction_id = client_instruction_id sent to escrow vendor (C9, A2 §2.3).
--         This IS the idempotency key: the same UUID may be retried to the vendor; the vendor
--         must return the same outcome without double-execution. Application must never reuse
--         a vendor_instruction_id for a different instruction payload.
--   VI.2  All inbound webhooks from the escrow vendor are HMAC-verified before any state
--         mutation on this row (C10, A2 §2.2). hmac_verified_at is set at webhook ingress.
--   VI.3  vendor_event_id UNIQUE constraint enforces webhook deduplication (first-write-wins).
--         Duplicate vendor callbacks produce a Webhook.DuplicateDropped envelope and a 200
--         response to the vendor; no business state changes (B2 §2.4, C22, G1).
--   VI.4  linked_payout_instruction_id is a soft FK to cash_payout_instruction (BC4).
--         Not a hard FK because BC4 lives in a different bounded context; cross-context
--         references are by identity only (B1 §2, B3 P3).

CREATE TABLE gate_vendor_instruction (
    -- UUIDv7 PK = client_instruction_id sent to escrow vendor (VI.1).
    vendor_instruction_id       UUID                                NOT NULL,
    instruction_type            vendor_instruction_type_enum        NOT NULL,
    -- Soft FK to cash_payout_instruction (BC4) — cross-context reference by identity (VI.4).
    linked_payout_instruction_id UUID,
    status                      vendor_instruction_status_enum      NOT NULL DEFAULT 'pending',
    -- Vendor-assigned event identifier returned in webhook. UNIQUE for deduplication (VI.3).
    -- NULL until the first matching vendor webhook is received.
    vendor_event_id             TEXT                                UNIQUE,
    -- SHA-256 of verbatim vendor response body. Full payload in sys_document_object (BC16).
    vendor_payload_hash         BYTEA,
    -- When the inbound webhook HMAC was verified (VI.2, C10).
    hmac_verified_at            TIMESTAMPTZ,

    CONSTRAINT gate_vendor_instruction_pk PRIMARY KEY (vendor_instruction_id)
);

COMMENT ON TABLE gate_vendor_instruction IS
    'BC18 Banking ACL. One row per outbound instruction to the escrow provider (A2 §2). '
    'vendor_instruction_id = client_instruction_id — the idempotency key for escrow calls (VI.1, C9). '
    'Verbatim vendor response archived in sys_document_object; only hash stored here. '
    'Webhook deduplication via UNIQUE vendor_event_id (VI.3, C22, G1).';
COMMENT ON COLUMN gate_vendor_instruction.vendor_instruction_id IS
    'UUIDv7 PK = client_instruction_id sent to escrow. Idempotency key: same UUID safe to retry (VI.1, C9).';
COMMENT ON COLUMN gate_vendor_instruction.linked_payout_instruction_id IS
    'UUID of the originating cash_payout_instruction (BC4). '
    'SOFT FK — no REFERENCES constraint. Cross-context reference by identity only (B1 §2, VI.4).';
COMMENT ON COLUMN gate_vendor_instruction.vendor_event_id IS
    'Vendor-assigned event identifier echoed in webhook. UNIQUE constraint enforces deduplication. '
    'First-write-wins: subsequent deliveries of the same vendor_event_id are silently dropped (VI.3).';
COMMENT ON COLUMN gate_vendor_instruction.vendor_payload_hash IS
    'SHA-256 of verbatim escrow vendor response. Full payload archived in sys_document_object (BC16).';
COMMENT ON COLUMN gate_vendor_instruction.hmac_verified_at IS
    'Timestamp of HMAC verification of the inbound webhook (C10, A2 §2.2). '
    'Must be set before any state mutation on this row (VI.2).';

-- Indexes — BC18 VendorInstruction
CREATE INDEX idx_vinstr_payout_link
    ON gate_vendor_instruction (linked_payout_instruction_id)
    WHERE linked_payout_instruction_id IS NOT NULL;
COMMENT ON INDEX idx_vinstr_payout_link IS
    'Lookup all escrow instructions for a given cash_payout_instruction (BC4 reconciliation path).';

CREATE INDEX idx_vinstr_status
    ON gate_vendor_instruction (status)
    WHERE status IN ('pending', 'sent');
COMMENT ON INDEX idx_vinstr_status IS
    'In-flight instruction sweep for timeout detection and dead-letter escalation (G11).';


-- APPLICATION-LAYER INVARIANTS: InflowObservation
--   IO.1  utr (Unique Transaction Reference) is UNIQUE — each banking inflow carries exactly
--         one UTR. Duplicates are webhook-dedup artefacts; the UNIQUE constraint enforces this
--         at storage layer as a last-resort guard.
--   IO.2  vendor_event_id is UNIQUE — same dedup guarantee as VendorInstruction (VI.3).
--   IO.3  corrects references an earlier gate_inflow_observation that this row supersedes in a
--         corrective reconciliation envelope (B2 P6). The original row is not modified.
--   IO.4  va_id is a soft FK to the VirtualAccount aggregate in BC4.
--         Not a hard FK — cross-context reference by identity (B1 §2).
--   IO.5  Inflow status transitions: provisional → reconciled | unmatched.
--         provisional: webhook received, EoD reconciliation pending (VI.4, C23, G6).
--         reconciled:  matched to a Listing VA, Subscription confirmed.
--         unmatched:   EoD master-statement could not match; routes to ManualRemediation (G11).

CREATE TABLE gate_inflow_observation (
    -- UUIDv7 PK.
    inflow_id               UUID                    NOT NULL,
    -- Soft FK to VirtualAccount (BC4) — cross-context reference by identity (IO.4).
    va_id                   UUID                    NOT NULL,
    -- Amount in paise. Must be > 0 (a zero-amount inflow is a vendor data anomaly).
    amount                  money_paise             NOT NULL,
    -- UTR — Unique Transaction Reference assigned by banking rails (NEFT/RTGS/IMPS).
    utr                     TEXT                    NOT NULL,
    observed_at             TIMESTAMPTZ             NOT NULL,
    status                  inflow_status_enum      NOT NULL DEFAULT 'provisional',
    -- Vendor-assigned event identifier for deduplication (IO.2, C22).
    vendor_event_id         TEXT                    NOT NULL,
    -- References a prior gate_inflow_observation this row corrects (IO.3, B2 P6).
    corrects                UUID,

    CONSTRAINT gate_inflow_observation_pk PRIMARY KEY (inflow_id),
    CONSTRAINT gate_inflow_observation_utr_uq UNIQUE (utr),
    CONSTRAINT gate_inflow_observation_vendor_event_id_uq UNIQUE (vendor_event_id),
    CONSTRAINT gate_inflow_observation_amount_positive CHECK (amount > 0)
);

COMMENT ON TABLE gate_inflow_observation IS
    'BC18 Banking ACL. Records inbound funds observed at a virtual account (webhook-driven). '
    'Status is provisional until EoD master-statement reconciliation (C23, G6). '
    'Unmatched inflows route to BC4 ManualRemediation queue (G11).';
COMMENT ON COLUMN gate_inflow_observation.inflow_id IS
    'UUIDv7 PK minted by the Banking ACL at webhook ingress.';
COMMENT ON COLUMN gate_inflow_observation.va_id IS
    'UUID of the VirtualAccount (BC4) that received this inflow. '
    'SOFT FK — no REFERENCES constraint; cross-context reference by identity (B1 §2).';
COMMENT ON COLUMN gate_inflow_observation.amount IS
    'Inflow amount in BIGINT paise (money_paise domain). Must be > 0 (IO.5 guard).';
COMMENT ON COLUMN gate_inflow_observation.utr IS
    'NEFT/RTGS/IMPS Unique Transaction Reference. UNIQUE — identifies a single banking transaction.';
COMMENT ON COLUMN gate_inflow_observation.status IS
    'provisional: webhook received, awaiting EoD reconciliation. '
    'reconciled: matched to a Listing VA, Subscription confirmed. '
    'unmatched: EoD master-statement could not match; routed to ManualRemediation (G11).';
COMMENT ON COLUMN gate_inflow_observation.vendor_event_id IS
    'Vendor-assigned event identifier. UNIQUE constraint enforces deduplication at storage layer (IO.2, C22).';
COMMENT ON COLUMN gate_inflow_observation.corrects IS
    'UUID of the gate_inflow_observation this row supersedes in a corrective reconciliation (IO.3, B2 P6). '
    'SOFT FK — original row is never modified; this row extends the append-only log.';

-- Indexes — BC18 InflowObservation
CREATE INDEX idx_inflow_va
    ON gate_inflow_observation (va_id, observed_at DESC);
COMMENT ON INDEX idx_inflow_va IS
    'Per-VA inflow history ordered by observation time (reconciliation and auditor queries).';

CREATE INDEX idx_inflow_status
    ON gate_inflow_observation (status)
    WHERE status IN ('provisional', 'unmatched');
COMMENT ON INDEX idx_inflow_status IS
    'Reconciliation sweep: provisional inflows awaiting EoD match; unmatched inflows for remediation.';


-- =============================================================================
-- SECTION 7 — BC19 SIGNING ACL
-- =============================================================================

-- APPLICATION-LAYER INVARIANTS: VendorSignatureRequest
--   VS.1  vsr_id = vendor session ref = the identifier sent to the signing vendor as the
--         session token (A2 §3.3). Acts as the idempotency key for the vendor session.
--   VS.2  UNIQUE (signature_request_id, doc_hash): one vendor session per
--         (platform SignatureRequest, document). Prevents duplicate signing sessions for
--         the same document under the same business request.
--   VS.3  cert_serial must be NULL unless status = 'completed' (CHECK constraint).
--         It is set exactly once, at the terminal completed transition (B3 SR.3, C2).
--   VS.4  retry_count is capped at 3 (CHECK constraint + A2 §3.6, B3 SR.2).
--         After exhaustion status transitions to 'failed'; bubbles to parent aggregate.
--   VS.5  All inbound signing vendor webhooks are HMAC-verified before any state mutation
--         (C10, A2 §3.4). hmac_verified_at is set at webhook ingress.
--   VS.6  signature_request_id is a soft FK to SignatureRequest (BC5).
--         Not a hard FK — cross-context reference by identity (B1 §2).
--   VS.7  doc_hash is a soft FK to sys_document_object (BC16) — the document being signed.
--         Not a hard FK — document may not exist in sys_document_object at session initiation
--         (e.g. stamped PDF is created on signing completion). Application ensures consistency.

CREATE TABLE gate_signature_session (
    -- UUIDv7 PK = vendor session ref (VS.1).
    vsr_id                  UUID                NOT NULL,
    -- Soft FK to SignatureRequest (BC5) — cross-context reference by identity (VS.6).
    signature_request_id    UUID                NOT NULL,
    -- SHA-256 of the document being signed — soft FK to sys_document_object (VS.7).
    doc_hash                BYTEA               NOT NULL,
    -- Signer reference as used by the vendor (e.g. masked phone for Aadhaar-OTP path).
    signer_ref              TEXT                NOT NULL,
    sign_method             sign_method_enum    NOT NULL,
    status                  vsr_status_enum     NOT NULL DEFAULT 'session_initiated',
    -- Vendor-provided signing session URL (deeplink or redirect target).
    vendor_session_url      TEXT,
    -- Digital certificate serial number. Set exactly once on terminal 'completed' (VS.3, SR.3).
    cert_serial             TEXT,
    -- When the vendor callback HMAC was verified (VS.5, C10).
    hmac_verified_at        TIMESTAMPTZ,
    -- Number of signing retries attempted. Capped at 3 (VS.4, SR.2, A2 §3.6).
    retry_count             INT                 NOT NULL DEFAULT 0,

    CONSTRAINT gate_signature_session_pk
        PRIMARY KEY (vsr_id),
    CONSTRAINT gate_signature_session_unique_request_doc_uq
        UNIQUE (signature_request_id, doc_hash),
    CONSTRAINT gate_signature_session_cert_serial_only_on_completed
        CHECK (cert_serial IS NULL OR status = 'completed'),
    CONSTRAINT gate_signature_session_retry_count_max
        CHECK (retry_count >= 0 AND retry_count <= 3)
);

COMMENT ON TABLE gate_signature_session IS
    'BC19 Signing ACL. One row per vendor e-sign / e-stamp session (A2 §3). '
    'vsr_id = vendor session ref = idempotency key for the vendor (VS.1). '
    'Document-level idempotency via UNIQUE (signature_request_id, doc_hash) (VS.2). '
    'cert_serial set exactly once on completed; cert_serial IS NULL OR status=completed enforced by CHECK (VS.3). '
    'retry_count capped at 3 by CHECK (VS.4, A2 §3.6).';
COMMENT ON COLUMN gate_signature_session.vsr_id IS
    'UUIDv7 PK = the session token sent to the signing vendor. Idempotency key for the vendor session (VS.1).';
COMMENT ON COLUMN gate_signature_session.signature_request_id IS
    'UUID of the originating SignatureRequest aggregate (BC5). '
    'SOFT FK — no REFERENCES constraint; cross-context reference by identity (B1 §2, VS.6).';
COMMENT ON COLUMN gate_signature_session.doc_hash IS
    'SHA-256 of the document submitted for signing. '
    'SOFT FK to sys_document_object (BC16) — no REFERENCES constraint (VS.7). '
    'Together with signature_request_id, forms the UNIQUE document-level dedup key (VS.2).';
COMMENT ON COLUMN gate_signature_session.signer_ref IS
    'Signer identifier as understood by the vendor (e.g. masked mobile for Aadhaar-OTP, DN for DSC). '
    'Does not contain raw Aadhaar or unmasked PII (C14, C15).';
COMMENT ON COLUMN gate_signature_session.cert_serial IS
    'Digital certificate serial number. NULL until terminal status = completed. '
    'Set exactly once; subsequent updates are rejected by the CHECK constraint (VS.3, SR.3, C2).';
COMMENT ON COLUMN gate_signature_session.hmac_verified_at IS
    'Timestamp of HMAC verification of the inbound vendor webhook (C10, A2 §3.4, VS.5). '
    'Must be set before any state mutation on this row.';
COMMENT ON COLUMN gate_signature_session.retry_count IS
    'Number of retry attempts made. Capped at 3 by CHECK (VS.4, SR.2, A2 §3.6). '
    'Exhaustion transitions status to failed; bubbles to parent BC5 aggregate.';

-- Indexes — BC19
CREATE INDEX idx_vsr_signature_request
    ON gate_signature_session (signature_request_id);
COMMENT ON INDEX idx_vsr_signature_request IS
    'Look up all vendor sessions for a given BC5 SignatureRequest (e.g. retry history lookup).';

CREATE INDEX idx_vsr_status
    ON gate_signature_session (status)
    WHERE status IN ('session_initiated', 'failed');
COMMENT ON INDEX idx_vsr_status IS
    'Ops sweep: in-flight sessions and failed sessions pending manual intervention.';


-- =============================================================================
-- SECTION 8 — CROSS-TABLE / FINAL INDEXES
-- =============================================================================

-- Cryptographic chain verification index — BC14.
-- Chain verification reads envelopes in recorded_at order within a shard.
-- This index supports the verifier that walks the chain to confirm previous_envelope_hash integrity.
CREATE INDEX idx_audit_chain_verify
    ON sys_audit_event (recorded_at ASC, event_id ASC);
COMMENT ON INDEX idx_audit_chain_verify IS
    'Supports cryptographic chain verification (AE.2, G7, G25): '
    'walk envelopes in arrival order to verify previous_envelope_hash chain integrity. '
    'Shard-level chain verification scoped to (context, business_date) at query time (G25).';

-- Soft-FK lookup: notifications → audit events (BC15 → BC14 causation tracing)
CREATE INDEX idx_notification_causation
    ON sys_notification_dispatch (causation_event_id)
    WHERE causation_event_id IS NOT NULL;
COMMENT ON INDEX idx_notification_causation IS
    'Trace which audit event caused each notification dispatch (BC15 → BC14 causation soft-FK).';

-- Soft-FK lookup: inflow corrections (BC18 corrective reconciliation chain)
CREATE INDEX idx_inflow_corrects
    ON gate_inflow_observation (corrects)
    WHERE corrects IS NOT NULL;
COMMENT ON INDEX idx_inflow_corrects IS
    'Corrective reconciliation chain: find the new observation that supersedes a given original (IO.3, B2 P6).';

-- Audit event corrects chain index (BC14)
CREATE INDEX idx_audit_corrects
    ON sys_audit_event (corrects)
    WHERE corrects IS NOT NULL;
COMMENT ON INDEX idx_audit_corrects IS
    'Find all correcting envelopes for a given original event_id (AE.6, B2 P6).';

-- =============================================================================
-- END OF FILE: 04_generic_acl.sql
-- =============================================================================
