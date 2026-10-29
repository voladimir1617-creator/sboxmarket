package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.service.AdminSimulatorService
import com.sboxmarket.service.security.AdminAuthorization
import org.springframework.mock.env.MockEnvironment
import spock.lang.Specification
import spock.lang.Subject

/**
 * AdminSimulatorService spawns fake listings for QA. The spec asserts:
 *   - admin-only gate
 *   - bounded count (1..100)
 *   - "empty catalogue" guard so an operator doesn't sleepwalk into a
 *     broken state
 *   - simulated rows are tagged with "SIM · " so clearSimulated can
 *     find them later
 *   - clearSimulated only deletes tagged rows, never real ones
 */
class AdminSimulatorServiceSpec extends Specification {

    ItemRepository     itemRepository     = Mock()
    ListingRepository  listingRepository  = Mock()
    AdminAuthorization adminAuthorization = Mock()

    @Subject
    AdminSimulatorService service = new AdminSimulatorService(
        itemRepository    : itemRepository,
        listingRepository : listingRepository,
        adminAuthorization: adminAuthorization,
        // A local/dev deployment: no prod profile, no Stripe key (SIMULATED).
        // The simulator now refuses on anything that can move real money,
        // and a missing Environment counts as real money (fail-closed).
        environment       : new MockEnvironment()
    )

    private Item catItem(long id, String name, String cat, BigDecimal price) {
        new Item(id: id, name: name, category: cat, lowestPrice: price, rarity: 'Standard')
    }

    def "simulateListings refuses zero/negative count"() {
        when:
        service.simulateListings(1L, count)

        then:
        1 * adminAuthorization.requireAdmin(1L)
        thrown(BadRequestException)

        where:
        count << [0, -1, 101]
    }

    def "simulateListings refuses when the catalogue is empty"() {
        given:
        itemRepository.findAll() >> []

        when:
        service.simulateListings(1L, 5)

        then:
        thrown(BadRequestException)
    }

    def "simulateListings refuses when all items have no price"() {
        given:
        itemRepository.findAll() >> [
            catItem(1L, 'x', 'Hats', null),
            catItem(2L, 'y', 'Hats', BigDecimal.ZERO),
        ]

        when:
        service.simulateListings(1L, 5)

        then:
        thrown(BadRequestException)
    }

    def "simulateListings creates at least `count` rows when the catalogue is big enough"() {
        given:
        def items = (1..20).collect { i -> catItem(i as long, "Item $i", 'Hats', new BigDecimal("10")) }
        itemRepository.findAll() >> items
        def saved = []
        listingRepository.save(_) >> { args -> def l = args[0]; l.id = saved.size() + 1L; saved << l; l }

        when:
        def result = service.simulateListings(1L, 5)

        then:
        result.created >= 5
        saved.size() >= 5
        // every saved row is tagged as simulated
        saved.every { it.sellerName?.startsWith('SIM · ') }
        saved.every { it.description?.startsWith('[SIMULATED]') }
        saved.every { it.sellerUserId == null }
    }

    // ── clearSimulated ────────────────────────────────────────────

    def "clearSimulated removes only the rows findSimulated returned"() {
        given:
        def sim1 = new Listing(id: 3L, sellerName: 'SIM · QA_Buyer', description: '[SIMULATED] x')
        def sim2 = new Listing(id: 4L, sellerName: 'SIM · DemoRunner', description: '[SIMULATED] y')
        listingRepository.findSimulated() >> [sim1, sim2]

        when:
        def result = service.clearSimulated(1L)

        then:
        1 * adminAuthorization.requireAdmin(1L)
        1 * listingRepository.deleteAll({ List<Listing> list -> list*.id.sort() == [3L, 4L] })
        result.removed == 2
    }

    def "clearSimulated delegates to the indexed JPQL tag filter (no full-table scan)"() {
        when:
        def result = service.clearSimulated(1L)

        then:
        1 * listingRepository.findSimulated() >> [
            new Listing(id: 9L, sellerName: 'Legit', description: '[SIMULATED] edge case')
        ]
        0 * listingRepository.findAll()
        result.removed == 1
    }

    // ── countSimulated ────────────────────────────────────────────

    def "countSimulated returns the SQL-aggregate count"() {
        given:
        listingRepository.countSimulated() >> 2L

        when:
        def result = service.countSimulated()

        then:
        result.count == 2L
        0 * listingRepository.findAll()
    }

    def "countSimulated returns zero when nothing is tagged"() {
        given:
        listingRepository.countSimulated() >> 0L

        when:
        def result = service.countSimulated()

        then:
        result.count == 0L
    }

    // ── real-money refusal (audit 2026-10-29 finding 3) ───────────
    // Simulated rows are seller-less "house" listings a real buyer can pay
    // real balance for, and clear hard-deletes by a name/description LIKE.
    // Neither may run where real money moves.

    private AdminSimulatorService serviceWith(MockEnvironment env) {
        new AdminSimulatorService(
            itemRepository    : itemRepository,
            listingRepository : listingRepository,
            adminAuthorization: adminAuthorization,
            environment       : env)
    }

    private static MockEnvironment prodEnv() {
        def env = new MockEnvironment()
        env.setActiveProfiles('prod')
        env
    }

    private static MockEnvironment liveKeyEnv() {
        new MockEnvironment().withProperty('stripe.secret-key', 'sk_live_' + ('x' * 24))
    }

    def "simulateListings is refused under the prod profile and creates nothing"() {
        given:
        def svc = serviceWith(env)
        itemRepository.findAll() >> [catItem(1L, 'Hat', 'Hats', new BigDecimal('10'))]

        when:
        svc.simulateListings(1L, 5)

        then:
        1 * adminAuthorization.requireAdmin(1L)
        def e = thrown(ForbiddenException)
        e.code == 'SIMULATOR_DISABLED'
        0 * listingRepository.save(_)

        where:
        env << [prodEnv(), liveKeyEnv()]
    }

    def "clearSimulated is refused under prod / a live key and deletes nothing"() {
        given:
        def svc = serviceWith(env)

        when:
        svc.clearSimulated(1L)

        then:
        1 * adminAuthorization.requireAdmin(1L)
        def e = thrown(ForbiddenException)
        e.code == 'SIMULATOR_DISABLED'
        0 * listingRepository.findSimulated()
        0 * listingRepository.deleteAll(_)

        where:
        env << [prodEnv(), liveKeyEnv()]
    }

    def "prod with a TEST key is still refused (MoneyMode treats it as INDETERMINATE)"() {
        given:
        def env = prodEnv()
        env.setProperty('stripe.secret-key', 'sk_test_' + ('x' * 24))
        def svc = serviceWith(env)

        when:
        svc.clearSimulated(1L)

        then:
        thrown(ForbiddenException)
        0 * listingRepository.deleteAll(_)
    }

    def "a service with no Environment wired fails closed"() {
        given:
        def svc = new AdminSimulatorService(
            itemRepository: itemRepository, listingRepository: listingRepository,
            adminAuthorization: adminAuthorization)

        expect:
        svc.refusalReason() == AdminSimulatorService.REFUSAL_REAL_MONEY
    }

    def "a local SIMULATED deployment (no prod, no key) is allowed"() {
        expect:
        service.refusalReason() == null
    }
}
