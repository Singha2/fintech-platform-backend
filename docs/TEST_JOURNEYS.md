# Test Journeys — what's built, and how to prove it

A **concrete catalogue of every end-to-end journey the backend supports today**, so you can manually test
what exists before any new work starts. Each journey lists its steps, the **role** that must run each step,
the endpoint, the key request fields, and the **end state** you should see.

- **Setup, login handshake, headers, and reset** live in [`MANUAL_TESTING.md`](MANUAL_TESTING.md) — read §0–§3
  there first. This doc assumes the app is running in the **`dev` profile** and you can log in per role.
- **One-click runnable** versions of most of these are in
  `postman/fintech-platform.postman_collection.json` (folder names in *italics* below).
- Amounts are **paise** (`100000000` = ₹10L). Rates are **bps**. Every `POST` needs
  `Authorization: Bearer <session>` + a fresh `X-Command-Id: <uuid>`; state transitions also need
  `X-Aggregate-Version: <n>` (chain it from the previous response's `aggregate_version`).

---

## Build status at a glance

| # | Journey | Module | Endpoints | Runnable now? |
|---|---|---|---|---|
| 1 | Admin / IAM (incl. API-key bootstrap) | BC10 (WS-0) | `/bootstrap/admin-users`, `/admin-users/*` | ✅ |
| 2 | Supplier onboarding | M7 / BC8 (WS-1) | `/suppliers/*` | ✅ |
| 3 | Buyer management | M8 / BC9 (WS-2) | `/buyers/*` | ✅ |
| 4 | Investor onboarding | BC7 (WS-3) | `/investors/*`, `/investor-invites/*` | ✅ |
| 5 | Deal lifecycle ("golden path") | M1–M5 | `/listings/*`, `/subscriptions/*`, `/webhooks/*`, disbursement, maturity | ✅ |
| 6 | Credit & pricing | M6 / BC3 | `/credit/*` | ✅ |

> **Not built yet (do not test):** TDS distribution + deal close (deferred to M16 Tax, DL-BE-054),
> investor login/portal flow (M10-full), real external integrations (still ACL stubs).

---

## Journey 0 — Setup & login (prerequisite for all)

1. Start Postgres, run the Flyway migrator, start the app in `dev` (MANUAL_TESTING §0).
2. Log in as each role you'll need and keep the bearer: `super`, `ops`, `treasury`, `treasury2`,
   `compliance`, `credit` — all `@dev.local`, password `DevPass123!`.
   - `POST /auth/login/password` → `GET /dev/last-otp?email=…` → `POST /auth/login/verify-otp` → `{ bearer }`.
3. `GET /dev/seed-info` → the seeded **active** `supplier_id`, `buyer_id`, `investor_id`.

*Postman: `0 · Dev helpers` then `1 · Login (one click per role)`.*

---

## Journey 1 — Admin / IAM (BC10)

### 1a. Bootstrap the first admin (API-key gated — no login needed)

The chicken-and-egg breaker: mint the first admin before any super_admin (hence any login) exists. Protected
by a **static API key** from config (`platform.bootstrap.api-key`, local default
`local-bootstrap-key-change-me`; prod overrides `PLATFORM_BOOTSTRAP_API_KEY` from a vault), passed as a bearer.

| Step | Auth | Endpoint | Body |
|---|---|---|---|
| Bootstrap super_admin | `Authorization: Bearer <api-key>` | `POST /bootstrap/admin-users` | `{ email, display_name, phone, password }` → **201** + `admin_user_id`, role **`super_admin`**, status **`active`** |
| Peek the login OTP | `Authorization: Bearer <api-key>` | `GET /bootstrap/last-otp?email=…` | → `{ code }` (works for **any** email; reads the stub since there's no real SMS yet) |

- **super_admin only** — this is the trust-chain seed. Creates a **fully active, loginable** super_admin
  (identity + role + password) in one shot. Every *other* admin/role is then created through the normal
  maker-checker `/admin-users` flow (Journey 1b), by this super_admin.
- **Full login with only the key** (any profile): bootstrap → `POST /auth/login/password` → `GET
  /bootstrap/last-otp` → `POST /auth/login/verify-otp` → bearer. The OTP peek is the non-dev counterpart of
  `/dev/last-otp`; both self-retire once a real SMS/email provider is wired (then the code goes to the phone).

**Prove the guardrails**
- Wrong or missing key (either endpoint) → **401 `unauthorized`**.
- Duplicate email → **400 `validation_failed`**; unknown email / no OTP sent yet on peek → **404 `not_found`**.

> In the **dev** profile you usually don't need this — `DevDataSeeder` already seeds six admins on startup.
> Bootstrap is the mechanism for a **fresh/non-dev** DB (and later production).

### 1b. Provision & disable via the normal flow (needs a super_admin)

| Step | Role | Endpoint | Body / notes |
|---|---|---|---|
| Provision admin | super_admin | `POST /admin-users/provision` | `{ email, display_name, phone }` → **201** (`invited`; role + password set separately) |
| Read it | any | `GET /admin-users/{id}` | status `invited` |
| Disable it | super_admin | `POST /admin-users/{id}/disable` | needs `X-Aggregate-Version` |

**Prove the guardrails**
- Provision run by `ops` (not super_admin) → **403 `role_not_held`**.
- Replay the same `X-Command-Id` + body → **200** no-op, still one row (idempotency).
- Same `X-Command-Id`, different email → **409 `command_id_payload_mismatch`**.

---

## Journey 2 — Supplier onboarding (M7 / BC8): `created → active`

*Postman: `5 · Supplier onboarding`.* PAN/GSTIN/CIN must be unique per supplier.

| # | Step | Role | Endpoint | Body |
|---|---|---|---|---|
| 1 | Create | ops | `POST /suppliers/create` | `{ legal_name, constitution_type:"private_limited", pan, gstin, cin }` → **201** |
| 2 | Grant agency consent | ops | `/{id}/grant-agency-consent` | `{ scope:["listing_intake","disbursement"] }` |
| 3 | Record identity verified | ops | `/{id}/record-identity-verified` | — |
| 4 | Submit KYC | ops | `/{id}/submit-kyc` | — |
| 5 | Record KYC approved | **compliance** | `/{id}/record-kyc-approved` | — (maker≠checker vs step 4) |
| 6 | Submit financial profile | ops | `/{id}/submit-financial-profile` | `{ top_buyers:[{buyer_name, annual_turnover_paise}] }` |
| 7 | Record credit review | **credit** | `/{id}/record-credit-review` | `{ exposure_cap_paise, risk_rating:"BBB" }` |
| 8 | Record MAA signed | ops | `/{id}/record-maa-signed` | — |
| 9 | Activate | ops | `/{id}/activate` | — → status **`active`** |

**Prove the guardrails**
- Activate before MAA-signed → **4xx** (SA8.2 gate); status unchanged.
- Credit review by `ops` → **403 `role_not_held`** (SoD).
- `exposure_cap_paise` over ₹10 Cr → **four-eyes required**.
- KYC approved by the same person who submitted → **409 `checker_equals_maker`**.
- Branch: `record-kyc-rejected {reason}` → `resubmit-kyc` re-enters the KYC loop.

---

## Journey 3 — Buyer management (M8 / BC9): `nominated → active`

*Postman: `6 · Buyer management`.* GSTIN/MCA-CIN must be unique per buyer.

| # | Step | Role | Endpoint | Body |
|---|---|---|---|---|
| 1 | Nominate | **credit** | `POST /buyers/nominate` | `{ legal_name, mca_cin, gstin, sector:"manufacturing" }` → **201** |
| 2 | Record identity verified | ops | `/{id}/record-identity-verified` | — |
| 3 | Record credit assessment | **credit** | `/{id}/record-credit-assessment` | `{ credit_limit_paise }` |
| 4 | Start engagement | ops | `/{id}/start-engagement` | — |
| 5 | Designate ack user | ops | `/{id}/designate-ack-user` | `{ email, phone, display_name }` → provisions an **OTP-only** login identity |
| 6 | Confirm payment instruction | ops | `/{id}/confirm-payment-instruction` | — |
| 7 | Activate | ops | `/{id}/activate` | — → status **`active`** |

**Prove the guardrails**
- Activate with no ack-user → **4xx** (BA.3 gate); status unchanged.
- Credit assessment by `ops` → **403 `role_not_held`**.
- Second ack-user with a duplicate email → clean **400 `validation_failed`** (not a 500).
- `credit_limit_paise` = 0 or negative → **400 `validation_failed`**.

> To run a **deal** (Journey 5) against a buyer you onboarded here, first set a **pricing band** for it
> (Journey 6) — the *seeded* buyer already has one; a fresh buyer does not.

---

## Journey 4 — Investor onboarding (BC7): `signed_up → active`

Invite-gated (C20): the invite stores only SHA-256 of email/phone; sign-up must match. *(Postman: not yet a
folder — use `.http`.)* Valid `sub_type`: `resident_individual`.

| # | Step | Role | Endpoint | Body |
|---|---|---|---|---|
| 1 | Issue invite | **compliance** | `POST /investor-invites/issue` | `{ email, phone }` → **201** + `aggregate_id` = **invite_id** |
| 2 | Sign up | ops | `POST /investors/sign-up` | `{ invite_id, email, phone, sub_type:"resident_individual" }` → **201** + investor_id (email/phone **must match** the invite) |
| 3 | Record identity verified | ops | `/investors/{id}/record-identity-verified` | `{ pan, aadhaar_last4 }` |
| 4 | Submit KYC | ops | `/investors/{id}/submit-kyc` | — |
| 5 | Assess suitability | **compliance** | `/investors/{id}/assess-suitability` | `{}` (or `{ mismatch:true }` to force the override path) |
| 5a | *(if mismatch)* Acknowledge override | ops | `/investors/{id}/acknowledge-suitability-override` | `{ override_text }` |
| 6 | Complete financial profile | ops | `/investors/{id}/complete-financial-profile` | `{ bank_account_last4 }` |
| 7 | Record KYC approved | **compliance** | `/investors/{id}/record-kyc-approved` | — (maker≠checker vs step 4) |
| 8 | Record MIA signed | ops | `/investors/{id}/record-mia-signed` | — |
| 9 | Activate | ops | `/investors/{id}/activate` | — → status **`active`**, `kyc_refresh_due_at` set |

**Prove the guardrails**
- Sign up with an email that doesn't match the invite → **4xx** (C20 gate); no account created.
- Assess suitability by `ops` → **403 `role_not_held`**.
- Malformed `pan` / `aadhaar_last4` / `bank_account_last4` → **400 `validation_failed`** (never a DB 500).
- KYC approved by the submitter → **409 `checker_equals_maker`**.
- Branch: `record-kyc-rejected {reason}` → `resubmit-kyc`.

---

## Journey 5 — Deal lifecycle / "golden path" (M1–M5)

The full money-flow spine for one invoice, using the **seeded** (or freshly-onboarded + priced)
supplier/buyer/investor. **Fully detailed in [`MANUAL_TESTING.md`](MANUAL_TESTING.md) §5** — that is the
authoritative step list; summarised here so this catalogue is complete. *(Postman: `2 · Golden Path`, runs
top-to-bottom with auto-chained ids/versions + a scripted HMAC webhook.)*

`create listing [ops]` → `start / record ×7 / complete ops-checks [ops]` → `record buyer-ack [ops]` →
`snapshot-and-ready {rate_bps} [ops]` → `approve-go-live [treasury]` (maker≠checker) → `subscribe commit [ops]`
→ `inflow webhook (HMAC)` → `assignment request → complete-signing [ops]` →
`disbursement draft [treasury] → approve [treasury2]` (maker≠checker) → `record-maturity [ops]` →
**`matured_payment_received`**.

**Prove the guardrails / branches**
- Go-live approved by the same Ops who prepared it → **409 `checker_equals_maker`**.
- Disbursement approved by the same Treasury who drafted it → **409 `checker_equals_maker`**.
- Stale `X-Aggregate-Version` on any listing step → **409 `version_conflict`**.
- `snapshot-and-ready` before all ops-checks pass → **422 `operational_checks_incomplete`**.
- Alternate paths that exist: `declare-funding-shortfall`, subscription `cancel` / `record-refund`,
  assignment `declare-incomplete` / `record-leg-failed` / `reinitiate-leg`.

---

## Journey 6 — Credit & pricing (M6 / BC3)

*Postman: `3 · Credit (M6)`.* Needed to price a buyer before its listings can go live at a rate.

| Step | Role | Endpoint | Body |
|---|---|---|---|
| Set pricing band | credit | `POST /credit/pricing-bands` | `{ buyer_id, tenor_bucket:"31_60d", rate_range_min_bps, rate_range_max_bps, fee_bps }` |
| Set buyer credit profile | credit | `POST /credit/buyers/{id}/profile` | `{ sector, rating_source, rating, credit_limit_paise, tenor_cap_days }` |
| Set supplier credit profile | credit | `POST /credit/suppliers/{id}/profile` | `{ risk_rating, exposure_cap_paise }` |

**Prove the guardrails**
- Buyer profile with `credit_limit_paise` > ₹10 Cr → **422 `four_eyes_required`**.
- Any of these run by a non-credit role → **403 `role_not_held`**.

---

## The five non-negotiables — pick any command and prove them

Every state-changing command is all five; a quick cross-cutting checklist you can apply anywhere:

1. **Maker-checker** — approver ≠ proposer, else **409 `checker_equals_maker`**.
2. **MFA-fresh** — a stale session on a sensitive command → **401 `mfa_assertion_expired`** (re-login).
3. **SoD** — wrong role → **403 `role_not_held`**.
4. **Idempotent** — replay same `X-Command-Id` → **200** no-op, no duplicate row.
5. **Audit-logged** — every command appends one chained `sys_audit_event`
   (`SELECT event_type FROM sys_audit_event ORDER BY occurred_at` is the deal's life story).

---

## Suggested first session (one pass over everything real)

1. Journey 0 — log in as every role, grab `seed-info`.
2. Journey 1 — provision + disable an admin (auth, IAM, idempotency, SoD).
3. Journey 5 — full golden path on the **seeded** counterparties (the whole money flow + maker-checker).
4. Journeys 2–4 — onboard fresh supplier / buyer / investor from scratch.
5. Journey 6 — price your fresh buyer, then re-run Journey 5 against it.
