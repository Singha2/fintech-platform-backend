# BACKEND BRIEF — expose `POST /auth/logout` (server-side session revoke)

> **What this is.** A **thin HTTP endpoint** over logic that already exists: `SessionService.revokeSession(...)`
> flips `auth_session.status='revoked'` (and audits `auth.Session.Revoked`), and `SessionService.resolve` already
> returns `terminated("revoked")` for a revoked session — so a revoked bearer 401s on the next request. There is
> just **no controller** exposing it. Add `POST /auth/logout`.
>
> **Why it's needed (business value).** Today the UI's "Log out" only clears the bearer **client-side**; the token
> stays **valid on the server** until its idle/absolute expiry. On a shared QA/demo machine — or for any real
> pilot user — a "logged out" session is still usable by anyone who kept the token. Proper logout must **terminate
> the session server-side** so the bearer stops working immediately. The domain already supports this; we're only
> missing the ~6-line endpoint.
>
> **This is a small, additive brief** — no migration, no new domain logic. Take it through the repo loop; append
> `DL-BE-089`. Sibling to the other `*_BRIEF.md` handoffs.

---

## 1. The change

- **Endpoint:** `POST /auth/logout` — authenticated-only (any live bearer), **not** under the `/auth/login`
  open group. The natural home is `SessionController` (it already resolves `@AuthenticationPrincipal AuthSession`
  for `GET /auth/session`).
  ```java
  @PostMapping("/auth/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(@AuthenticationPrincipal AuthSession session) {
      sessions.revokeSession(session.sessionId());   // existing method; audits auth.Session.Revoked
  }
  ```
- **Idempotent:** `revokeSession` is already a no-op on a missing/already-terminal session, so a double-logout (or
  a logout on an about-to-expire session) returns 204 cleanly — no error.
- **No body, no envelope** — this is a session-lifecycle action, not a domain command (mirrors how `GET
  /auth/session` sits outside the `CommandGateway`).

---

## 2. Definition of done
1. A logged-in caller `POST /auth/logout` → **204**; the **same bearer** then `GET /auth/session` → **401**
   (session terminated), and any command with it → 401.
2. **Idempotent:** a second `POST /auth/logout` with the (now revoked) bearer → still clean (204 or 401 per the
   security filter's order — no 500).
3. The `auth.Session.Revoked` audit event is emitted (already wired in `revokeSession`).
4. Works for **both** kinds — admin and investor sessions.
5. Catalogue row added under `## Authentication`; `DL-BE-089` appended; no migration.

---

## 3. Handoff to the front-end (mock repo)
Already wired **best-effort**, so it starts working the moment this ships — no further UI change needed:
- `src/api/services/auth.js` → `logoutSession(bearer)` = `POST /auth/logout`.
- `AuthContext.logout()` clears local state immediately, then calls `logoutSession(capturedBearer)` and ignores
  failure (so today's 404 is harmless; once the endpoint exists it becomes a real server revoke).
- The TopBar **Log out** button (live mode) drives it.
