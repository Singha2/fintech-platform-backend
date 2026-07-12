# Manual testing & local debugging

A practical guide to **running the backend locally, driving every controller by hand, and stepping through
the code** to understand it. Nothing here changes production behaviour — the dev helpers are all
`@Profile("dev")`.

---

## 0. TL;DR — zero to a running, testable app

> **Three ways to drive the API once the app is up:** (a) **Postman** — import
> `postman/fintech-platform.postman_collection.json` (one-click OTP login per role; the **Golden Path** folder
> runs a whole deal `listed → matured` with auto-chained ids/versions + a scripted HMAC webhook). (b)
> `manual-test.http` in IntelliJ/VS Code. (c) `scripts/dev-smoke.sh` for a one-shot end-to-end check.

```bash
# 1. Postgres (db/user/pw = platform / platform / avc@2026)
docker run -d --name avc-pg -p 5432:5432 \
  -e POSTGRES_DB=platform -e POSTGRES_USER=platform -e POSTGRES_PASSWORD='avc@2026' postgres:16-alpine

# 2. Build the schema (Flyway autostart is OFF; run the standalone migrator once)
./mvnw -q compile exec:java -Dexec.mainClass=com.arthvritt.platform.infrastructure.migration.FlywayMigrationRunner

# 3. Run the app in the dev profile (seeds admins + counterparties, enables /dev helpers)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

App is at `http://localhost:8080`. Now either open **`manual-test.http`** in IntelliJ (run requests one by
one, breakpoints work) or run **`scripts/dev-smoke.sh`** (drives the whole money-flow spine in one go).

> Don't have the `exec` plugin? Alternatives for step 2: in IntelliJ right-click
> `FlywayMigrationRunner.main()` → Run; or `./mvnw -q -Dexec.mainClass=...:exec:java` once the schema exists
> the app boots with `ddl-auto=validate`.

---

## 1. What the dev profile gives you

`-Dspring-boot.run.profiles=dev` wires two beans that exist in **no other profile**:

- **`DevDataSeeder`** — on startup, if `admin_user` is empty, seeds:
  - **6 admins**, all with password **`DevPass123!`**:

    | email | role | use for |
    |---|---|---|
    | `super@dev.local` | super_admin | provisioning other admins |
    | `ops@dev.local` | ops_executive | listings, ops-checks, ack, subscribe, assignment, maturity |
    | `treasury@dev.local` | treasury_and_settlement | go-live checker, disbursement **maker**, refunds |
    | `treasury2@dev.local` | treasury_and_settlement | disbursement **checker** (≠ maker) |
    | `compliance@dev.local` | compliance_reviewer | invites, suitability, KYC approve/reject |
    | `credit@dev.local` | credit_reviewer | buyer nominate / credit |
  - **one active supplier, buyer (+ active ack user), investor, and a pricing band** (tenor `31_60d`,
    rate `[1000,1500]`, fee `200` bps) — so a listing can go live → … → mature immediately, no onboarding.
- **`DevController`**:
  - `GET /dev/last-otp?email=ops@dev.local` → `{ "code": "123456" }` (the SMS-OTP the stub "sent").
  - `GET /dev/seed-info` → `{ supplier_id, buyer_id, investor_id, admins_password }`.

Re-running is safe: the seeder skips if `admin_user` already has rows. To re-seed, reset the DB (§7).

---

## 2. How auth works (and the OTP shortcuts)

Login is **two steps** (password → SMS-OTP), and every state-changing command then carries a **bearer**:

```
POST /auth/login/password   {email, password}            → { challenge_id }
GET  /dev/last-otp?email=…                                → { code }          ← dev profile only
   or  GET /bootstrap/last-otp?email=…  (Bearer <api-key>) → { code }          ← any profile, API-key gated
POST /auth/login/verify-otp {challenge_id, code}          → { bearer }        ← this is your session id
```

**Why two OTP peeks?** The code is generated at the password step, stored **hashed** in `auth_otp_challenge`,
and "sent" via the in-process `StubNotifier` (no real SMS/SMTP yet). The plaintext lives only in the stub's
memory — never in the DB, never in the logs. Two ways to read it without a real provider: `/dev/last-otp`
(dev profile) or `/bootstrap/last-otp` (any profile, gated by `platform.bootstrap.api-key`; works for any
email). Both self-retire once a real SMS/email `NotificationChannel` is wired at the Production gate — then
the code goes to the actual handset/inbox.

Use the bearer on every other call: `Authorization: Bearer <bearer>`. One login → one session you reuse for
many commands. **MFA freshness:** the session's MFA is "fresh" for a short window; sensitive commands
(go-live, disbursement) past that window return `401 mfa_assertion_expired` → just log in again.

---

## 3. The command envelope (the headers that trip people up)

Every **state-changing** command (`POST …`) needs:

| Header | When | What |
|---|---|---|
| `Authorization: Bearer <id>` | always | your session (from login) |
| `X-Command-Id: <uuid>` | always | **idempotency key** — a fresh UUID per *new* command; replay the *same* one and the command is a no-op returning the original result (B4 §2.4) |
| `X-Aggregate-Version: <n>` | every command that **advances an existing aggregate** | optimistic lock — must equal the aggregate's current version. **The command response returns the new `aggregate_version`** in its body, so chain it from the previous response (or read the aggregate's `GET`). |

Which commands need `X-Aggregate-Version`? Every **transition on an existing aggregate**: the **listing**
lifecycle (`start-ops-checks`, `record-ops-check`, `complete-ops-checks`, `request-buyer-ack`,
`record-buyer-ack`, `snapshot-and-ready`, `approve-go-live`, `declare-funding-shortfall`), subscription
`cancel` / `record-refund`, and every **onboarding** transition on a supplier / buyer / investor (everything
after their create/nominate/sign-up step). **Creating** commands are version-`0` and take no header:
`POST /listings`, `/suppliers/create`, `/buyers/nominate`, `/investor-invites/issue`, `/investors/sign-up`,
`/admin-users/provision`, subscription `commit`. (Assignment, disbursement, and maturity are version-`0` too —
they key off the listing, not their own aggregate version.)

**Reading the response / errors:** success is `200/201` with `{ aggregate_id, aggregate_version,
emitted_events[], correlation_id }`. Errors are a flat `{ error_code, message, … }` (B4 §4.1). Common codes:

| HTTP | error_code | meaning |
|---|---|---|
| 400 | `validation_failed` | malformed/missing field, bad format (PAN, amount, etc.) |
| 401 | `mfa_assertion_expired` / `bearer_*` | session/MFA stale or missing → re-login |
| 403 | `role_not_held` | the actor lacks the role this command needs (SoD) — use the right admin |
| 409 | `checker_equals_maker` / `version_conflict` / `command_id_payload_mismatch` | maker==checker, stale `X-Aggregate-Version`, or a reused command id with a different body |
| 422 | `operational_checks_incomplete` / `verification_failed` / `suitability_override_required` | an invariant blocked it |

---

## 4. Endpoint map (all controllers)

- **Auth** — `POST /auth/login/password`, `POST /auth/login/verify-otp`
- **Bootstrap (API-key)** — `POST /bootstrap/admin-users` — mints the first active **super_admin**;
  `Authorization: Bearer <platform.bootstrap.api-key>` (no session), body `{email, display_name, phone, password}`.
  `GET /bootstrap/last-otp?email=…` (same key) — the non-dev OTP peek: returns the login OTP the stub "sent"
  to any email, so bootstrap → login works headlessly in any profile until a real SMS provider is wired.
- **Admin IAM** — `POST /admin-users/provision`, `POST /admin-users/{id}/disable`, `GET /admin-users/{id}`
- **Supplier (BC8)** — `POST /suppliers/create` · `/{id}/grant-agency-consent` · `/record-identity-verified` ·
  `/submit-kyc` · `/record-kyc-approved` · `/submit-financial-profile` · `/record-credit-review` ·
  `/record-maa-signed` · `/activate` · `GET /{id}`
- **Buyer (BC9)** — `POST /buyers/nominate` · `/{id}/record-identity-verified` · `/record-credit-assessment` ·
  `/start-engagement` · `/designate-ack-user` · `/confirm-payment-instruction` · `/activate` · `GET /{id}`
- **Investor (BC7)** — `POST /investor-invites/issue` · `/investors/sign-up` ·
  `/investors/{id}/record-identity-verified` · `/submit-kyc` · `/assess-suitability` ·
  `/acknowledge-suitability-override` · `/complete-financial-profile` · `/record-kyc-approved` ·
  `/record-kyc-rejected` · `/resubmit-kyc` · `/record-mia-signed` · `/activate` · `GET /investors/{id}`
- **Listing (BC1)** — `POST /listings` · `/{id}/start-ops-checks` · `/record-ops-check` ·
  `/complete-ops-checks` · `/request-buyer-ack` · `/record-buyer-ack` · `/snapshot-and-ready` ·
  `/approve-go-live` · `/declare-funding-shortfall` · `GET /{id}`
- **Subscription (BC2)** — `POST /listings/{id}/subscriptions/commit` · `/subscriptions/{id}/cancel` ·
  `/subscriptions/{id}/record-refund` · `GET /listings/{id}/subscriptions/{subId}`
- **Banking webhook (BC18 inbound)** — `POST /webhooks/banking/{vendor}/inflow.received` (HMAC-signed, §6)
- **Assignment (BC5)** — `POST /listings/{id}/assignment-set/request` · `/complete-signing` ·
  `/declare-incomplete` · `/record-leg-failed` · `/reinitiate-leg` · `GET /listings/{id}/assignment-set`
- **Disbursement (BC4)** — `POST /listings/{id}/disbursement/draft` · `/approve` · `GET /{id}/disbursement`
- **Maturity (BC4)** — `POST /listings/{id}/record-maturity`

---

## 5. The golden path (the money-flow spine)

This is the full lifecycle one invoice walks. Drive it interactively with **`manual-test.http`** or all at
once with **`scripts/dev-smoke.sh`**. Roles in brackets; amounts in paise (`100000000` = ₹10L).

1. **login** ops, treasury, treasury2 (capture each bearer)
2. **`GET /dev/seed-info`** → supplier_id, buyer_id, investor_id
3. `POST /listings` **[ops]** `{supplier_id, buyer_id, invoice_number, face_value_paise:100000000, invoice_date:"2026-06-01", tenor_days:60}` → **listing_id**
4. `POST /listings/{id}/start-ops-checks` **[ops]**
5. `POST /listings/{id}/record-ops-check` **[ops]** ×7 — `irn_validity` (no outcome), then `eway_bill_match`,
   `buyer_supplier_relationship`, `duplicate_check`, `supplier_exposure_cap`, `buyer_limit_headroom`,
   `document_completeness` each `{outcome:"passed"}`
6. `POST /listings/{id}/complete-ops-checks` **[ops]** → `awaiting_acknowledgment`
7. `POST /listings/{id}/record-buyer-ack` **[ops]** `{outcome:"acknowledged", method:"email"}`
8. `POST /listings/{id}/snapshot-and-ready` **[ops]** `{rate_bps:1200}` → `ready_for_review`,
   funding_target = **96027397**
9. `POST /listings/{id}/approve-go-live` **[treasury]** → `live` + a VA created
10. `GET /listings/{id}` → funding_target, **va_id**
11. `POST /listings/{id}/subscriptions/commit` **[ops]** `{investor_id, amount_paise:96027397}` →
    `fully_funded`
12. **inflow webhook** (§6) `{va_id, amount_paise:96027397, utr, event_id}` → subscription `confirmed`
13. `POST /listings/{id}/assignment-set/request` **[ops]** → set `in_progress`
14. `POST /listings/{id}/assignment-set/complete-signing` **[ops]** `{investor_id}` → `all_signed`
    (`deal_listing.all_signed=true`, the C27 gate)
15. `POST /listings/{id}/disbursement/draft` **[treasury]**
16. `POST /listings/{id}/disbursement/approve` **[treasury2]** (checker ≠ maker) → `disbursed`
17. `POST /listings/{id}/record-maturity` **[ops]** `{amount_paise:100000000, utr}` →
    `matured_payment_received`

*(Beyond §17 — TDS distribution + close — is deferred until M16 Tax; see DL-BE-054.)*

---

## 6. The inflow webhook (HMAC)

`/webhooks/banking/{vendor}/inflow.received` is **not** bearer-authed — it's verified by an HMAC over
`"{timestamp}.{body}"` with the secret `platform.webhook.banking.secret` (dev default
`dev-banking-webhook-secret-change-me`). Headers: `X-Timestamp` (epoch millis) + `X-Signature` (hex
HMAC-SHA256). `scripts/dev-smoke.sh` builds this with `openssl`; the IntelliJ `.http` client can't HMAC
easily, so use the script (or a pre-request script) for step 12.

```bash
SECRET='dev-banking-webhook-secret-change-me'
BODY='{"va_id":"<va>","amount_paise":96027397,"utr":"UTR-1","event_id":"evt-1"}'
TS=$(($(date +%s)*1000))
SIG=$(printf '%s.%s' "$TS" "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/^.* //')
curl -s -X POST localhost:8080/webhooks/banking/stub-escrow/inflow.received \
  -H "X-Timestamp: $TS" -H "X-Signature: $SIG" -H 'Content-Type: application/json' -d "$BODY"
```

---

## 7. Debugging & inspecting state

- **Step through in IntelliJ:** Run `PlatformBackendApplication` in **Debug** with VM/program args
  `--spring.profiles.active=dev` (Run config → Active profiles: `dev`). Put breakpoints in any
  `*Service` (e.g. `ListingService.snapshotAndReady`) or `CommandGateway.execute` to watch the
  idempotency → MFA → SoD → handler → audit-append flow. Then fire the request from `manual-test.http`.
- **Watch the flow in the console:** the dev profile sets `com.arthvritt.platform` to DEBUG.
- **Inspect state with SQL** (psql: `docker exec -it avc-pg psql -U platform -d platform`):
  ```sql
  SELECT status, funding_target, committed_total, all_signed, va_id FROM deal_listing;
  SELECT status, amount FROM sub_subscription;
  SELECT event_type, aggregate_type, occurred_at FROM sys_audit_event ORDER BY occurred_at DESC LIMIT 20;
  SELECT status, kind, gross_amount FROM cash_payout_instruction;
  ```
- **The audit chain** is the source of truth for "what happened": every command appends exactly one
  `sys_audit_event` (cryptographically chained). `SELECT event_type FROM sys_audit_event ORDER BY occurred_at`
  is a literal log of the deal's life.
- **Understanding a module:** read its DL entry (`docs/DECISION_LOG.md`, `DL-BE-xxx`) + its spec
  (`docs/modules/*.md`) alongside the `*Service`. The tests (`src/test/.../*Test.java`) are runnable,
  commented examples of every path.

---

## 8. Reset / "clear cache" (start fresh)

```bash
# Nuke and recreate the schema + dev seed (fastest):
docker exec -it avc-pg psql -U platform -d platform -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;'
./mvnw -q compile exec:java -Dexec.mainClass=com.arthvritt.platform.infrastructure.migration.FlywayMigrationRunner
# then restart the app (dev) → DevDataSeeder re-seeds.

# Or throw away the whole DB container:
docker rm -f avc-pg   # then re-run the docker run from §0
```

Build cache: `./mvnw clean` (recompile). The app needs the schema at the **latest** migration (V6) or it
refuses to start (`ddl-auto=validate`).
