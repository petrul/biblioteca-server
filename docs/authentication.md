# Authentication (Google One Tap)

Readers sign in with Google One Tap in biblioteca-reader; this server owns
the resulting session. Google proves who the reader is **once**; after that
the reader stays signed in through this server's own session cookie, for
30 days, without asking Google again.

## The sign-in flow

1. **Prompt (reader).** biblioteca-reader loads Google Identity Services
   with the OAuth client id `GOOGLE_OAUTH_CLIENT_ID` (mapped to the reader's
   build-time `VITE_GOOGLE_CLIENT_ID`) and shows the One Tap prompt only when
   `GET /api/users/me` says the visitor is not signed in. The reader's own
   module is `src/services/googleOneTapAuth.ts`.
2. **Credential (Google -> reader).** On consent Google hands the reader an
   ID token (a signed JWT, valid about an hour). The reader's same-origin
   proxy posts it here as the form field `credential`:
   `POST /api/auth/google` (`application/x-www-form-urlencoded`).
3. **Verification (server).** `GoogleAuthService` verifies the token
   (signature, audience = `GOOGLE_OAUTH_CLIENT_ID`, verified email) and finds
   the account by its Google `sub`, else links an existing account with the
   same email, else creates one. The profile photo comes from the token's
   `picture` claim when present.
4. **Session (server).** `GoogleAuthController` stores a Spring Security
   context in the servlet session and answers with a redirect plus
   `Set-Cookie: JSESSIONID=...`. The reader's proxy relays that cookie to
   the browser, scoped to the reader's own origin.
5. **Every later request.** The browser sends `JSESSIONID` to the reader,
   whose proxy forwards it here; `GET /api/users/me` returns
   `{"authenticated": true, "username": ..., "isAdmin": ...}`. Administrators
   are the emails listed in `ADMIN_USERS`.

## Why the sign-in lasts 30 days

`SessionConfig` replaces Tomcat's defaults (30-minute idle timeout, a cookie
that dies with the browser, sessions kept only in memory):

| Setting | Value |
|---|---|
| Session idle timeout | 30 days |
| `JSESSIONID` cookie | `Max-Age` 30 days, `HttpOnly`; `Secure` when `biblioteca.session.cookie-secure=true` |
| Persistence | written to `${work.dir}/sessions` on a graceful shutdown, reloaded on start |

So a session survives closing the browser, a reader or server restart, and a
redeploy - as long as `WORK_DIR` is on persistent storage (it is a volume in
prod) and the server stops gracefully (`docker stop`, not a kill). A leading
`~` in `WORK_DIR` (zmeu's dev profile uses `~/.biblioteca`) is expanded at
startup by `WorkDirTilde`, for this and every other `${work.dir}` path.

## Sign-out and profile photo

- **Sign-out:** the reader posts to its `/api/auth/logout`, which calls this
  server's Spring Security `POST /logout` (invalidates the session and clears
  the cookie). The reader also disables Google auto-select, so the next visit
  does not silently sign back in.
- **Profile photo:** when the ID token has no `picture`, the reader may ask
  Google for an access token and send it to `POST /api/auth/google/profile`;
  the server reads the photo from Google's userinfo endpoint for the signed-in
  account only.

## Configuration and troubleshooting

- `GOOGLE_OAUTH_CLIENT_ID` must equal the reader's client id; unset disables
  Google sign-in. Every origin serving the reader must be listed under the
  OAuth client's *Authorized JavaScript origins*.
- One Tap reappearing on every visit means the session is gone: check the
  `JSESSIONID` cookie has a `Max-Age`, and that `${work.dir}/sessions` is on
  persistent storage.
- Google rate-limits the prompt after a reader dismisses it (an increasing
  cooldown) and can skip it when FedCM fails in the browser; the reader's
  explicit "Continue with Google" button always works.

## Username/password accounts

Besides Google, an account can be created with `POST /api/users/register` and signed in through Spring Security's session-based `formLogin` (`POST /login`). A Google-only account has no local password. Google sign-in stays disabled until `GOOGLE_OAUTH_CLIENT_ID` is set - deliberately no guessed default.
