# ADR-001 · Deferred: a data-driven permission layer (PBAC) beneath roles

| | |
|---|---|
| **Status** | **Accepted — deferred** (not built; documented so the current model is a recorded decision, not drift) |
| **Date** | 2026-07-11 |
| **Owner** | Amit + Claude |
| **Refs** | C18, DL-032 (role-as-permission), [[DL-BE-063]]/[[DL-BE-064]] (role assign + SoD seed), [[DL-BE-065]] |
| **Supersedes** | nothing — first ADR |

## Context

The current admin authorization model (BC10 RBAC, C18) has **no permission primitive**. A *role* is the only
authorization unit: each state-changing command declares a `requiredRoles` set in Java, and
`CommandGateway.execute` admits the actor iff they hold one of those roles (resolved from
`admin_role_assignment`). There is no permission entity, no `role_permission` mapping, and no resource/row-level
scoping (a `tenant_claims` placeholder exists on the session but is `TenantClaims.empty()` — deferred to a later
milestone).

```
Current:   command (resource+action)  ─────────────────►  ROLE  ─────►  user
                                          (role IS the permission unit — no layer in between)

Standard PBAC (the target):
           resource + action  ─►  PERMISSION  ─►  ROLE (= a bundle of permissions)  ─►  user
```

This is a deliberate Phase-1 simplification (DL-032), appropriate for **5 fixed, internal roles**. Its limits:

- A role's capabilities can only change by **editing code and redeploying** (the `requiredRoles` constants).
- New roles require a **migration** (`ALTER TYPE admin_role ADD VALUE`) **plus** code wiring.
- Roles cannot be **composed or defined as data** (e.g. by an operator, or per-tenant).

## Decision

**Do not build a permission layer now.** With five fixed roles and no need to reshape capabilities without a
deploy, a permission layer would be speculative. **Record** the target model and migration path here so the
current design is an explicit, revisitable choice, and adopt it only when a concrete trigger appears (below).

## Why this is cheap to add later (the codebase is well-positioned)

1. **Single enforcement chokepoint.** All authorization runs through one method — `CommandGateway.execute` —
   against one interface, `ActorAuthorization`. The check changes in exactly one place, behind an interface, so
   controllers and handlers are untouched.
2. **Every command already has a stable identifier** — its `commandType` (e.g. `listing.approve_go_live`,
   `disbursement.approve`, `admin_iam.Role.Assign`). That string is a ready-made **permission key**; no new
   identifiers need inventing.

## Target model

Two new tables; the role→command binding moves from code into data.

| New table | Holds |
|---|---|
| `sys_permission` | the catalog — one row per resource+action, keyed by `commandType` (e.g. `listing.approve_go_live`) |
| `role_permission` | the composition — `(role, permission_key)`; **a role becomes a set of permissions, as data** |

- Commands declare a **required permission** (their `commandType`) instead of a `requiredRoles` set.
- `ActorAuthorization` gains `activePermissions(actor)` = the union of `role_permission` over the actor's active
  roles. The gateway checks `requiredPermission ∈ activePermissions`.
- **SoD is untouched.** It keeps operating on *roles* (which roles may be co-held); assignment still grants
  *roles*. Only the role→action binding becomes data.

## Migration path (incremental, backward-compatible — no big-bang)

The current in-code role→command mapping (see the table in `MANUAL_TESTING`/the `requiredRoles` constants) **is
the seed data**, so step 2 reproduces today's behavior exactly — zero behavior change on adoption day.

1. Add `sys_permission` + `role_permission` via migration.
2. Seed them from the current in-code mapping → identical enforcement, now data-driven.
3. Add `activePermissions()` to `ActorAuthorization`; switch `CommandGateway` to check a permission.
4. Flip commands `requiredRoles` → `requiredPermission` one at a time (each a one-line change at the command).
5. When the last command flips, delete the hardcoded `Set.of(AdminRole…)` constants — roles are now pure data
   compositions. Composing/reshaping a role is then `INSERT`/`DELETE` on `role_permission`, no redeploy.

## Triggers — adopt when any of these becomes real

- A role's capabilities must change **without a deploy**.
- **Operator- or tenant-defined** roles are required.
- The role count / granularity grows past what 5 hardcoded sets can express cleanly.

## Open decisions (defer to adoption time)

- **Roles are a Postgres `ENUM` (`admin_role`).** Data-driven *permissions* do **not** require changing that. But
  adding *new roles as data* (no migration) needs promoting `admin_role` from an enum to a `role` table (FK from
  `admin_role_assignment.role`). That is a separate, larger call — do the permission layer first; leave roles as
  an enum until tenant/operator-defined roles are actually needed.
- **One source of truth.** During the flip both code and data describe the mapping. Add a startup/CI check
  asserting every `commandType` has a `sys_permission` row, so the catalog cannot drift from the commands.

## Consequences

- **Now:** none — behavior and schema unchanged. This ADR only records intent.
- **Later, once adopted:** role capabilities and role composition become configuration (data), not code; SoD and
  assignment semantics are preserved; the change is additive and localized to the gateway + `ActorAuthorization`.

## Explicitly out of scope

Resource/row-level (data) authorization — e.g. "this ops_executive may only act on *these* listings". That is a
separate axis (the `tenant_claims` placeholder), independent of this permission layer, and is not addressed here.
