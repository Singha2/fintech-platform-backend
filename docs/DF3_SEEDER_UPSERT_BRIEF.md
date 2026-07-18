# BACKEND DEV BRIEF — make `DevDataSeeder` admin seeding idempotent per-email (DF-3)

> **✅ SHIPPED (2026-07-18, DL-BE-087).** `DevDataSeeder` now ensures admins per-email every boot and guards the
> counterparty seed on `sup_account` emptiness; test `DevSeederAdminEnsureTest` green, full suite green. This
> brief is retained as the design record.

> **What this is.** A small **dev-only** change so seed admin accounts added *after* a database was first seeded
> (e.g. `ops2@dev.local`, DL-BE-086) actually **materialize on a pre-existing dev DB** — without a destructive
> wipe. Today `DevDataSeeder` is all-or-nothing empty-guarded, so anything added to the seed list is silently
> skipped on every environment that isn't a fresh schema.
>
> **Why it's needed (business value).** DL-BE-086 added `ops2@dev.local` for the **DOC.3 two-ops maker-checker**
> and shipped "9 tests green" — but those tests run on a *fresh* schema. Every real dev/CI DB seeded before that
> still has 6 admins, so the seeder's empty-guard skips and `ops2` never appears. Result: the **go-live and
> disbursement maker-checker paths can't be exercised** on those databases, and nothing warns you — a false-green
> hole. The only workarounds are wiping the dev DB (losing all onboarded suppliers/buyers/investors/listings) or
> hand-inserting into the auth tables (fragile — this is what unblocked S5 verification manually). This change
> retires both, and future-proofs seed evolution: any account added later lands automatically.
>
> **This is a brief, not a spec.** Take it through the repo loop (`/specify → /clarify → DoR → /plan → /tasks →
> /implement → DoD`). Append `DL-BE-087` (next free id). It touches **no** production code.
>
> **Sibling to** `DEV_SEED_LISTING_HELPER.md` (DL-BE-086) — same `@Profile("dev")` guard, same
> direct-`JdbcTemplate` idiom, same `DevDataSeeder` file.

---

## 0. Scope & non-goals

**In scope:** make the dev **admin** seeding ensure-missing (idempotent per email) so newly-added seed accounts
appear on any dev DB; keep counterparty seeding a one-time operation.

**Non-goals / guardrails:**
- **Dev profile only.** `DevDataSeeder` is already `@Profile("dev")`; `/dev/**` is `permitAll` + 404 in prod. No
  prod path, ever.
- **No change to any real command/service/read.** Only the dev seeder's control flow changes.
- **Raw `JdbcTemplate`** (repo idiom), no JPA.
- **Idempotent.** A second dev boot adds nothing; the manually-inserted `ops2@dev.local` (present on our running
  dev DB) is **adopted, not duplicated** — no duplicate `admin_user` / `auth_identity` / `auth_credential` /
  `admin_role_assignment` rows, unique constraints hold.

---

## 1. The change

File: `src/main/java/com/arthvritt/platform/dev/DevDataSeeder.java`

1. **Extract `ensureAdmin(email, displayName, role)`** — returns an existing `admin_user_id` if a row with that
   email exists, else falls through to the current `seedAdmin(...)`:
   ```java
   private UUID ensureAdmin(String email, String displayName, String role) {
       List<UUID> existing = jdbc.query(
               "SELECT admin_user_id FROM admin_user WHERE email = ?::citext",
               (rs, i) -> (UUID) rs.getObject("admin_user_id"), email);
       if (!existing.isEmpty()) {
           log.info("[dev-seed] admin {} already present — skipping", email);
           return existing.get(0);
       }
       return seedAdmin(email, displayName, role);   // existing method, unchanged
   }
   ```
2. **In `run(...)`, drop the early-return admin guard** and call `ensureAdmin(...)` for **all seven** accounts on
   every dev boot: `super`, `ops`, `ops2`, `treasury`, `treasury2`, `compliance`, `credit`. Capture the
   `super@dev.local` id it returns for use as `assigned_by`/`nominated_by` below.
3. **Guard the counterparty block on counterparty emptiness, not admin count.** Replace the old
   `count(admin_user)` guard with e.g.:
   ```java
   Integer counterparties = jdbc.queryForObject("SELECT count(*) FROM sup_account", Integer.class);
   if (counterparties != null && counterparties > 0) {
       log.info("[dev-seed] counterparties already present — admins ensured, skipping counterparty seed");
       return;
   }
   // else: seedActiveSupplier / seedActiveBuyer / seedActiveAckUser / seedPricingBand / seedActiveInvestor …
   ```
   (`seedAdmin`, `seedActiveSupplier`, etc. are unchanged.) Add `import java.util.List;`.

Net effect: **admins are ensured every boot; counterparties seed exactly once**, as today.

---

## 2. Definition of done
1. On a dev DB that already holds the six pre-`ops2` admins, a restart **creates `ops2@dev.local`** — login
   `POST /auth/login/password {ops2@dev.local, DevPass123!}` → 200 — and leaves the other six untouched (no dup
   rows).
2. On a **fresh/empty** DB, all seven admins **and** the counterparties seed exactly as before (`/dev/seed-info`
   unchanged).
3. **Rebooting twice is a no-op** — no duplicate admins/identities/credentials/role-assignments; unique
   constraints hold.
4. Full suite green; add a test for "admin ensured on a **non-empty** admin table" (the case the old guard
   missed). Prod unaffected (dev bean only, `/dev/**` 404).
5. `DL-BE-087` appended; **DF-3 flipped to ✅** in `PROJECT_TRACKER.md` (§5 Deferred fixes).

---

## 3. Handoff to the front-end (mock repo)
Once shipped, the mock side stops needing the manual `ops2` insert: a plain `./mvnw spring-boot:run
-Dspring-boot.run.profiles=dev` on any dev DB yields the full seven-admin set, so `scripts/e2e/s5golive.mjs`
(DOC.3 two-ops go-live) and the disbursement/distribution maker-checker suites run clean everywhere. Update the
caveat in the mock's `scripts/e2e/README.md` once DF-3 lands.
