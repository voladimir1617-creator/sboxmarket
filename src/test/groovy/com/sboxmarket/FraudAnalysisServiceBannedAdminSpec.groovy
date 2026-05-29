package com.sboxmarket

import com.sboxmarket.model.AuditLog
import com.sboxmarket.model.Notification
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.AuditLogRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.FraudAnalysisService
import com.sboxmarket.service.NotificationService
import spock.lang.Specification

/**
 * Regression for the banned-admin fan-out in
 * {@link FraudAnalysisService#sweepAndPushFraudSignals()}.
 *
 * Pre-fix, the sweeper iterated every row returned by
 * {@code steamUserRepository.findByRole('ADMIN')} and pushed a
 * FRAUD_SIGNAL_HIGH bell to each one — banned admins included. A
 * banned admin is a demoted/compromised staff account; pinging them
 * with the live fraud-detection stream is both dead-end noise (the
 * banGuard rejects every admin action they try) AND an information
 * leak (an ex-admin learns the moment a HIGH signal lands).
 *
 * Every other multi-target fan-out in the codebase already filters
 * banned recipients (NotificationService.filterActiveRecipients,
 * PurchaseService.buy, ListingService.bulkAdjust, TradeService trade
 * fan-outs). This pins the same behaviour for the fraud sweeper.
 */
class FraudAnalysisServiceBannedAdminSpec extends Specification {

    AuditLogRepository auditLogRepository = Mock()

    private static long now() { System.currentTimeMillis() }

    private static AuditLog row(Map args = [:]) {
        new AuditLog(
            id:            args.id ?: 0L,
            actorUserId:   args.containsKey('actor') ? args.actor : 1L,
            actorName:     args.containsKey('actorName') ? args.actorName : "user${args.containsKey('actor') ? args.actor : 1L}",
            eventType:     args.event ?: AuditService.LISTING_PURCHASED,
            resourceId:    100L,
            summary:       'test',
            ipAddress:     args.containsKey('ip') ? args.ip : '10.0.0.1',
            userAgent:     'spec-agent',
            createdAt:     args.ts ?: now()
        )
    }

    def "sweeper skips banned admins when fanning out HIGH fraud signals"() {
        given:
        def notificationService = Mock(NotificationService)
        def steamUserRepository = Mock(SteamUserRepository)
        def svc = new FraudAnalysisService(
            auditLogRepository:  auditLogRepository,
            notificationService: notificationService,
            steamUserRepository: steamUserRepository
        )
        def t = now()
        // One HIGH signal: 6-IP user trips MULTIPLE_IPS_PER_USER at HIGH severity.
        auditLogRepository.since(_) >> (1..6).collect { i -> row(actor: 1L, ip: "10.0.0.${i}", ts: t) }
        // Two admins: one banned, one active.
        steamUserRepository.findByRole('ADMIN') >> [
            new SteamUser(id: 11L, role: 'ADMIN', banned: true),
            new SteamUser(id: 22L, role: 'ADMIN', banned: false),
        ]

        when:
        svc.sweepAndPushFraudSignals()

        then:
        // The active admin must still receive the bell...
        1 * notificationService.push(22L, 'FRAUD_SIGNAL_HIGH', _, _, _, '/admin?tab=fraud') >> new Notification(id: 1L)
        // ...but the banned admin must NEVER be pushed to.
        0 * notificationService.push(11L, _, _, _, _, _)
    }

    def "sweeper treats a null banned flag as not-banned (legacy rows)"() {
        // Defensive: SteamUser.banned is nullable in older rows (pre-V9).
        // A null must not be treated as banned — that would silently drop
        // every legacy admin from the fan-out.
        given:
        def notificationService = Mock(NotificationService)
        def steamUserRepository = Mock(SteamUserRepository)
        def svc = new FraudAnalysisService(
            auditLogRepository:  auditLogRepository,
            notificationService: notificationService,
            steamUserRepository: steamUserRepository
        )
        def t = now()
        auditLogRepository.since(_) >> (1..6).collect { i -> row(actor: 1L, ip: "10.0.0.${i}", ts: t) }
        steamUserRepository.findByRole('ADMIN') >> [
            new SteamUser(id: 33L, role: 'ADMIN', banned: null),
        ]

        when:
        svc.sweepAndPushFraudSignals()

        then:
        1 * notificationService.push(33L, 'FRAUD_SIGNAL_HIGH', _, _, _, _) >> new Notification(id: 1L)
    }

    def "sweeper short-circuits when every admin is banned"() {
        // If every ADMIN row is banned, the fan-out has nowhere to go.
        // Must not push, and (importantly) must not stamp the dedup
        // signature — a later legitimate admin promotion should still
        // be able to receive the alert next pass.
        given:
        def notificationService = Mock(NotificationService)
        def steamUserRepository = Mock(SteamUserRepository)
        def svc = new FraudAnalysisService(
            auditLogRepository:  auditLogRepository,
            notificationService: notificationService,
            steamUserRepository: steamUserRepository
        )
        def t = now()
        auditLogRepository.since(_) >> (1..6).collect { i -> row(actor: 1L, ip: "10.0.0.${i}", ts: t) }
        steamUserRepository.findByRole('ADMIN') >> [
            new SteamUser(id: 11L, role: 'ADMIN', banned: true),
            new SteamUser(id: 22L, role: 'ADMIN', banned: true),
        ]

        when:
        svc.sweepAndPushFraudSignals()

        then:
        0 * notificationService.push(*_)
    }
}
