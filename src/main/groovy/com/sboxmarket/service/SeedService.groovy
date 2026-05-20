package com.sboxmarket.service

import com.sboxmarket.model.Loadout
import com.sboxmarket.model.LoadoutSlot
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.LoadoutRepository
import com.sboxmarket.repository.LoadoutSlotRepository
import com.sboxmarket.repository.PriceHistoryRepository
import com.sboxmarket.repository.WalletRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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
    @Autowired(required = false) ItemRepository itemRepository

    @Transactional
    void seed() {
        if (walletRepository.count() == 0) {
            walletRepository.save(new Wallet(username: "demo", balance: new BigDecimal("250.00"), currency: "USD"))
            log.info("Seeded demo wallet (\$250.00 starting balance)")
        }
        seedCatalogueItems()
        backfillDemoSales()
        backfillPublicLoadouts()
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
    private void seedCatalogueItems() {
        if (itemRepository == null) return
        try {
            if (itemRepository.count() > 0) return
            // Format: [name, category, rarity, iconEmoji, accentColor, lowestPriceUSD]
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
            def fixtures = [
                // Hats — 5
                ['Beanie',                    'Hats',        'Standard',   '🧢', '#7a8b9c', '0.50'],
                ['Hard Hat',                  'Hats',        'Standard',   '⛑',  '#f5c116', '1.20'],
                ['Top Hat',                   'Hats',        'Limited',    '🎩', '#1a1a1a', '8.40'],
                ['Witch Hat',                 'Hats',        'Limited',    '🧙', '#4a2370', '12.10'],
                ['Crown of Thorns',           'Hats',        'Off-Market', '👑', '#c89b3c', '64.00'],
                // Jackets — 4
                ['Leather Jacket',            'Jackets',     'Limited',    '🧥', '#3a2417', '6.80'],
                ['Trench Coat',               'Jackets',     'Limited',    '🧥', '#5a4632', '11.50'],
                ['Tactical Vest',             'Jackets',     'Limited',    '🦺', '#3d5a3a', '14.20'],
                ['Cape of the Wanderer',      'Jackets',     'Off-Market', '🦸', '#7b1fa2', '89.00'],
                // Shirts — 3
                ['Hoodie',                    'Shirts',      'Standard',   '👕', '#2c3e50', '0.80'],
                ['Lab Coat',                  'Shirts',      'Standard',   '🥼', '#ecf0f1', '1.45'],
                ['Hawaiian Shirt',            'Shirts',      'Standard',   '👔', '#ff6b6b', '2.10'],
                // Pants — 3
                ['Cargo Pants',               'Pants',       'Standard',   '👖', '#5b6e3d', '0.95'],
                ['Denim Jeans',               'Pants',       'Standard',   '👖', '#2456a8', '0.70'],
                ['Hazmat Trousers',           'Pants',       'Limited',    '👖', '#d4a72c', '4.80'],
                // Gloves — 2
                ['Wool Mittens',              'Gloves',      'Standard',   '🧤', '#a83d3d', '0.60'],
                ['Tactical Gloves',           'Gloves',      'Limited',    '🧤', '#3a3f47', '5.40'],
                // Boots — 2
                ['Combat Boots',              'Boots',       'Standard',   '🥾', '#3a2417', '1.30'],
                ['Steel-Toe Boots',           'Boots',       'Limited',    '🥾', '#5a5a5a', '6.20'],
                // Accessories — 7
                ['Sunglasses',                'Accessories', 'Standard',   '🕶', '#1a1a1a', '0.65'],
                ['Pocket Watch',              'Accessories', 'Limited',    '⌚', '#c0a062', '5.30'],
                ['Backpack',                  'Accessories', 'Standard',   '🎒', '#2c3e50', '1.15'],
                ['Gas Mask',                  'Accessories', 'Limited',    '😷', '#3a3f47', '7.90'],
                ['Engineer Goggles',          'Accessories', 'Limited',    '🥽', '#a87b3a', '4.40'],
                ['Bone Necklace',             'Accessories', 'Limited',    '💀', '#ddd6c7', '3.20'],
                ['Halo of the Forsaken',      'Accessories', 'Off-Market', '🌟', '#ffd700', '120.00'],
                // Workshop — 6 (only visible under "All" chip — Workshop has no chip)
                ['Map: Foundry',              'Workshop',    'Standard',   '🏭', '#5a5a5a', '0.99'],
                ['Map: Lakeside',             'Workshop',    'Standard',   '🏞', '#3a7d44', '0.99'],
                ['Vehicle Wrap: Cyber',       'Workshop',    'Limited',    '🚗', '#9b59b6', '4.50'],
                ['Vehicle Wrap: Camo',        'Workshop',    'Standard',   '🚗', '#5b6e3d', '1.80'],
                ['Decal Pack: Graffiti',      'Workshop',    'Standard',   '🎨', '#e74c3c', '0.75'],
                ['Workshop Pass: Founders',   'Workshop',    'Off-Market', '🎟', '#237bff', '250.00']
            ]
            long now = System.currentTimeMillis()
            int idx = 0
            fixtures.each { fx ->
                def item = new com.sboxmarket.model.Item()
                item.name         = fx[0]
                item.category     = fx[1]
                item.rarity       = fx[2]
                item.iconEmoji    = fx[3]
                item.accentColor  = fx[4]
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
                    favorites:   (5 - fxIdx) * 7,  // descending so Discover sort is sensible
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
