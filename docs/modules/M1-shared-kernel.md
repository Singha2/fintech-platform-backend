# M1a · Shared Kernel — value objects, error model & logging

> **Lean module spec** — light tier (see `docs/spec/Spec_Driven_Build_Plan.md` §D).
> Spec before code; invariant test before rule.

| | |
|---|---|
| **Module** | M1 (Shared Kernel & Event Backbone) |
| **Slice** | M1a — money/bps value objects, unified error model, logging + global exception handler |
| **Tier** | Light |
| **Status** | Done (impl + tests green; `/code-review` findings fixed) |
| **Owner** | Amit + Claude |
| **Created** | 2026-06-20 |

## 1. Scope
**Owns:**
- `Money` value object — money as `long` **paise** (BIGINT-compatible), never float.
- `Bps` value object — rates as integer basis points.
- Unified error model — a small domain-exception hierarchy + a single mapping to an HTTP
  response (RFC 7807 `ProblemDetail`).
- Logging baseline — `logback-spring.xml`, per-request correlation id (MDC), and a global
  exception handler that logs every unhandled/handled exception with context.

**Does NOT own (deferred):**
- Idempotency store (`command_id` / `sys_command_log`) → **M1b**.
- In-process event bus (pub/sub) → **M1b**.
- `aggregate_version` optimistic-locking helper → **M1b**.
- Any DB table or migration (M1a is pure application code).

## 2. Upstream dependencies
- M0 schema (V1–V4) — Done. M1a does **not** touch the DB; listed only for context.

## 3. Invariants & rules
- **INV-1** — Money is stored and computed as integer **paise**; there is **no** float/double
  constructor or accessor. _(ref: Constitution / CLAUDE.md "Money is paise (BIGINT)")_
- **INV-2** — `Money` arithmetic (`plus`/`minus`/`times`) throws on `long` overflow rather than
  wrapping silently. _(ref: money-conservation intent, DL-BE-011 §4)_
- **INV-3** — A positive-money context rejects negative amounts (mirrors the DB
  `positive_money_paise` domain). _(ref: `docs/sql` domains; manifest §Notes)_
- **INV-4** — `Bps` is a non-negative integer in basis points; no float. _(ref: "Rates are bps")_
- **INV-5** — Every exception surfaced at the API boundary is logged **with** the request
  correlation id and stack trace, and returned as a `ProblemDetail` (never a raw stack trace to the
  client). _(ref: user goal — fast exception debugging; DoD §F "structured logs")_

## 4. API / type surface
- **Commands (state-changing):** none — pure code slice.
- **Queries (read-only):** none.
- **Types / value objects:** `Money` (factory `Money.ofPaise(long)`, `plus`, `minus`, `times(long)`,
  `isNegative`, `toPaise`, `toString`), `Bps` (`Bps.of(int)`, `toInt`).
- **Error model:** `PlatformException` (base) → e.g. `ValidationException`, `NotFoundException`,
  `ConflictException`; each carries a stable `errorCode`. Mapped centrally to `ProblemDetail`.

## 5. Five non-negotiables — applicability
M1a changes **no state** — it's value objects + cross-cutting plumbing. **All five are N/A here.**
They first bind in M1b (idempotency) and the first state-changing feature module.

| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | no | no commands in this slice |
| 2 | MFA-fresh | no | — |
| 3 | SoD-checked | no | — |
| 4 | Idempotent on `command_id` | no | lands in M1b |
| 5 | Audit-logged envelope | no | lands with M2 / first command |

## 6. Events
- **Publishes:** none (event bus is M1b).
- **Subscribes:** none.

## 7. Test scenarios (write these first)
- [ ] `Money.ofPaise(100).plus(Money.ofPaise(50))` == `Money.ofPaise(150)`.
- [ ] `Money.ofPaise(Long.MAX_VALUE).plus(Money.ofPaise(1))` → throws (overflow), no silent wrap.
- [ ] Positive-money context rejects a negative amount → `ValidationException`.
- [ ] `Money` exposes **no** float/double constructor or accessor (compile-time / reflection guard).
- [ ] `Bps.of(-1)` → rejected; `Bps.of(250)` round-trips to `250`.
- [ ] A thrown `ValidationException` from a test controller → HTTP 400 `ProblemDetail` with the
      `errorCode`, **and** an `ERROR` log line carrying the request correlation id + stack trace.

## 8. Definition of Done (light tier)
- [ ] §7 tests green.
- [ ] `/code-review` on the diff; findings fixed.
- [ ] `DL-BE-012` entry (money/bps modelling + error-model + logging decisions).
- [ ] Status flipped to **Done**.
