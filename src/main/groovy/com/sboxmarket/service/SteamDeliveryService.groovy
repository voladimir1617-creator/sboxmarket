package com.sboxmarket.service

import com.sboxmarket.model.SteamDeliveryAttempt
import com.sboxmarket.model.Trade
import com.sboxmarket.repository.SteamDeliveryAttemptRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Automated Steam trade-offer delivery orchestrator (bot-escrow model).
 *
 * Replaces the honor-system "Mark Sent" button: instead of trusting a seller's
 * click, the bot sidecar actually SENDS the Steam trade offer to the buyer and
 * the platform VERIFIES (via the Steam Web API, surfaced through the sidecar)
 * that the buyer ACCEPTED it before crediting the seller.
 *
 * Per poll tick, for every trade in {@code PENDING_SELLER_SEND} (seller has
 * accepted, owes a Steam offer):
 *   (a) if the bot hasn't sent an offer for this trade yet, ask the sidecar to
 *       SEND a trade offer giving the item to the BUYER's trade URL, recording
 *       the resulting Steam offer id + state in {@link SteamDeliveryAttempt}
 *       (the authoritative offer store — no new column on Trade);
 *   (b) if an offer is outstanding, POLL its status;
 *   (c) when the Steam offer is verified ACCEPTED, advance the trade and credit
 *       the seller by driving the EXISTING public TradeService transitions —
 *       it never reimplements escrow/fee/release math.
 *
 * ── How crediting works WITHOUT editing TradeService ──────────────────────
 * TradeService's only non-buyer/non-admin path to VERIFIED is `release()`,
 * which is `protected`. The public transitions are:
 *     sellerMarkSent(sellerUserId, tradeId, tradeOfferUrl)  // SELLER_SEND -> BUYER_CONFIRM
 *     buyerConfirm(buyerUserId, tradeId)                    // BUYER_CONFIRM -> VERIFIED (+credit)
 * The bot acts on the seller's behalf to mark sent, and — once the Steam Web
 * API proves the buyer accepted — confirms receipt on the BUYER's behalf. Both
 * are existing public methods; buyerConfirm runs the frozen fee/escrow math in
 * release(). No TradeService edit is required for the happy path.
 *
 * If you would rather the system NOT impersonate the buyer for the confirm
 * step, see DELIVERY-INTEGRATION-NOTES.md for the single optional one-line hook
 * (`systemConfirmDelivery`) that would let the orchestrator release without the
 * buyer-id/ban-guard semantics. The code here works today without it.
 *
 * ── Disabled-mode safe ────────────────────────────────────────────────────
 * When SteamTradeBotService is disabled (no STEAM_BOT_BASE_URL), or this poller
 * is turned off via steam.delivery.enabled=false, the scheduled tick is a
 * no-op, so dev/test environments need no bot. Scheduling is already enabled
 * app-wide by @EnableScheduling on SboxMarketApplication.
 */
@Service
@Slf4j
class SteamDeliveryService {

    /** Trade state in which the seller owes a Steam offer — our send trigger. */
    static final String STATE_AWAITING_SEND = 'PENDING_SELLER_SEND'
    /** Trade state after the bot marks sent — we poll until the buyer accepts. */
    static final String STATE_AWAITING_CONFIRM = 'PENDING_BUYER_CONFIRM'

    @Autowired SteamTradeBotService steamTradeBotService
    @Autowired TradeService tradeService
    @Autowired TradeRepository tradeRepository
    @Autowired SteamDeliveryAttemptRepository attemptRepository
    @Autowired(required = false) SteamUserRepository steamUserRepository
    @Autowired(required = false) NotificationService notificationService
    /** Custody store — the REAL bot-held asset id for a sold listing comes from
     *  here (the deposit/escrow leg). Optional so existing unit specs that wire
     *  this service field-by-field stay green without a stub; when absent (and
     *  no test override), delivery records NO_ASSET_ID exactly as before. */
    @Autowired(required = false) SteamEscrowService steamEscrowService

    /** Master switch for the scheduled poller (independent of the bot's own
     *  enabled flag). Lets ops freeze auto-delivery without unsetting the bot. */
    @Value('${steam.delivery.enabled:true}')
    boolean pollerEnabled = true

    /** Default trade-offer message attached to outgoing offers. */
    @Value('${steam.delivery.offer-message:sboxmarket delivery}')
    String offerMessage

    /** Per-tick candidate cap so a backlog can't blow the poll budget. */
    @Value('${steam.delivery.batch-size:50}')
    int batchSize = 50

    // -----------------------------------------------------------------------
    // Scheduled entry point
    // -----------------------------------------------------------------------

    /**
     * Polls trades awaiting send/confirm. Interval configurable via
     * steam.delivery.poll-interval-ms (default 30s) with a short initial delay.
     */
    @Scheduled(initialDelayString = '${steam.delivery.initial-delay-ms:20000}',
               fixedDelayString = '${steam.delivery.poll-interval-ms:30000}')
    void pollDeliveries() {
        if (!pollerEnabled) return
        if (steamTradeBotService == null || !steamTradeBotService.enabled) {
            // Bot not configured (dev/test) — nothing to do.
            return
        }
        List<Trade> sendQ, confirmQ
        try {
            def page = PageRequest.of(0, Math.max(1, batchSize))
            // The bot delivers from the SELLER_SEND state onward; we also poll
            // any trade we previously advanced to BUYER_CONFIRM whose offer
            // we're still tracking.
            sendQ = tradeRepository.findByStateIn([STATE_AWAITING_SEND], page) ?: []
            confirmQ = tradeRepository.findByStateIn([STATE_AWAITING_CONFIRM], page) ?: []
        } catch (Exception e) {
            log.warn("SteamDelivery: failed to load candidate trades: ${e.message}")
            return
        }
        for (Trade t : sendQ) {
            try { processTrade(t.id) } catch (Exception e) {
                log.warn("SteamDelivery: error processing send-trade ${t?.id}: ${e.message}")
            }
        }
        for (Trade t : confirmQ) {
            // Only poll BUYER_CONFIRM trades the BOT is actually driving (an
            // offer row exists) — manual/legacy trades in this state are left
            // entirely to the existing buyerConfirm + auto-release sweeper.
            try { processTrade(t.id) } catch (Exception e) {
                log.warn("SteamDelivery: error processing confirm-trade ${t?.id}: ${e.message}")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Per-trade processing
    // -----------------------------------------------------------------------

    /**
     * Process one trade. NOTE: when invoked from the scheduled pollDeliveries()
     * this is a self-invocation, so Spring's CGLIB proxy is bypassed and this
     * method's @Transactional does NOT open a transaction — that is intentional
     * and safe here: the only money mutation is delegated to
     * tradeService.buyerConfirm()/sellerMarkSent(), which are calls to the
     * separately-proxied TradeService bean and therefore run in their OWN
     * (frozen, correct) transactions. The attempt-row writes are independent
     * audit inserts that are fine to auto-commit. The @Transactional is kept so
     * direct callers (tests / future external callers) still get a boundary.
     */
    @Transactional
    void processTrade(Long tradeId) {
        Trade trade = tradeRepository.findById(tradeId).orElse(null)
        if (trade == null) return
        SteamDeliveryAttempt existingOffer = latestOfferAttempt(tradeId)

        if (trade.state == STATE_AWAITING_SEND) {
            if (existingOffer == null) {
                sendOfferForTrade(trade)
            } else {
                // Offer already sent but the trade is still SELLER_SEND — the
                // sellerMarkSent transition must not have landed; poll, and on a
                // live/accepted offer drive the state forward.
                pollOfferForTrade(trade, existingOffer)
            }
        } else if (trade.state == STATE_AWAITING_CONFIRM) {
            if (existingOffer != null) {
                pollOfferForTrade(trade, existingOffer)
            }
            // else: not a bot-driven trade — leave to manual confirm / sweeper.
        }
        // Any other state (VERIFIED / CANCELLED / DISPUTED / SELLER_ACCEPT):
        // out of scope for the bot — ignore.
    }

    /** Most-recent attempt row that carries a Steam offer id, or null. */
    private SteamDeliveryAttempt latestOfferAttempt(Long tradeId) {
        def rows = attemptRepository.findLatestWithOffer(tradeId, PageRequest.of(0, 1))
        return (rows == null || rows.isEmpty()) ? null : rows.get(0)
    }

    /** (a) Ask the bot to send the offer; persist the offer id/state. */
    private void sendOfferForTrade(Trade trade) {
        String tradeUrl = resolveBuyerTradeUrl(trade)
        String assetId = resolveAssetId(trade)
        if (tradeUrl == null || tradeUrl.trim().isEmpty()) {
            log.warn("SteamDelivery: trade ${trade.id} buyer ${trade.buyerUserId} has no Steam trade URL; cannot auto-deliver")
            recordAttempt(trade.id, null, 'NO_TRADE_URL', 'SEND', false, 'buyer has no Steam trade URL')
            return
        }
        if (assetId == null || assetId.trim().isEmpty()) {
            log.warn("SteamDelivery: trade ${trade.id} has no resolvable Steam asset id; cannot auto-deliver (see DELIVERY-INTEGRATION-NOTES.md)")
            recordAttempt(trade.id, null, 'NO_ASSET_ID', 'SEND', false, 'no Steam asset id available for the sold item')
            return
        }

        SteamBotResult res = steamTradeBotService.sendOffer(tradeUrl, [assetId], offerMessage)
        if (res.ok) {
            recordAttempt(trade.id, res.offerId, res.status ?: 'sent', 'SEND', true, null)
            log.info("SteamDelivery: sent offer ${res.offerId} for trade ${trade.id} (status ${res.status})")
            // The held asset has been sent onward to the buyer — mark custody
            // DELIVERED so the return-to-seller path never tries to claw it
            // back. Best-effort; no-op when escrow is disabled / not custody.
            try { steamEscrowService?.markDelivered(trade.listingId) } catch (Exception ignore) {}
            // Mark the trade as sent on the SELLER's behalf — drives
            // PENDING_SELLER_SEND -> PENDING_BUYER_CONFIRM via the EXISTING
            // public transition. Reuse the already-resolved partner trade URL as
            // the offer URL (it satisfies the steamcommunity.com/tradeoffer
            // validation and gives the buyer a one-click link).
            advanceMarkSent(trade, tradeUrl)
            safeNotifyBuyer(trade,
                "A trade offer for your purchase (trade #${trade.id}) has been sent by our bot. Accept it in Steam to complete delivery.")
        } else {
            // Transient (rate limit / not ready / transport) — record but do NOT
            // persist an offer id, so the next tick retries the send.
            recordAttempt(trade.id, null, res.errorCode, 'SEND', false, res.message)
            log.warn("SteamDelivery: send failed for trade ${trade.id}: ${res.errorCode} ${res.message}")
        }
    }

    /** (b)+(c) Poll the outstanding offer and advance on ACCEPTED. */
    private void pollOfferForTrade(Trade trade, SteamDeliveryAttempt offer) {
        SteamBotResult res = steamTradeBotService.getOfferStatus(offer.steamOfferId)
        if (!res.ok) {
            recordAttempt(trade.id, offer.steamOfferId, res.errorCode, 'POLL', false, res.message)
            log.warn("SteamDelivery: poll failed for trade ${trade.id} offer ${offer.steamOfferId}: ${res.errorCode} ${res.message}")
            return
        }

        String status = res.status
        recordAttempt(trade.id, offer.steamOfferId, status, 'POLL', true, null)

        if (res.accepted) {
            // If the trade is still SELLER_SEND (mark-sent never landed),
            // advance it first so buyerConfirm's state gate is satisfied.
            if (trade.state == STATE_AWAITING_SEND) {
                advanceMarkSent(trade)
                // Re-load: advanceMarkSent saved through TradeService.
                trade = tradeRepository.findById(trade.id).orElse(trade)
            }
            advanceToVerified(trade)
        } else if (res.terminalFailure) {
            // Offer declined/expired/canceled. We do NOT touch escrow/money —
            // that is TradeService's domain. Notify so the buyer can be re-sent
            // or the seller/staff can act; the existing seller-timeout / dispute
            // paths remain the money authority.
            log.warn("SteamDelivery: offer ${offer.steamOfferId} for trade ${trade.id} is terminal (${status}); leaving money state to TradeService")
            safeNotifySeller(trade,
                "The Steam trade offer for trade #${trade.id} was ${status}. Delivery needs attention.")
            safeNotifyBuyer(trade,
                "Your Steam trade offer for trade #${trade.id} was ${status}.")
        } else if (res.inEscrow) {
            log.info("SteamDelivery: offer ${offer.steamOfferId} for trade ${trade.id} is in Steam escrow/hold; will keep polling until it clears")
        } else {
            // active / pending / needs_confirmation / unknown -> keep polling.
            log.debug("SteamDelivery: offer ${offer.steamOfferId} for trade ${trade.id} status ${status} — waiting")
        }
    }

    /**
     * Drive PENDING_SELLER_SEND -> PENDING_BUYER_CONFIRM via the EXISTING
     * public {@code sellerMarkSent}. Tolerates a benign state race.
     * {@code preResolvedUrl} avoids a redundant buyer lookup when the caller
     * already has the URL (the send path); null falls back to a fresh resolve.
     */
    private void advanceMarkSent(Trade trade, String preResolvedUrl = null) {
        if (trade.state != STATE_AWAITING_SEND) return
        String offerUrl = preResolvedUrl ?: resolveBuyerTradeUrl(trade)
        try {
            // Pass the buyer's trade URL as the offer URL so the buyer's Trades
            // tab gets a one-click Steam link. sellerMarkSent validates it is a
            // steamcommunity.com/tradeoffer/... URL; the partner trade URL is.
            tradeService.sellerMarkSent(trade.sellerUserId, trade.id, offerUrl)
            log.info("SteamDelivery: trade ${trade.id} marked sent (SELLER_SEND -> BUYER_CONFIRM) via bot")
        } catch (Exception e) {
            // BadRequest (URL validation / state) or Forbidden — log and let the
            // next tick re-evaluate. Never throw out of the poller.
            log.info("SteamDelivery: sellerMarkSent for trade ${trade.id} did not apply: ${e.message}")
        }
    }

    /**
     * (c) Advance PENDING_BUYER_CONFIRM -> VERIFIED + credit seller via the
     * EXISTING public {@code buyerConfirm}. Does not reimplement fee/escrow math.
     */
    private void advanceToVerified(Trade trade) {
        if (trade.state != STATE_AWAITING_CONFIRM) {
            log.info("SteamDelivery: trade ${trade.id} not in ${STATE_AWAITING_CONFIRM} (is ${trade.state}); skipping credit")
            return
        }
        try {
            // Confirm receipt on the buyer's behalf — the Steam Web API has
            // verified the buyer accepted the on-chain offer, which is the
            // platform's proof of delivery. buyerConfirm runs the frozen
            // release()/fee math and credits the seller wallet.
            tradeService.buyerConfirm(trade.buyerUserId, trade.id)
            log.info("SteamDelivery: trade ${trade.id} auto-verified via accepted Steam offer; seller credited through existing release path")
            recordAttempt(trade.id, null, 'verified', 'POLL', true, 'auto-confirmed via Steam Web API')
        } catch (Exception e) {
            // Benign race (already confirmed / advanced), banned buyer, or
            // missing wallet — log and move on. The existing auto-release
            // sweeper remains the safety net for stuck BUYER_CONFIRM trades.
            log.info("SteamDelivery: buyerConfirm for trade ${trade.id} did not apply (likely already progressed / banned buyer): ${e.message}")
        }
    }

    // -----------------------------------------------------------------------
    // Resolution helpers
    // -----------------------------------------------------------------------

    /** The buyer's Steam trade URL (SteamUser.tradeUrl). Null when unknown. */
    private String resolveBuyerTradeUrl(Trade trade) {
        if (trade.buyerUserId == null || steamUserRepository == null) return null
        try {
            def buyer = steamUserRepository.findById(trade.buyerUserId).orElse(null)
            return buyer?.tradeUrl
        } catch (Exception e) {
            log.debug("SteamDelivery: buyer trade-url lookup failed for trade ${trade.id}: ${e.message}")
            return null
        }
    }

    /**
     * Resolve the Steam ASSET id of the item the bot must give the buyer.
     *
     * Primary source (REAL, end-to-end): the bot-held asset from the custody
     * store ({@link SteamEscrowService#heldAssetIdForListing}). The deposit/
     * escrow leg has the bot RECEIVE the seller's specific 590830 asset before
     * the listing ever becomes buyable, so by the time a trade exists the bot
     * genuinely holds an asset id it can send onward. This is what makes
     * auto-delivery real rather than mocked.
     *
     * Fallback (explicit staging ONLY): {@code steam.delivery.test-asset-id}
     * — a demo item the bot already holds, for staging an end-to-end run
     * without a real deposit. Left in deliberately and only as an override.
     *
     * When neither yields an id, sendOfferForTrade records a NO_ASSET_ID
     * attempt and the trade falls through to the existing manual / sweeper
     * flow. See DELIVERY-INTEGRATION-NOTES.md.
     */
    @Value('${steam.delivery.test-asset-id:}')
    String testAssetIdOverride

    private String resolveAssetId(Trade trade) {
        // 1. Real custody-held asset for this sold listing.
        try {
            String held = steamEscrowService?.heldAssetIdForListing(trade?.listingId)
            if (held != null && !held.trim().isEmpty()) return held.trim()
        } catch (Exception e) {
            log.debug("SteamDelivery: custody asset lookup failed for trade ${trade?.id}: ${e.message}")
        }
        // 2. Explicit staging override.
        return (testAssetIdOverride != null && !testAssetIdOverride.trim().isEmpty())
                ? testAssetIdOverride.trim()
                : null
    }

    // -----------------------------------------------------------------------
    // Audit + notifications (best-effort)
    // -----------------------------------------------------------------------

    /** Append an audit/offer-tracking row. Never blocks delivery. */
    private void recordAttempt(Long tradeId, String offerId, String state, String phase,
                               boolean ok, String error) {
        try {
            def a = new SteamDeliveryAttempt(
                    tradeId: tradeId,
                    steamOfferId: offerId,
                    offerState: state,
                    phase: phase,
                    success: ok,
                    errorMessage: error != null && error.length() > 500 ? error.substring(0, 500) : error,
                    createdAt: System.currentTimeMillis()
            )
            attemptRepository.save(a)
        } catch (Exception e) {
            log.debug("SteamDelivery: could not persist attempt for trade ${tradeId}: ${e.message}")
        }
    }

    private void safeNotifyBuyer(Trade trade, String body) {
        if (trade?.buyerUserId == null) return
        try {
            notificationService?.safePush(trade.buyerUserId, 'TRADE_SENT',
                    "Steam delivery · trade #${trade.id}", body, trade.id, '/profile?tab=trades')
        } catch (Exception ignore) { /* best-effort */ }
    }

    private void safeNotifySeller(Trade trade, String body) {
        if (trade?.sellerUserId == null) return
        try {
            notificationService?.safePush(trade.sellerUserId, 'TRADE_SENT',
                    "Steam delivery · trade #${trade.id}", body, trade.id, '/profile?tab=trades')
        } catch (Exception ignore) { /* best-effort */ }
    }
}
