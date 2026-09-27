package com.sboxmarket

import com.sboxmarket.service.SavedSearchService
import org.springframework.transaction.annotation.Transactional
import spock.lang.Specification

import java.lang.reflect.Method

/**
 * Wave 140 regression pin for {@link SavedSearchService#upsert(Long, Map)}.
 * Same systematic rollback-only leak class as waves 136-139.
 */
class SavedSearchServiceUpsertRaceTxRollbackSpec extends Specification {

    def "upsert carries noRollbackFor = DataIntegrityViolationException"() {
        given:
        Method m = SavedSearchService.class.getDeclaredMethod('upsert', Long, Map)

        when:
        Transactional tx = m.getAnnotation(Transactional)

        then:
        tx != null
        tx.noRollbackFor().any { Class<?> c ->
            c.name == 'org.springframework.dao.DataIntegrityViolationException'
        }
    }
}
