# BACKEND BRIEF — BE-18 · Passwordless investor login + investor self-commit (Phase B)

> **What this is.** The **Phase B** carry-forward from M10-D (`DL-BE-084`): let a **real** investor (a) **log in
> passwordless** (email + OTP, no admin, no dev password) and (b) **commit to a listing themselves** — the
> `CommandGateway` non-admin-actor authorization change — plus the read-path **audit of denied cross-tenant reads**
> deferred at M10-D. This is the last backend milestone gating a genuine investor experience; the admin +
> deal-flow surface and the read-only investor portal are already live and UI-wired.
>
> **Why it's needed (business value).** Today the investor portal is a **façade over admin plumbing**: M10-D ships
> **dev-password login only** (`investor@dev.local` / `DevPass123!`), and every investor *write* is **ops-on-behalf**
> — an Ops user runs `subscriptions/commit` with a client-supplied `investor_id`. That means: no real investor can
> log in or invest without staff doing it for them; the pilot can't put an actual investor in front of the product;
> and "ops types the investor_id" is both a scaling bottleneck and a tenant-isolation risk (the correct id is the
> **session's own**, never client-typed). BE-18 turns the read-only façade into a real self-service portal:
> investors log in with an email OTP and place their own subscriptions, scoped to their own account. It unblocks
> the first true investor pilot and removes ops from the money-in path.
>
> **This is a brief, not a spec.** It is a **module-sized** slice (spec it as **M11-x**, per PROJECT_TRACKER Track B
> #7) — take it through the full repo loop (`/specify → /clarify → DoR → /plan → /tasks → /implement → DoD`).
> Append `DL-BE-088` (next free id). Design rationale + constraints already recorded in **`DL-BE-084` (§Phase B,
> §Non-obvious decisions)**.
>
> **Sibling to** `DEV_SEED_LISTING_HELPER.md` (DL-BE-086), `DF3_SEEDER_UPSERT_BRIEF.md` (DL-BE-087) — same brief
> format; unlike those, this one **does** touch production code and needs a full module treatment.

---

## 0. Scope & non-goals

**In scope (three parts):**
1. **Passwordless investor login** — email → OTP → session, for an onboarded (`active`, KYC-approved) investor. No
   password, no admin role.
2. **Investor self-commit** — the investor commits to a listing under their **own** session; `investor_id` is
   **derived from the session**, never taken from the request body. The `CommandGateway` authorizes a **non-admin
   actor** for this one command.
3. **Read-path audit of denied cross-tenant reads** — the `403 cross_tenant_read` denials from M10-D are audited
   (deferred there, `DL-BE-084` §Phase B).

**Non-goals / guardrails:**
- **No change to admin login or admin commands.** Admin two-step password→OTP + MFA-fresh gate unchanged.
- **Onboarding stays ops-assisted.** Investor sign-up → activate (S10) remains ops/compliance-driven; BE-18 is
  about **login** + **self-commit (subscribe)** only. No investor-driven onboarding.
- **Self-commit is the only investor write in scope.** Cancel/refund stay ops/treasury. No investor-initiated
  disbursement/distribution.
- **Reuse the existing OTP machinery.** `/auth/login/verify-otp`, `auth_otp_challenge`, the stub notifier +
  `/dev/last-otp` / `/bootstrap/last-otp` all exist — Phase A already built ~80% of the passwordless plumbing
  (`DL-BE-084` OQ-1). Add the passwordless *entry*, not a second OTP stack.
- **Preserve the five non-negotiables** for the new command: idempotency (`X-Command-Id`, `(actor_id, command_id)`),
  optimistic version (`X-Aggregate-Version`), audit envelope, tenant scoping. **MFA-freshness does not apply** to a
  non-admin actor (the catalogue already states non-admin-actor commands skip the MFA gate) — confirm at `/clarify`.

---

## 1. Part 1 — Passwordless investor login (email + OTP)

**The gap (from `DL-BE-084`).** `inv_invite` has **no secret token**, and there is **no session-less command
substrate** (`CommandGateway` needs a session), so an invite-link/set-password path was rejected. The chosen shape
is **email + OTP**: the investor proves control of their registered email/phone, no shared secret.

**Proposed flow (confirm at `/clarify`):**
- `POST /auth/login/investor/request-otp` `{ email }` (open) → look up an **active, KYC-approved** `inv_account`
  by that email (via `auth_identity`); if found, issue an SMS/email OTP through the existing stub notifier and
  return a `challenge_id` (same `auth_otp_challenge` row shape as admin login). **Enumeration-safe:** return
  `{ challenge_id }`-shaped 200 whether or not the email matches (don't leak which emails are investors).
- `POST /auth/login/verify-otp` `{ challenge_id, code }` (existing) → establishes the session, returns the bearer.
  The session `kind='investor'`; `GET /auth/session` already returns `{ kind:'investor', investor_id, roles:[] }`
  (M10-D A2) — no change.
- **Gate:** only an `active` (and, if the product requires it to invest, KYC-approved) investor can obtain a
  session this way; a `signed_up`/`kyc_submitted`/suspended investor cannot log in (decide the exact status gate at
  `/clarify`). Dev password login (M10-D A1) stays as-is for dev/pilot fallback.

---

## 2. Part 2 — Investor self-commit (the `CommandGateway` non-admin-actor change)

**Today.** `POST /listings/{id}/subscriptions/commit { investor_id, amount_paise }` is gated `ops_executive`
(ops-on-behalf). The gateway's authz step is **role-based** (`requiredRoles` → `role_not_held`), and investors
hold **no roles**, so they cannot run any command.

**Change.**
- Allow the **investor kind** to execute `subscriptions/commit` for their **own** account. The gateway gains a
  non-role authorization path: a command may declare it is **investor-self-actor permitted**, and the handler
  scopes to `session.investor_id`.
- **`investor_id` comes from the session, never the body.** For an investor caller, ignore/reject a body
  `investor_id` that isn't the session's own (mirror the portfolio read's `cross_tenant_read` fail-closed rule).
  Ops-on-behalf (admin caller passing `investor_id`) stays supported — so S12 works both ways during transition.
- **Preconditions enforced server-side** (already partly there): investor is `active` + KYC-approved; amount ≥ min
  (DL-007); within remaining headroom (G10/L.2); listing is `live`. A not-eligible investor → a clean domain error,
  not a generic 403.
- **Keep the five non-negotiables**: `X-Command-Id` idempotency on `(actor_id, command_id)` where `actor_id` is now
  an **investor** actor; `X-Aggregate-Version`; audit envelope with `actor_type='investor'`; no MFA gate (non-admin
  actor). The `sys_audit_event` actor CHECK (AE.5) must already accept an investor actor key — verify.

---

## 3. Part 3 — Audit denied cross-tenant reads

M10-D returns `403 cross_tenant_read` for a mismatched-id portfolio/document read but **does not audit** the
denial (deferred, `DL-BE-084` §Phase B). Add a read-path audit event for these denials (actor, attempted id,
endpoint, at) so tenant-isolation probes are visible. Keep it lightweight — this is security telemetry, not a
state transition.

---

## 4. Definition of done
1. An `active`, KYC-approved investor logs in with **email + OTP only** (no password, no admin) and gets a
   `kind='investor'` session; a non-active / unknown-email request is **enumeration-safe** (indistinguishable
   response).
2. That investor calls `POST /listings/{id}/subscriptions/commit { amount_paise }` (no `investor_id`) and it
   commits **to their own account**; `committed_total` moves; the subscription is theirs. A body `investor_id`
   for a **different** investor is rejected (own-scoped, fail-closed).
3. Ineligible cases are clean domain errors: not-active/not-KYC investor → cannot log in / cannot commit; amount <
   min or over headroom → validation error; non-live listing → rejected.
4. Ops-on-behalf commit (admin caller) **still works** (no regression to S12's current path).
5. A denied cross-tenant read emits an audit event.
6. Idempotency/version/audit hold for the investor actor; admin login + all admin commands unchanged. Full suite
   green (+ new tests: passwordless login, self-commit happy + cross-tenant reject + ineligible). `DL-BE-088`
   appended; PROJECT_TRACKER §5 / Track B #7 updated; the catalogue rows for the new login route + the
   commit role-line updated.

---

## 5. Handoff to the front-end (mock repo `../fintech-patform-ui`)
Once shipped, the mock swaps its three interim investor shims for the real thing — all already isolated:
- **Investor login** — add a passwordless path in `AuthContext` (email → `request-otp` → `verify-otp`), i.e. skip
  the password step for the investor entry. Today investors log in via the dev password (`login('investor@…')`).
- **S12 subscribe** — drop `resolveInvestorId()` (currently `/dev/seed-info` in live+dev) and the
  ops-on-behalf `investor_id`; commit `{ amount_paise }` under the investor's own session. The `INVESTOR.id`
  placeholder retires.
- **S13 portfolio** — already scopes to `GET /auth/session`'s own `investor_id` (see `investor-portfolio.mjs`); it
  just stops needing the `/dev/seed-info` admin fallback once a real investor session exists. No structural change.
- **S10 onboarding** — unchanged (stays ops-assisted).
- Add an E2E harness `scripts/e2e/investor-self-commit.mjs` mirroring `investor-onboarding.mjs`: passwordless
  login → self-commit → assert own subscription + `cross_tenant` reject.

Net: the interim caveats logged in the mock's `UI_WORKORDER.md` (S12 `investor_id` via seed-info; S13 dev-password
bearer) all clear, and the investor portal becomes genuinely self-service.
