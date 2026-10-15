package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.LoadoutRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Defect pass 38: Loadout Lab's Generate promises the cheapest active
 * listing, but its pick also took items nobody sells here, whose lowestPrice
 * the Steam price sync fills with Steam's price.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefectPassThirtyEightSpec extends Specification {

    @Autowired ItemRepository itemRepo
    @Autowired LoadoutRepository loadoutRepo

    String uniq = String.valueOf(System.nanoTime())

    def "Generate's pick skips items that are not listed"() {
        given:
        def category = "Pass38-${uniq}"
        itemRepo.save(new Item(name: "Unlisted ${uniq}", category: category, rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal('1.00'), isListed: false, iconEmoji: '🎩'))
        def listed = itemRepo.save(new Item(name: "Listed ${uniq}", category: category, rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal('2.00'), isListed: true, iconEmoji: '🎩'))

        when:
        def picks = loadoutRepo.findCheapestInBudgetExcluding(category, new BigDecimal('10.00'), [-1L], PageRequest.of(0, 5))

        then:
        picks*.id == [listed.id]
    }
}
