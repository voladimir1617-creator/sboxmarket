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

    @Subject
    ProfileService service = new ProfileService(
        steamUserRepository   : steamUserRepository,
        walletRepository      : walletRepository,
        listingRepository     : listingRepository,
        transactionRepository : transactionRepository,
        offerRepository       : offerRepository,
        buyOrderRepository    : buyOrderRepository,
        bidRepository         : bidRepository
    )

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
        listingRepository.findActiveBySeller(10L)   >> [new Listing(), new Listing()]
        listingRepository.findOwnedBy(10L)          >> [new Listing(), new Listing(), new Listing()]
        buyOrderRepository.countActiveByBuyer(10L)  >> 1L
        offerRepository.countPendingByBuyer(10L)    >> 1L
        bidRepository.findActiveAutoBidsForUser(10L) >> []

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

    def "buildProfile returns zeroes when the user has no wallet and no history"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '111')
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername(_) >> null
        transactionRepository.sumByWalletAndType(_, _, _) >> null
        transactionRepository.countByWalletAndType(_, _) >> 0L
        transactionRepository.countCompletedByWalletAndType(_, _) >> 0L
        listingRepository.findActiveBySeller(_) >> []
        listingRepository.findOwnedBy(_)        >> []
        buyOrderRepository.countActiveByBuyer(_) >> 0L
        offerRepository.countPendingByBuyer(_)   >> 0L
        bidRepository.findActiveAutoBidsForUser(_) >> []

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
        listingRepository.findActiveBySeller(_) >> []
        listingRepository.findOwnedBy(_)        >> []
        buyOrderRepository.countActiveByBuyer(_) >> 0L
        offerRepository.countPendingByBuyer(_)   >> 0L
        bidRepository.findActiveAutoBidsForUser(_) >> []

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
        listingRepository.findActiveBySeller(_) >> []
        listingRepository.findOwnedBy(_)        >> []
        buyOrderRepository.countActiveByBuyer(_) >> 0L
        offerRepository.countPendingByBuyer(_)   >> 0L
        bidRepository.findActiveAutoBidsForUser(_) >> []

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
}
