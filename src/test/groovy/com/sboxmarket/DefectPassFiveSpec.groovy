package com.sboxmarket

import com.sboxmarket.controller.ApiKeyController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.ApiKey
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Loadout
import com.sboxmarket.model.LoadoutSlot
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.SupportTicket
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.LoadoutRepository
import com.sboxmarket.repository.LoadoutSlotRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.SupportMessageRepository
import com.sboxmarket.repository.SupportTicketRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.ApiKeyService
import com.sboxmarket.service.CsrService
import com.sboxmarket.service.ItemService
import com.sboxmarket.service.LoadoutService
import com.sboxmarket.service.SupportService
import com.sboxmarket.service.TextSanitizer
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification

/**
 * Defect pass 5: support tickets, loadouts, API keys, the item page's
 * sales figures and the /db price filters.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DefectPassFiveSpec extends Specification {

    @Autowired MockMvc mockMvc
    @Autowired SupportService supportService
    @Autowired CsrService csrService
    @Autowired AdminService adminService
    @Autowired LoadoutService loadoutService
    @Autowired ItemService itemService
    @Autowired SteamUserRepository userRepo
    @Autowired SupportTicketRepository ticketRepo
    @Autowired SupportMessageRepository messageRepo
    @Autowired LoadoutRepository loadoutRepo
    @Autowired LoadoutSlotRepository slotRepo
    @Autowired ItemRepository itemRepo
    @Autowired ListingRepository listingRepo

    static int seq = 0
    String uniq = String.valueOf(System.nanoTime())

    private SteamUser user(String role = 'USER') {
        seq++
        def tail = (uniq + seq).reverse().take(9).reverse()
        userRepo.save(new SteamUser(steamId64: '7656119' + tail.padLeft(10, '0'),
            displayName: "Pass5-${role}-${seq}-${uniq}", role: role))
    }

    private Object json(String path) {
        def r = mockMvc.perform(MockMvcRequestBuilders.get(path)
            .with({ req -> req.remoteAddr = "10.55.${seq % 250}.${(seq++ % 250) + 1}".toString(); req }
                as org.springframework.test.web.servlet.request.RequestPostProcessor)).andReturn()
        assert r.response.status == 200
        new JsonSlurper().parseText(r.response.contentAsString)
    }

    // ── Support ───────────────────────────────────────────────────

    def "a new ticket waits on staff, so the CSR queue shows it and the sweeper leaves it alone"() {
        given:
        def u = user()
        def csr = user('CSR')

        when:
        def t = supportService.create(u.id, u.displayName, "Pass5 stuck ${uniq}", 'PAYMENT', 'Help please')

        then:
        ticketRepo.findById(t.id).get().status == 'WAITING_STAFF'
        csrService.listTickets('WAITING_STAFF', "Pass5 stuck ${uniq}")*.id.contains(t.id)
    }

    def "staff replies keep their line breaks"() {
        given:
        def u = user()
        def csr = user('CSR')
        def t = supportService.create(u.id, u.displayName, "Pass5 lines ${uniq}", 'OTHER', 'Question')

        when:
        def msg = csrService.reply(csr.id, t.id, "Step 1: open Wallet\nStep 2: press Withdraw")

        then:
        msg.body == "Step 1: open Wallet\nStep 2: press Withdraw"
    }

    def "an admin cannot reply into a resolved ticket"() {
        given:
        def u = user()
        def admin = user('ADMIN')
        def t = ticketRepo.save(new SupportTicket(userId: u.id, username: u.displayName,
            subject: "Pass5 closed ${uniq}", category: 'OTHER', status: 'RESOLVED'))

        when:
        adminService.staffReply(admin.id, t.id, 'late reply')

        then:
        def e = thrown(BadRequestException)
        e.code == 'ALREADY_RESOLVED'
        ticketRepo.findById(t.id).get().status == 'RESOLVED'
    }

    def "CSR ticket search treats _ as a literal character"() {
        given:
        def u = user()
        def hit  = ticketRepo.save(new SupportTicket(userId: u.id, username: u.displayName,
            subject: "p5${uniq}a_b", category: 'OTHER', status: 'WAITING_STAFF'))
        def miss = ticketRepo.save(new SupportTicket(userId: u.id, username: u.displayName,
            subject: "p5${uniq}axb", category: 'OTHER', status: 'WAITING_STAFF'))

        when:
        def ids = csrService.listTickets('', "p5${uniq}a_b")*.id

        then:
        ids.contains(hit.id)
        !ids.contains(miss.id)
    }

    def "TextSanitizer.multiline keeps lines, clamps blank runs and still strips markup"() {
        expect:
        TextSanitizer.multiline(new TextSanitizer(), "a\r\n\n\n\nb <script>x</script>") ==~ /a\n\nb(?!.*<script).*/
    }

    // ── Loadouts ──────────────────────────────────────────────────

    def "loadout Discover search matches _ literally and survives a trailing backslash"() {
        given:
        def u = user()
        def tag = "p5${uniq}"
        loadoutRepo.save(new Loadout(ownerUserId: u.id, ownerName: 'o', name: "${tag}a_b", visibility: 'PUBLIC'))
        loadoutRepo.save(new Loadout(ownerUserId: u.id, ownerName: 'o', name: "${tag}axb", visibility: 'PUBLIC'))

        expect:
        json("/api/loadouts/discover?search=${tag}a_b")*.name == ["${tag}a_b"]
        json("/api/loadouts/discover?search=${tag}a%5C") == []
    }

    def "clearing a locked slot drops the lock"() {
        given:
        def u = user()
        def item = itemRepo.save(new Item(name: "Pass5 hat ${uniq}", category: 'Hats', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal('2.00'), isListed: true, iconEmoji: '🎩'))
        def lo = loadoutService.create(u.id, u.displayName, "Pass5 lock ${uniq}", null, 'PRIVATE')
        loadoutService.setSlot(u.id, lo.id, 'Hats', item.id)
        def s = slotRepo.findByLoadout(lo.id).find { it.slot == 'Hats' }
        s.locked = true
        slotRepo.save(s)

        when:
        loadoutService.setSlot(u.id, lo.id, 'Hats', null)

        then:
        def after = slotRepo.findByLoadout(lo.id).find { it.slot == 'Hats' }
        after.itemId == null
        after.locked == false
    }

    // ── Item page sales figures ───────────────────────────────────

    def "an auction sale shows the winning bid on recent sales, last sold and 30d volume"() {
        given:
        def item = itemRepo.save(new Item(name: "Pass5 auction ${uniq}", category: 'Hats', rarity: 'Standard',
            supply: 1, totalSold: 1, lowestPrice: BigDecimal.ZERO, isListed: false, iconEmoji: '🎩'))
        listingRepo.save(new Listing(item: item, price: new BigDecimal('1.00'), currentBid: new BigDecimal('25.00'),
            listingType: 'AUCTION', status: 'SOLD', soldAt: System.currentTimeMillis() - 60_000L,
            sellerName: 'p5-seller', rarityScore: BigDecimal.ZERO))

        when:
        def sales = json("/api/items/${item.id}/recent-sales")
        def vel   = json("/api/items/${item.id}/velocity")

        then:
        new BigDecimal(sales[0].price.toString()) == new BigDecimal('25.00')
        new BigDecimal(vel.lastSoldPrice.toString()) == new BigDecimal('25.00')
        new BigDecimal(vel.volumeLast30d.toString()) == new BigDecimal('25.00')
    }

    // ── /db and /api/items price filters ──────────────────────────

    def "unlisted (zero-price) items fall outside price bands and sort after priced ones"() {
        given:
        def tag = "Pass5db${uniq}"
        itemRepo.save(new Item(name: "${tag} unlisted", category: 'Hats', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: BigDecimal.ZERO, isListed: false, iconEmoji: '🎩'))
        itemRepo.save(new Item(name: "${tag} priced", category: 'Hats', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal('3.00'), isListed: true, iconEmoji: '🎩'))

        when:
        def banded = json("/api/database?q=${tag}&maxPrice=5")
        def sorted = json("/api/database?q=${tag}&sort=price_asc")
        def svc    = itemService.search(tag, null, null, 'price_asc', null, new BigDecimal('5'))

        then:
        rows(banded)*.name == ["${tag} priced"]
        rows(sorted)*.name == ["${tag} priced", "${tag} unlisted"]
        svc*.name == ["${tag} priced"]
    }

    private static List rows(Object body) {
        body instanceof List ? body : (body.items ?: body.content ?: body.rows)
    }

    // ── API keys ──────────────────────────────────────────────────

    def "the API key list never pushes a live key out for revoked history"() {
        given:
        def live = new ApiKey(id: 1L, userId: 9L, revoked: false, createdAt: 1L)
        def revoked = (2..60).collect { new ApiKey(id: it as Long, userId: 9L, revoked: true, createdAt: it as Long) }
        def svc = Mock(ApiKeyService) { listForUser(9L) >> (revoked.reverse() + [live]) }
        def controller = new ApiKeyController(apiKeyService: svc)
        def req = new MockHttpServletRequest()
        def session = new MockHttpSession()
        session.setAttribute(SteamAuthController.SESSION_USER_ID, 9L)
        req.session = session

        when:
        def body = controller.list(req).body

        then:
        body.size() == 50
        body*.id.contains(1L)
    }
}
