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
    com.sboxmarket.service.EmailService emailService = Mock() {
        // Batch 627: delegate the gate to user flags so "skips email
        // for unverified / opted-out" tests still close the gate
        // without per-test stub churn.
        canSendTo(_, _) >> { args ->
            def user = args[0]
            user != null &&
            user.email && !user.email.isEmpty() &&
            Boolean.TRUE.equals(user.emailVerified) &&
            Boolean.TRUE.equals(user.emailNotificationsEnabled)
        }
    }
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

    def "sweep skips the push + email for banned users but still flips the alert to FIRED (batch 332)"() {
        given:
        def a = new WatchlistAlert(id: 1L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('10'), status: 'ACTIVE')
        repo.findTriggered() >> [[a, new BigDecimal('9.00')] as Object[]]
        itemRepository.findById(7L) >> Optional.of(itemFor())
        repo.save(_) >> { args -> args[0] }
        // Banned user — price drop is pure noise since they can't buy.
        steamUserRepository.findById(42L) >> Optional.of(new com.sboxmarket.model.SteamUser(
            id: 42L, email: 'bad@example.com', emailVerified: true,
            emailNotificationsEnabled: true, banned: true
        ))

        when:
        service.sweep()

        then:
        // No in-app push and no email.
        0 * notificationService.push(42L, _, _, _, _, _)
        0 * emailService.sendPriceDrop(_, _, _, _, _, _)
        // Still flipped to FIRED so the sweep doesn't keep re-scanning this
        // row every 5 minutes for the rest of time.
        a.status == 'FIRED'
        a.firedAt != null
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

    // ── countWatchersForItem (public social-proof chip) ────────────

    def "countWatchersForItem returns 0 for a null item id without hitting the repo"() {
        when:
        def n = service.countWatchersForItem(null)

        then:
        n == 0L
        0 * repo.countActiveForItem(_)
    }

    def "countWatchersForItem forwards to the repo for a real item id"() {
        given:
        repo.countActiveForItem(42L) >> 7L

        expect:
        service.countWatchersForItem(42L) == 7L
    }

    // ── upsertAlert price rounding ───────────────────────────────

    def "upsertAlert rounds the target price to 2 dp HALF_UP on create"() {
        given:
        itemRepository.findById(7L) >> Optional.of(itemFor())
        repo.findActiveFor(42L, 7L) >> Optional.empty()
        repo.countByUserIdAndStatus(42L, 'ACTIVE') >> 0L
        repo.save(_) >> { args -> args[0] }

        when:
        def a = service.upsertAlert(42L, 7L, new BigDecimal('8.125'))

        then: 'persisted scale is exactly 2, rounded half-up'
        a.targetPrice == new BigDecimal('8.13')
        a.targetPrice.scale() == 2
    }

    def "upsertAlert rounds the target price to 2 dp HALF_UP on update"() {
        given:
        def existing = new WatchlistAlert(id: 9L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('9.00'), status: 'ACTIVE', createdAt: 1L)
        itemRepository.findById(7L) >> Optional.of(itemFor())
        repo.findActiveFor(42L, 7L) >> Optional.of(existing)
        repo.save(_) >> { args -> args[0] }

        when:
        def updated = service.upsertAlert(42L, 7L, new BigDecimal('7.005'))

        then:
        updated.targetPrice == new BigDecimal('7.01')
        updated.targetPrice.scale() == 2
    }

    def "upsertAlert accepts a brand-new alert at exactly PER_USER_LIMIT - 1"() {
        given:
        itemRepository.findById(7L) >> Optional.of(itemFor())
        repo.findActiveFor(42L, 7L) >> Optional.empty()
        repo.countByUserIdAndStatus(42L, 'ACTIVE') >> 49L   // one slot left (limit is 50)

        when:
        def a = service.upsertAlert(42L, 7L, new BigDecimal('5'))

        then: 'the last open slot is honoured — the create path runs'
        1 * repo.save(_) >> { args -> args[0].id = 1L; args[0] }
        a.status == 'ACTIVE'
        a.itemId == 7L
    }

    // ── listForUser ──────────────────────────────────────────────

    def "listForUser returns an empty list for a null user id without hitting the repo"() {
        when:
        def out = service.listForUser(null)

        then:
        out == []
        0 * repo.findByUserIdPaged(_, _)
    }

    def "listForUser caps the page size at ALERT_LIST_CAP"() {
        given:
        def captured = null
        1 * repo.findByUserIdPaged(42L, _) >> { args -> captured = args[1]; [] }

        when:
        service.listForUser(42L)

        then: 'first page, sized to the display cap'
        captured.pageNumber == 0
        captured.pageSize == WatchlistAlertService.ALERT_LIST_CAP
    }

    // ── fireRow projection: name comes from the JOIN, not an N+1 ──

    def "sweep reads the item name from the 3-col projection without an itemRepository fetch"() {
        given:
        def a = new WatchlistAlert(id: 1L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('10'), status: 'ACTIVE')
        // Production projection shape: [alert, lowestPrice, name].
        repo.findTriggered() >> [[a, new BigDecimal('9.00'), 'Dragon Lore'] as Object[]]
        repo.save(_) >> { args -> args[0] }

        when:
        service.sweep()

        then: 'name carried by the JOIN — no per-row findById (no N+1)'
        0 * itemRepository.findById(_)
        1 * notificationService.push(42L, 'WATCHLIST_PRICE_DROP',
            { it.contains('Dragon Lore') }, _, 7L, '/item/7')
        a.status == 'FIRED'
    }

    def "sweep falls back to itemRepository when the projection carries a blank name"() {
        given:
        def a = new WatchlistAlert(id: 1L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('10'), status: 'ACTIVE')
        // 3-col row but name is empty → fallback lookup expected.
        repo.findTriggered() >> [[a, new BigDecimal('9.00'), ''] as Object[]]
        repo.save(_) >> { args -> args[0] }

        when:
        service.sweep()

        then: 'blank projection name forces exactly one fallback fetch'
        1 * itemRepository.findById(7L) >> Optional.of(itemFor())
        1 * notificationService.push(42L, 'WATCHLIST_PRICE_DROP', _, _, 7L, '/item/7')
    }

    // ── sweepForItem (synchronous per-item sweep, batch 389) ──────

    def "sweepForItem fires the alerts scoped to that one item"() {
        given:
        def a = new WatchlistAlert(id: 1L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('10'), status: 'ACTIVE')
        repo.findTriggeredForItem(7L) >> [[a, new BigDecimal('8.00'), 'Wizard Hat'] as Object[]]
        repo.save(_) >> { args -> args[0] }

        when:
        service.sweepForItem(7L)

        then:
        1 * notificationService.push(42L, 'WATCHLIST_PRICE_DROP', _, _, 7L, '/item/7')
        a.status == 'FIRED'
        a.firedAt != null
    }

    def "sweepForItem is a no-op for a null item id"() {
        when:
        service.sweepForItem(null)

        then:
        0 * repo.findTriggeredForItem(_)
        0 * notificationService.push(_, _, _, _, _, _)
    }

    def "sweepForItem is a no-op when the scoped query returns nothing"() {
        given:
        repo.findTriggeredForItem(7L) >> []

        when:
        service.sweepForItem(7L)

        then:
        0 * notificationService.push(_, _, _, _, _, _)
        0 * repo.save(_)
    }

    def "sweepForItem swallows a repository failure — best-effort, never rethrows"() {
        given: 'the scoped query itself blows up'
        repo.findTriggeredForItem(7L) >> { throw new RuntimeException('db boom') }

        when:
        service.sweepForItem(7L)

        then: 'caller (the sell transaction) is shielded from the failure'
        noExceptionThrown()
    }

    def "sweepForItem skips the push for a banned user but still flips the alert to FIRED"() {
        given:
        def a = new WatchlistAlert(id: 1L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('10'), status: 'ACTIVE')
        repo.findTriggeredForItem(7L) >> [[a, new BigDecimal('8.00'), 'Wizard Hat'] as Object[]]
        repo.save(_) >> { args -> args[0] }
        steamUserRepository.findById(42L) >> Optional.of(new com.sboxmarket.model.SteamUser(
            id: 42L, email: 'bad@example.com', emailVerified: true,
            emailNotificationsEnabled: true, banned: true
        ))

        when:
        service.sweepForItem(7L)

        then:
        0 * notificationService.push(42L, _, _, _, _, _)
        0 * emailService.sendPriceDrop(_, _, _, _, _, _)
        a.status == 'FIRED'
        a.firedAt != null
    }

    // ── cancelAlert on a non-ACTIVE row ──────────────────────────

    def "cancelAlert on an already-FIRED row still flips it to CANCELLED"() {
        given:
        def a = new WatchlistAlert(id: 9L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('5'), status: 'FIRED', firedAt: 123L)
        repo.findById(9L) >> Optional.of(a)
        repo.save(_) >> { args -> args[0] }

        when:
        service.cancelAlert(42L, 9L)

        then:
        a.status == 'CANCELLED'
    }

    // ── email digest dedup (EMAIL_DEDUP_WINDOW_MS) ────────────────
    //
    // A user with N alerts all firing in one tick must NOT receive N
    // simultaneous price-drop emails — the inbox would be napalmed.
    // shouldSendEmail() collapses to one email per user per 5-minute
    // window. The in-app push still fires once per triggered item
    // (cheap, contextual), but only the FIRST firing in the window
    // sends an email. Two specs cover both sides of the gate.

    def "sweep sends EXACTLY ONE price-drop email when two alerts for the same user fire in one tick (digest dedup)"() {
        given: 'two ACTIVE alerts owned by the same user on different items, both triggered this tick'
        def a1 = new WatchlistAlert(id: 1L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('10'), status: 'ACTIVE')
        def a2 = new WatchlistAlert(id: 2L, userId: 42L, itemId: 8L,
            targetPrice: new BigDecimal('5'),  status: 'ACTIVE')
        repo.findTriggered() >> [
            [a1, new BigDecimal('9.00'), 'Wizard Hat']  as Object[],
            [a2, new BigDecimal('4.00'), 'Dragon Lore'] as Object[]
        ]
        repo.save(_) >> { args -> args[0] }
        // Same user for both alerts — emailable.
        steamUserRepository.findById(42L) >> Optional.of(new com.sboxmarket.model.SteamUser(
            id: 42L, email: 'buyer@example.com', emailVerified: true,
            emailNotificationsEnabled: true, displayName: 'Alice'
        ))

        when:
        service.sweep()

        then: 'each item still gets its own in-app push — those are cheap and contextual'
        1 * notificationService.push(42L, 'WATCHLIST_PRICE_DROP', _, _, 7L, '/item/7')
        1 * notificationService.push(42L, 'WATCHLIST_PRICE_DROP', _, _, 8L, '/item/8')

        and: 'but ONLY ONE email — the second send is collapsed by the 5-min dedup gate'
        1 * emailService.sendPriceDrop('buyer@example.com', _, _, _, _, _)

        and: 'both alerts still flip to FIRED — the dedup is email-only, not state'
        a1.status == 'FIRED'
        a2.status == 'FIRED'
    }

    def "sweep emails BOTH users when two distinct users each have an alert fire in one tick (dedup is per-user)"() {
        given: 'two different users — dedup must not bleed across user ids'
        def a1 = new WatchlistAlert(id: 1L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('10'), status: 'ACTIVE')
        def a2 = new WatchlistAlert(id: 2L, userId: 43L, itemId: 8L,
            targetPrice: new BigDecimal('5'),  status: 'ACTIVE')
        repo.findTriggered() >> [
            [a1, new BigDecimal('9.00'), 'Wizard Hat']  as Object[],
            [a2, new BigDecimal('4.00'), 'Dragon Lore'] as Object[]
        ]
        repo.save(_) >> { args -> args[0] }
        steamUserRepository.findById(42L) >> Optional.of(new com.sboxmarket.model.SteamUser(
            id: 42L, email: 'alice@example.com', emailVerified: true,
            emailNotificationsEnabled: true, displayName: 'Alice'
        ))
        steamUserRepository.findById(43L) >> Optional.of(new com.sboxmarket.model.SteamUser(
            id: 43L, email: 'bob@example.com', emailVerified: true,
            emailNotificationsEnabled: true, displayName: 'Bob'
        ))

        when:
        service.sweep()

        then: 'one email per user — dedup is keyed on userId, not on the (user, sweep-tick) pair'
        1 * emailService.sendPriceDrop('alice@example.com', _, _, _, _, _)
        1 * emailService.sendPriceDrop('bob@example.com',   _, _, _, _, _)
    }
}
