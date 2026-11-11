package com.sboxmarket

import com.sboxmarket.model.BuyOrder
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.NotificationRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.EmailService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.SellerMarketPingService
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.Specification
import spock.lang.Subject

/**
 * The seller market pings (BUY_ORDER_ON_YOUR_ITEM, LISTING_UNDERCUT) also
 * email, but only when the bell was actually pushed (same 24h dedupe), only
 * through EmailService.canSendTo(seller, 'MATCHES'), and only after the
 * caller's transaction commits.
 */
class SellerMarketPingEmailSpec extends Specification {

    ListingRepository      listingRepository      = Mock()
    NotificationService    notificationService    = Mock()
    NotificationRepository notificationRepository = Mock()
    SteamUserRepository    steamUserRepository    = Mock()
    EmailService           emailService           = Mock()

    @Subject
    SellerMarketPingService service = new SellerMarketPingService(
        listingRepository:      listingRepository,
        notificationService:    notificationService,
        notificationRepository: notificationRepository,
        steamUserRepository:    steamUserRepository,
        emailService:           emailService
    )

    Item item = new Item(id: 7L, name: 'Red Cap')
    Set<Long> pingedToday = [] as Set

    private Listing listing(long id, long seller, String price) {
        new Listing(id: id, item: item, sellerUserId: seller, price: new BigDecimal(price),
                    status: 'ACTIVE', hidden: false, listingType: 'BUY_NOW')
    }

    private BuyOrder order() {
        new BuyOrder(id: 5L, buyerUserId: 10L, itemId: 7L, itemName: 'Red Cap',
            maxPrice: new BigDecimal('10.00'), quantity: 1, status: 'ACTIVE')
    }

    def setup() {
        steamUserRepository.findAllById(_) >> { args ->
            args[0].collect { new SteamUser(id: it, email: "s${it}@x.com", displayName: "Seller${it}") }
        }
        notificationRepository.countRecentByKindAndRef(*_) >> { args -> args[0] in pingedToday ? 1L : 0L }
        listingRepository.findActiveForItemIds([7L]) >> [listing(1, 21, '12.00'), listing(2, 22, '15.00')]
    }

    def cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    def "a buy-order ping emails each pinged seller under the MATCHES bucket"() {
        given:
        emailService.canSendTo(_ as SteamUser, 'MATCHES') >> true

        when:
        service.notifyBuyOrderPlaced(order())

        then:
        2 * notificationService.push(_, 'BUY_ORDER_ON_YOUR_ITEM', *_)
        1 * emailService.sendBuyOrderOnYourItem('s21@x.com', 'Seller21', 'Red Cap', new BigDecimal('10.00'), '/item/7')
        1 * emailService.sendBuyOrderOnYourItem('s22@x.com', 'Seller22', 'Red Cap', new BigDecimal('10.00'), '/item/7')
    }

    def "an undercut ping emails the sellers priced above the new listing"() {
        given:
        emailService.canSendTo(_ as SteamUser, 'MATCHES') >> true
        def cheap = listing(9, 30, '9.50')

        when:
        service.notifyUndercut(cheap)

        then:
        2 * notificationService.push(_, 'LISTING_UNDERCUT', *_)
        1 * emailService.sendListingUndercut('s21@x.com', 'Seller21', 'Red Cap', new BigDecimal('9.50'), '/item/7')
        1 * emailService.sendListingUndercut('s22@x.com', 'Seller22', 'Red Cap', new BigDecimal('9.50'), '/item/7')
    }

    def "a seller who muted the bucket (or has no verified email) still gets the bell but no email"() {
        given:
        emailService.canSendTo({ it.id == 21L }, 'MATCHES') >> false
        emailService.canSendTo({ it.id == 22L }, 'MATCHES') >> true

        when:
        service.notifyBuyOrderPlaced(order())

        then:
        1 * notificationService.push(21L, *_)
        1 * notificationService.push(22L, *_)
        0 * emailService.sendBuyOrderOnYourItem('s21@x.com', *_)
        1 * emailService.sendBuyOrderOnYourItem('s22@x.com', *_)
    }

    def "a seller already pinged today gets neither a bell nor an email"() {
        given:
        pingedToday << 21L
        emailService.canSendTo(_ as SteamUser, 'MATCHES') >> true

        when:
        service.notifyBuyOrderPlaced(order())

        then:
        0 * notificationService.push(21L, *_)
        0 * emailService.sendBuyOrderOnYourItem('s21@x.com', *_)
        1 * emailService.sendBuyOrderOnYourItem('s22@x.com', *_)
    }

    def "a failed bell push sends no email"() {
        given:
        emailService.canSendTo(_ as SteamUser, 'MATCHES') >> true
        notificationService.push(21L, *_) >> { throw new RuntimeException('db down') }

        when:
        service.notifyBuyOrderPlaced(order())

        then:
        0 * emailService.sendBuyOrderOnYourItem('s21@x.com', *_)
        1 * emailService.sendBuyOrderOnYourItem('s22@x.com', *_)
    }

    def "inside a transaction the email waits for the commit"() {
        given:
        emailService.canSendTo(_ as SteamUser, 'MATCHES') >> true
        TransactionSynchronizationManager.initSynchronization()

        when:
        service.notifyBuyOrderPlaced(order())

        then: 'nothing is sent while the caller transaction is open'
        0 * emailService.sendBuyOrderOnYourItem(*_)

        when: 'it commits'
        TransactionSynchronizationManager.synchronizations.each { it.afterCommit() }

        then:
        2 * emailService.sendBuyOrderOnYourItem(*_)
    }

    def "a rolled-back transaction sends no email"() {
        given:
        emailService.canSendTo(_ as SteamUser, 'MATCHES') >> true
        TransactionSynchronizationManager.initSynchronization()

        when:
        service.notifyBuyOrderPlaced(order())
        TransactionSynchronizationManager.synchronizations.each { it.afterCompletion(1) }

        then:
        0 * emailService.sendBuyOrderOnYourItem(*_)
    }
}
