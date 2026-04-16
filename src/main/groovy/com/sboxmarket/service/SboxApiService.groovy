package com.sboxmarket.service

import com.sboxmarket.model.Item
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Pulls the canonical s&box skin catalogue from SCMM
 * (https://sbox.scmm.app — the community Steam Community Market Manager that
 * mirrors sbox.game/metrics/skins). That service *is* the one the user
 * referenced as "most accurate data"; sbox.game itself is a Blazor-server UI
 * with no public JSON API, so we use the open SCMM mirror.
 *
 * Fields we consume (from the /api/item response):
 *   - name, itemType            → our Item.name + mapped category
 *   - buyNowPrice, originalPrice → lowestPrice + steamPrice (prices are cents)
 *   - supply, supplyTotalKnown   → our supply + total-minted signal
 *   - subscriptions              → approximated totalSold
 *   - priceMovement              → trendPercent
 *   - iconUrl                    → imageUrl (Steam CDN)
 *   - iconAccentColour           → accentColor
 *   - sellEnd                    → used to flag Limited items (drop expired)
 *
 * Runs every 30 minutes via @Scheduled — the user asked for consistent price
 * sync so drift on this catalogue stays inside half an hour.
 */
@Service
@Slf4j
class SboxApiService {

    private static final String SCMM_API = 'https://sbox.scmm.app/api/item?count=1000'
    private static final long SYNC_INTERVAL_MS = 30L * 60L * 1000L   // 30 minutes

    // itemType → our 7 clothing categories. Anything not listed falls through
    // to Accessories, which is deliberately the catch-all.
    private static final Map<String, String> TYPE_CATEGORY = [
        // Hats
        'Hat': 'Hats', 'HatCap': 'Hats', 'HatSpecial': 'Hats',
        'HatUniform': 'Hats', 'HeadSpecial': 'Hats',
        'HairLong': 'Hats', 'HairShort': 'Hats', 'HairUpdo': 'Hats',
        // Jackets
        'Jacket': 'Jackets', 'Gilet': 'Jackets', 'Outfit': 'Jackets',
        'Coat': 'Jackets', 'Hoodie': 'Jackets',
        // Shirts
        'Shirt': 'Shirts', 'TShirt': 'Shirts', 'Clothing': 'Shirts',
        'Top': 'Shirts',
        // Pants
        'Jeans': 'Pants', 'Pants': 'Pants', 'Trousers': 'Pants',
        'Shorts': 'Pants',
        // Gloves
        'Gloves': 'Gloves',
        // Boots
        'Boots': 'Boots', 'Shoes': 'Boots', 'Sneakers': 'Boots',
        // Accessories (catch-all)
        'Earring':     'Accessories',
        'Eyewear':     'Accessories',
        'GlassesSpecial': 'Accessories',
        'Facial':      'Accessories',
        'Necklace':    'Accessories',
        'Furniture':   'Accessories',
        'Backpack':    'Accessories',
        'Watch':       'Accessories',
        'Mask':        'Accessories',
        'Tattoo':      'Accessories',
    ]

    // itemType → emoji fallback shown when Steam CDN image 404s
    private static final Map<String, String> TYPE_EMOJI = [
        'Hat': '🎩', 'HatCap': '🧢', 'HatSpecial': '🎩', 'HatUniform': '🎓', 'HeadSpecial': '🎭',
        'HairLong': '💇', 'HairShort': '💇', 'HairUpdo': '💇',
        'Jacket': '🧥', 'Gilet': '🦺', 'Outfit': '👔', 'Coat': '🧥', 'Hoodie': '🧥',
        'Shirt': '👕', 'TShirt': '👕', 'Clothing': '👕', 'Top': '👕',
        'Jeans': '👖', 'Pants': '👖', 'Trousers': '👖', 'Shorts': '🩳',
        'Gloves': '🧤', 'Boots': '🥾', 'Shoes': '👟', 'Sneakers': '👟',
        'Earring': '💎', 'Eyewear': '👓', 'GlassesSpecial': '🕶',
        'Facial': '😷', 'Necklace': '📿', 'Furniture': '🪑',
        'Backpack': '🎒', 'Watch': '⌚', 'Mask': '🎭', 'Tattoo': '🖋'
    ]

    @Autowired ItemRepository itemRepository
    @Autowired ListingRepository listingRepository

    /** Consistency check the background sync uses. Never throws — returns an
     *  empty map on any upstream failure so the scheduler keeps ticking. */
    List<Map> fetchRemoteCatalogue() {
        try {
            def conn = (HttpURLConnection) new URL(SCMM_API).openConnection()
            conn.setRequestProperty('User-Agent', 'SBoxMarket/1.0 (+https://sboxmarket.local)')
            conn.setRequestProperty('Accept', 'application/json')
            conn.connectTimeout = 10_000
            conn.readTimeout    = 15_000
            if (conn.responseCode != 200) {
                log.warn("SCMM sync: HTTP ${conn.responseCode}")
                return []
            }
            def text = conn.inputStream.getText('UTF-8')
            def parsed = new JsonSlurper().parseText(text)
            def items = (parsed?.items as List) ?: []
            log.info("SCMM catalogue fetched — ${items.size()} items (total reported: ${parsed?.total})")
            items as List<Map>
        } catch (Exception e) {
            log.error("SCMM fetch failed: ${e.message}")
            return []
        }
    }

    /**
     * One-shot sync — pulls the remote catalogue, upserts by exact name.
     * Does NOT create synthetic listings (that was the old behaviour and it
     * duplicated rows). Only the `Item` catalogue is touched; listings come
     * from real users and from `SeedService` on first boot.
     */
    @Transactional
    Map syncFromScmm() {
        def remote = fetchRemoteCatalogue()
        if (remote.isEmpty()) return [created: 0, updated: 0, totalRemote: 0, skipped: 0]

        int created = 0, updated = 0, skipped = 0
        def now = System.currentTimeMillis()
        def nowIso = new Date()

        // Build a fast name→item index once instead of scanning the repo per row
        def existingByName = [:]
        itemRepository.findAll().each { existingByName[it.name?.toLowerCase()] = it }

        remote.each { r ->
            def name = (r.name ?: '').toString().trim()
            if (!name) { skipped++; return }

            def itemType = (r.itemType ?: 'Clothing').toString()
            def category = TYPE_CATEGORY[itemType] ?: 'Accessories'
            def emoji    = TYPE_EMOJI[itemType] ?: '🎮'

            // SCMM prices are cents (int). Cast safely.
            def priceCents   = (r.buyNowPrice   ?: 0) as Long
            def originalCents = (r.originalPrice ?: priceCents) as Long
            def price        = new BigDecimal(priceCents).divide(new BigDecimal(100))
            def steamPrice   = new BigDecimal(originalCents).divide(new BigDecimal(100))

            // Rarity tier: compute from life-cycle signals. If the item is
            // past its sellEnd date OR has very low supply relative to the
            // total minted, it's Limited. Low-supply but still on sale is
            // Off-Market. Everything else is Standard.
            def rarity = 'Standard'
            try {
                def sellEnd = r.sellEnd ? Date.parse("yyyy-MM-dd'T'HH:mm:ssX", r.sellEnd.toString()) : null
                def supply = (r.supply ?: 0) as Integer
                def total  = (r.supplyTotalKnown ?: supply) as Integer
                if (sellEnd != null && sellEnd.before(nowIso)) {
                    rarity = 'Limited'
                } else if (total > 0 && supply < total * 0.05) {
                    rarity = 'Off-Market'
                }
            } catch (Exception ignore) { /* keep Standard */ }

            def supply     = (r.supplyTotalKnown ?: r.supply ?: 0) as Integer
            def totalSold  = (r.subscriptions ?: 0) as Integer
            def trendInt   = 0
            try {
                def pm = (r.priceMovement ?: 0) as Number
                trendInt = Math.max(-99, Math.min(99, Math.round((pm.doubleValue()) * 100) as int))
            } catch (Exception ignore) {}
            def iconUrl    = (r.iconUrl ?: '').toString()
            def accent     = (r.iconAccentColour ?: '#13192a').toString()
            if (!accent.startsWith('#')) accent = '#13192a'

            def existing = existingByName[name.toLowerCase()]
            if (existing != null) {
                existing.category    = category
                existing.rarity      = rarity
                existing.imageUrl    = iconUrl ?: existing.imageUrl
                existing.iconEmoji   = emoji
                existing.accentColor = accent
                existing.supply      = supply ?: existing.supply
                existing.totalSold   = totalSold ?: existing.totalSold
                // DO NOT overwrite lowestPrice or trendPercent — those
                // come from SteamMarketPriceService which fetches
                // directly from Steam's priceoverview endpoint. SCMM
                // prices are their own marketplace prices, not Steam's.
                // Only set steamPrice as a reference point for the
                // "Steam price" label on item cards.
                existing.steamPrice  = steamPrice
                itemRepository.save(existing)
                updated++
            } else {
                def item = new Item(
                    name:         name,
                    category:     category,
                    rarity:       rarity,
                    imageUrl:     iconUrl,
                    iconEmoji:    emoji,
                    accentColor:  accent,
                    supply:       supply,
                    totalSold:    totalSold,
                    lowestPrice:  price,
                    steamPrice:   steamPrice,
                    trendPercent: trendInt,
                    isListed:     true,
                    createdAt:    now
                )
                itemRepository.save(item)
                existingByName[name.toLowerCase()] = item
                created++
            }
        }

        log.info("SCMM sync complete — created=${created}, updated=${updated}, skipped=${skipped}, total=${remote.size()}")
        [created: created, updated: updated, skipped: skipped, totalRemote: remote.size()]
    }

    /**
     * Scheduled sync — runs on a 30-minute cadence. The first tick is
     * delayed 2 minutes after startup so we don't fight the initial seed.
     */
    @Scheduled(fixedDelay = SYNC_INTERVAL_MS, initialDelay = 2L * 60L * 1000L)
    void scheduledSync() {
        try {
            syncFromScmm()
        } catch (Exception e) {
            log.error("Scheduled SCMM sync failed: ${e.message}")
        }
    }
}
