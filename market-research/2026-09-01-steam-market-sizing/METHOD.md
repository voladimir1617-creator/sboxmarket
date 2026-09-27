# Steam market sizing — method and controls (captured 2026-09-01)

Purpose: decide whether pointing this codebase at a different Steam `appid`
reaches a market where its shipped fee model earns anything. Companion to
`UNIT-ECONOMICS.md`, which prices the fee model itself.

## Sources

Two Steam endpoints, both already used by the shipped code:

| endpoint | field used | shipped caller |
|---|---|---|
| `steamcommunity.com/market/search/render/` | `total_count`, `hash_name`, `sell_price`, `sell_listings` | `SeedService` (documented at `:813`) |
| `steamcommunity.com/market/priceoverview/` | `lowest_price`, `median_price`, **`volume`** | `SteamMarketPriceService:351` |

`robots.txt` for `steamcommunity.com` was read first. It disallows `/actions/`,
`/linkfilter/`, `/tradeoffer/`, `/trade/`, `/email/` — **not** `/market/`. No
blocked path was fetched. `currency=1` (USD) is pinned on every call.

## Controls run BEFORE any measurement (`control.py`)

1. **Fee model** — reproduces the shipped arithmetic from source
   (`TradeService:539` HALF_UP 2%; `PlatformLedgerService` 2.9%+$0.30 /
   0.25%+$0.25). Anchor: a $1.61 item yields platform $0.03, rail $0.60.
2. **Currency** — `AK-47 | Redline (Field-Tested)` cross-checked on
   `priceoverview` at `$39.01`; every price string verified `$`-denominated.
3. **Abort states are distinct** — `HTTP-429`, `HTTP-<code>`, `EMPTY-BODY`,
   `UNPARSEABLE-JSON`, `API-SUCCESS-FALSE`, `NO-TOTAL-COUNT`,
   `TRUNCATED-READ`, `EMPTY-PAGE-INSIDE-RANGE`. **None can render as an empty
   market.** The first sizing attempt returned HTTP-429 on all 20 candidates
   and reported exactly that, rather than 20 empty markets.
4. **Reproduction** — the s&box census independently reproduced the prior
   pass to within 1%: 201 items / 10,930 listings / $35,035 book / $699.50
   revenue / median item **exactly $1.61** (prior: 200 / 10,989 / $34,663 /
   $691 / $1.61).

## Four defects found and corrected during the run

- **Unstable ordering loses items.** Steam's default (popularity) ordering
  shifts between paged requests: a full s&box walk returned only 177 of 203
  items with no error. Every capture here therefore pins
  `sort_column=price&sort_dir=desc`, which recovered all 203.
- **Steam caps the page at 10 rows** regardless of `count`. A full CS2 census
  is 3,539 requests, so large markets are sampled systematically over price
  rank (`stride` recorded per market in `manifest.json`).
- **A price-rank sample cannot count listings.** Listings concentrate in a few
  penny commodity items a rank sample only hits by luck. TF2's price-rank sample
  said 366,424 listings while a *single* item (Winter 2025 Cosmetic Case) holds
  2,302,127. Uncorrected, TF2's average take read **$0.1117** — the best of any
  market measured; capturing its 300 deepest books via
  `sort_column=quantity&sort_dir=desc` corrected it to **$0.0059**, a **19x**
  error, and moved it from best to second-worst. CS2, Rust, TF2 and Dota 2 all
  combine an exact deep-book head with the sampled tail (`combine.py`); the
  head items are masked out of the tail so nothing is double-counted. Note the
  error runs both ways: Dota 2's listing estimate *fell* from 33.0M to 8.3M once
  its head was counted exactly rather than scaled 45x.
- **`sell_price` is the lowest ASK, not the transaction price.** On
  ultra-high-volume commodity items the two diverge: `Dreams & Nightmares
  Case` measured lowest **$0.13** against a 24h median of **$1.68** (13x) on
  93,036 sales/day. All GMV is therefore priced on `median_price`. On ordinary
  items the two agree (s&box median ratio 0.999, n=22; Rust 1.000, n=52).

## Estimator

Large markets use a systematic sample over price rank, integrated with a
**log-mean** interpolator between adjacent sampled pages. Validated against
the s&box full census by simulating sparser samples of it:

| sample | step | trapezoid | **geometric** |
|---|---|---|---|
| 1-in-3 | +82.9% | +16.5% | **-0.5%** |
| 1-in-5 | +157.7% | +48.0% | **+11.4%** |

Complete censuses (`stride = 1`) are summed exactly, never interpolated.
Accuracy depends on the price ratio between adjacent samples, not the sampling
fraction — which is why the CS2 estimators agree within 10% while the s&box
simulation at the same fraction does not.

## Turnover

`volume` is Steam's 24h sale count — the field `SteamMarketPriceService`
fetches and discards at `:397-400`. **Volume absent means zero sales in 24h**,
not missing data: across the s&box sample, `volume` and `median_price` (both
24h statistics) were absent together on 23 items and present together on 22,
with zero disagreement, while `lowest_price` (not a 24h statistic) was present
on all 45. Absent is therefore scored as zero, which is unbiased — an item
selling once a week reports volume on 1 day in 7 and contributes 1/7 in
expectation.

## Known limits

- Book value prices every standing listing at its item's lowest ask, so it is
  a **lower bound**; this matches the method the prior s&box pass used, which
  is what makes the comparison like-for-like.
- GMV for markets whose volume is concentrated in a few commodity items (CS2,
  TF2) has high estimator variance. 90% bootstrap CIs are reported and are wide.
- Standing book is **not** demand. An unsold listing is an item already
  refused at that price.
