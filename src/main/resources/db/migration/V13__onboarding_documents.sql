CREATE TYPE onboarding_subject_type AS ENUM ('investor', 'supplier');
CREATE TYPE kyc_doc_kind AS ENUM (
    'pan_card', 'address_proof', 'gst_certificate', 'certificate_of_incorporation',
    'board_resolution', 'moa_aoa', 'bank_statement', 'cancelled_cheque', 'photograph', 'other');
CREATE TYPE onboarding_doc_status AS ENUM ('active', 'superseded');

CREATE TABLE onboarding_doc_requirement (
    subject_type onboarding_subject_type NOT NULL,
    doc_kind     kyc_doc_kind NOT NULL,
    mandatory    BOOLEAN NOT NULL DEFAULT FALSE,   -- NOTHING mandatory in Phase 1 (Ops decides); M15 opt-in seam
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (subject_type, doc_kind)
);
INSERT INTO onboarding_doc_requirement (subject_type, doc_kind, mandatory, active) VALUES
    ('investor', 'pan_card',        FALSE, TRUE),
    ('investor', 'address_proof',   FALSE, TRUE),
    ('supplier', 'pan_card',        FALSE, TRUE),
    ('supplier', 'gst_certificate', FALSE, TRUE),
    ('supplier', 'board_resolution',FALSE, TRUE),
    ('supplier', 'moa_aoa',         FALSE, TRUE),
    ('supplier', 'bank_statement',  FALSE, TRUE);

CREATE TABLE kyc_document (
    kyc_document_id UUID PRIMARY KEY,
    kyc_file_id     UUID NOT NULL REFERENCES comp_kyc_file(kyc_file_id),
    document_id     UUID NOT NULL,                 -- identity ref into BC16 (OD.2 — no FK to sys_document)
    doc_kind        kyc_doc_kind NOT NULL,
    status          onboarding_doc_status NOT NULL DEFAULT 'active',
    uploaded_by     UUID NOT NULL,
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    superseded_by   UUID,
    UNIQUE (kyc_file_id, document_id)
);
CREATE UNIQUE INDEX uidx_kyc_active_kind ON kyc_document (kyc_file_id, doc_kind) WHERE status = 'active';

COMMENT ON COLUMN comp_kyc_file.doc_hashes IS
    'DEPRECATED (DL-BE-073): superseded by the typed kyc_document table (doc_kind + lifecycle). '
    'Never written by code; retained inert to avoid a needless migration. Do not use.';
