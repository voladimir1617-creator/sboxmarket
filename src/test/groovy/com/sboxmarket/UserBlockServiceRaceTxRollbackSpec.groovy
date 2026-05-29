package com.sboxmarket

import com.sboxmarket.service.UserBlockService
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Transactional
import spock.lang.Specification

/**
 * Regression pin for the Spring-transaction-poisoning class of bug on
 * UserBlockService.block().
 *
 * The TOCTOU recovery (commit 6418f56) wraps save() in a try/catch for
 * DataIntegrityViolationException and re-reads the winner row via
 * findByBlocker. The intent is that a rapid double-tap of the Block
 * button surfaces the already-committed row instead of a 500
 * INTERNAL_ERROR. The unit spec
 *   "block treats a UNIQUE-constraint race as a graceful no-op, not a 500"
 * uses Spock mocks against a hand-constructed UserBlockService — there
 * is NO Spring transaction in that path, so the test passes regardless
 * of the @Transactional posture.
 *
 * The production catch is NOT enough on its own. Spring's @Transactional
 * proxy marks the transaction rollback-only on ANY RuntimeException
 * thrown from a JPA operation, even when the service swallows it. The
 * recovery's findByBlocker read therefore runs against an already-doomed
 * transaction, and the proxy's commit at method exit fires
 * UnexpectedRollbackException("Transaction silently rolled back because
 * it has been marked as rollback-only") — surfacing as a 500 to the
 * caller despite the in-method catch.
 *
 * The fix is the `noRollbackFor = DataIntegrityViolationException` hint
 * on the @Transactional annotation: Spring then skips the rollback-only
 * marking for that exception class and the in-method recovery commits
 * cleanly with the winning row.
 *
 * This spec asserts the annotation directly via reflection — fast,
 * deterministic, and independent of whether the test profile happens
 * to exercise the catch path. Mirrors the
 * SideEffectTransactionIsolationIntegrationSpec posture for the
 * AuditService / NotificationService deferral fixes which proved the
 * same Spring rollback-only behaviour against real H2 + a real
 * TransactionTemplate.
 */
class UserBlockServiceRaceTxRollbackSpec extends Specification {

    def "block() carries noRollbackFor = DataIntegrityViolationException so the in-catch recovery can commit"() {
        when: 'inspect the public block(Long, Long) method'
        def method = UserBlockService.getMethod('block', Long, Long)
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
