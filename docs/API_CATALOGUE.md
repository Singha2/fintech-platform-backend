# API Catalogue — working endpoints & what they do

> **Status: all endpoints below are fully working** — implemented, covered by the 350-test suite, and the
> money-flow spine runs end-to-end as an automated smoke test (now all the way to **`distributed / closed`**,
> incl. TDS + Form 16A). Generated from the controllers on 2026-07-12; **documents (BC16), invoice artifacts
> (BC1) + buyer KYB (BC9)** added this pass.
> Postman: `postman/fintech-platform.postman_collection.json`. Endpoint map + golden path: `MANUAL_TESTING.md`.

**Auth legend** — how each route is authorised:
- 🔓 **open** — no bearer (login / webhook / bootstrap / dev)
- 🔑 **api-key** — `Authorization: Bearer <platform.bootstrap.api-key>`
- 🪪 **bearer** — a valid session bearer, any identity (no role checked)
- 👤 **role** — bearer **and** the named admin role (enforced at `CommandGateway`)

**Command envelope** — every `POST` command needs `X-Command-Id`; transitions on an existing aggregate also
need `X-Aggregate-Version`. Each is idempotent, MFA-fresh, and audit-logged.

---

## Authentication — `/auth/login`
| Method · Path | Functionality | Auth |
|---|---|---|
| `POST /auth/login/password` | Step 1 of login: verify email+password, issue an SMS-OTP → returns `challenge_id` | 🔓 open |
| `POST /auth/login/verify-otp` | Step 2: verify OTP → establishes a session, returns the **bearer** (the token) | 🔓 open |

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

## Listing lifecycle (BC1) — `/listings`
| Method · Path | Functionality | Auth |
|---|---|---|
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
| `GET /listings/{id}/disbursement` | Read the disbursement instruction | 🪪 bearer |
| `POST /listings/{id}/record-maturity` | Record buyer repayment → `matured_payment_received` | 👤 ops_executive |

## Distribution & Tax (BC12) — `/listings`, `/investors/{id}/tax`
Closes the money lifecycle: pay investors their principal + return, withhold TDS on the return only (10% with a verified PAN, 20% without — §206AA), record the TDS ledger, and close the deal as `distributed`. Then issue each investor's Form 16A.
| Method · Path | Functionality | Auth |
|---|---|---|
| `POST /listings/{id}/distribution/draft` | Draft the distribution (maker): compute + **freeze** the per-investor TDS snapshot on a matured listing | 👤 treasury_and_settlement |
| `POST /listings/{id}/distribution/approve` | Approve & pay every investor their net → writes TDS ledger + FY cumulatives, **closes deal → `distributed`** (checker ≠ maker) | 👤 treasury_and_settlement |
| `GET /listings/{id}/distribution` | Read the distribution instruction (gross, net, total TDS, terminal outcome) | 🪪 bearer |
| `POST /investors/{id}/tax/form-16a/{fyCode}/issue` | Issue the investor's Form 16A TDS certificate for a financial year (e.g. `FY2026-27`) | 👤 compliance_reviewer |
| `GET /investors/{id}/tax/form-16a/{fyCode}` | Download the issued Form 16A document (frozen bytes, `text/plain` attachment) | 🪪 bearer |
| `GET /investors/{id}/tax/deductions` | List the investor's TDS deduction ledger (optional `?fy=FY2026-27`) | 🪪 bearer |
| `GET /investors/{id}/tax/statements` | List the investor's issued tax statements (period, kind, doc hash) | 🪪 bearer |

## Credit & pricing (BC3) — `/credit`
| Method · Path | Functionality | Auth |
|---|---|---|
| `POST /credit/pricing-bands` | Set a tenor→rate/fee pricing band | 👤 credit_reviewer |
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
  `identity_verified` (GSTIN+CIN) — the two are independent and neither gates the buyer lifecycle. Investor/
  supplier **KYC** documents (typed, checklist-driven) are not built yet — pending the compliance checklist (M20 §9b).
- **Not yet built:** post-`active` counterparty management (suspend/blacklist/off-board), field amendments, and
  self-service (non-admin) actions. See `USE_CASES_COUNTERPARTY_ONBOARDING.md` §4 and `ROADMAP.md`.
