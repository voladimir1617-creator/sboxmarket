package com.sboxmarket.service

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
 * Minimal first-boot bootstrap. Intentionally does NOT create fake items,
 * listings, auctions, or loadouts — per the operator's request we stopped
 * placeholder-ing the UI. Real content now comes from:
 *
 *  - `SboxApiService.scheduledSync()`  — pulls the canonical s&box skin
 *    catalogue from the SCMM mirror every 30 minutes (and on first boot
 *    when the DB is empty).
 *  - Live users listing items from their Steam inventory via the Sell
 *    Items → Steam Inventory tab.
 *
 * What we DO still create:
 *  - A single "demo" wallet with a small starting balance so an
 *    unauthenticated browser still sees a working wallet widget.
 */
@Service
@Slf4j
class SeedService {

    @Autowired ItemRepository itemRepository
    @Autowired ListingRepository listingRepository
    @Autowired PriceHistoryRepository priceHistoryRepository
    @Autowired WalletRepository walletRepository
    @Autowired LoadoutRepository loadoutRepository
    @Autowired LoadoutSlotRepository loadoutSlotRepository
    @Autowired SboxApiService sboxApiService

    @Transactional
    void seed() {
        // 1) Demo wallet — so a logged-out browser sees "$250 demo balance"
        //    and can click around. Harmless because only the session owner
        //    can spend from it, and it's recreated if deleted.
        if (walletRepository.count() == 0) {
            walletRepository.save(new Wallet(username: "demo", balance: new BigDecimal("250.00"), currency: "USD"))
            log.info("Seeded demo wallet (\$250.00 starting balance)")
        }

        // 2) If the catalogue is empty on first boot, kick the SCMM sync
        //    immediately instead of waiting 2 minutes for the scheduler.
        //    Failure is non-fatal — startup continues and the scheduler
        //    will retry on its normal cadence.
        if (itemRepository.count() == 0) {
            try {
                def result = sboxApiService.syncFromScmm()
                log.info("Initial SCMM sync on first boot → ${result}")
            } catch (Exception e) {
                log.warn("Initial SCMM sync failed — scheduler will retry: ${e.message}")
            }
        }
    }
}
