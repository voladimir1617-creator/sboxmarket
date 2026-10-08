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
    @Autowired(required = false) @org.springframework.context.annotation.Lazy SavedSearchService savedSearchService

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

    /** Hours an UNACCEPTED deposit may sit in PENDING_DEPOSIT before the
     *  timeout sweeper gives up on it: marks custody FAILED and reverts the
     *  held listing out of PENDING_ESCROW so the seller's item is no longer
     *  locked out of the market. 24h gives a seller a full day to accept the
     *  bot's deposit offer. Mirrors the Stripe stale-deposit sweeper's
     *  bounded-window posture.
     *
     *  ── This is the UNHELD deadline only ──────────────────────────────────
     *  It applies to a deposit the seller has not accepted, where the item is
     *  still in their own inventory and giving up costs nobody anything. It is
     *  explicitly NOT the deadline for a deposit sitting in a Steam trade hold
     *  — see {@link #depositDeadlineFor}, which is the single place that
     *  decides lateness. A constant cannot be the answer for a window Steam
     *  varies between 7 and 15 days. */
    @Value('${steam.escrow.deposit-timeout-hours:24}')
    int depositTimeoutHours = 24

    /** Grace window added to a Steam trade hold's OWN release time before the
     *  deposit is treated as overdue.
     *
     *  Steam releases a held item at {@code escrowEndsUnix}, but the asset does
     *  not appear in the bot's inventory at that instant — the release, Steam's
     *  own inventory propagation, and our next poll all add latency, and the
     *  published release time is itself approximate. 48h absorbs all of that
     *  without ever being the reason a genuinely-arrived item is called late.
     *
     *  This grace only affects when we start SHOUTING about a hold. It is not a
     *  give-up threshold: nothing in this service ever fails a deposit whose
     *  item has left the seller, at any age. */
    @Value('${steam.escrow.hold-grace-hours:48}')
    int holdGraceHours = 48

    /** Minimum gap between two escalations for the SAME overdue hold. Every
     *  escalation stamps updatedAt and the sweep's query is
     *  {@code updatedAt < cutoff}, so this is what stops one stuck item
     *  producing an ERROR line and a seller notification on every tick for the
     *  rest of the container's life. 6h keeps it visible without becoming noise
     *  an operator learns to scroll past. */
    @Value('${steam.escrow.hold-escalation-backoff-ms:21600000}')
    long holdEscalationBackoffMs = 21600000L

    /** Minimum gap between two return attempts for the SAME item. Every
     *  attempt stamps updatedAt, and the retry query is {@code updatedAt <
     *  cutoff}, so this is what stops a rate-limited bot from being retried on
     *  every tick — and stops a stranded item generating a log line every ten
     *  minutes forever. 10 minutes is well inside Steam's rate-limit window. */
    @Value('${steam.escrow.return-retry-backoff-ms:600000}')
    long returnRetryBackoffMs = 600000L

    /** Attempt count past which a still-held item is logged at ERROR rather
     *  than WARN. NOT a give-up threshold — the sweep keeps trying forever,
     *  because the thing it is trying to hand back is a real user's item and
     *  the platform is the one holding it. This only changes the volume. */
    @Value('${steam.escrow.return-alert-attempts:5}')
    int returnAlertAttempts = 5

    /** Minimum gap between two deposit re-requests for the same listing.
     *  Shorter than the return backoff because the whole window is bounded by
     *  {@code deposit-timeout-hours} anyway: a seller who pastes a missing
     *  trade URL should get their bot offer within minutes, not hours, or the
     *  24h timeout arrives before the retry does any good. */
    @Value('${steam.escrow.deposit-retry-backoff-ms:300000}')
    long depositRetryBackoffMs = 300000L

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
    // Steam trade holds — reading them, and what they do to the deadline
    // -----------------------------------------------------------------------

    /**
     * Read a Steam trade-hold release time out of a sidecar offer-status
     * response and return it in epoch MILLIS, or null when the offer carries no
     * hold.
     *
     * ── The units conversion is the whole method ──────────────────────────
     * The sidecar reports {@code escrowEndsUnix} in SECONDS (it is
     * {@code Math.floor(offer.escrowEnds.getTime() / 1000)} in
     * steam-bot/index.js). Every timestamp on {@code escrowed_items} is epoch
     * MILLIS. Storing the raw value would put the release date in January 1970
     * — a hold that expired 56 years ago — so every held deposit would read as
     * instantly overdue and the sweeper would fail and cancel it on the first
     * tick. That is the identical outcome to having no hold support at all,
     * except now with a populated column that makes it look handled. Hence a
     * named method with a test on it rather than an inline {@code * 1000}.
     *
     * Defensive about the shape because this crosses a process boundary: the
     * value arrives as whatever Jackson made of the JSON (Integer for small
     * values, Long, or a String), and a hold 15 days out is ~1.7e9 seconds,
     * which overflows nothing but does arrive as an Integer often enough to
     * matter. Non-positive and unparseable values read as "no hold" rather than
     * as an epoch at the dawn of 1970.
     */
    static Long readEscrowEndsMillis(SteamBotResult res) {
        if (res == null || res.raw == null) return null
        def raw = res.raw['escrowEndsUnix']
        if (raw == null) return null
        long seconds
        try {
            seconds = (raw instanceof Number) ? ((Number) raw).longValue()
                                              : Long.parseLong(String.valueOf(raw).trim())
        } catch (Exception ignore) {
            return null
        }
        if (seconds <= 0L) return null
        return seconds * 1000L
    }

    /**
     * The instant after which a deposit is genuinely late — the single place
     * that decides it, and the reason the timeout is a function of the hold
     * rather than a constant.
     *
     * Three cases, in the order they are checked, because the later ones are
     * only safe once the earlier ones are ruled out:
     *
     *   1. A KNOWN hold ({@code escrowEndsAt} set) — late only once Steam's own
     *      release time has passed plus {@link #holdGraceHours}. This is the
     *      case that the flat 24h constant got catastrophically wrong: Steam
     *      holds for 7-15 days, so hour 24 of a hold is not late, it is early.
     *
     *   2. Accepted, hold unknown ({@code depositAcceptedAt} set) — NEVER late,
     *      returns {@link Long#MAX_VALUE}. The item has left the seller's
     *      inventory and this row is the only record of where it went. There is
     *      no deadline at which throwing that record away becomes the right
     *      move, so there is no deadline. The overdue-hold sweep escalates
     *      these instead; it never fails them.
     *
     *   3. Neither ({@code createdAt} only) — the ordinary unheld deadline,
     *      {@link #depositTimeoutHours} after the deposit was requested. The
     *      seller still holds the item, so giving up costs nothing but a
     *      listing, and the pre-existing 24h behaviour is exactly right.
     */
    long depositDeadlineFor(EscrowedItem e) {
        if (e == null) return Long.MAX_VALUE
        if (e.escrowEndsAt != null) {
            return e.escrowEndsAt + Math.max(0L, (long) holdGraceHours) * 60L * 60L * 1000L
        }
        if (e.depositAcceptedAt != null) return Long.MAX_VALUE
        long created = e.createdAt != null ? e.createdAt : System.currentTimeMillis()
        return created + Math.max(1L, (long) depositTimeoutHours) * 60L * 60L * 1000L
    }

    /** True when {@code now} is past {@link #depositDeadlineFor}. The sweeps'
     *  SQL does the coarse selection; this is the authoritative per-row gate
     *  applied before anything irreversible happens to the row. */
    boolean isDepositOverdue(EscrowedItem e, long now = System.currentTimeMillis()) {
        return now > depositDeadlineFor(e)
    }

    /** Human-readable release date for a hold, for seller-facing copy. UTC so
     *  the sentence is unambiguous rather than rendered in the server's
     *  incidental timezone. */
    static String formatHoldRelease(Long escrowEndsAtMillis) {
        if (escrowEndsAtMillis == null) return null
        return java.time.Instant.ofEpochMilli(escrowEndsAtMillis)
                .atZone(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern('d MMM yyyy')) + ' (UTC)'
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
        if (e == null) return
        if (e.custodyState != EscrowedItem.PENDING_DEPOSIT
                && e.custodyState != EscrowedItem.IN_ESCROW_HOLD) return
        if (e.depositOfferId == null || e.depositOfferId.trim().isEmpty()) return

        SteamBotResult res = steamTradeBotService.getOfferStatus(e.depositOfferId)
        if (!res.ok) {
            touchError(e, "poll ${res.errorCode}: ${res.message}")
            return
        }

        // Record the hold BEFORE branching on anything else. Steam attaches
        // escrowEnds to the offer as soon as the hold exists, which can be
        // while the offer still reads `in_escrow` rather than `accepted` — so
        // reading it only on the accepted branch would miss the entire window
        // the value is most needed in. Persisting it here is also what stops
        // the timeout sweeper from failing the row (findStalePendingDeposits
        // requires escrowEndsAt IS NULL), so this assignment is the actual
        // mechanism that prevents the orphaning, not merely a record of it.
        Long holdEnds = readEscrowEndsMillis(res)
        boolean newHold = holdEnds != null && e.escrowEndsAt == null
        if (holdEnds != null) e.escrowEndsAt = holdEnds

        if (res.terminalFailure) {
            // Seller declined / let the deposit offer expire.
            //
            // Only safe to fail this row while the item is still the seller's.
            // Once depositAcceptedAt is set the asset has left their inventory,
            // and a terminal status arriving after that is a contradiction we
            // must not resolve by throwing away the only pointer to a real
            // item. Keep the row, shout, and let the overdue-hold sweep and an
            // operator work out where it went.
            if (e.depositAcceptedAt != null) {
                touchError(e, "offer reported ${res.status} AFTER acceptance — item location unconfirmed")
                log.error("SteamEscrow: deposit offer ${e.depositOfferId} for listing ${e.listingId} " +
                        "reported terminal (${res.status}) AFTER it was accepted — the item has already left " +
                        "seller ${e.sellerUserId}. Row deliberately NOT failed: custody=${e.custodyState} " +
                        "escrow=${e.id} asset=${e.assetId}. Manual reconciliation required.")
                return
            }
            // The item never moved — cancelling costs the seller a listing and
            // nothing else. Mark FAILED and leave the listing in PENDING_ESCROW
            // (a separate staff/seller action can cancel it).
            e.custodyState = EscrowedItem.FAILED
            e.lastError = "deposit offer ${res.status}"
            touch(e)
            log.warn("SteamEscrow: deposit offer ${e.depositOfferId} for listing ${e.listingId} is terminal (${res.status})")
            safeNotifySeller(e.sellerUserId, e.listingId,
                    "Your deposit trade offer was ${res.status}, so your listing didn't go live. Re-list to try again.")
            return
        }

        // `in_escrow` — Steam has the item and is holding it. The seller has
        // already parted with it, so this row must never again be a candidate
        // for the give-up path, and the seller must be told what is happening
        // rather than watching a listing sit "pending" for a fortnight.
        if (res.inEscrow || (holdEnds != null && !res.accepted)) {
            enterEscrowHold(e, holdEnds, newHold)
            return
        }

        if (!res.accepted) {
            // active / pending — the seller has not acted yet. Item is still
            // theirs; the ordinary 24h unheld deadline applies.
            if (newHold) touch(e)
            return
        }

        // From here the offer is ACCEPTED: the item is out of the seller's
        // inventory for good. Stamp that fact before anything else can fail,
        // because it is what disqualifies this row from ever being timed out.
        if (e.depositAcceptedAt == null) e.depositAcceptedAt = System.currentTimeMillis()

        // Offer accepted. Confirm the bot ACTUALLY holds the asset before we
        // make the listing buyable — the offer being "accepted" plus the asset
        // appearing in the bot inventory is the platform's proof of custody.
        Map index = botInventory != null ? botInventory : fetchBotInventoryIndex()
        String held = resolveHeldAssetId(e, index)
        if (held == null) {
            // Accepted, but the asset is not in the bot's inventory: a Steam
            // trade hold, ordinary propagation lag, or a transient inventory
            // fetch failure. All three mean the same thing about custody — the
            // item has left the seller and the bot cannot yet touch it — so all
            // three go to IN_ESCROW_HOLD.
            //
            // Parking it here rather than leaving it PENDING_DEPOSIT is what
            // gives it a watcher: PENDING_DEPOSIT rows with the item already
            // gone are excluded from the timeout sweep (correctly — failing
            // them orphans the item) but were then in NO sweep's candidate set
            // at all, which is the same silence one state to the left.
            // IN_ESCROW_HOLD is polled until the asset lands and escalated if
            // it never does.
            enterEscrowHold(e, holdEnds, newHold)
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

    /**
     * Move a deposit into {@link EscrowedItem#IN_ESCROW_HOLD}: the seller has
     * parted with the item and the bot cannot use it yet.
     *
     * Stamps {@code depositAcceptedAt} if it is not already set — that stamp,
     * not the state, is what permanently disqualifies the row from the give-up
     * path, so it must survive even if the state is later changed by hand.
     *
     * The seller is notified ONCE per hold, and only when there is a real
     * release date to tell them. A hold is the single most alarming thing that
     * can happen to a first-time seller — their item has vanished from their
     * inventory and their listing is not live — and the difference between
     * "pending" and "in a Steam trade hold until 14 March, then it goes live
     * automatically" is the difference between a support ticket and a shrug.
     * When there is no known release date we stay quiet rather than inventing
     * one: an ordinary few-second inventory propagation lag also lands here,
     * and notifying on that would train the seller to ignore the message that
     * matters.
     */
    private void enterEscrowHold(EscrowedItem e, Long holdEnds, boolean newHold) {
        boolean firstEntry = e.custodyState != EscrowedItem.IN_ESCROW_HOLD
        if (e.depositAcceptedAt == null) e.depositAcceptedAt = System.currentTimeMillis()
        e.custodyState = EscrowedItem.IN_ESCROW_HOLD
        String release = formatHoldRelease(e.escrowEndsAt)
        e.lastError = release != null
                ? "Steam trade hold — item releases ${release}".toString()
                : 'item accepted by Steam; awaiting arrival in bot inventory'
        touch(e)
        if (firstEntry || newHold) {
            log.info("SteamEscrow: listing ${e.listingId} deposit is IN_ESCROW_HOLD " +
                    "(escrow=${e.id} seller=${e.sellerUserId} releases=${release ?: 'unknown'})")
        }
        if (release != null && (firstEntry || newHold)) {
            safeNotifySeller(e.sellerUserId, e.listingId,
                    "Steam is holding your item until ${release} — this is Steam's trade hold, not us. " +
                    "We already have the trade; your listing goes live automatically the moment the hold ends. " +
                    "Adding the Steam Mobile Authenticator removes this delay on future trades.")
        }
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
        long now = System.currentTimeMillis()
        for (EscrowedItem e : stale) {
            try {
                // Authoritative per-row gate. The SQL above already excludes
                // accepted / held rows, but this is the money question — "is
                // giving up on this row safe?" — and it is answered here, in
                // one place, by the same helper the tests pin. A row that
                // slipped through the query (an escrowEndsAt written between
                // the read and now, a hand-edited row) is dropped rather than
                // failed. Cheap, and the failure mode it guards is losing a
                // real item.
                if (!isDepositOverdue(e, now)) {
                    log.debug("SteamEscrow: escrow=${e.id} matched the stale query but is not overdue " +
                            "(deadline=${depositDeadlineFor(e)}) — skipping")
                    continue
                }
                if (e.depositAcceptedAt != null || e.escrowEndsAt != null) {
                    // Belt and braces on the one mistake that costs an item.
                    log.warn("SteamEscrow: refusing to time out escrow=${e.id} listing=${e.listingId} — " +
                            "the item has already left the seller (accepted=${e.depositAcceptedAt} " +
                            "holdEnds=${e.escrowEndsAt}). Handled by the escrow-hold sweep instead.")
                    continue
                }
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
    // 3a. ESCROW HOLDS — watch them, escalate them, never give up on them
    // -----------------------------------------------------------------------

    /**
     * Escalation sweep for deposits stuck inside a Steam trade hold that has
     * run past its own release time plus {@link #holdGraceHours}.
     *
     * ── This sweep has no give-up state, on purpose ───────────────────────
     * Every row it touches has {@code depositAcceptedAt} set, which means the
     * seller no longer has the item. The custody row is therefore the only
     * record of where a real person's real item went. There is no state this
     * sweep could move the row to that would improve the seller's position, and
     * exactly one that would destroy it — FAILED, which is precisely how the
     * pointer got lost in the bug this wave closes. So it logs, tells the
     * seller the truth, and leaves the row exactly where it is, for an operator
     * to act on. Same posture, for the same reason, as
     * {@link #sweepPendingReturns}.
     *
     * The confirm poller keeps polling these rows the whole time
     * ({@code findPendingDeposits} includes IN_ESCROW_HOLD), so the ordinary
     * happy ending — the hold expires, the asset appears, custody flips
     * IN_CUSTODY and the listing goes live — needs nothing from this sweep at
     * all. This only fires when that did NOT happen, which is exactly when a
     * human needs to know.
     *
     * NOT @Transactional — per-row atomic claims + per-row try/catch, the same
     * posture as the other sweeps.
     */
    @Scheduled(initialDelayString = '${steam.escrow.hold-escalation-initial-delay-ms:120000}',
               fixedDelayString = '${steam.escrow.hold-escalation-interval-ms:3600000}')
    void sweepOverdueEscrowHolds() {
        if (!escrowSweepEnabled) return
        if (!escrowEnabled) return
        long now = System.currentTimeMillis()
        long releasedBefore = now - Math.max(0L, (long) holdGraceHours) * 60L * 60L * 1000L
        long backoffCutoff = now - Math.max(0L, holdEscalationBackoffMs)
        List<EscrowedItem> overdue
        try {
            overdue = escrowRepository.findOverdueEscrowHolds(releasedBefore, backoffCutoff,
                    PageRequest.of(0, Math.max(1, batchSize))) ?: []
        } catch (Exception ex) {
            log.warn("SteamEscrow: failed to load overdue escrow holds: ${ex.message}")
            return
        }
        if (overdue.isEmpty()) return
        log.warn("SteamEscrow: ${overdue.size()} deposit(s) past their Steam trade-hold release and still not in the bot inventory")

        for (EscrowedItem e : overdue) {
            try {
                String release = formatHoldRelease(e.escrowEndsAt)
                String reason = release != null
                        ? "Steam hold ended ${release} but the item has not arrived — under investigation".toString()
                        : 'item accepted but never arrived in the bot inventory — under investigation'
                int claimed = escrowRepository.claimOverdueHoldEscalation(
                        e.id, reason, e.updatedAt, now)
                if (claimed == 0) {
                    log.debug("SteamEscrow: hold-escalation claim lost for escrow=${e.id} — sibling pod, or the row just advanced")
                    continue
                }
                // ERROR, with every id an operator needs to chase it by hand.
                // The row is deliberately left IN_ESCROW_HOLD — see the method
                // javadoc; this is a real user's item and the platform is the
                // party that has it.
                log.error("SteamEscrow: deposit STILL not received past its Steam trade hold — " +
                        "escrow=${e.id} listing=${e.listingId} seller=${e.sellerUserId} " +
                        "asset=${e.assetId} offer=${e.depositOfferId} " +
                        "holdEnded=${release ?: 'unknown'} acceptedAt=${e.depositAcceptedAt}. " +
                        "The seller no longer has this item. Custody row left IN_ESCROW_HOLD on purpose " +
                        "(failing it would delete the only pointer to the item); manual reconciliation required.")
                safeNotifySeller(e.sellerUserId, e.listingId,
                        "Your item's Steam trade hold has ended but we haven't received it yet. " +
                        "We're looking into it — your listing is still reserved and we have a record of the trade. " +
                        "Nothing is lost; contact support if you'd like an update.")
            } catch (Exception ex) {
                log.warn("SteamEscrow: hold escalation failed on escrow=${e?.id}: ${ex.message}")
            }
        }
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

        // A deposit inside a Steam trade hold cannot be handed back yet — the
        // bot does not have the item, Steam does, and no trade offer the bot
        // sends can move it before the hold expires. But the seller has asked
        // for it back, and that ask must not evaporate just because it arrived
        // during the one window where it cannot be actioned.
        //
        // Stamping returnRequestedAt now makes the existing return machinery do
        // the rest for free: when the hold expires, confirmDeposit promotes the
        // row IN_ESCROW_HOLD -> IN_CUSTODY, at which point it matches
        // findPendingReturns (IN_CUSTODY AND returnRequestedAt IS NOT NULL) and
        // sweepPendingReturns mails it straight back out to the seller.
        // activateListing only touches PENDING_ESCROW rows, so the listing the
        // seller just cancelled is not resurrected on the way through.
        if (e.custodyState == EscrowedItem.IN_ESCROW_HOLD) {
            if (e.returnRequestedAt == null) {
                e.returnRequestedAt = System.currentTimeMillis()
                touch(e)
            }
            String release = formatHoldRelease(e.escrowEndsAt)
            log.info("SteamEscrow: return requested for listing ${listingId} while its deposit is in a Steam " +
                    "trade hold (escrow=${e.id} releases=${release ?: 'unknown'}) — queued; the item goes back " +
                    "to seller ${e.sellerUserId} automatically once the hold clears (${reason})")
            safeNotifySeller(e.sellerUserId, listingId, release != null
                    ? "Your listing is cancelled. Steam is still holding the item until ${release}; we'll send it " +
                      "straight back to you as soon as the hold ends — you don't need to do anything."
                    : "Your listing is cancelled. Steam is still holding the item; we'll send it straight back to " +
                      "you as soon as the hold ends — you don't need to do anything.")
            return false
        }

        // Only items the bot actually holds can be returned. DELIVERED (sent to
        // a buyer) and already-RETURNED rows are terminal.
        if (e.custodyState != EscrowedItem.IN_CUSTODY) return false

        // Record that a return was ASKED FOR, before attempting it — this is
        // what makes a failure recoverable. Every call site of this method is
        // best-effort and discards the boolean, so if the attempt below fails
        // (or throws) the ONLY durable trace that the seller is owed their item
        // back is this stamp. Without it the row reverts to looking exactly
        // like a live for-sale listing and no sweep can distinguish the two.
        // Stamped once — a retry must not keep pushing the "first asked" time
        // forward, or the age of a stranded item would be permanently understated.
        if (e.returnRequestedAt == null) {
            e.returnRequestedAt = System.currentTimeMillis()
            touch(e)
        }
        return attemptReturn(e, reason)
    }

    /**
     * Send the actual return offer for a custody row whose return intent is
     * already recorded. Shared by the first attempt ({@link #returnToSeller})
     * and every retry ({@link #sweepPendingReturns}) so the two can never drift
     * — the retry path must send the same offer, to the same freshly-resolved
     * trade URL, and apply the same terminal RETURNED flip.
     *
     * Re-resolves the seller's trade URL on every attempt rather than caching
     * it: the single most likely reason a first attempt failed is that the
     * seller had no trade URL, and the fix for that is the seller pasting one
     * in. A retry that reused the stale answer could never observe the fix.
     */
    private boolean attemptReturn(EscrowedItem e, String reason) {
        Long listingId = e.listingId
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
            // Leave IN_CUSTODY. returnRequestedAt (stamped by returnToSeller
            // before the first attempt) keeps the row in the retry sweep's
            // candidate set, so "a later tick" is now a real thing rather than
            // an aspiration in a comment.
            touchError(e, "return failed ${res.errorCode}: ${res.message}")
            log.warn("SteamEscrow: return offer failed for listing ${listingId}: ${res.errorCode} ${res.message}")
            return false
        }
    }

    /**
     * Retry sweep for returns that were asked for and did not happen — the
     * safety net that stops a transient bot failure from permanently keeping a
     * real user's real item.
     *
     * The candidate set is {@code IN_CUSTODY AND returnRequestedAt IS NOT NULL}.
     * That second clause is load-bearing, not decorative: IN_CUSTODY is the
     * healthy state of every live for-sale listing, so sweeping IN_CUSTODY
     * alone would mail every seller's item back out from under their own active
     * listing. On a healthy marketplace this query returns nothing.
     *
     * Unlike the deposit-timeout sweep there is no give-up state. A deposit
     * that never lands costs the seller a cancelled listing; a return that
     * never lands costs them the ITEM, and the platform is holding it. So the
     * sweep keeps trying, and past {@code return-alert-attempts} it escalates
     * the log to ERROR with the ids an operator needs — it never silently
     * stops.
     *
     * NOT @Transactional — per-row atomic claims + per-row try/catch, the same
     * posture as {@link #sweepStalePendingDeposits}, so one bad row can't abort
     * the sweep or roll back its siblings' attempt counters.
     */
    @Scheduled(initialDelayString = '${steam.escrow.return-retry-initial-delay-ms:90000}',
               fixedDelayString = '${steam.escrow.return-retry-interval-ms:600000}')
    void sweepPendingReturns() {
        if (!escrowSweepEnabled) return
        if (!escrowEnabled) return
        long cutoff = System.currentTimeMillis() - Math.max(0L, returnRetryBackoffMs)
        List<EscrowedItem> pending
        try {
            pending = escrowRepository.findPendingReturns(cutoff,
                    PageRequest.of(0, Math.max(1, batchSize))) ?: []
        } catch (Exception ex) {
            log.warn("SteamEscrow: failed to load pending returns: ${ex.message}")
            return
        }
        if (pending.isEmpty()) return
        log.info("SteamEscrow: ${pending.size()} item(s) owed back to sellers and still held; retrying returns")

        for (EscrowedItem e : pending) {
            try {
                // Multi-pod claim. The row stays IN_CUSTODY across a retry, so
                // there is no state transition to claim on — the attempt
                // counter is the compare-and-swap token instead. A lost claim
                // means a sibling pod is sending this return right now; sending
                // a second offer for the same asset is a real, user-visible
                // mess, so we bail rather than double-send.
                int claimed = escrowRepository.claimReturnRetry(
                        e.id, e.returnAttempts ?: 0, System.currentTimeMillis())
                if (claimed == 0) {
                    log.debug("SteamEscrow: return-retry claim lost for escrow=${e.id} — sibling pod, or the row just RETURNED")
                    continue
                }
                // Re-load after the claim. claimReturnRetry is a @Modifying
                // bulk UPDATE, which writes straight past the persistence
                // context — the `e` we are holding still carries the OLD
                // returnAttempts, and attemptReturn ends in a save(). Saving
                // the stale instance would silently roll the counter back to
                // its pre-claim value, so the next tick would re-claim with the
                // same expected value and the CAS would never advance: an
                // infinite retry loop that also defeats the escalation
                // threshold, since attempts would never climb.
                EscrowedItem fresh = escrowRepository.findById(e.id).orElse(null)
                if (fresh == null || fresh.custodyState != EscrowedItem.IN_CUSTODY) continue

                boolean sent = attemptReturn(fresh,
                        "retry ${fresh.returnAttempts} — item owed back to seller".toString())
                if (!sent && (fresh.returnAttempts ?: 0) >= returnAlertAttempts) {
                    // Escalate rather than give up. An operator can read this
                    // line and act on it by hand; the row stays in the sweep.
                    log.error("SteamEscrow: item STILL HELD after ${fresh.returnAttempts} return attempts — " +
                            "escrow=${fresh.id} listing=${fresh.listingId} seller=${fresh.sellerUserId} " +
                            "asset=${fresh.heldAssetId ?: fresh.assetId} lastError=${fresh.lastError}. " +
                            "This is a real user's item held by the platform; manual intervention required.")
                }
            } catch (Exception ex) {
                log.warn("SteamEscrow: return retry failed on escrow=${e?.id}: ${ex.message}")
            }
        }
    }

    /**
     * Re-request sweep for deposits whose trade offer never got created.
     *
     * {@link #pollDeposits} filters on {@code depositOfferId IS NOT NULL}, so a
     * row whose bot call failed at list time — or whose seller had no trade URL
     * to send a request to — was returned by NO poller. The only thing that
     * ever happened to it was {@link #sweepStalePendingDeposits} cancelling the
     * listing 24 hours later. A seller who pasted their trade URL a minute
     * after listing still lost the listing, because nothing was watching for
     * the fix.
     *
     * This sweep re-resolves the trade URL and re-sends the deposit request, so
     * both failure shapes self-heal: the bot recovers, or the seller does. The
     * 24h timeout stays as the backstop for a deposit nobody ever fixes.
     *
     * NOT @Transactional — per-row claims + per-row try/catch, same posture as
     * the other two sweeps.
     */
    @Scheduled(initialDelayString = '${steam.escrow.deposit-retry-initial-delay-ms:75000}',
               fixedDelayString = '${steam.escrow.deposit-retry-interval-ms:300000}')
    void sweepUnsentDeposits() {
        if (!escrowSweepEnabled) return
        if (!escrowEnabled) return
        long cutoff = System.currentTimeMillis() - Math.max(0L, depositRetryBackoffMs)
        List<EscrowedItem> unsent
        try {
            unsent = escrowRepository.findUnsentDeposits(cutoff,
                    PageRequest.of(0, Math.max(1, batchSize))) ?: []
        } catch (Exception ex) {
            log.warn("SteamEscrow: failed to load unsent deposits: ${ex.message}")
            return
        }
        if (unsent.isEmpty()) return
        log.info("SteamEscrow: ${unsent.size()} deposit(s) with no trade offer yet; re-requesting")

        for (EscrowedItem e : unsent) {
            try {
                String tradeUrl = resolveSellerTradeUrl(e.sellerUserId)
                if (tradeUrl == null || tradeUrl.trim().isEmpty()) {
                    // Still no address to send to. Touch the row so the backoff
                    // window applies and this doesn't spin every tick; the 24h
                    // timeout sweeper remains the give-up path.
                    touchError(e, 'seller has no Steam trade URL — set one in Profile to deposit')
                    continue
                }
                // Multi-pod claim, CAS on updatedAt. The side effect is a real
                // Steam trade offer landing in a seller's client — two pods
                // racing here would send the seller two identical deposit
                // requests for one listing, and accepting both is impossible,
                // so one of them would sit in their offer list as garbage.
                int claimed = escrowRepository.claimDepositRetry(
                        e.id, e.updatedAt, System.currentTimeMillis())
                if (claimed == 0) {
                    log.debug("SteamEscrow: deposit re-request claim lost for escrow=${e.id} — sibling pod")
                    continue
                }
                EscrowedItem fresh = escrowRepository.findById(e.id).orElse(null)
                if (fresh == null
                        || fresh.custodyState != EscrowedItem.PENDING_DEPOSIT
                        || fresh.depositOfferId != null) continue

                SteamBotResult res = steamTradeBotService.requestItems(
                        tradeUrl, [(fresh.assetId ?: '').trim()], offerMessage)
                if (res.ok) {
                    fresh.depositOfferId = res.offerId
                    fresh.lastError = null
                    touch(fresh)
                    log.info("SteamEscrow: deposit re-requested for listing ${fresh.listingId} (offer ${res.offerId})")
                    safeNotifySeller(fresh.sellerUserId, fresh.listingId,
                            "Accept the Steam trade offer from our bot to deposit your item — your listing goes live the moment we receive it.")
                } else {
                    touchError(fresh, "re-request ${res.errorCode}: ${res.message}")
                    log.warn("SteamEscrow: deposit re-request failed for listing ${fresh.listingId}: ${res.errorCode} ${res.message}")
                }
            } catch (Exception ex) {
                log.warn("SteamEscrow: deposit re-request failed on escrow=${e?.id}: ${ex.message}")
            }
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

    /**
     * Seller-facing custody explanation for a set of held listings, keyed by
     * listing id — what is actually happening to their item, and when it ends.
     *
     * ── Why this exists ──────────────────────────────────────────────────
     * A PENDING_ESCROW listing is invisible to every other seller-facing query,
     * and the one endpoint that does return them
     * ({@code GET /api/listings/my-stall/pending-escrow}) returned bare Listing
     * rows carrying no custody information at all. So a seller in a 15-day
     * Steam trade hold saw, at best, a listing that was not live, with no
     * reason and no end date — indistinguishable from one that had silently
     * failed. Their item was gone from their Steam inventory at the same time.
     *
     * That is this operator's most expensive recurring defect (absence rendered
     * as success, or here as an unexplained nothing), on the single most
     * alarming event in the sell flow. Every row below carries a state, a
     * human-readable sentence, and — when Steam has told us one — a concrete
     * release date.
     *
     * Never throws: a failure to explain a listing must not take down the
     * seller's stall view. Returns an empty map when escrow is disabled, which
     * is correct — nothing is ever created in PENDING_ESCROW on the legacy path.
     */
    Map<Long, Map> custodyViewForListings(Collection<Long> listingIds) {
        Map<Long, Map> out = [:]
        if (!escrowEnabled || listingIds == null || listingIds.isEmpty()) return out
        List<EscrowedItem> rows
        try {
            rows = escrowRepository.findByListingIds(listingIds) ?: []
        } catch (Exception ex) {
            log.warn("SteamEscrow: custody view lookup failed: ${ex.message}")
            return out
        }
        long now = System.currentTimeMillis()
        for (EscrowedItem e : rows) {
            if (e?.listingId == null) continue
            String release = formatHoldRelease(e.escrowEndsAt)
            boolean holding = e.custodyState == EscrowedItem.IN_ESCROW_HOLD
            String message
            switch (e.custodyState) {
                case EscrowedItem.IN_ESCROW_HOLD:
                    message = release != null
                        ? "Steam is holding this item until ${release}. This is Steam's own trade hold, not a " +
                          "problem with your listing — it goes live automatically when the hold ends."
                        : "We've received your trade and are waiting for Steam to release the item. Your listing " +
                          "goes live automatically once it arrives."
                    break
                case EscrowedItem.PENDING_DEPOSIT:
                    message = e.depositOfferId != null
                        ? 'Waiting for you to accept our bot\'s Steam trade offer — your listing goes live the moment we receive the item.'
                        : 'We haven\'t been able to send you a trade offer yet. Check that your Steam trade URL is set in Profile.'
                    break
                case EscrowedItem.FAILED:
                    message = 'This deposit didn\'t complete, so the listing never went live. You still have the item — re-list to try again.'
                    break
                case EscrowedItem.IN_CUSTODY:
                    message = 'We have your item; this listing should be live.'
                    break
                default:
                    message = "Custody state: ${e.custodyState}".toString()
            }
            out[e.listingId] = [
                custodyState:    e.custodyState,
                inSteamHold:     holding,
                // Machine-readable release instant alongside the sentence, so a
                // client can render a live countdown rather than re-parsing prose.
                holdReleasesAt:  e.escrowEndsAt,
                holdReleasesOn:  release,
                holdOverdue:     holding && e.escrowEndsAt != null && now > depositDeadlineFor(e),
                itemLeftSeller:  e.depositAcceptedAt != null,
                returnQueued:    e.returnRequestedAt != null,
                message:         message
            ]
        }
        return out
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
                def saved = listingRepository.save(l)
                // Saved-search matches are skipped while the listing waits
                // on the deposit, so this is the moment they're true.
                try { savedSearchService?.notifyMatchingForListing(saved) }
                catch (Exception e) { log.warn("SteamEscrow: saved-search fanout failed for listing ${listingId}: ${e.message}") }
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
