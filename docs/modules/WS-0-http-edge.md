# WS-0 · The HTTP edge — the B4 command surface, over real controllers

> **Lean sub-slice spec** (first cut of the Walking Skeleton — see `WS-walking-skeleton.md` §4).
> Light tier. The reusable *skin* every later WS slice plugs into: it turns the headless core (M1–M5)
> into something a real HTTP client — and the frontend contract — can call, per
> `docs/spec/10_B4_API_Conventions.md`. Spec before code; invariant test before rule.

| | |
|---|---|
| **Module** | M1 — Walking Skeleton · the HTTP edge |
| **Slice** | WS-0 — request-envelope + command-controller + error taxonomy + bearer auth |
| **Tier** | Light (wires already-built M3/M4 services; no new domain logic) |
| **Status** | Done |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-22 |

> **Why first.** Nothing else in the milestone can be HTTP-driven until the edge exists; every WS-1…WS-7
> controller plugs into the envelope resolver, the `emitted_events` response, and the error taxonomy this
> slice establishes. It's also the lowest-risk first build — it wires *already-tested* services
> (`AuthService`, `SessionService`, `AdminUserService` via `CommandGateway`), isolating the genuinely new
> risk (Spring Security filter chain, header resolution, audit-before-2xx at the boundary, the MockMvc
> harness) into one slice before any money-flow logic rides on top.

---

## 1. Scope
**Owns:** the HTTP edge under `com.arthvritt.platform.infrastructure.web` / `…security`:
- `SecurityConfig` — a **stateless** `SecurityFilterChain` (CSRF/formLogin/httpBasic off; permit
  `/auth/login/**` + Actuator health; everything else authenticated). *(Replaces Spring Security's
  locked-down default, which is currently the only thing standing between the app and open endpoints.)*
- `SessionBearerAuthFilter` — `Authorization: Bearer <sessionId>` → `SessionService.resolveSession` →
  sets the `AuthSession` as the authenticated principal; a custom `AuthenticationEntryPoint` renders the
  B4 401 body for missing/invalid bearer (no envelope).
- `RequestEnvelope` — resolves the B4 §2.2 headers (`X-Command-Id`, `X-Aggregate-Version`,
  `X-Correlation-Id`) and assembles a `CommandRequest` from headers + the resolved session.
- `CommandResponse` — the B4 §2.3 success body (`aggregate_id`, `aggregate_version`, `emitted_events`,
  `correlation_id`); an assembler reads the emitted envelope(s) back by `event_id` from `sys_audit_event`
  (since `CommandResult` carries only the id).
- the **full B4 §4.1 error body** on `GlobalExceptionHandler` (flat snake_case: `error_code`,
  `error_category`, `violating_rule`, `correlation_id`, `retryable`) for the no-envelope rejects.
- two demonstrator controllers wiring existing services: `AuthController`, `AdminUserController`.

**Does NOT own (deferred):** the webhook ingress (B4 §5 → WS-5); the 422 / `MakerChecker.Blocked`
*envelope-emitting* reject (B4 §4.3 → WS-4, reuses this error body); projections, cursor pagination,
sensitive-read envelopes (B4 §3 → Milestone 2); step-up MFA + the `X-Mfa-Assertion-Id` header (see §3
INV-3); AI-agent auth (G31); rate limiting / outage banners.

## 2. Upstream dependencies
- **B4 API Conventions** — the contract this implements. Done (spec).
- **M3a/b** `AuthService` (password + SMS-OTP login, mints the assertion) + `SessionService` (establish /
  resolve / `isMfaFresh`). Done.
- **M4a–d** `CommandGateway` (idempotency #4, MFA-fresh #2, authz, audit #5) + `AdminUserService`
  (`provisionAdminUser`, `disableAdminUser`). Done. *(`CommandRequest`'s own javadoc: "The HTTP layer
  (Walking Skeleton) populates this from headers + the resolved session.")*
- **M2** `AuditLog` — the same-tx envelope append; the read-back source for `emitted_events`. Done.
- `spring-boot-starter-security` / `-web` — on the classpath since M0.

## 3. Invariants & rules
- **INV-1 — Bearer = `sessionId`.** The bearer token is the opaque `auth_session.session_id` UUID;
  every other principal field is loaded server-side via `resolveSession`, never trusted from the client
  (Phase-1 simplification — no JWT; DL-BE). _(ref: B4 §2.2 `Authorization`, M3b INV-3)_
- **INV-2 — Audit-before-2xx.** No command returns 2xx before its envelope is durable. M2 appends in the
  command's transaction, which satisfies this in the monolith **without a separate outbox table**; WS-0
  asserts it at the HTTP boundary so the contract survives the Phase-2 broker swap. _(ref: C-X13, B4 §7, G27)_
- **INV-3 — MFA freshness is session-carried.** The assertion minted at login is stamped on the session
  and re-checked per sensitive command via `isMfaFresh` (the existing `CommandGateway` path). B4's
  per-command `X-Mfa-Assertion-Id` header models **step-up MFA** (mint a fresh assertion right before a
  sensitive action); that is **deferred** — WS-0 enforces freshness off the session. **Decision-logged
  deviation from B4 §2.2** (B4 permits deviations with a DL entry). _(ref: B4 §2.2/§6.4, C7, DL-BE)_
- **INV-4 — Idempotency on `(actor_id, X-Command-Id)`.** Replay → original `emitted_events`, no second
  envelope; divergent body under the same id → 409 `command_id_payload_mismatch`. Enforced by
  `CommandGateway`/`sys_command_log`. _(ref: B4 §2.4, G18/G32)_
- **INV-5 — No envelope on pre-authorisation failure.** Bad/missing header, missing/invalid bearer, stale
  MFA, role-not-held, version conflict, idempotency conflict → 4xx with the B4 error body and **no audit
  envelope** (the actor was never authorised to record a business fact). _(ref: B4 §4.2, G22, B2 §5.6)_

## 4. API / type surface
- **Commands (state-changing — through `CommandGateway`):**
  - `POST /auth/login/password` `{email, password}` → `{challenge_id}` (authenticate → issue SMS-OTP).
    *No bearer (mints the session path); not a `CommandGateway` command — it's the auth entry.*
  - `POST /auth/login/verify-otp` `{challenge_id, code}` → `{bearer}` (verify → establish session).
  - `POST /admin-users/provision` `{email, display_name, phone}` → **201** `CommandResponse` (server mints
    the aggregate id; `X-Aggregate-Version` omitted — B4 §2.1). Requires `super_admin`.
  - `POST /admin-users/{admin_user_id}/disable` → **200** `CommandResponse`. Requires
    `X-Aggregate-Version`, `super_admin`, fresh MFA.
- **Queries (read-only):** `GET /admin-users/{admin_user_id}` → the aggregate read (`status`,
  `aggregate_version`, …). Authenticated; full RBAC scoping deferred (B4 §3.5).
- **Types:** `RequestEnvelope`, `CommandResponse{aggregate_id, aggregate_version, emitted_events[], correlation_id}`,
  `EmittedEvent{event_id, event_type, occurred_at}`, the B4 `ErrorBody`.

## 5. Five non-negotiables — applicability
The edge **carries** the controls; `CommandGateway` enforces them (WS-0 adds no new enforcement, it
exposes the existing one over HTTP).

| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | no (WS-0) | no maker-checker command here; first pair is WS-4 |
| 2 | MFA-fresh | yes | session assertion + `isMfaFresh` on `disable` (INV-3) |
| 3 | SoD-checked | yes | `super_admin` role gate on both admin commands (`CommandGateway` authz) |
| 4 | Idempotent on `command_id` | yes | `X-Command-Id` → `sys_command_log` (INV-4) |
| 5 | Audit-logged | yes | M2 envelope per command, appended before 2xx (INV-2) |

## 6. Events
- **Publishes:** none new — the edge surfaces existing envelopes
  (`admin_iam.AdminUser.Created`/`.Disabled`, `auth.Session.Established`, …).
- **Subscribes:** none.

## 7. Test scenarios (write these first) — `@SpringBootTest` + MockMvc, Testcontainers
- [ ] No bearer on a protected route (`GET /admin-users/{id}`) → 401 `bearer_missing`, no envelope.
- [ ] Login round-trip: `POST /auth/login/password` then `/verify-otp` (code via `StubNotifier`) → a
      usable bearer; a subsequent authenticated call succeeds.
- [ ] Happy command `POST /admin-users/provision` → **201**, body has `emitted_events` naming
      `admin_iam.AdminUser.Created`; the row exists.
- [ ] **Audit-before-2xx:** each `emitted_events[].event_id` resolves to a persisted `sys_audit_event`.
- [ ] Idempotent replay (same `X-Command-Id`, same body) → original `emitted_events`, **no second
      envelope** (`count(*)` unchanged).
- [ ] Same `X-Command-Id`, different body → 409 `command_id_payload_mismatch`.
- [ ] `disable` with stale `X-Aggregate-Version` → 409 `aggregate_version_stale`.
- [ ] `disable` with a stale MFA assertion (backdated `consumed_at`) → 401 `mfa_assertion_expired`,
      target unchanged.
- [ ] `GET /admin-users/{id}` with a valid bearer → 200 aggregate read.

## 8. Definition of Done (light tier)
- [x] §7 tests green; whole suite green — `WalkingSkeletonEdgeTest` **11/11**, full suite **144**.
- [x] `/code-review` on the diff; findings fixed — 8 findings (the headline: Spring MVC framework
      exceptions were leaking a non-B4 ProblemDetail; plus input-validation 500s, GET-404, access-denied
      handler, fresh security context) all resolved + 3 regression tests added.
- [x] `DL-BE-030` added (the edge plumbing, audit-before-2xx realization, the bearer=sessionId &
      session-carried-MFA deviations from B4 §2.2, the creating-command identity derivation).
- [x] This spec flipped to **Status: Done**.
