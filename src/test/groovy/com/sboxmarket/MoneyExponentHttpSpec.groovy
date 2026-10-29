package com.sboxmarket

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification
import spock.lang.Timeout

import java.util.concurrent.TimeUnit

/**
 * HTTP-level regression for the BigDecimal exponent DoS (audit
 * 2026-10-29, findings 1 + 5). Each money endpoint that turns a client
 * string into a BigDecimal used to accept `"1e-100000000"` — a 12-char,
 * positive, sub-$1 value that passes every range check — and then call
 * `setScale(2, HALF_UP)` on it, which builds 10^100000000 and pins the
 * Tomcat thread for minutes. Post-fix every site goes through MoneyParse
 * and answers 400 before any arithmetic.
 *
 * Every feature carries a 1-second @Timeout: pre-fix these requests ran
 * for minutes, so the timeout is what fails, not just the status check.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MoneyExponentHttpSpec extends Specification {

    static final String EVIL = '1e-100000000'

    @Autowired ApplicationContext ctx

    MockMvc             mockMvc
    SteamUserRepository userRepo
    ItemRepository      itemRepo
    ListingRepository   listingRepo

    def setup() {
        mockMvc     = ctx.getBean(MockMvc)
        userRepo    = ctx.getBean(SteamUserRepository)
        itemRepo    = ctx.getBean(ItemRepository)
        listingRepo = ctx.getBean(ListingRepository)
        // Warm the dispatcher outside the timed feature body.
        mockMvc.perform(MockMvcRequestBuilders.get('/api/trade-protection/quote').param('price', '5')).andReturn()
    }

    private SteamUser newUser(String role = null) {
        def uniq = String.valueOf(System.nanoTime())
        userRepo.save(new SteamUser(
            steamId64:   '7656119' + uniq.substring(uniq.length() - 10),
            displayName: 'ExpUser-' + uniq,
            role:        role
        ))
    }

    private MockHttpSession sessionFor(SteamUser u) {
        def s = new MockHttpSession()
        s.setAttribute(SteamAuthController.SESSION_USER_ID, u.id)
        s
    }

    private MvcResult send(MockHttpServletRequestBuilder b, MockHttpSession s, String json) {
        mockMvc.perform(b.session(s).contentType(MediaType.APPLICATION_JSON).content(json)).andReturn()
    }

    private static void assertRejected(MvcResult r, String code) {
        assert r.response.status == 400
        assert r.response.contentAsString.contains(code)
    }

    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    def "POST /api/offers/{id}/raise refuses a huge-exponent amount fast"() {
        given:
        def s = sessionFor(newUser())

        when:
        def r = send(MockMvcRequestBuilders.post('/api/offers/999999/raise'), s, """{"amount":"${EVIL}"}""")

        then:
        assertRejected(r, 'INVALID_AMOUNT')
    }

    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    def "POST /api/offers/{id}/counter refuses a huge-exponent amount fast"() {
        given:
        def s = sessionFor(newUser())

        when:
        def r = send(MockMvcRequestBuilders.post('/api/offers/999999/counter'), s, """{"amount":"${EVIL}"}""")

        then:
        assertRejected(r, 'INVALID_AMOUNT')
    }

    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    def "PUT /api/buy-orders/{id} refuses a huge-exponent maxPrice fast"() {
        given:
        def s = sessionFor(newUser())

        when:
        def r = send(MockMvcRequestBuilders.put('/api/buy-orders/999999'), s, """{"maxPrice":"${EVIL}"}""")

        then:
        assertRejected(r, 'INVALID_PRICE')
    }

    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    def "PUT /api/listings/{id}/stall refuses a huge-exponent price fast"() {
        given: "an active listing the caller owns"
        def seller = newUser()
        def uniq = String.valueOf(System.nanoTime())
        def item = itemRepo.save(new Item(name: "ExpItem-${uniq}", category: 'Hats', rarity: 'Standard',
            supply: 10, totalSold: 0, lowestPrice: new BigDecimal('10.00')))
        def listing = listingRepo.save(new Listing(item: item, price: new BigDecimal('10.00'),
            sellerName: seller.displayName, sellerAvatar: 'EX', status: 'ACTIVE',
            rarityScore: BigDecimal.ZERO, sellerUserId: seller.id))
        def s = sessionFor(seller)

        when:
        def r = send(MockMvcRequestBuilders.put("/api/listings/${listing.id}/stall"), s, """{"price":"${EVIL}"}""")

        then:
        assertRejected(r, 'INVALID_PRICE')
        listingRepo.findById(listing.id).get().price == new BigDecimal('10.00')
    }

    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    def "PUT /api/listings/my-stall/bulk-adjust refuses a huge-exponent percent fast"() {
        given:
        def s = sessionFor(newUser())

        when:
        def r = send(MockMvcRequestBuilders.put('/api/listings/my-stall/bulk-adjust'), s, """{"percent":"${EVIL}"}""")

        then:
        assertRejected(r, 'INVALID_PERCENT')
    }

    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    def "POST /api/steam/list refuses a huge positive exponent price fast"() {
        given: "1e100000000 passes the \$0.01 floor, which ran before setScale"
        def s = sessionFor(newUser())

        when:
        def r = send(MockMvcRequestBuilders.post('/api/steam/list'), s, """{"assetId":"1001","price":"1e100000000"}""")

        then:
        assertRejected(r, 'INVALID_PRICE')
    }

    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    def "POST /api/steam/list-bulk refuses a huge-exponent price fast"() {
        given:
        def s = sessionFor(newUser())

        when:
        def r = send(MockMvcRequestBuilders.post('/api/steam/list-bulk'), s, """{"assetIds":["1001"],"price":"1e100000000"}""")

        then:
        assertRejected(r, 'INVALID_PRICE')
    }

    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    def "POST /api/csr/users/{id}/goodwill refuses a huge-exponent amount fast"() {
        given:
        def target = newUser()
        def s = sessionFor(newUser('CSR'))

        when:
        def r = send(MockMvcRequestBuilders.post("/api/csr/users/${target.id}/goodwill"), s,
            """{"amount":"${EVIL}","note":"exp test"}""")

        then:
        assertRejected(r, 'INVALID_AMOUNT')
    }

    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    def "POST /api/admin/users/{id}/credit refuses a huge-exponent amount fast"() {
        given:
        def target = newUser()
        def s = sessionFor(newUser('ADMIN'))

        when:
        def r = send(MockMvcRequestBuilders.post("/api/admin/users/${target.id}/credit"), s,
            """{"amount":"${EVIL}","note":"exp test"}""")

        then:
        assertRejected(r, 'INVALID_AMOUNT')
    }

    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    def "POST /api/admin/deposits/{id}/refund refuses a huge-exponent amount fast"() {
        given:
        def s = sessionFor(newUser('ADMIN'))

        when:
        def r = send(MockMvcRequestBuilders.post('/api/admin/deposits/999999/refund'), s, """{"amount":"${EVIL}"}""")

        then:
        assertRejected(r, 'INVALID_AMOUNT')
    }

    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    def "GET /api/trade-protection/quote refuses a short huge-exponent price fast (unauthenticated)"() {
        when: "12 chars — under the old 20-char cap, which alone did not stop it"
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/trade-protection/quote').param('price', EVIL)).andReturn()

        then:
        assertRejected(r, 'INVALID_PRICE')
    }

    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    def "POST /api/listings/sell (Jackson DTO) refuses a zero maxDiscount with a huge scale fast"() {
        given: "@DecimalMin('0.00') lets 0e-100000000 through bean validation"
        def s = sessionFor(newUser())

        when:
        def r = send(MockMvcRequestBuilders.post('/api/listings/sell'), s,
            """{"listingId":999999,"price":5,"maxDiscount":"0e-100000000"}""")

        then: "SellService's scale guard answers before any setScale / lookup"
        assertRejected(r, 'INVALID_DISCOUNT')
    }

    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    def "Jackson-bound DTO money fields with a huge exponent are refused by bean validation fast"() {
        given: "CreateOfferRequest.amount is @DecimalMin(0.01)/@DecimalMax(100000)"
        def s = sessionFor(newUser())

        when:
        def r = send(MockMvcRequestBuilders.post('/api/offers'), s,
            """{"listingId":999999,"amount":"${EVIL}"}""")

        then: "rejected at bean validation (below the 0.01 floor), never reaching a setScale"
        assertRejected(r, 'VALIDATION_FAILED')
        r.response.contentAsString.contains('amount')
    }
}
