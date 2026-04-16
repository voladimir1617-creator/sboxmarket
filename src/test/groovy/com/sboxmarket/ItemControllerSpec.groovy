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

    def "GET /api/items/{id} returns 404 for missing item"() {
        when:
        def response = rest.getForEntity("http://localhost:$port/api/items/99999", Map)

        then:
        response.statusCode == HttpStatus.NOT_FOUND
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
}
