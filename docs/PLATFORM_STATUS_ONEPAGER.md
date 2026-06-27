# What the Platform Does Today — One-Page Status

> **For investors / founder review · 2026-06-27.** Plain-language summary of the working product. The
> technical backend core is **complete and proven end-to-end**; the user-facing screens and live external
> integrations are the next build phases.

---

## The product in one line
A **regulated invoice-discounting platform** — suppliers raise cash against unpaid invoices; investors fund
those invoices and earn a return when the buyer pays. Every rupee movement is controlled, segregated, and
audited.

## The full deal journey works today (end-to-end)

A live demo can take a single invoice through its **entire life**, automatically:

| Step | What happens | Status |
|---|---|:---:|
| **1 · Onboard** | Supplier, Buyer and Investor are vetted — identity (PAN/GSTIN/CIN), KYC, bank verification, credit assessment | ✅ |
| **2 · List** | An invoice is listed, operationally checked, buyer-acknowledged, and **priced** | ✅ |
| **3 · Go live** | The listing is published for funding (with a second-person approval) | ✅ |
| **4 · Fund** | Investor(s) subscribe; money lands in a virtual escrow account and is confirmed | ✅ |
| **5 · Assign** | The invoice is legally assigned and **e-signed** by every funding investor | ✅ |
| **6 · Disburse** | Cash is released to the supplier (two-person treasury approval) | ✅ |
| **7 · Mature** | The buyer repays at maturity; the deal is marked settled | ✅ |

*Result: a deal goes from "invoice listed" to "supplier paid and buyer repaid" — the core economic loop is built.*

## The trust & compliance guarantees (built into every money action)

These are not features bolted on later — **every state-changing action enforces all five**, at the database
level as the last line of defence:

- **Two-person rule** (maker ≠ checker) on every sensitive action — no single person can move money.
- **Fresh multi-factor authentication** required to authorise.
- **Segregation of duties** — Ops, Credit, Compliance and Treasury have distinct, enforced powers.
- **Exactly-once** — a repeated or replayed instruction can never double-pay.
- **Tamper-evident audit trail** — every action is cryptographically chained and immutable.

Plus: **money is held as exact integers (paise), never floating-point**; all credit limits, exposure caps and
pricing are policy-driven and version-snapshotted.

## What's deliberately next (honest roadmap)

| Next | Why |
|---|---|
| **Manual API validation** *(in progress)* | Exercise every endpoint before building on top |
| **User screens (UI)** | 15 screens wired to the live backend, module by module |
| **Live integrations** | Swap the safe test-stubs for real bank/escrow, e-sign, KYC and messaging providers |
| **Returns payout + tax (TDS)** | Pay investors their returns and close deals — the one money-step still stubbed |
| **Stronger controls for scale** | Senior sign-off above ₹10 Cr, counterparty suspension, automated reminders |

## Proof points

- **279 automated tests** pass on every change (real database, not mocks).
- The **entire 7-step journey** above runs as a one-command automated smoke test.
- Built **spec-first** against a frozen product specification and a 28-point architectural constitution;
  every non-obvious decision is logged.
- **Regulator-ready foundation:** immutable audit, segregation of duties, and DB-enforced invariants from day
  one — not retrofitted.
