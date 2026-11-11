package com.sboxmarket

import com.sboxmarket.config.GlobalExceptionHandler
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.AuditLog
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.AuditLogRepository
import com.sboxmarket.repository.ProcessedStripeEventRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.FraudAnalysisService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.StripeService
import com.stripe.model.Event
import com.stripe.model.EventDataObjectDeserializer
import com.stripe.model.radar.EarlyFraudWarning
import com.stripe.net.Webhook
import com.stripe.param.checkout.SessionCreateParams
import jakarta.servlet.http.HttpServletRequest
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.Specification

/**
 * Scam-defense pass 4 (2026-11-08), the items the owner approved:
 *
 *  4. Stripe Early Fraud Warnings are audited, ping admins and count in the
 *     CHARGEBACK_IN_WINDOW fraud signal. No wallet hold.
 *  8. Checkout sends client_reference_id, and the email only when verified.
 *  -  Fresh-card hold: card money can't be cashed out until N days (7 by
 *     default) after the deposit completed.
 *
 * Items 2 and 3 (the bot account by the offer code, the API-key line in
 * declined-offer bells) are covered by main's own specs: SteamTradeBotServiceSpec,
 * TradeControllerSpec, SteamEscrowHoldSpec, SteamEscrowDeclineRecheckSpec and
 * SteamDeliveryServiceSpec.
 */
class ScamDefensePass4Spec extends Specification {

    def cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    // ── 4. Early Fraud Warnings ─────────────────────────────────────

    private StripeService stripe(Map extra = [:]) {
        def s = new StripeService(
                secretKey: 'sk_test_replace_me', publishableKey: 'pk_test_replace_me',
                webhookSecret: 'whsec_replace_me', successUrl: 'http://localhost/ok?x=1',
                cancelUrl: 'http://localhost/cancel', currency: 'usd')
        extra.each { k, v -> s[k] = v }
        s
    }

    def "an Early Fraud Warning is matched to its deposit, audited and pinged to every admin, with no hold"() {
        given:
        def txRepo = Mock(TransactionRepository)
        def wallets = Mock(WalletRepository)
        def users = Mock(SteamUserRepository)
        def audit = Mock(AuditService)
        def notes = Mock(NotificationService)
        def s = stripe(transactionRepository: txRepo, walletRepository: wallets, steamUserRepository: users,
                auditService: audit, notificationService: notes)
        def dep = new Transaction(id: 770L, walletId: 500L, type: 'DEPOSIT', status: 'COMPLETED',
                amount: 50.00G, description: 'card deposit [pi:pi_123]', stripeReference: 'cs_1')
        txRepo.findByTypeAndStatusOrderByCreatedAtDesc('DEPOSIT', 'COMPLETED') >> [dep]
        txRepo.findByTypeAndStatusOrderByCreatedAtDesc('DEPOSIT', 'DISPUTED') >> []
        wallets.findById(500L) >> Optional.of(new Wallet(id: 500L, username: 'steam_76561198000000001'))
        users.findBySteamId64('76561198000000001') >> new SteamUser(id: 9L)
        users.findByRole('ADMIN') >> [new SteamUser(id: 1L), new SteamUser(id: 2L)]

        when:
        s.handleEarlyFraudWarning(new EarlyFraudWarning(id: 'issfr_1', charge: 'ch_1', paymentIntent: 'pi_123',
                fraudType: 'unauthorized_use_of_card', actionable: true))

        then:
        1 * audit.log(AuditService.EARLY_FRAUD_WARNING, null, 9L, 770L, { it.contains('issfr_1') && it.contains('$50.00') })
        1 * notes.safePush(1L, 'CHARGEBACK_OPENED', { it.contains('Early fraud warning') }, _, 770L, '/admin?tab=disputes')
        1 * notes.safePush(2L, 'CHARGEBACK_OPENED', _, _, 770L, '/admin?tab=disputes')
        0 * notes.safePush(9L, *_)
        0 * txRepo.save(_)
        0 * wallets.save(_)
        dep.status == 'COMPLETED'
    }

    def "an unmatched Early Fraud Warning still audits and pings admins"() {
        given:
        def txRepo = Mock(TransactionRepository)
        def users = Mock(SteamUserRepository)
        def audit = Mock(AuditService)
        def notes = Mock(NotificationService)
        def s = Spy(stripe(transactionRepository: txRepo, walletRepository: Mock(WalletRepository),
                steamUserRepository: users, auditService: audit, notificationService: notes))
        s.lookupChargePaymentIntent('ch_9') >> null
        users.findByRole('ADMIN') >> [new SteamUser(id: 1L)]

        when:
        s.handleEarlyFraudWarning(new EarlyFraudWarning(id: 'issfr_9', charge: 'ch_9'))

        then:
        1 * audit.log(AuditService.EARLY_FRAUD_WARNING, null, null, null, { it.contains('no matching deposit') })
        1 * notes.safePush(1L, 'CHARGEBACK_OPENED', _, _, null, '/admin?tab=disputes')
    }

    def "the webhook routes radar.early_fraud_warning.created to the handler"() {
        given:
        def processed = Mock(ProcessedStripeEventRepository) { existsByEventId(_) >> false }
        def s = Spy(stripe(processedStripeEventRepository: processed, environment: SpecEnvs.env(),
                transactionRepository: Mock(TransactionRepository), walletRepository: Mock(WalletRepository)))
        def efw = new EarlyFraudWarning(id: 'issfr_2', charge: 'ch_2')
        def deser = Mock(EventDataObjectDeserializer) { getObject() >> Optional.of(efw) }
        def event = Mock(Event) {
            getId() >> 'evt_efw'
            getType() >> 'radar.early_fraud_warning.created'
            getDataObjectDeserializer() >> deser
        }
        GroovySpy(Webhook, global: true)
        Webhook.constructEvent(_, _, _) >> event

        when:
        s.handleWebhookEvent('{}', 'sig')

        then:
        1 * s.handleEarlyFraudWarning(efw) >> null
    }

    def "an Early Fraud Warning audit row raises a HIGH CHARGEBACK_IN_WINDOW signal"() {
        given:
        def repo = Mock(AuditLogRepository)
        def fraud = new FraudAnalysisService(auditLogRepository: repo)
        repo.since(_) >> [new AuditLog(id: 31L, eventType: AuditService.EARLY_FRAUD_WARNING, subjectUserId: 9L,
                summary: 'Stripe early fraud warning issfr_1', createdAt: System.currentTimeMillis())]

        when:
        def sig = fraud.computeSignals().find { it.type == 'CHARGEBACK_IN_WINDOW' }

        then:
        sig != null
        sig.severity == 'HIGH'
        sig.userId == 9L
        sig.summary.startsWith('Stripe early fraud warning')
    }

    // ── 8. Checkout risk data ───────────────────────────────────────

    def "checkout sends the wallet id as client_reference_id, and the email only when it is verified"() {
        given:
        def wallets = Mock(WalletRepository)
        def txRepo = Mock(TransactionRepository)
        def users = Mock(SteamUserRepository)
        def s = stripe(walletRepository: wallets, transactionRepository: txRepo, steamUserRepository: users,
                secretKey: 'sk_live_radar_test', dailyDepositCap: 5000G)
        wallets.findByIdForUpdate(500L) >> Optional.of(new Wallet(id: 500L, username: 'steam_76561198000000001',
                balance: 0G))
        txRepo.sumDepositsSince(_, _) >> 0G
        txRepo.save(_) >> { Transaction t -> t.id = 1L; t }
        users.findBySteamId64('76561198000000001') >> new SteamUser(id: 9L, email: 'a@example.com',
                emailVerified: verified)
        SessionCreateParams sent = null
        GroovySpy(com.stripe.model.checkout.Session, global: true)
        com.stripe.model.checkout.Session.create(_, _) >> { SessionCreateParams p, opts ->
            sent = p
            [id: 'cs_live_radar', url: 'https://stripe/co']
        }

        when:
        s.createDepositSession(500L, 100.00G)

        then:
        sent.clientReferenceId == '500'
        sent.customerEmail == email

        where:
        verified | email
        true     | 'a@example.com'
        false    | null
    }

    // ── Fresh-card withdrawal hold ──────────────────────────────────

    private static final long DAY = 24L * 60L * 60L * 1000L

    private StripeService holdService(TransactionRepository txRepo, Long days = 7L) {
        stripe(transactionRepository: txRepo, cardHoldDays: days)
    }

    private static Transaction cardDeposit(BigDecimal amount, long completedAgoMs, BigDecimal fee = 0G, String ref = 'cs_x') {
        new Transaction(walletId: 500L, type: 'DEPOSIT', status: 'COMPLETED', amount: amount, feeAmount: fee,
                stripeReference: ref, updatedAt: System.currentTimeMillis() - completedAgoMs)
    }

    def "card money younger than the hold can't be withdrawn; the error names the date it frees up"() {
        given:
        def txRepo = Mock(TransactionRepository)
        long completedAgo = 2L * DAY
        def dep = cardDeposit(100.00G, completedAgo, 3.20G)
        txRepo.findCompletedDepositsUpdatedSince(500L, _) >> [dep]
        String expectedDate = java.time.format.DateTimeFormatter.ofPattern('d MMM yyyy, HH:mm', Locale.ENGLISH)
                .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(dep.updatedAt + 7L * DAY))

        when: 'balance 120 = 96.80 fresh card money + 23.20 older money; asking 50'
        holdService(txRepo).assertCardHoldAllowsWithdrawal(500L, 120.00G, 50.00G)

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'WITHDRAW_CARD_HOLD'
        ex.message.contains('can\'t be withdrawn for 7 days')
        ex.message.contains('up to $23.20 now')
        ex.message.contains(expectedDate + ' UTC')

        when: 'the part not from fresh card money is fine'
        holdService(txRepo).assertCardHoldAllowsWithdrawal(500L, 120.00G, 23.20G)

        then:
        noExceptionThrown()
    }

    def "the date is the first deposit whose ageing-out covers the amount"() {
        given:
        def txRepo = Mock(TransactionRepository)
        def older = cardDeposit(30G, 6L * DAY)
        def newer = cardDeposit(70G, 1L * DAY)
        txRepo.findCompletedDepositsUpdatedSince(500L, _) >> [newer, older]
        String olderFree = java.time.format.DateTimeFormatter.ofPattern('d MMM yyyy, HH:mm', Locale.ENGLISH)
                .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(older.updatedAt + 7L * DAY))

        when: 'balance 100, all fresh; 25 frees when the older 30 ages out'
        holdService(txRepo).assertCardHoldAllowsWithdrawal(500L, 100G, 25G)

        then:
        def ex = thrown(BadRequestException)
        ex.message.contains('up to $0.00 now')
        ex.message.contains(olderFree)
    }

    def "dev credits, held-off config and no fresh deposits never block"() {
        given:
        def txRepo = Mock(TransactionRepository)
        def other = Mock(TransactionRepository)
        txRepo.findCompletedDepositsUpdatedSince(500L, _) >> [cardDeposit(100G, DAY, 0G, 'dev_123')]

        when:
        holdService(txRepo).assertCardHoldAllowsWithdrawal(500L, 100G, 100G)

        then:
        noExceptionThrown()

        when:
        holdService(other, 0L).assertCardHoldAllowsWithdrawal(500L, 100G, 100G)

        then:
        noExceptionThrown()
        0 * other.findCompletedDepositsUpdatedSince(*_)
    }

    def "requestWithdrawal applies the hold before any money moves"() {
        given:
        def wallets = Mock(WalletRepository)
        def txRepo = Mock(TransactionRepository)
        def s = stripe(walletRepository: wallets, transactionRepository: txRepo, cardHoldDays: 7L,
                secretKey: 'sk_live_hold')
        wallets.findById(500L) >> Optional.of(new Wallet(id: 500L, username: 'steam_1', balance: 100G,
                payoutsEnabled: true, stripeConnectAccountId: 'acct_1'))
        txRepo.findCompletedDepositsUpdatedSince(500L, _) >> [cardDeposit(100G, DAY)]

        when:
        s.requestWithdrawal(500L, 10G, 'acct_1')

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'WITHDRAW_CARD_HOLD'
        0 * wallets.save(_)
        0 * txRepo.save(_)
    }

    def "the hold message reaches the user through the exception handler"() {
        given:
        def handler = new GlobalExceptionHandler(verboseErrors: false)
        def req = Mock(HttpServletRequest) { getMethod() >> 'POST'; getRequestURI() >> '/api/wallet/withdraw' }
        String msg = "Card deposits can be spent on the market right away but can't be withdrawn for 7 days. " +
                'You can withdraw up to $0.00 now; this amount becomes withdrawable on 18 Nov 2026, 10:00 UTC.'

        when:
        def resp = handler.handleApi(new BadRequestException('WITHDRAW_CARD_HOLD', msg), req)

        then:
        resp.body.code == 'WITHDRAW_CARD_HOLD'
        resp.body.message == msg
    }
}
