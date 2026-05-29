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

    /** Wave 112 baseline: fireRow's atomic ACTIVE → FIRED claim returns
     *  the affected-row count. Per-test stub (rather than a setup()
     *  default) because Spock 2.x picks the FIRST declared matching
     *  stub when multiple stubs cover the same call, so a setup()
     *  default would beat the per-test override the race specs need
     *  for the "lost the claim" branch. Each legacy sweep/sweepForItem
     *  test that exercises fireRow stubs the win path explicitly. */

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

    /**
     * Batch 1196 — round-then-validate regression.
     *
     * Prior bug: the `<= 0` floor was checked BEFORE `setScale(2,
     * HALF_UP)`. A value like `0.00001` slipped past the >0 guard,
     * was rounded down to `0.00`, and persisted as a zero target.
     * The sweep query (`i.lowestPrice <= a.targetPrice AND
     * i.lowestPrice > 0`) can never match a zero target, so the
     * user's alert sat dormant forever in their per-user quota —
     * silently broken, no signal to either the user or ops.
     *
     * Contract pinned: anything in (0, 0.005) that rounds DOWN to
     * 0.00 must fast-fail with INVALID_TARGET so the SPA shows the
     * same error toast as the explicit `<= 0` and `null` cases, and
     * no save / no quota burn occurs. Boundary value `0.005` rounds
     * UP to `0.01` (HALF_UP) and stays a legitimate target.
     */
    def "upsertAlert refuses sub-cent targets that round to zero (batch 1196)"() {
        when:
        service.upsertAlert(42L, 7L, target)

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'INVALID_TARGET'

        and: 'never reaches the item lookup or the repo — fast-fails at the floor'
        0 * itemRepository.findById(_)
        0 * repo.save(_)

        where:
        target << [new BigDecimal('0.001'),
                   new BigDecimal('0.004'),
                   new BigDecimal('0.00001'),
                   new BigDecimal('0.0049')]
    }

    def "upsertAlert accepts \$0.005 — HALF_UP rounds up to \$0.01 (boundary case)"() {
        given:
        itemRepository.findById(7L) >> Optional.of(itemFor())
        repo.findActiveFor(42L, 7L) >> Optional.empty()
        repo.countByUserIdAndStatus(42L, 'ACTIVE') >> 0L
        repo.save(_) >> { args -> args[0].id = 100L; args[0] }

        when:
        def a = service.upsertAlert(42L, 7L, new BigDecimal('0.005'))

        then: 'rounded up to the storage scale — saved as a real $0.01 target'
        a.targetPrice == new BigDecimal('0.01')
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

        then: 'sweep absorbs the per-row failure — a2 still fires clean'
        a2.status == 'FIRED'
        noExceptionThrown()
        // Wave 112 race-claim: a1 already had its ACTIVE → FIRED claim
        // committed (claimForFiring returned 1) BEFORE the push throws.
        // We accept the missed-push trade-off over duplicate pushes in a
        // multi-pod cluster: the row stays FIRED so next tick won't
        // re-attempt and spray a second notification once the transient
        // downstream issue clears.
        a1.status == 'FIRED'
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
        repo.findTriggeredForItem(7L, _) >> [[a, new BigDecimal('8.00'), 'Wizard Hat'] as Object[]]
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
        0 * repo.findTriggeredForItem(_, _)
        0 * notificationService.push(_, _, _, _, _, _)
    }

    def "sweepForItem is a no-op when the scoped query returns nothing"() {
        given:
        repo.findTriggeredForItem(7L, _) >> []

        when:
        service.sweepForItem(7L)

        then:
        0 * notificationService.push(_, _, _, _, _, _)
        0 * repo.save(_)
    }

    def "sweepForItem swallows a repository failure — best-effort, never rethrows"() {
        given: 'the scoped query itself blows up'
        repo.findTriggeredForItem(7L, _) >> { throw new RuntimeException('db boom') }

        when:
        service.sweepForItem(7L)

        then: 'caller (the sell transaction) is shielded from the failure'
        noExceptionThrown()
    }

    def "sweepForItem skips the push for a banned user but still flips the alert to FIRED"() {
        given:
        def a = new WatchlistAlert(id: 1L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('10'), status: 'ACTIVE')
        repo.findTriggeredForItem(7L, _) >> [[a, new BigDecimal('8.00'), 'Wizard Hat'] as Object[]]
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

    // ── sync sweep row-cap (wave 108 — un-paged sweepForItem on viral item) ──
    //
    // Pinning spec for the OOM-on-hot-item bug: a single fresh listing on
    // a watched item could collect tens of thousands of triggered alerts,
    // and the synchronous per-item sweep used the UN-paged repo method
    // (`findTriggeredForItem(itemId)`) — so every triggered row was
    // hydrated into a single `List<Object[]>` on the sell-request thread
    // before fan-out began. PER_USER_LIMIT caps alerts per USER (50), but
    // there is NO per-ITEM watcher cap, so a viral item (sticker capsule
    // release, market-moving sale) routinely sat on hundreds-to-thousands
    // of alerts. Coupled with an inline notification + email fan-out, the
    // hot-path either OOM'd the JVM on under-provisioned nodes or blocked
    // the sell HTTP request for the full sweep duration.
    //
    // Fix shape: a paged repository overload + a SYNC_SWEEP_ITEM_CAP
    // ceiling enforced at the SQL LIMIT level (NOT in-memory after the
    // un-paged load). Overflow rolls into the next 5-minute scheduled
    // sweep, which still picks up the un-fired rows (their `status`
    // stays ACTIVE until `fireRow` flips them).
    //
    // Three asserts pin the contract:
    //   1) the paged method is the one actually called (regression guard
    //      against a future refactor reverting to the un-paged shape),
    //   2) the page size passed in equals SYNC_SWEEP_ITEM_CAP (so a
    //      future tuning of the constant lands here too), and
    //   3) the un-paged single-arg method is NEVER hit on this code
    //      path (the bug was specifically the un-paged call).

    def "sweepForItem uses the paged repo overload with SYNC_SWEEP_ITEM_CAP (wave 108 OOM guard)"() {
        given: 'a triggered alert that the sweep should fire'
        def a = new WatchlistAlert(id: 1L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('10'), status: 'ACTIVE')
        org.springframework.data.domain.Pageable captured = null
        repo.findTriggeredForItem(7L, _ as org.springframework.data.domain.Pageable) >> {
            Long itemId, org.springframework.data.domain.Pageable p ->
            captured = p
            [[a, new BigDecimal('8.00'), 'Wizard Hat'] as Object[]]
        }
        repo.save(_) >> { args -> args[0] }

        when:
        service.sweepForItem(7L)

        then: 'the paged variant was called — never the un-paged single-arg one'
        0 * repo.findTriggeredForItem(7L)

        and: 'the page size handed to the repo matches the documented cap (not unbounded, not a default 20)'
        captured != null
        captured.pageSize == WatchlistAlertService.SYNC_SWEEP_ITEM_CAP
        captured.pageNumber == 0

        and: 'the row that came back still fires — behaviour is unchanged for in-cap inputs'
        a.status == 'FIRED'
        1 * notificationService.push(42L, 'WATCHLIST_PRICE_DROP', _, _, 7L, '/item/7')
    }

    // ── multi-pod race claim (wave 112 duplicate-push guard) ──────
    //
    // Production hazard: `@Scheduled(fixedDelay)` only serialises ticks
    // WITHIN one JVM. On a two-pod cluster both schedulers fire roughly
    // together, both call `findTriggered()`, both pull the SAME ACTIVE
    // row, and (before this fix) both invoked
    // `notificationService.push` + `emailService.sendPriceDrop` BEFORE
    // either persisted `status=FIRED`. The user got two duplicate
    // "Price drop" pushes (the in-app bell shows two of the same item
    // notification stacked) and — because the email dedup ledger is a
    // per-pod in-memory `LinkedHashMap` — up to two duplicate emails
    // per fired alert too.
    //
    // Fix shape: convert the FIRED flip into a conditional UPDATE that
    // matches on `status='ACTIVE'` and returns the affected row count.
    // The repo method (`claimForFiring`) is the authoritative gate —
    // whichever pod's UPDATE lands first returns 1 and proceeds to
    // push + email; the loser returns 0 and bails BEFORE any
    // user-facing side-effect. The same conditional gate also closes
    // the cancel-vs-sweep race (user clicks Cancel between
    // findTriggered() and the claim → row is now CANCELLED → claim
    // returns 0 → no push lands on a row the user just disowned).
    //
    // Three asserts pin the contract:
    //   1) sweep calls claimForFiring with the alert id BEFORE the
    //      push (regression guard against re-ordering the claim after
    //      the side-effects),
    //   2) when claimForFiring returns 0 (sibling pod won the race or
    //      user cancelled mid-tick), NO push and NO email lands, and
    //   3) the in-memory alert object is NOT mutated to FIRED on a
    //      lost claim — important for any test or caller asserting
    //      "FIRED rows = rows we successfully fired this tick".

    def "fireRow performs the conditional ACTIVE→FIRED claim BEFORE pushing the notification (wave 112)"() {
        given: 'a triggered alert headed into the sweep'
        def a = new WatchlistAlert(id: 99L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('10'), status: 'ACTIVE')
        repo.findTriggered() >> [[a, new BigDecimal('8.00'), 'Wizard Hat'] as Object[]]

        // Stub the claim so we can observe call ordering relative to
        // the push. Returns 1 (this pod wins) so the rest of fireRow
        // continues normally.
        def callOrder = []
        repo.claimForFiring(99L, _) >> { args ->
            callOrder << 'claim'
            return 1
        }
        notificationService.push(42L, _, _, _, _, _) >> { args ->
            callOrder << 'push'
        }

        when:
        service.sweep()

        then: 'claim happens FIRST — push is gated by a successful claim'
        callOrder == ['claim', 'push']

        and: 'the in-memory mirror also flips so caller assertions still see FIRED'
        a.status == 'FIRED'
        a.firedAt != null
    }

    def "fireRow swallows the push when the conditional claim returns 0 (sibling pod won the race)"() {
        given: 'two pods both see the same ACTIVE row — this pod loses the race'
        def a = new WatchlistAlert(id: 99L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('10'), status: 'ACTIVE')
        repo.findTriggered() >> [[a, new BigDecimal('8.00'), 'Wizard Hat'] as Object[]]
        steamUserRepository.findById(42L) >> Optional.of(new com.sboxmarket.model.SteamUser(
            id: 42L, email: 'buyer@example.com', emailVerified: true,
            emailNotificationsEnabled: true, displayName: 'Alice'
        ))
        // Sibling pod already flipped this row to FIRED — our UPDATE
        // matches zero rows (status='ACTIVE' filter fails).
        repo.claimForFiring(99L, _) >> 0

        when:
        service.sweep()

        then: 'no duplicate notification — the bell does NOT get a second WATCHLIST_PRICE_DROP'
        0 * notificationService.push(_, _, _, _, _, _)

        and: 'no duplicate email — the inbox does NOT get a second price-drop send'
        0 * emailService.sendPriceDrop(_, _, _, _, _, _)

        and: 'this pod did NOT mutate the in-memory alert — the row was the sibling pod\'s to flip'
        a.status == 'ACTIVE'
        a.firedAt == null
    }

    def "fireRow swallows the push when claim returns 0 because the user cancelled mid-tick (cancel-race)"() {
        given: 'sweep saw the row as ACTIVE, but the user cancelled it before fireRow ran'
        def a = new WatchlistAlert(id: 99L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('10'), status: 'ACTIVE')
        repo.findTriggered() >> [[a, new BigDecimal('8.00'), 'Wizard Hat'] as Object[]]
        // Conditional UPDATE matches zero rows because status is now
        // CANCELLED — the cancel-vs-sweep race closes through the same
        // gate as the cross-pod race.
        repo.claimForFiring(99L, _) >> 0

        when:
        service.sweep()

        then: 'no push lands on a row the user just disowned'
        0 * notificationService.push(_, _, _, _, _, _)
        0 * emailService.sendPriceDrop(_, _, _, _, _, _)
    }

    def "sweepForItem also gates push behind the conditional claim (sync-sweep multi-pod parity)"() {
        given: 'fresh listing triggers a sync sweep on a hot item; sibling pod claims it first'
        def a = new WatchlistAlert(id: 77L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('10'), status: 'ACTIVE')
        repo.findTriggeredForItem(7L, _) >> [[a, new BigDecimal('8.00'), 'Wizard Hat'] as Object[]]
        repo.claimForFiring(77L, _) >> 0   // lost the race

        when:
        service.sweepForItem(7L)

        then: 'same gate applies to the synchronous per-item path — no duplicate spray'
        0 * notificationService.push(_, _, _, _, _, _)
        0 * emailService.sendPriceDrop(_, _, _, _, _, _)
        a.status == 'ACTIVE'
    }

    def "fireRow does NOT call repo.save — the conditional UPDATE is the only write path (wave 112)"() {
        given: 'a normal triggered row that the sweep should fire'
        def a = new WatchlistAlert(id: 88L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('10'), status: 'ACTIVE')
        repo.findTriggered() >> [[a, new BigDecimal('8.00'), 'Wizard Hat'] as Object[]]
        repo.claimForFiring(88L, _) >> 1

        when:
        service.sweep()

        then: 'the FIRED write happens via claimForFiring — repo.save is dead code on the sweep hot path'
        0 * repo.save(_)

        and: 'in-memory mirror still reflects FIRED so caller / UI sees the flip immediately'
        a.status == 'FIRED'
        a.firedAt != null
    }
}
