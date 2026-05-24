package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Loadout
import com.sboxmarket.model.LoadoutSlot
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.LoadoutRepository
import com.sboxmarket.repository.LoadoutSlotRepository
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

    @Subject
    SeedService service = new SeedService(
        walletRepository     : walletRepository,
        itemRepository       : itemRepository,
        listingRepository    : listingRepository,
        loadoutRepository    : loadoutRepository,
        loadoutSlotRepository: loadoutSlotRepository
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

        when:
        service.seed()

        then:
        saved.size() == 32
        saved.every { it.lowestPrice != null && it.lowestPrice.scale() == 2 }
        saved.every { it.lowestPrice > BigDecimal.ZERO }
        saved.every { it.rarity in ['Standard', 'Limited', 'Off-Market'] }
        and: "every chip-filterable category is represented (no empty grid on a chip click)"
        ['Hats','Jackets','Shirts','Pants','Gloves','Boots','Accessories'].every { cat ->
            saved.any { it.category == cat }
        }
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
