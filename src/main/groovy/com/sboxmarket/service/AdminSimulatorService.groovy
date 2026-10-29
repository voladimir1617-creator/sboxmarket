package com.sboxmarket.service

import com.sboxmarket.config.LiveMoneyGuard
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.service.security.AdminAuthorization
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Admin-only "simulator" — spins up fake listings so an operator can seed the
 * marketplace for manual QA without waiting on real users. Lives in its own
 * service (SRP) instead of bloating AdminService further.
 *
 * Every simulated row is clearly marked:
 *   - sellerName     : "SIM · <handle>"
 *   - description    : starts with "[SIMULATED]"
 *   - sellerUserId   : null (system listing)
 * so ops can one-shot delete them later with `clearSimulated()`.
 */
@Service
@Slf4j
class AdminSimulatorService {

    @Autowired ItemRepository itemRepository
    @Autowired ListingRepository listingRepository
    @Autowired AdminAuthorization adminAuthorization
    @Autowired(required = false) AuditService auditService
    /** Read by {@link #refusalReason()}. Null counts as real money
     *  (MoneyMode.of fails closed), so a context-less caller is refused. */
    @Autowired(required = false) Environment environment

    static final String REFUSAL_REAL_MONEY =
        'simulator disabled: real-money deployment (prod profile or live Stripe key)'

    /**
     * Why the simulator must not run here, or null when it may.
     *
     * Simulated rows are seller-less (`sellerUserId: null`), which
     * PurchaseService treats as house listings: a real buyer's real balance
     * is debited and nothing real is delivered. `clearSimulated` hard-deletes
     * by a sellerName / description LIKE, which can also hit a buyer's SOLD
     * row or a real listing whose text happens to match. So on any deployment
     * that can move real money — `prod` profile, a live Stripe key, or an
     * unclassifiable one (LiveMoneyGuard / MoneyMode) — both are refused.
     * Same gate the demo seeder and dev-login use.
     */
    String refusalReason() {
        boolean realMoney
        try {
            realMoney = LiveMoneyGuard.isRealMoney(environment)
        } catch (Throwable unreadable) {
            realMoney = true
        }
        realMoney ? REFUSAL_REAL_MONEY : null
    }

    private void assertSimulatorAllowed(Long adminUserId, String action) {
        String refusal = refusalReason()
        if (refusal != null) {
            log.warn("Admin ${adminUserId} ${action} refused — ${refusal} (profiles=${environment?.activeProfiles})")
            throw new ForbiddenException('SIMULATOR_DISABLED', refusal)
        }
    }

    /** Random pool of plausible seller handles so the grid doesn't look copy-pasted. */
    private static final List<String> HANDLES = [
        'TestBot_01','TestBot_02','TestBot_03',
        'QA_Buyer','QA_Seller','LoadTester',
        'DemoRunner','StagingOne','StagingTwo'
    ]

    /** Price jitter so simulated listings don't all land on round numbers.
     *  Kept as BigDecimal so multiplication with `item.lowestPrice` returns
     *  a BigDecimal — double would demote the result and crash .setScale(). */
    private static final List<BigDecimal> JITTER = [
        new BigDecimal("0.87"), new BigDecimal("0.93"), new BigDecimal("0.99"),
        new BigDecimal("1.04"), new BigDecimal("1.12"), new BigDecimal("1.23")
    ]

    /**
     * Create up to `count` simulated listings. Distribution:
     *   - Walks the catalogue in shuffled order
     *   - Skips items where no catalogue row has `lowestPrice` set
     *   - For each picked item, creates one BUY_NOW listing and optionally one
     *     AUCTION listing (~1/5 of the time) that closes in 24 hours.
     */
    @Transactional
    Map simulateListings(Long adminUserId, int count) {
        adminAuthorization.requireAdmin(adminUserId)
        assertSimulatorAllowed(adminUserId, 'simulate')
        if (count <= 0 || count > 100) {
            throw new BadRequestException("INVALID_COUNT", "count must be between 1 and 100")
        }

        def catalog = itemRepository.findAll()
                .findAll { it.lowestPrice != null && it.lowestPrice > BigDecimal.ZERO }
        if (catalog.isEmpty()) {
            throw new BadRequestException("CATALOG_EMPTY", "No catalogue items with prices — run /api/admin/sync-scmm first")
        }

        Collections.shuffle(catalog)
        def rnd = new Random()
        def created = []

        // Iterate exactly `count` times — the modulo wraps when the catalogue
        // is smaller than `count`, deliberately producing duplicate listings
        // on the same item (multiple sellers offering the same hat is realistic
        // marketplace state, and exactly what QA needs to smoke-test sorting
        // and floor-price aggregation). The previous bound
        // `Math.min(count, catalog.size() * 3)` silently capped the request at
        // 3× catalogue size, so on a fresh dev DB with 5 catalogue rows an
        // admin asking for 100 listings got only 15 with no error — the admin
        // panel then showed "Created 15" instead of the expected "Created 100"
        // and the QA fixture was too sparse to exercise the grid.
        (0..<count).each { idx ->
            def item = catalog[idx % catalog.size()]
            def handle = HANDLES[rnd.nextInt(HANDLES.size())]
            def jitter = JITTER[rnd.nextInt(JITTER.size())]
            def price = (item.lowestPrice * jitter).setScale(2, BigDecimal.ROUND_HALF_UP)
            if (price <= BigDecimal.ZERO) price = new BigDecimal("0.25")

            def listing = new Listing(
                item:          item,
                price:         price,
                sellerName:    "SIM · ${handle}",
                sellerAvatar:  handle.take(2).toUpperCase(),
                status:        'ACTIVE',
                condition:     '',
                rarityScore:   BigDecimal.ZERO,
                listingType:   'BUY_NOW',
                description:   "[SIMULATED] admin-generated test listing",
                sellerUserId:  null
            )
            listingRepository.save(listing)
            created << listing

            // ~1 in 5 picks also gets a 24h auction so admins can smoke-test the
            // bid flow without waiting for real auctions.
            if (rnd.nextInt(5) == 0) {
                def auction = new Listing(
                    item:          item,
                    price:         price,
                    sellerName:    "SIM · ${handle}",
                    sellerAvatar:  handle.take(2).toUpperCase(),
                    status:        'ACTIVE',
                    condition:     '',
                    rarityScore:   BigDecimal.ZERO,
                    listingType:   'AUCTION',
                    expiresAt:     System.currentTimeMillis() + 24L * 3600L * 1000L,
                    description:   "[SIMULATED] admin-generated test auction",
                    sellerUserId:  null
                )
                listingRepository.save(auction)
                created << auction
            }
        }

        auditService?.log('ADMIN_SIMULATE_LISTINGS', adminUserId, null, null,
            "Spawned ${created.size()} simulated listings")
        log.info("Admin ${adminUserId} spawned ${created.size()} simulated listings")
        [created: created.size(), ids: created*.id]
    }

    /** Delete every listing that's tagged as simulated. Non-destructive to real data. */
    @Transactional
    Map clearSimulated(Long adminUserId) {
        adminAuthorization.requireAdmin(adminUserId)
        assertSimulatorAllowed(adminUserId, 'clear-simulated')
        def all = listingRepository.findSimulated()
        def removed = all.size()
        listingRepository.deleteAll(all)
        auditService?.log('ADMIN_CLEAR_SIMULATED', adminUserId, null, null,
            "Removed ${removed} simulated listings")
        log.info("Admin ${adminUserId} cleared ${removed} simulated listings")
        [removed: removed]
    }

    /** Count of currently-active simulated listings, for the admin UI header. */
    Map countSimulated() {
        [count: listingRepository.countSimulated()]
    }
}
