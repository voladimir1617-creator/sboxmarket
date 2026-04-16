package com.sboxmarket

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
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
 * Full-stack integration spec for the BUY path:
 *   HTTP → ListingController → PurchaseService → repositories → H2
 *
 * We seed a signed-in user + wallet + listing in the in-memory DB, then
 * POST through MockMvc with a session cookie carrying the Steam user id.
 * Asserts both the HTTP response shape AND the persisted side effects.
 *
 * HISTORY: this spec was quarantined under spock-spring 2.3 because
 * Groovy field injection didn't work with Spring Boot 3.2. Upgrading to
 * spock-spring 2.4-M6 (on the maven-central classpath) unblocks it and
 * repositories now inject cleanly. We still resolve beans from the
 * ApplicationContext in setup() as a belt-and-braces fallback.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BuyFlowIntegrationSpec extends Specification {

    @Autowired ApplicationContext ctx

    MockMvc               mockMvc
    SteamUserRepository   steamUserRepository
    WalletRepository      walletRepository
    ItemRepository        itemRepository
    ListingRepository     listingRepository
    TransactionRepository transactionRepository

    SteamUser buyer
    Wallet    buyerWallet
    Item      item
    Listing   listing
    MockHttpSession session

    def setup() {
        // Resolve beans from the application context — rock-solid in Groovy.
        mockMvc               = ctx.getBean(MockMvc)
        steamUserRepository   = ctx.getBean(SteamUserRepository)
        walletRepository      = ctx.getBean(WalletRepository)
        itemRepository        = ctx.getBean(ItemRepository)
        listingRepository     = ctx.getBean(ListingRepository)
        transactionRepository = ctx.getBean(TransactionRepository)

        // Additive isolation: create records with unique ids/usernames and
        // only assert against those, so this spec coexists with any other
        // spec sharing the Spring context.
        def uniq = String.valueOf(System.nanoTime())
        def steamId = "76561199" + uniq.substring(uniq.length() - 9)

        buyer = steamUserRepository.save(new SteamUser(
            steamId64:   steamId,
            displayName: "TestBuyer-" + uniq
        ))
        buyerWallet = walletRepository.save(new Wallet(
            username: "steam_" + steamId,
            balance:  new BigDecimal("500.00")
        ))
        item = itemRepository.save(new Item(
            name:         "Wizard Hat " + uniq,
            category:     "Hats",
            rarity:       "Limited",
            imageUrl:     "https://example.com/wizard.png",
            iconEmoji:    "🧙",
            accentColor:  "#1a0a3a",
            supply:       7000,
            totalSold:    0,
            trendPercent: 0,
            lowestPrice:  new BigDecimal("50.00")
        ))
        listing = listingRepository.save(new Listing(
            item:         item,
            price:        new BigDecimal("50.00"),
            sellerName:   "Bot",
            sellerAvatar: "BO",
            status:       "ACTIVE",
            rarityScore:  new BigDecimal("0.5")
        ))

        session = new MockHttpSession()
        session.setAttribute(SteamAuthController.SESSION_USER_ID, buyer.id)
    }

    def "POST /api/listings/{id}/buy — 200 + wallet debited + listing marked SOLD"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/listings/${listing.id}/buy").session(session)
        ).andReturn()

        then: "HTTP 200 with a correlation id header"
        result.response.status == 200
        result.response.getHeader("X-Correlation-Id") != null
        result.response.contentAsString.contains('"newBalance"')
        result.response.contentAsString.contains('"transactionId"')

        and: "wallet debited from 500.00 to 450.00"
        def reloaded = walletRepository.findById(buyerWallet.id).get()
        reloaded.balance == new BigDecimal("450.00")

        and: "listing marked SOLD with buyer set"
        def soldListing = listingRepository.findById(listing.id).get()
        soldListing.status == "SOLD"
        soldListing.buyerUserId == buyer.id
        soldListing.soldAt != null

        and: "purchase transaction recorded"
        def txs = transactionRepository.findByWalletIdOrderByCreatedAtDesc(buyerWallet.id)
        txs.size() == 1
        txs[0].type == "PURCHASE"
        txs[0].amount == new BigDecimal("50.00")
        txs[0].listingId == listing.id
        txs[0].status == "COMPLETED"
    }

    def "POST /api/listings/{id}/buy — 402 INSUFFICIENT_BALANCE when wallet is empty"() {
        given:
        buyerWallet.balance = new BigDecimal("10.00")
        walletRepository.save(buyerWallet)

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/listings/${listing.id}/buy").session(session)
        ).andReturn()

        then: "402 with structured error body containing the typed code"
        result.response.status == 402
        def body = result.response.contentAsString
        body.contains('"code":"INSUFFICIENT_BALANCE"')
        body.contains('"correlationId"')

        and: "no money moved, listing still ACTIVE"
        def reloaded = walletRepository.findById(buyerWallet.id).get()
        reloaded.balance == new BigDecimal("10.00")
        def listingReloaded = listingRepository.findById(listing.id).get()
        listingReloaded.status == "ACTIVE"
        listingReloaded.buyerUserId == null

        and: "no purchase transaction written"
        transactionRepository.findByWalletIdOrderByCreatedAtDesc(buyerWallet.id).isEmpty()
    }

    def "POST /api/listings/{id}/buy — 401 UNAUTHORIZED without session"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/listings/${listing.id}/buy")
        ).andReturn()

        then:
        result.response.status == 401
        result.response.contentAsString.contains('"code":"UNAUTHORIZED"')
    }

    def "POST /api/listings/{id}/buy — 409 when listing is already SOLD"() {
        given:
        listing.status = "SOLD"
        listingRepository.save(listing)

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/listings/${listing.id}/buy").session(session)
        ).andReturn()

        then:
        result.response.status == 409
        result.response.contentAsString.contains('"code":"LISTING_NOT_AVAILABLE"')
    }

    def "POST /api/wallet/deposit — 400 VALIDATION_FAILED on missing amount"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/wallet/deposit")
                .session(session)
                .contentType("application/json")
                .content("{}")
        ).andReturn()

        then:
        result.response.status == 400
        result.response.contentAsString.contains('"code":"VALIDATION_FAILED"')
        result.response.contentAsString.contains('"amount"')
    }

    def "POST /api/wallet/deposit — 400 VALIDATION_FAILED when amount exceeds max"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/wallet/deposit")
                .session(session)
                .contentType("application/json")
                .content('{"amount":50000}')
        ).andReturn()

        then:
        result.response.status == 400
        result.response.contentAsString.contains('"code":"VALIDATION_FAILED"')
    }

    def "POST /api/wallet/withdraw — 400 VALIDATION_FAILED when amount exceeds the \$10k cap"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/wallet/withdraw")
                .session(session)
                .contentType("application/json")
                .content('{"amount":50000,"destination":"acct_1"}')
        ).andReturn()

        then:
        result.response.status == 400
        result.response.contentAsString.contains('"code":"VALIDATION_FAILED"')
        // Pins the per-request cap so a future DTO edit can't silently
        // widen it — this is the single biggest defence against a
        // drained-wallet exploit from an inflated balance.
        result.response.contentAsString.contains('amount')
    }

    def "POST /api/wallet/withdraw — 400 VALIDATION_FAILED when amount is below \$1 minimum"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/wallet/withdraw")
                .session(session)
                .contentType("application/json")
                .content('{"amount":0.50,"destination":"acct_1"}')
        ).andReturn()

        then:
        result.response.status == 400
        result.response.contentAsString.contains('"code":"VALIDATION_FAILED"')
    }
}
