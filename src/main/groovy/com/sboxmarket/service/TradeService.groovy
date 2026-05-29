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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

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
    // Optional — decorates trade rows with itemImageUrl + itemAccentColor
    // so the Profile → Trades tab can render an item thumbnail next to the
    // name (parity with LoadoutService.getWithSlots / Cart row pattern).
    // `required = false` so older test contexts still wire without the
    // catalogue tier.
    @Autowired(required = false) com.sboxmarket.repository.ItemRepository itemRepository
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
    // PlatformTransactionManager for per-trade isolated transactions in
    // the scheduled sweepers. The sweeps loop over candidate trades and
    // self-invoke the per-trade helpers (release, autoCancelStaleSellerTrade,
    // autoCancelBannedSellerTrade) — Spring's CGLIB proxy is BYPASSED on
    // self-invocation, so the @Transactional annotation on those helpers
    // is a no-op when entered via the sweeps. Without a real per-trade tx,
    // a refundBuyer wallet save that succeeds followed by a transitionTo
    // save that fails (concurrent dispute → optimistic-lock) leaves the
    // buyer credited but the trade still in PENDING_SELLER_* — the next
    // 30-min sweep tick re-finds it and refunds AGAIN. Mirrors the
    // BidService.runInIsolatedTx pattern (commit on settle isolation).
    // Required = false so unit-test contexts that build TradeService via
    // the property-map constructor (no Spring context) still wire — the
    // helper falls back to inline execution when the manager is null.
    @Autowired(required = false) PlatformTransactionManager transactionManager
    // Optional — the Trade Protection add-on. When a trade carries a
    // protection record, the dispute / timeout-loss paths auto-claim it
    // (full item-price refund to the buyer, no support ticket) and the
    // normal-release / cancel paths expire it (cover consumed, fee
    // kept). `required = false` so older test contexts still wire.
    @Autowired(required = false) TradeProtectionService tradeProtectionService

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
        // Pagination cap (bug class #9). Previously this called
        // `findByParticipant` UNBOUNDED — a power-user with thousands
        // of historical trades would force the server to serialise the
        // entire history on every call, blowing memory + latency on
        // the JSON marshal step. The sibling `listForUserWithCounterparty`
        // already caps via `findByParticipantPaged` + TRADE_LIST_CAP=200;
        // this method is the public Trade-entity entry point on the
        // service and was the matching outlier. Older trades remain
        // queryable by id via /api/trades/{id}.
        tradeRepository.findByParticipantPaged(userId,
            org.springframework.data.domain.PageRequest.of(0, TRADE_LIST_CAP))
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
        // Item-thumb decoration — bulk findAllById over the unique item
        // ids referenced in this user's trades, then index by id. One
        // round-trip vs N (or zero, if itemRepository isn't wired in
        // legacy test contexts). Mirrors the LoadoutService.getWithSlots
        // pattern (commit 9fab32f). Legacy trades whose itemId no longer
        // resolves fall through to null and the frontend renders the
        // generic gift-box icon.
        Map<Long, Map> itemDecorById = [:]
        if (itemRepository != null) {
            def itemIds = trades*.itemId.findAll { it != null }.unique()
            if (!itemIds.isEmpty()) {
                try {
                    itemRepository.findAllById(itemIds).each { it ->
                        itemDecorById[it.id] = [imageUrl: it.imageUrl, accentColor: it.accentColor]
                    }
                } catch (Exception e) {
                    log.warn("Trade item-thumb lookup failed for user ${userId}: ${e.message}")
                }
            }
        }
        // Counterparty review summaries (batch 402). One bulk GROUP BY
        // query across every unique counterparty id, instead of N
        // single-row aggregates inside a loop. Even at <10 unique
        // counterparties the round-trip savings are worth it on a
        // tab-open hot path. Review service is optional in test contexts.
        Map<Long, Map> ratingByUser = [:]
        if (reviewService != null && !ids.isEmpty()) {
            try {
                ratingByUser = reviewService.summariesForUsers(ids) ?: [:]
            } catch (Exception e) {
                log.warn("Counterparty rating bulk lookup failed: ${e.message}")
            }
        }
        trades.collect { t ->
            def cpId = t.buyerUserId == userId ? t.sellerUserId : t.buyerUserId
            def cp = cpId == null ? null : byId[cpId]
            def cpRating = cpId == null ? null : ratingByUser[cpId]
            def itemDecor = t.itemId == null ? null : itemDecorById[t.itemId]
            tradeToMap(t, cp?.tradeUrl, cp?.displayName, cp?.steamId64, cp?.avatarUrl,
                cpRating,
                unreadCounts[t.id] ?: 0L, lastMessages[t.id], itemDecor, userId)
        }
    }

    private Map tradeToMap(Trade t, String counterpartyTradeUrl, String counterpartyName,
                           String counterpartySteamId = null,
                           String counterpartyAvatarUrl = null,
                           Map counterpartyRatingSummary = null,
                           long unreadCount = 0L,
                           com.sboxmarket.model.TradeMessage lastMessage = null,
                           Map itemDecor = null,
                           Long viewerUserId = null) {
        // Truncated last-message preview — collapsed-row inline preview
        // (batch 283). 80-char cap matches the TRADE_MESSAGE notification
        // body so a notification + the inline preview read identically.
        Map preview = null
        if (lastMessage != null) {
            def body = lastMessage.body ?: ''
            // `fromMe` reflects "was this message sent by the viewer of
            // the list?". The old expression collapsed to a tautology
            // (`senderUserId == (buyerUserId == senderUserId ? buyerUserId
            // : sellerUserId)`) that always evaluated `true` regardless of
            // who actually sent the message, because the viewer's id was
            // never threaded through. Compare directly against the viewer
            // now; null `viewerUserId` (legacy single-arg callers) falls
            // back to `false` so callers without viewer context don't
            // falsely claim ownership.
            preview = [
                body:         body.length() > 80 ? body.substring(0, 77) + '…' : body,
                senderUserId: lastMessage.senderUserId,
                createdAt:    lastMessage.createdAt,
                fromMe:       viewerUserId != null && lastMessage.senderUserId == viewerUserId
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
            // Item thumbnail + accent — bulk-decorated above. Frontend
            // renders a 44px contain-fit thumbnail next to the item
            // name on each trade row; null falls back to the generic
            // gift-box MaterialIcon ('inventory_2'). Both fields are
            // null on legacy trades whose itemId no longer resolves
            // in the catalogue.
            itemImageUrl:    itemDecor?.imageUrl,
            itemAccentColor: itemDecor?.accentColor,
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
        // Audit actor: on the MANUAL buyer-confirm path the buyer is the
        // actor (they clicked Confirm Receipt). On the AUTO-release sweep
        // path the buyer did NOTHING — the system released the funds after
        // `autoReleaseDays` of buyer silence — so the actor is the system
        // (null), matching the sibling TRADE_AUTO_RELEASED /
        // TRADE_AUTO_CANCELLED rows (actor=null, subject=sellerUserId).
        // Attributing the auto-release to `t.buyerUserId` falsely records
        // a privileged money-moving action as something the buyer did,
        // corrupting the actor-keyed audit trail (`byActor`) and the
        // buyer's own security-activity view. The subject stays
        // sellerUserId either way — the seller is the party who got paid.
        auditService?.log(AuditService.TRADE_VERIFIED,
            autoRelease ? null : t.buyerUserId, t.sellerUserId, t.id,
            "Verified trade #${t.id} for \$${t.price}")
        // Trade Protection resolution. Normally the trade completed
        // cleanly, so any ACTIVE cover lapses (ACTIVE → EXPIRED) and the
        // fee is kept as revenue. BUT a disputed protected trade was
        // already auto-claimed at dispute time — the buyer was refunded
        // the full item price. If staff then force-releases that trade
        // (ruling the seller delivered), crediting the seller above
        // while leaving the buyer's claim standing double-pays the
        // buyer (keeps the item AND the refund). So a CLAIMED protection
        // is reversed here, reclaiming the payout — the release-path
        // twin of cancel()'s `alreadyPaidByProtection` guard. Best-
        // effort: a protection hiccup must not roll back the seller
        // credit + VERIFIED transition above.
        //
        // The pre-fix unlocked `findForTrade(t.id)` raced with a concurrent
        // dispute → autoClaim REQUIRES_NEW. Order of operations on a
        // protected PENDING_BUYER_CONFIRM trade:
        //   • Thread B (dispute) acquires the protection row lock via
        //     autoClaim, credits the buyer, flips ACTIVE → CLAIMED, commits.
        //   • Thread A (release) reads `findForTrade` UNLOCKED, but TIMING
        //     decides whether READ_COMMITTED shows ACTIVE or CLAIMED. If
        //     Thread A reads BEFORE autoClaim's REQUIRES_NEW commit lands,
        //     it sees ACTIVE → falls through to `expire()`. expire() then
        //     does its own unlocked read in REQUIRES_NEW — by the time it
        //     runs, autoClaim has committed CLAIMED, so expire's `status !=
        //     ACTIVE` gate bails. NO reverseClaim ever runs.
        //   • Thread A's outer commit succeeds (or wins the optimistic-lock
        //     race vs Thread B). Seller is credited price-fee.
        //   • Thread B's outer commit fails @Version → DISPUTED rolls back,
        //     but autoClaim's REQUIRES_NEW commit is durable. Buyer keeps
        //     the protection payout.
        //   • Net: seller paid AND buyer paid the cover. Platform eats the
        //     full item price. Symmetric to the cancel × autoClaim race
        //     wave 105 closed.
        //
        // Fix: route through `lockAndExpireIfActiveOrReportClaimed` — the
        // same arbiter cancel() uses. It acquires the protection row's
        // pessimistic write lock inside release's outer tx (REQUIRED
        // propagation), atomically reports CLAIMED-or-flips-ACTIVE-to-
        // EXPIRED, and the lock is held until release commits. A
        // concurrent autoClaim either runs FIRST (we see CLAIMED, call
        // reverseClaim to claw back) or BLOCKS on the lock and re-reads
        // EXPIRED after we commit (its `status != ACTIVE` gate bails).
        // No double payout window remains.
        try {
            boolean alreadyClaimed = tradeProtectionService
                    ?.lockAndExpireIfActiveOrReportClaimed(t.id) ?: false
            if (alreadyClaimed) {
                // autoClaim already paid the buyer the full cover — reverse
                // the claim to claw it back, otherwise the seller credit
                // above PLUS the standing buyer payout double-pays the buyer.
                tradeProtectionService.reverseClaim(t.id, 'Trade released as valid')
            }
            // The false branch is already handled: ACTIVE was consumed
            // ACTIVE → EXPIRED inline under the lock by
            // lockAndExpireIfActiveOrReportClaimed, and a null protection
            // (unprotected trade) is a no-op there as well.
        } catch (Exception e) {
            log.warn("Protection resolve-on-release failed for trade ${t.id}: ${e.message}")
        }
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
        // Trade Protection auto-claim. A protected buyer who disputes
        // is made whole IMMEDIATELY — the protection pays out the full
        // item price to their wallet without waiting on a staff
        // verdict, which is the feature's "no support ticket needed"
        // promise. autoClaim is idempotent + a no-op for unprotected
        // trades, so this is safe to call on every BUYER-filed dispute.
        // Best-effort: a protection failure must not roll back the
        // DISPUTED flip. The trade stays DISPUTED for staff to settle
        // the seller side.
        //
        // CRITICAL: gate on actor == buyer. A SELLER-filed dispute means
        // the seller suspects buyer wrongdoing (e.g., buyer accepted the
        // Steam offer then ghosted buyerConfirm to grief). Auto-paying
        // the protection to the buyer in that case would hand a scammer
        // the item AND a full refund — net loss to the platform equal to
        // the entire item price, minus the 2% protection fee the buyer
        // paid. Seller-filed disputes must wait for staff resolution and
        // only pay out the protection if staff actually rule for the
        // buyer (via the cancel path's alreadyPaidByProtection logic, or
        // via a direct admin claim). See the symmetric reverseClaim
        // logic in release() for the case where staff overturn an
        // already-paid buyer claim.
        if (actorUserId == t.buyerUserId) {
            try {
                tradeProtectionService?.autoClaim(t.id, 'Trade disputed by buyer')
            } catch (Exception e) {
                log.warn("Protection auto-claim failed for disputed trade ${t.id}: ${e.message}")
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
        // Anti-theft guard #2 — a DISPUTED trade is frozen for staff.
        // Without this a buyer sitting in PENDING_BUYER_CONFIRM could
        // call dispute() (state → DISPUTED), then cancel(): the
        // PENDING_BUYER_CONFIRM guard below would no longer match, so
        // refundBuyer() runs and the buyer keeps the item AND is
        // refunded. A losing seller could likewise cancel out of
        // DISPUTED to dodge a fraud ruling. Once a trade is in dispute,
        // only staff may resolve it — admins keep access via the
        // isAdmin branch (and forceCancelTrade for CSR cleanup).
        if (!isAdmin && t.state == 'DISPUTED') {
            throw new BadRequestException("TRADE_DISPUTED",
                "This trade is in dispute — only SkinBox support can resolve it.")
        }
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

        // Anti-double-payout guard. If this trade was disputed first,
        // dispute() already fired Trade Protection's autoClaim — which
        // refunds the buyer the FULL item price the moment the dispute
        // is filed. The standard staff resolution for a buyer-favour
        // dispute is forceCancelTrade → cancel() (admin branch). If we
        // also ran refundBuyer() here the buyer would be paid the item
        // price TWICE for a single escrowed sale: once by the protection
        // claim, once by this cancel refund. Escrow only ever held the
        // price once, so the platform would eat the second payout.
        //
        // The pre-fix `findForTrade(t.id)` was an UNLOCKED read. It races
        // with a concurrent dispute → autoClaim (REQUIRES_NEW): cancel
        // could see status=ACTIVE while autoClaim's commit was still in
        // flight, refundBuyer would credit the wallet, then autoClaim's
        // commit would land a SECOND credit for the same trade.
        // Reproducible against the seller-timeout sweeper (every 30 min)
        // firing on a stale trade at the exact instant the buyer files
        // their dispute — both run concurrently, both credit the buyer.
        //
        // lockAndExpireIfActiveOrReportClaimed runs in cancel's outer tx
        // and acquires a pessimistic write lock on the protection row.
        // CLAIMED → returns true → we skip refund. ACTIVE → flipped to
        // EXPIRED inline (under the lock) → returns false → we refund;
        // a concurrent autoClaim then blocks on the lock, re-reads after
        // cancel commits, sees EXPIRED on its locked re-read and bails
        // via its existing idempotency gate. Null protection → returns
        // false, ordinary refund. Best-effort: a protection-service
        // hiccup must not block the cancel, so on any error we
        // conservatively fall through to refundBuyer() — a missed skip
        // is recoverable (staff claw-back), a missed refund on a
        // genuinely unrefunded buyer is not.
        boolean alreadyPaidByProtection = false
        try {
            alreadyPaidByProtection =
                tradeProtectionService?.lockAndExpireIfActiveOrReportClaimed(t.id) ?: false
        } catch (Exception e) {
            log.warn("Protection lock/check failed for cancelling trade ${t.id} — " +
                "falling through to escrow refund: ${e.message}")
        }
        if (alreadyPaidByProtection) {
            log.info("Trade #{} cancel — protection already CLAIMED, skipping escrow refund " +
                "to avoid a double payout to the buyer", t.id)
        } else {
            refundBuyer(t)
        }
        returnListingToSeller(t)
        // Sanitize the user-supplied reason before it lands in the trade
        // note AND the notification body. React auto-escapes, but defense
        // in depth stops a crafted payload from riding through every UI
        // surface that might later render a cancel reason.
        def cleanReason = textSanitizer.medium(reason)
        t.note = cleanReason
        t.settledAt = System.currentTimeMillis()
        transitionTo(t, 'CANCELLED')
        // Buyer notification — only claim "refund issued" when this
        // cancel actually issued one. A protection-claimed trade was
        // already refunded at dispute time, so the cancel ping just
        // confirms closure rather than (falsely) a second refund.
        notificationService?.safePush(t.buyerUserId, 'TRADE_CANCELLED',
            alreadyPaidByProtection ? "Trade cancelled" : "Trade cancelled · refund issued",
            cleanReason, t.id, '/profile?tab=trades')
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
        // Trade Protection — the buyer is whole either way: this cancel
        // refunded the escrowed item price via refundBuyer() above
        // (unprotected / unclaimed trades), OR the protection already
        // paid out at dispute time (the alreadyPaidByProtection branch).
        // So the protection must NOT pay out again — expire the cover.
        // expire() is a correct no-op on an already-CLAIMED protection
        // (status != ACTIVE → returns early), so this single call is
        // safe for both paths. Best-effort so a protection hiccup can't
        // roll back the refund + CANCELLED transition.
        try {
            tradeProtectionService?.expire(t.id)
        } catch (Exception e) {
            log.warn("Protection expire failed for cancelled trade ${t.id}: ${e.message}")
        }
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

        // Reverse the totalSold bump that PurchaseService.buy /
        // BidService.settleAuction applied when this trade first opened.
        // Without this, a buy → dispute/cancel loop inflates Item.totalSold
        // forever and pollutes the Database page's "Most Traded" sort.
        // Same null-guard + GREATEST-clamp shape as incrementTotalSold.
        //
        // Wave 113 — deferred to afterCommit via deferOrRun. The previous
        // try/catch wrapper swallowed the throw LOCALLY, but
        // ItemRepository.decrementTotalSold is `@Modifying @Query` — Spring's
        // transactional proxy ran the UPDATE inside the SHARED cancel /
        // auto-cancel tx, so any failure (constraint, dialect quirk, lock-
        // wait timeout) marked the tx rollback-only on the way out. The
        // outer try/catch then absorbed the exception and the caller
        // "succeeded", but the cancel's commit blew up with
        // UnexpectedRollbackException — refundBuyer's wallet credit, the
        // returnListingToSeller listing flip, the CANCELLED transition,
        // and the buyer's TRADE_CANCELLED notification were ALL rolled
        // back while the API returned 200. Pinned by
        // SideEffectTransactionIsolationIntegrationSpec /
        // "a failing totalSold decrement does NOT roll back the parent
        // trade cancel". The counter bump is cosmetic ("Most Traded"
        // sort) and can safely run post-commit; the refund correctness
        // is now isolated from any catalogue-side hiccup.
        if (itemRepository != null && listing.item?.id != null) {
            final Long _itemIdForDecrement = listing.item.id
            final Long _tradeIdForLog = t.id
            deferOrRun {
                try {
                    itemRepository.decrementTotalSold(_itemIdForDecrement)
                } catch (Exception e) {
                    log.warn("totalSold decrement failed for item ${_itemIdForDecrement} (trade ${_tradeIdForLog}): ${e.message}")
                }
            }
        }
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
     * Run {@code work} in a fresh REQUIRES_NEW transaction when a
     * PlatformTransactionManager is wired (production), otherwise run
     * it inline (Spock unit tests that build TradeService via the
     * property-map constructor without a Spring context).
     *
     * Used by the per-trade sweep helpers (release, autoCancelStaleSellerTrade,
     * autoCancelBannedSellerTrade) so a failing refundBuyer / wallet save /
     * transitionTo for ONE trade can't leave the row in a half-applied
     * state that the NEXT sweep tick would re-process — concretely, a
     * concurrent dispute on a stale-seller trade can cause the in-memory
     * Trade to fail optimistic-lock on transitionTo, AFTER refundBuyer has
     * already credited the buyer wallet. Without a real transaction the
     * wallet credit is committed in its own auto-commit tx and the next
     * sweep tick refunds the buyer a SECOND time. With this helper each
     * per-trade run is atomic — the refundBuyer rolls back with the
     * transitionTo failure, so the next sweep re-attempts cleanly.
     *
     * Mirrors BidService.runInIsolatedTx — the same self-invocation
     * gotcha that motivated batch 800's auction-settle isolation. The
     * @Transactional annotations on the protected helpers below were
     * dead code because every caller is `this.helper(...)` from the
     * outer @Scheduled sweep, which bypasses Spring's CGLIB proxy.
     */
    private void runInIsolatedTx(Closure work) {
        if (transactionManager == null) {
            work()
            return
        }
        def tt = new TransactionTemplate(transactionManager)
        tt.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        tt.executeWithoutResult { work() }
    }

    /**
     * Run {@code work} after the caller's transaction commits — or
     * immediately when there is no active transaction (unit-test path,
     * or a non-transactional caller). Mirrors the helper of the same
     * name in NotificationService / AuditService / PriceHistoryService.
     *
     * Used for best-effort writes that LIVE INSIDE a @Transactional
     * trade method (cancel / autoCancelStaleSellerTrade /
     * autoCancelBannedSellerTrade) but must NEVER fail the parent.
     * `returnListingToSeller`'s `itemRepository.decrementTotalSold` is
     * the canonical case: the call mutates the catalogue counter for
     * the "Most Traded" sort — cosmetic, never load-bearing for the
     * cancel's correctness. The pre-fix try/catch swallowed the throw
     * locally, but Spring's @Modifying @Query proxy had already marked
     * the SHARED trade tx rollback-only on the way out — the cancel's
     * commit then exploded with UnexpectedRollbackException and the
     * refundBuyer wallet credit + listing return + CANCELLED transition
     * were ALL rolled back while the buyer's HTTP response said 200.
     * Catastrophic for a money-path call: the buyer "received" their
     * refund (per the API), then never actually got it.
     *
     * Deferring to afterCommit means the deferred UPDATE runs AFTER
     * the parent has durably committed: a failing counter bump can no
     * longer poison the parent, and the parent's row locks are
     * already released so the new tx extends no lock-hold window.
     * Runs in a fresh REQUIRES_NEW transaction (load-bearing — inside
     * afterCommit the original tx is committed with "no commit
     * following", so a plain REQUIRED save would join a spent tx and
     * never persist).
     */
    private void deferOrRun(Closure work) {
        if (transactionManager != null && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override void afterCommit() {
                    try {
                        def tt = new TransactionTemplate(transactionManager)
                        tt.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
                        tt.executeWithoutResult { work() }
                    } catch (Exception e) {
                        log.warn("Deferred best-effort write failed: ${e.message}")
                    }
                }
            })
        } else {
            try {
                work()
            } catch (Exception e) {
                log.warn("Best-effort write failed (no active tx): ${e.message}")
            }
        }
    }


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
                // runInIsolatedTx so the per-trade @Transactional actually
                // takes effect — `this.autoCancelStaleSellerTrade(trade)`
                // is a self-invocation that bypasses Spring's CGLIB proxy,
                // so the annotation alone was a no-op (mirrors the
                // BidService.sweepExpired fix in batch 800). Without a
                // real tx, a refundBuyer wallet credit followed by a
                // transitionTo optimistic-lock failure left the buyer
                // credited but the trade still PENDING_SELLER_* — the
                // next sweep tick re-found it and DOUBLE-REFUNDED.
                runInIsolatedTx { autoCancelStaleSellerTrade(trade) }
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
    // Deliberately NOT @Transactional on the outer sweep. Mirrors the
    // fix BidService.sweepExpired adopted (batch 800): a per-row
    // `tradeRepository.save(trade)` that throws (e.g. an
    // OptimisticLockingFailureException from a concurrent
    // tradeRepository write touching the same row) would mark the
    // SHARED outer tx rollback-only — the per-row try/catch below
    // swallows the exception, but every reviewNudgeSentAt stamp the
    // sweep had already applied to SIBLING rows then silently reverts
    // on commit. Result: the next 24h tick re-finds those rows and
    // re-fires REVIEW_REMINDER pushes to buyers we already nudged — a
    // duplicate-notification leak the partial-index dedup was meant to
    // prevent. With the outer tx removed each per-row save commits in
    // its own auto-commit, so one bad row never poisons sibling stamps.
    @Scheduled(fixedDelay = 24L * 60L * 60L * 1000L, initialDelay = 30L * 60L * 1000L)
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
                // Multi-pod claim (wave 129). Without it both pods fire
                // REVIEW_REMINDER + save() before either save lands — the
                // 48h "leave a review" nudge fires TWICE per trade,
                // defeating the per-trade "one nudge total" guarantee.
                // The conditional UPDATE stamps reviewNudgeSentAt only
                // WHERE it's still NULL and returns 1 to the winning
                // pod / 0 to the losing pod (also 0 if a buyer reviewed
                // out-of-band between sweeper read and claim — the
                // findReviewNudgeCandidates filter would have excluded
                // them next tick, but the claim closes the in-flight
                // window). Stamps even for banned buyers so the sweep
                // doesn't re-scan them every 24h.
                int claimed = tradeRepository.claimReviewNudge(trade.id, System.currentTimeMillis())
                if (claimed == 0) {
                    log.debug("Review-nudge claim lost for trade ${trade.id} — sibling pod or status change")
                    return
                }
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

    // Deliberately NOT @Transactional — same per-batch poisoning
    // concern as sweepReviewNudge above. One per-row
    // `tradeRepository.save(trade)` that throws (concurrent
    // postMessage / dispute / sellerMarkSent mutating the same trade
    // → optimistic-lock failure) would mark the shared outer tx
    // rollback-only; the slowSellerWarnedAt stamp on every previously
    // processed sibling row would silently revert at commit time, and
    // the next hourly tick would re-fire TRADE_SLOW_SELLER +
    // TRADE_SELLER_NUDGE pushes to every buyer/seller in the batch.
    // Per-row auto-commit isolates the failure.
    @Scheduled(fixedDelay = 60L * 60L * 1000L, initialDelay = 5L * 60L * 1000L)
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
                // Multi-pod claim (wave 129). Without it both pods stamp
                // slowSellerWarnedAt + fire TRADE_SLOW_SELLER (buyer) +
                // TRADE_SELLER_NUDGE (seller) — buyer AND seller both
                // receive the heads-up TWICE for one trade, defeating the
                // per-trade "one warning total" promise the partial index
                // dedup was meant to provide. The conditional UPDATE
                // stamps slowSellerWarnedAt only WHERE it's still NULL
                // and returns 1 to the winning pod / 0 to the losing pod.
                int claimed = tradeRepository.claimSlowSellerWarning(trade.id, System.currentTimeMillis())
                if (claimed == 0) {
                    log.debug("Slow-seller-warning claim lost for trade ${trade.id} — sibling pod or status change")
                    return
                }
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

    /** Per-trade body for the seller-timeout sweep. Invoked from
     *  sweepStaleSellerResponse via runInIsolatedTx, which provides
     *  the REQUIRES_NEW transaction that the @Transactional annotation
     *  on this protected method previously promised but never delivered
     *  (Spring's CGLIB proxy is bypassed on self-invocation, so the
     *  annotation alone was a no-op). With the per-trade tx in place,
     *  the refundBuyer + returnListingToSeller + CANCELLED transition
     *  are atomic — a transitionTo failure (e.g., optimistic-lock from
     *  a concurrent dispute) rolls back the buyer wallet credit, so the
     *  next sweep tick cannot DOUBLE-REFUND. The @Transactional stays
     *  for any future external caller that would actually go through
     *  the proxy. */
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
        // Trade Protection — the seller-timeout auto-cancel already
        // refunds the buyer via refundBuyer() above, so the buyer is
        // whole; expire the cover rather than double-paying.
        try {
            tradeProtectionService?.expire(trade.id)
        } catch (Exception e) {
            log.warn("Protection expire failed for auto-cancelled trade ${trade.id}: ${e.message}")
        }
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

    /**
     * Bulk companion to {@link #typicalShipMs} — collapses N per-seller
     * SQL round-trips into a single IN-clause query. Drives
     * {@code /api/sellers/ship-times?ids=...}: the controller used to
     * loop {@code parsed.each { uid -> typicalShipMs(uid) }}, which at
     * the 200-id cap fired up to 200 separate queries on every
     * marketplace-grid load.
     *
     * Same per-seller semantics: median over verified trades in the
     * lookback window, omitted when fewer than 3 samples (the noise
     * floor that {@code typicalShipMs} enforces). Sellers with no
     * qualifying trades are absent from the returned map so callers
     * can render "unknown" the same way the per-seller path does.
     */
    Map<Long, Long> typicalShipMsBulk(Collection<Long> sellerUserIds, int lookbackDays = 90) {
        Map<Long, Long> out = [:]
        if (sellerUserIds == null || sellerUserIds.isEmpty()) return out
        def ids = sellerUserIds.findAll { it != null }.toSet()
        if (ids.isEmpty()) return out
        def since = System.currentTimeMillis() - (lookbackDays * 24L * 60L * 60L * 1000L)
        List<Object[]> rows
        try {
            rows = tradeRepository.findRecentShipMsForSellers(ids, since)
        } catch (Exception e) {
            log.debug("typicalShipMsBulk lookup failed: ${e.message}")
            return out
        }
        if (rows == null || rows.isEmpty()) return out
        Map<Long, List<Long>> bySeller = [:].withDefault { [] }
        rows.each { row ->
            def uid = row[0] as Long
            def ms = row[1] as Long
            if (uid != null && ms != null && ms >= 0L) bySeller[uid] << ms
        }
        bySeller.each { uid, samples ->
            if (samples.size() < 3) return
            def sorted = samples.sort(false)
            def mid = sorted.size().intdiv(2)
            def median = sorted.size() % 2 == 0
                ? ((sorted[mid - 1] + sorted[mid]) / 2L) as Long
                : sorted[mid] as Long
            out[uid] = median
        }
        out
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
                    // runInIsolatedTx so the per-trade @Transactional on
                    // autoCancelBannedSellerTrade actually takes effect —
                    // self-invocation bypasses Spring's CGLIB proxy, so
                    // the annotation alone was a no-op. Without a real
                    // tx, the refundBuyer + returnListingToSeller saves
                    // committed in their own auto-commit transactions
                    // even when the trailing transitionTo failed (e.g.,
                    // optimistic-lock from a concurrent dispute), leaving
                    // the buyer credited but the trade still
                    // PENDING_BUYER_CONFIRM — the next sweep tick
                    // re-found it and DOUBLE-REFUNDED.
                    runInIsolatedTx { autoCancelBannedSellerTrade(trade) }
                } else {
                    // autoRelease=true fires the buyer-side email
                    // (batch 601) — the 8-day-silent buyer probably
                    // isn't checking the bell.
                    // runInIsolatedTx so the @Transactional on
                    // release() actually engages — without it,
                    // transitionTo's trade.save (committing VERIFIED)
                    // and the seller wallet credit each ran in their
                    // own auto-commit, so a crash between them left
                    // the trade VERIFIED but the seller uncredited
                    // (or vice versa on the very next attempt).
                    runInIsolatedTx { release(trade, true) }
                    // actor=null (the sweeper is the system, no human
                    // acted), subject=sellerUserId (the party most
                    // affected by the action — they got paid). Pre-fix
                    // the subject was also null, so the row was invisible
                    // to ProfileController /security-activity (filters
                    // via auditLogRepository.bySubject on subjectUserId
                    // = uid). Same null/null bug class WITHDRAW_REQUESTED
                    // (StripeService:547) + DEPOSIT_COMPLETE (line 1326)
                    // were closed for in commit de40340 / this commit;
                    // TRADE_AUTO_RELEASED was the matching outlier on the
                    // trade-sweep path. Mirrors the sibling
                    // TRADE_AUTO_CANCELLED's actor=null, subject=
                    // sellerUserId pattern (line 1573).
                    auditService?.log(AuditService.TRADE_AUTO_RELEASED, null, trade.sellerUserId, trade.id,
                        "Auto-released after ${autoReleaseDays}d no-confirm window")
                }
            } catch (Exception e) {
                log.warn("Trade sweeper failed on ${trade.id}: ${e.message}")
            }
        }
    }

    /** Per-trade body for the banned-seller branch of the auto-release
     *  sweep. Invoked from sweepPendingConfirm via runInIsolatedTx, which
     *  provides the REQUIRES_NEW transaction this needs. The @Transactional
     *  annotation alone was previously dead code: the sweep loop calls
     *  `this.autoCancelBannedSellerTrade(trade)`, and Spring's CGLIB proxy
     *  is bypassed on self-invocation — see the runInIsolatedTx docstring.
     *  With the per-trade tx in place, the refundBuyer + returnListingToSeller +
     *  CANCELLED transition are atomic, so a transitionTo failure
     *  (optimistic-lock) cannot leave the buyer credited while the trade
     *  stays in PENDING_BUYER_CONFIRM for the next tick to refund again. */
    @Transactional
    protected void autoCancelBannedSellerTrade(Trade trade) {
        refundBuyer(trade)
        returnListingToSeller(trade)
        trade.note = "Automatically cancelled — seller account banned"
        trade.settledAt = System.currentTimeMillis()
        transitionTo(trade, 'CANCELLED')
        notificationService?.safePush(trade.buyerUserId, 'TRADE_CANCELLED',
            "Trade cancelled · refund issued", trade.note, trade.id, '/profile?tab=trades')
        auditService?.log(AuditService.TRADE_AUTO_CANCELLED, null, trade.sellerUserId, trade.id,
            "Seller banned — auto-cancelled after ${autoReleaseDays}d window")
        // Trade Protection — the banned-seller branch
        // refunds the buyer via refundBuyer(), so expire
        // the cover (no double payout). The auto-release
        // branch routes through release(), which
        // expires protection on its own.
        try {
            tradeProtectionService?.expire(trade.id)
        } catch (Exception e) {
            log.warn("Protection expire failed for banned-seller trade ${trade.id}: ${e.message}")
        }
    }
}
