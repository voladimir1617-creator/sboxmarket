package com.sboxmarket

import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.UserBlockRepository
import com.sboxmarket.service.UserBlockService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification

/**
 * End-to-end integration coverage for the block-list feature (batch
 * 343-347). Seeds two users + a listing from one of them, creates a
 * block row via the service, then probes the listing-returning
 * endpoints to confirm the blocked seller's listing disappears for
 * the signed-in blocker AND stays visible for anonymous callers.
 *
 * Goes through the real Spring MVC stack + real H2 repos — locks in
 * the plumbing so a future refactor that accidentally drops the
 * filter breaks here (not silently in production).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserBlockIntegrationSpec extends Specification {

    @Autowired ApplicationContext ctx

    MockMvc               mockMvc
    SteamUserRepository   steamUserRepository
    UserBlockRepository   userBlockRepository
    UserBlockService      userBlockService
    ItemRepository        itemRepository
    ListingRepository     listingRepository

    SteamUser blocker
    SteamUser blockedSeller
    Item      item
    Listing   sellerListing
    MockHttpSession blockerSession

    def setup() {
        mockMvc             = ctx.getBean(MockMvc)
        steamUserRepository = ctx.getBean(SteamUserRepository)
        userBlockRepository = ctx.getBean(UserBlockRepository)
        userBlockService    = ctx.getBean(UserBlockService)
        itemRepository      = ctx.getBean(ItemRepository)
        listingRepository   = ctx.getBean(ListingRepository)

        def uniq = String.valueOf(System.nanoTime())
        def seed = { String prefix ->
            def id = "76561199" + uniq.substring(uniq.length() - 9) + prefix
            steamUserRepository.save(new SteamUser(
                steamId64:   id,
                displayName: "${prefix}-${uniq}"
            ))
        }
        blocker       = seed('Blocker')
        blockedSeller = seed('Blocked')

        item = itemRepository.save(new Item(
            name:         "BlockTestItem-${uniq}",
            category:     'Hats',
            rarity:       'Limited',
            imageUrl:     'https://example.com/x.png',
            iconEmoji:    '🎩',
            supply:       10,
            totalSold:    0,
            lowestPrice:  new BigDecimal('25.00')
        ))
        sellerListing = listingRepository.save(new Listing(
            item:         item,
            price:        new BigDecimal('25.00'),
            sellerName:   "Blocked-${uniq}",
            sellerUserId: blockedSeller.id,
            sellerAvatar: 'BL',
            status:       'ACTIVE',
            rarityScore:  new BigDecimal('0.5')
        ))

        blockerSession = new MockHttpSession()
        blockerSession.setAttribute(SteamAuthController.SESSION_USER_ID, blocker.id)
    }

    def cleanup() {
        // Clean up the block row we created so sibling specs sharing the
        // Spring context aren't poisoned by leftover state.
        if (blocker != null && blockedSeller != null) {
            try { userBlockRepository.deleteByPair(blocker.id, blockedSeller.id) } catch (Exception ignored) {}
        }
    }

    def "GET /api/listings hides the blocked seller's listing from the blocker's grid"() {
        given:
        userBlockService.block(blocker.id, blockedSeller.id)

        when:
        def anon = mockMvc.perform(MockMvcRequestBuilders.get('/api/listings'))
            .andReturn()
        def asBlocker = mockMvc.perform(
            MockMvcRequestBuilders.get('/api/listings').session(blockerSession)
        ).andReturn()

        then:
        anon.response.status == 200
        asBlocker.response.status == 200
        // Anonymous caller sees the listing — no session, no block applied.
        anon.response.contentAsString.contains("BlockTestItem")
        // Blocker does NOT see the listing — the controller-level filter
        // stripped the row based on sellerUserId ∈ blocklist.
        !asBlocker.response.contentAsString.contains("BlockTestItem-${item.name.split('-')[1]}")
    }

    def "GET /api/listings/item/{itemId} block-filters for the blocker too"() {
        given:
        userBlockService.block(blocker.id, blockedSeller.id)

        when:
        def anon = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/listings/item/${item.id}")
        ).andReturn()
        def asBlocker = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/listings/item/${item.id}").session(blockerSession)
        ).andReturn()

        then:
        anon.response.status == 200
        asBlocker.response.status == 200
        // Anonymous visitor gets the full list (one row — our seeded listing).
        anon.response.contentAsString.contains("\"id\":${sellerListing.id}")
        // Blocker gets an empty list for this item.
        !asBlocker.response.contentAsString.contains("\"id\":${sellerListing.id}")
    }

    def "GET /api/listings/stall/{sellerId} returns blockedByViewer=true for a blocker"() {
        given:
        userBlockService.block(blocker.id, blockedSeller.id)

        when:
        def anon = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/listings/stall/${blockedSeller.id}")
        ).andReturn()
        def asBlocker = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/listings/stall/${blockedSeller.id}").session(blockerSession)
        ).andReturn()

        then:
        anon.response.status == 200
        asBlocker.response.status == 200
        // Anonymous caller: false (no session, nobody to be the blocker).
        anon.response.contentAsString.contains('"blockedByViewer":false')
        // Blocker sees true — lets the stall page render the "You've
        // blocked this seller" banner.
        asBlocker.response.contentAsString.contains('"blockedByViewer":true')
    }

    def "GET /api/listings/stall/{sellerId} null-outs steamId64 AND profileUrl on banned accounts (batch 982)"() {
        // Scraper walking stall/1..N on banned accounts can no longer
        // harvest the 17-digit Steam community id. Before the fix,
        // profileUrl was null'd for banned but the steamId64 right next
        // to it wasn't — same identifier, different field.
        given:
        // Populate profileUrl so the non-banned comparator has a real value.
        blockedSeller.profileUrl = "https://steamcommunity.com/profiles/${blockedSeller.steamId64}".toString()
        steamUserRepository.save(blockedSeller)
        def liveBody = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/listings/stall/${blockedSeller.id}")
        ).andReturn().response.contentAsString

        // Flip the seed to banned.
        blockedSeller.banned = true
        blockedSeller.banReason = 'test'
        steamUserRepository.save(blockedSeller)
        def bannedBody = mockMvc.perform(
            MockMvcRequestBuilders.get("/api/listings/stall/${blockedSeller.id}")
        ).andReturn().response.contentAsString

        expect:
        // Non-banned: both fields populated.
        liveBody.contains('"banned":false')
        liveBody.contains('"steamId64":"' + blockedSeller.steamId64 + '"')
        liveBody.contains('"profileUrl":"https://steamcommunity.com/profiles/')

        // Banned: both fields null'd out (even though the row still
        // carries steamId64 in the DB — the endpoint elides it).
        bannedBody.contains('"banned":true')
        bannedBody.contains('"steamId64":null')
        bannedBody.contains('"profileUrl":null')

        cleanup:
        blockedSeller.banned = false
        blockedSeller.banReason = null
        steamUserRepository.save(blockedSeller)
    }
}
