package com.sboxmarket

import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.model.Loadout
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.LoadoutFavoriteRepository
import com.sboxmarket.repository.LoadoutRepository
import com.sboxmarket.repository.LoadoutSlotRepository
import com.sboxmarket.service.LoadoutService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Regression spec for the banGuard hole in LoadoutService.toggleFavorite.
 *
 * Before the fix, a banned user could still hit POST /api/loadouts/{id}/favorite
 * and toggle the public `favorites` counter on any PUBLIC loadout — that counter
 * drives the Discover sort, so a banned account could boost (or, via toggle-off,
 * suppress) any public loadout's ranking with one tap. clone() already calls
 * banGuard.assertNotBanned(viewerUserId); toggleFavorite() was missing it.
 *
 * The fix adds banGuard.assertNotBanned(viewerUserId) at the top of
 * toggleFavorite(), mirroring clone().
 */
class LoadoutFavoriteBanGuardSpec extends Specification {

    LoadoutRepository         loadoutRepository         = Mock()
    LoadoutSlotRepository     loadoutSlotRepository     = Mock()
    LoadoutFavoriteRepository loadoutFavoriteRepository = Mock()
    ItemRepository            itemRepository            = Mock()
    TextSanitizer             textSanitizer             = Mock()
    BanGuard                  banGuard                  = Mock()

    @Subject
    LoadoutService service = new LoadoutService(
        loadoutRepository        : loadoutRepository,
        loadoutSlotRepository    : loadoutSlotRepository,
        loadoutFavoriteRepository: loadoutFavoriteRepository,
        itemRepository           : itemRepository,
        textSanitizer            : textSanitizer,
        banGuard                 : banGuard
    )

    def "toggleFavorite rejects a banned viewer before reading the loadout"() {
        given:
        banGuard.assertNotBanned(77L) >> { throw new ForbiddenException("banned") }

        when:
        service.toggleFavorite(77L, 1L)

        then:
        thrown(ForbiddenException)
        // Ban check must short-circuit before any repo work so a banned user
        // can never reach the favorites-counter mutation path.
        0 * loadoutRepository.findById(_)
        0 * loadoutFavoriteRepository.findByUserAndLoadout(*_)
        0 * loadoutFavoriteRepository.save(_)
        0 * loadoutFavoriteRepository.deleteByUserAndLoadout(*_)
        0 * loadoutRepository.save(_)
    }

    def "toggleFavorite still works for a non-banned viewer (regression guard)"() {
        given:
        def loadout = new Loadout(id: 1L, visibility: 'PUBLIC', ownerUserId: 10L, favorites: 0)
        banGuard.assertNotBanned(77L) >> { /* not banned */ }
        loadoutRepository.findById(1L) >> Optional.of(loadout)
        loadoutFavoriteRepository.findByUserAndLoadout(77L, 1L) >> null
        loadoutFavoriteRepository.countByLoadout(1L) >> 1L
        loadoutRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.toggleFavorite(77L, 1L)

        then:
        1 * loadoutFavoriteRepository.save(_)
        result.favorited == true
        result.favorites == 1
    }
}
