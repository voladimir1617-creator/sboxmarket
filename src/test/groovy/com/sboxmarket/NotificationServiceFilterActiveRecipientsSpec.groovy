package com.sboxmarket

import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.NotificationRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.NotificationService
import spock.lang.Specification

/**
 * Batch 317 — fan-out ban-filter regression specs.
 *
 * NotificationService.filterActiveRecipients lifts the inline
 * ban-drop pattern (PurchaseService.buy CART_ITEM_SOLD) into a
 * shared helper. The 4 sister fan-outs (ListingController price
 * edit, ListingService.bulkAdjust, OfferService.notifyOfferHolders,
 * AdminService.forceCancel CART_ITEM_SOLD) now use it so banned
 * recipients no longer get bell entries they can't act on (banGuard
 * rejects every re-shop attempt anyway).
 */
class NotificationServiceFilterActiveRecipientsSpec extends Specification {

    private NotificationService svc

    def setup() {
        svc = new NotificationService()
        svc.notificationRepository = Mock(NotificationRepository)
    }

    def "filterActiveRecipients passes through everything when repo is unwired (unit-test path)"() {
        when:
        def out = svc.filterActiveRecipients([1L, 2L, 3L])

        then:
        out == [1L, 2L, 3L]
    }

    def "filterActiveRecipients drops banned uids"() {
        given:
        svc.steamUserRepository = Mock(SteamUserRepository)
        def alice = new SteamUser(id: 1L, banned: false)
        def bob   = new SteamUser(id: 2L, banned: true)
        def carol = new SteamUser(id: 3L, banned: false)

        when:
        def out = svc.filterActiveRecipients([1L, 2L, 3L])

        then:
        1 * svc.steamUserRepository.findAllById([1L, 2L, 3L]) >> [alice, bob, carol]
        out == [1L, 3L]
    }

    def "filterActiveRecipients returns input on repository failure (degrade gracefully)"() {
        given:
        svc.steamUserRepository = Mock(SteamUserRepository)

        when:
        def out = svc.filterActiveRecipients([1L, 2L, 3L])

        then:
        1 * svc.steamUserRepository.findAllById([1L, 2L, 3L]) >> { throw new RuntimeException("db blip") }
        out == [1L, 2L, 3L]
    }

    def "filterActiveRecipients handles null/empty/null-element inputs cleanly"() {
        expect:
        svc.filterActiveRecipients(null) == []
        svc.filterActiveRecipients([]) == []
        svc.filterActiveRecipients([null, null]) == []
    }

    def "filterActiveRecipients returns input unchanged when nobody is banned"() {
        given:
        svc.steamUserRepository = Mock(SteamUserRepository)
        def alice = new SteamUser(id: 1L, banned: false)
        def bob   = new SteamUser(id: 2L, banned: null)   // null treated as not-banned
        def carol = new SteamUser(id: 3L, banned: false)

        when:
        def out = svc.filterActiveRecipients([1L, 2L, 3L])

        then:
        1 * svc.steamUserRepository.findAllById([1L, 2L, 3L]) >> [alice, bob, carol]
        out == [1L, 2L, 3L]
    }
}
