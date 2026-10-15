package com.sboxmarket

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.EscrowedItem
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.EscrowedItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.SteamDeliveryService
import com.sboxmarket.service.SteamEscrowService
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Shared
import spock.lang.Specification

/**
 * The whole skin loop with the bot switched ON, against a fake Steam.
 *
 * Every other escrow spec stubs one service or points the bot at a closed
 * port. This one wires nothing by hand: the real controllers, the real
 * SteamEscrowService and SteamDeliveryService, and the real HTTP client talk to
 * an in-process stand-in for the steam-bot sidecar (the same contract
 * steam-bot/fake-bot.js serves). The seller's Steam inventory is read through
 * steam.inventory.base-url from the same stand-in, so the list call goes
 * through the real SteamInventoryService too.
 *
 *   list   seller's skin -> bot (deposit offer) -> listing goes live
 *   buy    buyer pays -> bot sends the skin -> buyer accepts -> seller paid
 *   unsold seller cancels -> bot sends it back -> declined -> re-sent
 *
 * The background pollers are pushed an hour out so the spec drives each tick.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkinDepositLoopSpec extends Specification {

    @Shared static FakeSteam steam = FakeSteam.start()

    @DynamicPropertySource
    static void bot(DynamicPropertyRegistry r) {
        r.add('steam.bot.base-url') { steam.url }
        r.add('steam.bot.api-token') { FakeSteam.TOKEN }
        r.add('steam.inventory.base-url') { steam.url }
        ['steam.escrow.initial-delay-ms', 'steam.escrow.timeout-initial-delay-ms',
         'steam.escrow.hold-escalation-initial-delay-ms', 'steam.escrow.return-retry-initial-delay-ms',
         'steam.escrow.deposit-retry-initial-delay-ms', 'steam.escrow.return-confirm-initial-delay-ms',
         'steam.delivery.initial-delay-ms'].each { k -> r.add(k) { '3600000' } }
        r.add('steam.escrow.return-retry-backoff-ms') { '0' }
        r.add('steam.escrow.protection-initial-delay-ms') { '3600000' }
        // The Trade Protection window is off for the loop tests and turned on
        // by the one test that exercises it.
        r.add('steam.escrow.trade-protection-hours') { '0' }
    }

    def cleanupSpec() { steam.stop() }

    @Autowired MockMvc mockMvc
    @Autowired SteamUserRepository steamUserRepository
    @Autowired WalletRepository walletRepository
    @Autowired ListingRepository listingRepository
    @Autowired TradeRepository tradeRepository
    @Autowired EscrowedItemRepository escrowRepository
    @Autowired SteamEscrowService escrowService
    @Autowired SteamDeliveryService deliveryService
    @Autowired com.sboxmarket.repository.ItemRepository itemRepository
    @Autowired com.sboxmarket.service.TradeService tradeService

    SteamUser seller, buyer
    Wallet sellerWallet, buyerWallet
    MockHttpSession sellerSession, buyerSession
    String sellerSteamId, buyerSteamId, skin, assetId

    def setup() {
        def n = System.nanoTime() % 1_000_000_000L
        long sellerPartner = 100_000_000L + n, buyerPartner = 200_000_000L + n
        sellerSteamId = FakeSteam.steamId(sellerPartner)
        buyerSteamId = FakeSteam.steamId(buyerPartner)
        seller = steamUserRepository.save(new SteamUser(steamId64: sellerSteamId, displayName: "LoopSeller$n",
                tradeUrl: "https://steamcommunity.com/tradeoffer/new/?partner=${sellerPartner}&token=s${n}"))
        buyer = steamUserRepository.save(new SteamUser(steamId64: buyerSteamId, displayName: "LoopBuyer$n",
                tradeUrl: "https://steamcommunity.com/tradeoffer/new/?partner=${buyerPartner}&token=b${n}"))
        sellerWallet = walletRepository.save(new Wallet(username: "steam_$sellerSteamId", balance: 0.00G))
        buyerWallet = walletRepository.save(new Wallet(username: "steam_$buyerSteamId", balance: 500.00G))
        sellerSession = new MockHttpSession(); sellerSession.setAttribute(SteamAuthController.SESSION_USER_ID, seller.id)
        buyerSession = new MockHttpSession(); buyerSession.setAttribute(SteamAuthController.SESSION_USER_ID, buyer.id)

        skin = "Loop Test Wizard Hat $n"
        assetId = steam.give(sellerSteamId, skin)
    }

    private def post(String path, MockHttpSession session, String body = null) {
        def req = MockMvcRequestBuilders.post(path).session(session)
        if (body != null) req = req.contentType('application/json').content(body)
        mockMvc.perform(req).andReturn().response
    }

    private Map myTrade(MockHttpSession session, Long tradeId) {
        def res = mockMvc.perform(MockMvcRequestBuilders.get('/api/trades').session(session)).andReturn().response
        assert res.status == 200
        (new JsonSlurper().parseText(res.contentAsString) as List<Map>).find { it.id == tradeId }
    }

    private Map myBotCustody() {
        def res = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings/my-stall/bot-custody')
                .session(sellerSession)).andReturn().response
        assert res.status == 200
        new JsonSlurper().parseText(res.contentAsString) as Map
    }

    def "a manual sale on the same deployment is not marked bot-delivered"() {
        given: "a listing that never went through the bot (created directly, legacy-style)"
        def item = new com.sboxmarket.model.Item(name: "Manual Hat ${System.nanoTime()}", category: 'Hats', rarity: 'Standard')
        item = itemRepository.save(item)
        def listing = listingRepository.save(new com.sboxmarket.model.Listing(item: item, sellerUserId: seller.id,
                sellerName: seller.displayName, price: 5.00G, status: 'ACTIVE'))
        tradeService.open(listing.id, item.id, item.name, buyer.id, buyerWallet.id, seller.id, sellerWallet.id, 5.00G)
        def trade = tradeRepository.findByListingId(listing.id)

        expect:
        trade.state == 'PENDING_SELLER_ACCEPT'
        def row = myTrade(sellerSession, trade.id)
        row != null
        row.botManaged == null
    }

    /** List the seller's skin and let the bot take custody of it. */
    private Long listAndDeposit() {
        def res = post('/api/steam/list', sellerSession, """{"assetId":"$assetId","price":"40.00"}""")
        assert res.status == 200 : res.contentAsString
        def listing = listingRepository.findAll().find { it.assetId == assetId }
        assert listing.status == SteamEscrowService.STATUS_PENDING_ESCROW
        steam.acceptAll()
        escrowService.pollDeposits()
        listing.id
    }

    def "a listed skin goes into the bot, and the listing is buyable only once it is there"() {
        when:
        def res = post('/api/steam/list', sellerSession, """{"assetId":"$assetId","price":"40.00"}""")
        def listing = listingRepository.findAll().find { it.assetId == assetId }

        then: "the bot asked the seller for exactly that skin"
        res.status == 200
        listing.status == SteamEscrowService.STATUS_PENDING_ESCROW
        steam.openOffers().any { it.kind == 'receive' && it.partner == sellerSteamId && it.assetIds == [assetId] }

        and: "the code the site shows him is the one written on the bot's offer"
        def shown = new JsonSlurper().parseText(mockMvc.perform(MockMvcRequestBuilders
                .get('/api/listings/my-stall/pending-escrow').session(sellerSession))
                .andReturn().response.contentAsString)[0].escrow.securityCode as String
        shown ==~ /[A-Z2-9]{4}/
        steam.openOffers().find { it.kind == 'receive' }.message.endsWith("code $shown")

        when: "the seller accepts the deposit offer and the poller ticks"
        steam.acceptAll()
        escrowService.pollDeposits()

        then: "the skin left the seller and the bot holds it, under its NEW Steam asset id"
        !steam.holds(sellerSteamId, skin)
        steam.holds(FakeSteam.BOT, skin)
        def custody = escrowRepository.findByListingId(listing.id)
        custody.custodyState == EscrowedItem.IN_CUSTODY
        custody.heldAssetId == steam.assetOf(FakeSteam.BOT, skin)
        custody.heldAssetId != assetId

        and: "and only now is the listing live"
        listingRepository.findById(listing.id).get().status == 'ACTIVE'
    }

    def "a buyer pays, the bot delivers the skin, and the seller is paid only after the buyer accepts"() {
        given:
        Long listingId = listAndDeposit()

        when: "the buyer buys it"
        def res = post("/api/listings/$listingId/buy", buyerSession, '{"expectedPrice":"40.00"}')
        def trade = tradeRepository.findByListingId(listingId)

        then: "the sale goes straight to the bot; the seller has nothing left to accept"
        res.status == 200
        trade.state == 'PENDING_SELLER_SEND'
        walletRepository.findById(buyerWallet.id).get().balance == 460.00G

        and: "the seller's Trades tab knows the bot is delivering, so it asks for no hand-sent offer"
        def sellerRow = myTrade(sellerSession, trade.id)
        sellerRow.botManaged == true
        sellerRow.botDeliveryState == null

        when: "the delivery poller ticks"
        deliveryService.pollDeliveries()

        then: "the bot sent the buyer an offer for the held skin, and nobody is paid yet"
        steam.openOffers().any { it.kind == 'give' && it.partner == buyerSteamId }
        tradeRepository.findById(trade.id).get().state == 'PENDING_BUYER_CONFIRM'
        walletRepository.findById(sellerWallet.id).get().balance == 0.00G

        and: "the buyer's Trades tab shows the bot's offer is out"
        def buyerRow = myTrade(buyerSession, trade.id)
        buyerRow.botManaged == true
        buyerRow.botDeliveryState in ['sent', 'active']

        when: "the buyer accepts in Steam and the poller ticks"
        steam.acceptAll()
        deliveryService.pollDeliveries()
        def done = tradeRepository.findById(trade.id).get()

        then: "the skin is the buyer's, the trade is verified, the seller is credited"
        steam.holds(buyerSteamId, skin)
        !steam.holds(FakeSteam.BOT, skin)
        done.state == 'VERIFIED'
        escrowRepository.findByListingId(listingId).custodyState == EscrowedItem.DELIVERED
        walletRepository.findById(sellerWallet.id).get().balance == 40.00G - done.feeAmount
    }

    def "a deposit under Steam Trade Protection goes on sale only once the protection is over"() {
        given: "Steam protects fresh deposits, and the site holds them for 8 days like Skin.Club"
        steam.protectNew = true
        int savedHours = escrowService.tradeProtectionHours
        escrowService.tradeProtectionHours = 192
        post('/api/steam/list', sellerSession, """{"assetId":"$assetId","price":"40.00"}""")
        def listing = listingRepository.findAll().find { it.assetId == assetId }

        when: "the seller accepts and the skin lands in the bot, marked not tradable"
        steam.acceptAll()
        escrowService.pollDeposits()
        def row = escrowRepository.findByListingId(listing.id)

        then: "the bot holds it, but the listing is not for sale and the seller is told when it will be"
        steam.holds(FakeSteam.BOT, skin)
        row.custodyState == EscrowedItem.IN_TRADE_PROTECTION
        row.protectedUntil > System.currentTimeMillis() + 7L * 24 * 3600 * 1000
        listingRepository.findById(listing.id).get().status == SteamEscrowService.STATUS_PENDING_ESCROW
        escrowService.heldAssetIdForListing(listing.id) == null
        def view = new JsonSlurper().parseText(mockMvc.perform(MockMvcRequestBuilders
                .get('/api/listings/my-stall/pending-escrow').session(sellerSession))
                .andReturn().response.contentAsString)[0].escrow
        view.inTradeProtection == true
        view.protectedUntilOn != null

        when: "the window passes but Steam still says the skin is protected"
        row.protectedUntil = System.currentTimeMillis() - 1000
        escrowRepository.save(row)
        escrowService.releaseTradeProtection()

        then: "it keeps waiting"
        escrowRepository.findByListingId(listing.id).custodyState == EscrowedItem.IN_TRADE_PROTECTION

        when: "Steam lifts the protection"
        steam.unlock(skin)
        escrowService.releaseTradeProtection()

        then: "custody is confirmed and the listing goes live"
        escrowRepository.findByListingId(listing.id).custodyState == EscrowedItem.IN_CUSTODY
        listingRepository.findById(listing.id).get().status == 'ACTIVE'

        cleanup:
        steam.protectNew = false
        escrowService.tradeProtectionHours = savedHours
    }

    def "a seller who reverses the deposit in Steam gets the skin back and the listing is cancelled, nothing sold"() {
        given:
        steam.protectNew = true
        long savedConfirm = escrowService.missingConfirmMs
        escrowService.missingConfirmMs = 0L
        post('/api/steam/list', sellerSession, """{"assetId":"$assetId","price":"40.00"}""")
        def listing = listingRepository.findAll().find { it.assetId == assetId }
        steam.acceptAll()
        escrowService.pollDeposits()
        assert escrowRepository.findByListingId(listing.id).custodyState == EscrowedItem.IN_TRADE_PROTECTION

        when: "the seller presses Reverse trade in Steam"
        steam.reverse(skin)
        escrowService.releaseTradeProtection()

        then: "one missing read is only noted"
        escrowRepository.findByListingId(listing.id).custodyState == EscrowedItem.IN_TRADE_PROTECTION
        escrowRepository.findByListingId(listing.id).missingSince != null

        when: "the next read still has no skin"
        escrowService.releaseTradeProtection()
        def row = escrowRepository.findByListingId(listing.id)

        then:
        steam.holds(sellerSteamId, skin)
        row.custodyState == EscrowedItem.REVERSED
        listingRepository.findById(listing.id).get().status == SteamEscrowService.STATUS_CANCELLED
        walletRepository.findById(sellerWallet.id).get().balance == 0.00G

        cleanup:
        steam.protectNew = false
        escrowService.missingConfirmMs = savedConfirm
    }

    def "an unsold skin goes back to the seller, and a declined return is sent again"() {
        given:
        Long listingId = listAndDeposit()

        expect: "My Stall shows the skin as held by the bot, so its button withdraws"
        myBotCustody().heldListingIds == [listingId]
        myBotCustody().returning == []

        when: "the seller cancels the listing"
        def res = mockMvc.perform(MockMvcRequestBuilders.delete("/api/listings/$listingId")
                .session(sellerSession)).andReturn().response

        then: "the bot sends the skin back"
        res.status == 200
        def out = steam.openOffers().find { it.kind == 'give' && it.partner == sellerSteamId }
        out != null
        escrowRepository.findByListingId(listingId).custodyState == EscrowedItem.RETURNED
        out.message.endsWith("code ${myBotCustody().returning[0].securityCode}")

        and: "My Stall shows it on its way back, no longer held"
        myBotCustody().heldListingIds == []
        myBotCustody().returning*.state == ['OFFER_SENT']
        myBotCustody().returning[0].name == skin

        when: "the seller declines that offer and the return poller ticks"
        steam.decline(out.id)
        escrowService.pollReturnOffers()

        then: "the row no longer claims the skin went home; it is owed again"
        def owed = escrowRepository.findByListingId(listingId)
        owed.custodyState == EscrowedItem.IN_CUSTODY
        owed.returnRequestedAt != null
        steam.holds(FakeSteam.BOT, skin)
        myBotCustody().heldListingIds == []
        myBotCustody().returning*.state == ['RESENDING']

        when: "the retry sweep re-sends, the seller accepts, the poller ticks"
        escrowService.sweepPendingReturns()
        steam.acceptAll()
        escrowService.pollReturnOffers()

        then: "the skin is back with the seller and the return is confirmed"
        steam.holds(sellerSteamId, skin)
        !steam.holds(FakeSteam.BOT, skin)
        def back = escrowRepository.findByListingId(listingId)
        back.custodyState == EscrowedItem.RETURNED
        back.returnConfirmedAt != null
        myBotCustody().returning*.state == ['BACK']
    }

    /**
     * In-process stand-in for the steam-bot sidecar, on the contract
     * steam-bot/fake-bot.js serves: offers stay active until the partner acts,
     * Steam re-issues asset ids on receipt, and the public inventory endpoint
     * answers in Steam Community's JSON shape.
     */
    static class FakeSteam {
        static final String TOKEN = 'loop-test-token'
        static final String BOT = '76561190000000001'
        static String steamId(long partner) { String.valueOf(76561197960265728L + partner) }

        HttpServer server
        String url
        long nextId = 5_000_000_001L
        Map<String, List<Map>> inv = [:].withDefault { [] }
        Map<String, Map> offers = [:]
        /** Play Steam's Trade Protection: skins that land in the bot read
         *  tradable:false until {@link #unlock}, and can be {@link #reverse}d. */
        boolean protectNew = false

        static FakeSteam start() {
            def f = new FakeSteam()
            f.server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
            f.server.createContext('/') { HttpExchange x -> f.handle(x) }
            f.server.start()
            f.url = "http://127.0.0.1:${f.server.address.port}".toString()
            f
        }

        void stop() { server.stop(0) }

        synchronized String give(String steamId, String name) {
            def id = String.valueOf(nextId++)
            inv[steamId] << [assetId: id, name: name]
            id
        }
        synchronized boolean holds(String steamId, String name) { inv[steamId].any { it.name == name } }
        synchronized String assetOf(String steamId, String name) { inv[steamId].find { it.name == name }?.assetId }
        synchronized List<Map> openOffers() { offers.values().findAll { it.state == 'active' }.collect { new HashMap(it) } }
        synchronized void acceptAll() { openOffers().each { settle(offers[it.id], 'accepted') } }
        synchronized void decline(String id) { settle(offers[id], 'declined') }

        private void settle(Map o, String outcome) {
            if (o.state != 'active') return
            if (outcome != 'accepted') { o.state = outcome; return }
            def (from, to) = o.kind == 'give' ? [BOT, o.partner] : [o.partner, BOT]
            def moving = inv[from].findAll { it.assetId in o.assetIds }
            if (moving.size() != o.assetIds.size()) { o.state = 'invalid_items'; return }
            inv[from].removeAll(moving)
            moving.each {
                def id = give(to as String, it.name as String)
                if (to == BOT && protectNew) inv[BOT].find { a -> a.assetId == id }.putAll([locked: true, cameFrom: from])
            }
            o.state = 'accepted'
        }

        synchronized void unlock(String name) { inv[BOT].findAll { it.name == name }*.locked = false }
        /** The sender presses Steam's "Reverse trade": the skin goes back. */
        synchronized void reverse(String name) {
            def it = inv[BOT].find { a -> a.name == name }
            inv[BOT].remove(it)
            give(it.cameFrom as String, name)
        }

        private synchronized Object handle(HttpExchange x) {
            def path = x.requestURI.path
            def body = x.requestBody.text
            def json = body ? new JsonSlurper().parseText(body) : null
            def m
            def reply = { int code, Object payload ->
                byte[] bytes = JsonOutput.toJson(payload).getBytes('UTF-8')
                x.responseHeaders.add('Content-Type', 'application/json')
                x.sendResponseHeaders(code, bytes.length)
                x.responseBody.withStream { it.write(bytes) }
            }
            if ((m = path =~ /^\/inventory\/(\d{17})\/590830\/2$/)) {
                def items = inv[m[0][1] as String]
                return reply(200, [
                    assets: items.collect { [appid: 590830, contextid: '2', assetid: it.assetId, classid: it.name.hashCode().abs().toString(), instanceid: '0', amount: '1'] },
                    descriptions: items.collect { [classid: it.name.hashCode().abs().toString(), instanceid: '0', market_hash_name: it.name, name: it.name, type: 'Hat', tradable: 1, marketable: 1] }.unique { it.classid },
                    total_inventory_count: items.size(), success: 1])
            }
            if (x.requestHeaders.getFirst('Authorization') != "Bearer $TOKEN".toString()) {
                return reply(401, [ok: false, error: 'unauthorized'])
            }
            if (x.requestMethod == 'POST' && path in ['/offers/send', '/offers/request']) {
                String kind = path.endsWith('send') ? 'give' : 'receive'
                def partner = (json.partnerTradeUrl =~ /partner=(\d+)/)
                if (!partner) return reply(400, [ok: false, error: 'BAD_TRADE_URL', message: 'bad trade url'])
                String partnerId = steamId(partner[0][1] as long)
                List<String> ids = json.assetIds.collect { String.valueOf(it) }
                String holder = kind == 'give' ? BOT : partnerId
                if (!ids.every { id -> inv[holder].any { it.assetId == id } }) {
                    return reply(502, [ok: false, error: 'INVENTORY_ERROR', message: 'item not in inventory'])
                }
                def id = String.valueOf(nextId++)
                offers[id] = [id: id, kind: kind, partner: partnerId, assetIds: ids, state: 'active', message: json.message]
                return reply(200, [ok: true, offerId: id, status: 'sent', steamStatus: 'sent', confirmed: true])
            }
            if (x.requestMethod == 'GET' && (m = path =~ /^\/offers\/(\d+)$/)) {
                def o = offers[m[0][1] as String]
                if (o == null) return reply(404, [ok: false, error: 'ERROR', message: 'no matching offer'])
                return reply(200, [ok: true, offerId: o.id, status: o.state, isOurOffer: true, partner: o.partner, escrowEndsUnix: null])
            }
            if (x.requestMethod == 'GET' && path == '/inventory') {
                def items = inv[BOT]
                return reply(200, [ok: true, appId: 590830, count: items.size(),
                    items: items.collect { [assetId: it.assetId, marketHashName: it.name, name: it.name, tradable: !it.locked] }])
            }
            reply(404, [ok: false, error: 'not_found'])
        }
    }
}
