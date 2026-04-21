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

    /** Seller-response window — a trade sitting in PENDING_SELLER_ACCEPT
     *  or PENDING_SELLER_SEND for longer than this gets auto-cancelled
     *  with a buyer refund. Without this, an unresponsive seller could
     *  freeze buyer funds in escrow indefinitely. Default 3 days so the
     *  seller has ample time; tunable via `trade.seller-response-days`. */
    @Value('${trade.seller-response-days:3}') long sellerResponseDays

    @Autowired TradeRepository tradeRepository
    @Autowired WalletRepository walletRepository
    @Autowired TransactionRepository transactionRepository
    @Autowired(required = false) com.sboxmarket.repository.ListingRepository listingRepository
    @Autowired(required = false) com.sboxmarket.repository.SteamUserRepository steamUserRepository
    @Autowired(required = false) NotificationService notificationService
    @Autowired(required = false) AuditService auditService
    @Autowired(required = false) EmailService emailService
    @Autowired TextSanitizer textSanitizer
    @Autowired(required = false) com.sboxmarket.repository.TradeMessageRepository tradeMessageRepository
    @Autowired(required = false) ReviewService reviewService
    // Narrow security dependencies — we only need the ban check and admin
    // role check, not the full AdminService graph. This is what breaks the
    // old TradeService ↔ AdminService cycle that forced @Lazy injection.
    @Autowired BanGuard banGuard
    @Autowired AdminAuthorization adminAuthorization

    /** Cap on in-trade messages per sender per 10 min. Anti-spam — a
     *  compromised account trying to DM every counterparty will trip this
     *  long before the chat becomes unusable for legitimate users. */
    private static final int MESSAGE_RATE_PER_10MIN = 30

    // ── Queries ──────────────────────────────────────────────────────

    Trade get(Long id) {
        tradeRepository.findById(id).orElseThrow { new NotFoundException("Trade", id) }
    }

    // ── Trade chat (counterparty thread) ───────────────────────────

    /**
     * Post a message in the trade's private chat thread. Only the buyer
     * and seller on the parent trade can post; admins can read via
     * listMessages but not post (they have their own support channels).
     * 2000-char cap, HTML sanitised. Rate-limited to 30/10min per user.
     */
    @Transactional
    com.sboxmarket.model.TradeMessage postMessage(Long tradeId, Long senderUserId, String body) {
        banGuard.assertNotBanned(senderUserId)
        if (tradeMessageRepository == null) {
            throw new BadRequestException('CHAT_UNAVAILABLE', 'Trade chat is temporarily unavailable')
        }
        def trade = get(tradeId)
        if (trade.buyerUserId != senderUserId && trade.sellerUserId != senderUserId) {
            throw new ForbiddenException('Only trade participants can post messages')
        }
        if (trade.state in ['VERIFIED','CANCELLED']) {
            throw new BadRequestException('TRADE_CLOSED',
                "Can't post to a ${trade.state.toLowerCase()} trade")
        }
        def cleanBody = textSanitizer.clean(body, 2000)
        if (cleanBody == null || cleanBody.trim().isEmpty()) {
            throw new BadRequestException('EMPTY_MESSAGE', 'Message body is required')
        }
        def since = System.currentTimeMillis() - 10 * 60_000L
        long recent = tradeMessageRepository.countBySenderUserIdAndCreatedAtGreaterThan(senderUserId, since)
        if (recent >= MESSAGE_RATE_PER_10MIN) {
            throw new BadRequestException('CHAT_RATE_LIMITED',
                "You've posted too many messages. Slow down.")
        }
        def msg = new com.sboxmarket.model.TradeMessage(
            tradeId:      tradeId,
            senderUserId: senderUserId,
            body:         cleanBody,
            createdAt:    System.currentTimeMillis()
        )
        tradeMessageRepository.save(msg)

        // Notify the counterparty. Use the trade's itemName as preview so
        // they know which trade the message is about when they see the bell.
        def counterpartyId = (trade.buyerUserId == senderUserId) ? trade.sellerUserId : trade.buyerUserId
        if (counterpartyId != null) {
            try {
                def preview = cleanBody.length() > 80 ? cleanBody.substring(0, 77) + '…' : cleanBody
                // Deep-link includes the tradeId so the Profile Trades tab can
                // auto-open the chat panel for this specific trade. Without
                // this a user with N pending trades lands on the tab and has
                // to click the chat toggle to find which row pinged them.
                notificationService?.push(counterpartyId, 'TRADE_MESSAGE',
                    "New message on trade #${tradeId}",
                    preview, tradeId, "/profile?tab=trades&openChat=${tradeId}".toString())
            } catch (Exception e) {
                log.warn("Trade-message notification failed for user ${counterpartyId}: ${e.message}")
            }
        }
        msg
    }

    /**
     * Full message thread for a trade. Only participants and admins can
     * see it — buyer-seller messaging is private by design.
     */
    @Transactional
    List<com.sboxmarket.model.TradeMessage> listMessages(Long tradeId, Long viewerUserId) {
        if (tradeMessageRepository == null) return []
        def trade = get(tradeId)
        boolean isParticipant = trade.buyerUserId == viewerUserId || trade.sellerUserId == viewerUserId
        boolean isAdmin = adminAuthorization?.isAdmin(viewerUserId) ?: false
        if (!isParticipant && !isAdmin) {
            throw new ForbiddenException('Only trade participants can read this thread')
        }
        // Mark every unread message NOT sent by the viewer as read
        // (V37 / batch 280) — drives the "✓✓ read" indicator the
        // sender will see on their own messages next time their thread
        // re-fetches. Admin-side reads do NOT mark as read so staff
        // forensics can still see the original unread state. Best-
        // effort: failure here doesn't block the thread fetch.
        if (isParticipant && !isAdmin) {
            try {
                tradeMessageRepository.markIncomingRead(tradeId, viewerUserId,
                    System.currentTimeMillis())
            } catch (Exception e) {
                log.warn("markIncomingRead failed for trade ${tradeId} viewer ${viewerUserId}: ${e.message}")
            }
        }
        // Batch 1028 — cap the thread at the most-recent 200 messages.
        // Chat-thread size is unbounded in principle (escrow lasts up
        // to 8 days, could see dozens of back-and-forth messages per
        // trade), but the UI panel only needs the tail. Pull DESC,
        // reverse to ASC so the chat renderer (oldest → newest) keeps
        // its existing ordering. Older history still sits in the DB
        // for staff forensics via the unbounded findByTrade.
        def recent = tradeMessageRepository.findByTradeRecent(tradeId,
            org.springframework.data.domain.PageRequest.of(0, TRADE_MESSAGES_CAP))
        recent.reverse()
    }

    /** Display cap on per-trade chat thread fetch. 200 messages covers
     *  even the longest escrow negotiation; older messages remain in
     *  the DB for staff / audit access. */
    static final int TRADE_MESSAGES_CAP = 200

    /**
     * Soft-redact a trade-chat message (V41 / batch 349). Staff-only.
     * The row stays in the DB but `body` is cleared and `redactedAt`
     * is stamped so consumers render a placeholder in its place. This
     * preserves chat continuity (vs the previous hard-delete which
     * left silent gaps) and makes "did staff intervene here?"
     * answerable from a simple DB query without trawling audit logs.
     *
     * Idempotent — re-redacting an already-redacted message is a
     * no-op (the second audit entry still writes, so staff have a
     * record of the repeat action, but the row itself doesn't change).
     */
    @Transactional
    void deleteMessage(Long adminUserId, Long messageId) {
        adminAuthorization?.requireAdmin(adminUserId)
        if (tradeMessageRepository == null) return
        def msg = tradeMessageRepository.findById(messageId)
            .orElseThrow { new NotFoundException('TradeMessage', messageId) }
        if (msg.redactedAt == null) {
            msg.body = ''
            msg.redactedAt = System.currentTimeMillis()
            tradeMessageRepository.save(msg)
        }
        auditService?.log(AuditService.TRADE_MESSAGE_DELETED, adminUserId, msg.senderUserId, msg.id,
            "Redacted message in trade ${msg.tradeId}")
        log.warn("Admin ${adminUserId} redacted trade message ${messageId} in trade ${msg.tradeId}")
    }

    List<Trade> listForUser(Long userId) {
        tradeRepository.findByParticipant(userId)
    }

    /**
     * Enriched trade-list payload. Each row is the Trade entity fields plus
     * the counterparty's Steam trade URL and display name. Lets the Profile
     * → Trades tab show "Send Steam offer to @SellerX · copy URL" without
     * the frontend having to fan out one /api/auth lookup per row.
     *
     * Only the counterparty's URL is attached — the viewer's own URL is
     * already in their profile payload. Rows whose counterparty doesn't
     * have a URL set carry null fields.
     */
    /** Display cap on the Profile → Trades tab. A power-user with
     *  thousands of historical trades otherwise forces the server to
     *  serialise them all, with per-row counterparty + unread-chat
     *  + last-message joins, on every tab open. Older rows stay
     *  queryable by id via /api/trades/{id}. */
    static final int TRADE_LIST_CAP = 200

    /** True row count across the user's trades — feeds the
     *  `X-Total-Count` header on `/api/trades`. Null-safe. */
    long countForUser(Long userId) {
        if (userId == null) return 0L
        tradeRepository.countByParticipant(userId)
    }

    List<Map> listForUserWithCounterparty(Long userId) {
        def trades = tradeRepository.findByParticipantPaged(userId,
            org.springframework.data.domain.PageRequest.of(0, TRADE_LIST_CAP))
        if (trades.isEmpty() || steamUserRepository == null) {
            return trades.collect { tradeToMap(it, null, null, null, null, null, 0L) }
        }
        def ids = trades.collect { t -> t.buyerUserId == userId ? t.sellerUserId : t.buyerUserId }
            .findAll { it != null }.unique()
        def users = ids.isEmpty() ? []
            : steamUserRepository.findAllById(ids)
        def byId = users.collectEntries { [(it.id): it] }
        // Bulk per-trade unread chat-message count for the viewing user
        // (V37 / batch 281). One query, one map back. Skipped when the
        // tradeMessageRepository isn't wired (older test contexts).
        Map<Long, Long> unreadCounts = [:]
        Map<Long, com.sboxmarket.model.TradeMessage> lastMessages = [:]
        if (tradeMessageRepository != null) {
            def tradeIds = trades.collect { it.id }.findAll { it != null }
            if (!tradeIds.isEmpty()) {
                try {
                    tradeMessageRepository.countUnreadBulk(tradeIds, userId).each { row ->
                        unreadCounts[row[0] as Long] = (row[1] ?: 0L) as Long
                    }
                } catch (Exception e) {
                    log.warn("countUnreadBulk failed for user ${userId}: ${e.message}")
                }
                // Last-message preview per trade (batch 283). Lets the
                // collapsed trade row show "buyer: did you ship?" inline
                // so the user knows the chat content without opening it.
                try {
                    tradeMessageRepository.findNewestPerTrade(tradeIds).each { msg ->
                        lastMessages[msg.tradeId] = msg
                    }
                } catch (Exception e) {
                    log.warn("findNewestPerTrade failed for user ${userId}: ${e.message}")
                }
            }
        }
        // Counterparty review summaries (batch 402). Looks up each unique
        // cp id once and caches the summary for every trade row that
        // shares that counterparty. Loop over ids is fine for a typical
        // trade list — a power user with 50 trades still hits <10 unique
        // counterparties. Review service is optional in test contexts.
        Map<Long, Map> ratingByUser = [:]
        if (reviewService != null) {
            ids.each { id ->
                try {
                    ratingByUser[id] = reviewService.summaryForUser(id)
                } catch (Exception e) {
                    log.warn("Counterparty rating lookup failed for user ${id}: ${e.message}")
                }
            }
        }
        trades.collect { t ->
            def cpId = t.buyerUserId == userId ? t.sellerUserId : t.buyerUserId
            def cp = cpId == null ? null : byId[cpId]
            def cpRating = cpId == null ? null : ratingByUser[cpId]
            tradeToMap(t, cp?.tradeUrl, cp?.displayName, cp?.steamId64, cp?.avatarUrl,
                cpRating,
                unreadCounts[t.id] ?: 0L, lastMessages[t.id])
        }
    }

    private Map tradeToMap(Trade t, String counterpartyTradeUrl, String counterpartyName,
                           String counterpartySteamId = null,
                           String counterpartyAvatarUrl = null,
                           Map counterpartyRatingSummary = null,
                           long unreadCount = 0L,
                           com.sboxmarket.model.TradeMessage lastMessage = null) {
        // Truncated last-message preview — collapsed-row inline preview
        // (batch 283). 80-char cap matches the TRADE_MESSAGE notification
        // body so a notification + the inline preview read identically.
        Map preview = null
        if (lastMessage != null) {
            def body = lastMessage.body ?: ''
            preview = [
                body:         body.length() > 80 ? body.substring(0, 77) + '…' : body,
                senderUserId: lastMessage.senderUserId,
                createdAt:    lastMessage.createdAt,
                fromMe:       lastMessage.senderUserId == (t.buyerUserId == lastMessage.senderUserId
                    ? t.buyerUserId : t.sellerUserId)
            ]
        }
        [
            id:             t.id,
            listingId:      t.listingId,
            itemId:         t.itemId,
            itemName:       t.itemName,
            buyerUserId:    t.buyerUserId,
            sellerUserId:   t.sellerUserId,
            price:          t.price,
            feeAmount:      t.feeAmount,
            state:          t.state,
            note:           t.note,
            createdAt:      t.createdAt,
            updatedAt:      t.updatedAt,
            settledAt:      t.settledAt,
            // Seller "mark sent" timestamp (batch 550). Surfaced on the
            // /profile?tab=trades row so the buyer sees "📨 sent 2h ago"
            // at a glance and knows the Steam trade offer is live.
            sentAt:         t.sentAt,
            // Seller-provided Steam trade-offer URL (batch 773). Lets
            // the buyer jump directly to the offer from the Trades tab.
            // Null when the seller skipped the field or on legacy rows.
            tradeOfferUrl:  t.tradeOfferUrl,
            // Pre-computed deadline for the current state so the Profile →
            // Trades tab can render an "Expires in Xh" chip without having
            // to know the server-side timeout config. Null once the trade
            // settles. Seller-pending states expire on seller silence;
            // buyer-confirm expires on auto-release to seller.
            expiresAt:      computeExpiresAt(t),
            // Bulk-counted unread chat messages from the counterparty
            // (V37 / batch 281). Drives the "💬 N" chip on the trade row.
            unreadCount:    unreadCount,
            // Last-message preview (batch 283) — null when the trade
            // has no chat history yet. Frontend renders inline when
            // chat panel is closed.
            lastMessage:    preview,
            counterpartyTradeUrl: counterpartyTradeUrl,
            counterpartyName:     counterpartyName,
            // Direct link to the counterparty's public Steam profile
            // (batch 399). Sellers use this to sanity-check the account
            // before sending a Steam trade offer — a fresh account with
            // no games/friends is a red flag. Null when the counterparty
            // is the system / no Steam id resolved.
            counterpartySteamProfileUrl: counterpartySteamId
                ? "https://steamcommunity.com/profiles/${counterpartySteamId}"
                : null,
            // Counterparty avatar URL (batch 400). Lets the trade row
            // render a thumbnail-style identity chip next to the name so
            // buyers + sellers instantly recognise who they're dealing
            // with in a list of multiple trades. Null for system trades.
            counterpartyAvatarUrl: counterpartyAvatarUrl,
            // Counterparty review stats (batch 402). Trust signal shown
            // inline next to the identity chip — a seller about to send
            // a $500 skin to a 2★ / 1-review buyer can choose to dispute
            // or cancel instead. Null when reviewService isn't wired or
            // the counterparty has no reviews yet.
            counterpartyRating: counterpartyRatingSummary?.average,
            counterpartyReviewCount: counterpartyRatingSummary?.count
        ]
    }

    /** Absolute epoch-ms at which the current state auto-resolves (cancel or
     *  release). Null for settled + disputed trades — disputes pause every
     *  timer until staff acts. */
    private Long computeExpiresAt(Trade t) {
        if (t == null || t.updatedAt == null) return null
        switch (t.state) {
            case 'PENDING_SELLER_ACCEPT':
            case 'PENDING_SELLER_SEND':
                return t.updatedAt + (sellerResponseDays * 24L * 60L * 60L * 1000L)
            case 'PENDING_BUYER_CONFIRM':
                return t.updatedAt + (autoReleaseDays * 24L * 60L * 60L * 1000L)
            default:
                return null
        }
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

        // Notification bodies enriched with counterparty name + price
        // (batch 421). Previously the buyer just saw "Waiting for seller
        // to accept" with no info on how much they paid or who the
        // seller is; the seller saw "Accept the trade" with no buyer
        // name or sale amount. Both names + prices come from the same
        // SteamUser repo lookup the listing-card row already does.
        // Null-guarded so a test using a mocked repository (Spock returns
        // null from unstubbed findById) doesn't NPE.
        def buyerOpt  = (steamUserRepository != null) ? steamUserRepository.findById(buyerUserId) : null
        def sellerOpt = (sellerUserId != null && steamUserRepository != null)
                ? steamUserRepository.findById(sellerUserId) : null
        def buyer  = (buyerOpt  != null) ? buyerOpt.orElse(null)  : null
        def seller = (sellerOpt != null) ? sellerOpt.orElse(null) : null
        def buyerName  = buyer?.displayName  ?: 'a buyer'
        def sellerName = seller?.displayName ?: 'the seller'
        def priceStr   = "\$${price.toPlainString()}"
        // Batch 631: safePush — a bell failure must not roll back the
        // escrow + money-held step. Both sides get the bell via safePush.
        notificationService?.safePush(buyerUserId, 'TRADE_OPENED',
            "Escrow opened · ${itemName}",
            sellerUserId != null
                ? "${priceStr} held in escrow until ${sellerName} sends the Steam offer."
                : "${priceStr} held in escrow until the trade settles.",
            trade.id, '/profile?tab=trades')
        if (sellerUserId != null) {
            notificationService?.safePush(sellerUserId, 'TRADE_REQUESTED',
                "New sale · ${itemName}",
                "${buyerName} bought it for ${priceStr}. Accept the trade and send the Steam offer to release funds.",
                trade.id, '/profile?tab=trades')
            // New-sale email (batch 564). The in-app bell push is great for
            // users actively on the site, but most sellers aren't in-app
            // when a sale lands — they check email. A single transactional
            // email when a sale opens closes the "seller never saw it →
            // auto-cancel at day 3" loop. Gated on verified email +
            // global notification preference + per-bucket TRADES mute, so
            // an engaged seller who opted out stays quiet. Silent-fail so
            // an SMTP hiccup never rolls back the trade open.
            try {
                if (emailService != null && emailService.canSendTo(seller, 'TRADES')) {
                    emailService.sendTradeOpened(seller.email, seller.displayName,
                        itemName, buyerName, price, trade.id)
                }
            } catch (Exception e) {
                log.warn("Trade-opened email failed for seller ${sellerUserId}: ${e.message}")
            }
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
        notificationService?.safePush(t.buyerUserId, 'TRADE_ACCEPTED',
            "Seller accepted ${t.itemName}",
            "The seller now has to send the Steam trade offer.", t.id, '/profile?tab=trades')
        t
    }

    @Transactional
    Trade sellerMarkSent(Long sellerUserId, Long tradeId, String tradeOfferUrl = null) {
        banGuard.assertNotBanned(sellerUserId)
        def t = get(tradeId)
        requireParticipant(t, sellerUserId, 'seller')
        require(t.state == 'PENDING_SELLER_SEND', "Trade cannot be marked sent in state ${t.state}")
        // Stamp the ship-time before the state flip (batch 550). Powers the
        // "Typically ships in ~N hours" stall metric — measured from trade
        // creation to this seller click. Set once and kept across future
        // state transitions so the metric stays honest even if the trade
        // later bounces through DISPUTED / CANCELLED.
        if (t.sentAt == null) t.sentAt = System.currentTimeMillis()
        // Batch 773 — optional Steam trade-offer URL. Gives the buyer a
        // one-click link to the Steam offer from their Trades tab and
        // gives staff a concrete reference when triaging a dispute.
        // Validated to be a real `https://steamcommunity.com/tradeoffer/...`
        // URL. Empty string / null skips (optional field); non-empty but
        // malformed raises BadRequest so the UI can surface the error
        // instead of silently dropping what the seller thought was a
        // valid link (batch 860). Prior behaviour was to silently drop —
        // sellers had no signal their URL was invalid until a buyer
        // opened a dispute asking where the offer was.
        if (tradeOfferUrl != null) {
            def clean = tradeOfferUrl.trim()
            if (clean.length() > 0) {
                if (clean.length() > 200) {
                    throw new BadRequestException("TRADE_OFFER_URL_TOO_LONG",
                        "Steam trade offer URL must be 200 characters or fewer.")
                }
                if (!clean.matches("^https://steamcommunity\\.com/tradeoffer/[A-Za-z0-9_?&=/\\-]+\$")) {
                    throw new BadRequestException("TRADE_OFFER_URL_INVALID",
                        "Trade offer URL must start with https://steamcommunity.com/tradeoffer/… — copy the full link from Steam.")
                }
                t.tradeOfferUrl = clean
            }
        }
        transitionTo(t, 'PENDING_BUYER_CONFIRM')
        notificationService?.safePush(t.buyerUserId, 'TRADE_SENT',
            "Steam trade offer sent · ${t.itemName}",
            "Confirm the trade on Steam and then click Confirm Receipt.", t.id, '/profile?tab=trades')
        // Trade-sent email to the buyer (batch 565). Pairs with the new
        // TRADE_OPENED email (batch 564) so the full "sale → shipped →
        // confirmed" loop has an email touchpoint at each stage. Same
        // verified-email + opt-in + bucket-mute gating as the rest of
        // the trade email path. Silent-fail on any error.
        try {
            if (emailService != null && steamUserRepository != null && t.buyerUserId != null) {
                def buyer = steamUserRepository.findById(t.buyerUserId).orElse(null)
                def seller = t.sellerUserId != null
                    ? steamUserRepository.findById(t.sellerUserId).orElse(null)
                    : null
                if (emailService.canSendTo(buyer, 'TRADES')) {
                    emailService.sendTradeSent(buyer.email, buyer.displayName,
                        t.itemName, seller?.displayName, t.id)
                }
            }
        } catch (Exception e) {
            log.warn("Trade-sent email failed for buyer ${t.buyerUserId}: ${e.message}")
        }
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
     * Admin-path release. Skips the ban-guard on the buyer (admin may need
     * to release a trade even when the buyer was banned since the sale)
     * and the PENDING_BUYER_CONFIRM state gate (admin is releasing from a
     * DISPUTED or other non-terminal state). Still refuses to operate on
     * terminal states. Caller is responsible for the admin-auth check;
     * this method is package-private-ish via the force-release path.
     */
    @Transactional
    Trade adminRelease(Long adminUserId, Long tradeId, String reason) {
        adminAuthorization?.requireAdmin(adminUserId)
        def t = get(tradeId)
        require(!(t.state in ['VERIFIED','CANCELLED']), "Trade cannot be released in state ${t.state}")
        if (reason != null && !reason.trim().isEmpty()) {
            t.note = textSanitizer.medium(reason)
        }
        release(t)
        auditService?.log(AuditService.TRADE_FORCE_RELEASED, adminUserId, t.sellerUserId, t.id,
            "Force-released: ${reason ?: '(no reason)'}")
        t
    }

    /**
     * Transition → VERIFIED. Credits the seller wallet with (price - fee) and
     * records matching SALE transactions on both sides. Called by
     * `buyerConfirm` and by the auto-release sweep after the trade-hold window.
     */
    @Transactional
    protected void release(Trade t, boolean autoRelease = false) {
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
        notificationService?.safePush(t.buyerUserId, 'TRADE_VERIFIED',
            "Trade verified · ${t.itemName}", null, t.id, '/profile?tab=trades')
        // Auto-release email to the buyer (batch 601). Only fires when
        // the 8-day sweeper triggered this release — the manual-confirm
        // path means the buyer is actively on the site and doesn't need
        // a "trade verified" email. Gated on TRADES bucket + verified
        // email like every other transactional send.
        if (autoRelease && t.buyerUserId != null) {
            try {
                if (emailService != null && steamUserRepository != null) {
                    def buyer = steamUserRepository.findById(t.buyerUserId).orElse(null)
                    if (emailService.canSendTo(buyer, 'TRADES')) {
                        emailService.sendTradeAutoReleased(buyer.email, buyer.displayName,
                            t.itemName, t.id)
                    }
                }
            } catch (Exception e) {
                log.warn("Auto-release email to buyer ${t.buyerUserId} failed: ${e.message}")
            }
        }
        if (t.sellerUserId != null) {
            notificationService?.safePush(t.sellerUserId, 'TRADE_VERIFIED',
                "Funds released · ${t.itemName}",
                "\$${(t.price - t.feeAmount)} credited to your wallet.", t.id, '/wallet')
            // Email the seller too — the auto-release path fires 8 days
            // after the buyer went quiet, which is plenty of time for a
            // seller to stop checking the in-app bell. A simple
            // transactional email closes the loop so they know the
            // money actually arrived. Gated on verified email +
            // emailNotificationsEnabled like every other transactional
            // send. Null / non-verified email silently skips.
            try {
                if (emailService != null && steamUserRepository != null) {
                    def seller = steamUserRepository.findById(t.sellerUserId).orElse(null)
                    if (emailService.canSendTo(seller, 'TRADES')) {
                        emailService.sendSaleCompleted(seller.email, seller.displayName,
                            t.itemName, (t.price - t.feeAmount), '/wallet')
                    }
                }
            } catch (Exception e) {
                log.warn("Sale-completed email failed for seller ${t.sellerUserId}: ${e.message}")
            }
            // Review nudge — deep-links the buyer to the seller's
            // stall, where the "Leave a review" CTA already surfaces
            // every verified trade (eligibleTrades from ReviewService).
            // Distinct kind from TRADE_VERIFIED so a user who muted
            // wallet notifications still gets this one, and the bell
            // icon maps it to a star glyph.
            notificationService?.safePush(t.buyerUserId, 'REVIEW_REMINDER',
                "How did the trade go?",
                "Leave a review for the seller — takes 10 seconds.",
                t.id, "/stall/${t.sellerUserId}".toString())
        }
        auditService?.log(AuditService.TRADE_VERIFIED, t.buyerUserId, t.sellerUserId, t.id,
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
        auditService?.log(AuditService.TRADE_DISPUTED, actorUserId, disputeSubject, t.id, "Disputed: ${t.note ?: '(none)'}")
        // Notify the counterparty so they know a dispute is live against
        // them — otherwise the only signal was a staff follow-up hours
        // later. Both sides also get it if a staff member escalates,
        // since disputeSubject is the "other party" in both cases. The
        // filer doesn't get a self-notification — they just clicked the
        // button, they know. Note-length capped at 140 chars so long
        // complaints don't blow out the notification row.
        def itemName = t.itemName ?: 'your trade'
        def bodySnippet = (t.note ?: '').take(140)
        if (disputeSubject != null) {
            try {
                notificationService?.push(disputeSubject, 'TRADE_DISPUTED',
                    "Trade disputed · ${itemName}",
                    bodySnippet.isEmpty() ? 'A staff review is now in progress.' : bodySnippet,
                    t.id, '/profile?tab=trades')
            } catch (Exception e) {
                log.warn("TRADE_DISPUTED push to counterparty ${disputeSubject} failed: ${e.message}")
            }
            // Counterparty email (batch 567). Frozen escrow + silent
            // staff review means the counterparty needs to respond
            // fast; a push alone is easy to miss across timezones.
            // Same opt-in + bucket-mute gate as the trade-open /
            // trade-sent emails. Silent-fail via try/catch.
            try {
                if (emailService != null && steamUserRepository != null) {
                    def cp = steamUserRepository.findById(disputeSubject).orElse(null)
                    if (emailService.canSendTo(cp, 'TRADES')) {
                        def filerRole = (actorUserId == t.buyerUserId) ? 'BUYER' : 'SELLER'
                        emailService.sendTradeDisputed(cp.email, cp.displayName,
                            itemName, filerRole, t.note, t.id)
                    }
                }
            } catch (Exception e) {
                log.warn("TRADE_DISPUTED email to counterparty ${disputeSubject} failed: ${e.message}")
            }
        }
        // Admin fan-out (batch 500). Disputes used to land silently in
        // the admin /admin?tab=trades queue — staff had to manually
        // refresh to spot a new one. Now every admin gets a bell ping
        // the moment the dispute is filed, deep-linked to the trades
        // tab filtered to DISPUTED. Same fan-out shape as
        // StripeService.handleChargebackOpened (batch 461) so the role-
        // indexed `findByRole('ADMIN')` keeps the query O(admin count).
        if (notificationService != null && steamUserRepository != null) {
            try {
                def filerLabel = (actorUserId == t.buyerUserId) ? 'Buyer' : 'Seller'
                steamUserRepository.findByRole('ADMIN').each { admin ->
                    try {
                        notificationService.push(admin.id, 'TRADE_DISPUTED',
                            "⚠ New trade dispute · ${itemName}",
                            "${filerLabel} filed a dispute on trade #${t.id}" +
                                (bodySnippet.isEmpty() ? '.' : ": ${bodySnippet}"),
                            t.id, '/admin?tab=trades&filter=DISPUTED')
                    } catch (Exception e) {
                        log.warn("TRADE_DISPUTED admin-push failed for uid=${admin.id}: ${e.message}")
                    }
                }
            } catch (Exception e) {
                log.warn("TRADE_DISPUTED admin fan-out failed: ${e.message}")
            }
        }
        t
    }

    @Transactional
    Trade cancel(Long actorUserId, Long tradeId, String reason) {
        def t = get(tradeId)
        boolean isAdmin = false
        // Either participant OR an admin/CSR may cancel.
        if (actorUserId != t.buyerUserId && actorUserId != t.sellerUserId) {
            adminAuthorization.requireAdmin(actorUserId)
            isAdmin = true
        } else {
            // Participant path — banned users can't cancel trades themselves.
            // Admins calling cancel bypass this (they already passed the admin check).
            banGuard.assertNotBanned(actorUserId)
        }
        require(t.state != 'VERIFIED' && t.state != 'CANCELLED',
            "Trade cannot be cancelled in state ${t.state}")
        // Anti-theft guard (batch 326). Once the seller marks the Steam
        // trade offer as sent (state = PENDING_BUYER_CONFIRM), the buyer
        // must not be able to unilaterally cancel — they could accept
        // the Steam offer on their side (taking the item) and then
        // flip the trade to CANCELLED to get their money refunded too.
        // At this state the only buyer-available path is DISPUTE, which
        // routes to staff. Admins can still force-cancel for CSR cases
        // (stuck-trade cleanup, confirmed seller fraud, etc).
        if (!isAdmin && actorUserId == t.buyerUserId && t.state == 'PENDING_BUYER_CONFIRM') {
            throw new BadRequestException("BUYER_CANT_CANCEL_AFTER_SENT",
                "Seller has already marked the Steam trade offer as sent. " +
                "If you didn't receive the item or the wrong item arrived, open a dispute instead.")
        }

        refundBuyer(t)
        returnListingToSeller(t)
        // Sanitize the user-supplied reason before it lands in the trade
        // note AND the notification body. React auto-escapes, but defense
        // in depth stops a crafted payload from riding through every UI
        // surface that might later render a cancel reason.
        def cleanReason = textSanitizer.medium(reason)
        t.note = cleanReason
        t.settledAt = System.currentTimeMillis()
        transitionTo(t, 'CANCELLED')
        notificationService?.safePush(t.buyerUserId, 'TRADE_CANCELLED',
            "Trade cancelled · refund issued", cleanReason, t.id, '/profile?tab=trades')
        if (t.sellerUserId != null) {
            notificationService?.safePush(t.sellerUserId, 'TRADE_CANCELLED',
                "Trade cancelled", cleanReason, t.id, '/profile?tab=trades')
        }
        // Log the counterparty as the audit subject
        def cancelSubject = (actorUserId == t.buyerUserId) ? t.sellerUserId :
                            (actorUserId == t.sellerUserId) ? t.buyerUserId : t.buyerUserId
        auditService?.log(AuditService.TRADE_CANCELLED, actorUserId, cancelSubject, t.id, "Cancelled: ${cleanReason ?: '(none)'}")
        // Email both sides (batch 573). Buyer side especially matters
        // — their money was just refunded from escrow and the bell
        // push is easy to miss over hours. Gated the same as every
        // other transactional trade email. Single helper handles
        // both sides via role param so the recipient's body reads
        // from THEIR perspective.
        fireTradeCancelledEmail(t, cleanReason, 'buyer')
        fireTradeCancelledEmail(t, cleanReason, 'seller')
        t
    }

    /** Shared trade-cancel email helper (batch 573). Fires a single
     *  sendTradeCancelled to the given side if they have a verified
     *  email + notification opt-in + TRADES bucket unmuted. Silent-
     *  fail on any error. Called from the manual-cancel path AND the
     *  day-3 auto-cancel sweeper so both paths have identical
     *  notification shape. */
    private void fireTradeCancelledEmail(Trade t, String reason, String role) {
        def targetUid = role == 'buyer' ? t.buyerUserId : t.sellerUserId
        if (targetUid == null || emailService == null || steamUserRepository == null) return
        try {
            def u = steamUserRepository.findById(targetUid).orElse(null)
            if (emailService.canSendTo(u, 'TRADES')) {
                emailService.sendTradeCancelled(u.email, u.displayName,
                    t.itemName, reason, role, t.id)
            }
        } catch (Exception e) {
            log.warn("Trade-cancelled email failed for ${role} ${targetUid}: ${e.message}")
        }
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

    /**
     * Return the cancelled trade's listing to the seller's inventory so
     * they can relist it. Without this, a cancel refunds the buyer but
     * leaves `listing.buyerUserId` pointing at the (refunded) buyer —
     * the buyer sees a phantom item in Platform Inventory and the
     * seller can never get their item back. Mirrors the
     * SellService.cancelListing / BidService no-bids pattern:
     * `status = SOLD + buyerUserId = sellerUserId + soldAt = now` so
     * `findOwnedBy` surfaces the row in the seller's inventory.
     *
     * System listings (sellerUserId == null) are left untouched — no
     * one to return to.
     */
    private void returnListingToSeller(Trade t) {
        if (t.sellerUserId == null || t.listingId == null || listingRepository == null) return
        // Spock mocks return null (not Optional.empty) when no stub is
        // defined, so guard against both. Production JPA always yields
        // a non-null Optional.
        def opt = listingRepository.findById(t.listingId)
        def listing = (opt != null) ? opt.orElse(null) : null
        if (listing == null) return
        // Skip no-op — if the listing was already returned (double-
        // cancel after admin override) don't re-stamp soldAt.
        if (listing.status == 'SOLD' && listing.buyerUserId == t.sellerUserId) return
        listing.status      = 'SOLD'
        listing.buyerUserId = t.sellerUserId
        listing.soldAt      = System.currentTimeMillis()
        listingRepository.save(listing)
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
    /**
     * Auto-cancel sweeper for PENDING_SELLER_ACCEPT / PENDING_SELLER_SEND
     * trades where the seller has been silent past the response window.
     * Without this, an unresponsive seller can hold buyer funds in
     * escrow indefinitely — the confirm sweeper below only handles the
     * post-send tail. Refunds the buyer wallet, transitions to CANCELLED,
     * and pings both sides.
     *
     * Runs every 30 minutes, offset from the confirm sweeper so the two
     * don't contend on the trades table at the same tick.
     */
    @Scheduled(fixedDelay = 30L * 60L * 1000L, initialDelay = 10L * 60L * 1000L)
    void sweepStaleSellerResponse() {
        def cutoff = System.currentTimeMillis() - (sellerResponseDays * 24L * 60L * 60L * 1000L)
        def candidates = tradeRepository.findStaleSellerPending(cutoff)
        if (candidates.isEmpty()) return
        log.info("Trade sweeper (seller): ${candidates.size()} stale seller-pending trades found, auto-cancelling")
        candidates.each { trade ->
            try {
                autoCancelStaleSellerTrade(trade)
            } catch (Exception e) {
                log.warn("Seller-response sweeper failed on ${trade.id}: ${e.message}")
            }
        }
    }

    /**
     * Slow-seller WARNING sweep — runs hourly, finds trades sitting
     * in PENDING_SELLER_ACCEPT / PENDING_SELLER_SEND for >24h that
     * haven't yet received a TRADE_SLOW_SELLER push (V35 column +
     * partial index). Pings the buyer with a heads-up so they have
     * time to chat with the seller / file a dispute / decide to wait
     * before the auto-cancel sweep kicks in at the 3-day mark.
     *
     * Idempotent — the partial index limits the work to un-warned
     * rows; setting `slowSellerWarnedAt` removes the row from the
     * candidate set on subsequent ticks.
     */
    /**
     * 48h-after-verification review nudge sweep. The initial REVIEW_REMINDER
     * push (TradeService.release) fires inline at verification time but a
     * buyer who walked away from the bell often misses it. This sweep
     * sends a SECOND nudge ~48h later — one-shot per trade, gated by the
     * V38 `review_nudge_sent_at` column + partial index, and only for
     * trades the buyer hasn't already reviewed (LEFT JOIN exclusion in the
     * repo query).
     *
     * Runs daily at offset to avoid contending with the other sweepers.
     */
    @Scheduled(fixedDelay = 24L * 60L * 60L * 1000L, initialDelay = 30L * 60L * 1000L)
    @Transactional
    void sweepReviewNudge() {
        // 48-hour threshold — gives a buyer two days after the trade
        // verifies before pinging again, but soon enough that the
        // experience is still fresh enough to write a useful review.
        def cutoff = System.currentTimeMillis() - (48L * 60L * 60L * 1000L)
        def candidates = tradeRepository.findReviewNudgeCandidates(cutoff)
        if (candidates.isEmpty()) return
        log.info("Review-nudge sweep: ${candidates.size()} verified-unreviewed trade(s) past 48h, re-nudging buyers")
        // Bulk lookup buyers once + filter banned (batch 329). Same
        // pattern as batch 320 for AUCTION_ENDING. A banned user can't
        // leave a review anyway (banGuard on leaveReview), so the nudge
        // is pure noise. Also still flip `reviewNudgeSentAt` for
        // banned-buyer trades so the sweep doesn't re-scan them.
        def buyerIds = candidates*.buyerUserId.findAll { it != null }.unique()
        def bannedBuyers = new HashSet<Long>()
        if (!buyerIds.isEmpty() && steamUserRepository != null) {
            try {
                steamUserRepository.findAllById(buyerIds).each {
                    if (Boolean.TRUE.equals(it.banned)) bannedBuyers.add(it.id)
                }
            } catch (Exception e) {
                log.warn("Review-nudge banned-filter lookup failed: ${e.message}")
            }
        }
        candidates.each { trade ->
            try {
                // Flip the marker even for banned buyers so we don't
                // keep re-scanning them every 24h.
                trade.reviewNudgeSentAt = System.currentTimeMillis()
                tradeRepository.save(trade)
                if (bannedBuyers.contains(trade.buyerUserId)) return
                notificationService?.push(trade.buyerUserId, 'REVIEW_REMINDER',
                    "How was your trade with ${trade.itemName ?: 'the seller'}?",
                    "Two days since it cleared — leave a quick review while it's fresh. Takes 10 seconds.",
                    trade.id, "/stall/${trade.sellerUserId}".toString())
            } catch (Exception e) {
                log.warn("Review-nudge push failed on trade ${trade.id}: ${e.message}")
            }
        }
    }

    @Scheduled(fixedDelay = 60L * 60L * 1000L, initialDelay = 5L * 60L * 1000L)
    @Transactional
    void sweepSlowSellerWarning() {
        // 24-hour silence threshold — half of the seller-response
        // auto-cancel window so the buyer always gets >=1 ping before
        // the trade evaporates.
        def cutoff = System.currentTimeMillis() - (24L * 60L * 60L * 1000L)
        def candidates = tradeRepository.findSlowSellerUnwarned(cutoff)
        if (candidates.isEmpty()) return
        log.info("Slow-seller warning sweep: ${candidates.size()} trade(s) past 24h with no warning yet")
        // Batch 329 ban filter — banned buyers can't dispute or chat
        // anyway (banGuard on postMessage + dispute). Skip their pings
        // but still stamp the warning marker so the sweep doesn't
        // re-scan the same trade indefinitely.
        def buyerIds = candidates*.buyerUserId.findAll { it != null }.unique()
        def bannedBuyers = new HashSet<Long>()
        if (!buyerIds.isEmpty() && steamUserRepository != null) {
            try {
                steamUserRepository.findAllById(buyerIds).each {
                    if (Boolean.TRUE.equals(it.banned)) bannedBuyers.add(it.id)
                }
            } catch (Exception e) {
                log.warn("Slow-seller banned-filter lookup failed: ${e.message}")
            }
        }
        candidates.each { trade ->
            try {
                trade.slowSellerWarnedAt = System.currentTimeMillis()
                tradeRepository.save(trade)
                def hoursIdle = Math.max(24L, (long) ((System.currentTimeMillis() - (trade.updatedAt ?: 0L)) / 3_600_000L))
                if (!bannedBuyers.contains(trade.buyerUserId)) {
                    notificationService?.push(trade.buyerUserId, 'TRADE_SLOW_SELLER',
                        "Trade #${trade.id} — seller hasn't responded",
                        "Idle ${hoursIdle}h on ${trade.itemName ?: 'your trade'}. Auto-cancels at the ${sellerResponseDays}-day mark; chat the seller or file a dispute if you suspect a problem.",
                        trade.id, "/profile?tab=trades&openChat=${trade.id}".toString())
                }
                // Seller-side nudge (batch 563). The existing warning only
                // pings the BUYER ("your seller is slow") — the seller
                // themselves might have genuinely forgotten. One push at
                // the 24h mark, keyed on the same sweeper so both sides
                // see the "your turn to act" prompt simultaneously. The
                // `slowSellerWarnedAt` stamp above guards against repeat
                // nudges on every sweep tick. Skipped when the seller is
                // banned (banned users can't accept / send anyway).
                if (trade.sellerUserId != null) {
                    def sellerBanned = false
                    try {
                        def seller = steamUserRepository?.findById(trade.sellerUserId)?.orElse(null)
                        sellerBanned = seller != null && Boolean.TRUE.equals(seller.banned)
                    } catch (Exception ignore) { /* conservatively push */ }
                    if (!sellerBanned) {
                        try {
                            notificationService?.push(trade.sellerUserId, 'TRADE_SELLER_NUDGE',
                                "Buyer is waiting · ${trade.itemName ?: 'Trade ' + trade.id}",
                                "You've had this trade for ${hoursIdle}h. ${trade.state == 'PENDING_SELLER_ACCEPT' ? 'Accept & send a Steam trade offer' : 'Send the Steam trade offer'} now, or it auto-cancels at the ${sellerResponseDays}-day mark and refunds the buyer.",
                                trade.id, "/profile?tab=trades&openChat=${trade.id}".toString())
                        } catch (Exception e) {
                            log.warn("Seller-nudge push failed on trade ${trade.id}: ${e.message}")
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Slow-seller warning push failed on trade ${trade.id}: ${e.message}")
            }
        }
    }

    /** Per-trade transaction for the seller-timeout sweep. Marked
     *  protected so Spring's proxy still wraps the call in a tx when
     *  invoked via `this` inside the sweeper loop above — each refund
     *  + state transition is atomic, so one failure mid-loop doesn't
     *  half-cancel a trade. */
    @Transactional
    protected void autoCancelStaleSellerTrade(Trade trade) {
        refundBuyer(trade)
        returnListingToSeller(trade)
        trade.note = "Automatically cancelled — seller did not respond within ${sellerResponseDays} days"
        trade.settledAt = System.currentTimeMillis()
        transitionTo(trade, 'CANCELLED')
        // Batch 631: both sides migrated to safePush — a bell failure
        // must not roll back the refundBuyer + returnListingToSeller
        // steps above. The helper handles the swallow + log internally.
        if (trade.buyerUserId != null) {
            notificationService?.safePush(trade.buyerUserId, 'TRADE_CANCELLED',
                "Trade auto-cancelled · refund issued",
                "The seller did not respond in ${sellerResponseDays} days. Your wallet has been refunded.",
                trade.id, '/profile?tab=trades')
        }
        if (trade.sellerUserId != null) {
            notificationService?.safePush(trade.sellerUserId, 'TRADE_CANCELLED',
                "Trade auto-cancelled · ${trade.itemName ?: 'item'}",
                "We cancelled this trade because you didn't respond in ${sellerResponseDays} days. Relist the item if you'd still like to sell.",
                trade.id, '/profile?tab=trades')
        }
        // Auto-cancel email fan-out (batch 573). Buyer gets "your
        // refund is back"; seller gets "relist if you still want to
        // sell." Same gating as every other trade email.
        fireTradeCancelledEmail(trade,
            "Seller did not respond within ${sellerResponseDays} days — your wallet has been refunded.",
            'buyer')
        fireTradeCancelledEmail(trade,
            "You didn't respond within ${sellerResponseDays} days, so the trade was auto-cancelled. Relist from Profile → Sold to try again.",
            'seller')
        auditService?.log(AuditService.TRADE_AUTO_CANCELLED, null, trade.sellerUserId, trade.id,
            "Seller-response timeout after ${sellerResponseDays}d")
    }

    /**
     * Median ship-time (ms) for a seller across VERIFIED trades in the
     * last {@code lookbackDays} days (batch 550). Drives the
     * "Typically ships in ~N hours" stat on the public stall page.
     *
     * Returns null if fewer than 3 samples are available — one lucky fast
     * ship shouldn't advertise a misleading headline. Median instead of
     * mean because a single day-long wait from the one seller who went on
     * holiday would skew the mean badly.
     */
    Long typicalShipMs(Long sellerUserId, int lookbackDays = 90) {
        if (sellerUserId == null) return null
        def since = System.currentTimeMillis() - (lookbackDays * 24L * 60L * 60L * 1000L)
        def samples
        try {
            samples = tradeRepository.findRecentShipMsForSeller(sellerUserId, since)
        } catch (Exception e) {
            log.debug("typicalShipMs lookup failed for ${sellerUserId}: ${e.message}")
            return null
        }
        if (samples == null || samples.size() < 3) return null
        def sorted = samples.findAll { it != null && it >= 0L }.sort()
        if (sorted.size() < 3) return null
        def mid = sorted.size().intdiv(2)
        sorted.size() % 2 == 0
            ? ((sorted[mid - 1] + sorted[mid]) / 2L) as Long
            : sorted[mid] as Long
    }

    /** Sample count backing {@link #typicalShipMs} — so the UI can render
     *  "based on N trades" for transparency. Returns 0 for an unknown or
     *  zero-volume seller. */
    int typicalShipSampleCount(Long sellerUserId, int lookbackDays = 90) {
        if (sellerUserId == null) return 0
        def since = System.currentTimeMillis() - (lookbackDays * 24L * 60L * 60L * 1000L)
        try {
            def samples = tradeRepository.findRecentShipMsForSeller(sellerUserId, since)
            return samples == null ? 0 : samples.size()
        } catch (Exception ignored) {
            return 0
        }
    }

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
                    returnListingToSeller(trade)
                    trade.note = "Automatically cancelled — seller account banned"
                    trade.settledAt = System.currentTimeMillis()
                    transitionTo(trade, 'CANCELLED')
                    notificationService?.push(trade.buyerUserId, 'TRADE_CANCELLED',
                        "Trade cancelled · refund issued", trade.note, trade.id, '/profile?tab=trades')
                    auditService?.log(AuditService.TRADE_AUTO_CANCELLED, null, trade.sellerUserId, trade.id,
                        "Seller banned — auto-cancelled after ${autoReleaseDays}d window")
                } else {
                    // autoRelease=true fires the buyer-side email
                    // (batch 601) — the 8-day-silent buyer probably
                    // isn't checking the bell.
                    release(trade, true)
                    auditService?.log(AuditService.TRADE_AUTO_RELEASED, null, null, trade.id,
                        "Auto-released after ${autoReleaseDays}d no-confirm window")
                }
            } catch (Exception e) {
                log.warn("Trade sweeper failed on ${trade.id}: ${e.message}")
            }
        }
    }
}
