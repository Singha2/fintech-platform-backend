# A2 — Integration Contracts

*Phase 1 MVP. One-page contracts for the three critical-path integrations. Each contract states what we assume the vendor provides, how callbacks work, how we make instructions safely repeatable, what breaks, and what we do when it does. All assumptions checked against Decision Log + A1 constraints; new gaps lifted into the Gap Log.*

---

## 1. Aggregator Contract — KYC + Bureau + GST + MCA + Bank

**Vendor category:** single partner from Perfios / Karza / FinBox / Setu (DL-026, DL-047).

### 1.1 Assumed API surface

Synchronous REST/JSON over HTTPS. OAuth2 client-credentials *or* API key + HMAC. One logical endpoint per data source:

- `verify/pan` (entity & individual), `verify/gstin`, `verify/penny-drop`, `verify/irn`, `verify/eway-bill`
- `verify/aadhaar-ekyc` (OTP-based; UIDAI flow). **Primary identity-binding mechanism for investors and individual signatories** (DL-050)
- `fetch/mca21` (master data, directors, charges, financials)
- `fetch/gst-returns` (GSTR-1/3B, taxpayer-consented)
- `fetch/cibil-commercial` (bureau pull, reason code)
- `fetch/aa-bank-statement` (Account Aggregator: consent → async deliver)
- `screen/aml-pep` (name + DOB → match-score payload)

**Not in Phase 1 surface (per DL-050):** Video KYC / V-CIP API. Document collection for KYC supporting evidence (address proof, photo, signature) is handled by platform's own upload flow, not via aggregator.

### 1.2 Callback semantics

Mostly synchronous. **Async only for:** AA bank-statement delivery (consent-then-deliver), AML re-screening (Phase 2 only — DL-037). Webhook payloads HMAC-signed (`X-Signature` over body + timestamp), 5-minute replay window. Each callback echoes our `client_request_id`. At-least-once delivery assumed; we dedupe by `event_id` (C22).

### 1.3 Idempotency

We send `client_request_id` (UUID) on every call. Provider must return the same response for the same id within 24 hours. Our verification records are keyed on `(subject_id, api_name, client_request_id)`. Stored verbatim alongside extracted fields, for evidence (C24).

### 1.4 TTL by data type

PAN/GSTIN/CIN identity: 12 months. MCA financials: next FY filing or 18 months, whichever earlier. Bureau: 30 days for credit decisions. GST returns: 90 days. AA bank statement: 90 days. Penny-drop: 12 months/same beneficiary. Aadhaar e-KYC: 12 months (annual refresh C17). AML: onboarding-only in Phase 1.

### 1.5 Failure modes & fallback

| Mode | Detection | Fallback |
|---|---|---|
| 5xx / timeout >30s | Network/provider error | Retry 3× exponential backoff; persistent failure → Ops Executive captures manually with Compliance Reviewer co-sign; "aggregator unavailable" stamped in audit log (G8) |
| Stale data past TTL | TTL check at use-time | Re-verify; if still stale, manual capture flagged to Credit Reviewer |
| AA consent expired | 401 `consent_expired` | Re-issue consent request to user; block downstream step |
| Aadhaar OTP failure (UIDAI outage) | Provider error on `verify/aadhaar-ekyc` | Pause onboarding; retry; persistent outage → Compliance Reviewer escalation. No Phase 1 fallback to alternate liveness check (Video KYC not in Phase 1 per DL-050) |
| Business mismatch (e.g., GSTIN inactive) | Post-fetch validation | Pause onboarding; Compliance Reviewer adjudicates |
| Provider rate-limit | HTTP 429 | Queue + retry; alert if backlog > 50 |

**Hard rule:** aggregator response is authoritative for fields it returns. Self-attested values never accepted where an API exists (C24).

**Constraints honoured:** C10, C13, C14, C15, C17, C24. **Decisions referenced:** DL-026, DL-047, DL-050. **Open gaps:** G8, G16.

---

## 2. Escrow Contract — Per-listing Virtual Sub-accounts

**Vendor category:** single provider from ICICI Escrow Pro / Yes Bank / IndusInd / RazorpayX / M2P / Decentro / Setu / HDFC Trustee (DL-046).

### 2.1 Assumed API surface

REST/JSON over HTTPS. Mutual TLS preferred; API key + HMAC as fallback.

- `va/create` (listing VA — returns IFSC + account no), `va/{id}/close`, `va/{id}/balance`, `va/{id}/statement`
- `payout/single` (supplier disbursement)
- `payout/multi-leg` (investor distribution — N legs in one instruction)
- `payout/{id}/status`
- `refund/{inflow_ref}` (reverse a specific inflow)
- TDS deduction embedded in payout payload (DL-045)
- `statements/daily` (master + per-VA)

### 2.2 Callback semantics

HMAC-signed (C10). At-least-once delivery; we dedupe by provider `event_id`. Events:
`inflow.received`, `payout.leg.success`, `payout.leg.failed`, `refund.executed`, `tds.challan.generated`, `va.lifecycle.created|closed`. Every event carries `event_id`, `va_id`, `txn_ref`, `amount`, `utr`, `timestamp`. Dead-letter queue + manual replay required (G6, C22).

### 2.3 Idempotency

Every payout/refund/VA-create carries `client_instruction_id` (UUID). Provider must NOT execute twice for the same id. Webhook state is **provisional** until end-of-day batch reconciliation against master statement, which is **authoritative** (C9, C23, G6).

### 2.4 Multi-leg payout — atomicity

**Working assumption (G11):** multi-leg is non-atomic at the provider; each leg is an independent NEFT/RTGS instruction. Platform tracks per-leg state. Partial-success enters a Manual Remediation queue under Treasury & Settlement — failed legs are never silently dropped. Confirm semantics at vendor selection.

### 2.5 TDS execution (DL-045)

Platform computes per-investor TDS at distribution-instruction time (G4). Payout payload carries net-amount-per-leg + total-TDS-aggregate + investor PAN list. Escrow deducts, remits to government, returns challan reference via `tds.challan.generated`. Platform issues Form 16A-equivalent annually using those refs. **Form 26Q filing ownership remains open (G12).**

### 2.6 Failure modes & fallback

| Mode | Detection | Fallback |
|---|---|---|
| `va/create` fails | API error | Block listing go-live; Treasury & Settlement retries |
| Inflow webhook missed | EOD recon mismatch | Backfill from statement; subscription state corrected next morning |
| Payout leg fails (beneficiary bank, IFSC error) | `payout.leg.failed` | Manual Remediation queue; Treasury & Settlement reviews; alternate rail (RTGS↔NEFT) or contact party for corrected details |
| TDS challan not generated within SLA | No `tds.challan.generated` webhook | Block annual Form 16A for affected investors; panel-CA manual filing |
| Provider outage | Healthcheck + missed heartbeats | All money movement frozen; T&S notified; user-facing status banner |
| RTGS/NEFT cutoff missed | Instruction timestamp vs cutoff window | Auto-queue for next business day; T+1 SLA (C11) measured from next-eligible-rail |
| Idempotent retry executed twice (provider bug) | EOD recon excess | Refund excess leg; provider escalation; treat as data-integrity incident |

**Constraints honoured:** C8, C9, C10, C11, C12, C22, C23. **Open gaps:** G6, G11, G12.

---

## 3. E-Sign & E-Stamping Contract

**Vendor category:**
**e-Sign** — NSDL e-Sign / Protean ESP / Leegality / Digio / SignDesk (Aadhaar OTP + DSC).
**e-Stamping** — SHCIL or bundled via the same orchestration vendor (Leegality/Digio typically bundle).
Vendor consolidation question is open (G14).

### 3.1 Three signing flows in Phase 1

| Flow | Trigger | Document | Signers | Stamping |
|---|---|---|---|---|
| Supplier Master Assignment Agreement | Supplier onboarding final step | MAA template populated | Authorised signatory (Aadhaar e-sign or DSC) | At master level (DL-048); state-of-supplier rate |
| Investor Master Investment Agreement | Investor onboarding final step | MIA + POA + risk disclosure | Individual investor (Aadhaar OTP) | At master level; state-of-investor rate |
| Per-investor assignment document | On 100%-funded transition, BEFORE disbursement (C27) | Auto-generated per investor | Individual investor (Aadhaar OTP) + supplier counter-sign | Per-invoice stamping — **PARKED-LEGAL** (DL-048); working assumption G2: async post-event, may be batched |

### 3.2 Assumed API surface

**e-Sign:** `document/upload` → `signature/init` (returns hosted URL) → `signature/status` → `document/{id}/download` (signed PDF + cert).
**e-Stamping:** `stamp/quote(state, doc_type, value)` → `stamp/issue` → `stamp/{cert_id}/attach`.

### 3.3 Callback semantics

HMAC-signed (C10). Events: `signature.completed`, `signature.failed`, `signature.expired`, `stamp.issued`, `stamp.failed`. At-least-once; we dedupe by `signature_request_id` / `stamp_cert_id`.

### 3.4 Idempotency

Our `client_request_id` per signature init; replays return the same hosted URL within session validity (~30 min). Document-level idempotency: same `doc_hash` + same signer → same logical request.

### 3.5 Per-investor assignment as one logical event (G2 + G13)

On 100%-funded transition, platform emits `AssignmentsRequested(listing_id, investors[])` and generates N assignment docs in parallel. Each signature tracked independently; **aggregate state** is `all_signed` or `incomplete`. Disbursement gate (T&S approval) requires `all_signed`. **Time-box (G13):** 24h to complete all signatures, else listing escalates to Credit Reviewer.

### 3.6 Failure modes & fallback

| Mode | Detection | Fallback |
|---|---|---|
| UIDAI outage (Aadhaar OTP down) | e-Sign provider error | Offer DSC alternate where available; otherwise pause and notify investor; T+1 disbursement SLA (C11) breaks gracefully — communicated to investor and supplier |
| Signer abandons mid-flow | Session timeout | Re-issue link; max 3 attempts; then escalate |
| Stamp issuance failure (master level) | `stamp.failed` webhook | Retry; persistent → panel-CA manual e-stamp; onboarding completion gated on resolution |
| Stamp issuance failure (per-invoice, when activated) | `stamp.failed` | Mark "stamping-pending"; does NOT block disbursement, since per-invoice strategy still parked-legal — re-confirm when activated |
| Signature verification fails on download | SHA-256 hash mismatch | Doc invalidated; re-sign required; incident logged (C2) |

### 3.7 Audit trail per signature event (C1, C2)

Signer identity, IP, timestamp, document SHA-256, certificate serial number, provider event_id — written to immutable audit log. Signed PDFs stored encrypted (C14) for 10 years (aligned with C1 retention).

**Constraints honoured:** C1, C2, C10, C14, C27. **Open gaps:** G2, G13, G14, G15.

---

## 4. Cross-cutting Patterns

These apply identically across all three integrations and are not repeated per contract:

1. **Webhook signature verification** — every inbound callback HMAC-checked before any state mutation. Unsigned/invalid signature → reject + alert (C10).
2. **Idempotency by `client_request_id`** — platform-generated UUID on every outbound mutating call. Vendor must honour it.
3. **At-least-once webhook handling** — platform-side dedupe on vendor `event_id`. Never assume exactly-once.
4. **Provider-instructed actions are provisional** until end-of-day reconciliation overlay (C23). Authoritative state lives in our reconciled ledger, not in webhook stream.
5. **All vendor payloads stored verbatim** alongside our extracted fields, for evidence and dispute defence.
6. **Single-vendor risk accepted in Phase 1** (Founder Review §5). No multi-provider redundancy until Phase 2.

---

## How to use this document

Each downstream artefact (event model, aggregates, settlement workflows, signing orchestration) is checked against these three contracts. When a vendor is selected, the *assumed* API surface and callback semantics here are reconciled against the *actual* vendor capabilities; deltas either change our design or trigger a Decision Log entry.

Cross-references:
- **Decision Log:** DL-026, DL-043–046 (escrow), DL-047 (aggregator), DL-048 (e-sign), DL-045 (TDS), DL-030 (T+1), DL-017 (funding invariants), DL-050 (KYC stack).
- **Constraints:** C8, C9, C10, C11, C12, C13, C14, C15, C17, C22, C23, C24, C27.
- **Gaps:** G2, G4, G6, G8, G11, G12, G13, G14, G15, G16.
