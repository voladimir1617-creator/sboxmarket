package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.service.ItemService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.Subject

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ItemControllerSpec extends Specification {

    @LocalServerPort int port
    @Autowired TestRestTemplate rest
    @Autowired ItemRepository itemRepository
    @Subject @Autowired ItemService itemService

    def "GET /api/items returns 200 with a list"() {
        when:
        def response = rest.getForEntity("http://localhost:$port/api/items", List)

        then:
        response.statusCode == HttpStatus.OK
        response.body instanceof List
    }

    def "GET /api/items?category=Hats returns only hat items"() {
        when:
        def response = rest.getForEntity("http://localhost:$port/api/items?category=Hats", List)

        then:
        response.statusCode == HttpStatus.OK
        response.body.every { it.category == "Hats" }
    }

    def "GET /api/items?rarity=Limited returns only limited items"() {
        when:
        def response = rest.getForEntity("http://localhost:$port/api/items?rarity=Limited", List)

        then:
        response.statusCode == HttpStatus.OK
        response.body.every { it.rarity == "Limited" }
    }

    def "GET /api/items/{id} returns 200 with a notFound sentinel for a missing item"() {
        when:
        def response = rest.getForEntity("http://localhost:$port/api/items/99999", Map)

        then:
        // Contract change: missing item reads now return 200 with
        // `{notFound: true}` instead of 404. Reason: Chrome auto-logs every
        // fetch 404 to the browser console regardless of JS handling, which
        // made every /item/{deadId} landing read as a phantom bug. The SPA's
        // `fetchItem` translates the sentinel back to null.
        response.statusCode == HttpStatus.OK
        response.body?.notFound == true
    }

    def "GET /api/items/batch returns the items for the given ids in one response"() {
        given: "two real catalogue ids"
        def all = itemRepository.findAll().toList()
        // The seeded test catalogue always has at least a couple of items.
        def ids = all.take(2).collect { it.id }

        when:
        def response = rest.getForEntity(
            "http://localhost:$port/api/items/batch?ids=${ids.join(',')}", List)

        then:
        response.statusCode == HttpStatus.OK
        response.body instanceof List
        response.body.size() == ids.size()
        response.body.collect { it.id }.toSet() == ids.toSet()
    }

    def "GET /api/items/batch omits ids that do not resolve instead of a notFound sentinel"() {
        given:
        def real = itemRepository.findAll().toList().first().id

        when: "one real id mixed with a missing id"
        def response = rest.getForEntity(
            "http://localhost:$port/api/items/batch?ids=${real},99999999", List)

        then: "only the real item comes back — the missing id is silently dropped"
        response.statusCode == HttpStatus.OK
        response.body.size() == 1
        response.body[0].id == real
    }

    def "GET /api/items/batch returns an empty list for blank or missing ids"() {
        expect:
        rest.getForEntity("http://localhost:$port/api/items/batch", List).body == []
        rest.getForEntity("http://localhost:$port/api/items/batch?ids=", List).body == []
        rest.getForEntity("http://localhost:$port/api/items/batch?ids=notanumber", List).body == []
    }

    def "GET /api/items/batch caps the number of ids at 50"() {
        given: "more than 50 ids requested"
        def ids = (1..120).collect { it as String }

        when:
        def response = rest.getForEntity(
            "http://localhost:$port/api/items/batch?ids=${ids.join(',')}", List)

        then: "request still succeeds and never returns more than the cap"
        response.statusCode == HttpStatus.OK
        response.body.size() <= 50
    }

    def "GET /api/items/stats returns market stats structure"() {
        when:
        def response = rest.getForEntity("http://localhost:$port/api/items/stats", Map)

        then:
        response.statusCode == HttpStatus.OK
        response.body.containsKey("totalItems")
        response.body.containsKey("floorPrice")
        response.body.containsKey("categories")
    }

    def "GET /api/items/stats exposes lastSyncedAt so the footer can render Catalog-updated-X-ago (batch 956)"() {
        when:
        def response = rest.getForEntity("http://localhost:$port/api/items/stats", Map)

        then:
        response.statusCode == HttpStatus.OK
        response.body.containsKey("lastSyncedAt")
        // Value is a Long (epoch ms) or 0 on first boot. Never null.
        response.body.lastSyncedAt != null
    }
}
