-- V6 (DL-BE-037, WS-7) — one disbursement instruction per listing (PI.2).
--
-- cash_payout_instruction lacked a uniqueness guard on (listing_id) for kind='disbursement': at most one
-- disbursement may exist per listing, but the table had no DB constraint enforcing it (unlike
-- legal_assignment_set's one-per-listing UNIQUE, AS.1). WS-7's application guard was check-then-insert,
-- which a concurrent double-draft (two command_ids, READ COMMITTED) could slip past — creating two drafted
-- payout instructions for one listing. A partial unique index makes the invariant declarative (the DB as
-- the last line of defence, per the Constitution), backing the application guard.
--
-- Hibernate ddl-auto=validate is unaffected (no JPA entity maps this table; access is native JdbcTemplate).
CREATE UNIQUE INDEX uidx_cash_payout_disbursement_per_listing
    ON cash_payout_instruction (listing_id)
    WHERE kind = 'disbursement';

COMMENT ON INDEX uidx_cash_payout_disbursement_per_listing IS
    'At most one disbursement payout instruction per listing (PI.2). Backs the WS-7 application guard '
    'against a concurrent double-draft (DL-BE-037).';
