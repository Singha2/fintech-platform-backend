# UI Integration — Backend Read-Surface Spec

> **What this is.** The additive backend work the 15-screen frontend (`../fintech-patform-mock`) needs to run
> against the live API without mock data. It is the concrete output of **ROADMAP Phase B ("UI plugin")**
> discovering which reads the screens require, expressed as **Phase-D additive endpoints**.
>
> **What this is NOT.** It does **not** change any command, the `CommandResponse` envelope, any existing
> by-id `GET`, the auth/session write path, the schema (no migration for the read endpoints), or any deferred
> control. It only *adds* read surface. This is exactly the ROADMAP contract: *"D adds endpoints, never alters
> the ones B/C depend on"* and *"UI and integrations build against the API contract, backend remainder is
> additive — so nothing downstream conflicts."* (ROADMAP §2, §6.)
>
> **Source of truth.** The backend owns the final shapes. Every response below is a **PROPOSAL**; source the
> exact columns from the underlying write table + the matching existing by-id read, and the UI will adhere to
> whatever ships. UI-side counterparts: `../fintech-patform-mock/docs/API_ALIGNMENT.md` (gap register G1–G13),
> `INTEGRATION_PLAN.md` (UI steps), `INTEGRATION_WORK_SPLIT.md` (Part A ↔ Part B).

---

## 0. Design invariants this spec preserves (the guardrails)

Read these before implementing any item. If an item seems to require breaking one of these, stop — it is
mis-specified.

1. **Additive only.** New endpoints/controllers only. **No edit** to: existing command handlers, the command
   envelope, the existing by-id `GET`s (they are both the *frozen contract* Phase B consumes **and** the
   command-refresh reads — do not widen or reshape them; add a *separate* richer read instead), the security
   filter's write path, or existing tests. (ROADMAP §2/§6.)
2. **Match the native read pattern** (as `TaxQueryController`, `KycDocumentController.list`, the by-id gets
   already do): thin `JdbcTemplate` `SELECT` over the **existing write tables**; return `Map<String,Object>` /
   `List<Map<String,Object>>` with **snake_case** keys; `@AuthenticationPrincipal AuthSession session` as the
   first handler param (even when unused); `shared.error.NotFoundException` on an empty single-row; filters via
   `@RequestParam`. No JPA/entities, no new dependency, no method-level `@PreAuthorize`, no Lombok on
   controllers, constructor injection.
3. **No migrations for the read endpoints.** BE-1…BE-13 query existing tables (`sup_account`, `buyer_account`,
   `deal_listing`, `cash_payout_instruction`, `inv_invite`, `comp_kyc_file`, `sys_audit_event`, etc.) — the same
   tables the commands already write and the by-id gets already read. Only the deferred-milestone items
   (BE-15 buyer login) touch new auth surface. If any item appears to need DDL, it is out of scope (flag it).
4. **Authorization stays at the command boundary.** Reads stay authenticated-only (any live bearer), matching
   the current, deliberate posture — *"any authenticated actor can currently read any admin aggregate (no
   sensitive read gating)"* (DECISION_LOG, sensitive-read-gating deferral). **Do not** add per-role read gating
   in this pass; that is a separately-tracked deferred control. The **only** reads that must be scoped are the
   investor/buyer-facing portal reads (BE-14/BE-15), which is *why* they are gated to their milestones below.
5. **Respect the deferred milestones — do not pull them forward as casual admin reads.**
   - Investor-facing marketplace/portfolio reads = **M10-full (investor portal)**. They require ownership
     scoping + the KYC'd-investor download gate (API_CATALOGUE §Invoice artifacts). → **BE-14**.
   - Buyer ack-user login is a **passwordless email+OTP** path (DL-021) that **does not exist yet** and is
     **not** the admin `/auth/login/password` flow; buyer-scoped reads + self-ack ride on it = **WS-2**. → **BE-15**.
   - Audit/regulator read access = **M17 Auditor (BC13)**, already on the roadmap. → **BE-13**.
   - Real KYC completeness / mandatory-doc enforcement = **M15**. The KYC-file-id *resolver* here (**BE-2**) is a
     one-row additive read, **not** enforcement — it does not touch M15's scope.
6. **Projections are a future optimization, not now.** The work-queue and audit-log are noted as eventual
   projections (DECISION_LOG: "queue is a projection", "audit-log-as-read-model … a projection table"). For
   pilot scale, compute from the write tables. Introduce a projection table only if profiling demands it
   (post-pilot).
7. **Do not fake deferred *controls* the UI happens to surface.** These remain backend-deferred; the UI must not
   imply they work: four-eyes/second-approver >₹10 Cr (DL-BE-059), pricing-band re-pricing/supersession
   (DL-BE-060 — needs a migration), suspend/blacklist off-boarding, agency-consent enforcement (DL-BE-055),
   doc encryption-at-rest (DL-BE-074), session-token hardening (DL-BE-077). Out of scope here.
8. **Pagination has no precedent.** Pilot data is small. Return full admin lists with a documented cap (propose
   `LIMIT 500`) plus optional `?status=` / `?q=` filters. Add real pagination only when a screen actually needs
   it. This is an explicit decision left to the backend owner.

---

## 1. Priority tiers & module mapping

| Tier | Items | Why | Milestone/owner |
|---|---|---|---|
| **P0** — unblock a coherent live *admin* UI | BE-1 session/`me`, BE-2 kyc-file resolver, BE-3 MFA answer (no code) | tiny, zero-risk, needed by almost every screen | new additive |
| **P1** — additive admin read/list surface | BE-4…BE-12 (suppliers, buyers+bands, listings+ops-checks, disbursement queue, distribution investors, invites, supplier tracker, dashboard, listing-detail) | removes the bulk of the UI mock-flags (G2–G12) | new additive read surface (suggest grouping as an "Admin Read" work item; module code owner-assigned) |
| **P1 (planned module)** | BE-13 audit list | already a roadmap module | **M17 Auditor** |
| **P2** — deferred portals | BE-14 investor reads, BE-15 buyer reads + ack login + self-ack | require ownership scoping + deferred login flows | **M10-full**, **WS-2** |
| **P2** — infra | BE-16 CORS | prod cross-origin only | infra/deploy |

**Sequencing:** P0 first (unblocks role display + KYC docs + tells the UI how to gate MFA), then P1 admin
reads (each removes one mock-flag, in the ROADMAP §4 lifecycle order: onboarding → listing → funding →
settlement → consoles), then P2 with their milestones. All P0/P1 items are independent and parallelizable.

---

## 2. Endpoint specifications

Each item: **Unblocks · Design-fit · Route/Auth · Params · Response (PROPOSAL) · Source · Migration · Acceptance · Effort.**

### BE-1 — `GET /auth/session` (current identity + roles) · P0
- **Unblocks:** UI role discovery (Part B B2/Step 6.5); removes the "advisory persona" workaround. Screens: all.
- **Design-fit:** every internal piece already exists — only the HTTP surface is missing. Reuse the request
  principal `AuthSession` (id + expiries + `mfa_assertion_id`), `auth_identity.kind`, and
  `adminiam.RoleResolver.activeRoles(identityId)` (the *same* resolver `CommandGateway` uses for authz). No new
  query logic, no authz change.
- **Route/Auth:** `GET /auth/session` — any live bearer (add to a new `SessionController` or extend
  `AuthController`; note `/auth/login/**` is permitAll but `/auth/session` must stay authenticated — place it so
  it is **not** under the `permitAll("/auth/login/**")` matcher).
- **Response (PROPOSAL):**
  ```json
  { "identity_id":"uuid", "kind":"admin_user|investor|acknowledgment_user|auditor",
    "email":"…", "roles":["ops_executive", …], "admin_user_id":"uuid|null",
    "mfa_fresh": true, "idle_expires_at":"ISO", "absolute_expires_at":"ISO" }
  ```
  `roles` empty for non-admin kinds. `admin_user_id` null unless kind=admin_user. `mfa_fresh` = `SessionService
  .isMfaFresh(session, SENSITIVE)`.
- **Source:** `auth_session` (principal), `auth_identity.kind`, `admin_role_assignment` via `RoleResolver`.
- **Migration:** none.
- **Acceptance:** log in as `ops@dev.local` → `roles:["ops_executive"]`, `kind:"admin_user"`; as
  `investor@dev.local` → `roles:[]`, `kind:"investor"`.
- **Effort:** S.

### BE-2 — KYC-file-id resolver · P0
- **Unblocks:** the KYC-doc UI (Part B B4/Step 4.3). Today **no** endpoint returns a `kyc_file_id` given a
  supplier/investor id, so `POST /kyc/{kycFileId}/documents` is unreachable from the UI.
- **Design-fit:** `comp_kyc_file` has a **UNIQUE (subject_id, subject_type)** — exactly one row per subject
  (created in `ComplianceService.submitKyc`). A one-row read, native pattern.
- **Route/Auth:** `GET /suppliers/{id}/kyc-file` and `GET /investors/{id}/kyc-file` — any bearer. (Or a single
  `GET /kyc?subject_id=&subject_type=` — owner's choice; the per-subject route reads more RESTfully.)
- **Response (PROPOSAL):** `{ "kyc_file_id":"uuid", "subject_id":"uuid", "subject_type":"supplier|investor", "status":"submitted|approved|rejected" }`
- **Source:** `SELECT kyc_file_id, status FROM comp_kyc_file WHERE subject_id=? AND subject_type=?::comp_kyc_subject_type`. `NotFoundException` if the subject hasn't submitted KYC yet.
- **Migration:** none.
- **Acceptance:** the seeded supplier that has submitted KYC returns its `kyc_file_id`; a fresh
  never-submitted subject → 404 (UI hides the KYC-doc panel until KYC is submitted).
- **Effort:** S.

### BE-3 — MFA-freshness contract for the UI (no code) · P0
- **Answer to record (in `API_CATALOGUE.md`):** *every* admin-actor command requires MFA freshness; today all
  are `ActionSensitivity.SENSITIVE` (5-minute window) — there is no per-command allowlist and `NORMAL` is
  unused. Non-admin-actor commands skip the MFA gate.
- **UI consequence (Part B Step 6.4):** the UI must treat **all** admin commands as MFA-fresh-gated (the
  `MfaModal` isn't only for go-live/disbursement) and handle a stale-MFA rejection by routing to re-auth. No
  backend change — just make this explicit in the catalogue so the UI gates correctly.
- **Effort:** doc only.

### BE-4 — `GET /suppliers` (list) · P1 · unblocks G2 / S3
- **Route/Auth:** `GET /suppliers?status=&q=` — any bearer.
- **Response (PROPOSAL, per row):** `{ supplier_id, legal_name, constitution_type, pan, gstin, status, activated_at }` — **source exact columns from `sup_account`** (mirror the by-id `GET /suppliers/{id}` field set, plus display columns).
- **Source/Migration:** `SELECT … FROM sup_account [WHERE status=?::sup_account_status] [AND legal_name ILIKE ?] ORDER BY … LIMIT 500`. None.
- **Acceptance:** returns the seeded supplier; `?status=active` filters.
- **Effort:** S.

### BE-5 — `GET /buyers` + pricing bands · P1 · unblocks G3 / S4
- **Routes:** `GET /buyers?status=&q=` → rows `{ buyer_id, legal_name, sector, status, credit_limit_paise, mca_cin, gstin }` from `buyer_account`; and `GET /credit/buyers/{id}/pricing-bands` → `[{ tenor_bucket, rate_range_min_bps, rate_range_max_bps, fee_bps, effective_from, status }]` from `risk_pricing_policy`.
- **Migration:** none. **Effort:** S–M.
- **Note:** pricing-band **re-pricing/supersession** stays deferred (DL-BE-060). These are read-only.

### BE-6 — `GET /listings` + ops-checks read · P1 · unblocks G4 / S5
- **Routes:** `GET /listings?status=&supplier_id=&buyer_id=` → rows `{ listing_id, invoice_number, supplier_id, buyer_id, face_value_paise, tenor_days, status, funding_target, rate_bps }` from `deal_listing`; and `GET /listings/{id}/ops-checks` → `[{ check_name, outcome, checked_by, checked_at }]` from the ops-check table (source table name from `record-ops-check`'s writer).
- **Supplier/buyer names:** if `deal_listing` stores only ids, the UI can join client-side via BE-4/BE-5, **or**
  the backend may add `supplier_legal_name`/`buyer_legal_name` to the row via a JOIN (owner's choice — a JOIN
  read is native, see `DisbursementController`).
- **Migration:** none. **Effort:** M.

### BE-7 — Disbursement queue + detail · P1 · unblocks G5 / S6
- **Routes:** `GET /disbursements?status=` (or `GET /listings?status=fully_funded,disbursed`) → queue rows; and
  a **new** `GET /listings/{id}/disbursement/detail` exposing `{ payout_instruction_id, status, gross_amount, net_amount, maker, checker, utr, funding_completed_at, listing_status }` — **do not widen** the existing
  `GET /listings/{id}/disbursement` (frozen); add the richer read alongside.
- **Source:** `cash_payout_instruction` (+ JOIN `deal_listing`), `kind='disbursement'`. **Migration:** none.
- **Effort:** M.

### BE-8 — `GET /listings/{id}/distribution/investors` + reconciliation read · P1 · unblocks G6 / S7
- **Routes:** `GET /listings/{id}/distribution/investors` → `[{ investor_id, gross_paise, tds_amount_paise, fee_paise, net_paise, utr }]` (the per-investor breakdown frozen at distribution draft); and, if a reconciliation
  ledger is exposed, `GET /listings/{id}/reconciliation` → the `cash_recon_ledger` view.
- **Source:** the per-investor distribution rows (source table from `distribution/draft`'s writer),
  `cash_recon_ledger`. **Migration:** none. **Effort:** M.

### BE-9 — `GET /investor-invites` (list) · P1 · unblocks G7 / S8
- **Route:** `GET /investor-invites?status=` → `[{ invite_id, status, issued_by, expiry_at, consumed_at }]` from `inv_invite`.
- **Note:** emails/phones are stored **hashed** (`email_hash`/`phone_hash`) — the list can't return plaintext
  email; return the hash/last-4 or omit, per the backend's PII rule. The UI's `email_display`/`justification`
  are UI-only and must not be expected from this endpoint. **Migration:** none. **Effort:** S.

### BE-10 — `GET /listings/{id}/detail` (rich display) · P1 · unblocks G10 / S12
- **Route:** a **new** detail read (do not widen the frozen `GET /listings/{id}`): `{ listing_id, status, funding_target, committed_total, va_id, va_number, va_ifsc, pricing_snapshot:{rate_bps,fee_bps,snapshot_at}, invoice:{invoice_number,face_value_paise,tenor_days,invoice_date,due_date,irn}, buyer:{…}, supplier:{…} }`.
- **Source:** JOIN `deal_listing` + snapshot columns + `cash_virtual_account` + counterparty tables.
- **Scoping caveat:** when this feeds the **investor** S12, ownership + KYC'd-investor gating apply — that
  variant belongs to **BE-14 / M10-full**. The admin-facing detail is fine now. **Migration:** none. **Effort:** M.

### BE-11 — `GET /suppliers/{id}/listings` (tracker) · P1 · unblocks G12 / S14 (admin view)
- **Route:** `GET /suppliers/{id}/listings` → per-supplier invoice/listing rows for the tracker.
- **Source:** `SELECT … FROM deal_listing WHERE supplier_id=?`. **Migration:** none. **Effort:** S.
- **Note:** the *supplier-facing* (supplier-login) version is **not in scope** — suppliers have no login
  (V3 schema; USE_CASES §"No self-service"). This endpoint is the **admin** view of a supplier's listings.

### BE-12 — Dashboard: work-queues + stats · P1 · unblocks G1 / S2
- **Routes:** `GET /admin/work-queues?role=` → per-role pending items (composed from the aggregates' statuses);
  `GET /admin/stats` → `{ active_listings, total_deployed_paise, investors_active, suppliers_active, pending_disbursements }`.
- **Design-fit:** compute from write tables now (COUNT/status filters); the queue is *"a projection"*
  eventually (DECISION_LOG) — do **not** build a projection table for pilot. **Migration:** none. **Effort:** M.

### BE-13 — Audit list · P1 · **M17 Auditor (BC13)**
- **Route:** `GET /audit/events?entity_type=&from=&to=&sensitivity=` → the S9 log, from `sys_audit_event`.
- **Design-fit:** this is the already-planned **M17** module (ROADMAP §5). Build it there, in M17's read-only,
  regulator-access scope; the audit-as-projection optimization is noted for later. **Migration:** none (reads
  `sys_audit_event`). **Effort:** M (within M17).

### BE-14 — Investor portal reads · P2 · **M10-full (investor portal)**
- **Scope:** `GET /listings?status=live` (investor-visible marketplace, G9/S11) and
  `GET /investors/{id}/subscriptions` + summary (G11/S13 portfolio).
- **Design constraint (why deferred):** these are the first **ownership-scoped** reads — an investor may see
  only live listings and only *their own* subscriptions — and the invoice-PDF download must enforce the
  KYC'd-investor gate (API_CATALOGUE §Invoice artifacts). Build **with M10-full**, together with the read
  scoping/authz that milestone introduces. Do not ship as ungated admin reads. **Effort:** L (with M10-full).

### BE-15 — Buyer portal + ack-user login + self-ack · P2 · **WS-2**
- **Scope:** the **passwordless email+OTP** ack-user login (DL-021 — a *new* auth path, not
  `/auth/login/password`); buyer-ack-user-scoped reads `GET /buyer/invoices`, `GET /buyer/payment-instruction`,
  `GET /listings/{id}/noa`; and a buyer **self-ack** command `POST /listings/{id}/buyer-ack` (ack-user bearer),
  complementing the admin-captured `record-buyer-ack`.
- **Design constraint (why deferred):** the ack-user login flow is **WS-2**, explicitly deferred (the OTP
  per-invoice ack "needs the buyer ack-user login flow, itself deferred WS-2"). This is real auth surface, not
  a read — build it with WS-2. **Effort:** L (with WS-2).

### BE-16 — CORS (prod only) · P2 · infra
- **Dev:** no change — the UI uses a Vite dev proxy, so the browser stays same-origin; `SecurityConfig` keeps
  `.cors()` disabled.
- **Prod:** if the UI is served cross-origin, either front both with one reverse proxy (no CORS) **or** enable
  `http.cors(...)` + a `CorsConfigurationSource` allowlisting the UI origin. Deploy-time decision.

---

## 3. Migration & test impact

- **Migrations:** **none** for BE-1…BE-13 (pure reads over existing write tables — the established pattern).
  BE-15's ack-user login is auth surface (WS-2), not a read migration. Any item that appears to need DDL is
  out of scope for this spec — raise it separately (e.g. pricing-band supersession DL-BE-060).
- **Tests:** the existing suite (**359 green**) must stay green untouched — no write-side code changes here.
  Add integration tests for each new read in the existing style (real-Postgres, dev-seed-driven, assert the
  proposed keys). A read endpoint must not alter or depend on the frozen command/by-id contract.
- **Contract-freeze note (ROADMAP Phase A):** these additive reads extend the frozen contract; they do not
  reopen it. Fold the new read shapes into `API_CATALOGUE.md` as they land so the UI adheres.

---

## 4. Cross-reference — backend item ↔ UI

| BE | Endpoint(s) | UI gap | Screen | UI Part B item | Milestone |
|---|---|---|---|---|---|
| BE-1 | `GET /auth/session` | (role discovery) | all | B2 / Step 6.5 | additive |
| BE-2 | `GET /{suppliers,investors}/{id}/kyc-file` | (A7.1) | S3/S10 | B4 / Step 4.3 | additive |
| BE-3 | (doc: MFA-fresh = all admin cmds) | (A7.2) | all | Step 6.4 | — |
| BE-4 | `GET /suppliers` | G2 | S3 | B5.2 | additive |
| BE-5 | `GET /buyers`, `…/pricing-bands` | G3 | S4 | B5.3 | additive |
| BE-6 | `GET /listings`, `…/ops-checks` | G4 | S5 | B5.4 | additive |
| BE-7 | disbursement queue + `…/disbursement/detail` | G5 | S6 | B5.5 | additive |
| BE-8 | `…/distribution/investors`, `…/reconciliation` | G6 | S7 | B5.6 | additive |
| BE-9 | `GET /investor-invites` | G7 | S8 | B5.7 | additive |
| BE-10 | `GET /listings/{id}/detail` | G10 | S12 | B5.9 | additive (investor variant → M10-full) |
| BE-11 | `GET /suppliers/{id}/listings` | G12 | S14 | B5.11 | additive (admin view) |
| BE-12 | `GET /admin/work-queues`, `/admin/stats` | G1 | S2 | (S2) | additive |
| BE-13 | `GET /audit/events` | G8 | S9 | (S9) | **M17** |
| BE-14 | investor marketplace + portfolio reads | G9/G11 | S11/S13 | B5.9/B5.10 | **M10-full** |
| BE-15 | ack login + buyer reads + self-ack | G13 | S15 | B5.12 | **WS-2** |
| BE-16 | CORS | — | (prod) | B0 (proxy in dev) | infra |

---

## 5. What this spec deliberately does NOT do (design-preservation checklist)

- ❌ Does not change any command handler, the `CommandResponse` envelope, or any existing by-id `GET`
  (frozen contract + command-refresh reads stay intact — richer reads are always *new* routes).
- ❌ Does not add read-side role/ownership gating for admin reads (deferred sensitive-read-gating control);
  the only scoped reads are the portal reads, gated to M10-full/WS-2.
- ❌ Does not pull the investor portal (M10-full) or buyer ack-login (WS-2) forward — it slots their read
  surface into those milestones with the scoping they require.
- ❌ Does not introduce JPA, projection tables, or a pagination framework (all reads = `JdbcTemplate` over
  existing tables; projections remain a post-pilot optimization).
- ❌ Does not implement or imply any deferred *control* (four-eyes, pricing re-pricing, suspend/blacklist,
  agency-consent enforcement, encryption-at-rest, token hardening).
- ❌ Requires no Flyway migration for the P0/P1 read endpoints.

**Net effect:** the frontend can drive the live API for the entire admin command spine + all reads it needs,
with the backend changing only by *adding* read endpoints — precisely the additive, non-conflicting path the
ROADMAP prescribes.
