package com.sboxmarket

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.SteamInventoryService
import org.spockframework.spring.SpringBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification

/**
 * Full-stack integration spec for the SELL path — the mirror of
 * {@link BuyFlowIntegrationSpec}, which covered only the buy side.
 *
 * ── Why this exists ───────────────────────────────────────────────────────
 * Before this spec, the entire sell side had exactly three assertions, all of
 * them "401 without a session". Nothing had ever driven a listing POST through
 * validation, the servlet filter chain and a real database. The operator's
 * FIRST REAL LISTING would have been the first time that request was executed
 * end to end — with one of his own items in it.
 *
 * The path covered here, in the order he will walk it:
 *   1. GET  /api/steam/inventory  — import, and what he is told when it's empty
 *   2. POST /api/steam/list       — listing creation + price validation
 *   3.                              catalogue auto-create for a new item
 *   4. POST /api/listings/{id}/buy — the sale, and the escrow that holds it
 *   5. POST /api/trades/{id}/...   — accept → sent → confirm
 *   6.                              seller credited, net of the 2% fee
 *
 * The outbound Steam inventory call is the ONLY stub: {@code fetchInventory}
 * would otherwise hit the live Steam community endpoint from the test suite.
 * Everything downstream of it — filters, validation, JPA, the trade state
 * machine, the fee arithmetic — is the real thing against H2.
 *
 * Escrow is OFF in this spec (no bot sidecar configured in the test profile,
 * so {@code isEscrowEnabled()} is false and listings go straight to ACTIVE).
 * The escrow-ON custody path is covered by {@link SellFlowEscrowIntegrationSpec}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SellFlowIntegrationSpec extends Specification {

    /** The one seam. Everything else is real. */
    @SpringBean
    SteamInventoryService steamInventoryService = Stub(SteamInventoryService)

    @Autowired ApplicationContext ctx

    MockMvc               mockMvc
    SteamUserRepository   steamUserRepository
    WalletRepository      walletRepository
    ItemRepository        itemRepository
    ListingRepository     listingRepository
    TransactionRepository transactionRepository
    TradeRepository       tradeRepository

    SteamUser seller
    Wallet    sellerWallet
    SteamUser buyer
    Wallet    buyerWallet

    MockHttpSession sellerSession
    MockHttpSession buyerSession

    String assetId
    String itemName

    /**
     * What the stubbed Steam inventory returns, and why.
     *
     * These are mutable fields rather than per-feature interaction
     * declarations on purpose: Spock only rewrites `a.b() >> c` inside
     * fixture and feature methods, so declaring interactions in a private
     * helper silently turns them into REAL calls on the stub. Stubbing once
     * in setup() with closures that read these fields keeps the response
     * lazy, so a feature can still choose its scenario in its `given:`.
     */
    List<Map> steamInventory = []
    Map       steamOutcome   = null
    /** [total: n, shown: m] when the last fetch was capped, else null. */
    Map       steamTruncation = null

    def setup() {
        mockMvc               = ctx.getBean(MockMvc)
        steamUserRepository   = ctx.getBean(SteamUserRepository)
        walletRepository      = ctx.getBean(WalletRepository)
        itemRepository        = ctx.getBean(ItemRepository)
        listingRepository     = ctx.getBean(ListingRepository)
        transactionRepository = ctx.getBean(TransactionRepository)
        tradeRepository       = ctx.getBean(TradeRepository)

        // Additive isolation — unique ids per run so this spec coexists with
        // every other spec sharing the Spring context.
        def uniq = String.valueOf(System.nanoTime())
        assetId  = uniq.substring(uniq.length() - 12)
        itemName = "Operator Test Cosmetic " + uniq

        def sellerSteamId = "76561199" + uniq.substring(uniq.length() - 9)
        seller = steamUserRepository.save(new SteamUser(
            steamId64:   sellerSteamId,
            displayName: "TestSeller-" + uniq,
            tradeUrl:    "https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc"
        ))
        sellerWallet = walletRepository.save(new Wallet(
            username: "steam_" + sellerSteamId,
            balance:  new BigDecimal("0.00")
        ))

        def buyerSteamId = "76561198" + uniq.substring(uniq.length() - 9)
        // The buyer needs a trade URL too: on a P2P listing the SELLER has to
        // send them the item, so /buy refuses with TRADE_URL_MISSING without
        // one. BuyFlowIntegrationSpec never hit this because its listing is a
        // system listing (sellerUserId == null), which skips the P2P gate.
        buyer = steamUserRepository.save(new SteamUser(
            steamId64:   buyerSteamId,
            displayName: "TestBuyer-" + uniq,
            tradeUrl:    "https://steamcommunity.com/tradeoffer/new/?partner=2&token=xyz"
        ))
        buyerWallet = walletRepository.save(new Wallet(
            username: "steam_" + buyerSteamId,
            balance:  new BigDecimal("500.00")
        ))

        sellerSession = new MockHttpSession()
        sellerSession.setAttribute(SteamAuthController.SESSION_USER_ID, seller.id)
        buyerSession = new MockHttpSession()
        buyerSession.setAttribute(SteamAuthController.SESSION_USER_ID, buyer.id)

        // Declared here (a fixture method) so Spock's AST transform actually
        // treats them as interactions. Closures keep them lazy.
        steamInventoryService.fetchInventory(_ as String)  >> { args -> steamInventory }
        steamInventoryService.blockedUntilMs(_ as String)  >> null
        steamInventoryService.lastOutcomeFor(_ as String)  >> { args -> steamOutcome }
        steamInventoryService.inferCategory(_ as Map)      >> 'Hats'
        steamInventoryService.truncationFor(_ as String)   >> { args -> steamTruncation }
    }

    /** One tradable asset in the seller's Steam inventory. */
    private void inventoryHasTheItem() {
        steamInventory = [
            [assetId: assetId, name: itemName, category: 'Hats', rarity: 'Standard',
             iconUrl: 'https://example.com/hat.png', marketable: true, tradable: true,
             quantity: 1] as Map
        ]
        steamOutcome = [outcome: SteamInventoryService.OUTCOME_OK] as Map
    }

    /** The inventory read came back empty for the given reason. */
    private void inventoryEmptyBecause(String outcome, String detail = null) {
        steamInventory = []
        steamOutcome = [outcome: outcome, detail: detail] as Map
    }

    // ── 1. Inventory import ──────────────────────────────────────────────

    def "GET /api/steam/inventory — 200 and the seller's asset comes back"() {
        given:
        inventoryHasTheItem()

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/steam/inventory").session(sellerSession)
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        body.contains(assetId)
        body.contains('"count":1')
    }

    def "GET /api/steam/inventory — 401 without a session"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/steam/inventory")
        ).andReturn()

        then:
        result.response.status == 401
    }

    /**
     * The operator's most expensive recurring defect, on the first screen of
     * the sell flow: a failure to READ the inventory rendered as the settled
     * fact that he owns nothing. A private profile must not be reported the
     * same way as an empty one.
     */
    def "GET /api/steam/inventory — a private profile is NOT reported as owning nothing"() {
        given:
        inventoryEmptyBecause(SteamInventoryService.OUTCOME_PRIVATE, 'HTTP 403')

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/steam/inventory").session(sellerSession)
        ).andReturn()

        then: "the cause is named, and marked as unreadable rather than empty"
        result.response.status == 200
        def body = result.response.contentAsString
        body.contains('"reason":"private_profile"')
        body.contains('"unreadable":true')

        and: "the message tells him the remedy that actually works"
        body.contains('Privacy Settings')
    }

    def "GET /api/steam/inventory — an unreadable Steam reply is not an empty inventory"() {
        given:
        inventoryEmptyBecause(SteamInventoryService.OUTCOME_MALFORMED, 'no descriptions key')

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/steam/inventory").session(sellerSession)
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        body.contains('"reason":"malformed_response"')
        body.contains('"unreadable":true')
    }

    /**
     * A genuinely empty read is the ONE case that may say "nothing to sell" —
     * and even then it must stay `unreadable:false`, because the app id and
     * context are hardcoded and a wrong one looks exactly like owning nothing.
     */
    def "GET /api/steam/inventory — a genuine empty read is marked readable"() {
        given:
        inventoryEmptyBecause(SteamInventoryService.OUTCOME_EMPTY)

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/steam/inventory").session(sellerSession)
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        body.contains('"reason":"empty_or_wrong_context"')
        body.contains('"unreadable":false')
    }

    /**
     * A large inventory must not arrive quietly short.
     *
     * The Steam fetch is capped at count=500 and does not paginate, so a
     * seller holding more than that gets a truncated list in which the missing
     * items look exactly like items he does not own. Unlike the seven
     * empty-list causes this rides on a NON-empty 200, so none of the
     * reason/unreadable machinery fires for it.
     */
    def "GET /api/steam/inventory — a capped fetch says the list is incomplete"() {
        given:
        inventoryHasTheItem()
        steamTruncation = [total: 1337, shown: 500] as Map

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/steam/inventory").session(sellerSession)
        ).andReturn()

        then:
        result.response.status == 200
        def body = result.response.contentAsString
        body.contains('"truncated":true')
        body.contains('"totalInventoryCount":1337')
        body.contains('"shownCount":500')

        and: "and it says plainly that the gap is not proof of non-ownership"
        body.contains('1337')
        body.contains("NOT")
    }

    def "GET /api/steam/inventory — a complete fetch is not flagged as truncated"() {
        given:
        inventoryHasTheItem()
        steamTruncation = null

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/steam/inventory").session(sellerSession)
        ).andReturn()

        then:
        result.response.status == 200
        !result.response.contentAsString.contains('"truncated"')
    }

    // ── 2. Listing creation ──────────────────────────────────────────────

    def "POST /api/steam/list — 200, listing persisted ACTIVE and owned by the seller"() {
        given:
        inventoryHasTheItem()

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/steam/list")
                .session(sellerSession)
                .contentType("application/json")
                .content("""{"assetId":"${assetId}","price":"25.00"}""")
        ).andReturn()

        then: "HTTP 200 carrying the new listing id"
        result.response.status == 200
        def body = result.response.contentAsString
        body.contains('"listingId"')

        and: "the row is really in the database, ACTIVE and attributed to him"
        def rows = listingRepository.findAll().findAll { it.assetId == assetId }
        rows.size() == 1
        def listing = rows[0]
        listing.status == 'ACTIVE'
        listing.sellerUserId == seller.id
        listing.price == new BigDecimal("25.00")
        listing.listingType == 'BUY_NOW'

        and: "his brand-new cosmetic was auto-created in the catalogue"
        def item = itemRepository.findByNameIgnoreCase(itemName)
        item != null
        listing.item.id == item.id
    }

    def "POST /api/steam/list — 401 without a session"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/steam/list")
                .contentType("application/json")
                .content("""{"assetId":"${assetId}","price":"25.00"}""")
        ).andReturn()

        then:
        result.response.status == 401

        and: "no listing row was created"
        listingRepository.findAll().findAll { it.assetId == assetId }.isEmpty()
    }

    /**
     * A sub-cent price rounds to $0.00 in listings.price NUMERIC(10,2) — a
     * free, instantly-buyable listing of a real item. Pins the floor.
     */
    def "POST /api/steam/list — 400 on a sub-cent price, and no free listing is created"() {
        given:
        inventoryHasTheItem()

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/steam/list")
                .session(sellerSession)
                .contentType("application/json")
                .content("""{"assetId":"${assetId}","price":"0.004"}""")
        ).andReturn()

        then:
        result.response.status == 400
        result.response.contentAsString.contains('"code":"INVALID_PRICE"')

        and:
        listingRepository.findAll().findAll { it.assetId == assetId }.isEmpty()
    }

    def "POST /api/steam/list — 400 above the \$100,000 ceiling"() {
        given:
        inventoryHasTheItem()

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/steam/list")
                .session(sellerSession)
                .contentType("application/json")
                .content("""{"assetId":"${assetId}","price":"100001"}""")
        ).andReturn()

        then:
        result.response.status == 400
        result.response.contentAsString.contains('"code":"PRICE_TOO_HIGH"')

        and:
        listingRepository.findAll().findAll { it.assetId == assetId }.isEmpty()
    }

    /** You cannot list what you do not own — the ownership re-check. */
    def "POST /api/steam/list — 400 NOT_IN_INVENTORY for an asset he does not hold"() {
        given:
        inventoryHasTheItem()

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/steam/list")
                .session(sellerSession)
                .contentType("application/json")
                .content("""{"assetId":"999999999999","price":"25.00"}""")
        ).andReturn()

        then:
        result.response.status == 400
        result.response.contentAsString.contains('"code":"NOT_IN_INVENTORY"')
    }

    def "POST /api/steam/list — 400 NOT_TRADABLE for a locked item"() {
        given:
        steamInventory = [
            [assetId: assetId, name: itemName, category: 'Hats', rarity: 'Standard',
             iconUrl: 'https://example.com/hat.png', marketable: true, tradable: false,
             quantity: 1] as Map
        ]
        steamOutcome = [outcome: SteamInventoryService.OUTCOME_OK] as Map

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/steam/list")
                .session(sellerSession)
                .contentType("application/json")
                .content("""{"assetId":"${assetId}","price":"25.00"}""")
        ).andReturn()

        then:
        result.response.status == 400
        result.response.contentAsString.contains('"code":"NOT_TRADABLE"')

        and:
        listingRepository.findAll().findAll { it.assetId == assetId }.isEmpty()
    }

    /**
     * Double-list → double-sell. One physical asset must never carry two live
     * listings: both could sell and pay him twice for one undeliverable copy.
     */
    def "POST /api/steam/list — 400 ALREADY_LISTED on a second listing of the same asset"() {
        given:
        inventoryHasTheItem()
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/steam/list")
                .session(sellerSession)
                .contentType("application/json")
                .content("""{"assetId":"${assetId}","price":"25.00"}""")
        ).andReturn()

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/steam/list")
                .session(sellerSession)
                .contentType("application/json")
                .content("""{"assetId":"${assetId}","price":"30.00"}""")
        ).andReturn()

        then:
        result.response.status == 400
        result.response.contentAsString.contains('"code":"ALREADY_LISTED"')

        and: "still exactly one listing for that asset"
        listingRepository.findAll().findAll { it.assetId == assetId }.size() == 1
    }

    // ── 3. The sale, the escrow, and getting paid ────────────────────────

    /**
     * The whole reason he is doing this: list → someone buys → he gets the
     * money. Walks the complete lifecycle and asserts the arithmetic at the
     * end, including the 2% fee.
     */
    def "sell → sale → escrow → confirm → the seller is actually paid, net of fee"() {
        given: "he lists his item at \$100"
        inventoryHasTheItem()
        def listResult = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/steam/list")
                .session(sellerSession)
                .contentType("application/json")
                .content("""{"assetId":"${assetId}","price":"100.00"}""")
        ).andReturn()
        listResult.response.status == 200
        def listing = listingRepository.findAll().find { it.assetId == assetId }

        when: "a buyer buys it"
        def buyResult = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/listings/${listing.id}/buy").session(buyerSession)
        ).andReturn()

        then: "the sale succeeds and the buyer is debited"
        buyResult.response.status == 200
        walletRepository.findById(buyerWallet.id).get().balance == new BigDecimal("400.00")

        and: "the listing is SOLD"
        listingRepository.findById(listing.id).get().status == 'SOLD'

        and: "the seller is NOT paid yet — the money is held in escrow"
        walletRepository.findById(sellerWallet.id).get().balance == new BigDecimal("0.00")

        and: "a trade was opened for him to fulfil"
        def trades = tradeRepository.findBySeller(seller.id)
        trades.size() == 1
        def trade = trades[0]
        trade.state == 'PENDING_SELLER_ACCEPT'
        trade.price == new BigDecimal("100.00")

        when: "he accepts and marks the Steam offer sent, then the buyer confirms receipt"
        mockMvc.perform(MockMvcRequestBuilders.post("/api/trades/${trade.id}/accept")
            .session(sellerSession)).andReturn()
        mockMvc.perform(MockMvcRequestBuilders.post("/api/trades/${trade.id}/sent")
            .session(sellerSession)).andReturn()
        def confirmResult = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/trades/${trade.id}/confirm").session(buyerSession)
        ).andReturn()

        then: "confirmation succeeds"
        confirmResult.response.status == 200

        and: "he is paid \$98.00 — \$100 less the 2% platform fee"
        walletRepository.findById(sellerWallet.id).get().balance == new BigDecimal("98.00")

        and: "and the SALE transaction records it against his wallet"
        def sale = transactionRepository
            .findByWalletIdOrderByCreatedAtDesc(sellerWallet.id)
            .find { it.type == 'SALE' }
        sale != null
        sale.amount == new BigDecimal("98.00")
    }

    /** He must not be able to buy his own listing to wash-trade it. */
    def "POST /api/listings/{id}/buy — the seller cannot buy his own listing"() {
        given:
        inventoryHasTheItem()
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/steam/list")
                .session(sellerSession)
                .contentType("application/json")
                .content("""{"assetId":"${assetId}","price":"25.00"}""")
        ).andReturn()
        def listing = listingRepository.findAll().find { it.assetId == assetId }
        sellerWallet.balance = new BigDecimal("500.00")
        walletRepository.save(sellerWallet)

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/listings/${listing.id}/buy").session(sellerSession)
        ).andReturn()

        then: "refused, and the listing is untouched"
        result.response.status >= 400

        and: "refused as a self-purchase, not by some unrelated gate"
        !result.response.contentAsString.contains('TRADE_URL_MISSING')

        and:
        listingRepository.findById(listing.id).get().status == 'ACTIVE'

        and: "his own money is untouched"
        walletRepository.findById(sellerWallet.id).get().balance == new BigDecimal("500.00")
    }
}
