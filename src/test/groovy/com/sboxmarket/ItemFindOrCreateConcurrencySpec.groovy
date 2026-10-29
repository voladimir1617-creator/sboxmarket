package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.service.ItemService
import spock.lang.Specification

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Two first listings of a brand-new item name at the same moment used to
 * create two catalogue rows (items has no unique name constraint). Both
 * callers must end up with the same single row.
 */
class ItemFindOrCreateConcurrencySpec extends Specification {

    def "two threads listing a brand-new name at once create one Item row"() {
        given:
        def rows = new CopyOnWriteArrayList<Item>()
        def ids = new AtomicLong(100)
        // Both threads miss on their first lookup together: this is the
        // window in which each used to go on and create its own row.
        def bothLookedUp = new CountDownLatch(2)
        def lookups = new AtomicLong(0)
        ItemRepository repo = Stub() {
            findByNameIgnoreCase(_) >> { String n ->
                def hit = rows.find { it.name.equalsIgnoreCase(n) }
                if (lookups.incrementAndGet() <= 2) {
                    bothLookedUp.countDown()
                    bothLookedUp.await(5, TimeUnit.SECONDS)
                }
                hit
            }
            save(_) >> { Item i ->
                Thread.sleep(50)   // a slow insert widens the race
                i.id = ids.incrementAndGet()
                rows << i
                i
            }
        }
        def service = new ItemService(itemRepository: repo)
        def results = new CopyOnWriteArrayList<Item>()
        def errors = new CopyOnWriteArrayList<Throwable>()
        def threads = (1..2).collect {
            Thread.start {
                try {
                    results << service.findOrCreateByName('Brand New Hat') { new Item(name: 'Brand New Hat') }
                } catch (Throwable t) { errors << t }
            }
        }

        when:
        threads*.join(10000)

        then:
        errors.isEmpty()
        rows.size() == 1
        results.size() == 2
        results*.id.unique() == [rows[0].id]
    }

    def "an existing name is returned without creating anything"() {
        given:
        def existing = new Item(id: 7L, name: 'Wizard Hat')
        ItemRepository repo = Mock()
        def service = new ItemService(itemRepository: repo)

        when:
        def r = service.findOrCreateByName('wizard hat') { new Item(name: 'wizard hat') }

        then:
        1 * repo.findByNameIgnoreCase('wizard hat') >> existing
        0 * repo.save(_)
        r.is(existing)
    }
}
