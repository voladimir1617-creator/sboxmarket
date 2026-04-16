package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.InsufficientBalanceException
import com.sboxmarket.exception.ListingNotAvailableException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.PurchaseService
import com.sboxmarket.service.TradeService
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit tests for the money path. We mock the repository layer so these are
 * fast and isolated from the database. The asserts check both the typed
 * exception class AND the side effects (balance, listing state, transaction).
 */
class PurchaseServiceSpec extends Specification {

    ListingRepository     listingRepo = Mock()
    WalletRepository      walletRepo  = Mock()
    TransactionRepository txRepo      = Mock()
    SteamUserRepository   steamUserRepo = Mock()
    BanGuard              banGuard    = Mock()

    @Subject
    PurchaseService service = new PurchaseService(
        listingRepository    : listingRepo,
        walletRepository     : walletRepo,
        transactionRepository: txRepo,
        steamUserRepository  : steamUserRepo,
        banGuard             : banGuard
    )

    def "buy succeeds: debits buyer, marks listing SOLD, records transaction"() {
        given:
        def buyer = new Wallet(id: 1L, username: "steam_111", balance: new BigDecimal("100.00"))
        def item = new Item(id: 10L, name: "Wizard Hat", lowestPrice: new BigDecimal("50.00"))
        def listing = new Listing(id: 5L, item: item, price: new BigDecimal("50.00"),
                                  status: 'ACTIVE', sellerName: "Bot")

        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        def result = service.buy(1L, 999L, 5L)

        then:
        result.newBalance == new BigDecimal("50.00")
        listing.status == 'SOLD'
        listing.buyerUserId == 999L
        listing.soldAt != null
        1 * walletRepo.save({ it.balance == new BigDecimal("50.00") })
        1 * listingRepo.save(listing)
        1 * txRepo.save({ it.type == 'PURCHASE' && it.amount == new BigDecimal("50.00") })
    }

    def "buy throws InsufficientBalanceException when wallet has too little"() {
        given:
        def buyer = new Wallet(id: 1L, balance: new BigDecimal("10.00"))
        def item = new Item(id: 10L, name: "Skull Helmet")
        def listing = new Listing(id: 5L, item: item, price: new BigDecimal("50.00"), status: 'ACTIVE')

        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        service.buy(1L, 999L, 5L)

        then:
        def ex = thrown(InsufficientBalanceException)
        ex.required == new BigDecimal("50.00")
        ex.available == new BigDecimal("10.00")
        ex.code == "INSUFFICIENT_BALANCE"

        and: "no money moved, no listing change"
        0 * walletRepo.save(_)
        0 * listingRepo.save(_)
        0 * txRepo.save(_)
    }

    def "buy throws ListingNotAvailable when status != ACTIVE"() {
        given:
        def buyer = new Wallet(id: 1L, balance: new BigDecimal("100.00"))
        def listing = new Listing(id: 5L, item: new Item(id: 10L), price: new BigDecimal("10.00"), status: 'SOLD')

        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        service.buy(1L, 999L, 5L)

        then:
        def ex = thrown(ListingNotAvailableException)
        ex.code == "LISTING_NOT_AVAILABLE"
    }

    def "buy throws NotFoundException when wallet missing"() {
        given:
        walletRepo.findById(1L) >> Optional.empty()

        when:
        service.buy(1L, 999L, 5L)

        then:
        thrown(NotFoundException)
    }

    def "buy throws BadRequest when buyer is also seller"() {
        given:
        def buyer = new Wallet(id: 1L, balance: new BigDecimal("100.00"))
        def listing = new Listing(id: 5L, item: new Item(id: 10L),
                                  price: new BigDecimal("10.00"),
                                  status: 'ACTIVE', sellerUserId: 999L)

        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        service.buy(1L, 999L, 5L)

        then:
        def ex = thrown(BadRequestException)
        ex.code == "OWN_LISTING"
    }

    def "buy creates a P2P escrow Trade instead of crediting the seller immediately"() {
        given: "a buyer with funds, a seller with a wallet, an active listing at \$100"
        def buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal("200.00"), currency: 'USD')
        def seller = new SteamUser(id: 2L, steamId64: '222')
        def sellerWallet = new Wallet(id: 500L, username: 'steam_222', balance: new BigDecimal("0.00"), currency: 'USD')
        def listing = new Listing(
            id: 5L,
            item: new Item(id: 10L, name: 'Wizard Hat'),
            price: new BigDecimal("100.00"),
            status: 'ACTIVE',
            sellerName: 'Bob',
            sellerUserId: 2L
        )
        def tradeService = Mock(TradeService)
        service.tradeService = tradeService
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        steamUserRepo.findById(2L) >> Optional.of(seller)
        walletRepo.findByUsername('steam_222') >> sellerWallet

        when:
        def result = service.buy(1L, 999L, 5L)

        then:
        result.newBalance == new BigDecimal("100.00")
        // Buyer was debited
        1 * walletRepo.save({ Wallet w -> w.balance == new BigDecimal("100.00") })
        // Seller was NOT credited immediately — funds held in escrow
        sellerWallet.balance == new BigDecimal("0.00")
        // A Trade record was created for the P2P escrow
        1 * tradeService.open(5L, 10L, 'Wizard Hat', 999L, 1L, 2L, 500L, new BigDecimal("100.00"))
        // Purchase tx recorded on buyer side
        1 * txRepo.save({ it.type == 'PURCHASE' })
        // NO immediate SALE tx — that happens when the buyer confirms receipt
        0 * txRepo.save({ it.type == 'SALE' })
    }

    def "buy still completes when seller has no wallet (falls through gracefully)"() {
        given:
        def buyer = new Wallet(id: 1L, balance: new BigDecimal("200.00"), currency: 'USD')
        def seller = new SteamUser(id: 2L, steamId64: '222')
        def listing = new Listing(
            id: 5L,
            item: new Item(id: 10L, name: 'x'),
            price: new BigDecimal("50.00"),
            status: 'ACTIVE',
            sellerUserId: 2L
        )
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        steamUserRepo.findById(2L) >> Optional.of(seller)
        walletRepo.findByUsername('steam_222') >> null  // seller wallet doesn't exist

        when:
        def result = service.buy(1L, 999L, 5L)

        then:
        // Buy completes, buyer debited, no crash
        result.newBalance == new BigDecimal("150.00")
        listing.status == 'SOLD'
        // Only buyer-side transaction saved
        1 * txRepo.save({ it.type == 'PURCHASE' })
        0 * txRepo.save({ it.type == 'SALE' })
    }

    // ── AUCTION guard (bug #29) ───────────────────────────────────

    def "buy refuses to bypass an AUCTION via the BUY_NOW path"() {
        given:
        def buyer = new Wallet(id: 1L, balance: new BigDecimal("500.00"))
        def item = new Item(id: 10L, name: "Rare Helmet")
        def listing = new Listing(
            id: 5L, item: item, price: new BigDecimal("50.00"),
            status: 'ACTIVE', sellerName: 'Bot',
            listingType: 'AUCTION',
            currentBid: new BigDecimal("40.00"),
            currentBidderId: 77L
        )

        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)

        when:
        service.buy(1L, 999L, 5L)

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'NOT_BUY_NOW'
        // Critical side effects must NOT have happened — no debit, no
        // listing mutation, no transaction log, no notification.
        listing.status == 'ACTIVE'
        buyer.balance == new BigDecimal("500.00")
        0 * walletRepo.save(_)
        0 * listingRepo.save(_)
        0 * txRepo.save(_)
    }
}
