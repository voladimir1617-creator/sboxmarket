package com.sboxmarket

import com.sboxmarket.model.Item
import com.sboxmarket.model.WatchlistAlert
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.WatchlistAlertRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.WatchlistAlertService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Wave 113 — stale-alert revival race between `upsertAlert` (user re-
 * arming the same item with a new target) and `sweep` (scheduled tick
 * claiming the row FIRED).
 *
 * Production hazard: a user with an ACTIVE alert at $10 watches the
 * price drop to $9. The scheduled sweep tick fires the alert: the
 * conditional `claimForFiring` (wave 112) lands `status='FIRED'` in
 * the DB and the WATCHLIST_PRICE_DROP push + email go out. Meanwhile
 * the user — seeing the floor at $9 — decides $8 is the real target
 * they want, and pulls the alert modal up to re-arm at $8. Their
 * `upsertAlert` runs `findActiveFor(uid, itemId)` BEFORE the sweep's
 * `claimForFiring` commits (or just before — depends on tx interleave),
 * sees status='ACTIVE', loads the entity into memory, mutates
 * targetPrice + createdAt, and calls `repo.save(a)`. JPA's save is a
 * MERGE: it writes EVERY mapped column, including the in-memory
 * `status='ACTIVE'` value the entity was loaded with. The sweep's
 * FIRED flip is silently REVERTED.
 *
 * End result: the user got one "Price drop · X" push + email this
 * tick (sweep's claim had committed), the row is now ACTIVE again
 * with the new $8 target, and the NEXT sweep tick will re-fire on
 * the same lowestPrice — spraying a duplicate push and (per-pod
 * dedup-ledger LRU eviction permitting) a duplicate email. From
 * the user's seat: "Why am I getting two of these?".
 *
 * Fix shape (mirrors the wave 112 `claimForFiring` race-gate):
 * convert the re-arm path from `repo.save(a)` into a CONDITIONAL
 * UPDATE that matches on `status='ACTIVE'`. The
 * {@link WatchlistAlertRepository#updateActiveTarget} method writes
 * only `targetPrice` + `createdAt` and filters by ACTIVE — so a row
 * that has FIRED / CANCELLED since `findActiveFor` ran is left alone
 * (affected-rows == 0), and the service falls through to the create-
 * new path (the user's intent — "an active alert at $X" — still
 * lands as a brand-new ACTIVE row without resurrecting the spent
 * one). The same gate also closes the cancel-vs-upsert race (user
 * cancels on one device, immediately re-arms on another).
 */
class WatchlistAlertReArmRaceSpec extends Specification {

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
        new Item(id: id, name: 'Wizard Hat', lowestPrice: new BigDecimal('9.00'))
    }

    def "upsertAlert re-arm path uses the conditional UPDATE — never repo.save (wave 113 revival guard)"() {
        given: 'a user with an existing ACTIVE alert that they want to re-arm at a new target'
        def existing = new WatchlistAlert(id: 9L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('10.00'), status: 'ACTIVE',
            createdAt: 1L)
        itemRepository.findById(7L) >> Optional.of(itemFor())
        repo.findActiveFor(42L, 7L) >> Optional.of(existing)
        // Conditional UPDATE lands cleanly — row was still ACTIVE.
        repo.updateActiveTarget(9L, new BigDecimal('8.00'), _) >> 1

        when:
        def updated = service.upsertAlert(42L, 7L, new BigDecimal('8.00'))

        then: 'the re-arm went through the conditional UPDATE'
        1 * repo.updateActiveTarget(9L, new BigDecimal('8.00'), _) >> 1

        and: 'JPA MERGE is NEVER used on the re-arm path — that was the bug'
        0 * repo.save(_)

        and: 'the in-memory entity reflects the new target + a fresh createdAt'
        updated.id == 9L
        updated.targetPrice == new BigDecimal('8.00')
        updated.createdAt > 1L
        updated.status == 'ACTIVE'

        and: 'no quota check on the re-arm — only the create-new path hits the cap'
        0 * repo.countByUserIdAndStatus(_, _)
    }

    def "upsertAlert falls through to create-new when the row FIRED between findActiveFor and the UPDATE (wave 113 race)"() {
        given: 'sweep claimed FIRED concurrently — findActiveFor saw the stale ACTIVE view, UPDATE will miss'
        def existing = new WatchlistAlert(id: 9L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('10.00'), status: 'ACTIVE',
            createdAt: 1L)
        itemRepository.findById(7L) >> Optional.of(itemFor())
        repo.findActiveFor(42L, 7L) >> Optional.of(existing)
        // The row has flipped to FIRED in the DB — the WHERE status='ACTIVE'
        // clause in updateActiveTarget matches zero rows.
        repo.updateActiveTarget(9L, _, _) >> 0
        repo.countByUserIdAndStatus(42L, 'ACTIVE') >> 1L
        repo.save(_) >> { args -> args[0].id = 100L; args[0] }

        when:
        def alert = service.upsertAlert(42L, 7L, new BigDecimal('8.00'))

        then: 'the stale FIRED row is NOT resurrected — a fresh ACTIVE row is created instead'
        1 * repo.save({ WatchlistAlert a ->
            a.userId == 42L && a.itemId == 7L &&
            a.targetPrice == new BigDecimal('8.00') &&
            a.status == 'ACTIVE'
        }) >> { args -> args[0].id = 100L; args[0] }

        and: 'the created row is the one returned'
        alert.id == 100L
        alert.status == 'ACTIVE'
        alert.targetPrice == new BigDecimal('8.00')

        and: 'the original FIRED row was NEVER touched by the service'
        existing.status == 'ACTIVE'  // in-memory copy unmutated; DB row stays FIRED untouched
    }

    def "upsertAlert falls through to create-new when the row CANCELLED between findActiveFor and the UPDATE (cancel-vs-rearm race)"() {
        given: 'user cancelled on device A, re-armed on device B — A landed first, B sees stale ACTIVE'
        def existing = new WatchlistAlert(id: 9L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('10.00'), status: 'ACTIVE',
            createdAt: 1L)
        itemRepository.findById(7L) >> Optional.of(itemFor())
        repo.findActiveFor(42L, 7L) >> Optional.of(existing)
        // Row is CANCELLED in the DB — the WHERE status='ACTIVE' clause misses.
        repo.updateActiveTarget(9L, _, _) >> 0
        repo.countByUserIdAndStatus(42L, 'ACTIVE') >> 0L
        repo.save(_) >> { args -> args[0].id = 101L; args[0] }

        when:
        def alert = service.upsertAlert(42L, 7L, new BigDecimal('8.00'))

        then: 'a brand-new ACTIVE row lands — the cancel from device A is NOT silently reverted'
        1 * repo.save({ WatchlistAlert a -> a.status == 'ACTIVE' }) >> { args -> args[0] }
        alert.status == 'ACTIVE'
        alert.targetPrice == new BigDecimal('8.00')
    }

    def "upsertAlert re-arm respects the conditional UPDATE return — affected=0 means do NOT mutate the entity"() {
        given: 'a row that has flipped under us; verify we do NOT lie to the caller'
        def existing = new WatchlistAlert(id: 9L, userId: 42L, itemId: 7L,
            targetPrice: new BigDecimal('10.00'), status: 'ACTIVE',
            createdAt: 1L)
        itemRepository.findById(7L) >> Optional.of(itemFor())
        repo.findActiveFor(42L, 7L) >> Optional.of(existing)
        // Lost the race — affected=0.
        repo.updateActiveTarget(9L, _, _) >> 0
        repo.countByUserIdAndStatus(42L, 'ACTIVE') >> 0L
        repo.save(_) >> { args -> args[0].id = 200L; args[0] }

        when:
        service.upsertAlert(42L, 7L, new BigDecimal('8.00'))

        then: 'the in-memory existing entity was NOT touched — only the create-new path mutates state'
        existing.targetPrice == new BigDecimal('10.00')
        existing.createdAt == 1L
    }
}
