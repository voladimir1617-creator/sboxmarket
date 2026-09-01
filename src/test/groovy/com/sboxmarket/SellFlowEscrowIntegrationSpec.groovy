package com.sboxmarket

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.EscrowedItem
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.EscrowedItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.SteamEscrowService
import com.sboxmarket.service.SteamInventoryService
import org.spockframework.spring.SpringBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification

/**
 * The SELL path with bot-escrow ON — the configuration the operator will
 * actually run once he pastes his Steam bot credentials.
 *
 * ── Why this is separate from SellFlowIntegrationSpec ─────────────────────
 * {@code SteamEscrowService.isEscrowEnabled()} is simply
 * {@code steamTradeBotService.enabled}, which is itself just "a bot base-url
 * is configured". So escrow is switched on here by setting that property —
 * no mocking of the escrow logic at all. The bot URL points at a closed local
 * port, which is deliberate: it exercises the REALISTIC first-run failure
 * where the sidecar is configured but not reachable yet.
 *
 * The money-critical invariant, and the reason this spec exists:
 * a listing whose item is not yet in the bot's custody MUST NOT be buyable.
 * If it were, a buyer could pay for an item the platform cannot deliver.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = [
    // Non-empty base-url ⇒ SteamTradeBotService.enabled ⇒ escrow enabled.
    // Port 1 is closed, so the sidecar call fails fast (connection refused)
    // and returns a TRANSPORT_ERROR result rather than throwing.
    'steam.bot.base-url=http://127.0.0.1:1',
    'steam.bot.api-token=test-token',
    'steam.bot.connect-timeout-ms=250',
    'steam.bot.request-timeout-ms=250',
    // Keep the background escrow sweeps out of this spec — we assert the
    // synchronous listing-time behaviour only.
    'steam.escrow.enabled=false'
])
class SellFlowEscrowIntegrationSpec extends Specification {

    @SpringBean
    SteamInventoryService steamInventoryService = Stub(SteamInventoryService)

    @Autowired ApplicationContext ctx

    MockMvc                mockMvc
    SteamUserRepository    steamUserRepository
    WalletRepository       walletRepository
    ListingRepository      listingRepository
    EscrowedItemRepository escrowedItemRepository
    SteamEscrowService     steamEscrowService

    SteamUser seller
    SteamUser buyer
    Wallet    buyerWallet
    MockHttpSession sellerSession
    MockHttpSession buyerSession
    String assetId
    String itemName

    def setup() {
        mockMvc                = ctx.getBean(MockMvc)
        steamUserRepository    = ctx.getBean(SteamUserRepository)
        walletRepository       = ctx.getBean(WalletRepository)
        listingRepository      = ctx.getBean(ListingRepository)
        escrowedItemRepository = ctx.getBean(EscrowedItemRepository)
        steamEscrowService     = ctx.getBean(SteamEscrowService)

        def uniq = String.valueOf(System.nanoTime())
        assetId  = uniq.substring(uniq.length() - 12)
        itemName = "Escrow Test Cosmetic " + uniq

        def sellerSteamId = "76561199" + uniq.substring(uniq.length() - 9)
        seller = steamUserRepository.save(new SteamUser(
            steamId64:   sellerSteamId,
            displayName: "EscrowSeller-" + uniq,
            tradeUrl:    "https://steamcommunity.com/tradeoffer/new/?partner=1&token=abc"
        ))
        walletRepository.save(new Wallet(username: "steam_" + sellerSteamId,
                                         balance: new BigDecimal("0.00")))

        def buyerSteamId = "76561198" + uniq.substring(uniq.length() - 9)
        // Give the buyer a trade URL. Without one, /buy refuses every P2P
        // listing with TRADE_URL_MISSING — so the PENDING_ESCROW test below
        // would have passed for entirely the wrong reason.
        buyer = steamUserRepository.save(new SteamUser(
            steamId64:   buyerSteamId,
            displayName: "EscrowBuyer-" + uniq,
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

        steamInventoryService.fetchInventory(seller.steamId64) >> [
            [assetId: assetId, name: itemName, category: 'Hats', rarity: 'Standard',
             iconUrl: 'https://example.com/hat.png', marketable: true, tradable: true,
             quantity: 1] as Map
        ]
        steamInventoryService.blockedUntilMs(_ as String) >> null
        steamInventoryService.inferCategory(_ as Map) >> 'Hats'
    }

    def "escrow is actually enabled in this spec"() {
        expect: "otherwise every assertion below would be vacuously true"
        steamEscrowService.escrowEnabled
    }

    def "POST /api/steam/list — with escrow on, the listing is held PENDING_ESCROW, not ACTIVE"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/steam/list")
                .session(sellerSession)
                .contentType("application/json")
                .content("""{"assetId":"${assetId}","price":"100.00"}""")
        ).andReturn()

        then:
        result.response.status == 200
        result.response.contentAsString.contains('"escrowPending":true')

        and: "persisted as PENDING_ESCROW — the item is not in custody yet"
        def listing = listingRepository.findAll().find { it.assetId == assetId }
        listing != null
        listing.status == SteamEscrowService.STATUS_PENDING_ESCROW

        and: "a custody row is tracking the deposit we are waiting for"
        def custody = escrowedItemRepository.findByListingId(listing.id)
        custody != null
        custody.custodyState == EscrowedItem.PENDING_DEPOSIT
        custody.assetId == assetId
    }

    /**
     * THE money-safety invariant. A PENDING_ESCROW listing is one whose item
     * the platform does not yet hold. If a buyer could purchase it, we would
     * have taken his money for something we cannot deliver.
     */
    def "a PENDING_ESCROW listing cannot be bought"() {
        given: "a listing held for deposit"
        mockMvc.perform(
            MockMvcRequestBuilders.post("/api/steam/list")
                .session(sellerSession)
                .contentType("application/json")
                .content("""{"assetId":"${assetId}","price":"100.00"}""")
        ).andReturn()
        def listing = listingRepository.findAll().find { it.assetId == assetId }
        assert listing.status == SteamEscrowService.STATUS_PENDING_ESCROW

        when: "a buyer tries to buy it anyway"
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/listings/${listing.id}/buy").session(buyerSession)
        ).andReturn()

        then: "refused"
        result.response.status >= 400

        and: "refused BECAUSE it is not in custody — not for some unrelated gate"
        // Guards against this test passing for the wrong reason: without a
        // buyer trade URL every P2P buy 400s with TRADE_URL_MISSING, which
        // would have made the assertion above green while proving nothing
        // about escrow at all.
        !result.response.contentAsString.contains('TRADE_URL_MISSING')

        and: "no money moved"
        walletRepository.findById(buyerWallet.id).get().balance == new BigDecimal("500.00")

        and: "and the listing is still held, not sold"
        def after = listingRepository.findById(listing.id).get()
        after.status == SteamEscrowService.STATUS_PENDING_ESCROW
        after.buyerUserId == null
    }

    /**
     * The bot collects the item using the seller's trade URL. Without one it
     * can never take custody, so the listing must be refused up front rather
     * than created and silently stranded.
     */
    def "POST /api/steam/list — 400 TRADE_URL_MISSING when the bot has no way to collect"() {
        given: "a seller who never set a trade URL"
        seller.tradeUrl = null
        steamUserRepository.save(seller)

        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/steam/list")
                .session(sellerSession)
                .contentType("application/json")
                .content("""{"assetId":"${assetId}","price":"100.00"}""")
        ).andReturn()

        then:
        result.response.status == 400
        result.response.contentAsString.contains('"code":"TRADE_URL_MISSING"')

        and: "no listing row was created at all"
        listingRepository.findAll().findAll { it.assetId == assetId }.isEmpty()
    }

    /**
     * The sidecar being unreachable must not lose the listing or leave it
     * buyable. It stays held, with the failure recorded for retry.
     */
    def "an unreachable bot sidecar leaves the listing held and records the error"() {
        when:
        def result = mockMvc.perform(
            MockMvcRequestBuilders.post("/api/steam/list")
                .session(sellerSession)
                .contentType("application/json")
                .content("""{"assetId":"${assetId}","price":"100.00"}""")
        ).andReturn()

        then: "the list call still succeeds — the listing is persisted"
        result.response.status == 200

        and: "held, not buyable"
        def listing = listingRepository.findAll().find { it.assetId == assetId }
        listing.status == SteamEscrowService.STATUS_PENDING_ESCROW

        and: "custody row carries no offer id and records why, so a retry can re-request"
        def custody = escrowedItemRepository.findByListingId(listing.id)
        custody != null
        custody.depositOfferId == null
        custody.lastError != null
    }
}
