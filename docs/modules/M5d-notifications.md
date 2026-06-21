# M5d · Notifications-full (BC15) — the dispatch lifecycle behind the real port

> **Lean module spec** — *foundation* (completes M3a's thin notification slice). Low rigor (stub).
> See `docs/spec/Spec_Driven_Build_Plan.md` §C/§D. Spec before code; invariant test before rule.

| | |
|---|---|
| **Module** | M5 — Integration ACLs, stubbed (BC15/17/18/19) |
| **Slice** | M5d — Notifications-full (BC15): the `sys_notification_dispatch` lifecycle + swappable channel |
| **Tier** | Foundation (low rigor — stub) |
| **Status** | Draft |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-22 |

> Last M5 slice — **completes Wave 0**. M3a shipped a thin `NotificationPort`/`StubNotifier` (OTP
> delivery, no persistence). M5d builds the full **`sys_notification_dispatch` lifecycle** and recasts
> the pieces: `NotificationService` (extends [[DL-BE-026]]'s `AbstractAclService`) becomes the
> `NotificationPort` impl; `StubNotifier` becomes the swappable **`NotificationChannel`**. M3a's
> callers (AuthService OTP) are **unchanged** — they get the lifecycle for free. BC15 is a
> vendor-neutral dispatch abstraction (`send_email`/`send_sms`), fire-and-forget (ND.1, DL-049).

## 1. Scope
**Owns:**
- **`NotificationService implements NotificationPort`** (the fixed half): record a
  `sys_notification_dispatch` row (`queued`), deliver via the channel, record `sent`/`failed` +
  `provider_ref`, audit. M3a's `send(NotificationRequest)` boundary is unchanged.
- **`NotificationChannel`** (the swappable seam): `send(request) → providerRef`. `StubNotifier`
  (recast — keeps its `lastCodeFor`/`sent` test hooks) is the Phase-1 channel; a real SES/SNS/Twilio
  adapter swaps in later.
- **Fire-and-forget (ND.1, B1 §4.4)** — a delivery failure records `failed` + `DispatchFailed`, never
  rolls back the caller's business state. (M3a already sends OTPs on `afterCommit`; M5d keeps that.)
- **No PII/OTP in the persisted payload (ND.2, C14/C15)** — the channel receives the full params (to
  deliver), but `sys_notification_dispatch.payload` stores only **non-sensitive template vars** (OTP
  codes, phone/email, PAN/Aadhaar filtered out); the recipient is a `recipient_identity_id`, never a raw address.
- **Audit** — `notifications.Notification.Dispatched` / `.DispatchFailed` via the inherited `auditAclEvent`.

**Does NOT own (deferred / other slice):**
- **The real email/SMS provider** (SES/SNS/Twilio; sandbox → production) → **Production gate**.
- **Template rendering** — `template_id` is stored; the actual template/i18n engine → later.
- **Retries, the dispatch scheduler, and `delivered` status** (provider delivery-receipt webhooks) → real adapter.
- **Multi-channel** (WhatsApp / push / in-app) → not Phase 1 (email + SMS only).
- **The triggering event-bus subscriptions** (BC1/2/4/7/8/9/10/11 → notifications; `*Outage.Declared` →
  admin banner) → wire up at the **Walking Skeleton** (no bus yet). `causation_event_id` is null until then.
- **Dispatch dedup** — the schema has no `(recipient, type, reference)` key; fire-and-forget allows
  duplicates (acceptable for notifications, unlike money) → a future concern if needed.

## 2. Upstream dependencies
- **M5 ACL base** (`AbstractAclService`), **M2 Audit Log**, **M3a** (`NotificationPort`,
  `NotificationRequest`, `StubNotifier`), **M1a/b** — Done.
- **M0 schema** — Done: `sys_notification_dispatch`, `notification_channel_enum`,
  `notification_status_enum` (V4). **No new migration.**

## 3. Invariants & rules
- **INV-1 — Fire-and-forget (ND.1).** A channel failure records `status='failed'` + a `DispatchFailed`
  envelope and does **not** throw / roll back the caller. _(ref: B1 §4.4; M3a `afterCommit`)_
- **INV-2 — No OTP/PII in `payload` (ND.2).** The persisted `payload` excludes sensitive keys (code,
  otp, phone, email, pan, aadhaar, …); the raw code/address is never stored. _(ref: C14, C15, DL-050)_
- **INV-3 — Dispatch lifecycle.** `queued → sent (provider_ref) | failed`, recorded in
  `sys_notification_dispatch`. _(ref: schema; `notification_status_enum`)_
- **INV-4 — Every dispatch is audited.** Dispatched or DispatchFailed. _(ref: non-negotiable #5; C1)_
- **INV-5 — The vendor model never leaks.** Callers use `NotificationPort.send(NotificationRequest)`;
  the channel is swappable. _(ref: A1/B1)_

## 4. API / type surface
- **Port (unchanged from M3a):** `NotificationPort.send(NotificationRequest{recipientIdentityId,
  channel, templateId, params})`.
- **Service:** `NotificationService extends AbstractAclService implements NotificationPort`.
- **Channel (new seam):** `NotificationChannel.send(NotificationRequest) → String providerRef`;
  `StubNotifier implements NotificationChannel`.
- **Types:** `NotificationChannelKind` (email/sms — or keep the channel as the request's String), `NotificationStatus`.

## 5. Five non-negotiables — applicability
| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | no | notifications are system/event-triggered |
| 2 | MFA-fresh | no | — |
| 3 | SoD-checked | no | — |
| 4 | Idempotent | no (by design) | fire-and-forget; the schema has no dedup key (acceptable for notifications) |
| 5 | Audit-logged envelope | **yes** | Dispatched / DispatchFailed via `auditAclEvent` |

## 6. Events (audit envelopes; no bus yet)
`notifications.Notification.Dispatched`, `notifications.Notification.DispatchFailed`. The triggering
subscriptions (every BC + `*Outage.Declared`) wire up at the Walking Skeleton.

## 7. Test scenarios (write these first) — integration, Testcontainers
- [ ] `send` → a `sys_notification_dispatch` row (`status='sent'`, `provider_ref` set), a
      `Notification.Dispatched` envelope; the channel received the full request (INV-3, INV-4).
- [ ] **No OTP/PII in payload:** a request with `params={code, phone, greeting}` persists `payload`
      containing only `greeting` (code/phone filtered); the channel still got the code (INV-2).
- [ ] Fire-and-forget: a channel that throws records `status='failed'` + `DispatchFailed` and does
      **not** propagate (INV-1).
- [ ] Regression: the M3a OTP path still works — `sessionFor`/`lastCodeFor` deliver the code through
      `NotificationService → StubNotifier` (the whole admin-IAM/auth suite is the guard).

## 8. Definition of Done (foundation, low rigor)
- [ ] §7 tests green (fire-and-forget + no-PII-in-payload are the headline); whole suite green (OTP regression).
- [ ] `/code-review` on the diff; findings fixed.
- [ ] `DL-BE-028` entry (the dispatch lifecycle on `AbstractAclService`, the Port-impl/channel recast,
      fire-and-forget, the PII-filter, the provider/templates/retries/dedup deferrals).
- [ ] Status flipped to **Done**. **Wave 0 complete.**

## 9. Self-review resolutions (DoR-green)
1. **Reuses `AbstractAclService`** (audit; no sha256 — no payload-hash column).
2. **Migration — RESOLVED: none** (`sys_notification_dispatch` + enums exist V4).
3. **Completes M3a — RESOLVED:** `NotificationService` is now the sole `NotificationPort` bean;
   `StubNotifier` is recast to `NotificationChannel` (still a `@Component`, still `lastCodeFor` for
   tests). M3a's `AuthService` (injects `NotificationPort`) is unchanged — it now gets the lifecycle.
4. **Fire-and-forget — RESOLVED:** OTPs already send on `afterCommit` (M3a); `NotificationService.send`
   records `failed` on a channel error and does not rethrow, so the caller (already committed) is unaffected.
5. **No PII/OTP in payload — RESOLVED:** a sensitive-key denylist filters `params` before persisting;
   the channel gets the full params to deliver. The audit envelope likewise carries no secret.
6. **No dedup — RESOLVED:** the schema has no `(recipient, type, ref)` key; fire-and-forget permits
   duplicates (fine for notifications). `causation_event_id` is null until the event bus wires subscriptions.

## 10. Watch-for (carry forward)
- **Real provider swap** brings templates/i18n, retries, the dispatch scheduler, `delivered` via
  provider receipts, and the outage-banner notifications.
- **Event-bus subscriptions** (BCs → notifications) + `causation_event_id` linkage at the Walking Skeleton.
- **Address resolution** — the real channel resolves email/phone from Identity at send; never persist it (ND.2).
- **Dispatch dedup** — add a `(recipient, type, reference)` key if duplicate notifications become a problem.
