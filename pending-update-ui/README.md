# pending-update-ui

Minimal Angular UI for the pending-update status (spec 04) and AI summary (spec 05). Standalone components, signals, OnPush, strict TypeScript, plain CSS ported from the mockups. Angular 21 (CLI 21; CLI 22 needs a newer Node than 24.11).

## Run

Start the Java API on `localhost:8080`, then:

    npm install
    npx ng serve        # http://localhost:4200, /api is proxied to http://localhost:8080 (proxy.conf.json)

## Dev identity headers (DEV ONLY, NOT AUTH)

`src/app/dev-identity.interceptor.ts` adds `X-Dev-Firm` and `X-Dev-User` to every `/api/v1` request. They are a development stand-in with no proof of identity. Values come from `src/environments/environment.ts` (default `firm-demo` / `demo.user`); change them there to match the backend demo seed firm. Remove the interceptor before any real deployment.

## Test and build

    npx ng test --watch=false     # Vitest + jsdom (Angular CLI default), no browser needed
    npx ng build

Tests use `HttpTestingController` (no backend) and include axe-core smoke checks (colour contrast is not checked under jsdom).

Last run (2026-11-01): `npx ng test --watch=false` 35 passed, 0 failed (5 files); `npx ng build` succeeded, 260.35 kB initial (70.72 kB transfer).

## Behaviour notes

- Status is a discriminated union (`up-to-date | update-pending | unknown`). Errors, unrecognised values and a pending row missing its versions all render as unknown or an error, never "up to date" (FR-24, FR-25).
- `404 FEATURE_OFF` renders nothing (NFR-41). The list is paged with the server `nextToken` (NFR-14).
- Filter counts come from server `totals`; the filter only narrows the page already loaded and is labelled so.
- A READY summary always shows the constant "AI-generated" marker (FR-43); the server `label` is shown only as extra text, never as the sole marker.
- W5: with the flag off, the list renders nothing, but an EF detail shows "not found". This is by design: the server answers 404 NOT_FOUND for a flag-off detail, identical to a foreign or unknown EF (NFR-33 indistinguishability).
- `422 STALE_OR_NOT_PENDING` on accept/decline (the pending target changed or the EF is no longer pending): the page shows "This update changed. Reloading the current status.", re-reads the row with the authoritative GET and shows it. It says "up to date" only if the re-read row says so; if the re-read fails it shows the unavailable state. `422 UNKNOWN_VERSION` keeps its own message and the row.
- With a status filter active the list says the filter applies to the loaded page only; an empty filtered page with a nonzero total and a next page says "No rows of this status on this page. N in total; use Next to look further."
- Server text is rendered by interpolation only, never `innerHTML`.

## Left out

- Mockup toolbar simulations (publish, withdraw, retry, state switcher)
- Decision log / decision history
- Server-side filtering
- Count of versions in the cumulative range
- Withdrawn-version handling
- Real authentication
