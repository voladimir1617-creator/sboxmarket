# SkinBox Inventory Valuer

Cross-browser extension (Manifest V3) that values your s&box Steam inventory
side-by-side against the SkinBox marketplace.

## Supported browsers

- **Chrome** ≥ 88
- **Edge** ≥ 88
- **Brave** ≥ 1.20
- **Opera** ≥ 74
- **Firefox** ≥ 109 (via `browser_specific_settings.gecko`)
- **Vivaldi** (Chromium-based, same as Chrome)

Any Chromium-based or Gecko-based browser that understands MV3 is covered.

## Install (unpacked — for local dev)

1. Open `chrome://extensions` (or `edge://extensions`, `brave://extensions`,
   `about:addons` on Firefox).
2. Turn on **Developer mode** / **Debug add-ons**.
3. Click **Load unpacked** (Chromium) or **Load temporary add-on**
   (Firefox) and pick the `extension/` folder from this repo — or the
   `skinbox-valuer-v1.0.0.zip` artifact.
4. Visit your Steam inventory page — a floating panel docks to the
   bottom-right and starts pricing items.

## Install (published — for end users)

- **Chrome Web Store**: [pending submission]
- **Microsoft Edge Add-ons**: [pending submission]
- **Firefox AMO**: [pending submission]

Submissions use `skinbox-valuer-v1.0.0.zip` as the upload artifact. Edge
and Firefox accept the same CRX package shape since MV3 is a shared
standard, but Firefox additionally honors the
`browser_specific_settings.gecko` block in the manifest for ID + minimum
version enforcement.

## What it does

- Reads the public inventory JSON for the profile you're viewing
  (appid `590830`).
- For each unique item, looks up:
  - Steam Community Market `priceoverview` (public endpoint, rate-limited
    to ~1 req/s — the extension throttles at 600 ms to stay under).
  - SkinBox `/api/listings?search=<name>` for the lowest live listing
    (handles both the bare-array and wrapped-object response shapes).
- Renders a total: **Steam Market** vs **SkinBox**, plus absolute and %
  delta so you can see at a glance where you'd get more.
- Lists the top 20 biggest-value items for drill-down.

## What it does NOT do

- No auth, no Steam credentials, no session cookies leave the browser.
- Nothing is sent to SkinBox until the user chooses to list an item.
- The `host_permissions` list is the **only** network reach the extension
  has. It lists steamcommunity.com, steamcommunity-a.akamaihd.net (for
  item images), and the SkinBox origins.
- Zero trackers. No analytics. No telemetry.

## Security

- Content script has no access to `chrome.*` / `browser.*` APIs beyond
  `fetch` and DOM — so it doesn't need a browser-polyfill.
- Runs at `document_idle` only on the two inventory URL patterns.
- Uses `all_frames: false` so it never injects into nested iframes.
- CSP-friendly: all styles live in `content.css`, no inline styles.

## Files

```
extension/
├── manifest.json          ← MV3 with firefox gecko settings
├── content.js             ← inventory scrape + price lookup + UI
├── content.css            ← floating panel styling
├── popup.html             ← toolbar popup (dark theme to match site)
├── icons/
│   ├── icon16.png
│   ├── icon48.png
│   └── icon128.png
└── README.md              ← this file
```

## Release checklist

- [x] MV3 manifest
- [x] Firefox `browser_specific_settings.gecko.id` + `strict_min_version`
- [x] Minimal permissions (just `storage` + host-list)
- [x] Icons at 16/48/128 px from the branded logo
- [x] Popup in dark theme, matches main site
- [x] Zipped `skinbox-valuer-v1.0.0.zip` artifact
- [ ] Chrome Web Store listing written
- [ ] Microsoft Edge Add-ons listing written
- [ ] Firefox AMO listing written
- [ ] Privacy policy URL (use `/legal/privacy.html` on skinbox.example)
- [ ] Support email (support@skinbox.example)
- [ ] Screenshots: panel open on a real Steam inventory page
