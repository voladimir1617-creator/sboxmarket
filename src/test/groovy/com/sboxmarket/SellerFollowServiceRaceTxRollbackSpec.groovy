package com.sboxmarket

import com.sboxmarket.service.SellerFollowService
import org.springframework.transaction.annotation.Transactional
import spock.lang.Specification

import java.lang.reflect.Method

/**
 * Wave 137 regression pin for {@link SellerFollowService#follow(Long, Long)}.
 *
 * The @Transactional annotation on follow() MUST carry
 * `noRollbackFor = [DataIntegrityViolationException]`. Without it, the
 * catch(DataIntegrityViolationException) recovery path inside follow()
 * runs but the SHARED outer tx has already been marked rollback-only by
 * Spring's DIVE translator — every write inside the same worker thread's
 * tx silently rolls back at commit time and the next request reads
 * inconsistent state.
 *
 * Same shape as wave 136 UserBlockServiceRaceTxRollbackSpec, with the
 * same TOCTOU race: double-tap Follow button or two devices firing
 * simultaneously, both observe `existing=empty`, both INSERT, V19's
 * UNIQUE(follower_user_id, seller_user_id) rejects the loser.
 *
 * This spec inspects the @Transactional annotation directly via
 * reflection — a structural pin that doesn't need a live Spring context.
 */
class SellerFollowServiceRaceTxRollbackSpec extends Specification {

    def "follow() carries noRollbackFor = DataIntegrityViolationException so the in-catch recovery can commit"() {
        given: "the follow(Long, Long) method declaration"
        Method follow = SellerFollowService.class.getDeclaredMethod('follow', Long, Long)

        when: "we inspect its @Transactional annotation"
        Transactional tx = follow.getAnnotation(Transactional)

        then: "the annotation is present"
        tx != null

        and: "noRollbackFor includes DataIntegrityViolationException — without this, the catch-and-recover path inside follow() runs but the shared outer tx is already rollback-only, so the recovery save commits NOTHING and the next request reads inconsistent state"
        tx.noRollbackFor().any { Class<?> c ->
            c.name == 'org.springframework.dao.DataIntegrityViolationException'
        }
    }
}
