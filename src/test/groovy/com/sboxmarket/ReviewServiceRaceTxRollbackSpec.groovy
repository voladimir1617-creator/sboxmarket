package com.sboxmarket

import com.sboxmarket.service.ReviewService
import org.springframework.transaction.annotation.Transactional
import spock.lang.Specification

import java.lang.reflect.Method

/**
 * Wave 137 regression pin for {@link ReviewService#leaveReview} and
 * {@link ReviewService#toggleHelpful}.
 *
 * Both methods are @Transactional and catch
 * DataIntegrityViolationException internally to handle a concurrent
 * UNIQUE-constraint race (two requests for the same (buyer, trade) or
 * (review, user) pair). Spring's DIVE translator marks the SHARED outer
 * tx rollback-only BEFORE the catch fires, so without
 * noRollbackFor every write inside the same worker thread silently
 * rolls back at commit time and the controller's 200 is followed by
 * UnexpectedRollbackException.
 *
 * Same shape as wave 136 (UserBlockService), wave 137 SellerFollowService,
 * WatchlistService.add — structural pin via reflection on the
 * @Transactional annotation's noRollbackFor list.
 */
class ReviewServiceRaceTxRollbackSpec extends Specification {

    def "leaveReview carries noRollbackFor = DataIntegrityViolationException so the in-catch recovery can commit"() {
        given:
        Method m = ReviewService.class.getDeclaredMethod('leaveReview',
            Long, Long, Integer, String)

        when:
        Transactional tx = m.getAnnotation(Transactional)

        then:
        tx != null
        tx.noRollbackFor().any { Class<?> c ->
            c.name == 'org.springframework.dao.DataIntegrityViolationException'
        }
    }

    def "toggleHelpful carries noRollbackFor = DataIntegrityViolationException so the in-catch recovery can commit"() {
        given:
        Method m = ReviewService.class.getDeclaredMethod('toggleHelpful', Long, Long)

        when:
        Transactional tx = m.getAnnotation(Transactional)

        then:
        tx != null
        tx.noRollbackFor().any { Class<?> c ->
            c.name == 'org.springframework.dao.DataIntegrityViolationException'
        }
    }
}
