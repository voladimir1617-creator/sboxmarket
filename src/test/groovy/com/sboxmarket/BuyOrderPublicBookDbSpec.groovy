package com.sboxmarket

import com.fasterxml.jackson.databind.ObjectMapper
import com.sboxmarket.model.BuyOrder
import com.sboxmarket.model.Item
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.BuyOrderRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.SteamUserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification

/**
 * The public buy-order book against the real query: price levels grouped
 * from ACTIVE item orders, best first, with no buyer identity in the
 * response, and paused, empty and banned-buyer orders left out.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BuyOrderPublicBookDbSpec extends Specification {

    @Autowired MockMvc mockMvc
    @Autowired SteamUserRepository userRepo
    @Autowired BuyOrderRepository buyOrderRepo
    @Autowired ItemRepository itemRepo
    @Autowired ObjectMapper objectMapper

    String uniq = String.valueOf(System.nanoTime())
    int seq = 0

    private SteamUser user(String name, boolean banned = false) {
        def tail = (uniq + (seq++)).reverse().take(9).reverse()
        userRepo.save(new SteamUser(steamId64: '7656119' + tail.padLeft(10, '0'),
            displayName: "${name}-${uniq}", role: 'USER', banned: banned))
    }

    private Item item(String name) {
        itemRepo.save(new Item(name: "${name} ${uniq}", category: 'Accessories', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal('10'), isListed: true, iconEmoji: '🎩'))
    }

    private BuyOrder order(SteamUser u, Item i, String max, int qty, String status = 'ACTIVE') {
        buyOrderRepo.save(new BuyOrder(buyerUserId: u.id, buyerName: u.displayName, itemId: i.id,
            itemName: i.name, maxPrice: new BigDecimal(max), quantity: qty, originalQuantity: Math.max(qty, 1),
            status: status))
    }

    def "GET /api/buy-orders/book/{id} groups live orders into price levels, best first, anonymously"() {
        given:
        SteamUser a = user('alice')
        SteamUser b = user('bob')
        SteamUser c = user('carl')
        SteamUser banned = user('mallory', true)
        Item book = item('Book')
        order(a, book, '9.00', 2)
        order(b, book, '9.00', 1)
        order(c, book, '8.50', 3)
        order(c, book, '9.50', 1, 'PAUSED')    // paused: not live demand
        order(a, book, '9.75', 0)              // filled out: nothing left to buy
        order(banned, book, '9.90', 5)         // banned buyer: can't transact

        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get("/api/buy-orders/book/${book.id}")).andReturn()
        def body = r.response.contentAsString
        def levels = objectMapper.readValue(body, List)

        then:
        r.response.status == 200
        r.response.getHeader('Cache-Control').contains('public')
        levels.size() == 2
        new BigDecimal(levels[0].price.toString()) == new BigDecimal('9.00')
        levels[0].orders == 2
        levels[0].quantity == 3
        new BigDecimal(levels[1].price.toString()) == new BigDecimal('8.50')
        levels[1].orders == 1
        levels[1].quantity == 3
        // Only the three aggregate fields, nothing that names or numbers a buyer.
        levels.every { Map l -> l.keySet() == ['price', 'orders', 'quantity'] as Set }
        !body.contains('alice') && !body.contains('bob') && !body.contains('carl') && !body.contains('mallory')
        !body.contains('buyer')
    }

    def "an item nobody wants has an empty book, and the limit caps the levels"() {
        given:
        SteamUser a = user('dana')
        Item empty = item('Empty')
        Item deep = item('Deep')
        (1..5).each { n -> order(a, deep, "${n}.00", 1) }

        when:
        def none = objectMapper.readValue(mockMvc.perform(
            MockMvcRequestBuilders.get("/api/buy-orders/book/${empty.id}")).andReturn().response.contentAsString, List)
        def two = objectMapper.readValue(mockMvc.perform(
            MockMvcRequestBuilders.get("/api/buy-orders/book/${deep.id}").param('limit', '2')).andReturn().response.contentAsString, List)

        then:
        none == []
        two.size() == 2
        two*.price.collect { new BigDecimal(it.toString()) } == [new BigDecimal('5.00'), new BigDecimal('4.00')]
    }

    def "GET /api/buy-orders/public rolls the same levels up per item: best price, orders, quantity, no buyer"() {
        given: "prices high enough to sit at the top of a shared test database"
        SteamUser a = user('erin')
        SteamUser b = user('fred')
        SteamUser banned = user('trudy', true)
        Item hot = item('Hot')
        Item warm = item('Warm')
        Item onlyBanned = item('OnlyBanned')
        order(a, hot, '99000.00', 2)
        order(b, hot, '98000.00', 1)
        order(b, hot, '98000.00', 4)
        order(a, warm, '97000.00', 1)
        order(banned, onlyBanned, '99500.00', 1)

        when:
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/buy-orders/public')).andReturn()
        def body = r.response.contentAsString
        def rows = objectMapper.readValue(body, List)
        def mine = rows.findAll { (it.itemId as Long) in [hot.id, warm.id, onlyBanned.id] }

        then:
        r.response.status == 200
        r.response.getHeader('Cache-Control').contains('public')
        mine*.itemId.collect { it as Long } == [hot.id, warm.id]       // best price first; banned-only demand is no demand
        new BigDecimal(mine[0].bestPrice.toString()) == new BigDecimal('99000.00')
        mine[0].orders == 3
        mine[0].quantity == 7
        mine[0].itemName == hot.name
        mine[0].lowestListing == null              // nothing listed for it
        mine[1].orders == 1
        rows.every { Map row -> !row.containsKey('buyerUserId') && !row.containsKey('buyerName') && !row.containsKey('id') }
        !body.contains('erin') && !body.contains('fred') && !body.contains('trudy')
    }

    def "the public board honours its limit"() {
        given:
        SteamUser a = user('gina')
        (1..3).each { n -> order(a, item("Lim${n}"), "9${n}00.00", 1) }

        expect:
        objectMapper.readValue(mockMvc.perform(MockMvcRequestBuilders.get('/api/buy-orders/public').param('limit', '2'))
            .andReturn().response.contentAsString, List).size() == 2
    }
}
