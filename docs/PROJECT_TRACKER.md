# PROJECT TRACKER — Backend + UI (single source of truth)

> **This file is the one place to read "where are we and what's next" across *both* repos.**
> Backend: `fintech-platform-backend` · UI: `../fintech-patform-mock`.
> Every other plan doc is **subordinate detail** feeding this tracker (see §1). When a status changes,
> update **here first**, then the detail doc. Do **not** start a new top-level plan — that is what caused
> the drift this file exists to end.
>
> **Last updated:** 2026-07-18 · **Maintained by:** solo dev (Amit).

---

## 0. Current state — the one-glance summary

Backend and UI are each mature and the **bridge is essentially built** — auth, reads, the whole admin +
deal-flow write path, **real investor self-service (BE-18)** and the **buyer portal (BE-15)** are live and
E2E-verified. **14 of 15 screens are wired live;** only **S9** (audit log) remains, blocked on **M17 auditor**.

| Half | State | Evidence |
|---|---|---|
| **Backend core** | ✅ Complete — full money lifecycle `listed → … → distributed → closed` over HTTP, five controls enforced | 413 tests green (real Postgres) |
| **Backend read surface (for the UI)** | ✅ **BE-1…BE-12 + BE-14 + BE-17 shipped** (admin reads + dashboard + investor read-only portal). BE-13/15/16 deferred by design | `docs/API_CATALOGUE.md`, `DL-BE-079…084` |
| **UI (mock)** | ✅ All **15 screens** built and working **offline** on a mock store | `../fintech-patform-mock/src/store/PlatformStore.jsx` |
| **🟢 Live wiring (the bridge)** | ✅ **Auth + reads + admin/deal-flow writes live & E2E-verified** — `src/api/` client + envelope + `ApiError` + full service layer + `AuthContext` + Vite proxy. **S1 login**; **reads** for S2–S8/S11/S12/S13/S14 (S13 investor portfolio, own-scoped); **write chains** onboarding (S3/S4/S8 + **S10 investor**) + deal spine **S5 go-live → S12 subscribe → S6 disburse → S7 mature/distribute** (harnesses `scripts/e2e/*`, DL-BE-086/087 seed helper + two-ops); **investor self-service** — passwordless login + S12 self-commit (BE-18/DL-BE-088); **buyer portal** — ack-user login + reads + self-ack (BE-15/DL-BE-090); **admin IAM** — super-admin provision + role-assignment widget on S2 (`/admin-users/*`). Also: live nav derived from `/auth/session` roles (no persona map). Remaining: **S9** (M17). | mock `docs/UI_WORKORDER.md` |

**The admin + deal-flow surface, investor onboarding (S10) + portfolio (S13) + self-service (BE-18), and the
buyer portal (S15/BE-15) are all done and verified.** The only screen not wired live is **S9** (audit log),
blocked on the **M17 auditor** backend milestone.

---

## 1. How the docs fit together (drift-killer)

**Rule of thumb:** *status* lives here; *detail* lives in exactly one doc below; *nothing* has two homes.

| Doc | Repo | Role now | Authority |
|---|---|---|---|
| **`PROJECT_TRACKER.md`** (this) | backend | **Index + status + next actions** | ⭐ top |
| `API_CATALOGUE.md` | backend | Endpoint reference (every route + shape) | ✅ live reference |
| `UI_INTEGRATION_BACKEND_SPEC.md` | backend | BE-1…BE-16 read-surface design detail | ✅ done (reference) |
| `ROADMAP.md` | backend | Backend **remainder** (non-UI: M14/M15/M17) | ✅ live reference |
| `spec/Spec_Driven_Build_Plan.md` | backend | Module bible (build order, gates) | ✅ authoritative for modules |
| `DECISION_LOG.md` (`DL-BE-*`) | backend | Why-we-did-it record | ✅ append-only |
| **`INTEGRATION_PLAN.md`** | mock | **UI wiring — the executable step-by-step** | ⭐ active execution doc |
| `API_ALIGNMENT.md` | mock | Per-screen ↔ endpoint shapes + enum corrections | ✅ reference |
| **`BACKEND_UI_READINESS.md`** | mock | **Backend→UI hand-off** — what the live API actually ships + corrections to stale plan assumptions | ⭐ read before wiring |
| `HARMONIZATION.md`, `Mock_Build_Plan.md`, `TIER2_SHARED_STORE_PLAN.md`, `STEP*_BLUEPRINT.md` | mock | **Historical** — how the mock was built | 🗄️ archive (don't extend) |

Two docs are "plans you execute from": **INTEGRATION_PLAN** (UI side) and **Spec_Driven_Build_Plan** (backend
modules). Everything else is reference or history. This tracker links them; it does not replace them.

---

## 2. Master board — 15 screens × (backend · UI mock · live wiring)

Legend: ✅ done · ⚠️ partial · ❌ not done · ⛔ blocked (deferred milestone).

| Screen | Needs (backend) | Backend | UI mock | **Wired live** | Next action | Blocked by |
|---|---|:---:|:---:|:---:|---|---|
| **S1** Login + MFA | `auth/login/*`, `GET /auth/session` (BE-1) | ✅ | ✅ | ✅ | **Done** — bridge Phase 0–2 (proxy + `src/api` + `AuthContext`); `/auth/session` wiring lands in Phase 3 | — |
| **S2** Admin dashboard | `/admin/work-queues`, `/admin/stats` (BE-12); `/admin-users/*` (IAM) | ✅ | ✅ | ✅ | **Stats + queue reads live** (`useHydrate('dashboard')`; queues counts-only per BE-12). **+ Admin & Roles widget (super-admin only)** — provision admins + assign roles live (`POST /admin-users/provision`, `…/{id}/roles`); SoD soft-pair → override_reason; non-super → 403. E2E-verified `scripts/e2e/admin-roles.mjs` (7/7) | — |
| **S3** Supplier onboarding | `GET /suppliers` (BE-4), `…/kyc-file` (BE-2), supplier cmds | ✅ | ✅ | ✅ | **Read + full onboarding chain live** — create → identity → submit-kyc → kyc-approve (COMPLIANCE) → financial + credit-review (CREDIT) → maa → activate. Status-driven wizard; version threaded; SoD roles enforced; no fallback | — |
| **S4** Buyer mgmt + credit | `GET /buyers`, `…/pricing-bands` (BE-5), buyer/credit cmds | ✅ | ✅ | ✅ | **Read + full onboarding chain live** — nominate → identity-verified → credit-assessment → engagement → (ack-user + confirm-PI + activate). Version threaded per step; SoD roles enforced; no fallback. *(pricing-bands read still mock)* | — |
| **S5** Invoice checks + listing | `GET /listings`, `…/ops-checks` (BE-6), listing cmds | ✅ | ✅ | ✅ | **Reads + all listing writes wired + E2E-verified** — full real two-ops go-live pipeline (`scripts/e2e/s5golive.mjs`, 22/22): ops-checks → invoice-PDF upload (BC16) → `document_completeness` DOC.3 (uploader rejected, `ops2@` accepted) → complete → buyer-ack → snapshot-and-ready → approve-go-live → `live`+VA. **Invoice-doc upload UI added** (DF-4). | — |
| **S6** Disbursement queue | `/disbursements`, `…/disbursement/detail` (BE-7) | ✅ | ✅ | ✅ | **Queue read + approve write wired + E2E-verified** (`moneyflow.mjs`): `POST …/disbursement/approve` on a `stage:"disbursable"` seed — same-maker → **409 checker_equals_maker**, `treasury2@` → 200, listing `fully_funded → disbursed` | — |
| **S7** Distribution + recon | `…/distribution/investors`, `/reconciliation` (BE-8) | ✅ | ✅ | ✅ | **Maturity + distribution writes wired + E2E-verified** (`moneyflow.mjs`): `record-maturity` is **OPS** (treasury → 403; face-value amount) → `matured`; distribution `draft` (TREASURY maker, now **two separate buttons**) → `approve` (`treasury2@` checker) → **closed/distributed**. Distributions-list read → projection; recon local | — |
| **S8** Investor invites | `GET /investor-invites` (BE-9) + issue cmd | ✅ | ✅ | ⚠️ | **List read + Issue write live** (POST /investor-invites/issue → refresh; persists); list omits email/phone PII; revoke has no endpoint (mock) | — |
| **S9** Audit log | `GET /audit/events` (BE-13) | ⛔ | ✅ | ❌ | Await **M17 Auditor** | BE-13 / M17 |
| **S10** Investor onboarding | investor cmds + `…/kyc-file` (BE-2) | ✅ | ✅ | ✅ | **Full onboarding chain live + E2E-verified** (`scripts/e2e/investor-onboarding.mjs`, 11/11) — status-driven ops/compliance console: sign-up (from pending invite) → identity → submit-kyc → assess-suitability (COMPLIANCE ± override) → complete-financial-profile → record-kyc-approved (COMPLIANCE) → record-mia-signed → activate. Version threaded; SoD enforced (ops issue-invite → 403). Ops-on-behalf until investor self-login (**BE-18**) | — |
| **S11** Listing marketplace | investor-scoped `GET /listings?status=live` (BE-14) | ✅ | ✅ | ✅ | **Marketplace read live** (`useHydrate('marketplace')`); an investor logs in passwordless (BE-18) and lands here, then subscribes on S12 (self-commit) | — |
| **S12** Listing detail | `GET /listings/{id}/detail` (BE-10, admin) | ✅ | ✅ | ✅ | **Detail read + subscribe write wired + E2E-verified** — **investor self-commit** `{amount_paise}` under own session (BE-18, `investor-self-commit.mjs` 8/8) **or** ops-on-behalf `{investor_id,…}`; cross-tenant id → 403. `committed_total` increments | — |
| **S13** Investor portfolio | `…/subscriptions` + summary (BE-14) | ✅ | ✅ | ✅ | **Portfolio read live + E2E-verified** (`scripts/e2e/investor-portfolio.mjs`, 10/10) — `GET /investors/{id}/subscriptions` → `{rows,summary}` (BE-17) + tax deductions/statements, scoped via `GET /auth/session` own `investor_id` (dev: `/dev/seed-info` fallback for admin bearer). Cross-tenant read → **403 `cross_tenant_read`**. Read-only persona; real investor login stays dev-password (**BE-18**) | — |
| **S14** Supplier tracker (admin) | `GET /suppliers/{id}/listings` (BE-11) | ✅ | ✅ | ✅ | **Tracker read + Submit Invoice live** — submit now runs the real origination chain (acting-as/agency, OPS): `POST /listings` (→ deal_invoice `submitted`) → BC16 doc flow (initiate → PUT → finalize → attach). E2E-verified `scripts/e2e/invoice-submit.mjs` (8/8). *(buyer_name blank in the tracker read — not in BE-11)* | — |
| **S15** Buyer portal | ack-user OTP login + buyer reads + self-ack (BE-15) | ✅ | ✅ | ✅ | **Live + E2E-verified** (`scripts/e2e/buyer-portal.mjs`, 9/9) — passwordless ack-user login → `/auth/session` `buyer_id` → own-scoped `GET /buyers/{id}/ack-invoices` + `/payment-instruction` → buyer **self-ack** (`record-buyer-ack {outcome:'acknowledged'}`, requires outstanding request) → acknowledged; cross-tenant read → 403; ops-on-behalf no regression. UI: passwordless login, live reads, self-ack, real logout (DL-BE-089). Payment-instruction metadata-only (bank fields upstream follow-up). Spec: [`modules/M11-C-buyer-portal-ack.md`](modules/M11-C-buyer-portal-ack.md) | — |

**Wire-able now (backend ready): S1–S8, S10, S11, S12, S13, S14.** Blocked on a milestone: S9, S15.
_(S11/S13 unblocked by **M10-D**; investor read-only login uses the dev password today — real investor login is Phase B/BE-18.)_

---

## 3. Backend read surface — BE-1…BE-17 at a glance

| | Endpoints | Status |
|---|---|:---:|
| BE-1…BE-12 | session, kyc-file, suppliers, buyers+bands, listings+ops-checks, disbursement, distribution+recon, invites, listing-detail, supplier-tracker, dashboard | ✅ shipped (413 tests) |
| BE-13 | `GET /audit/events` | ⛔ build in **M17 Auditor** |
| BE-14 | investor marketplace + portfolio (ownership-scoped) | ✅ shipped (**M10-D**, DL-BE-084) |
| BE-15 | buyer ack-user login + buyer reads + self-ack | ⛔ build in **WS-2** |
| BE-16 | CORS | prod-only (dev uses Vite proxy) |
| BE-17 | investor read-only self-login (`/auth/session` +`investor_id`, ownership scoping, KYC download gate); dev password only — real login is Phase B/BE-18 | ✅ shipped (**M10-D**, DL-BE-084) |

Detail: `UI_INTEGRATION_BACKEND_SPEC.md`. All are **additive** — no frozen contract changes.

---

## 4. Backend remainder (non-UI, runs in parallel — does not block wiring)

From `ROADMAP.md` §5. These add capability but the UI wiring above does not wait on them.

| Module | What | Status |
|---|---|:---:|
| **M15** Compliance | sanctions/PEP, reg reporting hooks | ⬜ todo |
| **M14** Collections | delinquency, recovery ladder | ⬜ todo |
| **M17** Auditor | read-only regulator access → **unblocks BE-13 / S9** | ⬜ todo |
| Deferred-control sweep | ≥₹10 Cr senior sign-off, suspension, reminders, sensitive-read gating | ⬜ todo |

---

## 5. Next actions — the single ordered to-do

> One list. Do top-down. Backend read surface is done, so the **critical path is UI wiring**.

**Track A — UI live wiring (critical path)** · execute via mock `INTEGRATION_PLAN.md`; the **current scoped slice** is the mock's `docs/UI_WORKORDER.md` (built by the mock-side session)
1. **Bridge foundation** — build `src/api/` client (bearer + `X-Command-Id`/`X-Aggregate-Version` envelope), flip `DATA_MODE` live/mock switch, wire **S1 login + OTP + `/auth/session`**. Keep offline mock path working.
2. **Read-only admin screens** (fast wins, no writes): **S2 → S3 → S4 → S5 → S6 → S7 → S8 → S12 → S14**.
3. **Command flows** (writes with the envelope): supplier/buyer/listing/investor onboarding cmds on S3/S4/S5/S10.
   - ✅ **Money-flow writes are now E2E-testable** (DL-BE-086): the dev helper `POST /dev/seed-listing {stage}` fast-forwards a fresh listing to `live | fully_funded | disbursable | disbursed | matured`, so S12 subscribe, S6 approve, S7 record-maturity/distribution can be driven live without hand-running the ~20-command pipeline. Second ops (`ops2@dev.local`) also seeded so the real DOC.3 two-ops path is drivable. Mock-side flips S5/S6/S7/S12 write cells to ✅ once exercised.
4. **Investor read-only portal** — **S11 + S13 now backend-ready (M10-D/BE-14/BE-17)**; wire against a real investor bearer (dev password today, Phase B for real login). Details in the mock's `BACKEND_UI_READINESS.md`.
5. **Deferred screens** — **S9** (after M17) is the only screen left. **S15 is live** (BE-15/DL-BE-090 wired + E2E-verified, `scripts/e2e/buyer-portal.mjs`).

**Track B — backend remainder (parallel, non-blocking)**
6. M17 Auditor (also unblocks S9) → then M15, M14, deferred-control sweep.
7. ✅ **BE-18 · Phase B investor login + self-commit — SHIPPED (DL-BE-088, 2026-07-18).** Passwordless email+OTP investor login (`POST /auth/login/investor/request-otp`, enumeration-safe) **+** investor self-commit (own `investor_id` from session; the `CommandGateway` non-admin-actor path — controller-routed, gateway-open) **+** audited denied cross-tenant reads (`investor.CrossTenantReadDenied`). Mostly additive, no new tables. 448 tests green. **Spec:** [`modules/M11-B-investor-login-selfcommit.md`](modules/M11-B-investor-login-selfcommit.md) · `DL-BE-088`. Deferred follow-up: `request-otp` rate-limiter (platform-wide auth-hardening).

**Track C — go-live prep (later)**
8. BE-16 CORS decision, real integration credentials (verification/escrow/e-sign/KYC swap from stubs).

### Deferred fixes — known gaps, fix eventually (don't lose these)
| # | Item | Where it lives | Trigger to fix |
|---|---|---|---|
| **DF-1** | ✅ **DONE (BE-18 / DL-BE-088, 2026-07-18)** — real investors log in passwordless (email+OTP) and self-commit under their own session; `request-otp` rate-limiting deferred to auth-hardening. | = **BE-18** (Track B #7) · `DL-BE-088` · [`modules/M11-B-investor-login-selfcommit.md`](modules/M11-B-investor-login-selfcommit.md) | **Shipped.** (Rate-limiter before a public-scale pilot.) |
| **DF-2** | **KYC download gate over-permissive for suspended/exited investors** — `InvestorService.isKycApprovedForDownload`'s `… OR kyc_approved_at IS NOT NULL` lets a once-approved but later `suspended`/`exited` investor still download. Latent (Suspend/Exit not built) but a real over-permission. | `InvestorService.isKycApprovedForDownload`, `InvoiceDocumentService.download` · `DL-BE-084` (§findings) | **With the Suspend/Exit module** (M10 §9 post-active lifecycle) — decide there whether a de-activated investor retains document access, then tighten the clause. |
| **DF-3** | ✅ **DONE (DL-BE-087)** — `DevDataSeeder` now **ensures admins per-email every boot** (`ensureAdmin` via `email::citext`), so late-added seed accounts (e.g. `ops2@dev.local`) materialize on a pre-existing dev DB; counterparties guarded on `sup_account` emptiness → seed once. Idempotent (adopts a manual `ops2`, no dups); test `DevSeederAdminEnsureTest` covers the non-empty-table case the old guard missed. | `DevDataSeeder.run` / `ensureAdmin` (backend) | **Shipped** — brief: [`DF3_SEEDER_UPSERT_BRIEF.md`](DF3_SEEDER_UPSERT_BRIEF.md); `DL-BE-087`. Mock side can drop the manual `ops2` insert (plain dev boot yields all seven admins). |
| **DF-4** | ~~S5 has no invoice-document upload UI~~ | mock `S5.jsx` + `src/api/services/documents.js` | ✅ **Fixed (mock).** S5 invoice detail now has an **Upload Invoice PDF** control → `documents.initiate/uploadContent/finalize` → `listings.attachInvoiceDoc`; `document_completeness` then recorded by a second Ops (DOC.3). Build green; endpoints covered by `scripts/e2e/s5golive.mjs`. |
| **DF-5** | ✅ **DONE (DL-BE-089)** — `POST /auth/logout` revokes the caller's session server-side; the bearer then 401s (`bearer_expired`) on any later request. Idempotent; admin + investor. UI already wired best-effort, so it activated on ship (no FE change). | `SessionController` (backend) · mock `auth.logoutSession` + `AuthContext.logout` | **Shipped** — brief: [`LOGOUT_ENDPOINT_BRIEF.md`](LOGOUT_ENDPOINT_BRIEF.md); `DL-BE-089`. Verified E2E `../fintech-patform-mock/scripts/e2e/logout.mjs` (9/9). |

---

## 6. Update protocol (keep it alive, solo-friendly)

- **When you finish wiring a screen:** flip its **Wired live** cell in §2 to ✅ (or ⚠️), update §5 if the next action changed, bump the "Last updated" date. One-line commit.
- **When you ship a backend item:** update §3/§4 + add a `DL-BE-*` entry; reflect the ✅ here.
- **One status, one home.** If you catch yourself writing a status into another doc, delete it and link here.
- **No new top-level plan.** New detail goes into `INTEGRATION_PLAN.md` (UI) or `Spec_Driven_Build_Plan.md` (backend modules) as a step — never a new competing plan.
- The mock repo carries a 1-line pointer (`../fintech-patform-mock/docs/PROJECT_TRACKER.md`) back to this file so both repos resolve to the same source of truth.
