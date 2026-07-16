# API Catalogue — working endpoints & what they do

> **Status: all endpoints below are fully working** — implemented, covered by the 400-test suite, and the
> money-flow spine runs end-to-end as an automated smoke test (now all the way to **`distributed / closed`**,
> incl. TDS + Form 16A). Generated from the controllers on 2026-07-12 (reviewed 2026-07-14); **documents (BC16),
> invoice artifacts (BC1), buyer KYB + investor/supplier KYC docs (BC9/BC11)** added this pass.
> Postman: `postman/fintech-platform.postman_collection.json`. Endpoint map + golden path: `MANUAL_TESTING.md`.
>
> 🧪 **New here? Follow [`API_TEST_PLAN.md`](API_TEST_PLAN.md)** — a step-by-step manual test plan (setup →
> tokens → run every suite) built to be driven straight off this catalogue.

**Base path** — every path below is served under **`/api/v1`** (e.g. `POST /api/v1/suppliers/create`). The
paths in this catalogue omit that prefix for brevity. Health/ops is the exception: `/actuator/health` on the
separate **management port 8081**, not under `/api/v1`. _(DL-BE-078)_

**Auth legend** — how each route is authorised:
- 🔓 **open** — no bearer (login / webhook / bootstrap / dev)
- 🔑 **api-key** — `Authorization: Bearer <platform.bootstrap.api-key>`
- 🪪 **bearer** — a valid session bearer, any identity (no role checked)
- 👤 **role** — bearer **and** the named admin role (enforced at `CommandGateway`)

**Command envelope** — every `POST` command needs `X-Command-Id`; transitions on an existing aggregate also
need `X-Aggregate-Version`. Each is idempotent, MFA-fresh, and audit-logged.

**MFA-freshness (BE-3, for the UI)** — **every admin-actor command** requires a fresh MFA assertion: they are
all `ActionSensitivity.SENSITIVE` (5-minute window); there is no per-command allowlist and `NORMAL` is unused.
Non-admin-actor commands skip the MFA gate. **UI consequence:** treat *all* admin commands as MFA-fresh-gated
(the MFA prompt is not only for go-live/disbursement) and route a stale-MFA rejection to re-auth. Read
`GET /auth/session` (`mfa_fresh`) to know the current state.

---

## Authentication — `/auth/login`
| Method · Path | Functionality | Auth |
|---|---|---|
| `POST /auth/login/password` | Step 1 of login: verify email+password, issue an SMS-OTP → returns `challenge_id` | 🔓 open |
| `POST /auth/login/verify-otp` | Step 2: verify OTP → establishes a session, returns the **bearer** (the token) | 🔓 open |
| `GET /auth/session` | **Who am I** (BE-1): current `{identity_id, kind, email, roles[], admin_user_id, mfa_fresh, idle/absolute_expires_at}` — drives UI role-nav + MFA gating. `roles` empty for non-admin kinds | 🪪 bearer |

## Bootstrap & dev helpers
| Method · Path | Functionality | Auth |
|---|---|---|
| `POST /bootstrap/admin-users` | Break-glass: mint the **first active super_admin** (identity + role + password) | 🔑 api-key |
| `GET /bootstrap/last-otp?email=` | Read the OTP the stub "sent" (any profile) so login is scriptable pre-SMS | 🔑 api-key |
| `GET /dev/last-otp?email=` | Same OTP peek, dev-profile only | 🔓 open (dev only) |
| `GET /dev/seed-info` | Returns seeded `supplier_id`/`buyer_id`/`investor_id` + admin password | 🔓 open (dev only) |

## Admin IAM — `/admin-users`
| Method · Path | Functionality | Auth |
|---|---|---|
| `POST /admin-users/provision` | Create a new (role-less) admin user | 👤 super_admin |
| `POST /admin-users/{id}/roles` | **Assign a role** (any, incl. super_admin); SoD-checked | 👤 super_admin |
| `POST /admin-users/{id}/roles/{role}/revoke` | **Revoke a role** | 👤 super_admin |
| `POST /admin-users/{id}/disable` | Disable an admin user | 👤 super_admin |
| `GET /admin-users/{id}` | Read admin status + version | 🪪 bearer |

## Admin dashboard (BE-12) — `/admin` · cross-BC read, computed live
| Method · Path | Functionality | Auth |
|---|---|---|
| `GET /admin/work-queues` | **Per-role pending counts** (BE-12): `?role=` → `[{queue, role, count}]` for the S2 landing queues (supplier/investor KYC, supplier credit, listing ops-checks/go-live, disbursement/distribution approval). Each queue's `(status, role)` maps to that command's role gate; computed live (no projection table) | 🪪 bearer |
| `GET /admin/stats` | **Headline tiles** (BE-12): `{active_listings, total_deployed_paise, investors_active, suppliers_active, pending_disbursements}` — COUNT/SUM over write tables. `total_deployed_paise` = gross of disbursements past `drafted` (excl. `failed`) | 🪪 bearer |

## Supplier onboarding (BC8) — `/suppliers`
| Method · Path | Functionality | Auth |
|---|---|---|
| `POST /suppliers/create` | Create supplier → `created` | 👤 ops_executive |
| `POST /suppliers/{id}/grant-agency-consent` | Record agency consent scope | 👤 ops_executive |
| `POST /suppliers/{id}/record-identity-verified` | Mark identity (PAN/GSTIN) verified | 👤 ops_executive |
| `POST /suppliers/{id}/submit-kyc` | Submit KYC → `kyc_submitted` | 👤 ops_executive |
| `POST /suppliers/{id}/record-kyc-approved` | Approve KYC → `kyc_approved` | 👤 compliance_reviewer |
| `POST /suppliers/{id}/record-kyc-rejected` | Reject KYC (→ resubmit path) | 👤 compliance_reviewer |
| `POST /suppliers/{id}/resubmit-kyc` | Resubmit after rejection | 👤 ops_executive |
| `POST /suppliers/{id}/submit-financial-profile` | Capture financials (input to credit review) | 👤 ops_executive |
| `POST /suppliers/{id}/record-credit-review` | Set exposure cap + risk rating → `credit_reviewed` | 👤 credit_reviewer |
| `POST /suppliers/{id}/record-maa-signed` | Record master agreement signed → `maa_signed` | 👤 ops_executive |
| `POST /suppliers/{id}/activate` | Activate supplier → `active` | 👤 ops_executive |
| `GET /suppliers` | **List** (BE-4): `?status=&q=` (legal-name) filters → `[{supplier_id, legal_name, constitution_type, pan, gstin, status, activated_at}]`, `LIMIT 500` | 🪪 bearer |
| `GET /suppliers/{id}/listings` | **Tracker** (BE-11, admin view): per-supplier `[{listing_id, invoice_number, face_value_paise, tenor_days, invoice_date, due_date, status, funding_target, committed_total, rate_bps, created_at}]` — funding progress + timeline (the plain catalogue is BE-6 `?supplier_id=`); `LIMIT 500` | 🪪 bearer |
| `GET /suppliers/{id}` | Read supplier status + version | 🪪 bearer |

## Buyer onboarding (BC9) — `/buyers`
| Method · Path | Functionality | Auth |
|---|---|---|
| `POST /buyers/nominate` | Nominate buyer → `nominated` | 👤 credit_reviewer |
| `POST /buyers/{id}/record-identity-verified` | Mark identity (CIN) verified | 👤 ops_executive |
| `POST /buyers/{id}/record-credit-assessment` | Set credit limit → `credit_assessed` | 👤 credit_reviewer |
| `POST /buyers/{id}/start-engagement` | Begin engagement → `engagement_started` | 👤 ops_executive |
| `POST /buyers/{id}/designate-ack-user` | Provision the buyer-side ack user (OTP-only login) | 👤 ops_executive |
| `POST /buyers/{id}/confirm-payment-instruction` | Capture/confirm payment instruction | 👤 ops_executive |
| `POST /buyers/{id}/activate` | Activate buyer → `active` | 👤 ops_executive |
| `POST /buyers/{id}/kyb-verification` | **KYB attestation** (`{verified, document_id?}`): set `kyb_verified` + stamp who/when, optionally attach one custom doc — a manual sign-off on top of the automated identity check (M8), gates nothing | 👤 ops_executive |
| `GET /buyers` | **List** (BE-5): `?status=&q=` filters → `[{buyer_id, legal_name, sector, status, credit_limit_paise, mca_cin, gstin}]`, `LIMIT 500` | 🪪 bearer |
| `GET /buyers/{id}` | Read buyer status + version | 🪪 bearer |
| `GET /buyers/{id}/kyb-verification` | Read `kyb_verified` + verified_by/at + optional `kyb_document_id` | 🪪 bearer |

## Investor onboarding (BC7) — `/investors`, `/investor-invites`
| Method · Path | Functionality | Auth |
|---|---|---|
| `POST /investor-invites/issue` | Issue an invite (`pending`) | 👤 compliance_reviewer |
| `POST /investors/sign-up` | Consume invite → `signed_up` | 👤 ops_executive |
| `POST /investors/{id}/record-identity-verified` | Verify identity (PAN, Aadhaar last-4) | 👤 ops_executive |
| `POST /investors/{id}/submit-kyc` | Submit KYC → `kyc_submitted` | 👤 ops_executive |
| `POST /investors/{id}/assess-suitability` | Suitability assessment (mismatch flag) | 👤 compliance_reviewer |
| `POST /investors/{id}/acknowledge-suitability-override` | Override a suitability mismatch | 👤 compliance_reviewer |
| `POST /investors/{id}/complete-financial-profile` | Capture bank last-4 → `financial_profile_completed` | 👤 ops_executive |
| `POST /investors/{id}/record-kyc-approved` | Approve KYC → `kyc_approved` | 👤 compliance_reviewer |
| `POST /investors/{id}/record-kyc-rejected` | Reject KYC (→ resubmit path) | 👤 compliance_reviewer |
| `POST /investors/{id}/resubmit-kyc` | Resubmit after rejection | 👤 ops_executive |
| `POST /investors/{id}/record-mia-signed` | Record master investment agreement → `mia_signed` | 👤 ops_executive |
| `POST /investors/{id}/activate` | Activate investor → `active` | 👤 ops_executive |
| `GET /investors/{id}` | Read investor status + version | 🪪 bearer |
| `GET /investor-invites` | **Tracker** (BE-9): `?status=` (`pending`/`consumed`/`expired`) → `[{invite_id, status, issued_by, issued_at, expiry_at, consumed_at}]`, newest first, `LIMIT 500` (email/phone hashes are PII — not surfaced) | 🪪 bearer |

## Documents (BC16) — `/documents`
Generic two-phase document service (M18). Upload is content-custody plumbing — **any authenticated admin**
may call it (no role gate, no MFA); the *consumer's* attach command (invoice, KYB) carries the five
non-negotiables. Bytes live in DB locally, GCS in prod (deferred). No encryption at rest yet (Production gate).

| Method · Path | Functionality | Auth |
|---|---|---|
| `POST /documents` | **Initiate** (`{kind, content_type, declared_size}`) → `pending_upload`; `document_id` derived from `X-Command-Id` (idempotent) | 🪪 bearer |
| `PUT /documents/{id}/content` | **Upload** the raw `application/pdf` body (size cap `documents.max-upload-bytes`, default 20 MB) | 🪪 bearer |
| `POST /documents/{id}/finalize` | **Finalize** → hash (SHA-256) + `stored`; idempotent on `document_id` | 🪪 bearer |
| `GET /documents/{id}` | Read metadata (kind, status, content_type, byte_size) — no bytes | 🪪 bearer |
| `GET /documents/{id}/content` | Download the stored bytes (STORE-1: no authZ here — the consumer is the policy point) | 🪪 bearer |

## Onboarding KYC documents (BC11) — `/kyc`, `/onboarding-doc-requirements`
Typed KYC documents (investor + supplier) attached to a `comp_kyc_file` (M20). **Capture-only — nothing is
mandatory; Ops decides KYC completeness** at approval. The coverage read is *advisory*; the requirement list
is a runtime-editable *suggested* set (buyer KYB is separate — see the Buyer section).

| Method · Path | Functionality | Auth |
|---|---|---|
| `GET /suppliers/{id}/kyc-file` | **Resolve** the subject's `kyc_file_id` (BE-2) → `{kyc_file_id, subject_id, subject_type, status}`; 404 until KYC submitted. Unblocks the KYC-doc UI (the id has no other read) | 🪪 bearer |
| `GET /investors/{id}/kyc-file` | Same resolver for an investor subject | 🪪 bearer |
| `POST /kyc/{kycFileId}/documents` | **Attach** a stored `document_id` typed by `doc_kind`; `uploaded_by`=actor; one active per kind | 👤 ops_executive |
| `PUT /kyc/{kycFileId}/documents/{kycDocumentId}` | **Replace** a document → supersedes the old link | 👤 ops_executive |
| `GET /kyc/{kycFileId}/documents` | List the KYC doc links (kind, status, document_id) — no bytes | 🪪 bearer |
| `GET /kyc/{kycFileId}/documents/coverage` | **Advisory** coverage: suggested kinds vs uploaded (`{doc_kind: covered}`) — no verdict, no gate | 🪪 bearer |
| `GET /onboarding-doc-requirements?subject_type=` | Read the suggested-documents list (investor\|supplier) | 🪪 bearer |
| `POST /onboarding-doc-requirements` | Upsert a suggested kind (`{subject_type, doc_kind, active}`; `mandatory` stays false) | 👤 ops_executive |

## Listing lifecycle (BC1) — `/listings`
| Method · Path | Functionality | Auth |
|---|---|---|
| `GET /listings` | **List** (BE-6): `?status=&supplier_id=&buyer_id=` → `[{listing_id, invoice_number, supplier_id, buyer_id, face_value_paise, tenor_days, status, funding_target, rate_bps}]` (JOIN `deal_invoice`; `rate_bps` from `pricing_snapshot`), `LIMIT 500` | 🪪 bearer |
| `GET /listings/{id}/ops-checks` | **Ops-check panel** (BE-6): expands `check_outcomes` JSONB → `[{check_name, outcome, verification_id, checked_at}]`; 404 unknown listing | 🪪 bearer |
| `GET /listings/{id}/detail` | **Rich detail** (BE-10): read-model `{listing_id, status, funding_target, committed_total, va_id, pricing_snapshot:{rate_bps,fee_bps,pricing_band_id}, virtual_account:{account_no,ifsc,status}, invoice:{…}, buyer:{…}, supplier:{…}}` (JOINs invoice+buyer+supplier+VA); `pricing_snapshot`/`virtual_account` null pre-price/pre-VA; 404 unknown. Admin variant — investor S12 (ownership+KYC gate) is BE-14/M10-full | 🪪 bearer |
| `POST /listings` | Create a listing from an invoice | 👤 ops_executive |
| `POST /listings/{id}/start-ops-checks` | Begin operational checks | 👤 ops_executive |
| `POST /listings/{id}/record-ops-check` | Record one ops check (IRN, e-way, exposure, …) | 👤 ops_executive |
| `POST /listings/{id}/complete-ops-checks` | Close ops checks → `awaiting_acknowledgment` | 👤 ops_executive |
| `POST /listings/{id}/request-buyer-ack` | Request buyer acknowledgement (SLA) | 👤 ops_executive |
| `POST /listings/{id}/record-buyer-ack` | Record buyer ack outcome | 👤 ops_executive |
| `POST /listings/{id}/snapshot-and-ready` | Price it (rate bps) + snapshot → `ready_for_review`, funding target | 👤 ops_executive |
| `POST /listings/{id}/approve-go-live` | Second-person approval → `live` + virtual account | 👤 treasury_and_settlement |
| `POST /listings/{id}/declare-funding-shortfall` | Declare a funding shortfall | 👤 ops_executive |
| `GET /listings/{id}` | Read status, funding target, VA id, version | 🪪 bearer |

## Invoice artifacts (BC1) — `/listings/{id}/invoice-documents`
The invoice PDF investors review before funding (M19). Ops uploads it via `/documents`, then **attaches** the
`document_id` here; it gates the `document_completeness` ops-check (recorder ≠ uploader) and freezes at snapshot.

| Method · Path | Functionality | Auth |
|---|---|---|
| `POST /listings/{id}/invoice-documents` | **Attach** a stored `document_id` (must be PDF + `stored`); stamps `uploaded_by`; rejected after `ready_for_review` | 👤 ops_executive |
| `PUT /listings/{id}/invoice-documents/{documentId}` | **Replace** the active artifact (`{new_document_id}`) → supersedes; pre-snapshot only | 👤 ops_executive |
| `GET /listings/{id}/invoice-documents` | List the invoice↔document links (kind, status, document_id) — no bytes | 🪪 bearer |
| `GET /listings/{id}/invoice-documents/{documentId}/content` | **Download** the invoice PDF — gated on a live-set listing status _(download-audit deferred, DL-BE-071; the KYC'd-investor gate arrives with the investor portal)_ | 🪪 bearer (ops / investor) |

## Subscription / funding (BC2)
| Method · Path | Functionality | Auth |
|---|---|---|
| `POST /listings/{id}/subscriptions/commit` | Investor commits an amount → `fully_funded` when met | 👤 ops_executive |
| `POST /subscriptions/{id}/cancel` | Cancel a subscription | 👤 ops_executive |
| `POST /subscriptions/{id}/record-refund` | Record a refund | 👤 treasury_and_settlement |
| `GET /listings/{id}/subscriptions/{subId}` | Read a subscription | 🪪 bearer |

## Money inflow (BC18) — banking webhook
| Method · Path | Functionality | Auth |
|---|---|---|
| `POST /webhooks/banking/{vendor}/inflow.received` | Confirm escrow inflow (UTR) → subscription `confirmed` | 🔓 open, HMAC-signed |

## Legal assignment (BC5)
| Method · Path | Functionality | Auth |
|---|---|---|
| `POST /listings/{id}/assignment-set/request` | Open the assignment set → `in_progress` | 👤 ops_executive |
| `POST /listings/{id}/assignment-set/complete-signing` | Record an investor's e-sign → `all_signed` when complete | 👤 ops_executive |
| `POST /listings/{id}/assignment-set/declare-incomplete` | Declare the set incomplete | 👤 ops_executive |
| `POST /listings/{id}/assignment-set/record-leg-failed` | Record a failed signing leg | 👤 ops_executive |
| `POST /listings/{id}/assignment-set/reinitiate-leg` | Reinitiate a failed leg | 👤 ops_executive |
| `GET /listings/{id}/assignment-set` | Read the assignment set | 🪪 bearer |

## Disbursement & maturity (BC4)
| Method · Path | Functionality | Auth |
|---|---|---|
| `POST /listings/{id}/disbursement/draft` | Draft the disbursement (maker) | 👤 treasury_and_settlement |
| `POST /listings/{id}/disbursement/approve` | Approve & release to supplier → `disbursed` (checker ≠ maker) | 👤 treasury_and_settlement |
| `GET /disbursements` | **Queue** (BE-7): `?status=` over disbursement instructions → `[{payout_instruction_id, listing_id, status, gross_amount, net_amount, maker_id, checker_id, listing_status, created_at}]`, `LIMIT 500` (maker's "awaiting draft" queue = `GET /listings?status=fully_funded`) | 🪪 bearer |
| `GET /listings/{id}/disbursement` | Read the disbursement instruction (frozen minimal) | 🪪 bearer |
| `GET /listings/{id}/disbursement/detail` | **Detail** (BE-7): richer read alongside the frozen one — `{payout_instruction_id, status, gross_amount, net_amount, fee_amount, total_tds_amount, maker_id, checker_id, instruction_sla_date, created_at, updated_at, listing_status}` (`utr` lives in the audit trail, not a column) | 🪪 bearer |
| `POST /listings/{id}/record-maturity` | Record buyer repayment → `matured_payment_received` | 👤 ops_executive |

## Distribution & Tax (BC12) — `/listings`, `/investors/{id}/tax`
Closes the money lifecycle: pay investors their principal + return, withhold TDS on the return only (10% with a verified PAN, 20% without — §206AA), record the TDS ledger, and close the deal as `distributed`. Then issue each investor's Form 16A.

| Method · Path | Functionality | Auth |
|---|---|---|
| `POST /listings/{id}/distribution/draft` | Draft the distribution (maker): compute + **freeze** the per-investor TDS snapshot on a matured listing | 👤 treasury_and_settlement |
| `POST /listings/{id}/distribution/approve` | Approve & pay every investor their net → writes TDS ledger + FY cumulatives, **closes deal → `distributed`** (checker ≠ maker) | 👤 treasury_and_settlement |
| `GET /listings/{id}/distribution` | Read the distribution instruction (gross, net, total TDS, terminal outcome) | 🪪 bearer |
| `GET /listings/{id}/distribution/investors` | **Per-investor breakdown** (BE-8): `[{investor_id, gross_paise, tds_amount_paise, fee_paise, net_paise, challan_ref}]` from `tax_tds_deduction` (same table as the FY-wide TDS read), ordered by investor | 🪪 bearer |
| `GET /reconciliation` | **Dashboard** (BE-8): `?status=` over the platform-**daily** `cash_recon_ledger` (not per-listing) → `[{business_date, status, master_statement_hash, inflows_matched, inflows_unmatched, discrepancy_count, updated_at}]`, newest day first, `LIMIT 500` (JSONB detail surfaced as a count) | 🪪 bearer |
| `POST /investors/{id}/tax/form-16a/{fyCode}/issue` | Issue the investor's Form 16A TDS certificate for a financial year (e.g. `FY2026-27`) | 👤 compliance_reviewer |
| `GET /investors/{id}/tax/form-16a/{fyCode}` | Download the issued Form 16A document (frozen bytes, `text/plain` attachment) | 🪪 bearer |
| `GET /investors/{id}/tax/deductions` | List the investor's TDS deduction ledger (optional `?fy=FY2026-27`) | 🪪 bearer |
| `GET /investors/{id}/tax/statements` | List the investor's issued tax statements (period, kind, doc hash) | 🪪 bearer |

## Credit & pricing (BC3) — `/credit`
| Method · Path | Functionality | Auth |
|---|---|---|
| `POST /credit/pricing-bands` | Set a tenor→rate/fee pricing band | 👤 credit_reviewer |
| `GET /credit/buyers/{id}/pricing-bands` | **List** a buyer's bands (BE-5): `[{pricing_band_id, tenor_bucket, rate_range_min_bps, rate_range_max_bps, fee_bps, effective_from, status}]` (`status` derived from `superseded_by`; re-pricing deferred DL-BE-060) | 🪪 bearer |
| `POST /credit/buyers/{id}/profile` | Set a buyer credit profile | 👤 credit_reviewer |
| `POST /credit/suppliers/{id}/profile` | Set a supplier credit profile | 👤 credit_reviewer |

---

### Notes on scope
- **Reads (`GET`, 🪪)** require only a valid bearer — no role or ownership scoping yet (any authenticated
  identity can read).
- **Distribution/Tax (BC12):** issuance (`.../form-16a/{fy}/issue`) is a single Compliance command, not
  maker-checker (it moves no money); the distribution payout itself is the Treasury maker-checker gate. FY code
  format is `FY20XX-YY` (Indian April–March). Monthly-portfolio statements are recorded-only for now.
- **Documents (BC16):** the upload steps (`PUT …/content`, `POST …/finalize`) are **operational plumbing**, not
  `CommandGateway` commands — they carry no `X-Aggregate-Version` and enforce no role/MFA. The five
  non-negotiables live on the *consumer's* attach command (invoice attach, buyer KYB). No encryption at rest
  yet, and GCS blob storage is deferred — both gated on the Production milestone (DL-BE-072/074).
- **Buyer KYB (BC9):** `kyb-verification` is a single manual Ops attestation on top of the *automated*
  `identity_verified` (GSTIN+CIN) — the two are independent and neither gates the buyer lifecycle. This is a
  *bare* document reference (one optional `document_id`), distinct from the typed investor/supplier **KYC
  documents** (BC11, `/kyc/...` above), which are capture-only and advisory. Restricted/investor-403 download
  of KYC docs (OD.5) is deferred with the investor portal.
- **Not yet built:** post-`active` counterparty management (suspend/blacklist/off-board), field amendments, and
  self-service (non-admin) actions. See `USE_CASES_COUNTERPARTY_ONBOARDING.md` §4 and `ROADMAP.md`.
