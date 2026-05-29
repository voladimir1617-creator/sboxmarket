package com.sboxmarket

import com.sboxmarket.service.FraudAnalysisService
import org.springframework.transaction.annotation.Transactional
import spock.lang.Specification

import java.lang.reflect.Method

/**
 * Wave 140 regression pin for {@link FraudAnalysisService#sweepAndPushFraudSignals}.
 *
 * Without noRollbackFor on the @Transactional outer, Spring's DIVE
 * translator marks the sweep tx rollback-only when the wave-120
 * multi-pod claim-and-bail (catch DIVE at line 467) absorbs the
 * sibling-pod-INSERT-won-first race. Every signal already pushed to
 * admins in this sweep tick then rolls back at commit time — defeating
 * the per-pod-per-signal claim ledger (V70 fraud_signal_claims) wave
 * 120 was built to enforce.
 */
class FraudAnalysisServiceSweepRaceTxRollbackSpec extends Specification {

    def "sweepAndPushFraudSignals carries noRollbackFor = DataIntegrityViolationException"() {
        given:
        Method m = FraudAnalysisService.class.getDeclaredMethod('sweepAndPushFraudSignals')

        when:
        Transactional tx = m.getAnnotation(Transactional)

        then:
        tx != null
        tx.noRollbackFor().any { Class<?> c ->
            c.name == 'org.springframework.dao.DataIntegrityViolationException'
        }
    }
}
