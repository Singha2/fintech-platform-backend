# Counterparty use cases — Supplier · Buyer · Investor (current implementation)

> **What this is.** A factual catalogue of the business use cases **implemented today** for the three
> counterparties, all **admin-driven** (no self-service). Generated from the controllers/services as of
> 2026-07-11. Each row is a real endpoint. States are the `*_account_status` enums. Roles are the admin role
> the command requires (enforced at `CommandGateway`). "Data step" = a command that captures data without
> advancing the lifecycle state.

**Cross-cutting (every command below).** Requires an admin **bearer** + `X-Command-Id`; transitions on an
existing aggregate also require `X-Aggregate-Version`. Each command is idempotent, MFA-fresh, and audit-logged
(the five non-negotiables; maker-checker is not yet gateway-enforced — single-actor today). None of these
counterparties can self-serve: Supplier has no login at all, Buyer's only login principal is its ack-user,
Investor can log in but is read-only (see personas doc).

---

## 1. Supplier (BC8) — `/suppliers`

**Happy-path lifecycle:**
```
created → identity_verified → kyc_submitted → kyc_approved → credit_reviewed → maa_signed → active
```

| Use case | Endpoint | Role | Notes |
|---|---|---|---|
| Create supplier | `POST /suppliers/create` | ops_executive | → `created` |
| Grant agency consent | `POST /suppliers/{id}/grant-agency-consent` | ops_executive | data step (consent scope) |
| Record identity verified | `POST /suppliers/{id}/record-identity-verified` | ops_executive | → `identity_verified` |
| Submit KYC | `POST /suppliers/{id}/submit-kyc` | ops_executive | → `kyc_submitted` |
| Record KYC approved | `POST /suppliers/{id}/record-kyc-approved` | **compliance_reviewer** | → `kyc_approved` |
| Record KYC rejected | `POST /suppliers/{id}/record-kyc-rejected` | **compliance_reviewer** | rejection branch → resubmit |
| Resubmit KYC | `POST /suppliers/{id}/resubmit-kyc` | ops_executive | back to `kyc_submitted` |
| Submit financial profile | `POST /suppliers/{id}/submit-financial-profile` | ops_executive | data step (input to credit review) |
| Record credit review | `POST /suppliers/{id}/record-credit-review` | **credit_reviewer** | `kyc_approved` → `credit_reviewed` (exposure cap, risk rating) |
| Record MAA signed | `POST /suppliers/{id}/record-maa-signed` | ops_executive | → `maa_signed` |
| Activate | `POST /suppliers/{id}/activate` | ops_executive | `maa_signed` → `active` |
| Read supplier | `GET /suppliers/{id}` | any bearer | status + version |

---

## 2. Buyer (BC9) — `/buyers`

**Happy-path lifecycle:**
```
nominated → identity_verified → credit_assessed → engagement_started → active
```

| Use case | Endpoint | Role | Notes |
|---|---|---|---|
| Nominate buyer | `POST /buyers/nominate` | **credit_reviewer** | → `nominated` |
| Record identity verified | `POST /buyers/{id}/record-identity-verified` | ops_executive | → `identity_verified` |
| Record credit assessment | `POST /buyers/{id}/record-credit-assessment` | **credit_reviewer** | `identity_verified` → `credit_assessed` (sets credit limit) |
| Start engagement | `POST /buyers/{id}/start-engagement` | ops_executive | `credit_assessed` → `engagement_started` |
| Designate ack-user | `POST /buyers/{id}/designate-ack-user` | ops_executive | data step — provisions the buyer-side `acknowledgment_user` (OTP-only login) |
| Confirm payment instruction | `POST /buyers/{id}/confirm-payment-instruction` | ops_executive | data step (payment details) |
| Activate | `POST /buyers/{id}/activate` | ops_executive | → `active` |
| Read buyer | `GET /buyers/{id}` | any bearer | status + version |

---

## 3. Investor (BC7) — `/investors`, `/investor-invites`

**Happy-path lifecycle:**
```
(invite) → signed_up → identity_verified → kyc_submitted → suitability_assessed
        → financial_profile_completed → kyc_approved → mia_signed → active
```

| Use case | Endpoint | Role | Notes |
|---|---|---|---|
| Issue invite | `POST /investor-invites/issue` | **compliance_reviewer** | creates `inv_invite` (`pending`) |
| Sign up | `POST /investors/sign-up` | ops_executive | consumes invite → `signed_up` |
| Record identity verified | `POST /investors/{id}/record-identity-verified` | ops_executive | → `identity_verified` (PAN, Aadhaar last-4) |
| Submit KYC | `POST /investors/{id}/submit-kyc` | ops_executive | → `kyc_submitted` |
| Assess suitability | `POST /investors/{id}/assess-suitability` | **compliance_reviewer** | → `suitability_assessed` (mismatch flag) |
| Acknowledge suitability override | `POST /investors/{id}/acknowledge-suitability-override` | **compliance_reviewer** | override branch when suitability mismatched |
| Complete financial profile | `POST /investors/{id}/complete-financial-profile` | ops_executive | → `financial_profile_completed` (bank last-4) |
| Record KYC approved | `POST /investors/{id}/record-kyc-approved` | **compliance_reviewer** | → `kyc_approved` |
| Record KYC rejected | `POST /investors/{id}/record-kyc-rejected` | **compliance_reviewer** | rejection branch → resubmit |
| Resubmit KYC | `POST /investors/{id}/resubmit-kyc` | ops_executive | back to `kyc_submitted` |
| Record MIA signed | `POST /investors/{id}/record-mia-signed` | ops_executive | → `mia_signed` |
| Activate | `POST /investors/{id}/activate` | ops_executive | → `active` |
| Read investor | `GET /investors/{id}` | any bearer | status + version |

---

## 4. What is NOT covered yet (explicit gaps)

These are true of the current code, worth knowing before planning next work:

- **No post-`active` lifecycle management.** The status enums include terminal/suspension states —
  supplier `suspended` / `blacklisted` / `voluntarily_exited`, buyer `suspended`, investor `suspended` /
  `exited` — but **no command reaches them**. Once `active`, a counterparty cannot currently be suspended,
  blacklisted, off-boarded, or reactivated via the API.
- **No amend/correct commands.** No way to update a captured field (bank details, credit limit, KYC docs) on an
  existing counterparty after the fact — the flow is forward-only through the happy path + KYC-reject/resubmit.
- **No self-service.** Every use case above is admin-driven. Supplier has no login; the Buyer entity has no
  login (only its ack-user authenticates); Investor can authenticate but has no write API (reads only).
- **Reads are unauthorized beyond authentication.** `GET` on any counterparty is reachable by any valid bearer
  — no role or ownership scoping (see personas / read-authorization notes).
- **Maker-checker not enforced** on these commands yet (single-actor), pending the gateway four-eyes work.

*Related: `MANUAL_TESTING.md` (§4 endpoint map, §5 golden path), `docs/design/ADR-001` (permission layer).*
