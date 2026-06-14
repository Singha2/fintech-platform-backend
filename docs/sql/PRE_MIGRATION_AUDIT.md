# Pre-Migration SQL Audit — `docs/sql/` bundle

> **Status:** complete · **Date:** 2026-06-13 · **Gate:** Phase 0 / M0, before any Flyway migration is written.
> **Method:** 15-agent multi-agent audit (Opus) — 4 per-file inventory readers → 10 parallel dimension auditors → 1 completeness critic. 1.27M tokens.
> **Scope:** 52 tables across `01_core.sql`, `02_counterparty_platform.sql`, `03_auth.sql`, `04_generic_acl.sql`.

This audit runs **before** porting the bundle to versioned Flyway migrations, because once `V1__*.sql` is applied with `baseline-on-migrate=true`, a missed constraint is expensive to fix. The DB is the *last line of defence* (CLAUDE.md), so every gap below is a place the schema fails to catch bad state.

## Decisions baked into this audit
- **No procedural DB triggers/functions.** Per user decision: keep the DB layer simple and debuggable. Enforce invariants **declaratively** (CHECK / FK / NOT NULL / UNIQUE / domain types / enums) and via **GRANT/REVOKE**, never `plpgsql` triggers. The bundle's *only* procedural artifact (the audit-immutability trigger in `04`) is flagged for removal — see AUDIT-1.
- **`updated_at` is app-owned.** No `set_updated_at()` trigger. Risk: native-SQL / jOOQ write paths that bypass JPA lifecycle callbacks must set `updated_at = now()` explicitly. Tracked as a `DL-BE` decision + an ArchUnit/test guard.
- **Maximize the declarative DB layer.** Every fix below prefers DDL the database enforces over app-only checks.

## Summary

| Severity | Count |
|---|---|
| Fix now | 5 |
| High | 23 |
| Medium | 59 |
| Low | 36 |
| Info | 20 |
| **Total** | **143** |

| Dimension | Findings |
|---|---|
| Domain-type consistency | 13 |
| Conditional-nullability CHECK constraints | 23 |
| Five non-negotiables coverage | 13 |
| Audit chain | 8 |
| Money/rate/time primitives | 12 |
| FK integrity, cross-file load order, and migration split | 7 |
| Bounded-context purity | 15 |
| Indexes | 26 |
| Enum completeness vs state machines | 9 |
| Manifest accuracy | 7 |
| Completeness critic | 10 |

---

## 🔴 Fix now (blockers before V1)

**FN1. [Fix now]** 01_core.sql · cash_payout_instruction · kind / listing_id / subscription_id

- **Issue:** kind discriminates the owning aggregate: kind in {disbursement, distribution} requires listing_id (subscription_id must be NULL); kind=refund requires subscription_id (listing_id must be NULL) — see lines 1045-1047 comments and column comments at 1095-1098. Both columns are plain nullable UUIDs with FKs but NO CHECK ties them to kind. A refund row with no subscription_id, a disbursement with no listing_id, or a row carrying both, is silently accepted. This is a money-movement aggregate; the discriminator must be enforced as the last line of defence.
- **Fix:** ALTER TABLE cash_payout_instruction ADD CONSTRAINT cash_payout_instruction_kind_target_chk CHECK ( (kind IN ('disbursement','distribution') AND listing_id IS NOT NULL AND subscription_id IS NULL) OR (kind = 'refund' AND subscription_id IS NOT NULL AND listing_id IS NULL) );
- **Evidence:** lines 1042-1080: `kind cash_payout_kind NOT NULL`, `listing_id UUID,`, `subscription_id UUID,` with FKs but only maker_ne_checker + amount CHECKs.

**FN2. [Fix now]** 01_core.sql, 02_counterparty_platform.sql, 04_generic_acl.sql · ALL state-changing aggregates (platform-wide) · command_id

- **Issue:** Non-negotiable #4 (idempotent on command_id, G18) has ZERO declarative footprint. `command_id` is not a column on any aggregate, not on sys_audit_event, and there is no producer-dedup table with UNIQUE(actor_id, command_id). Spec B2 §2.1 + G18 require every command-originated event to carry command_id and producers to dedupe on (actor_id, command_id) before mutating state. Nothing in the DB enforces or even records this, so command replay is not a guaranteed no-op at the last line of defence.
- **Fix:** Add a producer-dedup table, e.g. CREATE TABLE sys_command_log (actor_id UUID NOT NULL, command_id UUID NOT NULL, command_type TEXT NOT NULL, aggregate_type TEXT NOT NULL, aggregate_id UUID NOT NULL, first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(), resulting_event_id UUID, CONSTRAINT sys_command_log_pk PRIMARY KEY (actor_id, command_id)); command handlers INSERT ... ON CONFLICT DO NOTHING and treat conflict as replay. Also add `command_id UUID` to sys_audit_event (NOT NULL for command-originated event_types, NULL for scheduler/webhook per B2 line 44) so the envelope records the idempotency key declaratively.
- **Evidence:** grep -c command_id over all four .sql files = 0; spec 09_B3 line 23 (P8) and line 331 (PI.1) and 08_B2 line 44 reference command_id as the idempotency key.

**FN3. [Fix now]** 04_generic_acl.sql · sys_audit_event · envelope_hash

- **Issue:** The self-hash / tamper-evidence column `envelope_hash` is entirely ABSENT from the table, even though the AE.2 comment (line 113) describes it and the spec mandates it: B2 §2.1 (08_B2_Event_Model.md line 51: 'envelope_hash bytes32 yes - SHA-256 over canonical encoding of this envelope minus envelope_hash itself. Tamper-evidence'), B3 AE.2 (09_B3_Aggregates.md line 1237), and B4 (10_B4_API_Conventions.md line 345: 'computing previous_envelope_hash AND envelope_hash'). With only `previous_envelope_hash` and no stored self-hash, the chain cannot be verified: row N+1's previous_envelope_hash references row N but row N stores no hash of itself to compare against. This silently defeats tamper-evidence, the entire purpose of the audit chain non-negotiable.
- **Fix:** Add the column: `envelope_hash BYTEA NOT NULL` to sys_audit_event (computed app-side over the canonical encoding of the row minus envelope_hash itself, per AE.2). Make it NOT NULL (every persisted envelope must be self-hashed) and add `CONSTRAINT sys_audit_event_envelope_hash_len CHECK (octet_length(envelope_hash) = 32)`. Optionally `UNIQUE (envelope_hash)` to catch accidental duplicate inserts. Apply the same length CHECK to previous_envelope_hash: `CHECK (previous_envelope_hash IS NULL OR octet_length(previous_envelope_hash) = 32)`.
- **Evidence:** Line 163 declares only `previous_envelope_hash BYTEA,` — grep for 'envelope_hash' in 04_generic_acl.sql returns only previous_envelope_hash references; no `envelope_hash` column exists. Spec: 08_B2_Event_Model.md:51 and 09_B3_Aggregates.md:1237.

**FN4. [Fix now]** 04_generic_acl.sql · sys_audit_event · (table-level / trigger)

- **Issue:** Append-only (AE.1) is enforced by a plpgsql trigger + function (prevent_audit_modification / fn_prevent_audit_modification), which directly violates the project NO-TRIGGER / keep-the-DB-simple policy. CLAUDE.md SCHEMA INDEX explicitly flags this as 'the existing trigger/function in 04_generic_acl.sql should be flagged for reconsideration.' Immutability can be enforced DECLARATIVELY for the application's least-privilege role via GRANT/REVOKE, which is debuggable and policy-compliant.
- **Fix:** Drop the trigger and function. Replace with a declarative privilege grant in a migration: `REVOKE UPDATE, DELETE, TRUNCATE ON sys_audit_event FROM platform;` and `GRANT INSERT, SELECT ON sys_audit_event TO platform;` (where `platform` is the app role). Note this does not block a superuser/table-owner; the spec already names WORM substrate (G7) as the complementary control for owner/superuser/pg_dump paths (lines 220-221, 243). Add a DL-BE decision log entry recording trigger->REVOKE swap.
- **Evidence:** Lines 222-244: `CREATE OR REPLACE FUNCTION fn_prevent_audit_modification() ... RAISE EXCEPTION ...` and `CREATE TRIGGER prevent_audit_modification BEFORE UPDATE OR DELETE ON sys_audit_event FOR EACH ROW EXECUTE FUNCTION ...`.

**FN5. [Fix now]** SQL_FILES_MANIFEST.md · auth_identity, admin_user, inv_account, buyer_ack_user

- **Issue:** The 'Execution Order Rationale' (lines 50-52) is factually false and the load reason it gives is fabricated. It claims 03_auth.sql 'adds foreign-key constraints on tables (tblAdminUser, tblInvestorAccount, tblAcknowledgmentUser) that are created by 02_counterparty_platform.sql'. (1) Those table names do not exist anywhere — the real names are admin_user, inv_account, buyer_ack_user. (2) 03_auth.sql adds NO FK onto any 02 table; every FK in 03 targets its own auth_identity. (3) The real cross-file direction is the opposite and is a SOFT (un-constrained) reference: 02's tables carry identity_id with no REFERENCES clause. (4) There are ZERO hard inter-file FK constraints in the whole bundle.
- **Fix:** Rewrite the rationale to: 'Run 01_core.sql FIRST. It is the only hard cross-file dependency: SECTION 0 of 01_core defines the shared DOMAIN value-object types (pan_type, gstin_type, ifsc_type, irn_type, aadhaar_last4_type, money_paise, positive_money_paise, bps_type) and the core enums that 02 and 04 use in column definitions; running them before 01 fails with "type does not exist". Files 02, 03, 04 contain only intra-file FK constraints (03_auth FKs all target its own auth_identity; 04 has a single self-FK sys_audit_event.corrects) and otherwise reference other contexts via SOFT identity_id columns with no REFERENCES clause, so among themselves they have no hard ordering requirement. The canonical sequence is 01 -> 02 -> 03 -> 04 (matching the order baked into 04''s own header comment).' When ported to Flyway, this maps to V1__core, V2__counterparty_platform, V3__auth, V4__generic_acl, and the dependency is type-availability, not FK ordering.
- **Evidence:** Manifest:52. 03_auth.sql REFERENCES grep: all 7 hits target auth_identity(identity_id) (lines 138,180,206,250,302,312,322) — none target 02. 02_counterparty_platform.sql REFERENCES grep: every FK is intra-file (inv_invite, inv_account, sup_account, buyer_account, buyer_payment_rule, admin_user) — zero into auth/01/04. 04 self-FK only (sys_audit_event.corrects, line 159). No tbl* identifier exists in any .sql file.

---

## Findings by dimension

### Domain-type consistency  (13)

**1. [High]** 01_core.sql · risk_pricing_policy · rate_range_min_bps, rate_range_max_bps, fee_bps

- **Issue:** All three bps columns declared as raw INT (lines 816, 817, 819) instead of the bps_type domain. Confirms the earlier review flag on rate_range_min_bps and fee_bps, and adds rate_range_max_bps as a third instance. Raw INT permits values >100000 (>1000%) and negative rates absent the hand-rolled CHECKs; the rate_range_max_bps column in particular has NO upper-bound CHECK at all (only the min<=max and min>0 checks exist), so a max of 9,999,999 bps would be accepted.
- **Fix:** Declare all three as bps_type: rate_range_min_bps bps_type NOT NULL, rate_range_max_bps bps_type NOT NULL, fee_bps bps_type NOT NULL. The domain's CHECK(VALUE>=0 AND VALUE<=100000) then gives every column a uniform 0..100000 bound (closing the missing upper bound on rate_range_max_bps and fee_bps). Keep the table-level CHECKs that express RELATIONSHIPS the domain cannot (rate_range_min_bps>0 strict-positivity, rate_range_min_bps<=rate_range_max_bps); drop the now-redundant fee_bps>=0 (subsumed by the domain).
- **Evidence:** 01_core.sql:816 rate_range_min_bps INT NOT NULL; :817 rate_range_max_bps INT NOT NULL; :819 fee_bps INT NOT NULL. CHECKs at :833-836 cover min>0 and min<=max and fee>=0 but no upper bound on any.

**2. [High]** 02_counterparty_platform.sql · tax_year_profile · tds_rate_bps

- **Issue:** TDS rate stored as raw INT (line 1212) instead of bps_type, the lone domain-type miss in 02_counterparty (which otherwise uses money_paise/positive_money_paise everywhere). Raw INT with only a >=0 CHECK permits an absurd >1000% TDS rate. A miskeyed rate directly distorts net payout via the gross-tds-fee=net invariant.
- **Fix:** Declare tds_rate_bps bps_type NOT NULL DEFAULT 0. The domain bound (0..100000) replaces the standalone >=0 CHECK and adds the missing upper bound; drop tax_year_profile_tds_rate_nonneg as redundant.
- **Evidence:** 02_counterparty_platform.sql:1212 tds_rate_bps INT NOT NULL DEFAULT 0; :1213 CONSTRAINT tax_year_profile_tds_rate_nonneg CHECK (tds_rate_bps >= 0)

**3. [High]** 01_core.sql · deal_invoice · face_value

- **Issue:** Invoice face value declared raw BIGINT (line 339) instead of positive_money_paise, despite the comment explicitly saying 'stored in paise (BIGINT)'. A separate CHECK(face_value>0) re-implements exactly what the positive_money_paise domain guarantees.
- **Fix:** face_value positive_money_paise NOT NULL; drop the redundant deal_invoice_face_value_positive CHECK (the domain enforces >0).
- **Evidence:** 01_core.sql:339 face_value BIGINT NOT NULL; :352 CONSTRAINT deal_invoice_face_value_positive CHECK (face_value > 0)

**4. [Medium]** 01_core.sql · deal_listing · buyer_limit_headroom_snapshot, supplier_exposure_cap_snapshot, funding_target, committed_total

- **Issue:** Four paise columns declared raw BIGINT (lines 450-455) with NO domain type and NO non-negativity CHECK. funding_target and committed_total in particular drive the funding-equality invariant; a negative committed_total would silently corrupt funding state with no DB defence.
- **Fix:** Type each as money_paise (nullable snapshots keep nullability; committed_total money_paise NOT NULL DEFAULT 0). funding_target should be positive_money_paise if a frozen target of 0 is invalid, else money_paise. This adds the missing >=0 floor that is currently entirely absent.
- **Evidence:** 01_core.sql:450 buyer_limit_headroom_snapshot BIGINT; :451 supplier_exposure_cap_snapshot BIGINT; :452 funding_target BIGINT; :455 committed_total BIGINT NOT NULL DEFAULT 0

**5. [Medium]** 01_core.sql · sub_subscription · amount, expected_inflow_amount

- **Issue:** Subscription amount and expected inflow declared raw BIGINT (lines 582, 586) with no domain type and no non-negativity CHECK at the DB level (the Rs 10,000 minimum is noted as app-layer S.1 only).
- **Fix:** amount positive_money_paise NOT NULL; expected_inflow_amount positive_money_paise NOT NULL (an expected inflow of 0 for a committed subscription is nonsensical). Domain adds the missing >0 floor; the Rs 10,000 business minimum stays app-layer.
- **Evidence:** 01_core.sql:582 amount BIGINT NOT NULL; :586 expected_inflow_amount BIGINT NOT NULL

**6. [Medium]** 01_core.sql · risk_buyer_profile · credit_limit

- **Issue:** Credit limit declared raw BIGINT (line 701) with a hand-rolled CHECK(credit_limit>0) re-implementing the positive_money_paise domain.
- **Fix:** credit_limit positive_money_paise NOT NULL; drop the redundant risk_buyer_profile_credit_limit_positive CHECK.
- **Evidence:** 01_core.sql:701 credit_limit BIGINT NOT NULL; :717 CONSTRAINT risk_buyer_profile_credit_limit_positive CHECK (credit_limit > 0)

**7. [Medium]** 01_core.sql · risk_supplier_profile · exposure_cap

- **Issue:** Exposure cap declared raw BIGINT (line 763); invariant SCP.1 (exposure_cap>=0) is left as an app-layer comment with no DB CHECK or domain type, so a negative cap would currently be accepted by the database.
- **Fix:** exposure_cap money_paise NOT NULL — the domain's VALUE>=0 enforces SCP.1 declaratively, which is currently enforced nowhere in DDL.
- **Evidence:** 01_core.sql:763 exposure_cap BIGINT NOT NULL (comment 'exposure_cap in paise. >= 0' at :762 but no CHECK in the table body)

**8. [Medium]** 01_core.sql · cash_virtual_account · expected_inflow_total, observed_inflow_total

- **Issue:** Running inflow ledger totals declared raw BIGINT (lines 969-970) with no domain type and no non-negativity CHECK. These feed reconciliation (V.2/C23/G6); a negative running total has no DB defence.
- **Fix:** Type both as money_paise NOT NULL DEFAULT 0 to add the >=0 floor declaratively.
- **Evidence:** 01_core.sql:969 expected_inflow_total BIGINT NOT NULL DEFAULT 0; :970 observed_inflow_total BIGINT NOT NULL DEFAULT 0

**9. [Medium]** 01_core.sql · cash_payout_instruction · gross_amount, net_amount, fee_amount, total_tds_amount

- **Issue:** Four money columns declared raw BIGINT (lines 1052-1055) with hand-rolled CHECKs re-implementing the domains (gross>0, net>0, fee>=0, tds>=0 or NULL).
- **Fix:** gross_amount positive_money_paise NOT NULL; net_amount positive_money_paise NOT NULL; fee_amount money_paise NOT NULL; total_tds_amount money_paise (nullable). Drop the redundant *_gross_positive, *_net_positive, *_fee_nonneg, *_tds_nonneg CHECKs — all subsumed by the domains.
- **Evidence:** 01_core.sql:1052-1055 gross_amount/net_amount/fee_amount BIGINT NOT NULL, total_tds_amount BIGINT; CHECKs at :1076-1079

**10. [Medium]** 01_core.sql · col_maturity_case · recovery_total

- **Issue:** Running recovery total declared raw BIGINT (line 1490) with no domain type and no non-negativity CHECK.
- **Fix:** recovery_total money_paise NOT NULL DEFAULT 0 to add the >=0 floor declaratively.
- **Evidence:** 01_core.sql:1490 recovery_total BIGINT NOT NULL DEFAULT 0

**11. [Medium]** 01_core.sql · col_claim_case · supplier_indemnity_amount

- **Issue:** Indemnity amount declared raw BIGINT (line 1604) with a hand-rolled nullable >=0 CHECK re-implementing the money_paise domain.
- **Fix:** supplier_indemnity_amount money_paise (nullable); drop the redundant col_claim_case_indemnity_nonneg CHECK (domain enforces >=0 on non-null values).
- **Evidence:** 01_core.sql:1604 supplier_indemnity_amount BIGINT; :1615-1616 CONSTRAINT col_claim_case_indemnity_nonneg CHECK (supplier_indemnity_amount IS NULL OR supplier_indemnity_amount >= 0)

**12. [Low]** 04_generic_acl.sql · gate_inflow_observation · amount

- **Issue:** Correctly uses money_paise domain but then re-adds CHECK(amount>0) (line 609) because the IO.5 guard wants strictly-positive. positive_money_paise would express this intent directly and drop the extra CHECK. Minor — flagged for consistency, not correctness.
- **Fix:** Optionally switch to amount positive_money_paise NOT NULL and drop the gate_inflow_observation_amount_positive CHECK. (No data-integrity gap today; purely domain-intent consistency.)
- **Evidence:** 04_generic_acl.sql:596 amount money_paise NOT NULL; :609 CONSTRAINT gate_inflow_observation_amount_positive CHECK (amount > 0)

**13. [Info]** 01_core.sql · deal_listing / sub_subscription / cash_payout_instruction · pricing_snapshot, distribution_outcome, payload

- **Issue:** Paise and bps values embedded inside JSONB (pricing_snapshot.rate_bps/fee_bps at :448; distribution_outcome {gross,tds,fee,net} at :592; payout payload {gross,net,fee,total_tds} at :1049) receive NO domain-type protection — JSONB scalars cannot carry money_paise/bps_type. The S.7 paise-equality CHECK at :622-631 casts to ::BIGINT inline. This is inherent to the JSONB choice, not a fixable column-typing miss, but it means a chunk of the platform's money/rate values sit entirely outside the domain-type safety net and rely solely on app-layer + the one equality CHECK.
- **Fix:** No declarative DDL fix for JSONB-embedded scalars. Mitigation: keep the value-equality CHECKs (already present for distribution_outcome), and consider promoting frequently-validated snapshot scalars (e.g. pricing_snapshot.rate_bps) to typed columns if invariants over them grow. Flag as app-layer responsibility.
- **Evidence:** 01_core.sql:448 pricing_snapshot JSONB; :592-594 distribution_outcome JSONB; :622-631 CHECK casting (distribution_outcome->>'gross')::BIGINT etc.


### Conditional-nullability CHECK constraints  (23)

**1. [Fix now]** 01_core.sql · cash_payout_instruction · kind / listing_id / subscription_id

- **Issue:** kind discriminates the owning aggregate: kind in {disbursement, distribution} requires listing_id (subscription_id must be NULL); kind=refund requires subscription_id (listing_id must be NULL) — see lines 1045-1047 comments and column comments at 1095-1098. Both columns are plain nullable UUIDs with FKs but NO CHECK ties them to kind. A refund row with no subscription_id, a disbursement with no listing_id, or a row carrying both, is silently accepted. This is a money-movement aggregate; the discriminator must be enforced as the last line of defence.
- **Fix:** ALTER TABLE cash_payout_instruction ADD CONSTRAINT cash_payout_instruction_kind_target_chk CHECK ( (kind IN ('disbursement','distribution') AND listing_id IS NOT NULL AND subscription_id IS NULL) OR (kind = 'refund' AND subscription_id IS NOT NULL AND listing_id IS NULL) );
- **Evidence:** lines 1042-1080: `kind cash_payout_kind NOT NULL`, `listing_id UUID,`, `subscription_id UUID,` with FKs but only maker_ne_checker + amount CHECKs.

**2. [High]** 01_core.sql · legal_master_agreement · status / signature_cert_serial / stamp_cert_id / failed_reason

- **Issue:** status is the discriminator (initiated -> signed -> stamped; or failed). Per comments MA.3 (lines 1263-1267), signature_cert_serial is set only on signed/stamped, stamp_cert_id only on stamped, and failed_reason belongs to failed. None of these companion columns are gated: a row can be status='initiated' yet carry a stamp_cert_id, or status='stamped' with a NULL signature_cert_serial, or status='failed' with NULL failed_reason. These feed legal/audit evidence (C1, C2), so the DB should enforce co-presence.
- **Fix:** ADD CONSTRAINT legal_master_agreement_status_shape_chk CHECK ( (status IN ('initiated','failed') OR signature_cert_serial IS NOT NULL) -- signed/stamped must have signature cert AND (status = 'stamped' OR stamp_cert_id IS NULL)            -- stamp cert only when stamped AND (status = 'failed' OR failed_reason IS NULL)             -- failed_reason only when failed AND (status <> 'failed' OR failed_reason IS NOT NULL) );
- **Evidence:** lines 1256-1274: signature_cert_serial TEXT, stamp_cert_id TEXT, failed_reason TEXT — table has only a PK constraint, no CHECKs at all.

**3. [High]** 01_core.sql · cash_remediation_case · status / linked_corrective_event_id / resolution_doc_hash

- **Issue:** status='resolved' must carry linked_corrective_event_id (RC.1, lines 1187-1188, 1227-1229: 'MUST be set before status can transition to resolved') and a resolution_doc_hash. Table has zero CHECKs, so a case can be marked 'resolved' with both fields NULL — exactly the silent-drop of a failed payout leg the spec warns against (G11, G23). Single-row, declaratively enforceable.
- **Fix:** ADD CONSTRAINT cash_remediation_case_resolved_shape_chk CHECK ( status <> 'resolved' OR (linked_corrective_event_id IS NOT NULL AND resolution_doc_hash IS NOT NULL) );
- **Evidence:** lines 1194-1212: status default 'open'; resolution_doc_hash TEXT; linked_corrective_event_id TEXT; only PRIMARY KEY constraint.

**4. [High]** 01_core.sql · risk_default_case · status / outcome / classified_at

- **Issue:** status='classified' is the terminal discriminator: outcome and classified_at must be set then, and conversely outcome/classified_at should be NULL while status in {requested, under_adjudication}. Comment (lines 923-925) states outcome is 'Set exactly once on Classify command. NULL until classified.' Only maker_ne_checker CHECK exists; nothing ties outcome/classified_at to status. A 'requested' row can carry an outcome, or a 'classified' row can have NULL outcome.
- **Fix:** ADD CONSTRAINT risk_default_case_classified_shape_chk CHECK ( (status = 'classified' AND outcome IS NOT NULL AND classified_at IS NOT NULL) OR (status <> 'classified' AND outcome IS NULL AND classified_at IS NULL) );
- **Evidence:** lines 886-914: outcome risk_default_outcome (nullable), classified_at TIMESTAMPTZ (nullable); only risk_default_case_maker_ne_checker CHECK present.

**5. [Medium]** 01_core.sql · cash_virtual_account · status / ifsc / account_no / created_at_bank / closed_at_bank

- **Issue:** status discriminates VA lifecycle (requested -> created -> closed). Bank coordinates (ifsc, account_no, created_at_bank) are only meaningful once 'created'; closed_at_bank only once 'closed' (V.3, lines 952-953). All are nullable with no gating: a 'requested' VA can carry an account number, or a 'closed' VA can have NULL closed_at_bank. Note ifsc is plain TEXT (separate finding-worthy: should be ifsc_type domain), but for this dimension the gap is the missing status linkage.
- **Fix:** ADD CONSTRAINT cash_virtual_account_status_shape_chk CHECK ( (status <> 'requested' OR (ifsc IS NULL AND account_no IS NULL AND created_at_bank IS NULL AND closed_at_bank IS NULL)) AND (status <> 'closed' OR (ifsc IS NOT NULL AND account_no IS NOT NULL AND created_at_bank IS NOT NULL AND closed_at_bank IS NOT NULL)) );  -- if 'created' is allowed to predate bank confirmation, relax the 'created' clause; tighten per BC4 state machine.
- **Evidence:** lines 959-983: ifsc TEXT, account_no TEXT, created_at_bank TIMESTAMPTZ, closed_at_bank TIMESTAMPTZ — only PK, listing FK/uq, and inflow nonneg CHECKs.

**6. [Medium]** 04_generic_acl.sql · gate_verification · status / failure_class / vendor_payload_hash / ttl_until

- **Issue:** status discriminates the verification lifecycle. Per comments (lines 452-453, V.6) failure_class classifies failed/manual_fallback rows, so it should be NOT NULL for status in {failed, manual_fallback} and arguably NULL otherwise. gate_signature_session already models the analogous rule declaratively (cert_serial IS NULL OR status='completed') — gate_verification does not, having zero CHECKs. A 'completed' row can carry a failure_class; a 'failed' row can have NULL failure_class.
- **Fix:** ADD CONSTRAINT gate_verification_failure_class_shape_chk CHECK ( (status IN ('failed','manual_fallback') AND failure_class IS NOT NULL) OR (status NOT IN ('failed','manual_fallback') AND failure_class IS NULL) );
- **Evidence:** lines 436-460: status default 'requested'; failure_class TEXT (nullable); no table CHECK constraints at all.

**7. [Medium]** 01_core.sql · deal_listing · status / terminal_outcome / golive_checker_id / golive_mfa_assertion_id

- **Issue:** Two discriminator-linked gaps. (1) terminal_outcome is 'Populated only on Close' (line 526) — i.e. should be NOT NULL exactly when status='closed' and NULL otherwise; currently ungated, so a 'live' listing could carry a terminal_outcome or a 'closed' one could have NULL. (2) GoneLive maker-checker: when golive_checker_id is set, golive_mfa_assertion_id must accompany it (L.4, C7) and golive_maker_id must be present; the existing maker_ne_checker CHECK (lines 485-488) only guards inequality, not co-presence of the MFA assertion. status='closed' is enum-only so the terminal_outcome linkage is single-row enforceable; the live/closed mapping for golive fields is partly cross-event so flagged conservatively.
- **Fix:** ADD CONSTRAINT deal_listing_terminal_outcome_shape_chk CHECK ( (status = 'closed' AND terminal_outcome IS NOT NULL) OR (status <> 'closed' AND terminal_outcome IS NULL) ); ADD CONSTRAINT deal_listing_golive_assertion_chk CHECK ( golive_checker_id IS NULL OR (golive_maker_id IS NOT NULL AND golive_mfa_assertion_id IS NOT NULL) );  -- verify against BC1 state machine: if terminal_outcome may be set at the terminal-branch states before final 'closed', widen the status set accordingly.
- **Evidence:** lines 464-470, 485-489, 526: terminal_outcome deal_terminal_outcome (nullable); golive_mfa_assertion_id TEXT (nullable); only deal_listing_golive_maker_ne_checker present.

**8. [Medium]** 01_core.sql · cash_payout_instruction · status / checker_id / checker_mfa_assertion_id

- **Issue:** Separate from the kind/target gap above: status discriminates approval (drafted -> approved -> ...). Per PI.5 the approved step requires a checker and a valid MFA assertion (lines 1025-1028, 1117-1118). When checker_id is set, checker_mfa_assertion_id must be co-present (C7 non-negotiable: MFA-fresh). The existing maker_ne_checker CHECK guards inequality only; co-presence of the MFA assertion is ungated, so a checker can be recorded with no MFA assertion. Single-row co-presence is declaratively enforceable; the full 'status>=approved implies checker present' edge is partly state-machine but the co-presence half is safe to enforce.
- **Fix:** ADD CONSTRAINT cash_payout_instruction_checker_mfa_chk CHECK ( checker_id IS NULL OR checker_mfa_assertion_id IS NOT NULL );
- **Evidence:** lines 1057-1059, 1074-1075: checker_id UUID (nullable), checker_mfa_assertion_id TEXT (nullable); only cash_payout_instruction_maker_ne_checker CHECK.

**9. [Medium]** 01_core.sql · risk_buyer_profile · four_eyes_approval_ref / second_approver_id

- **Issue:** Not a status discriminator but a mutually-dependent (co-presence) pair: four_eyes_approval_ref and second_approver_id are the two halves of the four-eyes envelope required above Rs 1 Cr (BCP.2, lines 706-710, 733-740). The value-threshold half (credit_limit > 10,000,000,000 implies these are set) is genuinely cross-policy and correctly left to the app layer. But the two columns should never be half-populated: if one is set the other must be too. No CHECK enforces this co-presence today.
- **Fix:** ADD CONSTRAINT risk_buyer_profile_four_eyes_pair_chk CHECK ( (four_eyes_approval_ref IS NULL) = (second_approver_id IS NULL) );
- **Evidence:** lines 706-710: four_eyes_approval_ref TEXT, second_approver_id UUID — table has only credit_limit_positive and tenor_cap_range CHECKs.

**10. [Medium]** 01_core.sql · risk_supplier_profile · four_eyes_approval_ref / second_approver_id

- **Issue:** Same co-presence gap as risk_buyer_profile: the four-eyes pair (SCP.2, lines 766-768, 787-789) can be half-populated. Value-threshold requirement is app-layer; intra-row co-presence is cheaply declarative.
- **Fix:** ADD CONSTRAINT risk_supplier_profile_four_eyes_pair_chk CHECK ( (four_eyes_approval_ref IS NULL) = (second_approver_id IS NULL) );
- **Evidence:** lines 766-768: four_eyes_approval_ref TEXT, second_approver_id UUID; only exposure_cap_nonneg CHECK present.

**11. [Medium]** 01_core.sql · risk_default_case · four_eyes_approval_ref / second_approver_id

- **Issue:** Same four-eyes co-presence pair (DC.2, lines 894-896, 926-928). The 'required when exposure > Rs 1 Cr' is cross-aggregate (depends on listing exposure) and stays in the app layer, but the two columns must move together. No CHECK today.
- **Fix:** ADD CONSTRAINT risk_default_case_four_eyes_pair_chk CHECK ( (four_eyes_approval_ref IS NULL) = (second_approver_id IS NULL) );
- **Evidence:** lines 894-896: four_eyes_approval_ref TEXT, second_approver_id UUID; only maker_ne_checker CHECK.

**12. [Medium]** 02_counterparty_platform.sql · tax_year_profile · form_16a_issued / form_16a_doc_hash / form_16a_issued_at

- **Issue:** form_16a_issued (BOOLEAN) is a discriminator over the form-16A companion columns. When form_16a_issued=TRUE, both form_16a_doc_hash and form_16a_issued_at must be present; when FALSE both should be NULL. No CHECK enforces this, so the flag can be TRUE with NULL doc hash (no evidence of the issued statement) — a compliance/tax-evidence gap (G12).
- **Fix:** ADD CONSTRAINT tax_year_profile_form16a_shape_chk CHECK ( (form_16a_issued = TRUE AND form_16a_doc_hash IS NOT NULL AND form_16a_issued_at IS NOT NULL) OR (form_16a_issued = FALSE AND form_16a_doc_hash IS NULL AND form_16a_issued_at IS NULL) );
- **Evidence:** lines 1220-1222: form_16a_issued BOOLEAN DEFAULT FALSE; form_16a_doc_hash BYTEA (nullable); form_16a_issued_at TIMESTAMPTZ (nullable); only tds_rate_nonneg CHECK on the table.

**13. [Medium]** 02_counterparty_platform.sql · comp_aml_screening · status / adjudication_decision

- **Issue:** comp_aml_screening_adjudication_chk (lines 1016-1022) already requires adjudicated_by/adjudicated_at when status='adjudicated', but it does NOT include adjudication_decision in that gate, even though adjudication_decision ('clear'|'false_positive'|'true_hit_suspend', line 1003) is the actual outcome of adjudication. A status='adjudicated' row can therefore have adjudicated_by/at set but a NULL decision. The existing CHECK should be extended to cover adjudication_decision for completeness.
- **Fix:** Replace comp_aml_screening_adjudication_chk with: CHECK ( (status = 'adjudicated' AND adjudication_decision IS NOT NULL AND adjudicated_by IS NOT NULL AND adjudicated_at IS NOT NULL) OR (status <> 'adjudicated' AND adjudication_decision IS NULL AND adjudicated_by IS NULL AND adjudicated_at IS NULL) ); -- also add a domain/enum or CHECK constraining adjudication_decision to the three documented values.
- **Evidence:** lines 1004, 1015-1022: adjudication_decision TEXT nullable; CHECK gates only adjudicated_by and adjudicated_at on status='adjudicated'.

**14. [Low]** 01_core.sql · legal_signature_request · status / cert_serial

- **Issue:** cert_serial is 'set exactly once on terminal completed' (SR.3, lines 1404-1405, 1437-1439). The sibling table gate_signature_session enforces exactly this with gate_signature_session_cert_serial_only_on_completed (04_generic_acl.sql line 694-695), but legal_signature_request has no equivalent CHECK — an 'initiated' or 'failed' request can carry a cert_serial. Inconsistent with its own ACL twin.
- **Fix:** ADD CONSTRAINT legal_signature_request_cert_only_on_completed_chk CHECK ( cert_serial IS NULL OR status = 'completed' );
- **Evidence:** lines 1400-1417: status default 'initiated'; cert_serial TEXT (nullable); only retry_count CHECKs present.

**15. [Low]** 01_core.sql · col_maturity_case · delay_status / outcome

- **Issue:** delay_status is the discriminator; outcome (col_maturity_outcome) is only meaningful once delay_status='outcome' (the enum has an explicit 'outcome' state, line 269). Comment MC.4 (lines 1484-1485, 1522-1525) stresses outcome must arrive only via the BC3 subscriber and there is deliberately NO DB default — but there is also no CHECK preventing an outcome being written while delay_status is still 'on_track'/'delayed' etc. A single-row CHECK linking the two would harden the 'no premature outcome' rule that the comment cares about.
- **Fix:** ADD CONSTRAINT col_maturity_case_outcome_shape_chk CHECK ( outcome IS NULL OR delay_status = 'outcome' );  -- and conversely if delay_status='outcome' should always carry an outcome, make it bidirectional: AND (delay_status <> 'outcome' OR outcome IS NOT NULL). Confirm against BC6 state machine before tightening.
- **Evidence:** lines 1482-1486: delay_status default 'on_track'; outcome col_maturity_outcome (nullable, no default); only dpd_nonneg and recovery_nonneg CHECKs.

**16. [Low]** 01_core.sql · col_claim_case · status / outcome

- **Issue:** status discriminates {raised, under_adjudication, resolved}; outcome (col_maturity_outcome) should only be set when status='resolved' (CCL.2, lines 1601-1602, 1629-1632: 'NO DB DEFAULT. Set exclusively via BC3 ... subscriber'). No CHECK ties outcome to status, so a 'raised' claim can carry an outcome. claim_type is separately gated only at app layer for supplier_indemnity_amount (CCL.3) — see related note. The supplier_indemnity_amount/claim_type pairing (indemnity only for dilution/fraud) is also a discriminator-nullability rule currently enforced only in the app layer.
- **Fix:** ADD CONSTRAINT col_claim_case_outcome_shape_chk CHECK ( outcome IS NULL OR status = 'resolved' ); -- optional, per Spec 2.2 limited-recourse: ADD CONSTRAINT col_claim_case_indemnity_kind_chk CHECK ( supplier_indemnity_amount IS NULL OR claim_type IN ('dilution','fraud') );
- **Evidence:** lines 1597-1604: claim_type col_claim_type NOT NULL; status default 'raised'; outcome col_maturity_outcome (nullable); supplier_indemnity_amount BIGINT (nullable); only indemnity_nonneg CHECK.

**17. [Low]** 02_counterparty_platform.sql · sup_account · status / suspended_at,suspension_reason / blacklisted_at,blacklist_reason / voluntarily_exited_at

- **Issue:** status discriminates supplier lifecycle including {suspended, blacklisted, voluntarily_exited}. The paired timestamp+reason columns for each terminal/exception state are entirely ungated (the table has only a PRIMARY KEY constraint). A status='suspended' row can have NULL suspended_at; a status='active' row can carry a blacklist_reason. Compare inv_account/admin_role_assignment which gate similar pairs. The reason/timestamp halves should at minimum be co-present, and ideally gated by status.
- **Fix:** ADD CONSTRAINT sup_account_suspend_shape_chk CHECK ( (suspended_at IS NULL) = (suspension_reason IS NULL) ); ADD CONSTRAINT sup_account_blacklist_shape_chk CHECK ( (blacklisted_at IS NULL) = (blacklist_reason IS NULL) ); -- optionally tie to status: AND (status = 'blacklisted' OR blacklisted_at IS NULL), (status = 'suspended' OR suspended_at IS NULL), (status = 'voluntarily_exited' OR voluntarily_exited_at IS NULL) — confirm in-flight vs terminal semantics first.
- **Evidence:** lines 442-449: suspended_at, suspension_reason, blacklisted_at, blacklist_reason, voluntarily_exited_at all nullable; CREATE TABLE has only sup_account_pk.

**18. [Low]** 02_counterparty_platform.sql · inv_account · status / suspended_at,suspension_reason / exited_at

- **Issue:** status discriminates investor lifecycle including {suspended, exited}. suspended_at/suspension_reason and exited_at are ungated. A status='suspended' row may have NULL suspended_at/reason; an 'active' row may carry a suspension_reason. The table already has good conditional CHECKs (activated_refresh_chk, sub_type_phase1_chk) so adding the suspension co-presence pair is consistent and cheap.
- **Fix:** ADD CONSTRAINT inv_account_suspend_shape_chk CHECK ( (suspended_at IS NULL) = (suspension_reason IS NULL) ); -- optionally: AND (status = 'suspended' OR suspended_at IS NULL) AND (status = 'exited' OR exited_at IS NULL).
- **Evidence:** lines 297-299: suspended_at TIMESTAMPTZ, suspension_reason TEXT, exited_at TIMESTAMPTZ all nullable; no CHECK references them.

**19. [Low]** 02_counterparty_platform.sql · buyer_account · status / suspended_at,suspension_reason

- **Issue:** status discriminates buyer lifecycle incl. {suspended}. suspended_at and suspension_reason are an ungated pair (table has only PK + the two Phase-1 enum-lock CHECKs). Same co-presence gap as inv_account/sup_account.
- **Fix:** ADD CONSTRAINT buyer_account_suspend_shape_chk CHECK ( (suspended_at IS NULL) = (suspension_reason IS NULL) ); -- optionally: AND (status = 'suspended' OR suspended_at IS NULL).
- **Evidence:** lines 604-605: suspended_at TIMESTAMPTZ, suspension_reason TEXT nullable; no CHECK references them.

**20. [Low]** 02_counterparty_platform.sql · audit_account · status / activated_at / auto_disabled_at

- **Issue:** audit_account_approved_chk already gates approved_by/approved_at well, but activated_at and auto_disabled_at are not tied to status: a status='activated' row can have NULL activated_at, and auto_disabled_at can be set while status<>'auto_disabled'. Completes the status-shape coverage for this aggregate.
- **Fix:** ADD CONSTRAINT audit_account_lifecycle_shape_chk CHECK ( (status <> 'activated' OR activated_at IS NOT NULL) AND (auto_disabled_at IS NULL OR status = 'auto_disabled') AND (status <> 'auto_disabled' OR auto_disabled_at IS NOT NULL) );
- **Evidence:** lines 1394-1395, 1406-1412: activated_at, auto_disabled_at nullable; approved_chk covers only approved_by/at.

**21. [Low]** 02_counterparty_platform.sql · comp_refresh_schedule · status / window_close_at / completed_at

- **Issue:** status discriminates {scheduled, due, completed, missed}. Per comment (line 1090) window_close_at is 'set when status transitions to due'; completed_at is meaningful only when status='completed'. Neither is gated — a 'scheduled' row can have a completed_at. Low impact (scheduler-owned, non-blocking per DL-037) but a cheap declarative tightening.
- **Fix:** ADD CONSTRAINT comp_refresh_schedule_status_shape_chk CHECK ( (status = 'completed' OR completed_at IS NULL) AND (status NOT IN ('scheduled') OR window_close_at IS NULL) );  -- confirm exact window_close_at semantics (set at 'due', persists through 'completed'/'missed') before tightening.
- **Evidence:** lines 1089-1091: status default 'scheduled'; window_close_at, completed_at nullable; only subject_uq constraint.

**22. [Info]** 04_generic_acl.sql · gate_vendor_instruction · status / vendor_event_id / vendor_payload_hash / hmac_verified_at

- **Issue:** status discriminates {pending, sent, executed, failed}; vendor_event_id, vendor_payload_hash, and hmac_verified_at are populated only once a vendor webhook is received (VI.2/VI.3). These are not strictly required-NOT-NULL at any single status (a 'sent' instruction may have no webhook yet), so this is genuinely loose and best left to the app layer / state machine rather than a single-row CHECK. Flagged only for completeness; no defect — included so the sweep is exhaustive.
- **Fix:** No declarative fix recommended — the population of these columns is asynchronous to status and cannot be pinned to a discriminator value in a single-row CHECK. Enforce ordering (hmac_verified_at before state mutation, VI.2) in the app layer as already documented.
- **Evidence:** lines 525-540: vendor_event_id TEXT UNIQUE (nullable), vendor_payload_hash BYTEA, hmac_verified_at TIMESTAMPTZ; only PK constraint.

**23. [Info]** 02_counterparty_platform.sql · comp_kyc_file · status / approver_id / decided_at / rejection_reason

- **Issue:** No defect — this table is the positive model: comp_kyc_file_decision_chk gates approver_id/decided_at by status, and comp_kyc_file_rejection_reason_chk gates rejection_reason on status='rejected' (lines 1137-1148). Listed so reviewers can confirm the pattern other tables should mirror. The only residual app-layer rule (approver must hold compliance_reviewer role and differ from submitter) is genuinely cross-row/cross-table and correctly NOT a CHECK.
- **Fix:** None — reference implementation for the discriminator-gated nullability pattern.
- **Evidence:** lines 1137-1148: comp_kyc_file_decision_chk + comp_kyc_file_rejection_reason_chk.


### Five non-negotiables coverage  (13)

**1. [Fix now]** 01_core.sql, 02_counterparty_platform.sql, 04_generic_acl.sql · ALL state-changing aggregates (platform-wide) · command_id

- **Issue:** Non-negotiable #4 (idempotent on command_id, G18) has ZERO declarative footprint. `command_id` is not a column on any aggregate, not on sys_audit_event, and there is no producer-dedup table with UNIQUE(actor_id, command_id). Spec B2 §2.1 + G18 require every command-originated event to carry command_id and producers to dedupe on (actor_id, command_id) before mutating state. Nothing in the DB enforces or even records this, so command replay is not a guaranteed no-op at the last line of defence.
- **Fix:** Add a producer-dedup table, e.g. CREATE TABLE sys_command_log (actor_id UUID NOT NULL, command_id UUID NOT NULL, command_type TEXT NOT NULL, aggregate_type TEXT NOT NULL, aggregate_id UUID NOT NULL, first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(), resulting_event_id UUID, CONSTRAINT sys_command_log_pk PRIMARY KEY (actor_id, command_id)); command handlers INSERT ... ON CONFLICT DO NOTHING and treat conflict as replay. Also add `command_id UUID` to sys_audit_event (NOT NULL for command-originated event_types, NULL for scheduler/webhook per B2 line 44) so the envelope records the idempotency key declaratively.
- **Evidence:** grep -c command_id over all four .sql files = 0; spec 09_B3 line 23 (P8) and line 331 (PI.1) and 08_B2 line 44 reference command_id as the idempotency key.

**2. [High]** 02_counterparty_platform.sql · sup_account · (no maker_id / checker_id / suspend_mfa_assertion_id)

- **Issue:** Spec 09_B3 line 733 mandates record-level maker-checker for Suspend/Blacklist (admin_user[Credit Reviewer + Compliance Reviewer maker-checker], C4). The table only has suspended_at, blacklisted_at, blacklist_reason — no distinct proposer/approver columns and no MFA assertion column, so non-negotiables #1 (maker≠checker) and #2 (MFA-fresh) are unenforced at the DB for these state-changing commands.
- **Fix:** Add suspend_maker_id UUID, suspend_checker_id UUID, suspend_checker_mfa_assertion_id TEXT (and blacklist_* equivalents, or a shared admin-action group) with CHECK (suspend_checker_id IS NULL OR suspend_maker_id <> suspend_checker_id) and FKs to admin_user, mirroring deal_listing.golive_* / cash_payout_instruction.maker_id pattern.
- **Evidence:** 02_counterparty_platform.sql lines 442-445: suspended_at TIMESTAMPTZ, blacklisted_at TIMESTAMPTZ, blacklist_reason TEXT — and grep for maker/checker on this table returns nothing.

**3. [High]** 02_counterparty_platform.sql · buyer_account · (no maker_id / checker_id / suspend_mfa_assertion_id)

- **Issue:** Spec 09_B3 line 819 mandates record-level maker-checker for Suspend (admin_user[Credit Reviewer + Treasury & Settlement maker-checker], C4); the in-file comment BA.2 (line 628) restates it as app-layer only. Table has only suspended_at — no distinct proposer/approver columns, no MFA column. Non-negotiables #1 and #2 unenforced at the DB.
- **Fix:** Add suspend_maker_id UUID, suspend_checker_id UUID (FKs to admin_user), suspend_checker_mfa_assertion_id TEXT, and CONSTRAINT buyer_account_suspend_maker_ne_checker CHECK (suspend_checker_id IS NULL OR suspend_maker_id <> suspend_checker_id).
- **Evidence:** 02_counterparty_platform.sql line 604 suspended_at TIMESTAMPTZ; comment lines 628-629 'buyer_account suspension requires maker-checker ... application-layer'.

**4. [High]** 02_counterparty_platform.sql · comp_kyc_file · approver_id / (missing submitter_id)

- **Issue:** Non-negotiable #1 (proposer≠approver) is required here by C4/KF.2 (spec line 1075; in-file KF.2 line 1165) — the approver cannot be the individual who submitted on the supplier's behalf. But the table has no submitter/maker column, so the distinctness CHECK is impossible to express and is left entirely app-layer. There is also no mfa_assertion column for the approval.
- **Fix:** Add submitted_by UUID (FK admin_user, the maker), approver_mfa_assertion_id TEXT, and CONSTRAINT comp_kyc_file_maker_ne_checker CHECK (approver_id IS NULL OR approver_id <> submitted_by). This makes the record-level maker-checker declarative for supplier KYC files.
- **Evidence:** 02_counterparty_platform.sql lines 1119-1147: approver_id present, no submitted_by/maker column; comment KF.2 line 1165-1166 says distinctness is application-layer only.

**5. [High]** 04_generic_acl.sql (sys_audit_event.actor) · ALL admin state-changing aggregates · mfa_assertion_id

- **Issue:** Non-negotiable #2 (MFA-fresh) per AU10.3 (spec line 888) applies to every admin_user state-changing command, but the only declarative MFA linkage is via sys_audit_event.actor JSONB, which is `actor JSONB NOT NULL` with no shape/NOT-NULL guarantee on mfa_assertion_id. Aggregates other than deal_listing and cash_payout_instruction (e.g. risk_default_case Classify, audit_account activate, BCP/SCP four-eyes commands, suspend commands) carry no MFA column, so MFA freshness is unverifiable at the DB.
- **Fix:** Either (a) add a checker/decision mfa_assertion_id TEXT column to each C4 maker-checker aggregate (risk_default_case, comp_kyc_file, audit_account, sup_account/buyer_account suspend, risk_buyer_profile/risk_supplier_profile four-eyes), or (b) add a generated/extracted NOT NULL column on sys_audit_event for command-originated admin events: mfa_assertion_id UUID GENERATED ALWAYS AS ((actor->>'mfa_assertion_id')::uuid) STORED, plus a CHECK that it is non-null when actor->>'actor_type' = 'admin_user' and the event is command-originated.
- **Evidence:** 04_generic_acl.sql lines 142-145 actor JSONB NOT NULL (comment: mfa_assertion_id nullable, validated at application layer); only 01_core.sql golive_mfa_assertion_id (line 470) and checker_mfa_assertion_id (line 1059) exist as real columns.

**6. [Medium]** 01_core.sql · risk_buyer_profile / risk_supplier_profile · four_eyes_approval_ref / second_approver_id

- **Issue:** These are state-changing aggregates whose threshold (>Rs1Cr) four-eyes (C6, BCP.2/SCP.2) is the maker-checker analogue. The columns second_approver_id and four_eyes_approval_ref exist but there is (a) no CHECK enforcing second_approver_id <> first approver (there is no first_approver_id column at all to compare against), and (b) no conditional CHECK that four_eyes_approval_ref/second_approver_id is NOT NULL when credit_limit/exposure_cap > 10_000_000_000. The whole BCP.2/SCP.2 rule is left app-layer despite being expressible declaratively, and there is no MFA column.
- **Fix:** Add first_approver_id UUID and CONSTRAINT ..._four_eyes_distinct CHECK (second_approver_id IS NULL OR first_approver_id IS NULL OR second_approver_id <> first_approver_id); add CONSTRAINT ..._four_eyes_required CHECK (credit_limit <= 10000000000 OR (four_eyes_approval_ref IS NOT NULL AND second_approver_id IS NOT NULL)) (and exposure_cap equivalent for supplier).
- **Evidence:** 01_core.sql risk_buyer_profile lines 709-719 (four_eyes_approval_ref, second_approver_id, but only credit_limit_positive + tenor_cap CHECKs); risk_supplier_profile lines 767-776 similarly lacks the threshold CHECK.

**7. [Medium]** 01_core.sql · risk_default_case · four_eyes_approval_ref / second_approver_id / maker_id+checker_id mfa

- **Issue:** maker_id/checker_id distinctness IS enforced (line 912-913, good) but: (a) the DC.2 conditional four-eyes-required-when-exposure>Rs1Cr is not declaratively enforceable (exposure not on this row, left app-layer — acceptable but worth noting), and (b) there is no checker mfa_assertion_id column despite Classify being an admin state-change requiring MFA freshness (C7/AU10.3).
- **Fix:** Add checker_mfa_assertion_id TEXT to record the MFA assertion at Classify time, mirroring cash_payout_instruction.checker_mfa_assertion_id; optionally add second_approver_id <> maker_id/checker_id CHECK for the ReClassify path.
- **Evidence:** 01_core.sql lines 886-914: maker_id, checker_id, second_approver_id, four_eyes_approval_ref present with maker_ne_checker CHECK; no mfa_assertion column.

**8. [Medium]** 02_counterparty_platform.sql · audit_account · approved_by / proposed_by (mfa)

- **Issue:** Record-level maker-checker IS declaratively enforced here (proposed_by <> approved_by CHECK at line 1414, AA13.1, good). Remaining gap: no approver mfa_assertion_id column, so non-negotiable #2 (MFA-fresh on the activation/approval admin command) is not declaratively linked.
- **Fix:** Add approved_mfa_assertion_id TEXT NULL, populated by the approval command handler, to make the C7 MFA linkage declarative for this command.
- **Evidence:** 02_counterparty_platform.sql lines 1391-1414: approved_by, proposed_by, audit_account_maker_checker_chk CHECK (proposed_by <> approved_by OR approved_by IS NULL).

**9. [Medium]** 02_counterparty_platform.sql · comp_aml_screening / comp_sar_case · adjudication_decision / SAR fields

- **Issue:** These are state-changing adjudication aggregates. Per AS11.3 (spec line 987) AML decisions are single-approver (maker-checker NOT required) — so #1 maker-checker is correctly N/A. However they are still admin state-changing commands and thus require MFA-freshness (#2, AU10.3) and command_id idempotency (#4); neither has any declarative column. Flagging so they are not assumed exempt from #2/#4 just because #1 is waived.
- **Fix:** Covered by the platform-wide command_id table and the sys_audit_event mfa_assertion_id generated column / per-aggregate mfa columns; no per-row maker-checker needed for AML per AS11.3.
- **Evidence:** 02_counterparty_platform.sql line 1003 adjudication_decision; comp_aml_screening / comp_sar_case tables carry aggregate_version but no command_id / mfa columns.

**10. [Medium]** 04_generic_acl.sql · sys_audit_event · previous_envelope_hash (and prevent_audit_modification trigger)

- **Issue:** Non-negotiable #5 requires an immutable, cryptographically-chained envelope. The chain column exists (previous_envelope_hash BYTEA, AE.2) but the hash is computed app-side and there is no stored this_envelope_hash column on the row to chain against deterministically — the verifier must recompute canonical encodings. Per project policy (no procedural triggers; audit-chain hashing is app-layer), the prevent_audit_modification BEFORE UPDATE/DELETE trigger + fn_prevent_audit_modification function should be reconsidered: it is the one procedural trigger/function in the bundle, and append-only is better enforced via REVOKE UPDATE,DELETE privileges + a write-only role than via plpgsql.
- **Fix:** Add this_envelope_hash BYTEA NOT NULL (app-computed SHA-256 over canonical payload) so the chain is row-local and verifiable by a simple join; replace the plpgsql append-only trigger with REVOKE UPDATE, DELETE ON sys_audit_event FROM <app_role>; (grant INSERT/SELECT only) to satisfy AE.1 declaratively without a procedural function, per the no-trigger policy.
- **Evidence:** 04_generic_acl.sql lines 159-163 (corrects FK, previous_envelope_hash BYTEA); trigger prevent_audit_modification + fn_prevent_audit_modification (per schema index).

**11. [Medium]** 01_core.sql, 02_counterparty_platform.sql · ALL aggregates with updated_at + native/jOOQ write paths · updated_at

- **Issue:** Per policy, updated_at is APP-OWNED (no trigger). Every aggregate has `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()` but nothing maintains it on UPDATE. The CLAUDE.md persistence rules also mandate native-SQL/jOOQ for invariant-heavy reconciliation/funding-equality writes (cash_recon_ledger, cash_virtual_account inflow ledger, deal_listing.committed_total). Those native write paths bypass JPA lifecycle callbacks, so updated_at and aggregate_version increments are at risk of being silently skipped on exactly the highest-integrity tables.
- **Fix:** App-layer responsibility (no trigger): ensure every command handler (JPA @PreUpdate AND every jOOQ/native UPDATE) explicitly sets updated_at = now() and bumps aggregate_version with a WHERE aggregate_version = :expected optimistic-lock predicate. Add an ArchUnit/integration test that fails if a native write to a funding/recon table omits the updated_at + aggregate_version columns.
- **Evidence:** deal_listing line 474, sub_subscription line 604, cash_virtual_account lines 969-974 (running ledger), cash_recon_ledger line 1157 — all have updated_at DEFAULT now() with no maintenance mechanism.

**12. [Low]** 01_core.sql · col_action_log · (no aggregate_version / no command_id)

- **Issue:** col_action_log is the one BC6 table with no aggregate_version (lines 1549-1562). It is an append-only immutable log entity (CollectionsAction.Recorded), and the spec models it as a record, not a mutated aggregate, so omitting aggregate_version is defensible. But the Record command (CA.1/CA.2, soft or hard maker-checker per actor) is still a state-changing command and so falls under command_id idempotency (#4) and MFA-freshness (#5/AU10.3) — neither is declaratively present.
- **Fix:** No aggregate_version needed (immutable log). Cover command_id via the platform-wide sys_command_log; ensure the Record handler logs actor MFA into sys_audit_event.actor. Optionally add UNIQUE(maturity_case_id, action_type, recorded_at) only if needed; primary mitigation is the command_id dedup table.
- **Evidence:** 01_core.sql lines 1549-1562: col_action_log has action_id, actor_id, recorded_at — no aggregate_version, no command_id, no mfa.

**13. [Info]** 01_core.sql · deal_listing (CancelPreDisbursement) · golive_maker_id/checker_id reused?

- **Issue:** Spec line 116 requires CancelPreDisbursement to be its own maker-checker pair (Credit Reviewer + Treasury & Settlement). The table only has golive_maker_id/golive_checker_id (for GoneLive). There are no separate cancel_maker_id/cancel_checker_id columns, so the cancel command's distinct maker-checker pair has no declarative home and would either overwrite the golive columns or be app-layer-only.
- **Fix:** If CancelPreDisbursement must record its own pair, add cancel_maker_id UUID, cancel_checker_id UUID, cancel_checker_mfa_assertion_id TEXT with a maker<>checker CHECK; otherwise document (DL-BE) that the cancel pair is captured solely in the sys_audit_event envelope (acceptable if the envelope carries maker/checker actor refs, but then it is not declaratively enforced).
- **Evidence:** 01_core.sql lines 468-470 golive_maker_id/golive_checker_id/golive_mfa_assertion_id only; spec 09_B3 line 116.


### Audit chain  (8)

**1. [Fix now]** 04_generic_acl.sql · sys_audit_event · envelope_hash

- **Issue:** The self-hash / tamper-evidence column `envelope_hash` is entirely ABSENT from the table, even though the AE.2 comment (line 113) describes it and the spec mandates it: B2 §2.1 (08_B2_Event_Model.md line 51: 'envelope_hash bytes32 yes - SHA-256 over canonical encoding of this envelope minus envelope_hash itself. Tamper-evidence'), B3 AE.2 (09_B3_Aggregates.md line 1237), and B4 (10_B4_API_Conventions.md line 345: 'computing previous_envelope_hash AND envelope_hash'). With only `previous_envelope_hash` and no stored self-hash, the chain cannot be verified: row N+1's previous_envelope_hash references row N but row N stores no hash of itself to compare against. This silently defeats tamper-evidence, the entire purpose of the audit chain non-negotiable.
- **Fix:** Add the column: `envelope_hash BYTEA NOT NULL` to sys_audit_event (computed app-side over the canonical encoding of the row minus envelope_hash itself, per AE.2). Make it NOT NULL (every persisted envelope must be self-hashed) and add `CONSTRAINT sys_audit_event_envelope_hash_len CHECK (octet_length(envelope_hash) = 32)`. Optionally `UNIQUE (envelope_hash)` to catch accidental duplicate inserts. Apply the same length CHECK to previous_envelope_hash: `CHECK (previous_envelope_hash IS NULL OR octet_length(previous_envelope_hash) = 32)`.
- **Evidence:** Line 163 declares only `previous_envelope_hash BYTEA,` — grep for 'envelope_hash' in 04_generic_acl.sql returns only previous_envelope_hash references; no `envelope_hash` column exists. Spec: 08_B2_Event_Model.md:51 and 09_B3_Aggregates.md:1237.

**2. [Fix now]** 04_generic_acl.sql · sys_audit_event · (table-level / trigger)

- **Issue:** Append-only (AE.1) is enforced by a plpgsql trigger + function (prevent_audit_modification / fn_prevent_audit_modification), which directly violates the project NO-TRIGGER / keep-the-DB-simple policy. CLAUDE.md SCHEMA INDEX explicitly flags this as 'the existing trigger/function in 04_generic_acl.sql should be flagged for reconsideration.' Immutability can be enforced DECLARATIVELY for the application's least-privilege role via GRANT/REVOKE, which is debuggable and policy-compliant.
- **Fix:** Drop the trigger and function. Replace with a declarative privilege grant in a migration: `REVOKE UPDATE, DELETE, TRUNCATE ON sys_audit_event FROM platform;` and `GRANT INSERT, SELECT ON sys_audit_event TO platform;` (where `platform` is the app role). Note this does not block a superuser/table-owner; the spec already names WORM substrate (G7) as the complementary control for owner/superuser/pg_dump paths (lines 220-221, 243). Add a DL-BE decision log entry recording trigger->REVOKE swap.
- **Evidence:** Lines 222-244: `CREATE OR REPLACE FUNCTION fn_prevent_audit_modification() ... RAISE EXCEPTION ...` and `CREATE TRIGGER prevent_audit_modification BEFORE UPDATE OR DELETE ON sys_audit_event FOR EACH ROW EXECUTE FUNCTION ...`.

**3. [High]** 04_generic_acl.sql · sys_audit_event · previous_envelope_hash / envelope_hash

- **Issue:** Hash-chain computation is inherently NOT enforceable by declarative DDL (a CHECK cannot read the previous row), so under the no-trigger policy it MUST be an APP-LAYER responsibility — exactly like app-owned updated_at. This is an explicit gap to flag: the DB cannot guarantee that previous_envelope_hash actually equals the prior row's envelope_hash, nor that envelope_hash is correctly computed. Any native-SQL/jOOQ write path that inserts into sys_audit_event bypassing the single app-layer chaining writer (per B4 §345, the BC14 audit-log writer consuming the outbox) would silently break the chain. This is the same class of risk the project flags for app-owned columns.
- **Fix:** Declaratively: cannot fully enforce — document the limit. App-layer: funnel ALL audit inserts through one serialized append path (the BC14 outbox writer), never via ad-hoc jOOQ/native inserts. Add a periodic chain-verification job (the idx_audit_chain_verify index at line 748 already supports walking recorded_at order). Add a DL-BE entry stating chain hashing is app-owned and listing sys_audit_event as a table where direct native-SQL inserts are prohibited. Consider an INSERT-only privilege model plus a code-level ArchUnit/lint rule that only the BC14 writer references sys_audit_event for writes.
- **Evidence:** 10_B4_API_Conventions.md:345 ('the audit-log writer consumes the outbox and appends ... computing previous_envelope_hash and envelope_hash'). No DB construct in 04_generic_acl.sql validates chain linkage; previous_envelope_hash is plain nullable BYTEA (line 163).

**4. [High]** 04_generic_acl.sql · sys_audit_event · previous_envelope_hash / shard key

- **Issue:** The chain is per-shard (G25, 'previous_envelope_hash NULL only for the first row in a shard'), but the table stores no explicit shard key column. The verify index (line 748) and comments (line 753) say sharding is 'scoped to (context, business_date) at query time' — i.e. derived implicitly. Without a persisted shard discriminator, the verifier cannot reliably partition the chain, and 'NULL only for the first row in a shard' is not checkable. This weakens both verifiability and any future declarative guard.
- **Fix:** Persist the shard discriminator explicitly, e.g. add `chain_shard TEXT NOT NULL` (or derive from a stored `business_date DATE NOT NULL` + context extracted from event_type) and index `(chain_shard, recorded_at, event_id)` to replace/augment idx_audit_chain_verify. This makes 'first row per shard has NULL previous_envelope_hash' an explainable, queryable invariant for the app-layer verifier.
- **Evidence:** Line 162: 'NULL only for the very first row in a shard (G25 per-shard chains)'; line 753: 'Shard-level chain verification scoped to (context, business_date) at query time (G25)'. No shard/context/business_date column exists in the table (lines 132-170).

**5. [Medium]** 04_generic_acl.sql · sys_audit_event · correlation_id / actor / payload

- **Issue:** AE.5 requires actor JSONB to contain at minimum actor_type, actor_id, session_id (and mfa_assertion_id for admin actors — a non-negotiable for state-changing commands). This is asserted only in comments (lines 124-125, 192-195) with zero declarative enforcement; actor is just `JSONB NOT NULL` and could be '{}'. Cheap CHECK constraints on JSONB key presence are available and would make the DB the last line of defence for the MFA/actor non-negotiable.
- **Fix:** Add declarative key-presence guards: `CONSTRAINT sys_audit_event_actor_keys CHECK (actor ? 'actor_type' AND actor ? 'actor_id' AND actor ? 'session_id')` and `CONSTRAINT sys_audit_event_admin_mfa CHECK (actor->>'actor_type' <> 'admin_user' OR actor ? 'mfa_assertion_id')` (enforces C7 MFA-fresh evidence in the audit trail). Optionally `CHECK (actor->>'actor_type' <> 'agency' OR actor ? 'agency_consent_id')` for AE.5/X12.
- **Evidence:** Line 145: `actor JSONB NOT NULL` with no key-presence CHECK. AE.5 comment lines 124-125.

**6. [Medium]** 04_generic_acl.sql · sys_audit_event · before_state / after_state

- **Issue:** Comments (AE, lines 156-157, 206-209) state before_state and after_state are 'Required on state-transition events', but both columns are plain nullable JSONB with no enforcement. State-transition events could be appended without the before/after snapshots the audit story relies on.
- **Fix:** If a discriminator exists (e.g. an is_state_transition BOOLEAN or an event_type naming convention), add a CHECK such as `CHECK (NOT is_state_transition OR (before_state IS NOT NULL AND after_state IS NOT NULL))`. If transition-ness is only derivable from event_type text, this is partly app-layer; document it and add the boolean to make it declaratively enforceable.
- **Evidence:** Lines 156-157: `before_state JSONB,` and `after_state JSONB,` — both nullable, no CHECK keyed off event semantics.

**7. [Low]** 04_generic_acl.sql · sys_audit_event · occurred_at / recorded_at

- **Issue:** AE invariant 'occurred_at <= recorded_at always' (comment line 189) is not enforced declaratively despite being a trivial CHECK.
- **Fix:** Add `CONSTRAINT sys_audit_event_time_order CHECK (occurred_at <= recorded_at)`.
- **Evidence:** Line 189 COMMENT: 'occurred_at <= recorded_at always.'; no CHECK present.

**8. [Info]** 04_generic_acl.sql · sys_audit_event (cross-aggregate references) · aggregate_id / causation_event_id / corrects

- **Issue:** Confirmation that the referencing model is correct and intentional: no business aggregate carries a hard FK INTO sys_audit_event (grep of 01/02/03 shows zero references), which is the correct DDD/bounded-context pattern — the audit log is a universal sink keyed by (aggregate_type, aggregate_id, aggregate_version), and downstream references are soft FKs by design. sys_notification_dispatch.causation_event_id is an intentional soft FK (ND.3, lines 320-324). The only hard FK is the self-referential `corrects` (line 159, DEFERRABLE INITIALLY DEFERRED), correctly allowing correction chains while keeping rows immutable. No change needed; flagged so the orchestrator does not mistake the absence of inbound FKs for a gap.
- **Fix:** No change. Keep aggregate_version on business tables (optimistic concurrency) as the linkage to audit envelopes via aggregate_id+aggregate_version; ensure the app-layer writer stamps these correctly.
- **Evidence:** Line 159: `corrects UUID REFERENCES sys_audit_event (event_id) DEFERRABLE INITIALLY DEFERRED`; lines 320-324 ND.3 soft-FK rationale; grep of 01_core/02_counterparty/03_auth shows no FK to sys_audit_event.


### Money/rate/time primitives  (12)

**1. [High]** 01_core.sql · deal_listing · buyer_limit_headroom_snapshot, supplier_exposure_cap_snapshot

- **Issue:** Two money columns (paise) declared as raw BIGINT with NO positivity/non-negativity CHECK at all. A negative headroom or negative exposure-cap snapshot would be silently accepted by the DB, defeating the last-line-of-defence guarantee. They are also not using the money_paise domain despite the domain existing and the project policy mandating it (02_counterparty_platform.sql:11).
- **Fix:** Retype both to the money_paise domain so the >=0 CHECK is inherited declaratively: `buyer_limit_headroom_snapshot money_paise,` and `supplier_exposure_cap_snapshot money_paise,` (both remain nullable until snapshot is taken; the domain CHECK only fires on non-null values).
- **Evidence:** 01_core.sql:450-451 `buyer_limit_headroom_snapshot BIGINT, -- paise` / `supplier_exposure_cap_snapshot BIGINT, -- paise`; table CHECK list (lines 479-489) contains no constraint on either column

**2. [High]** 01_core.sql · risk_pricing_policy · rate_range_min_bps, rate_range_max_bps, fee_bps

- **Issue:** Rate/fee columns are raw INT, not the bps_type domain. They therefore lack the bps_type upper bound (0..100000). A typo or bad import could store e.g. 5_000_000 bps (50000%); the existing per-table CHECKs only enforce min>0, min<=max, fee>=0 — none caps the maximum. bps_type exists specifically for this and is used elsewhere conceptually.
- **Fix:** Retype all three to bps_type: `rate_range_min_bps bps_type NOT NULL, rate_range_max_bps bps_type NOT NULL, fee_bps bps_type NOT NULL`. This adds the declarative 0..100000 ceiling. Keep the existing rate_min_positive and rate_range_valid CHECKs (they add the >0 and min<=max semantics bps_type does not).
- **Evidence:** 01_core.sql:816-819 `rate_range_min_bps INT NOT NULL, rate_range_max_bps INT NOT NULL, fee_bps INT NOT NULL`; CHECKs at 833-836 enforce no upper bound

**3. [High]** 02_counterparty_platform.sql · tax_year_profile · tds_rate_bps

- **Issue:** A rate (basis points) column declared as raw INT with only a >=0 CHECK; missing the bps_type upper bound (0..100000). A TDS rate stored in bps should never exceed 100000; raw INT permits any value up to 2^31.
- **Fix:** Retype to bps_type and drop the now-redundant hand-written CHECK: `tds_rate_bps bps_type NOT NULL DEFAULT 0`. bps_type already enforces 0..100000.
- **Evidence:** 02_counterparty_platform.sql:1212-1213 `tds_rate_bps INT NOT NULL DEFAULT 0 CONSTRAINT tax_year_profile_tds_rate_nonneg CHECK (tds_rate_bps >= 0)`

**4. [Medium]** 01_core.sql · deal_invoice · face_value

- **Issue:** Monetary column (paise) declared raw BIGINT rather than positive_money_paise. The intent (face_value > 0) is enforced by a hand-written CHECK (deal_invoice_face_value_positive) so behaviour is currently correct, but it diverges from the mandated domain-type convention (02 header line 11) and from how sibling files declare money. Using the domain makes the invariant uniform and removes a bespoke CHECK.
- **Fix:** Retype to `face_value positive_money_paise NOT NULL` and drop deal_invoice_face_value_positive (the domain enforces >0).
- **Evidence:** 01_core.sql:339 `face_value BIGINT NOT NULL` + 352 `CHECK (face_value > 0)`

**5. [Medium]** 01_core.sql · deal_listing · funding_target, committed_total

- **Issue:** Money columns (paise) as raw BIGINT. funding_target's >0 is hand-enforced (deal_listing_funding_target_positive) and committed_total's >=0 hand-enforced (deal_listing_committed_nonneg) — correct today but off-convention vs the money domains.
- **Fix:** Retype `funding_target positive_money_paise` (drop funding_target_positive CHECK) and `committed_total money_paise NOT NULL DEFAULT 0` (drop committed_nonneg CHECK). Retain deal_listing_committed_lte_target — that cross-column invariant is not expressible by a domain.
- **Evidence:** 01_core.sql:452 `funding_target BIGINT`, 455 `committed_total BIGINT NOT NULL DEFAULT 0`; CHECKs 481-484

**6. [Medium]** 01_core.sql · sub_subscription · amount, expected_inflow_amount

- **Issue:** Money columns (paise) as raw BIGINT; >0 hand-enforced via sub_subscription_amount_positive and sub_subscription_expected_inflow_positive. Off-convention vs money domains.
- **Fix:** Retype both to `positive_money_paise NOT NULL`; drop the two redundant positivity CHECKs. Keep sub_subscription_min_amount (>=1000000) — that is a business floor the domain does not express.
- **Evidence:** 01_core.sql:582 `amount BIGINT NOT NULL`, 586 `expected_inflow_amount BIGINT NOT NULL`; CHECKs 613-616

**7. [Medium]** 01_core.sql · risk_buyer_profile / risk_supplier_profile · credit_limit / exposure_cap

- **Issue:** Money columns (paise) as raw BIGINT; credit_limit>0 and exposure_cap>=0 hand-enforced. Off-convention vs money domains. Note buyer_account.credit_limit_paise (file 02) already uses positive_money_paise and sup_account.credit_exposure_cap_paise uses money_paise — these BC3 source-of-truth columns should match their BC9/BC8 projections.
- **Fix:** Retype `credit_limit positive_money_paise NOT NULL` (drop credit_limit_positive) and `exposure_cap money_paise NOT NULL` (drop exposure_cap_nonneg), aligning BC3 with the BC8/BC9 projection types in 02_counterparty_platform.sql.
- **Evidence:** 01_core.sql:701 `credit_limit BIGINT NOT NULL` (CHECK 717 >0); 763 `exposure_cap BIGINT NOT NULL` (CHECK 775 >=0)

**8. [Medium]** 01_core.sql · cash_virtual_account · expected_inflow_total, observed_inflow_total

- **Issue:** Running paise ledgers as raw BIGINT; >=0 hand-enforced. Off-convention vs money_paise.
- **Fix:** Retype both to `money_paise NOT NULL DEFAULT 0`; drop the two redundant non-negativity CHECKs.
- **Evidence:** 01_core.sql:969-970 `expected_inflow_total BIGINT NOT NULL DEFAULT 0`, `observed_inflow_total BIGINT NOT NULL DEFAULT 0`; CHECKs 981-982

**9. [Medium]** 01_core.sql · cash_payout_instruction · gross_amount, net_amount, fee_amount, total_tds_amount

- **Issue:** Four paise columns as raw BIGINT; gross>0/net>0/fee>=0/tds>=0 hand-enforced. Off-convention vs money domains.
- **Fix:** Retype `gross_amount positive_money_paise NOT NULL`, `net_amount positive_money_paise NOT NULL`, `fee_amount money_paise NOT NULL`, `total_tds_amount money_paise` (nullable); drop the four redundant CHECKs.
- **Evidence:** 01_core.sql:1052-1055; CHECKs 1076-1079

**10. [Medium]** 01_core.sql · col_maturity_case / col_claim_case · recovery_total / supplier_indemnity_amount

- **Issue:** Paise columns as raw BIGINT; >=0 hand-enforced. Off-convention vs money_paise.
- **Fix:** Retype `recovery_total money_paise NOT NULL DEFAULT 0` and `supplier_indemnity_amount money_paise` (nullable); drop the two redundant non-negativity CHECKs.
- **Evidence:** 01_core.sql:1490 `recovery_total BIGINT NOT NULL DEFAULT 0` (CHECK 1502); 1604 `supplier_indemnity_amount BIGINT` (CHECK 1615-1616)

**11. [Low]** 01_core.sql · sub_subscription · distribution_outcome (JSONB gross/tds/fee/net)

- **Issue:** Money values are stored inside a JSONB blob and cast to BIGINT in the net-equality CHECK (sub_subscription_distribution_net_check). JSONB-embedded money escapes the money_paise domain entirely: there is no type enforcement that gross/tds/fee/net are non-negative integers (a string or negative could be stored; only the arithmetic identity is checked). Same pattern in cash_payout_instruction.payload tds_snapshot and legal_assignment_set.legs[].allocation_paise and cash_recon_ledger.discrepancies. Flagged as Info-to-Low because the spec deliberately keeps these as JSONB value-objects.
- **Fix:** If these JSONB money sub-fields are load-bearing, add per-field non-negativity to the existing CHECK, e.g. extend sub_subscription_distribution_net_check with `AND (distribution_outcome->>'gross')::BIGINT >= 0 AND (distribution_outcome->>'tds')::BIGINT >= 0 AND (distribution_outcome->>'fee')::BIGINT >= 0 AND (distribution_outcome->>'net')::BIGINT >= 0`. Longer term, prefer promoting frequently-queried money out of JSONB into typed money_paise columns (as cash_payout_instruction already does with gross/net/fee/tds), reserving JSONB for non-invariant detail.
- **Evidence:** 01_core.sql:624-633 CHECK casts `(distribution_outcome->>'gross')::BIGINT` etc.; no per-field >=0 guard

**12. [Info]** 02_counterparty_platform.sql · comp_aml_screening · match_score

- **Issue:** NUMERIC(5,4) appears in a grep for non-integer numeric types, but this is a vendor match/probability score in 0..1, not money or a rate. NUMERIC is the correct type and it is already bounded by comp_aml_screening_match_score_range CHECK (0..1). No action needed; recorded so the reviewer knows it was evaluated and cleared.
- **Fix:** No change required — NUMERIC is appropriate for a bounded probability score; CHECK already enforces the 0..1 range.
- **Evidence:** 02_counterparty_platform.sql:1000 `match_score NUMERIC(5,4)`; CHECK 1023-1024


### FK integrity, cross-file load order, and migration split  (7)

**1. [Medium]** SQL_FILES_MANIFEST.md · (manifest)

- **Issue:** Manifest's Execution Order Rationale is FACTUALLY WRONG/STALE. It claims 03_auth adds FKs on tables 'tblAdminUser', 'tblInvestorAccount', 'tblAcknowledgmentUser' created by 02. Those three table names DO NOT EXIST in the schema. The real tables are admin_user, inv_account, buyer_ack_user (02 lines 737/272/640). The FKs to auth_identity are added in 03 (lines 300-322), not 'by 02'. This stale doc is the named target of this audit's verification.
- **Fix:** Rewrite manifest line 52 to: '03_auth.sql adds identity_id foreign keys on admin_user, inv_account, and buyer_ack_user (created in 02) referencing auth_identity. It also requires the citext extension created in 01.' Note this is docs/sql guidance only; the binding load order will be encoded by Flyway version numbers (V1..Vn), making this manifest non-authoritative once migrations exist.
- **Evidence:** Manifest line 52: 'adds foreign-key constraints on tables (tblAdminUser, tblInvestorAccount, tblAcknowledgmentUser)'. Actual: 03_auth.sql:300 ALTER TABLE admin_user ... ADD CONSTRAINT admin_user_identity_fk FOREIGN KEY (identity_id) REFERENCES auth_identity; same pattern for inv_account (310) and buyer_ack_user (320).

**2. [Medium]** SQL_FILES_MANIFEST.md · auth_identity · email

- **Issue:** Manifest omits the 01→03 dependency: 03_auth.sql uses CITEXT for auth_identity.email, but the citext extension is created in 01 (SECTION 0A). If a migrator ever ran 03 standalone (or split auth into its own DB init) it would fail with 'type citext does not exist'. The manifest only documents the 02→03 FK dependency.
- **Fix:** When porting to Flyway, put 'CREATE EXTENSION IF NOT EXISTS citext;' in V1 (the first/core migration) so it precedes any auth migration. Document the 01→03 citext edge alongside the FK edge. Verify the DB role running Flyway has CREATE EXTENSION privilege (superuser or rds_superuser); otherwise pre-provision the extension out-of-band and keep IF NOT EXISTS.
- **Evidence:** 03_auth.sql:88 'email CITEXT NOT NULL'; 01_core.sql:23 'CREATE EXTENSION IF NOT EXISTS citext; -- used by auth_identity.email (03_auth.sql)'.

**3. [Medium]** 04_generic_acl.sql · sys_audit_event · (trigger prevent_audit_modification / fn_prevent_audit_modification)

- **Issue:** Per project policy (no procedural DB triggers/functions; keep DB simple), the only trigger+function in the bundle should be reconsidered. It is a plpgsql BEFORE UPDATE/DELETE trigger enforcing append-only (AE.1). Policy explicitly flags this file's trigger/function for reconsideration. It also does not actually stop a superuser (the comment admits pg_dump/restore bypass), so its guarantee is partial.
- **Fix:** Prefer DECLARATIVE enforcement: revoke UPDATE/DELETE on sys_audit_event from all application roles (GRANT INSERT, SELECT ON sys_audit_event TO app_role; no UPDATE/DELETE grant). This is declarative, debuggable, and survives policy. If a DB-level hard guard is still wanted, keep the trigger but treat it as a documented exception in DECISION_LOG (DL-BE). The cryptographic chain hashing (previous_envelope_hash, envelope_hash over canonical encoding) is correctly left as an APP-LAYER responsibility per AE.2 — keep it there; do NOT add a hashing trigger.
- **Evidence:** 04_generic_acl.sql:222-244 CREATE OR REPLACE FUNCTION fn_prevent_audit_modification() ... RAISE EXCEPTION; CREATE TRIGGER prevent_audit_modification BEFORE UPDATE OR DELETE ON sys_audit_event.

**4. [Low]** 04_generic_acl.sql · sys_audit_event · corrects

- **Issue:** sys_audit_event.corrects is a self-referential hard FK declared DEFERRABLE INITIALLY DEFERRED. This is the ONLY deferrable FK in the bundle and is the correct choice for an append-only chain where a correcting envelope and its target may be inserted in the same tx, but app-layer native-SQL/jOOQ INSERT paths must not assume immediate checking. Note for the write-path team.
- **Fix:** No DDL change needed — the deferrable self-FK is correct. Document in DECISION_LOG (DL-BE) that the audit-append write path must insert the corrected (target) event before/within the same tx as the correcting event, and that bulk/native inserts rely on deferred constraint checking at COMMIT.
- **Evidence:** 04_generic_acl.sql:159-160 'corrects UUID REFERENCES sys_audit_event (event_id) DEFERRABLE INITIALLY DEFERRED'.

**5. [Info]** 01_core.sql · (migration split)

- **Issue:** Recommended Flyway migration split (V1..Vn) with placement of domains/enums, derived from the verified dependency graph. Single-file-per-migration is cleanest and matches the 1:1 file->wave mapping; the cross-file edges (citext in V1, FK-back in V3, money_paise in V4) constrain ordering only by version number.
- **Fix:** V1__core.sql = 01_core.sql verbatim (extensions + SECTION 0 domains + core enums + 17 core tables + intra-file FKs). DOMAINs and the citext EXTENSION MUST live in V1 so they are visible to V2/V3/V4 (a Flyway migration runs in its own tx but DDL is committed before the next runs, so types are available). V2__counterparty.sql = 02 verbatim (23 tables + intra-file deferred-FK ALTER block at its end). V3__auth.sql = 03 verbatim (auth enums + 5 auth tables + the 3 ALTER...ADD FOREIGN KEY ... REFERENCES auth_identity statements + indexes). V4__generic_acl.sql = 04 verbatim (its enums + sys_/gate_ tables; uses money_paise from V1). Keep ddl-auto=validate. Do NOT split domains/enums into a separate V0 'types' migration unless you find a second consumer ordering conflict — none exists; co-locating them in V1 is simpler and the graph confirms V1 precedes every consumer.
- **Evidence:** Graph edges: 01->03 (citext), 02->03 (3 identity FKs), 01->04 (money_paise). No cycles.

**6. [Info]** 02_counterparty_platform.sql · admin_user, inv_account, buyer_ack_user · identity_id

- **Issue:** Confirmed clean split of the identity_id wiring: 02 declares the column + UNIQUE (admin_user_identity_uq:751, inv_account_identity_uq:303, buyer_ack_user_identity_uq:655) but defers the FK to 03. This is the correct way to avoid a 02->03 forward FK that would break load order. sup_account intentionally has NO identity_id (DL-012/013, Phase-1 login-less supplier) — verified absent.
- **Fix:** No change. Preserve this column-in-02 / FK-in-03 split exactly when porting to V2/V3 so neither migration has a forward dependency. The UNIQUE constraints (one-to-one identity mapping) stay in V2 with the column.
- **Evidence:** 02 lines 740/275/644 declare identity_id; 03 lines 300-322 add the FKs. 02:419/455 confirm sup_account has no identity_id by design.

**7. [Info]** 04_generic_acl.sql · gate_vendor_instruction, gate_inflow_observation, gate_signature_session, sys_notification_dispatch · linked_payout_instruction_id, va_id, signature_request_id, doc_hash, recipient_identity_id, causation_event_id

- **Issue:** All cross-context references in 04 are intentionally SOFT FKs (no REFERENCES clause), consistent with the no-cross-BC-join rule. This means there is NO declarative integrity guard linking these to BC4 (cash_*), BC5 (legal_*), BC16 (sys_document_object), or auth_identity. Orphan rows are possible by design; the policy bias toward declarative constraints is deliberately overridden here for context isolation.
- **Fix:** No DDL change (cross-BC isolation is the constitution). But note recipient_identity_id's comment (line 311 'FK to auth_identity') is misleading since it is NOT a hard FK — and auth_identity lives in a different context anyway. Optionally tighten the comment to '(soft) identity anchor' to avoid a reviewer adding a hard cross-context FK later. Integrity for these soft links is an APP-LAYER responsibility; flag for the ArchUnit/repository layer.
- **Evidence:** 04 lines 301/530/604/620/660/675 columns present with NO REFERENCES; comments ND.3/VI.4/IO.4/VS.6/VS.7 state 'SOFT FK — no REFERENCES constraint'. recipient_identity_id (291) is described as 'FK to auth_identity' in comments but has NO REFERENCES clause.


### Bounded-context purity  (15)

**1. [High]** cash_payout_instruction (BC4) · subscription_id

- **Issue:** Hard FK from BC4 Settlement to BC2 Subscription (cash_payout_instruction.subscription_id -> sub_subscription.subscription_id). This crosses the BC4<->BC2 boundary with referential integrity. Per B1 §4.1 BC2->BC4 coordinate via events (refund instructions / distribution targets), i.e. by identity, not FK. It also couples a settlement row's existence to a subscription row.
- **Fix:** Drop cash_payout_instruction_subscription_fk; keep subscription_id as a plain UUID identity reference (the refund target arrives via the BC2 RefundEligible event). Same treatment as the listing_id reference if you adopt the uniform identity-reference policy.
- **Evidence:** 01_core.sql:1071-1072 CONSTRAINT cash_payout_instruction_subscription_fk FOREIGN KEY (subscription_id) REFERENCES sub_subscription (subscription_id)

**2. [High]** col_claim_case (BC6) · listing_id

- **Issue:** Hard FK from BC6 Collections to BC1 deal_listing (col_claim_case.listing_id -> deal_listing.listing_id), in addition to the legitimate same-BC FK to col_maturity_case. Cross-BC FK BC6->BC1.
- **Fix:** Drop col_claim_case_listing_fk; reach the listing via the same-BC col_maturity_case (which already holds the listing_id reference) or keep listing_id as a bare identity column. Retain col_claim_case_maturity_fk (same-BC, clean).
- **Evidence:** 01_core.sql:1611-1612 CONSTRAINT col_claim_case_listing_fk FOREIGN KEY (listing_id) REFERENCES deal_listing (listing_id)

**3. [Medium]** comp_aml_screening / comp_sar_case / comp_kyc_file / comp_spot_check (BC11) · adjudicated_by, raised_by, approver_id, completed_by

- **Issue:** BC11 Compliance tables carry hard FOREIGN KEYs to BC10 admin_user(admin_user_id). This is a cross-BC FK (BC11 -> BC10). Admin IAM is an Open Host Service (B1 §4.1) whose actor identity should be referenced by claim, not a DB join. The FK couples Compliance row lifecycle to Admin IAM and permits cross-BC joins.
- **Fix:** Drop these FKs; keep the columns as plain UUID 'actor identity reference' (mirror how risk_buyer_profile.buyer_id documents shared identity with no FK). Validate the actor's role/existence at the command handler via the IAM claim, not via DB referential integrity.
- **Evidence:** 02_counterparty_platform.sql:1014 FK (adjudicated_by) REFERENCES admin_user; :1064 (raised_by); :1136 (approver_id); :1188 (completed_by)

**4. [Medium]** inv_invite, inv_account (BC7) · issued_by, kyc_approved_by

- **Issue:** BC7 Investor Onboarding -> BC10 admin_user hard FKs (added in deferred ALTER block). Cross-BC FK to the IAM Open Host Service.
- **Fix:** Drop inv_invite_issued_by_fk and inv_account_kyc_approved_by_fk; retain columns as bare UUID actor references resolved against IAM at the application layer.
- **Evidence:** 02_counterparty_platform.sql:1465 inv_invite.issued_by FK -> admin_user; :1469 inv_account.kyc_approved_by FK -> admin_user

**5. [Medium]** sup_account, sup_agency_consent (BC8) · kyc_approved_by, revoked_by

- **Issue:** BC8 Supplier Onboarding -> BC10 admin_user hard FKs. Cross-BC FK to IAM OHS.
- **Fix:** Drop sup_account_kyc_approved_by_fk and sup_agency_consent_revoked_by_fk; keep as plain UUID identity references.
- **Evidence:** 02_counterparty_platform.sql:1473 sup_account.kyc_approved_by FK -> admin_user; :1478 sup_agency_consent.revoked_by FK -> admin_user

**6. [Medium]** buyer_account, buyer_ack_user, buyer_payment_rule (BC9) · nominated_by, designated_by, confirmed_by

- **Issue:** BC9 Buyer Management -> BC10 admin_user hard FKs (deferred ALTER block). Cross-BC FK to IAM OHS.
- **Fix:** Drop the three actor FKs; keep columns as bare UUID identity references validated at the command boundary.
- **Evidence:** 02_counterparty_platform.sql:1483 nominated_by; :1487 designated_by; :1491 confirmed_by — all FK -> admin_user

**7. [Medium]** audit_scope, audit_account (BC13) · defined_by, proposed_by, approved_by

- **Issue:** BC13 Auditor Access -> BC10 admin_user hard FKs. Cross-BC FK to IAM OHS. (audit_account.identity_id -> auth_identity is a separate, acceptable auth-layer kernel reference; see note.)
- **Fix:** Drop defined_by/proposed_by/approved_by FKs to admin_user; keep as UUID identity references. The maker-checker (proposed_by <> approved_by) CHECK at audit_account:1414 is intra-row and stays.
- **Evidence:** 02_counterparty_platform.sql:1346 audit_scope.defined_by FK -> admin_user; :1403 audit_account.proposed_by FK -> admin_user; :1405 audit_account.approved_by FK -> admin_user

**8. [Medium]** cash_virtual_account, cash_payout_instruction (BC4) · listing_id

- **Issue:** Hard FK BC4 Settlement -> BC1 deal_listing. Cross-BC. Inconsistent with the project's own identity-reference pattern (deal_listing.va_id intentionally has NO FK to cash_virtual_account, per comment at 01_core.sql:459/520 — the back-reference is identity-only, but the forward references here are hard FKs).
- **Fix:** Decide one direction-agnostic rule. To honour 'no cross-BC FK', drop both listing_id FKs and keep listing_id as identity references; the VA-per-listing UNIQUE (cash_virtual_account_listing_uq) still holds locally. If instead you keep them as a deliberate exception, document it in DECISION_LOG (DL-BE) and apply the SAME choice to risk_default_case/legal_assignment_set/col_maturity_case for consistency.
- **Evidence:** 01_core.sql:977-978 cash_virtual_account_listing_fk -> deal_listing; :1069-1070 cash_payout_instruction_listing_fk -> deal_listing; contrast deal_listing.va_id comment 'FK to cash_virtual_account' but NO actual FK declared

**9. [Medium]** risk_default_case (BC3) · listing_id

- **Issue:** Hard FK BC3 Credit & Underwriting -> BC1 deal_listing. Cross-BC. B1 §4.1 has Collections->Credit (classification request) and Credit->Listing as event/snapshot relationships, not FK joins.
- **Fix:** Drop risk_default_case_listing_fk; keep listing_id as identity reference. Retain risk_default_case_corrects_fk (self-reference, same-BC, clean).
- **Evidence:** 01_core.sql:908-909 CONSTRAINT risk_default_case_listing_fk FOREIGN KEY (listing_id) REFERENCES deal_listing (listing_id)

**10. [Medium]** legal_assignment_set (BC5) · listing_id

- **Issue:** Hard FK BC5 Assignment & Signing -> BC1 deal_listing. Cross-BC. BC1->BC5 coordination is the AssignmentsRequested event (B1 §4.1), not a FK.
- **Fix:** Drop legal_assignment_set_listing_fk; keep listing_id as identity reference. The one-set-per-listing UNIQUE (legal_assignment_set_listing_uq) is local and stays.
- **Evidence:** 01_core.sql:1343-1344 CONSTRAINT legal_assignment_set_listing_fk FOREIGN KEY (listing_id) REFERENCES deal_listing (listing_id)

**11. [Medium]** col_maturity_case (BC6) · listing_id

- **Issue:** Hard FK BC6 Collections -> BC1 deal_listing. Cross-BC. BC1->BC6 coordination is the Listing.Disbursed event.
- **Fix:** Drop col_maturity_case_listing_fk; keep listing_id as identity reference. Retain col_maturity_case_listing_uq (local UNIQUE).
- **Evidence:** 01_core.sql:1497-1498 CONSTRAINT col_maturity_case_listing_fk FOREIGN KEY (listing_id) REFERENCES deal_listing (listing_id)

**12. [Info]** deal_listing (BC1) / sub_subscription (BC2) / deal_invoice (BC1) · invoice_id, listing_id

- **Issue:** These FKs are within or directly down the core-domain aggregate spine. deal_listing.invoice_id->deal_invoice is intra-BC1 (clean). sub_subscription.listing_id->deal_listing is BC2->BC1 and IS a cross-BC FK by the strict rule, but it is the central transactional join the funding-equality invariant (G10) depends on.
- **Fix:** Keep deal_listing_invoice_fk (same BC). For sub_subscription.listing_id, apply whatever uniform cross-BC policy you choose; if any cross-BC FK is tolerated as a documented exception, the BC2->BC1 listing edge is the strongest candidate (G10 paise-equality). Record the exception in DECISION_LOG.
- **Evidence:** 01_core.sql:477-478 deal_listing_invoice_fk (intra-BC1, clean); 01_core.sql:607-608 sub_subscription_listing_fk -> deal_listing (BC2->BC1)

**13. [Info]** risk_pricing_policy, buyer_payment_rule, admin_sod_policy (self), admin_role_assignment, admin_deviation_log, admin_user (BC3/BC9/BC10) · superseded_by, disabled_by, assigned_by, revoked_by, reviewed_by, published_by, deviation_register_entry_id

- **Issue:** These are intra-BC FKs (self-references and within-BC10 references). They are CLEAN under the rule — no boundary crossed. Listed for completeness so they are not mistaken for violations.
- **Fix:** No change. These FKs stay; they enforce integrity inside a single bounded context.
- **Evidence:** 01_core.sql:831-832 risk_pricing_policy_superseded_fk (self); 02:705-706 buyer_payment_rule_superseded_fk (self); 02:753/828-833/901-903/958-961 admin_* intra-BC10 FKs; 02:1495-1498 admin_role_assignment_deviation_entry_fk (intra-BC10)

**14. [Info]** admin_user, inv_account, buyer_ack_user, audit_account (BC10/BC7/BC9/BC13) · identity_id

- **Issue:** identity_id FKs to auth_identity (03_auth.sql ALTER block) reference the auth/identity layer (auth_* tables, the shared kernel's identity substrate). This is the canonical identity-binding join, not a business-context cross-join. Treat as acceptable shared-kernel reference, NOT a BC-purity violation.
- **Fix:** No change. Note sup_account intentionally has NO identity_id (DL-012, login-less supplier) — correctly omitted from the ALTER block (03_auth.sql:292-293).
- **Evidence:** 03_auth.sql:300-302 admin_user.identity_id FK; :310-312 inv_account.identity_id FK; :320-322 buyer_ack_user.identity_id FK; 02:1399 audit_account_identity_uq + auth FK pattern

**15. [Info]** tax_tds_deduction (BC12) · payout_instruction_id, listing_id, investor_id

- **Issue:** Comments label payout_instruction_id as 'FK -> BC4 cash_payout_instruction' and investor_id as 'FK -> inv_account', but NO actual FOREIGN KEY constraints are declared (grep confirms none on tax_tds_deduction). This is the CORRECT identity-reference pattern for cross-BC, but the misleading 'FK' comments should be fixed to avoid an implementer adding the FK.
- **Fix:** Edit the comments from 'FK ->' to 'identity reference ->' on tax_tds_deduction.investor_id/payout_instruction_id/listing_id and the other cross-context comment columns (comp_*/tax_* subject_id, inv_account.mia_agreement_id, sup_account.maa_agreement_id, buyer_account.pricing_band_id) so the intent (no DB FK) is unambiguous.
- **Evidence:** 02_counterparty_platform.sql:1248 '-- FK -> inv_account', :1252 '-- payout_instruction_id: FK -> BC4 ...'; no FOREIGN KEY in CREATE TABLE tax_tds_deduction (grep of file shows none for tax_*)


### Indexes  (26)

**1. [High]** 01_core.sql · risk_pricing_policy · superseded_by

- **Issue:** Self-referential FK superseded_by REFERENCES risk_pricing_policy(pricing_band_id) has NO supporting index. This is also a genuine query path: the re-pricing chain walk ('which band did this one supersede / who superseded this band') and the supersession write (setting prior.superseded_by) both touch it. The only indexes are the PK, idx_risk_pricing_policy_buyer (buyer_id), and the partial-unique on active rows.
- **Fix:** CREATE INDEX idx_risk_pricing_policy_superseded_by ON risk_pricing_policy (superseded_by) WHERE superseded_by IS NOT NULL;
- **Evidence:** L831 risk_pricing_policy_superseded_fk FOREIGN KEY (superseded_by) REFERENCES risk_pricing_policy (pricing_band_id); no CREATE INDEX on superseded_by

**2. [Medium]** 02_counterparty_platform.sql · buyer_payment_rule · superseded_by

- **Issue:** Self-referential FK superseded_by REFERENCES buyer_payment_rule(instruction_id) has NO supporting index. Same supersession-chain access pattern as risk_pricing_policy. Only buyer_id and the partial-unique-on-current index exist.
- **Fix:** CREATE INDEX idx_buyer_payment_rule_superseded_by ON buyer_payment_rule (superseded_by) WHERE superseded_by IS NOT NULL;
- **Evidence:** L705 buyer_payment_rule_superseded_fk FOREIGN KEY (superseded_by) REFERENCES buyer_payment_rule (instruction_id); no index on superseded_by

**3. [Medium]** 01_core.sql · risk_default_case · corrects_case_id

- **Issue:** Self-referential FK corrects_case_id REFERENCES risk_default_case(case_id) has NO index. The re-classification chain (DC.3/G23) is walked to find correcting cases for a prior case; this is a real lookup path as well as an unindexed FK.
- **Fix:** CREATE INDEX idx_risk_default_case_corrects ON risk_default_case (corrects_case_id) WHERE corrects_case_id IS NOT NULL;
- **Evidence:** L910 risk_default_case_corrects_fk FOREIGN KEY (corrects_case_id) REFERENCES risk_default_case (case_id); only idx_risk_default_case_listing and _status exist

**4. [Medium]** 02_counterparty_platform.sql · comp_kyc_file · approver_id

- **Issue:** FK approver_id REFERENCES admin_user(admin_user_id) is unindexed. Doubles as a query path: 'all KYC files approved by reviewer X' (auditor / reviewer-the-reviewer trail). Also slows admin_user archival RESTRICT check.
- **Fix:** CREATE INDEX idx_comp_kyc_file_approver ON comp_kyc_file (approver_id) WHERE approver_id IS NOT NULL;
- **Evidence:** L1135 comp_kyc_file_approver_fk FOREIGN KEY (approver_id) REFERENCES admin_user (admin_user_id); indexes are only on (subject_id,subject_type) and partial status

**5. [Medium]** 02_counterparty_platform.sql · tax_tds_deduction · listing_id

- **Issue:** listing_id is a cross-context reference to BC1 deal_listing used in reconciliation/distribution (per-listing TDS aggregation), but there is no standalone index leading on listing_id. The existing indexes lead on investor_id (investor_listing, investor_fy), so a 'all TDS rows for listing X' reconciliation scan is unsupported. NOTE: it is a soft cross-context ref (no FK), so this is purely a hot-path index gap, not an FK gap.
- **Fix:** CREATE INDEX idx_tax_tds_deduction_listing ON tax_tds_deduction (listing_id);
- **Evidence:** L1283-1285 idx_tax_tds_deduction_investor_listing (investor_id, listing_id), idx_..._investor_fy, idx_..._payout_instruction — none leads on listing_id

**6. [Medium]** 01_core.sql · legal_master_agreement · party_id, kind

- **Issue:** MA.1 asserts 'one MasterAgreement per (party_id, kind) in {signed, stamped} status at any time' but this is left entirely to the app layer. The DB has only a plain index idx_legal_master_agreement_party (party_id, kind) — no partial-unique guard. The DB is meant to be the last line of defence; a duplicate active MAA/MIA for the same party can slip through on a race.
- **Fix:** CREATE UNIQUE INDEX uidx_legal_master_agreement_active_party_kind ON legal_master_agreement (party_id, kind) WHERE status IN ('signed','stamped'); (keep the existing non-unique index for the broader lookup, or drop it as redundant once the partial-unique covers the hot path)
- **Evidence:** L1245 MA.1 comment '...app layer enforces this before Initiate'; L1297 CREATE INDEX idx_legal_master_agreement_party ON (party_id, kind) is non-unique

**7. [Medium]** 02_counterparty_platform.sql · sup_account · gstin

- **Issue:** GSTIN is a natural business key for a supplier (one entity per GSTIN) yet sup_account.gstin has neither a UNIQUE constraint nor an index. pan is NOT NULL and indexed (idx_sup_account_pan) but also not UNIQUE — two supplier rows can share the same PAN/GSTIN silently. Per the maximize-declarative-constraints policy these natural keys should be UNIQUE at the DB.
- **Fix:** If business rules allow exactly one active supplier per PAN/GSTIN: CREATE UNIQUE INDEX uidx_sup_account_pan ON sup_account (pan); CREATE UNIQUE INDEX uidx_sup_account_gstin ON sup_account (gstin) WHERE gstin IS NOT NULL; (confirm with spec — proprietorships can share PAN across entities, so GSTIN is the safer unique key). At minimum add a plain index on gstin for lookup.
- **Evidence:** L426-427 pan pan_type NOT NULL, gstin gstin_type; L477 idx_sup_account_pan is a plain (non-unique) index; no constraint on gstin

**8. [Medium]** 02_counterparty_platform.sql · buyer_account · gstin, mca_cin

- **Issue:** buyer_account.gstin and buyer_account.mca_cin are natural identifiers but have no UNIQUE constraint and no index. Buyers are looked up / de-duplicated by CIN/GSTIN during nomination and BC17 verification (BA.3), and the same legal entity must not be nominated twice.
- **Fix:** CREATE UNIQUE INDEX uidx_buyer_account_gstin ON buyer_account (gstin) WHERE gstin IS NOT NULL; CREATE UNIQUE INDEX uidx_buyer_account_mca_cin ON buyer_account (mca_cin) WHERE mca_cin IS NOT NULL;
- **Evidence:** L583-584 mca_cin CHAR(21), gstin gstin_type; only idx_buyer_account_status exists

**9. [Low]** 04_generic_acl.sql · gate_signature_session · doc_hash

- **Issue:** doc_hash is a soft FK to sys_document_object and part of the composite UNIQUE (signature_request_id, doc_hash). A lookup by doc_hash alone (find all signing sessions for a document) cannot use that composite index because doc_hash is not the leading column. Low because signature_request_id is the dominant access path and is separately indexed.
- **Fix:** CREATE INDEX idx_vsr_doc_hash ON gate_signature_session (doc_hash); (only if doc-centric lookups are needed)
- **Evidence:** L692 UNIQUE (signature_request_id, doc_hash); L729 idx_vsr_signature_request leads on signature_request_id only

**10. [Low]** 02_counterparty_platform.sql · inv_account · kyc_approved_by

- **Issue:** FK kyc_approved_by REFERENCES admin_user(admin_user_id) (added L1467) is unindexed. Low because admin_user is archived not hard-deleted (RESTRICT check is rare) and 'investors approved by reviewer X' is not a primary screen, but it is still an unindexed FK.
- **Fix:** CREATE INDEX idx_inv_account_kyc_approved_by ON inv_account (kyc_approved_by) WHERE kyc_approved_by IS NOT NULL;
- **Evidence:** L1467-1469 inv_account_kyc_approved_by_fk; no CREATE INDEX on kyc_approved_by

**11. [Low]** 02_counterparty_platform.sql · sup_account · kyc_approved_by

- **Issue:** FK kyc_approved_by REFERENCES admin_user(admin_user_id) (added L1472) is unindexed.
- **Fix:** CREATE INDEX idx_sup_account_kyc_approved_by ON sup_account (kyc_approved_by) WHERE kyc_approved_by IS NOT NULL;
- **Evidence:** L1472-1474 sup_account_kyc_approved_by_fk; only idx_sup_account_status and idx_sup_account_pan exist

**12. [Low]** 02_counterparty_platform.sql · sup_agency_consent · revoked_by

- **Issue:** FK revoked_by REFERENCES admin_user(admin_user_id) (added L1476) is unindexed.
- **Fix:** CREATE INDEX idx_sup_agency_consent_revoked_by ON sup_agency_consent (revoked_by) WHERE revoked_by IS NOT NULL;
- **Evidence:** L1476-1478 sup_agency_consent_revoked_by_fk; only partial idx_sup_agency_consent_supplier_active exists

**13. [Low]** 02_counterparty_platform.sql · buyer_account · nominated_by

- **Issue:** FK nominated_by REFERENCES admin_user(admin_user_id) (added L1481) is unindexed (NOT NULL column).
- **Fix:** CREATE INDEX idx_buyer_account_nominated_by ON buyer_account (nominated_by);
- **Evidence:** L1481-1483 buyer_account_nominated_by_fk; only idx_buyer_account_status exists

**14. [Low]** 02_counterparty_platform.sql · buyer_ack_user · designated_by

- **Issue:** FK designated_by REFERENCES admin_user(admin_user_id) (added L1485) is unindexed.
- **Fix:** CREATE INDEX idx_buyer_ack_user_designated_by ON buyer_ack_user (designated_by);
- **Evidence:** L1485-1487 buyer_ack_user_designated_by_fk; only partial idx_buyer_ack_user_buyer_active exists

**15. [Low]** 02_counterparty_platform.sql · buyer_payment_rule · confirmed_by

- **Issue:** FK confirmed_by REFERENCES admin_user(admin_user_id) (added L1489) is unindexed.
- **Fix:** CREATE INDEX idx_buyer_payment_rule_confirmed_by ON buyer_payment_rule (confirmed_by);
- **Evidence:** L1489-1491 buyer_payment_rule_confirmed_by_fk; only idx_buyer_payment_rule_buyer and the partial-unique-current index exist

**16. [Low]** 02_counterparty_platform.sql · admin_user · disabled_by

- **Issue:** Self-referential FK disabled_by REFERENCES admin_user(admin_user_id) is unindexed.
- **Fix:** CREATE INDEX idx_admin_user_disabled_by ON admin_user (disabled_by) WHERE disabled_by IS NOT NULL;
- **Evidence:** L753 admin_user_disabled_by_fk; only idx_admin_user_email plus the identity/email UNIQUE indexes exist

**17. [Low]** 02_counterparty_platform.sql · admin_role_assignment · assigned_by, revoked_by, deviation_register_entry_id

- **Issue:** Three FKs unindexed: assigned_by and revoked_by (self-ref to admin_user) and deviation_register_entry_id (REFERENCES admin_deviation_log, added L1495). The deviation FK in particular is the inverse of admin_deviation_log->assignment and is walked when reconciling soft-SoD overrides.
- **Fix:** CREATE INDEX idx_admin_role_assignment_assigned_by ON admin_role_assignment (assigned_by); CREATE INDEX idx_admin_role_assignment_revoked_by ON admin_role_assignment (revoked_by) WHERE revoked_by IS NOT NULL; CREATE INDEX idx_admin_role_assignment_deviation_entry ON admin_role_assignment (deviation_register_entry_id) WHERE deviation_register_entry_id IS NOT NULL;
- **Evidence:** L830/L832 assigned_by/revoked_by FKs; L1495-1498 admin_role_assignment_deviation_entry_fk; existing indexes are PK(admin_user_id,role), idx_..._admin_user, partial idx_..._role_active

**18. [Low]** 02_counterparty_platform.sql · admin_deviation_log · reviewed_by

- **Issue:** FK reviewed_by REFERENCES admin_user(admin_user_id) is unindexed.
- **Fix:** CREATE INDEX idx_admin_deviation_log_reviewed_by ON admin_deviation_log (reviewed_by) WHERE reviewed_by IS NOT NULL;
- **Evidence:** L902 admin_deviation_log_reviewed_by_fk; indexes are only idx_admin_deviation_log_admin_user and partial review_status

**19. [Low]** 02_counterparty_platform.sql · admin_sod_policy · superseded_by, published_by

- **Issue:** Self-ref FK superseded_by and FK published_by (to admin_user) are both unindexed. Volume is tiny (one active policy at a time), so impact is negligible, but they are still unindexed FKs.
- **Fix:** CREATE INDEX idx_admin_sod_policy_published_by ON admin_sod_policy (published_by); (superseded_by index optional given tiny table)
- **Evidence:** L958/L960 admin_sod_policy_superseded_fk / _published_by_fk; only the uidx_admin_sod_policy_one_active partial-unique exists

**20. [Low]** 02_counterparty_platform.sql · comp_aml_screening · adjudicated_by

- **Issue:** FK adjudicated_by REFERENCES admin_user(admin_user_id) is unindexed.
- **Fix:** CREATE INDEX idx_comp_aml_screening_adjudicated_by ON comp_aml_screening (adjudicated_by) WHERE adjudicated_by IS NOT NULL;
- **Evidence:** L1013 comp_aml_screening_adjudicated_by_fk; indexes are only idx_comp_aml_screening_subject and _status

**21. [Low]** 02_counterparty_platform.sql · comp_sar_case · raised_by

- **Issue:** FK raised_by REFERENCES admin_user(admin_user_id) (NOT NULL) is unindexed.
- **Fix:** CREATE INDEX idx_comp_sar_case_raised_by ON comp_sar_case (raised_by);
- **Evidence:** L1063 comp_sar_case_raised_by_fk; only idx_comp_sar_case_subject exists

**22. [Low]** 02_counterparty_platform.sql · comp_spot_check · completed_by

- **Issue:** FK completed_by REFERENCES admin_user(admin_user_id) (NOT NULL) is unindexed.
- **Fix:** CREATE INDEX idx_comp_spot_check_completed_by ON comp_spot_check (completed_by);
- **Evidence:** L1187 comp_spot_check_completed_by_fk; only idx_comp_spot_check_period exists

**23. [Low]** 02_counterparty_platform.sql · audit_account · proposed_by, approved_by

- **Issue:** FKs proposed_by (NOT NULL) and approved_by REFERENCES admin_user(admin_user_id) are unindexed. scope_id and identity_id are indexed but the maker-checker actor columns are not.
- **Fix:** CREATE INDEX idx_audit_account_proposed_by ON audit_account (proposed_by); CREATE INDEX idx_audit_account_approved_by ON audit_account (approved_by) WHERE approved_by IS NOT NULL;
- **Evidence:** L1402/L1404 audit_account_proposed_by_fk / _approved_by_fk; indexes idx_audit_account_identity/status/valid_until/scope only

**24. [Low]** 01_core.sql · cash_payout_instruction · maker_id, checker_id

- **Issue:** maker_id (NOT NULL) and checker_id are soft references to admin_user (no hard FK declared) used in the maker-checker audit trail; neither is indexed. 'All payout instructions drafted/approved by admin X' is a plausible treasury-audit query. No FK so this is a hot-path-only consideration.
- **Fix:** CREATE INDEX idx_cash_payout_instruction_maker ON cash_payout_instruction (maker_id); (add checker_id index only if that query path is needed)
- **Evidence:** L1057-1058 maker_id / checker_id; indexes only on listing_id, subscription_id, status, kind

**25. [Info]** 01_core.sql · deal_listing · va_id

- **Issue:** va_id is documented as a reference to cash_virtual_account (C8/DL-043) but is declared as a plain UUID with NO FK and NO index. The reverse direction (cash_virtual_account.listing_id) carries the UNIQUE/FK, so the one-VA-per-listing invariant is anchored there; deal_listing.va_id is effectively a denormalised back-pointer. No action required unless deal_listing.va_id is used as a lookup key, but worth noting it is not constraint-backed.
- **Fix:** No index needed (reverse FK on cash_virtual_account.listing_id is UNIQUE). Optionally add CONSTRAINT deal_listing_va_fk FOREIGN KEY (va_id) REFERENCES cash_virtual_account(va_id) for referential integrity, with a partial index, if the back-pointer must be trustworthy.
- **Evidence:** L459 va_id UUID; comment L520 'Reference to cash_virtual_account'; no FK, no index in 01_core.sql

**26. [Info]** 04_generic_acl.sql · sys_audit_event · (trigger) fn_prevent_audit_modification

- **Issue:** Outside the index dimension but flagged per project policy: 04_generic_acl.sql defines a plpgsql trigger fn_prevent_audit_modification enforcing append-only (AE.1). Project policy is no procedural DB triggers/functions (keep DB simple; audit-chain hashing is an APP-LAYER responsibility). Reconsider replacing this with table-level REVOKE UPDATE,DELETE privileges + WORM substrate (G7) instead of a row-level trigger. This is the existing trigger the policy explicitly calls out.
- **Fix:** Replace with declarative privilege model: REVOKE UPDATE, DELETE ON sys_audit_event FROM <app_role>; rely on WORM storage substrate (G7) for superuser paths. Keep append-only enforcement in the app command surface. Removes a plpgsql function from the schema per the no-trigger policy.
- **Evidence:** L222-244 CREATE OR REPLACE FUNCTION fn_prevent_audit_modification ... CREATE TRIGGER prevent_audit_modification BEFORE UPDATE OR DELETE ON sys_audit_event


### Enum completeness vs state machines  (9)

**1. [High]** 02_counterparty_platform.sql · comp_sar_case · status (comp_sar_status)

- **Issue:** comp_sar_status enum = {internal} only (line 153-156). This models the Phase-1/Phase-2 ESCALATION TIER (internal vs escalated_to_fiu_ind), NOT the SAR lifecycle. But B3 §BC11 SAR aggregate state field is status:{opened|documented} (09_B3_Aggregates.md:1002) and B2 emits both Sar.Opened and Sar.Documented (08_B2_Event_Model.md:295-296). With only the value 'internal', there is no DB state to represent opened vs documented — the documented transition is unrepresentable and the two distinct events both map to the single immutable value. Two orthogonal concerns (lifecycle vs escalation tier) have been conflated into one column.
- **Fix:** Split the concerns. Introduce a lifecycle enum CREATE TYPE comp_sar_lifecycle AS ENUM ('opened','documented'); add column sar_lifecycle comp_sar_lifecycle NOT NULL DEFAULT 'opened'. Keep comp_sar_status (internal/[Phase2 fiu]) as the escalation-tier column, or rename it comp_sar_escalation_tier for clarity. Enforce opened->documented monotonicity in the app layer (no trigger).
- **Evidence:** 02_counterparty_platform.sql:153 CREATE TYPE comp_sar_status AS ENUM ('internal' ...); table col status comp_sar_status NOT NULL DEFAULT 'internal' (line 1054). vs 09_B3_Aggregates.md:1002 status:{opened|documented}; 08_B2 lines 295-296 Sar.Opened/Sar.Documented

**2. [Medium]** 04_generic_acl.sql · gate_vendor_instruction · status (vendor_instruction_status_enum)

- **Issue:** DB enum = {pending, sent, executed, failed} (lines 77-82), but the B3 BC18 BankingInstruction aggregate state set is status:{sent|acknowledged|webhook_received|reconciled|failed} (09_B3_Aggregates.md:1356). Three intermediate states the spec uses to track the vendor handshake/reconciliation (acknowledged, webhook_received, reconciled) are absent, and DB-only 'pending'/'executed' do not appear in the B3 set. The reconciled state is load-bearing for the C23/G6 EoD-overlay 'provisional until reconciled' rule (PI.7) — collapsing webhook_received+reconciled into 'executed' loses the provisional/final distinction at the ACL boundary.
- **Fix:** Reconcile the two: either ALTER TYPE vendor_instruction_status_enum ADD VALUE 'acknowledged'/'webhook_received'/'reconciled' (and decide whether 'executed' is an alias for 'reconciled'), or update B3 to match the simplified DB model and record the decision in DECISION_LOG (DL-BE). Confirm whether the gate-layer provisional/final distinction (PI.7, C23) needs the reconciled state at this table or only at cash_recon_ledger.
- **Evidence:** 04_generic_acl.sql:77 vendor_instruction_status_enum ENUM('pending','sent','executed','failed') vs 09_B3_Aggregates.md:1356 status:{sent|acknowledged|webhook_received|reconciled|failed}

**3. [Medium]** 02_counterparty_platform.sql · comp_kyc_file · status (comp_kyc_file_status)

- **Issue:** DB enum = {in_review, approved, rejected} (lines 158-162) but the B3 KycFile aggregate state set is status:{submitted|approved|rejected} (09_B3_Aggregates.md:1068). The initial/entry state differs: DB names it 'in_review', B3 names it 'submitted'. The B2 event KycFile.Approved/Rejected react to a submitted file. Not a missing-transition gap, but a naming mismatch that will surface as a JPA enum-mapping bug and confuses the AmlScreening/KycFile entry state (cf. InvestorAccount.kyc_file_status which B3:626 lists as {not_submitted|submitted|approved|rejected}).
- **Fix:** Align naming: rename DB value to 'submitted' (rebuild type, since enum value rename is not in-place: CREATE new type, ALTER COLUMN ... TYPE ... USING, DROP old) OR amend B3 to 'in_review' and log in DECISION_LOG. Prefer the spec name 'submitted' for consistency with inv_account_status.kyc_submitted and InvestorAccount.kyc_file_status.
- **Evidence:** 02_counterparty_platform.sql:158 comp_kyc_file_status ENUM('in_review','approved','rejected') vs 09_B3_Aggregates.md:1068 status:{submitted|approved|rejected}

**4. [Low]** 01_core.sql · deal_listing · status (deal_listing_status)

- **Issue:** deal_listing_status (lines 86-115) folds delay sub-states (mildly_delayed, delayed, seriously_delayed, under_adjudication) AND outcome states (disputed, dilution, fraud, defaulted, recovered) directly into the Listing status enum, duplicating col_delay_status (col_maturity_case.delay_status) and col_maturity_outcome/risk_default_outcome. B3 (09_B3_Aggregates.md:73, L.1 line 83) defines the Listing terminal set as exactly {distributed, funding_failed_refunded, cancelled_pre_disbursement, defaulted} via the separate terminal_outcome field (deal_terminal_outcome) — it does NOT list mildly_delayed/delayed/disputed/dilution/fraud/recovered as Listing statuses. The delay/outcome substates are owned by BC6 (col_maturity_case) per B2 §3.6 and DL-029. Carrying them in deal_listing_status risks two sources of truth for the same fact and an unbounded/ambiguous Listing state machine ('recovered' is not a Listing terminal per deal_terminal_outcome).
- **Fix:** Confirm whether delay/outcome belong on Listing at all. If Listing only needs a coarse 'in_repayment' + a denormalised delay flag, drop mildly_delayed/delayed/seriously_delayed/under_adjudication/disputed/dilution/fraud/recovered from deal_listing_status and source them from col_maturity_case.delay_status/outcome. If they are an intentional denormalisation, add a DECISION_LOG (DL-BE) note and a CHECK/app-rule keeping deal_listing.status in sync with col_maturity_case, since no trigger will maintain it.
- **Evidence:** 01_core.sql:104-114 delay sub-states + outcome states in deal_listing_status; vs 09_B3_Aggregates.md:83 terminal states only {distributed, funding_failed_refunded, cancelled_pre_disbursement, defaulted}; deal_terminal_outcome (01_core.sql:117-122) omits recovered/disputed/dilution/fraud

**5. [Low]** 01_core.sql · cash_payout_instruction · status (cash_payout_status)

- **Issue:** cash_payout_status = {drafted, approved, sent, executed, partial, failed, completed} (lines 182-190) matches B3 PI.5 transition drafted->approved->sent->(executed|partial|failed)->completed (09_B3_Aggregates.md:335). No missing states. BUT note B3 line 1356's separate BankingInstruction model and the fact that 'completed' is a terminal that PI.6/G11 requires be reachable from 'partial' only after remediation — pure enum membership cannot express this edge constraint, and the project bans triggers. Flagging that PI.5/PI.6/PI.7 transition legality (incl. partial->completed gating on resolved RemediationCase) is wholly an APP-LAYER responsibility; the enum bounds values, not edges.
- **Fix:** No DDL change to the enum (it is complete). Document in DECISION_LOG that payout-status transition legality + the partial->completed remediation gate are enforced in the command handler, and add an invariant test proving both the app rule and the lack of any backward/illegal transition. Beware native-SQL/jOOQ write paths bypassing the app guard.
- **Evidence:** 01_core.sql:182 cash_payout_status full set; 09_B3_Aggregates.md:335 PI.5, 336 PI.6 (partial cannot ->completed until every failed leg has a resolved RemediationCase), 337 PI.7 provisional-until-reconciled

**6. [Low]** 03_auth.sql · auth_identity · status (identity_status_enum)

- **Issue:** identity_status_enum = {invited, active, disabled, auto_disabled}. The mapped business identities (admin_user, investor, auditor) each have their OWN richer lifecycle enums: admin_user_status={invited,active,disabled} (no auto_disabled), inv_account_status has 10 states incl. suspended/exited, audit_account_status={proposed,approved,activated,auto_disabled}. The auth-layer status is a deliberately coarse projection, which is reasonable, but there is no declarative link guaranteeing it stays consistent with the owning aggregate (e.g. investor 'suspended'/'exited' both collapse to auth 'disabled'; an auditor past validity_window.end is 'auto_disabled' in both — good). Worth confirming the mapping is intentional and app-maintained, not a missing-state gap.
- **Fix:** No enum change required. Record in DECISION_LOG that auth_identity.status is a coarse app-maintained projection of the owning aggregate's lifecycle, and document the collapse mapping (suspended/exited/blacklisted -> disabled; validity-end -> auto_disabled). Ensure the projection is updated in the same command transaction as the aggregate.
- **Evidence:** 03_auth.sql identity_status_enum (invited,active,disabled,auto_disabled) vs admin_user_status (invited,active,disabled) and audit_account_status (proposed,approved,activated,auto_disabled) and inv_account_status 10-state set

**7. [Info]** 01_core.sql · legal_master_agreement · status (legal_master_agreement_status)

- **Issue:** legal_master_agreement_status = {initiated, signed, stamped, failed} (lines 226-231) exactly matches B3 MA.2 transitions initiated->{signed,failed}; signed->stamped (09_B3_Aggregates.md:428) and the B2 events MasterAgreement.Initiated/Signed/SigningFailed + MasterStamping.Completed. Complete. Note MA.4/G14: stamping may fail post-signing but per spec must NOT roll back the legally-signed state — so 'failed' must be reachable from 'initiated' but a stamping failure after 'signed' must NOT set status='failed' (it stays 'signed' + raises an alert). The enum cannot express this edge asymmetry; it is an app-layer rule.
- **Fix:** No DDL change. Document the MA.4/G14 rule in DECISION_LOG and cover with an invariant test: a stamp failure on a 'signed' agreement leaves status='signed' (alert only), it does not transition to 'failed'.
- **Evidence:** 01_core.sql:226 legal_master_agreement_status complete set; 09_B3_Aggregates.md:428 MA.2, 430 MA.4 (stamping failure does not block onboarding terminal state)

**8. [Info]** 01_core.sql · col_claim_case / col_maturity_case · status / delay_status / outcome

- **Issue:** BC6 enums are consistent with B3: col_claim_status={raised,under_adjudication,resolved} matches B3 line 569; col_delay_status={on_track,mildly_delayed,delayed,seriously_delayed,under_adjudication,outcome} matches B3 MC.2 line 526 + line 515; col_maturity_outcome and risk_default_outcome both = {disputed,dilution,fraud,defaulted,recovered} matching B2 DefaultCase.Classified outcome union (08_B2:161) and B3:255. risk_default_case_status={requested,under_adjudication,classified} matches B3:255. No missing or terminal-state gaps in BC3/BC6. col_claim_type={dilution,fraud,dispute} aligns with B2 Claim.Raised/Resolved (DL-015).
- **Fix:** None. These enums are complete and faithful. Transition legality (delay_status bucket progression MC.2, classification fires at under_adjudication MC.3, claim raised->under_adjudication->resolved) is app-layer/scheduler-enforced; cover with invariant tests.
- **Evidence:** 01_core.sql:152,263,272,293 vs 09_B3_Aggregates.md:255,515,526,569; 08_B2_Event_Model.md:161,212,215

**9. [Info]** 02_counterparty_platform.sql · inv_account / sup_account / buyer_account · status (inv_account_status / sup_account_status / buyer_account_status)

- **Issue:** DB onboarding enums are FULLER than the mock blueprints and consistent with B3. inv_account_status (10 states) covers the STEP2 flow; the blueprint's 'kyc_in_review' and 'mia_pending' (STEP2 lines 50-73) are NOT DB states — DB uses kyc_submitted/suitability_assessed/financial_profile_completed/kyc_approved/mia_signed instead. This is the spec-canonical model (B3 IA.1 follows Spec §6.3); the mock's *_pending/*_in_review are UI-derived wait states, not persisted states. Same for sup_account (blueprint maa_pending/kyc_in_review/under_credit_review vs DB credit_reviewed/maa_signed) and buyer_account (blueprint approved_with_limit/under_credit_review vs DB credit_assessed/engagement_started). Confirmed intentional: B3 explicitly cites Spec §6.1/§6.2/§6.3.
- **Fix:** None required (DB is canonical, mock states are UI-derived). Recommend a one-line DECISION_LOG note mapping mock wait-states (kyc_in_review->kyc_submitted, mia_pending->mia_signed-in-progress, under_credit_review->between kyc_approved and credit_reviewed, approved_with_limit->credit_assessed) so the frontend contract is traceable and no one re-adds them as DB enum values.
- **Evidence:** 02_counterparty_platform.sql:38 inv_account_status, :69 sup_account_status, :95 buyer_account_status vs STEP4_ADMIN_BLUEPRINT.md:94-96,538-541 and STEP2:50-73; 09_B3_Aggregates.md:626,705,793 cite Spec §6.x


### Manifest accuracy  (7)

**1. [Fix now]** SQL_FILES_MANIFEST.md · auth_identity, admin_user, inv_account, buyer_ack_user

- **Issue:** The 'Execution Order Rationale' (lines 50-52) is factually false and the load reason it gives is fabricated. It claims 03_auth.sql 'adds foreign-key constraints on tables (tblAdminUser, tblInvestorAccount, tblAcknowledgmentUser) that are created by 02_counterparty_platform.sql'. (1) Those table names do not exist anywhere — the real names are admin_user, inv_account, buyer_ack_user. (2) 03_auth.sql adds NO FK onto any 02 table; every FK in 03 targets its own auth_identity. (3) The real cross-file direction is the opposite and is a SOFT (un-constrained) reference: 02's tables carry identity_id with no REFERENCES clause. (4) There are ZERO hard inter-file FK constraints in the whole bundle.
- **Fix:** Rewrite the rationale to: 'Run 01_core.sql FIRST. It is the only hard cross-file dependency: SECTION 0 of 01_core defines the shared DOMAIN value-object types (pan_type, gstin_type, ifsc_type, irn_type, aadhaar_last4_type, money_paise, positive_money_paise, bps_type) and the core enums that 02 and 04 use in column definitions; running them before 01 fails with "type does not exist". Files 02, 03, 04 contain only intra-file FK constraints (03_auth FKs all target its own auth_identity; 04 has a single self-FK sys_audit_event.corrects) and otherwise reference other contexts via SOFT identity_id columns with no REFERENCES clause, so among themselves they have no hard ordering requirement. The canonical sequence is 01 -> 02 -> 03 -> 04 (matching the order baked into 04''s own header comment).' When ported to Flyway, this maps to V1__core, V2__counterparty_platform, V3__auth, V4__generic_acl, and the dependency is type-availability, not FK ordering.
- **Evidence:** Manifest:52. 03_auth.sql REFERENCES grep: all 7 hits target auth_identity(identity_id) (lines 138,180,206,250,302,312,322) — none target 02. 02_counterparty_platform.sql REFERENCES grep: every FK is intra-file (inv_invite, inv_account, sup_account, buyer_account, buyer_payment_rule, admin_user) — zero into auth/01/04. 04 self-FK only (sys_audit_event.corrects, line 159). No tbl* identifier exists in any .sql file.

**2. [High]** SQL_FILES_MANIFEST.md · (whole file)

- **Issue:** 01_core.sql is described as generic 'Core financial platform schemas: Account management / Transaction processing / Ledger entries / Financial instruments' (lines 7-12). This is wrong. The file is the Core Domain of an invoice-discounting platform and contains BC1 Listing & Invoice, BC2 Subscription, BC3 Credit & Underwriting, BC4 Settlement, BC5 Assignment & Signing, BC6 Collections & Recovery, PLUS the shared-kernel DOMAINs and all enums.
- **Fix:** Replace the 01_core bullet with: 'Core Domain (BC1-BC6) + shared kernel. Defines the platform's value-object DOMAIN types (pan_type, gstin_type, ifsc_type, irn_type, aadhaar_last4_type, money_paise, positive_money_paise, bps_type) and all core enums in SECTION 0/1 — these MUST exist before any other file runs. 17 tables: BC1 Listing & Invoice (deal_invoice, deal_listing); BC2 Subscription (sub_subscription); BC3 Credit & Underwriting (risk_buyer_profile, risk_supplier_profile, risk_pricing_policy, risk_default_case); BC4 Settlement (cash_virtual_account, cash_payout_instruction, cash_recon_ledger, cash_remediation_case); BC5 Assignment & Signing (legal_master_agreement, legal_assignment_set, legal_signature_request); BC6 Collections & Recovery (col_maturity_case, col_action_log, col_claim_case).'
- **Evidence:** 01_core.sql:5-6 header: 'Core Domain: BC1 Listing & Invoice, BC2 Subscription, BC3 Credit & Underwriting, BC4 Settlement, BC5 Assignment & Signing, BC6 Collections & Recovery'. SECTION 0 (lines 26-65) defines pan_type/gstin_type/ifsc_type/irn_type/aadhaar_last4_type/money_paise/positive_money_paise/bps_type.

**3. [High]** SQL_FILES_MANIFEST.md · (whole file)

- **Issue:** 02_counterparty_platform.sql is described as 'Counterparty management and trading schemas: Counterparty information / Trading relationships / Settlement instructions / Risk management' (lines 14-19). Wrong — there is no 'trading' and settlement/risk live in 01, not 02. The file actually holds the counterparty + platform-governance bounded contexts BC7-BC13.
- **Fix:** Replace the 02 bullet with: 'Counterparty onboarding + platform governance (BC7-BC13). 23 tables: BC7 Investor Onboarding (inv_invite, inv_account, inv_suitability); BC8 Supplier Onboarding (sup_account, sup_agency_consent, sup_financial_profile); BC9 Buyer Management (buyer_account, buyer_ack_user, buyer_payment_rule); BC10 Admin IAM (admin_user, admin_role_assignment, admin_deviation_log, admin_sod_policy); BC11 Compliance (comp_aml_screening, comp_sar_case, comp_refresh_schedule, comp_kyc_file, comp_spot_check); BC12 Tax & Reporting (tax_year_profile, tax_tds_deduction, tax_investor_statement); BC13 Auditor Access (audit_scope, audit_account). Consumes DOMAIN types from 01_core. Links to auth (03) and other BCs are SOFT references by identity_id (no DB FK).'
- **Evidence:** 02_counterparty_platform.sql:4-6 header: 'Covers: BC7 Investor Onboarding, BC8 Supplier Onboarding, BC9 Buyer Management, BC10 Admin IAM, BC11 Compliance, BC12 Tax & Reporting, BC13 Auditor Access'. 23 tables created in this file (grep CREATE TABLE).

**4. [High]** SQL_FILES_MANIFEST.md · (whole file)

- **Issue:** 04_generic_acl.sql is described as 'Access Control List (ACL) implementation: RBAC / Permission management / Resource access policies / User roles and assignments' (lines 28-33). This is entirely wrong. The file holds generic subdomains and integration Anti-Corruption-Layer ports — 'ACL' here means Anti-Corruption Layer, not Access Control List. RBAC/roles actually live in 02 (admin_role_assignment, admin_sod_policy).
- **Fix:** Replace the 04 bullet with: 'Generic subdomains + integration Anti-Corruption Layers ("ACL" = Anti-Corruption Layer, NOT access-control list). 7 tables: BC14 Audit Log (sys_audit_event — immutable, cryptographically-chained envelope store); BC15 Notifications (sys_notification_dispatch); BC16 Documents (sys_document_object); BC17 Verification ACL (gate_verification); BC18 Banking ACL (gate_vendor_instruction, gate_inflow_observation); BC19 Signing ACL (gate_signature_session). Contains the prevent_audit_modification trigger enforcing append-only on sys_audit_event (AE.1). Consumes DOMAIN types from 01_core. All cross-context links are SOFT FKs.'
- **Evidence:** 04_generic_acl.sql:4-5 header: 'Generic Subdomains: BC14 Audit Log, BC15 Notifications, BC16 Documents. Integration ACLs: BC17 Verification ACL, BC18 Banking ACL, BC19 Signing ACL'. Tables: sys_audit_event, sys_notification_dispatch, sys_document_object, gate_verification, gate_vendor_instruction, gate_inflow_observation, gate_signature_session.

**5. [Medium]** SQL_FILES_MANIFEST.md · (whole file)

- **Issue:** The manifest omits 03_auth.sql's actual scope and mis-describes it as generic 'user profile management / authentication tokens'. The real file is a deliberately narrow 5-table auth layer that is the identity join-point for the whole platform; it should be documented precisely because every state-changing command consults auth_session.mfa_assertion_id (one of the five non-negotiables).
- **Fix:** Replace the 03 bullet with: 'Authentication layer (5 tables, deliberately narrow): auth_identity (one row per authenticable principal — the identity join key the domain BCs reference by identity_id), auth_credential, auth_mfa_factor, auth_otp_challenge, auth_session. auth_session.mfa_assertion_id is the MFA-freshness anchor consulted by every state-changing command (non-negotiable #2). All FKs are internal to auth_identity; no FK into the domain — coupling is via soft identity_id references from 02 and elsewhere.'
- **Evidence:** 03_auth.sql:5-6 'Scope: five tables only — auth_identity, auth_credential, auth_mfa_factor, auth_otp_challenge, auth_session.' Lines 24-25: 'mfa_assertion_id on the session row is consulted by every admin state-changing command handler (AU10.3, C7).'

**6. [Medium]** 04_generic_acl.sql · sys_audit_event

- **Issue:** Per project policy (no procedural DB triggers/functions; keep the DB simple, append-only enforced declaratively where possible), the prevent_audit_modification trigger + fn_prevent_audit_modification() should be flagged for reconsideration. The manifest does not mention the trigger at all, hiding a policy-relevant artifact. Append-only is one place the policy explicitly wants reconsidered vs. an app-layer + declarative approach.
- **Fix:** In the corrected manifest, add a note under 04: 'sys_audit_event append-only (AE.1) is currently enforced by a plpgsql trigger (prevent_audit_modification). Per project no-trigger policy this is a candidate for replacement: prefer declarative protection (REVOKE UPDATE,DELETE ON sys_audit_event FROM application_role; grant INSERT/SELECT only) plus an app-layer append-only command surface. The envelope-hash chaining (AE.2) and 10-yr retention (AE.3) are already app-layer responsibilities — flag native-SQL/jOOQ write paths into sys_* tables as a risk for any app-owned column.'
- **Evidence:** 04_generic_acl.sql:12 'Immutable audit log: AE.1 enforced by trigger ... '; trigger prevent_audit_modification BEFORE UPDATE OR DELETE ON sys_audit_event EXECUTE fn_prevent_audit_modification() raising EXCEPTION. AE.2/AE.3 (chain hashing, 10yr retention) noted as NOT enforceable in DDL (lines 113-116), i.e. already app-owned.

**7. [Low]** SQL_FILES_MANIFEST.md · (metadata)

- **Issue:** Stale/irrelevant metadata: the manifest is framed as a tar.gz distribution bundle (extract SQL_Files_Bundle.tar.gz, run via psql) and lists 'compressed ~51KB' sizes. The repo policy is Flyway-owned schema (port the 4 files into V1..V4 migrations, ddl-auto=validate); raw psql execution contradicts CLAUDE.md persistence rules. File-size figures (80/72/32/44 KB) are unverified packaging trivia.
- **Fix:** Drop the tar.gz/psql 'Installation Instructions' and size section, or reframe as: 'These 4 files are the schema source of truth; they are ported into Flyway migrations V1__core .. V4__generic_acl under src/main/resources/db/migration. Do not run via raw psql against the app database — Flyway + ddl-auto=validate own the schema.' Update the generated date stamp (currently 2026-05-24).'
- **Evidence:** Manifest:37-48 tar/psql instructions; lines 61-64 size figures. CLAUDE.md: 'Flyway owns the schema. Port the 4 SQL files into versioned migrations'.


### Completeness critic  (10)

**1. [High]** 03_auth.sql · auth_otp_challenge · assertion_id

- **Issue:** assertion_id is the mfa_assertion_id consumed by auth_session.mfa_assertion_id and by every admin audit envelope (the literal anchor of non-negotiable #2 MFA-fresh). It has NO UNIQUE constraint and NO index. The freshness verification (A3) must look up the challenge by assertion_id to read consumed_at; that lookup is a full scan, and a non-unique assertion_id permits two challenges to claim the same assertion, making the session->challenge join ambiguous. No dimension audited this column.
- **Fix:** CREATE UNIQUE INDEX uidx_auth_otp_challenge_assertion ON auth_otp_challenge (assertion_id) WHERE assertion_id IS NOT NULL; This makes the assertion globally unique and gives the session-freshness join an index. (Declarative; no trigger.)
- **Evidence:** 03_auth.sql:216 assertion_id UUID NULL; constraint auth_otp_challenge_assertion_only_when_consumed at :227 gates presence-vs-status but NOT uniqueness; no CREATE INDEX references assertion_id.

**2. [Medium]** 03_auth.sql · auth_credential / auth_mfa_factor / auth_otp_challenge / auth_session · identity_id

- **Issue:** Every auth child table carries identity_id as a hard FK to auth_identity (ON DELETE RESTRICT) but the only indexes on identity_id are PARTIAL (WHERE revoked_at IS NULL / WHERE status='active'). The unfiltered FK lookup path - notably the ON DELETE RESTRICT integrity check fired against auth_identity, and 'all credentials/factors/sessions (incl. revoked/expired) for an identity' admin/audit queries - is unindexed for the revoked/expired rows. Identities are archived not deleted, so RESTRICT checks are rare, but revoked-row history scans are a real auditor path.
- **Fix:** Add unfiltered FK-covering indexes where a full-history path exists, e.g. CREATE INDEX idx_auth_session_identity ON auth_session (identity_id); (and similarly for auth_otp_challenge (identity_id) if the expiry-sweep/history path needs revoked rows). Lower priority than the assertion_id index because RESTRICT is rare.
- **Evidence:** 03_auth.sql:374 idx_auth_credential_identity WHERE revoked_at IS NULL; :387 idx_auth_mfa_factor_identity_active WHERE revoked_at IS NULL; :403 idx_auth_session_identity_active WHERE status='active'; auth_otp_challenge has only (identity_id,purpose) partial-active indexes. No full index on identity_id alone for any of the four.

**3. [Medium]** 04_generic_acl.sql · sys_notification_dispatch · status / provider_ref / (missing retry_count)

- **Issue:** Two gaps no dimension caught. (1) ND.4 references 'retry_count cap enforced at ND.3' but there is NO retry_count column on the table at all - the documented retry-cap invariant has no home. (2) status discriminates {queued,sent,delivered,failed} and provider_ref is 'NULL until the vendor acknowledges' (i.e. should be NOT NULL once sent/delivered) but no CHECK ties provider_ref to status. A 'delivered' dispatch can have NULL provider_ref.
- **Fix:** Add retry_count SMALLINT NOT NULL DEFAULT 0 with CHECK (retry_count >= 0 AND retry_count <= <cap>); and ADD CONSTRAINT sys_notification_dispatch_provider_ref_shape_chk CHECK (status NOT IN ('sent','delivered') OR provider_ref IS NOT NULL). Fire-and-forget semantics (ND.1) are unaffected.
- **Evidence:** 04_generic_acl.sql:298 provider_ref TEXT (nullable); :318-319 comment 'NULL until the vendor acknowledges'; ND.4 comment :284 cites a retry_count cap; table body :286-304 has neither a retry_count column nor any status-shape CHECK.

**4. [Medium]** 02_counterparty_platform.sql · comp_aml_screening · adjudication_decision

- **Issue:** adjudication_decision is plain TEXT documented as exactly {'clear','false_positive','true_hit_suspend'} but has NO enum and NO CHECK constraining it to those three values. Any string is accepted. The conditional-nullability dimension noted extending the chk to require it when status='adjudicated', and the money dimension cleared match_score, but neither flagged the unconstrained value-domain. Per the maximize-declarative-constraints policy this should be an enum or a CHECK.
- **Fix:** Prefer a CREATE TYPE comp_aml_adjudication_decision AS ENUM ('clear','false_positive','true_hit_suspend') and retype the column; or minimally ADD CONSTRAINT comp_aml_screening_decision_values_chk CHECK (adjudication_decision IS NULL OR adjudication_decision IN ('clear','false_positive','true_hit_suspend')).
- **Evidence:** 02_counterparty_platform.sql:1003-1004 adjudication_decision TEXT; only the presence gate at :1015-1022; no value constraint anywhere.

**5. [Medium]** 02_counterparty_platform.sql · sup_account / buyer_account · cin / mca_cin / udyam

- **Issue:** CIN (sup_account.cin CHAR(21), buyer_account.mca_cin CHAR(21)) and Udyam (sup_account.udyam VARCHAR(19)) are statutory-format identifiers with NO format CHECK and NO domain, even though the schema establishes a clear pattern of regex domains (pan_type, gstin_type, ifsc_type). CHAR(21) silently right-pads with spaces, so a 'valid' CIN may be a 5-char string padded to 21. The unique-business-key dimension flagged CIN uniqueness on buyer_account but not the missing format guard on either table, and sup_account.cin was not audited for uniqueness at all.
- **Fix:** Introduce cin_type AS TEXT CHECK (VALUE ~ '^[A-Z][0-9]{5}[A-Z]{2}[0-9]{4}[A-Z]{3}[0-9]{6}$') and (optionally) udyam_type, and retype the columns (drop CHAR padding by using TEXT-based domains). Add the same UNIQUE-WHERE-NOT-NULL guard to sup_account.cin that the index dimension recommended for buyer_account.mca_cin.
- **Evidence:** 02_counterparty_platform.sql:428 cin CHAR(21); :430 udyam VARCHAR(19); :583 mca_cin CHAR(21) - none has a CHECK; contrast pan/gstin which use regex domains.

**6. [Low]** 02_counterparty_platform.sql · inv_account · pan / aadhaar_last4

- **Issue:** Investor natural-key uniqueness was skipped by the unique-business-key dimension (which covered supplier/buyer only). inv_account.pan (pan_type) has no UNIQUE constraint and not even a plain index, unlike sup_account.pan (which at least has idx_sup_account_pan). Two investor rows can silently share a PAN. Whether one-investor-per-PAN holds is a spec question (resident_individual vs huf can share a PAN family-wise), but at minimum a lookup index is missing and the uniqueness decision is undocumented.
- **Fix:** At minimum CREATE INDEX idx_inv_account_pan ON inv_account (pan) WHERE pan IS NOT NULL; If spec mandates one active investor per PAN, make it a partial UNIQUE index and record the decision in DECISION_LOG.
- **Evidence:** 02_counterparty_platform.sql:280 pan pan_type (set once per IA.1); indexes at :356-360 cover identity_id, status, kyc_refresh_due - none on pan.

**7. [Low]** 01_core.sql · legal_assignment_set · legs (JSONB allocation_paise)

- **Issue:** The BC5 money-conservation invariant - sum of legs[].allocation_paise across all assignment legs must equal the listing's committed_total/funding_target (the assignment-side analogue of funding-equality G10) - has NO declarative footprint and was not audited by the money dimension (it only made a generic JSONB-escapes-typing note). allocation_paise sits in JSONB so it carries neither money_paise typing nor a sum check; a leg allocation can be negative or the legs can under/over-allocate silently.
- **Fix:** No single-row declarative fix for JSONB-internal sums (and the equality is cross-aggregate to deal_listing, so app-layer). Flag as APP-LAYER responsibility with an invariant test (sum allocation_paise = committed_total), and consider promoting per-leg allocation to a typed child table (legal_assignment_leg with allocation_paise positive_money_paise) if invariants over legs grow - mirrors the recommendation to lift cash_payout legs out of JSONB.
- **Evidence:** 01_core.sql:1336 legs JSONB; :1365-1369 comment defines {investor_id, subscription_id, allocation_paise,...}; the only checks are signed/unsigned/total count balance (:1348-1351) - nothing about allocation amounts.

**8. [Low]** 02_counterparty_platform.sql · inv_invite / sup_financial_profile · status

- **Issue:** Enum-consistency miss not caught by the enum-completeness dimension (which audited only the typed enums): inv_invite.status and sup_financial_profile.status are raw TEXT with inline CHECK(status IN (...)) instead of CREATE TYPE enums, diverging from every other status column in the bundle. This is a cross-file consistency/maintainability gap and risks JPA enum-mapping drift.
- **Fix:** Promote both to enum types (e.g. inv_invite_status_enum, sup_financial_profile_status_enum) for consistency with the rest of the schema; or document in DECISION_LOG why these two stay TEXT. Behaviourally equivalent today, so Low.
- **Evidence:** 02_counterparty_platform.sql:223-224 status TEXT ... CHECK (status IN ('pending','consumed','expired')); :541-542 status TEXT ... CHECK (status IN ('submitted','reviewed')).

**9. [Info]** 04_generic_acl.sql · gate_verification · signature_verified_at vs comment V.2/hmac naming

- **Issue:** Minor naming/consistency note missed by all dimensions: BC18 (gate_vendor_instruction) and BC19 (gate_signature_session) name the HMAC timestamp hmac_verified_at, but BC17 gate_verification names the same concept signature_verified_at while its comments (V.2, C10) call it 'HMAC ... hmac_verified_at'. Three sibling ACL tables, two column names for one concept - a JPA-mapping and reviewer-confusion hazard.
- **Fix:** Rename gate_verification.signature_verified_at to hmac_verified_at for cross-ACL consistency, or document the divergence. No data-integrity impact.
- **Evidence:** 04_generic_acl.sql:455 signature_verified_at (gate_verification) vs :538 hmac_verified_at (gate_vendor_instruction) vs :686 hmac_verified_at (gate_signature_session); gate_verification comment :484-486 refers to 'webhook HMAC'.

**10. [Info]** 04_generic_acl.sql · sys_audit_event · (aggregate_id, aggregate_version) uniqueness

- **Issue:** Confirmation/nuance the audit-chain dimension did not state explicitly: the schema DELIBERATELY does not enforce UNIQUE(aggregate_id, aggregate_version) (comment :196-199 cites hot-row contention). Combined with the absence of a stored envelope_hash (already a Fix-now finding), this means per-aggregate event ordering and optimistic-concurrency linkage to business rows are ENTIRELY app-owned. Worth recording as an explicit app-layer responsibility alongside the chain-hashing one, since native-SQL/jOOQ inserts that skip the aggregate_version stamp would corrupt the audit-to-aggregate linkage with zero DB defence.
- **Fix:** No DDL change (the contention rationale is sound). Add a DL-BE entry: per-aggregate audit ordering + aggregate_version stamping are app-owned; list sys_audit_event among tables where ad-hoc native inserts are prohibited (only the BC14 outbox writer may insert).
- **Evidence:** 04_generic_acl.sql:196-199 comment explicitly declines uniqueness on (aggregate_id, aggregate_version).


---

## Migration split (verified)

VERIFIED LOAD ORDER (one migration per source file; no cycles). The only hard cross-file dependency is type/extension availability, NOT foreign keys - confirmed by reading all four files: every FK is intra-file except the three identity FKs added in 03 (all targeting auth_identity, defined in 03 itself) and the deferred admin_user FKs added at the end of 02 (all intra-02). There are ZERO hard inter-file FK constraints.

V1__core.sql  = 01_core.sql verbatim. MUST run first: it contains SECTION 0A (CREATE EXTENSION citext - consumed by 03's auth_identity.email CITEXT) and SECTION 0 (the 8 shared DOMAINs pan_type/gstin_type/ifsc_type/irn_type/aadhaar_last4_type/money_paise/positive_money_paise/bps_type) plus all BC1-BC6 core enums and 17 tables with their intra-file FKs (deal_listing->deal_invoice, sub_subscription->deal_listing, risk_pricing_policy self-FK, risk_default_case->deal_listing + self-FK, cash_*->deal_listing/sub_subscription, legal/col_*->deal_listing). 02 and 04 reference money_paise/positive_money_paise/bps_type; 03 references citext - all resolved because each Flyway migration commits its DDL before the next runs, so the types are visible.

V2__counterparty_platform.sql = 02 verbatim. 23 tables (BC7-BC13) + the trailing DEFERRED FK ALTER block (lines 1463-1498) which wires inv_invite/inv_account/sup_account/sup_agency_consent/buyer_*/admin_role_assignment FKs - all intra-02 (targets admin_user / admin_deviation_log, both created earlier in 02). Consumes domains from V1.

V3__auth.sql = 03 verbatim. 7 auth enums + 5 auth tables (all FKs internal to auth_identity) + the three ALTER...ADD FOREIGN KEY statements wiring admin_user/inv_account/buyer_ack_user.identity_id -> auth_identity (those columns + UNIQUE were already created in V2, so V3 only adds the FK; sup_account intentionally has none). Requires citext from V1 and the three domain tables from V2 - both already committed.

V4__generic_acl.sql = 04 verbatim. 9 enums + 7 tables (sys_audit_event self-FK 'corrects' DEFERRABLE INITIALLY DEFERRED; everything else soft FK). Uses money_paise from V1. No dependency on V2/V3, but placing it last keeps the 01->02->03->04 file order the bundle's own headers assume.

DO NOT split the domains/enums into a separate V0 'types' migration - there is exactly one producer (01) and the graph confirms V1 precedes every consumer, so co-locating in V1 is simplest. Keep ddl-auto=validate.

TWO POLICY ADDENDA to bake into the migrations (not ordering, but migration-content decisions):
(1) In V4, DROP the prevent_audit_modification trigger + fn_prevent_audit_modification() (the bundle's only procedural artifact, explicitly flagged for reconsideration) and replace append-only enforcement with declarative privilege: REVOKE UPDATE, DELETE, TRUNCATE ON sys_audit_event FROM <app_role>; GRANT INSERT, SELECT ON sys_audit_event TO <app_role>; (WORM substrate G7 remains the superuser/pg_dump control). 
(2) Add the missing envelope_hash BYTEA NOT NULL column + length CHECK to sys_audit_event in V4 (currently absent - tamper-evidence is silently defeated), and add a new sys_command_log producer-dedup table (PK (actor_id, command_id)) - reconciling it with the existing vendor-side idempotency keys (cash_payout_instruction.payout_instruction_id PI.1, legal_signature_request idempotency uidx, gate_vendor_instruction.vendor_event_id, gate_inflow_observation.utr/vendor_event_id) so command_id dedup is not double-implemented. Place sys_command_log in V4 (or a V5) since it has no FK dependencies. Record both as DL-BE entries.

---

## Completeness-critic coverage notes

Areas the dimension auditors under-covered (verified by the critic; the additional findings above were folded into the dimension list):

- AUTH LAYER (03_auth.sql) IS THE LARGEST UNDER-AUDITED AREA. Only the enum-completeness dimension touched it (and only auth_identity.status). No dimension audited: (a) FK-index coverage on auth_credential.identity_id / auth_mfa_factor.identity_id / auth_otp_challenge.identity_id / auth_session.identity_id (all are FK columns; only partial WHERE-filtered indexes exist, so the unfiltered FK-archival/RESTRICT-check path on revoked rows is uncovered); (b) conditional-nullability completeness; (c) the assertion_id linkage. The five-non-negotiables dimension treated auth only as the MFA source, never auditing auth tables themselves.
- auth_otp_challenge.assertion_id: NOT audited by anyone. It is the mfa_assertion_id that auth_session.mfa_assertion_id and every admin envelope's actor.mfa_assertion_id point to (the literal anchor of non-negotiable #2), yet it has NO UNIQUE constraint and NO index. A non-unique assertion_id makes the session->challenge freshness join ambiguous and unindexed - this is a real gap given how central MFA-freshness is.
- auth_session.mfa_assertion_id -> auth_otp_challenge.assertion_id is a soft (un-constrained, un-indexed) cross-row link. No dimension flagged that the freshness check (A3) joins these with no supporting index and no referential guarantee.
- BC16 sys_document_object and BC15 sys_notification_dispatch were only touched tangentially by the audit-chain and BC-purity dimensions. No money/time/nullability sweep: e.g. sys_notification_dispatch has no status-shape CHECK (provider_ref expected once 'sent'/'delivered'; NULL while 'queued'/'failed') and no retry_count column despite ND.4 referencing a retry cap.
- inv_account natural-key uniqueness was skipped while sup_account/buyer_account got it: inv_account.pan has no UNIQUE/index at all (sup_account.pan at least has a plain index). The unique-business-key dimension audited supplier/buyer GSTIN/PAN/CIN but never investor PAN/Aadhaar.
- legal_assignment_set.legs[].allocation_paise: the sum-of-allocations = funding_target / committed_total invariant (the BC5 analogue of funding-equality G10) was not audited by the money dimension (it only noted JSONB money escapes typing generically). This is the assignment-side money-conservation invariant.
- inv_suitability, sup_financial_profile, comp_spot_check, tax_investor_statement, audit_scope received essentially no dedicated findings - lightly covered. (Most are clean, but they were not explicitly cleared.)
- CHAR/VARCHAR format columns were not swept: sup_account.cin CHAR(21), buyer_account.mca_cin CHAR(21), sup_account.udyam VARCHAR(19), inv_account.bank_account_last4 CHAR(4) (the last has a len CHECK but no digit CHECK). No domain or regex CHECK on CIN/Udyam despite the project's maximize-declarative-constraints policy and the existence of pan_type/gstin_type/ifsc_type domains as a pattern to follow.
- command_id idempotency (non-negotiable #4): correctly flagged as platform-wide-absent by the five-non-negotiables dimension, but no dimension reconciled it against the EXISTING idempotency mechanisms that DO exist (payout_instruction_id=client_instruction_id PI.1, signature_request_id idempotency uidx, gate_vendor_instruction.vendor_event_id UNIQUE, gate_inflow_observation.vendor_event_id/utr UNIQUE). The producer-dedup table should be reconciled with these vendor-side idempotency keys so they are not double-implemented.

---

## Next steps
1. Resolve the 🔴 Fix-now items in the source SQL (or as deltas captured in the V-migrations).
2. Port the bundle to `V1__core` → `V2__counterparty_platform` → `V3__auth` → `V4__generic_acl` per the verified split, applying the fixes inline.
3. `./mvnw flyway:info` + Testcontainers run; `ddl-auto=validate` must pass against the JPA mappings.
4. `/code-review` the migration diff; fix findings before commit.
5. Record `DL-BE` entries (no-trigger audit immutability, app-owned `updated_at`, `command_id`/`sys_command_log`, migration split).
