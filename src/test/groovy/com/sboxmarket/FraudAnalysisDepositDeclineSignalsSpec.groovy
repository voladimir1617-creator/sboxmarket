package com.sboxmarket

import com.sboxmarket.model.AuditLog
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.AuditLogRepository
import com.sboxmarket.repository.EscrowedItemRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.FraudAnalysisService
import com.sboxmarket.service.NotificationService
import spock.lang.Specification

/**
 * The two deposit-decline signals: DEPOSIT_DECLINED_ITEM_GONE (HIGH, from the
 * audit row the escrow re-check writes, belled to admins by the sweeper) and
 * FAST_DEPOSIT_DECLINES (MED, from the custody table over 7 days).
 */
class FraudAnalysisDepositDeclineSignalsSpec extends Specification {

    AuditLogRepository auditLogRepository = Mock()
    EscrowedItemRepository escrowedItemRepository = Mock()
    SteamUserRepository steamUserRepository = Mock()
    NotificationService notificationService = Mock()

    FraudAnalysisService service = new FraudAnalysisService(
            auditLogRepository: auditLogRepository,
            escrowedItemRepository: escrowedItemRepository,
            steamUserRepository: steamUserRepository,
            notificationService: notificationService)

    private static AuditLog goneRow(Long id, Long seller = 7L) {
        new AuditLog(id: id, eventType: AuditService.DEPOSIT_DECLINED_ITEM_GONE, subjectUserId: seller,
                subjectName: 'victim', resourceId: 5L, summary: 'asset 555 gone', createdAt: System.currentTimeMillis())
    }

    def "a DEPOSIT_DECLINED_ITEM_GONE audit row is a HIGH signal for the seller"() {
        given:
        auditLogRepository.since(_) >> [goneRow(41L)]
        escrowedItemRepository.countFastDeclinesBySeller(_, _, _) >> []

        when:
        def signals = service.computeSignals()

        then:
        signals.size() == 1
        signals[0].type == 'DEPOSIT_DECLINED_ITEM_GONE'
        signals[0].severity == 'HIGH'
        signals[0].userId == 7L
        signals[0].dedupKey == 41L
        signals[0].summary.contains('asset 555 gone')
    }

    def "the sweeper bells every admin once per DEPOSIT_DECLINED_ITEM_GONE row"() {
        given:
        auditLogRepository.since(_) >> [goneRow(41L), goneRow(42L)]
        escrowedItemRepository.countFastDeclinesBySeller(_, _, _) >> []
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 900L, role: 'ADMIN')]

        when:
        service.sweepAndPushFraudSignals()
        service.sweepAndPushFraudSignals()

        then:
        2 * notificationService.push(900L, 'FRAUD_SIGNAL_HIGH', { it.contains('DEPOSIT_DECLINED_ITEM_GONE') }, _, 7L, '/admin?tab=fraud')
    }

    def "fast declines per seller are a MED signal and read the 5 min / 7 day / 2+ thresholds"() {
        given:
        long now = System.currentTimeMillis()
        auditLogRepository.since(_) >> []
        steamUserRepository.findById(7L) >> Optional.of(new SteamUser(id: 7L, displayName: 'seller7'))

        when:
        def signals = service.computeSignals()

        then:
        1 * escrowedItemRepository.countFastDeclinesBySeller(
                { long since -> Math.abs(since - (now - 7L * 24 * 3600_000L)) < 5000L },
                5L * 60_000L, 2L) >> [[7L, 3L, now - 1000L] as Object[]]
        signals.size() == 1
        signals[0].type == 'FAST_DEPOSIT_DECLINES'
        signals[0].severity == 'MED'
        signals[0].userId == 7L
        signals[0].count == 3L
        signals[0].userName == 'seller7'
    }

    def "a MED fast-decline signal is not belled"() {
        given:
        auditLogRepository.since(_) >> []
        escrowedItemRepository.countFastDeclinesBySeller(_, _, _) >> [[7L, 2L, System.currentTimeMillis()] as Object[]]
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 900L, role: 'ADMIN')]

        when:
        service.sweepAndPushFraudSignals()

        then:
        0 * notificationService.push(*_)
    }

    def "a failing fast-decline query leaves the other signals intact"() {
        given:
        auditLogRepository.since(_) >> [goneRow(41L)]
        escrowedItemRepository.countFastDeclinesBySeller(_, _, _) >> { throw new RuntimeException('db blip') }

        when:
        def signals = service.computeSignals()

        then:
        signals*.type == ['DEPOSIT_DECLINED_ITEM_GONE']
    }

    def "without the custody repository the signal is simply absent"() {
        given:
        def bare = new FraudAnalysisService(auditLogRepository: auditLogRepository)
        auditLogRepository.since(_) >> []

        expect:
        bare.computeSignals() == []
    }
}
