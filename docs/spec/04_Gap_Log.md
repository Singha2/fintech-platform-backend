# Gap Log

*Living document. Working assumptions made to keep momentum. Each gap is reversible — if reality breaks the assumption, only the affected slice is revisited.*

**Status legend:** Open · Assumed · Resolved · Parked-Legal · Parked-Founder · Parked-Architect

---

## Active Gaps

| # | Gap | Working Assumption | Status | Resolve By | Blocks |
|---|---|---|---|---|---|
| G1 | Escrow webhook idempotency semantics | HMAC-signed, at-least-once delivery; idempotency key = provider's txn_ref; platform dedupes | Assumed | Escrow vendor selection | Event model, reconciliation |
| G2 | Per-invoice stamp duty execution | Per-investor assignment is one logical event; stamping is async post-event, may be batched | Assumed | Legal counsel (DL-048) | Assignment aggregate |
| G3 | Partial-funding refund SLA & accounting | T+1 refund on window close; same rails as inflow; no platform fee deducted | Assumed | Founder | Subscription state machine |
| G4 | TDS calculation moment | Calculated at distribution instruction time; locked into instruction payload | Assumed | Founder + counsel | Distribution event payload |
| G5 | "Acting on behalf of supplier" authorisation artefact | Click-through e-consent at supplier onboarding granting Platform Ops limited agency; specific actions logged individually | Assumed | Legal counsel | Supplier aggregate, audit schema |
| G6 | Reconciliation engine: real-time vs batch | Real-time per webhook event with end-of-day batch reconciliation overlay | Assumed | Architect | Settlement context |
| G7 | Audit log substrate | Append-only DB table with cryptographic chaining + periodic export to WORM storage | Assumed | Architect | Compliance context |
| G8 | Aggregator failure modes (timeouts, stale data) | Each verification has a TTL; stale = re-verify; aggregator outage = manual fallback with elevated approval | Assumed | Aggregator vendor selection | Onboarding flows |
| G9 | Investor invite code transport | Email + SMS dual-channel; code valid against email+phone hash at issuance | Assumed | Confirm with founder | Onboarding flow |
| G10 | "100% funded" boundary semantics | Strict equality on rupee value at virtual account balance; over-subscription prevented at commit time, not inflow time | Assumed | Founder | Funding invariants |
| G11 | Multi-leg payout atomicity at escrow | Non-atomic: each leg is independent NEFT/RTGS; partial-success enters Manual Remediation queue under T&S; failed legs never silently dropped | Assumed | Escrow vendor selection | Distribution workflow, settlement context |
| G12 | Form 26Q (TDS quarterly return) filing ownership | Escrow provider files Form 26Q using their TAN; platform issues Form 16A annually to investors using escrow's challan refs. Confirm vendor capability; fallback is panel-CA filing using platform's TAN | Assumed | Escrow vendor selection + counsel | Tax reporting flow |
| G13 | Per-investor assignment signing time-box | 24h window from `AssignmentsRequested` to `all_signed`. Incomplete at expiry → escalate to Credit Reviewer; listing rolls into review state. Investors who signed are refunded if listing fails to disburse | Assumed | Founder + counsel | Assignment aggregate, listing state machine, refund rules |
| G14 | e-Sign + e-Stamping vendor consolidation | One orchestration vendor (Leegality / Digio / SignDesk class) for both signing and stamping. Single integration surface; vendor handles UIDAI ESP and SHCIL backends | Assumed | Founder (vendor procurement) | Digital execution context |
| G15 | Signed document long-term storage | Encrypted object storage (India-resident, C13/C14), 10-year retention aligned with audit log (C1). SHA-256 hash in audit log; document body in object store keyed by hash | Assumed | Architect | Document storage subsystem |
| G16 | Phase 2 re-KYC obligation under regulated regime | Phase 1 KYC stack (document upload + Aadhaar e-KYC over OTP, no V-CIP — DL-050) may not satisfy RBI V-CIP / SEBI norms if platform regulates in Phase 2 (NBFC, TReDS, AIF). Working assumption: Phase 1 cohort of 50–100 investors is re-KYC'd at regulatory transition. Cost bounded by cohort size; treated as accepted, time-bound risk | Assumed | Founder + counsel at Phase 2 trigger | Phase 2 transition plan; not a Phase 1 blocker |
| G17 | Event-bus / published-language substrate | In-process pub/sub for Phase 1 monolith. `AuditEventEnvelope` is the wire format and is broker-future-proof. No cross-process broker until Phase 2 multi-service split | Assumed | Architect | Event model (B2), all inter-context flows |
| G18 | Cross-context command idempotency | All command handlers idempotent on `(actor_id, command_id)`. Replays safe; protects against retry-storms beyond the fund-movement scope C9 already covers | Assumed | Architect | Aggregate design (B3), API surface |
| G19 | Tenant-isolation enforcement layer | C16 enforced at repository layer in each context, driven by IAM-issued claims at session establishment. Never UI-only | Assumed | Architect | Repository pattern, query API design |
| G20 | Pricing band in-flight invariance | Listing snapshots pricing band + buyer-limit headroom + supplier exposure cap at `ready_for_review` → `live`. Subsequent Credit-side changes do not affect in-flight listings | Assumed | Founder + Credit Lead | Listing aggregate, Credit-Listing contract |
| G21 | Auditor account provisioning ownership | Super Admin proposes auditor account; Compliance Reviewer approves via record-level maker-checker. Account-level SoD (C19) then bars combining with operational roles | Assumed | Founder | Auditor Access bounded context, IAM policy |
| G22 | `CommandRejected` envelope semantics | Failed-but-authorised commands emit a `*.CommandRejected` envelope with proposed `after_state` and reason. Used for audit of attempted-but-blocked actions (e.g. `MakerChecker.Blocked` is a specialisation). Quietly-failed commands at network/auth layer do NOT emit; those live in infrastructure logs | Assumed | Architect | B3 aggregate API, audit completeness |
| G23 | Reconciliation correction event shape | Provisional inflow reversals and EoD overlay corrections emit `InflowReconciled` (or sibling) with `corrects` set. Subscribers treat corrective envelope as authoritative; projections preserve original for audit. No envelope is ever rewritten | Assumed | Architect | Settlement reconciler, BC2 projection design |
| G24 | Correlation propagation across vendor boundary | `correlation_id` does not survive a round-trip through an external vendor. Banking ACL re-establishes `correlation_id` from the platform-side `client_instruction_id` it stored when issuing the outbound call; webhook envelopes inherit it. No vendor receives or returns our `correlation_id` | Assumed | Architect | Banking/Verification/Signing ACL implementations |
| G25 | Cryptographic chain segmentation | Per-shard chains for the Audit Log substrate (G7) — global single chain unworkable at throughput. Shard key = `(context, business_date)` or similar. Each shard's chain is independently verifiable; cross-shard ordering is via `recorded_at` and `correlation_id`, not the chain | Assumed | Architect | Audit Log substrate selection (G7) |
| G26 | Suitability override-acknowledgment payload | The override text shown to the investor at suitability mismatch is itself a versioned artefact; the envelope stores `override_text_hash` and the version, not the text. Documents context stores the canonical text by hash. Allows the displayed disclosure to evolve without losing audit fidelity | Assumed | Founder + counsel | BC7 investor onboarding flow, disclosure artefact lifecycle |
| G27 | Cross-aggregate atomicity in monolith vs broker | In Phase 1 monolith (G17), cross-aggregate "coordinated commits" (B3 X1 Listing+Subscription, X13 audit publish-before-success) use a local DB transaction with an outbox flush. In Phase 2 broker world, X1 becomes a saga with a reservation hold + confirm/release; X13 becomes broker-publish-with-sync-ack. The aggregate boundaries themselves do not change; the mechanism does | Assumed | Architect | Subscription commit handler, Listing `committed_total` update, audit outbox |
| G28 | Recovery window for loss realisation | After `DefaultCase.Classified(defaulted)`, a 12-month recovery window is opened; on expiry, `Subscription.LossRealised` fires for any non-zero residual. Window may be extended once by Credit Reviewer + Founder. Each `Recovery.Achieved` during window emits a partial distribution and reduces eventual loss | Assumed | Founder + Credit Lead | BC2 Subscription S.7, BC6 MaturityCase MC.5, B3 X10 |
| G29 | Maker-checker on cross-aggregate commands | When a command touches two aggregates (e.g. `CancelPreDisbursement` updates `Listing` and triggers subscription refunds), maker-checker is evaluated on the *initiating* aggregate (`Listing`). The downstream effects inherit the same maker-checker decision via causation; no second pair-check on the dependent aggregates | Assumed | Architect | BC1 `Listing.CancelPreDisbursement`, BC4 `RemediationCase.Resolve`, B3 X11 |
| G30 | Concentration projection refresh cadence | Soft concentration warnings (B3 S.8) are computed from a read-side projection of the investor's positions. The projection is refreshed on every `Subscription.Committed`, `Subscription.Refunded`, `Subscription.Closed`. There is no separate "concentration aggregate"; the rule is read-side and warning-only (DL-011). If a stale projection misses a warning at commit time, no business consequence — the warning is advisory | Assumed | Founder | BC2 S.8, B3 X17 |

---

## Resolved Gaps

*(Empty for now. As gaps get resolved, they move here with the resolution recorded.)*

---

## How to use

- **Before each phase:** scan for gaps that block the phase. Resolve or confirm assumption.
- **During each phase:** if a new gap surfaces, add it here with a working assumption, don't stall.
- **End of each phase:** re-version this file in the project.
