# Is there a market where this code works?

Measured 2026-09-01 against Steam's own endpoints, run through the shipped fee
code (`TradeService:539`, `PlatformLedgerService`, `application.yml:370-378`).
Method, controls and the four defects found mid-run are in `METHOD.md`.

**Verdict: the arithmetic destination is real — but the port does not reach it.**

CS2 needs only ~0.2% of its trades to cover a $2,500/month cost base. So the
honest answer is *not* "no market exists". It is: the market exists, the code
is good, and what stands between them is a per-asset pricing pipeline, payments
underwriting, a host, and liquidity — the last of which killed four funded
competitors in the last 14 months. **s&box itself is closed beyond argument:
it would take 383% of the entire market to cover a $500/month host.**

---

## 1. The assumption that fails

The premise behind "point it at a bigger appid" is that s&box's problem is size.
It is not. Average platform take per transaction, shipped fee code, 2026-09-01:

| market | items | standing listings | book value | **avg take / listing** | listing median | earns $0.00 |
|---|---|---|---|---|---|---|
| **s&box** | 201 | 11,027 | $35,070 | **$0.0635** | $0.66 | 8.5% |
| Rust | 5,438 | 297,684 | $690,719 | $0.0457 | $1.11 | 30.3% |
| Unturned | 9,174 | 211,603+ | $257,308 | $0.0236 | $0.03 | 91.0% |
| CS2 | 35,389 | 25,731,702 | $28,692,788 | $0.0215 | $0.09 | 82.9% |
| Steam cards | 321,473 | 12,690,438+ | $5,712,293 | $0.0079 | $0.05 | 93.7% |
| TF2 | 41,414 | 7,402,454 | $2,430,467 | $0.0059 | $0.14 | 96.2% |
| Dota 2 | 34,158 | 8,288,649 | $1,990,153 | $0.0042 | $0.03 | 95.9% |
| PUBG | 357 | 1,738,371 | $201,006 | $0.0013 | $0.06 | 94.1% |
| Killing Floor 2 | 2,896 | 1,863,884+ | $88,712 | $0.0004 | $0.03 | 99.3% |
| PAYDAY 2 | 3,454 | 4,592,937+ | $191,614 | $0.0002 | $0.03 | 99.9% |
| Don't Starve Together | 438 | 1,901,133 | $73,776 | $0.0001 | $0.03 | 99.7% |

s&box, PUBG and DST are exact full censuses; CS2, Rust, TF2 and Dota 2 combine a
systematic price-rank sample with an exact capture of their ~300 deepest books.
`+` marks the four where no deep-book head was captured, so the listing count is
a **lower bound** and the true avg take is lower still.

**CS2 — the richest skin economy in the world — earns this platform 66% LESS
per standing listing than s&box does.** Large Steam markets are large because
they contain millions of near-worthless items, and an ad-valorem 2% on a $0.03
sticker rounds to $0.00 (`TradeService:539` is HALF_UP, so anything priced under
$0.25 earns exactly nothing).

Across the eleven markets, take per listing correlates negatively with listing
count. The direct observation is firmer than the correlation: **the market with
the highest take per listing is the smallest one measured, and it is the one
this codebase already serves.**

## 2. So the only thing a bigger market buys is transaction COUNT

And count converts to revenue only through share. Measured on **actual trades**
(Steam's 24h `volume`), not on the standing book — the standing book is clogged
with penny items that never trade, so "revenue if every listing sold once" is
the wrong denominator in both directions.

| measured 2026-09-01 | s&box | TF2 | Rust | Dota 2 | CS2 |
|---|---|---|---|---|---|
| whole market does | **156 trades/day** | 109,517 | 30,090 | 160,974 | 592,471 |
| Steam GMV / year | **$75,566** | $15.4M | $36.1M | $67.6M | ~$700M |
| take per ACTUAL trade | $0.0275 | $0.0064 | $0.0666 | $0.0215 | $0.0640 |
| revenue at 2%, **100% share** | $1,565 | $256,974 | $731,425 | $1,261,098 | $13,842,702 |
| share for a $500/mo cost base | **impossible** | 2.33% | 0.82% | 0.48% | **0.04%** |
| share for a $2,500/mo cost base | **impossible** | 11.67% | 4.10% | 2.38% | **0.22%** |
| share for a $10,000/mo cost base | **impossible** | 46.70% | 16.41% | 9.52% | **0.87%** |

90% bootstrap CIs on GMV: TF2 $7.5M-$24.4M, Rust $22.5M-$53.4M,
Dota 2 $31.2M-$110.4M, CS2 $377M-$1.08B, s&box $37k-$122k.

**s&box is closed beyond argument, and the robust form of that does not rest on
the GMV estimate at all — only on the trade count.** At Steam's own 15% rate,
with 100% share, 156 trades/day of a $1.61 median item is **$13,666/year**. No
take rate turns 156 trades a day into a business.

**CS2 has by far the most reachable arithmetic: 0.22% of its trades covers a
$2,500/month cost base.** That estimate is fragile — half of it rests on 4 of 54
sampled rows — but the conclusion is not: trimming the outliers that drive it
moves the required share only from 0.22% to 0.45%. Rust needs 4.10%, twenty
times harder than CS2 but still not absurd.

Share requirements above are against *Steam* GMV only. Third-party standing
inventory for Rust is comparable to Steam's (Skinport 121,020 + Rust.tm 41,400
+ DMarket 39,691 + Tradeit 19,964 ≈ 222k against the 268,016 Steam listings
measured here), so a fair total-addressable figure roughly halves them — Rust
would need ~2% of everything for a $2,500/mo cost base rather than 4.10%, and
CS2 ~0.1% rather than 0.22%. That cuts both ways: it also means the incumbents
this would take share *from* are larger than the Steam figures alone suggest.

## 3. Why share is the thing that cannot be manufactured

This platform would be the **cheapest cash exit in the industry**: 2% seller
plus Stripe at cost leaves a seller $97.50 on a $100 sale, against CSFloat ~$96,
Skinport $92, marketplace.tf $90, Steam $85 *and no cash exit at all*.

Price is not the axis this market competes on. Skinport charges **four times
CSFloat's seller fee** (8% against 2%, and twice its all-in rate) and is
nonetheless the **largest** third-party Rust venue, at 98.8% catalogue coverage.
If fee level decided share, that could not be true.

Meanwhile the sector is contracting — four exits in 14 months:

| venue | when | stated reason |
|---|---|---|
| LootBear | May 2025 | closed, absorbed into Tradeit |
| SkinBid | Nov 2025 | bankruptcy (Danish law); assets to Skinport Jan 2026 |
| GamerPay | May 2026 | "low traffic and an unprofitable fee model" |
| BitSkins | Jun 2026 | "the business has become loss-making" — VAT, regulators, Valve API. **Served Rust and Dota 2.** |

Valve's 2025-10-22 CS2 trade-up update removed an estimated $1.75-2B from the
skin market. The binding constraints these operators named — traffic, VAT,
regulators, Valve's API — are all things a new entrant has *less* control over,
and none of them is a code problem.

## 4. The chargeback floor

At a 2% take the model's tolerance for disputes is thin, and it depends
entirely on deposit size and how often a balance changes hands.
Break-even chargeback rate, per deposit (Stripe $15 dispute fee + value extracted):

| deposit | 1 turn | 2 turns | 5 turns |
|---|---|---|---|
| $10 | 0.80% | 1.60% | 4.00% |
| $50 | 1.54% | 3.08% | 7.69% |
| $100 | 1.74% | 3.48% | 8.70% |

High-risk digital goods run 1-3%+. So 2% is survivable **only** with a
meaningful minimum deposit and repeat trading — a product constraint that has
to be designed in, not an assumption. (Priced per *transaction* rather than per
deposit — one deposit per trade — the break-even rate is 0.27-0.39% and the
model does not survive at all. The per-deposit figures above are the fair ones.)

## 5. The candidate set is closed

40 appids probed. Beyond the markets tabulated, every plausible alternative
returns **zero** Steam market items: Deadlock, Rocket League, Brawlhalla,
Destiny 2, Trove, Crossout, Neverwinter, PlanetSide 2, Path of Exile 2,
Warframe (10 items), Path of Exile (10), Tower Unite, Aura Kingdom, ARK, DayZ,
R6 Siege, Apex, Halo Infinite, GTA V, Garry's Mod, Left 4 Dead 2 and others.
Only Artifact (237 items, dead game) turned up. **There is no undiscovered
destination.**

Structurally excluded on top of that:
- **PUBG** — Krafton disabled peer-to-peer item trading in May 2018; the
  357-item catalogue *is* the restriction.
- **Steam community items (753)** — 321,473 items, but Wallet credit only;
  every cash-out route is grey-market.
- **TF2** — barter-denominated in keys and refined metal, and Refined Metal is
  **not marketable on Steam at all**, so the unit of account cannot itself be
  transacted. Free-to-play accounts cannot trade without a store purchase. Its
  catalogue is the largest of any game (41,414 items) and almost all of it is
  dead: in the turnover sample, **34 of the 36 highest-priced items had zero
  sales in 24 hours**, and a single item (Winter 2025 Cosmetic Case) holds
  2,302,127 standing listings at $0.03. TF2 is one liquid instrument — the
  Mann Co. key — plus 41,000 rows that do not move. It needs the largest share
  of any candidate, 11.67%, to cover a $2,500/month cost base.

## 6. The real choice is a trade-off, and neither side wins

| | CS2 | Rust |
|---|---|---|
| share needed for $2,500/mo | **0.22%** | 4.10% |
| does the codebase fit? | **no** — needs float/pattern/sticker per asset | **yes** — no float, no pattern, name-level median is correct |
| incumbent floor on price | 2% (CSFloat, cs.deals, DMarket) — **no wedge** | 5-8% (DMarket 5%, Skinport 8%) — a real wedge |
| live competitors | 20+ | 7 |

**CS2 has the arithmetic but not the product; Rust has the product but needs
nearly twenty times the share.** That is the whole finding in one line.

Dota 2 sits between them and is worse than both on balance: 2.38% share needed,
no float or pattern so the pricing model fits — but a $0.03 listing median with
**95.9% of its listings earning exactly $0.00**, a three-month lock on items
from a Treasure, and the same generalist incumbents (DMarket, Skinport, Buff163,
Tradeit, Mannco). It buys the worst of both columns.

Rust is the better *test* if one is run, because it is the only market where
this codebase's pricing model is actually right: Rust skins have no float and
no pattern, and scarcity is manufactured by a weekly limited-run store rotation.
The sibling repo measured that the same name-median approach is wrong for CS2 by
multiples (Doppler Emerald 3.16x the name median, Case Hardened blue 2.72x).
Incumbents also price Rust *higher* than CS2 — DMarket charges 5% for Rust
against 2% for CS2 — leaving room underneath that CS2 does not have.

What would have to be true for Rust to pay:
1. ~1,234 trades/day, ≈4% of the whole Rust market (≈2% if off-Steam volume is
   comparable to Steam's, which its standing inventory suggests).
2. Average deposit ≥$50 with ≥2 balance turns, or chargebacks eat the 2%.
3. Inventory to seed the book. The operator holds 620 **CS2** items (~$1,595) —
   not Rust.
4. Stripe underwriting for a high-risk category, and a host. Both absent today.

Items 3 and 4 are the cold-start problem, and no amount of code solves them.
They are also precisely what GamerPay ("low traffic") and BitSkins ("the
business has become loss-making") failed on with more capital than this has.

## 7. What the port would actually cost

Small, and that is not the constraint:

| site | change |
|---|---|
| `SteamInventoryService.groovy:27,28` | `SBOX_APP_ID`, `CONTEXT_ID` → config (used at `:236`, `:347`) |
| `SteamMarketPriceService.groovy:27` | `SBOX_APP_ID` → config (used at `:351`) |
| `static/js/primitives.js:122`, `modals.js:755` | hardcoded `market/listings/590830/` deep links |
| `steam-bot/index.js:39,94` | **already configurable** via `STEAM_BOT_APP_ID` |
| `SeedService.groovy:813-844` | catalogue re-seed for the new game |
| templates + js | 48 user-visible "s&box" strings — a rebrand |

Context 2 is right for CS2/Rust/Dota 2/TF2; appid 753 would need context 6.
Call it a day of work. The delivery/escrow/bot services reference 590830 only
in comments — they take asset ids and delegate.

---

## 8. Incumbent picture

Steam itself takes **15%** (5% Valve + 10% publisher, each leg rounded down with
a $0.01 minimum) and **Steam Wallet funds cannot be converted to cash** — the
Subscriber Agreement is explicit. That prohibition is the entire reason
third-party venues exist, and it is what they are really selling.

What a seller keeps on a $100 sale, seller fee + withdrawal:

| venue | seller | withdrawal | games |
|---|---|---|---|
| **this codebase** | **2%** | Stripe at cost (0.25%+$0.25) → **$97.50** | s&box only |
| CSFloat | 2% | 2% (measured from their API) | CS2 |
| cs.deals | 2% (their `/config`) | — | CS2 |
| DMarket | **2% CS2 / 5% Rust & others** | 3% | CS2, Rust, Dota 2, TF2 |
| Mannco.store | 5% | 3% (unverified) | 730, 440, 570, 252490, **753** |
| white.market | 5% | 3%, crypto only | CS2 |
| market.csgo | 5% | 5% | CS2 |
| Waxpeer | 6% | 1% | CS2, Rust |
| Skinport | 8% (was 12%) | 0% | CS2, Rust, Dota 2, TF2 |
| marketplace.tf | 10% | 0%, PayPal | TF2 |
| Tradeit | 8.5-13% | 2% | CS2, Rust, Dota 2 |
| SkinBaron | 15% | 3% | CS2 |

**Price is not the axis this market competes on.** Skinport charges four times
CSFloat's rate and is nonetheless the largest third-party Rust venue at 98.8%
catalogue coverage. A 2% platform would be the cheapest cash exit in the
industry and that, by itself, buys nothing.

Rust specifically has seven live venues — Skinport (121,020 Rust listings),
Rust.tm (dedicated, 41.4k), DMarket (39,691), Tradeit (19,964), Waxpeer,
Mannco, SkinBaron. TF2's cash marketplace is marketplace.tf; backpack.tf is a
price *index* that takes 0% and monetises a $6.99/mo subscription; scrap.tf is
a barter bot dealer with no cash payouts.

## 9. Why CS2 — the one market with reachable arithmetic — still fails here

1. **No price wedge.** CSFloat, cs.deals and DMarket already charge 2% on CS2.
   This platform's 2% is parity with the floor, not an undercut.
2. **The data model is fungible-by-name.** `Item` carries name/category/rarity/
   `supply`/`totalSold` and no per-asset attributes. `Listing` has `condition`
   (wear) and `rarityScore` ("0-1 like float value in CSFloat") — but **no paint
   seed, pattern, phase or sticker fields at all**, and the float placeholder is
   dead in production: every real listing path sets `rarityScore =
   BigDecimal.ZERO` (`SteamInventoryController:505,680`), and the only code that
   populates it is `SeedService:693,1251`, with `rng.nextInt(1000)` — a random
   number for demo rows. The
   sibling repo measured on 3,744 sold records that this is exactly where CS2
   value lives: Doppler Emerald trades at **3.16x** the name median, Case
   Hardened 75-100% blue at **2.72x**, and the error runs both ways because gems
   drag the median up. One AK in the operator's own inventory wears a $585
   sticker. A name-median marketplace would be adversely selected: gems listed
   elsewhere, worst-in-tier dumped here.
3. Steam **Trade Protection (July 2025) is CS2-only**, adding a 7-day reversal
   window that Rust, Dota 2 and TF2 do not carry.

So the port to CS2 is not the appid — it is an inspect-link / per-asset
attribute pipeline, which is the product CSFloat is named after.

## 10. What could NOT be determined

- **Third-party (off-Steam) GMV for any market.** Every GMV here is Steam's own
  market. Venues like Skinport and DMarket trade volume that never appears in
  Steam's `volume`. For Rust it can be loosely bounded by standing inventory
  (~222k third-party listings against 268k on Steam); no venue publishes
  transaction volume and no accounts were created to find out.
- **CS2 GMV to better than an order of magnitude.** Half the estimate rests on
  4 of 54 sampled rows and 11.7% on a single knife that sold once, scaled 295x.
  Point estimate $700M/yr, 90% CI $377M-$1.08B, aggressive trim $341M. The
  *conclusion* survives it: required share moves only 0.22% → 0.45%.
- **Real chargeback rates for this category.** Published thresholds were used
  (Stripe "excessive" 0.75%; high-risk digital goods 1-3%), not a measurement.
- **The true fixed cost base.** $500 / $2,500 / $10,000 per month are brackets,
  not researched figures. BitSkins' closure suggests VAT and compliance dominate
  at scale; unquantified here.
- **Whether s&box is growing.** Two observations days apart (200 → 203 items) is
  not a growth measurement. If the game takes off the position has option value
  that cannot be priced from here.
- **Skinport's current seller fee.** The sibling repo models 12%; web sources say
  8% since 2025-07-18. Their site is client-rendered/403 to automated fetch and
  this was not verified at source.
- **Rust's Valve/Facepunch split.** The 15% total is certain; the 5/10 split is
  not confirmed at a Valve primary source for Rust specifically.
- **backpack.tf, scrap.tf, marketplace.tf** all `Disallow: /` for ClaudeBot.
  Not fetched. marketplace.tf's 10% is corroborated indirectly, not at source.
  mannco.store returned HTTP 403 (Cloudflare) to page fetches; only its API docs
  subdomain was readable.
- **Listing counts for markets sampled without a deep-book head capture**
  (Unturned, Steam cards, PAYDAY 2, Killing Floor 2) are **lower bounds**.
  Correcting them lowers avg take further, so the direction is safe — TF2's
  correction moved it from $0.1117 to $0.0059, a 19x error caught before use.
