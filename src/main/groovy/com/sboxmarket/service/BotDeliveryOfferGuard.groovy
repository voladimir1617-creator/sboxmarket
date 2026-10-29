package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.SteamDeliveryAttempt
import com.sboxmarket.model.Trade
import com.sboxmarket.repository.SteamDeliveryAttemptRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * Money must not go back to a buyer while our bot's Steam delivery offer is
 * still open for them to accept, or after they accepted it.
 *
 * The bot's offers stay acceptable for 14 days, and a refund (trade cancel,
 * staff force-cancel, the ban cascade, a Trade Protection payout on a buyer
 * dispute) used to run with the offer still live: the buyer kept the money AND
 * could take the item. This bean answers, for one trade, "is it safe to refund
 * now?", cancelling the live offer in Steam first:
 *
 *   NO_LIVE_OFFER  no bot offer, or the last one is declined / expired /
 *                  canceled: refund as before.
 *   CANCELLED      the offer was live and Steam has now cancelled it: refund.
 *   ACCEPTED       the buyer took the item (or Steam is holding it on its way
 *                  to them): never refund; it is a delivered trade.
 *   FAILED         the offer may still be live and could not be cancelled
 *                  (bot down, Steam error, log unreadable): do not refund yet.
 *
 * What it learns from Steam is written to the delivery log in its own
 * transaction, so a refused refund still leaves the evidence behind for the
 * poller and staff. Separate from {@link SteamDeliveryService} because that
 * service depends on TradeService, which depends on this.
 */
@Service
@Slf4j
class BotDeliveryOfferGuard {

    enum Outcome { NO_LIVE_OFFER, CANCELLED, ACCEPTED, FAILED }

    static class Result {
        Outcome outcome
        String offerId
        String state
        String detail

        /** True when a refund cannot hand the buyer the item as well. */
        boolean isSafeToRefund() { outcome == Outcome.NO_LIVE_OFFER || outcome == Outcome.CANCELLED }

        @Override
        String toString() { "${outcome}(offer=${offerId}, state=${state}${detail ? ', ' + detail : ''})" }
    }

    @Autowired(required = false) SteamTradeBotService steamTradeBotService
    @Autowired(required = false) SteamDeliveryAttemptRepository attemptRepository
    @Autowired(required = false) SteamEscrowService steamEscrowService
    @Autowired(required = false) PlatformTransactionManager transactionManager

    /** States in which Steam has the item moving to the buyer. */
    static final Set<String> RECEIVED_STATES = (TradeService.ACCEPTED_OFFER_STATES + ['in_escrow']) as Set<String>

    /**
     * Make sure the bot's delivery offer for {@code t} can no longer be
     * accepted, cancelling it in Steam when it is still live. Never throws.
     */
    Result cancelLiveOffer(Trade t) {
        SteamDeliveryAttempt row
        try {
            row = latestOfferRow(t)
        } catch (Exception e) {
            log.warn("Trade #{}: delivery log unreadable ({}); cannot tell whether a bot offer is live", t?.id, e.message)
            return new Result(outcome: Outcome.FAILED, detail: 'delivery log unreadable')
        }
        if (row == null) return new Result(outcome: Outcome.NO_LIVE_OFFER)
        String offerId = row.steamOfferId
        String state = row.offerState?.toLowerCase()
        if (state in SteamBotResult.TERMINAL_FAILURE_STATES) {
            return new Result(outcome: Outcome.NO_LIVE_OFFER, offerId: offerId, state: state)
        }
        if (state in RECEIVED_STATES) {
            return new Result(outcome: Outcome.ACCEPTED, offerId: offerId, state: state)
        }
        if (steamTradeBotService == null || !steamTradeBotService.enabled) {
            return new Result(outcome: Outcome.FAILED, offerId: offerId, state: state,
                    detail: 'the Steam bot is not configured, so the offer cannot be cancelled')
        }
        SteamBotResult c = steamTradeBotService.cancelOffer(offerId)
        if (c?.offerCancelled) {
            String now = c.status ?: 'canceled'
            // A POLL row in a terminal state: the poller then treats the
            // offer as reported and never re-notifies it.
            record(t, offerId, now, true, 'cancelled by the backend before a refund')
            log.info("Trade #{}: bot delivery offer {} cancelled in Steam ({}) before refunding", t.id, offerId, now)
            return new Result(outcome: Outcome.CANCELLED, offerId: offerId, state: now)
        }
        if (c?.offerAlreadyAccepted) {
            String now = c.status ?: 'accepted'
            record(t, offerId, now, true, 'cancel refused by Steam: the buyer already accepted')
            if (now == 'accepted') {
                // The same custody step the delivery poller takes on accept,
                // so the return-to-seller sweep never tries to claw it back.
                inNewTx { steamEscrowService?.markDelivered(t.listingId) }
            }
            log.warn("Trade #{}: bot delivery offer {} was already {} — the buyer has the item; no refund", t.id, offerId, now)
            return new Result(outcome: Outcome.ACCEPTED, offerId: offerId, state: now)
        }
        String why = "${c?.errorCode}: ${c?.message}".toString()
        // No offer id on this row, so it never becomes "the latest offer".
        record(t, null, c?.errorCode ?: 'CANCEL_FAILED', false, "cancel of offer ${offerId} failed: ${why}".toString())
        log.warn("Trade #{}: could not cancel bot delivery offer {} ({}); refund withheld", t.id, offerId, why)
        return new Result(outcome: Outcome.FAILED, offerId: offerId, state: state, detail: why)
    }

    /**
     * {@link #cancelLiveOffer}, then throw unless a refund is safe. For the
     * cancel path, where an exception aborts the refund and tells the caller.
     */
    Result requireNoLiveOfferBeforeRefund(Trade t) {
        Result r = cancelLiveOffer(t)
        if (r.outcome == Outcome.ACCEPTED) {
            throw new BadRequestException('OFFER_ALREADY_ACCEPTED',
                "The buyer already accepted our bot's Steam trade offer for trade #${t.id} (${r.state}), so they " +
                "have the item. The trade was not cancelled and nothing was refunded. If the item is wrong, " +
                "resolve it as a delivered trade.")
        }
        if (r.outcome == Outcome.FAILED) {
            throw new BadRequestException('OFFER_CANCEL_FAILED',
                "Our bot's Steam trade offer for trade #${t.id} may still be open and could not be cancelled " +
                "(${r.detail ?: 'unknown error'}). The trade was not cancelled and nothing was refunded; try again shortly.")
        }
        r
    }

    /**
     * Why staff must not force-release this trade, or null when they may.
     * The bot sent an offer and Steam never reported the buyer taking it, so
     * paying the seller would leave the buyer with nothing and the item in our
     * bot. Reads Steam live first, so an accept the poller has not recorded
     * yet still counts. Null with no bot offer at all (a manual trade).
     */
    String undeliveredReason(Trade t) {
        SteamDeliveryAttempt row
        try {
            row = latestOfferRow(t)
        } catch (Exception e) {
            return 'the delivery log could not be read'
        }
        if (row == null) return null
        String state = row.offerState?.toLowerCase()
        if (state in RECEIVED_STATES) return null
        if (steamTradeBotService != null && steamTradeBotService.enabled) {
            SteamBotResult live = steamTradeBotService.getOfferStatus(row.steamOfferId)
            if (live?.ok && live.status != null) {
                String now = live.status.toLowerCase()
                if (now != state) record(t, row.steamOfferId, now, true, 'read before a force-release')
                if (now == 'accepted') inNewTx { steamEscrowService?.markDelivered(t.listingId) }
                if (now in RECEIVED_STATES) return null
                state = now
            }
        }
        return "the buyer never accepted our bot's Steam trade offer ${row.steamOfferId} (${state ?: 'unknown'})"
    }

    private SteamDeliveryAttempt latestOfferRow(Trade t) {
        if (t?.id == null || attemptRepository == null) return null
        def rows = attemptRepository.findLatestWithOffer(t.id, PageRequest.of(0, 1))
        return (rows == null || rows.isEmpty()) ? null : rows.get(0)
    }

    private void record(Trade t, String offerId, String state, boolean ok, String note) {
        if (attemptRepository == null) return
        try {
            inNewTx {
                attemptRepository.save(new SteamDeliveryAttempt(
                        tradeId: t.id, steamOfferId: offerId, offerState: state, phase: 'POLL',
                        success: ok, errorMessage: note != null && note.length() > 500 ? note.substring(0, 500) : note,
                        createdAt: System.currentTimeMillis()))
            }
        } catch (Exception e) {
            log.warn("Trade #{}: could not record bot offer state {}: {}", t?.id, state, e.message)
        }
    }

    /** Commit on its own, so a refund the caller then refuses (and rolls
     *  back) does not take what Steam told us with it. */
    private void inNewTx(Closure work) {
        if (transactionManager == null) { work.call(); return }
        def tt = new TransactionTemplate(transactionManager)
        tt.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        tt.executeWithoutResult { work.call() }
    }
}
