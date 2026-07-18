# M10-D · Investor self-login (read-only portal) — **BE-17 + BE-14 reads**

> **Module spec — `/specify` output** for **Phase A** of `docs/INVESTOR_SELF_LOGIN_WORKORDER.md`.
> The "investor self-service login + portal" slice that **M10-full deferred** (see `M10-investor-full.md` §9,
> gap #1). Phase A is **read-only**: an investor logs in through the *existing* password+OTP flow and sees a
> live marketplace + their own portfolio; **no new write paths** (investors hold no roles → writes already
> reject). Phase B (`M11-…`, BE-18 — passwordless login + investor self-commit) is a separate spec.
> Spec before code; **invariant test before rule.** Umbrella decision: **DL-BE-084**; sub-items claim `DL-BE-085+`.

| | |
|---|---|
| **Module** | M10-D — Investor self-login, read-only portal (BC7 identity + BC9 listing + BC2 subscription reads) |
| **Tracker IDs** | **BE-17** (investor auth) · **BE-14** (investor marketplace + portfolio reads, pulled forward) |
| **Milestone label** | M10-full portal reads (tracker construct; build plan has plain M10/M11) |
| **Predecessor** | M10-full ([[DL-BE-044]]) — onboarding to `active`, admin-on-behalf; login was deferred |
| **Tier** | Light (read + auth-config slice; the write-side leash is *inherited*, not built) |
| **Status** | ✅ Done (2026-07-18) — 427 tests green, DL-BE-084, BE-14/BE-17 shipped |
| **Owner** | Amit + Claude |
| **Created** | 2026-07-17 |

---

## DoR decisions (settled at `/clarify`, 2026-07-17)

1. **Investor credential = dev/pilot password only; production login deferred to Phase B** *(OQ-1, reconfirmed at
   `/plan`)*. The `/clarify` answer initially picked "set-password in onboarding," but `/plan` found it has **no cheap
   path**: `inv_invite` carries **no secret token** (only email/phone hashes; today an *admin* re-types them) and
   there is **no session-less command substrate** (`CommandGateway` requires a session — `actorId =
   session.identityId()`). Letting an investor set *their own* secret therefore needs an OTP-verify step first — which
   is ~80% of Phase B's passwordless machinery. So (2026-07-17 decision) **Phase A ships only the dev seeder password**
   (`DevDataSeeder` → `auth.setPassword` for `investor@dev.local`); **real investors self-login lands in Phase B**
   (passwordless email+OTP, BE-18). Phase A adds **no new auth write path** — RO-1 read-only-pure holds.
2. **`investor_id` resolved per-request** *(OQ-2)*. Each scoped read calls
   `investorIdForIdentity(session.identityId())`; no `TenantClaims` change, no login-path change.
   `TenantClaims.forInvestor` stays unused for now.
3. **Cross-investor read → `403 Forbidden`** *(OQ-3)*. Explicit "not authorised" (accepts that it confirms the id
   exists). Applied uniformly to all OWN-1 reads.
4. **Portfolio shape fixed to the S13 contract** *(OQ-4)*. Physical table is **`sub_subscription`** (not
   `inv_subscription`). Row + summary fields are the mock S13 contract (§4); names + due-date come from
   `sub_subscription → deal_listing → deal_invoice` joins (Gap G10). **No** committed/confirmed split, IRR, XIRR, or
   units — those are not in the S13 UI and are out of scope.

---

## 0. Why this is small (verified against code, 2026-07-17)

Phase A rides on machinery that already exists and is **kind-agnostic**:

- `AuthService.authenticatePassword` looks up `auth_identity` by email only (no kind filter) →
  an investor with a password logs in through the **existing** `/auth/login/password → verify-otp` flow.
- `AuthService.setPassword(identityId, raw)` works for any identity (**Argon2id**, one active password;
  revokes prior). `auth_credential` already supports investor password creds — **no schema change**.
- Reads are authenticated-only (`SecurityConfig`: `anyRequest().authenticated()`); writes are role-gated
  *deeper* at `CommandGateway` (`execute(req, requiredRoles, …)` rejects a no-role caller with `role_not_held`).
  → **"investor = read-only" falls out of the design for free** (A4 is a regression test, not new code).
- `inv_account(investor_id PK, identity_id UNIQUE)` is the ownership join; `idx_inv_account_identity` makes
  `identity_id → investor_id` cheap.

## 1. Scope

**Owns (this slice is responsible for):**
- **BE-17 auth surface:** investor credential issuance for **dev only** (`DevDataSeeder.seedActiveInvestor`
  → `auth.setPassword` for `investor@dev.local`); the `identity_id → investor_id` **resolver** (built once, reused
  by A2/A3/A5 and Phase B); the `/auth/session` `investor_id` field (A2). *(Production investor login → Phase B.)*
- **BE-14 read surface, ownership-scoped:** the investor variant of `GET /listings?status=live` (A3-i,
  *add scoping to an existing endpoint*); the **new** `GET /investors/{id}/subscriptions` + summary (A3-ii);
  the **KYC-approved** gate on the investor invoice-PDF download (A5).
- The read-only **regression lock** (A4): an investor bearer cannot run any write command.

**Does NOT own (deferred / other module — with where it lands):**
- **Production investor login** (real investors authenticating) → **Phase B / M11-… / BE-18** passwordless email+OTP.
  Phase A's only login is the **dev seeder password** (DoR #1). No production set-password/credential surface here.
- **Investor self-commit** → **Phase B / BE-18**.
- **Investor password *management*** (reset / rotate / lockout / Google OAuth) → later slice, if password login is
  ever adopted for production (Phase B makes it moot).
- Supplier self-login (ops-on-behalf, BE-11), buyer ack-user login (BE-15 / WS-2), auditor login (M17 / BE-13).
- The mock's composite `ops-treasury` persona (mock-only; live keeps maker≠checker).

## 2. Upstream dependencies
- **M3a/M3b auth + sessions + OTP** — `authenticatePassword`, `setPassword`, `issueLoginOtp`, `verifyOtp`,
  `establishSession`, `/auth/session`. Done.
- **M10-full investor onboarding (BC7)** — `inv_account` with an active investor + `identity_id`. Done.
- **M9 listing (BC9)** — `ListingController.list` (`GET /listings`, currently admin-scoped). Done.
- **M19 invoice artifacts** — `InvoiceDocumentService.download` with a **documented deferred `InvestorQueryPort`
  KYC gate** (the exact seam A5 fills). Done (gate deferred).
- **BC2 subscription read** — the portfolio list has no existing endpoint; native SQL over **`sub_subscription`**
  (+ `deal_listing → deal_invoice` join for buyer/supplier names + due-date). New in A3-ii.

## 3. Invariants & rules
Each rule cites its corpus source. **Persistence idiom: raw `JdbcTemplate` native SQL — this codebase has zero
JPA `@Entity`/repositories.**

- **OWN-1 — Ownership scoping (per-investor resources).** For reads keyed to an investor — the **portfolio**
  (`/investors/{id}/subscriptions`) and the **KYC-gated document** — a `kind='investor'` bearer may access **only its
  own** `investor_id` (server-resolved per-request from the session identity — **never** a client-supplied id). A
  cross-investor read is rejected **403 Forbidden** (DoR #3). Admin bearers keep the un-scoped view. The
  **marketplace is *not* per-investor-owned** (a listing has no investor owner) — it is scoped by OWN-2 only.
  _(ref: BE-14 "first ownership-scoped reads"; C-authz)_
- **OWN-2 — Live-only marketplace.** The investor variant of `GET /listings` returns **only `status='live'`**
  listings, regardless of query params. Admin variant unchanged. _(ref: G9 / S11, BE-14)_
- **RO-1 — Read-only by inheritance.** An investor holds no admin roles, so every state-changing command is
  rejected at `CommandGateway` (`role_not_held`). Phase A adds **no** new write path. This is the leash — proven by
  test, not built. _(ref: CLAUDE.md non-negotiables; `CommandGateway`)_
- **KYC-1 — KYC-gated document download.** An investor may download a listing's invoice PDF **only if
  KYC-approved** (`inv_account` reached `kyc_approved`+). Enforced by implementing the deferred `InvestorQueryPort`
  gate in `InvoiceDocumentService`. Admin path unchanged. _(ref: BE-14, API_CATALOGUE §Invoice artifacts, C-authz)_
- **SES-1 — Session carries `investor_id`.** `/auth/session` returns `investor_id` (nullable) — non-null only for
  `kind='investor'`, resolved server-side. Additive, non-breaking (admins/others get `null`). _(ref: BE-1 extension)_

## 4. API / type surface (intent-shaped) — new/changed only

| Endpoint | Caller | Change |
|---|---|---|
| `GET /auth/session` | any bearer | **+`investor_id`** (nullable); non-null for `kind='investor'` (per-request resolver). Additive. |
| `GET /listings?status=live` | investor bearer | **add scoping branch:** investor → only `live` (OWN-2); admin → unchanged (existing `ListingController.list`, BE-6). |
| `GET /investors/{id}/subscriptions` (+ summary) | investor bearer | **NEW.** Portfolio list + summary over **`sub_subscription`**; `id` must equal caller's own `investor_id` (OWN-1) else 403. Admin → un-scoped. |
| `GET /listings/{lid}/invoice-documents/{did}/content` | investor bearer | **add KYC-1 gate** via `InvestorQueryPort` (existing endpoint; admin path unchanged). |

**Portfolio response shape (S13 contract, DoR #4) — `GET /investors/{id}/subscriptions`:**
- **List rows** (over `sub_subscription`, joined to `deal_listing → deal_invoice`): `subscription_id`, `listing_id`,
  `amount` (paise), `status` (`sub_subscription_status`), `buyer_name` + `supplier_name` + `due_date` (**joined**,
  Gap G10), `distribution_outcome` `{gross, tds, fee, net}` paise (nullable, JSONB pass-through). **Do NOT** expose
  `wallet_attribution` (dormant, Phase 2).
- **Summary tiles** (4): `total_deployed_paise` (Σ `amount` of **active** positions — status ∉ {`closed`,
  `cancelled_by_investor`, `refunded`, `loss_realised`}), `total_returned_paise` (Σ `distribution_outcome.net`),
  `active_positions` (count active), `matured_positions` (count with a `distribution_outcome`).

- **Commands (state-changing):** **none in Phase A.** The dev credential is a `DevDataSeeder` call to `setPassword`,
  not a command. (Production investor login is Phase B.) → RO-1 read-only-pure holds.
- **Resolver (shared foundation):** `investorIdForIdentity(UUID identityId) → Optional<UUID>` — one native-SQL
  method, `SELECT investor_id FROM inv_account WHERE identity_id = ?`. Reused by A2, A3, A5, and Phase B B2. A
  near-identical join already exists in `DevController` — **consolidate, do not re-query ad hoc.**

## 5. Five non-negotiables — applicability
Phase A adds **no new command** (production login deferred to Phase B), so per-command controls are **N/A** — this is
the read-only leash, stated explicitly per the template.

| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | **no** | No new command. |
| 2 | MFA-fresh | **no** (reads) | Login already MFA-gates via OTP; reads need only an authenticated bearer. |
| 3 | SoD-checked | **no** | No admin actor, no command. |
| 4 | Idempotent on `command_id` | **no** | No command. |
| 5 | Audit-logged envelope | **no** | No state change. (Reads are not audited today; keep consistent.) |

**Authz is the real control here** and it is *inherited*: ownership scoping (OWN-1) + read-only inheritance (RO-1)
+ KYC gate (KYC-1). No schema migration expected — **if `/plan` finds one is needed, stop and flag it.**

## 6. Events
- **Publishes:** none. **Subscribes:** none. (Pure read + auth-config slice.)

## 7. Test scenarios (write these first) — `InvestorSelfLoginTest extends AbstractEdgeHttpTest`
Reuse the MockMvc harness. Bearers come from `bearerFor(...)` (real password→OTP→bearer, OTP read from the in-process
`StubNotifier`, **not** `/dev/last-otp`). A new `seedActiveInvestorWithLogin()` helper (T1 below) bridges the current
gap — `seedLoginIdentity("investor")` gives a bearer but no `inv_account`; the per-test `seedActiveInvestor()` gives
an `inv_account` but no password. Seed **two** active-with-login investors for the cross-investor 403.

- [ ] **Login happy path:** an active investor with a password logs in (password→OTP→bearer via `bearerFor`).
- [ ] **Session shape (SES-1):** that bearer → `/auth/session` returns `kind:"investor"`, `roles:[]`,
      `investor_id:<uuid>` (matches the seeded `inv_account`).
- [ ] **Marketplace scoping (OWN-2):** investor bearer `GET /listings?status=live` → only `live`; a non-live
      listing is absent even if requested.
- [ ] **Portfolio own (OWN-1):** investor bearer `GET /investors/{ownId}/subscriptions` → 200; rows carry
      `subscription_id/listing_id/amount/status/buyer_name/supplier_name/due_date/distribution_outcome` and the 4
      summary tiles; `wallet_attribution` is **absent**.
- [ ] **Portfolio cross-investor (OWN-1):** investor A → `GET /investors/{B_id}/subscriptions` → **403** (DoR #3).
- [ ] **Read-only regression (RO-1):** investor bearer → `POST …/subscriptions/commit` (and one onboarding command)
      → rejected (`role_not_held`); **no** command-log row / envelope written.
- [ ] **KYC gate (KYC-1):** KYC-approved investor downloads the invoice PDF → 200; a not-yet-`kyc_approved`
      investor → 403; **admin path unchanged** (regression).
- [ ] **Admin no-regression:** admin bearer keeps the un-scoped listings + subscription views.

## 8. Definition of Done (light tier — gate F)
- [x] §7 tests green; whole suite green — **427** (was 413 at BE-12; +14 incl. the fail-closed regression test).
- [x] `/code-review` (high) on the diff; 1 authz finding fixed (portfolio fail-closed), 4 minor findings
      dispositioned in DL-BE-084 (1 latent/documented, 3 accepted).
- [x] No new migration (confirmed against V1–V6).
- [x] `DL-BE-084` umbrella written (OQ-1 defer-to-Phase-B, `InvestorQueryPort`, the 403 model, findings).
- [x] `PROJECT_TRACKER.md`: BE-17 registered, BE-14 flipped, S11/S13 cells + next-actions updated;
      `UI_INTEGRATION_BACKEND_SPEC.md`: BE-14 marked shipped + BE-17 registered.
- [x] Mock-side work-order written (`../fintech-patform-mock/docs/UI_WORKORDER.md`) — do not edit mock code from here.
- [x] This spec flipped to **Status: Done**.

---

## 9. Plan (`/plan` — code-anchored, verified 2026-07-17)

All touchpoints use the canonical `@AuthenticationPrincipal AuthSession session` + `JdbcTemplate` idiom (no JPA, no
custom resolver). **No migration** — confirmed (the set-password production path that *would* have needed one is
deferred to Phase B, DoR #1). Build order — each sub-item: red test → green → `/code-review` → DL entry.

**Shared foundation (build first):**
- **P0 · `investorIdForIdentity` resolver.** New method (natural home: a small `InvestorQueries`/lookup helper or on
  `InvestorService`) — `SELECT investor_id FROM inv_account WHERE identity_id = ?` → `Optional<UUID>` (`identity_id`
  is UNIQUE + indexed). Replace the ad-hoc join in `DevController` with a call to it. Reused by P1/P2/P3/P4 + Phase B.

**A2 — session:**
- **P1 · `/auth/session` +`investor_id`.** `SessionController.session` (`auth/SessionController.java:41-70`): after the
  `admin_user_id` block (line ~55), add a nullable read via P0's resolver and `body.put("investor_id", …)`. Additive.

**A3 — reads (ownership-scoped):**
- **P2 · marketplace scoping.** `ListingController.list` (`listing/ListingController.java:182`) already receives
  `session` but ignores it. Resolve caller `kind` (`SELECT kind::text FROM auth_identity WHERE identity_id=?`, the
  shape `SessionController` uses); when `investor`: **force `status='live'`** (ignore/override any `status` param).
  The marketplace is a **browse** surface — return **all** live listings (**no** per-investor ownership filter; a
  listing has no investor owner). Admin/other kinds: unchanged.
- **P3 · portfolio read (NEW).** New `@GetMapping("/investors/{id}/subscriptions")` in `InvestorController` (no
  class-level `@RequestMapping` — full path on the method; mirror the existing `get`/`invites` handlers, uses the
  injected `jdbc`). OWN-1: if `id ≠` caller's resolved `investor_id` → **403**. Rows over `sub_subscription` joined
  `deal_listing → deal_invoice` for `buyer_name`/`supplier_name`/`due_date` (Gap G10); pass `distribution_outcome`
  JSONB through; **omit `wallet_attribution`**. Summary = the 4 S13 tiles (§4). Admin caller → un-scoped.

**A5 — KYC download gate:**
- **P4 · KYC gate + `InvestorQueryPort`.** `InvestorQueryPort` does **not exist** — create it as a real port
  (follow the existing `document.DocumentPort` pattern) with e.g. `isDownloadEligible(identityId, listingId)` /
  `kycStatusOf(identityId)`. Thread `session` from `InvoiceDocumentController.content` (`listing/InvoiceDocumentController.java:73`,
  already in scope) into `InvoiceDocumentService.download` (`:138`); at the `DOWNLOADABLE_LISTING_STATUSES` check
  (`:140`) add: if caller is an investor, require `inv_account.status ∈ {kyc_approved, mia_signed, active}` **or**
  `kyc_approved_at IS NOT NULL` (durable signal) → else 403. Admin path unchanged.

**A4 — regression lock (test-only):**
- **P5 · read-only inheritance.** No code. Test that an investor bearer → `POST …/subscriptions/commit` + one
  onboarding command → `role_not_held`, no command-log row.

**A1 — dev credential:**
- **P6 · dev seeder.** `DevDataSeeder.seedActiveInvestor` (`dev/DevDataSeeder.java:121`): add
  `auth.setPassword(identityId, PASSWORD)` (mirrors `seedAdmin:83`) so `investor@dev.local` can log in. Dev-profile only.

### Residual `/plan` notes & flags
- **`sub_subscription` has no standalone `investor_id` index** (only `UNIQUE(listing_id, investor_id)`). P2/P3 filter
  by `investor_id`. At pilot scale a seq scan is **correctness-safe**; an index is a perf nicety. **Decision: do NOT
  add it in Phase A** (keeps the slice migration-free per the guardrail) — note it as a documented perf follow-up.
  If the portfolio/marketplace read is measurably slow, add `idx_sub_subscription_investor` then.
- **G10 name joins:** `buyer_name`/`supplier_name` live on the buyer/supplier aggregates via `deal_listing`;
  `due_date` on `deal_invoice`. Single query with joins (no N+1) — same shape as `ListingController.list`'s existing
  `deal_listing JOIN deal_invoice`.
- **No migration overall** — reads are native SQL over existing tables; the only credential write is the dev seeder.
  Confirmed against V1–V6. If `/tasks`/`/implement` surfaces an unavoidable migration, **stop and flag**.

---

## 10. Tasks (`/tasks` — ordered, red-test-first, harness-anchored 2026-07-17)

Discipline: for each feature task write the failing test **first** (run → confirm RED), then the green step, then
move on. Two new test classes, both `extends AbstractEdgeHttpTest` (MockMvc + Testcontainers): `investor/
InvestorSelfLoginTest` and `listing/InvoiceDownloadKycGateTest`. Bearers via `bearerFor(...)`; JSON via `node(res)` +
JsonPath; DB checks via `jdbc.queryForObject(... ::text ...)`. Copy the local `withEnvelope`/`send`/`versionOf`
pattern from `InvestorOnboardingTest` where a command POST is needed (T5 only).

**T1 · Test infra — `seedActiveInvestorWithLogin()` (enabling; no assertion of its own).**
Add to `AbstractEdgeHttpTest`: seed an `auth_identity` (`kind='investor'`, `status='active'`) + `inv_invite` + an
`inv_account` at a given status (default `active`), then `auth.setPassword(identityId, pw)`; return a record carrying
`{investorId, Seeded(identityId,email,password)}`. This bridges the harness gap (bearer **and** `inv_account`). Also
add small seed helpers reused below: `seedLiveListing()` / `seedListing(status)` (mirror `ListingReadTest`'s
`seedInvoice`+`seedListing`) and `seedSubscription(investorId, listingId, amountPaise, status)` (insert
`sub_subscription`). Validated implicitly by first use in T2.

**T2 · A2 session `investor_id` (P0 + P1).**
- **RED** (`InvestorSelfLoginTest`): `session_returns_investor_id_and_empty_roles_for_investor` — seed via T1, get
  bearer, `GET /auth/session` → `kind:"investor"`, `roles:[]` (JsonPath `$.roles` empty), `investor_id` == seeded id.
  `admin_bearer_session_has_null_investor_id` — admin (`seedAdminWithRoles`) → `$.investor_id` is null.
- **GREEN**: add `investorIdForIdentity` resolver (P0); wire `investor_id` into `SessionController` (P1); repoint
  `DevController`'s ad-hoc join at the resolver.

**T3 · A3-i marketplace scoping (P2).**
- **RED** (`InvestorSelfLoginTest`): `investor_marketplace_returns_all_live_only` — seed 2 `live` + 1 non-live
  (`fully_funded`) listing (T1 helpers); investor bearer `GET /listings` → exactly the 2 live rows; the non-live one
  absent even when `?status=fully_funded` is passed (param overridden). `admin_marketplace_unscoped` — admin
  `GET /listings?status=fully_funded` → sees the non-live one (unchanged).
- **GREEN**: in `ListingController.list`, resolve caller kind; investor → force `status='live'`; else unchanged.

**T4 · A3-ii portfolio + summary (P3).**
- **RED** (`InvestorSelfLoginTest`): `investor_reads_own_portfolio_with_summary` — seed investor + 2
  `sub_subscription` rows (one active status, one with a `distribution_outcome`) on live listings; `GET
  /investors/{ownId}/subscriptions` → 200; assert row fields present
  (`subscription_id/listing_id/amount/status/buyer_name/supplier_name/due_date/distribution_outcome`) and
  **`wallet_attribution` absent**; assert the 4 summary tiles (`total_deployed_paise`, `total_returned_paise`,
  `active_positions`, `matured_positions`). `investor_cannot_read_another_investors_portfolio` — investor A bearer →
  `GET /investors/{B_id}/subscriptions` → **403**. `admin_reads_any_portfolio` — admin → 200 un-scoped.
- **GREEN**: new `@GetMapping("/investors/{id}/subscriptions")` in `InvestorController`; 403 on ownership mismatch
  (admin exempt); native SQL over `sub_subscription JOIN deal_listing JOIN deal_invoice` (single query, G10 names +
  due-date); summary aggregation query. Omit `wallet_attribution`.

**T5 · A4 read-only regression lock (P5) — locks RO-1, expected GREEN on first run.**
- **RED/GREEN** (`InvestorSelfLoginTest`): `investor_bearer_cannot_commit` — investor bearer POSTs the commit command
  (`withEnvelope`) → **403** `role_not_held` (JsonPath `$.error_code`); `investor_bearer_cannot_run_onboarding_command`
  — e.g. `POST /investors/{id}/submit-kyc` → 403. Assert **no** state change / no new command-log row via `jdbc`.
  (Confirm the exact commit route in `SubscriptionController` at implement time.) No production code — this test
  *locks in* the inherited `CommandGateway` behaviour.

**T6 · A5 KYC download gate + `InvestorQueryPort` (P4).**
- **RED** (`listing/InvoiceDownloadKycGateTest`): seed a listing in a downloadable status with an attached
  `deal_invoice_document`; `kyc_approved_investor_can_download` — investor whose `inv_account.status='active'` (or
  `kyc_approved_at` set) → `GET …/content` → 200 `application/pdf`. `non_kyc_investor_forbidden` — investor at
  `signed_up` → **403**. `admin_download_unchanged` — admin bearer → 200.
- **GREEN**: create `InvestorQueryPort` (real interface, `DocumentPort` style) + adapter; thread `session` from
  `InvoiceDocumentController.content` into `download`; add the KYC check at the `DOWNLOADABLE_LISTING_STATUSES` gate.

**T7 · A1 dev seeder (P6) — not in the integration suite (dev profile).**
- **GREEN**: `DevDataSeeder.seedActiveInvestor` += `auth.setPassword(identityId, PASSWORD)`. Validate via the manual
  runbook (`docs/MANUAL_TESTING.md`): `investor@dev.local` / `DevPass123!` → OTP → bearer → `/auth/session` shows
  `investor_id`. Update the runbook if the investor login step isn't already there.

**T8 · DoD wrap (gate F).**
Run the full suite (record count); `/code-review` the diff, fix findings; write `DL-BE-084` umbrella + entries
(resolver placement; the OQ-1 defer-to-Phase-B decision; 403 choice; the deferred `sub_subscription` index);
update `PROJECT_TRACKER.md` (register **BE-17**, flip **BE-14**, S11/S13 cells) and `UI_INTEGRATION_BACKEND_SPEC.md`;
write the mock-side work-order note (per [[mock-ui-changes-via-workorder]] — do not edit mock code from here); flip
this spec to **Status: Done**.

### Task order & parallelism
Strict dependency: **T1 → T2** (resolver unblocks everything). After T2, **T3 / T4 / T6 are independent** (different
files — ListingController / InvestorController / InvoiceDocument) and could be done in any order or delegated in
parallel. **T5** needs only a working investor bearer (T1). **T7** is standalone. **T8** last. Per CLAUDE.md
*"one module in flight"* — this is all one module (M10-D); the parallelism is within it.
