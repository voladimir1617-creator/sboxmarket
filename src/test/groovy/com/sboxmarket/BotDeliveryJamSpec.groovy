package com.sboxmarket

import com.sboxmarket.model.EscrowedItem
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Trade
import com.sboxmarket.repository.EscrowedItemRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamDeliveryAttemptRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.service.SteamDeliveryService
import com.sboxmarket.service.SteamEscrowService
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import spock.lang.Shared
import spock.lang.Specification

/**
 * Stuck trades must not jam the bot's deliveries or re-hit Steam.
 *
 *  - P2P trades (the seller sends by hand) share PENDING_SELLER_SEND with bot
 *    sales. The poller took the oldest 50 of them all, so 50 P2P trades meant
 *    no bot sale behind them was ever sent.
 *  - A send the sidecar refuses with retryable:false (an item already in
 *    another live offer, a bad trade URL) was re-sent every tick. It now waits
 *    until its input changes, and the same goes for deposit requests.
 *
 * Runs against an in-process stand-in for the steam-bot sidecar that counts
 * every send; the pollers are pushed an hour out so the spec drives each tick.
 */
@SpringBootTest
@ActiveProfiles("test")
class BotDeliveryJamSpec extends Specification {

    @Shared static Sidecar sidecar = Sidecar.start()

    @DynamicPropertySource
    static void bot(DynamicPropertyRegistry r) {
        r.add('steam.bot.base-url') { sidecar.url }
        r.add('steam.bot.api-token') { Sidecar.TOKEN }
        ['steam.escrow.initial-delay-ms', 'steam.escrow.timeout-initial-delay-ms',
         'steam.escrow.hold-escalation-initial-delay-ms', 'steam.escrow.return-retry-initial-delay-ms',
         'steam.escrow.deposit-retry-initial-delay-ms', 'steam.escrow.return-confirm-initial-delay-ms',
         'steam.escrow.protection-initial-delay-ms', 'steam.delivery.initial-delay-ms'].each { k -> r.add(k) { '3600000' } }
        r.add('steam.escrow.deposit-retry-backoff-ms') { '0' }
    }

    def cleanupSpec() { sidecar.stop() }

    @Autowired SteamDeliveryService deliveryService
    @Autowired SteamEscrowService escrowService
    @Autowired TradeRepository tradeRepository
    @Autowired EscrowedItemRepository escrowRepository
    @Autowired SteamDeliveryAttemptRepository attemptRepository
    @Autowired SteamUserRepository steamUserRepository
    @Autowired ListingRepository listingRepository
    @Autowired ItemRepository itemRepository

    List<Long> tradeIds = []
    List<Long> escrowIds = []
    long n

    def setup() {
        n = System.nanoTime() % 1_000_000_000L
        sidecar.reset()
    }

    def cleanup() {
        tradeIds.each { id -> attemptRepository.deleteAll(attemptRepository.findByTrade(id, PageRequest.of(0, 1000))) }
        tradeRepository.deleteAllById(tradeIds)
        escrowRepository.deleteAllById(escrowIds)
    }

    private SteamUser user(String name, long partner) {
        steamUserRepository.save(new SteamUser(steamId64: Sidecar.steamId(partner), displayName: "$name$n",
                tradeUrl: "https://steamcommunity.com/tradeoffer/new/?partner=${partner}&token=t${n}"))
    }

    private Trade trade(Long listingId, SteamUser buyer, SteamUser seller, long updatedAt) {
        def t = tradeRepository.save(new Trade(listingId: listingId, itemId: 1L, itemName: 'Jam Hat',
                buyerUserId: buyer.id, sellerUserId: seller.id, price: 5.00G,
                state: SteamDeliveryService.STATE_AWAITING_SEND, createdAt: updatedAt, updatedAt: updatedAt))
        tradeIds << t.id
        t
    }

    /** A sale whose skin the bot holds. */
    private Trade botTrade(SteamUser buyer, SteamUser seller, long updatedAt) {
        long listingId = 900_000_000_000L + n
        def e = escrowRepository.save(new EscrowedItem(listingId: listingId, sellerUserId: seller.id,
                assetId: "77${n}", heldAssetId: "77${n}", marketHashName: 'Jam Hat',
                custodyState: EscrowedItem.IN_CUSTODY))
        escrowIds << e.id
        trade(listingId, buyer, seller, updatedAt)
    }

    def "a bot sale behind 60 P2P trades is sent on the first tick"() {
        given: "60 P2P trades, older than the bot sale, all owing a hand-sent skin"
        def seller = user('JamSeller', 300_000_000L + n)
        def p2pBuyer = user('JamP2P', 400_000_000L + n)
        60.times { i -> trade(800_000_000_000L + n * 100 + i, p2pBuyer, seller, 1L + i) }

        and: "one sale whose skin is in the bot"
        def buyer = user('JamBuyer', 500_000_000L + n)
        def sale = botTrade(buyer, seller, 100L)

        when:
        deliveryService.pollDeliveries()

        then: "the bot sent the buyer their skin"
        sidecar.sendsTo(buyer.steamId64) == 1
        attemptRepository.findByTrade(sale.id, PageRequest.of(0, 10)).any { it.phase == 'SEND' && it.success }

        and: "the P2P trades were left alone"
        sidecar.sendsTo(p2pBuyer.steamId64) == 0
    }

    def "a send refused with retryable:false is sent once over 10 ticks, and again once the buyer saves a new trade URL"() {
        given:
        sidecar.refuse = true
        def seller = user('HaltSeller', 600_000_000L + n)
        def buyer = user('HaltBuyer', 700_000_000L + n)
        def sale = botTrade(buyer, seller, 100L)

        when:
        10.times { deliveryService.pollDeliveries() }

        then: "Steam was asked once and one attempt was written"
        sidecar.sendsTo(buyer.steamId64) == 1
        attemptRepository.findByTrade(sale.id, PageRequest.of(0, 100)).size() == 1

        when: "the buyer saves a new trade URL"
        buyer.tradeUrl = "https://steamcommunity.com/tradeoffer/new/?partner=${700_000_000L + n}&token=new${n}"
        steamUserRepository.save(buyer)
        3.times { deliveryService.pollDeliveries() }

        then: "the new input is tried once"
        sidecar.sendsTo(buyer.steamId64) == 2
    }

    def "a deposit request refused with retryable:false is not re-requested until the seller's trade URL changes"() {
        given:
        sidecar.refuse = true
        def seller = user('DepSeller', 750_000_000L + n)
        def item = itemRepository.save(new Item(name: "Deposit Jam Hat $n", category: 'Hats', rarity: 'Standard'))
        def listing = listingRepository.save(new Listing(item: item, sellerUserId: seller.id,
                sellerName: seller.displayName, price: 5.00G, status: 'ACTIVE'))

        when:
        def row = escrowService.requestDepositForListing(listing, "88${n}", item.name)
        escrowIds << row.id
        10.times { Thread.sleep(3); escrowService.sweepUnsentDeposits() }

        then:
        row.depositOfferId == null
        sidecar.requestsFrom(seller.steamId64) == 1

        when: "the seller saves a new trade URL"
        seller.tradeUrl = "https://steamcommunity.com/tradeoffer/new/?partner=${750_000_000L + n}&token=new${n}"
        steamUserRepository.save(seller)
        Thread.sleep(3)
        escrowService.sweepUnsentDeposits()

        then:
        sidecar.requestsFrom(seller.steamId64) == 2
    }

    /** Stand-in for the steam-bot sidecar: accepts every offer, or with
     *  {@link #refuse} answers the way the real one does for a skin already in
     *  another live offer (409 ASSET_COMMITTED, retryable:false). */
    static class Sidecar {
        static final String TOKEN = 'jam-test-token'
        static String steamId(long partner) { String.valueOf(76561197960265728L + partner) }

        HttpServer server
        String url
        long nextId = 7_000_000_001L
        boolean refuse = false
        List<Map> calls = []

        static Sidecar start() {
            def s = new Sidecar()
            s.server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
            s.server.createContext('/') { HttpExchange x -> s.handle(x) }
            s.server.start()
            s.url = "http://127.0.0.1:${s.server.address.port}".toString()
            s
        }

        void stop() { server.stop(0) }
        synchronized void reset() { refuse = false; calls.clear() }
        synchronized int sendsTo(String steamId) { calls.count { it.kind == 'give' && it.partner == steamId } as int }
        synchronized int requestsFrom(String steamId) { calls.count { it.kind == 'receive' && it.partner == steamId } as int }

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
            if (x.requestHeaders.getFirst('Authorization') != "Bearer $TOKEN".toString()) {
                return reply(401, [ok: false, error: 'unauthorized'])
            }
            if (x.requestMethod == 'POST' && path in ['/offers/send', '/offers/request']) {
                def partner = (json.partnerTradeUrl =~ /partner=(\d+)/)
                calls << [kind: path.endsWith('send') ? 'give' : 'receive', partner: steamId(partner[0][1] as long)]
                if (refuse) {
                    return reply(409, [ok: false, error: 'ASSET_COMMITTED', retryable: false,
                                       message: 'an item in this offer is already in live offer #1'])
                }
                return reply(200, [ok: true, offerId: String.valueOf(nextId++), status: 'sent', confirmed: true])
            }
            if (x.requestMethod == 'GET' && (m = path =~ /^\/offers\/(\d+)$/)) {
                return reply(200, [ok: true, offerId: m[0][1], status: 'active', isOurOffer: true])
            }
            if (x.requestMethod == 'GET' && path == '/inventory') {
                return reply(200, [ok: true, appId: 590830, count: 0, items: []])
            }
            reply(404, [ok: false, error: 'not_found'])
        }
    }
}
