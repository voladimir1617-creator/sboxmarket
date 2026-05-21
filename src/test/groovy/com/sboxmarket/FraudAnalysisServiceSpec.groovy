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
            actorUserId:   args.containsKey('actor') ? args.actor : 1L,
            actorName:     args.containsKey('actorName') ? args.actorName : "user${args.containsKey('actor') ? args.actor : 1L}",
            subjectUserId: args.subject,
            subjectName:   args.subjectName,
            eventType:     args.event ?: AuditService.LISTING_PURCHASED,
            resourceId:    args.resource ?: 100L,
            summary:       args.containsKey('summary') ? args.summary : 'test',
            ipAddress:     args.containsKey('ip') ? args.ip : '10.0.0.1',
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

    def "null rows from the repository return empty signal list"() {
        when:
        auditLogRepository.since(_) >> null
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

    def "MULTIPLE_IPS_PER_USER counts distinct IPs, not raw row count"() {
        given:
        // Same user, same two IPs, ten rows — only 2 distinct IPs so no trip.
        auditLogRepository.since(_) >> (1..10).collect { i ->
            row(actor: 5L, ip: (i % 2 == 0) ? '10.0.0.1' : '10.0.0.2')
        }

        when:
        def signals = service.computeSignals()

        then:
        !signals.any { it.type == 'MULTIPLE_IPS_PER_USER' }
    }

    def "MULTIPLE_IPS_PER_USER is MED severity at exactly 3 IPs"() {
        given:
        auditLogRepository.since(_) >> (1..3).collect { i -> row(actor: 1L, ip: "10.0.0.${i}") }

        when:
        def signals = service.computeSignals()

        then:
        def sig = signals.find { it.type == 'MULTIPLE_IPS_PER_USER' }
        sig != null
        sig.severity == 'MED'
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

    def "MULTIPLE_IPS_PER_USER ignores rows with a null actor"() {
        given:
        // A null-actor row cannot be attributed to any user — it must not
        // group under a `null` key and trip a phantom signal.
        auditLogRepository.since(_) >> [
            row(actor: null, ip: '10.0.0.1'),
            row(actor: null, ip: '10.0.0.2'),
            row(actor: null, ip: '10.0.0.3'),
        ]

        when:
        def signals = service.computeSignals()

        then:
        !signals.any { it.type == 'MULTIPLE_IPS_PER_USER' }
    }

    def "MULTIPLE_IPS_PER_USER ignores rows with a null IP"() {
        given:
        // Rows with no captured IP can't be counted toward an IP-spread
        // signal — three null-IP rows is not three distinct IPs.
        auditLogRepository.since(_) >> [
            row(actor: 8L, ip: null),
            row(actor: 8L, ip: null),
            row(actor: 8L, ip: null),
        ]

        when:
        def signals = service.computeSignals()

        then:
        !signals.any { it.type == 'MULTIPLE_IPS_PER_USER' }
    }

    def "MULTIPLE_IPS_PER_USER summary falls back to userId when actorName is null"() {
        given:
        auditLogRepository.since(_) >> (1..3).collect { i ->
            row(actor: 77L, actorName: null, ip: "10.0.0.${i}")
        }

        when:
        def signals = service.computeSignals()

        then:
        def sig = signals.find { it.type == 'MULTIPLE_IPS_PER_USER' }
        sig != null
        sig.summary.contains('77')
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
        sig.userId == null
    }

    def "SHARED_IP_MULTIPLE_USERS stays quiet when only 4 accounts share an IP"() {
        given:
        auditLogRepository.since(_) >> (1..4).collect { i -> row(actor: i as Long, ip: '192.168.1.50') }

        when:
        def signals = service.computeSignals()

        then:
        !signals.any { it.type == 'SHARED_IP_MULTIPLE_USERS' }
    }

    def "SHARED_IP_MULTIPLE_USERS counts distinct users, not raw row count"() {
        given:
        // One user hammering the same IP 20 times is not 20 accounts.
        auditLogRepository.since(_) >> (1..20).collect { row(actor: 3L, ip: '192.168.1.50') }

        when:
        def signals = service.computeSignals()

        then:
        !signals.any { it.type == 'SHARED_IP_MULTIPLE_USERS' }
    }

    def "SHARED_IP_MULTIPLE_USERS is HIGH severity at 10+ accounts"() {
        given:
        auditLogRepository.since(_) >> (1..10).collect { i -> row(actor: i as Long, ip: '192.168.1.50') }

        when:
        def signals = service.computeSignals()

        then:
        def sig = signals.find { it.type == 'SHARED_IP_MULTIPLE_USERS' }
        sig != null
        sig.severity == 'HIGH'
    }

    def "SHARED_IP_MULTIPLE_USERS is MED severity at exactly 5 accounts"() {
        given:
        auditLogRepository.since(_) >> (1..5).collect { i -> row(actor: i as Long, ip: '192.168.1.50') }

        when:
        def signals = service.computeSignals()

        then:
        def sig = signals.find { it.type == 'SHARED_IP_MULTIPLE_USERS' }
        sig != null
        sig.severity == 'MED'
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
        sig.count == 5L
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

    def "RAPID_WITHDRAW_AFTER_DEPOSIT does NOT correlate two null-actor rows"() {
        given:
        // Regression: in production DEPOSIT_COMPLETE / WITHDRAW_REQUESTED
        // audit rows are written by the Stripe webhook with a null actor.
        // Groovy's `null == null` is `true`, so without an explicit
        // non-null-actor guard an unrelated deposit and an unrelated
        // withdrawal would falsely correlate and fire a bogus HIGH alert.
        def t = now()
        auditLogRepository.since(_) >> [
            row(actor: null, event: AuditService.DEPOSIT_COMPLETE,   ts: t - (3 * 60 * 1000L)),
            row(actor: null, event: AuditService.WITHDRAW_REQUESTED, ts: t),
        ]

        when:
        def signals = service.computeSignals()

        then:
        !signals.any { it.type == 'RAPID_WITHDRAW_AFTER_DEPOSIT' }
    }

    def "RAPID_WITHDRAW_AFTER_DEPOSIT does not match a deposit by a different user"() {
        given:
        def t = now()
        auditLogRepository.since(_) >> [
            row(actor: 100L, event: AuditService.DEPOSIT_COMPLETE,   ts: t - (2 * 60 * 1000L)),
            row(actor: 200L, event: AuditService.WITHDRAW_REQUESTED, ts: t),
        ]

        when:
        def signals = service.computeSignals()

        then:
        !signals.any { it.type == 'RAPID_WITHDRAW_AFTER_DEPOSIT' }
    }

    def "RAPID_WITHDRAW_AFTER_DEPOSIT does not match a deposit AFTER the withdrawal"() {
        given:
        // Deposit timestamp is later than the withdrawal — not a
        // deposit-then-withdraw pattern, must not fire.
        def t = now()
        auditLogRepository.since(_) >> [
            row(actor: 9L, event: AuditService.WITHDRAW_REQUESTED, ts: t - (5 * 60 * 1000L)),
            row(actor: 9L, event: AuditService.DEPOSIT_COMPLETE,   ts: t),
        ]

        when:
        def signals = service.computeSignals()

        then:
        !signals.any { it.type == 'RAPID_WITHDRAW_AFTER_DEPOSIT' }
    }

    def "RAPID_WITHDRAW_AFTER_DEPOSIT fires once per qualifying withdrawal"() {
        given:
        def t = now()
        auditLogRepository.since(_) >> [
            row(actor: 9L, event: AuditService.DEPOSIT_COMPLETE,   ts: t - (10 * 60 * 1000L)),
            row(actor: 9L, event: AuditService.WITHDRAW_REQUESTED, ts: t - (4 * 60 * 1000L)),
            row(actor: 9L, event: AuditService.WITHDRAW_REQUESTED, ts: t),
        ]

        when:
        def signals = service.computeSignals()

        then:
        signals.count { it.type == 'RAPID_WITHDRAW_AFTER_DEPOSIT' } == 2
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

    def "HIGH_VELOCITY_PURCHASES does not fire when 12 purchases are spread over hours"() {
        given:
        // 12 purchases but spaced 30 min apart — never 10 inside one
        // 10-minute window, so the sliding-window peak stays at 1.
        def t = now()
        auditLogRepository.since(_) >> (1..12).collect { i ->
            row(actor: 7L, event: AuditService.LISTING_PURCHASED, ts: t - (i * 30L * 60L * 1000L))
        }

        when:
        def signals = service.computeSignals()

        then:
        !signals.any { it.type == 'HIGH_VELOCITY_PURCHASES' }
    }

    def "HIGH_VELOCITY_PURCHASES is HIGH severity at 20+ in a window"() {
        given:
        def t = now()
        auditLogRepository.since(_) >> (1..22).collect { i ->
            row(actor: 7L, event: AuditService.LISTING_PURCHASED, ts: t - (i * 5 * 1000L))
        }

        when:
        def signals = service.computeSignals()

        then:
        def sig = signals.find { it.type == 'HIGH_VELOCITY_PURCHASES' }
        sig != null
        sig.severity == 'HIGH'
    }

    def "HIGH_VELOCITY_PURCHASES is MED severity for 10-19 in a window"() {
        given:
        def t = now()
        auditLogRepository.since(_) >> (1..12).collect { i ->
            row(actor: 7L, event: AuditService.LISTING_PURCHASED, ts: t - (i * 5 * 1000L))
        }

        when:
        def signals = service.computeSignals()

        then:
        def sig = signals.find { it.type == 'HIGH_VELOCITY_PURCHASES' }
        sig != null
        sig.severity == 'MED'
    }

    def "HIGH_VELOCITY_PURCHASES ignores purchases with a null actor"() {
        given:
        def t = now()
        auditLogRepository.since(_) >> (1..15).collect { i ->
            row(actor: null, event: AuditService.LISTING_PURCHASED, ts: t - (i * 5 * 1000L))
        }

        when:
        def signals = service.computeSignals()

        then:
        !signals.any { it.type == 'HIGH_VELOCITY_PURCHASES' }
    }

    def "HIGH_VELOCITY_PURCHASES is scoped per-user — two users at 6 each do not trip"() {
        given:
        def t = now()
        def rows = []
        (1..6).each { i -> rows << row(actor: 1L, event: AuditService.LISTING_PURCHASED, ts: t - (i * 5 * 1000L)) }
        (1..6).each { i -> rows << row(actor: 2L, event: AuditService.LISTING_PURCHASED, ts: t - (i * 5 * 1000L)) }
        auditLogRepository.since(_) >> rows

        when:
        def signals = service.computeSignals()

        then:
        !signals.any { it.type == 'HIGH_VELOCITY_PURCHASES' }
    }

    // ── CHARGEBACK_IN_WINDOW ──────────────────────────────────────

    def "CHARGEBACK_IN_WINDOW fires one HIGH signal per chargeback row"() {
        given:
        def t = now()
        auditLogRepository.since(_) >> [
            row(actor: null, event: AuditService.CHARGEBACK_OPENED, subject: 50L,
                subjectName: 'Mallory', summary: 'Stripe dispute on tx 900', ts: t),
        ]

        when:
        def signals = service.computeSignals()

        then:
        def sig = signals.find { it.type == 'CHARGEBACK_IN_WINDOW' }
        sig != null
        sig.severity == 'HIGH'
        sig.userId == 50L
        sig.userName == 'Mallory'
        sig.count == 1L
        sig.summary.contains('Stripe dispute on tx 900')
    }

    def "CHARGEBACK_IN_WINDOW tolerates a null summary"() {
        given:
        auditLogRepository.since(_) >> [
            row(actor: null, event: AuditService.CHARGEBACK_OPENED, subject: 50L, summary: null),
        ]

        when:
        def signals = service.computeSignals()

        then:
        def sig = signals.find { it.type == 'CHARGEBACK_IN_WINDOW' }
        sig != null
        sig.summary != null
    }

    def "CHARGEBACK_IN_WINDOW emits nothing when there are no chargebacks"() {
        given:
        auditLogRepository.since(_) >> [row(actor: 1L, ip: '10.0.0.1')]

        when:
        def signals = service.computeSignals()

        then:
        !signals.any { it.type == 'CHARGEBACK_IN_WINDOW' }
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

    def "within a severity bucket the newest signal sorts first"() {
        given:
        def t = now()
        def rows = []
        // Two HIGH chargebacks at different times.
        rows << row(actor: null, event: AuditService.CHARGEBACK_OPENED, subject: 1L, ts: t - 100000)
        rows << row(actor: null, event: AuditService.CHARGEBACK_OPENED, subject: 2L, ts: t - 100)
        auditLogRepository.since(_) >> rows

        when:
        def signals = service.computeSignals()
        def highs = signals.findAll { it.type == 'CHARGEBACK_IN_WINDOW' }

        then:
        highs.size() == 2
        highs[0].createdAt > highs[1].createdAt
    }

    // ── sweepAndPushFraudSignals ──────────────────────────────────

    def "sweeper is a no-op when no collaborators are wired"() {
        given:
        // notificationService and steamUserRepository are @Autowired(required=false)
        // and remain null in this unit setup.
        when:
        service.sweepAndPushFraudSignals()

        then:
        0 * auditLogRepository.since(_)
    }

    def "sweeper pushes one bell per admin for each HIGH signal"() {
        given:
        def notificationService = Mock(NotificationService)
        def steamUserRepository = Mock(SteamUserRepository)
        def svc = new FraudAnalysisService(
            auditLogRepository:  auditLogRepository,
            notificationService: notificationService,
            steamUserRepository: steamUserRepository
        )
        def t = now()
        // One HIGH signal: 6-IP user.
        auditLogRepository.since(_) >> (1..6).collect { i -> row(actor: 1L, ip: "10.0.0.${i}", ts: t) }
        steamUserRepository.findByRole('ADMIN') >> [
            new SteamUser(id: 11L, role: 'ADMIN'),
            new SteamUser(id: 22L, role: 'ADMIN'),
        ]

        when:
        svc.sweepAndPushFraudSignals()

        then:
        1 * notificationService.push(11L, 'FRAUD_SIGNAL_HIGH', _, _, _, '/admin?tab=fraud')
        1 * notificationService.push(22L, 'FRAUD_SIGNAL_HIGH', _, _, _, '/admin?tab=fraud')
    }

    def "sweeper dedupes a repeated identical HIGH signal across passes"() {
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
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 11L, role: 'ADMIN')]

        when:
        svc.sweepAndPushFraudSignals()
        svc.sweepAndPushFraudSignals()

        then:
        // Same (type, userId, ip-list, bucketed-count) signature both
        // passes — the second pass must not re-fire the bell.
        1 * notificationService.push(11L, 'FRAUD_SIGNAL_HIGH', _, _, _, _)
    }

    def "sweeper does not push when there are no admins"() {
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
        steamUserRepository.findByRole('ADMIN') >> []

        when:
        svc.sweepAndPushFraudSignals()

        then:
        0 * notificationService.push(*_)
    }

    def "sweeper does not push when there are no HIGH signals"() {
        given:
        def notificationService = Mock(NotificationService)
        def steamUserRepository = Mock(SteamUserRepository)
        def svc = new FraudAnalysisService(
            auditLogRepository:  auditLogRepository,
            notificationService: notificationService,
            steamUserRepository: steamUserRepository
        )
        // Only a MED signal: exactly 3 IPs.
        auditLogRepository.since(_) >> (1..3).collect { i -> row(actor: 1L, ip: "10.0.0.${i}") }

        when:
        svc.sweepAndPushFraudSignals()

        then:
        0 * steamUserRepository.findByRole(_)
        0 * notificationService.push(*_)
    }

    def "sweeper swallows a computeSignals failure without throwing"() {
        given:
        def notificationService = Mock(NotificationService)
        def steamUserRepository = Mock(SteamUserRepository)
        def svc = new FraudAnalysisService(
            auditLogRepository:  auditLogRepository,
            notificationService: notificationService,
            steamUserRepository: steamUserRepository
        )
        auditLogRepository.since(_) >> { throw new RuntimeException('db down') }

        when:
        svc.sweepAndPushFraudSignals()

        then:
        notThrown(Exception)
        0 * notificationService.push(*_)
    }

    def "sweeper swallows an admin-lookup failure without throwing"() {
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
        steamUserRepository.findByRole('ADMIN') >> { throw new RuntimeException('lookup failed') }

        when:
        svc.sweepAndPushFraudSignals()

        then:
        notThrown(Exception)
        0 * notificationService.push(*_)
    }

    def "sweeper keeps notifying remaining admins when one push throws"() {
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
            new SteamUser(id: 11L, role: 'ADMIN'),
            new SteamUser(id: 22L, role: 'ADMIN'),
        ]

        when:
        svc.sweepAndPushFraudSignals()

        then:
        1 * notificationService.push(11L, _, _, _, _, _) >> { throw new RuntimeException('bell down') }
        1 * notificationService.push(22L, _, _, _, _, _) >> new Notification(id: 1L)
        notThrown(Exception)
    }

    // ── sweeper transaction semantics (regression) ────────────────
    //
    // The sweeper WRITES notification rows via notificationService.push().
    // `push` is @Transactional(REQUIRED), so when called from inside the
    // sweeper's own transaction it JOINS that transaction. If the sweeper
    // were @Transactional(readOnly = true), Hibernate's FlushMode would be
    // MANUAL and every push save would be silently discarded — no admin
    // would ever get a fraud bell. This test pins the annotation so a
    // future edit can't reintroduce readOnly = true on the sweeper.

    def "sweepAndPushFraudSignals is a read-write transaction, not readOnly"() {
        given:
        def m = FraudAnalysisService.getDeclaredMethod('sweepAndPushFraudSignals')
        def txn = m.getAnnotation(org.springframework.transaction.annotation.Transactional)

        expect:
        // The annotation must be present...
        txn != null
        // ...and must NOT be readOnly — a readOnly outer transaction
        // would stop the joined push() save from ever flushing.
        !txn.readOnly()
    }

    def "computeSignals stays readOnly — it is a pure read used by the admin UI"() {
        given:
        // The externally-called rollup query (AdminController) must remain
        // readOnly; only the sweeper needs write capability.
        def m = FraudAnalysisService.getDeclaredMethod('computeSignals')
        def txn = m.getAnnotation(org.springframework.transaction.annotation.Transactional)

        expect:
        txn != null
        txn.readOnly()
    }

    // ── RAPID_WITHDRAW_AFTER_DEPOSIT window boundary ──────────────

    def "RAPID_WITHDRAW_AFTER_DEPOSIT fires at exactly the 15-minute boundary"() {
        given:
        // Gap is exactly WITHDRAW_AFTER_DEPOSIT_MS — the predicate uses
        // `<=`, so the boundary itself must trip.
        def t = now()
        auditLogRepository.since(_) >> [
            row(actor: 9L, event: AuditService.DEPOSIT_COMPLETE,   ts: t - (15 * 60 * 1000L)),
            row(actor: 9L, event: AuditService.WITHDRAW_REQUESTED, ts: t),
        ]

        when:
        def signals = service.computeSignals()

        then:
        def sig = signals.find { it.type == 'RAPID_WITHDRAW_AFTER_DEPOSIT' }
        sig != null
        sig.count == 15L
    }

    def "RAPID_WITHDRAW_AFTER_DEPOSIT stays quiet one millisecond past the window"() {
        given:
        def t = now()
        auditLogRepository.since(_) >> [
            row(actor: 9L, event: AuditService.DEPOSIT_COMPLETE,   ts: t - (15 * 60 * 1000L) - 1L),
            row(actor: 9L, event: AuditService.WITHDRAW_REQUESTED, ts: t),
        ]

        when:
        def signals = service.computeSignals()

        then:
        !signals.any { it.type == 'RAPID_WITHDRAW_AFTER_DEPOSIT' }
    }

    def "RAPID_WITHDRAW_AFTER_DEPOSIT fires on a same-instant deposit+withdraw"() {
        given:
        // Deposit and withdrawal at the identical timestamp — `<=` on
        // both the ordering check and a zero-gap window must still trip.
        def t = now()
        auditLogRepository.since(_) >> [
            row(actor: 9L, event: AuditService.DEPOSIT_COMPLETE,   ts: t),
            row(actor: 9L, event: AuditService.WITHDRAW_REQUESTED, ts: t),
        ]

        when:
        def signals = service.computeSignals()

        then:
        def sig = signals.find { it.type == 'RAPID_WITHDRAW_AFTER_DEPOSIT' }
        sig != null
        sig.count == 0L
    }

    // ── sweeper count bucketing / escalation ──────────────────────
    //
    // The dedup signature is (type, userId, ip, power-of-two bucket of
    // `count`). SHARED_IP_MULTIPLE_USERS is the cleanest signal to pin
    // the count-bucketing behaviour: its `ip` component is a single
    // stable address and `userId` is always null, so only the bucketed
    // count varies between passes.

    def "sweeper re-fires when a shared-IP attack escalates across a count bucket"() {
        given:
        def notificationService = Mock(NotificationService)
        def steamUserRepository = Mock(SteamUserRepository)
        def svc = new FraudAnalysisService(
            auditLogRepository:  auditLogRepository,
            notificationService: notificationService,
            steamUserRepository: steamUserRepository
        )
        def t = now()
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 11L, role: 'ADMIN')]
        // Pass 1: 10 users on one IP (count 10 → bucket 16).
        // Pass 2: 17 users on the same IP (count 17 → bucket 32) — a
        // genuine escalation across a bucket boundary, so it re-fires.
        auditLogRepository.since(_) >>> [
            (1..10).collect { i -> row(actor: i as Long, ip: '192.168.1.50', ts: t) },
            (1..17).collect { i -> row(actor: i as Long, ip: '192.168.1.50', ts: t) },
        ]

        when:
        svc.sweepAndPushFraudSignals()
        svc.sweepAndPushFraudSignals()

        then:
        2 * notificationService.push(11L, 'FRAUD_SIGNAL_HIGH', _, _, _, _)
    }

    def "sweeper does NOT re-fire when a shared-IP count grows within the same bucket"() {
        given:
        def notificationService = Mock(NotificationService)
        def steamUserRepository = Mock(SteamUserRepository)
        def svc = new FraudAnalysisService(
            auditLogRepository:  auditLogRepository,
            notificationService: notificationService,
            steamUserRepository: steamUserRepository
        )
        def t = now()
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 11L, role: 'ADMIN')]
        // 10 users then 12 users on the same IP — counts 10 and 12 both
        // bucketize to 16, so this is "the same signal growing", not a
        // fresh alert. The signature is identical → second pass dedupes.
        auditLogRepository.since(_) >>> [
            (1..10).collect { i -> row(actor: i as Long, ip: '192.168.1.50', ts: t) },
            (1..12).collect { i -> row(actor: i as Long, ip: '192.168.1.50', ts: t) },
        ]

        when:
        svc.sweepAndPushFraudSignals()
        svc.sweepAndPushFraudSignals()

        then:
        1 * notificationService.push(11L, 'FRAUD_SIGNAL_HIGH', _, _, _, _)
    }

    // ── sweeper handles a userId-less HIGH signal ─────────────────

    def "sweeper pushes a HIGH SHARED_IP signal even though it has a null userId"() {
        given:
        // SHARED_IP_MULTIPLE_USERS always has userId == null. The sweeper
        // must still build a valid signature + push it (refId just stays
        // null). 10 distinct users on one IP is HIGH severity.
        def notificationService = Mock(NotificationService)
        def steamUserRepository = Mock(SteamUserRepository)
        def svc = new FraudAnalysisService(
            auditLogRepository:  auditLogRepository,
            notificationService: notificationService,
            steamUserRepository: steamUserRepository
        )
        def t = now()
        auditLogRepository.since(_) >> (1..10).collect { i -> row(actor: i as Long, ip: '192.168.1.50', ts: t) }
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 11L, role: 'ADMIN')]

        when:
        svc.sweepAndPushFraudSignals()

        then:
        // refId (5th positional arg) is null because SHARED_IP has no
        // single user — the push must still go out.
        1 * notificationService.push(11L, 'FRAUD_SIGNAL_HIGH', _, _, null, '/admin?tab=fraud')
    }

    def "two distinct shared-IP HIGH signals on different IPs both push"() {
        given:
        def notificationService = Mock(NotificationService)
        def steamUserRepository = Mock(SteamUserRepository)
        def svc = new FraudAnalysisService(
            auditLogRepository:  auditLogRepository,
            notificationService: notificationService,
            steamUserRepository: steamUserRepository
        )
        def t = now()
        def rows = []
        (1..10).each  { i -> rows << row(actor: i as Long,        ip: '192.168.1.50', ts: t) }
        (1..10).each  { i -> rows << row(actor: (i + 100) as Long, ip: '192.168.1.99', ts: t) }
        auditLogRepository.since(_) >> rows
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 11L, role: 'ADMIN')]

        when:
        svc.sweepAndPushFraudSignals()

        then:
        // Different IPs → different signatures → both fire.
        2 * notificationService.push(11L, 'FRAUD_SIGNAL_HIGH', _, _, _, _)
    }

    // ── detector input null-safety ────────────────────────────────

    def "MULTIPLE_IPS_PER_USER ignores blank-string IPs the same as nulls"() {
        given:
        // A captured-but-empty ipAddress is not a distinct IP. Three rows
        // with an empty ip must not read as three IPs.
        auditLogRepository.since(_) >> [
            row(actor: 8L, ip: ''),
            row(actor: 8L, ip: ''),
            row(actor: 8L, ip: ''),
        ]

        when:
        def signals = service.computeSignals()

        then:
        !signals.any { it.type == 'MULTIPLE_IPS_PER_USER' }
    }

    def "mixed null-actor and real-actor rows only attribute the real actor"() {
        given:
        // A burst of null-actor system rows interleaved with one real
        // user's 3-IP spread — the phantom null group must not trip, the
        // real user must.
        def t = now()
        def rows = []
        (1..5).each { rows << row(actor: null, ip: '10.0.0.99', event: AuditService.LISTING_PURCHASED, ts: t) }
        (1..3).each { i -> rows << row(actor: 42L, ip: "10.0.0.${i}", ts: t) }
        auditLogRepository.since(_) >> rows

        when:
        def signals = service.computeSignals()

        then:
        def ipSigs = signals.findAll { it.type == 'MULTIPLE_IPS_PER_USER' }
        ipSigs.size() == 1
        ipSigs[0].userId == 42L
    }

    def "computeSignals produces no signal whose severity falls outside HIGH/MED/LOW"() {
        given:
        // Guards the sort comparator's severity-rank map: every emitted
        // signal must carry a severity the map knows, otherwise it sorts
        // to the rank-9 tail unpredictably.
        def t = now()
        def rows = []
        (1..6).each  { i -> rows << row(actor: 1L, ip: "10.0.0.${i}", ts: t) }
        (1..10).each { i -> rows << row(actor: i as Long, ip: '192.168.9.9', ts: t) }
        rows << row(actor: 1L, event: AuditService.DEPOSIT_COMPLETE,   ts: t - 1000)
        rows << row(actor: 1L, event: AuditService.WITHDRAW_REQUESTED, ts: t)
        (1..12).each { i -> rows << row(actor: 5L, event: AuditService.LISTING_PURCHASED, ts: t - (i * 5000L)) }
        rows << row(actor: null, event: AuditService.CHARGEBACK_OPENED, subject: 3L, ts: t)
        auditLogRepository.since(_) >> rows

        when:
        def signals = service.computeSignals()

        then:
        !signals.isEmpty()
        signals.every { it.severity in ['HIGH', 'MED', 'LOW'] }
    }
}
