package com.sboxmarket

import com.sboxmarket.config.GlobalExceptionHandler
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.controller.TradeController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.AuditLog
import com.sboxmarket.model.EscrowedItem
import com.sboxmarket.model.SteamDeliveryAttempt
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Trade
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.AuditLogRepository
import com.sboxmarket.repository.EscrowedItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.ProcessedStripeEventRepository
import com.sboxmarket.repository.SteamDeliveryAttemptRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.FraudAnalysisService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.SteamBotResult
import com.sboxmarket.service.SteamDeliveryService
import com.sboxmarket.service.SteamEscrowService
import com.sboxmarket.service.StripeService
import com.sboxmarket.service.SteamTradeBotService
import com.sboxmarket.service.TradeService
import com.stripe.model.Event
import com.stripe.model.EventDataObjectDeserializer
import com.stripe.model.radar.EarlyFraudWarning
import com.stripe.net.Webhook
import com.stripe.param.checkout.SessionCreateParams
import groovy.json.JsonOutput
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import org.springframework.data.domain.Pageable
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.Specification

/**
 * Scam-defense pass 4 (2026-11-08), the items the owner approved:
 *
 *  2. The bot's own Steam account (id + name from the sidecar's /health)
 *     rides on every payload that shows an offer code, so a user can check
 *     an offer comes from it and not from a scammer copying the code.
 *  3. A "declined" offer bell tells the user to revoke unknown Steam Web API
 *     keys and change the password when the decline wasn't theirs.
 *  4. Stripe Early Fraud Warnings are audited, ping admins and count in the
 *     CHARGEBACK_IN_WINDOW fraud signal. No wallet hold.
 *  8. Checkout sends client_reference_id, and the email only when verified.
 *  -  Fresh-card hold: card money can't be cashed out until N days (7 by
 *     default) after the deposit completed.
 */
class ScamDefensePass4Spec extends Specification {

    static final String BOT_ID = '76561198000000042'

    def cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    // ── 2. bot identity ─────────────────────────────────────────────

    private SteamTradeBotService botSpy() {
        def svc = Spy(SteamTradeBotService)
        svc.baseUrl = 'http://localhost:4000'
        svc.apiToken = 't'
        svc
    }

    private static SteamTradeBotService.RawResponse health(Map body) {
        new SteamTradeBotService.RawResponse(status: 200, body: JsonOutput.toJson(body))
    }

    def "botIdentity reads the bot's Steam id and persona name from /health, and caches it"() {
        given:
        def svc = botSpy()

        when:
        def a = svc.botIdentity()
        def b = svc.botIdentity()

        then: 'one /health read serves both calls'
        1 * svc.doRequest('GET', 'http://localhost:4000/health', null, _ as Long) >>
                health([ok: true, loggedIn: true, steamId: BOT_ID, personaName: 'SkinBox Bot 1'])
        a == [steamId: BOT_ID, name: 'SkinBox Bot 1']
        b == a
    }

    def "botIdentity is null when /health reports #why"() {
        given:
        def svc = botSpy()

        when:
        def id = svc.botIdentity()

        then:
        1 * svc.doRequest(*_) >> health(body)
        id == null

        where:
        why                    | body
        'a fake (dev) sidecar' | [ok: true, steamId: '76561190000000001', fake: true]
        'no id (logged out)'   | [ok: true, steamId: null]
        'a malformed id'       | [ok: true, steamId: '12345']
        'a failure'            | [ok: false, error: 'NOT_READY']
    }

    def "botIdentity makes no call when the bot is off"() {
        given:
        def svc = botSpy()
        svc.baseUrl = ''

        when:
        def id = svc.botIdentity()

        then:
        0 * svc.doRequest(*_)
        id == null
    }

    def "botIdentity without a persona name still gives the id"() {
        given:
        def svc = botSpy()

        when:
        def id = svc.botIdentity()

        then:
        1 * svc.doRequest(*_) >> health([ok: true, steamId: BOT_ID])
        id == [steamId: BOT_ID, name: null]
    }

    def "the buyer's trade payload carries the bot's Steam id and name next to the delivery code"() {
        given:
        def tradeService = Mock(TradeService)
        def delivery = Mock(SteamDeliveryService)
        def bot = Mock(SteamTradeBotService)
        def controller = new TradeController(tradeService: tradeService, steamDeliveryService: delivery,
                steamTradeBotService: bot)
        def req = Mock(HttpServletRequest)
        def ses = Mock(HttpSession)
        req.session >> ses
        ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 2L
        def t = new Trade(id: 7L, buyerUserId: 2L, sellerUserId: 1L, state: 'PENDING_BUYER_CONFIRM')
        tradeService.get(7L) >> t
        delivery.deliveryCodeFor(_) >> 'K7Q2'

        when:
        def known = controller.get(7L, req).body

        then:
        1 * bot.botIdentity() >> [steamId: BOT_ID, name: 'SkinBox Bot 1']
        known.deliveryCode == 'K7Q2'
        known.botSteamId == BOT_ID
        known.botName == 'SkinBox Bot 1'

        when: 'bot unknown: no identity keys at all'
        def unknown = controller.get(7L, req).body

        then:
        1 * bot.botIdentity() >> null
        unknown.deliveryCode == 'K7Q2'
        !unknown.containsKey('botSteamId')
        !unknown.containsKey('botName')
    }

    def "the trade list reads the bot identity once and puts it only on rows with a code"() {
        given:
        def tradeService = Mock(TradeService)
        def delivery = Mock(SteamDeliveryService)
        def bot = Mock(SteamTradeBotService)
        def controller = new TradeController(tradeService: tradeService, steamDeliveryService: delivery,
                steamTradeBotService: bot)
        def req = Mock(HttpServletRequest)
        def ses = Mock(HttpSession)
        req.session >> ses
        ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> 2L
        tradeService.listForUserWithCounterparty(2L) >> [
                [id: 1L, buyerUserId: 2L], [id: 2L, buyerUserId: 2L], [id: 3L, buyerUserId: 9L]]
        tradeService.countForUser(2L) >> 3L
        delivery.deliveryCodeFor(_) >> 'ABCD'

        when:
        List<Map> rows = controller.mine(req).body

        then:
        1 * bot.botIdentity() >> [steamId: BOT_ID, name: null]
        rows[0].botSteamId == BOT_ID && rows[1].botSteamId == BOT_ID
        !rows[0].containsKey('botName')
        !rows[2].containsKey('botSteamId')
    }

    private SteamEscrowService escrow(SteamTradeBotService bot, EscrowedItemRepository repo) {
        def s = new SteamEscrowService()
        s.steamTradeBotService = bot
        s.escrowRepository = repo
        s.listingRepository = Mock(ListingRepository)
        s.offerMessage = 'sboxmarket escrow'
        s
    }

    def "held-deposit and returning rows carry the bot's Steam account with their code"() {
        given:
        def bot = Mock(SteamTradeBotService) { isEnabled() >> true }
        def repo = Mock(EscrowedItemRepository)
        def s = escrow(bot, repo)
        def pending = new EscrowedItem(id: 1L, listingId: 10L, sellerUserId: 1L, assetId: '5',
                custodyState: EscrowedItem.PENDING_DEPOSIT, securityCode: 'Q1W2')
        def returning = new EscrowedItem(id: 2L, listingId: 11L, sellerUserId: 1L, assetId: '6',
                custodyState: EscrowedItem.RETURNED, securityCode: 'Z9X8',
                returnRequestedAt: System.currentTimeMillis() - 1000L)
        repo.findByListingIds(_) >> [pending]
        repo.findBySellerUserIdAndCustodyStateIn(1L, _) >> [returning]
        bot.botIdentity() >> [steamId: BOT_ID, name: 'SkinBox Bot 1']

        when:
        def view = s.custodyViewForListings([10L])
        def seller = s.custodyForSeller(1L)

        then:
        view[10L].securityCode == 'Q1W2'
        view[10L].botSteamId == BOT_ID
        view[10L].botName == 'SkinBox Bot 1'
        seller.returning[0].securityCode == 'Z9X8'
        seller.returning[0].botSteamId == BOT_ID
    }

    def "custody rows show no bot account when the bot identity is unknown"() {
        given:
        def bot = Mock(SteamTradeBotService) { isEnabled() >> true; botIdentity() >> null }
        def repo = Mock(EscrowedItemRepository)
        repo.findByListingIds(_) >> [new EscrowedItem(id: 1L, listingId: 10L, sellerUserId: 1L, assetId: '5',
                custodyState: EscrowedItem.PENDING_DEPOSIT, securityCode: 'Q1W2')]

        when:
        def view = escrow(bot, repo).custodyViewForListings([10L])

        then:
        view[10L].securityCode == 'Q1W2'
        !view[10L].containsKey('botSteamId')
    }

    // ── 3. decline bells ────────────────────────────────────────────

    static final String API_KEY_LINE = "If you didn't decline it, revoke any Steam Web API key you didn't create " +
            "(steamcommunity.com/dev/apikey) and change your Steam password."

    def "a declined deposit bell tells the seller to revoke unknown API keys and change the password"() {
        given:
        def bot = Mock(SteamTradeBotService) { isEnabled() >> true }
        def repo = Mock(EscrowedItemRepository)
        def notes = Mock(NotificationService)
        def s = escrow(bot, repo)
        s.notificationService = notes
        def e = new EscrowedItem(id: 5L, listingId: 10L, sellerUserId: 1L, assetId: '555',
                depositOfferId: '444', custodyState: EscrowedItem.PENDING_DEPOSIT)
        repo.findById(5L) >> Optional.of(e)
        bot.getOfferStatus('444') >> SteamBotResult.success([ok: true, status: status])
        String body = null

        when:
        s.confirmDeposit(5L, [ok: true, byAsset: [:], byName: [:], nonTradable: [] as Set, items: []])

        then:
        1 * notes.safePush(1L, 'TRADE_SENT', 'Steam escrow', _, 10L, '/me/stall') >> { args -> body = args[3] }
        body.contains(API_KEY_LINE) == warned

        where:
        status     | warned
        'declined' | true
        'expired'  | false
    }

    def "a declined delivery bell tells the buyer the same; the seller's bell is unchanged"() {
        given:
        def bot = Mock(SteamTradeBotService) { isEnabled() >> true }
        def trades = Mock(TradeRepository)
        def attempts = Mock(SteamDeliveryAttemptRepository)
        def notes = Mock(NotificationService)
        def svc = new SteamDeliveryService(steamTradeBotService: bot, tradeService: Mock(TradeService),
                tradeRepository: trades, attemptRepository: attempts,
                steamUserRepository: Mock(SteamUserRepository), notificationService: notes,
                steamEscrowService: Mock(SteamEscrowService), pollerEnabled: true, batchSize: 50)
        def t = new Trade(id: 7L, state: SteamDeliveryService.STATE_AWAITING_CONFIRM, buyerUserId: 2L,
                sellerUserId: 1L, listingId: 10L, itemId: 20L, itemName: 'Hat', price: 10G)
        trades.findById(7L) >> Optional.of(t)
        attempts.findLatestWithOffer(7L, _ as Pageable) >> [new SteamDeliveryAttempt(id: 1L, tradeId: 7L,
                steamOfferId: '987', offerState: 'active', phase: 'SEND', success: true)]
        bot.getOfferStatus('987') >> SteamBotResult.success([ok: true, offerId: '987', status: status])
        String buyerBody = null
        String sellerBody = null

        when:
        svc.processTrade(7L)

        then:
        1 * notes.safePush(2L, _, _, _, _, _) >> { args -> buyerBody = args[3] }
        1 * notes.safePush(1L, _, _, _, _, _) >> { args -> sellerBody = args[3] }
        buyerBody.contains(API_KEY_LINE) == warned
        !sellerBody.contains('apikey')

        where:
        status     | warned
        'declined' | true
        'expired'  | false
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
