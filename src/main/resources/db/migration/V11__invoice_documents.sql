-- V11 (M19, DL-BE-071) — BC1 layer-4: the invoice-artifact linkage over the BC16 document service (M18).
--
-- Binds a deal_invoice to a stored sys_document (BC16) with its own lifecycle. document_id is an IDENTITY
-- REFERENCE into sys_document, NOT a cross-BC foreign key (bounded-context isolation, ARCH.1 / B1) — the
-- listing context resolves bytes/metadata through DocumentPort only, mirroring how
-- sys_document_object.originating_aggregate_ref (V4) already does this without an FK.

CREATE TYPE deal_invoice_doc_status AS ENUM ('active', 'superseded');

CREATE TABLE deal_invoice_document (
    invoice_id     UUID NOT NULL REFERENCES deal_invoice(invoice_id),
    document_id    UUID NOT NULL,                    -- identity ref into BC16 sys_document; NO cross-BC FK
    status         deal_invoice_doc_status NOT NULL DEFAULT 'active',
    uploaded_by    UUID NOT NULL,                     -- maker (session identity_id); DOC.3 checks recorder ≠ this
    uploaded_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    superseded_by  UUID,                              -- replacement document_id when status='superseded'
    PRIMARY KEY (invoice_id, document_id)
);

-- DOC.1 — at most one active kind='invoice' artifact per invoice (declarative, not just check-then-act).
CREATE UNIQUE INDEX uidx_invoice_active_doc
    ON deal_invoice_document (invoice_id) WHERE status = 'active';

COMMENT ON TABLE deal_invoice_document IS
    'BC1 layer-4 linkage (M19): binds a deal_invoice to a BC16 document (document_id) with lifecycle. '
    'document_id is an IDENTITY REFERENCE into sys_document (BC16), not an FK — bounded-context isolation '
    '(no cross-BC FK; BC16 reached via DocumentPort). Bytes/encryption/dedup live in M18. DL-BE-071.';
COMMENT ON COLUMN deal_invoice_document.uploaded_by IS
    'The session identity_id that attached this document (the maker). DOC.3: the document_completeness '
    'ops-check recorder must differ from this value (maker ≠ checker, enforced in ListingService).';
COMMENT ON COLUMN deal_invoice_document.superseded_by IS
    'The replacement document_id once status=superseded (DOC.7) — the audit trail keeps both link rows.';
COMMENT ON INDEX uidx_invoice_active_doc IS
    'DOC.1: declaratively enforces at most one active invoice artifact per invoice.';
