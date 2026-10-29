package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.repository.ItemRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Two first listings of a brand-new skin racing each other each auto-create
 * a catalogue row of the same name. The name lookup behind /api/steam/list
 * must still answer (the oldest row) instead of throwing on every later
 * listing of that skin.
 */
@SpringBootTest
@ActiveProfiles("test")
class DuplicateCatalogueNameDbSpec extends Specification {

    @Autowired ItemRepository itemRepo

    def "name lookup returns the oldest of duplicate catalogue rows"() {
        given:
        def name = "Dup Hat ${System.nanoTime()}"
        def first = itemRepo.save(new Item(name: name, category: 'Hats', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: 1G, iconEmoji: 'x'))
        itemRepo.save(new Item(name: name, category: 'Hats', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: 1G, iconEmoji: 'x'))

        expect:
        itemRepo.findByNameIgnoreCase(name.toUpperCase())?.id == first.id
        itemRepo.findByNameIgnoreCase('no such item ' + name) == null
    }
}
