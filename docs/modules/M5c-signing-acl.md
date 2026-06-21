# M5c · Signing ACL (BC19) — stubbed e-Sign behind a real port

> **Lean module spec** — *foundation* (an anti-corruption port with a deterministic stub adapter; low
> rigor — no real vendor). See `docs/spec/Spec_Driven_Build_Plan.md` §C/§D.
> Spec before code; invariant test before rule.

| | |
|---|---|
| **Module** | M5 — Integration ACLs, stubbed (BC15/17/18/19) |
| **Slice** | M5c — Signing ACL (BC19): real `SigningPort`, fake deterministic in-process adapter |
| **Tier** | Foundation (low rigor — stub) |
| **Status** | Done (impl test-first + tests green; `/code-review` findings fixed; DL-BE-027) |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-22 |

> Third M5 slice (after [[M5a-verification-acl]], [[M5b-banking-escrow-acl]]). It **reuses the ACL
> shape** — fixed service (extends `AbstractAclService`) + swappable vendor client — and is closest to
> M5a (request → deterministic completion). It produces the **"signed via stub"** assignment the
> Walking Skeleton needs (`Spec_Driven_Build_Plan.md` vertical slice). Sole Phase-1 consumer is **BC5
> Assignment & Signing (M12, not built)**; M5c ships the port BC5 will call (DL-048).

## 1. Scope
**Owns:**
- **`SigningPort`** (the ops BC5 calls): `initiateSignature(vsrId, signatureRequestId, docHash,
  signerRef, signMethod) → SignatureSession{vendorSessionUrl}`; `completeSignature(vsrId)` (the
  await/webhook entry — for the stub, deterministic in-process); `fetchSignature(vsrId) →
  SignatureResult{certSerial}`.
- **`StubSigningVendorClient`** (the swappable seam): a deterministic hosted `vendor_session_url` on
  initiate; on completion an **auto-success** with a fake `cert_serial` (the "signed via stub").
- **`gate_signature_session` persistence + lifecycle** — `session_initiated → completed | failed`;
  idempotent on the `(signature_request_id, doc_hash)` UNIQUE and the `vsr_id` PK (VS.1). `cert_serial`
  is set **only** on `completed` (DB CHECK, VS.3).
- **Audit** — `signing.SignatureSession.Initiated` / `.SignatureCompleted` via the inherited
  `auditAclEvent` (M2). Stub stamps `hmac_verified_at` at completion (VS.5).

**Does NOT own (deferred / other slice):**
- **The real signing adapter** (NSDL/Protean/Leegality/Digio/SignDesk; sandbox → production, DL-048,
  G14) → **Production gate**; M5c is the fake adapter behind the fixed port.
- **HTTP webhook ingress** — `/webhooks/signing/{vendor}/{event}` routes, HMAC over `(timestamp||body)`
  (C10), the 5-min replay window (A2 §1.2), `vendor_event_id` dedup → **the real adapter** (the stub
  completes in-process).
- **e-Stamp / e-Stamping** (`issue_stamp` / `StampIssued`, master-level, G15) → later; M5c is e-Sign only.
- **Aadhaar-OTP vs DSC path differences**, **UIDAI-outage degradation** (A2 §3.6), and the
  **retry/expiry** lifecycle (`retry_count` cap 3, `expired`) → the real adapter (the stub auto-succeeds
  on one path).
- **Signed-document archival** into BC16 Documents → **BC16/later**; M5c stores `cert_serial` + the
  input `doc_hash`, not the signed PDF.
- **The other ACLs** (Verification, Banking done; Notifications-full BC15) → M5d.

## 2. Upstream dependencies
- **M5 ACL base** (`AbstractAclService`, [[DL-BE-026]]), **M2 Audit Log**, **M1a/b** — Done. No
  state-changing-command path (signing is BC5-triggered, not a maker-checker command).
- **M0 schema** — Done: `gate_signature_session`, `sign_method_enum`, `vsr_status_enum` (V4). **No new migration.**
- **BC5 Assignment & Signing** (the consumer) — **not built (M12)**; tests drive the port directly with
  synthetic signature-request / doc-hash refs.

## 3. Invariants & rules
- **INV-1 — Idempotent session (VS.1).** One session per `(signature_request_id, doc_hash)`; re-initiate
  returns the existing session, never a duplicate vendor call. _(ref: DB UNIQUE; `vsr_id` PK)_
- **INV-2 — `cert_serial` only on `completed` (VS.3).** A non-completed session has a null cert; the
  cert is set exactly once at completion. _(ref: DB `gate_signature_session_cert_serial_only_on_completed`)_
- **INV-3 — `doc_hash` is a hash, not the document.** The signed/source document lives in BC16; only its
  SHA-256 is here. _(ref: schema; C14)_
- **INV-4 — Retry bounded (VS.4).** `retry_count` is 0–3 (DB CHECK); the stub uses 0. _(ref: A2 §3.6)_
- **INV-5 — The vendor model never leaks (ACL rule).** BC5 calls domain ops; no vendor type crosses the port. _(ref: A1/B1)_
- **INV-6 — Every initiate/complete is audited.** _(ref: non-negotiable #5; C1)_

## 4. API / type surface
- **Port:** `SigningPort` — `initiateSignature(...) → SignatureSession`, `completeSignature(vsrId) →
  SignatureResult`, `fetchSignature(vsrId) → SignatureResult`.
- **Adapter:** `StubSigningVendorClient implements SigningVendorClient` (deterministic) — the only
  Phase-1 bean.
- **Service:** `SignatureAclService extends AbstractAclService implements SigningPort` (persistence,
  idempotency, audit).
- **Types:** `SignMethod` (enum `aadhaar_otp`/`dsc`), `SignatureSessionStatus`, `SignatureSession`,
  `SignatureResult`.

## 5. Five non-negotiables — applicability
| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | no | signing is BC5-triggered; the assignment maker-checker is BC5/BC12 |
| 2 | MFA-fresh | no | carried by the BC5 command, not the ACL call |
| 3 | SoD-checked | no | — |
| 4 | Idempotent | **yes (ACL key)** | `(signature_request_id, doc_hash)` + `vsr_id` — not the M4a `command_id` store |
| 5 | Audit-logged envelope | **yes** | Initiated / Completed via the inherited `auditAclEvent` |

## 6. Events (audit envelopes; no bus yet)
`signing.SignatureSession.Initiated`, `signing.SignatureCompleted`. Subscriber (BC5) wires up at M12.
_`SignatureFailed`, `StampIssued/Failed`, `WebhookSignature.Invalid` → with the real adapter / e-Stamp._

## 7. Test scenarios (write these first) — integration, Testcontainers
- [ ] `initiateSignature` → a `gate_signature_session` (session_initiated), a deterministic
      `vendor_session_url`, an Initiated envelope; `cert_serial` is null (INV-1, INV-2, INV-6).
- [ ] Idempotent re-initiate (same `(request, doc)`) → the same `vsr_id`, no new row (INV-1).
- [ ] `completeSignature` → status `completed`, `cert_serial` set, a Completed envelope (INV-2).
- [ ] The DB rejects a `cert_serial` on a non-completed row (the CHECK fires) (INV-2).
- [ ] `doc_hash` is bytes/hash; no vendor type crosses the port (INV-3, INV-5).

## 8. Definition of Done (foundation, low rigor)
- [x] §7 tests green — `SigningAclTest` (6, written test-first: red → green); 128 total green.
- [x] `/code-review` on the diff; findings fixed (idempotency targets the `(request, doc)` UNIQUE so
      there's no silent-null path; `vsr_id` reuse → clean reject; input guards; collision-free stub cert).
- [x] `DL-BE-027` entry (the signing stub pattern on `AbstractAclService`, idempotent (request,doc)
      session, cert-on-completed, the e-Stamp / webhook-ingress / BC16 deferrals).
- [x] Status flipped to **Done**.

## 9. Self-review resolutions (DoR-green)
1. **Reuses the ACL base** — `SignatureAclService extends AbstractAclService` (audit + sha256 shared,
   DL-BE-026); only the signing-specific persistence/idempotency is new.
2. **Migration — RESOLVED: none.** `gate_signature_session` + enums exist (V4).
3. **Stub shape — RESOLVED: deterministic.** Initiate → fixed `vendor_session_url`; complete →
   auto-success + fake `cert_serial`. Only the vendor client is swapped at Production.
4. **Two-step (initiate → complete)** mirrors the real async flow (init, then signing webhook); the stub
   completes in-process. The HTTP webhook ingress + HMAC are the real adapter's (§1 deferred).
5. **e-Sign only — RESOLVED:** e-Stamp (`issue_stamp`/`StampIssued`, master-level, G15) is deferred;
   the schema has no stamp table and stamping is its own concern.
6. **Not a gateway command** — signing is BC5-triggered; idempotency is the ACL key, audited via
   `auditAclEvent` directly.

## 10. Watch-for (carry forward)
- **Real-adapter swap** brings the webhook stack (`/webhooks/signing/...`, HMAC, 5-min replay,
  `vendor_event_id` dedup), the Aadhaar-OTP/DSC paths, **UIDAI-outage degradation** (A2 §3.6), the
  **retry/expiry** lifecycle (cap 3), and **BC16** signed-doc archival.
- **e-Stamp** (BC19's other half, G15) — model when the master-agreement stamping flow lands.
- **Failed path** (like M5a/b) — `failed`/`expired` transitions are latent under the always-succeeding stub.
- The consumer **BC5 (M12)** maps these envelopes to `MasterAgreement.Signed` / `AssignmentSignature.Completed`.
