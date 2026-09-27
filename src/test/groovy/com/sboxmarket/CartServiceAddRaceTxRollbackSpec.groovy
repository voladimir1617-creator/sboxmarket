package com.sboxmarket

import com.sboxmarket.service.CartService
import org.springframework.transaction.annotation.Transactional
import spock.lang.Specification

import java.lang.reflect.Method

/**
 * Wave 139 regression pin for {@link CartService#add(Long, Long)}.
 *
 * Same rollback-only leak class as waves 136 (UserBlockService.block),
 * 137 (SellerFollowService.follow), 138 (ReviewService leaveReview/
 * toggleHelpful + WatchlistService.add). The catch(DataIntegrityViolation
 * Exception) recovery path inside add() runs but the SHARED outer
 * @Transactional has already been marked rollback-only by Spring's DIVE
 * translator BEFORE the catch fires. The recovery's "treat as benign
 * no-op" path commits nothing and the 200 response is followed by
 * UnexpectedRollbackException at commit time.
 *
 * Structural pin via reflection — same shape as the sibling
 * UserBlockServiceRaceTxRollbackSpec / SellerFollowServiceRaceTxRollbackSpec.
 */
class CartServiceAddRaceTxRollbackSpec extends Specification {

    def "add() carries noRollbackFor = DataIntegrityViolationException so the in-catch recovery can commit"() {
        given:
        Method m = CartService.class.getDeclaredMethod('add', Long, Long)

        when:
        Transactional tx = m.getAnnotation(Transactional)

        then:
        tx != null
        tx.noRollbackFor().any { Class<?> c ->
            c.name == 'org.springframework.dao.DataIntegrityViolationException'
        }
    }
}
