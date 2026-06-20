# M<n><x> · <Module / Slice name>

> **Lean module spec** — the per-module unit of our spec-driven loop (see
> `docs/spec/Spec_Driven_Build_Plan.md` §D, run at the *light* tier).
> One page. Fill only what applies; delete rows that don't. Spec before code; invariant test before rule.

| | |
|---|---|
| **Module** | M<n> (<BC / area>) |
| **Slice** | M<n><x> — <what this cut delivers> |
| **Tier** | Light · / Heavy |
| **Status** | Draft / In progress / Done |
| **Owner** | Amit + Claude |
| **Created** | YYYY-MM-DD |

## 1. Scope
**Owns:** <tables / types / packages this slice is responsible for>
**Does NOT own (deferred / other module):** <explicit out-of-scope, with where it lands>

## 2. Upstream dependencies
- <module/stub> — Done / Stubbed / N/A

## 3. Invariants & rules
Each rule cites its source in the corpus (`DL-0xx` / `C1–C28` / `G…` / `DL-BE-xxx`).
- **INV-1** — <rule>. _(ref: …)_
- **INV-2** — <rule>. _(ref: …)_

## 4. API / type surface
- **Commands (state-changing):** <name(args) → result> — _none for a pure/read slice_
- **Queries (read-only):** <name(args) → result>
- **Types / value objects:** <Money, Bps, …>

## 5. Five non-negotiables — applicability
Mark each. For a pure-code or read-only slice, **all are N/A** — say so explicitly; that's the leash.

| # | Control | Applies? | How / where |
|---|---|---|---|
| 1 | Maker-checker | yes/no | |
| 2 | MFA-fresh | yes/no | |
| 3 | SoD-checked | yes/no | |
| 4 | Idempotent on `command_id` | yes/no | |
| 5 | Audit-logged envelope | yes/no | |

## 6. Events
- **Publishes:** <event> — payload <shape> — _none yet / N/A_
- **Subscribes:** <event> — _none / N/A_

## 7. Test scenarios (write these first)
- [ ] Happy path: <…>
- [ ] Invariant violation: <…> → rejected
- [ ] <maker-checker reject / idempotent retry / SoD block — only if §5 applies>

## 8. Definition of Done (light tier)
- [ ] §7 tests green (invariant tests prove the rule fires).
- [ ] `/code-review` on the diff; findings fixed.
- [ ] `DL-BE-xxx` entry added for any non-obvious decision.
- [ ] This spec flipped to **Status: Done**.
