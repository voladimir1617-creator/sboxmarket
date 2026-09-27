package com.sboxmarket

import com.sboxmarket.repository.TradeMessageRepository
import org.springframework.data.jpa.repository.Modifying
import spock.lang.Specification

/**
 * Pin: `markIncomingRead` MUST run with `clearAutomatically=true` AND
 * `flushAutomatically=true`.
 *
 * Why this is a contract, not an implementation detail:
 *  - TradeService.listMessages calls markIncomingRead and then
 *    immediately calls findByTradeRecent in the SAME transaction.
 *    Bulk JPQL UPDATE bypasses Hibernate's dirty-tracking, so any
 *    TradeMessage instances already in the L1 persistence context
 *    keep their pre-update `readAt = null` state. The re-fetch
 *    returns those cached entities and the response payload renders
 *    the just-marked-read messages as STILL unread — the "✓✓ read"
 *    indicator lags by one full poll cycle.
 *  - `flushAutomatically=true` covers the symmetric direction: any
 *    dirty TradeMessage save in the same tx (a counterparty posting
 *    right before the viewer opens the thread) needs to land in the
 *    DB before the UPDATE's WHERE clause evaluates, or the new row
 *    escapes the `readAt IS NULL` filter even though it qualifies.
 */
class TradeMessageRepositoryContractSpec extends Specification {

    def "markIncomingRead must enable clearAutomatically + flushAutomatically"() {
        given:
        def method = TradeMessageRepository.getMethod(
            'markIncomingRead', Long, Long, Long)

        when:
        Modifying mod = method.getAnnotation(Modifying)

        then:
        mod != null
        // Without clearAutomatically: stale L1-cached TradeMessage
        // entities would resurrect `readAt = null` in the same-tx
        // re-fetch, so the "✓✓ read" indicator lags by one poll.
        mod.clearAutomatically()
        // Without flushAutomatically: a dirty TradeMessage save in
        // the same tx (e.g. a counterparty's just-posted message)
        // could escape the WHERE filter and stay unread.
        mod.flushAutomatically()
    }
}
