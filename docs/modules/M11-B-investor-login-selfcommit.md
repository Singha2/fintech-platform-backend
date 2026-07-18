# M11-B · Investor passwordless login + investor self-commit (Phase B / BE-18)

> **Module spec — `/specify` + `/clarify` (DoR) output** for `docs/BE18_INVESTOR_LOGIN_SELFCOMMIT_BRIEF.md`.
> The **write** counterpart to **M10-D** (`M10-D-investor-self-login.md`, the read-only portal): M10-D lets an
> investor *log in with the dev password and read* their own portfolio; **Phase B** lets a **real** investor
> **log in passwordless (email+OTP)** and **place their own subscription**. This turns the read-only façade into
> a genuinely self-service investor portal and removes ops from the money-in path.
> Umbrella predecessor decision: **DL-BE-084 (§Phase B)**; this slice claims **DL-BE-088**.
> **This doc is DoR-stage** — `/specify` + `/clarify` are complete; **`/plan` / `/tasks` / `/implement` are not
> yet run** (next gate). Spec before code; invariant test before rule.

| | |
|---|---|
| **Module** | M11-B — Investor passwordless login (BC7 identity/auth) + investor self-commit (BC2 subscription) |
| **Slice** | The Phase-B write cut M10-D deferred — passwordless login **+** self-commit **+** denied-read audit. A narrow slice, **not** the full BC2 subscription module (`M11-subscription-full.md`). |
| **Tracker IDs** | **BE-18** (Track B #7) |
| **Tier** | Light-to-medium (adds one command path + one login entry; no new tables — see §0) |
| **Status** | **DoR-green (spec written) — implementation pending** |
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
- [ ] §7 tests green; whole suite green (record count).
- [ ] DoD from brief §4: passwordless login (enumeration-safe); self-commit own-scoped + cross-tenant reject;
      ineligible → clean domain errors; ops-on-behalf no regression; denied read audited; idempotency/version/audit
      hold for the investor actor; admin login + all admin commands unchanged.
- [ ] No new migration (confirm against V1–V13); if one is unavoidable, **stop and flag**.
- [ ] `/code-review` on the diff; findings fixed.
- [ ] **DL-BE-088** finalised (implementation notes + any findings); `PROJECT_TRACKER.md` Track B #7 + DF-1 updated;
      `API_CATALOGUE.md` rows for the new login route + the commit role-line updated.
- [ ] Mock-side work-order written (brief §5) — do not edit mock code from here.
- [ ] This spec flipped to **Status: Done**.

---

## 9. Next gate — `/plan` (not yet run)
`/specify` + `/clarify` (DoR) are complete (this doc). **`/plan` is the next step:** anchor each part to exact
files/lines (`AuthController` + a new `investorRequestOtp` service method; `SubscriptionController`/`Service`
branch; the two `InvestorController` append sites), confirm the no-migration assumption, confirm `verifyOtp`
handles a missing challenge indistinguishably (DoR-1), and order the red-test-first tasks. Do **not** start
`/implement` before `/plan` + a green DoR sign-off.
