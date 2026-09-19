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
 * ── The two halves ────────────────────────────────────────────────────────
 * CUSTODY: the item is only DELIVERED once Steam says the buyer accepted, so
 * until then the return path can still recover it.
 * MONEY:   the auto-release sweep asks whether the buyer accepted before
 * paying, and holds when the answer is no.
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

    // ── NOT LANDED: the buyer-side acceptance gate ─────────────────
    //
    // The working tree of 2026-09-17 also carried ~100 lines of specs for a
    // SECOND, buyer-side fix: `dispute()` fires
    // `tradeProtectionService.autoClaim` on every BUYER-filed dispute, gated
    // only on `actor == buyer` — existence and idempotency, not evidence. So a
    // buyer who ACCEPTED the bot's Steam offer (offerState='accepted' sitting
    // in the delivery log this very class reads) can dispute and be paid the
    // full protection cover anyway. The platform owns the disproving signal
    // and the payout path never asks for it.
    //
    // Those specs were NOT committed, because the gate they assert was never
    // written: measured 2026-09-19 against this tree, the case
    // "a buyer who ACCEPTED the Steam offer does not get an automatic
    // protection payout" fails with `1 * autoClaim(7, 'Trade disputed by
    // buyer')`. A spec for unbuilt work is not a guard; committing it green
    // would have required building the gate, and changing who gets paid on a
    // disputed trade is a money decision that deserves its own deliberate
    // change rather than riding along with an escrow fix.
    //
    // Recorded here so the finding is not lost with the working tree. The
    // asymmetry the design intended: only an AFFIRMATIVE acceptance may block
    // the payout — no bot, no offer row, an unreadable log or an exception
    // must all still pay, because there the unproven claim is "the buyer is
    // lying" and the loser of a wrong guess is a defrauded customer.
}
