# BACKEND WORK ORDER — Investor self-login (read-only now, investor-initiated payments next)

> **What this is.** A backend **module brief** for enabling an **investor to self-log-in**. It is scoped in two
> phases the founder asked for explicitly:
> - **Phase A (do now) — Option A:** investor **read-only** self-login, reusing the existing
>   `/auth/login/password` + OTP flow (add an investor credential). Investor can browse the marketplace and view
>   *their own* portfolio; they cannot write (they hold no roles → writes are already rejected).
> - **Phase B (build right after) — Option B:** **passwordless invite→email+OTP** investor login (DL-021 pattern,
>   like buyer WS-2/BE-15) **plus investor-initiated self-commit** — because *payments will be made by investors*.
>
> Build Phase A so Phase B is a small additive extension, not a rewrite (see §6).
>
> **This is a brief, not a spec.** Take it through the repo's spec-driven loop
> (`docs/spec/Spec_Driven_Build_Plan.md` §D: `/specify → /clarify → DoR → /plan → /tasks → /implement → DoD`).
> Every state-changing command still satisfies the five non-negotiables (CLAUDE.md). Append `DL-BE-xxx` entries
(highest currently used is **DL-BE-083** → start new ones at **DL-BE-084**). The highest registered read-surface
ID is **BE-16**, so **BE-17/BE-18 below are net-new** (not yet in the tracker).
>
> **Relationship to existing docs (don't duplicate):** the investor *reads* are already specced as **BE-14** in
> `UI_INTEGRATION_BACKEND_SPEC.md` (marketplace + portfolio, ownership-scoped, M10-full); the passwordless auth
> *pattern* is **BE-15 / WS-2 / DL-021** (buyer ack-user). This work order adds the missing piece — **investor
> authentication** — and pulls BE-14 forward as its read surface. Register the new items (**BE-17, BE-18**) in
> `PROJECT_TRACKER.md` §3 and flip the S11/S13 board cells when they land.

---

## 0. Scope & non-goals

**In scope:** investor authentication (Option A now, Option B next) + the ownership-scoped investor reads that
make a read-only login useful + investor self-commit (Phase B).

**Explicitly NOT in scope (stay ops-on-behalf / deferred, per the founder's current intent):**
- Supplier self-login — suppliers are acted-on by ops via agency consent; no supplier login (matches BE-11 note).
- Buyer ack-user login — separate passwordless flow already tracked as **BE-15 / WS-2**.
- Auditor login — **M17 / BE-13**.
- The mock's composite `ops-treasury` persona — a mock-only convenience; live keeps maker≠checker across
  `ops@`/`treasury@`/`treasury2@`.

---

## 1. What already exists (reuse — do NOT rebuild)

Verified in code; these make Phase A small:

| Capability | Where | Consequence |
|---|---|---|
| Password auth is **kind-agnostic** — looks up `auth_identity` by email, checks `auth_credential(kind='password')` | `AuthService.authenticatePassword` | An investor with a password logs in through the **existing** `/auth/login/password` → OTP flow, unchanged. |
| `setPassword(identityId, rawPassword)` works for **any** identity (**Argon2id** via `Argon2PasswordEncoder`, one active password/identity — revokes prior, inserts new) | `AuthService.setPassword`, `AuthConfig` | Option A credential issuance is one call — the question is *where in the investor lifecycle* it's called (§2, A1). |
| OTP issuance is **decoupled** from the password step (`issueLoginOtp(identityId)` → `verify-otp` → `establishSession`) | `AuthController`, `AuthService`, `SessionService` | Option B (passwordless) just calls `issueLoginOtp` after validating the invite/email — no password. Same session machinery. |
| Session established for **any** identity kind (`TenantClaims.empty()` for non-admin today) | `AuthController.verifyOtp` | An investor gets a normal bearer once authenticated. |
| **Reads are authenticated-only, not role-gated** (`anyRequest().authenticated()`) | `SecurityConfig` | An investor bearer can call GET reads; **writes stay rejected** because commands are role-gated deeper and investors hold no roles → **read-only falls out of the design**. |
| `/auth/session` returns `identity_id`, `kind ∈ {admin_user, investor, acknowledgment_user, auditor}`, `roles[]` | `SessionController` (BE-1) | Already investor-aware. Needs one addition: the **investor_id** (§2, A2). |
| `inv_account` links `identity_id ↔ investor_id` | `inv_account(investor_id, identity_id, …)` | The ownership-scoping join for reads **and** self-commit: `SELECT investor_id FROM inv_account WHERE identity_id = ?`. |
| `commit` is an **ops-on-behalf** command, hard-wired to an `admin_user` actor (`CommandRequest(…, "admin_user", SENSITIVE)`) and gated on the **`OPS` admin role** via `gateway.execute(request, OPS, …)` | `SubscriptionController:58`, `SubscriptionService.commit` | Phase B must widen this to accept an **investor** session committing for **their own** investor_id (§3, B2). *(NB: `roles.adminUserId(request.actorId())` is in `recordRefund`/buyer code, **not** in `commit` — the B2 target is the `OPS`-role gate + the `"admin_user"` literal actor.)* |
| Subscription **inflow** (the actual money) is confirmed by the **banking webhook**, not a UI command. The webhook advances **`committed → confirmed` directly — there is no intermediate `funds_received` status** in this skeleton (`SettlementService.recordReconciledInflow` → `SubscriptionService.confirmFromInflow`) | `SubscriptionController` header comment; `banking/`, `settlement/`, `subscription/` | "Payments by investors" = investor **self-commit** + transfer to the VA; the webhook confirms. No new payment API. |

---

## 2. PHASE A — Investor read-only self-login (Option A) · **BE-17** · target milestone **M10-full**

Goal: `investor@dev.local` (and real investors) log in via the existing password+OTP flow and see a **read-only**
portal — live marketplace + their own portfolio. No new write paths.

### A1 — Investor credential issuance (the one real design question)
- **Dev:** in `DevDataSeeder.seedActiveInvestor`, add `auth.setPassword(identityId, PASSWORD)` so
  `investor@dev.local` can log in (today it has an identity but **no** password credential → cannot log in).
- **Production (must be decided in `/clarify`):** where does a real investor's credential come from? Options to
  weigh — (i) a **set-password step** in investor onboarding (e.g. at `activate`, or invite-acceptance), (ii) a
  dedicated `POST /investors/{id}/set-credential` command, (iii) skip passwords entirely for real investors and
  make **Option B (passwordless)** the production path, keeping Option A as the **dev/pilot** login only.
  **Recommendation:** treat Option A's password as the **dev/pilot** login; do not add a heavyweight
  password-management surface (reset/rotate/lockout) for investors — that effort is better spent shipping Option
  B. Flag the choice in `/clarify`; it decides how much A1 costs.
- **Guardrail:** no schema change — `auth_credential` already supports it. If `/clarify` picks a new command,
  that command is a normal state-change (idempotent + audited); no maker-checker (self-service, not admin).

### A2 — `/auth/session` returns `investor_id` (small BE-1 extension)
- Add `investor_id` (nullable) to the `/auth/session` body: when `kind='investor'`, resolve
  `SELECT investor_id FROM inv_account WHERE identity_id = ?`. Additive, non-breaking (admins get `null`).
- **Why:** the UI portal needs to know *which* investor to scope reads to without trusting a client-supplied id.
- **Idiom (DRY):** persistence is **raw `JdbcTemplate`** everywhere — there are **zero JPA `@Entity`/repositories** in
  this codebase. Write the resolver as native SQL, not JPQL. A near-identical `identity_id → investor_id` join
  already lives in `DevController` (`inv_account JOIN auth_identity ON email`). **Build the resolver once**
  (`identity_id → investor_id`, server-side) and reuse it for A2 (session), A3 (read scoping), A5 (KYC gate), and
  B2 (self-commit) — do not re-query ad hoc in each place. The `idx_inv_account_identity` index + `UNIQUE(identity_id)`
  make it cheap.
- **`/clarify` decision — where does `investor_id` live?** `TenantClaims.forInvestor(investorId)` already exists as a
  factory but is **unused**: today `AuthController.verifyOtp` passes `TenantClaims.empty()` for **every** kind
  (admins included). Option (a) resolve the join per-request (this section as written); option (b) populate
  `forInvestor(investorId)` at `establishSession` time so `investor_id` flows through the session claims **once** and
  `/auth/session` + reads read it from the session. (b) is the cleaner DRY path. Decide in `/clarify`.

### A3 — Investor reads, ownership-scoped (this is **BE-14**, pulled forward)
> **These two reads are in different states — do not treat them as one task.**
- `GET /listings?status=live` — investor-visible marketplace (G9 / S11). **Already EXISTS** (`ListingController.list`,
  BE-6) but is admin-scoped: the `session` is injected and never used for scoping. A3 here = **add an ownership/kind
  branch** to the existing endpoint (investor bearer → only `live` listings), not build it from scratch.
- `GET /investors/{id}/subscriptions` + summary — portfolio (G11 / S13). **Does NOT exist at all** — full build.
  (Today the only subscription read is the admin-shaped `GET /listings/{listingId}/subscriptions/{subscriptionId}`.)
- **Ownership rule (the reason BE-14 was deferred):** an investor bearer may read **only their own**
  `investor_id` (from A2's resolver) and **only live** listings. Reject cross-investor reads (403/404). Admin bearers
  keep the un-scoped view. Reuse the A2 resolver **now** — Phase B reuses it for self-commit.
- **Effort:** L overall, but weighted toward the net-new portfolio read (the listings change is S). Native
  `JdbcTemplate` SQL over existing tables; no migration expected — flag if any appears.

### A4 — Read-only is automatic — verify, don't build
- Confirm (test) that an **investor bearer** calling any write (e.g. `POST …/subscriptions/commit`,
  onboarding commands) is rejected — investors hold no admin role, and `commit` requires `admin_user`. The point
  of A4 is a **regression test** locking in "investor = read-only in Phase A", not new code.

### A5 — Invoice-PDF download gate (KYC'd investor)
- Per BE-14 / API_CATALOGUE §Invoice artifacts: an investor may download a listing's invoice document **only if
  KYC-approved**. Enforce on the investor-facing document read. (Admin path unchanged.)
- **Fill the existing seam, don't invent one (DRY):** `InvoiceDocumentService.download` already **documents a deferred
  `InvestorQueryPort` KYC gate** (see `InvoiceDocumentService.java:43-45,134-137`; today the only gate is
  listing-status membership in `DOWNLOADABLE_LISTING_STATUSES`). A5 = **implement that already-declared port**, wired
  through the A2 resolver — not a new mechanism.

### Phase A — Definition of done
1. `investor@dev.local` logs in (password `DevPass123!` → OTP via `/dev/last-otp`) and gets a bearer.
2. `/auth/session` for that bearer returns `kind:"investor"`, `roles:[]`, `investor_id:<uuid>`.
3. That bearer: **can** read `GET /listings?status=live` and its **own** `…/subscriptions`; **cannot** read
   another investor's subscriptions; **cannot** run any write command (regression test green).
4. Every non-negotiable satisfied on any new command; `DL-BE` entry written; `PROJECT_TRACKER.md` BE-14 + BE-17
   flipped and S11/S13 "backend" cells updated.

---

## 3. PHASE B — Investor-initiated payments (Option B) · **BE-18** · target milestone **M11-full**

Goal: investors authenticate **passwordless** and **commit their own subscriptions** (then transfer funds to the
VA; the banking webhook confirms). This is the "payments by investors" milestone.

### B1 — Passwordless invite→email+OTP investor login (DL-021 pattern)
- A **new** open auth path (NOT `/auth/login/password`): validate the investor's invite/email → `issueLoginOtp`
  → `verify-otp` → session. No password. Investors created via invite need never hold a password.
- **There is nothing to copy yet — B1 is the *first* implementer of the DL-021 pattern.** BE-15 / WS-2 (the buyer
  ack-user login) is **also deferred and unbuilt** — the ack user is *defined* as OTP-only (`BuyerService.designateAckUser`)
  but **no login route consumes that path**. So B1 is built from the existing primitives — `AuthService.issueLoginOtp`
  + `verifyOtp` + `SessionService.establishSession` — replacing step 1's password precondition with an invite/email
  lookup. Whoever ships B1 or BE-15 first sets the reusable shape for the other; note that in the DL-BE entry.
- Additive to `SecurityConfig` permitAll matchers (a new open login route), like `/auth/login/**`.

### B2 — Investor self-commit (the authz change) · **the real hard part of Phase B**
- Widen `subscription.Subscription.Commit` to accept an **investor-kind** session committing for **their own**
  `investor_id`: resolve the caller's `investor_id` via the A2 resolver and **assert it equals** the target —
  reject otherwise (ownership). Keep the existing **ops-on-behalf** admin path working (both actors allowed).
- **What actually changes in code:** today `commit` sets the actor literal `"admin_user"` (`SubscriptionController:58`)
  and gates on the `OPS` admin role via `gateway.execute(request, OPS, …)`. B2 replaces **that** with an
  actor-kind-aware path. *(The `roles.adminUserId(...)` call the earlier draft cited is in `recordRefund`/buyer code,
  not `commit` — don't chase it here.)*
- **The architectural friction (settle in `/clarify` before any B code):** `CommandGateway` assumes an **admin**
  actor. Its role-gated overload `execute(req, requiredRoles, …)` **rejects any no-role caller** (`role_not_held`),
  and its no-role overload `execute(req, …)` skips SoD/role **entirely**. An investor self-commit fits neither: it
  needs a **non-admin authorization path that asserts `caller.investor_id == target.investor_id`** in place of the
  role check. This is the one genuinely new piece of Phase B — everything else is additive.
- **Controls:** idempotent on `command_id` (already), audit-logged (already), **MFA-fresh** = the investor's
  OTP/session freshness. **Maker-checker does NOT apply** — a self-commit has no separate approver.

### B3 — Money movement stays as-is
- The investor does **not** call a "pay" API. After self-commit they transfer funds to the listing VA; the
  existing **banking webhook** advances the subscription **`committed → confirmed` directly** (via
  `SettlementService.recordReconciledInflow` → `SubscriptionService.confirmFromInflow`, paise-exact match). **There
  is no intermediate `funds_received` status** in this skeleton. No new endpoint.

### Phase B — Definition of done
1. An invited investor logs in **passwordless** (email→OTP), no password credential.
2. That investor bearer **self-commits** a subscription for **their own** investor_id (envelope returned);
   committing for a *different* investor_id is rejected.
3. Ops-on-behalf commit still works (no regression). Non-negotiables satisfied; `DL-BE` entries; tracker BE-18
   flipped; S11/S13/S12 write paths noted.

---

## 4. Design-for-extensibility — how Phase A keeps Phase B cheap

Build A so B is additive:
- **Don't couple the portal to "password".** All read/session logic keys off the **bearer + `kind` + roles +
  investor_id**, never "has a password". B's passwordless login produces the *same* bearer → the portal doesn't
  change.
- **Build the ownership-scoping helper in A2/A3** (`identity_id → investor_id`, "is this the caller's own
  investor?"). B2's self-commit **reuses the exact same helper** — that's the main shared foundation.
- **Keep the OTP/session path generic** (it already is). B1 is "issue OTP without the password step" — a new
  entry route, not a new session mechanism.
- Net: A ships the credential + scoped reads + the ownership helper; B adds one open login route + one authz
  widening. No rework of A.

---

## 5. Guardrails (design-preserving — the founder asked us not to break the original design)
- **Additive only.** Reuse `auth_identity`/`auth_credential`/`inv_account` and the existing auth/session/OTP
  machinery. **No schema migration is expected** for Phase A; if `/plan` finds one is needed, stop and flag it.
- **Reads un-role-gated stays true** — A3 adds *ownership* scoping for the investor variant, it does not add
  role-gating to admin reads.
- **The five non-negotiables** apply to any new command (A1 set-credential if chosen, B2 self-commit): idempotent
  + audited always; MFA-fresh always; maker-checker/SoD **only where an admin actor is involved** (a self-service
  investor action has no maker-checker).
- **One module in flight; DoR before code; write the invariant test first; `/code-review` before DoD.**
- **`docs/spec/` is read-only input.** Milestone labels (M10-full portal reads, M11-full investor login) are the
  repo's own; confirm exact module IDs against `docs/spec/Spec_Driven_Build_Plan.md` when slotting.

---

## 6. Touchpoints (indicative — `/plan` owns the final list)
- `auth/` — `AuthService.setPassword` (reuse), a passwordless login route in `AuthController` (B1),
  `SessionController` `/auth/session` +`investor_id` (A2), `SecurityConfig` new permitAll matcher (B1).
- `dev/DevDataSeeder.seedActiveInvestor` — `setPassword` for `investor@dev.local` (A1 dev).
- `investor/` + a read surface for BE-14 (A3) with the ownership helper; the KYC-download gate (A5).
- `subscription/SubscriptionService.commit` + `SubscriptionController` — actor-kind-aware authz (B2).
- `docs/`: `UI_INTEGRATION_BACKEND_SPEC.md` (register BE-17/BE-18 next to BE-14/BE-15), `PROJECT_TRACKER.md`
  (§3 rows + S11/S13 cells), `DECISION_LOG.md` (`DL-BE`).

---

## 7. Handoff to the front-end
The mock (`../fintech-patform-ui`) will add an investor login entry + read-only presentation **after** Phase A
is green (founder's sequence: backend first). The UI persona mapping for this is already documented in the mock's
`docs/API_ALIGNMENT.md §1.4` (the `investor` persona is intentionally *not* live-mapped until this lands). When
Phase A is done, notify the mock side to start its investor-login increment against the real bearer.
