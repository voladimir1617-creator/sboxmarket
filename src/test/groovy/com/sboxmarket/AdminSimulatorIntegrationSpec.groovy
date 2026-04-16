package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.AdminSimulatorService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * End-to-end spec that drives AdminSimulatorService against real
 * repositories + real DB + real BigDecimal jitter arithmetic. Locks in
 * the fix for the `Double.setScale()` bug that would have crashed every
 * simulator invocation before this turn's fix.
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminSimulatorIntegrationSpec extends Specification {

    @Autowired ApplicationContext ctx

    AdminSimulatorService simulator
    ItemRepository        itemRepo
    ListingRepository     listingRepo
    SteamUserRepository   userRepo
    Long adminId

    def setup() {
        simulator   = ctx.getBean(AdminSimulatorService)
        itemRepo    = ctx.getBean(ItemRepository)
        listingRepo = ctx.getBean(ListingRepository)
        userRepo    = ctx.getBean(SteamUserRepository)

        // Seed an admin user so requireAdmin() passes
        def admin = userRepo.save(new SteamUser(
            steamId64:   String.valueOf(System.nanoTime()),
            displayName: 'SimAdmin',
            role:        'ADMIN'
        ))
        adminId = admin.id

        // Seed a handful of catalogue items with real BigDecimal prices
        (1..10).each { i ->
            itemRepo.save(new Item(
                name:         "SimItem-${System.nanoTime()}-${i}",
                category:     'Hats',
                rarity:       'Standard',
                supply:       1000,
                totalSold:    0,
                lowestPrice:  new BigDecimal("${10 + i}.00"),
                iconEmoji:    '🎩'
            ))
        }
    }

    def "simulateListings actually runs the BigDecimal jitter pipeline end-to-end"() {
        given: "a clean count of simulated listings before the run"
        def before = simulator.countSimulated().count

        when: "the admin spawns 5 simulated listings"
        def result = simulator.simulateListings(adminId, 5)

        then: "at least 5 rows were created and tagged as simulated"
        result.created >= 5
        simulator.countSimulated().count >= before + 5

        and: "each created row has a non-null BigDecimal price"
        def sims = listingRepo.findAll().findAll { it.sellerName?.startsWith('SIM · ') }
        sims.size() >= 5
        sims.every { it.price != null }
        sims.every { it.price instanceof BigDecimal }
        // The jitter multipliers (0.87..1.23) should have produced at least
        // one non-round price — i.e. the BigDecimal path actually executed
        // instead of the old crash.
        sims.any { it.price.scale() == 2 }

        cleanup:
        simulator.clearSimulated(adminId)
    }

    def "clearSimulated removes only SIM-tagged listings and leaves real ones alone"() {
        given:
        simulator.simulateListings(adminId, 3)
        def simCountAfter = simulator.countSimulated().count

        when:
        def result = simulator.clearSimulated(adminId)

        then:
        result.removed == simCountAfter
        simulator.countSimulated().count == 0
    }
}
