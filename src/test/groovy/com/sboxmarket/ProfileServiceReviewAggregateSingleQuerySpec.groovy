package com.sboxmarket

import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.BidRepository
import com.sboxmarket.repository.BuyOrderRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.repository.ReviewRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.ProfileService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Regression — buildProfile must hit the review COUNT/AVG aggregate
 * AT MOST ONCE per call.
 *
 * Pre-fix, the aggregate query ran twice per /me hit: once inside
 * `computeAccountStanding` (for the standing gauge) and a second
 * time inside `computeUserRating` (for the rating widget). Since
 * /me fires on every post-login and every client refresh, that
 * doubled the review-table scan cost for every active user — and
 * the scan grows with the number of reviews received, so power
 * sellers paid the heaviest tax.
 *
 * The fix threads a single aggregate fetch through both consumers.
 * This spec pins down the call count so the duplicate query can't
 * silently come back.
 */
class ProfileServiceReviewAggregateSingleQuerySpec extends Specification {

    SteamUserRepository   steamUserRepository   = Mock()
    WalletRepository      walletRepository      = Mock()
    ListingRepository     listingRepository     = Mock()
    TransactionRepository transactionRepository = Mock()
    OfferRepository       offerRepository       = Mock()
    BuyOrderRepository    buyOrderRepository    = Mock()
    BidRepository         bidRepository         = Mock()
    ReviewRepository      reviewRepository      = Mock()

    @Subject
    ProfileService service = new ProfileService(
        steamUserRepository   : steamUserRepository,
        walletRepository      : walletRepository,
        listingRepository     : listingRepository,
        transactionRepository : transactionRepository,
        offerRepository       : offerRepository,
        buyOrderRepository    : buyOrderRepository,
        bidRepository         : bidRepository,
        reviewRepository      : reviewRepository
    )

    def "buildProfile invokes ReviewRepository.aggregateForUser exactly once even though it feeds both rating and accountStanding"() {
        given:
        def user = new SteamUser(id: 42L, steamId64: '111', displayName: 'PowerSeller')
        steamUserRepository.findById(42L) >> Optional.of(user)
        walletRepository.findByUsername(_) >> null
        transactionRepository.sumByWalletAndType(_, _, _) >> null
        transactionRepository.countByWalletAndType(_, _) >> 0L
        transactionRepository.countCompletedByWalletAndType(_, _) >> 0L
        listingRepository.countActiveBySeller(_)            >> 0L
        listingRepository.sumActiveListingPriceBySeller(_)  >> BigDecimal.ZERO
        listingRepository.countOwnedBy(_)                   >> 0L
        listingRepository.sumOwnedInventoryValueBy(_)       >> BigDecimal.ZERO
        buyOrderRepository.countActiveByBuyer(_)            >> 0L
        offerRepository.countPendingByBuyer(_)              >> 0L
        bidRepository.countActiveAutoBidsForUser(_)         >> 0L

        when:
        def result = service.buildProfile(42L)

        then:
        // Exactly one DB hit for the review aggregate — pre-fix this
        // would have fired twice, doubling the review-table scan on
        // every /me poll.
        1 * reviewRepository.aggregateForUser(42L) >> [[5L, 4.5 as Double]]
        0 * reviewRepository.aggregateForUser(_)

        and: "both consumers still got their data from the single fetch"
        result.rating.count == 5L
        result.rating.average == new BigDecimal("4.50")
        result.accountStanding.state == 'good' // <10 sales, avg 4.5 → not excellent, but not poor either
    }

    def "buildProfile still resolves a sensible profile when the review repo is unwired (null)"() {
        given:
        // Mirror the test-harness path where reviewRepository was never
        // autowired — the cached-aggregate refactor must not regress
        // the null-safe early return.
        def bareService = new ProfileService(
            steamUserRepository   : steamUserRepository,
            walletRepository      : walletRepository,
            listingRepository     : listingRepository,
            transactionRepository : transactionRepository,
            offerRepository       : offerRepository,
            buyOrderRepository    : buyOrderRepository,
            bidRepository         : bidRepository,
            reviewRepository      : null
        )
        def user = new SteamUser(id: 7L, steamId64: '222')
        steamUserRepository.findById(7L) >> Optional.of(user)
        walletRepository.findByUsername(_) >> null
        transactionRepository.sumByWalletAndType(_, _, _) >> null
        transactionRepository.countByWalletAndType(_, _) >> 0L
        transactionRepository.countCompletedByWalletAndType(_, _) >> 0L
        listingRepository.countActiveBySeller(_)            >> 0L
        listingRepository.sumActiveListingPriceBySeller(_)  >> BigDecimal.ZERO
        listingRepository.countOwnedBy(_)                   >> 0L
        listingRepository.sumOwnedInventoryValueBy(_)       >> BigDecimal.ZERO
        buyOrderRepository.countActiveByBuyer(_)            >> 0L
        offerRepository.countPendingByBuyer(_)              >> 0L
        bidRepository.countActiveAutoBidsForUser(_)         >> 0L

        when:
        def result = bareService.buildProfile(7L)

        then:
        result.rating == null
        result.accountStanding.state == 'good'
    }
}
