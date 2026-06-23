# sboxmarket e2e (Playwright)

Headless end-to-end customer-flow tests that run against a **live server**.
They are the release gate for "safe for real customers."

## Run

```bash
cd e2e
npm install                 # once
npx playwright install chromium webkit   # once (downloads the browsers)
npx playwright test         # run all (against http://localhost:8082)
npx playwright test --headed # watch it drive a real browser
npx playwright show-report  # open the last HTML report
```

Point it at another environment:

```bash
E2E_BASE_URL=https://skinbox.market npx playwright test   # smoke prod
```

The server must be running first (see the project ops notes: launch the
detached jar on :8082). Auth uses the dev-login endpoint (`auth.setup.js`),
so this targets a dev/staging build — not a real Steam login.

## What's covered (20 specs)

| Project | Browser | Specs |
|---|---|---|
| `anon` | Chromium | home, market grid, item detail, /db, wallet auth-gate centered, search, nav chrome, deep-route OG shell |
| `chromium-auth` | Chromium | buy-now confirm (fee math + total), add-to-cart → checkout, bargain validation, sell list 2% fee math, wallet deposit + withdraw |
| `mobile-auth` | **WebKit / iPhone 13** | home / market / item — zero horizontal overflow + bottom-nav present |

`auth.setup.js` dev-logs-in once and saves `storageState` to `.auth/user.json`,
reused by the signed-in projects. Workers are serial (the dev money flows share
one user's session/cart).

## Conventions

- `*.anon.spec.js` → runs signed-out (no auth dependency).
- `*.mobile.spec.js` → runs on the WebKit iPhone project.
- everything else → runs signed-in on Chromium desktop.
- Money flows never actually move money (dialogs are opened + cancelled, forms
  are filled + asserted, never submitted). Cart state resets via `DELETE /api/cart`.
