package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.BidRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.NotificationRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.service.BuyOrderService
import com.sboxmarket.service.ListingService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.SellService
import com.sboxmarket.service.SellerMarketPingService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.TradeService
import com.sboxmarket.service.security.BanGuard
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.Specification

/**
 * Pass 95, Q2: the LISTING_UNDERCUT ping ("A cheaper {item} just went on
 * sale") used to fire synchronously, before the deferred buy-order match.
 * When a standing buy order filled the new listing, the sellers above it
 * were told about a cheaper copy that was already sold. The ping now runs
 * after tryMatch and reads the listing back, so a filled listing pings nobody.
 *
 * Uses a real SellerMarketPingService over a fake "DB" row whose status the
 * mocked tryMatch flips to SOLD (as a committed buy() would).
 */
class UndercutPingAfterMatchSpec extends Specification {

    ListingRepository      listingRepository      = Mock()
    ItemRepository         itemRepository         = Mock()
    BuyOrderService        buyOrderService        = Mock()
    NotificationService    notificationService    = Mock()
    NotificationRepository notificationRepository = Mock()
    SteamUserRepository    steamUserRepository    = Mock()
    TextSanitizer          textSanitizer          = Mock() {
        cleanShort(_) >> { String s -> s }
        clean(_, _) >> { args -> args[0] as String }
    }

    Item item = new Item(id: 1L, name: 'Wizard Hat', category: 'Hats', rarity: 'Limited', supply: 100,
                         lowestPrice: new BigDecimal('10'))

    /** The row as the database holds it after any committed fill. */
    Map<Long, String> dbStatus = [:]
    /** Rows returned as-is by findById (the owned listing a relist reads). */
    Map<Long, Listing> fixedRows = [:]

    SellerMarketPingService ping = new SellerMarketPingService(
        listingRepository:      listingRepository,
        notificationService:    notificationService,
        notificationRepository: notificationRepository,
        steamUserRepository:    steamUserRepository
    )

    /** Another seller's listing priced above the new one: the ping target. */
    Listing pricier = new Listing(id: 7L, item: item, sellerUserId: 77L, price: new BigDecimal('99'),
                                  status: 'ACTIVE', hidden: false, listingType: 'BUY_NOW')

    def setup() {
        steamUserRepository.findAllById(_) >> { args -> args[0].collect { new SteamUser(id: it, banned: false) } }
        notificationRepository.countRecentByKindAndRef(*_) >> 0L
        listingRepository.findActiveForItemIds([1L]) >> { [pricier] }
        listingRepository.findById(_) >> { args ->
            Long id = args[0] as Long
            if (fixedRows.containsKey(id)) return Optional.of(fixedRows[id])
            if (!dbStatus.containsKey(id)) return Optional.empty()
            Optional.of(new Listing(id: id, item: item, sellerUserId: 10L, price: new BigDecimal('80'),
                                    status: dbStatus[id], hidden: false, listingType: 'BUY_NOW'))
        }
    }

    def cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    // ── SellService.relist ───────────────────────────────────────

    private SellService sellService(PlatformTransactionManager txManager = null) {
        new SellService(
            listingRepository:       listingRepository,
            itemRepository:          itemRepository,
            tradeRepository:         Mock(TradeRepository),
            bidRepository:           Mock(BidRepository),
            offerRepository:         Mock(OfferRepository),
            buyOrderService:         buyOrderService,
            tradeService:            Mock(TradeService),
            notificationService:     notificationService,
            banGuard:                Mock(BanGuard),
            textSanitizer:           textSanitizer,
            sellerMarketPingService: ping,
            transactionManager:      txManager
        )
    }

    private void primeRelist() {
        def owned = new Listing(id: 50L, item: item, price: new BigDecimal('40'), buyerUserId: 10L,
                                sellerUserId: 99L, status: 'SOLD', rarityScore: new BigDecimal('0.5'))
        fixedRows[50L] = owned
        listingRepository.save(_) >> { args ->
            Listing l = args[0]
            if (l.id == null) l.id = 100L
            if (l.id == 100L) dbStatus[100L] = l.status
            l
        }
    }

    def "relist: no undercut ping goes out when the buy-order match fills the new listing"() {
        given:
        primeRelist()
        buyOrderService.tryMatch(_) >> { args -> dbStatus[(args[0] as Listing).id] = 'SOLD' }

        when:
        sellService().relist(10L, 'Alice', 50L, new BigDecimal('80'))

        then:
        0 * notificationService.push(77L, 'LISTING_UNDERCUT', *_)
    }

    def "relist: an unmatched listing still pings, and only after tryMatch ran"() {
        given:
        primeRelist()

        when:
        sellService().relist(10L, 'Alice', 50L, new BigDecimal('80'))

        then:
        1 * buyOrderService.tryMatch(_)

        then:
        1 * notificationService.push(77L, 'LISTING_UNDERCUT', *_)
    }

    def "relist inside a transaction: the ping waits for afterCommit and runs after the deferred match"() {
        given:
        primeRelist()
        buyOrderService.tryMatch(_) >> { args -> dbStatus[(args[0] as Listing).id] = 'SOLD' }
        TransactionSynchronizationManager.initSynchronization()

        when: "the relist tx body runs"
        sellService(Mock(PlatformTransactionManager)).relist(10L, 'Alice', 50L, new BigDecimal('80'))

        then: "nothing is pinged before commit"
        0 * notificationService.push(77L, 'LISTING_UNDERCUT', *_)

        when: "the relist commits and the deferred work runs in order"
        TransactionSynchronizationManager.getSynchronizations()*.afterCommit()

        then: "the match filled it first, so the ping stays silent"
        0 * notificationService.push(77L, 'LISTING_UNDERCUT', *_)
    }

    // ── ListingService.createListing ─────────────────────────────

    private ListingService listingService() {
        new ListingService(
            listingRepository:       listingRepository,
            itemRepository:          itemRepository,
            buyOrderService:         buyOrderService,
            sellerMarketPingService: ping
        )
    }

    private Listing newListing() {
        new Listing(id: 100L, item: item, sellerUserId: 10L, price: new BigDecimal('80'),
                    status: 'ACTIVE', hidden: false, listedAt: 1000L, listingType: 'BUY_NOW')
    }

    private void primeCreate() {
        listingRepository.save(_) >> { args -> Listing l = args[0]; dbStatus[l.id] = l.status; l }
        listingRepository.findCheapestForItem(_) >> []
        itemRepository.findById(_) >> Optional.of(item)
        itemRepository.save(_) >> { args -> args[0] }
    }

    def "createListing: no undercut ping goes out when the buy-order match fills the new listing"() {
        given:
        primeCreate()
        buyOrderService.tryMatch(_) >> { args -> dbStatus[(args[0] as Listing).id] = 'SOLD' }

        when:
        listingService().createListing(newListing())

        then:
        0 * notificationService.push(77L, 'LISTING_UNDERCUT', *_)
    }

    def "createListing: an unmatched listing still pings, and only after tryMatch ran"() {
        given:
        primeCreate()

        when:
        listingService().createListing(newListing())

        then:
        1 * buyOrderService.tryMatch(_)

        then:
        1 * notificationService.push(77L, 'LISTING_UNDERCUT', *_)
    }
}
