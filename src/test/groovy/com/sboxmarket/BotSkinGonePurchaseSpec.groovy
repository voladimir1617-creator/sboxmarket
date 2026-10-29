package com.sboxmarket

import com.sboxmarket.exception.ApiException
import com.sboxmarket.exception.ListingNotAvailableException
import com.sboxmarket.model.EscrowedItem
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Offer
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.EscrowedItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.OfferService
import com.sboxmarket.service.PurchaseService
import com.sboxmarket.service.SteamBotReconcileService
import com.sboxmarket.service.SteamEscrowService
import com.sboxmarket.service.SteamTradeBotService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.BanGuard
import groovy.json.JsonOutput
import spock.lang.Specification

/**
 * A bot-held listing whose skin has already left the bot must be refused at
 * purchase time, before any money moves, with the same LISTING_NOT_AVAILABLE
 * answer a sold listing gets. The bot is asked once, with a short timeout;
 * a bot that does not answer lets the sale through as before.
 */
class BotSkinGonePurchaseSpec extends Specification {

    ListingRepository listingRepo = Mock()
    WalletRepository walletRepo = Mock()
    TransactionRepository txRepo = Mock()
    EscrowedItemRepository escrowRepo = Mock()
    BanGuard banGuard = Mock()

    /** Bot answers: a list of asset ids it holds, or null for "unreachable". */
    List<String> botHolds = []
    List<Long> timeoutsSeen = []

    SteamTradeBotService bot = new SteamTradeBotService() {
        { baseUrl = 'http://127.0.0.1:4000'; apiToken = 't' }
        @Override
        protected SteamTradeBotService.RawResponse doRequest(String method, String url, String body, long timeoutMs) {
            timeoutsSeen << timeoutMs
            if (botHolds == null) throw new IOException('Connection refused')
            new SteamTradeBotService.RawResponse(status: 200, body: JsonOutput.toJson(
                [ok: true, items: botHolds.collect { [assetId: it, marketHashName: 'Wizard Hat', tradable: true] }]))
        }
    }
    SteamEscrowService escrow = new SteamEscrowService(steamTradeBotService: bot, escrowRepository: escrowRepo,
            listingRepository: listingRepo)
    SteamBotReconcileService reconcile = new SteamBotReconcileService(escrowService: escrow,
            escrowRepository: escrowRepo, listingRepository: listingRepo,
            pullExecutor: { Runnable r -> r.run() } as java.util.concurrent.Executor)

    PurchaseService purchase = new PurchaseService(listingRepository: listingRepo, walletRepository: walletRepo,
            transactionRepository: txRepo, banGuard: banGuard, steamBotReconcileService: reconcile)

    Wallet buyer
    Listing listing
    EscrowedItem custody

    def setup() {
        buyer = new Wallet(id: 1L, username: 'steam_111', balance: new BigDecimal('100.00'))
        listing = new Listing(id: 5L, item: new Item(id: 10L, name: 'Wizard Hat'), price: new BigDecimal('50.00'),
                status: 'ACTIVE', sellerName: 'House')
        custody = new EscrowedItem(id: 77L, listingId: 5L, assetId: '1000000001', heldAssetId: '1000000009',
                custodyState: EscrowedItem.IN_CUSTODY, marketHashName: 'Wizard Hat')
        walletRepo.findById(1L) >> Optional.of(buyer)
        listingRepo.findById(5L) >> Optional.of(listing)
        escrowRepo.findByListingId(5L) >> custody
        escrowRepo.findById(77L) >> Optional.of(custody)
    }

    def "skin gone from the bot: refused with LISTING_NOT_AVAILABLE, no money moves, listing taken off sale"() {
        given:
        botHolds = ['555']

        when:
        purchase.buy(1L, 999L, 5L)

        then:
        def e = thrown(ListingNotAvailableException)
        (e as ApiException).code == 'LISTING_NOT_AVAILABLE'
        e.message == 'Listing 5 is no longer available'
        0 * walletRepo.save(_)
        0 * txRepo.save(_)
        0 * listingRepo.saveAndFlush(_)
        buyer.balance == new BigDecimal('100.00')
        // Same state the hourly bot check leaves, so its next run puts the
        // listing back on sale if the skin turns up again.
        listing.status == 'PENDING_ESCROW'
        custody.missingSince != null
        custody.custodyState == EscrowedItem.IN_CUSTODY
        timeoutsSeen == [3000L]
    }

    def "skin still in the bot: the purchase goes through"() {
        given:
        botHolds = ['1000000009']

        when:
        def r = purchase.buy(1L, 999L, 5L)

        then:
        r.newBalance == new BigDecimal('50.00')
        listing.status == 'SOLD'
        1 * walletRepo.save(_)
        1 * txRepo.save(_)
        custody.missingSince == null
    }

    def "bot unreachable: the purchase goes through as before"() {
        given:
        botHolds = null

        when:
        def r = purchase.buy(1L, 999L, 5L)

        then:
        r.newBalance == new BigDecimal('50.00')
        listing.status == 'SOLD'
        1 * txRepo.save(_)
        custody.missingSince == null
    }

    def "a listing with no custody row (P2P / house) never asks the bot"() {
        given:
        escrowRepo.findByListingId(5L) >> null
        def p = new PurchaseService(listingRepository: listingRepo, walletRepository: walletRepo,
                transactionRepository: txRepo, banGuard: banGuard,
                steamBotReconcileService: new SteamBotReconcileService(escrowService: escrow,
                        escrowRepository: Mock(EscrowedItemRepository), listingRepository: listingRepo))

        when:
        p.buy(1L, 999L, 5L)

        then:
        listing.status == 'SOLD'
        timeoutsSeen.isEmpty()
    }

    def "offer accept: skin gone is refused before the listing price is touched, and the bot is asked once"() {
        given:
        botHolds = ['555']
        listing.sellerUserId = 99L
        def offer = new Offer(id: 1L, listingId: 5L, buyerUserId: 10L, sellerUserId: 99L, author: 'USER',
                amount: new BigDecimal('40.00'), status: 'PENDING', itemName: 'Wizard Hat')
        OfferRepository offerRepo = Mock() { findById(1L) >> Optional.of(offer) }
        SteamUserRepository users = Mock() {
            findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '7656117', displayName: 'Alice'))
        }
        walletRepo.findByUsername('steam_7656117') >> buyer
        PurchaseService mockedPurchase = Mock()
        def offers = new OfferService(offerRepository: offerRepo, listingRepository: listingRepo,
                walletRepository: walletRepo, steamUserRepository: users, purchaseService: mockedPurchase,
                banGuard: banGuard, textSanitizer: Mock(TextSanitizer), notificationService: Mock(NotificationService),
                steamBotReconcileService: reconcile)

        when:
        offers.acceptOffer(99L, 1L)

        then:
        thrown(ListingNotAvailableException)
        0 * mockedPurchase.buy(*_)
        listing.price == new BigDecimal('50.00')
        offer.status == 'PENDING'
        timeoutsSeen.size() == 1
    }

    def "offer accept with the skin present tells buy() the bot was already asked"() {
        given:
        botHolds = ['1000000009']
        listing.sellerUserId = 99L
        def offer = new Offer(id: 1L, listingId: 5L, buyerUserId: 10L, sellerUserId: 99L, author: 'USER',
                amount: new BigDecimal('40.00'), status: 'PENDING', itemName: 'Wizard Hat')
        OfferRepository offerRepo = Mock() { findById(1L) >> Optional.of(offer) }
        SteamUserRepository users = Mock() {
            findById(10L) >> Optional.of(new SteamUser(id: 10L, steamId64: '7656117', displayName: 'Alice'))
        }
        walletRepo.findByUsername('steam_7656117') >> buyer
        PurchaseService mockedPurchase = Mock()
        def offers = new OfferService(offerRepository: offerRepo, listingRepository: listingRepo,
                walletRepository: walletRepo, steamUserRepository: users, purchaseService: mockedPurchase,
                banGuard: banGuard, textSanitizer: Mock(TextSanitizer), notificationService: Mock(NotificationService),
                steamBotReconcileService: reconcile)

        when:
        offers.acceptOffer(99L, 1L)

        then:
        1 * mockedPurchase.buy(1L, 10L, 5L, null, true)
        offer.status == 'ACCEPTED'
    }
}
