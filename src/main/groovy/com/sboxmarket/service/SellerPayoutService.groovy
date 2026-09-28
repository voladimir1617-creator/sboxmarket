package com.sboxmarket.service

import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

/**
 * The seller payouts card on My Stall: one read that answers "how much can I
 * cash out, how much is still on its way, what have I already been paid, and
 * when does the next money land".
 *
 * Read-only. It adds no money path: the card's "Request payout" button opens
 * the existing Wallet → Withdraw form, which runs the same
 * {@link StripeService#requestWithdrawal} (2FA, daily cap, dispute hold,
 * Stripe Connect gate, test mode) as before.
 *
 * <ul>
 *   <li><b>available</b> — the wallet balance, i.e. what /withdraw accepts.</li>
 *   <li><b>pending</b> — sales still in escrow (seller has not sent, or the
 *       buyer has not confirmed), net of the platform fee.</li>
 *   <li><b>onHold</b> — sales parked as DISPUTED; staff decide those.</li>
 *   <li><b>inFlight</b> — withdrawals requested but not yet completed.</li>
 *   <li><b>paidOut</b> — every COMPLETED withdrawal, lifetime.</li>
 *   <li><b>nextRelease</b> — the latest date the oldest buyer-confirm sale
 *       auto-releases into the balance (the buyer can confirm sooner).</li>
 *   <li><b>arrivalIfRequestedNow</b> — when a payout requested now should
 *       reach the seller's bank: Stripe's usual two business days.</li>
 * </ul>
 */
@Service
class SellerPayoutService {

    static final int HISTORY_LIMIT = 10

    /** Stripe's standard payout lands in 1–2 business days; quote the later. */
    static final int PAYOUT_BUSINESS_DAYS = 2

    private static final long DAY_MS = 24L * 60L * 60L * 1000L

    @Autowired TradeRepository tradeRepository
    @Autowired TransactionRepository transactionRepository
    @Autowired StripeService stripeService

    /** Same knob {@code TradeService.sweepPendingConfirm} reads. */
    @Value('${trade.auto-release-days:8}') long autoReleaseDays = 8L

    Map summary(Long sellerUserId, Wallet wallet, long now = System.currentTimeMillis()) {
        def zero = BigDecimal.ZERO
        def pending = [amount: zero, count: 0L]
        def onHold  = [amount: zero, count: 0L]
        (tradeRepository.summarizeSellerEscrow(sellerUserId) ?: []).each { Object[] row ->
            def bucket = (row[0] as String) == 'DISPUTED' ? onHold : pending
            bucket.amount = (bucket.amount as BigDecimal) + ((row[1] as BigDecimal) ?: zero)
            bucket.count  = (bucket.count as Long) + ((row[2] as Number)?.longValue() ?: 0L)
        }

        Long oldestConfirm = tradeRepository.earliestPendingConfirmForSeller(sellerUserId)
        Long nextReleaseAt = oldestConfirm != null ? oldestConfirm + autoReleaseDays * DAY_MS : null

        boolean live = stripeService.isLive()
        if (wallet == null) {
            return [
                available:             zero,
                pending:               pending,
                onHold:                onHold,
                inFlight:              [amount: zero, count: 0L],
                paidOut:               [amount: zero, count: 0L],
                nextReleaseAt:         nextReleaseAt,
                autoReleaseDays:       autoReleaseDays,
                arrivalIfRequestedNow: addBusinessDays(now, PAYOUT_BUSINESS_DAYS),
                live:                  live,
                cashoutReady:          !live,
                frozen:                false,
                history:               []
            ]
        }

        def history = transactionRepository.findWithdrawalsByWallet(
                wallet.id, PageRequest.of(0, HISTORY_LIMIT)) ?: []
        def inFlightRows = (transactionRepository.findPendingByWallet(wallet.id) ?: [])
                .findAll { it.type in ['WITHDRAW', 'WITHDRAWAL'] }
        def inFlightAmt = inFlightRows.inject(zero) { s, t -> s + (t.amount ?: zero) }
        def paidAmt = ['WITHDRAW', 'WITHDRAWAL'].inject(zero) { s, type ->
            s + (transactionRepository.sumByWalletAndType(wallet.id, type, true) ?: zero)
        }
        long paidCt = ['WITHDRAW', 'WITHDRAWAL'].sum { type ->
            transactionRepository.countCompletedByWalletAndType(wallet.id, type)
        } as long

        [
            available:             wallet.balance ?: zero,
            pending:               pending,
            onHold:                onHold,
            inFlight:              [amount: inFlightAmt, count: (long) inFlightRows.size()],
            paidOut:               [amount: paidAmt, count: paidCt],
            nextReleaseAt:         nextReleaseAt,
            autoReleaseDays:       autoReleaseDays,
            arrivalIfRequestedNow: addBusinessDays(now, PAYOUT_BUSINESS_DAYS),
            live:                  live,
            // Mirrors requestWithdrawal's gate: only a live deployment needs a
            // finished Stripe Connect account; test mode records the payout.
            cashoutReady:          !live || Boolean.TRUE.equals(wallet.payoutsEnabled),
            frozen:                Boolean.TRUE.equals(wallet.frozen),
            history:               history.collect { t ->
                [
                    id:        t.id,
                    amount:    t.amount,
                    feeAmount: t.feeAmount,
                    status:    t.status,
                    createdAt: t.createdAt,
                    updatedAt: t.updatedAt,
                    // Estimated bank arrival, only for payouts still moving.
                    expectedAt: t.status == 'PENDING' || t.status == 'COMPLETED'
                        ? addBusinessDays(t.createdAt ?: now, PAYOUT_BUSINESS_DAYS) : null
                ]
            }
        ]
    }

    /** Skip Saturdays and Sundays (UTC). Bank holidays are not modelled. */
    static long addBusinessDays(long fromMs, int days) {
        def d = Instant.ofEpochMilli(fromMs).atZone(ZoneOffset.UTC)
        int added = 0
        while (added < days) {
            d = d.plusDays(1)
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) added++
        }
        d.toInstant().toEpochMilli()
    }
}
