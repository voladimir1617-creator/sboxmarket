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

    def "sweeper retries the signature next pass when every admin push failed"() {
        // Regression: the sweeper used to stamp the signature into
        // seenSignatures BEFORE the admin fan-out, so if every push threw
        // (DB blip, transient bell-storage error) the signal was permanently
        // marked seen — admins would never be re-fired even though zero
        // bells had landed. The fix only commits the signature once at
        // least one push has succeeded, so a fully-failed pass leaves the
        // signature open and the next 30-min tick retries the alert.
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
        int callCount = 0

        when:
        // Pass 1: the only admin's push throws — zero bells delivered.
        svc.sweepAndPushFraudSignals()
        // Pass 2: same HIGH signal — the sweeper MUST retry because pass 1
        // delivered no bells. Pre-fix this was skipped via the seen-sig cache.
        svc.sweepAndPushFraudSignals()

        then:
        2 * notificationService.push(11L, 'FRAUD_SIGNAL_HIGH', _, _, _, _) >> { args ->
            callCount++
            if (callCount == 1) throw new RuntimeException('bell down')
            return new Notification(id: 1L)
        }
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

    def "sweeper does NOT re-fire when a user's IP-set grows within the same count bucket"() {
        // Regression for the MULTIPLE_IPS_PER_USER dedup. The displayed
        // `ip` field on that signal is the comma-joined IP list, which
        // changes content every time a new IP joins the user's set. Pre-fix
        // the sweeper put that joined list into the dedup signature, so a
        // user going 6 → 7 → 8 distinct IPs (all bucketing to 8 → still
        // HIGH) would fan out THREE identical HIGH bells to every admin
        // instead of one. The seenSignatures comment claims this exact
        // case is deduped — this test pins that behaviour now that the
        // signal stamps `signatureIp: ''` to stabilise the signature.
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
        // Pass 1: 6 distinct IPs (count 6 → bucket 8, severity HIGH).
        // Pass 2: 7 distinct IPs — same user, same bucket, attack just
        // accreted one more IP. Must NOT re-fire the bell.
        auditLogRepository.since(_) >>> [
            (1..6).collect { i -> row(actor: 1L, ip: "10.0.0.${i}", ts: t) },
            (1..7).collect { i -> row(actor: 1L, ip: "10.0.0.${i}", ts: t) },
        ]

        when:
        svc.sweepAndPushFraudSignals()
        svc.sweepAndPushFraudSignals()

        then:
        1 * notificationService.push(11L, 'FRAUD_SIGNAL_HIGH', _, _, _, _)
    }

    def "sweeper re-fires for a MULTIPLE_IPS_PER_USER attack that escalates across a count bucket"() {
        // Companion to the within-bucket test: the bucketing must still
        // re-fire on a genuine escalation. 6 IPs (bucket 8) → 9 IPs
        // (bucket 16) is a meaningful jump and warrants a fresh bell.
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
        auditLogRepository.since(_) >>> [
            (1..6).collect { i -> row(actor: 1L, ip: "10.0.0.${i}", ts: t) },
            (1..9).collect { i -> row(actor: 1L, ip: "10.0.0.${i}", ts: t) },
        ]

        when:
        svc.sweepAndPushFraudSignals()
        svc.sweepAndPushFraudSignals()

        then:
        2 * notificationService.push(11L, 'FRAUD_SIGNAL_HIGH', _, _, _, _)
    }

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

    // ── bucketize (dedupe-signature stability) ────────────────────────
    //
    // The sweeper's dedup signature is (type, userId, ip, power-of-two
    // bucket of count, dedupKey). bucketize is the function that rounds
    // count → nearest power of two. The behaviour is load-bearing in two
    // ways: (a) signatures stay stable as a slowly-growing attacker count
    // (10→11→12) keeps hashing the same; (b) genuine escalations
    // (10→16→17) cross a bucket boundary and re-fire. Behaviour-level
    // coverage already exists in the sweep specs above — these specs
    // directly pin the boundary values.

    /** Invoke the private static `bucketize(long)` via reflection.
     *  Pattern mirrors AuditService.clientIp + SeedService.initialsFor. */
    private static long invokeBucketize(long n) {
        def m = com.sboxmarket.service.FraudAnalysisService.getDeclaredMethod('bucketize', long)
        m.accessible = true
        m.invoke(null, n) as long
    }

    def "bucketize returns 0 for non-positive counts"() {
        // Defensive lower bound — a malformed signal with count<=0 must
        // not break the dedup hash (returning 1 for both 0 and -5 would
        // collapse them into the same bucket as a real 1-count signal).
        expect:
        invokeBucketize(0L)  == 0L
        invokeBucketize(-1L) == 0L
        invokeBucketize(-1000L) == 0L
    }

    def "bucketize rounds UP to the next power of two"() {
        // The "bucket" is the smallest power of two that's >= n. A count
        // of 10 falls in bucket 16; a count of 5 in bucket 8.
        expect:
        invokeBucketize(n as long) == bucket as long

        where:
        n  | bucket
        1  | 1
        2  | 2
        3  | 4
        4  | 4
        5  | 8
        7  | 8
        8  | 8
        9  | 16
        10 | 16
        12 | 16
        16 | 16
        17 | 32
        20 | 32
        32 | 32
        33 | 64
        100 | 128
    }

    def "bucketize collapses 10..16 into one signature but escalates to 32 at 17"() {
        // This is the production invariant the sweep specs rely on for
        // the "grows within bucket = no re-fire" / "jumps a bucket = re-fire"
        // behaviour. Pin it directly so a future tweak can't drift the
        // boundary.
        expect:
        invokeBucketize(10L) == 16L
        invokeBucketize(16L) == 16L
        invokeBucketize(17L) == 32L
    }

    def "bucketize handles a very large count without overflow"() {
        // Defensive — a single audit-row count can't realistically reach
        // 1B but the helper must still produce a finite power-of-two
        // bucket instead of looping forever or overflowing to negative.
        when:
        long b = invokeBucketize(1_000_000_000L)

        then:
        noExceptionThrown()
        b > 0L
        // 2^30 = 1_073_741_824 > 1_000_000_000 > 2^29 = 536_870_912
        b == 1_073_741_824L
    }

    // ── multi-pod race claim (wave 112 parity for fraud sweeper) ──────
    //
    // Production hazard: `@Scheduled(fixedDelay)` only serialises ticks
    // WITHIN one JVM. On a two-pod cluster both schedulers fire roughly
    // together, both call `computeSignals()`, both find the same HIGH
    // signal list. Pre-fix the dedup set (`seenSignatures`) was a
    // per-JVM LinkedHashSet — pod A's set was blind to pod B's, so each
    // pod independently fanned out a "⚠ Fraud signal" bell to every
    // admin. Two pods = 2× duplicate bells per HIGH signal per admin,
    // N pods = N×. The exact failure mode wave 112 closed for the
    // watchlist sweeper via WatchlistAlertRepository.claimForFiring.
    //
    // Fix shape: a cluster-wide ledger table (`fraud_signal_claims`)
    // with a UNIQUE(signature) constraint. The sweeper checks the
    // ledger before fanning out; whichever pod's INSERT lands first
    // wins, the loser bails BEFORE invoking notificationService.push.
    //
    // The ledger repo (`FraudSignalClaimRepository`) is autowired
    // optional so unit tests that don't stub it still exercise the
    // per-JVM dedup path. These specs explicitly inject a Mock to pin
    // the wave-112 contract.
    //
    // Three asserts:
    //   1) when the ledger already has the signature (sibling pod won),
    //      the sweep does NOT push any admin bell,
    //   2) on a fresh signature, the sweep pushes AND stamps the ledger
    //      (so subsequent pods see the claim on their existsBy check),
    //   3) the ledger INSERT happens AFTER at least one push lands —
    //      a fully-failed admin fan-out must NOT stamp the ledger, so
    //      the next tick legitimately retries (mirrors the in-memory
    //      "retry after total fan-out failure" regression at line 725).

    def "sweeper skips fan-out when the cluster ledger already has the signature (wave-112 multi-pod gate)"() {
        given: 'a HIGH signal whose signature was already claimed by a sibling pod'
        def notificationService = Mock(NotificationService)
        def steamUserRepository = Mock(SteamUserRepository)
        def fraudSignalClaimRepository = Mock(com.sboxmarket.repository.FraudSignalClaimRepository)
        def svc = new FraudAnalysisService(
            auditLogRepository:           auditLogRepository,
            notificationService:          notificationService,
            steamUserRepository:          steamUserRepository,
            fraudSignalClaimRepository:   fraudSignalClaimRepository
        )
        def t = now()
        // 6 distinct IPs → MULTIPLE_IPS_PER_USER HIGH severity.
        auditLogRepository.since(_) >> (1..6).collect { i -> row(actor: 1L, ip: "10.0.0.${i}", ts: t) }
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 11L, role: 'ADMIN')]
        // Sibling pod claimed the signature first.
        fraudSignalClaimRepository.existsBySignature(_) >> true

        when:
        svc.sweepAndPushFraudSignals()

        then: 'no admin bell goes out — losing the cluster race must skip fan-out'
        0 * notificationService.push(*_)
        and: 'and we do NOT INSERT a duplicate claim row either (sibling already owns it)'
        0 * fraudSignalClaimRepository.save(_)
    }

    def "sweeper pushes and stamps the cluster ledger on a fresh signature (winning pod path)"() {
        given: 'a HIGH signal that no pod has claimed yet'
        def notificationService = Mock(NotificationService)
        def steamUserRepository = Mock(SteamUserRepository)
        def fraudSignalClaimRepository = Mock(com.sboxmarket.repository.FraudSignalClaimRepository)
        def svc = new FraudAnalysisService(
            auditLogRepository:           auditLogRepository,
            notificationService:          notificationService,
            steamUserRepository:          steamUserRepository,
            fraudSignalClaimRepository:   fraudSignalClaimRepository
        )
        def t = now()
        auditLogRepository.since(_) >> (1..6).collect { i -> row(actor: 1L, ip: "10.0.0.${i}", ts: t) }
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 11L, role: 'ADMIN')]
        fraudSignalClaimRepository.existsBySignature(_) >> false

        when:
        svc.sweepAndPushFraudSignals()

        then: 'the admin gets exactly one bell'
        1 * notificationService.push(11L, 'FRAUD_SIGNAL_HIGH', _, _, _, _) >> new Notification(id: 1L)
        and: 'and the cluster ledger gets one stamped claim so sibling pods skip on their next tick'
        1 * fraudSignalClaimRepository.save({ com.sboxmarket.model.FraudSignalClaim c ->
            c.signature != null && c.signature.startsWith('MULTIPLE_IPS_PER_USER|1|') && c.claimedAt > 0L
        })
    }

    def "sweeper does NOT stamp the cluster ledger when every admin push fails (retry-on-failure invariant)"() {
        // Regression: the cluster ledger MUST follow the same
        // commit-after-push ordering as the in-memory seenSignatures.
        // If we stamped pre-push and every push then threw, the next
        // tick would see the ledger row and skip the retry — admins
        // would never get the bell even though zero landed first pass.
        given:
        def notificationService = Mock(NotificationService)
        def steamUserRepository = Mock(SteamUserRepository)
        def fraudSignalClaimRepository = Mock(com.sboxmarket.repository.FraudSignalClaimRepository)
        def svc = new FraudAnalysisService(
            auditLogRepository:           auditLogRepository,
            notificationService:          notificationService,
            steamUserRepository:          steamUserRepository,
            fraudSignalClaimRepository:   fraudSignalClaimRepository
        )
        def t = now()
        auditLogRepository.since(_) >> (1..6).collect { i -> row(actor: 1L, ip: "10.0.0.${i}", ts: t) }
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 11L, role: 'ADMIN')]
        fraudSignalClaimRepository.existsBySignature(_) >> false

        when:
        svc.sweepAndPushFraudSignals()

        then: 'the only admin push throws — zero bells delivered this tick'
        1 * notificationService.push(11L, _, _, _, _, _) >> { throw new RuntimeException('bell down') }
        and: 'the ledger MUST stay un-stamped so the next tick can retry the alert'
        0 * fraudSignalClaimRepository.save(_)
        and: 'and the sweeper does not propagate the failure'
        notThrown(Exception)
    }

    def "sweeper tolerates an existsBySignature exception by falling through to fan-out"() {
        // Defensive: a transient DB blip on the existence check must not
        // wedge the sweeper. Better to occasionally over-notify than to
        // silently swallow a HIGH bell because the dedup ledger is down.
        given:
        def notificationService = Mock(NotificationService)
        def steamUserRepository = Mock(SteamUserRepository)
        def fraudSignalClaimRepository = Mock(com.sboxmarket.repository.FraudSignalClaimRepository)
        def svc = new FraudAnalysisService(
            auditLogRepository:           auditLogRepository,
            notificationService:          notificationService,
            steamUserRepository:          steamUserRepository,
            fraudSignalClaimRepository:   fraudSignalClaimRepository
        )
        def t = now()
        auditLogRepository.since(_) >> (1..6).collect { i -> row(actor: 1L, ip: "10.0.0.${i}", ts: t) }
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 11L, role: 'ADMIN')]
        fraudSignalClaimRepository.existsBySignature(_) >> { throw new RuntimeException('db down') }

        when:
        svc.sweepAndPushFraudSignals()

        then: 'the admin still gets the bell — fail open, never silently drop a HIGH'
        1 * notificationService.push(11L, 'FRAUD_SIGNAL_HIGH', _, _, _, _) >> new Notification(id: 1L)
        notThrown(Exception)
    }

    def "sweeper swallows a UNIQUE-violation on save (sibling pod beat us between check and insert)"() {
        // The TOCTOU window between existsBySignature and save can let a
        // sibling pod's claim slip in. The unique constraint is the true
        // gate; the violation just means the sibling pod is the legitimate
        // owner — we already pushed our bells, swallow the error and
        // move on. Must NOT propagate up and crash the sweeper.
        given:
        def notificationService = Mock(NotificationService)
        def steamUserRepository = Mock(SteamUserRepository)
        def fraudSignalClaimRepository = Mock(com.sboxmarket.repository.FraudSignalClaimRepository)
        def svc = new FraudAnalysisService(
            auditLogRepository:           auditLogRepository,
            notificationService:          notificationService,
            steamUserRepository:          steamUserRepository,
            fraudSignalClaimRepository:   fraudSignalClaimRepository
        )
        def t = now()
        auditLogRepository.since(_) >> (1..6).collect { i -> row(actor: 1L, ip: "10.0.0.${i}", ts: t) }
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 11L, role: 'ADMIN')]
        fraudSignalClaimRepository.existsBySignature(_) >> false

        when:
        svc.sweepAndPushFraudSignals()

        then:
        1 * notificationService.push(11L, _, _, _, _, _) >> new Notification(id: 1L)
        1 * fraudSignalClaimRepository.save(_) >> {
            throw new org.springframework.dao.DataIntegrityViolationException('duplicate key value violates unique constraint "idx_fraud_signal_claims_signature"')
        }
        notThrown(Exception)
    }

    def "retention sweep deletes claim rows older than 48h"() {
        given: 'the ledger has aging rows the daily sweep should prune'
        def fraudSignalClaimRepository = Mock(com.sboxmarket.repository.FraudSignalClaimRepository)
        def svc = new FraudAnalysisService(
            auditLogRepository:           auditLogRepository,
            fraudSignalClaimRepository:   fraudSignalClaimRepository
        )

        when:
        svc.sweepOldFraudSignalClaims()

        then: 'we delete rows older than 48h (2× the 24h fraud window for safety)'
        1 * fraudSignalClaimRepository.deleteOlderThan({ Long cutoff ->
            long now = System.currentTimeMillis()
            // 2 × 24h window = 48h. Allow ±2s slack for test scheduling jitter.
            long expected = now - (2L * 24L * 60L * 60L * 1000L)
            Math.abs(cutoff - expected) < 2000L
        }) >> 7
        notThrown(Exception)
    }

    def "retention sweep is a no-op when the ledger repo is not wired (unit-test path)"() {
        given:
        // Build the service with no ledger repo to match the
        // single-process / unit-test bootstrap.
        def svc = new FraudAnalysisService(auditLogRepository: auditLogRepository)

        when:
        svc.sweepOldFraudSignalClaims()

        then:
        notThrown(Exception)
    }

    def "retention sweep swallows a delete failure without propagating"() {
        given: 'the DB is having a bad day'
        def fraudSignalClaimRepository = Mock(com.sboxmarket.repository.FraudSignalClaimRepository)
        def svc = new FraudAnalysisService(
            auditLogRepository:           auditLogRepository,
            fraudSignalClaimRepository:   fraudSignalClaimRepository
        )
        fraudSignalClaimRepository.deleteOlderThan(_) >> { throw new RuntimeException('db down') }

        when:
        svc.sweepOldFraudSignalClaims()

        then: 'the @Scheduled thread must NOT die from a transient DB error'
        notThrown(Exception)
    }
}
