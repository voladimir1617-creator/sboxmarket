package com.sboxmarket

import com.sboxmarket.controller.ProfileController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.model.Loadout
import com.sboxmarket.model.Review
import com.sboxmarket.model.SavedSearch
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.WatchlistItem
import com.sboxmarket.repository.LoadoutRepository
import com.sboxmarket.repository.ReviewRepository
import com.sboxmarket.repository.SavedSearchRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WatchlistItemRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.SteamAuthService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Defect pass 14: data export and account deletion.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefectPassFourteenSpec extends Specification {

    @Autowired ProfileController profileController
    @Autowired AdminService adminService
    @Autowired SteamUserRepository userRepo
    @Autowired SavedSearchRepository savedSearchRepo
    @Autowired WatchlistItemRepository watchlistRepo
    @Autowired LoadoutRepository loadoutRepo
    @Autowired ReviewRepository reviewRepo

    String uniq = String.valueOf(System.nanoTime())

    private MockHttpServletRequest signedIn(Long uid) {
        def req = new MockHttpServletRequest()
        req.session.setAttribute(SteamAuthController.SESSION_USER_ID, uid)
        req
    }

    def "data export works for a user with a saved search and includes starred items and loadouts"() {
        given:
        def u = userRepo.save(new SteamUser(steamId64: "7656${uniq}".take(17), displayName: 'Exporter'))
        savedSearchRepo.save(new SavedSearch(userId: u.id, name: 'Cheap hats', category: 'Hats', dealsOnly: true))
        watchlistRepo.save(new WatchlistItem(userId: u.id, itemId: 4242L))
        loadoutRepo.save(new Loadout(ownerUserId: u.id, ownerName: 'Exporter', name: 'My kit', description: 'notes'))

        when:
        def body = profileController.exportData(signedIn(u.id)).body

        then:
        body.savedSearches*.name == ['Cheap hats']
        body.savedSearches[0].dealsOnly == true
        body.watchlistItemIds == [4242L]
        body.loadouts*.name == ['My kit']
        body.loadouts[0].description == 'notes'
    }

    def "finalising a deletion removes the user's name from their reviews and hides their loadouts"() {
        given:
        def admin = userRepo.save(new SteamUser(steamId64: "7657${uniq}".take(17), displayName: 'Staff', role: 'ADMIN'))
        def u = userRepo.save(new SteamUser(steamId64: "7658${uniq}".take(17), displayName: 'Ann Realname',
            deletionRequestedAt: 1234L))
        def review = reviewRepo.save(new Review(fromUserId: u.id, toUserId: admin.id, tradeId: 9_000_000L + (uniq.takeRight(5) as Long),
            rating: 5, comment: 'great', fromDisplayName: 'Ann Realname'))
        def loadout = loadoutRepo.save(new Loadout(ownerUserId: u.id, ownerName: 'Ann Realname', name: 'Public kit'))

        when:
        adminService.finalizeDeletion(admin.id, u.id)

        then:
        reviewRepo.findById(review.id).get().fromDisplayName == "Deleted user #${u.id}".toString()
        with(loadoutRepo.findById(loadout.id).get()) {
            visibility == 'PRIVATE'
            ownerName == "Deleted user #${u.id}".toString()
        }
    }

    def "signing in again does not copy the Steam name and avatar back onto a deleted account"() {
        given:
        def repo = Mock(SteamUserRepository)
        def deleted = new SteamUser(id: 77L, steamId64: '76561190000000077', displayName: 'Deleted user #77',
            banned: true, banReason: AdminService.DELETED_BAN_REASON)
        repo.findBySteamId64('76561190000000077') >> deleted
        repo.save(_) >> { args -> args[0] }
        SteamAuthService svc = Spy(SteamAuthService)
        svc.steamUserRepository = repo

        when:
        def out = svc.upsertUser('76561190000000077')

        then:
        0 * svc.fetchProfileWithRetry(_)
        out.displayName == 'Deleted user #77'
        out.avatarUrl == null
        SteamAuthService.isDeletedAccount(out)
    }
}
