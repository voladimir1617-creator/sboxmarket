package com.sboxmarket.service

import com.sboxmarket.model.EscrowedItem
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.EscrowedItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Seller → bot DEPOSIT / custody orchestrator (the missing escrow leg).
 *
 * Closes the bot-escrow loop so a real item actually moves seller → bot →
 * buyer. The DELIVERY leg ({@link SteamDeliveryService}) already sends a
 * bot-held asset to the buyer and credits the seller through the frozen
 * TradeService release path; it was missing a concrete bot-held asset id.
 * This service provides it:
 *
 *   1. DEPOSIT — when a seller lists a Steam item, the bot sends the seller a
 *      trade offer that REQUESTS (receives, gives nothing) that specific
 *      590830 asset. The listing is created in status PENDING_ESCROW so it is
 *      NOT yet public and NOT buyable (every public query + PurchaseService.buy
 *      already gate on status='ACTIVE').
 *   2. CONFIRM — a scheduled poller checks each pending deposit's offer state
 *      AND the bot's own inventory; once the asset is genuinely held the
 *      custody row flips PENDING_DEPOSIT → IN_CUSTODY and the listing flips
 *      PENDING_ESCROW → ACTIVE (now buyable).
 *   2a. TIMEOUT — a scheduled safety sweeper gives up on deposits the seller
 *      never completes: a PENDING_DEPOSIT row older than
 *      {@code steam.escrow.deposit-timeout-hours} flips custody → FAILED and
 *      its held listing PENDING_ESCROW → CANCELLED, so the seller's item is
 *      never locked out of the market forever. The bot-escrow analogue of
 *      StripeService.sweepStalePendingDeposits.
 *   3. DELIVER — handled by SteamDeliveryService, which resolves the held
 *      assetId from the custody row keyed by the trade's listingId.
 *   4. RETURN — when a listing is cancelled / expires / goes unsold, the bot
 *      sends the held asset back to the seller and custody → RETURNED.
 *
 * ── Disabled-mode safe ────────────────────────────────────────────────────
 * EVERYTHING here is gated on {@code steamTradeBotService.enabled} (true only
 * when STEAM_BOT_BASE_URL is set). When the bot is unconfigured (dev / test /
 * CI, or prod before the bot host is live), {@link #isEscrowEnabled()} is
 * false: {@link #requestDepositForListing} is a no-op (listings stay ACTIVE
 * and buyable the legacy way, no deposit required), the scheduled pollers +
 * the timeout sweeper short-circuit, and the return path no-ops. So the entire
 * escrow path is inert and the marketplace keeps working exactly as before.
 */
@Service
@Slf4j
class SteamEscrowService {

    /** Listing status while the seller's item is being deposited into bot
     *  custody. NOT 'ACTIVE', so the listing is invisible to every public
     *  query and rejected by PurchaseService.buy until custody confirms. */
    static final String STATUS_PENDING_ESCROW = 'PENDING_ESCROW'
    static final String STATUS_ACTIVE = 'ACTIVE'
    /** Terminal listing state for a held listing whose deposit never completed
     *  — the timeout sweeper reverts PENDING_ESCROW → CANCELLED so the seller's
     *  item is no longer locked out of the market in a never-resolving hold.
     *  Matches the {ACTIVE, SOLD, CANCELLED} listing state machine. */
    static final String STATUS_CANCELLED = 'CANCELLED'

    @Autowired SteamTradeBotService steamTradeBotService
    @Autowired EscrowedItemRepository escrowRepository
    @Autowired ListingRepository listingRepository
    @Autowired(required = false) SteamUserRepository steamUserRepository
    @Autowired(required = false) NotificationService notificationService

    /** Master switch for the escrow pollers, independent of the bot's own
     *  enabled flag — lets ops freeze deposit/return sweeps without unsetting
     *  the bot. Mirrors steam.delivery.enabled on the delivery side. */
    @Value('${steam.escrow.enabled:true}')
    boolean escrowSweepEnabled = true

    /** Message attached to deposit-request + return offers. */
    @Value('${steam.escrow.offer-message:sboxmarket escrow}')
    String offerMessage

    /** Per-tick deposit-confirm candidate cap. */
    @Value('${steam.escrow.batch-size:50}')
    int batchSize = 50

    /** Hours a deposit may sit in PENDING_DEPOSIT before the timeout sweeper
     *  gives up on it: marks custody FAILED and reverts the held listing out of
     *  PENDING_ESCROW so the seller's item is no longer locked out of the
     *  market. 24h gives a seller a full day to accept the bot's deposit offer.
     *  Mirrors the Stripe stale-deposit sweeper's bounded-window posture. */
    @Value('${steam.escrow.deposit-timeout-hours:24}')
    int depositTimeoutHours = 24

    /**
     * True only when the bot sidecar is configured. The whole escrow leg is
     * inert when this is false — listings are created ACTIVE the legacy way
     * and nothing is scheduled. Public so callers (the listing flow) can
     * decide whether to hold a Steam listing for deposit.
     */
    boolean isEscrowEnabled() {
        return steamTradeBotService != null && steamTradeBotService.enabled
    }

    // -----------------------------------------------------------------------
    // 1. DEPOSIT — bot requests the seller's asset on list
    // -----------------------------------------------------------------------

    /**
     * Kick off a deposit for a freshly-created Steam listing: the bot sends the
     * seller a trade offer REQUESTING {@code assetId}. Persists a
     * PENDING_DEPOSIT custody row and (if the bot accepted the request) flips
     * the listing to PENDING_ESCROW so it isn't buyable until custody confirms.
     *
     * No-op (returns null) when escrow is disabled — the caller keeps the
     * listing ACTIVE the legacy way.
     *
     * @return the persisted {@link EscrowedItem}, or null when escrow is
     *         disabled / inputs are unusable / the seller has no trade URL.
     */
    @Transactional
    EscrowedItem requestDepositForListing(Listing listing, String assetId, String marketHashName) {
        if (!escrowEnabled) return null
        if (listing == null || listing.id == null) return null
        if (assetId == null || assetId.trim().isEmpty()) {
            log.warn("SteamEscrow: listing ${listing?.id} has no assetId; cannot deposit")
            return null
        }
        // Idempotency — never create a second deposit for the same listing.
        def existing = escrowRepository.findByListingId(listing.id)
        if (existing != null) return existing

        String tradeUrl = resolveSellerTradeUrl(listing.sellerUserId)
        if (tradeUrl == null || tradeUrl.trim().isEmpty()) {
            // Surface a clear, actionable custody row so the seller/staff can
            // see WHY the listing never went live. The listing is held in
            // PENDING_ESCROW (not buyable) rather than silently ACTIVE — a
            // listing we can't escrow must not be sold.
            def row = persist(new EscrowedItem(
                    listingId: listing.id,
                    sellerUserId: listing.sellerUserId,
                    assetId: assetId.trim(),
                    marketHashName: marketHashName,
                    custodyState: EscrowedItem.PENDING_DEPOSIT,
                    lastError: 'seller has no Steam trade URL — set one in Profile to deposit'))
            holdListing(listing.id)
            log.warn("SteamEscrow: seller ${listing.sellerUserId} has no trade URL; listing ${listing.id} held PENDING_ESCROW")
            safeNotifySeller(listing.sellerUserId, listing.id,
                    "Your listing can't go live until you add a Steam trade URL in Profile so our bot can receive the item.")
            return row
        }

        // Hold the listing FIRST (PENDING_ESCROW) so there is never a window in
        // which it is ACTIVE-and-buyable without the item being in custody.
        holdListing(listing.id)

        def row = new EscrowedItem(
                listingId: listing.id,
                sellerUserId: listing.sellerUserId,
                assetId: assetId.trim(),
                marketHashName: marketHashName,
                custodyState: EscrowedItem.PENDING_DEPOSIT)

        SteamBotResult res = steamTradeBotService.requestItems(tradeUrl, [assetId.trim()], offerMessage)
        if (res.ok) {
            row.depositOfferId = res.offerId
            row.lastError = null
            persist(row)
            log.info("SteamEscrow: deposit offer ${res.offerId} requested for listing ${listing.id} asset ${assetId}")
            safeNotifySeller(listing.sellerUserId, listing.id,
                    "Accept the Steam trade offer from our bot to deposit your item — your listing goes live the moment we receive it.")
        } else {
            // Transient (rate limit / not ready / transport) — persist the row
            // WITHOUT an offer id so a later retry can re-request. Listing
            // stays held (PENDING_ESCROW) — not buyable.
            row.lastError = "${res.errorCode}: ${res.message}"
            persist(row)
            log.warn("SteamEscrow: deposit request failed for listing ${listing.id}: ${res.errorCode} ${res.message}")
        }
        return row
    }

    // -----------------------------------------------------------------------
    // 2. CONFIRM — scheduled poll of pending deposits
    // -----------------------------------------------------------------------

    /**
     * Poll deposits awaiting custody. For each PENDING_DEPOSIT row with a
     * deposit offer: check the offer state; once Steam reports it accepted AND
     * the bot inventory actually holds the asset, flip custody → IN_CUSTODY and
     * the listing PENDING_ESCROW → ACTIVE (buyable).
     */
    @Scheduled(initialDelayString = '${steam.escrow.initial-delay-ms:25000}',
               fixedDelayString = '${steam.escrow.poll-interval-ms:30000}')
    void pollDeposits() {
        if (!escrowSweepEnabled) return
        if (!escrowEnabled) return
        List<EscrowedItem> pending
        try {
            pending = escrowRepository.findPendingDeposits(PageRequest.of(0, Math.max(1, batchSize))) ?: []
        } catch (Exception e) {
            log.warn("SteamEscrow: failed to load pending deposits: ${e.message}")
            return
        }
        if (pending.isEmpty()) return
        // Fetch the bot inventory ONCE per tick and reuse it for every row's
        // custody confirmation (avoids one /inventory call per pending row).
        Map botInventory = fetchBotInventoryIndex()
        for (EscrowedItem e : pending) {
            try { confirmDeposit(e.id, botInventory) } catch (Exception ex) {
                log.warn("SteamEscrow: error confirming deposit ${e?.id}: ${ex.message}")
            }
        }
    }

    /**
     * Confirm one pending deposit. Re-loads the row (it may have advanced),
     * polls the deposit offer, and — when accepted and the asset is held —
     * promotes custody + listing. {@code botInventory} is the per-tick
     * inventory index (assetId/marketHashName → held assetId); null forces a
     * fresh fetch (direct-call path).
     */
    @Transactional
    void confirmDeposit(Long escrowId, Map botInventory = null) {
        EscrowedItem e = escrowRepository.findById(escrowId).orElse(null)
        if (e == null || e.custodyState != EscrowedItem.PENDING_DEPOSIT) return
        if (e.depositOfferId == null || e.depositOfferId.trim().isEmpty()) return

        SteamBotResult res = steamTradeBotService.getOfferStatus(e.depositOfferId)
        if (!res.ok) {
            touchError(e, "poll ${res.errorCode}: ${res.message}")
            return
        }
        if (res.terminalFailure) {
            // Seller declined / let the deposit offer expire. The listing can't
            // go live — mark the custody FAILED and leave the listing in
            // PENDING_ESCROW (a separate staff/seller action can cancel it).
            e.custodyState = EscrowedItem.FAILED
            e.lastError = "deposit offer ${res.status}"
            touch(e)
            log.warn("SteamEscrow: deposit offer ${e.depositOfferId} for listing ${e.listingId} is terminal (${res.status})")
            safeNotifySeller(e.sellerUserId, e.listingId,
                    "Your deposit trade offer was ${res.status}, so your listing didn't go live. Re-list to try again.")
            return
        }
        if (!res.accepted) {
            // active / in_escrow / pending — keep waiting.
            return
        }

        // Offer accepted. Confirm the bot ACTUALLY holds the asset before we
        // make the listing buyable — the offer being "accepted" plus the asset
        // appearing in the bot inventory is the platform's proof of custody.
        Map index = botInventory != null ? botInventory : fetchBotInventoryIndex()
        String held = resolveHeldAssetId(e, index)
        if (held == null) {
            // Accepted but not yet visible in inventory (Steam propagation lag,
            // or a transient inventory fetch failure) — wait for the next tick.
            touchError(e, 'offer accepted; awaiting asset in bot inventory')
            return
        }

        e.heldAssetId = held
        e.custodyState = EscrowedItem.IN_CUSTODY
        e.lastError = null
        touch(e)
        // Make the listing buyable: PENDING_ESCROW -> ACTIVE.
        activateListing(e.listingId)
        log.info("SteamEscrow: listing ${e.listingId} item now IN_CUSTODY (held asset ${held}); listing activated")
        safeNotifySeller(e.sellerUserId, e.listingId,
                "We've received your item — your listing is now live on the marketplace.")
    }

    // -----------------------------------------------------------------------
    // 3. TIMEOUT — give up on deposits the seller never completed
    // -----------------------------------------------------------------------

    /**
     * Production-safety sweeper for STUCK deposits. {@link #pollDeposits} only
     * ever ADVANCES a row that has a depositOfferId AND whose offer Steam
     * reports accepted; it has no give-up path. So a deposit the seller never
     * accepts (offer sits {@code active} forever) — or a row whose offer never
     * got created at all ({@code depositOfferId} null after a transient bot
     * failure, which {@code findPendingDeposits} won't even return) — sits in
     * PENDING_DEPOSIT / listing PENDING_ESCROW FOREVER, locking the seller's
     * item out of the market with no auto-resolution.
     *
     * This is the missing timeout leg, the bot-escrow analogue of
     * {@link com.sboxmarket.service.StripeService#sweepStalePendingDeposits}.
     * For each PENDING_DEPOSIT row older than {@code deposit-timeout-hours}:
     *   - custody PENDING_DEPOSIT → FAILED (atomic, status-guarded claim), and
     *   - listing PENDING_ESCROW → CANCELLED (frees the item — a listing whose
     *     deposit never landed must NOT become buyable, so we cancel rather
     *     than re-activate), and
     *   - the seller is notified to re-list.
     *
     * ── Disabled-mode safe ────────────────────────────────────────────────
     * Gated on {@link #escrowSweepEnabled} AND {@link #isEscrowEnabled} exactly
     * like {@link #pollDeposits}, so it is a complete no-op when the Steam bot
     * is unconfigured (STEAM_BOT_BASE_URL unset — dev / test / CI, or prod
     * before the bot host is live). It touches NO bot network APIs at all (a
     * timed-out deposit means the asset never reached the bot — there's nothing
     * to return), only the local custody + listing tables.
     *
     * NOT @Transactional — like StripeService.sweepStalePendingDeposits this
     * runs per-row atomic conditional UPDATEs (multi-pod claim) rather than a
     * shared outer tx, with a batch cap and per-row try/catch so one bad row
     * can't abort the sweep.
     */
    @Scheduled(initialDelayString = '${steam.escrow.timeout-initial-delay-ms:60000}',
               fixedDelayString = '${steam.escrow.timeout-interval-ms:3600000}')
    void sweepStalePendingDeposits() {
        if (!escrowSweepEnabled) return
        if (!escrowEnabled) return
        long timeoutMs = Math.max(1L, (long) depositTimeoutHours) * 60L * 60L * 1000L
        long cutoff = System.currentTimeMillis() - timeoutMs
        List<EscrowedItem> stale
        try {
            stale = escrowRepository.findStalePendingDeposits(cutoff,
                    PageRequest.of(0, Math.max(1, batchSize))) ?: []
        } catch (Exception e) {
            log.warn("SteamEscrow: failed to load stale pending deposits: ${e.message}")
            return
        }
        if (stale.isEmpty()) return
        log.info("SteamEscrow: ${stale.size()} candidate stale PENDING_DEPOSIT row(s) past ${depositTimeoutHours}h; racing for claims")

        int failed = 0
        for (EscrowedItem e : stale) {
            try {
                // Multi-pod / out-of-band claim. Flips custody
                // PENDING_DEPOSIT→FAILED only if the row is still
                // PENDING_DEPOSIT — a sibling pod, or a last-second
                // confirmDeposit that promoted the row to IN_CUSTODY, makes
                // this UPDATE miss (0 rows) and we bail WITHOUT reverting the
                // listing, so a just-deposited item is never wrongly failed.
                int claimed = escrowRepository.claimTimeoutPendingDeposit(
                        e.id,
                        "deposit not completed within ${depositTimeoutHours}h — timed out".toString(),
                        System.currentTimeMillis())
                if (claimed == 0) {
                    log.debug("SteamEscrow: timeout claim lost for escrow=${e.id} — sibling pod or last-second deposit")
                    continue
                }
                failed++
                // Free the seller's item: revert the held listing out of
                // PENDING_ESCROW. Own try/catch (already inside the per-row
                // one) — even if the listing flip hiccups the custody row is
                // already FAILED, so the row won't be re-swept.
                cancelHeldListing(e.listingId)
                safeNotifySeller(e.sellerUserId, e.listingId,
                        "Your listing didn't go live — we never received your item within ${depositTimeoutHours}h, so the listing was cancelled. Re-list and accept the bot's Steam trade offer to try again.")
            } catch (Exception ex) {
                log.warn("SteamEscrow: stale-deposit sweep failed on escrow=${e?.id}: ${ex.message}")
            }
        }
        log.info("SteamEscrow: stale-deposit sweep failed ${failed} of ${stale.size()} candidate(s) (rest claimed by sibling pods or deposited last-second)")
    }

    // -----------------------------------------------------------------------
    // 4. RETURN — send the held asset back to the seller
    // -----------------------------------------------------------------------

    /**
     * Return a held asset to its seller (listing cancelled / expired / unsold).
     * No-op when escrow is disabled or there is no IN_CUSTODY row for the
     * listing. Idempotent: a row already DELIVERED/RETURNED is left alone.
     *
     * @return true when a return offer was sent (custody → RETURNED).
     */
    @Transactional
    boolean returnToSeller(Long listingId, String reason = 'listing cancelled') {
        if (!escrowEnabled) return false
        EscrowedItem e = escrowRepository.findByListingId(listingId)
        if (e == null) return false
        // Only items the bot actually holds can be returned. DELIVERED (sent to
        // a buyer) and already-RETURNED rows are terminal.
        if (e.custodyState != EscrowedItem.IN_CUSTODY) return false

        String tradeUrl = resolveSellerTradeUrl(e.sellerUserId)
        if (tradeUrl == null || tradeUrl.trim().isEmpty()) {
            touchError(e, 'cannot return — seller has no trade URL')
            log.warn("SteamEscrow: cannot return listing ${listingId} asset — seller ${e.sellerUserId} has no trade URL")
            return false
        }
        String assetToSend = e.heldAssetId ?: e.assetId
        SteamBotResult res = steamTradeBotService.sendOffer(tradeUrl, [assetToSend], offerMessage)
        if (res.ok) {
            e.returnOfferId = res.offerId
            e.custodyState = EscrowedItem.RETURNED
            e.lastError = null
            touch(e)
            log.info("SteamEscrow: returned asset ${assetToSend} to seller ${e.sellerUserId} for listing ${listingId} (offer ${res.offerId}; ${reason})")
            safeNotifySeller(e.sellerUserId, listingId,
                    "Your listing closed without a sale — our bot has sent your item back to you. Accept the Steam offer to receive it.")
            return true
        } else {
            // Leave IN_CUSTODY so a later tick / retry can re-send the return.
            touchError(e, "return failed ${res.errorCode}: ${res.message}")
            log.warn("SteamEscrow: return offer failed for listing ${listingId}: ${res.errorCode} ${res.message}")
            return false
        }
    }

    /**
     * Mark a listing's held asset DELIVERED (it was sent to a buyer).
     * Called by SteamDeliveryService once it sends the bot-held asset onward,
     * so the return-to-seller path never tries to claw it back. No-op when
     * escrow is disabled or no custody row exists.
     */
    @Transactional
    void markDelivered(Long listingId) {
        if (!escrowEnabled) return
        EscrowedItem e = escrowRepository.findByListingId(listingId)
        if (e == null) return
        if (e.custodyState == EscrowedItem.IN_CUSTODY) {
            e.custodyState = EscrowedItem.DELIVERED
            touch(e)
        }
    }

    /** Resolve the bot-held asset id for a listing's confirmed custody, or
     *  null when the item isn't IN_CUSTODY. Used by the delivery leg to send
     *  the REAL asset to the buyer. */
    String heldAssetIdForListing(Long listingId) {
        if (!escrowEnabled || listingId == null) return null
        def e = escrowRepository.findByListingId(listingId)
        if (e == null || e.custodyState != EscrowedItem.IN_CUSTODY) return null
        return e.heldAssetId ?: e.assetId
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Build an index of the bot's inventory keyed by both assetId and
     *  marketHashName → held assetId, so custody-confirm can match an asset
     *  even when Steam reassigns the asset id on receipt. Empty on failure. */
    private Map fetchBotInventoryIndex() {
        def byAsset = [:]
        def byName = [:]
        try {
            SteamBotResult inv = steamTradeBotService.fetchBotInventory()
            if (inv.ok && inv.raw != null) {
                def items = inv.raw['items']
                if (items instanceof List) {
                    items.each { it ->
                        if (!(it instanceof Map)) return
                        def aid = it['assetId'] != null ? String.valueOf(it['assetId']) : null
                        def name = it['marketHashName'] != null ? String.valueOf(it['marketHashName']) : null
                        if (aid != null) {
                            byAsset[aid] = aid
                            if (name != null && !byName.containsKey(name)) byName[name] = aid
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("SteamEscrow: bot inventory fetch failed: ${e.message}")
        }
        return [byAsset: byAsset, byName: byName]
    }

    /** Find the held asset id for a deposit using the inventory index: prefer
     *  the original asset id (Steam usually preserves it), else fall back to a
     *  same-item match by market_hash_name. Null when neither matches. */
    private String resolveHeldAssetId(EscrowedItem e, Map index) {
        if (index == null) return null
        def byAsset = (index['byAsset'] ?: [:]) as Map
        def byName = (index['byName'] ?: [:]) as Map
        if (e.assetId != null && byAsset.containsKey(e.assetId)) {
            return e.assetId
        }
        if (e.marketHashName != null && byName.containsKey(e.marketHashName)) {
            return byName[e.marketHashName] as String
        }
        return null
    }

    private String resolveSellerTradeUrl(Long sellerUserId) {
        if (sellerUserId == null || steamUserRepository == null) return null
        try {
            def seller = steamUserRepository.findById(sellerUserId).orElse(null)
            return seller?.tradeUrl
        } catch (Exception e) {
            log.debug("SteamEscrow: seller trade-url lookup failed for ${sellerUserId}: ${e.message}")
            return null
        }
    }

    /** Flip a listing PENDING_ESCROW -> ACTIVE (now buyable). */
    private void activateListing(Long listingId) {
        try {
            def l = listingRepository.findById(listingId).orElse(null)
            if (l != null && l.status == STATUS_PENDING_ESCROW) {
                l.status = STATUS_ACTIVE
                listingRepository.save(l)
            }
        } catch (Exception e) {
            log.warn("SteamEscrow: could not activate listing ${listingId}: ${e.message}")
        }
    }

    /** Flip a held listing PENDING_ESCROW -> CANCELLED (deposit timed out).
     *  Only touches PENDING_ESCROW rows so a benign race (seller cancelled the
     *  listing themselves, or a last-second activate flipped it ACTIVE) can't
     *  be stomped — same status-guarded posture as holdListing/activateListing.
     *  Runs in its own implicit auto-commit tx (the sweeper is NOT
     *  @Transactional) so a failure here can't poison the per-row claim. */
    private void cancelHeldListing(Long listingId) {
        try {
            def l = listingRepository.findById(listingId).orElse(null)
            if (l != null && l.status == STATUS_PENDING_ESCROW) {
                l.status = STATUS_CANCELLED
                listingRepository.save(l)
                log.info("SteamEscrow: listing ${listingId} reverted PENDING_ESCROW -> CANCELLED (deposit timed out)")
            }
        } catch (Exception e) {
            log.warn("SteamEscrow: could not cancel held listing ${listingId}: ${e.message}")
        }
    }

    /** Flip a freshly-created listing ACTIVE -> PENDING_ESCROW (hold until
     *  custody). Only touches ACTIVE rows so it can't disturb a sold/cancelled
     *  listing in a benign race. */
    private void holdListing(Long listingId) {
        try {
            def l = listingRepository.findById(listingId).orElse(null)
            if (l != null && l.status == STATUS_ACTIVE) {
                l.status = STATUS_PENDING_ESCROW
                listingRepository.save(l)
            }
        } catch (Exception e) {
            log.warn("SteamEscrow: could not hold listing ${listingId}: ${e.message}")
        }
    }

    private EscrowedItem persist(EscrowedItem e) {
        e.updatedAt = System.currentTimeMillis()
        return escrowRepository.save(e)
    }

    private void touch(EscrowedItem e) {
        e.updatedAt = System.currentTimeMillis()
        escrowRepository.save(e)
    }

    private void touchError(EscrowedItem e, String err) {
        e.lastError = err != null && err.length() > 500 ? err.substring(0, 500) : err
        touch(e)
    }

    private void safeNotifySeller(Long sellerUserId, Long listingId, String body) {
        if (sellerUserId == null) return
        try {
            notificationService?.safePush(sellerUserId, 'TRADE_SENT',
                    'Steam escrow', body, listingId, '/profile?tab=trades')
        } catch (Exception ignore) { /* best-effort */ }
    }
}
