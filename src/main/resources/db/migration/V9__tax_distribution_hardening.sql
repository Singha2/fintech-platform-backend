-- V9 (DL-BE-067/069, M16 Tax hardening — code-review fixes) — two additive guards.
--
-- (1) One distribution per listing, DECLARATIVE. cash_payout_instruction had a partial unique index for
-- kind='disbursement' (V6) but NONE for kind='distribution'. DistributionService's one-per-listing rule was
-- therefore only a check-then-act app guard: two concurrent Treasury drafts (distinct command_ids → distinct
-- payout_instruction_id) both pass the count check and both INSERT, leaving two 'drafted' distributions. Since
-- escrow idempotency is keyed on payout_instruction_id, two different ids are NOT deduped → a real
-- (non-transactional) vendor could pay every investor twice. This mirrors V6 exactly: the DB is the last line
-- of defence; the app guard becomes a fast-path, DuplicateKeyException the backstop.
CREATE UNIQUE INDEX uidx_cash_payout_distribution_per_listing
    ON cash_payout_instruction (listing_id)
    WHERE kind = 'distribution';

COMMENT ON INDEX uidx_cash_payout_distribution_per_listing IS
    'M16/PI.3: at most one distribution instruction per listing (declarative one-per-listing, mirrors '
    'uidx_cash_payout_disbursement_per_listing). DL-BE-067.';

-- (2) Form 16A is a FROZEN artifact. Form16aService.download re-rendered the certificate from live
-- tax_year_profile cumulatives + tax_tds_deduction lines; a later same-FY distribution mutates both, so the
-- fresh hash diverged from the stored doc_hash and download threw (HTTP 500) for an already-issued certificate.
-- Persist the rendered bytes at issuance so download returns the immutable document (Phase-1 stub for
-- "binary in object storage"; sys_document_object remains the content-addressed registry). Nullable: existing
-- rows and the not-yet-generated monthly_portfolio statements carry no content.
ALTER TABLE tax_investor_statement ADD COLUMN doc_content BYTEA;

COMMENT ON COLUMN tax_investor_statement.doc_content IS
    'The rendered statement bytes, frozen at issuance (Phase-1 stub object store). SHA-256(doc_content) = '
    'doc_hash. download() returns this verbatim rather than re-deriving from mutable tables. DL-BE-069.';
