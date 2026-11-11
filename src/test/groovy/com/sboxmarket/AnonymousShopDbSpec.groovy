package com.sboxmarket

import com.fasterxml.jackson.databind.ObjectMapper
import com.sboxmarket.controller.ProfileController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.AnonymousShopService
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
 * Anonymous shop against the real stack: a seller with the setting on is
 * shown under a neutral label on public listing payloads, sees their own
 * listings unmasked, and the stored rows keep the real seller.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnonymousShopDbSpec extends Specification {

    @Autowired MockMvc mockMvc
    @Autowired ObjectMapper objectMapper
    @Autowired SteamUserRepository userRepo
    @Autowired ItemRepository itemRepo
    @Autowired ListingRepository listingRepo
    @Autowired ProfileController profileController

    String uniq = String.valueOf(System.nanoTime())
    int seq = 0

    private SteamUser user(String name, boolean anonymous) {
        def tail = (uniq + (seq++)).reverse().take(9).reverse()
        userRepo.save(new SteamUser(steamId64: '7656119' + tail.padLeft(10, '0'),
            displayName: "${name}-${uniq}", role: 'USER', anonymousShop: anonymous))
    }

    private Item item(String name) {
        itemRepo.save(new Item(name: "${name} ${uniq}", category: 'Hats', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal('5'), isListed: true, iconEmoji: '🎩'))
    }

    private Listing listing(Item i, SteamUser seller, String price) {
        listingRepo.save(new Listing(item: i, sellerUserId: seller.id, sellerName: seller.displayName,
            sellerAvatar: 'XY', price: new BigDecimal(price), status: 'ACTIVE', listingType: 'BUY_NOW',
            hidden: false, assetId: "asset-${uniq}-${seq++}"))
    }

    private Object get(String path, Long asUser = null) {
        def b = MockMvcRequestBuilders.get(path)
        if (asUser != null) b = b.sessionAttr(SteamAuthController.SESSION_USER_ID, asUser)
        def r = mockMvc.perform(b).andReturn()
        assert r.response.status == 200
        objectMapper.readValue(r.response.contentAsString, Object)
    }

    def "an anonymous shop's listing is masked for visitors, unmasked for its seller, and stored unchanged"() {
        given:
        SteamUser anon = user('hidden', true)
        SteamUser open = user('shown', false)
        SteamUser visitor = user('visitor', false)
        Item hat = item('MaskHat')
        Listing a = listing(hat, anon, '5.00')
        Listing o = listing(hat, open, '6.00')

        when:
        List rows = get("/api/listings/item/${hat.id}") as List
        List rowsSignedIn = get("/api/listings/item/${hat.id}", visitor.id) as List
        List rowsOwner = get("/api/listings/item/${hat.id}", anon.id) as List
        Map single = get("/api/listings/${a.id}") as Map

        then: "a visitor sees the neutral label, seller id 0 (a person, not SkinBox), no avatar or asset id"
        [rows, rowsSignedIn].every { List rs ->
            def m = rs.find { (it.id as Long) == a.id }
            m.sellerName == AnonymousShopService.LABEL &&
                (m.sellerUserId as Long) == 0L &&
                m.sellerAnonymous == true &&
                m.sellerAvatar == null &&
                m.assetId == null
        }
        !rows.toString().contains(anon.displayName)
        single.sellerName == AnonymousShopService.LABEL
        (single.sellerUserId as Long) == 0L

        and: "a seller without the setting is untouched"
        def other = rows.find { (it.id as Long) == o.id }
        other.sellerName == open.displayName
        (other.sellerUserId as Long) == open.id
        other.sellerAnonymous == null

        and: "the seller sees their own listing as theirs"
        def own = rowsOwner.find { (it.id as Long) == a.id }
        own.sellerName == anon.displayName
        (own.sellerUserId as Long) == anon.id

        and: "the database row still names the real seller (staff, trades, disputes read it)"
        def stored = listingRepo.findById(a.id).get()
        stored.sellerName == anon.displayName
        stored.sellerUserId == anon.id
        stored.assetId != null
    }

    def "the stall and seller rails don't tie an anonymous shop's listings back to it"() {
        given:
        SteamUser anon = user('stall', true)
        Item hat = item('StallHat')
        Item cap = item('StallCap')
        listing(hat, anon, '5.00')
        listing(cap, anon, '7.00')

        when:
        Map stall = get("/api/listings/stall/${anon.id}") as Map
        Map ownStall = get("/api/listings/stall/${anon.id}", anon.id) as Map
        List other = get("/api/listings/seller/${anon.id}/other?excludeItemId=${hat.id}") as List
        List sold = get("/api/listings/stall/${anon.id}/recent-sales") as List

        then:
        stall.listings == []
        stall.anonymousShop == true
        ownStall.listings.size() == 2
        ownStall.anonymousShop == false
        other == []
        sold == []
    }

    def "PUT /api/profile/anonymous-shop turns the setting on and off"() {
        given:
        SteamUser seller = user('toggle', false)
        def req = new MockHttpServletRequest()
        def session = new MockHttpSession()
        session.setAttribute(SteamAuthController.SESSION_USER_ID, seller.id)
        req.session = session

        when:
        def on = profileController.updateAnonymousShop([enabled: true], req).body

        then:
        on.anonymousShop == true
        userRepo.findById(seller.id).get().anonymousShop == true

        when:
        def off = profileController.updateAnonymousShop([enabled: 'false'], req).body

        then:
        off.anonymousShop == false
        userRepo.findById(seller.id).get().anonymousShop == false
    }
}
