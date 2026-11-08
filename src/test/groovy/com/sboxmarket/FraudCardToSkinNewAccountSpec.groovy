package com.sboxmarket

import com.sboxmarket.model.AuditLog
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.AuditLogRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.FraudAnalysisService
import com.sboxmarket.service.NotificationService
import spock.lang.Specification

/**
 * CARD_TO_SKIN_NEW_ACCOUNT: an account under 7 days old completes a card
 * deposit and buys listings within 60 minutes after it. HIGH at a $200+
 * purchase total in the window, else MED. Pure-logic, mocked repositories.
 */
class FraudCardToSkinNewAccountSpec extends Specification {

    static final long MIN = 60_000L
    static final long DAY = 24L * 60L * MIN

    AuditLogRepository auditLogRepository = Mock()
    SteamUserRepository steamUserRepository = Mock()
    ListingRepository listingRepository = Mock()

    FraudAnalysisService service = new FraudAnalysisService(
        auditLogRepository: auditLogRepository,
        steamUserRepository: steamUserRepository,
        listingRepository: listingRepository)

    long t = System.currentTimeMillis()

    private static AuditLog deposit(long id, Long uid, long ts) {
        new AuditLog(id: id, actorUserId: uid, actorName: "user${uid}", subjectUserId: uid,
            eventType: AuditService.DEPOSIT_COMPLETE, resourceId: 900L + id,
            summary: 'Deposit $50 credited', ipAddress: '10.0.0.1', createdAt: ts)
    }

    private static AuditLog purchase(long id, Long uid, Long listingId, long ts, String price = '10.00') {
        new AuditLog(id: id, actorUserId: uid, actorName: "user${uid}",
            eventType: AuditService.LISTING_PURCHASED, resourceId: listingId,
            summary: "Bought Hat for \$${price}", ipAddress: '10.0.0.2', createdAt: ts)
    }

    private void accounts(Map<Long, Long> createdAtById) {
        steamUserRepository.findAllById(_) >> { args ->
            (args[0] as Collection).findAll { createdAtById.containsKey(it) }
                .collect { new SteamUser(id: it, steamId64: "s${it}", createdAt: createdAtById[it]) }
        }
    }

    private void prices(Map<Long, String> priceById) {
        listingRepository.findAllById(_) >> { args ->
            (args[0] as Collection).findAll { priceById.containsKey(it) }
                .collect { new Listing(id: it, price: new BigDecimal(priceById[it])) }
        }
    }

    private List<Map> signals() {
        service.computeSignals().findAll { it.type == 'CARD_TO_SKIN_NEW_ACCOUNT' }
    }

    def "MED for a 2-day-old account buying under \$200 within 60 min of a card deposit"() {
        given:
        accounts([5L: t - 2 * DAY])
        prices([101L: '40.00', 102L: '35.50'])
        auditLogRepository.since(_) >> [
            deposit(1, 5L, t - 30 * MIN),
            purchase(2, 5L, 101L, t - 20 * MIN),
            purchase(3, 5L, 102L, t - 10 * MIN),
        ]

        when:
        def s = signals()

        then:
        s.size() == 1
        s[0].severity == 'MED'
        s[0].userId == 5L
        s[0].count == 2
        s[0].total == new BigDecimal('75.50')
        s[0].dedupKey == 1L
        s[0].summary.contains('$75.50')
    }

    def "HIGH when the window total reaches exactly \$200"() {
        given:
        accounts([5L: t - 1 * DAY])
        prices([101L: '150.00', 102L: '50.00'])
        auditLogRepository.since(_) >> [
            deposit(1, 5L, t - 50 * MIN),
            purchase(2, 5L, 101L, t - 40 * MIN),
            purchase(3, 5L, 102L, t - 30 * MIN),
        ]

        expect:
        signals()*.severity == ['HIGH']
    }

    def "MED at \$199.99"() {
        given:
        accounts([5L: t - 1 * DAY])
        prices([101L: '199.99'])
        auditLogRepository.since(_) >> [deposit(1, 5L, t - 50 * MIN), purchase(2, 5L, 101L, t - 40 * MIN)]

        expect:
        signals()*.severity == ['MED']
    }

    def "quiet for an account 7 days old or older at deposit time"() {
        given:
        accounts([5L: (t - 30 * MIN) - 7 * DAY])
        prices([101L: '500.00'])
        auditLogRepository.since(_) >> [deposit(1, 5L, t - 30 * MIN), purchase(2, 5L, 101L, t - 20 * MIN)]

        expect:
        signals().isEmpty()
    }

    def "fires one ms under the 7-day age limit"() {
        given:
        accounts([5L: (t - 30 * MIN) - 7 * DAY + 1])
        prices([101L: '5.00'])
        auditLogRepository.since(_) >> [deposit(1, 5L, t - 30 * MIN), purchase(2, 5L, 101L, t - 20 * MIN)]

        expect:
        signals().size() == 1
    }

    def "a purchase exactly 60 min after the deposit counts; one ms later does not"() {
        given:
        def d = t - 2 * 60 * MIN
        accounts([5L: t - DAY])
        prices([101L: '120.00', 102L: '120.00'])
        auditLogRepository.since(_) >> [
            deposit(1, 5L, d),
            purchase(2, 5L, 101L, d + 60 * MIN),
            purchase(3, 5L, 102L, d + 60 * MIN + 1),
        ]

        when:
        def s = signals()

        then:
        s.size() == 1
        s[0].count == 1
        s[0].severity == 'MED'
    }

    def "purchases before the deposit are ignored"() {
        given:
        accounts([5L: t - DAY])
        prices([101L: '300.00'])
        auditLogRepository.since(_) >> [purchase(2, 5L, 101L, t - 40 * MIN), deposit(1, 5L, t - 30 * MIN)]

        expect:
        signals().isEmpty()
    }

    def "another user's purchase does not match the deposit"() {
        given:
        accounts([5L: t - DAY, 6L: t - DAY])
        prices([101L: '300.00'])
        auditLogRepository.since(_) >> [deposit(1, 5L, t - 30 * MIN), purchase(2, 6L, 101L, t - 20 * MIN)]

        expect:
        signals().isEmpty()
    }

    def "null-actor deposit rows are never correlated"() {
        given:
        accounts([:])
        prices([101L: '300.00'])
        auditLogRepository.since(_) >> [deposit(1, null, t - 30 * MIN), purchase(2, null, 101L, t - 20 * MIN)]

        expect:
        signals().isEmpty()
    }

    def "unknown user (no SteamUser row) stays quiet"() {
        given:
        accounts([:])
        prices([101L: '300.00'])
        auditLogRepository.since(_) >> [deposit(1, 5L, t - 30 * MIN), purchase(2, 5L, 101L, t - 20 * MIN)]

        expect:
        signals().isEmpty()
    }

    def "falls back to the audit summary amount when the listing can't be loaded"() {
        given:
        accounts([5L: t - DAY])
        prices([:])
        auditLogRepository.since(_) >> [deposit(1, 5L, t - 30 * MIN), purchase(2, 5L, 101L, t - 20 * MIN, '250.00')]

        when:
        def s = signals()

        then:
        s.size() == 1
        s[0].severity == 'HIGH'
        s[0].total == new BigDecimal('250.00')
    }

    def "no user repository wired -> detector is a no-op"() {
        given:
        def bare = new FraudAnalysisService(auditLogRepository: auditLogRepository)
        auditLogRepository.since(_) >> [deposit(1, 5L, t - 30 * MIN), purchase(2, 5L, 101L, t - 20 * MIN, '250.00')]

        expect:
        !bare.computeSignals().any { it.type == 'CARD_TO_SKIN_NEW_ACCOUNT' }
    }

    def "user lookup failure is swallowed, other signals still compute"() {
        given:
        steamUserRepository.findAllById(_) >> { throw new RuntimeException('db blip') }
        auditLogRepository.since(_) >> [deposit(1, 5L, t - 30 * MIN), purchase(2, 5L, 101L, t - 20 * MIN)]

        when:
        def all = service.computeSignals()

        then:
        noExceptionThrown()
        !all.any { it.type == 'CARD_TO_SKIN_NEW_ACCOUNT' }
    }

    def "thresholds are configurable"() {
        given:
        service.cardToSkinMaxAccountAgeDays = 30L
        service.cardToSkinWindowMinutes = 180L
        service.cardToSkinHighTotalUsd = new BigDecimal('50')
        accounts([5L: t - 20 * DAY])
        prices([101L: '60.00'])
        auditLogRepository.since(_) >> [deposit(1, 5L, t - 170 * MIN), purchase(2, 5L, 101L, t - 10 * MIN)]

        expect:
        signals()*.severity == ['HIGH']
    }

    def "defaults are 7 days, 60 minutes and \$200"() {
        expect:
        service.cardToSkinMaxAccountAgeDays == 7L
        service.cardToSkinWindowMinutes == 60L
        service.cardToSkinHighTotalUsd == new BigDecimal('200')
    }

    def "signal is read-only: nothing is written, no listing or user is touched"() {
        given:
        accounts([5L: t - DAY])
        prices([101L: '300.00'])
        auditLogRepository.since(_) >> [deposit(1, 5L, t - 30 * MIN), purchase(2, 5L, 101L, t - 20 * MIN)]

        when:
        def s = signals()

        then:
        s.size() == 1
        0 * steamUserRepository.save(_)
        0 * listingRepository.save(_)
        0 * listingRepository.saveAndFlush(_)
        0 * auditLogRepository.save(_)
    }

    def "HIGH signal reaches the admin sweeper once per deposit"() {
        given:
        def notificationService = Mock(NotificationService)
        service.notificationService = notificationService
        accounts([5L: t - DAY])
        prices([101L: '300.00'])
        steamUserRepository.findByRole('ADMIN') >> [new SteamUser(id: 11L, role: 'ADMIN')]
        auditLogRepository.since(_) >> [deposit(1, 5L, t - 30 * MIN), purchase(2, 5L, 101L, t - 20 * MIN)]

        when:
        service.sweepAndPushFraudSignals()
        service.sweepAndPushFraudSignals()

        then:
        1 * notificationService.push(11L, 'FRAUD_SIGNAL_HIGH', { it.contains('CARD_TO_SKIN_NEW_ACCOUNT') }, _, 5L, '/admin?tab=fraud')
    }
}
