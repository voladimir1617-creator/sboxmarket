package com.sboxmarket

import com.sboxmarket.model.AuditLog
import com.sboxmarket.repository.AuditLogRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.FraudAnalysisService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Pure-logic tests for FraudAnalysisService. The service only touches the
 * AuditLogRepository for read access, so we mock it and feed synthetic
 * rows straight into the detection rules.
 *
 * Every signal type has at least one positive case (trips the rule) and
 * one negative case (stays under the threshold). Severity classification
 * is asserted where the boundary matters.
 */
class FraudAnalysisServiceSpec extends Specification {

    AuditLogRepository auditLogRepository = Mock()

    @Subject
    FraudAnalysisService service = new FraudAnalysisService(auditLogRepository: auditLogRepository)

    private static long now() { System.currentTimeMillis() }

    private static AuditLog row(Map args = [:]) {
        new AuditLog(
            id:            args.id ?: 0L,
            actorUserId:   args.actor ?: 1L,
            actorName:     args.actorName ?: "user${args.actor ?: 1L}",
            subjectUserId: args.subject,
            eventType:     args.event ?: AuditService.LISTING_PURCHASED,
            resourceId:    args.resource ?: 100L,
            summary:       args.summary ?: 'test',
            ipAddress:     args.ip ?: '10.0.0.1',
            userAgent:     'spec-agent',
            createdAt:     args.ts ?: now()
        )
    }

    def "empty audit log returns empty signal list"() {
        when:
        auditLogRepository.since(_) >> []
        def signals = service.computeSignals()

        then:
        signals == []
    }

    // ── MULTIPLE_IPS_PER_USER ─────────────────────────────────────

    def "MULTIPLE_IPS_PER_USER fires when one user acts from 3+ distinct IPs"() {
        given:
        def rows = [
            row(actor: 42L, ip: '10.0.0.1', ts: now() - 1000),
            row(actor: 42L, ip: '10.0.0.2', ts: now() - 2000),
            row(actor: 42L, ip: '10.0.0.3', ts: now() - 3000),
        ]
        auditLogRepository.since(_) >> rows

        when:
        def signals = service.computeSignals()

        then:
        signals.any { it.type == 'MULTIPLE_IPS_PER_USER' && it.userId == 42L && it.count == 3 }
    }

    def "MULTIPLE_IPS_PER_USER stays quiet when user has only 2 IPs"() {
        given:
        auditLogRepository.since(_) >> [
            row(actor: 42L, ip: '10.0.0.1'),
            row(actor: 42L, ip: '10.0.0.2'),
        ]

        when:
        def signals = service.computeSignals()

        then:
        !signals.any { it.type == 'MULTIPLE_IPS_PER_USER' }
    }

    def "MULTIPLE_IPS_PER_USER is HIGH severity when user has 6+ IPs"() {
        given:
        auditLogRepository.since(_) >> (1..6).collect { i -> row(actor: 1L, ip: "10.0.0.${i}") }

        when:
        def signals = service.computeSignals()

        then:
        def sig = signals.find { it.type == 'MULTIPLE_IPS_PER_USER' }
        sig != null
        sig.severity == 'HIGH'
    }

    // ── SHARED_IP_MULTIPLE_USERS ──────────────────────────────────

    def "SHARED_IP_MULTIPLE_USERS fires when 5+ accounts act from the same IP"() {
        given:
        auditLogRepository.since(_) >> (1..5).collect { i -> row(actor: i as Long, ip: '192.168.1.50') }

        when:
        def signals = service.computeSignals()

        then:
        def sig = signals.find { it.type == 'SHARED_IP_MULTIPLE_USERS' }
        sig != null
        sig.ip == '192.168.1.50'
        sig.count == 5
    }

    def "SHARED_IP_MULTIPLE_USERS stays quiet when only 4 accounts share an IP"() {
        given:
        auditLogRepository.since(_) >> (1..4).collect { i -> row(actor: i as Long, ip: '192.168.1.50') }

        when:
        def signals = service.computeSignals()

        then:
        !signals.any { it.type == 'SHARED_IP_MULTIPLE_USERS' }
    }

    // ── RAPID_WITHDRAW_AFTER_DEPOSIT ──────────────────────────────

    def "RAPID_WITHDRAW_AFTER_DEPOSIT fires for a sub-15-minute gap"() {
        given:
        def t = now()
        auditLogRepository.since(_) >> [
            row(actor: 9L, event: AuditService.DEPOSIT_COMPLETE,   ts: t - (5 * 60 * 1000L)),
            row(actor: 9L, event: AuditService.WITHDRAW_REQUESTED, ts: t),
        ]

        when:
        def signals = service.computeSignals()

        then:
        def sig = signals.find { it.type == 'RAPID_WITHDRAW_AFTER_DEPOSIT' }
        sig != null
        sig.severity == 'HIGH'
        sig.userId == 9L
    }

    def "RAPID_WITHDRAW_AFTER_DEPOSIT stays quiet for a 30-minute gap"() {
        given:
        def t = now()
        auditLogRepository.since(_) >> [
            row(actor: 9L, event: AuditService.DEPOSIT_COMPLETE,   ts: t - (30 * 60 * 1000L)),
            row(actor: 9L, event: AuditService.WITHDRAW_REQUESTED, ts: t),
        ]

        when:
        def signals = service.computeSignals()

        then:
        !signals.any { it.type == 'RAPID_WITHDRAW_AFTER_DEPOSIT' }
    }

    // ── HIGH_VELOCITY_PURCHASES ───────────────────────────────────

    def "HIGH_VELOCITY_PURCHASES fires when a user buys 10+ listings in 10 minutes"() {
        given:
        def t = now()
        auditLogRepository.since(_) >> (1..12).collect { i ->
            row(actor: 7L, event: AuditService.LISTING_PURCHASED, ts: t - (i * 10 * 1000L))
        }

        when:
        def signals = service.computeSignals()

        then:
        def sig = signals.find { it.type == 'HIGH_VELOCITY_PURCHASES' }
        sig != null
        sig.userId == 7L
        sig.count >= 10
    }

    def "HIGH_VELOCITY_PURCHASES stays quiet for 5 purchases in a window"() {
        given:
        def t = now()
        auditLogRepository.since(_) >> (1..5).collect { i ->
            row(actor: 7L, event: AuditService.LISTING_PURCHASED, ts: t - (i * 10 * 1000L))
        }

        when:
        def signals = service.computeSignals()

        then:
        !signals.any { it.type == 'HIGH_VELOCITY_PURCHASES' }
    }

    // ── Sort order ────────────────────────────────────────────────

    def "signals are sorted HIGH > MED > LOW, then newest-first"() {
        given:
        def t = now()
        def rows = []
        // HIGH: rapid withdraw
        rows << row(actor: 1L, event: AuditService.DEPOSIT_COMPLETE,   ts: t - 200)
        rows << row(actor: 1L, event: AuditService.WITHDRAW_REQUESTED, ts: t - 100)
        // MED: 3 IPs for same user
        (1..3).each { i -> rows << row(actor: 2L, ip: "10.0.0.${i}", ts: t - 5000) }
        auditLogRepository.since(_) >> rows

        when:
        def signals = service.computeSignals()

        then:
        signals.size() >= 2
        // Every HIGH row must appear before every MED row in the list.
        def lastHigh = signals.findLastIndexOf { it.severity == 'HIGH' }
        def firstMed = signals.findIndexOf { it.severity == 'MED' }
        lastHigh >= 0
        firstMed > lastHigh
    }
}
