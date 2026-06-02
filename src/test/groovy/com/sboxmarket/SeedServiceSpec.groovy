package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Loadout
import com.sboxmarket.model.LoadoutSlot
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.model.PriceHistory
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.LoadoutRepository
import com.sboxmarket.repository.LoadoutSlotRepository
import com.sboxmarket.repository.PriceHistoryRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.SeedService
import spock.lang.Specification
import spock.lang.Subject

/**
 * SeedService is the first-boot bootstrap. The spec pins the two
 * properties an operator depends on:
 *
 *   1. IDEMPOTENCY — re-running the seed on every app restart must not
 *      duplicate rows; each helper is gated on a `count() == 0`-style
 *      guard and must skip cleanly when data already exists.
 *
 *   2. NO-CRASH — a seed failure (repository throw, bad fixture) must
 *      never abort application startup. Because `seed()` is wired into a
 *      CommandLineRunner, an escaping exception kills the boot. The
 *      helpers swallow failures; `seed()` itself is deliberately NOT
 *      @Transactional so a swallowed PersistenceException can't poison a
 *      shared transaction and surface as UnexpectedRollbackException at
 *      commit time (which WOULD crash boot).
 *
 * Plus seeded-data sanity: scale-2 money, valid rarities, consistent
 * auction fields, referential integrity.
 */
class SeedServiceSpec extends Specification {

    WalletRepository       walletRepository       = Mock()
    ItemRepository         itemRepository         = Mock()
    ListingRepository      listingRepository      = Mock()
    LoadoutRepository      loadoutRepository      = Mock()
    LoadoutSlotRepository  loadoutSlotRepository  = Mock()
    PriceHistoryRepository priceHistoryRepository = Mock()
    SteamUserRepository    steamUserRepository    = Mock()

    @Subject
    SeedService service = new SeedService(
        walletRepository     : walletRepository,
        itemRepository       : itemRepository,
        listingRepository    : listingRepository,
        loadoutRepository    : loadoutRepository,
        loadoutSlotRepository: loadoutSlotRepository
    )

    /** Second subject that ALSO wires the optional priceHistoryRepository,
     *  so the price-history seed (inert on the bare `service` subject where
     *  that bean is null, mirroring the @Autowired(required=false) field)
     *  can be exercised in isolation without perturbing the other specs'
     *  strict interaction counts. */
    SeedService serviceWithHistory = new SeedService(
        walletRepository      : walletRepository,
        itemRepository        : itemRepository,
        listingRepository     : listingRepository,
        loadoutRepository     : loadoutRepository,
        loadoutSlotRepository : loadoutSlotRepository,
        priceHistoryRepository: priceHistoryRepository
    )

    /** Third subject that ALSO wires the optional steamUserRepository so
     *  the demo-seller seed (inert on the bare `service` subject where that
     *  bean is null, mirroring the @Autowired(required=false) field) can be
     *  exercised in isolation. Kept separate so the demo-seller writes don't
     *  perturb the strict interaction counts the other specs pin. */
    SeedService serviceWithSellers = new SeedService(
        walletRepository      : walletRepository,
        itemRepository        : itemRepository,
        listingRepository     : listingRepository,
        loadoutRepository     : loadoutRepository,
        loadoutSlotRepository : loadoutSlotRepository,
        steamUserRepository   : steamUserRepository
    )

    // ── demo wallet ──────────────────────────────────────────────────

    def "seed creates demo wallet when the wallet table is empty"() {
        given:
        walletRepository.count() >> 0L

        when:
        service.seed()

        then:
        1 * walletRepository.save({ Wallet w ->
            w.username == 'demo' && w.balance == new BigDecimal("250.00") && w.currency == 'USD'
        })
    }

    def "seed does NOT recreate the demo wallet on subsequent boots"() {
        given:
        walletRepository.count() >> 5L

        when:
        service.seed()

        then:
        0 * walletRepository.save(_)
    }

    def "demo wallet starting balance is currency-clean (scale 2)"() {
        given:
        walletRepository.count() >> 0L
        Wallet captured = null

        when:
        service.seed()

        then:
        1 * walletRepository.save(_) >> { Wallet w -> captured = w; w }
        captured.balance.scale() == 2
        captured.balance == new BigDecimal("250.00")
    }

    // ── idempotency — full re-boot over already-seeded data ──────────

    def "re-running seed over a fully-populated DB inserts NOTHING (idempotent restart)"() {
        given: "every table already has rows — a normal app restart"
        walletRepository.count() >> 3L
        itemRepository.count() >> 32L
        listingRepository.count() >> 80L
        listingRepository.countAllSold() >> 6L
        loadoutRepository.count() >> 5L
        // self-heal + name-prune scans see the existing curated loadouts
        loadoutRepository.findAll() >> publicLoadoutFixtureRows()
        // The per-item price-history + recent-sales seeds iterate the live
        // catalogue; on a populated restart both must be no-ops. findAll
        // returns the existing items, and each item already has a SOLD row
        // (recent-sales) so seedPerItemSales hits its idempotent skip path
        // and writes nothing. (priceHistoryRepository is null on this
        // subject, so seedPriceHistory short-circuits before any query.)
        itemRepository.findAll() >> existingCatalogueRows()
        listingRepository.findRecentSalesForItem(_, _) >> [new Listing(status: 'SOLD')]

        when:
        service.seed()

        then: "no writes of any kind"
        0 * walletRepository.save(_)
        0 * itemRepository.save(_)
        0 * listingRepository.save(_)
        0 * loadoutRepository.save(_)
        0 * loadoutSlotRepository.save(_)
        and: "and nothing is deleted either"
        0 * loadoutRepository.delete(_)
        0 * loadoutSlotRepository.deleteAll(_)
    }

    def "catalogue seed skips when even one item already exists"() {
        given:
        walletRepository.count() >> 1L
        itemRepository.count() >> 1L          // not empty -> skip
        listingRepository.count() >> 0L
        listingRepository.findActiveOrderByNewest() >> []
        listingRepository.countAllSold() >> 0L
        itemRepository.findAll() >> []
        loadoutRepository.findAll() >> []

        when:
        service.seed()

        then:
        0 * itemRepository.save(_)
    }

    def "marketplace-listing seed skips the instant any listing exists"() {
        given:
        walletRepository.count() >> 1L
        itemRepository.count() >> 32L
        listingRepository.count() >> 1L       // a real seller already posted -> skip
        listingRepository.countAllSold() >> 0L
        listingRepository.findActiveOrderByNewest() >> []
        loadoutRepository.findAll() >> []

        when:
        service.seed()

        then:
        0 * listingRepository.save(_)
    }

    def "demo-sales backfill skips when a SOLD listing already exists"() {
        given:
        walletRepository.count() >> 1L
        itemRepository.count() >> 32L
        listingRepository.count() >> 80L
        listingRepository.countAllSold() >> 1L   // a real sale cleared -> skip
        loadoutRepository.findAll() >> []

        when:
        service.seed()

        then:
        0 * listingRepository.findActiveOrderByNewest()
        0 * listingRepository.save(_)
    }

    // ── no-crash contract ────────────────────────────────────────────

    def "a throw from the demo-wallet seed does NOT abort startup"() {
        given:
        walletRepository.count() >> { throw new RuntimeException("DB down") }
        itemRepository.count() >> 32L
        listingRepository.count() >> 80L
        listingRepository.countAllSold() >> 6L
        loadoutRepository.findAll() >> []

        when:
        service.seed()

        then: "swallowed — seed() returns normally"
        noExceptionThrown()
    }

    def "a throw from any catalogue/listing/loadout helper does NOT abort startup"() {
        given: "every repository call blows up"
        walletRepository.count() >> 0L
        walletRepository.save(_) >> { throw new RuntimeException("constraint clash") }
        itemRepository.count() >> { throw new RuntimeException("boom") }
        listingRepository.count() >> { throw new RuntimeException("boom") }
        listingRepository.countAllSold() >> { throw new RuntimeException("boom") }
        loadoutRepository.count() >> { throw new RuntimeException("boom") }
        loadoutRepository.findAll() >> { throw new RuntimeException("boom") }

        when:
        service.seed()

        then: "every failure is swallowed; the app still boots"
        noExceptionThrown()
    }

    def "seed runs cleanly when optional repositories are absent (required=false beans null)"() {
        given: "listing/loadout/item beans never registered"
        SeedService bare = new SeedService(walletRepository: walletRepository)
        walletRepository.count() >> 0L

        when:
        bare.seed()

        then: "wallet still seeded, no NPE from the null optional repos"
        noExceptionThrown()
        1 * walletRepository.save(_)
    }

    // ── seeded catalogue data sanity ─────────────────────────────────

    def "seeded catalogue items all have scale-2 prices and valid rarities"() {
        given:
        walletRepository.count() >> 1L
        itemRepository.count() >> 0L          // empty -> catalogue seed fires
        listingRepository.count() >> 0L
        listingRepository.findActiveOrderByNewest() >> []
        listingRepository.countAllSold() >> 0L
        loadoutRepository.findAll() >> []
        // save() is hit twice per catalogue row (insert, then the
        // marketplace-listing reconcile pass re-saves the Item) — only
        // assign an id + record the row on first insert so `saved`
        // reflects the 32 distinct catalogue rows, not 64.
        List<Item> saved = []
        itemRepository.save(_) >> { Item it ->
            if (it.id == null) it.id = (saved.size() + 1L)
            if (!saved.contains(it)) saved << it
            it
        }
        // after catalogue seed, itemRepository.findAll() backs the later helpers
        itemRepository.findAll() >> { saved }
        // Per-item recent-sales seed runs after the catalogue seed; stub it
        // to the "already has a SOLD row" skip path so it adds no SOLD
        // Listing rows that would pollute this catalogue-shape assertion.
        listingRepository.findRecentSalesForItem(_, _) >> [new Listing(status: 'SOLD')]

        when:
        service.seed()

        then:
        saved.size() == 39
        saved.every { it.lowestPrice != null && it.lowestPrice.scale() == 2 }
        saved.every { it.lowestPrice > BigDecimal.ZERO }
        saved.every { it.rarity in ['Standard', 'Limited', 'Off-Market'] }
        and: "every chip-filterable category is represented (no empty grid on a chip click)"
        ['Hats','Jackets','Shirts','Pants','Gloves','Boots','Accessories'].every { cat ->
            saved.any { it.category == cat }
        }
    }

    def "seeded catalogue attaches real Steam CDN renders to the overwhelming majority of items and leaves only the unmatched one on the emoji fallback"() {
        given:
        walletRepository.count() >> 1L
        itemRepository.count() >> 0L          // empty -> catalogue seed fires
        listingRepository.count() >> 0L
        listingRepository.findActiveOrderByNewest() >> []
        listingRepository.countAllSold() >> 0L
        loadoutRepository.findAll() >> []
        List<Item> saved = []
        itemRepository.save(_) >> { Item it ->
            if (it.id == null) it.id = (saved.size() + 1L)
            if (!saved.contains(it)) saved << it
            it
        }
        itemRepository.findAll() >> { saved }
        listingRepository.findRecentSalesForItem(_, _) >> [new Listing(status: 'SOLD')]

        when:
        service.seed()

        then: "38 of the 39 seeded items carry a genuine Steam economy CDN render URL"
        // Every fixture but 'Denim Jeans' is a real, currently-listed s&box item
        // (icon_url fetched verbatim from the Steam Market render API for app
        // 590830, cross-checked across 2+ fetches), so /market and the home grid
        // open on real skin art instead of emoji-on-gradient tiles.
        def withImage = saved.findAll { it.imageUrl != null }
        withImage.size() == 38
        and: "real-render coverage is the overwhelming majority (well past a 30/39 bar)"
        withImage.size() >= 30
        and: "exactly one item — Denim Jeans, which had no fetched render — stays on the fallback"
        def withoutImage = saved.findAll { it.imageUrl == null }
        withoutImage.size() == 1
        withoutImage*.name as Set == ['Denim Jeans'] as Set
        and: "every seeded image URL is a real Steam economy CDN URL (no fabricated/broken host)"
        withImage.every {
            it.imageUrl.startsWith('https://steamcommunity-a.akamaihd.net/economy/image/') &&
            it.imageUrl.length() > 'https://steamcommunity-a.akamaihd.net/economy/image/'.length() + 20
        }
        and: "the fallback item degrades gracefully to the emoji/glyph tile (no broken image)"
        withoutImage.every { it.iconEmoji != null && !it.iconEmoji.isEmpty() }
    }

    def "seeded catalogue steamPrice stays a positive BigDecimal above lowestPrice"() {
        given:
        walletRepository.count() >> 1L
        itemRepository.count() >> 0L
        listingRepository.count() >> 0L
        listingRepository.findActiveOrderByNewest() >> []
        listingRepository.countAllSold() >> 0L
        loadoutRepository.findAll() >> []
        List<Item> saved = []
        itemRepository.save(_) >> { Item it ->
            if (it.id == null) it.id = (saved.size() + 1L)
            if (!saved.contains(it)) saved << it
            it
        }
        itemRepository.findAll() >> { saved }
        listingRepository.findRecentSalesForItem(_, _) >> [new Listing(status: 'SOLD')]

        when:
        service.seed()

        then:
        saved.every { it.steamPrice != null && it.steamPrice > it.lowestPrice }
    }

    // ── seeded marketplace-listing sanity ────────────────────────────

    def "seeded listings are scale-2, reference a seeded item, and AUCTION rows are internally consistent"() {
        given: "a small priced catalogue, no listings yet"
        walletRepository.count() >> 1L
        itemRepository.count() >> 6L
        def items = (1L..6L).collect { id ->
            new Item(id: id, name: "Item${id}", category: 'Hats', rarity: 'Standard',
                     lowestPrice: new BigDecimal("3.00"), steamPrice: new BigDecimal("3.60"))
        }
        itemRepository.findAll() >> items
        itemRepository.save(_) >> { Item it -> it }
        listingRepository.count() >> 0L
        listingRepository.countAllSold() >> 6L      // skip demo-sales so we inspect raw ACTIVE rows
        // Per-item recent-sales seed runs after the listing seed; stub it to
        // the "already has a SOLD row" skip path so the only rows captured
        // in `saved` are the ACTIVE marketplace listings under test.
        listingRepository.findRecentSalesForItem(_, _) >> [new Listing(status: 'SOLD')]
        loadoutRepository.findAll() >> []
        List<Listing> saved = []
        listingRepository.save(_) >> { Listing l -> l.id = (saved.size() + 1L); saved << l; l }

        when:
        service.seed()

        then: "listings were created"
        saved.size() > 0
        and: "every listing references one of the seeded items (referential integrity)"
        saved.every { it.item != null && items.contains(it.item) }
        and: "every price is positive and currency-clean"
        saved.every { it.price != null && it.price.scale() == 2 && it.price > BigDecimal.ZERO }
        and: "status / type / hidden are sane"
        saved.every { it.status == 'ACTIVE' }
        saved.every { it.listingType in ['BUY_NOW', 'AUCTION'] }
        saved.every { it.hidden == false }
        saved.every { it.sellerUserId == null }      // system / launch-seed rows
        saved.every { it.sellerName != null && !it.sellerName.isEmpty() }
        and: "AUCTION rows always have an expiry in the future and a non-null bid count"
        def auctions = saved.findAll { it.listingType == 'AUCTION' }
        long now = System.currentTimeMillis()
        auctions.every { it.expiresAt != null && it.expiresAt > now }
        auctions.every { it.bidCount != null && it.bidCount >= 0 }
        and: "when an auction has bids the currentBid is scale-2 and below ask"
        auctions.findAll { it.bidCount > 0 }.every {
            it.currentBid != null && it.currentBid.scale() == 2 &&
            it.currentBid > BigDecimal.ZERO && it.currentBid <= it.price &&
            it.currentBidderName != null
        }
        and: "BUY_NOW rows carry no auction-only fields"
        saved.findAll { it.listingType == 'BUY_NOW' }.every {
            it.expiresAt == null && it.currentBid == null
        }
    }

    def "marketplace-listing seed is a no-op when no catalogue item is priced"() {
        given:
        walletRepository.count() >> 1L
        itemRepository.count() >> 3L
        // items exist but all have null / zero price -> nothing to list against
        itemRepository.findAll() >> [
            new Item(id: 1L, name: 'A', category: 'Hats', rarity: 'Standard', lowestPrice: null),
            new Item(id: 2L, name: 'B', category: 'Hats', rarity: 'Standard', lowestPrice: BigDecimal.ZERO)
        ]
        listingRepository.count() >> 0L
        listingRepository.countAllSold() >> 0L
        listingRepository.findActiveOrderByNewest() >> []
        loadoutRepository.findAll() >> []

        when:
        service.seed()

        then:
        0 * listingRepository.save(_)
    }

    // ── seeded price-history sanity ──────────────────────────────────

    def "price-history seed plants a multi-day per-item series with scale-2 positive prices spanning >30 days"() {
        given: "a fresh boot — priced catalogue, no existing history"
        walletRepository.count() >> 1L
        // skip the catalogue/listing/sales seeds so we isolate the history seed
        itemRepository.count() >> 3L
        listingRepository.count() >> 80L
        listingRepository.countAllSold() >> 6L
        listingRepository.findRecentSalesForItem(_, _) >> [new Listing(status: 'SOLD')]
        loadoutRepository.findAll() >> []
        def items = (1L..3L).collect { id ->
            new Item(id: id, name: "Item${id}", category: 'Hats', rarity: 'Standard',
                     lowestPrice: new BigDecimal("5.00"), steamPrice: new BigDecimal("6.00"))
        }
        itemRepository.findAll() >> items
        List<PriceHistory> saved = []
        // Identity-aware save (like JPA): a brand-new row is appended once;
        // the final-pin step re-saves an already-managed row, which must NOT
        // create a duplicate. So `saved` ends up holding exactly the distinct
        // rows — one per seeded day.
        priceHistoryRepository.save(_) >> { PriceHistory p ->
            if (!saved.any { it.is(p) }) { p.id = (saved.size() + 1L); saved << p }
            p
        }
        // findLatestByItem is called TWICE per item: once as the per-item
        // idempotency gate (must be empty so the item gets seeded), then
        // once at the end to pin the final point to the floor. A stateful
        // stub keyed on what's been saved for that item models both: empty
        // until the first row for the item lands, then the most-recent row.
        priceHistoryRepository.findLatestByItem(_) >> { Long itemId ->
            def rows = saved.findAll { it.item?.id == itemId }
            rows.isEmpty() ? Optional.empty() : Optional.of(rows.last())
        }

        when:
        serviceWithHistory.seed()

        then: "a dense per-item series was written (one point per day across the window)"
        // 90 days × 3 items = 270 points.
        saved.size() == 3 * 90
        and: "every point is a positive, currency-clean BigDecimal"
        saved.every { it.price != null && it.price.scale() == 2 && it.price > BigDecimal.ZERO }
        and: "every point carries an epoch-ms recordedAt and a non-null day label"
        saved.every { it.recordedAt != null && it.recordedAt > 0L }
        saved.every { it.dayLabel != null && !it.dayLabel.isEmpty() }
        and: "the series spans more than 30 days so the 30-day delta + range chips compute"
        long spanMs = saved*.recordedAt.max() - saved*.recordedAt.min()
        spanMs > 30L * 86_400_000L
        and: "volume is a non-negative daily trade count"
        saved.every { it.volume != null && it.volume >= 0 }
        and: "each item's newest point lands on its reconciled floor (chart right edge == card price)"
        items.every { item ->
            def rows = saved.findAll { it.item.is(item) }.sort { it.recordedAt }
            rows.last().price == item.lowestPrice.setScale(2, java.math.RoundingMode.HALF_UP)
        }
    }

    def "price-history seed is idempotent per item — skips an item that already has history"() {
        given:
        walletRepository.count() >> 1L
        itemRepository.count() >> 3L
        listingRepository.count() >> 80L
        listingRepository.countAllSold() >> 6L
        listingRepository.findRecentSalesForItem(_, _) >> [new Listing(status: 'SOLD')]
        loadoutRepository.findAll() >> []
        itemRepository.findAll() >> [
            new Item(id: 1L, name: 'A', category: 'Hats', rarity: 'Standard',
                     lowestPrice: new BigDecimal("4.00"), steamPrice: new BigDecimal("4.80"))
        ]
        // The item already has a latest history row -> skip path, no writes.
        priceHistoryRepository.findLatestByItem(_) >> Optional.of(
            new PriceHistory(price: new BigDecimal("4.00"), dayLabel: 'y'))

        when:
        serviceWithHistory.seed()

        then:
        0 * priceHistoryRepository.save(_)
    }

    def "price-history seed is inert when the optional repository bean is absent"() {
        given: "the bare subject (priceHistoryRepository null, like required=false)"
        walletRepository.count() >> 1L
        itemRepository.count() >> 3L
        listingRepository.count() >> 80L
        listingRepository.countAllSold() >> 6L
        listingRepository.findRecentSalesForItem(_, _) >> [new Listing(status: 'SOLD')]
        loadoutRepository.findAll() >> []
        itemRepository.findAll() >> existingCatalogueRows()

        when:
        service.seed()    // bare subject — priceHistoryRepository is null

        then: "no NPE, and nothing is written to the (absent) history repo"
        noExceptionThrown()
        0 * priceHistoryRepository.save(_)
    }

    // ── seeded per-item recent-sales sanity ──────────────────────────

    def "per-item sales seed inserts SOLD rows for items with no prior sales"() {
        given: "a fresh boot — priced catalogue, no prior per-item sales"
        walletRepository.count() >> 1L
        itemRepository.count() >> 4L
        listingRepository.count() >> 80L     // skip the marketplace-listing seed
        listingRepository.countAllSold() >> 6L  // skip backfillDemoSales
        loadoutRepository.findAll() >> []
        def items = (1L..4L).collect { id ->
            new Item(id: id, name: "Item${id}", category: 'Hats', rarity: 'Standard',
                     lowestPrice: new BigDecimal("3.00"), steamPrice: new BigDecimal("3.60"))
        }
        itemRepository.findAll() >> items
        // No existing SOLD rows -> every item gets fresh sales.
        listingRepository.findRecentSalesForItem(_, _) >> []
        List<Listing> saved = []
        listingRepository.save(_) >> { Listing l -> l.id = (saved.size() + 1L); saved << l; l }

        when:
        service.seed()

        then: "every saved row is a SOLD listing referencing a seeded item"
        saved.size() >= items.size() * 3      // >=3 per item
        saved.size() <= items.size() * 6      // <=6 per item
        saved.every { it.status == 'SOLD' }
        saved.every { it.soldAt != null && it.soldAt > 0L }
        saved.every { it.item != null && items.contains(it.item) }
        and: "prices are positive, currency-clean, and sellerUserId is null (system rows)"
        saved.every { it.price != null && it.price.scale() == 2 && it.price > BigDecimal.ZERO }
        saved.every { it.sellerUserId == null }
        saved.every { it.sellerName != null && !it.sellerName.isEmpty() }
        and: "every item ends up with at least one SOLD row (Recent sales (N) is non-zero)"
        items.every { item -> saved.any { it.item.is(item) } }
    }

    def "per-item sales seed skips an item that already has a SOLD row (idempotent)"() {
        given:
        walletRepository.count() >> 1L
        itemRepository.count() >> 2L
        listingRepository.count() >> 80L
        listingRepository.countAllSold() >> 6L
        loadoutRepository.findAll() >> []
        itemRepository.findAll() >> [
            new Item(id: 1L, name: 'A', category: 'Hats', rarity: 'Standard',
                     lowestPrice: new BigDecimal("2.00"), steamPrice: new BigDecimal("2.40"))
        ]
        // Item already has a recent sale -> skip path, no new SOLD rows.
        listingRepository.findRecentSalesForItem(_, _) >> [new Listing(status: 'SOLD')]

        when:
        service.seed()

        then:
        0 * listingRepository.save(_)
    }

    // ── seeded demo-seller (stall) sanity ────────────────────────────
    //
    // The demo-seller seed is what makes /stall/{id} reachable: it creates
    // real SteamUser rows for a subset of the SEED_SELLERS handles and
    // re-points a portion of the already-seeded ACTIVE listings at those
    // user ids (matching on the listing's existing sellerName). Without it
    // every seeded listing has sellerUserId=null and no backing user, so
    // publicStall's findById always misses → "Stall not found".

    def "demo-seller seed creates real SteamUser accounts and attaches active listings to them by name"() {
        given: "a fresh boot — catalogue + listings already seeded, no demo sellers yet"
        walletRepository.count() >> 1L
        itemRepository.count() >> 32L            // skip catalogue seed
        listingRepository.count() >> 80L         // skip marketplace-listing seed
        listingRepository.countAllSold() >> 6L   // skip demo-sales backfill
        // per-item recent-sales seed iterates the catalogue; stub the skip path
        listingRepository.findRecentSalesForItem(_, _) >> [new Listing(status: 'SOLD')]
        itemRepository.findAll() >> []
        loadoutRepository.findAll() >> []
        // No demo account exists yet -> the seed runs.
        steamUserRepository.findBySteamId64(_) >> null
        List<SteamUser> savedUsers = []
        steamUserRepository.save(_) >> { SteamUser u ->
            u.id = (savedUsers.size() + 1L); savedUsers << u; u
        }
        // A book where the FIRST four handles (the account set) own listings
        // and a non-account handle (VaultRunner) plus an already-owned row
        // and a SOLD row are present — only the unowned ACTIVE account-handle
        // rows should be claimed.
        def item = new Item(id: 1L, name: 'Hat', category: 'Hats', rarity: 'Standard',
                            lowestPrice: new BigDecimal('3.00'), steamPrice: new BigDecimal('3.60'))
        def book = [
            new Listing(id: 1L, item: item, price: new BigDecimal('3.00'), sellerName: 'BoneTender',  status: 'ACTIVE', sellerUserId: null),
            new Listing(id: 2L, item: item, price: new BigDecimal('3.10'), sellerName: 'BoneTender',  status: 'ACTIVE', sellerUserId: null),
            new Listing(id: 3L, item: item, price: new BigDecimal('3.20'), sellerName: 'AtlasTrades', status: 'ACTIVE', sellerUserId: null),
            new Listing(id: 4L, item: item, price: new BigDecimal('3.30'), sellerName: 'PixelPusher', status: 'ACTIVE', sellerUserId: null),
            new Listing(id: 5L, item: item, price: new BigDecimal('3.40'), sellerName: 'GhostlyDeals',status: 'ACTIVE', sellerUserId: null),
            new Listing(id: 6L, item: item, price: new BigDecimal('3.50'), sellerName: 'VaultRunner', status: 'ACTIVE', sellerUserId: null), // not in account set -> stays anon
            new Listing(id: 7L, item: item, price: new BigDecimal('3.60'), sellerName: 'BoneTender',  status: 'SOLD',   sellerUserId: null), // SOLD -> not claimed
            new Listing(id: 8L, item: item, price: new BigDecimal('3.70'), sellerName: 'BoneTender',  status: 'ACTIVE', sellerUserId: 999L) // already owned -> not re-claimed
        ]
        listingRepository.findAll() >> book
        List<Listing> savedListings = []
        listingRepository.save(_) >> { Listing l -> savedListings << l; l }

        when:
        serviceWithSellers.seed()

        then: "six real seller accounts were created with synthetic 7656119xxxxxxxxxx ids"
        savedUsers.size() == 6
        savedUsers.every { it.steamId64 != null && it.steamId64.startsWith('765611900000000') }
        savedUsers*.steamId64.unique().size() == 6           // ids are distinct
        savedUsers.every { it.displayName != null && !it.displayName.isEmpty() }
        savedUsers.every { it.avatarUrl != null && it.avatarUrl.startsWith('https://') }
        savedUsers.every { it.role == 'USER' && it.banned == false }
        savedUsers.every { it.createdAt != null && it.createdAt > 0L }
        and: "the account handles are the documented BoneTender..TradeHaven subset of SEED_SELLERS"
        savedUsers*.displayName as Set ==
            ['BoneTender','AtlasTrades','PixelPusher','GhostlyDeals','EmberWolf','TradeHaven'] as Set
        and: "only the unowned ACTIVE listings whose name matches an account handle were attached"
        // ids 1,2,3,4,5 (BoneTender x2, AtlasTrades, PixelPusher, GhostlyDeals)
        savedListings.size() == 5
        savedListings.every { it.status == 'ACTIVE' }
        savedListings.every { it.sellerUserId != null }
        savedListings*.id as Set == [1L, 2L, 3L, 4L, 5L] as Set
        and: "the anon (non-account-handle), SOLD, and already-owned rows were left untouched"
        !savedListings*.id.contains(6L)   // VaultRunner — not in account set
        !savedListings*.id.contains(7L)   // SOLD
        !savedListings*.id.contains(8L)   // already owned by user 999
        and: "each attached listing points at the user id seeded for its handle"
        def idByHandle = savedUsers.collectEntries { [(it.displayName): it.id] }
        savedListings.every { it.sellerUserId == idByHandle[it.sellerName] }
        and: "BoneTender (two listings) ends up owning both of its rows"
        savedListings.findAll { it.sellerUserId == idByHandle['BoneTender'] }.size() == 2
    }

    def "demo-seller seed is idempotent — skips entirely when the sentinel account already exists"() {
        given: "the first demo account is already present (a prior boot seeded it)"
        walletRepository.count() >> 1L
        itemRepository.count() >> 32L
        listingRepository.count() >> 80L
        listingRepository.countAllSold() >> 6L
        listingRepository.findRecentSalesForItem(_, _) >> [new Listing(status: 'SOLD')]
        itemRepository.findAll() >> []
        loadoutRepository.findAll() >> []
        // Probe finds an existing account -> the whole seed short-circuits.
        steamUserRepository.findBySteamId64(_) >> new SteamUser(id: 1L, steamId64: '76561190000000001')

        when:
        serviceWithSellers.seed()

        then: "no new users, and no listing re-pointing"
        0 * steamUserRepository.save(_)
        and: "it never even scans the listing book to attach"
        0 * listingRepository.findAll()
    }

    def "demo-seller seed is inert when the optional SteamUser repository bean is absent"() {
        given: "the bare subject (steamUserRepository null, like required=false)"
        walletRepository.count() >> 1L
        itemRepository.count() >> 32L
        listingRepository.count() >> 80L
        listingRepository.countAllSold() >> 6L
        listingRepository.findRecentSalesForItem(_, _) >> [new Listing(status: 'SOLD')]
        itemRepository.findAll() >> existingCatalogueRows()
        loadoutRepository.findAll() >> []

        when:
        service.seed()    // bare subject — steamUserRepository is null

        then: "no NPE, and nothing is written to the (absent) user repo"
        noExceptionThrown()
        0 * steamUserRepository.save(_)
    }

    // ── seeded public-loadout sanity ─────────────────────────────────

    def "public-loadout seed skips when the catalogue is too small to fill slots"() {
        given:
        walletRepository.count() >> 1L
        itemRepository.count() >> 2L          // < 3 -> loadout seed bails
        listingRepository.count() >> 80L
        listingRepository.countAllSold() >> 6L

        when:
        service.seed()

        then:
        0 * loadoutRepository.save(_)
        0 * loadoutSlotRepository.save(_)
    }

    def "seeded loadouts use negative synthetic owner ids and scale-2 totals"() {
        given: "a catalogue big enough to fill slots, no existing loadouts"
        walletRepository.count() >> 1L
        listingRepository.count() >> 80L
        listingRepository.countAllSold() >> 6L
        def items = (1L..14L).collect { id ->
            new Item(id: id, name: "Cat${id}", category: catFor(id), rarity: 'Standard',
                     lowestPrice: new BigDecimal("2.00"), steamPrice: new BigDecimal("2.40"))
        }
        itemRepository.count() >> (long) items.size()
        itemRepository.findAll() >> items
        // Per-item recent-sales seed iterates the catalogue too; stub it to
        // the "already has a SOLD row" skip path so it issues no extra
        // listing saves while this test inspects the loadout writes.
        listingRepository.findRecentSalesForItem(_, _) >> [new Listing(status: 'SOLD')]
        loadoutRepository.findAll() >> []     // first boot — nothing yet
        List<Loadout> savedLoadouts = []
        loadoutRepository.save(_) >> { Loadout l ->
            if (l.id == null) l.id = (savedLoadouts.size() + 1L)
            if (!savedLoadouts.contains(l)) savedLoadouts << l
            l
        }
        List<LoadoutSlot> savedSlots = []
        loadoutSlotRepository.save(_) >> { LoadoutSlot s -> s.id = (savedSlots.size() + 1L); savedSlots << s; s }

        when:
        service.seed()

        then: "five curated public loadouts"
        savedLoadouts.unique().size() == 5
        savedLoadouts.every { it.visibility == 'PUBLIC' }
        and: "owner ids are negative — never collide with real positive Steam ids"
        savedLoadouts.every { it.ownerUserId != null && it.ownerUserId < 0L }
        and: "totalValue is a currency-clean non-negative BigDecimal"
        savedLoadouts.every { it.totalValue != null && it.totalValue >= BigDecimal.ZERO }
        and: "each loadout got the full 8-slot set"
        savedSlots.size() == 5 * 8
        savedSlots.every { it.slot in ['Hats','Jackets','Shirts','Pants','Gloves','Boots','Accessories','Wild'] }
        and: "filled slots reference a seeded item (referential integrity)"
        savedSlots.findAll { it.itemId != null }.every { slot ->
            items.any { it.id == slot.itemId }
        }
    }

    // ── seller-avatar initials helper (initialsFor) ──────────────────
    //
    // The avatar tokens drive the visual "crowd of sellers" effect on
    // the home grid — every seed seller's listing carries a 2-letter
    // initials chip rendered next to their handle. The helper has
    // documented behaviour (CamelCase → 2 caps; plain lowercase → first
    // two letters uppercased) that's load-bearing for the UI parity
    // with the rest of the avatar system.

    /** Invoke the private static `initialsFor(String)` via reflection
     *  so the helper can be exercised without going through a full seed
     *  cycle. Mirrors the AuditService.clientIp pattern. */
    private static String invokeInitialsFor(String handle) {
        def m = com.sboxmarket.service.SeedService.getDeclaredMethod('initialsFor', String)
        m.accessible = true
        m.invoke(null, handle) as String
    }

    def "initialsFor returns two capital letters for a CamelCase handle"() {
        // The 10 SEED_SELLERS handles ('VaultRunner', 'NeonArc',
        // 'CrateDigger', etc.) are all CamelCase, so this is the
        // dominant case in production.
        expect:
        invokeInitialsFor('VaultRunner') == 'VR'
        invokeInitialsFor('NeonArc')     == 'NA'
        invokeInitialsFor('CrateDigger') == 'CD'
        invokeInitialsFor('TradeHaven')  == 'TH'
    }

    def "initialsFor uppercases the first two letters of a plain-lowercase handle"() {
        // Fallback path — a non-CamelCase handle still produces a
        // 2-char uppercase token rather than rendering blank.
        expect:
        invokeInitialsFor('frame')   == 'FR'
        invokeInitialsFor('alice')   == 'AL'
    }

    def "initialsFor returns the '??' placeholder for null or empty input"() {
        // Defensive default so the avatar chip never renders blank
        // even if some upstream caller hands it a missing handle.
        expect:
        invokeInitialsFor(null) == '??'
        invokeInitialsFor('')   == '??'
    }

    def "initialsFor takes the FIRST TWO caps when a handle has more than two"() {
        // A handle like 'XYZ' / 'AAABBB' has many caps — the helper
        // only ever returns a 2-char token (the avatar chip is fixed
        // width).
        expect:
        invokeInitialsFor('ABC')          == 'AB'
        invokeInitialsFor('XYZ123')       == 'XY'
    }

    def "every seeded listing carries a 2-character non-empty sellerAvatar token"() {
        // End-to-end check that the seed pipeline actually plumbs the
        // avatar token onto each Listing row. This is the user-visible
        // property — the home grid renders the chip from this field.
        given:
        walletRepository.count() >> 1L
        itemRepository.count() >> 4L
        def items = (1L..4L).collect { id ->
            new Item(id: id, name: "Item${id}", category: 'Hats', rarity: 'Standard',
                     lowestPrice: new BigDecimal("3.00"), steamPrice: new BigDecimal("3.60"))
        }
        itemRepository.findAll() >> items
        itemRepository.save(_) >> { Item it -> it }
        listingRepository.count() >> 0L
        listingRepository.countAllSold() >> 6L
        // Skip the per-item recent-sales seed so `saved` holds only the
        // ACTIVE marketplace listings whose avatar token this test pins.
        listingRepository.findRecentSalesForItem(_, _) >> [new Listing(status: 'SOLD')]
        loadoutRepository.findAll() >> []
        List<Listing> saved = []
        listingRepository.save(_) >> { Listing l -> l.id = (saved.size() + 1L); saved << l; l }

        when:
        service.seed()

        then: "every Listing row has a sellerAvatar that is exactly 2 chars and all-uppercase"
        saved.size() > 0
        saved.every {
            it.sellerAvatar != null &&
            it.sellerAvatar.length() == 2 &&
            it.sellerAvatar == it.sellerAvatar.toUpperCase()
        }
    }

    // ── helpers ──────────────────────────────────────────────────────

    /** Round-robins ids across the 7 chip categories so a 14-item
     *  fixture catalogue has at least one item per slot category. */
    private static String catFor(long id) {
        ['Hats','Jackets','Shirts','Pants','Gloves','Boots','Accessories'][(int) ((id - 1L) % 7L)]
    }

    /** A small priced catalogue standing in for an already-populated DB —
     *  backs `itemRepository.findAll()` so the per-item price-history and
     *  recent-sales seeds have rows to iterate (and then skip, on a
     *  populated restart). */
    private static List<Item> existingCatalogueRows() {
        (1L..6L).collect { id ->
            new Item(id: id, name: "Item${id}", category: 'Hats', rarity: 'Standard',
                     lowestPrice: new BigDecimal("3.00"), steamPrice: new BigDecimal("3.60"))
        }
    }

    /** Five already-present curated public loadouts, deliberately
     *  diversified (distinct totalValue) so the self-heal block does
     *  NOT treat them as the legacy "all identical" seed and wipe them. */
    private static List<Loadout> publicLoadoutFixtureRows() {
        ['Cardboard Connoisseur','Cybernetic Drifter','Plague Doctor',
         'WW1 Trench Soldier','OG Streetwear'].withIndex().collect { name, i ->
            new Loadout(id: (i + 1L), name: name, visibility: 'PUBLIC',
                        ownerUserId: (-100L - i),
                        totalValue: new BigDecimal("${10 + i * 5}.00"))
        }
    }
}
