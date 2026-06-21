# M3b · Sessions, MFA-freshness check & tenant claims

> **Lean module spec** — *foundation-critical* (security-sensitive: session lifecycle, the
> freshness gate every later command trusts): light ceremony, heavy invariant rigor.
> See `docs/spec/Spec_Driven_Build_Plan.md` §C/§D. Spec before code; invariant test before rule.

| | |
|---|---|
| **Module** | M3 — Auth & Identity (BC: auth shared kernel) |
| **Slice** | M3b — `auth_session` lifecycle + `isMfaFresh` check + tenant-claim serialisation |
| **Tier** | Foundation-critical (light ceremony · heavy security-invariant rigor) |
| **Status** | Done (impl + tests green; `/code-review` findings fixed; DL-BE-017) |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-21 |

> M3a **minted** the `mfa_assertion_id` (non-negotiable #2). M3b is the **consumer side**: it
> establishes the authenticated session that carries that assertion and implements `isMfaFresh` —
> the freshness gate **every admin state-changing command will call** (AU10.3, C7, B4 §6.4). It also
> serialises the **tenant claims** that the repository layer injects as query predicates to enforce
> **C16** tenant isolation (G19). Like M3a it is a **service substrate**: native SQL onto the V3
> `auth_session` table, audited via `AuditLog` (M2). No HTTP endpoints / cookie filter yet — those
> land with the first authenticated endpoint (Walking Skeleton).

## 1. Scope
**Owns:**
- **Session establishment** — `establishSession(identityId, mfaAssertionId, claims, clientIp, userAgent)
  → sessionId`. Writes one `auth_session` row: `issued_at`, rolling `idle_expires_at`, hard
  `absolute_expires_at`, `mfa_assertion_id`, `tenant_claims`, `status='active'`. Called by the login
  flow *after* M3a's `verifyOtp` succeeds.
- **Session resolution + idle roll** — `resolveSession(sessionId) → AuthSession | Expired`. Loads the
  row, validates status + both expiries, rolls the idle window (`last_seen_at`, `idle_expires_at`)
  forward on a live session, and lazily transitions to `expired` when idle/absolute elapsed.
- **Revocation** — `revokeSession(sessionId)` (explicit logout / admin kill) → `status='revoked'`,
  `revoked_at=now()` (honours DB `auth_session_revoked_implies_status`).
- **MFA-freshness check** — `isMfaFresh(session, sensitivity) → boolean`. Consults
  `mfa_assertion_id` → the consumed `auth_otp_challenge.consumed_at` and compares against the policy
  window (default **5 min sensitive / 30 min normal**, B4 §6.4). Null/stale ⇒ not fresh.
- **Tenant-claim serialisation** — build the per-kind `tenant_claims` JSONB at establishment and a
  typed read accessor (`TenantClaims`). M3b owns the *mechanism*; the repository-level predicate
  injection that actually enforces C16 is consumed downstream (per-BC, M4+).
- **Audit wiring** — `SessionEstablished` / `SessionRevoked` / `SessionExpired` envelopes via
  `AuditLog.append()` (M2), plus the `TenantClaim.Issued` envelope at establishment (B2 §3.10).

**Does NOT own (deferred / other module):**
- **HTTP cookie/session filter** (HttpOnly, Secure, SameSite=Lax cookie carrying `session_id` only;
  Spring Security wiring) → **first authenticated endpoint / Walking Skeleton**. M3b is the
  service-layer substrate it will call.
- **Repository predicate injection enforcing C16** (claims → query `WHERE`) → **downstream per-BC
  (M4+)**; M3b *produces* the claims, consumers apply them.
- **Admin role computation inside claims** → **M4** (RBAC/SoD). M3b serialises whatever claims map it
  is handed; for admin the `{roles:[…]}` content arrives with M4.
- **Scheduled session-expiry sweep / cleanup job** → **M5/ops** (M3b expires *lazily on resolve*).
- **TOTP-based assertions** (`mfa_factor_kind=totp`, DL-035) → later; M3b reads whatever
  `assertion_id` M3a minted (SMS-OTP today), agnostic to factor kind.

## 2. Upstream dependencies
- **M3a Authentication** — Done. Mints `mfa_assertion_id` on `auth_otp_challenge` consume; M3b reads it.
- **M2 Audit Log** — Done (session events append here). **M1a** (errors), **M1b** (`Ids`, UUIDv7) — Done.
- **M0 schema** — Done: `auth_session` (+ `session_status_enum`) and `auth_otp_challenge` present in V3.

## 3. Invariants & rules
- **INV-1 — Expiry bounds hold.** `absolute_expires_at > issued_at` and `idle_expires_at ≤
  absolute_expires_at` — the absolute is the hard ceiling no idle-roll can exceed. _(ref: DB
  `auth_session_absolute_after_issue`, `auth_session_idle_within_absolute`)_
- **INV-2 — Terminal status is consistent + one-way.** `revoked_at` set ⇒ `status='revoked'`; an
  expired or revoked session never resolves back to `active`. _(ref: DB
  `auth_session_revoked_implies_status`; app for `expired`)_
- **INV-3 — The cookie carries `session_id` only.** Identity, claims and assertion are loaded
  server-side from the row, never trusted from client input. _(ref: Spec §7.3; schema COMMENT)_
- **INV-4 — MFA-freshness gates sensitive actions.** `isMfaFresh` is false when `mfa_assertion_id`
  is NULL, the referenced challenge is unconsumed, or `consumed_at` is older than the window for the
  action's sensitivity (5 min sensitive / 30 min normal). _(ref: C7, B4 §6.4, AU10.3)_
- **INV-5 — Tenant claims are server-issued, never client-trusted.** Serialised at establishment per
  identity kind (investor `{investor_id}`, ack_user `{buyer_id}`, admin `{roles}`, auditor
  `{scope_id}`); the repository injects them as predicates. _(ref: C16, G19, B4 §3.5)_
- **INV-6 — Every session lifecycle change is audited.** Establish / revoke / expire each emit an
  envelope; claim issuance emits `TenantClaim.Issued`. _(ref: non-negotiable #5, C1, B2 §3.10)_

## 4. API / type surface
- **Commands (state-changing):**
  - `establishSession(identityId, mfaAssertionId, claims, clientIp, userAgent) → sessionId`
  - `revokeSession(sessionId)` (logout / admin kill)
- **Queries / lifecycle (with bounded side effect):**
  - `resolveSession(sessionId) → AuthSession | Expired` (rolls idle window; lazy-expires — the only
    permitted state change on a read path, P4-aligned)
  - `isMfaFresh(session, sensitivity) → boolean` (pure read of session + challenge)
- **Types / value objects:** `AuthSession`, `TenantClaims` (typed view of the JSONB),
  `ActionSensitivity { SENSITIVE, NORMAL }`.

## 5. Five non-negotiables — applicability
| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | no | no business command here; M4 owns the engine |
| 2 | MFA-fresh | **consumed here** | M3b **implements `isMfaFresh`** — the check M3a's assertion exists to satisfy; admin command handlers (M4+) call it |
| 3 | SoD-checked | no | M4 |
| 4 | Idempotent on `command_id` | no | session ops; money-command idempotency store deferred ([[M1b-ids-and-versioning]], [[DL-BE-013]]) |
| 5 | Audit-logged envelope | **yes** | every session lifecycle event + `TenantClaim.Issued` → `AuditLog.append()` (M2) |

## 6. Events (audit envelopes; no bus yet)
Appended via `AuditLog.append()` ([[DL-BE-014]]): `SessionEstablished`, `SessionRevoked`,
`SessionExpired`, and `TenantClaim.Issued` (B2 §3.10, at establishment).
- **Not audited:** per-request idle-window roll on `resolveSession` (too noisy; `last_seen_at` is the
  record). Decided in §9.

## 7. Test scenarios (write these first) — integration, Testcontainers
- [ ] Establish: row has `status=active`, `mfa_assertion_id` set, `tenant_claims` populated, idle ≤
      absolute; envelope emitted (INV-1, INV-6).
- [ ] Resolve within idle → `last_seen_at`/`idle_expires_at` roll forward, stays `active` (INV-1).
- [ ] Resolve past `idle_expires_at` → transitions to `expired`, rejected; resolving past
      `absolute_expires_at` (even if recently active) → `expired` (INV-2).
- [ ] Revoke → `status=revoked`, `revoked_at` set, later resolve rejected; DB
      `auth_session_revoked_implies_status` holds (INV-2).
- [ ] `isMfaFresh`: fresh consume (≤5m) → true for SENSITIVE; consumed ~10m ago → false SENSITIVE,
      true NORMAL; consumed >30m → false both; NULL assertion → false (INV-4).
- [ ] Claims round-trip: serialise per kind, read back via `TenantClaims`; value comes from
      identity/IAM, not client input (INV-5).
- [ ] **DB invariant test:** attempt a row with `idle_expires_at > absolute_expires_at` → rejected by
      `auth_session_idle_within_absolute` (proves the rule fires at *both* app and DB — §I of the plan).

## 8. Definition of Done (foundation-critical)
- [x] §7 tests green (the freshness + expiry invariants are the headline) — `SessionServiceTest`, 11 tests; 74 total green.
- [x] `/code-review` on the diff; findings fixed (shared `correlation_id`, `isMfaFresh` purpose pin,
      `UPDATE … RETURNING` on the resolve hot path).
- [x] `DL-BE-017` entry (session TTLs, freshness window defaults, lazy-expiry-on-resolve vs sweep,
      claims serialisation shape, idle-roll-not-audited + the three review follow-ups).
- [x] Status flipped to **Done**.

## 9. Self-review resolutions (DoR-green)
1. **Session TTLs — RESOLVED (proposed defaults):** idle **30 min**, absolute **8 h** for admin
   sessions; final values are **BC10 policy** (tunable without schema change). Recorded in DL-BE-017.
2. **Expiry strategy — RESOLVED: lazy expire-on-`resolve`.** No scheduler in M3b; a sweep/cleanup
   job for sessions never resolved again is deferred to **M5/ops** (a stale `active` row is harmless
   because every resolve re-checks both expiries).
3. **Freshness window — RESOLVED: adopt B4 §6.4 defaults** (5 min sensitive / 30 min normal); BC10
   may tune later. `isMfaFresh` takes the action's `ActionSensitivity` so callers pick the band.
4. **HTTP cookie/session filter — RESOLVED: deferred** to the first authenticated endpoint (Walking
   Skeleton). M3b ships the session *service*; the filter that reads the `session_id` cookie and
   calls `resolveSession` is wired when there is an endpoint to protect.
5. **Claims content — RESOLVED:** M3b serialises a per-kind claims map for the kinds live today
   (investor/ack/auditor); **admin `{roles}` is populated by M4**. The serialisation mechanism +
   typed accessor are owned here so M4 only supplies the role values.
6. **Idle-roll auditing — RESOLVED: not audited.** Establish/revoke/expire are audited; the
   per-request idle roll is not (noise) — `last_seen_at` is the durable record.

## 10. Watch-for (carry forward)
- **Concurrent idle-roll races** — two near-simultaneous requests both updating `idle_expires_at`;
  use a guarded/conditional update (or the `aggregate_version` pattern) so a revoke can't be undone
  by an in-flight refresh.
- **Absolute-ceiling re-auth UX** — hitting `absolute_expires_at` forces full re-login; surface a
  clear `session_expired` to the frontend when the cookie filter lands.
- **Freshness clock source** — `isMfaFresh` compares against `consumed_at` (server time); ensure no
  client-supplied timestamp ever feeds the window (ties to INV-3/INV-5).
