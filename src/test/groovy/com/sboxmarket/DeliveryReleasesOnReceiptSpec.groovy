package com.sboxmarket

import com.sboxmarket.model.SteamDeliveryAttempt
import com.sboxmarket.model.Trade
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamDeliveryAttemptRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.SteamBotResult
import com.sboxmarket.service.SteamDeliveryService
import com.sboxmarket.service.SteamEscrowService
import com.sboxmarket.service.SteamTradeBotService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.TradeProtectionService
import com.sboxmarket.service.TradeService
import com.sboxmarket.service.security.AdminAuthorization
import com.sboxmarket.service.security.BanGuard
import org.springframework.data.domain.Pageable
import spock.lang.Specification
import spock.lang.Unroll

/**
 * "We sent an offer" is not "the buyer has the item". Only the second releases
 * money.
 *
 * ── The defect this pins ──────────────────────────────────────────────────
 * {@code SteamDeliveryService.sendOfferForTrade} fired on {@code res.ok} — the
 * offer being SENT, not accepted — and on that signal did two things:
 *
 *   1. {@code steamEscrowService.markDelivered(listingId)}, flipping custody
 *      IN_CUSTODY -> DELIVERED, which puts the item permanently out of reach of
 *      the return-to-seller path; and
 *   2. {@code advanceMarkSent}, which starts the {@code autoReleaseDays} clock.
 *
 * {@code TradeService.sweepPendingConfirm} then released the funds after that
 * window WITHOUT consulting the delivery attempt state at all. So a buyer who
 * simply never clicked accept produced: the seller paid in full on day 8, the
 * buyer's money gone, the item still sitting in our own bot's inventory marked
 * DELIVERED so nothing could give it back, and the Steam offer quietly expiring
 * on day 14 — six days after the money moved.
 *
 * It takes money and delivers nothing, and it sits behind a single config flag
 * ({@code STEAM_BOT_BASE_URL}). It is unreachable with the bot off, which is
 * exactly why it would have been discovered in production.
 *
 * ── The three halves ──────────────────────────────────────────────────────
 * CUSTODY: the item is only DELIVERED once Steam says the buyer accepted, so
 * until then the return path can still recover it.
 * MONEY:   the auto-release sweep asks whether the buyer accepted before
 * paying the SELLER, and holds when the answer is no.
 * COVER:   the Trade Protection auto-payout asks the same delivery log before
 * paying the BUYER, and refuses when the answer is yes.
 *
 * The last two are mirrors, not copies, and the difference is the whole point:
 * the same "I cannot tell" HOLDS the seller's money and PAYS the buyer's
 * cover, because a wrong guess costs a different person each time. See the
 * three-state note above Half 3.
 */
class DeliveryReleasesOnReceiptSpec extends Specification {

    // ══ Half 1 — CUSTODY: the item is not "delivered" until it is taken ══

    SteamDeliveryService delivery
    SteamTradeBotService bot                    = Mock()
    TradeService deliveryTradeService           = Mock()
    TradeRepository deliveryTradeRepository     = Mock()
    SteamDeliveryAttemptRepository attemptRepo  = Mock()
    SteamUserRepository steamUserRepository     = Mock()
    NotificationService deliveryNotifications   = Mock()
    SteamEscrowService escrowService            = Mock()

    def setup() {
        delivery = new SteamDeliveryService(
            steamTradeBotService : bot,
            tradeService         : deliveryTradeService,
            tradeRepository      : deliveryTradeRepository,
            attemptRepository    : attemptRepo,
            steamUserRepository  : steamUserRepository,
            notificationService  : deliveryNotifications,
            steamEscrowService   : escrowService,
            pollerEnabled        : true,
            offerMessage         : 'sboxmarket delivery',
            batchSize            : 50
        )
        delivery.testAssetIdOverride = '555'
        escrowService.heldAssetIdForListing(_) >> null
    }

    private static Trade botTrade(String state) {
        new Trade(id: 7L, state: state, buyerUserId: 2L, sellerUserId: 1L,
                  listingId: 10L, itemId: 20L, itemName: 'AK-47 | Redline',
                  price: new BigDecimal('100.00'))
    }

    private static final String BUYER_URL =
            'https://steamcommunity.com/tradeoffer/new/?partner=2&token=abc'

    def "SENDING the offer does NOT mark the item delivered — we still hold it"() {
        given: "a trade the bot is about to ship"
        def t = botTrade(SteamDeliveryService.STATE_AWAITING_SEND)

        when:
        delivery.processTrade(7L)

        then:
        1 * deliveryTradeRepository.findById(7L) >> Optional.of(t)
        1 * attemptRepo.findLatestWithOffer(7L, _ as Pageable) >> []
        1 * steamUserRepository.findById(2L) >> Optional.of(new SteamUser(id: 2L, tradeUrl: BUYER_URL))

        and: "the offer goes out"
        1 * bot.sendOffer(BUYER_URL, ['555'], 'sboxmarket delivery') >>
                SteamBotResult.success([ok: true, offerId: '987', status: 'sent'])

        and: "THE FIX: the item is still ours. `sent` is not `accepted`, and marking" +
             " custody DELIVERED here put it beyond the reach of return-to-seller while" +
             " it was physically still in the bot's inventory."
        0 * escrowService.markDelivered(_)

        and: "the trade does still advance — an offer really was sent"
        1 * deliveryTradeService.sellerMarkSent(1L, 7L, _ as String)
    }

    def "custody flips to DELIVERED only when Steam reports the buyer ACCEPTED"() {
        given:
        def t = botTrade(SteamDeliveryService.STATE_AWAITING_CONFIRM)

        when:
        delivery.processTrade(7L)

        then:
        1 * deliveryTradeRepository.findById(7L) >> Optional.of(t)
        1 * attemptRepo.findLatestWithOffer(7L, _ as Pageable) >>
                [new SteamDeliveryAttempt(id: 1L, tradeId: 7L, steamOfferId: '987',
                                          offerState: 'sent', phase: 'SEND', success: true)]
        1 * bot.getOfferStatus('987') >> SteamBotResult.success([ok: true, offerId: '987', status: 'accepted'])

        and: "NOW the buyer has it, so now it is delivered"
        1 * escrowService.markDelivered(10L)

        and: "and only now is the seller credited"
        1 * deliveryTradeService.buyerConfirm(2L, 7L)
    }

    @Unroll
    def "an offer that is #status does not count as delivered"() {
        given:
        def t = botTrade(SteamDeliveryService.STATE_AWAITING_CONFIRM)

        when:
        delivery.processTrade(7L)

        then:
        1 * deliveryTradeRepository.findById(7L) >> Optional.of(t)
        1 * attemptRepo.findLatestWithOffer(7L, _ as Pageable) >>
                [new SteamDeliveryAttempt(id: 1L, tradeId: 7L, steamOfferId: '987',
                                          offerState: 'sent', phase: 'SEND', success: true)]
        1 * bot.getOfferStatus('987') >> SteamBotResult.success([ok: true, offerId: '987', status: status])

        and:
        0 * escrowService.markDelivered(_)
        0 * deliveryTradeService.buyerConfirm(_, _)

        where:
        status << ['active', 'needs_confirmation', 'declined', 'expired', 'canceled']
    }

    // ══ Half 2 — MONEY: the sweep asks whether the buyer took it ═════════

    TradeRepository       tradeRepository       = Mock()
    WalletRepository      walletRepository      = Mock()
    TransactionRepository transactionRepository = Mock()
    NotificationService   notificationService   = Mock()
    AuditService          auditService          = Mock()
    BanGuard              banGuard              = Mock()
    AdminAuthorization    adminAuthorization    = Mock()
    TextSanitizer         textSanitizer         = Mock() { medium(_) >> { String s -> s ?: '' } }
    SteamDeliveryAttemptRepository sweepAttemptRepo = Mock()

    Wallet sellerWallet = new Wallet(id: 600L, balance: BigDecimal.ZERO, currency: 'USD')

    /** @param botUrl  STEAM_BOT_BASE_URL; '' means the bot is switched off. */
    private TradeService sweeper(String botUrl = 'http://127.0.0.1:4000') {
        def svc = new TradeService(
            tradeRepository               : tradeRepository,
            walletRepository              : walletRepository,
            transactionRepository         : transactionRepository,
            notificationService           : notificationService,
            auditService                  : auditService,
            banGuard                      : banGuard,
            adminAuthorization            : adminAuthorization,
            textSanitizer                 : textSanitizer,
            autoReleaseDays               : 8L,
            sellerResponseDays            : 3L,
            steamDeliveryAttemptRepository : sweepAttemptRepo,
            steamTradeBotService          : new SteamTradeBotService(baseUrl: botUrl)
        )
        walletRepository.findById(600L) >> Optional.of(sellerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        tradeRepository.save(_) >> { Trade t -> t }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        banGuard.isBanned(_) >> false
        return svc
    }

    private static Trade staleConfirm() {
        new Trade(id: 7L, listingId: 100L, itemId: 1L, itemName: 'AK-47 | Redline',
                  buyerUserId: 10L, buyerWalletId: 500L,
                  sellerUserId: 20L, sellerWalletId: 600L,
                  price: new BigDecimal('50.00'), feeAmount: new BigDecimal('1.00'),
                  state: 'PENDING_BUYER_CONFIRM')
    }

    private static List<SteamDeliveryAttempt> offerIn(String state) {
        [new SteamDeliveryAttempt(id: 1L, tradeId: 7L, steamOfferId: '987',
                                  offerState: state, phase: 'SEND', success: true)]
    }

    @Unroll
    def "THE BUG: the sweep must not pay the seller when the offer is only '#offerState'"() {
        given: "the bot sent a Steam offer 8 days ago and the buyer never accepted it"
        def stale = staleConfirm()
        def svc = sweeper()
        tradeRepository.findPendingConfirmOlderThan(_) >> [stale]
        sweepAttemptRepo.findLatestWithOffer(7L, _ as Pageable) >> offerIn(offerState)

        when:
        svc.sweepPendingConfirm()

        then: "the seller is NOT paid for an item the buyer never received"
        sellerWallet.balance == BigDecimal.ZERO
        stale.state != 'VERIFIED'

        and: "the money stays in escrow, parked for a human rather than silently stuck"
        stale.state == 'DISPUTED'

        and: "and both sides are told, once"
        1 * notificationService.safePush(10L, 'TRADE_DISPUTED', _, _, 7L, _)
        1 * notificationService.safePush(20L, 'TRADE_DISPUTED', _, _, 7L, _)

        where:
        // Every state Steam can report that is not "the buyer took it".
        offerState << ['sent', 'active', 'needs_confirmation', 'declined', 'expired',
                       'canceled', 'in_escrow', null]
    }

    def "it DOES release once Steam has reported the buyer accepted"() {
        given: "an accepted offer whose confirm transition never landed"
        def stale = staleConfirm()
        def svc = sweeper()
        tradeRepository.findPendingConfirmOlderThan(_) >> [stale]
        sweepAttemptRepo.findLatestWithOffer(7L, _ as Pageable) >> offerIn('accepted')

        when:
        svc.sweepPendingConfirm()

        then: "the buyer has the item, so the seller gets paid: price - fee"
        stale.state == 'VERIFIED'
        sellerWallet.balance == new BigDecimal('49.00')
    }

    def "a MANUAL trade the bot never touched still auto-releases — the old rule is intact"() {
        given: "no bot offer for this trade: the seller shipped it themselves"
        def stale = staleConfirm()
        def svc = sweeper()
        tradeRepository.findPendingConfirmOlderThan(_) >> [stale]
        sweepAttemptRepo.findLatestWithOffer(7L, _ as Pageable) >> []

        when:
        svc.sweepPendingConfirm()

        then: "silence still means release — we have no way to check a manual trade," +
              " and a silent buyer must not be able to freeze a seller's money forever"
        stale.state == 'VERIFIED'
        sellerWallet.balance == new BigDecimal('49.00')
    }

    def "with no bot configured the sweep behaves exactly as it always did"() {
        given: "STEAM_BOT_BASE_URL unset — the configuration the operator is launching in"
        def stale = staleConfirm()
        def svc = sweeper('')
        tradeRepository.findPendingConfirmOlderThan(_) >> [stale]

        when:
        svc.sweepPendingConfirm()

        then: "no bot means no bot-driven trades, so the delivery log is not even consulted"
        0 * sweepAttemptRepo.findLatestWithOffer(_, _)

        and: "and the legacy release is untouched"
        stale.state == 'VERIFIED'
        sellerWallet.balance == new BigDecimal('49.00')
    }

    def "an unreadable delivery log holds the money instead of paying out on an unknown"() {
        given:
        def stale = staleConfirm()
        def svc = sweeper()
        tradeRepository.findPendingConfirmOlderThan(_) >> [stale]
        sweepAttemptRepo.findLatestWithOffer(7L, _ as Pageable) >> { throw new RuntimeException('db down') }

        when:
        svc.sweepPendingConfirm()

        then: "fail closed. An absent answer is not a positive one — this codebase has" +
              " paid for that mistake before."
        sellerWallet.balance == BigDecimal.ZERO
        stale.state == 'DISPUTED'
    }

    /**
     * The case above proves a delivery log that THROWS holds the money. This
     * one is the same question asked three lines earlier in the same method,
     * and it used to get the opposite answer.
     *
     * <pre>
     *   if (steamDeliveryAttemptRepository == null) return false   // release
     *   try { rows = ...findLatestWithOffer(...) }
     *   catch (Exception e) { return true }                        // hold
     * </pre>
     *
     * Unreadable-because-it-threw and unreadable-because-it-is-not-wired are
     * the same epistemic state — "I cannot tell whether the buyer got the
     * item" — and they had opposite money outcomes. The method's own docstring
     * already states the intended rule: <i>"Fails CLOSED: if the delivery log
     * cannot be read while the bot is live, we hold rather than pay out on an
     * unknown."</i> The {@code == null} branch contradicted it.
     *
     * The bot being LIVE is what makes this branch reachable and wrong: the
     * enabled-bot check runs first, so by the time the null test is reached we
     * already know bot-driven trades exist on this deployment and that
     * acceptance is something we are supposed to positively observe. The
     * "no bot configured" case is a different question and still releases —
     * asserted separately above, and unchanged.
     */
    def "a delivery log that is not wired holds the money, exactly as one that throws does"() {
        given: "the bot is live, but the delivery-attempt repository is absent"
        def stale = staleConfirm()
        def svc = new TradeService(
            tradeRepository               : tradeRepository,
            walletRepository              : walletRepository,
            transactionRepository         : transactionRepository,
            notificationService           : notificationService,
            auditService                  : auditService,
            banGuard                      : banGuard,
            adminAuthorization            : adminAuthorization,
            textSanitizer                 : textSanitizer,
            autoReleaseDays               : 8L,
            sellerResponseDays            : 3L,
            steamDeliveryAttemptRepository : null,
            steamTradeBotService          : new SteamTradeBotService(baseUrl: 'http://127.0.0.1:4000')
        )
        walletRepository.findById(600L) >> Optional.of(sellerWallet)
        walletRepository.save(_) >> { Wallet w -> w }
        tradeRepository.save(_) >> { Trade t -> t }
        transactionRepository.save(_) >> { Transaction tx -> tx }
        banGuard.isBanned(_) >> false
        tradeRepository.findPendingConfirmOlderThan(_) >> [stale]

        when:
        svc.sweepPendingConfirm()

        then: "the seller is not paid for an item we cannot show the buyer received"
        sellerWallet.balance == BigDecimal.ZERO

        and: "and it is parked for a human, not left silently stuck"
        stale.state == 'DISPUTED'
    }

    // ══ Half 3 — COVER: positive receipt refuses the auto-payout ════════
    //
    // History, kept because it is the point. The working tree of 2026-09-17
    // carried ~100 lines of specs for this buyer-side fix and they were NOT
    // committed, because the gate they assert had never been written and
    // because the tree they sat in did not compile at all (Spock forbids
    // instance-field access from a `where:` block), so all 5,137 specs were
    // unrunnable and that code was never once executed. The finding was
    // recorded as prose here instead. Measured 2026-09-19 against that tree,
    // the case below failed with `1 * autoClaim(7, 'Trade disputed by
    // buyer')`. The gate is now built and these are the specs.
    //
    // THE HOLE: `dispute()` fired `tradeProtectionService.autoClaim` on every
    // BUYER-filed dispute, gated only on `actor == buyer` — existence and
    // idempotency, not evidence. A buyer who ACCEPTED the bot's Steam offer
    // (offerState='accepted' sitting in the delivery log this very class
    // reads) could dispute and be paid the full cover on top of the item. The
    // platform owned the disproving signal and the payout path never asked.
    //
    // THE ASYMMETRY, and the reason this is three states rather than two:
    // only an AFFIRMATIVE acceptance may refuse the payout. No bot, no offer
    // row, an unwired repository or a read that throws must ALL still pay,
    // because there the unproven proposition is "the buyer is lying" and the
    // loser of a wrong guess is a defrauded customer — precisely what the
    // cover is sold against. That is the exact inverse of Half 2 above, where
    // the same unknowns HOLD the seller's money; the question is different,
    // so the safe default is different. Collapsing "not received" into
    // "cannot tell" is this repo's signature defect and is what each of the
    // cannot-tell cases below exists to prevent.

    TradeProtectionService protection            = Mock()
    AuditService           coverAudit            = Mock()
    NotificationService    coverNotifications    = Mock()
    TradeRepository        coverTradeRepository  = Mock()
    SteamUserRepository    coverUserRepository   = Mock()
    SteamDeliveryAttemptRepository coverAttemptRepo = Mock()
    /** Read through a closure stub rather than re-declared per spec: Spock
     *  gives the FIRST matching declaration precedence, so a second
     *  `findByRole('ADMIN') >> [...]` in a spec body would be silently
     *  ignored in favour of the one in the builder below. */
    List<SteamUser> coverAdmins = []

    /** @param wireLog false builds the service with NO delivery-attempt
     *                 repository at all — "cannot tell", not "not received". */
    private TradeService disputer(boolean wireLog = true) {
        def svc = new TradeService(
            tradeRepository       : coverTradeRepository,
            walletRepository      : walletRepository,
            transactionRepository : transactionRepository,
            notificationService   : coverNotifications,
            auditService          : coverAudit,
            banGuard              : banGuard,
            adminAuthorization    : adminAuthorization,
            steamUserRepository   : coverUserRepository,
            textSanitizer         : textSanitizer,
            tradeProtectionService: protection,
            autoReleaseDays       : 8L,
            sellerResponseDays    : 3L,
            steamDeliveryAttemptRepository : wireLog ? coverAttemptRepo : null,
            steamTradeBotService  : new SteamTradeBotService(baseUrl: 'http://127.0.0.1:4000')
        )
        coverTradeRepository.save(_) >> { Trade t -> t }
        banGuard.assertNotBanned(_) >> {}
        coverUserRepository.findByRole('ADMIN') >> { coverAdmins }
        return svc
    }

    private Trade disputable() {
        new Trade(id: 7L, listingId: 100L, itemId: 1L, itemName: 'AK-47 | Redline',
                  buyerUserId: 10L, buyerWalletId: 500L,
                  sellerUserId: 20L, sellerWalletId: 600L,
                  price: new BigDecimal('50.00'), feeAmount: new BigDecimal('1.00'),
                  state: 'PENDING_BUYER_CONFIRM')
    }

    // ── RECEIVED: the one state that refuses ────────────────────────

    @Unroll
    def "a buyer whose offer the log records as '#offerState' gets NO automatic payout"() {
        given: "the delivery poller wrote the acceptance before buyerConfirm ran," +
               " so the trade is still disputable while we already know the buyer has it"
        def t = disputable()
        def svc = disputer()
        coverTradeRepository.findById(7L) >> Optional.of(t)
        coverAttemptRepo.findLatestWithOffer(7L, _ as Pageable) >>
                [new SteamDeliveryAttempt(id: 1L, tradeId: 7L, steamOfferId: '987',
                                          offerState: offerState, phase: 'POLL', success: true)]

        when:
        svc.dispute(10L, 7L, 'never got it')

        then: "the cover is NOT paid out automatically — the buyer has the item"
        0 * protection.autoClaim(_, _)

        and: "and the refusal is written down, naming the evidence, so that" +
             " 'unprotected trade' and 'cover withheld on evidence' are not both silence"
        // Matched on the interpolated field, not a bare word: "Steam delivery
        // log" would satisfy a naive contains('delivered')-style check on its
        // own, and a spec that passes on a substring of the text explaining
        // the rule is not testing the rule.
        1 * coverAudit.log(AuditService.TRADE_PROTECTION_CLAIM_REFUSED, null, 10L, 7L,
                           { String d -> d.contains("offerState='${offerState}'") }) >> null

        where:
        offerState << ['accepted', 'delivered', 'verified']
    }

    // ── NOT RECEIVED: a real offer row saying anything else still pays ──

    @Unroll
    def "a buyer whose offer is only '#offerState' is still paid the cover"() {
        given:
        def t = disputable()
        def svc = disputer()
        coverTradeRepository.findById(7L) >> Optional.of(t)
        coverAttemptRepo.findLatestWithOffer(7L, _ as Pageable) >>
                [new SteamDeliveryAttempt(id: 1L, tradeId: 7L, steamOfferId: '987',
                                          offerState: offerState, phase: 'POLL', success: true)]

        when:
        svc.dispute(10L, 7L, 'never got it')

        then: "this buyer really did not receive the item — the cover is what they bought"
        1 * protection.autoClaim(7L, 'Trade disputed by buyer')

        and:
        0 * coverAudit.log(AuditService.TRADE_PROTECTION_CLAIM_REFUSED, _, _, _, _)

        where:
        offerState << ['sent', 'active', 'needs_confirmation', 'declined',
                       'expired', 'canceled', 'in_escrow', null]
    }

    // ── CANNOT TELL: every unknown keeps paying ─────────────────────
    //
    // Four different ways of not knowing. Each is a separate case because the
    // cheap implementation — negating the sweep's `botOfferAwaitingAcceptance`
    // — returns "received" for all four and would strand every one of these
    // claimants.

    def "CANNOT TELL: no offer row at all (a manual trade) still pays"() {
        given: "the seller shipped it themselves; the bot never sent an offer"
        def t = disputable()
        def svc = disputer()
        coverTradeRepository.findById(7L) >> Optional.of(t)
        coverAttemptRepo.findLatestWithOffer(7L, _ as Pageable) >> []

        when:
        svc.dispute(10L, 7L, 'never got it')

        then: "an absent delivery log reads as CANNOT TELL, never as RECEIVED"
        1 * protection.autoClaim(7L, 'Trade disputed by buyer')
        0 * coverAudit.log(AuditService.TRADE_PROTECTION_CLAIM_REFUSED, _, _, _, _)
    }

    def "CANNOT TELL: the delivery log is not wired at all and it still pays"() {
        given: "the bot is live but no attempt repository is injected"
        def t = disputable()
        def svc = disputer(false)
        coverTradeRepository.findById(7L) >> Optional.of(t)

        when:
        svc.dispute(10L, 7L, 'never got it')

        then: "the same epistemic state as the throw below, and the same answer." +
              " Half 2 makes this HOLD the seller's money; here it must PAY the buyer," +
              " because the question is the other way round."
        1 * protection.autoClaim(7L, 'Trade disputed by buyer')
        0 * coverAudit.log(AuditService.TRADE_PROTECTION_CLAIM_REFUSED, _, _, _, _)
    }

    def "CANNOT TELL: the delivery log throws and it still pays"() {
        given:
        def t = disputable()
        def svc = disputer()
        coverTradeRepository.findById(7L) >> Optional.of(t)
        coverAttemptRepo.findLatestWithOffer(7L, _ as Pageable) >> { throw new RuntimeException('db down') }

        when:
        svc.dispute(10L, 7L, 'never got it')

        then: "fail OPEN. Refusing a cover on a database blip strands a defrauded" +
              " buyer with nothing — strictly worse than the hole being closed."
        1 * protection.autoClaim(7L, 'Trade disputed by buyer')
        0 * coverAudit.log(AuditService.TRADE_PROTECTION_CLAIM_REFUSED, _, _, _, _)
    }

    def "CANNOT TELL: a row with no offer state at all still pays"() {
        given: "a delivery attempt exists but Steam never told us what became of it"
        def t = disputable()
        def svc = disputer()
        coverTradeRepository.findById(7L) >> Optional.of(t)
        coverAttemptRepo.findLatestWithOffer(7L, _ as Pageable) >>
                [new SteamDeliveryAttempt(id: 1L, tradeId: 7L, steamOfferId: '987',
                                          offerState: null, phase: 'POLL', success: true)]

        when:
        svc.dispute(10L, 7L, 'never got it')

        then:
        1 * protection.autoClaim(7L, 'Trade disputed by buyer')
    }

    // ── The gate fires HERE and nowhere else ────────────────────────

    def "a SELLER-filed dispute still never auto-claims, accepted offer or not"() {
        given: "the pre-existing actor gate, unchanged by this fix"
        def t = disputable()
        def svc = disputer()
        coverTradeRepository.findById(7L) >> Optional.of(t)

        when:
        svc.dispute(20L, 7L, 'buyer took it and ghosted')

        then:
        0 * protection.autoClaim(_, _)

        and: "and it is not recorded as an evidence-based refusal either — the" +
             " seller-side gate is about WHO filed, and conflating the two rows" +
             " would make the audit log lie about why nobody was paid"
        0 * coverAudit.log(AuditService.TRADE_PROTECTION_CLAIM_REFUSED, _, _, _, _)
        0 * coverAttemptRepo.findLatestWithOffer(_, _)
    }

    // ── Refused is not denied: it must reach a human ────────────────

    def "a refused claim still surfaces for manual resolution rather than vanishing"() {
        given:
        def t = disputable()
        def svc = disputer()
        coverTradeRepository.findById(7L) >> Optional.of(t)
        coverAttemptRepo.findLatestWithOffer(7L, _ as Pageable) >>
                [new SteamDeliveryAttempt(id: 1L, tradeId: 7L, steamOfferId: '987',
                                          offerState: 'accepted', phase: 'POLL', success: true)]
        coverAdmins = [new SteamUser(id: 99L)]

        when:
        svc.dispute(10L, 7L, 'never got it')

        then: "the trade lands in the state staff adjudicate, with the money still" +
              " frozen in escrow and the cover still ACTIVE — AdminService" +
              " forceReleaseTrade / forceCancelTrade both accept DISPUTED, and" +
              " forceCancelTrade refunds this buyer in full if staff rule for them"
        t.state == 'DISPUTED'
        0 * protection.autoClaim(_, _)
        0 * protection.expire(_)

        and: "the counterparty is told a dispute is live against them"
        1 * coverNotifications.push(20L, 'TRADE_DISPUTED', _, _, 7L, _)

        and: "and every admin is pinged WITH the evidence, because the default staff" +
             " remedy for a buyer dispute is force-cancel-and-refund, which on this" +
             " trade would hand over the item and the money — the very hole just" +
             " closed, merely routed through a person"
        1 * coverNotifications.push(99L, 'TRADE_DISPUTED', _,
                { String body -> body.contains("'accepted'") && body.contains('refused') },
                7L, '/admin?tab=trades&filter=DISPUTED')
    }
}
