# BACKEND BRIEF — BE-15 / WS-2·M2 · Buyer portal: ack-user login + buyer reads + self-ack

> **What this is.** The last screen not wired live — **S15 buyer portal**. It needs three backend pieces, all
> deferred at WS-2 (`DL-BE-032`, "Milestone 2"): (a) **passwordless ack-user login** (OTP-only, no password), (b)
> **buyer reads** (the invoices awaiting this buyer's acknowledgment + payment instructions), and (c) **buyer
> self-ack** (the ack-user acknowledges an invoice under their own session). The ack-user *identity* already
> exists (`designate-ack-user` provisions an `acknowledgment_user` `auth_identity` + `buyer_ack_user` row); what's
> missing is the login, the read surface, and the self-actor command path.
>
> **Why it's needed (business value).** Buyer acknowledgment is a **control in the deal flow** (DL-019, per-invoice
> ack before a listing goes live), but today only **Ops** can record it (S5 `record-buyer-ack`, ops-on-behalf) —
> the actual buyer has no way in. That means a human relays every acknowledgment, and the "buyer confirmed this
> invoice" audit trail is really "an Ops user clicked on the buyer's behalf." BE-15 gives the buyer a real portal:
> they log in with an OTP, see their pending invoices, and acknowledge them **as themselves** — closing the
> control's provenance gap and removing Ops from the loop. It's the last piece for an end-to-end pilot.
>
> **Why it's cheap now.** This is **BE-18 again, for a different kind.** BE-18 shipped the reusable substrate:
> kind-agnostic passwordless login (`request-otp`/`verify-otp`), the `CommandGateway` non-admin-actor path
> (skips role/MFA), controller-routed self-actor authz, own-scoping with `cross_tenant_read`, and denied-read
> audit. BE-15 instantiates the same patterns for the `acknowledgment_user` kind. Reuse, don't reinvent.
>
> **This is a brief, not a spec.** Module-sized — spec it as **M11-C** (sibling to BE-18's M11-B). Take it through
> the repo loop (`/specify → /clarify → DoR → /plan → /tasks → /implement → DoD`). Append `DL-BE-090`.
>
> **Sibling to** `BE18_INVESTOR_LOGIN_SELFCOMMIT_BRIEF.md` (DL-BE-088) — same shape; read that first, this mirrors it.

---

## 0. Scope & non-goals

**In scope (three parts):**
1. **Passwordless ack-user login** — email → OTP → session for an **active** `acknowledgment_user`. No password, no
   MFA (AU.1 / DL-021).
2. **Buyer reads (BE-15)** — own-scoped to the ack-user's buyer: the listings/invoices **awaiting acknowledgment**
   (+ ack status, SLA deadline, supplier, amount, due date) and the **payment instruction** (the confirmed
   `buyer_payment_rule`).
3. **Buyer self-ack** — the ack-user runs `record-buyer-ack` on a listing belonging to **their** buyer, under
   their own session (controller-routed non-admin actor).

**Non-goals / guardrails:**
- **No change to admin flows.** S5's ops-on-behalf `record-buyer-ack` / `request-buyer-ack` stay exactly as-is
  (no S5 regression).
- **Self-ack is the only buyer write in scope.** No buyer-initiated onboarding, disputes, or bulk actions (mock
  S15 already flags "bulk ack / disputes / statements not in Phase 1").
- **NOA download is out of scope here** (assignment notice is post-funding, BC5) — adjacent, wire later behind the
  existing invoice-document download gated to the buyer.
- **Reuse BE-18's substrate** — the OTP flow, the non-admin-actor gateway path, own-scoping + `cross_tenant_read`,
  denied-read audit. Add the ack-user *instantiation*, not a parallel stack.
- **Preserve the five non-negotiables** for self-ack (idempotency, version, audit with `actor_type='acknowledgment_user'`); **no MFA gate** (non-admin actor) — confirm at `/clarify`.

---

## 1. Part 1 — Passwordless ack-user login

Mirror BE-18's investor login for the `acknowledgment_user` kind:
- `POST /auth/login/ack-user/request-otp` `{email}` (open) → look up an **active** `buyer_ack_user` (via
  `auth_identity`, kind `acknowledgment_user`); if eligible, issue an OTP via the stub notifier and return a
  `challenge_id`. **Enumeration-safe** (identical response shape for non-eligible emails; no OTP sent).
  *(Decide email vs phone at `/clarify` — the ack-user row has both; the mock S15 uses email OTP.)*
- `POST /auth/login/verify-otp` `{challenge_id, code}` (existing, kind-agnostic) → session; `kind='acknowledgment_user'`.
- **`GET /auth/session`** should resolve a **`buyer_id`** for an ack-user caller (as it resolves `investor_id` for
  investors, M10-D A2) — the UI scopes all buyer reads/acks to that id, never a client-supplied one.
- **Gate:** only an **active** ack-user of an **active** buyer can log in (decide the exact predicate at `/clarify`).

## 2. Part 2 — Buyer reads (BE-15)

New, own-scoped to the session's `buyer_id` (resolve `identity_id → buyer_id` via `buyer_ack_user`, the
`InvestorQueryPort` pattern → an `AckUserQueryPort`):
- **`GET /buyer/invoices`** (or `/buyers/{id}/ack-invoices`) → the buyer's listings **awaiting acknowledgment**
  (and recently acknowledged): `[{listing_id, invoice_number, supplier_name, face_value_paise, invoice_date,
  due_date, ack_status, ack_sla_deadline, acknowledged_at, noa_available}]`. Own-scoped; a mismatched buyer id →
  **403 `cross_tenant_read`**; admin caller may read any (mirror BE-18's portfolio read).
- **`GET /buyer/payment-instruction`** → the confirmed `buyer_payment_rule` (escrow bank, account name/number,
  IFSC, effective-from, note) for the session's buyer. *(Or fold into the invoices payload — decide at `/plan`.)*
- **Audit denied cross-tenant reads** (BE-18 pattern) — telemetry only, denials.

## 3. Part 3 — Buyer self-ack

- Allow the **`acknowledgment_user` kind** to run `POST /listings/{id}/record-buyer-ack` for a listing belonging to
  **their** buyer. Controller-routes on session kind (ack-user → own buyer + `actorType='acknowledgment_user'` +
  no-role gateway path; admin → existing OPS ops-on-behalf). Body: `{outcome, method?, evidence_ref?}` — for a
  self-ack `method` defaults to a self-portal value.
- **Own-scoped, fail-closed:** the listing's `buyer_id` must equal the session's `buyer_id`, else reject
  (`cross_tenant_read`, as BE-18 does for a mismatched investor).
- **Preserve the five non-negotiables**; `actor_id` is now an ack-user actor (the `sys_audit_event`/`sys_command_log`
  actor columns already accept a non-admin actor — verified for investor at BE-18, `DL-BE-088`).

---

## 4. Definition of done
1. An **active** ack-user logs in with **email + OTP only** → `kind='acknowledgment_user'` session; ineligible /
   unknown email is **enumeration-safe**.
2. That ack-user reads **their own** pending invoices + payment instruction; a mismatched buyer id →
   **403 `cross_tenant_read`**.
3. They `record-buyer-ack` a listing of **their** buyer → it acknowledges (S5 reflects it, G-B3); a listing of a
   **different** buyer → rejected.
4. Ops-on-behalf `record-buyer-ack` / `request-buyer-ack` (S5) **unchanged** — no regression.
5. Idempotency/version/audit hold for the ack-user actor; admin login + commands unchanged. Full suite green (+
   tests: ack-user login, own-scoped reads, self-ack happy + cross-tenant reject, S5 no-regression). `DL-BE-090`
   appended; catalogue rows added; PROJECT_TRACKER §2 (S15) + §5 updated.

---

## 5. Handoff to the front-end (mock repo `../fintech-patform-mock`)
S15 is today a **fully self-contained mock** (its own OTP screen + `Log out`, `acknowledgeInvoice` on the store).
Once shipped, wire it like the investor portal:
- **Ack-user login** — reuse the `AuthContext` passwordless pattern (`beginInvestorLogin` → a `beginAckUserLogin`
  calling `/auth/login/ack-user/request-otp`); S15's OTP screen calls it; session `kind='acknowledgment_user'`.
- **Reads** — replace the `mockData.S15` invoices + payment instruction with `GET /buyer/invoices` +
  `GET /buyer/payment-instruction`, scoped to `/auth/session`'s own `buyer_id`.
- **Self-ack** — the Acknowledge button calls `record-buyer-ack` under the ack-user session (drop the store stub).
- **Logout** — S15's `Log out` gains a real server revoke (`POST /auth/logout`, DL-BE-089) once it holds a bearer.
- Add `scripts/e2e/buyer-portal.mjs` (mirror `investor-self-commit.mjs`): ack-user login → read own invoices →
  self-ack → cross-tenant reject.

Net: S15 flips from ⛔ mock to ✅ live, and the deal flow's buyer-ack control gets real buyer provenance.
