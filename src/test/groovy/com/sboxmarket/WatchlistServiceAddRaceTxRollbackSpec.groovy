package com.sboxmarket

import com.sboxmarket.service.WatchlistService
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Transactional
import spock.lang.Specification

/**
 * Regression pin for the Spring-transaction-poisoning class of bug on
 * WatchlistService.add().
 *
 * The TOCTOU recovery on add() wraps repository.save() in a try/catch
 * for DataIntegrityViolationException so a same-user double-tap of the
 * star button — or a star fired from two devices at once — surfaces as
 * an idempotent no-op (return false) instead of a 500 INTERNAL_ERROR
 * from the `uq_watchlist_items_user_item` UNIQUE constraint (V30). The
 * unit spec WatchlistServiceSpec covers the catch behaviour with Spock
 * mocks against a hand-constructed WatchlistService — there is NO Spring
 * transaction in that path, so the test passes regardless of the
 * @Transactional posture.
 *
 * The production catch is NOT enough on its own. Spring's @Transactional
 * proxy marks the transaction rollback-only on ANY RuntimeException
 * thrown from a JPA operation, even when the service swallows it. The
 * method then commits a rollback-only tx and Spring fires
 * UnexpectedRollbackException("Transaction silently rolled back because
 * it has been marked as rollback-only") — surfacing as a 500 to the
 * losing client despite the in-method catch.
 *
 * The fix is the `noRollbackFor = DataIntegrityViolationException` hint
 * on the @Transactional annotation: Spring then skips the rollback-only
 * marking for that exception class and the method commits cleanly.
 *
 * This spec asserts the annotation directly via reflection. Mirrors
 * UserBlockServiceRaceTxRollbackSpec which pins the identical posture
 * on UserBlockService.block() — the canonical example called out by
 * WatchlistService.add()'s comment block.
 */
class WatchlistServiceAddRaceTxRollbackSpec extends Specification {

    def "add() carries noRollbackFor = DataIntegrityViolationException so the in-catch dup-key no-op can commit"() {
        when: 'inspect the public add(Long, Long) method'
        def method = WatchlistService.getMethod('add', Long, Long)
        def tx = method.getAnnotation(Transactional)

        then: '@Transactional is present and declares noRollbackFor for the dup-key class'
        tx != null
        // The recovery path catches DataIntegrityViolationException —
        // without this hint Spring still marks the tx rollback-only and
        // the method commit throws UnexpectedRollbackException, defeating
        // the entire purpose of the catch.
        DataIntegrityViolationException in (tx.noRollbackFor() as List)
    }
}
