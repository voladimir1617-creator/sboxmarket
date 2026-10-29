package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamDeliveryAttempt
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Trade
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamDeliveryAttemptRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.BotDeliveryOfferGuard
import com.sboxmarket.service.SteamBotResult
import com.sboxmarket.service.SteamTradeBotService
import com.sboxmarket.service.TradeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * A refund must not leave the bot's Steam delivery offer open for the buyer.
 *
 * The bot's offers stay acceptable for 14 days and nothing used to cancel one,
 * so staff force-cancel, the ban cascade and a seller's cancel of a
 * PENDING_BUYER_CONFIRM bot trade all refunded the buyer while the offer was
 * live (or already accepted): the buyer kept the money and the item. Now the
 * offer is cancelled in Steam first, and an accepted one refuses the refund.
 *
 * Runs against the real TradeService / AdminService; only the guard's bot
 * client is a mock standing in for the sidecar.
 */
@SpringBootTest
@ActiveProfiles("test")
class BotOfferCancelBeforeRefundSpec extends Specification {

    @Autowired TradeService tradeService
    @Autowired AdminService adminService
    @Autowired BotDeliveryOfferGuard guard
    @Autowired SteamUserRepository userRepo
    @Autowired ItemRepository itemRepo
    @Autowired ListingRepository listingRepo
    @Autowired TradeRepository tradeRepo
    @Autowired SteamDeliveryAttemptRepository attemptRepo

    SteamTradeBotService bot = Mock()
    SteamTradeBotService realBot

    static int seq = 0
    String uniq = String.valueOf(System.nanoTime())

    def setup() {
        realBot = guard.steamTradeBotService
        guard.steamTradeBotService = bot
        bot.enabled >> true
    }

    def cleanup() {
        guard.steamTradeBotService = realBot
    }

    private SteamUser user(String role = 'USER') {
        seq++
        def tail = (uniq + seq).reverse().take(9).reverse()
        userRepo.save(new SteamUser(steamId64: '7656122' + tail.padLeft(10, '0'),
            displayName: "OfferCancel-${role}-${seq}-${uniq}", role: role,
            tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc'))
    }

    private Trade botTrade(SteamUser buyer, SteamUser seller, String offerState, String state = 'PENDING_BUYER_CONFIRM') {
        def it = itemRepo.save(new Item(name: "OfferCancel ${uniq} ${++seq}", category: 'Accessories', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal('10.00'), isListed: true, iconEmoji: '🎩'))
        def l = listingRepo.save(new Listing(item: it, price: new BigDecimal('10.00'), listingType: 'BUY_NOW',
            status: 'SOLD', sellerName: 'oc', rarityScore: BigDecimal.ZERO, sellerUserId: seller.id,
            buyerUserId: buyer.id, soldAt: System.currentTimeMillis()))
        def t = tradeRepo.save(new Trade(buyerUserId: buyer.id, sellerUserId: seller.id, itemName: it.name,
            itemId: it.id, listingId: l.id, price: new BigDecimal('10.00'), state: state))
        attemptRepo.save(new SteamDeliveryAttempt(tradeId: t.id, steamOfferId: ("9" + t.id),
            offerState: offerState, phase: 'SEND', success: true, createdAt: System.currentTimeMillis() - 1000L))
        t
    }

    private static SteamBotResult cancelled(String id) {
        SteamBotResult.success([ok: true, offerId: id, status: 'canceled', cancelled: true, alreadyFinal: false])
    }

    private static SteamBotResult accepted(String id, String status = 'accepted') {
        def r = SteamBotResult.error(SteamBotResult.ALREADY_ACCEPTED, 'already accepted',
            [ok: false, error: 'ALREADY_ACCEPTED', offerId: id, status: status, retryable: false])
        r.status = status
        r
    }

    private String latestOfferState(Trade t) {
        attemptRepo.findLatestWithOffer(t.id, PageRequest.of(0, 1))[0].offerState
    }

    def "staff force-cancel cancels the live Steam offer, then refunds"() {
        given:
        def admin = user('ADMIN'); def buyer = user(); def seller = user()
        def t = botTrade(buyer, seller, 'active')

        when:
        adminService.forceCancelTrade(admin.id, t.id, 'buyer says not received')

        then:
        1 * bot.cancelOffer(("9" + t.id)) >> cancelled(("9" + t.id))
        tradeRepo.findById(t.id).get().state == 'CANCELLED'
        latestOfferState(t) == 'canceled'
    }

    def "staff force-cancel is refused when Steam says the buyer already accepted"() {
        given:
        def admin = user('ADMIN'); def buyer = user(); def seller = user()
        def t = botTrade(buyer, seller, 'active')

        when:
        adminService.forceCancelTrade(admin.id, t.id, 'buyer says not received')

        then:
        1 * bot.cancelOffer(("9" + t.id)) >> accepted(("9" + t.id))
        def e = thrown(BadRequestException)
        e.code == 'OFFER_ALREADY_ACCEPTED'

        and: "no refund, the trade stays open, and the accept is on record for the poller"
        tradeRepo.findById(t.id).get().state == 'PENDING_BUYER_CONFIRM'
        latestOfferState(t) == 'accepted'
    }

    def "an accept already in the delivery log refuses the refund without asking Steam"() {
        given:
        def admin = user('ADMIN'); def buyer = user(); def seller = user()
        def t = botTrade(buyer, seller, 'accepted', 'DISPUTED')

        when:
        adminService.forceCancelTrade(admin.id, t.id, 'dispute')

        then:
        0 * bot.cancelOffer(_)
        def e = thrown(BadRequestException)
        e.code == 'OFFER_ALREADY_ACCEPTED'
        tradeRepo.findById(t.id).get().state == 'DISPUTED'
    }

    def "a cancel the bot cannot carry out refuses the refund with a retryable code"() {
        given:
        def admin = user('ADMIN'); def buyer = user(); def seller = user()
        def t = botTrade(buyer, seller, 'in_flight_unknown', 'DISPUTED')

        when:
        adminService.forceCancelTrade(admin.id, t.id, 'dispute')

        then:
        1 * bot.cancelOffer(("9" + t.id)) >> SteamBotResult.error('TRANSPORT_ERROR', 'connection refused')
        def e = thrown(BadRequestException)
        e.code == 'OFFER_CANCEL_FAILED'
        tradeRepo.findById(t.id).get().state == 'DISPUTED'
    }

    def "a seller can no longer cancel a PENDING_BUYER_CONFIRM bot trade out from under a live offer"() {
        given:
        def buyer = user(); def seller = user()
        def t = botTrade(buyer, seller, 'active')

        when: "Steam reports the buyer took it"
        tradeService.cancel(seller.id, t.id, 'changed my mind')

        then:
        1 * bot.cancelOffer(("9" + t.id)) >> accepted(("9" + t.id))
        thrown(BadRequestException)
        tradeRepo.findById(t.id).get().state == 'PENDING_BUYER_CONFIRM'
    }

    def "a seller cancel with the offer cancelled in Steam goes through"() {
        given:
        def buyer = user(); def seller = user()
        def t = botTrade(buyer, seller, 'active')

        when:
        tradeService.cancel(seller.id, t.id, 'out of stock')

        then:
        1 * bot.cancelOffer(("9" + t.id)) >> cancelled(("9" + t.id))
        tradeRepo.findById(t.id).get().state == 'CANCELLED'
    }

    def "a declined offer needs no cancel: the refund runs as before"() {
        given:
        def admin = user('ADMIN'); def buyer = user(); def seller = user()
        def t = botTrade(buyer, seller, 'declined')

        when:
        adminService.forceCancelTrade(admin.id, t.id, 'offer declined')

        then:
        0 * bot.cancelOffer(_)
        tradeRepo.findById(t.id).get().state == 'CANCELLED'
    }

    def "ban cascade: a trade whose offer was accepted is left open for staff, and the ban still lands"() {
        given:
        def admin = user('ADMIN'); def buyer = user(); def seller = user()
        def taken = botTrade(buyer, seller, 'active')
        def stillLive = botTrade(buyer, seller, 'active')

        when:
        adminService.banUser(admin.id, buyer.id, 'fraud')

        then:
        1 * bot.cancelOffer(("9" + taken.id)) >> accepted(("9" + taken.id))
        1 * bot.cancelOffer(("9" + stillLive.id)) >> cancelled(("9" + stillLive.id))
        userRepo.findById(buyer.id).get().banned
        tradeRepo.findById(taken.id).get().state == 'PENDING_BUYER_CONFIRM'
        tradeRepo.findById(stillLive.id).get().state == 'CANCELLED'
    }

    def "banned-seller sweep: no refund while the buyer took the item; parked for staff instead"() {
        given:
        def buyer = user(); def seller = user()
        def t = botTrade(buyer, seller, 'active')

        when:
        tradeService.autoCancelBannedSellerTrade(tradeRepo.findById(t.id).get())

        then:
        1 * bot.cancelOffer(("9" + t.id)) >> answer
        tradeRepo.findById(t.id).get().state == state

        where:
        answer                                                 || state
        accepted('987')                                        || 'DISPUTED'
        SteamBotResult.error('TRANSPORT_ERROR', 'down')        || 'DISPUTED'
    }

    def "banned-seller sweep: an offer cancelled in Steam lets the refund run"() {
        given:
        def buyer = user(); def seller = user()
        def t = botTrade(buyer, seller, 'active')

        when:
        tradeService.autoCancelBannedSellerTrade(tradeRepo.findById(t.id).get())

        then:
        1 * bot.cancelOffer(("9" + t.id)) >> cancelled(("9" + t.id))
        tradeRepo.findById(t.id).get().state == 'CANCELLED'
    }

    def "force-release is refused while the buyer never accepted the bot's offer"() {
        given:
        def admin = user('ADMIN'); def buyer = user(); def seller = user()
        def t = botTrade(buyer, seller, 'expired', 'DISPUTED')

        when:
        adminService.forceReleaseTrade(admin.id, t.id, 'pay the seller')

        then:
        1 * bot.getOfferStatus(("9" + t.id)) >> SteamBotResult.success([ok: true, offerId: ("9" + t.id), status: 'expired'])
        def e = thrown(BadRequestException)
        e.code == 'BOT_OFFER_NOT_ACCEPTED'
        tradeRepo.findById(t.id).get().state == 'DISPUTED'
    }
}
