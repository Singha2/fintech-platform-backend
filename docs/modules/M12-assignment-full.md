# M12 · Assignment & Signing (BC5) — **full rigor** (Milestone 2, widen WS-6)

> **Module spec** (Spec_Driven_Build_Plan §H Milestone 2; register §C M12). Takes BC5 from the WS-6
> single-leg skeleton (`total_count=1`, inline complete → `all_signed`) to the complete spec:
> **multi-investor assignment legs**, the **G13 24h time-box / incomplete** path, and the **leg-failure +
> retry** path — to DoD. Spec before code; **invariant test before rule**. Umbrella decision: **DL-BE-051**;
> sub-slices claim `DL-BE-052+`.

| | |
|---|---|
| **Module** | M12 — Assignment & Signing (BC5), full rigor |
| **Predecessor** | WS-6 ([[DL-BE-036]]) — single leg (`total_count=1`), inline complete-signing → `all_signed` (C27 gate) |
| **Tier** | Full (maximum rigor — Wave 1) |
| **Status** | Done |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-26 |

---

## DoR decisions (settled at the gate — the two forks + derived)

1. **Signing completion = an ops `complete-signing` command per leg; the BC19 signing webhook DEFERRED.**
   Extend WS-6's admin-driven completion to multi-leg (the M5c stub completes in-process). The asynchronous
   signing webhook (inbound, like WS-5) stays deferred — consistent with the admin-on-behalf pilot and the
   M11 "inline, defer webhook" choice. (User decision, 2026-06-26.)
2. **G13 incomplete = an ops `DeclareIncomplete` command; the automatic cron DEFERRED.** Guarded on
   `sign_deadline ≤ now() AND signed_count < total_count`; the full incomplete path is built + testable, only
   the `@Scheduled` time-trigger is deferred (consistent with M11's `DeclareFundingShortfall`). (User decision.)
3. **(Derived) Admin-on-behalf retained.** Ops initiates legs + records completions/failures on the
   investors' behalf; the investor signs (`legal_signer_type='investor'`) but does not self-serve.
4. **(Derived) No new migration.** `legal_assignment_set_status` has `incomplete`; `sign_deadline` (AS.2),
   the counts-balance CHECK (AS.5), and the legs JSONB already exist. Multi-leg lives in the same JSONB array.
5. **(Derived) Per-invoice stamping (AS.7) DEFERRED** (soft/async, G2 placeholder) — recorded as a gap.

---

## 1. Scope

**Owns (BC5 assignment + signing lifecycle):** the `assignment` package (`AssignmentService` +
`AssignmentController`), the `legal_assignment_set` (+ legs JSONB), `legal_master_agreement`,
`legal_signature_request` aggregates, and the C27 `deal_listing.all_signed` flip. Signing via the M5c
`SignatureAclService` (inline).

**State machine — DB-enum-true (`legal_assignment_set_status`):**

```
requested/in_progress ──(each leg signed)──→ … ──(signed_count == total_count, within time-box)──→ all_signed
        │                                                                                    └─ flip deal_listing.all_signed = TRUE (C27)
        └──(DeclareIncomplete: sign_deadline passed, signed_count < total_count)──→ incomplete
                                                                                    └─ listing fully_funded → held_for_review (HoldForReview)
  per-leg: pending/initiated ──(complete-signing)──→ signed
                              └──(record-leg-failed)──→ failed ──(re-initiate, retry ≤ 3)──→ initiated
```

**Adds over WS-6:** (a) **multi-leg** — `request` enumerates ALL confirmed subscriptions (`total_count = N`),
one leg + MIA + signature request per investor; `complete-signing` targets one investor's leg; `all_signed`
fires only at `signed_count == total_count` (AS.3). (b) the **G13 incomplete** path (AS.4 + the HoldForReview
reaction). (c) **leg-failure + retry** (AS.5/SR.2).

**Does NOT own (deferred, documented):** the BC19 **signing webhook** (inbound async completion); the
automatic time-box **scheduler**; **per-invoice stamping** (AS.7/G2); the **refund of held subscriptions
after an incomplete set** (the listing `held_for_review → cancel/refund` wiring is a downstream BC1/BC2
cross-module reaction — the post-disbursement lifecycle); master-level stamping (MA.4).

## 2. Upstream dependencies
- **WS-6** assignment skeleton + the C27 gate. Done.
- **M5c Signing ACL (BC19)** — `SignatureAclService.initiateSignature` / `completeSignature` + retry. Done.
- **M11** — `confirmed` subscriptions are the legs' source (read at FullyFunded time, AS.1).
- **M4** roles `ops_executive`.

## 3. Invariants & rules (BC5, B3 §2.5 — M12 scope)
- **AS.1 — One AssignmentSet per listing; `total_count` = confirmed subscriptions at FullyFunded.** _(C27, DL-002)_
- **AS.2 — `sign_deadline = Requested_at + 24h` (G13).** _(G13)_ — set at WS-6; the incomplete path is new.
- **AS.3 — `AllSigned` ⟺ `signed_count == total_count` AND within the time-box.** _(C27, G13)_
- **AS.4 — `Incomplete` ⟺ `sign_deadline` reached with `signed_count < total_count`;** further leg signing rejected. _(G13)_
- **AS.5 — `signed_count + unsigned_count = total_count`** always; a signed leg never reverts. DB CHECK backs it. _(C27)_
- **AS.6 — Per-leg `doc_hash` set at `Initiated`, immutable thereafter.** _(C1, C2)_
- **MA.1/MA.2/MA.3 — MasterAgreement per leg:** one per `(party_id, kind)`; `initiated → {signed, failed}`; cert only on signed. _(DL-048)_
- **SR.1/SR.2/SR.3 — SignatureRequest:** dedup key, `retry_count ≤ 3`, cert once on completed. _(A2 §3.3/3.6, C2)_
- **C27/L.5 — `all_signed` is the disbursement gate** the WS-7 payout reads. _(C27, DL-002)_

## 4. API / type surface (intent-shaped, B4 §2.1) — new/changed vs WS-6

| Endpoint | Role | Effect |
|---|---|---|
| `POST /listings/{id}/assignment/request` | ops_executive | AS.1/AS.2: open the set with `total_count = N` confirmed subs; one leg + MIA + signature request per investor; `requested/in_progress` |
| `POST /listings/{id}/assignment/complete-signing` (body `investor_id`) | ops_executive | complete that investor's leg (M5c cert); leg `signed`; at `signed_count == total_count` → `all_signed` + flip `deal_listing.all_signed` (AS.3/C27); reject if past `sign_deadline` (AS.4) |
| `POST /listings/{id}/assignment/record-leg-failed` (body `investor_id`, `reason`) | ops_executive | leg `failed` (AS.5); re-initiable until `retry_count` exhausted (SR.2) |
| `POST /listings/{id}/assignment/declare-incomplete` | ops_executive | AS.4 guard (`sign_deadline ≤ now()` ∧ `signed_count < total_count`) → set `incomplete`; listing `fully_funded → held_for_review` (HoldForReview) |

## 5. Five non-negotiables — applicability
SoD (ops per command), idempotent (`X-Command-Id`; signing idempotent on the vsr/signature-request id),
audit (one envelope per command + the AllSigned/Incomplete transitions). Single-actor ops commands (signing
is a recorded vendor outcome, not a maker-checker pair). MFA-fresh on the admin commands via the gateway. No
money moved here — allocations are paise-exact references; the C27 gate flip is the load-bearing effect.

## 6. Sub-slices (build order — each: red tests → green → /code-review → DoD → DL)

- **M12-A · Multi-leg assignment** ([[DL-BE-052]]) — `request` builds `total_count = N` legs from all
  confirmed subscriptions (one MIA + signature request each); `complete-signing(investor_id)` completes one
  leg; `all_signed` + the C27 flip fire only when every leg is signed. Generalizes WS-6's single-leg
  (removes the `size()==1` guard). **Red:** 2-investor set — completing one leg keeps `in_progress`
  (`all_signed=false`); completing the second → `all_signed` + `deal_listing.all_signed=TRUE`; complete an
  unknown investor → reject; double-complete a leg → idempotent/rejected; SoD.
- **M12-B · G13 incomplete + leg failure** ([[DL-BE-053]]) — `declare-incomplete` (guarded) → `incomplete` +
  listing `held_for_review`; `complete-signing` past `sign_deadline` → reject (AS.4); `record-leg-failed` →
  leg `failed`, set can't reach `all_signed`; re-initiate a failed leg (retry ≤ 3). **Red:** declare before
  the deadline → reject; declare past the deadline with an unsigned leg → incomplete + held_for_review +
  `all_signed` stays false; sign a leg past the deadline → reject; a failed leg blocks `all_signed` until
  resigned.

## 7. Test scenarios (write first) — extend `AssignmentAllSignedTest`'s harness (seed a fully_funded listing
with N confirmed subscriptions). Each invariant test proves the app guard and the DB CHECK where one exists
(counts-balance AS.5, the MIA/signature-request cert shape). The C27 gate flip stays rowcount-asserted (the
WS-6 lesson).

## 8. Definition of Done (full tier — gate F)
- [x] §7 tests green; whole suite green — **255** (was 247 at M11 close; +8).
- [x] `/code-review` (high recall); 1 fix (`loadSet … FOR UPDATE` — closes the concurrent multi-leg
      lost-update that wedged the C27 gate + the stale-vsr-after-reinitiate race).
- [x] SoD, idempotency, audit — enforced + tested incl. reject paths; the C27 flip rowcount-asserted.
- [x] No new migration.
- [x] `DL-BE-051` umbrella + `DL-BE-052..053` per slice; spec flipped to **Status: Done**.

## 9. Remaining gaps after M12-full (documented, with owner)
- **BC19 signing webhook** (async SignatureCompleted/Failed) → a signing-webhook slice (mechanism proven by WS-5).
- **Automatic 24h time-box scheduler** → scheduler-era.
- **Per-invoice stamping (AS.7) + master-level stamping (MA.4)** → stamping slice (G2/DL-048).
- **Refund of held subscriptions after an incomplete set** → the listing `held_for_review → CancelPreDisbursement
  → refund` wiring (cross-module BC1/BC2 reaction; ties into M11's refund + the post-disbursement lifecycle).
- **MarkRefundEligible auto-fan-out** on Incomplete (the explicit per-subscription event) → event-bus era.
