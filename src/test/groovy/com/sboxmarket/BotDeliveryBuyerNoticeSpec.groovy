package com.sboxmarket

import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Trade
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.service.EmailService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.SteamEscrowService
import com.sboxmarket.service.TradeService
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification

/**
 * When the bot sends the delivery offer, SteamDeliveryService calls
 * sellerMarkSent and then tells the buyer "sent by our bot, accept it in
 * Steam". sellerMarkSent ALSO pushed "Confirm the trade on Steam and then
 * click Confirm Receipt" and emailed "Accept the incoming offer from
 * <seller>", so the buyer got two notices for one offer, one of them naming
 * the wrong sender.
 */
class BotDeliveryBuyerNoticeSpec extends Specification {

    TradeRepository tradeRepository = Mock() { save(_) >> { Trade t -> t } }
    NotificationService notificationService = Mock()
    EmailService emailService = Mock()
    SteamEscrowService steamEscrowService = Mock()
    SteamUserRepository steamUserRepository = Mock()

    TradeService service = new TradeService(
        tradeRepository: tradeRepository,
        notificationService: notificationService,
        emailService: emailService,
        steamEscrowService: steamEscrowService,
        steamUserRepository: steamUserRepository,
        banGuard: Mock(BanGuard)
    )

    def "a bot-delivered offer does not also send the seller-sent bell and email"() {
        given:
        def t = new Trade(id: 2L, listingId: 252L, itemName: 'Grand Ball Gown', buyerUserId: 3L, sellerUserId: 2L,
                price: new BigDecimal('230.00'), feeAmount: new BigDecimal('4.60'), state: 'PENDING_SELLER_SEND')
        tradeRepository.findById(2L) >> Optional.of(t)
        steamEscrowService.botDeliveredListingIds(_) >> ([252L] as Set)
        steamUserRepository.findById(_) >> Optional.of(new SteamUser(id: 3L, email: 'b@example.com', displayName: 'PixelPusher'))
        emailService.canSendTo(_, _) >> true

        when:
        service.sellerMarkSent(2L, 2L, 'https://steamcommunity.com/tradeoffer/9000002/')

        then:
        t.state == 'PENDING_BUYER_CONFIRM'
        0 * notificationService.safePush(3L, 'TRADE_SENT', _, { it?.contains('Confirm Receipt') }, _, _)
        0 * emailService.sendTradeSent(*_)
    }

    def "a seller's own Mark Sent still notifies the buyer"() {
        given:
        def t = new Trade(id: 5L, listingId: 300L, itemName: 'Hat', buyerUserId: 3L, sellerUserId: 2L,
                price: new BigDecimal('5.00'), feeAmount: new BigDecimal('0.10'), state: 'PENDING_SELLER_SEND')
        tradeRepository.findById(5L) >> Optional.of(t)
        steamEscrowService.botDeliveredListingIds(_) >> ([] as Set)

        when:
        service.sellerMarkSent(2L, 5L, null)

        then:
        1 * notificationService.safePush(3L, 'TRADE_SENT', _, { it?.contains('Confirm Receipt') }, 5L, _)
    }
}
