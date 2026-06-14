# B4 — API Conventions (lite)

*Phase 1 MVP. Shared conventions the team applies uniformly to every command and query derived from B3. Not a per-endpoint enumeration. Inputs: DL-001 through DL-050; A1 constraints C1–C28; A2 integration contracts; B1 contexts BC1–BC19; B2 envelope and event catalogue; B3 aggregates and invariants; Gap Log G1–G30. Output: a fixed contract for the API layer that internal screens and AI agents consume identically. Deployment-agnostic — REST-shaped semantics; the transport choice (framework, hosting, edge) is an Architect-layer decision.*

---

## 1. Principles

**P1. Conventions, not enumeration.** Every command in B3 §2 becomes a command endpoint by mechanical application of §2 below; every aggregate query is shaped by §3. This document does not list endpoints. Adding an aggregate or a command does not require an API-design pass.

**P2. Internal-only surface.** APIs are consumed by internal screens and AI agents. No external consumers (DL-005, DL-008 close the perimeter). No public versioning, no public docs, no public-abuse rate limiting. Conventions optimise for build-time uniformity and audit defensibility, not for backward-compat over years.

**P3. Two consumers, one surface.** Internal screens and AI agents call the same endpoints with the same envelopes. The endpoint does not branch on consumer type. An AI agent is an authenticated session like any other; its `actor` shape is settled by G31 (new), not by special-casing inside the handler.

**P4. Commands carry intent; queries carry no side effects (except sensitive-read audit).** Commands map 1:1 to B3 §2 command rows; their endpoints are intent-shaped. Queries return projections; the only state change permitted in a query path is the `*.SensitiveReadPerformed` envelope (B2 §2.6, C2).

**P5. The envelope is the wire-level success contract.** A command's HTTP success body names the envelopes it emitted (B2 §3). A command that produced no envelopes did not succeed — it is `CommandRejected` (G22) or an error (§4).

**P6. Idempotency keys are mandatory on every mutating call.** `command_id` on commands (G18), `client_instruction_id` on outbound vendor calls (C9, A2), vendor `event_id` dedupe on inbound webhooks (B2 §2.4, G1). No mutating call is ever unkeyed.

**P7. Tenant isolation is repository-layer.** Query handlers do not receive a "tenant filter" parameter; the repository injects it from session claims (X14, G19, C16). An endpoint that omits tenant filtering is impossible to write, by framework construction. UI-layer filtering is never the enforcement mechanism.

**P8. Audit-publish-before-success is a hard wire contract.** The HTTP response does not return 2xx until the envelope has been appended to BC14 via the in-process outbox flush (X13, G27). A 2xx response is a load-bearing claim that the audit log has the fact.

---

## 2. Command API Conventions

### 2.1 Endpoint shape — intent-shaped, derived from B3

Commands are intent-shaped, not resource-shaped. The endpoint name is the B3 command name lowercased and kebab-cased; the path locates the aggregate root by identity.

| B3 command | Endpoint |
|---|---|
| `Listing.ApproveGoLive(...)` (B3 §2.1.2) | `POST /listings/{listing_id}/approve-go-live` |
| `Subscription.CommitSubscription(...)` (B3 §2.2.1) | `POST /listings/{listing_id}/subscriptions/commit` |
| `PayoutInstruction.DraftDistribution(...)` (B3 §2.4.2) | `POST /payout-instructions/draft-distribution` |
| `BuyerCreditProfile.SetBuyerCreditLimit(...)` (B3 §2.3.1) | `POST /buyers/{buyer_id}/credit-limit/set` |
| `AuditorAccount.Propose(...)` (B3 §2.13) | `POST /auditor-accounts/propose` |

Aggregate-creating commands omit the aggregate identifier in the path; the response carries the minted identity. Subordinate path segments name the parent aggregate (e.g. `/listings/{listing_id}/subscriptions/commit`) only when the parent's identity is part of routing — never to imply a sub-resource hierarchy that B3 does not have.

**Verbs are intent verbs from B3, not CRUD.** `approve-go-live`, not `update`. `commit`, not `create`. `cancel-pre-disbursement`, not `delete`. The B3 catalogue is the only source of acceptable verbs.

### 2.2 Request envelope — required headers

Every command request carries the following headers. Missing or malformed headers are 400-class errors (§4) that do **not** emit an envelope (B2 §5.6 — pre-authorisation failures live in infrastructure logs, not the audit log).

| Header | Required | Type | Purpose | Refs |
|---|---|---|---|---|
| `X-Command-Id` | yes | UUIDv7 | Producer idempotency key; `(actor_id, command_id)` uniquely identifies the command. Replays return the original envelope chain. | G18, B2 §2.4 |
| `X-Aggregate-Version` | conditional | int | Expected `aggregate_version` for optimistic concurrency control (B3 P8). Required on every command that mutates an existing aggregate; omitted on aggregate-creating commands. Mismatch → 409 `version_conflict`. | B2 §2.1, B3 P8 |
| `X-Mfa-Assertion-Id` | conditional | UUID | Reference to a current `Mfa.ChallengeSucceeded` envelope (B2 §3.10). Required on every command whose actor is `admin_user`. Stamped onto the produced envelope's `actor.mfa_assertion_id`. | C7, DL-035 |
| `X-Agency-Consent-Id` | conditional | UUID | Reference to an active `AgencyConsent.Granted` envelope (B2 §3.8). Required iff `actor.actor_type=agency` — i.e. admin acting on behalf of a supplier (DL-013). Stamped onto the produced envelope's `actor.agency_consent_id`. | DL-013, G5, B3 X12 |
| `X-Correlation-Id` | yes | UUID | Propagated from upstream when this command is part of an in-flight transaction (caller-supplied for chained commands); minted by the API layer when this command is the root of a new transaction (a fresh UI click, a scheduled job, an AI-agent action). Copied verbatim into every produced envelope. | B2 §2.3 |
| `X-Idempotency-Replay` | optional | bool | Caller hint indicating "this is a known retry; please return the original result fast and skip side effects". Advisory; the server is required to be idempotent regardless of this header (G18). | G18 |
| `Authorization` | yes | bearer | Session credential. Resolves to an `actor` per B2 §2.2 and to tenant claims per X14. AI-agent identity model: G31 (new). | C18, X14, G31 |

The body is a JSON object containing exactly the command arguments named in the B3 §2 command row. No transport-level fields are smuggled into the body; transport lives in headers.

### 2.3 Success response — minted identifiers and emitted envelopes

A successful command returns `200 OK` (or `201 Created` for aggregate-creating commands) with a body of this shape:

```
{
  "aggregate_id": "<uuid>",
  "aggregate_version": 17,
  "emitted_events": [
    { "event_id": "...", "event_type": "listing.Listing.GoneLive", "occurred_at": "..." },
    { "event_id": "...", "event_type": "settlement.VirtualAccount.RequestCreation", "occurred_at": "..." }
  ],
  "correlation_id": "<uuid>"
}
```

The `emitted_events` list is the command's contract with its caller. AI agents and screens both use it: screens to advance the UI state machine (e.g. show the listing as `live` and link to its VA placeholder); agents to seed the next causation chain (their next call carries the same `correlation_id` and a fresh `command_id`). The `event_id` values are the keys callers use to subscribe to or query for downstream consequences.

The list contains every envelope the producing handler appended to BC14 within this command's transaction. Subscriber-emitted envelopes (e.g. BC4's `VirtualAccount.Created` that follows a successful `ApproveGoLive`) are **not** listed — they have their own envelope chain rooted in a separate command. The caller observes them by querying the affected aggregate (§3).

### 2.4 Idempotent retry semantics

Per G18, command handlers are idempotent on `(actor_id, command_id)`.

- Same `command_id`, same body, same `aggregate_version` → returns the original `emitted_events` list with the original `event_id` values. The handler does not re-execute; the response is reconstructed from the audit log.
- Same `command_id`, different body → 409 `idempotency_conflict`. The first-write-wins; the divergent retry is rejected. Caller mints a new `command_id` if the divergence is intentional.
- Same `command_id`, same body, stale `aggregate_version` → resolved as "version conflict during retry": the handler verifies that the original event chain *did* produce the expected version transition and replies with the original result. If it did not (the original execution failed somehow), the response is the original failure result.
- Different `command_id`, same business effect → not idempotent at this layer. This is what business-level uniqueness invariants are for (e.g. INV.1 on `(supplier_id, irn)`); the handler may still succeed and emit two events that fight at the invariant layer, in which case the second resolves to a domain error (§4).

The `(actor_id, command_id)` tuple is recorded on the produced envelope's `command_id` field (B2 §2.1) and is the database key for the deduplication index.

### 2.5 Optimistic concurrency

The handler reads the aggregate, checks `aggregate_version` against `X-Aggregate-Version`, and either proceeds (incrementing the version in the same transaction as the envelope append) or returns 409 `version_conflict` with the current version in the response body. Callers retry by re-reading and re-submitting (the AI-agent loop is the same as the screen's optimistic-write loop — see B3 P8).

---

## 3. Query API Conventions

### 3.1 Endpoint shape — resource-shaped

Queries are resource-shaped, in contrast to commands.

| Read need | Endpoint |
|---|---|
| Single aggregate by id | `GET /listings/{listing_id}` |
| Aggregate collection | `GET /listings?…` |
| Projection (read-side) | `GET /investor-portfolio/{investor_id}/concentration` |
| Audit-log query | `GET /audit-events?correlation_id=…` |

The path names the resource; query parameters carry filters, pagination, and projection selection.

### 3.2 Projection vs aggregate read distinction (B3 P5)

B3 P5 settles which fields live on the aggregate and which on projections. The API surface honours the same line:

- `GET /{aggregate}/{id}` returns the aggregate's state as defined in B3 §2. No derived or rolled-up fields. The response shape is small and bounded — `listing_id`, `status`, snapshots, `committed_total`, `va_id`, etc. — exactly the B3 state-fields row.
- `GET /{projection}/…` returns derived views: investor portfolio rollups, concentration computations (X17), reconciliation summaries (X3), maker-checker queues, the auditor-scoped admin UI views.

The same backend can serve both routes (B3 §5: "the same backend; the API surface honours the separation"), but the URL communicates whether the response is an aggregate read or a projection. AI agents and screens both rely on the distinction: aggregate reads are stable; projections may evolve schema as the read-side does.

### 3.3 Pagination

Cursor-based pagination on every collection endpoint. Offset-based pagination is not used (its results are unstable under concurrent writes).

```
GET /listings?status=live&cursor=eyJpZCI6Ii4uLiJ9&limit=50
```

Response includes a `next_cursor` field (null at the end). `limit` is bounded — default 50, max 200. AI agents that need bulk reads (e.g. reconciliation runs, auditor exports) use cursors; they do not need a separate bulk-export endpoint. Auditor exports specifically are rate-limited at the BC13 layer (C19, AA13).

### 3.4 Filtering

Filter parameters match aggregate state-field names exactly. The query layer is forbidden from inventing fields the aggregate does not have; new filter dimensions require a projection.

```
GET /subscriptions?listing_id=…&status=confirmed
GET /payout-instructions?kind=distribution&status=approved&maker_id=…
```

Multi-valued filters are repeated keys (`status=committed&status=funds_pending`). Range filters are explicit (`occurred_at_from=…&occurred_at_to=…`). No DSL, no operators in values.

### 3.5 Tenant isolation at repository layer (C16, X14, G19)

Tenant isolation is invisible to the endpoint surface and to the handler. The repository implementation reads the tenant claims attached to the session at authentication (B2 §3.10 `TenantClaim.Issued`) and injects them as predicates on every query.

| Actor type | Repository-injected filter |
|---|---|
| `investor` | `subscription.investor_id = session.investor_id`; listings visible (DL-010 supplier identity, DL-017 aggregate funding); other investors' subscriptions never visible |
| `supplier_user` | `invoice.supplier_id = session.supplier_id`; aggregate-only funding visibility on own listings (DL-017); other suppliers' invoices invisible |
| `buyer_ack_user` | invoices `buyer_id = session.buyer_id` only |
| `admin_user` | role-scoped admin views; tenant claims expand the visibility set per role permissions (BC10 RBAC) |
| `auditor` | `AccessScope`-bounded views (B3 §2.13, AA13.4); every read additionally emits `AuditorRead.Performed` (C3) |
| `agency` | inherits the supplier's filter via `agency_principal_id` (B2 §2.2); every read additionally emits `AgencyAction.Recorded` if the read crosses a sensitivity threshold defined in BC8 |

A handler that constructs a query without going through the repository — or that bypasses the repository's tenant predicates — is a defect, not a feature. C16 violations are 403 errors (§4) and are themselves audit events. The framework forbids the construction; per X14, queries that would omit the filter are rejected at framework level, not at handler level.

### 3.6 Sensitive reads emit envelopes

A `GET` on a sensitive aggregate or projection — KYC files (BC11), financial profiles (BC8), AML screening hits (BC11), or any document read via BC16 — emits a `*.SensitiveReadPerformed` envelope before the response returns (B2 §2.6, C2). The envelope has empty `before_state`/`after_state` (no mutation) but carries the actor, the target identity, and the read purpose. The sensitivity classification of each aggregate type is owned by the aggregate's bounded context, not by the API layer.

Sensitive reads are subject to X13 (audit-publish-before-success): the read response is not returned until the envelope is appended.

---

## 4. Error Taxonomy

One canonical error response shape, used for every non-success response in the system. Errors are classified into nine categories. Some categories emit envelopes (visible failures with an authenticated, authorised actor — G22); others do not (pre-authorisation failures live in infrastructure logs only).

### 4.1 Canonical error response shape

```
{
  "error_code": "maker_checker_violation",
  "error_category": "sod",
  "message": "Checker cannot equal maker on the same originating aggregate.",
  "violating_rule": "C4 / B3 X11",
  "violating_invariant_id": null,
  "command_rejected_envelope_id": "<uuid>",
  "details": {
    "originating_aggregate_type": "Listing",
    "originating_aggregate_id": "<uuid>",
    "maker_actor_id": "<uuid>",
    "checker_actor_id": "<uuid>"
  },
  "correlation_id": "<uuid>",
  "retryable": false
}
```

- `error_code` is a stable, machine-readable identifier from the table in §4.2.
- `error_category` is one of the nine in §4.2.
- `violating_invariant_id` carries the B3 invariant identifier (e.g. `L.2`, `S.1`, `PI.3`) when the error is a domain invariant violation; null otherwise.
- `violating_rule` cites the canonical constraint, decision, or B3 cross-aggregate rule (`C4`, `DL-007`, `X11`, etc.).
- `command_rejected_envelope_id` is present iff this error produced a `*.CommandRejected` envelope (G22); the value is the `event_id` of that envelope.
- `retryable` indicates whether a naive client retry can succeed. Version conflicts are retryable; invariant violations are not (the same command will fail the same way); vendor outages are retryable with backoff.

The shape is identical across endpoints. AI agents and screens both dispatch on `error_code` and `error_category`.

### 4.2 Category × envelope table

| Category | Examples (`error_code`) | HTTP class | Emits envelope? | Envelope type | Refs |
|---|---|---|---|---|---|
| `auth_failure` | `bearer_missing`, `bearer_expired`, `bearer_invalid` | 401 | no | (infrastructure log only) | G22, B2 §5.6 |
| `mfa_missing_or_expired` | `mfa_assertion_missing`, `mfa_assertion_expired` | 401 | no | — | C7, G22 |
| `role_not_authorised` | `role_not_held`, `sod_role_block` | 403 | no (pre-authorisation) | — | C5, C18 |
| `tenant_isolation_violation` | `tenant_filter_violation`, `cross_tenant_read` | 403 | yes | `<context>.TenantViolation.Detected` | C16, X14, G19 |
| `maker_checker_violation` | `checker_equals_maker`, `maker_aggregate_state_invalid` | 409 | yes | `admin_iam.MakerChecker.Blocked` | C4, X11, G22 |
| `invariant_violation` | per `violating_invariant_id` (e.g. `S.1_below_ticket_min`) | 422 | yes | `<context>.<aggregate>.CommandRejected` | B3 P6, G22 |
| `version_conflict` | `aggregate_version_stale` | 409 | no | — | B3 P8 |
| `idempotency_conflict` | `command_id_payload_mismatch` | 409 | no | — | G18, §2.4 |
| `vendor_outage` | `aggregator_unreachable`, `escrow_unreachable`, `signing_unreachable` | 503 | yes | `<context>.<Vendor>Outage.Declared` | G8, A2 §1.5, B2 §3.17–3.19 |

Notes on emission policy. Authentication and pure role-check failures are explicitly excluded from envelope emission (G22): the actor was never authorised to take action, so there is no business fact to record. Tenant violations *are* recorded — they indicate an authenticated session attempting cross-tenant access, which is an investigation-worthy signal (C16). Maker-checker violations and invariant violations both emit `CommandRejected` envelopes carrying the proposed `after_state` and reason (G22, B2 §5.6) — the audit log captures attempted-but-blocked actions, which is the whole point of maker-checker as an architectural control. Vendor outage emissions are bounded: the `*Outage.Declared` envelope fires on threshold crossing (A2 §1.5, §2.6), not on every 503; per-request 503s do not each spawn an envelope.

### 4.3 The `CommandRejected` envelope (G22) in detail

A 422 invariant violation or a 409 maker-checker violation produces a `<context>.<aggregate>.CommandRejected` envelope. Its payload carries:

- `command_name` — the B3 command attempted.
- `command_payload` — the body as received (sensitive fields masked per BC16 rules).
- `before_state` — the aggregate's state at command-handler entry.
- `proposed_after_state` — what the state would have been if the invariant had held.
- `failing_invariant_id` — e.g. `L.4`, `S.1`, `PI.3`.
- `failing_invariant_text` — human-readable summary copied from B3 §2.

`MakerChecker.Blocked` (B2 §3.10) is a specialisation: it is the `CommandRejected` for the specific case of X11 violations and is emitted by BC10 rather than by the originating context, since maker-checker is a BC10 workflow primitive.

The envelope is appended before the 4xx error response returns (X13), so callers can include `command_rejected_envelope_id` in the response body knowing the audit fact is durable.

---

## 5. Webhook Ingress Conventions

The platform receives webhooks from three external categories (BC17 Verification, BC18 Banking, BC19 Signing). The ingress endpoint is per-vendor under each ACL context:

```
POST /webhooks/banking/{vendor}/{event_type}
POST /webhooks/verification/{vendor}/{event_type}
POST /webhooks/signing/{vendor}/{event_type}
```

The same conventions apply uniformly across all three.

### 5.1 HMAC verification before any state mutation (C10, A2 §4)

The ingress handler verifies the HMAC signature over `(timestamp || body)` against the per-vendor shared secret. Verification happens **before** the body is parsed beyond raw bytes; before any database read; before any envelope is constructed.

- Signature valid → proceed to §5.2.
- Signature invalid → return 401, **emit** `<context>.WebhookSignature.Invalid` envelope (B2 §3.17–3.19) with the raw payload's SHA-256 hash (never the body itself), trigger alert to admins via BC15 (G11 manual remediation pattern), and stop. No vendor payload reaches business state. The envelope itself is one of the very few cases where an unauthenticated source produces an envelope — justified because signature failure *is* a security-relevant business fact (C10).

The 5-minute replay window (A2 §1.2) is enforced here: a signature with a `timestamp` older than 5 minutes from server clock is treated as `WebhookSignature.Invalid`.

### 5.2 Idempotency on vendor `event_id` (B2 §2.4, G1)

After HMAC verification, the handler checks the per-vendor `external_ref.vendor_event_id` against the dedup index for that vendor.

- First sight → proceed to §5.3.
- Already seen → emit `<context>.Webhook.DuplicateDropped` envelope (B2 §3.18) carrying the duplicate's `vendor_event_id`. Return 200 to the vendor to prevent re-delivery. No business state changes (G1).

Dedup is keyed on `(vendor, vendor_event_id)` and the index entry is the first-write's outcome. The `Webhook.DuplicateDropped` envelope is itself written before the 200 returns — visibility of the discard is the point (B2 §5.6).

### 5.3 Envelope construction and correlation re-establishment (G24)

A unique vendor `event_id` becomes a root envelope in the relevant ACL context:

- `external_ref` = `{vendor, vendor_event_id, signature_verified_at}` (B2 §2.1).
- `command_id` = null (webhook-driven events are not command-driven — B2 §2.4).
- `correlation_id` is re-established from the platform-side artefact the vendor is replying to, per G24:
  - Banking ACL: `correlation_id` is read from the `PayoutInstruction` (or `VirtualAccount` for inflows) whose `client_instruction_id` matches the vendor's `txn_ref` / `client_instruction_id` echo. The vendor never sees or returns the platform's `correlation_id`; the ACL re-links via the stored mapping.
  - Verification ACL: `correlation_id` is read from the `Verification` record matching `client_request_id`.
  - Signing ACL: `correlation_id` is read from the `SignatureRequest` matching `signature_request_id`.
- `causation_id` = the outbound instruction's envelope `event_id` (the platform-side event that originally caused this vendor reply).
- `actor.actor_type` = `vendor_aggregator` / `vendor_escrow` / `vendor_signing` (B2 §2.2). `mfa_assertion_id` and `agency_consent_id` are null.

### 5.4 Downstream propagation

The ACL context's published envelope (e.g. `banking.InflowWebhookProcessed`, B2 §3.18) is subscribed by the relevant domain context (BC4 Settlement), which then runs its own command (e.g. `VirtualAccount.RecordInflow`) emitting its own envelopes (`InflowObserved`). The webhook envelope's `correlation_id` is preserved end-to-end, so a single trace links the vendor event through every downstream consequence (B2 §5.1).

### 5.5 Signature-failure alerting

`WebhookSignature.Invalid` envelopes drive an alert via BC15 to admin users. A threshold pattern (configurable: N events in M minutes) triggers a `*Outage.Declared` envelope (per ACL context) that surfaces a user-visible status banner via BC15 (A2 §1.5, §2.6). The vendor is contacted out-of-band by Treasury & Settlement (for Banking) or Compliance (for Verification, Signing).

---

## 6. Maker-Checker at the API Layer

Maker-checker (C4, B3 X11) is a record-level workflow primitive owned by BC10. The API layer exposes it uniformly:

### 6.1 Two commands per maker-checker pair

Each maker-checker-gated transition has two B3 commands, hence two endpoints:

| Pair example (B3 §2.1.2 Listing go-live) | Endpoint |
|---|---|
| Maker prepares the aggregate to reach `ready_for_review` | `POST /listings/{listing_id}/snapshot-and-ready` (collapses `TakeSnapshot` + `ReadyForReview` if both fire from one actor; otherwise the prior commands' completion alone produces the `ready_for_review` envelope) |
| Checker approves | `POST /listings/{listing_id}/approve-go-live` |

The two endpoints share neither body nor identity; what binds them is the originating aggregate's identity (`listing_id`) and its envelope chain. The checker's command-handler reads the maker's envelope (the one that produced `Listing.ReadyForReview` or its equivalent) to extract `maker_id`.

### 6.2 Record-level maker ≠ checker on the same aggregate (X11)

The checker command's handler performs:

1. Read the aggregate and find the most-recent envelope on this aggregate whose actor was the maker for this transition (typically the `ReadyForReview` envelope on Listing, or the corresponding `Draft*` envelope on PayoutInstruction).
2. Compare `actor.actor_id` from that envelope against the checker's `actor_id` (extracted from the bearer credential).
3. If equal → emit `admin_iam.MakerChecker.Blocked` (B2 §3.10) and return 409 `maker_checker_violation` (§4.2). The aggregate state is unchanged.
4. If distinct → proceed; emit `admin_iam.MakerChecker.Approved` (BC10) and the originating-aggregate envelope (e.g. `Listing.GoneLive`) in the same transaction.

The check is on `actor_id` (the human account identity), not on `role` or `session_id`. A user holding both Ops Executive and Treasury & Settlement roles (via the composability of C18) still cannot be maker and checker on the same record (DL-033, C4).

### 6.3 Scope rule (G29): cross-aggregate commands evaluate on the initiating aggregate

When a single checker command has downstream effects on dependent aggregates (e.g. `CancelPreDisbursement` on Listing triggers refund instructions on every related Subscription), maker-checker is evaluated **on the initiating aggregate only** (the Listing). The dependent commands run under the same checker's authority and inherit the maker-checker decision via causation chain — they do not each acquire a fresh pair-check (G29).

The API surface reflects this: there is only one checker endpoint (`/listings/{id}/cancel-pre-disbursement`), not N (no per-subscription pair-check). Downstream subscriber commands write envelopes whose `causation_id` chains back to the checker's `MakerChecker.Approved`; the audit log lineage is the proof.

### 6.4 MFA on the checker

The checker's `X-Mfa-Assertion-Id` header is mandatory (C7, B3 L.4, PI.5, etc.); the assertion's freshness window is enforced at the handler level (the assertion's `Mfa.ChallengeSucceeded` envelope must be recent enough — bound is BC10 policy, default 5 minutes for sensitive actions, 30 minutes for normal admin actions). An expired or missing assertion → 401 `mfa_assertion_expired` / `mfa_assertion_missing` (§4.2); no envelope.

### 6.5 The maker-checker queue is a projection

Internal screens and AI agents discover "what's awaiting a checker" via a projection endpoint (§3.2):

```
GET /admin/queues/maker-checker?role=treasury_and_settlement&actor_id=<excluded-self>
```

The projection excludes the requesting actor automatically (so the same actor never sees the records they themselves are makers on — UX defence in depth; C4 enforcement is still at the handler). It is built from `ReadyForReview` and `Draft*` envelopes that have not yet been answered by a paired `MakerChecker.Approved` or `MakerChecker.Blocked`.

---

## 7. Audit-Publish-Before-Success Contract (X13, G27)

### 7.1 The transactional outbox

In the Phase 1 monolith (G17), the command handler runs in a single local database transaction that:

1. Reads the aggregate (acquiring optimistic-lock metadata).
2. Validates invariants per B3 §2.
3. Writes aggregate state changes.
4. Appends the produced envelope(s) to a local **outbox** table.
5. Commits the transaction.
6. Flushes the outbox to BC14 (in-process pub/sub, G17): the audit-log writer consumes the outbox and appends to the append-only chain, computing `previous_envelope_hash` and `envelope_hash` (B2 §2.1).
7. **Only after the flush completes and BC14 acknowledges the append** does the HTTP handler return 2xx.

Steps 1–5 are atomic at the database level. Steps 6–7 are the outbox flush, which is in-process and synchronous. If step 6 fails (BC14 is down, the shard chain is wedged per G25, etc.), the handler returns 503 `audit_log_unavailable`. The aggregate state change persists — but no caller has been told it succeeded — so on retry, the same `(actor_id, command_id)` re-attempts the outbox flush only (step 6 onward), not the state change (idempotency, G18).

### 7.2 Why this matters at the API layer

A 2xx response is a load-bearing claim. Callers — screens, AI agents, downstream subscribers — treat it as proof that:

- The aggregate state has the new version.
- The audit log has the envelope, indexed by `event_id`.
- Subscribers in other contexts will receive the envelope (eventually, at-least-once — B2 §5.2).

This is the bedrock of the whole system: every other guarantee (C1 immutability, C2 audit completeness, C3 auditor visibility) rests on the API layer not lying about whether the envelope was published.

### 7.3 Phase 2 evolution

Per G27, when Phase 2 splits the monolith into multiple services with an out-of-process broker, the outbox flush becomes a broker publish with a synchronous acknowledgement; the same contract holds (2xx not returned until the broker has acked the envelope durably). The aggregate boundaries and command shapes do not change; only the publication mechanism does. The API surface in this document continues unchanged into Phase 2.

---

## 8. Two Worked Examples

These are illustrative applications of §§ 1–7 to two B3 commands. They are not contracts; the conventions above are.

### 8.1 Example A — Maker-checker-gated: `ApproveGoLive` (BC1 Listing)

Setup. Listing `L1` is in `ready_for_review` (B3 §2.1.2, L.1). The Ops Executive (Alice) authored the snapshot and brought it to `ready_for_review`. Treasury & Settlement (Bob) approves go-live (B3 L.4).

**Endpoint.** `POST /listings/L1/approve-go-live`

**Request headers.**

```
X-Command-Id: 5f4e7c00-7c3a-7afe-9c44-c5b3ce0e9d11   (Bob's UI mints this)
X-Aggregate-Version: 6                                (Listing's current version)
X-Mfa-Assertion-Id: 9bce-...                          (Bob's most recent MFA assertion)
X-Correlation-Id: e21a-...                            (the listing's correlation, propagated)
Authorization: Bearer <Bob's session token>
```

`X-Agency-Consent-Id` is absent — Bob is acting as himself, not under supplier agency.

**Request body.**

```json
{ "checker_note": "Go-live approved, conditions in pricing snapshot." }
```

**Handler execution.**

1. Authn/authz: bearer resolves to Bob; role-check confirms `treasury_and_settlement` is held. If not → 403 `role_not_held`, no envelope.
2. MFA: `X-Mfa-Assertion-Id` resolves to an unexpired `Mfa.ChallengeSucceeded`. If expired → 401 `mfa_assertion_expired`, no envelope.
3. Aggregate load: `L1` is read at version 6; `X-Aggregate-Version` matches. If not → 409 `version_conflict`, no envelope.
4. Maker-checker check (X11): the handler reads the most-recent maker-side envelope on `L1` — `Listing.ReadyForReview` — finds `actor.actor_id = Alice`. Bob ≠ Alice. Pair-check passes.
5. Invariant checks: L.4 (checker is T&S, MFA present), L.7 (funding-target arithmetic on snapshot), L.11 (buyer and supplier still active — read by subscriber pattern X5). All hold.
6. State changes (single local transaction):
   - `L1.status: ready_for_review → live`
   - `L1.aggregate_version: 6 → 7`
   - Outbox append: `admin_iam.MakerChecker.Approved` envelope (BC10 owner; carries `record_id=L1`, `maker=Alice`, `checker=Bob`, `correlation_id=e21a-…`, `causation_id` pointing at `Listing.ReadyForReview`, `command_id=5f4e7c00-…`).
   - Outbox append: `listing.Listing.GoneLive` envelope (BC1 owner; carries `aggregate_id=L1`, `aggregate_version=7`, `va_id=null` for now, `funding_window_close_at=…`, `correlation_id=e21a-…`, `causation_id` pointing at the `MakerChecker.Approved`).
7. Commit transaction.
8. Outbox flush: BC14 appends both envelopes in arrival order (B2 §5.1), computing the chain hashes.
9. Return 200.

**Response body.**

```json
{
  "aggregate_id": "L1",
  "aggregate_version": 7,
  "emitted_events": [
    { "event_id": "<uuid-of-MakerChecker.Approved>", "event_type": "admin_iam.MakerChecker.Approved", "occurred_at": "..." },
    { "event_id": "<uuid-of-Listing.GoneLive>",      "event_type": "listing.Listing.GoneLive",          "occurred_at": "..." }
  ],
  "correlation_id": "e21a-..."
}
```

**Downstream observable consequences (not in this response).** BC4 subscribes to `Listing.GoneLive` and runs `VirtualAccount.RequestCreation`; BC18 calls the escrow provider; BC4 receives `Va.LifecycleObserved(created)` and emits `VirtualAccount.Created`. Bob's screen polls `GET /listings/L1` to observe `va_id` set; AI agents observe the same way. None of those subsequent envelopes are returned in the 200 response — they have their own command chains rooted in the subscriber handlers.

**Failure modes illustrated.** If Bob had been Alice (same `actor_id`), step 4 emits `admin_iam.MakerChecker.Blocked` (a specialisation of `CommandRejected` per G22), commits the envelope to BC14, and returns 409 with `error_code=checker_equals_maker` and `command_rejected_envelope_id=<uuid>` (§4.2). No state change to `L1`.

### 8.2 Example B — Webhook-driven subscriber: escrow inflow

Setup. Investor Charu has committed ₹3 lakh against listing `L2`, producing `Subscription.Committed` (subscription `Sub-7`). Charu pushes funds via NEFT to the listing's virtual account (DL-044). The escrow provider sends an `inflow.received` webhook to the Banking ACL.

**Endpoint.** `POST /webhooks/banking/<vendor>/inflow.received`

**Step 1: HMAC verification (§5.1).** The handler verifies `X-Signature` over `(timestamp || body)`. If invalid:
- Emit `banking.WebhookSignature.Invalid` (B2 §3.18, raw payload SHA-256 only).
- Alert via BC15.
- Return 401. Stop.

If valid → proceed.

**Step 2: Dedup on vendor `event_id` (§5.2).** The body's `event_id = "esc-evt-7741"`. The Banking ACL checks its dedup index:
- Already seen → emit `banking.Webhook.DuplicateDropped` with `vendor_event_id=esc-evt-7741`. Return 200. Stop.
- First sight → proceed.

**Step 3: Envelope construction (§5.3).** The Banking ACL constructs the root envelope `banking.InflowWebhookProcessed`:
- `external_ref = { vendor: "<vendor>", vendor_event_id: "esc-evt-7741", signature_verified_at: "..." }`
- `command_id = null` (webhook root, B2 §2.4)
- `correlation_id` re-established (G24): the inbound payload's `va_id` resolves to listing `L2`'s `VirtualAccount`; the ACL reads the original `Listing.GoneLive` envelope's `correlation_id` from the VA's stored mapping. Alternatively, for inflows that match no expected subscription, a new `correlation_id` is minted and the resulting envelope chain is reconciled separately (BC4 `InflowUnmatched`, X3).
- `causation_id` = null (root-of-its-own-chain; the chain links to the listing's chain via `correlation_id`)
- `actor = { actor_type: "vendor_escrow", actor_id: "<vendor>", mfa_assertion_id: null, agency_consent_id: null }` (B2 §2.2)
- `payload = { va_id, txn_ref, amount: 300000_00 (paise), sender_details, utr }`

Envelope appended to BC14 via the outbox; 200 returned to vendor.

**Step 4: Subscriber chain (downstream of webhook response).** BC4 Settlement subscribes to `banking.InflowWebhookProcessed` and runs the `VirtualAccount.RecordInflow` command on `L2`'s VA. This command:
- Has its own internal `command_id` (deterministic from the upstream `event_id`: `sha256("VirtualAccount.RecordInflow|esc-evt-7741")`, idempotent on `(scheduler-actor, command_id)` — B2 §2.4 scheduler rule).
- Carries the same `correlation_id`.
- `causation_id` = the `banking.InflowWebhookProcessed` envelope's `event_id`.
- Emits `settlement.InflowObserved` (provisional, B3 V.4).

BC4 then runs its real-time reconcile attempt: the inflow's amount and `va_id` are matched against the expected subscription Sub-7's `expected_inflow_amount`. Match → `settlement.InflowReconciled(matched_to=Sub-7)`. BC2 subscribes; runs `Subscription.RecordFundsReceived` on Sub-7 (S.3); emits `Subscription.FundsReceived` and then `Subscription.Confirmed`.

**Step 5: Caller observability.** Charu's screen polls `GET /subscriptions/Sub-7` and observes the status transition `committed → funds_received → confirmed`. The AI agent running the funding-progress sweep does the same. The full causation chain — webhook → InflowObserved → InflowReconciled → FundsReceived → Confirmed — is recoverable from the audit log by `correlation_id` (B2 §5.1).

**Step 6: Reconciliation overlay (later).** At EoD, BC4's reconciler ingests the master statement (BC18 `MasterStatement.Fetched`); if the webhook-driven `InflowReconciled` is contradicted, a corrective envelope is emitted with `corrects` set (G23, X15). BC2 treats the corrective envelope as authoritative; the original is preserved (B2 P6).

**Failure modes illustrated.** If step 1 fails: signature-failure path, alert, no business state. If step 2 dedups: nothing happens beyond the audit fact. If step 4's reconcile fails to match: `InflowUnmatched` envelope; RemediationCase opened (X3, G6); admin alert. If the EoD overlay corrects the provisional state: `corrects`-bearing envelope; BC2 projection rebuilds.

---

## 9. Gap Log Additions from B4

Two new working assumptions surface at the API-conventions layer. Proposed entries for the Gap Log (continuing the G27–G30 numbering from B3):

| # | Gap | Working Assumption | Status | Resolve By | Blocks |
|---|---|---|---|---|---|
| G31 | AI-agent actor model and MFA-equivalent factor | An AI agent operates as a session-bound service credential derived from an admin user's authority. The agent's session inherits the admin's role assignments and tenant claims. In place of TOTP/SMS MFA (C7) — which a headless agent cannot perform — the session binds to a trust-store-issued client certificate at agent provisioning, and the agent's `actor` on every envelope is `actor_type=admin_user` with `actor_id=<derived-admin-id>`, `session_id=<agent-session>`, and `mfa_assertion_id=<bootstrap-assertion>` minted at agent provisioning with a shorter validity (default 8 hours) than human MFA assertions. Maker-checker (C4, X11) treats the underlying admin id as the actor; an agent cannot be both maker and checker on the same record because both sides resolve to the same `actor_id` | Assumed | Founder + Architect | API authentication layer, BC10 IAM model |
| G32 | Idempotency-conflict semantics on `(actor_id, command_id)` with divergent payload | Same `(actor_id, command_id)` with a different request body is rejected as `409 idempotency_conflict` (§2.4, §4.2). Callers mint a new `command_id` if the divergence is intentional. The handler stores a content hash of the original payload alongside the dedup index entry to detect the conflict cheaply. Same `command_id` with identical payload returns the original `emitted_events` list reconstructed from the audit log | Assumed | Architect | Command-handler dedup implementation, retry libraries on both screens and agents |

These become G31 and G32 in the project Gap Log.

---

## How to use this document

- **B3 → B4 mechanical.** Every command in B3 §2 becomes a command endpoint by §2.1; every aggregate gets a `GET /{aggregate}/{id}` route by §3.1. Per-endpoint design discussion is unnecessary; deviation from the conventions requires an explicit Decision Log entry.
- **AI-agent and screen parity.** Code reviews enforce P3: an endpoint that branches on consumer type is a defect.
- **Audit completeness check.** Per X13 / §7, no command path may return 2xx without an audit envelope having been appended. CI integration tests for command handlers assert envelope-appended-before-response.
- **Architect handoff.** Transport (HTTPS framework, edge gateway, internal mesh), authentication substrate (G31), audit-log substrate (G7, G25), outbox flush mechanism (G27 monolith → broker), and projection store choices are downstream of this document.

Cross-references:
- **Decision Log:** DL-002, DL-005, DL-008, DL-013, DL-017, DL-022, DL-023, DL-030, DL-031–036, DL-040, DL-043–045, DL-049, DL-050.
- **Constraints:** C1, C2, C3, C4, C5, C6, C7, C8, C9, C10, C11, C12, C16, C17, C18, C19, C21, C22, C23, C24, C27.
- **Contexts:** BC1–BC19; conventions apply uniformly across every context.
- **B2 Events:** envelope shape §2.1, idempotency placement §2.4, ordering §5.1, retro events §5.3, authoring discipline §5.6.
- **B3 Aggregates:** §2 command rows (every command becomes an endpoint), invariants (cited in errors by id), cross-aggregate rules X1, X3, X11, X12, X13, X14, X15, X17.
- **A2 Integration Contracts:** §1–§3 (vendor-side semantics that webhook ingress §5 conforms to); §4 (cross-cutting patterns mirrored at the API boundary).
- **Gaps used:** G1, G5, G6, G7, G8, G11, G15, G17, G18, G19, G22, G23, G24, G25, G27, G29.
- **Gaps proposed:** G31, G32.
