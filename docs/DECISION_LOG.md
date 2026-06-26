# Backend Decision Log — Fintech Platform

Living record of **non-obvious backend build decisions and bugs**. One entry per decision:
what changed, why, and what to watch for. Adding the relevant entries is a **DoD requirement**
for every module (see `docs/spec/Spec_Driven_Build_Plan.md` §F).

**Scope of this log:** backend implementation only. Distinct from:
- `DL-0xx` — domain/product decisions, defined inline in the `docs/spec/` corpus (frozen inputs).
- `DL-MOCK-xxx` — frontend/mock decisions, in the `fintech-patform-mock` repo.

**Prefix:** `DL-BE-`. Number sequentially. Reference related `DL-0xx` / `C1–C28` / `G…` from the
spec corpus where a decision is constrained by one.

---

## DL-BE-001 — Repository housekeeping: spec corpus + SQL bundle moved into the backend
**Date:** 2026-06-13
**What:** Moved the domain spec corpus (numbered docs `02`–`10`, `Bounded_Contexts_Reference`,
`domain_entity_responsibility`) and the canonical SQL bundle into `docs/spec/` and `docs/sql/`.
Mirrored the `STEP2/4/5` blueprints and `Spec_Driven_Build_Plan.md` (backend copy is
rules-canonical). Deleted the stale `SQL_Files_Bundle_old/`.
**Why:** Single source of truth — Spec Kit runs here, so the corpus it consumes lives here. Avoids
the two-repos-diverge problem flagged in the build plan §0.
**Watch for:** Blueprints are mirrored, not unique — if a rule changes, edit the backend copy then
re-copy to the mock. There is **no** separate platform Decision Log file; `DL-0xx` refs are inline
in the corpus.

---

## DL-BE-002 — Declarative-only DB invariants: no procedural triggers; `updated_at` app-owned
**Date:** 2026-06-13
**What:** Adopted a standing policy for the schema: enforce invariants **declaratively**
(CHECK / FK / NOT NULL / UNIQUE / domain types / enums) and via **GRANT/REVOKE**, never `plpgsql`
triggers or functions. Concretely: (a) `updated_at` is **app-owned** (Hibernate `@UpdateTimestamp` /
explicit set) — no `set_updated_at()` trigger; (b) the bundle's one procedural artifact, the
`prevent_audit_modification` trigger + `fn_prevent_audit_modification()` on `sys_audit_event`
(`04_generic_acl.sql`), is **dropped** and replaced with declarative least-privilege:
`REVOKE UPDATE, DELETE, TRUNCATE ON sys_audit_event FROM <app_role>; GRANT INSERT, SELECT …`.
**⚠️ SUPERSEDED for the audit trigger by [[DL-BE-010]] (2026-06-13):** the
`prevent_audit_modification` trigger is **RETAINED as a documented exception** — `REVOKE` is
ineffective while the app role owns the table, so the trigger stays until a restricted non-owner
app role is deployed. The no-trigger rule still holds everywhere else (incl. `updated_at`).
**Why:** Triggers are hard to debug and reason about; the user wants the procedural DB layer simple
while still maximising the *declarative* last-line-of-defence. REVOKE/GRANT gives the same
append-only guarantee for the app role, transparently. (Superuser/owner/`pg_dump` paths remain
covered by the WORM substrate, spec G7.)
**Watch for:** App-owned `updated_at` is **silently skipped by any write path that bypasses JPA
lifecycle callbacks** — native SQL, jOOQ, bulk `UPDATE`. Such paths must set `updated_at = now()`
explicitly; add a test/ArchUnit guard. The REVOKE only binds the application role, not a superuser —
keep app DB credentials non-superuser. The crypto **hash-chain itself is not declaratively
enforceable** (a CHECK can't read the previous row) and is therefore an **app-layer** responsibility
(see [[DL-BE-003]]). Source: `docs/sql/PRE_MIGRATION_AUDIT.md` findings FN4 + audit-chain dimension.

---

## DL-BE-003 — `command_id` idempotency table + `sys_audit_event.envelope_hash`
**Date:** 2026-06-13
**What:** Two schema additions surfaced by the pre-migration audit as Fix-now gaps in the five
non-negotiables: (1) Added a producer-dedup table `sys_command_log` with
`PRIMARY KEY (actor_id, command_id)`; command handlers `INSERT … ON CONFLICT DO NOTHING` and treat a
conflict as a replay (non-negotiable #4, idempotent on `command_id`, G18). Also add `command_id UUID`
to `sys_audit_event` (NOT NULL for command-originated events, NULL for scheduler/webhook per B2). (2)
Added the missing `envelope_hash BYTEA NOT NULL` self-hash column to `sys_audit_event` (+ length-32
CHECK) — without it the hash chain is unverifiable and tamper-evidence is silently defeated.
**Why:** `command_id` had **zero** declarative footprint in the bundle (grep = 0 across all files),
so command replay was not a guaranteed no-op at the DB. `envelope_hash` was described in the AE.2
comment and mandated by spec B2/B3/B4 but absent from the table — only `previous_envelope_hash`
existed, which alone cannot verify a chain.
**Watch for:** Reconcile `sys_command_log` with the **existing** vendor-side idempotency keys so the
mechanism is not double-implemented: `cash_payout_instruction.payout_instruction_id` (PI.1),
`legal_signature_request` idempotency uidx, `gate_vendor_instruction.vendor_event_id`,
`gate_inflow_observation.vendor_event_id`/`utr`. Hash-chain + `envelope_hash` computation are
app-owned (see [[DL-BE-002]]); `UNIQUE(aggregate_id, aggregate_version)` is **deliberately not**
enforced (hot-row contention, comment in `04`) — ordering is app-stamped. Source:
`docs/sql/PRE_MIGRATION_AUDIT.md` FN2, FN3.

---

## DL-BE-004 — Flyway migration split: one migration per source file
**Date:** 2026-06-13
**What:** The 4-file SQL bundle ports to four Flyway migrations, in source-file order:
`V1__core` · `V2__counterparty_platform` · `V3__auth` · `V4__generic_acl`. Shared domains/enums stay
co-located in `V1` (not a separate `V0__types`). `ddl-auto=validate` only.
**Why:** The audit verified there are **zero hard inter-file FK constraints** — the only real
cross-file dependency is type/extension availability (`01` defines the `citext` extension + the 8
shared domains every other file consumes). Each Flyway migration commits its DDL before the next
runs, so types from `V1` are visible to `V2`–`V4`. One producer of types → co-locating in `V1` is
simplest; a separate types migration would add a file with no ordering benefit.
**Watch for:** The old `SQL_FILES_MANIFEST.md` claimed `03_auth` adds FKs onto `02` tables named
`tblAdminUser`/`tblInvestorAccount`/… — **fabricated**; those names don't exist and no such FKs
exist (manifest now rewritten). The Fix-now/High findings in `docs/sql/PRE_MIGRATION_AUDIT.md` must
be applied **inline as the bundle is ported** (or as deltas in the V-files), not deferred. Run
`./mvnw flyway:info` + Testcontainers and `/code-review` the migration diff before commit.

---

## DL-BE-005 — V1 (core) decision pass: 4 audit-flagged design items resolved
**Date:** 2026-06-13
**What:** Resolved the four design-level items the pre-migration audit flagged on `01_core.sql`
but deliberately left out of the mechanical fix (`V1__core.sql`):
1. **Enforced** the close-reason invariant: `CHECK ((status='closed') = (terminal_outcome IS NOT NULL))`
   — a closed listing always records its terminal outcome; a non-closed one never carries one.
2. **No change** to `deal_listing_status` (it folds delay/outcome sub-states that also live on
   `col_maturity_case`). Kept as-is.
3. **Added `checker_mfa_assertion_id`** to `risk_default_case` (+ `checker_id ⇒ MFA` CHECK), mirroring
   `deal_listing.golive_mfa_assertion_id` and `cash_payout_instruction.checker_mfa_assertion_id`.
   **Did not** add approver-identity columns to `risk_buyer_profile`/`risk_supplier_profile`.
4. **No schema change** for `legal_assignment_set.legs` (kept JSONB). The money-conservation rule
   (Σ `allocation_paise` = listing `committed_total`) is recorded as an **app-layer invariant test**.
**Why:** (1) B3 L.1 / `Close(terminal_outcome)` confirms the outcome is always set at close, so the
CHECK can never reject a valid row — pure bug-catch for clean books/audit. (2) The delay/outcome
values are part of the **frontend contract** (the listing status badge); removing them risks breaking
screens for only a tidiness gain — minor drift risk accepted. (3) Spec §B3 line 277 models the
>₹1 Cr four-eyes approval as a **FourEyesApproval envelope** referenced by `four_eyes_approval_ref`,
not as approver columns on the credit row — so adding `first_approver_id` would diverge from the
spec; default classification, by contrast, is record-level maker-checker (C4) and should carry the
MFA token on-row like the other C4 commands. (4) The equality is **cross-aggregate** (AssignmentSet
vs Listing), so it cannot be a single-row CHECK regardless; the spec models legs as a child
collection of the AssignmentSet aggregate, and JSONB matches that. Validated: V1 applies on
postgres:16-alpine; all new constraints negative-tested with seeded FK parents (CHECK, not FK, fires).
**Watch for:** Item 4 — the Σ-allocations = `committed_total` invariant test is a **DoD requirement**
for the BC5 (Assignment) module; do not ship assignment execution without it (see [[DL-BE-003]] for
the broader "DB can't reach into JSONB / cross-aggregate equality is app-owned" pattern). If the
frontend turns out **not** to use the delay/outcome listing-status values (item 2), revisit and move
them to a single source of truth in BC6.

---

## DL-BE-006 — V2 (counterparty) ported with mechanical audit fixes; test harness established
**Date:** 2026-06-13
**What:** Ported `02_counterparty_platform.sql` → `V2__counterparty_platform.sql` with the
**mechanical, low-risk declarative** audit fixes folded in: (a) `tax_year_profile.tds_rate_bps`
retyped `INT` → `bps_type`; (b) an appended `AUDIT-FIX` section adding discriminator-gated /
co-presence CHECKs (`tax_year_profile` form-16A, `sup_account` suspend+blacklist, `inv_account` /
`buyer_account` suspend, `audit_account` lifecycle, `comp_refresh_schedule` completion), a
strengthened + value-constrained AML adjudication CHECK, ~20 FK-coverage indexes, and 4 **non-unique**
natural-key lookup indexes (PAN/GSTIN/CIN). Also established the automated migration-validation
harness: `AbstractIntegrationTest` (Testcontainers postgres:16-alpine, shared context) +
`SchemaMigrationTest` (Flyway success + objects) + `CoreSchemaConstraintsTest` /
`CounterpartySchemaConstraintsTest` (invariant tests proving each headline CHECK fires). 20 tests green.
**Why:** Mirrors the V1 approach — apply mechanically-safe declarative hardening now, defer
design/spec-level calls to a decision pass (DL-BE-005 was V1's). Appending CHECKs/indexes via
`ALTER`/`CREATE INDEX` after the deferred-FK block keeps the migration low-risk (every column
already exists) and clearly delineates audit changes. Natural-key indexes are deliberately
**non-unique**: GSTIN/CIN/PAN uniqueness is a business rule (proprietorships may share a PAN), held
for the decision pass.
**Watch for:** Open V2 **decision-pass** items, NOT yet applied: (1) spec-mandated record-level
maker-checker *columns* for Suspend/Blacklist (`sup_account` C4 line 733, `buyer_account` line 819)
and KYC submitter (`comp_kyc_file` KF.2 line 1075) + `audit_account` approver MFA token; (2)
entity-dedup UNIQUE on GSTIN/CIN; (3) `comp_sar_status` models escalation-tier not SAR lifecycle;
(4) `comp_kyc_file` status `in_review` vs spec `submitted`, and `inv_invite`/`sup_financial_profile`
status as `TEXT`+CHECK vs enum. See [[DL-BE-005]] for the V1 precedent on this split. The
`command_id`/`sys_command_log` + `envelope_hash` + WORM `REVOKE` still land in V4 ([[DL-BE-003]]).

---

## DL-BE-007 — V2 decision pass: maker-checker columns, entity-dedup uniqueness, naming cleanups
**Date:** 2026-06-13
**What:** Resolved the four V2 decision-pass items in `V2__counterparty_platform.sql`:
1. **Record-level maker-checker made declarative** (spec C4). Added `suspend_maker_id` /
   `suspend_checker_id` / `suspend_checker_mfa_assertion_id` (+ blacklist equivalents) to
   `sup_account`; the suspend trio to `buyer_account`; `submitted_by` + `approver_mfa_assertion_id`
   to `comp_kyc_file`; `approved_mfa_assertion_id` to `audit_account`. CHECKs: maker≠checker, and
   "the action cannot exist without its two-person record + MFA" (`suspended_at`/`blacklisted_at`
   ⇒ maker+checker+MFA present). FKs to `admin_user`; checker columns indexed.
2. **Entity de-duplication:** UNIQUE (partial, WHERE NOT NULL) on `sup_account.gstin`,
   `sup_account.cin`, `buyer_account.gstin`, `buyer_account.mca_cin`. **PAN stays non-unique**
   (proprietors may legitimately reuse a personal PAN across entities) — plain index only.
3. **SAR:** kept `comp_sar_status` as the escalation-tier field (Phase 1 single value `internal`);
   the SAR opened→documented lifecycle will be modelled when the BC11 compliance module is built.
4. **Naming/consistency:** `comp_kyc_file_status` value `in_review` → `submitted` (spec B3);
   `inv_invite.status` and `sup_financial_profile.status` promoted from `TEXT`+CHECK to enums
   (`inv_invite_status`, `sup_financial_profile_status`).
**Why:** (1) C4 mandates record-level maker-checker for these exact actions (B3 lines 733/819/1075);
making it declarative enforces two-person control + MFA-freshness at the last line of defence, the
V2 analogue of V1's go-live/payout maker-checker. (2) GSTIN/CIN are per-entity government IDs;
uniqueness blocks duplicate company onboarding that would split exposure. PAN is excluded by design.
(3) Phase-1 SAR has one value, so modelling its lifecycle now is premature. (4) Pure consistency
with the spec/blueprints and the rest of the enum-typed schema. Validated: V1+V2 apply on
postgres:16-alpine; 25 tests green incl. new maker-checker/dedup/naming invariant tests.
**Watch for:** The maker-checker "required" CHECKs mean a Suspend/Blacklist command MUST populate
maker+checker+MFA in the same write — the command handlers (and any backfill of legacy suspends)
must supply them. Contrast [[DL-BE-005]] where credit-profile four-eyes intentionally stayed in the
approval envelope (no on-row approver columns) — here the spec calls for on-row maker-checker.

---

## DL-BE-008 — V3 (auth) ported; closes the least-audited file's two real gaps
**Date:** 2026-06-13
**What:** Ported `03_auth.sql` → `V3__auth.sql`. The file was already well-constrained (shape
CHECKs, partial-unique indexes, validity-window/auditor/ack-user CHECKs), so only the two gaps the
audit's completeness critic flagged were added, both declarative/index-only: (1) a partial UNIQUE
index `uidx_auth_otp_challenge_assertion` on `auth_otp_challenge.assertion_id` (WHERE NOT NULL) —
the MFA-assertion anchor that `auth_session.mfa_assertion_id` and every admin audit envelope
reference; (2) four **unfiltered** FK-coverage indexes on `identity_id`
(`auth_credential`/`auth_mfa_factor`/`auth_otp_challenge`/`auth_session`) — the existing identity_id
indexes are all partial (`WHERE revoked_at IS NULL`/`status='active'`), leaving the ON DELETE
RESTRICT check and full-history lookups uncovered.
**Why:** `assertion_id` is the literal anchor of non-negotiable #2 (MFA-fresh); a non-unique,
unindexed anchor makes the freshness join (invariant A3) ambiguous and slow. The citext extension
the auth `email` column needs is created in V1 (ported from 01_core SECTION 0A), so Flyway's
V1→V2→V3 order satisfies it. `auth_identity.status` is intentionally a **coarse projection** of each
owning aggregate's richer lifecycle (admin/investor/auditor) — app-maintained, no enum change
(collapse map: suspended/exited/blacklisted→disabled, validity-end→auto_disabled). Validated:
V1+V2+V3 apply on postgres:16-alpine; tests green incl. assertion-id uniqueness + password-shape.
**Watch for:** A **cross-cutting architectural decision is still open** and NOT yet actioned: the
audit's bounded-context-purity dimension flagged hard FKs that cross BC boundaries (e.g.
`cash_payout_instruction.subscription_id`→`sub_subscription`, `col_claim_case.listing_id`→
`deal_listing`, and the `admin_user` actor FKs on `audit_account`/`audit_scope`/many V2 tables) as
violations of "no cross-BC joins; coordinate via identity references". These span V1 and V2 (already
written). Decide as a dedicated pass before the ArchUnit BC-enforcement work, since it may convert
several hard FKs to soft UUID references. The auth-layer `identity_id` FKs to `auth_identity` are
explicitly **exempt** (auth is the shared kernel). `command_id`/`envelope_hash`/WORM still land in V4
([[DL-BE-003]]).

---

## DL-BE-009 — V4 (generic ACL / audit) ported; Fix-now items applied, trigger swap deferred
**Date:** 2026-06-13
**What:** Ported `04_generic_acl.sql` → `V4__generic_acl.sql`. Applied the decided Fix-now items
([[DL-BE-003]]) and declarative hardening: (1) **`sys_command_log`** producer-dedup table
(PK `(actor_id, command_id)`) — non-negotiable #4; (2) **`sys_audit_event.envelope_hash` BYTEA
NOT NULL** + 32-byte length CHECK (self-hash for tamper-evidence, previously absent), a length guard
on `previous_envelope_hash`, a nullable `command_id`, AE.5 actor key-presence CHECK (`actor_type`/
`actor_id`/`session_id`), a C7 admin-actor-MFA CHECK, and an `occurred_at <= recorded_at` CHECK;
(3) `gate_verification` failure_class shape CHECK; (4) `sys_notification_dispatch.retry_count` column
(ND.4 had no home) + provider_ref shape CHECK; (5) `gate_inflow_observation.amount` → `positive_money_paise`;
(6) `idx_vsr_doc_hash`. 33 tests green; full V1–V4 applies on postgres:16-alpine.
**Why:** Fix-now items were pre-approved in DL-BE-003. The actor/MFA/time CHECKs make AE.5 and the
C7 MFA-fresh evidence requirement declarative in the audit trail rather than comment-only.
**Watch for — DELIBERATELY DEFERRED to the V4 decision pass (NOT yet actioned):** (A) the
`prevent_audit_modification` **trigger is still in place** — replacing it with a WORM `REVOKE/GRANT`
is ineffective while the app role *owns* the audit table (dev/test/current prod), so dropping it now
would weaken the only effective DB-level immutability; this is a compliance + deployment-topology
decision. (B) the hash chain has **no explicit shard-key column** (G25) — it's app-derived today.
(C) `before_state`/`after_state` "required on state-transition events" has no DB discriminator. (D)
`vendor_instruction_status_enum` is simpler than B3's BankingInstruction states, and
`gate_verification.signature_verified_at` diverges from the `hmac_verified_at` naming used in BC18/BC19.
`command_id` on `sys_audit_event` is nullable (conditional NOT-NULL for command-originated events is
app-enforced). Also still open: the cross-BC hard-FK question from [[DL-BE-008]].

---

## DL-BE-010 — V4 decision pass: audit-trigger exception, chain shard key, transition snapshots, consistency
**Date:** 2026-06-13
**What:** Resolved the four V4 decision-pass items in `V4__generic_acl.sql`:
1. **Audit-immutability trigger KEPT** as a documented exception to the no-trigger rule (see the
   amendment on [[DL-BE-002]]). The `REVOKE/GRANT` swap is ineffective while the app role owns the
   audit table, so the trigger remains the only effective DB-level immutability until a restricted
   non-owner app role is deployed; WORM substrate (G7) remains the superuser-path control.
2. **Explicit chain shard key** — added `sys_audit_event.chain_shard TEXT NOT NULL`; dropped
   `idx_audit_chain_verify` and added `idx_audit_chain_shard_verify (chain_shard, recorded_at, event_id)`.
   Makes "first row per shard has NULL previous_envelope_hash" (G25) verifiable instead of app-inferred.
3. **State-transition snapshots enforced** — added `is_state_transition BOOLEAN NOT NULL DEFAULT FALSE`
   + CHECK requiring `before_state`/`after_state` when true.
4. **Consistency** — extended `vendor_instruction_status_enum` with the B3 superset values
   (`acknowledged`, `webhook_received`, `reconciled`; additive, existing values retained) and renamed
   `gate_verification.signature_verified_at` → `hmac_verified_at` to match BC18/BC19.
**Why:** (1) Audit immutability is a hard compliance control; honouring "no triggers" strictly would
remove the only effective guard in every current environment — the user chose to keep it as the one
sanctioned exception. (2) An app-inferred shard makes chain verification fragile. (3) Guarantees state
events capture what changed. (4) Pure spec/cross-ACL consistency; the enum extension is additive so it
can't break existing rows, and the Phase-1 app may use whichever subset the ACL needs.
**Watch for:** `chain_shard` and `envelope_hash` are **app-populated** on every audit append — the
single serialized BC14 append path must set them (no ad-hoc native inserts). `ALTER TYPE ADD VALUE`
ran inside Flyway's transaction fine on PG16 (values added, not used in-migration). The trigger
exception should be **revisited** when the restricted-app-role deployment lands (then drop trigger +
`REVOKE`). Cross-BC hard-FK decision ([[DL-BE-008]]) and the ArchUnit BC-enforcement harness remain
the next architectural items.

---

## DL-BE-011 — `/code-review` follow-ups: ₹10 Cr four-eyes threshold + relax DB to maximise app flexibility
**Date:** 2026-06-13
**What:** Acted on the `/code-review` findings with a guiding principle from the founder — **maximise
application-level flexibility now; don't over-constrain the DB for future state machines.**
1. **Four-eyes threshold is ₹10 crore, by decision** (not ₹1 crore). The constant
   `10000000000` paise is therefore CORRECT; the misleading "Rs 1 Cr" comments were fixed to "Rs 10 Cr"
   (11 occurrences in V1). **This DIVERGES from spec C6 / DL-023 (which say "> ₹1 crore")** — recorded
   here as the authoritative backend decision; reconcile the spec text separately.
2. **Removed the blanket `sys_audit_event_admin_mfa` CHECK** — it rejected legitimate admin audit
   appends (login-success, MFA-challenge issuance, sensitive reads) that carry no `mfa_assertion_id`.
   MFA-freshness on state-changing commands stays per-aggregate (`*_mfa_assertion_id` columns) + app.
3. **Dropped three over-strict lifecycle CHECKs** so the app owns those state machines for now:
   `risk_default_case_classified_shape_chk` (was blocking the maker's ProposeClassification step),
   `cash_virtual_account_status_shape_chk` (bank close-webhook may set `closed_at_bank` after the
   status flip), `col_maturity_case_outcome_shape_chk` (BC3 sets outcome while delay_status is still
   `under_adjudication`). Each is re-addable as a precise guard when its module's state machine is final.
4. **Made `sub_subscription_distribution_net_check` sound** — added `distribution_outcome ?& array[...]`
   so a non-null outcome missing a money key can no longer make the CHECK evaluate to UNKNOWN and pass
   silently. Money-conservation is the one place worth keeping a DB guard.
5. **KYC SoD (`comp_kyc_file`) left as the partial DB guard** (`approver_id <> submitted_by` when both
   present); full enforcement (requiring `submitted_by` on supplier KYC) is **app-owned** for now —
   no rigid `subject_type`-keyed NOT NULL added, per the flexibility principle.
6. **Tests:** removed the now-invalid admin-MFA test (replaced with an accepted-case assertion);
   hardened two brittle multi-violation tests (AML, payout-refund) to seed real FK parents so the
   asserted CHECK is the sole violation; `SchemaMigrationTest` domain assertion switched to `contains`.
**Why:** The schema is not deployed and there is no domain code yet, so relaxing CHECKs now costs
nothing and avoids the DB fighting the application as modules are built; the constraints can be
re-tightened per-module later (a one-line `ALTER`). Money-conservation and the four-eyes control are
kept because they encode hard invariants, not provisional state-machine shapes.
**Watch for — DEFERRED (revisit per module, tracked here):** `legal_master_agreement_status_shape_chk`
is asymmetric (a 'stamped' row with NULL `stamp_cert_id` passes — weaker than MA.3) — left as-is. No
DB guard on `chain_shard` non-emptiness or `sys_command_log` ↔ `sys_audit_event` linkage (audit-chain
integrity is app-owned). When BC3/BC4/BC5/BC6/BC11 are built, re-add precise lifecycle/SoD guards
appropriate to each finalized state machine. Validated: 34 tests green after the changes.

---

## DL-BE-012 — M1a shared kernel: Money/Bps value objects, error model, logging; tests re-enable Flyway
**Date:** 2026-06-20
**What:** First feature slice (M1a, light tier — spec `docs/modules/M1-shared-kernel.md`). Added:
(1) `shared.Money` (record over `long` paise) and `shared.Bps` (record over `int` bps) — overflow-safe
arithmetic via `Math.*Exact`, no float anywhere; (2) error model `shared.error.PlatformException`
(base, carries stable `errorCode` + `HttpStatus`) → `ValidationException` (400); (3) logging baseline:
`logback-spring.xml` with a per-request correlation id in the pattern, `RequestIdFilter` (MDC +
`X-Request-Id` echo), and `GlobalExceptionHandler` (`@RestControllerAdvice`) mapping domain errors to
RFC 7807 `ProblemDetail` and logging every exception with the request id. **Also fixed a pre-existing
test regression:** commit 8e0c433 added `spring.flyway.enabled=false` (prod runs migrations manually
via `FlywayMigrationRunner`), which left the integration-test DB empty; re-enabled Flyway *only* in
tests via `@TestPropertySource` on `AbstractIntegrationTest`. 41 tests green.
**Why:** `Money` is **signed** (maps to `BIGINT`; negative ledger deltas are valid) — only
`requirePositive()` enforces `> 0`, mirroring the DB `positive_money_paise` domain (zero is an
accounting error per V1 comment); plain `money_paise` (`>= 0`) is enforced at the column, not the type.
Records give value equality for free and forbid a float accessor by construction. Domain exceptions log
at WARN (expected business flow, no stack trace); unexpected ones at ERROR with trace. The Flyway fix
is scoped to tests so production's deliberate manual-migration posture is unchanged.
**Watch for:** M1a is the first cut of M1 — **M1b still owes** the `command_id` idempotency store
(`sys_command_log`, [[DL-BE-003]]), the in-process event bus, and the `aggregate_version`
optimistic-locking helper; none of the five non-negotiables bind until then. `requirePositive()` must
be called explicitly at strictly-positive boundaries — nothing forces it. If app code ever bypasses JPA
for writes, `updated_at` still app-owned ([[DL-BE-002]]).
**`/code-review` follow-ups applied (high effort):** (1) `GlobalExceptionHandler` now maps Spring
Security `AccessDeniedException`→403 / `AuthenticationException`→401 (the catch-all previously turned
in-handler method-security denials into 500 — load-bearing once `@PreAuthorize` lands for SoD/
maker-checker); (2) `Bps` now enforces the **full** `bps_type` range `[0, 100000]`, not just `>= 0`;
(3) added `Money.requireNonNegative()` (`>= 0`, mirrors `money_paise`) — `requirePositive()` is `> 0`,
so callers pick the guard matching the target column's domain; (4) `RequestIdFilter` validates an
inbound `X-Request-Id` against `[A-Za-z0-9_-]{1,64}` and otherwise generates one (untrusted value was
logged verbatim → log-forging / log-volume risk); (5) 500 responses now carry the `errorCode`
property like every other error; (6) dropped redundant `toPaise()`/`toInt()` aliases (use record
accessors `paise()`/`value()`); (7) tests extended for the Bps bounds, `minus()`/`times()` overflow,
the non-negative guard, and the malicious-request-id discard. **Deferred (not a bug yet):** MDC
correlation id is not re-established across async dispatch — revisit when the first async endpoint
lands. Validated: 43 tests green.

---

## DL-BE-013 — M1b kernel: UUIDv7 IDs + `aggregate_version` optimistic-locking base; idempotency deferred
**Date:** 2026-06-21
**What:** Second shared-kernel slice (M1b, light tier — spec `docs/modules/M1b-ids-and-versioning.md`).
Added: (1) `shared.Ids.newId()` — **UUIDv7** via `com.fasterxml.uuid:java-uuid-generator`
(`Generators.timeBasedEpochGenerator()`, singleton) for `event_id`/`command_id`/`correlation_id`/
`causation_id` (B2 §2.1); (2) `shared.VersionedAggregate` — `@MappedSuperclass` mapping the schema's
`aggregate_version INT` to JPA `@Version` optimistic locking (P5), proven against the **real schema**
by a minimal test entity on `risk_pricing_policy` (two stale writers → `OptimisticLockingFailureException`,
version 1→2). Test-infra: `AbstractIntegrationTest` made **public** (base for tests in other packages);
`PlatformBackendApplicationTests` folded onto it because this is the project's **first `@Entity`**, so
`ddl-auto=validate` now runs in every context and the boot smoke test legitimately needs a migrated DB.
**Idempotency store deliberately DEFERRED** out of M1b (option 1) — see Watch-for.
**Why:** `Ids` is programmatic because `command_id`/`event_id` are minted in app code; Hibernate's
`@UuidGenerator` only covers entity-id assignment (so it can't serve app-side minting). UUIDv7 gives the
audit/event chain a time-ordered tie-breaker. `VersionedAggregate` makes the optimistic-lock pattern
inherited, not re-declared per entity. The test entity maps only `@Id` + `buyer_id` + `@Version` and
seeds rows via SQL, so `validate` only sees UUID/INT columns (no Postgres enum / `bps_type` mapping
needed). **Idempotency has no consumer until the first state-changing command**, and its dedup/
transaction boundary is far safer designed against a real handler; the contract is already fixed by
`sys_command_log` ([[DL-BE-003]]) so deferring carries zero schema risk.
**`/code-review` follow-ups applied (high effort):** (a) `AggregateVersionLockingTest` is non-transactional
(needs separate txs to model two writers), so added `@AfterEach` to DELETE its seeded row — the shared
cached container/context convention depends on tests not leaking rows; (b) strengthened the lock test to
assert A's value survived and version advanced exactly once (so a non-lock failure can't masquerade as a
pass); (c) corrected `IdsTest` — JUG's v7 generator **is** monotonic within a millisecond (RFC 9562 §6.2),
so the test now asserts strict ordering on back-to-back calls with no sleep, instead of the weaker cross-ms
case; (d) hoisted the lib version to a `<java-uuid-generator.version>` property (not in the Spring Boot BOM).
**Watch for — DEFERRED / ACCEPTED (decisions on the record):**
1. **`@Version` on a primitive `int` seeds JPA-managed INSERTs to 0**, overriding the column `DEFAULT 1`
   and diverging from B2 "first version = 1". **Accepted for now**: no aggregate *table* carries a
   `CHECK(aggregate_version >= 1)` (only `sys_audit_event` does — and that is append-only, **app-stamped**
   per [[DL-BE-003]], NOT a JPA-`@Version` aggregate), so a JPA aggregate starting at 0 is harmless.
   **Distinct concepts:** the optimistic-lock column (JPA-owned) vs the audit-envelope `aggregate_version`
   (app-stamped event sequence) — do not conflate. Revisit (boxed `Integer` seeded to 1, or explicit
   stamping) if the first real aggregate genuinely needs first=1.
2. **`command_id` idempotency store deferred — STILL MANDATORY (non-negotiable #4, G18).** Guardrail:
   **no money-moving command may ship without it.** Lands with the first state-changing command module.
3. **Test-only `PricingPolicyRow` lives under the base package**, so it's scanned into every context's
   persistence unit. Accepted: real aggregates (M6+) will populate the PU regardless, and `validate` runs
   once on the cached context; confine via a dedicated `@EntityScan` only if it becomes noisy.
Validated: 47 tests green.

---

## DL-BE-014 — Event-backbone sequencing: "M1c" dissolved; envelope→M2, bus+outbox→Walking Skeleton
**Date:** 2026-06-21
**What:** Decided *when* and *where* to build the remaining "Event Backbone" half of M1 (the in-process
pub/sub bus, the `AuditEventEnvelope` type, and the X13 transactional outbox). The monolithic "M1c" is
**dissolved** and its three pieces re-plugged at their real first-consumers: (1) `AuditEventEnvelope`
→ built **inside M2** (it is the audit log's own wire/record type); (2) in-process **pub/sub bus** +
(3) **X13 outbox** → built at the **Walking Skeleton**, where the first cross-context subscriber and the
first state-changing command co-exist. For Wave 0 (M2/M3/M4/M5), contexts reach the audit log via a
**direct synchronous `AuditLog.append(envelope)` in the command's DB transaction**. Documented in
`docs/modules/M1c-event-backbone.md` (status Deferred/Scheduled, with the build trigger). The M1b spec
forward-reference was updated to point here.
**Why:** A pub/sub bus has no rider until a **non-audit** subscriber exists (a cross-context reaction),
which first appears in Wave 1 — building it sooner is infra-without-consumer (same reasoning as the
idempotency deferral, [[DL-BE-013]]). Crucially, a **synchronous in-transaction append already satisfies
audit-publish-before-success (X13/P8)**: the append commits or rolls back atomically with the command, so
a 2xx genuinely means "the fact is logged" — no bus or outbox required for M2/M3/M4/M5. The full outbox
(G27) earns its keep only for **cross-aggregate** coordination (X1 Listing+Subscription), a Walking-
Skeleton concern. This keeps each Wave-0 module small and avoids designing the bus blind.
**Watch for:** When the bus lands at the Walking Skeleton, the audit log is **refactored from a direct
call into "the first subscriber"** — keep `AuditLog.append()` behind an interface in M2 so this is
mechanical, not a rewrite. At-least-once (P4) means subscribers dedupe on `event_id` and producers on
`(actor_id, command_id)` — the latter needs the deferred idempotency store ([[DL-BE-013]]), so the bus
and the idempotency store will likely arrive together at the first command module. This sequencing is a
backend *implementation* decision; the authoritative plan (`docs/spec/`) is unchanged (it lists "event
bus" under M1 and "Audit Log" as M2 — both still true).

---

## DL-BE-015 — M2 Audit Log (BC14): RFC 8785 hashing, declarative chain linearity, native-SQL append
**Date:** 2026-06-21
**What:** Built the audit log (spec `docs/modules/M2-audit-log.md`). `AuditEventEnvelope` (B2 §2 record
+ `Actor` + builder); `AuditLog.append()` as the sole serialized write path (native SQL / JdbcTemplate,
runs in the caller's tx → publish-before-success X13/P8); `AuditCanonicalizer` (SHA-256 over **RFC 8785
JCS** canonical JSON of the persisted columns, `hash_encoding_version=1`, micro-truncated timestamps);
`AuditChainVerifier` (recompute + walk). New migration **V5** adds `uidx_audit_chain_link (chain_shard,
previous_envelope_hash) NULLS NOT DISTINCT`. `chain_shard = <context>:<business_date in IST>`;
`aggregate_version` caller-supplied (≥1). 54 tests green.
**Why (key decisions):** (1) **RFC 8785 JCS** over a bespoke encoding — a standardized canonicalization
is far less likely to get tamper-evidence subtly wrong; a round-trip test (BigDecimal `12.50`, `0.1`,
21-digit integer, unicode, nested) confirms the in-app hash and the JSONB-round-tripped verify hash are
byte-identical. (2) **Declarative chain linearity** via a UNIQUE index + optimistic insert-retry
(`ON CONFLICT DO NOTHING`, re-resolve head, retry) instead of a Postgres **advisory lock** — the user's
call: a session lock is hard to observe when debugging and doesn't carry to multi-instance / Phase-2
topologies; a constraint is the single source of truth everywhere ([[DL-BE-002]]). (3) **Native SQL**:
the table is append-only with no updates, so a JPA entity adds nothing.
**`/code-review` follow-ups applied (high effort):** (a) **cycle guard** in the verifier walk — a
crafted cycle among raw-inserted rows previously looped forever (the verifier's threat model includes
raw inserts); now returns broken; (b) **envelope validation** in `append()` (M1a `ValidationException`)
so a null `occurredAt`/`actor` is a clean domain error, not an NPE deep in the write path — the
state-transition snapshot rule is deliberately left to the DB CHECK; (c) added the number/unicode
round-trip test (closing the gap that prior tests used only integer payloads); (d) dropped a redundant
`StoredEvent.previousHash` field.
**Watch for — ACCEPTED / DEFERRED (decisions on the record):**
1. **`recorded_at` is the app clock**, bound explicitly (not the DB `now()`), because it must be known
   pre-insert to be hashed. Fine for the Phase-1 single-JVM monolith; revisit for clock-skew when a
   producer runs on a different host than the audit writer (Phase 2). The `occurred_at <= recorded_at`
   CHECK then becomes skew-sensitive.
2. **Append throughput ceiling:** all appends for a `<context>:<IST-day>` serialize through one chain
   head; `MAX_CHAIN_RETRIES=50`, no backoff. Adequate for Phase-1 volume; if a single context gets hot,
   narrow `chain_shard` granularity (add hour) and/or add backoff.
3. **"`append()` is the only writer" is by-construction + review only — NOT yet enforced.** The spec's
   ArchUnit guard is deferred to the M0 ArchUnit harness (ArchUnit intentionally deprioritized for now).
   Until then, a stray `INSERT INTO sys_audit_event` elsewhere would bypass chaining. Tracked here.
4. **`docs/sql/` is no longer the live schema source.** V4's `[AUDIT-FIX]` columns and V5's index are not
   in `docs/sql/` — Flyway migrations are the **source of record post-baseline**; `docs/sql/` is the
   original ported bundle/baseline and is not retro-updated. (CLAUDE.md's "docs/sql is the schema source
   of truth" line predates this and should be amended — flagged, not yet changed.)
5. **Verifier loads a whole shard into memory** — fine now; move to a streaming/cursor walk if a shard
   grows large. And **tamper-evidence is unkeyed SHA-256**: it detects partial edits, but an actor who
   can rewrite an entire shard could re-chain self-consistently — full protection is the WORM substrate
   + restricted non-owner role (G7, [[DL-BE-002]]/[[DL-BE-009]]) at the Production gate, plus possible
   HMAC/anchored checkpoints later.

---

## DL-BE-016 — M3a authentication: argon2id, SMS-OTP MFA, BC15 NotificationPort stub
**Date:** 2026-06-21
**What:** First auth slice (spec `docs/modules/M3a-authentication.md`). `AuthService` (native SQL onto
the V3 auth tables): `provisionIdentity`, `setPassword` (argon2id), `authenticatePassword`
→ `PasswordResult`, `issueLoginOtp`, `verifyOtp` → `OtpResult`; mints the `mfa_assertion_id`
(non-negotiable #2) on OTP consume; every auth event is appended to the audit log (M2 — M3a is its
first producer). BC15 `NotificationPort` + lean in-memory `StubNotifier` (no real SMS, no
`sys_notification_dispatch`). Deps added: `bcprov-jdk18on` (argon2 backing). OTP = 6-digit, 5-min TTL,
5 attempts. 63 tests green.
**Why (key decisions):** (1) **argon2id** for passwords (Spring Security `Argon2PasswordEncoder`).
(2) **OTP failure returns `OtpResult`, never throws** — a thrown failure under `@Transactional` would
roll back the `attempts++` that enforces the lockout (caught test-first). (3) **Lean stub** behind a
real port — Constitution-compliant; the real SMS adapter swaps in at M5/Production with no caller
change. (4) M3a owns the auth-table **primitives**; the admin workflow is M4 (breaks M3↔M4 circularity).
**`/code-review` follow-ups applied (high effort):**
(a) **OTP verify race fixed** — `SELECT … FOR UPDATE OF c` + status-guarded consume; concurrent
correct-code verifies previously minted *two* assertions / bypassed the attempt cap. Added a 2-thread
regression test (exactly one verified, one consumed row).
(b) **`setPassword` rotates** — revokes the prior active password credential before inserting (the
schema allows only one active password per identity, so a second call used to fail).
(c) **OTP sent on `afterCommit`** (TransactionSynchronization), not mid-transaction — a rolled-back
tx no longer leaves a real SMS dispatched for a vanished challenge.
(d) **Auditor validity window enforced** at login (`valid_from`/`valid_until`), independent of the
auto-disable scheduler's lag.
(e) **Stub hardened** — logs param *keys* only (no OTP/phone in logs), bounded retention (500);
`NotificationRequest` defensively copies `params`. Folded `kindOf()` into existing queries (removed an
N+1).
**Watch for — ACCEPTED / DEFERRED (decisions on the record):**
1. **`provisionIdentity` creates `status='active'` directly**, bypassing the enrollment matrix (A1/A2:
   admin active only with MFA enrolled; auditor needs a validity window). It is a **primitive** for
   M3a/tests; **M4 owns enrollment-gated activation** and real admin provisioning (and M17 the auditor
   validity window). Auditor provisioning via this primitive will fail the DB time-bound CHECK by
   design — provision auditors through M17.
2. **`correlation_id` is per-event**, so a multi-step login flow isn't correlated yet. The HTTP caller
   (controller) will thread one `correlation_id` (+ `session_id`) per request in **M3b**; consider a
   shared audit-envelope factory then (also to set `is_state_transition`/snapshots consistently).
3. **No `SecurityFilterChain`** — Spring Security's default still secures all (future) endpoints with a
   generated password. The HTTP security config lands with **M3b** (sessions + endpoints); there are no
   controllers yet, so it's latent.
4. **argon2id also hashes the 6-digit OTP.** Secure but heavier than needed (TTL + 5-attempt cap
   already bound brute force). Kept for simplicity (no key management); switch the OTP path to
   HMAC-SHA256 with a server pepper once a secrets/pepper mechanism exists.
5. **OTP rate-limiting / resend throttling** is minimal while stubbed — revisit with the real SMS
   provider (cost + abuse), M5/Production.
Validated: 63 tests green.

---

## DL-BE-017 — M3b sessions, MFA-freshness check & tenant claims
**Date:** 2026-06-21
**What:** Second auth slice (spec `docs/modules/M3b-sessions-and-mfa-freshness.md`). `SessionService`
(native SQL onto the V3 `auth_session` table): `establishSession` (stamps the M3a-minted
`mfa_assertion_id` + serialises `tenant_claims`), `resolveSession → SessionResolution` (validates both
expiries, lazily transitions to `expired`, rolls the idle window via `UPDATE … RETURNING`),
`revokeSession` (logout / admin kill), and **`isMfaFresh(session, ActionSensitivity)`** — the freshness
gate every admin command will call (M3a minted the assertion, M3b consumes it; non-negotiable #2). New
value types: `AuthSession`, `SessionResolution`, `TenantClaims` (typed JSONB view + per-kind factories),
`ActionSensitivity`. Every session lifecycle event is audited via `AuditLog` (M2): `auth.Session.Established`,
`auth.Session.Revoked`, `auth.Session.Expired`, `auth.TenantClaim.Issued` (B2 §3.10). Service substrate
only — no HTTP/cookie layer. 11 new tests; 74 green.
**Why (key decisions, as built):**
1. **Session TTLs (proposed):** idle **30 min**, absolute **8 h** for admin sessions — final values
   are BC10 policy, tunable without schema change.
2. **Lazy expire-on-`resolve`** — no scheduler in M3b; a sweep/cleanup job is deferred to M5/ops
   (a stale `active` row is harmless because every resolve re-checks both expiries).
3. **Freshness window = B4 §6.4 defaults** — 5 min sensitive / 30 min normal; `isMfaFresh` takes the
   action's `ActionSensitivity` so callers pick the band. Compares server-side `consumed_at` only —
   no client timestamp ever feeds the window.
4. **HTTP cookie/session filter deferred** to the first authenticated endpoint (Walking Skeleton);
   M3b ships the session *service*. (Resolves DL-BE-016 watch-for #3 — the `SecurityFilterChain` —
   when the endpoint exists.)
5. **Claims content:** M3b owns the per-kind serialisation mechanism + typed accessor; **admin
   `{roles}` is populated by M4** (M3b only supplies the role values' container).
6. **Idle-roll not audited** — establish/revoke/expire are audited; the per-request idle roll is not
   (noise); `last_seen_at` is the durable record.
**`/code-review` follow-ups applied (high effort):**
(a) **Shared `correlation_id`** — `establishSession` emits two envelopes (`Session.Established` +
`TenantClaim.Issued`) for one logical act; they now share one `correlation_id` so an auditor can tie the
claim issuance back to the establishment (B2 §3.10). Partially addresses DL-BE-016 watch-for #2; full
per-request threading arrives with the HTTP layer.
(b) **`isMfaFresh` pins `purpose = 'login_mfa'`** — `assertion_id` is login_mfa-only by schema COMMENT,
not by a DB CHECK, so the freshness query now constrains the purpose explicitly; the gate can never be
satisfied by a non-login assertion.
(c) **`resolveSession` folds the post-roll re-read into `UPDATE … RETURNING`** — the hot path (every
authenticated request) now does SELECT-FOR-UPDATE + one UPDATE instead of three round-trips.
**Carried forward (still open):** stand up the `SecurityFilterChain` + cookie filter at the first
authenticated endpoint (DL-BE-016 watch-for #3); concurrent idle-roll vs revoke races (the
status-guarded `WHERE … AND status='active'` updates serialise under the `FOR UPDATE` lock, but revisit
if the lock is ever dropped); absolute-ceiling re-auth UX (`session_expired` to the frontend).
Validated: 74 tests green.

---

## DL-BE-018 — M4a command substrate: idempotency, MFA-freshness enforcement & the audited command envelope
**Date:** 2026-06-21
**What:** First state-changing-command slice (spec `docs/modules/M4a-command-substrate.md`).
`CommandGateway.execute(CommandRequest, CommandHandler)` — the one harness every command routes
through: idempotency claim/replay on `sys_command_log` (#4 — the M1b deferral's first consumer),
MFA-freshness enforcement for admin actors via M3b `isMfaFresh` (#2/AU10.3), optimistic concurrency
(P8), and exactly one `command_id`-stamped audit envelope appended in-tx (#5/X13). Types
`CommandRequest/Handler/Outcome/Event/Result` + `CommandRejectedException` (B4 §4.2 reason codes →
HTTP status). First command `AdminUserService.disableAdminUser` proves the harness. **No new
migration** (all M4 tables exist V2–V4). Built **test-first**: 9 `CommandGatewayTest` cases written
against the API, run **red** on a stubbed gateway, then implemented to green; 83 total.
**Why (key decisions, as built):**
**M4 slicing (architect's call, substrate-first):** **M4a** = the command-control engine (this);
**M4b** = Admin IAM + RBAC + TOTP enrollment; **M4c** = SoD engine + maker-checker. Distinct from the
coarse plan register, which lists M4 as one module — recorded here so the split is on the record.
**Key decisions:**
1. **Boundary order (as built):** null-session/missing-assertion guard → idempotency-replay →
   MFA-fresh gate → claim (`INSERT … ON CONFLICT DO NOTHING`) → execute → append envelope → record
   `resulting_event_id`. The **version gate lives inside the handler** (a generic gateway can't read an
   arbitrary aggregate's version pre-claim), so it fires after the claim; the single transaction rolls
   the claim back on any throw, so a reject still leaves no log row / no envelope / no mutation. A
   replay returns the original without re-checking freshness. The #1/#3 gates (M4c) slot just before execute.
2. **Idempotency conflict scope:** detect divergent reuse on `(command_type, aggregate_type,
   aggregate_id)`; full payload-hash G32 deferred (no `sys_command_log` column).
3. **First command `disableAdminUser`:** proves the harness; its Super-Admin role authz is M4b. M4a
   exposes no HTTP surface, so the un-authorized path is not externally reachable.
4. **MFA-fresh reuse:** reuse M3b `isMfaFresh(session, ActionSensitivity)`; the command declares its
   sensitivity; no new freshness code.
**`/code-review` follow-ups applied (high effort):**
(a) **Null-session NPE guards + request validation** — `execute()` dereferenced `session.identityId()`
in the idempotency lookup *before* the null-session check the code claimed to handle; reordered so a
session-less / assertion-less admin command returns `mfa_assertion_missing` cleanly, and added a typed
`validate()` so a null `aggregateId`/`commandId` fails with a 400 instead of a raw NOT-NULL violation
mid-claim.
(b) **Clean reject when the actor has no `admin_user` row** — the `disabled_by` lookup used
`queryForObject` (threw `EmptyResultDataAccessException`); now null-tolerant → `ValidationException`
(real RBAC authz is M4b).
(c) **Accurate version on the lost-race path** — the post-`UPDATE` `updated==0` branch reported a
fabricated `actual=-1`; now re-reads the true current `aggregate_version` for the 409 body.
**Guardrail (carry forward):** maker-checker (#1) and SoD (#3) are **unbuilt** in M4a — no
maker-checker-gated command (go-live, disbursement, role-assign) may ship before **M4c** fills the
hooks (same discipline as the M1b #4 deferral). Also: full payload-hash idempotency conflict; one
`correlation_id` per command until multi-step chains (G29) are designed in M4c; the audit-envelope
builder spine is now hand-assembled in three services (`AuthService`, `SessionService`,
`CommandGateway`) — extract a shared factory when the next producer lands (deferred, not a bug).
Validated: 83 tests green.

---

## DL-BE-019 — M4b Admin IAM + RBAC + TOTP enrollment
**Date:** 2026-06-21
**What:** M4 slice 2/3 (spec `docs/modules/M4b-admin-iam-rbac-totp.md`). `AdminUserService`
(provision invited → `activateAdminUser` gated on AU10.1 → disable/enable), `TotpService` (RFC-6238
TOTP enroll/confirm; `Totp` is dependency-free HMAC-SHA1 + inline Base32, verified by RFC-6238 vector
tests), `RbacService` (super_admin-gated assign/revoke) + `RoleResolver` (the read-only
`ActorAuthorization` the gateway consults), `SecretCipher`/`AesGcmSecretCipher` (AES-256-GCM, dev key)
for the encrypted TOTP secret, and `AdminBootstrap` (seeds the first super_admin outside the authz'd
flow). The M4a `CommandGateway` gained a role-authz gate (`requiredRoles` → `role_not_held` 403, no
envelope), closing the M4a `disableAdminUser` authz gap. **No new migration.** 10 new tests; 95 green.
**Why (key decisions, as built):**
**Planned scope (M4 slice 2 of 3):** admin user lifecycle (`provisionAdminUser` invited →
`activateAdminUser`, gated on AU10.1 → `disable`/`enable`), **TOTP enrollment** into `auth_mfa_factor`
(the C7 factor admin activation requires), **composable RBAC** (`assignRole`/`revokeRole`,
`activeRoles` = union of active assignments, C18/DL-032), and **role-authorization at the command
boundary** — extending the M4a `CommandGateway` so a command declares `requiredRoles` and a non-holder
is rejected `role_not_held` (403, no envelope, G22). Every command runs through the M4a gateway,
inheriting #2/#4/#5. **No new migration** (`admin_user`, `admin_role_assignment`, `auth_mfa_factor`
exist V2/V3).
**Decisions taken to DoR-green (confirm/revise at build):**
1. **SoD (#3) + maker-checker (#1) deferred to M4c.** M4b writes `admin_role_assignment` **without**
   the SoD gate. **Guardrail:** role-assign must not be exposed (and SoD must gate it) before M4c —
   an un-gated assign can create the strict credit_reviewer⊕treasury_and_settlement pair.
2. **TOTP secret:** a `SecretCipher` port (dev AES-GCM with a config key) writes
   `auth_mfa_factor.secret_encrypted`; real KMS at Production (ACL-port pattern). `last_used_at IS NOT
   NULL` is the "confirmed" signal for the AU10.1 activation gate — no schema column added.
3. **TOTP algorithm:** RFC-6238 via JDK `javax.crypto.Mac` (HMAC-SHA1, 6-digit, 30s, ±1 window) +
   Base32; add `commons-codec` only if Base32 is not already transitive. No heavyweight lib.
4. **Permission model = role-based:** the five `admin_role` values are the Phase-1 permission units
   (the corpus defines no fine-grained catalog); authz = `activeRoles ∩ requiredRoles ≠ ∅` (C18).
5. **Bootstrap:** the first `super_admin` is **seeded** outside the authz'd flow (no Super Admin exists
   to provision the first).
6. **Authz home:** wired into the M4a `CommandGateway` (authz step after the MFA gate, before claim);
   closes M4a's `disableAdminUser` authz gap.
7. **TOTP-at-login assertion minting deferred** (small follow-up); admins use the SMS-OTP fallback
   (M3a) meanwhile; the gateway is factor-agnostic.
**As-built notes:** Base32 is implemented inline in `Totp` (the only `commons-codec` jar is a stale,
unresolved transitive — no dependency added). `provisionAdminUser` uses the caller-supplied
`request.aggregateId()` as the new admin's id, so the gateway's idempotency key + envelope name the
entity being created. Dev AES key is `platform.security.secret-key` (32-byte base64) with a clearly
dev-only default.
**`/code-review` follow-ups applied (high effort):**
(a) **Disabled admin is deauthorized** — `RoleResolver.activeRoles` now filters
`admin_user.status = 'active'` as well as the role-assignment status; disabling an admin (whose role
rows are not individually revoked) immediately strips their command authority. (High-severity authz
hole; regression test added.)
(b) **TOTP confirm is one-time** — `confirmTotp` rejects a replayed code against an already-confirmed
factor (`last_used_at IS NOT NULL`); full last-step replay tracking belongs with the deferred
TOTP-at-login slice.
(c) **AES-GCM decrypt length guard** — a blob shorter than nonce+tag is rejected with a typed error
instead of an `ArrayIndexOutOfBounds`.
(d) **Duplicate-key translation** — `provisionAdminUser` catches `DuplicateKeyException` (the email
pre-check is best-effort/TOCTOU) and returns a clean `ValidationException` rather than a 500.
(e) **Reactivation clears soft-SoD columns** — `assignRole`'s `ON CONFLICT DO UPDATE` nulls
`deviation_register_entry_id` / `sod_warning_acknowledged_at` / `override_reason` so a reactivated
assignment carries no stale deviation reference (M4c re-evaluates SoD).
**Watch for (carry forward):** revoking an admin's last active factor must downgrade `status` from
active (schema comment) — not handled in M4b; KMS + secret rotation at Production; TOTP-at-login needs
proper last-step replay tracking; the **audit-envelope builder is now hand-assembled in four producers**
(`AuthService`, `SessionService`, `CommandGateway`, `TotpService`) — the shared-factory extraction
(flagged in [[DL-BE-018]]) is now overdue and should be its own cleanup change (touches Done modules,
kept out of this slice deliberately); keep the authz gate factor-agnostic so the AI-agent actor model
(G31) composes later.
Validated: 95 tests green.

---

## DL-BE-020 — Shared audit-envelope factory (`AuditEnvelopes`)
**Date:** 2026-06-21
**What:** Standalone cleanup closing the watch-for carried since [[DL-BE-018]]/[[DL-BE-019]]. The
common `AuditEventEnvelope` builder spine (fresh `eventId`, `occurredAt = now`, fresh
`correlationId`, default `aggregateVersion = 1`, context/aggregate) was hand-typed in four producers;
extracted into `audit/AuditEnvelopes.seed(context, aggregateType, aggregateId)`. Migrated all four:
`AuthService`, `SessionService` (overrides `correlationId` for the shared-correlation establish path),
`TotpService`, and `CommandGateway` (overrides `aggregateVersion` + sets `commandId`/before/after).
**Why:** A fourth producer (TotpService, M4b) made the duplication overdue — a future required-field or
correlation change was an N-site edit. Pure refactor, behaviour-preserving; the 95-test suite (which
asserts envelope `command_id`, `mfa_assertion_id`, chain integrity, and shared correlation) is the
regression guard. No new producer-visible behaviour, no schema change.
**Watch for:** the Actor sub-shape is still built at each call site (it genuinely varies — null vs
session-bound); not worth abstracting further.
Validated: 95 tests green.

---

## DL-BE-021 — M4c SoD enforcement & deviation register
**Date:** 2026-06-21
**What:** M4 slice 3 (spec `docs/modules/M4c-sod-enforcement.md`). `SodPolicyService` (rules-as-data
strict/soft evaluator reading the active `admin_sod_policy`; `seedDefaultPolicy`, `publishSodPolicy`
supersession, `reviewDeviation`, `logDeviation`) + SoD integration into `RbacService.assignRole`:
strict pair → `sod_role_block` (403, no envelope); soft pair → requires `override_reason`, writes one
`admin_deviation_log` entry, links it, emits `SodSoftDeviation.Logged`. Builds non-negotiable #3 and
**lifts the M4b role-assign guardrail**. `super_admin`-gated commands via the M4a gateway. **No new
migration.** 7 new tests; 103 green.
**Why (key decisions, as built):**
**M4 re-slice (flagged for the architect):** the original plan grouped M4c = "SoD + maker-checker."
Built M4a/M4b show these are independently large, unrelated mechanisms, so **M4c = SoD enforcement +
deviation register** and **maker-checker (#1) → M4d**. Recorded so the split is on the record; trivial
to recombine if the architect prefers.
**Planned scope (M4 slice 3 of N):** the two-tier SoD engine (C5/DL-033) on M4b's `assignRole` —
**strict block** (credit_reviewer ⊕ treasury_and_settlement → `sod_role_block`, 403, no envelope) and
**soft warn** (the three soft pairs → require `override_reason`, create exactly one
`admin_deviation_log` entry, emit `SodSoftDeviation.Logged`); a rules-as-data `SodPolicyEvaluator`
reading the current `admin_sod_policy`; `reviewDeviation` (quarterly, DE.1/DE.2); `publishSodPolicy`
(supersession, SP.1). Builds **non-negotiable #3** and **lifts the M4b role-assign guardrail**. All
commands route through the M4a gateway (#2/#4/#5 + super_admin authz). **No new migration**
(`admin_sod_policy`, `admin_deviation_log` exist V2).
**Decisions taken to DoR-green (confirm/revise at build):**
1. **Rules-as-data evaluator:** the SP.2 fixed policy is seeded and read from `admin_sod_policy` (not
   hard-coded), so a future `publishSodPolicy` changes behaviour without code change.
2. **Strict block is app-only** (no DB CHECK spans the two-row pair); the handler **locks the admin's
   active role rows (`SELECT … FOR UPDATE`)** before check+insert to close the read-then-insert TOCTOU.
   A DB exclusion constraint is the stronger long-term guard (watch-for).
3. **Maker-checker (#1) → M4d**, with the Walking-Skeleton guardrail (go-live/disbursement need it).
4. **RA.4 (auditor ⊕ operational role) deferred to M17** (no `audit_account` to cross-check yet).
5. **Quarterly-review scheduler → M5/ops**; M4c ships only the `reviewDeviation` command.
**`/code-review` follow-ups applied (high effort):**
(a) **`publishSodPolicy` validates every pair** — exactly two distinct, known `AdminRole` wires. A
malformed pair (wrong arity, typo, same-role) would never match a real `Set.of(a,b)` at evaluate time
and would *silently disable* the strict block — the highest-severity finding. (Regression test added.)
(b) **Fail closed on a missing policy** — `evaluate` now refuses the assignment if no active policy is
seeded (an explicitly-published *empty* policy still returns CLEAR); a regulated control must not
default-open because the policy row is absent.
(c) **No duplicate deviation on re-assign** — re-assigning an already-active soft-override role
short-circuits (idempotent) instead of minting a second `admin_deviation_log` entry and orphaning the
first.
(d) **`reviewDeviation` validates the decision** (non-blank) before the UPDATE, so a null decision is a
clean 400, not a raw `admin_deviation_log_review_chk` 500.
(e) **Duplicate-key translation** — concurrent `publishSodPolicy` or a reused policy id surfaces as a
clean `ValidationException` (retry), not a raw 500; the one-active uidx remains the integrity backstop.
(f) **Consolidated `identity→admin_user_id`** — the thrice-duplicated lookup (AdminUserService /
RbacService / SodPolicyService) moved to `RoleResolver.adminUserId`; `requireAdminExists` folded into
the `FOR UPDATE` lock. Stale "maker-checker (M4c)" javadoc fixed to M4d.
**Watch for (carry forward):** strict-SoD concurrency uses a parent-row `FOR UPDATE` lock; a DB
exclusion constraint would make the DB the last line of defence (evaluate in M4d/hardening). Policy
supersession must not orphan in-flight deviations. `enforcement_tier` is descriptive-only in Phase 1
(the evaluator reads only the pair-sets). **Test-helper duplication** (SodEnforcementTest ↔
AdminIamTest) — a shared `AbstractAdminIamTest` base is now warranted; deferred as test-only cleanup.
Validated: 103 tests green.

---

## DL-BE-022 — Disable-cascade hardening (admin disable revokes identity + sessions)
**Date:** 2026-06-21
**What:** Completes the M4b authz-hole remediation. `RoleResolver` already stripped a disabled admin's
command authority (DL-BE-019 follow-up a); but disable wasn't a full cascade — the admin's
`auth_identity` stayed `active` (so they could re-authenticate, M3a checks identity status) and their
`auth_session` rows stayed live. `disableAdminUser` now also sets `auth_identity.status = 'disabled'`
and bulk-revokes the identity's active sessions (`SessionService.revokeAllForIdentity`), all in the
command transaction; `enableAdminUser` re-activates the identity (old sessions stay revoked — a fresh
one is minted on next login). The `AdminUser.Disabled` envelope payload carries `sessions_revoked`.
**Why:** Defense-in-depth across the three planes a disabled admin could still act on — command boundary
(RoleResolver), authentication (identity status), and existing sessions. Invariant test asserts a
disabled admin can neither authenticate, resolve a live session, nor be authorized.
**Decisions:** (1) **bulk session revoke, no per-session envelopes** — the `AdminUser.Disabled` envelope
(with the count) is the audit record; N envelopes would be noise. (2) `revokeAllForIdentity` lives on
`SessionService` (auth owns `auth_session`); `AdminUserService` calls it — no cross-module table write.
**Watch for:** revoking an admin's *last MFA factor* still doesn't downgrade status (separate, noted in
DL-BE-019); a future generic "deactivate principal" path should reuse this cascade.
Validated: 96 tests green.

---

## DL-BE-023 — M4d maker-checker engine
**Date:** 2026-06-21
**What:** M4 slice 4/4 — the last foundation control, **non-negotiable #1** (spec
`docs/modules/M4d-maker-checker.md`). `MakerCheckerGate` (C4, X11): reads the most-recent *unanswered*
proposal envelope from `sys_audit_event` and compares its `actor_id` (= the human identity) to the
checker's; `evaluate` returns blocked/clear + the proposed `aggregate_version`; `pendingApprovals` is
the queue projection (excludes the maker). Proving flow on `AdminUserService`: `proposeDisableAdmin`
(maker; one-open-proposal guard) + `approveDisableAdmin` (checker; **Blocked = a committed
`MakerChecker.Blocked` envelope returned as the command event, no rollback**; **Approved = the disable
cascade anchored to the proposed version + a `MakerChecker.Approved` side-envelope**, one transaction).
`applyDisableTransition` extracted so the direct (M4b) and four-eyes (M4d) disables share it. Built
**test-first** (7 `MakerCheckerTest`, red → green). **No new migration** — maker-checker state IS the
envelope stream. 110 green.
**Why (key decisions, as built):**
**Planned scope (M4 slice 4/4 — last foundation control):** the record-level maker≠checker primitive
(C4, X11, DL-033) — a `MakerCheckerGate` the checker command invokes: read the most-recent maker
(proposal) envelope on the originating aggregate, compare `actor.actor_id` to the checker's. Equal →
`MakerChecker.Blocked` + 409 `maker_checker_violation`; distinct → `MakerChecker.Approved` + the
transition, atomically. Plus the queue projection (proposals awaiting a different checker). Builds
**non-negotiable #1**. Proven on one representative BC10 four-eyes flow (maker-checker'd admin disable:
propose → approve). Real consumers (M9 go-live, M13 disbursement, M15 KYC) plug in later. **No new
migration** — maker-checker state IS the envelope stream (`sys_audit_event`); the queue is a projection.
**Decisions taken to DoR-green (confirm/revise at build):**
1. **Blocked is a committed, audited outcome — the key departure from M4a.** A blocked checker command
   does NOT throw-and-roll-back (that's the pre-authorisation reject model); the gate appends
   `MakerChecker.Blocked` and the command commits, returning a `Blocked` `CommandResult` the caller maps
   to 409 (B4 §4.2: `maker_checker_violation` *emits* an envelope, unlike role/version rejects).
2. **Approve = `MakerChecker.Approved` side-envelope + the transition as the gateway `CommandEvent`**
   (same two-envelope pattern as M4c soft-deviation), both in one transaction (X13).
3. **Check on `actor_id`, not role/session** (C4/C18) — two roles held by one human still can't be both
   maker and checker.
4. **Proving flow = maker-checker'd admin disable**; whether to retrofit other sensitive BC10 commands
   (provision, role-assign) to four-eyes is a product/compliance decision — NOT assumed in M4d; the
   direct M4b disable remains until then (control-coverage gap, pre-production only).
5. **Maker-checker state read from `sys_audit_event`** (latest proposal envelope = the maker; unanswered
   = no later Approved/Blocked); the queue is a projection over it.
6. **G29 scope:** the check is on the initiating aggregate only; dependents inherit via `causation_id`.
**`/code-review` follow-ups applied (high effort):** the review found a structural weakness — the gate
answered "does a proposal exist" rather than "is the latest proposal still *unanswered*", and approve
used the current (not proposed) version. Fixed:
(a) **Answer-aware gate** — `evaluate` now returns the most-recent proposal with **no later
Approved/Blocked** (so a resolved proposal is not actionable, and re-answering is impossible); ordering
uses `event_id` (UUIDv7, time-ordered) not microsecond-truncated `recorded_at`.
(b) **Proposed-version anchor** — approve applies the transition guarded on the **proposed** version
(returned by the gate), so a state drift between propose and approve conflicts rather than
blind-approving the current state; the `MakerChecker.Approved` envelope is stamped with that version
(was the default 1).
(c) **One open proposal per aggregate** — propose locks the admin row `FOR UPDATE` and rejects if an
unanswered proposal exists, closing the "interpose a second proposer so the original maker can approve"
hole (the maker-of-record is now unambiguous).
(d) Shared event-type constants (`MakerCheckerGate.APPROVED_EVENT/BLOCKED_EVENT`, `DISABLE_PROPOSED`);
dropped the redundant `currentVersion`; refreshed the stale `AdminUserService` class javadoc.
**Watch for (carry forward):** audit-log-as-read-model can get query-heavy → a projection table is the
later optimisation (audit log stays canonical); `maker_aggregate_state_invalid` full state-machine check
arrives with M9+; the `MakerChecker.Approved` side-envelope and the transition envelope are not yet
correlation-linked (the broader correlation-threading deferral from [[DL-BE-018]]); **test-helper
duplication is now in three admin-IAM test classes** — the `AbstractAdminIamTest` base extraction
(flagged in [[DL-BE-021]]) is overdue; **four-eyes coverage** — the direct M4b disable still bypasses
maker-checker (close before production). Maker-checker'd disable could move to its own service if
`AdminUserService` grows further.
Validated: 110 tests green.

---

## DL-BE-024 — M5a Verification ACL (BC17)
**Date:** 2026-06-21
**What:** First M5 slice (spec `docs/modules/M5a-verification-acl.md`) — the BC17 Verification ACL.
A `VerificationPort` (universal `verify(VerificationRequest)` over all 11 `VerificationApi` values +
typed sugar for 4 common ops) implemented by the **fixed** `VerificationService` (cache,
`gate_verification` persistence, audit, ACL idempotency), which calls the **swappable**
`VerificationVendorClient` — `StubVerificationVendorClient` (deterministic auto-pass) now, real
aggregator later. A non-stale completed TTL'd result for `(subject, api)` is cache-reused; one-shot
APIs (null TTL) are never reused. `Verification.Requested`/`.Completed` audited via `AuditLog`. Built
**test-first** (6 `VerificationAclTest`, red → green). **No new migration** (`gate_verification` exists
V4; the migrated column is `hmac_verified_at`). 116 green.
**Why (key decisions, as built):**
**M5 slicing (flagged for the architect):** M5 bundles four integration ACLs; they're independent
integrations, so M5 is sliced **by bounded context** — **M5a Verification (BC17)** (this) → **M5b
Banking/Escrow (BC18)** → **M5c Signing (BC19)** → **M5d Notifications-full (BC15)** (M3a already
shipped a thin `NotificationPort`/`StubNotifier`). M5a is first because it's the simplest (no money,
auto-pass) and **establishes the ACL pattern** the others reuse.
**Planned scope:** a real `VerificationPort` (the 11 aggregator ops — `verifyPan`, `verifyGstin`,
`verifyIrn`, `screenAmlPep`, …) with a **fake in-process `StubVerificationAdapter`** that auto-passes
with deterministic `extracted_fields` and per-type TTLs (A2 §1.4); `gate_verification` persistence with
the cache rule (a non-stale completed `(subject, api)` is reused, not re-called, V.1/V.4); audit
envelopes. **No new migration** (`gate_verification` + enums exist V4).
**Decisions taken to DoR-green (confirm/revise at build):**
1. **Real ports, fake adapters** — only the adapter is swapped (fake → sandbox → production) at the
   Production gate; the port/aggregate/events stay fixed. The vendor model never leaks (A1/B1 ACL rule).
2. **Webhook ingress deferred to the real adapter** — the stub completes **in-process**, so the
   `/webhooks/verification/...` routes, HMAC over `(timestamp||body)` (C10), the 5-min replay window
   (A2 §1.2), and `vendor_event_id` dedup land when the real adapter does. The stub stamps
   `signature_verified_at` at completion (V.2).
3. **Verbatim payload archival deferred** — only `vendor_payload_hash` stored now; full payload into
   `sys_document_object` is **BC16 Documents** (not built).
4. **Not a gateway command** — verification is system-triggered; idempotency is the ACL key
   (`client_request_id` = `verification_id`) + the `(subject, api)` cache, **not** the M4a `command_id`
   store. Audited via `AuditLog` directly.
5. **AML/PEP adjudication → BC11/M15**; BC17 makes the `screen_aml_pep` call only.
**As-built refinements:** (a) the swappable seam is `VerificationVendorClient`, **not** the port —
`VerificationService` (fixed: cache/persistence/audit) implements `VerificationPort` and delegates the
raw call to the client; only the client is swapped. Cleaner than the draft's "adapter implements port"
and the correct ACL shape. (b) The migrated column is **`hmac_verified_at`** (V4 renamed
`signature_verified_at`) — the stub stamps it at completion. (c) Typed convenience covers 4 ops;
`verify(VerificationRequest)` supports all 11.
**`/code-review` follow-up applied:** the stub's raw payload now folds in `inputs`, so a one-shot
verification's `vendor_payload_hash` actually varies with the request (not just the api/subject) —
regression test added.
**Watch for (carry forward):** **(real-adapter, latent under the stub)** — the failed path
(`requested→failed` + `Verification.Failed` envelope + `failure_class`) has **no code yet**: the
stub never fails, so a vendor error would currently roll back the `requested` row + its envelope with
no record. Build the failed-path (likely a separate transaction so the failure persists) when the real
adapter lands. **Concurrent `verify()`** for one `(subject, api)` both miss the cache and insert two
completed rows (a real adapter = a duplicate paid vendor call); a partial unique index on
`(subject_id, api_name) WHERE status='completed'` would close it (needs a migration) — acceptable for a
stub. Plus the previously-noted: the real-adapter webhook stack (HMAC, 5-min replay, `vendor_event_id`
dedup, `/webhooks/...` routes) + BC16 payload archival + manual-fallback (G8) + the TTL-sweep scheduler
+ the outage banner; and the JSON/Instant helpers are the first candidates for a shared ACL base once
M5b/M5c land (not before).
Validated: 116 tests green.

---

## DL-BE-025 — M5b Banking/Escrow ACL (BC18)
**Date:** 2026-06-21
**What:** Second M5 slice (spec `docs/modules/M5b-banking-escrow-acl.md`) — the BC18 escrow ACL,
reusing [[DL-BE-024]]'s shape (fixed `EscrowAclService` + swappable `EscrowVendorClient`).
`EscrowPort` (createVa, payout single + multi-leg, refund, `processInflowWebhook`) keyed by
`client_instruction_id` (= `vendor_instruction_id` PK). Outbound idempotency + inbound inflow dedup
both use `INSERT … ON CONFLICT DO NOTHING` (atomic). `StubEscrowVendorClient` gives a deterministic
IFSC/account + fake UTR. Inflows recorded provisional in `gate_inflow_observation` (amount > 0, deduped
on `vendor_event_id` AND `utr` → `Webhook.DuplicateDropped`). Vendor-assigned values (IFSC/UTR) flow to
BC4 via the webhook envelopes (no dedicated columns), so an idempotent retry re-reads them from the
audit stream. Built **test-first** (6 `BankingAclTest`, red → green). **No new migration** (both
`gate_*` tables + enums exist V4). 122 green.
**Why (key decisions, as built):**
**Planned scope (M5 slice 2/4 — heaviest):** the BC18 escrow ACL, reusing [[DL-BE-024]]'s shape (fixed
service + swappable vendor client). `EscrowPort` (createVa / payout single+multi-leg / refund / closeVa)
keyed by `client_instruction_id` (= `vendor_instruction_id` PK, the idempotency key, VI.1); inbound
webhook handlers (`processPayout/Refund/InflowWebhook`) with **`vendor_event_id` dedup** → first applies
(fake UTR, executed), duplicate → `Webhook.DuplicateDropped`, no state change (VI.3); inflows recorded
**provisional** in `gate_inflow_observation` (amount > 0, deduped on `vendor_event_id` AND `utr`).
`StubEscrowVendorClient` returns a deterministic IFSC/account + fake UTR and fires webhooks in-process.
Sole consumer is **BC4 Settlement (M13, not built)** — M5b ships the port BC4 will call. **No new
migration** (both `gate_*` tables + enums exist V4).
**Decisions taken to DoR-green (confirm/revise at build):**
1. **One slice, with a split option** — heaviest M5 slice; if it balloons, split outbound (VA/payouts)
   from inbound (inflow/webhook-dedup). Held together because they share the dedup substrate.
2. **Idempotency = ACL keys** — `client_instruction_id` (retry = no-op, no double-execute) +
   `vendor_event_id` webhook dedup — **not** the M4a `command_id` store.
3. **Maker-checker/MFA is BC4's**, not the ACL's — the ACL executes an already-approved payout
   instruction (PI.5 four-eyes is upstream, M4d).
4. **Deferred to BC4 Settlement (M13):** reconciliation (`provisional → reconciled/unmatched`, the EoD
   master-statement parser, corrective overlays X15); multi-leg partial-failure remediation (VI.5/G11);
   TDS challan (VI.6). The stub leaves inflows provisional and auto-succeeds all legs.
5. **Webhook ingress deferred to the real adapter** — `processXWebhook` is the entry; HMAC + 5-min
   replay (A2 §1.2) + dead-letter + the `/webhooks/banking/...` routes are the real adapter's. Stub
   stamps `hmac_verified_at` at the in-process webhook (VI.2).
**`/code-review` follow-ups applied (high effort):**
(a) **Atomic idempotency claim** — replaced the outbound `exists()`-then-insert (a TOCTOU where a
concurrent same-`client_instruction_id` caller's loser hit the PK and got an exception) with `INSERT …
ON CONFLICT (vendor_instruction_id) DO NOTHING`; rowcount 0 ⇒ re-read the original outcome. Now matches
the inflow path and honours the VI.1 no-op contract under concurrency.
(b) **Multi-leg retry order** — `envelopeFields` now re-reads per-leg UTRs ordered by the explicit
`leg_index`, not `recorded_at` (same-microsecond legs could permute → UTR↔beneficiary misattribution on
a money distribution). Regression test added.
(c) **DuplicateDropped traceability** — a dropped duplicate inflow's envelope is filed under the
**original** inflow's id (looked up by the colliding `vendor_event_id`/`utr`), so the drop and the
original correlate by `aggregate_id`.
**Watch for (carry forward):** the failed-instruction path (like M5a, latent under the stub); the
real-adapter webhook stack (HMAC, 5-min replay, dead-letter, `/webhooks/banking/...`) + TDS + BC16
archival; **reconciliation/remediation are BC4/M13** (provisional inflows are meaningless until then).
M5b is the **second** ACL consumer — the shared-ACL-base extraction (idempotent-instruction +
`vendor_event_id` dedup + JSON/Instant helpers) is now worth doing before/with M5c.
Validated: 122 tests green.

---

## DL-BE-026 — Shared ACL base (`AbstractAclService`)
**Date:** 2026-06-22
**What:** Standalone refactor closing the shared-ACL-base watch-for ([[DL-BE-024]]/[[DL-BE-025]]) before
M5c lands. Extracted `acl/AbstractAclService` — the two pieces every integration-ACL fixed service
repeats: `auditAclEvent(aggregateId, eventType, payload)` (the system-actor ACL envelope) and
`sha256(byte[])` (verbatim-payload hash). The per-ACL `context` / actor-id / aggregate-type are
constructor args. `VerificationService` (BC17) and `EscrowAclService` (BC18) now extend it; M5c
Signing and M5d Notifications will too.
**Why:** Two near-identical `audit()` + identical `sha256()` copies today, heading for four. Sharing
them keeps the ACL audit shape and the hash-only (V.3) discipline in one place. Behaviour-preserving;
the 122-test suite (envelope assertions across both ACLs) is the regression guard.
**Scope decision:** deliberately kept the base **minimal** — the JSON↔jsonb and Instant↔OffsetDateTime
helpers stay in `VerificationService` (the only user; Escrow doesn't serialise JSON), and the
idempotency mechanisms are **not** shared (Verification caches by `(subject, api)`; Banking claims by
`client_instruction_id` via `ON CONFLICT` — genuinely different, table-specific). Forcing them into a
base would be a wrong-altitude abstraction.
Validated: 122 tests green.

---

## DL-BE-027 — M5c Signing ACL (BC19)
**Date:** 2026-06-22
**What:** Third M5 slice (spec `docs/modules/M5c-signing-acl.md`) — the BC19 e-Sign ACL, the first ACL
built **on** [[DL-BE-026]]'s `AbstractAclService`. `SignatureAclService` (extends the base) +
`SigningPort` (`initiateSignature` → `SignatureSession{vendorSessionUrl}`, `completeSignature` →
`cert_serial`, `fetchSignature`) behind the swappable `StubSigningVendorClient` (deterministic session
URL + auto-success fake cert). `gate_signature_session` lifecycle `session_initiated → completed`,
idempotent on the `(signature_request_id, doc_hash)` UNIQUE; `cert_serial` set only on `completed`
(DB CHECK). Produces the "signed via stub" the Walking Skeleton needs. Sole consumer BC5 (M12, not
built). Built **test-first** (6 `SigningAclTest`, red → green). **No new migration**
(`gate_signature_session` + enums exist V4). 128 green.
**Why (key decisions, as built):**
**Planned scope (M5 slice 3/4):** the BC19 e-Sign ACL, reusing the ACL shape on
[[DL-BE-026]]'s `AbstractAclService`. `SigningPort` (`initiateSignature` → `SignatureSession{vendorSessionUrl}`,
`completeSignature` → `cert_serial`, `fetchSignature`) with a deterministic `StubSigningVendorClient`
(fixed session URL, auto-success fake cert). `gate_signature_session` lifecycle
`session_initiated → completed`, idempotent on the `(signature_request_id, doc_hash)` UNIQUE + `vsr_id`
PK; `cert_serial` only on `completed` (DB CHECK). Audit via the inherited `auditAclEvent`. Sole consumer
is **BC5 Assignment & Signing (M12, not built)** — produces the "signed via stub" the Walking Skeleton
needs. **No new migration** (`gate_signature_session` + enums exist V4).
**Decisions taken to DoR-green (confirm/revise at build):**
1. **Reuses `AbstractAclService`** (audit + sha256); only the signing persistence/idempotency is new.
2. **Two-step initiate → complete** mirrors the real async flow; the stub completes in-process. HTTP
   webhook ingress + HMAC + 5-min replay are the real adapter's.
3. **e-Sign only** — e-Stamp (`issue_stamp`/`StampIssued`, master-level, G15) deferred (no stamp table;
   own concern).
4. **Not a gateway command** — BC5-triggered; idempotency is the ACL key, audited directly.
5. **Aadhaar-OTP/DSC paths, UIDAI-outage degradation, retry/expiry (cap 3), BC16 archival** → the real adapter.
**`/code-review` follow-ups applied (high effort):**
(a) **Idempotency targets the `(signature_request_id, doc_hash)` UNIQUE** (`ON CONFLICT
(signature_request_id, doc_hash)`), not an untargeted clause — so the only `claimed==0` path is the
idempotent re-initiate, which always re-reads a present session (no silent-null → NPE). A `vsr_id` PK
collision (the caller reusing a vsr id for a *different* signature) is now a clean `ValidationException`,
not a 500 or a null. Regression test added.
(b) **Input guards** — null/empty `docHash` or blank `signerRef` → `ValidationException` (consistent with
the rest of the class, instead of a raw NOT-NULL 500).
(c) **Collision-free stub cert** — uses the full `vsr_id` hex, not a 12-char prefix (UUIDv7's monotonic
timestamp prefix made same-prefix collisions plausible).
**Watch for (carry forward):** the failed/expired path (latent under the always-succeeding stub);
under concurrency `completeSignature` could make a double *vendor* call before the guarded UPDATE
(no double-audit/state; benign for the deterministic stub, a real-adapter dedup concern); the
real-adapter webhook stack + the two signing paths + outage degradation + BC16 signed-doc archival.
Validated: 128 tests green.

---

## DL-BE-028 — M5d Notifications-full (BC15): the dispatch lifecycle behind the real port
**Date:** 2026-06-22
**Status:** Built. Spec `docs/modules/M5d-notifications.md` (Status: Done). **Wave 0 complete.**

**What changed.** Completed M3a's thin notification slice into the full BC15 `sys_notification_dispatch`
lifecycle, and split the port from the wire:
- `NotificationService extends AbstractAclService implements NotificationPort` ([[DL-BE-026]]) — now the
  **sole** `NotificationPort` bean. `send()` is `@Transactional`: INSERT `queued` (with the filtered
  payload) → deliver via the channel → UPDATE `sent`+`provider_ref` | `failed` → audit
  `notifications.Notification.Dispatched`/`.DispatchFailed`.
- `StubNotifier` recast `implements NotificationChannel` (was the `NotificationPort` impl) — the swappable
  raw-delivery seam (fake → SES/SNS/Twilio at Production). Kept `lastFor`/`lastCodeFor`/`clear`/`sent`, so
  the M3a OTP-delivery chain is intact (the ~50-test regression risk did **not** materialise — full suite
  **133/133**). `send()` now returns the vendor provider-ref `String`.
- No new migration (`sys_notification_dispatch` + both enums are V4). M3a's `AuthService` (injects
  `NotificationPort`, sends on `afterCommit`) is **unchanged** — it gets the lifecycle + audit for free.

**Why these shapes.**
- **Port/channel split** — exactly one bean per interface (no `@Primary`/qualifier needed): the *fixed*
  half (persistence + audit + PII filter) is `NotificationService`; only the *channel* swaps at
  Production. Same ACL shape as M5a/b/c.
- **Fire-and-forget (ND.1)** — `send()` runs in `afterCommit` (business tx already committed), so a
  delivery failure must never escape. The delivery outcome is settled **first** in a private `deliver()`
  that catches every channel `RuntimeException` *and* a null/blank provider-ref, returning null; only then
  does `send()` persist the outcome + audit. So no channel fault reaches the caller, and an audit hiccup
  can't reclassify a delivered message.
- **No OTP/PII in payload (ND.2, C14/C15, DL-050)** — `safePayload` drops sensitive **keys** (code, otp,
  password, secret, phone, mobile, email, pan, aadhaar) **and** any non-scalar value (Map/Collection/array),
  persisting only scalar template vars. The channel still receives the full `params` to render/deliver.
- **No dedup key** — fire-and-forget permits duplicate sends (acceptable for notifications); the schema
  has no `(recipient, type, ref)` unique. `causation_event_id` left null until the event bus wires
  subscriptions (Walking Skeleton).

**`/code-review` (5 findings, all fixed; 2 regression tests added).**
1. *(must-fix)* `channel.send()` returning **null** → the `'sent'` UPDATE would breach
   `sys_notification_dispatch_provider_ref_shape_chk`, **poison** the Postgres tx, and the recovery
   statements would then throw out of `afterCommit` (ND.1 breach). Fixed: `deliver()` treats null/blank
   ref as a failure → `status='failed'`, no CHECK hit. Test `a_null_provider_ref_is_recorded_as_failed_not_sent`.
2. Audit failure on the **success path** was caught by the same handler that flips to `failed` →
   a delivered message mislabelled `failed`. Fixed by settling the outcome before any UPDATE/audit.
3. Unguarded recovery block could escape `send()`. Fixed: the only fire-and-forget path (channel
   delivery) is fully contained in `deliver()`; residual infra/audit faults surface loudly (audit `append`
   uses `ON CONFLICT DO NOTHING` for chain conflicts, so it does **not** poison the tx).
4. `safePayload` denylist only scrubbed **top-level keys** → a nested map (`{"borrower":{"pan":…}}`) leaked
   PII (ND.2). Fixed: scalars-only filter (structured values dropped + warn-logged). Test
   `a_nested_param_value_is_dropped_from_the_persisted_payload`.
5. Stale `NotificationPort` javadoc still named `StubNotifier` as its adapter — corrected to
   `NotificationService` (impl) delegating to a `NotificationChannel`.

**Watch for.** Real provider swap brings templates/i18n, retries, the dispatch scheduler, `delivered` via
provider receipts, and outage-banner notifications. Event-bus subscriptions + `causation_event_id` linkage
land at the Walking Skeleton. The real channel resolves email/phone from Identity at send — never persist
it (ND.2). Add a `(recipient, type, reference)` dedup key if duplicate notifications become a problem.

---

## DL-BE-029 — Walking Skeleton: one invoice `listed → disbursed` — **MILESTONE 1 COMPLETE**
**Date:** 2026-06-24 (completed)
**Status:** Built. Spec `docs/modules/WS-walking-skeleton.md` (Status: Done). **Milestone 1 — the Walking
Skeleton — is complete.** One invoice walks `listed → disbursed` end-to-end **over HTTP through real
controllers** (`WalkingSkeletonE2ETest`); full suite **198** green.

**The eight sub-slices, all built (each with its own as-built DL):**
- **WS-0** the B4 HTTP edge ([[DL-BE-030]]) — request envelope, error taxonomy, bearer auth.
- **WS-1** Supplier active ([[DL-BE-031]]) · **WS-2** Buyer + ack user ([[DL-BE-032]]) · **WS-3** Investor
  active ([[DL-BE-033]]) — the three counterparties (admin-on-behalf onboarding).
- **WS-4** Listing priced + gone-live ([[DL-BE-034]]) — first money math (`funding_target`, HALF_EVEN) +
  first maker-checker+MFA gate (go-live) + VA.
- **WS-5** Subscribe → fully-funded ([[DL-BE-035]]) — funding equality **G10** + the first inbound webhook
  (HMAC).
- **WS-6** Assignment single-leg signed ([[DL-BE-036]]) — the **C27** `all_signed` gate.
- **WS-7** Disbursement ([[DL-BE-037]]) — the second maker-checker+MFA gate (payout) → `disbursed`.

**The five hardest invariants, proven early and at the wire boundary** (the whole point of the skeleton):
**maker-checker** (go-live WS-4 + payout WS-7, proposer ≠ approver, column-based, no M4d extraction);
**MFA-freshness** (the two checker steps); **funding equality G10** (`Σ confirmed = committed_total =
observed_inflow_total = funding_target`, paise-exact, over-subscription blocked at commit by construction);
**idempotency** (`command_id` + vendor `event_id`/`client_instruction_id`); **audit chaining + audit-before-
2xx** (X13). Compliance auto-approved behind the same command the real BC11 will call (M15); all vendors are
the M5 in-process stubs (the inbound seam exercised for real via the WS-5 HMAC webhook).

**Process that held across all eight slices:** spec → reserve DL → **test-first red→green** → `/code-review`
(adversarial sub-agent finders) → fix → DoD → commit. The review caught a real, non-obvious bug on **every**
money/gate slice (WS-4 int-cast wrap; WS-5 stale-before-image `fully_funded` miss; WS-6 orphaned audit
envelope + swallowed gate flip; WS-7 concurrent double-draft) — the discipline paid for itself repeatedly.

**Carried forward to Milestone 2 (widen Wave 1 to full rigor — the `M`-numbers return):** multi-investor
allocation + partial fills + concentration (M11); the real BC11 compliance engine (M15); the BC18 payout
webhook + EoD reconciliation overlay; the signing webhook (M12); business-day windows + schedulers; all the
reject/alternate state-machine branches; ArchUnit + the BC query-ports that replace the documented
cross-context-read shortcuts; the `docs/sql/` bundle reconciled with the V6 index.

### DL-BE-029 — original reservation (planned, pre-build)
Umbrella number for Milestone 1; sub-slices claim their own subsequent `DL-BE-030+` numbers as built.
**Planned scope (Milestone 1 — the first end-to-end money-flow, *over HTTP*):** the thin vertical cut that
takes ONE invoice through the **B4 HTTP edge** + BC1/2/3/4/5 + BC7/8/9 + BC11(auto-approve stub), on top of
the finished foundation (M0–M5). M1–M5 built the hexagonal *core* (services proven headless); **there is no
HTTP surface yet** (zero business endpoints — only `GlobalExceptionHandler` + Actuator). A true walking
skeleton (Cockburn/GOOS) is a thin slice through **every layer including the delivery mechanism**, so M1
adds the skin: it stands up the B4 command + webhook edges and drives `Listing.GoneLive → Disbursed`
**through real controllers** under one `correlation_id`. **No new money-flow migration** (schema is V1–V5);
the only new persistence is what the edge needs (e.g. a webhook dedup index, if absent). Proves the five
hardest invariants *at the wire boundary*: **maker-checker** (go-live + payout, two endpoints each, proposer
≠ approver), **MFA-freshness** (`X-Mfa-Assertion-Id` on the two checker steps), **funding equality G10**
(`Σ confirmed = committed_total = observed_inflow_total = funding_target`, paise-exact), **idempotency**
(`X-Command-Id` / vendor `event_id`), and **audit chaining + audit-before-2xx** (X13: no 2xx before the
envelope append; unbroken `previous_envelope_hash` end-to-end).
**Minimal config (decisions to confirm at build):** one of each counterparty; one invoice **< ₹1 Cr** to
keep four-eyes (C6) out; a **single subscriber funding 100%** so `assignment_set.total_count = 1`
(collapses BC5 to one leg, dodges the G13 24 h time-box); compliance **auto-approved** behind the same
`record-kyc-approved` command the real BC11 engine will call at M15; all vendors are the M5 in-process
stubs, with their inbound results **delivered through the B4 webhook ingress** (the test POSTs HMAC-signed
events to `/webhooks/…`, exercising the inbound seam for real).
**Sub-slices (build order):** **WS-0 the HTTP edge** (request-envelope resolver, intent-shaped
command-controller, `emitted_events` response, the full B4 §4 error taxonomy on `GlobalExceptionHandler`,
bearer→actor security filter, and — from WS-5 — the webhook ingress; proven against already-built M3/M4
services + one query) · WS-1 supplier active · WS-2 buyer + ack user · WS-3 investor active · WS-4 listing
priced+gone-live (snapshot + maker-checker+MFA #1 + VA) · WS-5 subscribe-to-100% (G10, coordinated commit,
inflow via webhook) · WS-6 assignment single-leg signed (`all_signed` gate, C27, signing via webhook) ·
WS-7 disbursement (maker-checker+MFA #2, payout via webhook). Capstone = `WalkingSkeletonE2ETest`
(MockMvc/`TestRestTemplate`, HTTP-driven). (WS-8 maturity→distribution→closed deferred.)
**Watch for (at build):** **audit-before-2xx** — M2's same-tx append already satisfies X13 in the monolith
*without* a separate outbox table, but WS-0 must assert it at the HTTP boundary so it survives the Phase-2
broker swap (G27); `funding_target` **paise rounding** (pin the rule — a drift breaks G10); the
**coordinated commit** must make over-subscription impossible by construction, not check-then-act; M4d's
maker-checker engine is admin-IAM-shaped — WS-4/WS-7 are the first *domain* two-endpoint pairs, so a
decision-logged extraction may precede WS-4 (mirror the `AbstractAclService` move); `correlation_id` minted
at the edge / propagated via `X-Correlation-Id`; **webhook correlation re-establishment** (G24 — stubs
never echo the platform id; re-link via the stored `client_instruction_id`/`va_id`/`signature_request_id`
mapping); use **DB-enum-true** state names (`operational_checks_in_progress`, `awaiting_acknowledgment`;
the disbursement gate is the `deal_listing.all_signed` boolean, **not** a separate state).

---

## DL-BE-030 — WS-0 the HTTP edge (B4 command surface)
**Date:** 2026-06-23
**Status:** Built. Spec `docs/modules/WS-0-http-edge.md` (Status: Done). First sub-slice of [[DL-BE-029]].
The headless core (M1–M5) now has an HTTP skin: 11 MockMvc edge tests green, full suite 144.

**What shipped.** A stateless B4 command surface over the already-built services (no new domain logic, no
migration):
- `infrastructure/security/SecurityConfig` — a `SecurityFilterChain` replacing Spring Security's
  locked-down default: CSRF/formLogin/httpBasic off, `STATELESS`, permit `/auth/login/**` + Actuator
  health, everything else authenticated. `SessionBearerAuthFilter` (`Authorization: Bearer <sessionId>` →
  `SessionService.resolveSession` → a fresh `SecurityContext` whose principal is the `AuthSession`) +
  `BearerAuthenticationEntryPoint` (B4 401) + `B4AccessDeniedHandler` (B4 403). **Authentication only —
  authz/SoD/MFA stay at the command boundary** in `CommandGateway`, so an endpoint cannot under-enforce by
  forgetting an annotation.
- `infrastructure/web/` — `CommandResponse`/`EmittedEvent` (B4 §2.3) assembled by
  `CommandResponseAssembler` (reads the appended `sys_audit_event` row back by `event_id`, so a replay
  reconstructs the original body even when `CommandResult.result` is null); `ApiError` — the single B4 §4.1
  flat snake_case error body; `GlobalExceptionHandler` now renders it for domain rejects **and** (via a
  `handleExceptionInternal` override) every Spring MVC framework exception, so missing/malformed headers
  and bodies never leak a non-B4 ProblemDetail.
- Demonstrator controllers: `AuthController` (`/auth/login/password` → `/verify-otp` → bearer),
  `AdminUserController` (`provision` 201 / `disable` / `GET` aggregate read).

**Decisions (deviations from B4 documented per its own deviation clause).**
1. **Bearer = `auth_session.session_id`** (opaque; no JWT in Phase 1 — every other principal field loaded
   server-side via `resolveSession`). INV-1.
2. **MFA freshness is session-carried**, re-checked per sensitive command via `isMfaFresh`; B4's per-command
   `X-Mfa-Assertion-Id` header models **step-up MFA** and is deferred. Deviation from B4 §2.2. INV-3.
3. **Audit-before-2xx without an outbox table** — M2's same-tx append already satisfies X13 in the monolith;
   the edge test asserts the named `event_id` is durable before 2xx, so the contract survives the Phase-2
   broker swap (G27). INV-2.
4. **Creating-command identity = `nameUUID(command_id, payload)`** — so a same-`command_id` replay resolves
   to the same aggregate id (the gateway keys idempotency structurally on `(command_type, aggregate_type,
   aggregate_id)`, not a payload hash), while a divergent body under the same id maps to a different id →
   409 `command_id_payload_mismatch`. A payload-hash column (G32) is the later refinement.

**`/code-review` (8 findings — the edge's error-taxonomy completeness; all fixed, 3 regression tests added).**
The headline (both finders): Spring MVC framework exceptions (missing `X-Command-Id`, malformed body/UUID)
were rendering Spring's default RFC-7807 ProblemDetail, **leaking a non-B4 shape on the commonest 4xx
paths** → fixed by the `handleExceptionInternal` override + a `MethodArgumentTypeMismatchException` handler.
Plus: unauthenticated 500s on the open `/auth/login/**` routes (unchecked `body.get`/`UUID.fromString`) →
input validation → 400; `provision` NPE on missing fields → validation; `GET` unknown id → 500 → now 404
(`NotFoundException`); no filter-chain `accessDeniedHandler` → added; filter mutated the shared
`SecurityContext` → now builds a fresh one.

**Watch for.** Step-up MFA + the `X-Mfa-Assertion-Id` header (deferred); the 422 / `MakerChecker.Blocked`
*envelope-emitting* reject (B4 §4.3) lands at WS-4 and reuses `ApiError`; RBAC tenant-scoping on reads
(B4 §3.5) is deferred — any authenticated actor can currently read any admin aggregate (no sensitive
field exposed). The earlier reserved-stub text follows for history.

### DL-BE-030 — original reservation (planned, pre-build)
First sub-slice of [[DL-BE-029]]; **fill in the as-built edge + deviations at WS-0 DoD.**
**Planned scope:** turn the headless core (M1–M5) into an HTTP surface per B4 — a **stateless**
`SecurityFilterChain` (replacing Spring Security's locked-down default), a `SessionBearerAuthFilter`
(`Authorization: Bearer <sessionId>` → `SessionService.resolveSession` → authenticated principal) with a
custom `AuthenticationEntryPoint` for the B4 401 body, a `RequestEnvelope` resolver (B4 §2.2 headers →
`CommandRequest`), a `CommandResponse` assembler (B4 §2.3 `emitted_events`, read back by `event_id`), the
full **B4 §4.1 error body** on `GlobalExceptionHandler` (flat snake_case: `error_code`, `error_category`,
`violating_rule`, `correlation_id`, `retryable`), and two demonstrator controllers wiring already-built
services (`AuthController` login; `AdminUserController` provision/disable + a GET aggregate read). No new
domain logic; no new migration.
**Decisions to confirm at build (deviations need a DL entry per B4):**
1. **Bearer = `auth_session.session_id` UUID** (opaque; no JWT in Phase 1 — every other principal field
   loaded server-side via `resolveSession`).
2. **MFA freshness is session-carried**, re-checked per sensitive command via `isMfaFresh` — B4's
   per-command `X-Mfa-Assertion-Id` header models **step-up MFA** and is **deferred**. Documented
   deviation from B4 §2.2.
3. **Audit-before-2xx without an outbox table** — M2's same-tx append already satisfies X13 in the
   monolith; WS-0 asserts the contract at the HTTP boundary so it survives the Phase-2 broker swap (G27).
4. `CommandResponse.emitted_events` reconstructed by reading `sys_audit_event` by `event_id` (the
   gateway's `CommandResult` carries only the id).
**Watch for (at build):** the existing default `GlobalExceptionHandler` emits camelCase `errorCode` —
align to B4's flat snake_case contract; the 422 / `MakerChecker.Blocked` *envelope-emitting* reject
(B4 §4.3) is **not** WS-0's — it lands at WS-4 and reuses this same error body; keep authz at the command
boundary (the gateway), so the security filter only authenticates, never authorises.

---

## DL-BE-031 — WS-1 Supplier active (BC8)
**Date:** 2026-06-23
**Status:** Built. Spec `docs/modules/WS-1-supplier-active.md` (Status: Done). Second sub-slice of
[[DL-BE-029]]. The first business slice over the WS-0 edge: 9/9 supplier tests green, full suite **153**.

**What shipped.** A `supplier` package (`SupplierService` + `SupplierController`) driving the linear
`sup_account` machine `created → identity_verified → kyc_submitted → kyc_approved → credit_reviewed →
maa_signed → active` over HTTP, all admin-on-behalf, each command through `CommandGateway` (idempotency #4,
MFA-fresh #2, per-command SoD role #3, audit #5). Each transition is a status-guarded optimistic UPDATE
(`WHERE status=<prior> AND aggregate_version=?`) — that guard IS the SA8.2 gate. A thin `compliance`
package (`ComplianceService` over `comp_kyc_file`) is the M15 swap-point. Decisions as planned: outcomes
recorded (no inline M5a/M5c), one slice, compliance seam. No new migration.

**Notable discovery — KYC is DB-enforced maker-checker.** The inline `comp_kyc_file` DDL hid ALTER-added
columns/constraints (`submitted_by`, `approver_mfa_assertion_id`; `comp_kyc_file_maker_ne_checker` +
`comp_kyc_file_approver_mfa_chk`): KYC approval requires submitter (ops, maker) ≠ approver (compliance,
checker) + the checker's MFA. So WS-1 already exercises a real maker-checker control. (Recurring lesson:
trust the migration, not the inline `CREATE TABLE` — the truth is the ALTERs too. [[db-declarative-no-triggers]].)
`uidx_sup_account_gstin`/`_cin` (UNIQUE) were likewise only in separate `CREATE UNIQUE INDEX` statements.

**`/code-review` (6 findings, all fixed; 3 regression tests added).**
1. *(high)* `grant-agency-consent` built a Postgres array literal by string-joining client scope values —
   a comma/brace corrupts the stored consent scope (or 500). Fixed: typed `String[]` via `createArrayOf`
   + per-element validation. Test: a comma-bearing scope value stored as one element.
2. *(high, CLAUDE.md money rule)* `paise()` read `exposure_cap_paise` as `Number.longValue()`, silently
   truncating a JSON float on a regulated credit-cap field. Fixed: reject non-integral. Test added.
3. *(med)* maker=checker on KYC surfaced as a 500 (only the DB CHECK stopped it). Fixed: app-layer guard
   in `ComplianceService.approveKyc` (checker ≠ submitter) → clean **409 `checker_equals_maker`**, with the
   DB CHECK as backstop (app rule AND DB constraint both fire). New `CommandRejectedException.checkerEqualsMaker`.
   Test: a dual-role admin's self-approval → 409, not 500.
4. *(med)* create idempotency keyed the derived `supplier_id` on only `(command_id, legal_name, pan)` —
   a divergent gstin/cin under the same `command_id` silently replayed. Fixed: derive from the full body.
5. *(med)* `submit-financial-profile` re-submit hit the one-per-supplier UNIQUE → 500. Fixed: clean 400 guard.
6. *(med)* `grant-agency-consent` could insert multiple active consents. Fixed: idempotent single-active-consent.

**Deferred (noted, not bugs).** The **HTTP login/seed test helper** is now duplicated across
`WalkingSkeletonEdgeTest` + `SupplierOnboardingTest`, and `deriveAggregateId`/`str`/GET-404 across two
controllers — extract a shared test base + edge helpers at **WS-2** (rule of three). Child-row commands
(grant-consent, financial-profile) accept `X-Aggregate-Version` but are **version-advisory** (additive
inserts, no optimistic check) — deliberate. `X-Agency-Consent-Id` enforcement + AC.1 *rejection* and the
separate `compliance.KycFile.Approved` envelope remain deferred (the supplier command envelope carries the
fact for WS-1).

### DL-BE-031 — original reservation (planned, pre-build)
Second sub-slice of [[DL-BE-029]]; **fill in the as-built state machine + decisions at WS-1 DoD.**
**Planned scope:** the first business slice through the WS-0 edge — one supplier, `created → active`, all
commands admin-on-behalf (no supplier login, DL-012), each routed through `CommandGateway` (idempotency #4,
MFA-fresh #2, per-command SoD role #3, audit #5). A new `supplier` package (`SupplierService` +
`SupplierController`) over `sup_account`/`sup_agency_consent`/`sup_financial_profile`, plus a thin
`compliance` package (`ComplianceService` over `comp_kyc_file`) as the M15 swap-point. No new migration.
**DoR decisions (settled at the gate):** (A) **record outcomes** — the M5a verify / M5c sign round-trips are
proven in their own tests; WS-1 records the verified-identity (pan/gstin/cin) and signed-MAA
(`maa_agreement_id`) outcomes on the aggregate, deferring inline ACL calls + event-bus consumption to
Milestone 2. (B) **one slice** — nine uniform state-machine commands, test-first, one E2E supplier test +
per-transition + gate-reject asserts. (C) **compliance seam** — `ComplianceService.submitKyc/approveKyc` is
the one-place swap-point for the real BC11 engine.
**Invariants:** SA8.2 activation gate (kyc_approved ∧ credit cap ∧ MAA id — enforced by the linear
status-guarded UPDATEs); agency consent *established* in the happy path (AC.1 *rejection* deferred); MAA is
the supplier's own signatory (AC.2, recorded); exposure < ₹10 Cr keeps the BC3 four-eyes (C6) out;
creating-command `supplier_id` derived from `(command_id, payload)` for stable replay (the WS-0 pattern).
**Watch for (at build):** the `comp_kyc_file` CHECK requires `approver_id` (FK → admin_user, must be a
compliance_reviewer) + `decided_at` when status is approved; `sup_financial_profile` has a one-per-supplier
UNIQUE; the credit-review snapshot column is `credit_exposure_cap_paise` (paise) on `sup_account` — the real
four-eyes lives in BC3 `risk_supplier_profile` (deferred); `X-Agency-Consent-Id` is advisory in WS-1 (the
gateway does not yet stamp/enforce it — deferred with AC.1 rejection).

---

## DL-BE-032 — WS-2 Buyer + ack user (BC9)
**Date:** 2026-06-23
**Status:** Built. Spec `docs/modules/WS-2-buyer-active.md` (Status: Done). Third sub-slice of
[[DL-BE-029]]. Mirrors WS-1 ([[DL-BE-031]]); 8/8 buyer tests green, full suite **161**.

**What shipped.** A `buyer` package (`BuyerService` + `BuyerController`) driving the linear `buyer_account`
machine `nominated → identity_verified → credit_assessed → engagement_started → active` over HTTP, all
admin-on-behalf through `CommandGateway`. The acknowledgment user is a login principal —
`designate-ack-user` provisions an `acknowledgment_user` identity (OTP-only: no password, no MFA — AU.1/
DL-021) + a `buyer_ack_user` row. **BA.3 gate** on `activate` is part state-machine (status guard), part
app-check (≥1 active ack user ∧ a current payment rule). No new migration.

**Shared-helper extraction (committed at WS-1, rule of three).** `RequestBodies`
(requiredString/Strings/Paise/PositivePaise, deriveAggregateId) now shared by the admin/supplier/buyer
controllers; `AbstractEdgeHttpTest` (seedAdminWithRoles + HTTP bearerFor) shared by the WS-0/WS-1/WS-2
tests. Migrated the existing controllers + tests; suite stayed green throughout.

**`/code-review` (4 findings, all fixed; 2 regression tests added).**
1. *(high)* `designate-ack-user` used `AuthService.provisionIdentity`, which **500s on a duplicate email**
   and emits a **second audit envelope leaking the ack user's email (PII)** — defeating the buyer
   envelope's PII-avoidance. Fixed: a direct `auth_identity` insert (mirroring `AdminUserService`) with a
   `DuplicateKeyException`→400 catch → one envelope, no PII in the log, clean dup handling. Test added.
2. *(med)* `RequestBodies.requiredPaise` didn't enforce `> 0`, so a zero/negative `credit_limit_paise`
   (`positive_money_paise`) 500'd on the DB CHECK. Fixed: `requiredPositivePaise` rejects ≤ 0 at the edge.
   Test added.
3. *(med)* `activate` ran the BA.3 check before the state guard → a wrong-state buyer got a misleading
   "ack user required". Fixed: check state first, then BA.3, then the version-guarded transition.

**Deferred (noted, not bugs).** Buyer **suspension is DB maker-checker** (ALTER-added
`suspend_maker_id`/`suspend_checker_id`/`suspend_checker_mfa_assertion_id` — only bites when `suspended_at`
is set, so the active path is clear) — Milestone 2. The ack user's OTP **login flow** (WS-2 only provisions
the identity). A `nominate` with a duplicate gstin still 500s on the UNIQUE — consistent with WS-1's
supplier create; a uniform "translate UNIQUE → 409" pass is a future cleanup across both.

### DL-BE-032 — original reservation (planned, pre-build)
Third sub-slice of [[DL-BE-029]]; **fill in the as-built state machine + decisions at WS-2 DoD.**
**Planned scope:** the second counterparty — one buyer `nominated → active` over HTTP, admin-on-behalf,
each command through `CommandGateway`. A new `buyer` package (`BuyerService` + `BuyerController`) over
`buyer_account`/`buyer_ack_user`/`buyer_payment_rule`. Mirrors WS-1 ([[DL-BE-031]]); decisions inherited
(record outcomes, one slice, full-body create id). No new migration.
**New this slice:** the acknowledgment user is a login principal — `designate-ack-user` provisions an
`acknowledgment_user` `auth_identity` (OTP-only: no password, no MFA factor — AU.1/DL-021) + a
`buyer_ack_user` row. **BA.3 activation gate** is part status-machine, part app-check: `activate` requires
`engagement_started` ∧ ≥1 active ack user ∧ a current payment rule.
**Also lands here (committed at WS-1):** the **shared-helper extraction** (rule of three) — `RequestBodies`
(requiredString/Strings/Paise, deriveAggregateId) now shared by the admin/supplier/buyer controllers, and
`AbstractEdgeHttpTest` (seedAdminWithRoles + HTTP bearerFor) shared by the WS-0/WS-1/WS-2 tests. Migrated
the existing controllers + tests; full suite stayed green (153).
**Watch for (at build):** `buyer_account` has UNIQUE on gstin + mca_cin (vary per buyer in tests, as WS-1);
buyer **suspension is DB-enforced maker-checker** (ALTER-added `suspend_maker_id`/`suspend_checker_id`/
`suspend_checker_mfa_assertion_id` + CHECKs — deferred, only bites when `suspended_at` is set, so the
active path is clear); `buyer_payment_rule` has a partial UNIQUE on `(buyer_id) WHERE superseded_by IS NULL`
(one current rule — guard re-confirm like WS-1's financial profile); ack-user identity kind is
`acknowledgment_user` (identity_kind_enum); `buyer_ack_user` UNIQUE(identity_id).

---

## DL-BE-033 — WS-3 Investor active (BC7, = M10 min)
**Date:** 2026-06-23
**Status:** Built. Spec `docs/modules/WS-3-investor-active.md` (Status: Done). Fourth sub-slice of
[[DL-BE-029]]. Mirrors WS-1/2; 8/8 investor tests green, full suite **169**.

**What shipped.** An `investor` package (`InvestorService` + `InvestorController`) driving the linear
`inv_account` machine `signed_up → identity_verified → kyc_submitted → suitability_assessed →
financial_profile_completed → kyc_approved → mia_signed → active` over HTTP, **invite-gated** and
admin-on-behalf. Compliance issues the invite (`/investor-invites/issue`, stores SHA-256 of email/phone
only); an ops `sign-up` consumes it (pending + unexpired + hash-match — C20/DL-008) and provisions the
investor identity (direct `auth_identity` insert, kind `investor`; one envelope, `DuplicateKey`→400) +
`inv_account`. KYC reuses `ComplianceService` (maker-checker, subject_type=investor). `activate` sets
`kyc_refresh_due_at = activated_at + 12 months` (both via `now()` in one UPDATE — stable in-statement,
C17). PII (pan/aadhaar/bank) lands on columns, never in an audit payload. No new migration.

**`/code-review` (3 findings; fixed + a platform-wide hardening).** The two real findings were a
**platform-wide input-validation gap**: identity fields cast to DB domains (`pan_type`, `aadhaar_last4_type`,
`CHAR(4)`) with no edge format-check → an operator typo 500'd on the DB CHECK instead of a clean 400. Fixed
**once** in the shared `RequestBodies` (`requiredPan` / `requiredGstin` / `requiredFourDigits`, regexes
matching the V1 domains) and applied across **all three** onboarding controllers (investor pan/aadhaar/bank;
supplier pan/gstin; buyer gstin). Third (low): the invite-consume UPDATE ignored its rowcount — added an
`updated == 1` defence-in-depth check. 2 regression tests (bad bank-last4, bad pan → 400).

**Deferred (noted).** The investor self-service portal + login flow (M10-full); suitability mismatch +
override-ack (skeleton uses `mismatch=false`); penny-drop realism; KYC-refresh scheduler; suspension/exit
(DB maker-checker, ALTER-added). `mca_cin`/`cin` are `CHAR(21)` (no domain CHECK) — a >21-char value still
pads/errors at the DB; a length guard is a minor future add.

### DL-BE-033 — original reservation (planned, pre-build)
Fourth sub-slice of [[DL-BE-029]] (= M10 min); **fill in the as-built state machine + decisions at WS-3 DoD.**
**Planned scope:** the third onboarding flow — one investor `signed_up → active` over HTTP, invite-gated.
A new `investor` package (`InvestorService` + `InvestorController`) over `inv_invite`/`inv_account`/
`inv_suitability`, reusing `ComplianceService` (comp_kyc_file, subject_type='investor'). Mirrors WS-1/2
([[DL-BE-031]]/[[DL-BE-032]]); decisions inherited (record outcomes, one slice, full-body create id). No
new migration.
**DoR decision (settled at gate):** **admin-driven signup** — an ops_executive command consumes the invite
+ provisions the investor identity + account (fully admin-on-behalf); the C20 invite-gate is still enforced
(pending + unexpired + email/phone SHA-256 hash-match). The investor self-service portal + login flow is
deferred to M10-full.
**New this slice:** the **invite-gate** (`investor-invites/issue` by compliance → consume at sign-up,
C20/DL-008); the `inv_account` activation CHECK `kyc_refresh_due_at = activated_at + 12 months` (both set
via `now()` in one UPDATE — stable in-statement, C17); the investor identity is a direct `auth_identity`
insert (kind `investor`) — one envelope, no PII (pan/aadhaar/bank) in any audit payload.
**Watch for (at build):** linear status order is `signed_up → identity_verified → kyc_submitted →
suitability_assessed → financial_profile_completed → kyc_approved → mia_signed → active` (DB-enum truth —
KYC *approval* comes AFTER suitability + financial profile, not adjacent to submission); `inv_account` has
UNIQUE(identity_id) + UNIQUE(invite_id) (one account per invite); `auth_identity.email` is UNIQUE
(DuplicateKey→400 like the WS-2 ack user); sub_type CHECK locks `resident_individual`/`huf`;
`inv_suitability.mismatch=false` keeps the override path out; suspension/exit are DB maker-checker
(ALTER-added, deferred).

---

## DL-BE-034 — WS-4 Listing priced + gone-live (BC1/3/4, = M9 min)
**Date:** 2026-06-24
**Status:** Built. Spec `docs/modules/WS-4-listing-golive.md` (Status: Done). The **inflection slice** —
first money math + first two-endpoint maker-checker+MFA gate. 10/10 listing tests green, full suite **179**.

**What shipped.** A `listing` package (`ListingService` + `ListingController` + `FundingMath`) driving
invoice → ops-checks → pricing snapshot → go-live → VA over HTTP. Go-live is a two-endpoint maker-checker
(B4 §6.1): `snapshot-and-ready` (ops, maker — freezes the snapshot + `funding_target`, stamps
`golive_maker_id`) then `approve-go-live` (treasury, checker — checker≠maker + MFA, creates the VA inline).

**Rounding worked example (HALF_EVEN per line item, asserted to the paise).** face=100000000 (₹10L),
rate=1200bps, tenor=60d, fee=200bps → discount = round(1972602.74) = **1972603**; fee = **2000000**;
**funding_target = 96027397**. `FundingMath` keeps the arithmetic in `BigDecimal` and narrows to `long`
only after the `>0` guard (a valid target is in `(0, face_value]`, so it never overflows).

**Resolved the open maker-checker question — no M4d extraction.** `deal_listing` carries its own
`golive_maker_id`/`golive_checker_id`/`golive_mfa_assertion_id` columns + DB CHECKs (`maker_ne_checker`,
`checker ⟹ maker+MFA`), exactly like `comp_kyc_file`. App-guard checker≠maker → clean 409
`checker_equals_maker`, DB CHECK as backstop.

**`/code-review` (3 findings, all fixed; 2 regression tests).** All were 500-on-edge-input (the reviewer
cleared the structural risks — maker-checker atomicity, snapshot immutability, no orphan VA, no floats):
1. *(high)* `tenor_days`/`rate_bps` read via the **money** helper `requiredPositivePaise` then `(int)` cast
   → a large JSON number silently wrapped to an in-range value, bypassing the band/tenor guards. Fixed:
   new `RequestBodies.requiredPositiveInt` (range-checked). Test added.
2. *(med)* two concurrent `approve-go-live` could both insert the VA → UNIQUE → 500 on the loser. Fixed by
   **reordering**: the version-guarded listing UPDATE runs first (loser → clean 409 before any VA insert;
   `va_id` has no FK so it can name the VA inserted next).
3. *(low)* `longValueExact` on a line item → `ArithmeticException` (500) for an absurd-but-BIGINT-legal
   face×rate. Fixed by the `BigDecimal`-to-the-end refactor. Test added.

**Deferred (noted).** Real per-invoice OTP acknowledgment; the BC3 pricing-band *command* + BC8/9 caps
(seeded in tests; the cross-context reads are a documented shortcut → BC query ports + event bus at M2 when
ArchUnit is wired); business-day funding-window (L.8 — uses 5 calendar days); the funding-window-expiry
scheduler; L.11 counterparty-active re-check; ops-check failure / rejected_operational.

### DL-BE-034 — original reservation (planned, pre-build)
Fifth sub-slice of [[DL-BE-029]]; the **inflection slice** — first money math + first maker-checker+MFA gate.
**Planned scope:** invoice → ops checks → pricing snapshot (freeze `funding_target`) → go-live
(maker-checker + MFA, two endpoints) → VA. A new `listing` package (`ListingService` + `ListingController`
+ `FundingMath`) over `deal_invoice`/`deal_listing`, creating `cash_virtual_account` inline at go-live. No
new migration.
**DoR decisions (settled at gate):**
1. **`funding_target` rounding = HALF_EVEN per line item** (banker's, unbiased): `funding_target =
   face_value − round(face_value·rate_bps/10000·tenor_days/365) − round(face_value·fee_bps/10000)`, each
   round HALF_EVEN to integer paise via `BigDecimal`; guard `> 0` at the edge; frozen at snapshot (L.7/
   DL-024/G20). The user's call (compliance can re-pin later — documented + reversible).
2. **Maker-checker = column-based on `deal_listing`** (`golive_maker_id`/`golive_checker_id`/
   `golive_mfa_assertion_id` + DB CHECKs `maker_ne_checker`, `checker ⟹ maker+MFA`) — **resolves the open
   "M4d extraction may precede WS-4" question: NO extraction needed.** The listing carries its own
   purpose-built maker-checker columns, exactly like `comp_kyc_file` (WS-1/3); app-guard checker≠maker →
   clean 409 `checker_equals_maker`, DB CHECK as backstop. M4d's envelope-stream `MakerCheckerGate` is the
   alternative for aggregates *without* dedicated columns. (New `CommandRejectedException.checkerEqualsMaker`
   already exists from WS-1.) Maker = actor at `snapshot-and-ready`; checker ∈ treasury_and_settlement at
   `approve-go-live`.
3. **Inline VA** at go-live (real BC4 subscribes to `Listing.GoneLive` — no bus yet). One VA per listing
   (DB UNIQUE), `expected_inflow_total = funding_target`, `deal_listing.va_id` set once.
4. **OTP per-invoice acknowledgment deferred** (needs the buyer ack-user login flow, itself deferred WS-2);
   skeleton enters/exits `awaiting_acknowledgment` via ops; transient `operational_checks_in_progress`
   collapsed.
5. **Cross-context reads** (buyer credit limit, supplier exposure cap, active pricing band) are
   **documented pragmatic direct reads** in the listing service for the skeleton — replaced by BC3/8/9
   query ports + event bus at Milestone 2 (when ArchUnit is wired). Pricing band + counterparty caps are
   **seeded** in WS-4 tests (upstream context state, like seeding admins).
**Watch for (at build):** `face_value`/`funding_target` overflow — use `BigDecimal` for the products
(face_value up to ~10^9 paise × bps × tenor); `funding_window_close_at = now + 5 calendar days` (L.8
business-day calc deferred); tenor_bucket from tenor_days (≤30 lte_30d / 31-60 / 61-90 / 91-180);
`risk_pricing_policy` active band = `superseded_by IS NULL` partial UNIQUE; `cash_virtual_account.va_id`
has NO FK from `deal_listing` (soft ref) — insert VA (FKs to listing) then set `listing.va_id`; keep
face_value < ₹1 Cr in tests to keep four-eyes (C6) out.

---

## DL-BE-035 — WS-5 Subscribe to 100% → fully-funded (BC2/1/18/4, = M11 min)
**Date:** 2026-06-24
**Status:** Built. Spec `docs/modules/WS-5-subscribe-fully-funded.md` (Status: Done). The **funding-equality
(G10)** slice + the platform's first **inbound webhook**. 8/8 tests green, full suite **187**.

**What shipped.** `subscription` (BC2: `SubscriptionService.commit` via gateway + `confirmFromInflow`;
`SubscriptionController`) · `settlement` (BC4: `SettlementService.recordReconciledInflow`) · a banking
webhook ingress (`BankingWebhookController` + `HmacVerifier`, B4 §5). The G10 chain runs end-to-end over
HTTP: commit (ops on-behalf) bumps `committed_total` atomically and flips `live → fully_funded` at exact
equality (L.6); a HMAC-signed inflow → `EscrowAclService` (dedup) → settlement reconcile (VA `observed`
+= amount) → subscription `confirmed` (S.3). **G10 asserted to the paise:** `Σ confirmed = committed_total
= observed_inflow_total = funding_target` (= 50000000 in the test). HMAC over `(timestamp‖body)` against a
config secret (`platform.webhook.banking.secret`), 5-min replay, constant-time compare; invalid → 401 +
`WebhookSignature.Invalid`; re-delivery (same `vendor_event_id`/`utr`) → 200, counted once. No new migration.

**The coordinated commit (INV-3).** Over-subscription is impossible by construction: the cap predicate
`committed_total + amount ≤ funding_target` lives **inside** the bump UPDATE (rowcount 0 → 422-class reject),
and the `fully_funded` flip is folded into the **same statement** via
`status = CASE WHEN committed_total + ? = funding_target THEN 'fully_funded' ELSE status END … RETURNING
status` — so the flip is driven off the true post-bump value, never a racy before-image.

**`/code-review` (3 findings, all fixed; 2 regression tests).** The review's highest-value catch:
1. *(high, must-fix)* the `fully_funded` flip originally used the **stale before-image** `committed_total`
   (read before the bump), so under concurrent commits the flip could be **missed** (listing stuck `live`
   at full funding). Fixed by the atomic single-statement bump+CASE+RETURNING above. Regression: a 2-investor
   partial-fill (30M + 20M → fully_funded).
2. *(med, money)* the webhook parsed `amount_paise` via `(Number).longValue()` → a JSON float was silently
   truncated. Fixed: reject non-integral at parse. Test added.
3. *(low)* `SettlementService` flipped the observation to `reconciled` before the unknown-VA guard → a
   reconciled-but-unfunded orphan. Fixed by resolving the listing first.

**Deferred (noted).** Investor self-service commit + login (M11-full); multi-investor allocation / partial
fills beyond the sequential case / concentration warnings; funding shortfall + refund; pre-confirmation
cancellation; the EoD master-statement reconciliation overlay (provisional→reconciled collapsed); the
funding-window-expiry scheduler (L.9); full G24 correlation re-establishment (uses `va_id`→listing). The
non-deterministic dedup-correlation SELECT in M5b's `processInflowWebhook` is latent/audit-only (the two
UNIQUEs make a true partial-collision impossible) — left as-is, out of WS-5 scope.

### DL-BE-035 — original reservation (planned, pre-build)
Sixth sub-slice of [[DL-BE-029]]; the **funding-equality (G10)** slice + the platform's first **inbound
webhook**. **Planned scope:** subscription commit → fully-funded → HMAC inflow webhook → confirmed.
**Planned scope:** subscription commit → fully-funded → HMAC inflow webhook → confirmed. New `subscription`
(BC2) + `settlement` (BC4) packages + a banking webhook ingress (`BankingWebhookController` + `HmacVerifier`).
Reuses M5b `EscrowAclService.processInflowWebhook` (dedup/audit). No new migration.
**DoR decisions (settled at gate):** one slice (G10 end-state needs commit+inflow+confirm together); commit
actor = `ops_executive` on-behalf (investor login → M11-full; commit is unilateral, no maker-checker);
over-subscription blocked at commit (S.5 app-guard `committed_total+amount ≤ funding_target` → 422, DB CHECK
`committed_lte_target` backstop); **FullyFunded fires at commit** (`committed_total == funding_target`, L.6 —
"fully funded" = fully *subscribed*); **HMAC** over `(timestamp‖body)` against a per-vendor config secret
(`platform.webhook.banking.secret`), 5-min replay, invalid → 401 + `WebhookSignature.Invalid`; provisional→
reconciled collapsed (EoD overlay deferred), inflow auto-confirms; cross-BC coordination via inline calls
(webhook → EscrowAcl → settlement → subscription) pending the event bus.
**G10 (the headline):** `Σ confirmed sub.amount = deal_listing.committed_total =
cash_virtual_account.observed_inflow_total = funding_target`, paise-exact.
**Watch for (at build):** the **coordinated commit** must bump `committed_total` in the same tx as the
subscription insert (over-subscription impossible by construction, not check-then-act); the webhook handler
must verify HMAC **before** any parse/DB-read (C10) and emit `WebhookSignature.Invalid` on failure; the
webhook body must be hashed as the **exact received bytes** (controller takes the raw String, parses after
verify) so the test can sign the identical payload; `gate_inflow_observation` inserts as `'provisional'` then
WS-5 reconciles to `'reconciled'`; FullyFunded is a 2nd envelope from the commit command (gateway emits
`Committed`, the handler appends `Listing.FullyFunded` like `approveDisableAdmin`); webhook returns **200
even on duplicate** (B4 §5.2, stop re-delivery); single-investor inflow↔subscription match only.

---

## DL-BE-036 — WS-6 Assignment single-leg signed → all_signed (BC5, = M12 min)
**Date:** 2026-06-24
**Status:** Built. Spec `docs/modules/WS-6-assignment-all-signed.md` (Status: Done). The **C27 disbursement
gate** (single-investor cut). 4/4 tests green, full suite **191**.

**What shipped.** An `assignment` package (`AssignmentService` + `AssignmentController`): `request` opens the
`legal_assignment_set` (one per listing, AS.1; `total_count=1`) + the investor's MIA (`legal_master_agreement`,
`initiated`) + `legal_signature_request`, and initiates the signature via the M5c `SignatureAclService`
(inline — no signing webhook); `complete-signing` drives `completeSignature` (cert), marks the request
`completed` + the MIA `signed` (cert) + the leg `signed`, and at `signed_count == total_count` flips the set
to `all_signed` and **`deal_listing.all_signed = TRUE`** — the C27 gate WS-7 reads. The leg is a JSONB value
object carrying the `vsr_id` to complete. No new migration.

**`/code-review` (3 findings, all fixed; 1 regression assertion).**
1. *(high)* `complete-signing` derived a **synthetic** command aggregate_id (`assignment-set-complete:…`) ≠
   the real `legal_assignment_set` id, so the `AssignmentSet.AllSigned` envelope was chained to a
   non-existent aggregate — the gate-opening event orphaned from its aggregate. Fixed: the controller
   resolves the real set id from the listing and targets it. Regression: assert the AllSigned envelope's
   `aggregate_id` equals the real set id.
2. *(med)* the C27 `deal_listing.all_signed` flip was guarded `WHERE status='fully_funded'` with the rowcount
   ignored → a 0-row flip would leave the set `all_signed` but the gate flag false (silent, and WS-7 would be
   silently blocked). Fixed: assert `flipped == 1`, else throw (clean rollback).
3. *(low, defensive)* `legs.get(0)` hard-coded the single-leg invariant — added a guard
   (`legs.size()==1 && total_count==1`) so a future multi-leg widening can't silently mis-gate C27.

**Deferred (noted).** Multi-leg assignment (multi-investor); the 24h `sign_deadline` *incomplete* path +
scheduler (AS.4/G13); the signing *webhook* (M12-full — the inbound mechanism is already proven by WS-5);
per-invoice stamping (AS.7/G2); signing retry/failure paths.

### DL-BE-036 — original reservation (planned, pre-build)
Seventh sub-slice of [[DL-BE-029]]; the **C27 disbursement gate** (the single-investor cut).
**Planned scope:** on `fully_funded`, request the assignment set (total_count=1), initiate the investor's
MIA signature via the M5c `SignatureAclService` (inline), complete it, and flip `deal_listing.all_signed =
TRUE` (C27/L.5 — the gate WS-7's disbursement requires). A new `assignment` package (`AssignmentService` +
`AssignmentController`) over `legal_assignment_set`/`legal_master_agreement`/`legal_signature_request`,
reusing M5c. No new migration.
**DoR decisions (settled at gate):** **inline completion** — a `complete-signing` ops step calls
`completeSignature` (the M5c stub completes in-process); the signing *webhook* is deferred to M12-full
(WS-5 already proved the inbound-webhook mechanism, so a signing webhook repeats it with a different
vendor). **Single leg** (`total_count = 1`); multi-leg + the 24h `sign_deadline` *incomplete* path (AS.4,
G13) deferred. **Admin-on-behalf** — investor signs (`legal_signer_type='investor'`) but ops initiates +
completes; request is an explicit ops command (real BC5 subscribes to `Listing.FullyFunded`).
**Watch for (at build):** `legal_assignment_set` UNIQUE(listing_id) (AS.1) + counts CHECK
(`signed+unsigned=total`, AS.5) — request sets unsigned=total=1, completion moves one to signed=1; the MIA
`status_shape_chk` requires `signature_cert_serial` NOT NULL when status leaves `initiated`/`failed` (set it
with the `signed` transition); `legal_signature_request_cert_only_on_completed_chk` (cert only on
`completed`); the M5c `initiateSignature(vsrId, signatureRequestId, docHash bytes, signerRef, SignMethod)` →
`completeSignature(vsrId)` → `certSerial` (store `vsr_id` in the leg JSONB to complete later); `SignMethod`
= AADHAAR_OTP/DSC; the `all_signed` flip is on `deal_listing` (boolean), listing stays `fully_funded`.

---

## DL-BE-037 — WS-7 Disbursement → disbursed (BC4, = M13 min)
**Date:** 2026-06-24
**Status:** Built. Spec `docs/modules/WS-7-disbursement.md` (Status: Done). **The finale** — the second
maker-checker+MFA gate (the payout) → `listed → disbursed`. **Milestone 1 (Walking Skeleton) complete.**
6/6 disbursement tests + the capstone green, full suite **198**.

**What shipped.** A `DisbursementService` + `DisbursementController` (settlement, BC4): `draft` (Treasury
maker, PI.2 gate `fully_funded ∧ all_signed`) records the `cash_payout_instruction` (PK = the bank
`client_instruction_id`, PI.1; gross=net=funding_target, fee=0); `approve` (Treasury checker ≠ maker + fresh
MFA, PI.5 — column-based, twin of WS-4 go-live) instructs the M5b escrow payout (inline) → `executed` and
flips the listing `fully_funded → disbursed` (rowcount-asserted, the WS-6 lesson). `approve` targets the
**real** instruction id so its audit envelope chains to the aggregate. **Plus the milestone capstone**
`WalkingSkeletonE2ETest` — one invoice walks the money-flow spine **WS-4→WS-7 over HTTP**: create → go-live
(maker-checker #1) → subscribe → HMAC inflow → assign → sign → disburse (maker-checker #2) → `disbursed`,
with G10 still holding at the end.

**`/code-review` (2 findings — one root cause; fixed + a V6 migration + a regression test).** The
one-disbursement-per-listing guard was **check-then-insert with no DB UNIQUE**, so a concurrent double-draft
could create two payout rows (then the approve resolver would silently pick one, orphaning the other). The
WS-6 twin avoided this with a real UNIQUE; WS-7 had dropped that backstop. **Fix (Constitution-aligned —
declarative + app both fire):** new migration **V6** adds a partial UNIQUE index
`uidx_cash_payout_disbursement_per_listing ON cash_payout_instruction(listing_id) WHERE kind='disbursement'`,
and `draft` now catches `DuplicateKeyException` → clean 400 (the app guard stays as the fast path). The
reviewer cleared everything else — the concurrent-**approve** race (guarded UPDATE + reject), double-flip
(rowcount assert), amounts (`funding_target > 0` domain), idempotency, and the audit aggregate-id.

> **First migration since M0.** WS-7 was planned as no-migration, but the review surfaced a genuine missing
> constraint; V6 is the right-depth fix. The canonical `docs/sql/` bundle should gain this index when the
> schema is revisited at Milestone 2 (the bundle is the documented stale-able source; the running schema is
> Flyway's).

**Deferred (noted).** The distribution leg (maturity → investor payout + TDS; WS-8/M13-full); the BC18
payout *webhook* + EoD reconciliation overlay (PI.7); partial/failed legs + remediation (PI.6); T+1 SLA
*enforcement* (PI.8 — date recorded); refund.

### DL-BE-037 — original reservation (planned, pre-build)
**The finale** — eighth sub-slice of [[DL-BE-029]]; the second maker-checker+MFA gate (the payout) →
`listed → disbursed`, completing Milestone 1.
**Planned scope:** disbursement draft (T&S maker, PI.2 gate: only when `fully_funded ∧ all_signed`) →
approve (checker ≠ maker + fresh MFA) → escrow payout via M5b `instructPayoutSingle` (inline) → flip listing
`fully_funded → disbursed`. A `DisbursementService` + `DisbursementController` (settlement package, BC4) over
`cash_payout_instruction`. No new migration. **Plus the milestone capstone** `WalkingSkeletonE2ETest` — the
money-flow spine WS-4→WS-7 over HTTP.
**DoR decisions (settled at gate):** column-based maker-checker (twin of WS-4 go-live — `cash_payout_
instruction.maker_id`/`checker_id`/`checker_mfa_assertion_id` + DB CHECKs `maker_ne_checker`/`checker_mfa`,
no M4d extraction); inline payout execution (BC18 payout webhook + EoD overlay PI.7 → M13-full); disbursement
amount = `funding_target` (gross=net=funding_target, fee=0, tds=null — TDS/fee at distribution); PI.1
`payout_instruction_id` = the bank `client_instruction_id`; one disbursement per listing (app guard, no DB
UNIQUE); `approve` targets the REAL instruction id (the WS-6 audit-aggregate lesson, applied proactively).
**Watch for (at build):** `cash_payout_instruction_kind_target_chk` (disbursement ⟹ listing_id NOT NULL,
subscription_id NULL); gross/net positive_money_paise (>0), fee money_paise (>=0); `instructPayoutSingle(
payout_instruction_id, payoutRef, amount, beneficiary)` → utr, idempotent on the instruction id; the listing
flip guarded `WHERE status='fully_funded'` with the rowcount asserted (the WS-6 lesson); PI.2 reads the C27
gate `deal_listing.all_signed`.

---

## DL-BE-038 — M9 Listing & Invoice (BC1) full rigor — **COMPLETE** (Milestone 2, module 1)
**Date:** 2026-06-24 (kickoff) · 2026-06-26 (complete)
**Status:** **Done.** All five sub-slices built to DoD ([[DL-BE-039]]..[[DL-BE-043]]); full suite **224**.
Spec `docs/modules/M9-listing-full.md` (Status: Done). Umbrella for the
first Milestone-2 module: widen BC1 from the WS-4 skeleton ([[DL-BE-034]]) — happy path
`draft → awaiting_acknowledgment → ready_for_review → live` + the go-live maker-checker — to the **complete
BC1 intake→go-live spec**: every state path, invariant, and reject/alternate branch, to DoD. Sub-slices
claim their own `DL-BE-039+` as built.

**Scope (the BC1 intake → go-live span + its alternate branches).** Adds over WS-4: the operational-check
sub-machine (`operational_checks_in_progress`, the 7 DL-027 checks in `check_outcomes`, IRN/e-way via the
BC17 ACL — INV.5/INV.7, → `rejected_operational` on fail), the buyer-acknowledgment branch
(`awaiting_acknowledgment` → received | `acknowledgment_failed`), pricing/snapshot **via new BC query
ports**, the L.8 **business-day** funding window, and the L.11 **synchronous** counterparty-active re-check
at go-live → `held_for_review`. Downstream listing states (`fully_funded`…`closed`) stay owned by
M11/M12/M13/M14.

**The four scope forks (DoR, user-decided 2026-06-24):**
1. **BC query ports + ArchUnit — IN.** `credit.PricingQueryPort` / `buyer.BuyerQueryPort` /
   `supplier.SupplierQueryPort` (read-only) replace the DL-BE-034 documented direct reads; ArchUnit forbids
   cross-BC internals access (ARCH.1). Sets the M2 architectural pattern. (Was "deferred until ArchUnit
   wired" in [[DL-BE-034]] — M9 is the consumer, so it wires it.)
2. **Buyer ack = admin-captured only (DL-019).** Full SLA + state branch, but Ops records on the buyer's
   behalf; ack-user OTP login deferred to the buyer-portal slice. Ack evidence in `check_outcomes`.
3. **Funding window = business days (L.8); scheduler deferred to M11.** New shared-kernel `BusinessDate`;
   the window-expiry scheduler + `DeclareFundingShortfall` (L.9 active side) is M11's.
4. **C6 four-eyes ≥ ₹1 Cr — DEFERRED** to the M4d four-eyes engine; go-live stays the single maker-checker.

**No new migration** (V1–V6 already carry every enum value + `check_outcomes` + the maker-checker columns).
**DB-enum-true wins** over the prose `acknowledged/priced` states — pricing+snapshot stay collapsed in
`snapshot-and-ready`, ack is a recorded precondition not a listing state.

**Sub-slices (build order):** A query-ports+ArchUnit ([[DL-BE-039]]) · B BusinessDate + business-day window
([[DL-BE-040]]) · C operational-check sub-machine ([[DL-BE-041]]) · D buyer ack admin-captured
([[DL-BE-042]]) · E go-live L.11 hold + E2E ([[DL-BE-043]]). Each: red invariant tests → implementer green →
`/code-review` → DoD → its DL.

**Remaining gaps after M9-full (documented):** C6 four-eyes (M4d); funding-window scheduler (M11); real
ack-user OTP (buyer portal); async HoldForReview on in-flight suspension + `CancelPreDisbursement`
(event-bus + M11/M13); event bus replacing synchronous port reads (Phase-2, G17/G27).

## DL-BE-039 — M9-A query ports + ArchUnit harness (ARCH.1)
**Date:** 2026-06-24
**Status:** Built. First sub-slice of [[DL-BE-038]]. Pure refactor — no behaviour change; `ListingGoLiveTest`
10/10 unchanged, full suite **200** (198 + 2 ArchUnit rules).

**What shipped.** The WS-4 "documented direct cross-context reads" (DL-BE-034) are gone: `ListingService`
now reads buyer/supplier/credit state only through read-only **query ports** in dedicated `.port`
sub-packages:
- `credit.port.PricingQueryPort` + `PricingBand` (impl `credit.PricingQueryService`) — active band lookup.
- `buyer.port.BuyerQueryPort` (impl `buyer.BuyerQueryService`) — `creditLimitPaise` + `isActive`.
- `supplier.port.SupplierQueryPort` (impl `supplier.SupplierQueryService`) — `exposureCapPaise` + `isActive`.
`isActive` on buyer/supplier is added now but first *used* at M9-E (L.11 go-live re-check).

**ArchUnit harness (`architecture.BoundedContextRulesTest`, ARCH.1).** Standing rule set, wired with the
first Milestone-2 module: (1) no `..listing..` class may depend on anything under
`..buyer..`/`..supplier..`/`..credit..` **except** their `..*.port..` sub-packages (the conjunction is a
single predicate on the *target* class — `resideInAnyPackage(BCs).and(resideOutsideOfPackages(ports))`);
(2) every `*QueryPort` under a `.port` package is an interface.

**Watch for.** ArchUnit sees only the *Java* boundary, not raw-SQL table reads — the "no cross-BC table
join" half of ARCH.1 is held by routing every foreign-table read through a `*QueryService` in the owning
BC's package (convention + code-review). The rule was vacuously green before the refactor (listing had no
Java dep on those BCs, only SQL strings); it has teeth now that the ports are imported. `PricingBand.covers`
folds the L.10 band check into the DTO.

## DL-BE-040 — M9-B BusinessDate kernel + business-day funding window (L.8)
**Date:** 2026-06-25
**Status:** Built. Second sub-slice of [[DL-BE-038]]. `BusinessDateTest` 6/6 + a listing integration
assertion; full suite **207**.

**What shipped.** A shared-kernel `BusinessDate` (`shared.BusinessDate`, `@Component`): pure date
arithmetic — `plusBusinessDays(start, n)` advances strictly past `start`, skipping weekends and
`HolidayCalendar` holidays. Backed by `ConfiguredHolidayCalendar` (property `platform.calendar.holidays`,
empty → weekend-only) and a `CalendarConfig` `Clock platformClock()` bean in **Asia/Kolkata** (Indian
banking time; injected so date-handlers are test-deterministic). `ListingService.approveGoLive` now sets
`funding_window_close_at = end-of-business (23:59:59.999999 IST) on the 5th business day` after
`LocalDate.now(clock)` — replacing the WS-4 `now() + interval '5 days'` calendar shortcut (L.8 now exact).
The kernel will also serve Settlement (C11 T+1) and Collections.

**Watch for — pgjdbc nanosecond rounding (real bug, caught in build).** `LocalTime.MAX` is
`23:59:59.999999999`; binding that as a `TIMESTAMPTZ` via `OffsetDateTime` makes pgjdbc 42.7.x round the
nanoseconds **up to the next microsecond → +1 second → midnight on the next calendar day**, silently
shifting the window one day. Fix: `.truncatedTo(ChronoUnit.MICROS)` before binding. Any
`LocalTime.MAX`/nanosecond timestamp written to Postgres must be truncated to micros.

**Deferred (noted).** Real RBI/exchange holiday feed (replaces `ConfiguredHolidayCalendar`, varies by
year/centre — currently a documented gap); the funding-window-**expiry scheduler** + `DeclareFundingShortfall`
(L.9 active side) → M11.

## DL-BE-041 — M9-C operational-check sub-machine (DL-027, INV.4/5/7)
**Date:** 2026-06-25
**Status:** Built. Third sub-slice of [[DL-BE-038]]. `ListingOpsChecksTest` 7/7; full suite **214**.

**What shipped.** The real DL-027 operational-check flow replaces the WS-4 collapsed `pass-ops-checks`.
Invoice `submitted → ops_checks_in_progress → {ops_checks_passed → listed | ops_checks_failed}`; listing
`draft → operational_checks_in_progress → {awaiting_acknowledgment | rejected_operational}`. Three commands
(all `gateway.execute(OPS)`): `start-ops-checks` (version-guarded listing + status-guarded invoice),
`record-ops-check`, `complete-ops-checks`. A new `OperationalCheck` enum is the single source of the 7
checks. `POST /listings` now accepts an optional 64-char `irn` (manual-fallback when null; INV.1).

**Check taxonomy (DL-027).** `irn_validity` is **VENDOR**-backed: when an IRN is present the platform calls
the BC17 `VerificationPort.verifyIrn` and the ACL result wins — the caller's `outcome` is **ignored**
(INV.7, no self-attestation); no IRN → `not_applicable`. The other 6 (`eway_bill_match`,
`buyer_supplier_relationship`, `duplicate_check`, `supplier_exposure_cap`, `buyer_limit_headroom`,
`document_completeness`) are **OPS-attested** (`passed`/`failed` in the body). `complete-ops-checks` passes
iff every check ∈ {passed, not_applicable}; a missing check → new
`CommandRejectedException.operationalChecksIncomplete` (**422**, no state change — first 422 reject in the
codebase, matching B4 §4.2's invariant class); any failed check → `rejected_operational`.

**Design decisions.** (1) `record-ops-check` is a **non-transition** command (BuyerService precedent): it
writes `deal_invoice.check_outcomes` via an **atomic JSONB merge** (`check_outcomes || ?::jsonb`, no
read-modify-write → no lost update under concurrent recordings) and does **not** bump the listing version.
(2) Pass path does invoice `→ ops_checks_passed → listed` as **two** guarded UPDATEs (honours INV.4's
no-skip). (3) Ack is NOT one of these 7 — it is the separate `awaiting_acknowledgment` branch (M9-D).

**Deferred (DL-BE-041, noted in `OperationalCheck`):** wire the OPS-attested numeric/vendor checks to real
sources — e-way → BC17 `VERIFY_EWAY_BILL`; cap/headroom → BC8/BC9/BC3 query ports; duplicate → in-DB query.

**Process note.** The mechanical implementer dropped its connection twice mid-task; the enum + exception +
service commands landed, but the `ListingController` (new endpoints, removal of `pass-ops-checks`, threading
`irn`) and the `WalkingSkeletonE2ETest`/`ListingGoLiveTest` migration to the new flow were finished by hand.

## DL-BE-042 — M9-D buyer acknowledgment (admin-captured, DL-019)
**Date:** 2026-06-25
**Status:** Built. Fourth sub-slice of [[DL-BE-038]]. `ListingAcknowledgmentTest` 7/7; full suite **221**.

**What shipped.** The buyer-acknowledgment branch off `awaiting_acknowledgment`. Two ops commands:
`request-buyer-ack` (looks up the buyer's active ack user, sends a BC15 notification to its identity, stamps
`check_outcomes.buyer_ack = {status:requested, sla_hours, requested_at}`) and `record-buyer-ack`
(admin-captured per DL-019): `acknowledged` stamps `buyer_ack` and stays `awaiting_acknowledgment`
(non-transition); `failed` transitions the listing to terminal-M9 `acknowledgment_failed`.
**`snapshot-and-ready` now requires `check_outcomes.buyer_ack.status == 'acknowledged'`** (new precondition
before the pricing snapshot) — the existing happy-path test helpers fold an admin ack in before snapshot.

**Decisions.** (1) **Admin-captured only** (DoR fork 2): Ops records on the buyer's behalf; the real ack-user
OTP login stays deferred to the buyer-portal slice. (2) **Ack evidence in `check_outcomes`** (no migration —
no ack columns on `deal_listing`); same atomic-`||`-merge as the ops checks, guarded on invoice status
`'listed'`. (3) `request-buyer-ack` **requires an active ack user** (BA.3) — a faithful guard, and the seam
where the real notification lands. (4) The **SLA is recorded, not enforced**: the auto-fail-on-breach
scheduler is deferred alongside the funding-window scheduler (M11-era), consistent with the no-scheduler
stance. (5) `record-buyer-ack(acknowledged)` is non-transition (no listing version bump); `failed` is a
version-guarded transition.

**Self-implemented** (not delegated) after the M9-C implementer instability — mirrors `recordOpsCheck`.

## DL-BE-043 — M9-E go-live L.11 hold + M9 /code-review — **M9 FULL RIGOR COMPLETE**
**Date:** 2026-06-26
**Status:** Built. Fifth/final sub-slice of [[DL-BE-038]]. Full suite **224**; the WS E2E
(`WalkingSkeletonE2ETest`) now flows end-to-end through the complete M9-C/D sub-machine.

**What shipped (L.11 hold).** `approveGoLive` re-checks both counterparties are still `active` at the gate
via `BuyerQueryPort.isActive`/`SupplierQueryPort.isActive` (the methods reserved in M9-A). If either is
inactive → the listing transitions `ready_for_review → held_for_review` instead of going live: **no VA, no
funding window, no `golive_checker_id` stamp** (the attempt is recorded in the audit envelope; the DB CHECK
`golive_checker ⟹ maker+mfa` stays satisfied since checker is left null). Both active → `live` as before.
This is the **synchronous** L.11 guard; the asynchronous mid-flight-suspension subscriber (and un-hold /
`CancelPreDisbursement`) remain event-bus-era deferrals.

**`/code-review` (high effort, 8 finder angles → verify).** 4 findings fixed, 1 refuted:
1. *(ARCH.1 — the headline)* `ListingService.activeAckUserIdentity` read the **foreign `buyer_ack_user`
   table via raw SQL** — the exact cross-BC read M9-A eliminated for the other three tables, and one the
   ArchUnit harness **cannot** catch (SQL strings are invisible to it). Fixed: new
   `BuyerQueryPort.activeAckUserIdentity` (impl in `BuyerQueryService`); the listing BC now routes through
   the port. Re-affirms why ARCH.1 needs both the harness *and* the "all foreign reads via a `*QueryService`"
   convention.
2. *(correctness)* `requestBuyerAck` shallow-`||`-merged `buyer_ack`, so a **re-request after an
   acknowledgment silently downgraded `status` 'acknowledged' → 'requested'** and blocked the snapshot.
   Fixed: reject a re-request once a recorded outcome (acknowledged/failed) exists; a benign 'requested'
   resend is still allowed. Regression test added.
3. *(defensive)* IRN check dereferenced `result.extractedFields()` (nullable per the record) → potential
   NPE/500. Fixed: null-guarded, **fail-closed** (an unverifiable IRN → 'failed', never a crash).
4. *(fail-closed)* `completeOpsChecks` pass-eval filtered out malformed check entries → a partial/non-object
   outcome was silently treated as a pass. Fixed: iterate the 7 wire names and require each to be a
   well-formed object with outcome ∈ {passed, not_applicable}; anything else **blocks** go-live.
   - *Refuted:* "non-transition commands ignore `X-Aggregate-Version`" — by design, matches the
     `BuyerService` non-transition precedent (guard on status, not listing version; merges are atomic `||`).

**M9 full rigor — done.** Intake → go-live with every reject/alternate branch: operational checks
(pass/`rejected_operational`), buyer ack (`acknowledgment_failed`), L.11 `held_for_review`, business-day
funding window, BC query ports + ArchUnit. Remaining gaps unchanged (see [[DL-BE-038]]): C6 four-eyes,
funding-window scheduler, ack-user OTP login, async hold/cancel, event bus.

---

## DL-BE-044 — M10 Investor Onboarding (BC7) full rigor — **COMPLETE** (Milestone 2, module 2)
**Date:** 2026-06-26
**Status:** **Done.** All three sub-slices built to DoD ([[DL-BE-045]]..[[DL-BE-047]]); full suite **236**.
Spec `docs/modules/M10-investor-full.md` (Status: Done). Umbrella for the second Milestone-2
module: widen BC7 from the WS-3 skeleton ([[DL-BE-033]]) — linear `signed_up → active`, admin-on-behalf,
`mismatch=false` — to the complete onboarding spec: suitability mismatch + override-ack (IA.4/C21/G26), the
KYC-rejected branch, BC17-verified PAN + penny-drop, and the full IA.3 activation gate. Sub-slices claim
`DL-BE-045+`.

**The four scope forks (DoR, user-decided 2026-06-26):**
1. **Admin-on-behalf retained; investor self-service portal/login DEFERRED.** Pilot is admin-driven (investor
   shares details offline, admin uploads docs + drives commands), as WS-3.
2. **PAN + bank verified via the BC17 ACL, admin-triggered** (`verify_pan` / `verify_penny_drop`) — C24/IA.8,
   no self-attestation, mirrors M9-C IRN. **Full-Aadhaar eKYC stays out** (only `aadhaar_last4` stored,
   IA.7/C15; admin-recorded). _Interpretation of the user's "admin uploads documents" answer — keeps the C24
   non-negotiable while admin-driven; flagged in the spec for veto._
3. **Invite Revoke DEFERRED** (no migration; enum lacks `'revoked'` — auto-expiry only).
4. **Post-active Suspend/Exit DEFERRED** (documented; Exit needs a BC2 read for IA.9; `Blacklisted` has no
   enum value).

**No new migration** (schema V1–V6 already carries every enum value + `inv_suitability.override_text_hash` +
`suspended_at`/`exited_at`; the only schema-changing items — Revoke/Suspend/Exit — are deferred).
**No new ArchUnit boundary** (M10 reaches other contexts only via the BC17 `VerificationPort` + BC11
`ComplianceService` — existing seams).

**Sub-slices (build order):** A BC17-verified PAN + bank ([[DL-BE-045]]) · B suitability mismatch +
override-ack + IA.3 gate ([[DL-BE-046]]) · C KYC-rejected branch ([[DL-BE-047]]). Each: red tests → green →
`/code-review` → DoD → its DL.

**Remaining gaps after M10-full (documented):** investor self-service login/portal; invite Revoke (V7);
Suspend/Exit/Blacklisted (+ BC2 `SubscriptionQueryPort`); full-Aadhaar eKYC; KYC-refresh scheduler (IA.6).

## DL-BE-045 — M10-A BC17-verified PAN + bank (penny-drop)
**Date:** 2026-06-26
**Status:** Built. First sub-slice of [[DL-BE-044]]. `InvestorVerificationTest` 5/5; WS-3
`InvestorOnboardingTest` 8/8 unchanged; full suite **229**.

**What shipped.** `record-identity-verified` now calls BC17 `VerificationPort.verifyPan` and
`complete-financial-profile` calls `verifyPennyDrop` (new port convenience) — the ACL result decides, the
admin-supplied value is never self-attested (C24/IA.8), mirroring the M9-C IRN pattern. Pass requires
`status=COMPLETED` AND the field (`pan_status`/`account_status`) == `VALID`; otherwise a new
`CommandRejectedException.verificationFailed` → **422** `verification_failed`, no transition (the gateway
rolls back the whole tx, including the `gate_verification` insert — so a failed check leaves no stale cached
row and a retry re-issues). **Full-Aadhaar eKYC stays out**: only `aadhaar_last4` is admin-recorded
(IA.7/C15); eKYC needs a secure transient-input path, deferred with self-service.

**Test seam.** `StubVerificationVendorClient` now honours two sentinels (like the notifier's FAIL_TEMPLATE):
PAN `ZZZZZ9999Z` → `pan_status=INVALID`; bank-last4 `0000` → `account_status=INVALID`. `deterministicFields`
takes the request inputs to apply them. Default (any other value) still auto-passes, so existing tests are
unaffected.

**Watch for.** `requireVerified` is **fail-closed** (null/missing field → fail, never NPE). The edge
PAN/bank format-check (`requiredPan`/`requiredFourDigits`, DL-BE-033) still fires *before* the ACL call, so a
malformed value is a 400 at the edge, a well-formed-but-unverifiable value is a 422 from the ACL.

## DL-BE-046 — M10-B suitability mismatch + override-ack + IA.3 activation gate
**Date:** 2026-06-26
**Status:** Built. Second sub-slice of [[DL-BE-044]]. `InvestorSuitabilityTest` 4/4; full suite **233**.

**What shipped.** `assess-suitability` now carries a `mismatch:bool` (optional, default false — keeps WS-3
callers green); it is stored on the fresh `inv_suitability` row (SA.1). A new
`acknowledge-suitability-override` (Compliance) stamps `override_text_hash = sha256(override_text)` on the
mismatched assessment — rejecting if the assessment is not a mismatch (nothing to override) or the investor
is already active; non-transition (no status change). `activate` now enforces **IA.3/IA.4**: a `mismatch`
assessment with no `override_text_hash` → new `CommandRejectedException.suitabilityOverrideRequired` (**422**),
the listing stays `mia_signed`; once acknowledged, activation proceeds. The other IA.3 prerequisites
(kyc_approved, bank set, MIA signed) are guaranteed by the linear forward machine reaching `mia_signed`, so
the override-ack is the only new gate.

**Decisions.** (1) The override command **hashes the `override_text`** the platform displays (G26), like the
questionnaire hash — the client sends text, not a pre-computed hash. (2) Override role = **Compliance**
(admin-on-behalf; B3's `investor` actor maps to Compliance for the pilot). (3) `loadSuitability` takes the
latest assessment by `assessment_id DESC` (one per investor in the forward-only Phase-1 machine; future
re-assessment creates a new id per SA.1). (4) PII unchanged — only hashes touch `inv_suitability`.

## DL-BE-047 — M10-C KYC-rejected branch + M10 /code-review — **M10 FULL RIGOR COMPLETE**
**Date:** 2026-06-26
**Status:** Built. Final sub-slice of [[DL-BE-044]]. `InvestorKycRejectionTest` 3/3; full suite **236**.

**What shipped.** The KYC-rejected branch. `ComplianceService` gains `rejectKyc` (maker ≠ checker + MFA,
file `submitted → rejected` with a reason — same controls as approve) and `resubmitKyc` (file
`rejected → submitted` under a fresh submitter, clearing the prior decision so the
`submitted ⟹ approver_id/decided_at NULL` CHECK holds). `InvestorService.recordKycRejected` (Compliance) +
`resubmitKyc` (Ops), both **non-transition**, require the account at `financial_profile_completed` (the
decision stage, symmetric with approve). The `inv_account_status` enum has **no `kyc_rejected` state**, so
the rejection lives on the `comp_kyc_file`, not the account: a rejected file holds the investor at
`financial_profile_completed` (approve blocked — no submitted file) until resubmit → approve recovers.

**`/code-review` (high recall) — 1 fix, 2 documented.**
1. *(fixed)* `rejectKyc` did a SELECT-then-UPDATE without a rowcount check (TOCTOU): a racing decision
   between the two could update 0 rows yet return success + emit an orphaned `KycRejected` envelope (the
   WS-6 orphaned-envelope class of bug). Added `updated == 1` guard — now symmetric with `resubmitKyc`.
   (Pre-existing `approveKyc` has the same unchecked shape; left untouched — WS-1 code, separate concern,
   noted here for a future symmetry pass.)
2. *(documented, not fixed)* The BC17 `VerificationService` cache keys on `(subject_id, api_name)` and
   **ignores the inputs** — a TTL'd VALID result is reused even if the same subject is later verified with a
   *different* PAN/bank value. **Pre-existing ACL defect, not M10 code, not reachable in M10's linear flow**
   (each subject verifies PAN/penny-drop exactly once; a failed verify rolls back with the command tx, so no
   stale FAIL persists). Flagged for a BC17 fix (cache key should include an input hash for one-/few-shot
   identity checks).
3. *(by design)* The three new non-transition commands don't version-guard `inv_account` — matches the
   established Supplier/Buyer non-transition convention (status-guarded + command_id-idempotent + DB CHECKs);
   same call the M9 review made.

**M10 full rigor — done.** Onboarding intake → active with every reject/alternate branch: BC17-verified PAN +
penny-drop (C24/IA.8), suitability mismatch + override-ack (IA.4/C21/G26), the IA.3 activation gate, and the
KYC-rejected → resubmit recovery. Remaining gaps unchanged (see [[DL-BE-044]]): investor self-service
login/portal, invite Revoke (V7), Suspend/Exit/Blacklisted, full-Aadhaar eKYC, KYC-refresh scheduler.

---

## DL-BE-048 — M11 Subscription (BC2) full rigor — **COMPLETE** (Milestone 2, module 3)
**Date:** 2026-06-26
**Status:** **Done.** Both sub-slices built to DoD ([[DL-BE-049]]–[[DL-BE-050]]); full suite **247**.
Spec `docs/modules/M11-subscription-full.md` (Status: Done). Umbrella for the third Milestone-2
module: widen BC2 from the WS-5 skeleton ([[DL-BE-035]]) — commit → `fully_funded` → inflow → `confirmed`
(the G10 chain) — to the complete pre-disbursement lifecycle: **pre-confirmation cancellation + release**
and the **funding-shortfall → refund → closed** path. Sub-slices claim `DL-BE-049+`.

**The three scope forks (DoR, user-decided 2026-06-26):**
1. **Admin-on-behalf retained; investor self-service commit + login DEFERRED** (Ops commits/cancels for the
   investor, as WS-5; consistent with M9/M10).
2. **`DeclareFundingShortfall` = an Ops-triggered command; automatic cron DEFERRED.** Guarded on
   `now() ≥ funding_window_close_at AND committed_total < funding_target`; the shortfall→refund path is fully
   built + testable, only the `@Scheduled` time-trigger (L.8/L.9 active side) is deferred.
3. **Concentration warnings (S.8) DEFERRED** (gap recorded) — soft/non-blocking, so deferring changes no
   money outcome; `concentration_warnings_at_commit` stays `[]`.

**Derived:** refund money-movement runs **inline via `EscrowPort.instructRefund`** (`cash_payout_kind='refund'`,
PI.4), mirroring WS-7 (refund webhook deferred). **No new migration** (status enums + the refund payout kind
already exist; there is **no `refund_eligible` status** — `RefundEligible` is an audit event). **No new
ArchUnit boundary** (BC1↔BC2 coordinated commit/release is the documented in-process G17 coupling; BC18 via
`EscrowPort`).

**Sub-slices (build order):** A pre-confirmation cancel + release ([[DL-BE-049]]) · B funding shortfall →
refund → closed ([[DL-BE-050]]). Each: red tests → green → `/code-review` → DoD → its DL.

**Remaining gaps after M11-full (documented):** investor self-service commit/login; concentration warnings
(S.8); automatic funding-window-expiry cron; refund webhook + EoD overlay; post-disbursement subscription
lifecycle (assignment_executed/distribution_received/loss_realised → M12/M13/M14).

## DL-BE-049 — M11-A pre-confirmation cancellation + release (S.2 / L.2 inverse)
**Date:** 2026-06-26
**Status:** Built. First sub-slice of [[DL-BE-048]]. `SubscriptionCancellationTest` 4/4; WS-5
`SubscribeFullyFundedTest` 8/8 unchanged; full suite **240**.

**What shipped.** `POST /subscriptions/{id}/cancel` (ops-on-behalf): a `committed` subscription flips to
`cancelled_by_investor` and **releases** the host listing in one atomic statement — the inverse of the WS-5
coordinated commit: `committed_total -= amount` with `status = CASE WHEN 'fully_funded' THEN 'live' …` +
`RETURNING` so the reopen decision is driven off the true row, never a stale before-image. The subscription
flip is **version-guarded and runs first** (a concurrent double-cancel loses cleanly before any listing
release). A reopened listing (`fully_funded → live`) emits its own `listing.Listing.FundingReleased` envelope
— the symmetric counterpart of the WS-5 `FullyFunded` one. S.2 enforced: only a `committed` subscription can
be cancelled (a `confirmed`/funded one → reject; committed_total untouched).

**Watch for / decisions.** (1) **S.2 boundary**: cancellation is allowed only pre-funds (`committed`); the
`funds_pending` micro-state is collapsed (WS-5), so in practice cancel is `committed`-only. (2)
**Re-subscription is blocked** after cancel: the `sub_subscription_listing_investor_uq (listing_id,
investor_id)` UNIQUE is unconditional, so a cancelled row keeps the slot (DuplicateKey → 400). A *partial*
UNIQUE (excluding cancelled/refunded) to allow re-subscription is a documented future **migration**, out of
M11-full (no new migration). (3) Release guarded `WHERE status IN ('live','fully_funded')` — a committed sub
can't exist on a disbursed listing, but the guard is the backstop.

## DL-BE-050 — M11-B funding shortfall → refund + M11 /code-review — **M11 FULL RIGOR COMPLETE**
**Date:** 2026-06-26
**Status:** Built. Final sub-slice of [[DL-BE-048]]. `SubscriptionRefundTest` 7/7; full suite **247**.

**What shipped.** `POST /listings/{id}/declare-funding-shortfall` (ops, BC1): guarded on `status='live' AND
funding_window_close_at ≤ now() AND committed_total < funding_target` → version-guarded flip `live →
funding_failed_refunded` (the version guard closes the SELECT→UPDATE TOCTOU — a concurrently-filled listing
fails cleanly, never declared on a just-fully_funded row). `POST /subscriptions/{id}/record-refund`
(treasury, BC2): on a `funding_failed_refunded` listing, a `confirmed` (funded) position returns money inline
via `EscrowPort.instructRefund` + a `kind=refund` `cash_payout_instruction` → `refunded`; a `committed`
(unfunded) position flips with no money movement. `refunded` is the money-returned terminal (explicit `Close`
folded). The automatic window-expiry cron is deferred (ops-triggered command per the DoR).

**`/code-review` (high recall) — 1 fix (covering 2 findings), 1 documented.**
1. *(fixed — high)* the refund's escrow idempotency key (`payout_instruction_id`) was minted fresh per call
   (`Ids.newId()`), so a retried/concurrent refund would generate a *different* key and
   `escrow.instructRefund` could fire **two real vendor refunds** (a non-transactional adapter would not roll
   the second back — only the test stub masked it). Fixed: the id is now **derived deterministically** from
   the subscription (`UUID.nameUUIDFromBytes("refund:"+subscriptionId)`), so a retry reuses the key (escrow
   dedups) and a concurrent second insert **collides on the payout PK** → caught → reject. This also supplies
   the **DB last-line-of-defence** the reviewer's 2nd finding wanted (one refund row per subscription,
   enforced by the PK — no migration needed). Regression test added (a second refund → 4xx, exactly one
   payout row).
2. *(documented, not fixed — low)* a `committed` (unfunded) refund leaves the failed listing's
   `committed_total` un-decremented. The listing is **terminal** (`funding_failed_refunded`) and no invariant
   reads `committed_total` after — it is a *historical* record of what was committed when the window closed,
   not stale-wrong. Left as-is to keep `record-refund` from coupling back to the listing aggregate.

**M11 full rigor — done.** Pre-confirmation cancellation + release (S.2/L.2 inverse, atomic, reopens a
fully_funded listing) and the funding-shortfall → refund path (L.8/L.9, inline escrow refund). Remaining gaps
unchanged (see [[DL-BE-048]]): investor self-service commit/login; concentration warnings (S.8); the
automatic window-expiry cron; refund webhook + EoD overlay; post-disbursement lifecycle (M12/M13/M14);
re-subscription after cancel (needs a partial UNIQUE — future migration).

---

## DL-BE-051 — M12 Assignment & Signing (BC5) full rigor — **COMPLETE** (Milestone 2, module 4)
**Date:** 2026-06-26
**Status:** **Done.** Both sub-slices built to DoD ([[DL-BE-052]]–[[DL-BE-053]]); full suite **255**.
Spec `docs/modules/M12-assignment-full.md` (Status: Done). Umbrella for the fourth Milestone-2
module: widen BC5 from the WS-6 skeleton ([[DL-BE-036]]) — single leg (`total_count=1`), inline complete →
`all_signed` (the C27 gate) — to the complete spec: **multi-investor assignment legs**, the **G13 24h
time-box / incomplete** path, and the **leg-failure + retry** path. Sub-slices claim `DL-BE-052+`.

**The two scope forks (DoR, user-decided 2026-06-26):**
1. **Signing completion = ops `complete-signing` per leg; BC19 signing webhook DEFERRED** (extend WS-6's
   inline M5c completion to multi-leg; consistent with the admin pilot + M11's inline-defer-webhook choice).
2. **G13 incomplete = ops `DeclareIncomplete` command; auto-cron DEFERRED** (guarded on `sign_deadline ≤ now()
   ∧ signed_count < total_count`; twin of M11's `DeclareFundingShortfall`).

**Derived:** admin-on-behalf retained; **per-invoice stamping (AS.7) DEFERRED**; **no new migration**
(`legal_assignment_set_status` has `incomplete`; `sign_deadline`/counts-CHECK/legs-JSONB already exist;
multi-leg lives in the same JSONB array).

**Sub-slices (build order):** A multi-leg assignment ([[DL-BE-052]]) · B G13 incomplete + leg failure
([[DL-BE-053]]). Each: red tests → green → `/code-review` → DoD → its DL.

**Remaining gaps after M12-full (documented):** BC19 signing webhook; auto 24h scheduler; per-invoice +
master stamping (AS.7/MA.4); the refund of held subscriptions after an incomplete set (listing
`held_for_review → cancel → refund`, cross-module BC1/BC2); the per-subscription MarkRefundEligible fan-out
(event-bus era).

## DL-BE-052 — M12-A multi-leg assignment (AS.1/AS.3/AS.5)
**Date:** 2026-06-26
**Status:** Built. First sub-slice of [[DL-BE-051]]. `AssignmentMultiLegTest` 4/4; WS-6
`AssignmentAllSignedTest` 4/4 + the WS E2E unchanged; full suite **251**.

**What shipped.** `AssignmentService.request` now enumerates **all** confirmed subscriptions on the listing
(`ORDER BY subscription_id`) and builds `total_count = N` legs — one leg + `legal_master_agreement` (MIA) +
`legal_signature_request` + M5c `initiateSignature` per investor, in one `legal_assignment_set` (legs JSONB
array). `completeSigning(listingId, investorId)` completes ONE investor's leg (finds it in the JSONB by
`investor_id`, completes its vsr, marks the MIA signed + cert, flips that leg to `signed`); `all_signed` and
the **C27 `deal_listing.all_signed` flip** fire only when `signed_count == total_count` (AS.3/AS.5). The
WS-6 single-leg `size()==1` guard is gone. The `complete-signing` endpoint now takes an `investor_id` body.

**Watch for / decisions.** (1) Each leg has its **own** MIA — MA.1 (one MasterAgreement per `(party_id,
kind)`) holds because investors differ. (2) Re-completing a leg is rejected (`leg already signed`); same
`command_id` replays via the gateway. (3) Completing an unknown investor → clean reject. (4) The C27 flip
stays **rowcount-asserted** (WS-6 lesson). (5) Existing `AssignmentAllSignedTest` (single confirmed sub) is
the `N=1` case and passes unchanged behaviourally (now via the `investor_id` body). **Watch:** global-count
test assertions must be scoped (the suite doesn't roll back per method — a sibling test's MIA rows leak into
an unscoped `count(*)`).

## DL-BE-053 — M12-B G13 time-box + leg failure/retry + M12 /code-review — **M12 FULL RIGOR COMPLETE**
**Date:** 2026-06-26
**Status:** Built. Final sub-slice of [[DL-BE-051]]. `AssignmentTimeBoxTest` 4/4; full suite **255**.

**What shipped.** `declare-incomplete` (ops, G13/AS.4): guarded on `in_progress ∧ sign_deadline ≤ now() ∧
signed_count < total_count` → set `incomplete` + listing `fully_funded → held_for_review` (both
rowcount-asserted) + a `listing.Listing.HeldForReview` envelope (the C27 gate never opens, so WS-7
disbursement stays blocked). `complete-signing` now **rejects past `sign_deadline`** (AS.4 — added to
`loadSet`'s `past_deadline`). `record-leg-failed` (leg `initiated → failed`; MIA→failed+reason;
signature_request→failed; `signed_count` unchanged). `reinitiate-leg` (SR.2 retry ≤ 3): a **fresh** MIA +
signature_request carrying `retry_count = prior+1` (the failed pair retained for audit, MA.1), new vsr,
leg→`initiated`; rejected once retries are exhausted or past the deadline.

**`/code-review` (high recall) — 1 fix covering 2 findings (both high).** `loadSet` was a **non-locking
SELECT**, and every set-mutating command blind-writes `signed_count` + the whole `legs` JSONB from that
stale snapshot. Two real races:
1. **Concurrent `complete-signing` of two different legs lost a signature** — both read `signed_count=0`,
   each wrote `1`; the set never reached `all_signed`, `deal_listing.all_signed` stuck FALSE on a
   fully-signed deal, AS.5 (`signed_count == #signed MIAs`) violated — the **C27 disbursement gate wedged
   shut**. (This is the M12 headline risk — multi-leg is the whole point.)
2. **Stale-vsr-after-reinitiate** — `complete-signing` captured `vsr_id`/`agreement_id` from the stale
   snapshot, so an interleaving `reinitiate` could complete the *superseded* signing attempt and clobber the
   new ids.
**Fix (one line, both bugs):** `loadSet` now does `SELECT … FOR UPDATE` — all set-mutating commands serialize
on the assignment-set row, so each reads the prior's committed state (no lost update, no stale vsr). Clean on
the existing single-/multi-leg tests; a thread-level concurrency test is impractical in MockMvc, so the
row-lock is the remedy (the standard fix, same class as the WS-4 concurrent-go-live reorder).

**M12 full rigor — done.** Multi-investor legs (the C27 gate opens only when *every* leg signs), the G13 24h
time-box → incomplete + HoldForReview, and leg failure → retry (SR.2). Remaining gaps unchanged (see
[[DL-BE-051]]): BC19 signing webhook; auto time-box cron; per-invoice + master stamping; the refund of held
subscriptions after an incomplete set (cross-module BC1/BC2).

---

## DL-BE-054 — M13 Settlement (BC4) maturity recording — **COMPLETE** (Milestone 2, module 5, narrowed)
**Date:** 2026-06-26
**Status:** **Done.** Spec `docs/modules/M13-settlement-maturity.md` (Status: Done). `ListingMaturityTest`
5/5; full suite **260**. Widens WS-7 ([[DL-BE-037]]) by the next spine step buildable **without BC12/Tax**:
maturity recording — the buyer's full repayment flips the listing `disbursed → matured_payment_received`
(`Listing.Matured`). New `MaturityService` + `MaturityController` (settlement, BC4): one ops command,
status-guarded + rowcount-asserted (the WS-7 twin), `amount == face_value` (under-payment → reject, M14
Collections). `/code-review` **clean** (reviewer noted the event `aggregateVersion = version+1` is more
correct than the WS-7 twin's hardcoded `1`).

**The three scope forks (DoR, user-decided 2026-06-26):** (1) scope = the distribution/maturity **spine**
(not the reconciliation/remediation engine); (2) **TDS investor distribution DEFERRED entirely** (no payout
until BC12); (3) **inline + ops-triggered** (no maturity/payout webhook, no EoD overlay).

**Why the slice is narrow (the schema forces it).** The only TDS-free part of the spine is **maturity
recording**. `deal_terminal_outcome` has only `{distributed, funding_failed_refunded,
cancelled_pre_disbursement, defaulted}` and `deal_listing_terminal_outcome_shape_chk` makes `closed ⟺
terminal_outcome NOT NULL` — so a matured deal can close **only** as `distributed`, i.e. **close requires the
distribution to have happened**. Distribution is deferred (TDS), so **close defers with it**; M13 stops at
`matured_payment_received`. A maturity **shortfall** (buyer pays < face value) routes to BC6 Collections
(`col_maturity_case`, M14) — also deferred; M13 records the **full** repayment and rejects under-payment.

**Sub-slice (single):** A maturity recording ([[DL-BE-054]]). **No new migration**
(`matured_payment_received` already in `deal_listing_status`).

**Remaining BC4 (the large deferred surface — future M13-distribution + M13-reconciliation):** the TDS
investor distribution (S.9/PI.3, blocked on BC12/M16); deal close (coupled to distribution); the
reconciliation/remediation engine (RL/RC, V.2 discrepancy, PI.6 partial legs, PI.7 webhook+EoD, PI.8 T+1);
maturity shortfall → Collections (BC6/M14); the subscription assignment_executed/distribution_received
lifecycle; the VA maturity-inflow ledger + VA close.

---

## DL-BE-055 — M7 Supplier Onboarding (BC8) full rigor — **COMPLETE** (Milestone 2, module 6)
**Date:** 2026-06-26
**Status:** **Done.** Both sub-slices built to DoD ([[DL-BE-056]]–[[DL-BE-057]]); full suite **265**.
Spec `docs/modules/M7-supplier-full.md` (Status: Done). Direct analog of M10 Investor
([[DL-BE-044]]); applies the same precedents. Widens BC8 from the WS-1 skeleton ([[DL-BE-031]]) — linear
`created → active`, admin-on-behalf, identity admin-recorded — to the TDS-free full-rigor parts: **BC17-verified
identity** (SA8.3) and the **KYC-rejected** branch. Sub-slices claim `DL-BE-056+`.

**Precedents applied (from M10; no fork re-litigation):** admin-on-behalf retained (supplier has no login in
Phase 1, DL-012); **PAN + GSTIN verified via the BC17 ACL** (`verify_pan`/`verify_gstin`, + `fetch_mca21` for
CIN when present), not self-attested (SA8.3/C24); **KYC-rejected branch reuses the subject-generic
`ComplianceService.rejectKyc`/`resubmitKyc`** from M10-C ([[DL-BE-047]]).

**Deferred (the flagged call + the rest):** **Suspend/Blacklist** (SA8.1, Credit+Compliance maker-checker —
mirrors M10's investor-suspend deferral; *pull forward on request*); voluntary exit (SA8.4, needs a BC1
read); **agency-consent enforcement** (AC.1/AC.3 — grant is built, enforcement awaits *agency* actors, i.e.
the deferred portal); KYC-refresh scheduler (SA8.5); UDYAM. **No new migration; no new ArchUnit boundary.**

**Sub-slices (build order):** A BC17-verified identity ([[DL-BE-056]]) · B KYC-rejected branch
([[DL-BE-057]]). Each: red tests → green → `/code-review` → DoD → its DL.

## DL-BE-056 — M7-A BC17-verified supplier identity (PAN/GSTIN/CIN, SA8.3)
**Date:** 2026-06-26
**Status:** Built. First sub-slice of [[DL-BE-055]]. `SupplierIdentityVerificationTest` 2/2; WS-1
`SupplierOnboardingTest` 9/9 unchanged; full suite **262**.

**What shipped.** `record-identity-verified` now verifies the supplier's stored identity through the BC17 ACL
(SA8.3/C24 — not self-attested), mirroring M10-A: `verifyPan` (pan_status=VALID) + `verifyGstin`
(gstin_status=ACTIVE) always; `fetchMca21` (cin_valid=true) **when a CIN is present** (companies have one;
proprietorship/MSME do not). On pass → `created → identity_verified`; otherwise
`CommandRejectedException.verificationFailed` → **422**, no transition (the gateway rolls back the
`gate_verification` insert, so a retry re-issues). New `VerificationPort.fetchMca21` convenience.

**Watch for.** `requireVerified` here is **boolean-aware** (`String.valueOf(value)` compared to `expected`) —
MCA21's `cin_valid` is a JSON boolean, unlike the string `pan_status`/`gstin_status`. Fail-closed on
null/missing. The default stub returns VALID/ACTIVE/true, so the WS-1 happy path now passes *through* the ACL
unchanged; the FAIL_PAN sentinel (`ZZZZZ9999Z`, valid format) drives the reject test.

## DL-BE-057 — M7-B supplier KYC-rejected branch + M7 /code-review — **M7 FULL RIGOR COMPLETE**
**Date:** 2026-06-26
**Status:** Built. Final sub-slice of [[DL-BE-055]]. `SupplierKycRejectionTest` 3/3; full suite **265**.

**What shipped.** The supplier KYC-rejected branch, **reusing the subject-generic
`ComplianceService.rejectKyc`/`resubmitKyc`** from M10-C unchanged. `record-kyc-rejected` (Compliance,
maker ≠ checker + MFA) → `comp_kyc_file` rejected; the supplier **holds at `kyc_submitted`** (one stage
earlier than the investor's `financial_profile_completed` — supplier KYC approval is
`kyc_submitted → kyc_approved`). `record-kyc-approved` is then blocked (no submitted file) until
`resubmit-kyc` (Ops) re-opens the file → approve advances. Both non-transition on `sup_account`.

**`/code-review` (high recall) — clean (no findings).** A faithful mirror of the reviewed M10 patterns: the
boolean-aware `requireVerified` is fail-closed; `pan`/`gstin` are edge-required so the verify calls never see
null; `reason` flows only to `comp_kyc_file.rejection_reason`, never an audit payload (no PII leak); the KYC
maker-checker + rowcount guards come from the already-fixed `ComplianceService`.

**M7 full rigor — done.** BC17-verified identity (SA8.3) + the KYC-rejected → resubmit branch, admin-on-behalf.
Remaining gaps unchanged (see [[DL-BE-055]]): supplier/agency portal + login; Suspend/Blacklist (SA8.1,
*flagged — pull forward on request*); voluntary exit (SA8.4); agency-consent enforcement (AC.1/AC.3);
KYC-refresh scheduler; UDYAM.
