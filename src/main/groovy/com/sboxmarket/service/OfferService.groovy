package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.InsufficientBalanceException
import com.sboxmarket.exception.ListingNotAvailableException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.exception.OfferNotPendingException
import com.sboxmarket.model.Listing
import com.sboxmarket.model.Offer
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.OfferRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.security.BanGuard
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Single-responsibility: handles the OFFER lifecycle.
 *
 * Flow:
 *   Buyer makes offer below asking price → PENDING
 *   Seller can ACCEPT → triggers a real PurchaseService.buy at the offered amount
 *                          (cancels any other pending offers on the same listing)
 *                       REJECT → marks REJECTED
 *   Buyer can withdraw → CANCELLED (only while PENDING)
 *
 * Depends on PurchaseService for the actual money movement —
 * Dependency Inversion: OfferService doesn't know how purchases happen,
 * it just calls the service.
 */
@Service
@Slf4j
class OfferService {

    @Autowired OfferRepository offerRepository
    @Autowired ListingRepository listingRepository
    @Autowired WalletRepository walletRepository
    @Autowired(required = false) com.sboxmarket.repository.TransactionRepository transactionRepository
    @Autowired SteamUserRepository steamUserRepository
    @Autowired PurchaseService purchaseService
    @Autowired BanGuard banGuard
    @Autowired TextSanitizer textSanitizer
    @Autowired(required = false) NotificationService notificationService
    @Autowired(required = false) UserBlockService userBlockService
    @Autowired(required = false) EmailService emailService
    @Autowired(required = false) com.sboxmarket.repository.TradeRepository tradeRepository
    @Autowired(required = false) ReviewService reviewService

    /** Sweeper window for auto-declining idle offers. Defaults to 7 days —
     *  same as CSFloat's offer-expiry policy. Configurable so ops can
     *  shorten to 24h during pricing incidents without a redeploy. */
    @Value('${offer.auto-decline-days:7}') long autoDeclineDays

    /** Cap on the optional buyer-supplied note attached to an offer.
     *  Mirrors the Flyway V43 column width — 280 chars (Twitter-length).
     *  Long enough for a real explanation, short enough that the seller's
     *  inbox row stays readable without truncation. */
    static final int MESSAGE_MAX_LEN = 280

    /** Per-buyer PENDING offer cap. A serious buyer shopping in bulk
     *  might have 20-30 offers open across listings; 100 is generous
     *  headroom while still protecting sellers from spam-offer floods
     *  and the per-listing offer-thread UI from unbounded growth.
     *  ACCEPTED / REJECTED / WITHDRAWN / EXPIRED offers don't count —
     *  the cap is purely on the live queue the buyer is juggling. */
    static final long MAX_PENDING_PER_BUYER = 100L

    @Transactional
    Offer makeOffer(Long buyerUserId, String buyerName, Long listingId, BigDecimal amount,
                    String message = null) {
        banGuard.assertNotBanned(buyerUserId)
        if (buyerUserId != null
                && offerRepository.countPendingByBuyer(buyerUserId) >= MAX_PENDING_PER_BUYER) {
            throw new BadRequestException("OFFER_CAP",
                "You've reached the ${MAX_PENDING_PER_BUYER}-pending-offer cap. " +
                "Resolve one from Profile → Offers → Outgoing before making another.")
        }
        // Wallet freeze gate (batch 510). Rejects at offer-creation time
        // instead of letting the offer sit PENDING and fail at acceptance
        // — the seller would see the cryptic WALLET_FROZEN error from
        // PurchaseService.buy at acceptOffer time and have to chase the
        // buyer. Failing early keeps the seller's queue clean. Best-
        // effort lookup: if the wallet row doesn't exist yet, skip the
        // check (the buyer hasn't interacted with the wallet flow yet,
        // which means they're not the frozen target of staff action).
        def buyerUser = steamUserRepository?.findById(buyerUserId)?.orElse(null)
        if (buyerUser != null) {
            def buyerWallet = walletRepository.findByUsername("steam_${buyerUser.steamId64}")
            if (buyerWallet != null && Boolean.TRUE.equals(buyerWallet.frozen)) {
                throw new BadRequestException("WALLET_FROZEN",
                    "Your wallet is frozen by staff" +
                        (buyerWallet.frozenReason ? ": ${buyerWallet.frozenReason}" : '') +
                        ". Open a support ticket to resolve.")
            }
            // Dispute-hold fail-early (batch 511). Mirrors the check
            // PurchaseService.buy does at offer acceptance time — stops
            // the offer landing in the seller's queue only to fail
            // cryptically with PURCHASE_DISPUTE_HOLD when they try to
            // accept. Null-safe: transactionRepository is @Autowired
            // optional for legacy specs with mocked collaborators.
            if (buyerWallet != null && transactionRepository != null) {
                long disputed = transactionRepository.countActiveDisputedDeposits(buyerWallet.id)
                if (disputed > 0L) {
                    throw new BadRequestException("PURCHASE_DISPUTE_HOLD",
                        "Offers are paused while you have ${disputed} unresolved deposit " +
                        "dispute${disputed == 1 ? '' : 's'} on file.")
                }
            }
        }
        buyerName = textSanitizer.cleanShort(buyerName)
        // Optional buyer note. Sanitised + capped server-side so the
        // seller's offer row never renders raw user input. Empty string
        // collapses to null (clean DB). Length-check the RAW input first
        // because textSanitizer.clean silently truncates to its cap —
        // pre-fix used cleanShort (LIMIT_SHORT=80) so a 100-char note
        // was silently chopped to 80 even though the column + docs +
        // MESSAGE_MAX_LEN all advertise 280. Check raw length first to
        // surface MESSAGE_TOO_LONG (better UX than silent truncation),
        // then sanitise with the correct MESSAGE_MAX_LEN cap.
        String cleanMessage = null
        if (message != null) {
            if (message.length() > MESSAGE_MAX_LEN) {
                throw new BadRequestException("MESSAGE_TOO_LONG",
                    "Offer message must be ${MESSAGE_MAX_LEN} characters or fewer.")
            }
            def trimmed = textSanitizer.clean(message, MESSAGE_MAX_LEN)
            if (trimmed) cleanMessage = trimmed
        }
        if (amount == null || amount <= BigDecimal.ZERO) {
            throw new BadRequestException("INVALID_OFFER", "Offer must be greater than 0")
        }
        // Defense-in-depth cap mirroring the DTO layer. Keeps services
        // safe when invoked directly from another service or test.
        if (amount > new BigDecimal("100000")) {
            throw new BadRequestException("OFFER_TOO_HIGH", "Offer must not exceed \$100,000")
        }
        def listing = listingRepository.findById(listingId)
                .orElseThrow { new NotFoundException("Listing", listingId) }
        if (listing.status != 'ACTIVE') {
            throw new ListingNotAvailableException(listingId)
        }
        // Hidden (per-listing or vacation-mode) listings are
        // invisible to the public grid but the listing id is stable
        // and guessable. Reject offer attempts on hidden rows so a
        // stale client (or a crafted /api/offers POST with a
        // scraped listing id) can't bypass the visibility rule.
        if (Boolean.TRUE.equals(listing.hidden)) {
            throw new ListingNotAvailableException(listingId)
        }
        // Offers only make sense for BUY_NOW listings — AUCTION listings
        // have their own bidding surface. Without this guard the offer
        // row lands in the DB in PENDING state forever (acceptOffer
        // would later fail on the PurchaseService auction guard) and
        // clogs both the buyer's outgoing offers and the seller's
        // incoming queue.
        if (listing.listingType == 'AUCTION') {
            throw new BadRequestException("NOT_BUY_NOW",
                "This is an auction listing — place a bid instead of making an offer")
        }
        if (listing.sellerUserId != null && listing.sellerUserId == buyerUserId) {
            throw new ForbiddenException("You can't offer on your own listing")
        }
        // Block-list enforcement (batch 343). Symmetric — if either the
        // buyer has blocked the seller OR the seller has blocked the
        // buyer, the offer path is closed. The error message is generic
        // (NOT_AVAILABLE / same as hidden-listing) so neither side can
        // enumerate the other's block list via the response code.
        if (listing.sellerUserId != null && userBlockService != null) {
            if (userBlockService.isBlocked(listing.sellerUserId, buyerUserId)
                    || userBlockService.isBlocked(buyerUserId, listing.sellerUserId)) {
                throw new ListingNotAvailableException(listingId)
            }
        }
        if (amount >= listing.price) {
            throw new BadRequestException("OFFER_TOO_HIGH",
                "Offer must be below the asking price (\$${listing.price}) — use Buy Now instead")
        }
        // Duplicate-offer guard (batch 438). Without this a buyer can spam
        // the seller with N PENDING offers at different amounts on the
        // same listing, flooding the incoming queue and making it
        // ambiguous which one the seller should accept. The canonical
        // flow is: one active offer per (buyer, listing) pair; to change
        // the amount, call buyerRaise. Raise supports only upward moves,
        // which is the intended behaviour — going lower is effectively a
        // withdraw + re-offer, so callers cancel the existing PENDING
        // first. Matches CSFloat's per-listing offer thread semantics.
        // Null-safe: Spock mocks return null when a method isn't stubbed;
        // treat that as "no existing offers" so existing service specs
        // don't need to stub this call explicitly.
        def existingLive = offerRepository.findLiveByBuyerAndListing(buyerUserId, listingId) ?: []
        if (!existingLive.isEmpty()) {
            throw new BadRequestException("OFFER_ALREADY_PENDING",
                "You already have an offer on this listing — raise your existing offer instead of creating a new one.")
        }
        // Same buyer-trade-URL gate as PurchaseService.buy — an accepted
        // offer auto-debits through PurchaseService.buy, so without this
        // check the offer can be accepted by the seller and then strand
        // them with no way to ship. Enforce at offer-creation time so the
        // buyer fixes their profile instead of making an offer that's
        // quietly unfulfillable. System listings (no sellerUserId) skip.
        if (listing.sellerUserId != null) {
            def buyer = steamUserRepository?.findById(buyerUserId)?.orElse(null)
            if (buyer != null && !buyer.tradeUrl?.trim()) {
                throw new BadRequestException("TRADE_URL_MISSING",
                    "Set your Steam trade URL in Profile before making an offer — the seller needs it to send you the item.")
            }
        }

        def offer = new Offer(
            listingId   : listingId,
            buyerUserId : buyerUserId,
            sellerUserId: listing.sellerUserId,
            amount      : amount,
            askingPrice : listing.price,
            buyerName   : buyerName,
            itemName    : listing.item?.name,
            itemImageUrl: listing.item?.imageUrl,
            itemId      : listing.item?.id,
            status      : 'PENDING',
            author      : 'USER',
            parentOfferId: null,
            message     : cleanMessage
        )
        def saved = offerRepository.save(offer)
        log.info("Offer ${saved.id} created: $buyerName offered \$${amount} on listing $listingId")
        // Auto-accept check. Sellers can set listing.maxDiscount (0..1,
        // stored as a fraction) on the stall edit page — offers at or
        // above `price * (1 - maxDiscount)` bypass the manual-accept
        // wait and complete the purchase immediately. Only fires when
        // the listing has a real sellerUserId (system listings stay
        // manual) and the seller isn't banned. Failures fall back to
        // the regular PENDING state so the buyer doesn't lose their
        // offer.
        def autoAccepted = tryAutoAccept(listing, saved, amount)
        if (autoAccepted != null) return autoAccepted
        // Seller notification + email — only fires for PENDING offers
        // (auto-accept uses the purchase flow's own seller-notify +
        // receipt path, so duplicating here would double-ring the bell).
        // System listings (sellerUserId null) have no counterparty to
        // notify. Best-effort: any failure here is logged and does not
        // roll back the offer save.
        if (listing.sellerUserId != null) {
            def preview = amount != null ? "\$${amount.toPlainString()}" : ''
            def askPart = listing.price != null ? " (asking \$${listing.price.toPlainString()})" : ''
            notificationService?.safePush(
                listing.sellerUserId,
                'OFFER_RECEIVED',
                "New offer on ${listing.item?.name ?: 'your listing'}",
                "${buyerName ?: 'A buyer'} offered ${preview}${askPart}",
                saved.id,
                '/offers'
            )
            try {
                if (emailService != null && steamUserRepository != null) {
                    def seller = steamUserRepository.findById(listing.sellerUserId).orElse(null)
                    if (emailService.canSendTo(seller, 'TRADES')) {
                        emailService.sendOfferReceived(
                            seller.email, seller.displayName, buyerName,
                            listing.item?.name, amount, listing.price, cleanMessage)
                    }
                }
            } catch (Exception e) {
                log.warn("OFFER_RECEIVED email failed for seller ${listing.sellerUserId}: ${e.message}")
            }
        }
        saved
    }

    /**
     * Shared auto-accept gate (batch follow-up).
     *
     * Returns the ACCEPTED offer snapshot when the listing's maxDiscount
     * threshold is met and the auto-accept purchase succeeds; returns
     * null in every other case (no threshold set, amount below
     * threshold, system listing, or any failure during accept) so the
     * caller falls through to the PENDING + seller-notification path.
     *
     * Previously this lived inline in makeOffer only — buyerRaise saved
     * the raised offer as PENDING and waited for manual seller action
     * even when the raise amount crossed the seller's auto-accept
     * threshold. That contradicted the seller's stated intent
     * (maxDiscount = "auto-accept offers >= threshold") and slowed
     * conversions: a buyer who initially offered $30 on a $50 ask with
     * maxDiscount=0.10 (threshold $45) and then raised to $46 sat
     * waiting for seller action that should have been bypassed.
     */
    private Offer tryAutoAccept(Listing listing, Offer saved, BigDecimal amount) {
        if (listing?.maxDiscount == null
                || listing.maxDiscount <= BigDecimal.ZERO
                || listing.sellerUserId == null) {
            return null
        }
        def threshold = (listing.price - (listing.price * listing.maxDiscount))
            .setScale(2, BigDecimal.ROUND_HALF_UP)
        if (amount < threshold) return null
        try {
            banGuard.assertNotBanned(listing.sellerUserId)
            acceptOffer(listing.sellerUserId, saved.id)
            // Reload to return the ACCEPTED snapshot.
            return offerRepository.findById(saved.id).orElse(saved)
        } catch (Exception e) {
            log.warn("Auto-accept failed for offer ${saved.id}: ${e.message} — leaving in PENDING")
            return null
        }
    }

    /**
     * Buyer raise — the buyer wants to escalate their own pending offer
     * without waiting for the seller to respond. Closes the original
     * PENDING offer with status CANCELLED (buyer-withdraw semantics, so
     * the seller's response-rate stat doesn't get polluted with a
     * never-responded-to row) and opens a new PENDING offer threaded
     * via `parentOfferId`. Only the buyer can raise; amount must be
     * strictly greater than the old offer and strictly below the
     * asking price (hitting asking = "just buy it").
     */
    @Transactional
    Offer buyerRaise(Long buyerUserId, Long originalOfferId, BigDecimal amount, String message = null) {
        banGuard.assertNotBanned(buyerUserId)
        if (amount == null || amount <= BigDecimal.ZERO) {
            throw new BadRequestException("INVALID_RAISE", "Raise amount must be greater than 0")
        }
        // Optional buyer note attached to the raise — same 280-char rule
        // as the initial makeOffer path. Empty/whitespace collapses to null.
        // Pre-sanitize length check: see makeOffer for the cleanShort vs
        // clean(_, MESSAGE_MAX_LEN) rationale.
        String cleanMessage = null
        if (message != null) {
            if (message.length() > MESSAGE_MAX_LEN) {
                throw new BadRequestException("MESSAGE_TOO_LONG",
                    "Offer message must be ${MESSAGE_MAX_LEN} characters or fewer.")
            }
            def trimmed = textSanitizer.clean(message, MESSAGE_MAX_LEN)
            if (trimmed) cleanMessage = trimmed
        }
        def original = offerRepository.findById(originalOfferId)
                .orElseThrow { new NotFoundException("Offer", originalOfferId) }
        if (original.buyerUserId != buyerUserId) {
            throw new ForbiddenException("You can only raise your own offers")
        }
        if (original.status != 'PENDING') {
            throw new OfferNotPendingException(originalOfferId, original.status)
        }
        if (amount <= original.amount) {
            throw new BadRequestException("RAISE_NOT_HIGHER",
                "A raise must be above your current offer (\$${original.amount})")
        }
        def listing = listingRepository.findById(original.listingId)
                .orElseThrow { new NotFoundException("Listing", original.listingId) }
        // A raise on a listing that's no longer ACTIVE (sold, cancelled,
        // or hidden since the offer landed) would close the buyer's
        // existing PENDING row and open a fresh PENDING offer that can
        // never be accepted — stranding the buyer worse off than before
        // the raise. Reject up-front so the buyer keeps their original
        // offer intact. Mirrors the makeOffer ACTIVE/hidden gate.
        if (listing.status != 'ACTIVE' || Boolean.TRUE.equals(listing.hidden)) {
            throw new ListingNotAvailableException(original.listingId)
        }
        if (amount >= listing.price) {
            throw new BadRequestException("RAISE_AT_OR_ABOVE_ASK",
                "At or above the ask, buy instead of offer (ask \$${listing.price})")
        }

        // Cap quantity — DTO validation bounds the initial offer at $100k;
        // a raise must stay under that too. listing.price is already
        // bounded, but guard anyway.
        if (amount > new BigDecimal("100000")) {
            throw new BadRequestException("PRICE_TOO_HIGH",
                "Offer must not exceed \$100,000")
        }

        original.status = 'CANCELLED'
        original.updatedAt = System.currentTimeMillis()
        offerRepository.save(original)
        // P1 bug fix — a buyer can raise directly on a SELLER counter
        // (the counter carries buyerUserId = the buyer and status
        // PENDING, so it passes the ownership + PENDING checks above).
        // That cancels the counter and opens a fresh PENDING offer, but
        // the buyer's grandparent original is still parked in COUNTERED.
        // Close it so the negotiation thread leaves exactly one live row
        // (the new raise) instead of a dead COUNTERED node the dup-guard
        // would still count.
        closeCounteredParent(original)

        def raised = new Offer(
            listingId    : original.listingId,
            buyerUserId  : buyerUserId,
            sellerUserId : original.sellerUserId,
            amount       : amount,
            askingPrice  : listing.price,
            buyerName    : original.buyerName,
            itemName     : original.itemName,
            itemImageUrl : original.itemImageUrl,
            itemId       : original.itemId ?: listing.item?.id,
            status       : 'PENDING',
            author       : 'USER',
            parentOfferId: original.id,
            message      : cleanMessage
        )
        def saved = offerRepository.save(raised)
        // Auto-accept the raise if it crosses listing.maxDiscount.
        // Mirrors the makeOffer auto-accept path so a buyer who raises
        // past the seller's auto-accept threshold gets the same instant
        // purchase a fresh offer at that amount would get. Without
        // this, the seller's stated intent ("auto-accept >= threshold")
        // was honoured for initial offers but ignored for raises —
        // converting buyers had to wait for manual seller action
        // anyway, defeating the feature.
        def autoAccepted = tryAutoAccept(listing, saved, amount)
        if (autoAccepted != null) return autoAccepted
        if (original.sellerUserId != null) {
            try {
                notificationService?.push(
                    original.sellerUserId,
                    'OFFER_RECEIVED',
                    "Buyer raised their offer on ${original.itemName}",
                    "New offer: \$${amount.toPlainString()} (was \$${original.amount.toPlainString()})",
                    saved.id,
                    '/offers'
                )
            } catch (Exception e) {
                log.warn("Buyer-raise notification failed for seller ${original.sellerUserId}: ${e.message}")
            }
            try {
                if (emailService != null && steamUserRepository != null) {
                    def seller = steamUserRepository.findById(original.sellerUserId).orElse(null)
                    if (emailService.canSendTo(seller, 'TRADES')) {
                        emailService.sendOfferReceived(
                            seller.email, seller.displayName, original.buyerName,
                            original.itemName, amount, listing.price, cleanMessage)
                    }
                }
            } catch (Exception e) {
                log.warn("Buyer-raise email failed for seller ${original.sellerUserId}: ${e.message}")
            }
        }
        saved
    }

    /**
     * Seller counter-offer — creates a new Offer linked to the original via
     * `parentOfferId`, flips the old one to COUNTERED, and waits for the
     * buyer to accept or counter again. Mirrors CSFloat's bargaining thread.
     */
    @Transactional
    Offer counterOffer(Long sellerUserId, Long originalOfferId, BigDecimal amount, String message = null) {
        if (amount == null || amount <= BigDecimal.ZERO) {
            throw new BadRequestException("INVALID_COUNTER", "Counter must be greater than 0")
        }
        // Optional seller note attached to the counter ("can't go lower —
        // already 30% below median"). Same 280-char rule as makeOffer.
        // Pre-fix used cleanShort (LIMIT_SHORT=80) which silently chopped a
        // 100-char counter note to 80 — the length() > MESSAGE_MAX_LEN
        // guard could then never fire, mirroring the makeOffer bug closed
        // in 4cc7288. Check raw length first, then sanitise at the correct
        // 280-char cap.
        String cleanMessage = null
        if (message != null) {
            if (message.length() > MESSAGE_MAX_LEN) {
                throw new BadRequestException("MESSAGE_TOO_LONG",
                    "Counter message must be ${MESSAGE_MAX_LEN} characters or fewer.")
            }
            def trimmed = textSanitizer.clean(message, MESSAGE_MAX_LEN)
            if (trimmed) cleanMessage = trimmed
        }
        def original = offerRepository.findById(originalOfferId)
                .orElseThrow { new NotFoundException("Offer", originalOfferId) }
        // Explicitly reject counter-offers on system listings (no
        // sellerUserId on the original). The old guard was
        // `original.sellerUserId != null && original.sellerUserId != caller`,
        // which silently let any user counter a system-seeded listing
        // and insert themselves as the seller on the new counter record.
        if (original.sellerUserId == null) {
            throw new ForbiddenException("Counter-offers on system listings are not supported — accept or reject only")
        }
        if (original.sellerUserId != sellerUserId) {
            throw new ForbiddenException("You can only counter offers on your own listings")
        }
        if (original.status != 'PENDING') {
            throw new OfferNotPendingException(originalOfferId, original.status)
        }
        def listing = listingRepository.findById(original.listingId)
                .orElseThrow { new NotFoundException("Listing", original.listingId) }
        // Reject counters on a listing that's no longer ACTIVE (sold via
        // direct Buy Now, cancelled, or hidden since the offer landed).
        // Without this the counter is saved PENDING, the buyer is invited
        // to accept it, and acceptOffer then dead-ends on the same guard
        // — a wasted round-trip that clogs the buyer's outgoing queue.
        // Mirrors the makeOffer ACTIVE/hidden gate.
        if (listing.status != 'ACTIVE' || Boolean.TRUE.equals(listing.hidden)) {
            throw new ListingNotAvailableException(original.listingId)
        }
        if (amount > listing.price) {
            throw new BadRequestException("COUNTER_TOO_HIGH",
                "Counter must not exceed the asking price (\$${listing.price})")
        }
        if (amount <= original.amount) {
            throw new BadRequestException("COUNTER_NOT_HIGHER",
                "Counter must be higher than the buyer's offer (\$${original.amount})")
        }

        original.status = 'COUNTERED'
        original.updatedAt = System.currentTimeMillis()
        offerRepository.save(original)

        def counter = new Offer(
            listingId    : original.listingId,
            buyerUserId  : original.buyerUserId,
            sellerUserId : sellerUserId,
            amount       : amount,
            askingPrice  : listing.price,
            buyerName    : original.buyerName,
            itemName     : original.itemName,
            itemImageUrl : original.itemImageUrl,
            itemId       : original.itemId ?: listing.item?.id,
            status       : 'PENDING',
            author       : 'SELLER',
            parentOfferId: original.id,
            message      : cleanMessage
        )
        def saved = offerRepository.save(counter)

        // Notify the buyer that the seller has come back with a counter.
        // Without this the buyer sees their original offer flip to
        // COUNTERED but has no nudge to act on the reply — the ball is in
        // their court now (accept / counter-again / let it expire).
        if (original.buyerUserId != null && notificationService != null) {
            try {
                notificationService.push(original.buyerUserId, 'OFFER_COUNTERED',
                    "Seller countered your offer · ${listing.item?.name ?: 'listing'}",
                    "They're asking \$${amount.toPlainString()} (you offered \$${original.amount.toPlainString()}).",
                    saved.id,
                    '/offers')
            } catch (Exception e) {
                log.warn("OFFER_COUNTERED push failed for buyer ${original.buyerUserId}: ${e.message}")
            }
        }
        // Mirror the bell to email for the buyer — sleeping buyer would
        // otherwise let a counter lapse into auto-decline without ever
        // seeing it. Gated on TRADES bucket so a power buyer can mute
        // without losing bid / auction emails.
        if (original.buyerUserId != null) {
            try {
                if (emailService != null && steamUserRepository != null) {
                    def buyer = steamUserRepository.findById(original.buyerUserId).orElse(null)
                    if (emailService.canSendTo(buyer, 'TRADES')) {
                        emailService.sendOfferCountered(
                            buyer.email, buyer.displayName, listing.item?.name,
                            original.amount, amount, cleanMessage)
                    }
                }
            } catch (Exception e) {
                log.warn("OFFER_COUNTERED email failed for buyer ${original.buyerUserId}: ${e.message}")
            }
        }
        saved
    }

    /**
     * Full offer thread for a given listing. When `viewerUserId` is one of
     * the participants (listing seller or any offer's buyer) they see every
     * field. Other callers get the thread back with buyer identities
     * redacted to "Buyer #N" so a third party cannot enumerate who is
     * bargaining on a listing by hitting /api/offers/thread/{listingId}.
     *
     * Uses the indexed `findByListingId` query instead of the old
     * `findAll().findAll { it.listingId == ... }` full-table scan.
     */
    List<Offer> thread(Long listingId, Long viewerUserId = null) {
        def all = offerRepository.findByListingId(listingId)
        if (all.isEmpty()) return all

        def listing = listingRepository.findById(listingId).orElse(null)
        def isSeller = listing != null && listing.sellerUserId != null && listing.sellerUserId == viewerUserId
        def isBuyer  = viewerUserId != null && all.any { it.buyerUserId == viewerUserId }
        if (isSeller || isBuyer) return all

        // Redact — return fresh detached Offer instances so we never mutate
        // Hibernate-managed entities. Each unique buyerUserId becomes
        // "Buyer #1", "Buyer #2", etc. so the seller-side UI can still
        // visually group a single counter thread.
        def handles = [:]
        int next = 0
        all.collect { o ->
            def handle = handles[o.buyerUserId]
            if (handle == null) {
                handle = "Buyer #${++next}".toString()
                handles[o.buyerUserId] = handle
            }
            new Offer(
                id:            o.id,
                listingId:     o.listingId,
                buyerUserId:   null,
                sellerUserId:  o.sellerUserId,
                amount:        o.amount,
                askingPrice:   o.askingPrice,
                buyerName:     handle,
                itemName:      o.itemName,
                itemImageUrl:  o.itemImageUrl,
                status:        o.status,
                author:        o.author,
                parentOfferId: o.parentOfferId,
                createdAt:     o.createdAt,
                updatedAt:     o.updatedAt
            )
        }
    }

    /** Seller accepts an offer — runs the purchase at the offered price.
     *  Also handles the buyer accepting a SELLER-authored counter — when
     *  the caller is the buyerUserId on a `author='SELLER'` offer, treat
     *  it as the buyer accepting the counter and run the same purchase
     *  flow at the counter price.
     *
     *  `noRollbackFor = InsufficientBalanceException` (batch 328) so the
     *  `offer.status = 'EXPIRED'` flip + the buyer-notification push
     *  actually persist when the buyer's wallet is short — otherwise
     *  the TX rolls back and the offer stays PENDING forever, the
     *  buyer never knows their offer couldn't close, and the seller
     *  sees the same row keep failing every time they retry accept.
     *
     *  Second-pass audit fix — `ListingNotAvailableException` belongs in
     *  the same bucket. The `listing.status != 'ACTIVE'` branch below
     *  saves `offer.status = 'EXPIRED'` THEN throws the exception, but
     *  without `noRollbackFor` covering it the TX rolls back the save
     *  too — leaving the offer stuck PENDING against a sold/cancelled
     *  listing, so every subsequent seller retry hits the same dead
     *  branch and never clears the row. */
    @Transactional(noRollbackFor = [InsufficientBalanceException, ListingNotAvailableException])
    Map acceptOffer(Long callerUserId, Long offerId) {
        def offer = offerRepository.findById(offerId)
                .orElseThrow { new NotFoundException("Offer", offerId) }
        // Authorisation: seller-authored counters are accepted by the
        // buyer; buyer-authored offers are accepted by the seller. Any
        // other caller is forbidden.
        //
        // The `author` discriminator is load-bearing here. A SELLER
        // counter carries `sellerUserId` = the seller, so an unguarded
        // `offer.sellerUserId == callerUserId` would let the seller
        // accept their OWN counter — unilaterally completing a purchase
        // and debiting the buyer's wallet for a price the buyer never
        // agreed to. Restrict the seller path to USER-authored offers so
        // a seller can only ever accept what a buyer actually offered.
        boolean buyerAcceptingCounter =
                offer.author == 'SELLER' && offer.buyerUserId == callerUserId
        boolean sellerAcceptingOffer =
                offer.author == 'USER' &&
                offer.sellerUserId != null && offer.sellerUserId == callerUserId
        if (!buyerAcceptingCounter && !sellerAcceptingOffer) {
            throw new ForbiddenException("You can only accept offers on your own listings")
        }
        if (offer.status != 'PENDING') {
            throw new OfferNotPendingException(offerId, offer.status)
        }

        def listing = listingRepository.findById(offer.listingId)
                .orElseThrow { new NotFoundException("Listing", offer.listingId) }
        if (listing.status != 'ACTIVE') {
            offer.status = 'EXPIRED'
            offer.updatedAt = System.currentTimeMillis()
            offerRepository.save(offer)
            throw new ListingNotAvailableException(offer.listingId)
        }

        // Resolve buyer wallet via SteamUser → username = "steam_<steamId64>"
        def buyer = steamUserRepository.findById(offer.buyerUserId).orElse(null)
        if (buyer == null) {
            throw new NotFoundException("Buyer", offer.buyerUserId)
        }
        def actualWallet = walletRepository.findByUsername("steam_" + buyer.steamId64)
        if (actualWallet == null) {
            throw new NotFoundException("Wallet for buyer ${offer.buyerUserId}")
        }
        if (actualWallet.balance < offer.amount) {
            offer.status = 'EXPIRED'
            offerRepository.save(offer)
            // Notify the buyer so their Offers tab doesn't show a
            // silent "EXPIRED" label with no context (batch 328). The
            // buyer's wallet balance dropped below their offer amount
            // sometime between creating the offer and the seller
            // accepting — they need to know to top up + re-offer.
            if (notificationService != null) {
                try {
                    notificationService.push(offer.buyerUserId, 'OFFER_REJECTED',
                        "Offer couldn't close · ${offer.itemName ?: 'listing'}",
                        "Your \$${offer.amount.toPlainString()} offer was accepted but your wallet balance dropped below that amount. Top up and make a new offer.",
                        offer.id,
                        '/offers')
                } catch (Exception e) {
                    log.warn("Offer-insufficient-balance push failed for buyer ${offer.buyerUserId}: ${e.message}")
                }
            }
            throw new InsufficientBalanceException(offer.amount, actualWallet.balance)
        }

        // Temporarily lower the listing price to the offer price so the existing
        // PurchaseService can run unchanged. This is internal — listing transitions
        // to SOLD immediately after.
        def originalPrice = listing.price
        listing.price = offer.amount
        listingRepository.save(listing)

        try {
            purchaseService.buy(actualWallet.id, offer.buyerUserId, offer.listingId)
        } catch (Exception e) {
            // Restore price if purchase fails
            listing.price = originalPrice
            listingRepository.save(listing)
            throw e
        }

        offer.status = 'ACCEPTED'
        offer.updatedAt = System.currentTimeMillis()
        offerRepository.save(offer)

        // P1 bug fix — when the accepted offer is a SELLER counter, the
        // buyer's original is still parked in COUNTERED. Acceptance is the
        // counter's happy-path terminal state, yet it was the one terminal
        // transition that never reached closeCounteredParent (reject /
        // cancel / sweep / buyer-raise all did). Left uncleaned, the
        // COUNTERED original stays "live" to findLiveByBuyerAndListing —
        // so the buyer's ItemModal "You offered $X" chip keeps showing a
        // live offer on a listing they've already bought, and the dead
        // COUNTERED node never reaches the inert CLOSED state the
        // state-machine design requires. Idempotent + no-op for a
        // USER-authored offer (no parent), so the seller-accept path is
        // unaffected.
        closeCounteredParent(offer)

        // Explicit OFFER_ACCEPTED push to the buyer (batch 395). The
        // PurchaseService.buy path already fires ITEM_PURCHASED, but that
        // reads as a generic "you bought X" — a buyer who made an offer
        // days ago + forgot doesn't immediately connect a cold purchase
        // ping to their old offer. This distinct notification names the
        // offer acceptance explicitly and deep-links to /profile?tab=trades
        // (where the new trade row is, ready for them to act).
        if (offer.buyerUserId != null && notificationService != null) {
            try {
                notificationService.push(offer.buyerUserId, 'OFFER_ACCEPTED',
                    "Offer accepted · ${offer.itemName ?: 'listing'}",
                    "Your \$${offer.amount.toPlainString()} offer was accepted — a trade has been opened. Head to Trades to watch for the Steam offer.",
                    offer.id,
                    '/profile?tab=trades')
            } catch (Exception e) {
                log.warn("OFFER_ACCEPTED push failed for buyer ${offer.buyerUserId}: ${e.message}")
            }
        }
        // Email confirmation to the buyer with the new trade id + the
        // 3-day escrow timeline. Mirrors the bell push above so a
        // sleeping buyer hears about acceptance promptly without needing
        // to open the app — and so they have an off-platform record of
        // the trade id for support tickets. Best-effort + null-safe.
        Long tradeId = null
        try {
            if (tradeRepository != null) {
                def trade = tradeRepository.findByListingId(offer.listingId)
                tradeId = trade?.id as Long
            }
        } catch (Exception e) {
            log.debug("Trade-id lookup for offer-accepted email failed: ${e.message}")
        }
        if (offer.buyerUserId != null && emailService != null && steamUserRepository != null) {
            try {
                // Renamed from `buyer` to `buyerForEmail` to avoid the
                // Groovy scope clash with the outer `def buyer` at line
                // 585 (Groovy hoists def to method scope so nested
                // try-blocks don't get a fresh binding).
                def buyerForEmail = steamUserRepository.findById(offer.buyerUserId).orElse(null)
                if (buyerForEmail != null && emailService.canSendTo(buyerForEmail, 'TRADES')) {
                    emailService.sendOfferAccepted(
                        buyerForEmail.email, buyerForEmail.displayName, offer.itemName,
                        offer.amount, tradeId)
                }
            } catch (Exception e) {
                log.warn("OFFER_ACCEPTED email failed for buyer ${offer.buyerUserId}: ${e.message}")
            }
        }

        // Reject all other pending offers on the same listing — and ping
        // each displaced buyer so they know the listing is gone (vs.
        // thinking their offer auto-expired silently). Without this, a
        // buyer's PENDING offer would flip to EXPIRED in their Offers tab
        // with no explanation of WHY they lost.
        def listingItemId = (listing.item?.id) as Long
        def listingItemName = (listing.item?.name ?: 'the listing') as String
        offerRepository.findPendingForListing(offer.listingId).each { other ->
            if (other.id != offer.id) {
                other.status = 'EXPIRED'
                other.updatedAt = System.currentTimeMillis()
                offerRepository.save(other)
                // P1 bug fix — a competing PENDING row being expired here
                // may itself be a SELLER counter (seller countered buyer A,
                // then accepted buyer B's separate offer). Expiring the
                // counter without closing its COUNTERED parent strands
                // buyer A's original in COUNTERED — same dangling-node bug
                // closeCounteredParent exists to prevent. No-op for a plain
                // USER offer with no parent.
                closeCounteredParent(other)
                if (other.buyerUserId != null && notificationService != null) {
                    try {
                        notificationService.push(other.buyerUserId, 'OFFER_REJECTED',
                            "Offer closed · ${listingItemName}",
                            "Another buyer's offer was accepted on this listing.",
                            other.id,
                            listingItemId != null ? "/item/${listingItemId}" : '/offers')
                    } catch (Exception e) {
                        log.warn("Competing-offer notification failed for buyer ${other.buyerUserId}: ${e.message}")
                    }
                }
            }
        }

        log.info("Offer ${offer.id} accepted, listing ${offer.listingId} sold for \$${offer.amount}")
        [accepted: true, listingId: offer.listingId, finalPrice: offer.amount]
    }

    @Transactional
    Offer rejectOffer(Long sellerUserId, Long offerId, String reply = null) {
        def offer = offerRepository.findById(offerId)
                .orElseThrow { new NotFoundException("Offer", offerId) }
        // Same shape as counterOffer — the old check let random users
        // reject offers on system listings (null sellerUserId) and grief
        // real buyers out of their pending offers.
        if (offer.sellerUserId == null) {
            throw new ForbiddenException("Offers on system listings cannot be rejected — they auto-accept when processed")
        }
        if (offer.sellerUserId != sellerUserId) {
            throw new ForbiddenException("You can only reject offers on your own listings")
        }
        if (offer.status != 'PENDING') {
            throw new OfferNotPendingException(offerId, offer.status)
        }
        // Optional seller rejection note (V45 / batch 387). Same 280-char
        // rule as the buyer's offer message. Empty/whitespace collapses to
        // null so we never store blank strings. Same cleanShort vs
        // clean(_, 280) mismatch as counterOffer / 4cc7288 — pre-sanitise
        // length check before clean so a 100-char rejection note isn't
        // silently chopped to 80.
        String cleanReply = null
        if (reply != null) {
            if (reply.length() > MESSAGE_MAX_LEN) {
                throw new BadRequestException("MESSAGE_TOO_LONG",
                    "Rejection note must be ${MESSAGE_MAX_LEN} characters or fewer.")
            }
            def trimmed = textSanitizer.clean(reply, MESSAGE_MAX_LEN)
            if (trimmed) cleanReply = trimmed
        }
        offer.status = 'REJECTED'
        offer.updatedAt = System.currentTimeMillis()
        if (cleanReply) offer.sellerReply = cleanReply
        def saved = offerRepository.save(offer)
        // P1 bug fix — if this is a SELLER counter being rejected, the
        // buyer's original is parked in COUNTERED with no exit. Close it
        // so the makeOffer duplicate guard stops treating a dead
        // negotiation as a live offer and locking the buyer out.
        closeCounteredParent(offer)
        // Notify the buyer that the seller turned them down. Without
        // this the buyer just saw their offer flip to REJECTED in the
        // Offers tab with no signal — easy to miss entirely. When the
        // seller attached a reason, surface a truncated preview in the
        // notification body so the buyer sees the context without
        // opening the tab.
        if (offer.buyerUserId != null && notificationService != null) {
            def body = cleanReply
                ? "The seller declined your \$${offer.amount.toPlainString()} offer — \"${cleanReply.take(120)}${cleanReply.length() > 120 ? '…' : ''}\""
                : "The seller declined your \$${offer.amount.toPlainString()} offer."
            try {
                notificationService.push(offer.buyerUserId, 'OFFER_REJECTED',
                    "Offer rejected · ${offer.itemName ?: 'listing'}",
                    body, offer.id, '/offers')
            } catch (Exception e) {
                log.warn("OFFER_REJECTED push failed for buyer ${offer.buyerUserId}: ${e.message}")
            }
        }
        // Mirror to email so a sleeping buyer hears about the rejection
        // promptly + sees the optional seller reply without opening the
        // app. Gated on TRADES bucket.
        if (offer.buyerUserId != null) {
            try {
                if (emailService != null && steamUserRepository != null) {
                    def buyer = steamUserRepository.findById(offer.buyerUserId).orElse(null)
                    if (emailService.canSendTo(buyer, 'TRADES')) {
                        emailService.sendOfferRejected(
                            buyer.email, buyer.displayName, offer.itemName,
                            offer.amount, cleanReply)
                    }
                }
            } catch (Exception e) {
                log.warn("OFFER_REJECTED email failed for buyer ${offer.buyerUserId}: ${e.message}")
            }
        }
        saved
    }

    @Transactional
    Offer cancelOffer(Long buyerUserId, Long offerId) {
        def offer = offerRepository.findById(offerId)
                .orElseThrow { new NotFoundException("Offer", offerId) }
        if (offer.buyerUserId != buyerUserId) {
            throw new ForbiddenException("You can only cancel your own offers")
        }
        // P2 bug fix — COUNTERED is also withdrawable. The old PENDING-only
        // guard meant a buyer whose offer the seller had countered could
        // not walk away from the negotiation at all: their original is
        // parked in COUNTERED, and without an exit here it stayed live to
        // the makeOffer duplicate guard forever. Both PENDING (offer not
        // yet answered) and COUNTERED (seller answered, buyer declining
        // the reply) are legitimate buyer-withdraw states.
        if (offer.status != 'PENDING' && offer.status != 'COUNTERED') {
            throw new OfferNotPendingException(offerId, offer.status)
        }
        boolean wasCountered = offer.status == 'COUNTERED'
        offer.status = 'CANCELLED'
        offer.updatedAt = System.currentTimeMillis()
        def saved = offerRepository.save(offer)
        // If the buyer is withdrawing a COUNTERED original, the seller's
        // live PENDING counter hanging off it must die too — otherwise the
        // buyer has walked away but the counter is still acceptable, and
        // the buyer's COUNTERED→CANCELLED original no longer guards
        // against a duplicate. Expire (not cancel) the counter: from the
        // seller's side it's an offer that lapsed, consistent with how
        // the sweeper closes seller-side rows.
        if (wasCountered) {
            def now = System.currentTimeMillis()
            offerRepository.findByParentOfferId(offer.id).each { child ->
                if (child.status == 'PENDING' && child.author == 'SELLER') {
                    child.status = 'EXPIRED'
                    child.updatedAt = now
                    offerRepository.save(child)
                }
            }
        }
        // If the buyer is withdrawing a SELLER counter (a walk-away from
        // the seller's reply), the buyer's original is parked in COUNTERED
        // upstream — close it so the duplicate guard frees up.
        closeCounteredParent(offer)
        // Notify the seller so they stop seeing the row in their incoming
        // queue / pending-count chip. Only matters for USER-authored rows
        // (a SELLER counter being cancelled by the buyer is really a
        // walk-away, and those are logged in the thread anyway). Silent
        // for system listings (sellerUserId is null).
        if (offer.sellerUserId != null && notificationService != null) {
            try {
                notificationService.push(offer.sellerUserId, 'OFFER_REJECTED',
                    "Buyer withdrew their offer · ${offer.itemName ?: 'listing'}",
                    "They cancelled their \$${offer.amount.toPlainString()} offer before you responded.",
                    offer.id, '/offers')
            } catch (Exception e) {
                log.warn("Buyer-cancel push failed for seller ${offer.sellerUserId}: ${e.message}")
            }
        }
        saved
    }

    /**
     * Bulk-cancel every PENDING offer the caller has outgoing. Mirrors
     * BuyOrderService.cancelAllForUser — lets a buyer walk away from
     * N simultaneous negotiations in one click rather than clicking
     * through N individual X-buttons. Returns the count flipped.
     *
     * Each cancel is independent — a per-row push failure doesn't
     * abort the batch. Offers don't escrow wallet funds so there's no
     * refund bookkeeping to worry about (unlike auction bids). Seller
     * notifications go out per-row so stalls see the queue drain
     * cleanly.
     *
     * NOT @Transactional — same Spring rollback-only leak the wave 23 +
     * commit 443f910 wave closed for sibling fan-outs (AnnouncementService
     * .sweepExpired, BuyOrderService.cancelAllForUser, TradeService.sweep
     * ReviewNudge / sweepSlowSellerWarning). Spring Data's save() proxy
     * marks the SHARED outer tx as rollback-only the moment one inner save
     * throws — the per-row catch absorbs the throw but the tx is already
     * poisoned, so on method return the commit throws
     * UnexpectedRollbackException and every "successfully" cancelled offer
     * in the batch is rolled back too. Zero cross-row invariant here —
     * each offer cancel is independent (no shared escrow, no shared
     * inventory row). Drop the outer tx so each save() runs in its own
     * implicit tx and a single bad offer only loses itself, matching the
     * docstring's "a per-row push failure doesn't abort the batch"
     * promise.
     */
    int cancelAllForUser(Long buyerUserId) {
        if (buyerUserId == null) return 0
        // Batch 1030 — indexed PENDING-only fetch instead of pulling every
        // historical offer then filtering to PENDING in Groovy. For a
        // prolific buyer with hundreds of closed rows the old path
        // wasted the hydration every time.
        def pending = offerRepository.findPendingByBuyer(buyerUserId)
        if (pending.isEmpty()) return 0
        int n = 0
        def now = System.currentTimeMillis()
        pending.each { o ->
            try {
                o.status = 'CANCELLED'
                o.updatedAt = now
                offerRepository.save(o)
                n++
                // Quiet per-row push to the seller — best-effort, a
                // failure here doesn't undo the cancel.
                if (o.sellerUserId != null && notificationService != null) {
                    try {
                        notificationService.push(o.sellerUserId, 'OFFER_REJECTED',
                            "Buyer withdrew their offer · ${o.itemName ?: 'listing'}",
                            "They cancelled their \$${o.amount.toPlainString()} offer.",
                            o.id, '/offers')
                    } catch (Exception e) {
                        log.warn("Bulk-cancel seller push failed for offer ${o.id}: ${e.message}")
                    }
                }
            } catch (Exception e) {
                log.warn("Bulk-cancel failed for offer ${o.id}: ${e.message}")
            }
        }
        log.info("Bulk-cancelled ${n} offer(s) for user ${buyerUserId}")
        n
    }

    /** Display cap on the Profile → Offers tabs. A power-user with
     *  thousands of historical offers otherwise forces the server to
     *  hydrate + counterparty-enrich + reputation-enrich every row on
     *  every tab open. 300 is generous — enough for a serious bulk
     *  shopper to see months of history — while bounding per-tab
     *  rendering cost. Older offers stay queryable by id. */
    static final int OFFER_LIST_CAP = 300

    List<Offer> incoming(Long sellerUserId) {
        offerRepository.findBySellerPaged(sellerUserId,
            org.springframework.data.domain.PageRequest.of(0, OFFER_LIST_CAP))
    }
    List<Offer> outgoing(Long buyerUserId)  {
        offerRepository.findByBuyerPaged(buyerUserId,
            org.springframework.data.domain.PageRequest.of(0, OFFER_LIST_CAP))
    }

    /** Total row counts for the X-Total-Count header on the Offers
     *  tabs — same pattern as `TradeService.countForUser`. */
    long countIncoming(Long sellerUserId) {
        sellerUserId == null ? 0L : offerRepository.countBySeller(sellerUserId)
    }
    long countOutgoing(Long buyerUserId) {
        buyerUserId == null ? 0L : offerRepository.countByBuyer(buyerUserId)
    }

    /** DTO-decorated counterparts to the raw entity lists above. Adds
     *  `expiresAt` so the Offers tab can render an "Auto-declines in
     *  Xh" countdown without having to know the server's
     *  `offer.auto-decline-days` config. PENDING-only — terminal
     *  states return null since an EXPIRED / ACCEPTED / REJECTED /
     *  CANCELLED offer has no live timer. */
    List<Map> incomingWithExpiry(Long sellerUserId) {
        def offers = offerRepository.findBySellerPaged(sellerUserId,
            org.springframework.data.domain.PageRequest.of(0, OFFER_LIST_CAP))
        // Batch 847 — counterparty reputation enrichment. Two bulk
        // GROUP BY queries (verified-trade count + review summary)
        // across every unique buyer instead of 2×N_b round-trips in a
        // loop. Cheap because `(buyer_user_id, state)` is indexed.
        def buyerIds = offers*.buyerUserId.findAll { it != null }.unique()
        Map<Long, Long>   tradeCounts = [:].withDefault { 0L }
        Map<Long, Map>    summaries   = [:]
        if (!buyerIds.isEmpty()) {
            if (tradeRepository != null) {
                try {
                    tradeRepository.countVerifiedByBuyerIds(buyerIds).each { row ->
                        def uid = (row[0] as Number)?.longValue()
                        def cnt = ((row[1] as Number) ?: 0L).longValue()
                        if (uid != null) tradeCounts[uid] = cnt
                    }
                } catch (Exception e) {
                    log.debug("Incoming-offer verified-trade bulk enrichment failed: ${e.message}")
                }
            }
            if (reviewService != null) {
                try {
                    summaries = reviewService.summariesForUsers(buyerIds) ?: [:]
                } catch (Exception e) {
                    log.debug("Incoming-offer review-summary bulk enrichment failed: ${e.message}")
                }
            }
        }
        offers.collect { o ->
            def m = toMap(o)
            def bid = o.buyerUserId
            if (bid != null) {
                m.buyerCompletedTrades = tradeCounts[bid] ?: 0L
                def s = summaries[bid]
                m.buyerReviewCount     = (s?.count ?: 0L) as Long
                m.buyerReviewAvg       = s?.average
            }
            m
        }
    }
    List<Map> outgoingWithExpiry(Long buyerUserId) {
        // Batch 849 — seller-name enrichment. The buyer viewing their
        // own outgoing offers had no way to see WHO they'd offered to
        // without clicking through to the listing. Single bulk
        // findAllById keeps this cheap regardless of list size.
        //
        // Batch 851 — seller reputation enrichment (count + average).
        // Mirrors the incoming-offer buyer-reputation enrichment
        // (batch 847) so both sides of an offer surface their
        // counterparty's track record.
        def offers = offerRepository.findByBuyerPaged(buyerUserId,
            org.springframework.data.domain.PageRequest.of(0, OFFER_LIST_CAP))
        Map<Long, String> sellerNames = [:]
        Map<Long, Map>    summaries   = [:]
        def sellerIds = offers*.sellerUserId.findAll { it != null }.unique()
        if (!sellerIds.isEmpty()) {
            try {
                steamUserRepository.findAllById(sellerIds).each { u ->
                    if (u?.id != null) sellerNames[u.id] = u.displayName
                }
            } catch (Exception e) {
                log.debug("Outgoing-offer seller-name enrichment failed: ${e.message}")
            }
            if (reviewService != null) {
                // Bulk GROUP BY across every unique seller — one
                // round-trip vs N_s. Mirrors the incoming-offer
                // enrichment above and the controller-side
                // summariesForUsers usage in ListingController.
                try {
                    summaries = reviewService.summariesForUsers(sellerIds) ?: [:]
                } catch (Exception e) {
                    log.debug("Outgoing-offer review-summary bulk enrichment failed: ${e.message}")
                }
            }
        }
        offers.collect { o ->
            def m = toMap(o)
            def sid = o.sellerUserId
            if (sid != null) {
                m.sellerName = sellerNames[sid]
                def s = summaries[sid]
                m.sellerReviewCount = (s?.count ?: 0L) as Long
                m.sellerReviewAvg   = s?.average
            }
            m
        }
    }

    /**
     * Fire PRICE_DROPPED pings to every buyer with a PENDING offer on a
     * listing whose price just dropped to AT OR BELOW their offer
     * amount (batch 699). That's the "your offer would now execute at
     * the ask" moment — strongest buy signal in the system. Cart-
     * holder fan-out (ListingController) covers the generic
     * "any-interest" case; this covers the specific "you actively
     * bargained on this" case. Capped at 50 offer-holders so a
     * popular listing doesn't balloon fan-out cost. Best-effort: a
     * push failure on one buyer is logged but doesn't break the rest.
     */
    void notifyOfferHoldersOfPriceDrop(Long listingId, BigDecimal oldPrice, BigDecimal newPrice, String itemName, Long itemId) {
        if (listingId == null || newPrice == null || oldPrice == null || newPrice >= oldPrice) return
        if (notificationService == null) return
        try {
            def pending = offerRepository.findPendingForListing(listingId) ?: []
            // Only buyers whose offer amount >= the NEW ask — that's when
            // the price drop actually matters to them ("you could buy at
            // ≤ what you already offered"). A buyer who offered $20 on a
            // $50 listing that dropped to $40 is NOT in the target set.
            def winners = pending.findAll { o ->
                o.buyerUserId != null && o.amount != null && o.amount.compareTo(newPrice) >= 0
            }.unique { it.buyerUserId }.take(50)
            def name = itemName ?: 'an item you offered on'
            // Drop banned recipients (batch 316/317) — banned offer-holders
            // can't act on the ping. Build uid→offer map so we can recover
            // the matching offer after filtering.
            Map<Long, Object> byUid = [:]
            winners.each { o -> byUid[o.buyerUserId as Long] = o }
            def activeUids = notificationService.filterActiveRecipients(byUid.keySet() as List<Long>)
            def activeWinners = activeUids.collect { byUid[it] }.findAll { it != null }
            activeWinners.each { o ->
                try {
                    notificationService.push(o.buyerUserId as Long, 'PRICE_DROPPED',
                        "Your offer is now at or above the ask · ${name}",
                        "The seller dropped the ask from \$${oldPrice.toPlainString()} to \$${newPrice.toPlainString()} — your \$${o.amount.toPlainString()} offer could buy outright now.",
                        listingId,
                        itemId != null ? "/item/${itemId}" : null)
                } catch (Exception e) {
                    log.warn("PRICE_DROPPED (offer-holder) push failed for uid=${o.buyerUserId}: ${e.message}")
                }
            }
        } catch (Exception e) {
            log.warn("PRICE_DROPPED offer-holder fan-out failed for listing=${listingId}: ${e.message}")
        }
    }

    /** Absolute epoch-ms at which a PENDING offer is auto-declined by
     *  `sweepStaleOffers`. Mirrors `TradeService.computeExpiresAt`. */
    Long computeExpiresAt(Offer o) {
        if (o == null || o.updatedAt == null) return null
        if (o.status != 'PENDING') return null
        o.updatedAt + (autoDeclineDays * 24L * 60L * 60L * 1000L)
    }

    private Map toMap(Offer o) {
        [
            id:             o.id,
            listingId:      o.listingId,
            buyerUserId:    o.buyerUserId,
            sellerUserId:   o.sellerUserId,
            amount:         o.amount,
            askingPrice:    o.askingPrice,
            status:         o.status,
            buyerName:      o.buyerName,
            itemName:       o.itemName,
            itemImageUrl:   o.itemImageUrl,
            createdAt:      o.createdAt,
            updatedAt:      o.updatedAt,
            parentOfferId:  o.parentOfferId,
            author:         o.author,
            expiresAt:      computeExpiresAt(o)
        ]
    }

    /**
     * Counter-thread cleanup (P1 bug fix). When a SELLER-authored counter
     * reaches a terminal state — rejected, cancelled (buyer walk-away),
     * swept stale, or superseded by a buyer raise — the buyer's original
     * offer is still sitting in COUNTERED with NO exit transition. Nothing
     * else ever mutates a COUNTERED row, so `findLiveByBuyerAndListing`
     * (which matches PENDING *or* COUNTERED) keeps reporting a live offer
     * forever and `makeOffer`'s duplicate guard locks the buyer out of
     * ever offering on that listing again. `findStalePending` only matches
     * PENDING, so the sweeper can't rescue it either.
     *
     * Given the now-dead child counter, walk up via `parentOfferId` and,
     * if the parent is still COUNTERED, flip it to the terminal CLOSED
     * state. CLOSED is inert across every repository query (no query
     * treats it as live) so the dup-guard frees up and the buyer can make
     * a fresh offer. Idempotent — a parent already CLOSED / ACCEPTED /
     * etc. is left untouched, so calling this twice is harmless.
     *
     * No-op when the child has no parent (a root offer) or the parent row
     * has vanished. Runs in the caller's transaction: if the parent save
     * fails the whole counter-termination rolls back rather than leaving
     * a half-cleaned thread.
     */
    private void closeCounteredParent(Offer childOffer) {
        if (childOffer == null || childOffer.parentOfferId == null) return
        def parent = offerRepository.findById(childOffer.parentOfferId).orElse(null)
        if (parent == null) return
        if (parent.status != 'COUNTERED') return
        parent.status = 'CLOSED'
        parent.updatedAt = System.currentTimeMillis()
        offerRepository.save(parent)
        log.info("Offer ${parent.id} (COUNTERED) closed — its counter ${childOffer.id} reached terminal state ${childOffer.status}")
    }

    long countPendingIncoming(Long sellerUserId) {
        offerRepository.countPendingBySeller(sellerUserId)
    }
    long countPendingOutgoing(Long buyerUserId) {
        offerRepository.countPendingByBuyer(buyerUserId)
    }

    /**
     * For each of the seller's active listings that has at least one
     * PENDING buyer offer, return a small summary map keyed by listing
     * id: { bestAmount, count, newestAt }. Drives the MyStall "Best
     * offer $X · N pending" chip so sellers see actionable bargaining
     * lanes without opening the Offers tab.
     *
     * PENDING-only by design — COUNTERED means the seller already
     * answered and it's the buyer's move now, so surfacing it in the
     * stall would just add noise. CANCELLED / EXPIRED are terminal.
     */
    Map<Long, Map> pendingOfferSummaryForSeller(Long sellerUserId) {
        if (sellerUserId == null) return [:]
        def rows = offerRepository.aggregatePendingBySeller(sellerUserId)
        def out = [:]
        rows.each { row ->
            def lid = row[0] as Long
            out[lid] = [
                bestAmount: row[1],
                count:      (row[2] ?: 0L) as long,
                newestAt:   (row[3] ?: 0L) as long
            ]
        }
        out
    }

    /** The caller's live (PENDING/COUNTERED) offer on a specific
     *  listing, or null if none. Drives the "You offered $X" chip
     *  on the ItemModal (batch 368). */
    Map liveOfferByBuyerForListing(Long buyerUserId, Long listingId) {
        if (buyerUserId == null || listingId == null) return null
        def rows = offerRepository.findLiveByBuyerAndListing(buyerUserId, listingId)
        if (rows.isEmpty()) return null
        toMap(rows[0])
    }

    /**
     * Fraction of offers the seller actually engaged with — (resolved)
     * / (resolved + expired). Returns null until the seller has at
     * least 5 resolvable offers so the stat can't flash an alarming
     * "0%" off a single sweeper miss. CANCELLED (buyer-withdrawn) is
     * excluded from the denominator — it isn't a seller outcome.
     *
     * Buyers read this alongside the response-time chip: a 95% / 2h
     * seller is a pro, a 20% / 2h seller is cherry-picking.
     */
    Double responseRatePct(Long sellerUserId) {
        if (sellerUserId == null) return null
        long total = offerRepository.countSellerEngagedTotal(sellerUserId)
        if (total < 5L) return null
        long responded = offerRepository.countSellerResponded(sellerUserId)
        ((responded as double) / (total as double)) * 100.0d
    }

    /**
     * Seller's typical response time across their most recent resolved
     * offers — median milliseconds between offer creation and the
     * seller's action (accept / reject / counter). Returns null until
     * the seller has at least 3 data points, so the stat never misleads
     * a stall viewer with noise. Bounded to the most recent 50 offers
     * to keep the query cheap and to favour "what the seller does
     * lately" over "what they did six months ago".
     *
     * The chip it powers reads "Typically responds in X" — a CSFloat-
     * style trust signal that lets a buyer gauge whether bargaining is
     * worth their time vs. just hitting Buy Now.
     */
    Long typicalResponseMs(Long sellerUserId) {
        if (sellerUserId == null) return null
        def rows = offerRepository.findRecentSellerResponses(
            sellerUserId, org.springframework.data.domain.PageRequest.of(0, 50))
        if (rows == null || rows.size() < 3) return null
        def deltas = rows
            .collect { (it.updatedAt ?: 0L) - (it.createdAt ?: 0L) }
            .findAll { it > 0L }
            .sort()
        if (deltas.size() < 3) return null
        def mid = (int) (deltas.size() / 2)
        (deltas.size() % 2 == 1)
            ? deltas[mid] as Long
            : ((deltas[mid - 1] + deltas[mid]) / 2L) as Long
    }

    /**
     * Scheduled sweeper — auto-declines pending offers older than the
     * configured window so they stop clogging the seller's incoming queue
     * and blocking item modals where an old offer thread still shows.
     * Fires every 6 hours; idempotent — rows that flip CANCELLED here
     * won't match the query next tick.
     *
     * Notifies the buyer so they know their offer expired; the seller
     * doesn't need a ping, the row just disappears from their queue.
     *
     * NOT @Transactional — sixth instance of the rollback-only leak the
     * 443f910 / 526a5f4 / d3a3df7 / fe48e76 family closed for sibling fan-
     * outs. Spring Data's save() proxy marks the SHARED outer tx as
     * rollback-only the moment one inner save throws (e.g. an
     * OptimisticLockingFailureException from a concurrent buyer cancel /
     * seller accept landing on that offer mid-batch, or a DB blip on one
     * row); the per-row catch absorbs the throw, sweepStaleOffers
     * returns normally — but at commit Spring raises
     * UnexpectedRollbackException and every prior EXPIRED stamp + queued
     * notification + closeCounteredParent flip reverts. The next 6h tick
     * then re-finds the same offers and re-fires duplicate
     * OFFER_REJECTED pushes to buyers we already pinged. Zero cross-row
     * invariant here — each offer auto-decline is independent (no
     * shared wallet escrow, no shared inventory). Drop the outer tx so
     * each save() runs in its own implicit tx and one bad row only
     * loses itself.
     */
    @Scheduled(fixedDelay = 6L * 60L * 60L * 1000L, initialDelay = 10L * 60L * 1000L)
    void sweepStaleOffers() {
        def cutoff = System.currentTimeMillis() - (autoDeclineDays * 24L * 60L * 60L * 1000L)
        def stale = offerRepository.findStalePending(cutoff)
        if (stale.isEmpty()) return
        // Bulk-fetch the listings in one query so we can resolve item
        // names for the user-facing notification body (used to say
        // "listing #N" — opaque and scary). Missing rows fall through
        // to a generic "this item" label, still better than a raw id.
        def listingIds = stale*.listingId.findAll { it != null }.unique()
        def listingsById = [:]
        if (listingRepository != null && !listingIds.isEmpty()) {
            try {
                listingRepository.findAllById(listingIds).each { l -> listingsById[l.id] = l }
            } catch (Exception e) {
                log.warn("Stale-offer listing name lookup failed: ${e.message}")
            }
        }
        stale.each { offer ->
            try {
                // EXPIRED (not CANCELLED) because the sweeper closing a
                // stale offer is a seller-side failure to respond. The
                // response-rate query from batch 97 puts EXPIRED rows in
                // the engagement denominator (seller had a chance and
                // didn't act) but excludes CANCELLED (buyer withdrew).
                // Labeling the sweeper output CANCELLED inflated every
                // seller's response-rate stat — their no-responses were
                // invisible to the denominator.
                offer.status = 'EXPIRED'
                offer.updatedAt = System.currentTimeMillis()
                offerRepository.save(offer)
                // P1 bug fix — if the swept row is a SELLER counter, its
                // parent is stuck in COUNTERED. Close it in the same pass
                // so the buyer isn't permanently blocked by the dup-guard
                // once a counter negotiation simply times out unanswered.
                closeCounteredParent(offer)
                def itemName = listingsById[offer.listingId]?.item?.name ?: 'this item'
                def itemId = listingsById[offer.listingId]?.item?.id
                notificationService?.push(
                    offer.buyerUserId,
                    'OFFER_REJECTED',
                    "Offer auto-declined · ${itemName}",
                    "Your \$${offer.amount?.toPlainString() ?: '0'} offer on ${itemName} expired after ${autoDeclineDays} days with no seller response.",
                    offer.listingId,
                    itemId != null ? "/item/${itemId}" : '/offers'
                )
            } catch (Exception e) {
                log.warn("offer auto-decline failed for id=${offer.id}: ${e.message}")
            }
        }
        log.info("Auto-declined ${stale.size()} stale offers (> ${autoDeclineDays} days idle)")
    }

    /**
     * Half-life nudge sweeper (batch 499). Pings the seller once when a
     * PENDING offer has been sitting for half its auto-decline window so
     * a busy seller can act before the silent auto-EXPIRY closes it.
     * `seller_nudged_at` (V47) makes this idempotent — every offer gets
     * at most one nudge across the full PENDING window.
     *
     * Fires every 3 hours. Per-row try/catch so one failure doesn't
     * poison the loop. The query already excludes offers past full life
     * (those will get the auto-decline notification on the next stale-
     * sweeper pass) so a seller can't get nudge + decline back-to-back.
     *
     * NOT @Transactional — same rollback-only leak as sweepStaleOffers
     * above (six-time-fixed family). The per-row offerRepository.save
     * inside the try/catch (both for the system-listing fast-path and
     * the normal nudge path) would poison the SHARED outer tx the
     * moment one save throws; the catch swallows the throw, the method
     * returns normally, then commit raises UnexpectedRollbackException
     * and every prior sellerNudgedAt stamp reverts → the next 3h tick
     * re-finds the same offers and re-fires duplicate OFFER_RECEIVED
     * nudges to sellers we already pinged. Per-save implicit tx
     * isolates each row.
     */
    @Scheduled(fixedDelay = 3L * 60L * 60L * 1000L, initialDelay = 30L * 60L * 1000L)
    void sweepOffersDueForNudge() {
        long now = System.currentTimeMillis()
        long halfLifeMs = (autoDeclineDays * 24L * 60L * 60L * 1000L) / 2L
        long fullLifeMs = autoDeclineDays * 24L * 60L * 60L * 1000L
        def due = offerRepository.findPendingDueForNudge(now - halfLifeMs, now - fullLifeMs)
        if (due.isEmpty()) return
        // Bulk-resolve item names so the notification body is "your
        // pending Wizard Hat offer" instead of "your pending offer".
        def listingIds = due*.listingId.findAll { it != null }.unique()
        def listingsById = [:]
        if (listingRepository != null && !listingIds.isEmpty()) {
            try {
                listingRepository.findAllById(listingIds).each { l -> listingsById[l.id] = l }
            } catch (Exception e) {
                log.warn("Offer-nudge listing lookup failed: ${e.message}")
            }
        }
        long nudged = 0L
        due.each { offer ->
            if (offer.sellerUserId == null) {
                // System listings have no seller to nudge. Stamp the
                // field anyway so we don't re-evaluate this row each pass.
                // Wrapped in try/catch like the seller-bound path below —
                // without the outer @Transactional an inner save throw
                // would otherwise propagate up to .each and abort every
                // remaining row in the batch.
                try {
                    offer.sellerNudgedAt = now
                    offerRepository.save(offer)
                } catch (Exception e) {
                    log.warn("Offer system-listing nudge stamp failed for id=${offer.id}: ${e.message}")
                }
                return
            }
            try {
                def itemName = listingsById[offer.listingId]?.item?.name ?: 'an item'
                def itemId = listingsById[offer.listingId]?.item?.id
                long msLeft = (offer.updatedAt + fullLifeMs) - now
                // intdiv keeps the result as a primitive long. Plain `/`
                // in Groovy promotes long/long to BigDecimal, and
                // Math.max(1L, <BigDecimal>) then throws "Ambiguous method
                // overloading for method java.lang.Math#max" (no
                // (long,BigDecimal) overload — the JVM picks between
                // (double,double) and (float,float) and bails). That
                // GroovyRuntimeException was silently swallowed by the
                // outer per-row catch on every nudge attempt, so since
                // batch 499 introduced this sweeper NO half-life nudge
                // has ever fired and EVERY seller_nudged_at flag stayed
                // null — the auto-decline arrived 7 days later with no
                // warning. Explicit long arithmetic restores the path.
                long hoursLeft = Math.max(1L, msLeft.intdiv(60L * 60L * 1000L))
                long daysLeft = Math.max(1L, hoursLeft.intdiv(24L))
                String windowText = hoursLeft >= 48L ? "${daysLeft} day${daysLeft == 1 ? '' : 's'}" : "${hoursLeft}h"
                notificationService?.push(
                    offer.sellerUserId,
                    'OFFER_RECEIVED',
                    "Pending offer expiring in ${windowText} · ${itemName}",
                    "A \$${offer.amount?.toPlainString() ?: '0'} offer is still waiting for your response. Accept, counter, or reject before it auto-declines.",
                    offer.listingId,
                    itemId != null ? "/item/${itemId}" : '/offers'
                )
                offer.sellerNudgedAt = now
                offerRepository.save(offer)
                nudged++
            } catch (Exception e) {
                log.warn("Offer half-life nudge failed for id=${offer.id}: ${e.message}")
            }
        }
        log.info("Half-life nudge swept ${due.size()} offers; ${nudged} sellers pinged (auto-decline window=${autoDeclineDays}d)")
    }
}
