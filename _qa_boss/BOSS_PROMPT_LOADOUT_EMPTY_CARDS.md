# P1 polish — `/loadout` cards have ~200px of empty space on mobile (and ~80px on desktop)

Operator-visible blocker for the "fully functional / sellable to customer" quality bar. Screenshots in `_qa_boss/cycle_qa_audit/desktop_loadout.png` and `_qa_boss/cycle_qa_audit/mobile_loadout.png` — each loadout-card on the Discover tab renders:

```
[ Cardboard Connoisseur                                            ]
[   ♥ 35   $20.02                                                  ]
[   by CardKing                                                    ]
[                                                                  ]
[   [empty grey strip with one tiny chevron icon button]           ]
[                                                                  ]
[                                                                  ]   ← ~150-200px void
[                                                                  ]
```

The card LOOKS unfinished — like a placeholder. On mobile especially, scrolling past 5 of these = ~2000px of mostly-empty space, communicating "this site doesn't ship".

## Diagnose

Check `src/main/resources/static/js/csfloat-modals.js` (or wherever `LoadoutDiscoverTab` / loadout-card renders). The card likely has:
- `min-height: 360px` or similar fixed height that doesn't shrink to content
- A footer slot reserved for action buttons that aren't being rendered for anon users (or no public actions at all)
- A loadout-slot grid (e.g. 6 helmet/jacket/pants/etc thumbnails) that's data-driven and silent on empty

## Fix priorities (ship in order)

### 1. Render the loadout's actual slot thumbnails

A loadout IS a collection of slots (Hat / Jacket / Shirt / Pants / Gloves / Boots / Accessories). On the Discover card, render a 6-7 small thumbnails strip (40×40 each) showing the Steam community image of every filled slot, neutral placeholder for empty. This IS the loadout — currently the card shows zero visual evidence that "Cardboard Connoisseur" is a cardboard-themed loadout.

API: `/api/loadouts/{id}` returns `slots: [{ category, listingId, listing: { ... thumbnailUrl ... } }]`. Render thumbnails from `slot.listing.thumbnailUrl` (Steam CDN).

### 2. Compact the card chrome

Current card has the title at H1-ish weight with too much vertical padding. Tighten:
- Card padding: 18px (was probably 24-28px)
- Title size: 18px (was probably 22-28px)
- Heart + price row: 13px, 10px gap below title
- "by USER" line: 12px, var(--ink-3), 6px gap below stats
- Slot thumbnails strip: 40×40, gap 6px, 12px above and 14px below

Target card height: ~140px total at desktop, ~180px at mobile. Currently 280px desktop / 400px mobile — drop ~140px / ~220px respectively.

### 3. Add a discreet action footer

Bottom of card: `[ ↗ Open Loadout ]` ghost-link styled chevron+label pointing to `/loadout/{id}`. Currently there's a single icon button that doesn't even read as clickable. Make the entire card a clickable surface (already may be — verify and ensure pointer cursor + hover lift).

### 4. Verify on mobile + desktop

Re-snap `cycle_qa_audit/desktop_loadout.png` and `cycle_qa_audit/mobile_loadout.png` after the fix:

```bash
node _qa_boss/snap.js https://skinbox.market/loadout _qa_boss/cycle_qa_audit_after desktop
node _qa_boss/snap.js https://skinbox.market/loadout _qa_boss/cycle_qa_audit_after mobile
```

Compare side-by-side. Card heights should be visibly compact and content-rich (slot thumbnails visible).

## Constraints

- Mono-primary palette (no blue introduced for slot bg / borders).
- Use `--bg-2` / `--line` / `--ink-3` for slot frames.
- Don't break anon vs signed-in — anon should still see the card + thumbnails (loadouts are public).

## Acceptance

- [ ] `/loadout` Discover-card height ≤180px mobile, ≤140px desktop.
- [ ] Each card shows ≥1 slot thumbnail (or a clear "empty loadout" badge if zero slots).
- [ ] Card has a single visible click target ("Open Loadout" footer or whole-card click).
- [ ] One commit on main.
- [ ] Container rebuilt + redeployed; public probe 200; zero new errors in logs.

GO. After this, RETURN to the continuous-polish queue (`_qa_boss/BOSS_PROMPT_CONTINUOUS_POLISH.md`) and keep grinding.
