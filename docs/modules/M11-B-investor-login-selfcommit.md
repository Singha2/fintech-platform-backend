# M11-B · Investor passwordless login + investor self-commit (Phase B / BE-18)

> **Module spec — `/specify` + `/clarify` (DoR) output** for `docs/BE18_INVESTOR_LOGIN_SELFCOMMIT_BRIEF.md`.
> The **write** counterpart to **M10-D** (`M10-D-investor-self-login.md`, the read-only portal): M10-D lets an
> investor *log in with the dev password and read* their own portfolio; **Phase B** lets a **real** investor
> **log in passwordless (email+OTP)** and **place their own subscription**. This turns the read-only façade into
> a genuinely self-service investor portal and removes ops from the money-in path.
> Umbrella predecessor decision: **DL-BE-084 (§Phase B)**; this slice claims **DL-BE-088**.
> **✅ Shipped (2026-07-18)** — full loop complete (`/specify → /clarify → DoR → /plan → /tasks → /implement →
> DoD`). 448 tests green; `DL-BE-088`. Spec before code; invariant test before rule.

| | |
|---|---|
| **Module** | M11-B — Investor passwordless login (BC7 identity/auth) + investor self-commit (BC2 subscription) |
| **Slice** | The Phase-B write cut M10-D deferred — passwordless login **+** self-commit **+** denied-read audit. A narrow slice, **not** the full BC2 subscription module (`M11-subscription-full.md`). |
| **Tracker IDs** | **BE-18** (Track B #7) |
| **Tier** | Light-to-medium (adds one command path + one login entry; no new tables — see §0) |
| **Status** | ✅ **Done (2026-07-18)** — 448 tests green; DL-BE-088 |
| **Owner** | Amit + Claude |
| **Created** | 2026-07-18 |

---

## DoR decisions (settled at `/clarify`, 2026-07-18)

These resolve the five ambiguities raised in the BE-18 assessment. Items marked **(confirm/revise)** are
reversible product/scope calls settled with a sensible default — override any before `/plan`.

1. **DoR-1 — Enumeration-safety mechanics (Part 1).** `POST /auth/login/investor/request-otp {email}` **always**
   returns `200 { challenge_id }`, shape-identical, whether or not the email maps to an eligible investor.
   - **Eligible** (an `active` investor, DoR-2) → issue a real OTP via the existing `AuthService.issueLoginOtp`
     (`AuthService.java:129-155`) and return its real `challenge_id`.
   - **Not eligible** (unknown email, non-investor kind, or non-`active`) → return a **synthetic opaque
     `challenge_id`** (a fresh random UUID) with **no `auth_otp_challenge` row** and **no OTP sent**. A later
     `verify-otp` on that id fails through the *same* generic `LoginFailedException` as a wrong code — no signal
     that distinguishes "email isn't an investor" from "wrong code."
   - **Timing:** run the eligibility lookup unconditionally (no early-return fast path) so latency doesn't leak
     membership. _Rationale: satisfies DoD #1 "indistinguishable response"; reuses the existing verify-otp
     failure path; never emits an OTP to a non-eligible address._
   - _Confirm at `/plan`:_ that `AuthService.verifyOtp` (`:163-209`) treats "challenge not found" identically to
     "wrong code" (both → not-verified → `LoginFailedException`). If it 404s on a missing challenge, normalise it.

2. **DoR-2 — Login/commit eligibility gate = `inv_account.status = 'active'` only.** Only a **currently `active`**
   investor may (a) obtain a passwordless session and (b) self-commit. Mid-onboarding
   (`signed_up`/`kyc_submitted`/`kyc_approved`/`mia_signed`) and post-active (`suspended`/`exited`) are refused.
   _Rationale: `active` is the terminal onboarding state and already implies KYC-approved + MIA-signed, so login
   and self-commit share one predicate; no separate KYC sub-check needed._

3. **DoR-3 — Fail-closed on *current* status; do NOT reuse `isKycApprovedForDownload` (avoids DF-2).** Both gates
   test `status = 'active'`, **not** the laxer `… OR kyc_approved_at IS NOT NULL` clause that
   `InvestorService.isKycApprovedForDownload` (`InvestorService.java:274-281`) uses for the document-download
   gate. _Rationale: that clause is the DF-2 over-permission (a once-approved-but-later-`suspended`/`exited`
   investor still passes). Logging in or moving money must key off the **current** lifecycle state — a suspended
   investor must not._

4. **DoR-4 — Investor-self authorization is controller-routed, gateway-open (no new gateway authz mode)
   (Part 2).** `SubscriptionController.commit` selects the actor path by **session kind**:
   - **Investor session** (`investorIdForIdentity(session.identityId())` is present, `InvestorController.java:231`
     idiom) → resolve the **own** `investor_id` from the session, set `actorType='investor'`, reject a body
     `investor_id` that isn't the session's own, and call the gateway's **no-required-roles** overload
     (`CommandGateway.execute(request, handler)`, `:48-50`).
   - **Admin session** → the existing **OPS-gated ops-on-behalf** path, `investor_id` from the body (unchanged).

   The `CommandGateway` is **not** extended with a declarative "investor-self" authz mode now: the no-role
   overload + controller kind-routing + own-id derivation already enforce "an investor may commit only for
   itself," and the gateway already skips the MFA/role gates for non-admin actors (`:66,:79,:84`). **(confirm/revise)**
   — a first-class `AllowedActor` predicate in the gateway (defense-in-depth) is an additive follow-up, flagged not built.

5. **DoR-5 — `request-otp` abuse: enumeration-safety + challenge-supersession in scope; a dedicated rate-limiter
   deferred (Part 1). (confirm/revise)** In scope now: DoR-1 (no enumeration) and the **existing one-active-
   challenge-per-identity supersession** inside `issueLoginOtp` (`AuthService.java:131-132`) — an eligible email
   can't accumulate live challenges. A dedicated per-email / per-IP **rate limiter is deferred** to a platform-
   wide **auth-hardening** follow-up (new `DL-BE` when built), because: (a) there is **no existing throttle infra
   to reuse**; (b) the concern applies **equally to the already-open admin `/auth/login/password` step**, so it's
   not BE-18-specific; (c) the notifier is still a `StubNotifier` (no real SMS cost/spam yet). _Revisit before the
   Production gate, when a real notification channel lands. If you want a basic per-email cap inside this slice, say so._

**Additional decisions surfaced by `/specify`:**

6. **DoR-6 — Ops-on-behalf commit is retained (no S12 regression).** The admin OPS `subscriptions/commit` path
   stays fully supported so S12 keeps working during the mock's transition. A regression test locks it (DoD #4).

7. **DoR-7 — Fix the hard-coded audit actor type in the commit path (latent correctness).** `SubscriptionService`'s
   `actor(request)` helper hard-codes `new Actor("admin_user", …)` (`SubscriptionService.java:303-306`) for the
   secondary `Listing.FullyFunded` envelope. For an investor self-commit that would mislabel the actor. Change it
   to `request.actorType()`. This is in-scope for the slice (it's on the path the new command exercises).

8. **DoR-8 — Denied cross-tenant reads audited via a direct, non-command append (Part 3).** The two
   `cross_tenant_read` denials in `InvestorController.subscriptions` (`:234`, `:237`;
   `ForbiddenException.crossInvestorRead` / `notAuthorisedForPortfolio`) emit a lightweight audit event
   (`actor`, attempted `investor_id`, endpoint, `at`) via a **direct `AuditLog.append`** (non-state-transition,
   **no `command_id`** — the same "append outside the gateway" shape `SubscriptionService.confirmFromInflow` uses).
   Scope: **denials only** — successful reads stay unaudited (reads-unaudited otherwise holds, per M10-D DoR #3).
   Event type: `auth.CrossTenantReadDenied` (confirm the context/type name at `/plan`).

---

## 0. Why this is (mostly) additive — verified against code (2026-07-18)

The command + session + audit substrates were built kind-agnostic; Phase B **activates paths already anticipated**,
it does not restructure. **No new tables; no migration expected** (flag at `/plan` if one is unavoidable).

- **The gateway already branches on actor type.** `boolean adminActor = ADMIN_ACTOR.equals(request.actorType())`
  (`CommandGateway.java:61`); the MFA-missing check (`:66`), the MFA-freshness gate (`:79`), and the role gate
  (skipped when `requiredRoles` is empty, `:84`) **all already no-op for a non-admin actor**. Idempotency keys on
  `(actor_id, command_id)` where `actor_id = session.identityId()` (`CommandRequest.java:38`) — works for an
  investor identity.
- **The audit schema already accepts an investor actor.** `sys_audit_event.actor` is a JSONB blob with only a
  *key-presence* CHECK (`actor ? 'actor_type' AND actor ? 'actor_id' AND actor ? 'session_id'`,
  `V4__generic_acl.sql:821-822`); the blanket admin-MFA CHECK was **deliberately removed** (`:823-826`). So
  `actor_type='investor'` with a null `mfa_assertion_id` validates — **no schema change**.
- **`sys_command_log.actor_id` is a bare `UUID`, no FK** (`V4__generic_acl.sql:791-800`) — an investor's
  `identity_id` is a legal `actor_id`.
- **Login is kind-agnostic.** `issueLoginOtp(identityId)` (`AuthService.java:129`), `verifyOtp` (`:163`), and
  `SessionService.establishSession` (`:54`) never gate on admin — they resolve `kind` only for the audit label.
  A passwordless investor entry reuses them wholesale.
- **The ownership idiom already exists.** `InvestorController.subscriptions` (`:229-238`) resolves
  `investorIdForIdentity(session.identityId())` and 403s a cross-investor read — self-commit mirrors it exactly.

## 1. Scope

**Owns (this slice is responsible for):**
- **Part 1 — passwordless investor login:** a new open endpoint `POST /auth/login/investor/request-otp {email}`
  + the eligibility lookup (active investor by email via `auth_identity → inv_account`); OTP issuance reuses
  `issueLoginOtp`; session establishment reuses the existing `/auth/login/verify-otp`. Enumeration-safe (DoR-1).
- **Part 2 — investor self-commit:** the actor-kind branch in `SubscriptionController.commit`; the investor path
  through `SubscriptionService.commit` (own-`investor_id` from session, `actorType='investor'`, no-role gateway
  call); the `actor()` actor-type fix (DoR-7). Ops-on-behalf retained (DoR-6).
- **Part 3 — denied-read audit:** the two `cross_tenant_read` denial appends (DoR-8).

**Does NOT own (deferred / other module — with where it lands):**
- **Investor-driven onboarding** (sign-up → activate stays ops/compliance-driven, S10 / M10-full).
- **Any investor write other than self-commit** — cancel/refund/disbursement/distribution stay ops/treasury.
- **A dedicated `request-otp` rate limiter** → platform-wide auth-hardening follow-up (DoR-5).
- **A first-class gateway `AllowedActor` authz mode** → optional additive follow-up (DoR-4).
- **Real investor *password* management / OAuth** — moot under passwordless.
- **Buyer ack-user login (BE-15/WS-2), auditor login (BE-13/M17), supplier login** — separate slices.

## 2. Upstream dependencies (all Done)
- **M3a/M3b auth + OTP + sessions** — `authenticatePassword`, `issueLoginOtp`, `verifyOtp`, `establishSession`,
  `/auth/session`. Reused as-is.
- **M10-D investor read portal** — `investorIdForIdentity` resolver, `/auth/session` `investor_id`, the ownership
  idiom, `kind='investor'` sessions. Directly extended.
- **M4a command substrate** — `CommandGateway` (non-admin-actor paths already present).
- **M11 subscription (WS-5)** — `SubscriptionController`/`SubscriptionService.commit`, the atomic commit+FullyFunded
  flip, `sub_subscription` one-per-`(listing, investor)` unique. Extended, not rebuilt.
- **M2 audit log** — `AuditLog.append` for the denied-read event (Part 3).

## 3. Invariants & rules
Persistence idiom: raw `JdbcTemplate` native SQL (no JPA).

- **LOGIN-1 — Passwordless entry for active investors only.** `request-otp` issues an OTP only to an `active`
  (DoR-2) `kind='investor'` identity resolved by email; all other emails get an indistinguishable response and no
  OTP (DoR-1). _(ref: brief §1; DoR-1/2/3)_
- **SELF-1 — Self-commit is own-scoped, session-derived id.** An investor caller commits **only** to its own
  `investor_id`, resolved server-side from the session — **never** a client-supplied body id; a mismatched body
  `investor_id` is rejected fail-closed (mirrors OWN-1 of M10-D). _(ref: brief §2; DoR-4)_
- **SELF-2 — Eligibility enforced server-side, as clean domain errors.** Investor `active` (DoR-2); amount ≥ min
  ticket (DL-007, existing `MIN_TICKET_PAISE`); within remaining headroom (G10/L.2, existing atomic cap predicate);
  listing `live` (existing S.5). An ineligible investor → a typed domain error, **not** a generic 403. _(ref: brief §2)_
- **SELF-3 — Ops-on-behalf preserved.** An admin OPS caller may still commit on behalf with a body `investor_id`
  (DoR-6). _(ref: brief §4)_
- **AUTHZ-1 — No MFA-fresh gate for the non-admin actor** (already the gateway's behavior, `CommandGateway.java:79`);
  the five non-negotiables that **do** apply (idempotency, version, audit) hold with `actor_type='investor'`. _(ref: brief §0/§2)_
- **AUDIT-READ-1 — Denied cross-tenant reads are audited; successful reads are not.** Only the `cross_tenant_read`
  denials emit an event (DoR-8). _(ref: brief §3; M10-D DoR #3)_

## 4. API / type surface (new/changed only)

| Endpoint | Caller | Change |
|---|---|---|
| `POST /auth/login/investor/request-otp` | open (no bearer) | **NEW.** `{email}` → `200 {challenge_id}`, enumeration-safe (DoR-1). Eligible active investor → real OTP; else synthetic id, no send. |
| `POST /auth/login/verify-otp` | open | **Unchanged** — reused; establishes the `kind='investor'` session + bearer. |
| `POST /listings/{id}/subscriptions/commit` | **investor** bearer | **Investor path added.** Body `{amount_paise}` only; `investor_id` from session; a body `investor_id ≠` own → reject. `actor_type='investor'`, no MFA gate, idempotent on `(investor identity, command_id)`. |
| `POST /listings/{id}/subscriptions/commit` | admin (OPS) bearer | **Unchanged** — ops-on-behalf, body `{investor_id, amount_paise}`. |
| `GET /investors/{id}/subscriptions` | investor/admin | **No shape change**; a denied cross-tenant read now also **emits an audit event** (Part 3). |

- **Commands (state-changing):** the **existing** `subscription.Subscription.Commit` — now reachable by an
  **investor actor** as well as an admin actor. No new command type.
- **New auth surface:** `request-otp` (login entry, not a `CommandGateway` command — it mints, doesn't mutate an aggregate).

## 5. Five non-negotiables — applicability (the self-commit command)

| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | **no** | Subscription commit is a single-actor command (the admin path isn't maker-checker either). |
| 2 | MFA-fresh | **no** (non-admin actor) | Gateway skips the freshness gate for a non-admin actor (`:79`). Login itself is OTP-gated. AUTHZ-1. |
| 3 | SoD-checked | **no** | Investor acts on its own account; no admin-role segregation in play. |
| 4 | Idempotent on `command_id` | **yes** | `(actor_id = investor identity, command_id)` on `sys_command_log`; unchanged mechanism. |
| 5 | Audit-logged envelope | **yes** | Gateway envelope with `actor_type='investor'` (schema already accepts it, §0). Plus the Part-3 denied-read event. |

Ownership scoping (SELF-1) + eligibility (SELF-2) are the real controls, enforced server-side.

## 6. Events
- **Publishes:** `subscription.Subscription.Committed` (existing, now also from an investor actor) +
  `listing.Listing.FullyFunded` (existing, on exact-fill) + **new** `auth.CrossTenantReadDenied` (Part 3, telemetry).
- **Subscribes:** none.

## 7. Test scenarios (write these first — `extends AbstractEdgeHttpTest`)
Bearers via the real login flow; investor bearer via the **new** `request-otp → verify-otp` path (OTP read from the
in-process `StubNotifier`). Reuse `seedActiveInvestorWithLogin()` (M10-D T1) and add a passwordless-login helper.

- [ ] **Login happy path (LOGIN-1):** `active` investor → `request-otp` → OTP → `verify-otp` → bearer;
      `/auth/session` shows `kind:"investor"`, `investor_id`, `roles:[]`.
- [ ] **Enumeration-safe (DoR-1):** unknown email / non-investor / non-`active` investor → `request-otp` returns a
      `{challenge_id}`-shaped 200 **indistinguishable** from the happy path; a subsequent `verify-otp` fails the same
      generic way; **no OTP sent** (StubNotifier has no code for that identity).
- [ ] **Non-active cannot log in (DoR-2/3):** a `kyc_approved` / `suspended` investor → cannot obtain a session.
- [ ] **Self-commit happy (SELF-1/2):** investor bearer `POST …/subscriptions/commit {amount_paise}` (no
      `investor_id`) → commits to **own** account; `committed_total` moves; the `sub_subscription.investor_id` is theirs.
- [ ] **Cross-tenant self-commit rejected (SELF-1):** investor A passes a body `investor_id` = B → rejected, no write.
- [ ] **Ineligible self-commit (SELF-2):** amount < min → validation; over headroom → rejected; non-`live` listing
      → rejected — each a clean domain error, not a bare 403.
- [ ] **Ops-on-behalf still works (SELF-3 / DoD #4):** admin OPS `commit {investor_id, amount_paise}` → unchanged.
- [ ] **Idempotency/audit (AUTHZ-1):** replay `(investor identity, command_id)` → replayed, one envelope,
      `actor_type='investor'`; a divergent reuse → conflict.
- [ ] **Denied-read audit (AUDIT-READ-1):** investor A `GET /investors/{B}/subscriptions` → 403 **and** one
      `auth.CrossTenantReadDenied` audit row (actor A, attempted B, endpoint). A **successful** own read writes none.
- [ ] **Admin no-regression:** admin login + one admin command unchanged (MFA-fresh still enforced for admins).

## 8. Definition of Done (gate F)
- [x] §7 tests green; whole suite green — **448** (was 437; +12 BE-18 tests, −1 retired M10-D RO-1 lock).
- [x] DoD from brief §4: passwordless login (enumeration-safe); self-commit own-scoped + cross-tenant reject;
      ineligible → clean domain errors; ops-on-behalf no regression; denied read audited; idempotency/audit hold
      for the investor actor; admin login + all admin commands unchanged.
- [x] No new migration (confirmed against V1–V13).
- [x] `/code-review` (self, high) on the diff — implementation matches `/plan`; cosmetic javadoc tidied; the RO-1
      supersession (M10-D `investor_bearer_cannot_commit`) handled; residuals recorded in DL-BE-088.
- [x] **DL-BE-088** finalised (implementation notes + residuals); `PROJECT_TRACKER.md` Track B #7 → done + DF-1
      resolved; `API_CATALOGUE.md` login route + commit role-line updated.
- [x] Mock-side work-order written (brief §5) — do not edit mock code from here.
- [x] This spec flipped to **Status: Done**.

---

## 9. Plan (`/plan` — code-anchored, verified 2026-07-18)

**Two `/plan`-time confirmations that de-risk the slice:**
- **DoR-1 needs no verify-path change.** `AuthService.verifyOtp` already returns `OtpResult.failed("not_found")`
  for a missing challenge (`AuthService.java:176-178`), which `AuthController.verifyOtp` (`:41-47`) turns into the
  **same** generic `LoginFailedException` as a wrong code. So a **synthetic `challenge_id` with no row** verifies
  identically to a real-but-wrong code — enumeration-safety falls out for free.
- **No migration.** All three parts use existing tables (`auth_otp_challenge`, `auth_session`, `sub_subscription`,
  `sys_command_log`, `sys_audit_event`), all already accepting an investor actor (§0). Confirmed against V1–V13.
  If `/tasks`/`/implement` surfaces an unavoidable migration, **stop and flag**.

Build order — each feature step: red test → green → `/code-review` → DL note. All touchpoints use the canonical
`@AuthenticationPrincipal AuthSession session` + `JdbcTemplate` idiom (no JPA).

**Part 1 — passwordless login**
- **P1 · `AuthService.requestInvestorOtp(String email) → UUID` (new `@Transactional`).** Eligibility lookup
  (DoR-2/3, fail-closed on *current* status):
  `SELECT i.identity_id FROM auth_identity i JOIN inv_account a ON a.identity_id = i.identity_id
   WHERE i.email = ? AND i.kind = 'investor' AND i.status = 'active' AND a.status = 'active'`.
  Eligible → `return issueLoginOtp(identityId)` (reused verbatim, `:129-155` — real OTP, one-active-challenge
  supersession). Not eligible → `return Ids.newId()` (**synthetic** id, **no** `auth_otp_challenge` row, **no**
  send). Run the lookup unconditionally (no early return) so the two paths share latency as far as practical (DoR-1;
  the residual timing delta — issuing vs. not — is a best-effort note, §residual). Do **not** reuse
  `isKycApprovedForDownload` (DoR-3).
- **P2 · `AuthController` `POST /auth/login/investor/request-otp` (open).** `{email}` → `requestInvestorOtp` →
  `{ challenge_id }`. `SecurityConfig` already `permitAll`s `/auth/login/**` (`:41`), so the route is open with no
  security change. `verify-otp` (`:41-53`) is reused unchanged — it establishes the `kind='investor'` session
  (`SessionService.establishSession` is kind-agnostic). `/auth/session` already returns `investor_id` (M10-D).

**Part 2 — investor self-commit**
- **P3 · `SubscriptionController.commit` — kind branch (`SubscriptionController.java:49-63`).** Inject
  `InvestorQueryPort` (the M10-D shared resolver). Resolve `callerInvestorId =
  investors.investorIdForIdentity(session.identityId())`:
  - **investor caller** (`callerInvestorId != null`): use `callerInvestorId` as the commit id; if the body carries
    an `investor_id` that isn't it → reject fail-closed (SELF-1); `actorType = "investor"`; derive
    `subscriptionId` from `(commandId, listing:callerInvestorId)`.
  - **else** (admin/other): the existing path unchanged — `investor_id` from the body, `actorType = "admin_user"`.
    A non-investor non-OPS caller still hits the OPS role gate downstream (fail-closed).
- **P4 · `SubscriptionService.commit` — actor-kind authorization + actor fix (`SubscriptionService.java:57-112`).**
  Choose the gateway gate by actor kind: `Set<String> required = "investor".equals(request.actorType()) ? Set.of()
  : OPS;` then `gateway.execute(request, required, …)`. The investor path rides the no-role overload; eligibility
  is already enforced by `requireActiveInvestor(investorId)` (`:265-274`, checks `inv_account.status='active'` =
  DoR-2) — no extra KYC sub-check. **Fix (DoR-7):** `actor(request)` (`:303-306`) must use `request.actorType()`
  instead of the hard-coded `"admin_user"` so the `Listing.FullyFunded` envelope labels an investor self-commit
  correctly. No path here calls `RoleResolver.adminUserId(actorId)` (which would throw for an investor) — keep it so.

**Part 3 — audit denied cross-tenant reads**
- **P5 · `InvestorController.subscriptions` (`InvestorController.java:229-238`).** Inject `AuditLog`. Before each
  throw (`:234` `crossInvestorRead`, `:237` `notAuthorisedForPortfolio`) append a lightweight event via
  `AuditEnvelopes.seed("investor", "InvestorAccount", attemptedId).eventType("investor.CrossTenantReadDenied")
  .actor(new Actor(<kind>, session.identityId().toString(), session.sessionId().toString(), null, null))
  .payload({attempted_investor_id, endpoint, caller_investor_id?}).build()` → `auditLog.append(...)`
  (non-state-transition, **no `command_id`** — the `confirmFromInflow` append shape). Denials only; successful
  reads stay unaudited. Update the `ForbiddenException.crossInvestorRead` Javadoc + the M10-D §5 note that these
  denials are now audited (was "reads emit no audit envelope").

**Tests (detailed in `/tasks`)** — `investor/InvestorPasswordlessLoginTest` + `subscription/InvestorSelfCommitTest`,
both `extends AbstractEdgeHttpTest`; add a passwordless-login bearer helper (`request-otp → verify-otp`, OTP from
`StubNotifier`). Cover the §7 scenarios.

### Residual `/plan` notes & flags
- **Timing side-channel (DoR-1):** the response *shape* is identical, but issuing a real OTP is heavier than
  returning a synthetic id — a timing oracle could in principle distinguish. Acceptable at pilot; a constant-time
  refinement (e.g. always run a dummy `encoder.encode`) is a noted follow-up, not built now.
- **Rate-limiting** deferred (DoR-5) — `request-otp` is open; the one-active-challenge supersession is the only
  throttle. Platform-wide auth-hardening follow-up.
- **Audit context/type name** (`investor.CrossTenantReadDenied`) — confirm the context shard at `/implement`
  (must match an existing audit-chain context or add one; prefer reusing `"investor"`).
- **No migration overall** — if `/implement` hits an unavoidable one, stop and flag.

## 10. Tasks (`/tasks` — ordered, red-test-first, 2026-07-18)

Two new test classes, both `extends AbstractEdgeHttpTest` (MockMvc + Testcontainers). Reuse
`seedActiveInvestorWithLogin(status)` (M10-D T1 — seeds `auth_identity` active + `inv_account` at a given status +
a password). **Part 2/3 tests get an investor bearer via the existing password flow** (`bearerFor(login())`) so they
**don't depend on Part 1**; Part 1 tests exercise the new passwordless entry directly.

**T1 · Harness — `bearerForInvestorPasswordless(Seeded)` (enabling; no assertion).** Add to `AbstractEdgeHttpTest`:
`POST /auth/login/investor/request-otp {email}` → read the code from `StubNotifier.lastCodeFor(identityId)` →
`POST /auth/login/verify-otp` → bearer.

**T2 · P1+P2 passwordless login — `investor/InvestorPasswordlessLoginTest`.**
- **RED→GREEN**: `active_investor_logs_in_passwordless` (request-otp → OTP → verify → bearer; `/auth/session` →
  `kind:"investor"`, `investor_id`, `roles:[]`). `unknown_email_is_enumeration_safe` (→ `200 {challenge_id}`,
  shape-identical; no identity ⇒ nothing sent). `ineligible_investor_gets_no_otp` (a `kyc_approved` investor →
  `200 {challenge_id}` but `StubNotifier.lastCodeFor(identity)` is **empty** — no real OTP; DoR-2/3).
- **GREEN**: `AuthService.requestInvestorOtp` (P1) + `AuthController` route (P2).

**T3 · P3+P4 self-commit — `subscription/InvestorSelfCommitTest`.**
- **RED→GREEN**: `investor_commits_to_own_account` (investor bearer, body `{amount_paise}` only → 201; `committed_total`
  moves; `sub_subscription.investor_id` == own). `investor_cannot_commit_for_another` (body `investor_id` = B →
  **403 `cross_tenant_read`**, no write). `below_min_ticket_rejected` / `over_headroom_rejected` /
  `non_live_listing_rejected` (clean domain errors). `ops_on_behalf_still_works` (admin OPS + body `investor_id` →
  201, no regression). `replay_is_idempotent_for_investor_actor` (same `(investor identity, command_id)` → replayed;
  the envelope's `actor_type='investor'`).
- **GREEN**: `SubscriptionController` kind branch (P3) + `SubscriptionService` actor-kind gate + actor-type fix (P4).

**T4 · P5 denied-read audit — (in `InvestorSelfCommitTest` or a small `investor/*` test).**
- **RED→GREEN**: `denied_cross_tenant_read_is_audited` (investor A → `GET /investors/{B}/subscriptions` → 403 **and**
  exactly one `investor.CrossTenantReadDenied` row for actor A / attempted B). `own_read_writes_no_audit` (A reads
  own → 200, no such row).
- **GREEN**: `InvestorController` inject `AuditLog` + append at the two throw sites (P5); update the
  `ForbiddenException.crossInvestorRead` Javadoc + M10-D §5 note.

**T5 · DoD wrap.** Full suite (record count); `/code-review`, fix findings; finalize `DL-BE-088`; update
`PROJECT_TRACKER.md` (Track B #7 → done, DF-1), `API_CATALOGUE.md` (new login route + commit role-line); mock-side
work-order note (do not edit mock code from here); flip this spec to **Status: Done**.

**Order & parallelism:** T1 first (unblocks T2). After T1, **T2 (auth) / T3 (subscription) / T4 (investor)** touch
different files and are independent. T5 last. One module in flight (M11-B) — parallelism is within it.
