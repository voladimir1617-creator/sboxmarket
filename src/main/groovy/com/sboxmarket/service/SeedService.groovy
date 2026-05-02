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
        backfillDemoSales()
        backfillPublicLoadouts()
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
            def fixtures = [
                [name: 'Cardboard Connoisseur', desc: 'Budget-tier brown-aesthetic build, under $30 total.',                  owner: 'CardKing'],
                [name: 'Cybernetic Drifter',    desc: 'Sci-fi loadout - neon helmet, polymer plates, glow accents.',           owner: 'NeonArc'],
                [name: 'Plague Doctor',         desc: 'Victorian-noir set - long coat, beak mask, leather gloves.',            owner: 'BoneTender'],
                [name: 'WW1 Trench Soldier',    desc: 'Period-correct kit pulling from the WW1 collection.',                   owner: 'TrenchVet'],
                [name: 'OG Streetwear',         desc: 'Casual-fit loadout: sneakers, joggers, crossbody bag, plain tee.',      owner: 'Frame']
            ]
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
            def onePage = org.springframework.data.domain.PageRequest.of(0, 1)
            long now = System.currentTimeMillis()
            fixtures.eachWithIndex { fx, i ->
                def loadout = new Loadout(
                    ownerUserId: -100L - i,  // synthetic owner ids, never collide with real Steam ids
                    ownerName:   fx.owner,
                    name:        fx.name,
                    description: fx.desc,
                    visibility:  'PUBLIC',
                    favorites:   (5 - i) * 7,  // descending so Discover sort is sensible
                    createdAt:   now - (i + 1) * 86_400_000L,
                    updatedAt:   now - (i + 1) * 3_600_000L
                )
                loadoutRepository.save(loadout)
                BigDecimal total = BigDecimal.ZERO
                slotsList.each { slotName ->
                    def slot = new LoadoutSlot(loadoutId: loadout.id, slot: slotName)
                    def category = slotName == 'Wild' ? '' : slotName
                    def pool = itemRepository.findCheapestInBudget(category, new BigDecimal("1000"), onePage)
                    def pick = pool.isEmpty() ? null : pool.first()
                    if (pick != null) {
                        slot.itemId        = pick.id
                        slot.itemName      = pick.name
                        slot.itemEmoji     = pick.iconEmoji
                        slot.snapshotPrice = pick.lowestPrice ?: BigDecimal.ZERO
                        total = total + (pick.lowestPrice ?: BigDecimal.ZERO)
                    }
                    loadoutSlotRepository.save(slot)
                }
                loadout.totalValue = total
                loadoutRepository.save(loadout)
            }
            log.info("Seeded ${fixtures.size()} public loadouts so /loadout/{id} renders on first boot")
        } catch (Exception e) {
            log.warn("Public-loadout seed skipped: ${e.message}", e)
        }
    }
}
