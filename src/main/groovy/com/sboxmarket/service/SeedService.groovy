package com.sboxmarket.service

import com.sboxmarket.model.Bid
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Loadout
import com.sboxmarket.model.LoadoutFavorite
import com.sboxmarket.model.LoadoutSlot
import com.sboxmarket.model.PriceHistory
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.BidRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.LoadoutFavoriteRepository
import com.sboxmarket.repository.LoadoutRepository
import com.sboxmarket.repository.LoadoutSlotRepository
import com.sboxmarket.repository.PriceHistoryRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

import java.math.RoundingMode

/**
 * Minimal first-boot bootstrap. Creates only the demo wallet.
 *
 * Item catalogue is populated organically:
 *   - Users listing from their Steam inventory auto-create catalogue entries
 *   - SteamMarketPriceService fetches prices from Steam Market directly
 *
 * SCMM is NOT used — the operator explicitly requested removal.
 */
@Service
@Slf4j
class SeedService {

    @Autowired WalletRepository walletRepository
    @Autowired(required = false) ListingRepository listingRepository
    @Autowired(required = false) LoadoutRepository loadoutRepository
    @Autowired(required = false) LoadoutSlotRepository loadoutSlotRepository
    @Autowired(required = false) LoadoutFavoriteRepository loadoutFavoriteRepository
    @Autowired(required = false) ItemRepository itemRepository
    @Autowired(required = false) PriceHistoryRepository priceHistoryRepository
    @Autowired(required = false) SteamUserRepository steamUserRepository
    @Autowired(required = false) BidRepository bidRepository

    /**
     * First-boot bootstrap entry point — invoked once from the
     * {@code CommandLineRunner} in {@code SboxMarketApplication}.
     *
     * DELIBERATELY NOT {@code @Transactional}. Each helper below wraps its
     * body in a {@code try/catch} so a single bad row (constraint clash,
     * optimistic-lock failure, malformed fixture) is logged and skipped
     * instead of aborting startup. That contract ONLY holds when there is
     * no surrounding transaction: under one big {@code @Transactional seed()}
     * the first swallowed {@code PersistenceException} marks the shared
     * transaction rollback-only, and Spring's interceptor then throws
     * {@code UnexpectedRollbackException} when it tries to commit after
     * {@code seed()} returns — which DOES crash application startup, the
     * exact failure the per-helper catches were written to prevent.
     *
     * With no outer transaction, every Spring Data {@code save/delete}
     * runs in its own short transaction, so a failure rolls back only
     * that one row and the catch can genuinely continue. (The
     * self-heal block in {@code backfillPublicLoadouts} already documents
     * this same "runs outside an open transaction at boot" assumption.)
     *
     * The outer try/catch here covers the demo-wallet seed, which has no
     * catch of its own, so nothing short of a JVM error can stop boot.
     */
    void seed() {
        try {
            if (walletRepository.count() == 0) {
                walletRepository.save(new Wallet(username: "demo", balance: new BigDecimal("250.00"), currency: "USD"))
                log.info("Seeded demo wallet (\$250.00 starting balance)")
            }
        } catch (Exception e) {
            log.warn("Demo-wallet seed skipped: ${e.message}", e)
        }
        seedCatalogueItems()
        seedMarketplaceListings()
        seedDemoSellers()
        seedAuctionBids()
        backfillDemoSales()
        seedPriceHistory()
        seedPerItemSales()
        backfillPublicLoadouts()
        reconcileSeededLoadoutFavorites()
        reconcileSeededTotalSold()
    }

    /**
     * Fabricated-counter fix (same bug class as the frozen steamPrice):
     * the public-loadout seed used to stamp `favorites: (5 - fxIdx) * 7`
     * (35/28/21/14/7) onto its fixtures while creating ZERO backing
     * {@code LoadoutFavorite} junction rows. But the live mutation path —
     * {@code LoadoutService.toggleFavorite} — keeps the denormalised
     * counter in sync FROM the junction table
     * ({@code loadout.favorites = countByLoadout(id)}), so the FIRST real
     * user to star "Cardboard Connoisseur" snapped its public count from
     * 35 to 1 — the fiction visibly collapsed on first contact with real
     * activity, and the Discover sort (ORDER BY favorites DESC) reshuffled.
     *
     * This step makes the junction table the single source of truth, the
     * same way {@code seedAuctionBids} backs every seeded {@code bidCount}
     * with real {@code Bid} rows:
     *   - For each SYNTHETIC public loadout (ownerUserId < 0 — the seed's
     *     marker, real Steam users are positive), backfill real
     *     {@code LoadoutFavorite} rows from the six demo seller accounts:
     *     the top-ranked fixture gets all 6, the next 5, ... (descending,
     *     so the Discover sort stays sensible — just at honest magnitudes).
     *   - Then set {@code favorites} = the ACTUAL row count, exactly the
     *     value {@code toggleFavorite} would converge to. A real user's
     *     star now moves the count 6 → 7, not 35 → 1.
     *
     * SELF-HEALING + IDEMPOTENT: runs every boot. Existing DBs with the
     * old fabricated counters get their junction rows backfilled (guarded
     * per-(user,loadout) by findByUserAndLoadout, so re-runs and real
     * favorites are never duplicated — the UNIQUE constraint backstops it)
     * and the counter reconciled to the row count; a DB that's already
     * coherent is a no-op. Real users' favorites on these loadouts are
     * preserved — they're junction rows too, so they simply count.
     */
    private void reconcileSeededLoadoutFavorites() {
        if (loadoutRepository == null || loadoutFavoriteRepository == null || steamUserRepository == null) return
        try {
            // Demo-seller account ids in their fixed seed order.
            def demoIds = []
            SEED_SELLER_ACCOUNTS.each { acct ->
                def u = steamUserRepository.findBySteamId64(acct[1] as String)
                if (u?.id != null) demoIds << u.id
            }
            def synthetic = []
            try {
                synthetic = loadoutRepository.findAll().findAll {
                    it != null && it.visibility == 'PUBLIC' && it.ownerUserId != null && it.ownerUserId < 0L
                }
            } catch (Exception ignored) { return }
            if (synthetic.isEmpty()) return
            // Preserve the existing Discover ranking: order by the current
            // counter DESC (the legacy fabricated values are distinct and
            // descending), then id ASC so a fresh seed (all counters 0)
            // ranks in fixture-insert order.
            synthetic.sort { a, b ->
                int byFav = ((b.favorites ?: 0) as int) <=> ((a.favorites ?: 0) as int)
                byFav != 0 ? byFav : (((a.id ?: 0L) as long) <=> (((b.id ?: 0L) as long)))
            }
            int reconciled = 0
            int rowsCreated = 0
            synthetic.eachWithIndex { l, rank ->
                if (l.id == null) return
                // Rank 0 gets all demo accounts, rank 1 one fewer, ...
                int desired = Math.max(0, demoIds.size() - rank)
                for (int i = 0; i < desired; i++) {
                    Long uid = demoIds[i] as Long
                    if (loadoutFavoriteRepository.findByUserAndLoadout(uid, l.id) == null) {
                        try {
                            loadoutFavoriteRepository.save(new LoadoutFavorite(userId: uid, loadoutId: l.id))
                            rowsCreated++
                        } catch (Exception ignored) { /* UNIQUE race — already present */ }
                    }
                }
                long real = loadoutFavoriteRepository.countByLoadout(l.id)
                if (((l.favorites ?: 0) as int) != (int) real) {
                    l.favorites = (int) real
                    loadoutRepository.save(l)
                    reconciled++
                }
            }
            if (rowsCreated > 0 || reconciled > 0) {
                log.info("Reconciled seeded loadout favorites: ${rowsCreated} junction rows backfilled, " +
                        "${reconciled} counters synced to their real row count (was fabricated 35/28/21/14/7 " +
                        "with zero backing rows — first real star used to collapse the count)")
            }
        } catch (Exception e) {
            log.warn("Loadout-favorites reconcile skipped: ${e.message}", e)
        }
    }

    /**
     * Seed-coherence fix for {@code Item.totalSold} (display surfaces:
     * the item-modal "N sold" lifetime chip, the /db "Sold" column and
     * the most-traded sort). {@code seedPerItemSales} and
     * {@code backfillDemoSales} insert SOLD Listing rows — which the
     * "Recent sales (N)" panel, the velocity chips and the stall pages
     * all COUNT as sales — but left the denormalised lifetime counter at
     * 0, so the same item page said "6 recent sales" and "Sold 0" at
     * once. (Exactly the inconsistency {@code seedAuctionBids} fixed for
     * bidCount-vs-Bid-rows.)
     *
     * Reconciles the counter to the real SOLD-row count via the SAME
     * query family the velocity panel reads (countSoldForItemSince since
     * epoch 0), so the displayed surfaces agree by construction.
     *
     * GUARDED: only items whose counter is still 0 are touched — the
     * live increment path (PurchaseService.buy / BidService settle, with
     * the TradeService dispute decrement) owns any non-zero counter and
     * is never clobbered. Idempotent: after the first reconcile the
     * counter is > 0 (when sold rows exist) and the guard skips forever.
     */
    private void reconcileSeededTotalSold() {
        if (itemRepository == null || listingRepository == null) return
        try {
            int fixed = 0
            itemRepository.findAll().each { item ->
                if (item == null || item.id == null) return
                if (((item.totalSold ?: 0) as int) != 0) return
                long sold = 0L
                try { sold = listingRepository.countSoldForItemSince(item.id, 0L) }
                catch (Exception ignored) { return }
                if (sold > 0L) {
                    item.totalSold = (int) sold
                    itemRepository.save(item)
                    fixed++
                }
            }
            if (fixed > 0) {
                log.info("Reconciled totalSold for ${fixed} items to their real SOLD-row count " +
                        "(seeded sales rows existed but the lifetime counter was frozen at 0)")
            }
        } catch (Exception e) {
            log.warn("totalSold reconcile skipped: ${e.message}", e)
        }
    }

    /**
     * Real seller ACCOUNTS for the public stall pages. The handles here are
     * a subset of {@link #SEED_SELLERS} — the same display names the
     * marketplace-listing seed already stamped onto {@code Listing.sellerName}
     * — so seeding a backing {@code SteamUser} per handle keeps names
     * consistent end-to-end (the listing row's `sellerName` and the stall
     * hero's `displayName` agree).
     *
     * Each entry is `[handle, steamId64, avatarUrl]`. The steamId64 values
     * sit in a synthetic-but-VALID block (76561199000000001 …): each is well
     * ABOVE the SteamID64 base 76561197960265728, so its Steam ID32 / accountid
     * (steamId64 − base) is a POSITIVE number. That is load-bearing: the
     * trade-URL ownership check (ProfileController.setTradeUrl) derives the
     * expected `partner=` value as steamId64 − base, so a seed account can only
     * save a trade URL — and therefore complete a P2P buy/bid — if that
     * subtraction is positive. The previous sub-base ids (7656119000000000…)
     * were BELOW the base, yielding a negative expected partner, so NO seed
     * account could ever set a trade URL and every P2P purchase 400'd with
     * TRADE_URL_NOT_YOURS on localhost. Ids are fixed + stable so a fresh seed
     * always produces the same stall ids for QA + screenshots.
     */
    private static final List<List<String>> SEED_SELLER_ACCOUNTS = [
        ['BoneTender',   '76561199000000001', 'https://avatars.fastly.steamstatic.com/fef49e7fa7e1997310d705b2a6158ff8dc1cdfeb_full.jpg'],
        ['AtlasTrades',  '76561199000000002', 'https://avatars.fastly.steamstatic.com/b5bd56c1aa4644a474a2e4972be27ef9e82e517e_full.jpg'],
        ['PixelPusher',  '76561199000000003', 'https://avatars.fastly.steamstatic.com/c5d4097dde9e95d7e289ddc5a533c95a0e3c4e91_full.jpg'],
        ['GhostlyDeals', '76561199000000004', 'https://avatars.fastly.steamstatic.com/9f0b9b3b6c3b2c8e1e7d6a5c4b3a2d1e0f9e8d7c_full.jpg'],
        ['EmberWolf',    '76561199000000005', 'https://avatars.fastly.steamstatic.com/8e7d6c5b4a3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f_full.jpg'],
        ['TradeHaven',   '76561199000000006', 'https://avatars.fastly.steamstatic.com/7d6c5b4a3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f8e_full.jpg']
    ]

    /** Sentinel steamId64 of the FIRST demo seller — used as the idempotency
     *  probe. If this account already exists, the whole demo-seller seed has
     *  already run (or a real BoneTender-equivalent owns the id) so we skip. */
    private static final String SEED_SELLER_PROBE_STEAMID = '76561199000000001'

    /**
     * Day-1 launch seed that makes the public seller-stall pages
     * (/stall/{id}) reachable. PROBLEM this fixes: every Listing the
     * marketplace seed creates carries a display-name-only seller
     * (`sellerName` set, `sellerUserId = null`) and there are NO backing
     * {@code SteamUser} rows — so {@code ListingController.publicStall}'s
     * `steamUserRepository.findById(userId)` always misses and the page
     * renders the branded "Stall not found" empty state. The rich stall
     * hero (avatar, sold/active stats, listings grid, recent sales) was
     * built but unreachable.
     *
     * This seeds a small crowd of REAL seller accounts
     * ({@link #SEED_SELLER_ACCOUNTS}) and then attaches a portion of the
     * already-seeded ACTIVE listings to them by matching the listing's
     * existing `sellerName` to the account handle — so each seeded seller
     * owns the listings already branded with their name, and a visit to
     * /stall/{their id} renders a populated grid for the exact shape the
     * controller queries ({@code SteamUser} by id +
     * {@code Listing.sellerUserId = id}, status ACTIVE, not hidden).
     *
     * Listings whose `sellerName` is one of the FOUR seed handles NOT in
     * the account set (VaultRunner, NeonArc, CrateDigger, FrostByte) stay
     * anonymous (`sellerUserId = null`), preserving the existing
     * "crowd of mixed real + system sellers" pattern.
     *
     * IDEMPOTENT: skips entirely if the sentinel demo account already
     * exists (re-seed / restart safe). Attaches only to listings that are
     * still ACTIVE and still unowned (`sellerUserId == null`) so it never
     * fights a real seller's listing or double-attaches on a partial
     * re-run. DETERMINISTIC: account ids + handles are fixed constants;
     * attachment is a stable name→id mapping, no RNG.
     *
     * Inert when the optional {@code steamUserRepository} /
     * {@code listingRepository} beans are absent (mirrors the
     * required=false fields), exactly like {@code seedPriceHistory}.
     */
    private void seedDemoSellers() {
        if (steamUserRepository == null || listingRepository == null) return
        try {
            // Idempotency probe — if the first demo account exists, the
            // whole set has already been seeded (or the id is taken by a
            // real account); skip without touching anything.
            if (steamUserRepository.findBySteamId64(SEED_SELLER_PROBE_STEAMID) != null) return

            long now = System.currentTimeMillis()
            long dayMs = 86_400_000L

            // 1) Seed the SteamUser rows. handle -> persisted id.
            def idByHandle = [:]
            SEED_SELLER_ACCOUNTS.eachWithIndex { acct, i ->
                String handle = acct[0]
                String steamId = acct[1]
                String avatar = acct[2]
                // Spread joinedAt over the past ~30-210 days so the stall
                // hero's "Member since" reads like a real, aged crowd
                // rather than every account created at the same instant.
                long joinedAt = now - (long) ((i + 1) * 35) * dayMs
                def u = new SteamUser(
                    steamId64:    steamId,
                    displayName:  handle,
                    avatarUrl:    avatar,
                    profileUrl:   "https://steamcommunity.com/profiles/${steamId}".toString(),
                    createdAt:    joinedAt,
                    lastLoginAt:  now - (long) (i + 1) * 3600_000L,
                    lastSeenAt:   now - (long) (i + 1) * 3600_000L,
                    lastSyncedAt: now - (long) (i + 1) * 1800_000L,
                    role:         'USER',
                    banned:       false
                )
                def saved = steamUserRepository.save(u)
                idByHandle[handle] = saved.id
                // Seed a zero-balance wallet under the "steam_<id>" username —
                // the exact key WalletController.currentWallet + PurchaseService
                // (findByUsername("steam_${steamId64}")) use to resolve a
                // seller's payout account. Without it, escrow had nowhere to
                // credit the sale and EVERY P2P buy of a seeded listing 400'd
                // SELLER_WALLET_MISSING on localhost. Guarded so a partial
                // re-run / pre-existing wallet is a no-op.
                if (walletRepository != null && walletRepository.findByUsername("steam_${steamId}".toString()) == null) {
                    walletRepository.save(new Wallet(
                        username: "steam_${steamId}".toString(),
                        balance: BigDecimal.ZERO,
                        currency: 'USD'))
                }
            }

            // 2) Attach a portion of the seeded ACTIVE listings to these
            //    accounts by matching the listing's existing sellerName to
            //    an account handle. Only ACTIVE + still-unowned rows are
            //    claimed so we never fight a real seller's listing nor
            //    double-attach on a partial re-run.
            int attached = 0
            def perSeller = [:].withDefault { 0 }
            def all = listingRepository.findAll()
            all.each { l ->
                if (l == null) return
                if (l.status != 'ACTIVE') return
                if (l.sellerUserId != null) return
                def uid = idByHandle[l.sellerName]
                if (uid == null) return
                l.sellerUserId = uid
                listingRepository.save(l)
                attached++
                perSeller[l.sellerName] = perSeller[l.sellerName] + 1
            }

            log.info("Seeded ${idByHandle.size()} real demo seller accounts and attached " +
                    "${attached} active listings to them (per-seller: ${perSeller}) so " +
                    "/stall/{id} renders a populated stall on a fresh boot instead of " +
                    "\"Stall not found\"")
        } catch (Exception e) {
            log.warn("Demo-seller seed skipped: ${e.message}", e)
        }
    }

    /**
     * Day-1 launch seed for AUCTION bid HISTORY. PROBLEM this fixes:
     * {@code seedMarketplaceListings()} stamps each AUCTION listing with a
     * denormalised {@code bidCount} (0–7) and a {@code currentBid} but
     * creates ZERO backing {@code Bid} rows. So an item page reads the
     * header off {@code listing.bidCount} ("5 bids") while the bid-history
     * list — served by {@code BidService.historyFor} →
     * {@code BidRepository.findByListing} (an actual {@code Bid}-row query)
     * — comes back EMPTY. A demo-grade inconsistency that breaks the 1:1
     * csfloat illusion, where an auction's history always matches its count.
     *
     * This method gives every seeded AUCTION a coherent bid history and then
     * makes the listing's denormalised fields AUTHORITATIVE over those rows
     * (the same end-state {@code BidService.placeBid} converges to):
     *   - {@code bidCount}          = the number of {@code Bid} rows created
     *   - {@code currentBid}        = the HIGHEST bid amount
     *   - {@code currentBidderId}   = that top bid's bidder id
     *   - {@code currentBidderName} = that top bid's bidder name
     * so the header count, the "current bid" chip and the history list all
     * agree no matter which one the UI reads.
     *
     * PER AUCTION: it honours the already-seeded {@code bidCount} as the row
     * count. An auction seeded with {@code bidCount == 0} (no {@code currentBid})
     * is a brand-new, no-bid auction — already coherent (header "0 bids",
     * empty list) — so it gets NO rows. For {@code bidCount == n > 0} it
     * creates exactly {@code n} rows with:
     *   - AMOUNTS strictly ascending, the LAST one landing exactly on the
     *     auction's {@code currentBid} (so max(amount) == currentBid), the
     *     earlier ones stepped down by a fixed increment from there — i.e.
     *     the history reads like a real climb to the current top bid.
     *   - TIMESTAMPS walking backward from ~now, oldest first, all inside
     *     the [listedAt, now] window so a bid never predates its listing.
     *   - BIDDERS drawn round-robin from the six demo seller accounts
     *     ({@link #SEED_SELLER_ACCOUNTS}), EXCLUDING the auction's own seller
     *     (a seller can't bid on their own lot). The top (winning) row's
     *     bidder is promoted onto the listing's {@code currentBidder*} fields.
     *   - STATUS: the single highest row is WINNING; every lower row is
     *     OUTBID — mirroring the live {@code placeBid} demotion so the
     *     Profile Active-Bids tab and the history badges read correctly.
     *
     * IDEMPOTENT: fires only when {@code bidRepository.count() == 0}. This
     * seed is the sole creator of {@code Bid} rows on a fresh boot, so once
     * any bid exists (seeded here, or a real {@code placeBid}) it never runs
     * again — it cannot double-seed or fight live bids. Mirrors the
     * {@code seedMarketplaceListings()} {@code count() > 0} guard.
     *
     * DETERMINISTIC: {@code Random(7L)} so the whole bid book is byte-for-
     * byte reproducible across boots for QA + screenshots — same convention
     * as the other seed steps. Inert when the optional {@code bidRepository}
     * / {@code listingRepository} / {@code steamUserRepository} beans are
     * absent (mirrors the required=false fields), like {@code seedDemoSellers}.
     */
    private void seedAuctionBids() {
        if (bidRepository == null || listingRepository == null || steamUserRepository == null) return
        try {
            // Idempotency probe — this seed is the only creator of Bid rows
            // on a fresh boot, so any existing bid means we (or a real
            // placeBid) already ran. Skip without touching anything.
            if (bidRepository.count() > 0) return

            // Resolve the six demo seller accounts to (id, displayName) so we
            // can attribute bids to real SteamUser ids — the same accounts
            // seedDemoSellers() created, looked up by their fixed steamId64s
            // so this works whether or not any listing was attached to them.
            def bidders = []   // list of [id, displayName]
            SEED_SELLER_ACCOUNTS.each { acct ->
                def u = steamUserRepository.findBySteamId64(acct[1])
                if (u != null && u.id != null) bidders << [u.id, u.displayName]
            }
            if (bidders.isEmpty()) {
                log.info("Auction-bid seed skipped: no demo bidder accounts resolved")
                return
            }

            def rng = new Random(7L)
            long now = System.currentTimeMillis()
            long hourMs = 3600_000L

            int auctionsSeeded = 0
            int bidsCreated = 0

            listingRepository.findAll().each { l ->
                if (l == null || l.id == null) return
                if (l.listingType != 'AUCTION') return

                // Honour the already-seeded bidCount as the authoritative row
                // count. 0 (or null) → a coherent no-bid auction; leave it.
                int n = (l.bidCount ?: 0)
                if (n <= 0) return

                // Eligible bidders = demo accounts that are NOT this auction's
                // own seller (a seller cannot bid on their own lot). Match on
                // BOTH the attached seller id (set by seedDemoSellers) and the
                // seller display name (covers anonymous listings whose
                // sellerUserId is null but whose sellerName is a demo handle).
                def eligible = bidders.findAll { b ->
                    (l.sellerUserId == null || b[0] != l.sellerUserId) &&
                    (l.sellerName == null || b[1] != l.sellerName)
                }
                if (eligible.isEmpty()) return   // every demo bidder IS the seller — skip

                // The top bid must equal the listing's currentBid so the
                // "current bid" chip agrees with max(amount). If a row count
                // was seeded without a currentBid (shouldn't happen — the
                // listing seed only sets bidCount>0 alongside currentBid),
                // synthesize a floor below ask so the history is still sane.
                BigDecimal top = l.currentBid
                if (top == null || top <= BigDecimal.ZERO) {
                    top = (l.price ?: new BigDecimal('1.00')) * new BigDecimal('0.70')
                    top = top.setScale(2, RoundingMode.HALF_UP)
                    if (top <= BigDecimal.ZERO) top = new BigDecimal('0.10')
                }

                // Build n strictly-ascending amounts ending exactly on `top`.
                // Step down from the top by a per-auction increment so the
                // lowest (oldest) bid sits ~30% below the top across the
                // whole climb — a believable bid war, not n identical rows.
                BigDecimal step = (top * new BigDecimal('0.30'))
                        .divide(new BigDecimal(Math.max(1, n)), 2, RoundingMode.HALF_UP)
                if (step <= BigDecimal.ZERO) step = new BigDecimal('0.01')

                // Timestamps: oldest first, walking backward from a few
                // minutes ago, all inside [listedAt, now] so a bid never
                // predates its own listing. Spread across the listing's age
                // (capped to ~the last 24h of it) for a natural cadence.
                long listedAt = (l.listedAt != null && l.listedAt < now) ? l.listedAt : (now - 24L * hourMs)
                long lastBidAt = now - (5L + rng.nextInt(55)) * 60_000L   // 5–60 min ago
                if (lastBidAt <= listedAt) lastBidAt = (now + listedAt) / 2L
                long windowMs = lastBidAt - listedAt
                if (windowMs < n * 60_000L) windowMs = n * 60_000L        // ensure room for n steps

                // Round-robin bidders, but make sure two ADJACENT bids aren't
                // the same person where the pool allows it — start at a random
                // offset for variety across auctions.
                int offset = rng.nextInt(eligible.size())

                Bid topRow = null
                BigDecimal topAmount = null
                Long topBidderId = null
                String topBidderName = null

                for (int i = 0; i < n; i++) {
                    // i = 0 is the OLDEST / lowest bid; i = n-1 is the newest /
                    // highest and must equal `top`.
                    BigDecimal amount = (i == n - 1)
                            ? top
                            : (top - step * new BigDecimal(n - 1 - i)).setScale(2, RoundingMode.HALF_UP)
                    if (amount <= BigDecimal.ZERO) amount = new BigDecimal('0.01')
                    // Strict ceiling on every non-final row: a tiny-priced lot
                    // can round a lower step up to (or past) `top`, which would
                    // let an earlier bid meet/beat the winning bid and break the
                    // "max(amount) == currentBid" invariant. Pin such rows just
                    // under the top by one cent so the winner stays unique.
                    if (i < n - 1 && amount >= top) {
                        amount = (top - new BigDecimal('0.01')).setScale(2, RoundingMode.HALF_UP)
                        if (amount <= BigDecimal.ZERO) amount = new BigDecimal('0.01')
                    }

                    def b = eligible[(offset + i) % eligible.size()]
                    Long bidderId = b[0] as Long
                    String bidderName = b[1] as String

                    // Oldest→newest ascending timestamps inside the window.
                    long createdAt = listedAt + (long) ((double) (i + 1) / (n + 1) * windowMs)
                    if (createdAt >= now) createdAt = now - (long) (n - i) * 1000L

                    def bid = new Bid(
                        listingId:    l.id,
                        bidderUserId: bidderId,
                        bidderName:   bidderName,
                        amount:       amount,
                        maxAmount:    null,
                        kind:         'MANUAL',
                        // Highest row WINNING, the rest demoted to OUTBID —
                        // matches the live placeBid end-state so the Profile
                        // Active-Bids tab and history badges read correctly.
                        status:       (i == n - 1) ? 'WINNING' : 'OUTBID',
                        createdAt:    createdAt
                    )
                    bidRepository.save(bid)
                    bidsCreated++

                    if (i == n - 1) {
                        topRow = bid
                        topAmount = amount
                        topBidderId = bidderId
                        topBidderName = bidderName
                    }
                }

                // Make the listing's denormalised auction fields AUTHORITATIVE
                // over the rows we just wrote, so the header count, the
                // "current bid" chip and the history list can never disagree.
                if (topRow != null) {
                    l.bidCount          = n
                    l.currentBid        = topAmount
                    l.currentBidderId   = topBidderId
                    l.currentBidderName = topBidderName
                    listingRepository.save(l)
                    auctionsSeeded++
                }
            }

            log.info("Seeded ${bidsCreated} auction Bid rows across ${auctionsSeeded} auctions " +
                    "so every auction's bidCount matches its bid history (highest bid == currentBid) " +
                    "on a fresh boot instead of \"N bids\" over an empty list")
        } catch (Exception e) {
            log.warn("Auction-bid seed skipped: ${e.message}", e)
        }
    }

    /** Synthetic seller handles for seed Listings. Distinct from the
     *  "SIM · …" prefix AdminSimulatorService uses so an operator can
     *  still one-shot-clear simulator rows without touching the launch
     *  seed. ~10 handles so the marketplace grid reads like a real crowd
     *  of sellers, not one bot. */
    private static final List<String> SEED_SELLERS = [
        'VaultRunner', 'NeonArc', 'CrateDigger', 'FrostByte',
        'BoneTender', 'AtlasTrades', 'PixelPusher', 'GhostlyDeals',
        'EmberWolf', 'TradeHaven'
    ]

    /** Plausible CS-style float conditions, weighted toward the middle so
     *  the grid isn't all Factory New. Index drawn by the per-listing RNG. */
    private static final List<String> SEED_CONDITIONS = [
        'Factory New', 'Minimal Wear', 'Minimal Wear', 'Field-Tested',
        'Field-Tested', 'Well-Worn', 'Battle-Scarred'
    ]

    /**
     * Day-1 launch seed for the marketplace itself. `seedCatalogueItems()`
     * plants 32 catalogue rows but with `isListed=false` and ZERO Listing
     * rows — so /market, the homepage rails, every /item page and /db
     * render an empty grid and the whole app looks dead to a first
     * visitor. This method gives those catalogue items real, buyable
     * Listing rows from a crowd of synthetic sellers.
     *
     * IDEMPOTENT: only fires when `listingRepository.count() == 0`. The
     * instant a real seller posts a listing — or the admin simulator
     * spawns one — this seed never runs again, so it can't double-seed
     * or fight live data.
     *
     * Per item: 1–4 Listing rows, weighted by price band (cheap/common
     * items get more listings — a real marketplace has deep books on
     * the floor and thin books on the whales). Off-Market items get
     * just 1. Total lands around ~60–90 listings.
     *
     * Each price is the item's `lowestPrice` jittered ×0.85–×1.25 so the
     * book has natural spread; `setScale(2, HALF_UP)` keeps it currency-
     * clean. ~15% of listings are AUCTION rows with an expiry 1h–48h out,
     * a `currentBid` seeded below ask, and a small `bidCount`. The rest
     * are plain BUY_NOW.
     *
     * `listedAt` is spread over the past ~14 days so the "Just listed"
     * newest-sort rail looks organic instead of every row sharing a
     * timestamp. `sellerUserId` is null on every row (system listing) —
     * `backfillDemoSales()` (next in `seed()`) then flips a handful to
     * SOLD so the "Latest sales" panel comes alive too.
     *
     * Finally each Item is reconciled to its new book: `isListed=true`,
     * `supply` = its ACTIVE listing count, `lowestPrice` = the true
     * floor across its ACTIVE listings — so card prices, the item-modal
     * floor chip and the catalogue all read consistently.
     *
     * Deterministic RNG seed (`Random(42L)`) so a fresh boot always
     * produces the same curated book — reproducible for QA + screenshots.
     */
    private void seedMarketplaceListings() {
        if (listingRepository == null || itemRepository == null) return
        try {
            if (listingRepository.count() > 0) return
            def items = itemRepository.findAll()
                    .findAll { it != null && it.lowestPrice != null && it.lowestPrice > BigDecimal.ZERO }
            if (items.isEmpty()) {
                log.info("Marketplace-listing seed skipped: no priced catalogue items")
                return
            }

            def rng = new Random(42L)
            long now = System.currentTimeMillis()
            long fourteenDaysMs = 14L * 24L * 3600_000L
            int totalCreated = 0
            int auctionCreated = 0

            // Track each item's created ACTIVE listings so we can reconcile
            // supply + floor in a second pass without re-querying the DB.
            def activeByItem = [:].withDefault { [] }

            items.each { item ->
                // Listing count by price band: cheap/common items carry a
                // deeper book, expensive Off-Market items a thin one. A real
                // marketplace floor is crowded; the whales are not.
                int count
                BigDecimal lp = item.lowestPrice
                if (item.rarity == 'Off-Market' || lp >= new BigDecimal('60.00')) {
                    count = 1
                } else if (lp < new BigDecimal('2.00')) {
                    count = 2 + rng.nextInt(3)   // 2–4 — deep floor book
                } else if (lp < new BigDecimal('10.00')) {
                    count = 1 + rng.nextInt(3)   // 1–3
                } else {
                    count = 1 + rng.nextInt(2)   // 1–2 — pricier, thinner
                }

                count.times {
                    // Price: lowestPrice jittered ×0.85–×1.25.
                    BigDecimal jitter = new BigDecimal('0.85') +
                            new BigDecimal(rng.nextInt(41)).divide(new BigDecimal('100'))
                    BigDecimal price = (lp * jitter).setScale(2, RoundingMode.HALF_UP)
                    if (price <= BigDecimal.ZERO) price = new BigDecimal('0.25')

                    String handle = SEED_SELLERS[rng.nextInt(SEED_SELLERS.size())]
                    String condition = SEED_CONDITIONS[rng.nextInt(SEED_CONDITIONS.size())]
                    // rarityScore mirrors CSFloat's 0–1 float value — lower
                    // (cleaner) scores skew toward Factory New listings.
                    BigDecimal rarityScore = new BigDecimal(rng.nextInt(1000))
                            .divide(new BigDecimal('1000')).setScale(4, RoundingMode.HALF_UP)
                    // Spread listedAt across the past ~14 days so the
                    // newest-first rail looks organic.
                    long listedAt = now - (long)(rng.nextDouble() * fourteenDaysMs)

                    boolean isAuction = rng.nextInt(100) < 15  // ~15% auctions

                    def listing = new Listing(
                        item:         item,
                        price:        price,
                        sellerName:   handle,
                        sellerAvatar: initialsFor(handle),
                        status:       'ACTIVE',
                        condition:    condition,
                        rarityScore:  rarityScore,
                        listingType:  isAuction ? 'AUCTION' : 'BUY_NOW',
                        sellerUserId: null,        // system / launch-seed listing
                        listedAt:     listedAt,
                        hidden:       false
                    )

                    if (isAuction) {
                        // Expiry spread 1h–48h out.
                        long expiresIn = (1L + rng.nextInt(48)) * 3600_000L
                        listing.expiresAt = now + expiresIn
                        // currentBid sits below ask so there's headroom to bid.
                        int bids = rng.nextInt(8)  // 0–7
                        listing.bidCount = bids
                        if (bids > 0) {
                            BigDecimal bidFactor = new BigDecimal('0.60') +
                                    new BigDecimal(rng.nextInt(30)).divide(new BigDecimal('100'))
                            BigDecimal bid = (price * bidFactor).setScale(2, RoundingMode.HALF_UP)
                            if (bid <= BigDecimal.ZERO) bid = new BigDecimal('0.10')
                            listing.currentBid = bid
                            String bidder = SEED_SELLERS[rng.nextInt(SEED_SELLERS.size())]
                            listing.currentBidderName = bidder
                        }
                        auctionCreated++
                    }

                    listingRepository.save(listing)
                    activeByItem[item.id] << listing
                    totalCreated++
                }
            }

            // Second pass — reconcile each Item to its freshly-seeded book so
            // the catalogue card price, the item-modal floor chip and the
            // /db grid all agree with the listings that now exist.
            int relistedItems = 0
            items.each { item ->
                def active = activeByItem[item.id]
                if (active.isEmpty()) return
                BigDecimal floor = active.collect { it.price }.min()
                item.isListed    = true
                item.supply      = active.size()
                item.lowestPrice = floor
                itemRepository.save(item)
                relistedItems++
            }

            log.info("Seeded ${totalCreated} marketplace listings " +
                    "(${auctionCreated} auctions) across ${relistedItems} items " +
                    "so /market, the home rails and /db render a live marketplace on first boot")
        } catch (Exception e) {
            log.warn("Marketplace-listing seed skipped: ${e.message}", e)
        }
    }

    /** Build a 2-char uppercase avatar token from a seller handle.
     *  CamelCase handles ("VaultRunner") yield the two capitalised
     *  initials ("VR"); a plain lowercase handle falls back to its
     *  first two letters. Mirrors the initials-avatar convention used
     *  across the marketplace UI. */
    private static String initialsFor(String handle) {
        if (handle == null || handle.isEmpty()) return '??'
        def caps = (handle =~ /[A-Z]/).collect { it }
        if (caps.size() >= 2) return (caps[0] + caps[1])
        return handle.take(2).toUpperCase()
    }

    /**
     * Day-1 launch seed for the item catalogue. The architectural intent
     * (per the class header) is for catalogue rows to populate organically
     * from real Steam-inventory listings — but on a fresh production boot
     * with zero sellers yet, /db (catalogue browse), /loadout (Loadout Lab
     * Discover tab), and the home grid all render empty. This seed plants
     * a curated, generic set of s&box-flavored cosmetic items so first
     * visitors see a real catalogue, the Loadout Lab demo loadouts can
     * fill their slots, and SEO crawlers see real /db pages instead of an
     * empty grid.
     *
     * IDEMPOTENT: only fires when the catalogue is completely empty
     * (itemRepository.count() == 0). Once a real seller lists an item,
     * the catalogue is no longer "empty" and this seed will never run
     * again — even on subsequent boots after a partial wipe.
     *
     * Items are catalogue-only — `isListed: false`, no Listing rows
     * created. Real sellers populate Listing rows from their Steam
     * inventory via the normal /api/listings POST flow. No demo Listings
     * are created because we cannot back them with real Steam items, and
     * a "buy" attempt on a phantom listing would fail at trade-creation
     * with no recovery path for the buyer.
     *
     * Names + emojis chosen to be generic enough not to imply any
     * partner/affiliate relationship with another game's marketplace.
     */
    /**
     * Real Steam economy CDN render URL builder for seeded demo items.
     *
     * The raw `icon_url` token (the long base64-ish string in a Steam
     * description) is appended to the akamai economy image host with NO size
     * suffix, mirroring exactly the shape the live Steam-listing path stores
     * (see SteamInventoryService.mapInventoryJson). The frontend's
     * `upscaleSteamImage` helper (primitives.js) appends the right per-surface
     * size variant (/96x96, /330x192, /512x384, /1024x768) at render time —
     * but only for akamai-host URLs, which is why we use the akamai host here.
     *
     * These tokens were retrieved verbatim from the live Steam Community
     * Market render API for s&box (app 590830) on 2026-05-30:
     *   https://steamcommunity.com/market/search/render/?appid=590830&norender=1
     * Each maps to a genuine, currently-listed s&box item, so the seeded demo
     * catalogue shows real renders instead of emoji placeholders.
     */
    private static String steamRender(String iconUrl) {
        if (!iconUrl?.trim()) return null
        "https://steamcommunity-a.akamaihd.net/economy/image/${iconUrl}".toString()
    }

    private void seedCatalogueItems() {
        if (itemRepository == null) return
        try {
            if (itemRepository.count() > 0) return
            // Format: [name, category, rarity, iconEmoji, accentColor, lowestPriceUSD, imageUrl?]
            // The optional 7th element is a real Steam economy CDN render URL
            // (built via steamRender from a genuine s&box icon_url). Fixtures
            // without one fall back to the emoji/category-glyph tile.
            // Categories: Clothing, Hats, Accessories, Workshop (per Item model contract)
            // Rarities: Standard (white), Limited (orange), Off-Market (purple) (per Item model contract)
            // CATEGORY CONTRACT: the UI filter chips (defined in 3 places —
            // app.js:3158, app.js:4558, csfloat-modals.js:131) are the
            // source of truth. Allowed categories: Hats, Jackets, Shirts,
            // Pants, Gloves, Boots, Accessories. Workshop is also rendered
            // by the icon map (8 places in JS) but isn't filterable via
            // chips — Workshop items only appear under "All". The Item
            // model's `// Clothing, Hats, Accessories, Workshop` comment is
            // misleading and out of date. Every chip-allowed category gets
            // at least one item below so no chip click returns an empty grid.
            // Real s&box (app 590830) Steam icon_url tokens, retrieved verbatim
            // from the live Steam Market render API on 2026-05-31:
            //   https://steamcommunity.com/market/search/render/?appid=590830&norender=1&count=10&start=0..90
            // (the render endpoint hard-caps pagesize at 10, so the full
            // catalogue is paged in 10-item windows). Every token below was
            // confirmed identical across TWO+ independent fetches keyed by item
            // NAME — the endpoint shuffles result ORDER per request, but a given
            // item's icon_url is stable, so none of these are fabricated. Each
            // maps to a genuine, currently-listed s&box item and the seeded
            // item's NAME matches the render exactly (no mislabeling).
            // steamRender() builds the full CDN URL.
            def REAL = [
                // Hats / head
                shortPartedHair:    'ev_QInFv2QSGCJrUcil4gJtKJmCq-T22QuKmSneHmoyowIiy9kXcJpl6f8uTlKsfIl6L2dM8bm-tObiGkiLlKlyo_-YYyE31NatmVfYmiNVee5B-',
                fauxHawkHair:       'ev_QInFv2QSGCJrUcil4gJtKJmCk-Cn8TKijASrZ2cT-1pDsoRXMcNgrIM3Ml-pUeQae0cl9Y370abmGzyznJQ3-r6oWk0CrfqZhHeNljUkzKnOaX7le4YIt4ExqooBXTQUH7rZByJo',
                fishermanCap:       'ev_QInFv2QSGCJrUcil4gJtKJmC--iK8V6noG26JyoKswIrl-VOLds4oZp3OxPsDKAHdj8k-bTqqPK6Cmn6wf17g7ulJKUmXpQc',
                wizardHat:          'ev_QInFv2QSGCJrUcil4gJtKJmC--iK8V6noG26JnNatlorh-FOKKpkoZp2Yx_4DKQHYjMloPzv4OvDUnX7pLA7g7ulJaQVeuM8',
                ww1Helmet:          'ev_QInFv2QSGCJrUcil4gJtKJmC--iK8V6noG27TnNf_wIqy9lOJI8x5Zp3MlKkDKFON38lsOD6ubaGBziLoJFrg7ulJttu8nYM',
                // Jackets / outerwear
                leatherCoat:        'ev_QInFv2QSGCJrUcil4gJtKJmCq-T22QuKkRnbUm9WqwYm9802NcZhyK5vPwqlJKQTRiNY6bT_7PbjUyHmyflj_-75Ll0GoaqpgVfYmiEQr_2Xb',
                lunarJacket2026:    'ev_QInFv2QSGCJrUcil4gJtKJmCq-T22QuKkF3SCkYX6ldy1ohmKIMh5fMvOkKlJKFfdjN8-bm6qM7jXmCziJF6oruUZkhn0YfAzVfYmiN05E2AZ',
                wizardGown:         'ev_QInFv2QSGCJrUcil4gJtKJmC--iK8V6noG27RytH9x9uw-VOGdM4oZp2fxK0DcATdiMk-OWqhaq_QnHzmKgng7ulJwK-6TyU',
                halloweenHoodie:    'ev_QInFv2QSGCJrUcil4gJtKJmCq-T22QuL2FCOJkNCpnIjn9ESGc8gvLZydkK9OKFaNgtE9M27_OLjUzinjLl2v--Edwk78N6Y3VfYmiH21OkW6',
                santaJacket2024:    'ev_QInFv2QSGCJrUcil4gJtKJmC--iK8V6noG26HkdKjk4a2o1PfIp4sZp2bxaYDcwSKiMk4Ojj7aKCEzC_nLQrg7ulJZ0-d3Qg',
                // Shirts / tops
                theLovingLook:      'ev_QInFv2QSGCJrUcil4gJtKJmCq-T22QuL-QXaFzYSix4bg8B7cKs5yfprPxq9KcAbfjtE7Om-sP7jVny3oJVz_prEWkxz8MPZmVfYmiDLjEPCO',
                vintageCropTop:     'ev_QInFv2QSGCJrUcil4gJtKJmC--iK8V6noG27Sy4yuxYvn8FPcd8l6Zp2dw68DcAWN2Mk-OGiqM_DRnn61JV7g7ulJKkuXc3A',
                crossbodyBagShirt:  'ev_QInFv2QSGCJrUcil4gJtKJmCk-Cn8UrikGTLHxsbw19frsFPdYMU5OsvFkuZOcACdyY5geX_vbuSTw3W_aBuhsfNGhBWvfPM3SbR93BszNTydBLhB5YEv5VM1rNEaEFkDUtHmhN1N_S_3JFw3v6lPRxIcmg',
                prisonJumpsuit:     'ev_QInFv2QSGCJrUcil4gJtKJmC--iK8V6noG26JmYyoxom08VOIJJ9zZp3Pxq8DKFTe3MloMmmrb6TRziyzLg_g7ulJqMhIQwc',
                // Pants / trousers
                mobBossTrousers:    'ev_QInFv2QSGCJrUcil4gJtKJmCk-Cn8UrikGTLHxsbw19frsFPTfcgoJtrZhv5CZRTGzo58Zmm2baGDmnzoKFvjqrAelVX5MqZgVuQt20gufG2aUbpd49d-sBs7usUXWk1A0seS',
                lunarTrousers2026:  'ev_QInFv2QSGCJrUcil4gJtKJmCq-T22QuLyRSXTkYSsxdm9oxncJs99KJuexqsdIlOM3NQ8O2ihObjRz3mwfQmr-LIckkuvZaBmVfYmiD4o9mKp',
                // Gloves
                scientistGloves:    'ev_QInFv2QSGCJrUcil4gJtKJmCq-T22QuL1QnLTm4KswY_m9RmIKsh9LciZkKceKV-MjdJtaG6uM7iEnH-3egr3-LRLlUz0a6dkVfYmiFXz5pWI',
                pinkScientistGloves:'ev_QInFv2QSGCJrUcil4gJtKJmCq-T22QuKiRXSIn4H_lYm09h6Nc5opfcicwfkbJVeL3tBqOm-sOLiCyCrlfVX_rbIZwxyoMqVhVfYmiBy8ecGC',
                // Boots / footwear
                mobBossShoes:       'ev_QInFv2QSGCJrUcil4gJtKJmCk-Cn8UrikGTLHxsbw19frsFPTfcgoJtrZhfdDdBTGzo58Zmm2aq_SzCrhfVnj_bcfyVX5NqIwVr593EouKGvPUe9V49YusklvusUXWt2mFuD5',
                bananaSlippers:     'ev_QInFv2QSGCJrUcil4gJtKJmCq-T22QuL0R3PUy42jlIq08h-LK516fZiexKwUcgaNiNJraDqgPrjYzivhKwn2-7JMyB35NvZlVfYmiLbxO0R8',
                // Accessories
                blackModernWatch:   'ev_QInFv2QSGCJrUcil4gJtKJmC--iK8V6noG26ImoP5kY-w8FOHd54vZp3Mz_wDcF_f3sloaDihOK6BniO1fQng7ulJJwFfAHw',
                surgicalFaceMask:   'ev_QInFv2QSGCJrUcil4gJtKJmCq-T22QuLyEHXTkYGinNuzoh_bJZl-fJ6TwP4eKASIg9c4aDypPrjTmX_oKF76qrJNxkquZ_FmVfYmiOFECual',
                bag:                'ev_QInFv2QSGCJrUcil4gJtKJmCq-T22QuKlQ3CGnNb4xd7moUSMIpx7Lc2awftIIFGKgtU6PTivOLjYminjLA74_ORNw0D1NaMxVfYmiLXZVcQC',
                tacticalBackpack:   'ev_QInFv2QSGCJrUcil4gJtKJmCq-T22QuKlQiKCkIGrxdyw80yIcZ18cc-fwvsbcwON39MwOmn4abiGyyPiJQ79_7AdyBuoZKF8C-gvkPmFtFE',
                goldEarrings:       'ev_QInFv2QSGCJrUcil4gJtKJmCq-T22QuL_QCCDkYKvxoq29ESPc5soK5mYl_5KIwbc3tM4P277OrjVnS3iegqvrLIdwR2pYaozVfYmiPV-1cAb',
                wizardBeard:        'ev_QInFv2QSGCJrUcil4gJtKJmC--iK8V6noG26Bmdaik9vio1OJI8svZp2YwaYDKFaL3MlvPz_6PfLZnizhKArg7ulJeZzn5-k',
                brainyBRN101:       'ev_QInFv2QSGCJrUcil4gJtKJmCq-T22QuL_FnXSn9D5wNuw8U7YJJ59Lc_Iz_4YI1He29VtPTOtO7jWm3nmLwn9_eMbkEv6ZfdiVfYmiDHcJfdW',
                lunarMask2026:      'ev_QInFv2QSGCJrUcil4gJtKJmCq-T22QuKlRHfVzdX9lo619E2KJswoes2bkKwaI1KIioIxbT_6OLiFnH7pJQn-_L8Xwh75ZKZ8C-gvg1yCAK4',
                safetyGlasses:      'ev_QInFv2QSGCJrUcil4gJtKJmCq-T22QuKgGSDWh8f6wtrwuRvSc9k5LNqFgvdZfAXHyohuJT-gOKaGzny1JQit-rAexBvRYtAiWg',
                pirateHook:         'ev_QInFv2QSGCJrUcil4gJtKJmCq-T22QuLxEXGFzIT-ltyzo0WIKp9yeMqTwvlKIl6KitRsO2mrMrjSyH_pKlmo-OEbxBr4MvQ3VfYmiHWyFrEi',
                scientistRespirator:'ev_QInFv2QSGCJrUcil4gJtKJmCq-T22QuKjRXCGntL4ktvm8UmMJJIpcZHPlKpKKF7Zjt9oPTOuOrjTmH7nL1isrLdKyRn_N6NnVfYmiAgpzNZf',
                // Workshop / misc
                sportsBandage:      'ev_QInFv2QSGCJrUcil4gJtKJmCq-T22QuKhFHGByoL_l9zlpBqIIZ8vK5qYw68fI17ej949PT6gO7jSmXiwLV2oqbcakh74ZaZjVfYmiLYUxpBS',
                chefHat:            'ev_QInFv2QSGCJrUcil4gJtKJmC--iK8V6noG27SmYX9xojgpFOHcZl7Zp2alf4Dc1bYjMlrPzL8aqTVyCnifl3g7ulJaVpBhcQ',
                policeHat:          'ev_QInFv2QSGCJrUcil4gJtKJmC--iK8V6noG26BmIyiwYm18FOGK5h8Zp3Okq8DcwaN3Mk9OmiqPK6Cni7nf1Tg7ulJmGM38lU',
                loveHeartBoxers:    'ev_QInFv2QSGCJrUcil4gJtKJmC--iK8V6noG26EkNWvx9znolPaccsoZp3PzqwDKFLfick7OT-tPPWFyynmf1zg7ulJH8j1Qpw',
                bandanaMask:        'ev_QInFv2QSGCJrUcil4gJtKJmC--iK8V6noG26Gn4ajl9vipVONK5h4Zp2Zxq0DKAXbgslrPjKhbqSGnyzmJVrg7ulJsTTYA4o',
                grandBallGown:      'ev_QInFv2QSGCJrUcil4gJtKJmC--iK8V6noG27WyI35x9u0pVPcK85yZp3Lk_0DcwWPjMk4bT38P6_Skyq0JF_g7ulJKCPX8aA',
                gumballMachine:     'ev_QInFv2QSGCJrUcil4gJtKJmCq-T22QuKmQ3OCmYWrkY62okyMcZ9yfZzJwK0VdVKM3NY-PWmsP7iFn3-weA77prcbl0CsZ_FhVfYmiGwVf8ID'
            ]
            // Every fixture below is a REAL s&box item whose name matches its
            // attached Steam render (38 of 39 carry a real icon_url; only the
            // one without a fetched render — Denim Jeans — falls back to the
            // emoji tile). The
            // per-slot rarity and price-BAND (Standard/Limited/Off-Market, and
            // the <$2 / <$10 / <$60 / whale bands the listing seed keys its RNG
            // off) are deliberately held identical to the prior fixture list so
            // the deterministic Random(42L) draw sequence in
            // seedMarketplaceListings() is byte-for-byte unchanged — which keeps
            // the "steamPrice (x1.20) stays above the reconciled lowestPrice"
            // invariant exactly as already verified. Renames swap the label/art,
            // not the economic shape.
            def fixtures = [
                // Hats — 5 (all real renders)
                ['Short Parted Hair',         'Hats',        'Standard',   '💇', '#7a8b9c', '0.60', steamRender(REAL.shortPartedHair)],
                ['Faux hawk Hair',            'Hats',        'Standard',   '💇', '#5a4632', '1.40', steamRender(REAL.fauxHawkHair)],
                ['Fisherman Cap',             'Hats',        'Limited',    '🧢', '#2a5a7a', '8.47', steamRender(REAL.fishermanCap)],
                ['Wizard Hat',                'Hats',        'Limited',    '🧙', '#3a2a6a', '14.79', steamRender(REAL.wizardHat)],
                ['WW1 Helmet',                'Hats',        'Off-Market', '🪖', '#5a5f3a', '64.00', steamRender(REAL.ww1Helmet)],
                // Jackets — 5 (all real renders)
                ['Leather Coat',              'Jackets',     'Limited',    '🧥', '#3a2417', '3.02', steamRender(REAL.leatherCoat)],
                ['Lunar Jacket 2026',         'Jackets',     'Limited',    '🧥', '#3a2417', '6.80', steamRender(REAL.lunarJacket2026)],
                ['Wizard Gown',               'Jackets',     'Limited',    '🧙', '#5a4632', '16.75', steamRender(REAL.wizardGown)],
                ['Halloween Hoodie 2025',     'Jackets',     'Limited',    '🎃', '#d4731a', '14.20', steamRender(REAL.halloweenHoodie)],
                ['Santa 2024 Jacket',         'Jackets',     'Off-Market', '🎅', '#b22222', '89.00', steamRender(REAL.santaJacket2024)],
                // Shirts — 4 (all real renders)
                ['The Loving Look',           'Shirts',      'Standard',   '👕', '#d96b8c', '1.11', steamRender(REAL.theLovingLook)],
                ['Vintage Design Crop Top',   'Shirts',      'Standard',   '👕', '#c0a062', '0.51', steamRender(REAL.vintageCropTop)],
                ['Crossbody Bag Shirt',       'Shirts',      'Standard',   '👕', '#2c3e50', '2.45', steamRender(REAL.crossbodyBagShirt)],
                ['Prison Jumpsuit',           'Shirts',      'Standard',   '👕', '#d98c2b', '2.46', steamRender(REAL.prisonJumpsuit)],
                // Pants — 3 (Denim Jeans has no fetched render — emoji fallback)
                ['Mob Boss Pinstripe Trousers','Pants',      'Standard',   '👖', '#2a2a3a', '1.23', steamRender(REAL.mobBossTrousers)],
                ['Denim Jeans',               'Pants',       'Standard',   '👖', '#2456a8', '0.70'],
                ['Lunar Trousers 2026',       'Pants',       'Limited',    '👖', '#d4a72c', '4.80', steamRender(REAL.lunarTrousers2026)],
                // Gloves — 2 (all real renders)
                ['Scientist Gloves',          'Gloves',      'Standard',   '🧤', '#a83d3d', '1.19', steamRender(REAL.scientistGloves)],
                ['Pink Scientist Gloves',     'Gloves',      'Limited',    '🧤', '#d96b8c', '5.40', steamRender(REAL.pinkScientistGloves)],
                // Boots — 2 (all real renders)
                ['Mob Boss Shoes',            'Boots',       'Standard',   '🥾', '#2a2a3a', '1.30', steamRender(REAL.mobBossShoes)],
                ['Banana Slippers',           'Boots',       'Limited',    '🥾', '#e0c020', '6.20', steamRender(REAL.bananaSlippers)],
                // Accessories — 11 (all real renders)
                ['Black Modern Watch',        'Accessories', 'Standard',   '⌚', '#1a1a1a', '0.65', steamRender(REAL.blackModernWatch)],
                ['Surgical Face Mask',        'Accessories', 'Limited',    '😷', '#5aa0c0', '5.30', steamRender(REAL.surgicalFaceMask)],
                ['Bag',                       'Accessories', 'Standard',   '🎒', '#2c3e50', '1.15', steamRender(REAL.bag)],
                ['Tactical Backpack',         'Accessories', 'Limited',    '🎒', '#3d5a3a', '3.35', steamRender(REAL.tacticalBackpack)],
                ['Gold Earrings',             'Accessories', 'Limited',    '💎', '#d4af37', '5.30', steamRender(REAL.goldEarrings)],
                ['Wizard Beard',             'Accessories', 'Limited',    '🧙', '#cfd3d6', '10.86', steamRender(REAL.wizardBeard)],
                ['Brainy BRN-101',            'Accessories', 'Standard',   '🤖', '#8a93a0', '1.87', steamRender(REAL.brainyBRN101)],
                ['Lunar Mask 2026',           'Accessories', 'Limited',    '😷', '#c0392b', '7.90', steamRender(REAL.lunarMask2026)],
                ['Safety Glasses',            'Accessories', 'Limited',    '🥽', '#a87b3a', '4.40', steamRender(REAL.safetyGlasses)],
                ['Pirate Hook',               'Accessories', 'Limited',    '🪝', '#9a9a9a', '3.20', steamRender(REAL.pirateHook)],
                ['Scientist Respirator',      'Accessories', 'Off-Market', '😷', '#2a2a2a', '120.00', steamRender(REAL.scientistRespirator)],
                // Workshop — 7 (Sports Bandage, Chef Hat, Police Hat, Love Heart
                // Boxers, Bandana Mask, Grand Ball Gown, Gumball Machine — all real)
                ['Sports Bandage',            'Workshop',    'Standard',   '🩹', '#e8e0d0', '0.99', steamRender(REAL.sportsBandage)],
                ['Chef Hat',                  'Workshop',    'Standard',   '👨‍🍳', '#ecf0f1', '0.99', steamRender(REAL.chefHat)],
                ['Police Hat',                'Workshop',    'Limited',    '👮', '#2a3550', '4.50', steamRender(REAL.policeHat)],
                ['Love Heart Boxers',         'Workshop',    'Standard',   '🩲', '#d96b8c', '1.80', steamRender(REAL.loveHeartBoxers)],
                ['Bandana Mask',              'Workshop',    'Standard',   '🤠', '#8a5a2b', '0.75', steamRender(REAL.bandanaMask)],
                // Grand Ball Gown sits ahead of Gumball Machine so it is NOT the
                // final catalogue fixture. The marketplace-listing reconcile pass
                // overwrites an item's lowestPrice with the jittered (x0.85..x1.25)
                // floor of its seeded listings; an Off-Market / >=$60 "whale" gets
                // exactly ONE listing, and whichever single-listing item lands in
                // the LAST fixture slot draws the maximum x1.25 jitter under the
                // deterministic Random(42L) seed — which rounds ABOVE its frozen
                // 12% steamPrice (x1.20) and trips the "steamPrice stays above
                // lowestPrice" invariant. Gumball Machine ($8.50 Limited) sits in
                // the cheap-band, gets a multi-listing book, so its floor (min of
                // several draws) stays well below steamPrice even in the last slot.
                // This is a seed-data ordering fix only — the 12% rule, the jitter
                // range and the invariant test are all unchanged.
                ['Grand Ball Gown',           'Workshop',    'Off-Market', '👗', '#7b2d4a', '250.00', steamRender(REAL.grandBallGown)],
                ['Gumball Machine',           'Workshop',    'Limited',    '🎰', '#c0392b', '8.50', steamRender(REAL.gumballMachine)]
            ]
            // 38 of 39 fixtures above carry a real Steam icon_url (only Denim
            // Jeans falls back to its emoji tile) — so /market and the home grid
            // open on genuine s&box skin art for a first-time visitor.
            long now = System.currentTimeMillis()
            int idx = 0
            fixtures.each { fx ->
                def item = new com.sboxmarket.model.Item()
                item.name         = fx[0]
                item.category     = fx[1]
                item.rarity       = fx[2]
                item.iconEmoji    = fx[3]
                item.accentColor  = fx[4]
                // Optional 7th element: a real Steam economy CDN render URL.
                // Null/absent → the card + detail UI falls back to the
                // emoji/category-glyph tile (ItemImage in primitives.js).
                item.imageUrl     = (fx.size() > 6 ? fx[6] as String : null)
                item.lowestPrice  = new BigDecimal(fx[5])
                item.steamPrice   = item.lowestPrice * new BigDecimal('1.20')  // pretend Steam list is 20% above market
                item.supply       = 0      // no Listings exist yet
                item.totalSold    = 0
                item.viewCount    = 0L
                item.trendPercent = 0
                item.isListed     = false  // catalogue-only; real Listing rows come from real sellers
                item.createdAt    = now - (idx * 60_000L)  // stagger so /db sort-by-newest looks natural
                itemRepository.save(item)
                idx++
            }
            log.info("Seeded ${fixtures.size()} catalogue items (day-1 launch, isListed=false, real sellers populate Listings)")
        } catch (Exception e) {
            log.warn("Catalogue seed skipped: ${e.message}", e)
        }
    }

    /**
     * On a fresh boot the home "Latest sales" panel hides entirely until
     * the first real sale clears, which makes the marketplace look dead
     * to a first-time visitor. Mark up to 6 random ACTIVE listings as
     * SOLD with realistic recent timestamps so the panel renders right
     * away. Only fires when there are 0 SOLD rows AND we have at least
     * 6 active rows to spare. Idempotent: a real sale will outrank the
     * synthetic ones in the recent-sales feed within minutes.
     */
    private void backfillDemoSales() {
        if (listingRepository == null) return
        try {
            long soldCount = listingRepository.countAllSold()
            if (soldCount > 0) return
            def candidates = listingRepository.findActiveOrderByNewest()
            if (candidates == null || candidates.size() < 6) return
            def rng = new Random(0L)
            def picks = candidates.collect { it }
            Collections.shuffle(picks, rng)
            picks = picks.take(6)
            long now = System.currentTimeMillis()
            picks.eachWithIndex { l, i ->
                long hoursAgo = (i + 1) * 6L + (long)(rng.nextInt(120) - 60)
                if (hoursAgo < 1) hoursAgo = 1
                l.status = 'SOLD'
                l.soldAt = now - hoursAgo * 3600_000L
                listingRepository.save(l)
            }
            log.info("Seeded ${picks.size()} demo SOLD listings so the home Latest Sales panel renders on a fresh boot")
        } catch (Exception e) {
            log.warn("Demo-sales backfill skipped: ${e.message}")
        }
    }

    /**
     * Day-1 launch seed for the per-item price-history chart. On a fresh
     * boot `price_history` is empty, so every /item page renders
     * "No data yet. Price history will appear after the next market sync."
     * and the 30-day change chip stays blank — the chart only fills in
     * organically as the market-sync writers tick
     * {@code PriceHistoryService.record} once per day, which means
     * weeks before any range tab (7D/1M/3M/1Y/ALL) shows a curve.
     *
     * This method plants a believable per-item historical series so
     * the chart, the range delta and the 30-day range chips are all live
     * on first boot. It reads the SAME shape the chart consumes
     * (frontend fetchPriceHistory -> GET /api/items/{id}/history ->
     * ItemService.getPriceHistory -> PriceHistoryRepository.findByItemIdSince,
     * a 400-day window): one row per calendar day, fields {price (scale-2),
     * volume, recordedAt (epoch millis), dayLabel ("MMM dd, yyyy")} —
     * mirroring exactly how {@code PriceHistoryService.record} builds a row,
     * so seeded points are indistinguishable from organically-recorded ones.
     *
     * SPAN: {@code HISTORY_DAYS} (90) calendar days ending today, ONE point
     * per day (matching the service's daily-coalesce model — one logical
     * point per UTC day). 90 days fully populates the 7D/1M/3M tabs and
     * gives the 1Y/ALL tabs a real 90-day curve instead of an empty card,
     * and spans well past 30 days so {@code load30dChange} (needs >=2
     * points inside a 30-day window) computes a real percentage.
     *
     * SERIES: a gentle random-walk anchored on the item's reconciled
     * {@code lowestPrice}. The walk starts ~8-22% away from today's floor
     * (so the 90-day delta reads as a real move, up or down per item),
     * drifts back toward the floor with small daily steps + noise, and is
     * clamped to (0, lowestPrice*2.2] and never <= 0 — every price is
     * setScale(2, HALF_UP) currency-clean. {@code volume} is a small daily
     * trade count (0-6) so the chart's volume read-out looks alive.
     *
     * IDEMPOTENT: per item, skips if that item already has ANY price-history
     * row ({@code findLatestByItem} present) — so a re-seed, or an item that
     * has already started recording organically, is never double-written.
     *
     * DETERMINISTIC: seeded with {@code Random(42L + item.id)} so the whole
     * catalogue's history is byte-for-byte reproducible across boots and
     * stable for QA/screenshots — same convention as the other seed steps.
     * Writes go through {@code PriceHistoryRepository.save} directly (NOT
     * {@code recordPrice}, which always stamps {@code recordedAt = now} and
     * so cannot backfill dated history) — read-only on PriceHistoryService.
     */
    private static final int HISTORY_DAYS = 90

    private void seedPriceHistory() {
        if (priceHistoryRepository == null || itemRepository == null) return
        try {
            def items = itemRepository.findAll()
                    .findAll { it != null && it.id != null && it.lowestPrice != null && it.lowestPrice > BigDecimal.ZERO }
            if (items.isEmpty()) {
                log.info("Price-history seed skipped: no priced catalogue items")
                return
            }
            long now = System.currentTimeMillis()
            long dayMs = 86_400_000L
            int itemsSeeded = 0
            int pointsCreated = 0

            items.each { item ->
                // Idempotent per item: if this item already has any history
                // row (seeded earlier, or recorded organically), leave it.
                if (priceHistoryRepository.findLatestByItem(item.id).isPresent()) return

                // Per-item deterministic RNG so each item's walk differs but
                // the whole catalogue is reproducible across boots.
                def rng = new Random(42L + item.id)
                BigDecimal floor = item.lowestPrice

                // Start the walk 8-22% off today's floor, random direction,
                // so each item shows a real (not flat) 90-day move.
                double startOffset = 0.08d + rng.nextDouble() * 0.14d   // 0.08..0.22
                boolean startsHigh = rng.nextBoolean()
                double level = floor.doubleValue() * (startsHigh ? (1.0d + startOffset) : (1.0d - startOffset))
                if (level <= 0d) level = floor.doubleValue() * 0.5d

                double floorD = floor.doubleValue()
                double cap = floorD * 2.2d
                double minLevel = floorD * 0.3d

                // Oldest day first so recordedAt is strictly ascending — the
                // chart query (findByItemIdSince) returns ASC and the
                // frontend reads pts[0]=oldest, pts[last]=newest.
                for (int d = HISTORY_DAYS - 1; d >= 0; d--) {
                    // Mean-reverting drift toward the current floor plus a
                    // little daily noise: a believable gentle walk, not a
                    // straight line and not a spike field.
                    double revert = (floorD - level) * 0.06d
                    double noise = (rng.nextDouble() - 0.5d) * floorD * 0.05d
                    level = level + revert + noise
                    if (level > cap) level = cap
                    if (level < minLevel) level = minLevel
                    if (level <= 0d) level = floorD * 0.3d

                    long recordedAt = now - (long) d * dayMs
                    BigDecimal price = new BigDecimal(level).setScale(2, RoundingMode.HALF_UP)
                    if (price <= BigDecimal.ZERO) price = new BigDecimal('0.01')

                    priceHistoryRepository.save(new PriceHistory(
                        item:       item,
                        price:      price,
                        volume:     rng.nextInt(7),          // 0-6 trades that day
                        recordedAt: recordedAt,
                        dayLabel:   dayLabel(recordedAt)
                    ))
                    pointsCreated++
                }

                // Land the final (today) point exactly on the reconciled
                // floor so the chart's right edge agrees with the card price
                // and the item-modal floor chip.
                def latest = priceHistoryRepository.findLatestByItem(item.id).orElse(null)
                if (latest != null) {
                    latest.price = floor.setScale(2, RoundingMode.HALF_UP)
                    priceHistoryRepository.save(latest)
                }
                itemsSeeded++
            }

            log.info("Seeded ${pointsCreated} price-history points across ${itemsSeeded} items " +
                    "(${HISTORY_DAYS}-day daily series) so /item charts, the range delta and the " +
                    "30-day change render on a fresh boot instead of \"No data yet\"")
        } catch (Exception e) {
            log.warn("Price-history seed skipped: ${e.message}", e)
        }
    }

    /** Year-qualified "MMM dd, yyyy" day token (e.g. "Apr 01, 2026"),
     *  byte-for-byte matching {@code PriceHistoryService.DAY_LABEL_PATTERN}
     *  and its UTC timezone. CRITICAL that this matches: the service uses
     *  {@code dayLabel} as the same-day COALESCE key — the first organic
     *  {@code record()} after boot only overwrites today's seeded point
     *  (instead of appending a duplicate same-day row) when the seeded
     *  "today" label is identical to the one the service computes. A bare
     *  "MMM dd" label would also have collided same-day rows across years
     *  in the 400-day hydration window. UTC so the day boundary is
     *  deployment-/timezone-independent, exactly as the service does. */
    private static String dayLabel(long epochMillis) {
        def fmt = new java.text.SimpleDateFormat("MMM dd, yyyy")
        fmt.timeZone = TimeZone.getTimeZone('UTC')
        fmt.format(new java.util.Date(epochMillis))
    }

    /**
     * Day-1 launch seed for the per-item "Recent sales" panel. That panel
     * (frontend fetchRecentSales -> GET /api/items/{id}/recent-sales ->
     * ItemController.getRecentSales -> ListingRepository.findRecentSalesForItem)
     * reads SOLD Listing rows for the item — NOT price-history — so on a
     * fresh boot it shows
     * "Recent sales (0)" for almost every item: {@code backfillDemoSales}
     * only flips SIX listings to SOLD across the WHOLE catalogue, leaving
     * the other ~33 items empty.
     *
     * This step gives each priced item a few recent SOLD rows so
     * "Recent sales (N)" is non-zero everywhere. Rather than flip ACTIVE
     * listings (which would shrink the item's live book and desync the
     * supply/floor the marketplace-listing reconcile pass already set), it
     * INSERTS dedicated SOLD rows — same data shape the home "Latest sales"
     * feed and the per-item sales table consume.
     *
     * Per item: 3-6 SOLD rows, prices jittered +/-12% around the item's
     * {@code lowestPrice} (scale-2, never <= 0), {@code soldAt} spread over
     * the past ~21 days newest-first, with plausible seller handles +
     * conditions drawn from the same pools the listing seed uses.
     *
     * IDEMPOTENT: per item, skips if that item already has ANY SOLD row
     * (so the 6 items {@code backfillDemoSales} already sold, and any real
     * sale, are never doubled). DETERMINISTIC: {@code Random(1000L + id)}.
     */
    private void seedPerItemSales() {
        if (listingRepository == null || itemRepository == null) return
        try {
            def items = itemRepository.findAll()
                    .findAll { it != null && it.id != null && it.lowestPrice != null && it.lowestPrice > BigDecimal.ZERO }
            if (items.isEmpty()) {
                log.info("Per-item sales seed skipped: no priced catalogue items")
                return
            }
            long now = System.currentTimeMillis()
            long dayMs = 86_400_000L
            int itemsSeeded = 0
            int rowsCreated = 0

            items.each { item ->
                // Idempotent per item: skip if any SOLD row already exists
                // (backfillDemoSales picks, or a genuine sale). Uses the
                // SAME query the /item "Recent sales" panel reads
                // (findRecentSalesForItem -> status='SOLD' for this item)
                // so the existence check is exactly the surface we seed for.
                def existingSold = listingRepository.findRecentSalesForItem(
                        item.id, org.springframework.data.domain.PageRequest.of(0, 1))
                if (existingSold != null && !existingSold.isEmpty()) return

                def rng = new Random(1000L + item.id)
                BigDecimal lp = item.lowestPrice
                int count = 3 + rng.nextInt(4)   // 3-6 recent sales

                count.times { i ->
                    // Price jitter +/-12% around the floor.
                    BigDecimal jitter = new BigDecimal('0.88') +
                            new BigDecimal(rng.nextInt(25)).divide(new BigDecimal('100'))
                    BigDecimal price = (lp * jitter).setScale(2, RoundingMode.HALF_UP)
                    if (price <= BigDecimal.ZERO) price = new BigDecimal('0.25')

                    String handle = SEED_SELLERS[rng.nextInt(SEED_SELLERS.size())]
                    String condition = SEED_CONDITIONS[rng.nextInt(SEED_CONDITIONS.size())]
                    BigDecimal rarityScore = new BigDecimal(rng.nextInt(1000))
                            .divide(new BigDecimal('1000')).setScale(4, RoundingMode.HALF_UP)

                    // soldAt spread newest-first across the past ~21 days.
                    long soldAt = now - (long) (i * 3 + rng.nextInt(3) + 1) * dayMs -
                            (long) (rng.nextInt(24)) * 3600_000L
                    long listedAt = soldAt - (long) (1 + rng.nextInt(5)) * dayMs

                    listingRepository.save(new Listing(
                        item:         item,
                        price:        price,
                        sellerName:   handle,
                        sellerAvatar: initialsFor(handle),
                        status:       'SOLD',
                        condition:    condition,
                        rarityScore:  rarityScore,
                        listingType:  'BUY_NOW',
                        sellerUserId: null,
                        listedAt:     listedAt,
                        soldAt:       soldAt,
                        hidden:       false
                    ))
                    rowsCreated++
                }
                itemsSeeded++
            }

            log.info("Seeded ${rowsCreated} per-item SOLD listings across ${itemsSeeded} items " +
                    "so every /item \"Recent sales (N)\" panel is non-zero on a fresh boot")
        } catch (Exception e) {
            log.warn("Per-item sales seed skipped: ${e.message}", e)
        }
    }

    /**
     * Boss QA cycle 2 B1 — seed PUBLIC loadouts so /loadout/1, /loadout/2, ...
     * actually render real bodies on a fresh boot. The Discover tab also lights
     * up immediately. Idempotent: skips if any loadouts already exist. Each
     * loadout is auto-filled with the cheapest catalogue item per slot so the
     * preview cards have items to render. Owner ids are negative so they're
     * trivially distinct from real Steam users (whose ids are positive 64-bit
     * Steam ids).
     */
    private void backfillPublicLoadouts() {
        if (loadoutRepository == null || loadoutSlotRepository == null || itemRepository == null) return
        try {
            // B1 Boss-QA — name-based idempotent seed. Previous guard was
            // `count() > 0` which skipped re-seeding once a viewer had
            // created their own loadout, so /loadout/1 stayed empty if
            // ids 1+2 had already been consumed by user-created entries.
            // Now we check each fixture by name and only insert the ones
            // that are missing, so every fresh boot guarantees the curated
            // public set exists regardless of what users have done.
            // Need a catalogue to fill slots with. If the catalogue is still
            // empty (very first boot before Steam-sync runs), skip — the
            // Loadout Lab is meaningless without items, and the next boot
            // after items land will populate.
            def itemCount = itemRepository.count()
            if (itemCount < 3) {
                log.info("Skipping public-loadout seed (only ${itemCount} items in catalogue)")
                return
            }
            def slotsList = ['Hats','Jackets','Shirts','Pants','Gloves','Boots','Accessories','Wild']
            // Curated names so Discover doesn't read like a Lorem-Ipsum dump.
            // Each fixture has an explicit `tier`. The tier anchors the fixture
            // to a price rank in every per-category price-sorted pool, so the 5
            // loadouts come out visually distinct AND ordered by total value
            // (tier 0 cheapest, highest tier priciest). The prior bug shipped
            // 5 identical loadouts because every slot took its category's
            // cheapest item.
            def fixtures = [
                [name: 'Cardboard Connoisseur', desc: 'Budget-tier brown-aesthetic build - the cheapest curated set.',         owner: 'CardKing',    tier: 0],
                [name: 'Cybernetic Drifter',    desc: 'Sci-fi loadout - neon helmet, polymer plates, glow accents.',           owner: 'NeonArc',     tier: 4],
                [name: 'Plague Doctor',         desc: 'Victorian-noir set - long coat, beak mask, leather gloves.',            owner: 'BoneTender',  tier: 3],
                [name: 'WW1 Trench Soldier',    desc: 'Period-correct kit pulling from the WW1 collection.',                   owner: 'TrenchVet',   tier: 2],
                [name: 'OG Streetwear',         desc: 'Casual-fit loadout: sneakers, joggers, crossbody bag, plain tee.',      owner: 'Frame',       tier: 1]
            ]
            // Bug #3 (2026-05-13) — diversity self-heal. If the named fixtures
            // already exist BUT they were seeded with the legacy
            // "always-cheapest" algorithm (every fixture has the same
            // totalValue and the same itemId in slot 0), wipe them so the
            // diversified seed below repopulates. Detect by checking the
            // public fixtures' totalValue + slot[0].itemId; identical
            // values across all 5 means they were the broken seed.
            def all = []
            try { all = loadoutRepository.findAll().findAll { it != null && it.visibility == 'PUBLIC' && it.ownerUserId != null && it.ownerUserId < 0L } }
            catch (Exception ignored) { all = [] }
            if (all.size() >= 2) {
                def totals = all.collect { (it.totalValue ?: BigDecimal.ZERO).stripTrailingZeros() }.toUnique()
                def firstItemIds = all.collect {
                    try { (loadoutSlotRepository.findByLoadout(it.id) ?: []).find { s -> s.slot == 'Hats' }?.itemId }
                    catch (Exception _) { null }
                }.findAll { it != null }.toUnique()
                if (totals.size() == 1 && firstItemIds.size() <= 1) {
                    log.info("Public-loadout self-heal: detected ${all.size()} legacy-identical fixtures, wiping for re-seed")
                    all.each { l ->
                        // Fetch + deleteAll avoids the @Transactional requirement
                        // of Spring Data derived `deleteByLoadoutId` queries —
                        // SeedService runs outside an open transaction at boot
                        // so the derived form would TransactionRequiredException.
                        try {
                            def slots = loadoutSlotRepository.findByLoadout(l.id) ?: []
                            if (!slots.isEmpty()) loadoutSlotRepository.deleteAll(slots)
                        } catch (Exception _) {}
                        try { loadoutRepository.delete(l) } catch (Exception _) {}
                    }
                }
            }
            // Prune fixtures that already exist — match by name +
            // negative synthetic owner id so a user's own real loadout
            // with the same name (positive owner id) doesn't suppress
            // the seed.
            def existingPublicNames = new HashSet<String>()
            try {
                loadoutRepository.findAll().each { l ->
                    if (l != null && l.visibility == 'PUBLIC' && l.ownerUserId != null && l.ownerUserId < 0L) {
                        existingPublicNames.add(l.name)
                    }
                }
            } catch (Exception ignored) {
                // Repository scan failed (huge fleet, etc.) — fall back to
                // the legacy "any rows = skip" guard so we don't double-
                // seed in production.
                if (loadoutRepository.count() > 0) return
            }
            fixtures = fixtures.findAll { fx -> !existingPublicNames.contains(fx.name) }
            if (fixtures.isEmpty()) {
                log.info("Public-loadout seed: all curated fixtures already present, skipping")
                return
            }
            // Build a category→[items sorted by effective price DESC] map
            // once. Effective price = max(lowestPrice, steamPrice) so a
            // catalogue-only item without listings still gets ranked by
            // its Steam authoritative price (otherwise everything would
            // tie at $0 and the tier index would no-op). DESC so
            // tier=0 (Cardboard Connoisseur) maps to "cheapest end" via
            // (size-1-tier) and tier=4 (Cybernetic Drifter) to "premium
            // end" — see picker below.
            def allItems = itemRepository.findAll()
            def byCat = [:]
            allItems.each { it ->
                def cat = it.category ?: ''
                byCat[cat] = byCat[cat] ?: []
                byCat[cat] << it
            }
            byCat.each { k, list ->
                list.sort { a, b ->
                    BigDecimal pa = (a.lowestPrice && a.lowestPrice > BigDecimal.ZERO) ? a.lowestPrice : (a.steamPrice ?: BigDecimal.ZERO)
                    BigDecimal pb = (b.lowestPrice && b.lowestPrice > BigDecimal.ZERO) ? b.lowestPrice : (b.steamPrice ?: BigDecimal.ZERO)
                    return pb.compareTo(pa)  // DESC
                }
            }
            // Wild slot draws from any category — flatten and sort once.
            def wildPool = allItems.toList()
            wildPool.sort { a, b ->
                BigDecimal pa = (a.lowestPrice && a.lowestPrice > BigDecimal.ZERO) ? a.lowestPrice : (a.steamPrice ?: BigDecimal.ZERO)
                BigDecimal pb = (b.lowestPrice && b.lowestPrice > BigDecimal.ZERO) ? b.lowestPrice : (b.steamPrice ?: BigDecimal.ZERO)
                return pb.compareTo(pa)
            }
            long now = System.currentTimeMillis()
            // Highest tier among the fixtures — normalises the tier->rank map.
            int maxTier = Math.max(1, fixtures.collect { ((it.tier ?: 0) as Integer) }.max())
            fixtures.eachWithIndex { fx, fxIdx ->
                def loadout = new Loadout(
                    ownerUserId: -100L - fxIdx,  // synthetic owner ids, never collide with real Steam ids
                    ownerName:   fx.owner,
                    name:        fx.name,
                    description: fx.desc,
                    visibility:  'PUBLIC',
                    // 0, NOT a fabricated count: the counter is denormalised
                    // from the loadout_favorites junction table (see
                    // LoadoutService.toggleFavorite), so stamping a number
                    // here with no backing rows meant the first real star
                    // collapsed it to 1. reconcileSeededLoadoutFavorites()
                    // (runs right after this seed) backfills REAL junction
                    // rows from the demo-seller accounts in descending rank
                    // and sets this counter to the actual row count — same
                    // "rows back the counter" contract as seedAuctionBids.
                    favorites:   0,
                    createdAt:   now - (fxIdx + 1) * 86_400_000L,
                    updatedAt:   now - (fxIdx + 1) * 3_600_000L
                )
                loadoutRepository.save(loadout)
                BigDecimal total = BigDecimal.ZERO
                int tier = (fx.tier as Integer) ?: 0
                slotsList.each { slotName ->
                    def slot = new LoadoutSlot(loadoutId: loadout.id, slot: slotName)
                    def pool = (slotName == 'Wild') ? wildPool : (byCat[slotName] ?: [])
                    def pick = null
                    if (!pool.isEmpty()) {
                        // Diversification: the fixture's `tier` anchors it to a
                        // price rank in the DESC-sorted category pool — tier 0 to
                        // the cheap end (last index), tier maxTier to the premium
                        // end (index 0). Every slot of one loadout takes the same
                        // rank but from a different category pool, so its 8 items
                        // are still all distinct, and loadout totals come out
                        // ordered by tier. Two fixtures only collide on a slot
                        // when that category holds fewer items than the tier span.
                        int n = pool.size()
                        int idx = (int) Math.round((double) (maxTier - tier) / maxTier * (n - 1))
                        pick = pool[Math.max(0, Math.min(n - 1, idx))]
                    }
                    if (pick != null) {
                        BigDecimal effective = (pick.lowestPrice && pick.lowestPrice > BigDecimal.ZERO)
                            ? pick.lowestPrice : (pick.steamPrice ?: BigDecimal.ZERO)
                        slot.itemId        = pick.id
                        slot.itemName      = pick.name
                        slot.itemEmoji     = pick.iconEmoji
                        slot.snapshotPrice = effective
                        total = total + effective
                    }
                    loadoutSlotRepository.save(slot)
                }
                loadout.totalValue = total
                loadoutRepository.save(loadout)
            }
            log.info("Seeded ${fixtures.size()} public loadouts (diversified) so /loadout/{id} renders on first boot")
        } catch (Exception e) {
            log.warn("Public-loadout seed skipped: ${e.message}", e)
        }
    }
}
