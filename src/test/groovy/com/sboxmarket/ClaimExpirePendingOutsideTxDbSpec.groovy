package com.sboxmarket

import com.sboxmarket.model.Transaction
import com.sboxmarket.repository.TransactionRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/** The stale-deposit sweeper runs with no transaction of its own; its claim UPDATE must still execute. */
@SpringBootTest
@ActiveProfiles("test")
class ClaimExpirePendingOutsideTxDbSpec extends Specification {

    @Autowired TransactionRepository transactionRepository

    def "claimExpirePending flips a PENDING deposit once when called outside a transaction"() {
        given:
        def tx = transactionRepository.save(new Transaction(walletId: 999_001L, type: 'DEPOSIT', status: 'PENDING',
            amount: 5G, stripeReference: "cs_test_${System.nanoTime()}"))

        expect:
        transactionRepository.claimExpirePending(tx.id) == 1
        transactionRepository.claimExpirePending(tx.id) == 0
        transactionRepository.findById(tx.id).get().status == 'EXPIRED'
    }
}
