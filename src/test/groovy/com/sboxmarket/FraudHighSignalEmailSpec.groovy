package com.sboxmarket

import com.sboxmarket.model.AuditLog
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.AuditLogRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.FraudAnalysisService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.OpsAlertService
import spock.lang.Specification

/**
 * A HIGH fraud bell that lands is also handed to the ops email path, once per
 * new signal (the sweeper's own dedupe still applies), and the bells are
 * unchanged.
 */
class FraudHighSignalEmailSpec extends Specification {

    AuditLogRepository auditLogRepository = Mock()
    NotificationService notificationService = Mock()
    SteamUserRepository steamUserRepository = Mock()
    OpsAlertService ops = Mock()

    private static AuditLog row(Long actor, String ip, long ts) {
        new AuditLog(id: 0L, eventType: com.sboxmarket.service.AuditService.LISTING_PURCHASED, actorUserId: actor, actorName: "user${actor}", resourceId: 100L, summary: 'test', ipAddress: ip, userAgent: 'spec-agent', createdAt: ts)
    }

    def "a landed HIGH bell triggers one admin email; a repeat pass sends none"() {
        given:
        def svc = new FraudAnalysisService(auditLogRepository: auditLogRepository,
                notificationService: notificationService, steamUserRepository: steamUserRepository,
                opsAlertService: ops)
        long t = System.currentTimeMillis()
        auditLogRepository.since(_) >> (1..6).collect { i -> row(1L, "10.0.0.${i}".toString(), t) }
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 11L, role: 'ADMIN')]

        when:
        svc.sweepAndPushFraudSignals()

        then:
        1 * notificationService.push(11L, 'FRAUD_SIGNAL_HIGH', _, _, _, '/admin?tab=fraud')
        1 * ops.emailAdmins('FRAUD_SIGNAL_HIGH', { it.startsWith('Fraud signal: ') }, _)

        when:
        svc.sweepAndPushFraudSignals()

        then:
        0 * notificationService.push(*_)
        0 * ops.emailAdmins(*_)
    }

    def "no bell landed, no email"() {
        given:
        def svc = new FraudAnalysisService(auditLogRepository: auditLogRepository,
                notificationService: notificationService, steamUserRepository: steamUserRepository,
                opsAlertService: ops)
        long t = System.currentTimeMillis()
        auditLogRepository.since(_) >> (1..6).collect { i -> row(1L, "10.0.0.${i}".toString(), t) }
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 11L, role: 'ADMIN')]
        notificationService.push(*_) >> { throw new RuntimeException('bell down') }

        when:
        svc.sweepAndPushFraudSignals()

        then:
        0 * ops.emailAdmins(*_)
    }

    def "an email failure does not stop the sweep"() {
        given:
        def svc = new FraudAnalysisService(auditLogRepository: auditLogRepository,
                notificationService: notificationService, steamUserRepository: steamUserRepository,
                opsAlertService: ops)
        long t = System.currentTimeMillis()
        auditLogRepository.since(_) >> (1..6).collect { i -> row(1L, "10.0.0.${i}".toString(), t) }
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 11L, role: 'ADMIN')]
        ops.emailAdmins(*_) >> { throw new RuntimeException('smtp') }

        when:
        svc.sweepAndPushFraudSignals()

        then:
        noExceptionThrown()
    }
}
