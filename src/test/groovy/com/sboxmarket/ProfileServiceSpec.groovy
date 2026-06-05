package com.sboxmarket

import com.sboxmarket.model.BuyOrder
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Offer
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
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
 * Coverage for the one-call profile dashboard aggregator.
 *
 * Properties asserted: null for unknown user; balance and transaction
 * sums roll up correctly; counts pull from the right repositories; the
 * "net" figure equals total-sold minus total-purchased.
 */
class ProfileServiceSpec extends Specification {

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

    /** Shared minimal stubs for the no-history baseline used by the
     *  accountStanding specs below — keeps each spec focused on the
     *  bucket logic without re-stubbing eight mocks per test.
     *  saleCount parameterised so tests can exercise the ≥10-sales branch. */
    private void stubBaseline(SteamUser user, long saleCount = 0L) {
        steamUserRepository.findById(user.id) >> Optional.of(user)
        walletRepository.findByUsername(_) >> null
        transactionRepository.sumByWalletAndType(_, _, _) >> null
        transactionRepository.countByWalletAndType(_, 'PURCHASE') >> 0L
        transactionRepository.countByWalletAndType(_, 'SALE')     >> saleCount
        transactionRepository.countCompletedByWalletAndType(_, _) >> 0L
        listingRepository.countActiveBySeller(_)         >> 0L
        listingRepository.sumActiveListingPriceBySeller(_) >> BigDecimal.ZERO
        listingRepository.countOwnedBy(_)                 >> 0L
        listingRepository.sumOwnedInventoryValueBy(_)     >> BigDecimal.ZERO
        buyOrderRepository.countActiveByBuyer(_)          >> 0L
        offerRepository.countPendingByBuyer(_)            >> 0L
        bidRepository.countActiveAutoBidsForUser(_)       >> 0L
    }

    def "buildProfile returns null for an unknown user id"() {
        given:
        steamUserRepository.findById(_) >> Optional.empty()

        when:
        def result = service.buildProfile(999L)

        then:
        result == null
    }

    def "buildProfile aggregates wallet, stats, and counts for a known user"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111', displayName: 'Alice')
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: new BigDecimal("42.50"), currency: 'USD')
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        // Aggregated via SQL per bug #49 — the old findByWalletIdOrderByCreatedAtDesc
        // path pulled every row into memory on every profile view.
        transactionRepository.sumByWalletAndType(500L, 'PURCHASE', false) >> new BigDecimal("30")
        transactionRepository.sumByWalletAndType(500L, 'SALE',     false) >> new BigDecimal("50")
        transactionRepository.sumByWalletAndType(500L, 'DEPOSIT',  true)  >> new BigDecimal("100")
        transactionRepository.countByWalletAndType(500L, 'PURCHASE') >> 2L
        transactionRepository.countByWalletAndType(500L, 'SALE')     >> 1L
        transactionRepository.countCompletedByWalletAndType(500L, 'WITHDRAWAL') >> 0L
        listingRepository.countActiveBySeller(10L)         >> 2L
        listingRepository.sumActiveListingPriceBySeller(10L) >> BigDecimal.ZERO
        listingRepository.countOwnedBy(10L)                 >> 3L
        listingRepository.sumOwnedInventoryValueBy(10L)     >> BigDecimal.ZERO
        buyOrderRepository.countActiveByBuyer(10L)          >> 1L
        offerRepository.countPendingByBuyer(10L)            >> 1L
        bidRepository.countActiveAutoBidsForUser(10L)       >> 0L

        when:
        def result = service.buildProfile(10L)

        then:
        result != null
        result.user == user
        result.wallet.balance == new BigDecimal("42.50")
        result.wallet.currency == 'USD'
        result.wallet.username == 'steam_111'
        result.stats.totalPurchased == new BigDecimal("30")
        result.stats.totalSold      == new BigDecimal("50")
        result.stats.totalDeposited == new BigDecimal("100")
        result.stats.net            == new BigDecimal("20")
        result.stats.purchaseCount  == 2L
        result.stats.saleCount      == 1L
        result.counts.activeListings == 2
        result.counts.inventory      == 3
        result.counts.openBuyOrders  == 1L  // only ACTIVE counts
        result.counts.openOffers     == 1L
        result.counts.activeAutoBids == 0
    }

    def "buildProfile nets purchase-reversing refunds out of Total Purchased + Net"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111', displayName: 'Alice')
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: new BigDecimal("42.50"), currency: 'USD')
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        transactionRepository.sumByWalletAndType(500L, 'PURCHASE', false) >> new BigDecimal("30")
        transactionRepository.sumByWalletAndType(500L, 'SALE',     false) >> new BigDecimal("50")
        transactionRepository.sumByWalletAndType(500L, 'DEPOSIT',  true)  >> new BigDecimal("100")
        // $12 of those purchases was later refunded (trade cancel / protection
        // payout, carrying a listingId) — the wallet was made whole, so it must
        // NOT count toward lifetime spend or the net line.
        transactionRepository.sumRefundedPurchases(500L) >> new BigDecimal("12")
        transactionRepository.countByWalletAndType(500L, 'PURCHASE') >> 2L
        transactionRepository.countByWalletAndType(500L, 'SALE')     >> 1L
        listingRepository.sumActiveListingPriceBySeller(10L) >> BigDecimal.ZERO
        listingRepository.sumOwnedInventoryValueBy(10L)      >> BigDecimal.ZERO

        when:
        def result = service.buildProfile(10L)

        then:
        result.stats.totalPurchased == new BigDecimal("18")   // 30 gross - 12 refunded
        result.stats.net            == new BigDecimal("32")   // 50 sold - 18 net purchased
    }

    def "buildProfile clamps Total Purchased at zero if refunds somehow exceed purchases"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111')
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: BigDecimal.ZERO, currency: 'USD')
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_111') >> wallet
        transactionRepository.sumByWalletAndType(500L, 'PURCHASE', false) >> new BigDecimal("5")
        transactionRepository.sumByWalletAndType(500L, 'SALE',     false) >> BigDecimal.ZERO
        transactionRepository.sumByWalletAndType(500L, 'DEPOSIT',  true)  >> BigDecimal.ZERO
        transactionRepository.sumRefundedPurchases(500L) >> new BigDecimal("9")  // pathological over-refund
        listingRepository.sumActiveListingPriceBySeller(10L) >> BigDecimal.ZERO
        listingRepository.sumOwnedInventoryValueBy(10L)      >> BigDecimal.ZERO

        when:
        def result = service.buildProfile(10L)

        then:
        result.stats.totalPurchased == BigDecimal.ZERO        // clamped, not -4
        result.stats.net            == BigDecimal.ZERO        // 0 sold - 0 net purchased
    }

    def "buildProfile returns zeroes when the user has no wallet and no history"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111')
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername(_) >> null
        transactionRepository.sumByWalletAndType(_, _, _) >> null
        transactionRepository.countByWalletAndType(_, _) >> 0L
        transactionRepository.countCompletedByWalletAndType(_, _) >> 0L
        listingRepository.countActiveBySeller(_)         >> 0L
        listingRepository.sumActiveListingPriceBySeller(_) >> BigDecimal.ZERO
        listingRepository.countOwnedBy(_)                 >> 0L
        listingRepository.sumOwnedInventoryValueBy(_)     >> BigDecimal.ZERO
        buyOrderRepository.countActiveByBuyer(_)          >> 0L
        offerRepository.countPendingByBuyer(_)            >> 0L
        bidRepository.countActiveAutoBidsForUser(_)       >> 0L

        when:
        def result = service.buildProfile(10L)

        then:
        result.wallet.balance == BigDecimal.ZERO
        result.wallet.currency == 'USD'
        result.stats.totalPurchased == BigDecimal.ZERO
        result.stats.totalSold      == BigDecimal.ZERO
        result.stats.net            == BigDecimal.ZERO
        result.counts.activeListings == 0
    }

    def "buildProfile.twoFactorEnabled is false when the user has no TOTP secret"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111', totpSecret: null)
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername(_) >> null
        transactionRepository.sumByWalletAndType(_, _, _) >> null
        transactionRepository.countByWalletAndType(_, _) >> 0L
        transactionRepository.countCompletedByWalletAndType(_, _) >> 0L
        listingRepository.countActiveBySeller(_)         >> 0L
        listingRepository.sumActiveListingPriceBySeller(_) >> BigDecimal.ZERO
        listingRepository.countOwnedBy(_)                 >> 0L
        listingRepository.sumOwnedInventoryValueBy(_)     >> BigDecimal.ZERO
        buyOrderRepository.countActiveByBuyer(_)          >> 0L
        offerRepository.countPendingByBuyer(_)            >> 0L
        bidRepository.countActiveAutoBidsForUser(_)       >> 0L

        expect:
        service.buildProfile(10L).twoFactorEnabled == false
    }

    def "buildProfile.twoFactorEnabled is true when the user has a TOTP secret (without leaking it)"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111', totpSecret: 'JBSWY3DPEHPK3PXP')
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername(_) >> null
        transactionRepository.sumByWalletAndType(_, _, _) >> null
        transactionRepository.countByWalletAndType(_, _) >> 0L
        transactionRepository.countCompletedByWalletAndType(_, _) >> 0L
        listingRepository.countActiveBySeller(_)         >> 0L
        listingRepository.sumActiveListingPriceBySeller(_) >> BigDecimal.ZERO
        listingRepository.countOwnedBy(_)                 >> 0L
        listingRepository.sumOwnedInventoryValueBy(_)     >> BigDecimal.ZERO
        buyOrderRepository.countActiveByBuyer(_)          >> 0L
        offerRepository.countPendingByBuyer(_)            >> 0L
        bidRepository.countActiveAutoBidsForUser(_)       >> 0L

        when:
        def result = service.buildProfile(10L)

        then:
        result.twoFactorEnabled == true
        // The JSON-ignore on totpSecret means serializing the profile
        // still hides the secret — verify the *serialized* form doesn't
        // include it, even though the entity still holds the raw value.
        def json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result)
        !json.contains('JBSWY3DPEHPK3PXP')
        !json.contains('totpSecret')
        json.contains('"twoFactorEnabled":true')
    }

    // ── Account Standing gauge (batch 238 / Visual Manual §23) ──────

    def "accountStanding is 'banned' when the user is banned, with the ban reason as note"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111', banned: true, banReason: 'Rug-pulling buyers')
        stubBaseline(user)
        // Banned short-circuits before the review aggregate is read.

        expect:
        def r = service.buildProfile(10L).accountStanding
        r.state == 'banned'
        r.label == 'Banned'
        r.note == 'Rug-pulling buyers'
    }

    def "accountStanding is 'good' by default for new / clean accounts with no history"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111')
        stubBaseline(user)
        reviewRepository.aggregateForUser(10L) >> [[0L, null]]

        expect:
        def r = service.buildProfile(10L).accountStanding
        r.state == 'good'
        r.note == 'No restrictions on your account.'
    }

    def "accountStanding is 'excellent' when the user has ≥10 sales and (no reviews OR avg ≥ 4.0)"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111')
        stubBaseline(user, 15L)
        reviewRepository.aggregateForUser(10L) >> [[8L, 4.6 as Double]]

        expect:
        service.buildProfile(10L).accountStanding.state == 'excellent'
    }

    def "accountStanding is 'poor' when review avg < 3.0 with at least 3 reviews"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111')
        stubBaseline(user)
        reviewRepository.aggregateForUser(10L) >> [[4L, 2.5 as Double]]

        expect:
        def r = service.buildProfile(10L).accountStanding
        r.state == 'poor'
        r.note.contains('2.5★')
    }

    def "accountStanding is 'at_risk' when review avg < 2.0 with at least 3 reviews"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111')
        stubBaseline(user)
        reviewRepository.aggregateForUser(10L) >> [[5L, 1.4 as Double]]

        expect:
        def r = service.buildProfile(10L).accountStanding
        r.state == 'at_risk'
    }

    def "accountStanding respects the 3-review floor — one 1★ doesn't tank a new account"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111')
        stubBaseline(user)
        // A lone 1★ grudge review shouldn't drop the user into 'poor'.
        reviewRepository.aggregateForUser(10L) >> [[1L, 1.0 as Double]]

        expect:
        service.buildProfile(10L).accountStanding.state == 'good'
    }
}
