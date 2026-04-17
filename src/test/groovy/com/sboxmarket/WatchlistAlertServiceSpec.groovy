package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Item
import com.sboxmarket.model.WatchlistAlert
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.WatchlistAlertRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.WatchlistAlertService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit coverage for the server-side price-alert loop.
 * Covers upsert semantics, quota, validation, and the sweep path.
 */
class WatchlistAlertServiceSpec extends Specification {

    WatchlistAlertRepository repo                = Mock()
    ItemRepository           itemRepository      = Mock()
    NotificationService      notificationService = Mock()
    com.sboxmarket.service.EmailService emailService = Mock()
    com.sboxmarket.repository.SteamUserRepository steamUserRepository = Mock()

    @Subject
    WatchlistAlertService service = new WatchlistAlertService(
        repo:                repo,
        itemRepository:      itemRepository,
        notificationService: notificationService,
        emailService:        emailService,
        steamUserRepository: steamUserRepository
    )

    private Item itemFor(long id = 7L) {
        new Item(id: id, name: 'Wizard Hat', lowestPrice: new BigDecimal('10.00'))
    }

    // ── upsertAlert ─────────────────────────────────────────────

    def "upsertAlert creates a new alert when none exists"() {
        given:
        itemRepository.findById(7L) >> Optional.of(itemFor())
        repo.findActiveFor(42L, 7L) >> Optional.empty()
        repo.countByUserIdAndStatus(42L, 'ACTIVE') >> 0L
        repo.save(_) >> { args -> args[0].id = 100L; args[0] }

        when:
        def a = service.upsertAlert(42L, 7L, new BigDecimal('8.50'))

        then:
        a.status == 'ACTIVE'
        a.userId == 42L
        a.itemId == 7L
        a.targetPrice == new BigDecimal('8.50')
    }

    def "upsertAlert updates the existing ACTIVE row instead of adding a sibling"() {
        given:
        def existing = new WatchlistAlert(id: 9L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('9.00'), status: 'ACTIVE',
            createdAt: 1L)
        itemRepository.findById(7L) >> Optional.of(itemFor())
        repo.findActiveFor(42L, 7L) >> Optional.of(existing)
        repo.save(_) >> { args -> args[0] }

        when:
        def updated = service.upsertAlert(42L, 7L, new BigDecimal('7.00'))

        then:
        updated.id == 9L
        updated.targetPrice == new BigDecimal('7.00')
        updated.createdAt > 1L
        0 * repo.countByUserIdAndStatus(_, _)  // quota check skipped on update
    }

    def "upsertAlert refuses non-positive target prices"() {
        when:
        service.upsertAlert(42L, 7L, target)

        then:
        thrown(BadRequestException)

        where:
        target << [null, BigDecimal.ZERO, new BigDecimal('-1.50')]
    }

    def "upsertAlert refuses targets over the \$100k sanity cap"() {
        when:
        service.upsertAlert(42L, 7L, new BigDecimal('150000'))

        then:
        thrown(BadRequestException)
    }

    def "upsertAlert 404s for an unknown item"() {
        given:
        itemRepository.findById(999L) >> Optional.empty()

        when:
        service.upsertAlert(42L, 999L, new BigDecimal('5'))

        then:
        thrown(NotFoundException)
    }

    def "upsertAlert refuses a new alert once the per-user cap is reached"() {
        given:
        itemRepository.findById(7L) >> Optional.of(itemFor())
        repo.findActiveFor(42L, 7L) >> Optional.empty()
        repo.countByUserIdAndStatus(42L, 'ACTIVE') >> 50L   // at cap

        when:
        service.upsertAlert(42L, 7L, new BigDecimal('5'))

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'ALERT_LIMIT'
    }

    // ── cancelAlert ─────────────────────────────────────────────

    def "cancelAlert flips the row to CANCELLED"() {
        given:
        def a = new WatchlistAlert(id: 9L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('5'), status: 'ACTIVE')
        repo.findById(9L) >> Optional.of(a)
        repo.save(_) >> { args -> args[0] }

        when:
        service.cancelAlert(42L, 9L)

        then:
        a.status == 'CANCELLED'
    }

    def "cancelAlert refuses to cancel another user's alert"() {
        given:
        def a = new WatchlistAlert(id: 9L, userId: 99L, itemId: 7L, status: 'ACTIVE')
        repo.findById(9L) >> Optional.of(a)

        when:
        service.cancelAlert(42L, 9L)

        then:
        thrown(BadRequestException)
    }

    def "cancelAlert 404s for unknown ids"() {
        given:
        repo.findById(404L) >> Optional.empty()

        when:
        service.cancelAlert(42L, 404L)

        then:
        thrown(NotFoundException)
    }

    // ── sweep ───────────────────────────────────────────────────

    def "sweep pushes one notification per triggered alert and flips to FIRED"() {
        given:
        def a1 = new WatchlistAlert(id: 1L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('8.00'), status: 'ACTIVE')
        def a2 = new WatchlistAlert(id: 2L, userId: 43L, itemId: 8L,
            targetPrice: new BigDecimal('6.00'), status: 'ACTIVE')
        // Triggered payload = [alert, currentFloor].
        repo.findTriggered() >> [
            [a1, new BigDecimal('7.50')] as Object[],
            [a2, new BigDecimal('5.00')] as Object[]
        ]
        itemRepository.findById(7L) >> Optional.of(itemFor(7L))
        itemRepository.findById(8L) >> Optional.of(itemFor(8L))
        repo.save(_) >> { args -> args[0] }

        when:
        service.sweep()

        then:
        1 * notificationService.push(42L, 'WATCHLIST_PRICE_DROP', _, _, 7L, '/item/7')
        1 * notificationService.push(43L, 'WATCHLIST_PRICE_DROP', _, _, 8L, '/item/8')
        a1.status == 'FIRED'
        a2.status == 'FIRED'
        a1.firedAt != null
    }

    def "sweep is a no-op when nothing is triggered"() {
        given:
        repo.findTriggered() >> []

        when:
        service.sweep()

        then:
        0 * notificationService.push(_, _, _, _, _, _)
    }

    // ── clearFired ───────────────────────────────────────────────

    def "clearFired delegates to the repository and returns the delete count"() {
        given:
        repo.deleteFiredForUser(42L) >> 7

        when:
        def n = service.clearFired(42L)

        then:
        n == 7
    }

    def "sweep keeps going even if one row throws during the push"() {
        given:
        def a1 = new WatchlistAlert(id: 1L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('8'), status: 'ACTIVE')
        def a2 = new WatchlistAlert(id: 2L, userId: 43L, itemId: 8L,
            targetPrice: new BigDecimal('6'), status: 'ACTIVE')
        repo.findTriggered() >> [
            [a1, new BigDecimal('7.5')] as Object[],
            [a2, new BigDecimal('5.0')] as Object[]
        ]
        itemRepository.findById(_) >> Optional.of(itemFor())
        repo.save(_) >> { args -> args[0] }
        // First push blows up; sweep must keep going.
        notificationService.push(42L, _, _, _, _, _) >> { throw new RuntimeException('downstream boom') }

        when:
        service.sweep()

        then:
        // a1 stayed ACTIVE because its try-block threw before save; a2 fired clean.
        a2.status == 'FIRED'
        noExceptionThrown()
    }

    // ── sweep email hook ────────────────────────────────────────

    def "sweep emails users with emailNotificationsEnabled = true"() {
        given:
        def a = new WatchlistAlert(id: 1L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('10'), status: 'ACTIVE')
        repo.findTriggered() >> [[a, new BigDecimal('9.00')] as Object[]]
        itemRepository.findById(7L) >> Optional.of(itemFor())
        repo.save(_) >> { args -> args[0] }
        steamUserRepository.findById(42L) >> Optional.of(new com.sboxmarket.model.SteamUser(
            id: 42L, email: 'buyer@example.com', emailVerified: true,
            emailNotificationsEnabled: true, displayName: 'Alice'
        ))

        when:
        service.sweep()

        then:
        1 * emailService.sendPriceDrop('buyer@example.com', 'Alice', 'Wizard Hat',
            new BigDecimal('9.00'), new BigDecimal('10'), '/item/7')
    }

    def "sweep skips the email when emailNotificationsEnabled = false"() {
        given:
        def a = new WatchlistAlert(id: 1L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('10'), status: 'ACTIVE')
        repo.findTriggered() >> [[a, new BigDecimal('9.00')] as Object[]]
        itemRepository.findById(7L) >> Optional.of(itemFor())
        repo.save(_) >> { args -> args[0] }
        steamUserRepository.findById(42L) >> Optional.of(new com.sboxmarket.model.SteamUser(
            id: 42L, email: 'buyer@example.com', emailVerified: true,
            emailNotificationsEnabled: false
        ))

        when:
        service.sweep()

        then:
        0 * emailService.sendPriceDrop(_, _, _, _, _, _)
        // In-app notification still fires regardless of email pref.
        1 * notificationService.push(42L, 'WATCHLIST_PRICE_DROP', _, _, 7L, _)
    }
}
