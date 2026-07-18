# M16 · Tax & Reporting (BC12) — DoR (GREEN)

> **Full-rigor module** (money + legal risk). Spec-first: this is the DoR for the gate in
> `Spec_Driven_Build_Plan.md` §E. **DoR GREEN — founder sign-off 2026-07-12** (see
> `docs/M16-tax-founder-decisions.md`). All eight `/clarify` questions resolved (§ below); `/plan` may start.

| | |
|---|---|
| **Module** | M16 — Tax & Reporting (BC12) |
| **Why now** | Completes the money-movement lifecycle — unblocks M13 **distribution + close** (DL-BE-054). Spine today ends at `matured_payment_received`; M16 lets it reach `distributed / closed`. |
| **Tier** | Full rigor |
| **Status** | **DoD DONE — 2026-07-12.** Built, reviewed, hardened; full suite green. |
| **Owner** | Amit + Claude |
| **Refs** | BC12 (Bounded_Contexts_Reference), DL-045, G4, G12, S.7, S.9/PI.3, DL-BE-054, DL-BE-066..069 |

> **DoD summary (2026-07-12).** Shipped: `V8` effective-dated `tax_rate_default` (10%/20%, stamped+frozen per
> investor×FY); `TaxEngine` (largest-remainder split, TDS on return only, `fee=0`); Treasury maker-checker
> **distribution** command → writes TDS ledger + `distribution_outcome`, bumps FY cumulatives, **closes the deal
> as `distributed`**; Compliance-issued **Form 16A** (downloadable, frozen bytes) + tax query endpoints.
> `/code-review` (high) found 3 issues, all fixed in **`V9`** + code: (1) missing one-distribution-per-listing
> unique index (double-payout risk) → added, mirroring V6; (2) Form 16A `download` re-rendered over mutable
> tables (500 after a later same-FY distribution) → now serves persisted frozen bytes; (3) cumulative-bump
> UPDATE lacked a rowcount guard → added. `./mvnw clean verify` green. Decisions: DL-BE-066/067/068/069.

---

## The one-line outcome
A matured deal **pays every investor** (principal + return), **withholds TDS on the return correctly**,
**records the deduction + issues statements**, and **closes as `distributed`**.

---

## DoR checklist (§E) — drafted

### ☐ Scope & boundary
- **Owns (BC12):** TDS **computation**, the deduction ledger, the tax-year profile, investor tax statements.
- **Tables (already in schema, V2):** `tax_year_profile` (investor × FY: `tds_rate_bps`, `pan_verified`, `cumulative_gross_paise`, `cumulative_tds_paise`, Form-16A fields), `tax_tds_deduction` (per payout: `gross/tds/fee/net_paise` with `net = gross − tds − fee` CHECK, `challan_ref`, `payout_instruction_id`), `tax_investor_statement` (`kind ∈ {monthly_portfolio, form_16a}`).
- **Does NOT own:** the distribution **payout** itself — that is BC4's `cash_payout_instruction (kind='distribution')` and the deferred **M13-distribution** slice. ⚠️ **Boundary decision:** does M16 add the distribution command, or does BC4/settlement own the command and *call* M16 to compute TDS? *(Recommend: BC4 owns the payout command; M16 owns the tax brain it calls, in-tx.)*

### ☐ Upstream dependencies
- **M13 Settlement** (maturity recording) — **Done**; emits `Listing.Matured`.
- **M13-distribution (BC4)** — the payout instruction this couples to — *not built*; build together or M16-first-then-BC4. ⚠️ sequencing.
- **M10 Investor** (`inv_account`, PAN/`pan_verified`) — Done. **M11 Subscription** (per-investor funded amounts, `distribution_outcome` S.7) — Done.
- **Return math source:** interest = `face_value − funding_target` (BC1), split per investor by funded share.
- **Stubbed ports:** BC18 escrow (payout execution + TDS **challan** deposit → `challan_ref`, G12), BC15 notifications (statement delivery), document store (`sys_document_object`) for statement docs.

### ☐ Domain rules & invariants
- **Paise equality:** `net = gross − tds − fee`, integer paise, enforced app + DB (`tax_tds_deduction_net_formula_chk`, subscription `distribution_outcome` S.7). Σ(net)+Σ(tds)+Σ(fee) reconciles to buyer repayment.
- **TDS base:** ⚠️ TDS on **interest income only** (not returned principal) — confirm `gross_paise` = principal+interest while `tds_amount = rate × interest`. Pin the base definition.
- **Rate resolution:** `tds_rate_bps` from `tax_year_profile` (investor × FY); ⚠️ **PAN-absent → higher rate (206AA)** — confirm exact fallback rate + where `pan_verified` is read.
- **Cumulative & thresholds:** running `cumulative_gross/tds_paise` per FY; ⚠️ any threshold/exemption logic (G4/DL-045) in Phase 1, or flat rate?
- **Immutability:** the TDS snapshot is **frozen once the distribution is approved** (`PI.tds_immutable`) — deduction records can't change post-checker.
- **Close invariant:** a matured deal closes **only** as `terminal_outcome = 'distributed'` (`deal_listing_terminal_outcome_shape_chk`) — so close *is* the distribution executing.
- ⚠️ **Fee mechanics:** what is `fee` in the distribution leg (platform fee source/rate), and is it in M16 scope or already set upstream?

### ☑ Schema mapped
- The 3 BC12 tables + `tax_investor_statement_kind` enum exist (V2). Reuse as-is.
- **New migration V8** (additive, next free number after V7): create **`tax_rate_default`** — effective-dated
  reference table `(fy_code, pan_verified, rate_bps, effective_from, [note])`, PK/uniqueness on `(fy_code,
  pan_verified)`; **seed FY2026-27 rows** `{with-PAN: 1000 bps, without-PAN: 2000 bps}`. This is the *default
  source* only; the stamped rate lives on `tax_year_profile.tds_rate_bps` (immutable per investor×FY).
- No other schema changes: `tax_year_profile.form_16a_*` columns + `tax_tds_deduction.challan_ref`/`fee_paise`
  already exist (V2). `sub_subscription.distribution_outcome` CHECK already enforces `net = gross − tds − fee`.

### ☐ Events
- **Subscribes:** `Listing.Matured` (M13).
- **Publishes:** ⚠️ name them — e.g. `Tds.Deducted` (per investor), `Deal.Distributed` / `Listing.Closed`, `Statement.Issued`, `TaxYear.Closed`. Payload shapes TBD.

### ☐ ACL contracts (ports + stubs)
- Escrow payout + **TDS challan deposit** (BC18 port; stub returns a fake `challan_ref`).
- Notification dispatch for statements (BC15 port; stub).
- Document rendering/store for Form-16A. ✅ **Generation IS in Phase-1 scope** (Founder Q6): a `DocumentStore`
  port renders the Form 16A, stores bytes in `sys_document_object`, returns the `doc_hash` recorded on the
  profile + statement row. Monthly-portfolio doc = record/`doc_hash` seam only for now.

### ☐ API surface (draft)
- **Commands:** distribution draft → approve (maker≠checker, treasury) *[BC4, calls M16]*; issue Form-16A / statement; close FY.
- **Queries:** `GET` investor tax statement(s); `GET` TDS deductions for a listing / investor / FY.

### ☐ Control rules
- Distribution payout: **maker → checker** (treasury, two people), **MFA-fresh**, **idempotent on `command_id`**, TDS snapshot **immutable post-approval**, **audit-logged**. Tax computation runs inside the distribution command's tx.

### ☐ Audit events
- Each distribution/deduction → one envelope; each statement issuance → envelope; FY close → envelope. (All chained `sys_audit_event`.)

### ☐ Frontend contract
- ⚠️ Confirm consuming mock screen(s) in `../fintech-patform-ui` (investor statements / settlement console) and agree request/response shapes.

### ☐ Test scenarios
- Happy multi-investor distribution with TDS → all paid, deal `distributed/closed`.
- PAN-absent → higher-rate withholding. · `net = gross − tds − fee` equality (app **and** DB reject).
- Maker == checker → reject. · Idempotent retry → no double-pay. · Immutable snapshot: mutate-after-approval → reject.
- Paise equality across legs. · Under-/over-distribution → reject. · FY cumulative totals update correctly.

### ☐ Acceptance criteria
- A matured, fully-repaid deal distributes to **every** funding investor with correct per-investor TDS, reconciles to the paise, records deductions + `tax_year_profile` cumulatives, closes as `distributed`, and exposes each investor's statement/TDS records.

### ☑ `/clarify` questions — ALL RESOLVED (founder sign-off 2026-07-12)
1. **Command boundary** — ✅ **BC4 owns the distribution payout command; M16 owns the TDS engine it calls in-tx.**
   (Founder Q7/Q8.) M16 adds the distribution maker-checker command on `cash_payout_instruction kind='distribution'`
   and closes the listing; the tax computation is a shared engine invoked inside that command's tx.
2. **TDS base** — ✅ **Return (interest/discount) only, never returned principal** (Founder Q1). Per investor:
   `gross_paise = principal_share + interest_share`; `tds_amount_paise = round(tds_rate × interest_share)`;
   `fee_paise = 0`; `net = gross − tds − fee`. `interest_share` = that investor's funded share of the listing
   discount (`face_value − funding_target`, per `FundingMath`), split by funded amount, HALF_EVEN per line.
3. **Rate source & 206AA** — ✅ **10% with PAN / 20% without** (Founder Q2). Sourced from a new **effective-dated
   DB reference table `tax_rate_default` (FY × PAN-status → `rate_bps`, seeded via Flyway V8)**, then **stamped +
   frozen** onto `tax_year_profile.tds_rate_bps` at first resolution for that investor×FY. `pan_verified` read
   from `inv_account` KYC/verification at stamp time and snapshotted onto the profile. Future seams (not built):
   all-in `rate_bps` absorbs surcharge/cess; per-investor profile override supports §197 certificates.
4. **Thresholds/cumulative** — ✅ **From rupee one, no threshold** (Founder Q3). Flat per-FY rate; keep running
   `cumulative_gross/tds_paise` on `tax_year_profile` for reporting only, not gating.
5. **Fee** — ✅ **`fee_paise = 0`, kept configurable** (Founder Q4). First-class field retained; rate resolves to 0.
6. **Remittance/challan** — ✅ **Record only** (Founder Q5). No tax-authority integration; `challan_ref` seam
   recorded, deposit + 26Q filing manual by finance.
7. **Statement generation** — ✅ **Generate Form 16A document NOW** (Founder Q6, *deviation from pilot*). Render →
   store in `sys_document_object` → record `form_16a_doc_hash`/`form_16a_issued*` on `tax_year_profile` and a
   `tax_investor_statement(kind='form_16a')` row. Monthly-portfolio statement stays record-only for now.
8. **Sequencing** — ✅ **One combined slice** (Founder Q7) — distribution + TDS + Form 16A + close, built together.

---

**→ DoR GREEN. Proceed to `/plan` → `/tasks` → `/implement` (red invariant tests first).**
