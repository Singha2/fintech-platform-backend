-- V5 — Audit Log (M2, DL-BE-015): make hash-chain forking declaratively impossible.
--
-- Each row chains off exactly one predecessor via previous_envelope_hash. A UNIQUE index on
-- (chain_shard, previous_envelope_hash) means two concurrent appends that both chain off the same
-- head collide here; AuditLog.append() catches the conflict and retries against the new head
-- (optimistic, no procedural lock — DL-BE-002). NULLS NOT DISTINCT makes the genesis row
-- (previous_envelope_hash IS NULL) unique per shard too, so a shard has exactly one first row.
CREATE UNIQUE INDEX uidx_audit_chain_link
    ON sys_audit_event (chain_shard, previous_envelope_hash) NULLS NOT DISTINCT;

COMMENT ON INDEX uidx_audit_chain_link IS
    'Per-shard chain linearity (M2): at most one row may chain off a given predecessor (incl. the '
    'NULL genesis). Concurrent appends conflict here and retry. See DL-BE-015.';
