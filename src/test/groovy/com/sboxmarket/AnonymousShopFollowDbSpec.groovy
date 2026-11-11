package com.sboxmarket

import com.fasterxml.jackson.databind.ObjectMapper
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SellerFollow
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.NotificationRepository
import com.sboxmarket.repository.SellerFollowRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.SellerFollowService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification

/**
 * Anonymous shop and follows: anyone can follow any seller, so the "From
 * sellers you follow" feed and the new-listing ping must not list an
 * anonymous shop's listings under its name (the stall doesn't either).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnonymousShopFollowDbSpec extends Specification {

    @Autowired MockMvc mockMvc
    @Autowired ObjectMapper objectMapper
    @Autowired SteamUserRepository userRepo
    @Autowired ItemRepository itemRepo
    @Autowired ListingRepository listingRepo
    @Autowired SellerFollowRepository followRepo
    @Autowired NotificationRepository notificationRepo
    @Autowired SellerFollowService followService

    String uniq = String.valueOf(System.nanoTime())
    int seq = 0

    private SteamUser user(String name, boolean anonymous) {
        def tail = (uniq + (seq++)).reverse().take(9).reverse()
        userRepo.save(new SteamUser(steamId64: '7656117' + tail.padLeft(10, '0'),
            displayName: "${name}-${uniq}", role: 'USER', anonymousShop: anonymous))
    }

    private Listing listing(SteamUser seller) {
        Item hat = itemRepo.save(new Item(name: "FollowHat ${uniq} ${seq++}", category: 'Hats', rarity: 'Standard',
            supply: 1, totalSold: 0, lowestPrice: new BigDecimal('5'), isListed: true, iconEmoji: '🎩'))
        listingRepo.save(new Listing(item: hat, sellerUserId: seller.id, sellerName: seller.displayName,
            sellerAvatar: 'XY', price: new BigDecimal('5.00'), status: 'ACTIVE', listingType: 'BUY_NOW', hidden: false))
    }

    private List feed(Long asUser) {
        def r = mockMvc.perform(MockMvcRequestBuilders.get('/api/follows/feed')
            .sessionAttr(SteamAuthController.SESSION_USER_ID, asUser)).andReturn()
        assert r.response.status == 200
        objectMapper.readValue(r.response.contentAsString, List)
    }

    def "following an anonymous shop doesn't list its listings by name or ping about them"() {
        given:
        SteamUser anon = user('anonfollowed', true)
        SteamUser open = user('openfollowed', false)
        SteamUser follower = user('follower', false)
        followRepo.save(new SellerFollow(followerUserId: follower.id, sellerUserId: anon.id))
        followRepo.save(new SellerFollow(followerUserId: follower.id, sellerUserId: open.id))
        Listing hidden = listing(anon)
        Listing shown = listing(open)
        long before = notificationRepo.countByUser(follower.id)

        when:
        List rows = feed(follower.id)
        followService.notifyFollowersOfNewListing(hidden)
        long afterAnon = notificationRepo.countByUser(follower.id)
        followService.notifyFollowersOfNewListing(shown)
        long afterOpen = notificationRepo.countByUser(follower.id)

        then:
        rows*.id == [shown.id]
        !objectMapper.writeValueAsString(rows).contains(anon.displayName)
        afterAnon == before
        afterOpen == before + 1
    }
}
