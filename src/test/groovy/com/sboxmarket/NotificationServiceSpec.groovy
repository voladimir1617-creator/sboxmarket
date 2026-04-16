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
}
