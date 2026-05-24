package com.sboxmarket.service

import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.BidRepository
import com.sboxmarket.repository.BuyOrderRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.repository.ReviewRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Aggregated profile view — bundles the user entity with every piece of data
 * the expanded Profile modal needs (balance, stats, counts) so the frontend
 * makes one fetch instead of six.
 */
@Service
@Slf4j
class ProfileService {

    @Autowired SteamUserRepository steamUserRepository
    @Autowired WalletRepository walletRepository
    @Autowired ListingRepository listingRepository
    @Autowired TransactionRepository transactionRepository
    @Autowired OfferRepository offerRepository
    @Autowired BuyOrderRepository buyOrderRepository
    @Autowired BidRepository bidRepository
    @Autowired(required = false) ReviewRepository reviewRepository

    /**
     * Collapse the user's history into a CSFloat-style 5-node standing
     * gauge (Excellent → Good → Poor → At Risk → Banned). Rendered on the
     * Profile → Personal card per Visual Manual §23.
     *
     *   banned       → user.banned == true
     *   at_risk      → 1-star review avg (very rare but severe)
     *   poor         → rating avg < 3.0 with ≥3 reviews
     *   excellent    → ≥10 completed sales AND (no reviews OR avg ≥ 4.0)
     *   good         → default (new / clean accounts)
     *
     * Never drops from good→poor on a single bad review — the ≥3-review
     * floor prevents a lone grudge-rating from tanking someone's standing.
     */
    private Map computeAccountStanding(SteamUser user, long saleCount) {
        if (user?.banned) return [state: 'banned', label: 'Banned', note: user.banReason ?: 'Account banned by staff.']
        Double avg = null
        Long reviewCount = 0L
        try {
            def rows = reviewRepository?.aggregateForUser(user.id)
            if (rows && rows[0] != null) {
                reviewCount = ((rows[0][0] as Number) ?: 0).longValue()
                avg = rows[0][1] == null ? null : ((rows[0][1] as Number).doubleValue())
            }
        } catch (Exception ignore) { /* aggregate query optional */ }
        if (avg != null && reviewCount >= 3L && avg < 2.0d) {
            return [state: 'at_risk', label: 'At Risk', note: "Average rating ${String.format('%.1f', avg)}★ across ${reviewCount} reviews. Keep trading reliably to recover."]
        }
        if (avg != null && reviewCount >= 3L && avg < 3.0d) {
            return [state: 'poor', label: 'Poor', note: "Average rating ${String.format('%.1f', avg)}★ across ${reviewCount} reviews."]
        }
        if (saleCount >= 10L && (reviewCount == 0L || (avg != null && avg >= 4.0d))) {
            return [state: 'excellent', label: 'Excellent', note: 'No restrictions on your account.']
        }
        [state: 'good', label: 'Good', note: 'No restrictions on your account.']
    }

    @Transactional(readOnly = true)
    Map buildProfile(Long userId) {
        def user = steamUserRepository.findById(userId).orElse(null)
        if (user == null) return null

        def wallet = walletRepository.findByUsername("steam_${user.steamId64}")
        def balance = wallet?.balance ?: BigDecimal.ZERO
        def walletId = wallet?.id ?: -1L

        // Aggregate each transaction bucket in SQL instead of loading every
        // row and filtering in Groovy. This endpoint fires on every profile
        // fetch (post-login + /me refresh) so the old N-rows-per-view pull
        // scales badly — a user with years of trading history would walk
        // thousands of rows just to render the totals line.
        def totalPurchased = transactionRepository.sumByWalletAndType(walletId, 'PURCHASE', false) ?: BigDecimal.ZERO
        def totalSold      = transactionRepository.sumByWalletAndType(walletId, 'SALE',     false) ?: BigDecimal.ZERO
        def totalDeposited = transactionRepository.sumByWalletAndType(walletId, 'DEPOSIT',  true)  ?: BigDecimal.ZERO
        def purchaseCount  = transactionRepository.countByWalletAndType(walletId, 'PURCHASE')
        def saleCount      = transactionRepository.countByWalletAndType(walletId, 'SALE')
        // Includes BOTH the legacy `WITHDRAW` and the canonical `WITHDRAWAL`
        // type spellings — StripeService.requestWithdraw still stamps
        // `WITHDRAW` on new rows, so a single-type equality query (the
        // previous `countCompletedByWalletAndType(..., 'WITHDRAWAL')`)
        // silently always returned 0 and the Profile → Withdrawals count
        // never moved off zero no matter how many payouts the user took.
        def withdrawalCount = transactionRepository.countCompletedWithdrawalsByWallet(walletId)

        def net = (totalSold as BigDecimal) - (totalPurchased as BigDecimal)

        // Single-query aggregates (batch 1004) — avoids hydrating every
        // listing + inventory row just to sum them. For a power-user
        // with hundreds of rows on each side, /profile was pulling
        // thousands of Listings with JOIN FETCH l.item on every request.
        def activeListings       = listingRepository.countActiveBySeller(userId)
        def activeListingsValue  = listingRepository.sumActiveListingPriceBySeller(userId) ?: BigDecimal.ZERO
        def ownedInventory       = listingRepository.countOwnedBy(userId)
        // Inventory value approximation — sum each item's current floor
        // price (with steamPrice fallback). Matches CSFloat's surface.
        def ownedInventoryValue  = listingRepository.sumOwnedInventoryValueBy(userId) ?: BigDecimal.ZERO
        def openBuyOrders   = buyOrderRepository.countActiveByBuyer(userId)
        def activeAutoBids  = bidRepository.countActiveAutoBidsForUser(userId)
        def openOffers      = offerRepository.countPendingByBuyer(userId)

        [
            user: user,
            // Derived safe boolean so the frontend can render the 2FA UI
            // state without us having to serialize the secret itself.
            // Before bug #19 the client checked `user.totpSecret` directly
            // which meant the raw base32 secret shipped in every profile
            // response.
            twoFactorEnabled: user.totpSecret != null,
            wallet: [
                balance:  balance,
                currency: wallet?.currency ?: 'USD',
                username: wallet?.username
            ],
            stats: [
                totalPurchased:  totalPurchased,
                totalSold:       totalSold,
                totalDeposited:  totalDeposited,
                net:             net,
                purchaseCount:   purchaseCount,
                saleCount:       saleCount,
                withdrawalCount: withdrawalCount
            ],
            counts: [
                activeListings: activeListings,
                inventory:      ownedInventory,
                openBuyOrders:  openBuyOrders,
                openOffers:     openOffers,
                activeAutoBids: activeAutoBids
            ],
            // Estimated $ value of items the user currently owns + has
            // listed for sale. The personal-tab widget surfaces these so
            // the user can see "my portfolio is worth ~$X" at a glance.
            portfolio: [
                inventoryValue: ownedInventoryValue,
                listingsValue:  activeListingsValue,
                totalValue:     ownedInventoryValue + activeListingsValue
            ],
            // Self-facing rating (batch 491) — user sees their own
            // seller star average + review count on their profile
            // page. Null when they've never received a review yet.
            rating: computeUserRating(userId),
            accountStanding: computeAccountStanding(user, saleCount as long)
        ]
    }

    /** Compact review summary for a user — same aggregate the public
     *  stall page uses. Nulled out when reviewRepository isn't wired
     *  (test harness) or the user has no reviews yet. */
    private Map computeUserRating(Long userId) {
        if (reviewRepository == null || userId == null) return null
        try {
            def agg = reviewRepository.aggregateForUser(userId)
            if (agg == null || agg.isEmpty()) return null
            def row = agg[0]
            def count = (row[0] ?: 0L) as long
            if (count <= 0L) return null
            def avg = row[1] != null
                ? (row[1] as BigDecimal).setScale(2, java.math.RoundingMode.HALF_UP)
                : null
            [count: count, average: avg]
        } catch (Exception ignored) {
            null
        }
    }
}
