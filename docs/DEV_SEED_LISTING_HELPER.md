# BACKEND DEV HELPER — seed a listing at a money-flow stage (dev-profile only)

> **What this is.** A small **dev-only** helper that fast-forwards a fresh listing to a requested point in the
> deal spine, so the UI's **money-flow writes** (S6 approve-disbursement, S7 maturity/distribution, S12 subscribe,
> S13 portfolio) become testable **without hand-driving ~20 real commands + a document upload** each time.
>
> **Why it's needed.** Those writes sit behind the full pipeline: `create → ops-checks (incl. an attached invoice
> document for `document_completeness`) → buyer-ack → snapshot-and-ready → approve-go-live → subscribe → funding →
> assignment-signing → disbursement draft/approve → maturity → distribution`. The dev seed currently produces
> only onboarded counterparties (no listings), so S6/S7/S12/S13 can't be exercised live. A dev harness confirmed
> the pipeline works but blocks at each real prerequisite — and one of them is **currently unsatisfiable with the
> seed**: `document_completeness` needs an **attached invoice document** (a full BC16 upload) **and** must be
> recorded by a *different* ops user than the one who attached it (**DOC.3, maker-checker**). The dev seed has only
> **one** `ops_executive` account, so the real path to `ready_for_review`/`live` cannot be driven end-to-end at
> all. Direct-insert seeding (this helper) sidesteps every such gate; **additionally, seed a second ops account
> (`ops2@dev.local`)** so flows that genuinely need two ops (DOC.3) are drivable via real commands too.
>
> **This is a brief, not a spec.** Take it through the repo loop (`/specify → /clarify → DoR → /plan → /tasks →
> /implement → DoD`). Append `DL-BE-xxx` (start at the next free id). It touches **no** production code.
>
> **Sibling to** `DevDataSeeder` / `DevController` (the `/dev/last-otp`, `/dev/seed-info` helpers) — same
> `@Profile("dev")` guard, same direct-`JdbcTemplate`-insert approach.

---

## 0. Scope & non-goals

**In scope:** one dev-only endpoint that creates a **fresh** listing (+ its deal-invoice, subscription(s), and
`cash_payout_instruction` as needed) directly in the DB, fast-forwarded to a requested `stage`, and returns the
ids the tester/UI will act on.

**Non-goals / guardrails:**
- **Dev profile only.** Bean(s) guarded by `@Profile("dev")`; `/dev/**` is already `permitAll` + 404 in prod
  (`SecurityConfig`). No prod path, ever.
- **No change to any real command/service/read.** This only *inserts state*; it does not alter the pipeline.
- **Direct `JdbcTemplate` inserts** (mirroring `DevDataSeeder`) to bypass maker-checker/MFA/ops-checks/document
  gates — the point is to *land the terminal state* fast, not to re-run the flow.
- **The seeded state must be valid** — satisfy the DB CHECK constraints and the invariants the real reads +
  the command-under-test assume (see §3), so the UI write succeeds against it.
- **Repeatable:** every call mints new ids (fresh listing/invoice), so calls don't collide. Reuses the seeded
  active supplier/buyer/investor from `DevDataSeeder` (via `/dev/seed-info`).

---

## 1. The endpoint

```
POST /api/v1/dev/seed-listing
body (JSON, all optional): { "stage": "live", "rate_bps": 1200, "amount_paise": 5000000, "maker": "treasury@dev.local" }
→ 200 { listing_id, invoice_id, supplier_id, buyer_id, investor_id,
        subscription_id?, payout_instruction_id?, stage, funding_target, status }
```

- `stage` (default `live`) — how far to fast-forward (see §2).
- `rate_bps` (default 1200), `amount_paise` (default = a small face value, e.g. 50_00_000) — pricing/size.
- `maker` (default `treasury@dev.local`) — for the `disbursable` stage, the **maker** of the drafted payout
  instruction, so a *different* treasury account (e.g. `treasury2@dev.local`) can approve it (checker ≠ maker).
- Returns every id the caller needs, so the UI/harness can immediately drive the write under test.

---

## 2. Stages (each = the terminal DB state to land)

| `stage` | Lands the listing at | Unblocks (UI write to test) |
|---|---|---|
| `live` | `deal_listing.status = 'live'`, `funding_target` set, `committed_total = 0`, `va_id` set | **S12 subscribe** (`POST …/subscriptions/commit`) |
| `fully_funded` | `status = 'fully_funded'`, `committed_total = funding_target`, `all_signed = true`, + one confirmed `sub_subscription` | **S6 draft** (`POST …/disbursement/draft`) |
| `disbursable` | as `fully_funded`, **plus** a `cash_payout_instruction` (kind `disbursement`, `status='drafted'`, `maker_id` = the `maker` account) | **S6 approve** (`POST …/disbursement/approve`, checker ≠ maker) |
| `disbursed` | `status = 'disbursed'`, disbursement instruction `executed` | **S7 record-maturity** (`POST …/record-maturity`) |
| `matured` | `status = 'matured_payment_received'` + a **drafted** distribution instruction | **S7 distribution approve** + **S13** portfolio outcome |

Implement incrementally — `live` and `disbursable` are the highest value (unblock S12 and S6). `disbursed` /
`matured` unblock S7/S13.

---

## 3. What each stage must populate (grounded in the schema)

Direct inserts, reusing the seeded supplier/buyer/investor. Fill the **real** columns; below is the load-bearing
subset (the implementing session fills the full NOT-NULL set from the DDL):

- **`deal_invoice`** — a fresh invoice (`invoice_id`, `supplier_id`, `buyer_id`, `irn` valid-format or null,
  `invoice_number`, `face_value`, `invoice_date`, `tenor_days`, `due_date`, `status`, `check_outcomes` JSONB —
  set all canonical checks to a passing outcome so no gate trips downstream).
- **`deal_listing`** — `listing_id`, `invoice_id`, `supplier_id`, `buyer_id`, `status` (the stage target),
  `funding_target`, `committed_total`, `va_id`, `all_signed`, `aggregate_version`, and the immutable
  `pricing_snapshot` (`{pricing_band_id, rate_bps, fee_bps, snapshot_at}`) once past `ready_for_review`.
- **`sub_subscription`** (for `fully_funded`+) — one row for the seeded investor, `amount = funding_target`,
  status `confirmed` (funds received), so the funding + assignment invariants hold.
- **assignment set / all_signed** (for `fully_funded`+) — set `deal_listing.all_signed = true` (and any
  assignment-set row the disbursement draft gate reads) so **PI.2 / C27** (`fully_funded ∧ all_signed`) passes.
- **`cash_payout_instruction`** (for `disbursable` / `disbursed` / `matured`) — `payout_instruction_id`, `kind`
  (`disbursement` or `distribution`), `listing_id`, `status` (`drafted`/`executed`), `gross_amount`, `net_amount`,
  `maker_id` (≠ the account that will approve — **the maker-checker CHECK is enforced in the DB**).

**Correctness checks to bake into the seeder (so the write-under-test really works):**
- `disbursable`: S6 approve reads the instruction is `drafted` and flips `deal_listing` `fully_funded → disbursed`
  — so the listing must be exactly `fully_funded` and the instruction exactly `drafted`, `maker_id` ≠ approver.
- `live`: S12 commit reads `funding_target`/`va_id` and `status='live'`.
- Set `aggregate_version` consistently (transitions that follow will thread it).

---

## 4. Implementation notes

- New `@Profile("dev")` bean, e.g. `DevListingSeeder`, invoked from `DevController` (`POST /dev/seed-listing`).
  Keep it beside `DevDataSeeder`; **raw `JdbcTemplate`**, no JPA (repo idiom).
- Reuse `/dev/seed-info`'s supplier/buyer/investor ids (or look them up the same way `DevController` does).
- Resolve `maker`/approver `admin_user_id` from the seeded accounts (email → `admin_user`) for the payout
  instruction's `maker_id`.
- Wrap each seed in a transaction; on any constraint failure, fail loud (dev only) — don't half-seed.
- Log the returned ids at INFO (like `DevDataSeeder` logs the login recipe).

---

## 5. Definition of done
1. `POST /dev/seed-listing {stage:"live"}` returns a `live` listing; **S12 subscribe** against it succeeds
   (`…/subscriptions/commit`).
2. `POST /dev/seed-listing {stage:"disbursable", maker:"treasury@dev.local"}` returns a listing with a **drafted**
   disbursement; logging in as **treasury2@dev.local**, **S6 approve** succeeds and flips the listing to
   `disbursed` (and a same-maker approve is rejected — checker ≠ maker still holds).
3. `disbursed` / `matured` stages unblock S7 record-maturity + distribution approve and the S13 outcome.
4. Prod is unaffected (no bean, `/dev/**` 404). `DL-BE` entry written; note the helper in `PROJECT_TRACKER.md`.

---

## 6. Handoff to the front-end (mock repo)
Once shipped, the mock side (`../fintech-patform-mock`) verifies the money-flow writes exactly like the
onboarding chains: call `/dev/seed-listing` for the stage, then drive the UI write (S6 approve / S7 / S12) and
assert the persisted transition. The mock's `docs/UI_WORKORDER.md` (Increment 4) already lists S6 approve wired
but **E2E-pending a disbursable listing** — this helper is what unblocks that verification.
