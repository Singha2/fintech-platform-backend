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
