# M11-C · Buyer portal — ack-user passwordless login + buyer reads + self-ack (BE-15 / WS-2·M2)

> **Module spec — `/specify` + `/clarify` (DoR) output** for `docs/BE15_BUYER_PORTAL_BRIEF.md`.
> The **buyer-side sibling** of M11-B (BE-18): the same passwordless-login + non-admin-self-actor + own-scoping
> substrate, instantiated for the `acknowledgment_user` kind. Turns S15 from a self-contained mock into a real
> buyer portal — the buyer logs in with an OTP, sees their invoices awaiting acknowledgment + payment
> instruction, and **acknowledges as themselves**, closing the DL-019 control's provenance gap (today only Ops
> can record a buyer-ack). Predecessor substrate: **DL-BE-088 (BE-18)**; this slice claims **DL-BE-090**.
> **`/specify` + `/clarify` (DoR) + `/plan` complete** (this doc, §9); **`/tasks` / `/implement` not yet run**.

| | |
|---|---|
| **Module** | M11-C — ack-user login (BC7 auth) + buyer reads (BC9) + buyer self-ack (BC1 listing command) |
| **Slice** | The WS-2 "Milestone 2" deferral (`DL-BE-032`): ack-user login + buyer read surface + self-actor ack. |
| **Tracker IDs** | **BE-15** (S15 buyer portal) |
| **Tier** | Medium — reuses BE-18's substrate, but **modifies one live S5 command** (`recordBuyerAck`); see §0. |
| **Status** | **DoR + `/plan` done — implementation pending** |
| **Owner** | Amit + Claude |
| **Created** | 2026-07-18 |

---

## DoR decisions (settled at `/clarify`, 2026-07-18)

Items marked **(confirm/revise)** are reversible calls settled with a sensible default — override before `/plan`.

1. **DoR-1 — Enumeration-safety (Part 1), reuse BE-18 verbatim.** `POST /auth/login/ack-user/request-otp {email}`
   always returns `200 {challenge_id}`, shape-identical. Eligible (DoR-2) → real OTP via `AuthService.issueLoginOtp`;
   ineligible/unknown → synthetic opaque `challenge_id`, no `auth_otp_challenge` row, no send; `verify-otp` then
   fails through the same generic `LoginFailedException`. Unconditional lookup (timing best-effort). _Same mechanism
   proven at BE-18; `verifyOtp` already returns `failed("not_found")` for a missing challenge._

2. **DoR-2 — Eligibility gate = active ack-user of an active buyer, current state.** OTP issued / self-ack allowed
   only when `buyer_ack_user.is_active = TRUE` **AND** the identity is `kind='acknowledgment_user'`,
   `auth_identity.status='active'`, **AND** the owning `buyer_account.status='active'`. Fail-closed on **current**
   state (a deactivated ack-user or suspended buyer cannot log in or ack). _(mirrors BE-18 DoR-2/3.)_

3. **DoR-3 — OTP channel: reuse `issueLoginOtp` as-is (stub-cosmetic channel).** DL-021 frames ack-user login as
   email+OTP; `issueLoginOtp` currently labels delivery `sms` and reads `phone_e164` (NOT NULL for this kind).
   Because the notifier is still a `StubNotifier` (the code is retrieved by `identity_id`, channel-agnostic), Part 1
   **reuses `issueLoginOtp` unchanged** — no second OTP path. The real email-vs-SMS channel is a Production-gate
   notification-adapter concern, deferred. **(confirm/revise)**

4. **DoR-4 — Self-ack de-couples `captured_by` from the admin identity (the one non-additive edit).**
   `ListingService.recordBuyerAck` becomes **actor-kind-aware** (it currently hard-assumes an admin actor):
   - **Role gate:** `Set<String> required = "acknowledgment_user".equals(request.actorType()) ? Set.of() : OPS`
     (the BE-18 no-role overload for the self-actor; OPS unchanged for ops-on-behalf).
   - **`captured_by`:** today `roles.adminUserId(request.actorId())` (`ListingService.java:388`) — which **throws**
     for a non-admin identity. Branch: ops → `adminUserId` (unchanged); ack-user → the `ack_user_id` resolved from
     `buyer_ack_user WHERE identity_id = actorId`. Add **`captured_by_kind`** (`'ops'` | `'buyer_ack_user'`) to the
     `buyer_ack` JSONB entry so the audit trail records **real buyer provenance vs ops-on-behalf** — this *is* the
     brief's business case (closing the provenance gap).
   - **Own-scoping (SELF-1):** for an ack-user caller, the listing's `buyer_id` must equal the session's `buyer_id`
     (resolved server-side via `AckUserQueryPort`); a mismatch → **403 `cross_tenant_read`** (mirrors BE-18).
   - **Outcome restricted:** an ack-user self-ack may only record `outcome='acknowledged'`; `'failed'` (a
     timeout/decline) stays an **ops** action. `method` defaults to `'buyer_self_portal'` for a self-ack.
   - **Controller:** `record-buyer-ack` can't use the shared `command(...)` helper (hard-codes
     `actor_type='admin_user'`, used by ~8 listing commands) for the ack-user path — it builds its `CommandRequest`
     separately with `actor_type='acknowledgment_user'` (exactly how `SubscriptionController.commit` diverged at BE-18).

5. **DoR-5 — Payment-instruction read = metadata only; bank-detail surfacing is blocked upstream, not deferred by
   choice (resolved after checking the writer).** `GET /buyer/payment-instruction` returns
   `{effective_from, confirmed_at, present:true}` for the session's buyer (current row = `superseded_by IS NULL`).
   This is the **maximum the data model holds**: `BuyerService.confirmPaymentInstruction` (`:149-152`) stores
   `instruction_doc_hash = sha256("payment:" + buyerId)` — a **synthetic placeholder**, with **no real document and
   no bank / account-no / IFSC fields captured anywhere**. So there is literally nothing to download or parse.
   Surfacing real remittance details requires **enhancing `confirm-PI` upstream** to capture an actual instruction
   (a doc upload or structured columns + migration) — a **separate follow-up, out of BE-15 scope**, flagged. The
   portal shows "payment instruction confirmed (effective X)"; the buyer uses the details they were given at
   onboarding. _No migration in this slice; this is honest given the current stub._

**Additional decisions surfaced by `/specify`:**

6. **DoR-6 — Ops-on-behalf retained (no S5 regression).** `request-buyer-ack` + ops `record-buyer-ack` stay exactly
   as-is; a regression test locks it (DoD #4). This is the load-bearing guard on the DoR-4 edit.

7. **DoR-7 — `AckUserQueryPort` resolver.** New port `buyerIdForIdentity(UUID) → Optional<UUID>` =
   `SELECT buyer_id FROM buyer_ack_user WHERE identity_id = ? AND is_active = TRUE` (mirrors `InvestorQueryPort`;
   an inverse of the existing `activeAckUserIdentity(buyerId)`). Reused by `/auth/session`, the reads, and self-ack.

8. **DoR-8 — Denied cross-tenant reads audited.** The buyer reads + self-ack mismatch emit
   `buyer.CrossTenantReadDenied` via a direct non-command `AuditLog.append` (denials only; successful reads
   unaudited). Same shape as BE-18 (`investor.CrossTenantReadDenied`).

9. **DoR-9 — `/auth/session` gains a nullable `buyer_id`.** Non-null only for `kind='acknowledgment_user'`,
   server-resolved via `AckUserQueryPort` — the mirror of M10-D's `investor_id`. Additive.

10. **DoR-10 — Self-ack (and the actionable read) require an outstanding ops request (E2, new invariant).** Today
    `recordBuyerAck` only checks `status='awaiting_acknowledgment'`, not that ops actually **requested** the ack —
    so a buyer could ack a listing no one asked them to. BE-15 gates the ack-user path on
    `buyer_ack.status = 'requested'` (an ops `request-buyer-ack` is outstanding); a listing without a `requested`
    entry → rejected for a self-ack. `GET /buyer/invoices` surfaces `ack_status` so the portal shows what was
    requested. **Ops-on-behalf is unchanged** (ops may still ack a listing directly — the gate is on the *ack-user*
    branch only, so no S5 regression). _Tightens provenance; matches the mock's "invoices awaiting acknowledgment."_

11. **DoR-11 — No double-ack: reject when already acknowledged (E3, new invariant).** The `acknowledged` path is a
    status-check-then-JSONB-merge with **no version guard and no re-ack guard** — so a buyer ack + a concurrent ops
    ack (or two ack-users) is last-write-wins on `captured_by`/provenance, and a later ack silently overwrites an
    earlier one. BE-15 adds a guard: if `buyer_ack.status` is already `'acknowledged'` (or `'failed'`), the command
    is rejected (clean 400) — first-writer-wins, deterministic provenance. Mirrors the guard `requestBuyerAck`
    already has against re-requests (`ListingService.java:347-349`). Applies to **both** actor paths (a safe
    tightening of existing behaviour). _Note: the `acknowledged` path stays a non-transition — this guard is the
    concurrency protection it lacked; a full `FOR UPDATE`/version-guard is not needed for a single JSONB merge._

---

## 0. Why this is mostly BE-18 again — and the one part that isn't (verified 2026-07-18)

Reuses the BE-18 substrate wholesale — kind-agnostic OTP login, the `CommandGateway` non-admin-actor path (skips
MFA/role), controller-routed self-actor authz, own-scoping + `cross_tenant_read`, denied-read audit,
`sys_audit_event`/`sys_command_log` accepting a non-admin `actor_type` (`acknowledgment_user`, key-presence CHECK).
Login is **mandatorily** OTP-only here (`AU.1`: an `acknowledgment_user` identity has *zero* `auth_credential`
rows), so the passwordless pattern is the only path. **No new tables** for login / reads / self-ack.

**The one non-additive edit (DoR-4):** unlike BE-18's `subscribe` — which never resolved an admin id — the ack
command `ListingService.recordBuyerAck` **is coupled to an admin actor**: `gateway.execute(request, OPS, …)`
(`:375`) and `roles.adminUserId(request.actorId())` for `captured_by` (`:388`, throws for a non-admin). Self-ack
therefore **modifies a live S5 deal-flow control command** to branch on actor kind. That is the risk surface — it
must not regress ops-on-behalf (DoR-6), so it carries a mandatory regression test.

**De-risked at spec time (verified against code):** `recordBuyerAck`'s *only* admin coupling is that one line —
`new Actor(...)` appears **nowhere** in `ListingService` (the audit actor is built by the `CommandGateway` envelope
from `request.actorType()`, so provenance is correct automatically), and there is **no JSONB shape CHECK** on
`check_outcomes`, so the additive `captured_by_kind`/`captured_by_kind` key is safe. So the DoR-4 edit is a one-line
`captured_by` branch + the role-set — no hidden second coupling like BE-18's `SubscriptionService.actor()` had.

## 1. Scope

**Owns:**
- **Part 1 — ack-user login:** `POST /auth/login/ack-user/request-otp` + eligibility lookup (DoR-2); OTP via
  `issueLoginOtp`; session via existing `verify-otp`; `/auth/session` `buyer_id` (DoR-9); `AckUserQueryPort` (DoR-7).
- **Part 2 — buyer reads:** `GET /buyer/invoices` (own buyer's listings awaiting/recently acknowledged) +
  `GET /buyer/payment-instruction` (metadata, DoR-5). Own-scoped + denied-read audit (DoR-8).
- **Part 3 — buyer self-ack:** the `acknowledgment_user` actor path through `recordBuyerAck` (DoR-4).

**Does NOT own (deferred / other module):**
- Structured payment-instruction bank fields / instruction-doc download (DoR-5 follow-up).
- NOA (assignment-notice) download — post-funding BC5, wire later behind the invoice-document gate.
- Buyer-initiated onboarding, disputes, bulk-ack, statements (mock S15 flags these out of Phase 1).
- `outcome='failed'` self-ack (stays ops); ack-user deactivation/`AU.2` (no deactivation in scope).
- A `request-otp` rate limiter (platform-wide auth-hardening, as BE-18 DoR-5).

## 2. Upstream dependencies (all Done)
- **BE-18 / M11-B** — the reused substrate (OTP login, non-admin gateway path, own-scoping, denied-read audit).
- **M3a/M3b auth** — `issueLoginOtp`, `verifyOtp`, `establishSession`, `/auth/session`.
- **WS-2 buyer onboarding (BC9)** — `buyer_ack_user` (identity + buyer + `is_active`), `buyer_payment_rule`,
  `designate-ack-user`. Done.
- **WS-4 listing (BC1)** — `ListingController`/`ListingService.recordBuyerAck` + the `deal_invoice.check_outcomes
  ->'buyer_ack'` JSONB model + `deal_listing.status='awaiting_acknowledgment'`. Extended (DoR-4), not rebuilt.

## 3. Invariants & rules
Persistence idiom: raw `JdbcTemplate` native SQL.

- **LOGIN-B1 — OTP-only entry for active ack-users of active buyers** (DoR-1/2/3). _(ref: DL-021, AU.1; brief §1)_
- **OWN-B1 — Own-buyer scoping.** An `acknowledgment_user` caller may read/act on **only** its own `buyer_id`
  (server-resolved via `AckUserQueryPort`, never client-supplied); a cross-buyer read/ack → **403
  `cross_tenant_read`**. Admin bearers keep the un-scoped view. _(ref: brief §2/§3; mirrors BE-18 OWN-1/SELF-1)_
- **ACK-B1 — Self-ack provenance.** A buyer self-ack stamps `buyer_ack.captured_by = ack_user_id`,
  `captured_by_kind = 'buyer_ack_user'`; an ops-on-behalf ack stamps the admin id + `'ops'` (DoR-4). Both keep the
  listing in `awaiting_acknowledgment` (the `acknowledged` non-transition). _(ref: DL-019; brief §3)_
- **ACK-B2 — Self-ack is `acknowledged`-only.** An ack-user actor cannot record `failed` (ops-only). _(DoR-4)_
- **ACK-B3 — Self-ack needs an outstanding request.** An ack-user self-ack requires `buyer_ack.status='requested'`
  (ops has run `request-buyer-ack`); else rejected. The ops path is not gated (no S5 regression). _(DoR-10)_
- **ACK-B4 — No double-ack.** A `record-buyer-ack` whose `buyer_ack.status` is already `acknowledged`/`failed` is
  rejected (first-writer-wins). Applies to both actor paths. _(DoR-11)_
- **NOREG-B1 — No S5 regression.** Ops `request-buyer-ack` / `record-buyer-ack` behaviour is unchanged except the
  new ACK-B4 double-ack guard (a safe tightening — re-acking was never a valid flow). _(DoR-6)_
- **AUDIT-READ-B1 — Denied cross-tenant reads audited; successful reads not** (DoR-8).

## 4. API / type surface (new/changed)

| Endpoint | Caller | Change |
|---|---|---|
| `POST /auth/login/ack-user/request-otp` | open | **NEW.** `{email}` → `200 {challenge_id}`, enumeration-safe. |
| `POST /auth/login/verify-otp` | open | **Unchanged** — establishes the `kind='acknowledgment_user'` session. |
| `GET /auth/session` | any bearer | **+`buyer_id`** (nullable; non-null for ack-user). Additive (DoR-9). |
| `GET /buyer/invoices` | ack-user / admin | **NEW.** Own buyer's listings awaiting/recently acknowledged (see shape). Own-scoped; mismatch → 403. |
| `GET /buyer/payment-instruction` | ack-user / admin | **NEW.** `{effective_from, confirmed_at, present}` for the session's buyer (metadata; DoR-5). |
| `POST /listings/{id}/record-buyer-ack` | **ack-user** bearer | **Ack-user path added** — own buyer, `actor_type='acknowledgment_user'`, `outcome='acknowledged'` only, no MFA, idempotent on `(ack-user identity, command_id)`. Body `{outcome, method?, evidence_ref?}`. |
| `POST /listings/{id}/record-buyer-ack` | admin (OPS) | **Unchanged** — ops-on-behalf. |

**`GET /buyer/invoices` row shape** (native SQL `deal_listing ⋈ deal_invoice ⋈ sup_account`, `WHERE buyer_id = ?`,
listings in/past `awaiting_acknowledgment`): `{listing_id, invoice_number, supplier_name, face_value_paise,
invoice_date, due_date, ack_status (buyer_ack.status), sla_hours, requested_at, acknowledged_at (recorded_at when
acknowledged), aggregate_version}`. `aggregate_version` is included so the portal can thread it into the self-ack
command. `noa_available` omitted (NOA out of scope).

## 5. Five non-negotiables — applicability (the self-ack command)

| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | **no** | `record-buyer-ack` is a single-actor command (ops path isn't maker-checker either). |
| 2 | MFA-fresh | **no** (non-admin) | Gateway skips the freshness gate for a non-admin actor; login is OTP-gated. |
| 3 | SoD-checked | **no** | Ack-user acts on its own buyer; no admin-role segregation. |
| 4 | Idempotent on `command_id` | **yes** | `(actor_id = ack-user identity, command_id)`; unchanged mechanism. |
| 5 | Audit-logged envelope | **yes** | Gateway envelope, `actor_type='acknowledgment_user'`; plus the Part-2 denied-read event. |

## 6. Events
- **Publishes:** `listing.Listing.AcknowledgmentReceived` (existing, now also from an ack-user actor; carries the
  `captured_by_kind` provenance) + **new** `buyer.CrossTenantReadDenied` (telemetry).
- **Subscribes:** none.

## 7. Test scenarios (write first — `extends AbstractEdgeHttpTest`)
Add a `seedActiveAckUserWithLogin(...)` harness helper (an `acknowledgment_user` identity + `buyer_ack_user` on an
active buyer; OTP-only, no password) + a passwordless ack-user bearer helper.

- [ ] **Login (LOGIN-B1):** active ack-user of an active buyer → request-otp → OTP → verify → bearer;
      `/auth/session` → `kind:"acknowledgment_user"`, `buyer_id`, `roles:[]`.
- [ ] **Enumeration-safe:** unknown email / inactive ack-user / suspended buyer → `{challenge_id}` shape, no OTP sent.
- [ ] **Own invoices read (OWN-B1):** ack-user reads its buyer's awaiting-ack invoices (shape §4); a different
      buyer id → **403 `cross_tenant_read`** + one `buyer.CrossTenantReadDenied`; own read → no audit.
- [ ] **Payment instruction:** ack-user reads `{effective_from, confirmed_at, present}` for its buyer.
- [ ] **Self-ack happy (ACK-B1):** ack-user `record-buyer-ack {outcome:'acknowledged'}` on its buyer's listing →
      `buyer_ack.status='acknowledged'`, `captured_by=ack_user_id`, `captured_by_kind='buyer_ack_user'`; envelope
      `actor_type='acknowledgment_user'`. The listing then snapshots (G-B3, buyer_ack satisfied).
- [ ] **Cross-buyer self-ack (OWN-B1):** ack-user acks a **different** buyer's listing → 403, no write.
- [ ] **Self-ack `failed` rejected (ACK-B2):** ack-user `outcome:'failed'` → rejected.
- [ ] **Self-ack without a request rejected (ACK-B3):** ack-user acks an `awaiting_acknowledgment` listing that has
      **no** `buyer_ack.status='requested'` entry → rejected; ops-on-behalf on the same listing still works.
- [ ] **No double-ack (ACK-B4):** a second `record-buyer-ack` after `acknowledged` → rejected (both paths).
- [ ] **Ops no-regression (NOREG-B1 / DoD #4):** ops `request-buyer-ack` + `record-buyer-ack` (both outcomes) →
      unchanged; `captured_by_kind='ops'`.
- [ ] **Idempotency:** replay `(ack-user identity, command_id)` → replayed, one envelope.

## 8. Definition of Done (gate F)
- [ ] §7 tests green; whole suite green (record count).
- [ ] DoD from brief §4 satisfied; **no S5 regression** (the DoR-4 guard).
- [ ] No new migration (confirm against V1–V13); if one is unavoidable, **stop and flag** (esp. the DoR-5 fork).
- [ ] `/code-review` on the diff; findings fixed.
- [ ] **DL-BE-090** finalised; `PROJECT_TRACKER.md` §2 (S15) + Track B updated; `API_CATALOGUE.md` rows added.
- [ ] Mock-side work-order (brief §5) — do not edit mock code from here.
- [ ] This spec flipped to **Status: Done**.

---

## 9. Plan (`/plan` — code-anchored, verified 2026-07-18)

**`/plan`-time confirmations:**
- **No migration.** All three parts run on existing tables that already accept a non-admin actor. Confirmed V1–V13.
- **DoR-1 needs no verify-path change** — `verifyOtp` returns `failed("not_found")` for a missing challenge
  (`AuthService.java:176-178`) → same generic failure as a wrong code; a synthetic `challenge_id` is indistinguishable.
- **`recordBuyerAck` has exactly one admin coupling** (`:388`); no `new Actor(...)` in `ListingService`; no
  `check_outcomes` CHECK. So the DoR-4 edit is a one-line `captured_by` branch + the role-set (see §0).
- **Endpoint shape decision:** use **`/buyers/{id}/ack-invoices`** + **`/buyers/{id}/payment-instruction`** (explicit
  buyer id in the path), **not** `/buyer/*`. The brief offered both; the `{id}` form is what gives a **cross-tenant
  surface** (an ack-user requesting another buyer's id → 403 + audit, DoR-8/DoD #2) and admin-reads-any — a direct
  mirror of BE-18's `/investors/{id}/subscriptions`. A no-id session-scoped `/buyer/*` would have no cross-tenant
  check to make.

Build order — each step: red test → green → `/code-review` → DL note.

**Part 1 — ack-user login**
- **P1 · `AuthService.requestAckUserOtp(String email)`** (mirror `requestInvestorOtp`, BE-18). Eligibility (DoR-2):
  `SELECT i.identity_id FROM auth_identity i JOIN buyer_ack_user u ON u.identity_id = i.identity_id
   JOIN buyer_account b ON b.buyer_id = u.buyer_id WHERE i.email = ? AND i.kind::text='acknowledgment_user'
   AND i.status::text='active' AND u.is_active = TRUE AND b.status::text='active'`. Eligible → `issueLoginOtp`
  (DoR-3, reused as-is); else synthetic `Ids.newId()`.
- **P2 · `AuthController POST /auth/login/ack-user/request-otp`** (open; `/auth/login/**` already permitAll).
  `verify-otp` reused. `/auth/session` `+buyer_id` (P3).
- **P3 · `AckUserQueryPort.buyerIdForIdentity(UUID) → Optional<UUID>`** (`… WHERE identity_id=? AND is_active=TRUE`)
  + a natural home (e.g. `BuyerService` implements it, mirroring `InvestorService`/`InvestorQueryPort`). Wire into
  `SessionController` for the nullable `buyer_id` (DoR-9).

**Part 2 — buyer reads (`BuyerPortalController`, new; injects jdbc + `AckUserQueryPort` + `AuditLog`)**
- **P4 · `GET /buyers/{id}/ack-invoices`** — own-scope (mirror `InvestorController.subscriptions`): resolve
  `callerBuyerId = buyerIdForIdentity(session.identityId())`; if present and `≠ id` → audit
  `buyer.CrossTenantReadDenied` + `403 crossInvestorRead`-style; else if not admin → 403. Native SQL
  `deal_listing l ⋈ deal_invoice i ⋈ sup_account` `WHERE l.buyer_id = ?`, listings at/past
  `awaiting_acknowledgment`, extracting `i.check_outcomes->'buyer_ack'` (`status`, `sla_hours`, `requested_at`,
  `recorded_at`). Shape per §4; include `l.aggregate_version`.
- **P5 · `GET /buyers/{id}/payment-instruction`** — same own-scope; `SELECT effective_from, confirmed_at FROM
  buyer_payment_rule WHERE buyer_id = ? AND superseded_by IS NULL` → `{effective_from, confirmed_at, present}`
  (DoR-5 — metadata only; no bank fields exist).

**Part 3 — buyer self-ack**
- **P6 · `ListingController.recordBuyerAck` branch** — inject `AckUserQueryPort`. Resolve
  `callerBuyerId = buyerIdForIdentity(session.identityId())`: if present (ack-user) → read the listing's `buyer_id`
  (jdbc); `≠ callerBuyerId` → `403 cross_tenant_read` (a denied *write*, not audited — mirrors BE-18's self-commit
  reject); build the `CommandRequest` **separately** with `actor_type='acknowledgment_user'` (the shared `command()`
  helper hard-codes `admin_user`). Else → existing ops path (unchanged).
- **P7 · `ListingService.recordBuyerAck` actor-kind logic:**
  - Role set: `"acknowledgment_user".equals(request.actorType()) ? Set.of() : OPS`.
  - `captured_by`: ack-user → `request.actorId().toString()` (**the identity id** — uniquely maps to the ack-user via
    `buyer_ack_user_identity_uq`; **no resolver, no throw**); ops → `roles.adminUserId(request.actorId())`. Add
    `captured_by_kind` (`'buyer_ack_user'`|`'ops'`).
  - **ACK-B4** (both paths): reject if `buyerAckStatus(invoice)` ∈ {`acknowledged`,`failed`} (reuse the existing
    `buyerAckStatus` helper, `:627`).
  - **ACK-B2** (ack-user only): `outcome` must be `acknowledged`, else reject; `method` defaults to `buyer_self_portal`.
  - **ACK-B3** (ack-user only): require `buyerAckStatus(invoice) == 'requested'`, else reject.

**Tests** (detailed in `/tasks`): `investor/AckUserPasswordlessLoginTest`-style + `listing/BuyerSelfAckTest` +
`buyer/BuyerPortalReadTest`, covering §7. New harness helper `seedActiveAckUserWithLogin(...)` + a passwordless
ack-user bearer helper + fixtures for an `awaiting_acknowledgment` listing with a `requested` `buyer_ack`.

### Residual `/plan` notes & flags
- **The `acknowledged` path stays a non-transition** — ACK-B4 (reject-if-already-acked) is the concurrency guard;
  no `FOR UPDATE`/version-guard needed for one JSONB merge (E3 resolved).
- **DoR-5 is upstream-blocked, not deferred-by-choice** — real bank-detail surfacing needs a `confirm-PI`
  enhancement (doc upload or structured columns), a separate follow-up.
- **`request-otp` rate-limit** deferred (platform-wide auth-hardening, as BE-18).
- Verify at `/implement`: the `AckUserQueryPort` cross-BC dependency (listing controller → BC9 port) passes
  `BoundedContextRulesTest` (BE-18's `SubscriptionController → InvestorQueryPort` did); and no existing ops-ack test
  asserts the full `buyer_ack` JSONB object (they assert `.status`/`.captured_by`).

## 10. Next gate — `/tasks` (not yet run)
`/plan` complete (§9). **`/tasks`** breaks P1–P7 into red-test-first units in dependency order (P1→P2→P3 login;
P4/P5 reads; P6→P7 self-ack), with the new harness helpers. Do **not** start `/implement` before `/tasks` + sign-off.
