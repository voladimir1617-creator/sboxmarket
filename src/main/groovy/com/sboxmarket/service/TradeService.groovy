package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Trade
import com.sboxmarket.model.Transaction
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.security.AdminAuthorization
import com.sboxmarket.service.security.BanGuard
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Escrow state machine for Steam-style trades. Wraps the four legal
 * transitions plus the dispute / cancel exits.
 *
 *   open(listing, buyer, seller)   → PENDING_SELLER_ACCEPT
 *   sellerAccept(trade, seller)    → PENDING_SELLER_SEND
 *   sellerMarkSent(trade, seller)  → PENDING_BUYER_CONFIRM
 *   buyerConfirm(trade, buyer)     → VERIFIED   (credits seller wallet)
 *   dispute(trade, actor)          → DISPUTED   (CSR/Admin routes)
 *   cancel(trade, actor)           → CANCELLED  (refunds buyer wallet)
 *
 * Each transition is a single @Transactional method so a crash mid-way
 * leaves the database consistent. `requireParticipant` checks that the
 * caller is the right side of the trade for the transition they're asking
 * for — staff routes use AdminService/CsrService to bypass when needed.
 */
@Service
@Slf4j
class TradeService {

    /** Platform fee taken from the seller on a VERIFIED release. 2% by default. */
    private static final BigDecimal FEE_RATE = new BigDecimal('0.02')

    /** Auto-release window — trades that have been sitting in PENDING_BUYER_CONFIRM
     *  for longer than this are released to the seller automatically by a
     *  scheduled sweeper. Mirrors CSFloat's 8-day trade-hold window. Configurable
     *  via `trade.auto-release-days` so ops can shorten it during incidents. */
    @Value('${trade.auto-release-days:8}') long autoReleaseDays

    @Autowired TradeRepository tradeRepository
    @Autowired WalletRepository walletRepository
    @Autowired TransactionRepository transactionRepository
    @Autowired(required = false) NotificationService notificationService
    @Autowired(required = false) AuditService auditService
    @Autowired TextSanitizer textSanitizer
    // Narrow security dependencies — we only need the ban check and admin
    // role check, not the full AdminService graph. This is what breaks the
    // old TradeService ↔ AdminService cycle that forced @Lazy injection.
    @Autowired BanGuard banGuard
    @Autowired AdminAuthorization adminAuthorization

    // ── Queries ──────────────────────────────────────────────────────

    Trade get(Long id) {
        tradeRepository.findById(id).orElseThrow { new NotFoundException("Trade", id) }
    }

    List<Trade> listForUser(Long userId) {
        tradeRepository.findByParticipant(userId)
    }

    Trade findForListing(Long listingId) {
        tradeRepository.findByListingId(listingId)
    }

    // ── Open ─────────────────────────────────────────────────────────

    @Transactional
    Trade open(Long listingId, Long itemId, String itemName,
               Long buyerUserId, Long buyerWalletId,
               Long sellerUserId, Long sellerWalletId,
               BigDecimal price) {
        banGuard.assertNotBanned(buyerUserId)
        require(price != null && price > BigDecimal.ZERO, "Trade price must be positive")
        require(price <= new BigDecimal("100000"), "Trade price exceeds maximum (\$100,000)")
        def trade = new Trade(
            listingId:      listingId,
            itemId:         itemId,
            itemName:       itemName,
            buyerUserId:    buyerUserId,
            buyerWalletId:  buyerWalletId,
            sellerUserId:   sellerUserId,
            sellerWalletId: sellerWalletId,
            price:          price,
            feeAmount:      (price * FEE_RATE).setScale(2, BigDecimal.ROUND_HALF_UP),
            state:          sellerUserId == null ? 'PENDING_BUYER_CONFIRM' : 'PENDING_SELLER_ACCEPT'
        )
        tradeRepository.save(trade)

        notificationService?.push(buyerUserId, 'TRADE_OPENED',
            "Escrow opened for ${itemName}",
            "Waiting for seller to accept the trade", trade.id)
        if (sellerUserId != null) {
            notificationService?.push(sellerUserId, 'TRADE_REQUESTED',
                "New sale: ${itemName}",
                "Accept the trade and send the Steam offer to release funds.", trade.id)
        }
        trade
    }

    // ── Transitions ──────────────────────────────────────────────────

    @Transactional
    Trade sellerAccept(Long sellerUserId, Long tradeId) {
        banGuard.assertNotBanned(sellerUserId)
        def t = get(tradeId)
        requireParticipant(t, sellerUserId, 'seller')
        require(t.state == 'PENDING_SELLER_ACCEPT', "Trade cannot be accepted in state ${t.state}")
        transitionTo(t, 'PENDING_SELLER_SEND')
        notificationService?.push(t.buyerUserId, 'TRADE_ACCEPTED',
            "Seller accepted ${t.itemName}",
            "The seller now has to send the Steam trade offer.", t.id)
        t
    }

    @Transactional
    Trade sellerMarkSent(Long sellerUserId, Long tradeId) {
        banGuard.assertNotBanned(sellerUserId)
        def t = get(tradeId)
        requireParticipant(t, sellerUserId, 'seller')
        require(t.state == 'PENDING_SELLER_SEND', "Trade cannot be marked sent in state ${t.state}")
        transitionTo(t, 'PENDING_BUYER_CONFIRM')
        notificationService?.push(t.buyerUserId, 'TRADE_SENT',
            "Steam trade offer sent · ${t.itemName}",
            "Confirm the trade on Steam and then click Confirm Receipt.", t.id)
        t
    }

    @Transactional
    Trade buyerConfirm(Long buyerUserId, Long tradeId) {
        banGuard.assertNotBanned(buyerUserId)
        def t = get(tradeId)
        requireParticipant(t, buyerUserId, 'buyer')
        require(t.state == 'PENDING_BUYER_CONFIRM', "Trade cannot be confirmed in state ${t.state}")
        release(t)
        t
    }

    /**
     * Transition → VERIFIED. Credits the seller wallet with (price - fee) and
     * records matching SALE transactions on both sides. Called by
     * `buyerConfirm` and by the auto-release sweep after the trade-hold window.
     */
    @Transactional
    protected void release(Trade t) {
        transitionTo(t, 'VERIFIED')
        t.settledAt = System.currentTimeMillis()
        tradeRepository.save(t)

        if (t.sellerWalletId == null) {
            log.warn("Trade #{} VERIFIED but sellerWalletId is null — seller credit skipped. " +
                     "Manual payout required for \${}", t.id, t.price - t.feeAmount)
        }
        if (t.sellerWalletId != null) {
            def sellerWallet = walletRepository.findById(t.sellerWalletId).orElse(null)
            if (sellerWallet == null) {
                log.warn("Trade #{} VERIFIED but seller wallet {} not found — credit skipped. " +
                         "Manual payout required for \${}", t.id, t.sellerWalletId, t.price - t.feeAmount)
            }
            if (sellerWallet != null) {
                def credit = (t.price - t.feeAmount)
                sellerWallet.balance = sellerWallet.balance + credit
                walletRepository.save(sellerWallet)
                transactionRepository.save(new Transaction(
                    walletId:        sellerWallet.id,
                    type:            'SALE',
                    status:          'COMPLETED',
                    amount:          credit,
                    currency:        sellerWallet.currency,
                    stripeReference: 'trade',
                    description:     "Sold ${t.itemName} (-\$${t.feeAmount} fee)",
                    listingId:       t.listingId
                ))
            }
        }
        notificationService?.push(t.buyerUserId, 'TRADE_VERIFIED',
            "Trade verified · ${t.itemName}", null, t.id)
        if (t.sellerUserId != null) {
            notificationService?.push(t.sellerUserId, 'TRADE_VERIFIED',
                "Funds released · ${t.itemName}",
                "\$${(t.price - t.feeAmount)} credited to your wallet.", t.id)
        }
        auditService?.log('TRADE_VERIFIED', t.buyerUserId, t.sellerUserId, t.id,
            "Verified trade #${t.id} for \$${t.price}")
    }

    @Transactional
    Trade dispute(Long actorUserId, Long tradeId, String reason) {
        banGuard.assertNotBanned(actorUserId)
        def t = get(tradeId)
        if (actorUserId != t.buyerUserId && actorUserId != t.sellerUserId) {
            throw new ForbiddenException("Only participants can dispute a trade")
        }
        require(t.state in ['PENDING_SELLER_ACCEPT','PENDING_SELLER_SEND','PENDING_BUYER_CONFIRM'],
            "Trade cannot be disputed in state ${t.state}")
        // HTML-strip the user-supplied reason before it lands in the DB
        // and later shows up in the admin dispute queue. The old
        // `(reason ?: '').take(500)` only truncated — an attacker could
        // slip `<img src=x onerror=...>` into a trade note that an admin
        // would render on the dispute review page.
        t.note = textSanitizer.medium(reason)
        transitionTo(t, 'DISPUTED')
        // Log the counterparty as the audit subject so admins can search by either side
        def disputeSubject = (actorUserId == t.buyerUserId) ? t.sellerUserId : t.buyerUserId
        auditService?.log('TRADE_DISPUTED', actorUserId, disputeSubject, t.id, "Disputed: ${t.note ?: '(none)'}")
        t
    }

    @Transactional
    Trade cancel(Long actorUserId, Long tradeId, String reason) {
        def t = get(tradeId)
        // Either participant OR an admin/CSR may cancel.
        if (actorUserId != t.buyerUserId && actorUserId != t.sellerUserId) {
            adminAuthorization.requireAdmin(actorUserId)
        } else {
            // Participant path — banned users can't cancel trades themselves.
            // Admins calling cancel bypass this (they already passed the admin check).
            banGuard.assertNotBanned(actorUserId)
        }
        require(t.state != 'VERIFIED' && t.state != 'CANCELLED',
            "Trade cannot be cancelled in state ${t.state}")

        refundBuyer(t)
        // Sanitize the user-supplied reason before it lands in the trade
        // note AND the notification body. React auto-escapes, but defense
        // in depth stops a crafted payload from riding through every UI
        // surface that might later render a cancel reason.
        def cleanReason = textSanitizer.medium(reason)
        t.note = cleanReason
        t.settledAt = System.currentTimeMillis()
        transitionTo(t, 'CANCELLED')
        notificationService?.push(t.buyerUserId, 'TRADE_CANCELLED',
            "Trade cancelled · refund issued", cleanReason, t.id)
        if (t.sellerUserId != null) {
            notificationService?.push(t.sellerUserId, 'TRADE_CANCELLED',
                "Trade cancelled", cleanReason, t.id)
        }
        // Log the counterparty as the audit subject
        def cancelSubject = (actorUserId == t.buyerUserId) ? t.sellerUserId :
                            (actorUserId == t.sellerUserId) ? t.buyerUserId : t.buyerUserId
        auditService?.log('TRADE_CANCELLED', actorUserId, cancelSubject, t.id, "Cancelled: ${cleanReason ?: '(none)'}")
        t
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void transitionTo(Trade t, String nextState) {
        t.state = nextState
        t.updatedAt = System.currentTimeMillis()
        tradeRepository.save(t)
    }

    private void requireParticipant(Trade t, Long userId, String role) {
        if (role == 'seller' && t.sellerUserId != userId) {
            throw new ForbiddenException("You are not the seller on this trade")
        }
        if (role == 'buyer'  && t.buyerUserId  != userId) {
            throw new ForbiddenException("You are not the buyer on this trade")
        }
    }

    private static void require(boolean cond, String msg) {
        if (!cond) throw new BadRequestException("INVALID_STATE", msg)
    }

    /** Refund the buyer wallet — shared by cancel() and the sweeper's banned-seller path. */
    private void refundBuyer(Trade t) {
        if (t.buyerWalletId == null) return
        def buyerWallet = walletRepository.findById(t.buyerWalletId).orElse(null)
        if (buyerWallet == null) return
        buyerWallet.balance = buyerWallet.balance + t.price
        walletRepository.save(buyerWallet)
        transactionRepository.save(new Transaction(
            walletId:        buyerWallet.id,
            type:            'REFUND',
            status:          'COMPLETED',
            amount:          t.price,
            currency:        buyerWallet.currency,
            stripeReference: 'trade_cancel',
            description:     "Trade cancelled — refunded ${t.itemName}",
            listingId:       t.listingId
        ))
    }

    // ── Scheduled sweeper ────────────────────────────────────────────

    /**
     * Runs every 15 minutes. Any trade still sitting in PENDING_BUYER_CONFIRM
     * after `autoReleaseDays` gets auto-released to the seller. Buyers have
     * that window to click Confirm Receipt or open a dispute; after that we
     * treat silence as delivery.
     *
     * Safe to run concurrently — each trade is released inside its own
     * transaction, so one slow release doesn't delay the rest. Disputed
     * trades are skipped here and routed to staff via the admin panel.
     */
    @Scheduled(fixedDelay = 15L * 60L * 1000L, initialDelay = 5L * 60L * 1000L)
    void sweepPendingConfirm() {
        def cutoff = System.currentTimeMillis() - (autoReleaseDays * 24L * 60L * 60L * 1000L)
        // Narrow the candidate set in the query itself so the sweeper
        // doesn't pull every PENDING_BUYER_CONFIRM trade into memory on
        // each 15-minute tick. `findPendingConfirmOlderThan` uses the
        // existing idx_trades_state index + a cheap updatedAt filter.
        def candidates = tradeRepository.findPendingConfirmOlderThan(cutoff)
        if (candidates.isEmpty()) return
        log.info("Trade sweeper: ${candidates.size()} stale trades found, auto-releasing")
        candidates.each { trade ->
            try {
                // If the seller was banned mid-trade, refund the buyer instead
                // of releasing funds to a sanctioned account.
                if (trade.sellerUserId != null && banGuard.isBanned(trade.sellerUserId)) {
                    log.info("Trade #{} seller is banned — auto-cancelling with buyer refund instead of release", trade.id)
                    refundBuyer(trade)
                    trade.note = "Automatically cancelled — seller account banned"
                    trade.settledAt = System.currentTimeMillis()
                    transitionTo(trade, 'CANCELLED')
                    notificationService?.push(trade.buyerUserId, 'TRADE_CANCELLED',
                        "Trade cancelled · refund issued", trade.note, trade.id)
                    auditService?.log('TRADE_AUTO_CANCELLED', null, trade.sellerUserId, trade.id,
                        "Seller banned — auto-cancelled after ${autoReleaseDays}d window")
                } else {
                    release(trade)
                    auditService?.log('TRADE_AUTO_RELEASED', null, null, trade.id,
                        "Auto-released after ${autoReleaseDays}d no-confirm window")
                }
            } catch (Exception e) {
                log.warn("Trade sweeper failed on ${trade.id}: ${e.message}")
            }
        }
    }
}
