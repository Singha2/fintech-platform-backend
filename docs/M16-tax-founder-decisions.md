# Paying investors back — decisions we need (M16 · Tax)

> **Purpose.** Before we build the final money step — paying investors their money back — we need eight
> decisions. This page explains each in plain language, gives our recommendation, and leaves a space to
> tick. Meant to be read together in one sitting (~15 min).

> **STATUS — RESOLVED 2026-07-12 (founder sign-off).** All eight decided; scope locked, DoR green,
> build started. Ticks recorded inline below. Two conscious deviations from the pilot recommendation:
> **#6** — Form 16A is a **real downloadable document now** (not a fast-follow); **#4** — platform fee
> is **0% but kept configurable** (first-class `fee_paise` field, default 0). TDS rates are **not
> hardcoded**: an **effective-dated DB reference table** (FY × PAN-status → `rate_bps`, seeded via
> Flyway) is the default source; the rate is **stamped + frozen** onto each investor's `tax_year_profile`
> at first use, so changing a future year's default never disturbs already-issued certificates.

---

## Where we are, in one picture

Today a deal works all the way through — supplier listed, investor funded, invoice assigned, supplier paid,
buyer repaid. **The one step still missing: sending the money back to the investors** (with their return),
and closing the deal.

**A real example (a ₹10,00,000 invoice):**

| | Amount |
|---|---|
| Investor puts in (the discounted price) | **₹9,60,274** |
| Buyer repays at maturity (full invoice) | **₹10,00,000** |
| The difference = the investor's **return / profit** | **₹39,726** |

When we return money to the investor, the law says we must **deduct tax on their profit** before paying —
this is **TDS** (Tax Deducted at Source). So the investor gets back **their own money + profit − tax**, and
that deducted tax is set aside for the government.

The eight questions below are about **how exactly** we do that deduction and payout. Most are quick; a few
may need your tax advisor.

> **Mini-glossary:** **TDS** = tax we hold back before paying. **PAN** = the investor's tax ID. **Form 16A** =
> the certificate that proves how much tax we deducted. **206AA** = the rule that says "no PAN → higher tax."

---

## The 8 decisions

### 1. Tax only on the profit — please confirm 🟢 *(quick)*
We plan to deduct tax **only on the investor's profit (the ₹39,726)**, never on their own money coming back
(the ₹9,60,274). This is the normal rule.
- ✅ **Recommended:** tax on profit only.
- **Decision:** ☑ **Agree** — TDS base = the investor's return (discount) only, never returned principal.

### 2. What tax rate, and what if there's no PAN? 🔴 *(may need tax advisor)*
We hold back a percentage of the profit as tax. The rate is usually lower if the investor has given a valid
PAN, and **higher if they haven't** (the 206AA rule).
- ✅ **Recommended (please confirm the exact numbers):** standard rate with PAN (e.g. **10%**), higher rate
  without a verified PAN (e.g. **20%**).
- **Decision — rate with PAN:** ☑ **10 %** (1000 bps) · **rate without PAN:** ☑ **20 %** (2000 bps, 206AA).
  These are **defaults, not hardcoded**: sourced from an **effective-dated DB reference table**
  (`tax_rate_default`, FY × PAN-status → `rate_bps`, seeded via Flyway), then **stamped + frozen** onto each
  investor's `tax_year_profile.tds_rate_bps` at first use. Changing a future year's default never disturbs
  already-issued certificates. Future seams kept open (no build now): all-in `rate_bps` absorbs
  surcharge/cess; per-investor override supports §197 lower-deduction certificates.

### 3. Tax from the first rupee, or only after a yearly threshold? 🔴 *(may need tax advisor)*
Some tax rules only kick in after an investor's yearly earnings cross a limit.
- **Option A — ✅ Recommended for now:** deduct on **every** payout, from the first rupee (simplest, safest,
  never under-deducts).
- **Option B:** only deduct after a yearly threshold per investor (more complex; we track running totals).
- **Decision:** ☑ **A (from rupee one)** — deduct on every payout, no threshold tracking in Phase 1.

### 4. Does the platform take a fee out of the payout? 🟡 *(business)*
When we pay the investor, do we also subtract a platform fee from that payout?
- **Option A:** no fee taken at payout.
- **Option B — ✅ likely:** a fee is taken (please tell us **how much / how calculated**, e.g. % of profit).
- **Option C:** the fee is already taken elsewhere, not at payout.
- **Decision:** ☑ **A — no platform fee taken at payout**, `fee_paise = 0`. **Kept configurable:** `fee_paise`
  stays a first-class field on `tax_tds_deduction` and in `distribution_outcome` (the `net = gross − tds − fee`
  CHECK is unchanged); the fee *rate* resolves to 0 by default, so enabling a fee later is a config flip, not a
  schema change.

### 5. Do we deposit the tax with the government + file returns now, or later? 🔴 *(scope + compliance)*
After deducting the tax, it must eventually be **deposited with the government** and reported in a **quarterly
return**. Doing this automatically inside the system is a much bigger build (needs the live bank link).
- **Option A — ✅ Recommended for pilot:** the system **deducts and records** the tax correctly; **deposit +
  filing done manually / by finance team** for now.
- **Option B:** full automation — system deposits the tax and files the quarterly return itself (bigger,
  later, needs the real bank integration).
- **Decision:** ☑ **A — record now, deposit + filing manual** by finance team. No tax-authority integration;
  the system keeps the records correct (`challan_ref` seam recorded), finance deposits + files 26Q manually.

### 6. Do investors need a downloadable tax certificate now? 🟡 *(scope)*
Investors are eventually owed a **Form 16A** certificate showing tax deducted.
- **Option A — ✅ Recommended for pilot:** the system **records all the numbers** now; the actual downloadable
  certificate document comes in a later phase.
- **Option B:** generate the downloadable certificate document now.
- **Decision:** ☑ **B — generate the downloadable Form 16A document now** (deviation from pilot recommendation).
  Investors can download their Form 16A. Adds a document-generation slice: render → store in `sys_document_object`
  → record `form_16a_doc_hash` on `tax_year_profile`.

### 7. Build the payout and the tax together, or in stages? 🟢 *(quick)*
The tax calculation and the "pay the investors" step are two halves of the same thing.
- ✅ **Recommended:** build them **together in one push**, so the full money lifecycle works end-to-end (that's
  the whole goal of this module).
- **Decision:** ☑ **Together** — one push, full money lifecycle end-to-end (distribution + TDS + close).

### 8. Internal design note — no action needed ⚙️
We'll keep the tax calculation as a **shared engine** that the payout step calls — an internal engineering
choice for clean, testable code. **Flagging for visibility only; no decision needed** unless you have a
preference.
- **Decision:** ☑ **Fine, proceed** — shared TDS engine called in-tx by the distribution command.

---

## Quick summary of our recommendations

| # | Question | Our recommendation | **Decided (2026-07-12)** |
|---|---|---|---|
| 1 | Tax on profit only | ✅ Yes | ☑ **Yes** — return/interest only |
| 2 | Rate with / without PAN | Confirm the % (e.g. 10% / 20%) | ☑ **10% / 20%**, effective-dated DB defaults, stamped+frozen per FY |
| 3 | From rupee one or threshold | ✅ From rupee one | ☑ **From rupee one** |
| 4 | Platform fee at payout | Tell us the fee rule | ☑ **No fee (0%)**, kept configurable |
| 5 | Deposit + file now or later | ✅ Record now, deposit manually | ☑ **Record now, deposit + file manual** |
| 6 | Certificate document now or later | ✅ Numbers now, document later | ☑ **Document now** (Form 16A downloadable) — *deviation* |
| 7 | Build together or in stages | ✅ Together | ☑ **Together** |
| 8 | Internal design | No action | ☑ **Shared engine, proceed** |

**The pattern:** for the pilot we recommend **getting the deduction and payout 100% correct now**, and
treating the **government deposit, filing, and certificate documents as fast-follows**. That completes the
money lifecycle quickly without waiting on the live bank link.

> Once these are ticked, engineering locks the scope and builds the final "pay investors + close the deal"
> step. Questions 2, 3 and 5 are the ones worth a quick word with the tax advisor.
