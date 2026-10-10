package com.sboxmarket

import com.sboxmarket.controller.ListingController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamDeliveryAttempt
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Trade
import com.sboxmarket.repository.ApiKeyRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamDeliveryAttemptRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.ApiKeyService
import com.sboxmarket.service.TradeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Defect pass 11: trade escrow with a bot offer out, staff sign-outs and
 * API keys, and the grid's Verified badge.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefectPassElevenSpec extends Specification {

    @Autowired TradeService tradeService
    @Autowired AdminService adminService
    @Autowired ApiKeyService apiKeyService
    @Autowired ListingController listingController
    @Autowired SteamUserRepository userRepo
    @Autowired ItemRepository itemRepo
    @Autowired ListingRepository listingRepo
    @Autowired TradeRepository tradeRepo
    @Autowired SteamDeliveryAttemptRepository attemptRepo
    @Autowired ApiKeyRepository apiKeyRepo

    static int seq = 0
    String uniq = String.valueOf(System.nanoTime())

    private SteamUser user(String role = 'USER', Map extra = [:]) {
        seq++
        def tail = (uniq + seq).reverse().take(9).reverse()
        userRepo.save(new SteamUser([steamId64: '7656121' + tail.padLeft(10, '0'),
            displayName: "Pass11-${role}-${seq}-${uniq}", role: role,
            tradeUrl: 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc'] + extra))
    }

    private Item item() {
        itemRepo.save(new Item(name: "Pass11 ${uniq} ${++seq}", category: 'Accessories', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal('10.00'), isListed: true, iconEmoji: '🎩'))
    }

    private Trade botTrade(SteamUser buyer, SteamUser seller, String offerState) {
        def it = item()
        def l = listingRepo.save(new Listing(item: it, price: new BigDecimal('10.00'), listingType: 'BUY_NOW',
            status: 'SOLD', sellerName: 'p11', rarityScore: BigDecimal.ZERO, sellerUserId: seller.id,
            buyerUserId: buyer.id, soldAt: System.currentTimeMillis()))
        def t = tradeRepo.save(new Trade(buyerUserId: buyer.id, sellerUserId: seller.id, itemName: it.name,
            itemId: it.id, listingId: l.id, price: new BigDecimal('10.00'), state: 'PENDING_SELLER_SEND'))
        attemptRepo.save(new SteamDeliveryAttempt(tradeId: t.id, steamOfferId: "off-${t.id}",
            offerState: offerState, phase: 'SEND', success: true))
        t
    }

    def "a buyer cannot cancel once the bot's Steam offer is out"() {
        given:
        def buyer = user(); def seller = user()
        def t = botTrade(buyer, seller, 'active')

        when:
        tradeService.cancel(buyer.id, t.id, 'changed my mind')

        then:
        def e = thrown(BadRequestException)
        e.code == 'OFFER_IN_FLIGHT'
        tradeRepo.findById(t.id).get().state == 'PENDING_SELLER_SEND'
    }

    def "a declined bot offer still lets the buyer cancel"() {
        given:
        def buyer = user(); def seller = user()
        def t = botTrade(buyer, seller, 'declined')

        when:
        tradeService.cancel(buyer.id, t.id, 'never arrived')

        then:
        tradeRepo.findById(t.id).get().state == 'CANCELLED'
    }

    def "the stale-seller sweep holds a trade whose bot offer is out instead of refunding"() {
        given:
        def buyer = user(); def seller = user()
        def t = botTrade(buyer, seller, 'active')

        when:
        tradeService.autoCancelStaleSellerTrade(tradeRepo.findById(t.id).get())

        then:
        tradeRepo.findById(t.id).get().state == 'DISPUTED'
    }

    def "staff sign-out revokes the user's API keys too"() {
        given:
        def admin = user('ADMIN'); def target = user()
        apiKeyService.create(target.id, 'bot')

        when:
        adminService.forceLogout(admin.id, target.id)

        then:
        def keys = apiKeyRepo.findByUser(target.id)
        !keys.isEmpty()
        keys.every { it.revoked }
    }

    def "a staff 2FA reset revokes the user's API keys"() {
        given:
        def admin = user('ADMIN'); def target = user('USER', [totpSecret: 'JBSWY3DPEHPK3PXP'])
        apiKeyService.create(target.id, 'bot')

        when:
        adminService.reset2faFor(admin.id, target.id, 'lost phone')

        then:
        apiKeyRepo.findByUser(target.id).every { it.revoked }
    }

    def "the grid card's Verified badge follows the stall rule (10 sales, no reviews yet)"() {
        given:
        def seller = user(); def buyer = user()
        10.times {
            listingRepo.save(new Listing(item: item(), price: new BigDecimal('1.00'), listingType: 'BUY_NOW',
                status: 'SOLD', sellerName: 'p11', rarityScore: BigDecimal.ZERO, sellerUserId: seller.id,
                buyerUserId: buyer.id, soldAt: System.currentTimeMillis()))
        }
        def shown = listingRepo.save(new Listing(item: item(), price: new BigDecimal('2.00'), listingType: 'BUY_NOW',
            status: 'ACTIVE', sellerName: 'p11', rarityScore: BigDecimal.ZERO, sellerUserId: seller.id))

        when:
        def rows = listingController.otherFromSeller(seller.id, -1L, 8).body

        then:
        rows*.id.contains(shown.id)
        rows.every { it.sellerVerified == true }
    }
}
