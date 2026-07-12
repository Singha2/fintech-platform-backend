# API Test Plan — Manual (for a new developer)

**Goal:** by the end of this you will have started the app, logged in, and manually exercised **every**
API group in `docs/API_CATALOGUE.md` — sending requests, seeing real data, and knowing what "pass" looks like.

**How to use this doc:** keep `API_CATALOGUE.md` open beside it. The catalogue is the *list* of endpoints
(path, what it does, which role). This is the *order to run them in, what to send, and what to check*.
Tick each box as you go. Deep setup/debugging detail lives in `MANUAL_TESTING.md`.

> **Fastest way to send requests:** import `postman/fintech-platform.postman_collection.json` into Postman —
> it logs in per role in one click and its **Golden Path** folder runs a whole deal end-to-end with the ids
> and versions auto-filled. Or open `manual-test.http` in IntelliJ/VS Code. The `curl` snippets below work too.

---

## 0. One-time setup (≈5 min)

```bash
# 1. Start Postgres (db/user/pw = platform / platform / avc@2026)
docker run -d --name avc-pg -p 5432:5432 \
  -e POSTGRES_DB=platform -e POSTGRES_USER=platform -e POSTGRES_PASSWORD='avc@2026' postgres:16-alpine

# 2. Build the schema (Flyway autostart is OFF — run the migrator once)
./mvnw -q compile exec:java -Dexec.mainClass=com.arthvritt.platform.infrastructure.migration.FlywayMigrationRunner

# 3. Run the app in the DEV profile (seeds test admins + counterparties, enables /dev helpers)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

**Confirm it's up:** open <http://localhost:8080/actuator/health> → `{"status":"UP"}`, then
`curl http://localhost:8080/dev/seed-info` → you should see a `supplier_id`, `buyer_id`, `investor_id`.
Those are **pre-seeded, already-active** counterparties you'll test against — that's your "see data" starting point.

---

## 1. Get a login token (do this first)

Every non-public call needs `Authorization: Bearer <token>`. Getting a token is 3 calls:

```bash
BASE=http://localhost:8080
EMAIL=ops@dev.local

# a) password → returns a challenge_id
CHALLENGE=$(curl -s $BASE/auth/login/password -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"DevPass123!\"}" | jq -r .challenge_id)

# b) read the OTP the stub "sent" (DEV only)
CODE=$(curl -s "$BASE/dev/last-otp?email=$EMAIL" | jq -r .code)

# c) verify OTP → returns your bearer token
TOKEN=$(curl -s $BASE/auth/login/verify-otp -H 'Content-Type: application/json' \
  -d "{\"challenge_id\":\"$CHALLENGE\",\"code\":\"$CODE\"}" | jq -r .bearer)
echo $TOKEN
```

**The 6 seeded accounts** (password for all = `DevPass123!`). Log in as the role a call needs (see the
catalogue's **Auth** column):

| Email | Role | Use it for |
|---|---|---|
| `ops@dev.local` | `ops_executive` | most onboarding steps, listings, ops-checks, documents, KYC/KYB attach |
| `compliance@dev.local` | `compliance_reviewer` | KYC approve/reject, investor invites, Form 16A issue |
| `credit@dev.local` | `credit_reviewer` | buyer nominate, credit assessment, pricing bands |
| `treasury@dev.local` | `treasury_and_settlement` | go-live approval, disbursement, distribution (the **maker**) |
| `treasury2@dev.local` | `treasury_and_settlement` | the **checker** — disbursement/distribution need a 2nd person |
| `super@dev.local` | `super_admin` | provision admins, assign/revoke roles |

> Get a token **per role** and keep them in shell variables (`OPS`, `COMPLIANCE`, `TREASURY`, `TREASURY2`, …).
> Postman does this automatically — one "Login" request per role.

---

## 2. The 3 rules of every write (read this — it prevents 90% of errors)

Every `POST`/`PUT` that changes data needs these:

1. **`X-Command-Id: <a fresh UUID>`** — on **every** write. Use a new UUID each time.
   *To test idempotency, send the exact same request twice with the **same** `X-Command-Id` → the 2nd is a
   no-op (returns `200`, nothing duplicated).*
2. **`X-Aggregate-Version: <N>`** — only when changing something that **already exists** (e.g. advancing a
   supplier). **Not** needed on *create* calls (`create`, `nominate`, `POST /listings`, `POST /documents`).
   Get the current `N` from the entity's `GET` (the `aggregate_version` field) right before you write.
   *Send a stale `N` → `409 version_conflict` (that's correct — re-GET and retry).*
3. **The right role** — use the token whose role matches the catalogue's **Auth** column.
   *Wrong role → `403 role_not_held` (that's correct).*

Public routes (`🔓` in the catalogue: login, the banking webhook, `/dev/*`) need **no** token.

---

## 3. How to "see the data"

- **Every module has a `GET`** (catalogue rows marked `🪪 bearer`) — the fastest way to confirm a write
  landed. E.g. after any supplier step: `GET /suppliers/{id}` → shows `status` + `aggregate_version`.
- **`GET /dev/seed-info`** — the seeded supplier/buyer/investor ids to test against.
- **Direct DB peek** (optional): `docker exec -it avc-pg psql -U platform -d platform` then e.g.
  `SELECT status FROM sup_account;`, `SELECT * FROM sys_audit_event ORDER BY occurred_at DESC LIMIT 5;`.

---

## 4. Smoke test first (2 min) — is the environment healthy?

```bash
./scripts/dev-smoke.sh
```
This drives a whole deal `listed → … → matured` in one go. **If it finishes green, your setup is good** —
now test the pieces by hand below. (If it fails, fix setup before continuing — don't chase phantom bugs.)

---

## 5. The test plan (run the suites in this order — later ones depend on earlier data)

Each row: **do the call** (as the listed role) → **expect** the status → **see it** with a GET/query.
For exact request bodies, use the matching request in Postman or `manual-test.http`; the key/new ones are inlined.

### Suite A — Auth (no token needed)
- [ ] Log in as `ops@dev.local` (§1) → you get a `bearer`. ✅ token works if `GET /suppliers/{seeded id}` returns `200`.
- [ ] Wrong password → `POST /auth/login/password` with a bad password → `401`.
- [ ] Wrong OTP → `verify-otp` with `code:"000000"` → `401`.

### Suite B — Admin IAM (token: `super@dev.local`)
| # | Action | Expect | See it |
|---|---|---|---|
| B1 | `POST /admin-users/provision` `{email,display_name}` | `201` | `GET /admin-users/{id}` → `role-less`, active |
| B2 | `POST /admin-users/{id}/roles` `{role:"ops_executive"}` | `200` | `GET /admin-users/{id}` |
| B3 | Repeat B2 with a non-super token | `403 role_not_held` | — |

### Suite C — Supplier onboarding (token: `ops`, approve step: `compliance`)
Drive a **new** supplier `created → active`. (Or inspect the seeded `DEV Supplier`, already `active`.)
| # | Action (role) | Expect | See it |
|---|---|---|---|
| C1 | `POST /suppliers/create` (ops) | `201` + `aggregate_id` | `GET /suppliers/{id}` → `created` |
| C2 | `record-identity-verified` → `submit-kyc` (ops) | `200` each | status → `kyc_submitted` |
| C3 | `record-kyc-approved` (**compliance** — must differ from submitter) | `200` | status → `kyc_approved` |
| C4 | `submit-financial-profile` → `record-credit-review` (credit) → `record-maa-signed` → `activate` (ops) | `200` each | status → `active` |
| C5 | **Guardrail:** approve C2 with the *same* ops token that submitted | `409 checker_equals_maker` | status unchanged |

### Suite D — Buyer onboarding + KYB (tokens: `credit` to nominate, `ops` for the rest)
| # | Action (role) | Expect | See it |
|---|---|---|---|
| D1 | `POST /buyers/nominate` (credit) | `201` | `GET /buyers/{id}` → `nominated` |
| D2 | `record-identity-verified` → `record-credit-assessment` (credit) → `start-engagement` → `designate-ack-user` → `confirm-payment-instruction` → `activate` (ops) | `200` each | status → `active` |
| D3 | **KYB:** `POST /buyers/{id}/kyb-verification` `{"verified":true}` (ops) | `200` | `GET /buyers/{id}/kyb-verification` → `kyb_verified:true` + who/when |
| D4 | **Guardrail:** D3 with a non-ops token | `403` | `kyb_verified` still false |

### Suite E — Investor onboarding (tokens: `compliance` to invite, `ops` for the rest)
| # | Action (role) | Expect | See it |
|---|---|---|---|
| E1 | `POST /investor-invites/issue` (compliance) → `POST /investors/sign-up` (ops) | `201`/`200` | `GET /investors/{id}` → `signed_up` |
| E2 | `record-identity-verified` → `submit-kyc` (ops) → `assess-suitability` (compliance) → `complete-financial-profile` (ops) → `record-kyc-approved` (compliance) → `record-mia-signed` → `activate` (ops) | `200` each | status → `active` |

### Suite F — Documents, two-phase upload (token: `ops`)
The generic file service. Every upload is 3 calls.
| # | Action | Expect | See it |
|---|---|---|---|
| F1 | `POST /documents` `{"kind":"invoice","content_type":"application/pdf","declared_size":1234}` | `200` + `document_id`, status `pending_upload` | `GET /documents/{id}` |
| F2 | `PUT /documents/{id}/content` — body = raw PDF bytes, header `Content-Type: application/pdf` | `200` | — |
| F3 | `POST /documents/{id}/finalize` | `200`, status `stored` | `GET /documents/{id}` → `stored` |
| F4 | `GET /documents/{id}/content` | `200` + the exact bytes back | your file downloads |

```bash
# F1–F4 in curl (a tiny fake PDF):
printf '%%PDF-1.4 test' > /tmp/t.pdf
DOC=$(curl -s $BASE/documents -H "Authorization: Bearer $OPS" -H 'X-Command-Id: '$(uuidgen) \
  -H 'Content-Type: application/json' -d '{"kind":"invoice","content_type":"application/pdf","declared_size":13}' | jq -r .document_id)
curl -s -X PUT $BASE/documents/$DOC/content -H "Authorization: Bearer $OPS" -H 'Content-Type: application/pdf' --data-binary @/tmp/t.pdf
curl -s -X POST $BASE/documents/$DOC/finalize -H "Authorization: Bearer $OPS"
curl -s $BASE/documents/$DOC/content -H "Authorization: Bearer $OPS"   # → your bytes
```

### Suite G — Onboarding KYC documents (token: `ops`)
Attach a stored `document_id` (from Suite F) to a submitted KYC file. Needs a supplier/investor at
`kyc_submitted` (Suite C step C2, before approval).
| # | Action | Expect | See it |
|---|---|---|---|
| G1 | Find the KYC file id: `SELECT kyc_file_id FROM comp_kyc_file WHERE subject_id='<supplier id>';` (psql) | one row | — |
| G2 | `POST /kyc/{kycFileId}/documents` `{"document_id":"<F3 doc>","doc_kind":"pan_card"}` | `200` | `GET /kyc/{kycFileId}/documents` → your link |
| G3 | `GET /kyc/{kycFileId}/documents/coverage` | `200` `{"pan_card":true,"gst_certificate":false,...}` | advisory only — **nothing is mandatory** |
| G4 | `GET /onboarding-doc-requirements?subject_type=supplier` | `200` — the suggested list | — |
| G5 | **Guardrail:** attach a **second** `pan_card` while the first is active | `4xx` (one active per kind) | `GET …/documents` |

### Suite H — The money spine (the big end-to-end; tokens: `ops`, `treasury`, `treasury2`)
Uses the **seeded active** `DEV Supplier` + `DEV Buyer` (they already have a pricing band), so you can start here.
This is where you see the whole product work. Run it in Postman's **Golden Path** folder for the auto-chained
version — or step through with the checkpoints below.
| # | Action (role) | Expect | See it (`GET /listings/{id}`) |
|---|---|---|---|
| H1 | `POST /listings` (ops) using seeded supplier+buyer ids | `201` | status `submitted` |
| H2 | Upload an invoice PDF (Suite F) → `POST /listings/{id}/invoice-documents` `{document_id}` (ops) | `200` | `GET /listings/{id}/invoice-documents` |
| H3 | `start-ops-checks` → `record-ops-check` ×7 (ops; **`document_completeness` needs a 2nd ops user** ≠ the uploader) → `complete-ops-checks` | `200` each | status `awaiting_acknowledgment` |
| H4 | `record-buyer-ack` → `snapshot-and-ready {rate_bps}` (ops) | `200` | status `ready_for_review` |
| H5 | `approve-go-live` (**treasury**) | `200` | status `live` + a virtual-account id |
| H6 | `POST /listings/{id}/subscriptions/commit` (ops) up to the funding target | `200` | status `fully_funded` |
| H7 | `POST /webhooks/banking/{vendor}/inflow.received` (no token, HMAC-signed — use the Postman request) | `200` | subscription `confirmed` |
| H8 | assignment-set `request` → `complete-signing` (ops) | `200` | `GET …/assignment-set` → `all_signed` |
| H9 | disbursement `draft` (**treasury**) → `approve` (**treasury2** ≠ maker) | `200` | status `disbursed` |
| H10 | `record-maturity` (ops) → distribution `draft` (treasury) → `approve` (treasury2) | `200` | status `distributed` |
| H11 | `POST /investors/{id}/tax/form-16a/{fy}/issue` (compliance); `fy` e.g. `FY2026-27` | `200` | `GET /investors/{id}/tax/form-16a/{fy}` downloads it |

### Suite I — Guardrails (prove the controls work; do these anywhere)
- [ ] **Wrong role** → any write with the wrong-role token → `403 role_not_held`.
- [ ] **Idempotency** → resend any successful write with the **same** `X-Command-Id` → `200`, no duplicate.
- [ ] **Stale version** → send a write with an old `X-Aggregate-Version` → `409 version_conflict`.
- [ ] **Maker = checker** → the same user does both sides of a two-person step (KYC approve, disbursement) → `409 checker_equals_maker`.
- [ ] **No token** → any `🪪`/`👤` route without a bearer → `401`.

---

## 6. Done / reporting

You've tested everything when every box above is ticked. Log anything that behaves differently from
"Expect" as a bug: note the **request** (method, path, body, which role), the **actual** response, and the
**expected**. Reset to a clean slate any time: stop the app, `docker exec -it avc-pg psql -U platform -d platform -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;'`, re-run setup steps 2–3 (see `MANUAL_TESTING.md` §8).
