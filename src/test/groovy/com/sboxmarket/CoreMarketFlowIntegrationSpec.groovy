package com.sboxmarket

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.dto.request.WithdrawRequest
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.WalletRepository
import groovy.json.JsonSlurper
import jakarta.validation.Validation
import jakarta.validation.Validator
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Shared
import spock.lang.Specification

/**
 * The operator's core loop, end to end over HTTP, with the request bodies the
 * SPA actually sends: seller lists an owned item, buyer finds and buys it, the
 * seller accepts and marks the Steam offer sent, the buyer confirms receipt,
 * the seller's wallet is credited price minus the 2% fee, and the seller asks
 * to withdraw.
 *
 * Written after a headless walk of that loop (2026-09-26) found the LAST step
 * broken for every ordinary user: the Wallet form always posts the 2FA input's
 * value, "" for anyone without 2FA, and WithdrawRequest's `^[0-9]{6}$`
 * rejected it -- 400 VALIDATION_FAILED "Request body failed validation" before
 * the controller ran. Every earlier step worked; the money just could not
 * leave. Unit specs call the controller directly and skip bean validation,
 * which is how it stayed invisible.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CoreMarketFlowIntegrationSpec extends Specification {

    @Autowired ApplicationContext ctx
    @Shared Validator validator = Validation.buildDefaultValidatorFactory().validator

    MockMvc mockMvc
    SteamUserRepository users
    WalletRepository wallets
    ItemRepository items
    ListingRepository listings
    TradeRepository trades

    SteamUser seller, buyer
    Wallet sellerWallet, buyerWallet
    Item item
    Listing owned
    MockHttpSession sellerSession, buyerSession

    def setup() {
        mockMvc  = ctx.getBean(MockMvc)
        users    = ctx.getBean(SteamUserRepository)
        wallets  = ctx.getBean(WalletRepository)
        items    = ctx.getBean(ItemRepository)
        listings = ctx.getBean(ListingRepository)
        trades   = ctx.getBean(TradeRepository)

        def uniq = String.valueOf(System.nanoTime())
        def sellerSteam = "76561198" + uniq.takeRight(9)
        def buyerSteam  = "76561197" + uniq.takeRight(9)
        seller = users.save(new SteamUser(steamId64: sellerSteam, displayName: "FlowSeller-" + uniq,
            email: "seller-${uniq}@example.test", emailVerified: true))
        buyer  = users.save(new SteamUser(steamId64: buyerSteam, displayName: "FlowBuyer-" + uniq,
            tradeUrl: "https://steamcommunity.com/tradeoffer/new/?partner=123456&token=AbCd1234"))
        sellerWallet = wallets.save(new Wallet(username: "steam_" + sellerSteam, balance: new BigDecimal("0.00")))
        buyerWallet  = wallets.save(new Wallet(username: "steam_" + buyerSteam,  balance: new BigDecimal("100.00")))

        item = items.save(new Item(name: "Flow Crop Top " + uniq, category: "Shirts", rarity: "Standard",
            imageUrl: "https://example.com/i.png", accentColor: "#000000", supply: 1, totalSold: 0,
            trendPercent: 0, lowestPrice: new BigDecimal("1.00")))
        // An item the seller owns in Platform Inventory: a SOLD row whose buyer is the seller.
        owned = listings.save(new Listing(item: item, price: new BigDecimal("1.00"), sellerName: "House",
            sellerAvatar: "HO", status: "SOLD", buyerUserId: seller.id, rarityScore: BigDecimal.ZERO))

        sellerSession = new MockHttpSession()
        sellerSession.setAttribute(SteamAuthController.SESSION_USER_ID, seller.id)
        buyerSession = new MockHttpSession()
        buyerSession.setAttribute(SteamAuthController.SESSION_USER_ID, buyer.id)
    }

    private Map post(String path, MockHttpSession s, String json = null) {
        def rb = MockMvcRequestBuilders.post(path).session(s)
        if (json != null) rb = rb.contentType("application/json").content(json)
        def r = mockMvc.perform(rb).andReturn()
        [status: r.response.status, body: r.response.contentAsString ? new JsonSlurper().parseText(r.response.contentAsString) : null]
    }

    private Map get(String path, Map params = [:]) {
        def rb = MockMvcRequestBuilders.get(path)
        params.each { k, v -> rb = rb.param(k as String, v as String) }
        def r = mockMvc.perform(rb).andReturn()
        [status: r.response.status, body: new JsonSlurper().parseText(r.response.contentAsString)]
    }

    def "list -> search -> buy -> accept -> sent -> confirm credits the seller 98% of the price"() {
        when: "the seller lists the owned item at \$2.00"
        def listed = post("/api/listings/sell", sellerSession,
            """{"listingId":${owned.id},"price":2.00,"listingType":"BUY_NOW"}""")

        then:
        listed.status == 200
        listed.body.status == 'ACTIVE'
        def listingId = listed.body.listingId as Long

        when: "the buyer searches the market for it"
        def found = get("/api/listings", [search: item.name, limit: '50'])
        def rows = (found.body instanceof Map ? found.body.items : found.body) as List

        then:
        found.status == 200
        rows.any { (it.id as Long) == listingId && it.sellerUserId == seller.id }

        when: "the buyer buys it with the price the modal showed"
        def bought = post("/api/listings/${listingId}/buy", buyerSession, '{"expectedPrice":"2.00"}')

        then: "the buyer is debited and an escrow trade opens"
        bought.status == 200
        bought.body.tradeOpened == true
        wallets.findById(buyerWallet.id).get().balance == new BigDecimal("98.00")
        def trade = trades.findAll().find { it.listingId == listingId }
        trade != null
        trade.state == 'PENDING_SELLER_ACCEPT'

        when: "the seller accepts and marks the Steam offer sent"
        def accepted = post("/api/trades/${trade.id}/accept", sellerSession)
        def sent = post("/api/trades/${trade.id}/sent", sellerSession,
            '{"tradeOfferUrl":"https://steamcommunity.com/tradeoffer/7654321/"}')

        then:
        accepted.status == 200
        sent.status == 200
        sent.body.state == 'PENDING_BUYER_CONFIRM'

        and: "nothing is released to the seller before the buyer confirms"
        wallets.findById(sellerWallet.id).get().balance == new BigDecimal("0.00")

        when: "the buyer confirms receipt"
        def confirmed = post("/api/trades/${trade.id}/confirm", buyerSession)

        then: "escrow releases price minus the 2% platform fee to the seller"
        confirmed.status == 200
        confirmed.body.state == 'VERIFIED'
        wallets.findById(sellerWallet.id).get().balance == new BigDecimal("1.96")
    }

    def "the withdraw body the Wallet form sends for a non-2FA seller passes bean validation"() {
        // The exact shape: amount, no destination field any more, and the 2FA
        // input omitted (current client) or empty (any client that sends it).
        when:
        def omitted = post("/api/wallet/withdraw", sellerSession, '{"amount":1.00}')
        def empty   = post("/api/wallet/withdraw", sellerSession, '{"amount":1.00,"totpCode":""}')

        then: "whatever gate answers, it is not the DTO refusing the blank 2FA code"
        omitted.body?.code != 'VALIDATION_FAILED'
        empty.body?.code != 'VALIDATION_FAILED'
    }

    def "WithdrawRequest: blank 2FA code means absent; a present one must still be six digits"() {
        expect:
        validator.validate(new WithdrawRequest(amount: 1.00G, totpCode: code)).isEmpty() == ok

        where:
        code       | ok
        null       | true
        ''         | true
        '123456'   | true
        '12345'    | false
        '1234567'  | false
        'abcdef'   | false
    }
}
