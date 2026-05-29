package com.sboxmarket

import com.sboxmarket.service.LoadoutService
import org.springframework.transaction.annotation.Transactional
import spock.lang.Specification

import java.lang.reflect.Method

/**
 * Wave 139 regression pin for {@link LoadoutService#toggleFavorite}.
 *
 * Same rollback-only leak class as waves 136-138 sibling fixes. The
 * @Transactional MUST carry noRollbackFor = DataIntegrityViolationException
 * so the in-catch rapid-double-tap recovery path can actually commit.
 */
class LoadoutServiceToggleFavoriteRaceTxRollbackSpec extends Specification {

    def "toggleFavorite carries noRollbackFor = DataIntegrityViolationException"() {
        given:
        Method m = LoadoutService.class.getDeclaredMethod('toggleFavorite', Long, Long)

        when:
        Transactional tx = m.getAnnotation(Transactional)

        then:
        tx != null
        tx.noRollbackFor().any { Class<?> c ->
            c.name == 'org.springframework.dao.DataIntegrityViolationException'
        }
    }
}
