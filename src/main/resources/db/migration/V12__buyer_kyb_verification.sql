-- M20 buyer KYB (DL-BE-073, OD.8): a manual attestation on top of the automated identity_verified.
-- No typed buyer-document table, no doc_kind: at most ONE optional free-form custom document.
ALTER TABLE buyer_account
    ADD COLUMN kyb_verified     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN kyb_verified_by  UUID,                         -- ops_executive identity id who attested
    ADD COLUMN kyb_verified_at  TIMESTAMPTZ,
    ADD COLUMN kyb_document_id  UUID,                         -- optional single custom doc; identity ref into BC16 (OD.2 — no FK)
    ADD CONSTRAINT buyer_kyb_verified_stamped
        CHECK (kyb_verified = FALSE OR (kyb_verified_by IS NOT NULL AND kyb_verified_at IS NOT NULL));
