package com.sboxmarket

import com.sboxmarket.model.Notification
import com.sboxmarket.repository.NotificationRepository
import com.sboxmarket.service.NotificationService
import spock.lang.Specification
import spock.lang.Subject

/**
 * NotificationService is thin glue over the repository, so the spec is
 * thin too. The things worth asserting: userId=null is a no-op (we call
 * `push` from services that may have a null counterparty), read-state
 * updates are owner-scoped, markAllRead touches only unread rows.
 */
class NotificationServiceSpec extends Specification {

    NotificationRepository notificationRepository = Mock()

    @Subject
    NotificationService service = new NotificationService(
        notificationRepository: notificationRepository
    )

    def "push persists a row and returns it"() {
        given:
        notificationRepository.save(_) >> { Notification n -> n.id = 1L; n }

        when:
        def n = service.push(10L, 'SALE', 'You sold a thing', 'for $50', 100L)

        then:
        n != null
        n.userId == 10L
        n.kind == 'SALE'
        n.title == 'You sold a thing'
        n.body == 'for $50'
        n.refId == 100L
    }

    def "push is a no-op when userId is null"() {
        when:
        def result = service.push(null, 'SALE', 'x')

        then:
        result == null
        0 * notificationRepository.save(_)
    }

    def "markRead flips read on the owner's row"() {
        given:
        def n = new Notification(id: 1L, userId: 10L, read: false, title: 'x')
        notificationRepository.findById(1L) >> Optional.of(n)

        when:
        service.markRead(10L, 1L)

        then:
        n.read == true
        1 * notificationRepository.save(n)
    }

    def "markRead does nothing when the owner doesn't match"() {
        given:
        def n = new Notification(id: 1L, userId: 10L, read: false, title: 'x')
        notificationRepository.findById(1L) >> Optional.of(n)

        when:
        service.markRead(99L, 1L)

        then:
        n.read == false
        0 * notificationRepository.save(_)
    }

    def "markRead does nothing when the id is unknown"() {
        given:
        notificationRepository.findById(_) >> Optional.empty()

        when:
        service.markRead(10L, 999L)

        then:
        0 * notificationRepository.save(_)
    }

    def "markAllRead flips every unread row and leaves already-read rows alone"() {
        given:
        def rows = [
            new Notification(id: 1L, userId: 10L, read: false, title: 'a'),
            new Notification(id: 2L, userId: 10L, read: true,  title: 'b'),
            new Notification(id: 3L, userId: 10L, read: false, title: 'c'),
        ]
        notificationRepository.findForUser(10L, _) >> rows

        when:
        service.markAllRead(10L)

        then:
        1 * notificationRepository.saveAll({ List<Notification> saved ->
            saved.size() == 2 &&
            saved.every { it.read == true } &&
            saved*.id.containsAll([1L, 3L])
        })
    }

    def "countUnread returns 0 when the repository returns null"() {
        given:
        notificationRepository.countUnread(10L) >> null

        when:
        def n = service.countUnread(10L)

        then:
        n == 0L
    }

    def "countUnread passes through the repository value otherwise"() {
        given:
        notificationRepository.countUnread(10L) >> 7L

        when:
        def n = service.countUnread(10L)

        then:
        n == 7L
    }

    // ── deleteAllRead ───────────────────────────────────────────

    def "deleteAllRead removes only the read rows and returns the count"() {
        given:
        def rows = [
            new Notification(id: 1L, userId: 10L, read: true),
            new Notification(id: 2L, userId: 10L, read: false),
            new Notification(id: 3L, userId: 10L, read: true),
            new Notification(id: 4L, userId: 10L, read: true)
        ]
        notificationRepository.findForUser(10L, _) >> rows

        when:
        def n = service.deleteAllRead(10L)

        then:
        n == 3
        1 * notificationRepository.deleteAll({ List<Notification> toDelete ->
            toDelete.size() == 3 && toDelete.every { it.read == true }
        })
    }

    def "deleteAllRead is a zero-count no-op when nothing is read"() {
        given:
        notificationRepository.findForUser(10L, _) >> [
            new Notification(id: 1L, userId: 10L, read: false),
            new Notification(id: 2L, userId: 10L, read: false)
        ]

        when:
        def n = service.deleteAllRead(10L)

        then:
        n == 0
        0 * notificationRepository.deleteAll(_)
    }

    // ── deleteOne ─────────────────────────────────────────────────

    def "deleteOne removes the user's own notification"() {
        given:
        def row = new Notification(id: 42L, userId: 10L, read: true)
        notificationRepository.findById(42L) >> Optional.of(row)

        when:
        service.deleteOne(10L, 42L)

        then:
        1 * notificationRepository.delete(row)
    }

    def "deleteOne silently ignores notifications belonging to another user (no leak)"() {
        given:
        def row = new Notification(id: 42L, userId: 99L, read: true)
        notificationRepository.findById(42L) >> Optional.of(row)

        when:
        service.deleteOne(10L, 42L)

        then:
        // Same response shape whether the row exists or doesn't — we
        // don't leak ownership to a walker iterating ids.
        0 * notificationRepository.delete(_)
    }

    def "deleteOne silently ignores unknown ids"() {
        given:
        notificationRepository.findById(999L) >> Optional.empty()

        when:
        service.deleteOne(10L, 999L)

        then:
        0 * notificationRepository.delete(_)
    }

    def "deleteOne short-circuits on null inputs"() {
        when:
        service.deleteOne(null, 42L)
        service.deleteOne(10L, null)

        then:
        0 * notificationRepository.findById(_)
        0 * notificationRepository.delete(_)
    }

    // ── safePush (batch 625) ────────────────────────────────────────

    def "safePush saves the notification on the happy path"() {
        given:
        notificationRepository.save(_) >> { Notification n -> n.id = 7L; n }

        when:
        def n = service.safePush(10L, 'OFFER_RECEIVED', 'Title', 'Body', 5L, '/offers')

        then:
        n != null
        n.id == 7L
        n.kind == 'OFFER_RECEIVED'
    }

    def "safePush returns null when userId is null without touching the repo"() {
        when:
        def n = service.safePush(null, 'WHATEVER', 'Title')

        then:
        n == null
        0 * notificationRepository.save(_)
    }

    def "safePush swallows repo exceptions and returns null"() {
        given:
        notificationRepository.save(_) >> { throw new RuntimeException('DB down') }

        when:
        def n = service.safePush(10L, 'OFFER_RECEIVED', 'Title')

        then:
        // The parent operation (accepted offer, purchase, etc.) must
        // not fail because of a notification-push hiccup. safePush
        // eats the exception + logs a warning.
        n == null
        noExceptionThrown()
    }

    // ── markReadByIds (batch 635) ─────────────────────────────────────

    def "markReadByIds flips only unread rows the caller owns + returns the count"() {
        given:
        def rows = [
            new Notification(id: 1L, userId: 10L, read: false, title: 'a'),
            new Notification(id: 2L, userId: 10L, read: true,  title: 'b'),  // already read
            new Notification(id: 3L, userId: 99L, read: false, title: 'c'),  // someone else's row
            new Notification(id: 4L, userId: 10L, read: false, title: 'd')
        ]
        notificationRepository.findAllById(_) >> rows

        when:
        def n = service.markReadByIds(10L, [1L, 2L, 3L, 4L])

        then:
        n == 2
        1 * notificationRepository.saveAll({ List<Notification> saved ->
            saved.size() == 2 &&
            saved.every { it.read == true } &&
            saved*.id.containsAll([1L, 4L])
        })
        // Another user's row stayed unread
        rows.find { it.id == 3L }.read == false
    }

    def "markReadByIds short-circuits on null user, null ids, or empty ids"() {
        when:
        def a = service.markReadByIds(null, [1L])
        def b = service.markReadByIds(10L, null)
        def c = service.markReadByIds(10L, [])

        then:
        a == 0 && b == 0 && c == 0
        0 * notificationRepository.findAllById(_)
    }

    def "markReadByIds dedupes + caps the input so a huge id list can't drown the repo"() {
        given:
        // Feed a 600-id list with duplicates. Cap is 500; dedupe first,
        // then take 500. We assert the repo is called with <= 500 ids.
        def ids = (1..600).collect { (long) it } + [1L, 2L, 3L]  // 603 with dupes
        def passedToRepo = null
        notificationRepository.findAllById(_) >> { args -> passedToRepo = args[0]; [] }

        when:
        service.markReadByIds(10L, ids)

        then:
        passedToRepo != null
        passedToRepo.size() <= 500
        // Dedup happened — no duplicate of 1L inside the capped slice.
        passedToRepo.count(1L) <= 1
    }

    // ── deleteReadByIds (batch 636) ───────────────────────────────────

    def "deleteReadByIds removes only read rows the caller owns + returns the count"() {
        given:
        def rows = [
            new Notification(id: 1L, userId: 10L, read: true,  title: 'a'),
            new Notification(id: 2L, userId: 10L, read: false, title: 'b'),  // unread — skip
            new Notification(id: 3L, userId: 99L, read: true,  title: 'c'),  // foreign — skip
            new Notification(id: 4L, userId: 10L, read: true,  title: 'd')
        ]
        notificationRepository.findAllById(_) >> rows

        when:
        def n = service.deleteReadByIds(10L, [1L, 2L, 3L, 4L])

        then:
        n == 2
        1 * notificationRepository.deleteAll({ List<Notification> toDelete ->
            toDelete.size() == 2 &&
            toDelete.every { it.read == true && it.userId == 10L } &&
            toDelete*.id.containsAll([1L, 4L])
        })
    }

    def "deleteReadByIds refuses to touch UNREAD rows even when the caller owns them"() {
        given:
        // Safety-critical: accidentally deleting an unread row would hide
        // something actionable from the user. Spec pins the invariant.
        def rows = [
            new Notification(id: 1L, userId: 10L, read: false, title: 'a')
        ]
        notificationRepository.findAllById(_) >> rows

        when:
        def n = service.deleteReadByIds(10L, [1L])

        then:
        n == 0
        0 * notificationRepository.deleteAll(_)
    }

    def "deleteReadByIds short-circuits on null user, null ids, or empty ids"() {
        when:
        def a = service.deleteReadByIds(null, [1L])
        def b = service.deleteReadByIds(10L, null)
        def c = service.deleteReadByIds(10L, [])

        then:
        a == 0 && b == 0 && c == 0
        0 * notificationRepository.findAllById(_)
    }
}
