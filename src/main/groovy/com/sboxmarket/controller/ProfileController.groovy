package com.sboxmarket.controller

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.EmailService
import com.sboxmarket.service.ProfileService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.TotpService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

import java.security.SecureRandom

/**
 * Profile tab endpoints:
 *   GET  /api/profile/me          — aggregated dashboard snapshot
 *   PUT  /api/profile/email       — set/replace email, generates confirmation token
 *   POST /api/profile/email/verify — confirm with the token
 *   POST /api/profile/2fa/enroll  — generate a fresh secret + otpauth URL
 *   POST /api/profile/2fa/confirm — verify the first 6-digit code and enable 2FA
 *   POST /api/profile/2fa/disable — wipe the secret (requires a fresh code first)
 */
@RestController
@RequestMapping("/api/profile")
@Slf4j
class ProfileController {

    private static final SecureRandom RNG = new SecureRandom()
    private static final java.util.regex.Pattern EMAIL_RE = ~/^[A-Za-z0-9._%+\-]{1,64}@[A-Za-z0-9.\-]{1,253}\.[A-Za-z]{2,24}$/
    private static final java.util.regex.Pattern TRADE_URL_RE = ~/^https:\/\/steamcommunity\.com\/tradeoffer\/new\/\?partner=\d{1,10}&token=[A-Za-z0-9_-]{1,16}$/

    /** Per-user verification-resend cooldown (batch 602). Prevents a UI
     *  that double-fires the Resend button from flooding SMTP with
     *  redundant verification emails. Global 20/10s rate limit catches
     *  script-level hammer but allows 20 resends in 10s; this tighter
     *  60s cooldown is specifically for the outbound-email side. In-
     *  memory (survives a rolling deploy is not required — cooldown is
     *  about UX, not security). Capped at 10k entries so a wide ID
     *  sweep can't grow the map unbounded; LRU via ConcurrentHashMap
     *  size-check fallback. */
    private static final long RESEND_COOLDOWN_MS = 60_000L
    private static final int RESEND_MAP_CAP = 10_000
    private static final java.util.concurrent.ConcurrentHashMap<Long, Long> LAST_RESEND_AT =
        new java.util.concurrent.ConcurrentHashMap<>()

    @Autowired ProfileService profileService
    @Autowired SteamUserRepository steamUserRepository
    @Autowired TotpService totpService
    @Autowired TextSanitizer textSanitizer
    @Autowired EmailService emailService
    // Extra repos the GDPR /export bundle needs. All required=false so
    // tests can wire a smaller subset of collaborators.
    @Autowired(required = false) com.sboxmarket.repository.WalletRepository walletRepository
    @Autowired(required = false) com.sboxmarket.repository.TransactionRepository transactionRepository
    @Autowired(required = false) com.sboxmarket.repository.ListingRepository listingRepository
    @Autowired(required = false) com.sboxmarket.repository.TradeRepository tradeRepository
    @Autowired(required = false) com.sboxmarket.repository.OfferRepository offerRepository
    @Autowired(required = false) com.sboxmarket.repository.TradeMessageRepository tradeMessageRepository
    @Autowired(required = false) com.sboxmarket.repository.BuyOrderRepository buyOrderRepository
    @Autowired(required = false) com.sboxmarket.repository.BidRepository bidRepository
    @Autowired(required = false) com.sboxmarket.repository.ReviewRepository reviewRepository
    @Autowired(required = false) com.sboxmarket.service.ReviewService reviewService
    @Autowired(required = false) com.sboxmarket.repository.NotificationRepository notificationRepository
    @Autowired(required = false) com.sboxmarket.repository.UserBlockRepository userBlockRepository
    @Autowired(required = false) com.sboxmarket.repository.SellerFollowRepository sellerFollowRepository
    @Autowired(required = false) com.sboxmarket.repository.WatchlistAlertRepository watchlistAlertRepository
    @Autowired(required = false) com.sboxmarket.repository.SavedSearchRepository savedSearchRepository
    @Autowired(required = false) com.sboxmarket.repository.AuditLogRepository auditLogRepository
    @Autowired(required = false) com.sboxmarket.service.AuditService auditService
    @Autowired(required = false) com.sboxmarket.repository.ApiKeyRepository apiKeyRepository

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    @GetMapping("/me")
    ResponseEntity<Map> me(HttpServletRequest req) {
        def data = profileService.buildProfile(requireUser(req))
        if (data == null) throw new UnauthorizedException("Unknown user")
        ResponseEntity.ok(data)
    }

    /** Sign-in history for the caller (batch 569). Returns the 20
     *  most-recent USER_SIGN_IN audit rows — wall-clock timestamp +
     *  IP + user-agent. Lets the user spot suspicious logins from
     *  unfamiliar locations without opening a support ticket.
     *  Scoped to `subjectUserId = caller`; an attacker with a stolen
     *  session can't pivot to someone else's history. */
    @GetMapping('/sign-in-history')
    ResponseEntity<List<Map>> signInHistory(HttpServletRequest req) {
        def uid = requireUser(req)
        if (auditLogRepository == null) return ResponseEntity.ok([])
        def rows = auditLogRepository.byUserAndEvent(uid, 'USER_SIGN_IN',
            org.springframework.data.domain.PageRequest.of(0, 20))
        ResponseEntity.ok(rows.collect { r ->
            [
                id:        r.id,
                createdAt: r.createdAt,
                ipAddress: r.ipAddress,
                userAgent: r.userAgent
            ]
        })
    }

    /**
     * Security-relevant audit events where the caller is the subject
     * (batch 714). Gives the user transparency into their own account's
     * recent sensitive actions: 2FA resets, API key mints/revocations,
     * session logouts, admin force-actions, withdrawals, chargebacks.
     * Complements /sign-in-history which covers login attempts only.
     *
     * Whitelisted event set only — the full audit log contains ops-
     * internal rows (ADMIN_NOTES_UPDATED, ITEM_EDITED etc) that would
     * leak internal workflow to the user. Sorted newest-first, capped
     * at 50 rows. Subject-scoped via requireUser so a compromised
     * session can't pivot to someone else's history.
     */
    @GetMapping('/security-activity')
    ResponseEntity<List<Map>> securityActivity(HttpServletRequest req) {
        def uid = requireUser(req)
        if (auditLogRepository == null) return ResponseEntity.ok([])
        // Broad fetch + in-memory filter; the typical user generates
        // a few dozen audit rows in their lifetime, so loading 500 +
        // filtering is cheaper than a bulk-IN query against the typed
        // event list. Capped well below DB-level audit growth.
        def rows = auditLogRepository.bySubject(uid,
            org.springframework.data.domain.PageRequest.of(0, 500))
        def WHITELIST = [
            'TWOFA_RESET',
            'API_KEY_MINTED', 'API_KEY_REVOKED',
            'SESSION_LOGOUT_ALL', 'USER_FORCE_LOGOUT',
            'WITHDRAW_REQUESTED', 'WITHDRAW_APPROVED', 'WITHDRAW_REJECTED',
            'WITHDRAW_SELF_CANCELLED',
            'DEPOSIT_COMPLETE',
            'CHARGEBACK_OPENED', 'DISPUTE_CLEARED',
            'USER_BANNED', 'USER_UNBANNED',
            'ADMIN_GRANTED', 'ADMIN_REVOKED',
            'CSR_GRANTED', 'CSR_REVOKED',
            'REFUND_ISSUED'
        ] as Set
        def filtered = rows.findAll { WHITELIST.contains(it.eventType) }.take(50)
        ResponseEntity.ok(filtered.collect { r ->
            [
                id:        r.id,
                createdAt: r.createdAt,
                eventType: r.eventType,
                summary:   r.summary,
                // Deliberately NOT including actorUserId — when staff
                // takes an action (ban, 2FA reset, force-logout) the
                // user sees "Staff action" not the specific admin's id.
                actorIsStaff: r.actorUserId != null && r.actorUserId != uid
            ]
        })
    }

    /** CSV export of every bid the user has ever placed — manual + auto,
     *  across WINNING / OUTBID / WON / LOST / CANCELLED. Parity with
     *  /offers.csv, /trades.csv, /buy-orders/export.csv, /wallet/
     *  transactions.csv. Capped at 5000 rows (newest first). */
    @GetMapping(value = "/bids.csv", produces = "text/csv")
    ResponseEntity<String> exportBidsCsv(HttpServletRequest req) {
        def uid = requireUser(req)
        if (bidRepository == null) {
            return ResponseEntity.status(503).body('')
        }
        def rows = bidRepository.findByBidder(uid).take(5000)
        def sb = new StringBuilder()
        sb.append('id,date,listingId,kind,status,amount,maxAmount\n')
        def df = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        df.timeZone = java.util.TimeZone.getTimeZone('UTC')
        rows.each { b ->
            sb.append(b.id ?: '').append(',')
              .append(df.format(new Date(b.createdAt ?: 0))).append(',')
              .append(b.listingId ?: '').append(',')
              .append(b.kind ?: '').append(',')
              .append(b.status ?: '').append(',')
              .append((b.amount ?: BigDecimal.ZERO).toPlainString()).append(',')
              .append(b.maxAmount != null ? b.maxAmount.toPlainString() : '').append('\n')
        }
        def filename = "skinbox-bids-${df.format(new Date()).replace(':', '-')}.csv"
        ResponseEntity.ok()
            .header('Content-Disposition', "attachment; filename=\"${filename}\"")
            .header('Content-Type', 'text/csv; charset=utf-8')
            .header('Cache-Control', 'no-store')
            .body(sb.toString())
    }

    /** CSV export of every offer the user made or received. */
    @GetMapping(value = "/offers.csv", produces = "text/csv")
    ResponseEntity<String> exportOffersCsv(HttpServletRequest req) {
        def uid = requireUser(req)
        if (offerRepository == null) {
            return ResponseEntity.status(503).body('')
        }
        // Merge incoming + outgoing so the user sees the full picture in
        // one file. Small enough that a deduped list comprehension is
        // fine — capped at 5000 rows after sort.
        def outgoing = offerRepository.findByBuyer(uid)
        def incoming = offerRepository.findBySeller(uid)
        def merged = new ArrayList<com.sboxmarket.model.Offer>()
        merged.addAll(outgoing)
        merged.addAll(incoming)
        def rows = merged.sort { a, b -> (b.createdAt ?: 0) <=> (a.createdAt ?: 0) }.take(5000)
        def sb = new StringBuilder()
        // Batch 391: include itemName + V43 buyer `message` + V45 seller
        // `sellerReply` so the export matches what the user actually saw
        // on their offer row. askingPrice lets tax-software reconcile the
        // "% off" the offer represented.
        sb.append('id,date,role,listingId,itemName,status,amount,askingPrice,counterpartyId,message,sellerReply\n')
        def df = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        df.timeZone = java.util.TimeZone.getTimeZone('UTC')
        // Batch 979 — shared CsvUtil.safeCell (OWASP formula-injection safe).
        def csvEscape = com.sboxmarket.util.CsvUtil.&safeCell
        rows.each { o ->
            def isBuyer = (o.buyerUserId == uid)
            def role = isBuyer ? 'BUYER' : 'SELLER'
            def counterparty = isBuyer ? o.sellerUserId : o.buyerUserId
            sb.append(o.id ?: '').append(',')
              .append(df.format(new Date(o.createdAt ?: 0))).append(',')
              .append(role).append(',')
              .append(o.listingId ?: '').append(',')
              .append(csvEscape(o.itemName ?: '')).append(',')
              .append(csvEscape(o.status ?: '')).append(',')
              .append((o.amount ?: BigDecimal.ZERO).toPlainString()).append(',')
              .append((o.askingPrice ?: BigDecimal.ZERO).toPlainString()).append(',')
              .append(counterparty ?: '').append(',')
              .append(csvEscape(o.message ?: '')).append(',')
              .append(csvEscape(o.sellerReply ?: '')).append('\n')
        }
        def filename = "skinbox-offers-${df.format(new Date()).replace(':', '-')}.csv"
        ResponseEntity.ok()
            .header('Content-Disposition', "attachment; filename=\"${filename}\"")
            .header('Content-Type', 'text/csv; charset=utf-8')
            .header('Cache-Control', 'no-store')
            .body(sb.toString())
    }

    /**
     * CSV export of every trade the signed-in user participated in —
     * as buyer or seller. Columns cover the ledger-level fields a
     * user would want for tax / accounting: date, state, role
     * (BUYER or SELLER), counterparty id, item name, price, fee,
     * net (price − fee for sellers; price for buyers), trade id.
     * Cap at 5000 rows matching the transactions.csv limit.
     */
    @GetMapping(value = "/trades.csv", produces = "text/csv")
    ResponseEntity<String> exportTradesCsv(HttpServletRequest req) {
        def uid = requireUser(req)
        if (tradeRepository == null) {
            return ResponseEntity.status(503).body('')
        }
        def trades = tradeRepository.findByParticipant(uid).take(5000)
        def sb = new StringBuilder()
        // Batch 782 — tradeOfferUrl appended as the last column so legacy
        // column-indexed spreadsheet macros don't break. Users doing
        // tax / dispute bookkeeping can now reconcile "which Steam offer
        // was this trade?" without opening Profile → Trades and
        // re-clicking each row.
        sb.append('id,date,state,role,counterparty,itemName,price,fee,net,tradeOfferUrl\n')
        def df = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        df.timeZone = java.util.TimeZone.getTimeZone('UTC')
        // Batch 979 — shared CsvUtil.safeCell (OWASP formula-injection safe).
        def csvEscape = com.sboxmarket.util.CsvUtil.&safeCell
        trades.each { t ->
            def isBuyer = (t.buyerUserId == uid)
            def role = isBuyer ? 'BUYER' : 'SELLER'
            def counterparty = isBuyer ? t.sellerUserId : t.buyerUserId
            def price = t.price ?: BigDecimal.ZERO
            def fee = t.feeAmount ?: BigDecimal.ZERO
            // Buyer's net cash out is price (they paid it). Seller's net
            // cash in is price − fee (platform takes the fee from the
            // seller side).
            def net = isBuyer ? price : (price - fee)
            sb.append(t.id ?: '').append(',')
              .append(df.format(new Date(t.createdAt ?: 0))).append(',')
              .append(csvEscape(t.state ?: '')).append(',')
              .append(role).append(',')
              .append(counterparty ?: '').append(',')
              .append(csvEscape(t.itemName ?: '')).append(',')
              .append(price.toPlainString()).append(',')
              .append(fee.toPlainString()).append(',')
              .append(net.toPlainString()).append(',')
              .append(csvEscape(t.tradeOfferUrl ?: '')).append('\n')
        }
        def filename = "skinbox-trades-${df.format(new Date()).replace(':', '-')}.csv"
        ResponseEntity.ok()
            .header('Content-Disposition', "attachment; filename=\"${filename}\"")
            .header('Content-Type', 'text/csv; charset=utf-8')
            .header('Cache-Control', 'no-store')
            .body(sb.toString())
    }

    // ── Email ───────────────────────────────────────────────────────

    @PutMapping("/email")
    @Transactional
    ResponseEntity<Map> setEmail(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        def emailRaw = (body?.email as String ?: '').trim().toLowerCase()
        if (!EMAIL_RE.matcher(emailRaw).matches()) {
            throw new BadRequestException("INVALID_EMAIL", "Please enter a valid email address")
        }
        // Email-uniqueness check (batch 477). Refuse when another account
        // already owns this email — closes the multi-account vector
        // (chargeback evasion, spam ticket flood, password-reset fishing).
        // Allow when the OWNING user is the same caller (re-saving their
        // own email triggers a new verification token). Case-insensitive
        // match to match the lowercased-on-write contract.
        def existing = steamUserRepository.findByEmailIgnoreCase(emailRaw) ?: []
        def collision = existing.find { it.id != uid }
        if (collision != null) {
            throw new BadRequestException("EMAIL_TAKEN",
                "That email is already linked to another SkinBox account. Use a different address or contact support if this is yours.")
        }
        // Security-sensitive email-change notification (batch 512).
        // Before we overwrite, capture the previous verified address so
        // we can alert the OLD email that the account's login-recovery
        // channel has moved. Mitigates the takeover flow where an
        // attacker with a stolen session quietly swaps the email to
        // their own to lock the real owner out of password/dispute
        // recovery. Only fires when the previous email was actually
        // verified (unverified addresses don't meaningfully change
        // recovery posture).
        String prevVerifiedEmail = (Boolean.TRUE.equals(user.emailVerified) && user.email) ? user.email : null

        user.email = textSanitizer.cleanShort(emailRaw)
        user.emailVerified = false
        user.emailVerificationToken = randomToken()
        // Batch 647 — 24h expiry on the fresh token. Narrow enough that a
        // leaked mail archive / misrouted message from yesterday still
        // works (users don't always check email instantly), wide enough
        // to survive "check from a different device later today" without
        // forcing a resend dance.
        user.emailVerificationTokenExpiresAt = System.currentTimeMillis() + (24L * 60L * 60L * 1000L)
        steamUserRepository.save(user)
        // Hand the token off to EmailService — it either sends a real
        // email (SMTP configured) or logs it for local dev / CI. We only
        // echo the token back in the JSON when SMTP is NOT wired up, so
        // a real production instance with mail enabled never leaks it.
        emailService.sendVerification(user.email, user.emailVerificationToken)
        if (prevVerifiedEmail != null && prevVerifiedEmail != user.email) {
            try {
                emailService.sendEmailChanged(prevVerifiedEmail, user.displayName, user.email)
            } catch (Exception e) {
                log.warn("Email-change alert to previous address failed: ${e.message}")
            }
        }
        def resp = [email: user.email, verified: false] as Map
        if (!emailService.smtpReady) resp.token = user.emailVerificationToken
        ResponseEntity.ok(resp)
    }

    /**
     * Set (or clear) the user's Steam trade offer URL. Validated to the
     * canonical Steam format so we don't persist junk that would later
     * dead-link when surfaced on a trade row. Pass an empty string to
     * clear. No verification email / confirmation round-trip — the trade
     * URL is a public contact handle, not a secret.
     */
    @PutMapping("/trade-url")
    @Transactional
    ResponseEntity<Map> setTradeUrl(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        // Capture the prior URL so we can detect a real change (and skip
        // the security email on a no-op save) + send the alert to the
        // verified email if the URL actually moves (batch 513).
        String prevTradeUrl = user.tradeUrl
        def raw = (body?.tradeUrl as String ?: '').trim()
        if (raw.isEmpty()) {
            user.tradeUrl = null
        } else {
            if (!TRADE_URL_RE.matcher(raw).matches()) {
                throw new BadRequestException("INVALID_TRADE_URL",
                    "Paste your Steam trade URL — it looks like https://steamcommunity.com/tradeoffer/new/?partner=…&token=…")
            }
            // Partner-id collision check (batch 478). Extract the partner=
            // param from the URL; if another SkinBox account already has
            // that partner id, refuse — two accounts with the same Steam
            // ID32 is account-stuffing. Skipped when we can't parse the
            // partner id (malformed URL would have failed regex above,
            // but belt-and-braces).
            def partnerMatch = raw =~ /partner=(\d+)/
            if (partnerMatch) {
                def partnerId = partnerMatch[0][1] as String
                def collision = steamUserRepository.findByTradeUrlPartnerId(partnerId)
                    .find { it.id != uid }
                if (collision != null) {
                    throw new BadRequestException("TRADE_URL_TAKEN",
                        "That Steam account is already linked to another SkinBox account. One Steam account per SkinBox account — contact support if this is a mistake.")
                }
                // Ownership check (batch 555). The partner= value is the
                // Steam ID32 (accountid); the signed-in user's steamId64
                // is 76561197960265728 + accountid. If the URL's partner
                // id doesn't derive from the user's own Steam ID64, the
                // user is pasting *someone else's* trade URL — a fast
                // path to "why didn't I get my items?" support tickets.
                // Reject up-front with a specific error code so the UI
                // can render a clear message.
                try {
                    if (user.steamId64) {
                        long sid64    = Long.parseLong(user.steamId64)
                        long expected = sid64 - 76561197960265728L
                        long supplied = Long.parseLong(partnerId)
                        if (expected != supplied) {
                            throw new BadRequestException("TRADE_URL_NOT_YOURS",
                                "That trade URL belongs to a different Steam account. " +
                                "Copy the URL from steamcommunity.com while signed in as YOUR account " +
                                "(Your Inventory → Trade Offers → Who can send me Trade Offers?).")
                        }
                    }
                } catch (NumberFormatException ignored) {
                    // Belt-and-braces: if the user's steamId64 somehow
                    // isn't numeric, skip the check rather than blocking
                    // a legit save. Regex above already enforced
                    // partnerId is \d{1,10} so the Long.parseLong on
                    // supplied won't NFE in practice.
                }
            }
            user.tradeUrl = raw
        }
        steamUserRepository.save(user)
        // Security alert on trade-URL change (batch 513). The trade URL
        // is where purchased items get sent, so an attacker with a
        // stolen session could redirect future purchases by swapping
        // this out. Alert the verified email so the owner sees the
        // move. Fires only when the URL actually changes (not on a
        // no-op save or a clear-then-set-same flow) and the verified
        // address is present.
        if (emailService != null && prevTradeUrl != user.tradeUrl &&
                emailService.canSendSecurityTo(user)) {
            try {
                emailService.sendTradeUrlChanged(user.email, user.displayName, user.tradeUrl)
            } catch (Exception e) {
                log.warn("Trade-URL change alert failed for user ${uid}: ${e.message}")
            }
        }
        ResponseEntity.ok([tradeUrl: user.tradeUrl])
    }

    /**
     * Set (or clear) the seller's self-written stall bio — rendered
     * on the public /stall/{id} page under the hero. HTML-stripped via
     * TextSanitizer.medium and capped at 500 chars. Pass empty string
     * or null to clear. No email / confirmation loop — this is
     * self-managed public content, not a secret.
     */
    @PutMapping("/stall-bio")
    @Transactional
    ResponseEntity<Map> setStallBio(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        def raw = body?.bio as String
        if (raw == null || raw.trim().isEmpty()) {
            user.stallBio = null
        } else {
            def clean = textSanitizer.medium(raw)
            if (clean == null) clean = ''
            if (clean.length() > 500) clean = clean.substring(0, 500)
            user.stallBio = clean
        }
        steamUserRepository.save(user)
        ResponseEntity.ok([stallBio: user.stallBio])
    }

    /**
     * Self-service data export — bundles the user's profile, wallet,
     * transactions, listings, trades, offers, buy orders, bids, reviews,
     * and notifications into one JSON blob. Intended for GDPR /
     * right-to-copy requests. Excludes secrets (totpSecret,
     * emailVerificationToken) which are already @JsonIgnore'd at the
     * entity level. Returned as a downloadable attachment.
     */
    @GetMapping(value = "/export", produces = "application/json")
    @Transactional(readOnly = true)
    ResponseEntity<Map> exportData(HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        def wallet = walletRepository.findByUsername("steam_${user.steamId64}")
        def walletId = wallet?.id ?: -1L
        def payload = [
            exportedAt:   System.currentTimeMillis(),
            user: [
                id:            user.id,
                steamId64:     user.steamId64,
                displayName:   user.displayName,
                avatarUrl:     user.avatarUrl,
                profileUrl:    user.profileUrl,
                email:         user.email,
                emailVerified: user.emailVerified,
                tradeUrl:      user.tradeUrl,
                role:          user.role,
                banned:        user.banned,
                banReason:     user.banned ? user.banReason : null,
                createdAt:     user.createdAt,
                lastLoginAt:   user.lastLoginAt,
                lastSyncedAt:  user.lastSyncedAt
            ],
            wallet: wallet == null ? null : [
                id:       wallet.id,
                balance:  wallet.balance,
                currency: wallet.currency,
                username: wallet.username
            ],
            transactions: wallet == null ? [] : transactionRepository
                .findByWalletIdOrderByCreatedAtDesc(walletId,
                    org.springframework.data.domain.PageRequest.of(0, 5000))
                .collect { t -> [
                    id: t.id, type: t.type, status: t.status,
                    amount: t.amount, currency: t.currency,
                    description: t.description, listingId: t.listingId,
                    stripeReference: t.stripeReference, createdAt: t.createdAt
                ] },
            listings: listingRepository.findActiveBySeller(uid)
                .collect { l -> [
                    id: l.id, itemId: l.item?.id, itemName: l.item?.name,
                    price: l.price, status: l.status, listingType: l.listingType,
                    listedAt: l.listedAt, hidden: l.hidden
                ] },
            trades: tradeRepository?.findByParticipant(uid)?.collect { t -> [
                id: t.id, state: t.state, itemName: t.itemName, price: t.price,
                feeAmount: t.feeAmount, createdAt: t.createdAt,
                settledAt: t.settledAt, role: t.buyerUserId == uid ? 'buyer' : 'seller'
            ] } ?: [],
            offersOutgoing: offerRepository?.findByBuyer(uid)?.collect { o -> [
                id: o.id, listingId: o.listingId, amount: o.amount,
                status: o.status, createdAt: o.createdAt
            ] } ?: [],
            buyOrders: buyOrderRepository?.findByBuyer(uid)?.collect { b -> [
                id: b.id, itemName: b.itemName, category: b.category, rarity: b.rarity,
                maxPrice: b.maxPrice, quantity: b.quantity, status: b.status,
                createdAt: b.createdAt
            ] } ?: [],
            autoBids: bidRepository?.findActiveAutoBidsForUser(uid)?.collect { b -> [
                id: b.id, listingId: b.listingId, amount: b.amount,
                maxAmount: b.maxAmount, kind: b.kind, status: b.status,
                createdAt: b.createdAt
            ] } ?: [],
            reviewsGiven: reviewRepository?.findByFromUserId(uid)?.collect { r -> [
                id: r.id, toUserId: r.toUserId, rating: r.rating,
                comment: r.comment, itemName: r.itemName, createdAt: r.createdAt
            ] } ?: [],
            notifications: notificationRepository?.findForUser(uid,
                org.springframework.data.domain.PageRequest.of(0, 500))
                ?.collect { n -> [
                    id: n.id, kind: n.kind, title: n.title, body: n.body,
                    read: n.read, createdAt: n.createdAt, path: n.path
                ] } ?: [],
            // Preference-style rows that belong in a GDPR export because
            // they're data the user created about their preferences
            // (batch 350). None of these carry PII about other users
            // beyond the user ids they chose to target — same disclosure
            // as the public stall / follow endpoints already surface.
            blockedUsers: userBlockRepository?.findByBlocker(uid)?.collect { b -> [
                blockedUserId: b.blockedUserId, createdAt: b.createdAt
            ] } ?: [],
            sellerFollows: sellerFollowRepository?.findByFollowerUserIdOrderByCreatedAtDesc(uid)?.collect { f -> [
                sellerUserId:       f.sellerUserId,
                notificationsMuted: Boolean.TRUE.equals(f.notificationsMuted),
                createdAt:          f.createdAt
            ] } ?: [],
            watchlistAlerts: watchlistAlertRepository?.findByUserId(uid)?.collect { a -> [
                id:          a.id,
                itemId:      a.itemId,
                targetPrice: a.targetPrice,
                status:      a.status,
                createdAt:   a.createdAt,
                firedAt:     a.firedAt
            ] } ?: [],
            savedSearches: savedSearchRepository?.findByUser(uid)?.collect { s -> [
                id:          s.id,
                name:        s.name,
                q:           s.q,
                category:    s.category,
                rarity:      s.rarity,
                sort:        s.sort,
                minPrice:    s.minPrice,
                maxPrice:    s.maxPrice,
                createdAt:   s.createdAt,
                lastFiredAt: s.lastFiredAt
            ] } ?: [],
            // Batch 721 — API keys included in the GDPR export. Metadata
            // only; raw tokens are only returned once at mint time + the
            // hash is @JsonIgnore'd on the entity. Users asking for a
            // data copy deserve to see what keys are registered on their
            // account, including revoked ones (revocation history is
            // personal data too).
            apiKeys: apiKeyRepository?.findByUser(uid)?.collect { k -> [
                id:           k.id,
                publicPrefix: k.publicPrefix,
                label:        k.label,
                scope:        k.scope,
                revoked:      k.revoked,
                createdAt:    k.createdAt,
                lastUsedAt:   k.lastUsedAt
            ] } ?: []
        ]
        def df = new java.text.SimpleDateFormat("yyyy-MM-dd")
        ResponseEntity.ok()
            .header('Content-Disposition',
                "attachment; filename=\"skinbox-data-${user.steamId64}-${df.format(new Date())}.json\"")
            .header('Cache-Control', 'no-store')
            .body(payload)
    }

    /**
     * Self-service account-deletion request (GDPR/DSAR). Sets a soft
     * flag; nothing is actually deleted — admins review + finalise
     * after confirming the user has no pending withdrawals or open
     * trades. Safe against accidental double-click: a second POST
     * while the flag is set is a no-op and returns the existing
     * timestamp.
     */
    @PostMapping('/delete-account')
    @Transactional
    ResponseEntity<Map> requestAccountDeletion(HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException('Unknown user') }
        boolean fresh = user.deletionRequestedAt == null
        if (fresh) {
            user.deletionRequestedAt = System.currentTimeMillis()
            steamUserRepository.save(user)
            log.info("User ${uid} requested account deletion")
            // Email receipt — only fires on the first request, not the
            // no-op second-click path. Silent-fail so SMTP outages don't
            // roll back the request. Gated on email-verified as the
            // other security emails do.
            if (fresh && emailService != null && emailService.canSendSecurityTo(user)) {
                try {
                    emailService.sendDeletionRequested(user.email, user.displayName)
                } catch (Exception e) {
                    log.warn("Deletion-request email failed for user ${uid}: ${e.message}")
                }
            }
        }
        ResponseEntity.ok([
            requested:  true,
            requestedAt: user.deletionRequestedAt,
            message:    'Your deletion request has been recorded. Staff will review it within 1-2 business days.'
        ])
    }

    /**
     * Toggle the email-notifications preference. Doesn't affect
     * security/operational emails (verification, password reset).
     * Body: { enabled: boolean }.
     */
    @PutMapping('/email-notifications')
    @Transactional
    ResponseEntity<Map> updateEmailNotifications(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException('Unknown user') }
        if (body == null || body.enabled == null) {
            throw new BadRequestException('MISSING_FIELD', "'enabled' (boolean) is required")
        }
        user.emailNotificationsEnabled = (body.enabled as Boolean)
        steamUserRepository.save(user)
        log.info("User ${uid} set emailNotificationsEnabled=${user.emailNotificationsEnabled}")
        ResponseEntity.ok([emailNotificationsEnabled: user.emailNotificationsEnabled])
    }

    /**
     * Aggregate count of things that need the user's attention right now —
     * drives the red dot on the nav avatar. Cheap: 5 indexed-COUNT queries
     * executed in parallel on the JPA connection pool, each hitting a
     * narrow (state, userId) index. Returns per-kind breakdown so the
     * frontend can tooltip the badge with "2 trades, 1 offer to respond".
     *
     * Sums:
     *   sellerTrades   → PENDING_SELLER_ACCEPT + PENDING_SELLER_SEND
     *   buyerTrades    → PENDING_BUYER_CONFIRM
     *   disputedTrades → DISPUTED (either role)
     *   incomingOffers → PENDING offers ON the user's listings
     *   outgoingOffers → PENDING offers the user made (seller silence)
     *
     * Public to authenticated callers only. Anonymous → 401.
     */
    @GetMapping('/pending-actions')
    ResponseEntity<Map> pendingActions(HttpServletRequest req) {
        def uid = requireUser(req)
        long sellerTrades   = tradeRepository?.countSellerPending(uid) ?: 0L
        long buyerTrades    = tradeRepository?.countBuyerPending(uid) ?: 0L
        long disputed       = tradeRepository?.countDisputedForUser(uid) ?: 0L
        long incomingOffers = offerRepository?.countPendingBySeller(uid) ?: 0L
        long outgoingOffers = offerRepository?.countPendingByBuyer(uid) ?: 0L
        // Unread chat messages from any trade counterparty (batch 282).
        // A buyer with 5 unread "did you send the offer?" replies should
        // see the same red dot a pending trade triggers.
        long unreadChat     = tradeMessageRepository?.countUnreadForUser(uid) ?: 0L
        long pendingReviews = reviewService?.countPendingReviewsFor(uid) ?: 0L
        long total = sellerTrades + buyerTrades + disputed + incomingOffers + unreadChat + pendingReviews
        // outgoingOffers is intentionally NOT summed into the badge —
        // you can't take action on an offer you already sent; the number
        // is exposed for tooltip context only.
        ResponseEntity.ok([
            total:          total,
            sellerTrades:   sellerTrades,
            buyerTrades:    buyerTrades,
            disputedTrades: disputed,
            incomingOffers: incomingOffers,
            outgoingOffers: outgoingOffers,
            unreadChat:     unreadChat,
            // Trades the buyer settled but never reviewed (batch 338).
            // Summed into total so the nav avatar badge reflects the
            // real "things the user could act on" count, not just
            // trades-in-flight.
            pendingReviews: pendingReviews
        ])
    }

    /** Read the user's per-bucket email mutes plus the list of buckets
     *  the UI should render as toggles. `available` is static but served
     *  from the controller so the frontend stays in lockstep with
     *  whatever the server actually honours in EmailService. */
    @GetMapping('/email-mutes')
    ResponseEntity<Map> emailMutes(HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException('Unknown user') }
        def raw = user.mutedEmailKinds ?: ''
        def muted = raw.split(/,/).collect { it?.trim() }.findAll {
            it && com.sboxmarket.service.EmailService.MUTABLE_EMAIL_BUCKETS.contains(it)
        }.toSet()
        ResponseEntity.ok([
            available: com.sboxmarket.service.EmailService.MUTABLE_EMAIL_BUCKETS.toList().sort(),
            muted:     muted.toList().sort()
        ])
    }

    /** Replace the user's per-bucket email mutes. Accepts `{ muted: [...] }`
     *  — any bucket not in the whitelist is rejected so a crafted body
     *  can't wedge a nonsense value into the string. */
    @PutMapping('/email-mutes')
    @Transactional
    ResponseEntity<Map> updateEmailMutes(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException('Unknown user') }
        def raw = body?.muted
        if (raw != null && !(raw instanceof List)) {
            throw new BadRequestException('MISSING_FIELD', "'muted' must be an array of bucket names")
        }
        def requested = (raw as List ?: []).collect { it?.toString()?.trim()?.toUpperCase() }.findAll { it }
        def unknown   = requested.findAll { !com.sboxmarket.service.EmailService.MUTABLE_EMAIL_BUCKETS.contains(it) }
        if (unknown) {
            throw new BadRequestException('INVALID_BUCKET',
                "Unknown email bucket(s): ${unknown.join(', ')}. Allowed: ${com.sboxmarket.service.EmailService.MUTABLE_EMAIL_BUCKETS.toList().sort().join(', ')}")
        }
        user.mutedEmailKinds = requested.unique().sort().join(',')
        steamUserRepository.save(user)
        log.info("User ${uid} set mutedEmailKinds='${user.mutedEmailKinds}'")
        ResponseEntity.ok([muted: requested.unique().sort()])
    }

    /** Cancel a pending deletion request. */
    /**
     * Sign out every active session on this account (batch 697). Bumps
     * the user's sessionEpoch so the SessionEpochFilter will 401 every
     * currently-live cookie on the next request — including the
     * caller's own. This closes a real gap: the batch-606 new-sign-in
     * email and batch-691 api-key-mint alert both instructed the user
     * to "sign out everywhere" as the first recovery step, but there
     * was no self-service endpoint. Previously that required opening
     * a support ticket for staff to bump the epoch manually.
     *
     * Security posture: the bumped epoch invalidates the current
     * request's cookie mid-flight, so the 200 response doubles as the
     * logout confirmation — the next /api/* fetch lands on 401 and
     * the SPA session-expired banner fires. No CSRF or 2FA gate
     * beyond what's already on /api/*: a compromised session could
     * "lock itself out" at worst (which is the GOAL if an attacker
     * held the cookie). The audit log captures who triggered it.
     */
    @PostMapping('/sign-out-everywhere')
    @Transactional
    ResponseEntity<Map> signOutEverywhere(HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException('Unknown user') }
        user.sessionEpoch = System.currentTimeMillis()
        steamUserRepository.save(user)
        log.info("User ${uid} force-signed out everywhere")
        // Audit the action so staff can see when a user triggered a
        // self-logout. Especially important when triaging "did the user
        // actually respond to the security alert?" — staff can grep
        // SESSION_LOGOUT_ALL for the user id and see the timestamp.
        // Non-fatal: audit failures must not abort the logout.
        try {
            auditService?.log(com.sboxmarket.service.AuditService.SESSION_LOGOUT_ALL,
                uid, uid, uid,
                "User self-invalidated every live session (Profile → Personal → Sign out everywhere)")
        } catch (Exception ignore) { /* tolerated */ }
        try {
            // Invalidate the current HTTP session so the logout is
            // immediate even before the next /api/* request re-reads
            // the epoch. Belt-and-braces next to the DB bump.
            req.getSession(false)?.invalidate()
        } catch (Exception ignore) { /* tolerated — epoch bump is authoritative */ }
        ResponseEntity.ok([signedOut: true, at: user.sessionEpoch])
    }

    @PostMapping('/delete-account/cancel')
    @Transactional
    ResponseEntity<Map> cancelAccountDeletion(HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException('Unknown user') }
        if (user.deletionRequestedAt != null) {
            user.deletionRequestedAt = null
            steamUserRepository.save(user)
            log.info("User ${uid} cancelled their account deletion request")
        }
        ResponseEntity.ok([cancelled: true])
    }

    /** Resend the email-verification token. Regenerates the token (old
     *  link stops working) and re-delivers via EmailService. Rate-limited
     *  by the filter; 401 if no email is on the account yet. */
    @PostMapping("/email/resend")
    @Transactional
    ResponseEntity<Map> resendVerification(HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        if (!user.email) {
            throw new BadRequestException("NO_EMAIL", "Set an email address first")
        }
        if (user.emailVerified) {
            return ResponseEntity.ok([email: user.email, verified: true, resent: false])
        }
        // 60s per-user cooldown (batch 602). Prevents a UI that double-
        // fires the button or a refresh-spam pattern from flooding
        // SMTP. Separate from the global 20/10s rate limit — this
        // cooldown is specifically tuned to SMTP reputation cost.
        def now = System.currentTimeMillis()
        def last = LAST_RESEND_AT.get(uid)
        if (last != null && (now - last) < RESEND_COOLDOWN_MS) {
            long retryIn = (RESEND_COOLDOWN_MS - (now - last)) / 1000L
            throw new BadRequestException('RESEND_COOLDOWN',
                "Verification email already sent — wait ${retryIn}s before resending.")
        }
        user.emailVerificationToken = randomToken()
        // Batch 647 — fresh 24h window on every resend. Matches the
        // /email (set-new-email) behaviour so a user who rotates email
        // and then clicks resend gets a consistent expiry posture.
        user.emailVerificationTokenExpiresAt = now + (24L * 60L * 60L * 1000L)
        steamUserRepository.save(user)
        emailService.sendVerification(user.email, user.emailVerificationToken)
        // Cap the map size so a pathological id sweep can't grow it
        // unbounded. Simple eviction: when the cap is hit, drop a
        // random entry before inserting the new one.
        if (LAST_RESEND_AT.size() >= RESEND_MAP_CAP) {
            def iter = LAST_RESEND_AT.keySet().iterator()
            if (iter.hasNext()) { iter.next(); iter.remove() }
        }
        LAST_RESEND_AT.put(uid, now)
        def resp = [email: user.email, verified: false, resent: true] as Map
        if (!emailService.smtpReady) resp.token = user.emailVerificationToken
        ResponseEntity.ok(resp)
    }

    @PostMapping("/email/verify")
    @Transactional
    ResponseEntity<Map> verifyEmail(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        def token = (body?.token as String ?: '').trim()
        if (!user.emailVerificationToken || token != user.emailVerificationToken) {
            throw new BadRequestException("INVALID_TOKEN", "Verification token does not match")
        }
        // Batch 647 — enforce the 24h expiry. Tokens minted via the 2FA
        // staging path (totp_pending: prefix) keep `expiresAt` null and
        // short-circuit the check — those are already intra-session and
        // cleared on success/failure. Tokens minted by /email or
        // /email/resend always carry an expiresAt and this gate fires.
        // Null expiresAt on a non-2FA token means the row predates the
        // migration — we honour it as "never expires" for backward
        // compat so a legacy pending-verification user isn't locked out
        // by the deploy. Next resend picks up the new expiry.
        if (user.emailVerificationTokenExpiresAt != null
                && System.currentTimeMillis() > user.emailVerificationTokenExpiresAt) {
            throw new BadRequestException("TOKEN_EXPIRED",
                "This verification link has expired. Open Profile and click Resend to get a new one.")
        }
        user.emailVerified = true
        user.emailVerificationToken = null
        user.emailVerificationTokenExpiresAt = null
        steamUserRepository.save(user)
        ResponseEntity.ok([email: user.email, verified: true])
    }

    // ── 2FA ─────────────────────────────────────────────────────────

    /**
     * Step 1: generate a fresh secret and return the otpauth URL so the
     * user can scan a QR code or paste it into their authenticator app.
     * The secret is NOT yet activated — it's staged on the user row and
     * only becomes active after /2fa/confirm succeeds.
     */
    @PostMapping("/2fa/enroll")
    @Transactional
    ResponseEntity<Map> enroll2fa(HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        def secret = totpService.generateSecret()
        // Store the pending secret but leave totpSecret == null until
        // confirmation succeeds. Use the verificationToken column as a
        // lightweight staging slot so we don't need a new DB column.
        user.emailVerificationToken = "totp_pending:${secret}"
        steamUserRepository.save(user)
        ResponseEntity.ok([
            secret:     secret,
            otpauthUrl: totpService.otpauthUrl(secret, user.email ?: user.steamId64)
        ])
    }

    @PostMapping("/2fa/confirm")
    @Transactional
    ResponseEntity<Map> confirm2fa(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        def stage = user.emailVerificationToken
        if (!stage?.startsWith('totp_pending:')) {
            throw new BadRequestException("NOT_ENROLLING", "Call /2fa/enroll first")
        }
        def secret = stage.substring('totp_pending:'.size())
        def code = (body?.code as String ?: '').trim()
        def step = totpService.verify(secret, code, null)
        if (step < 0) {
            throw new BadRequestException("INVALID_CODE", "That code is invalid or already used")
        }
        user.totpSecret = secret
        user.lastTotpStep = step
        // Clear the staging slot — if the user had a pending email token
        // they'll have to re-enter their email, which is acceptable rare UX.
        user.emailVerificationToken = null
        // Mint backup codes atomically with enrollment so the user can never
        // be in the "2FA on, no recovery path" state. Shown exactly once in
        // the response; only the hashed set is persisted.
        def codes = totpService.generateBackupCodes()
        user.totpRecoveryCodes = codes.hashed as String
        steamUserRepository.save(user)
        ResponseEntity.ok([
            enabled:      true,
            recoveryCodes: codes.plaintext as List
        ])
    }

    @PostMapping("/2fa/disable")
    @Transactional
    ResponseEntity<Map> disable2fa(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        if (!user.totpSecret) {
            return ResponseEntity.ok([enabled: false])
        }
        def code = (body?.code as String ?: '').trim()
        def step = totpService.verify(user.totpSecret, code, user.lastTotpStep)
        if (step < 0) {
            // Allow a recovery code as a one-time override — user who lost
            // their authenticator device can disable 2FA by burning a
            // backup code instead. The consumed code is removed from the
            // stored hash set so it can't be replayed.
            def remaining = totpService.consumeRecoveryCode(user.totpRecoveryCodes, code)
            if (remaining == null) {
                throw new BadRequestException("INVALID_CODE",
                    "Provide a valid 2FA code or a one-time recovery code")
            }
            user.totpRecoveryCodes = remaining
        }
        user.totpSecret = null
        user.lastTotpStep = null
        user.totpRecoveryCodes = null
        steamUserRepository.save(user)
        // Security alert (batch 512). Disabling 2FA is a significant
        // account-security downgrade — notify the verified email so an
        // attacker who stole a session cookie can't silently weaken the
        // account's defences without the owner noticing.
        if (emailService != null && emailService.canSendSecurityTo(user)) {
            try {
                emailService.send2faDisabled(user.email, user.displayName)
            } catch (Exception e) {
                log.warn("2FA-disabled email alert failed for user ${uid}: ${e.message}")
            }
        }
        ResponseEntity.ok([enabled: false])
    }

    /**
     * Regenerate the user's backup codes. Returns a fresh plaintext list
     * (shown once) and replaces the stored hash set — the previous codes
     * become unusable. Requires a valid live TOTP code as proof-of-
     * possession so a stolen session cookie alone can't print new codes.
     */
    @PostMapping("/2fa/regenerate-codes")
    @Transactional
    ResponseEntity<Map> regenerateRecoveryCodes(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        if (!user.totpSecret) {
            throw new BadRequestException("NOT_ENROLLED", "2FA is not enabled on this account")
        }
        def code = (body?.code as String ?: '').trim()
        def step = totpService.verify(user.totpSecret, code, user.lastTotpStep)
        if (step < 0) {
            throw new BadRequestException("INVALID_CODE", "Provide a current 2FA code to regenerate backup codes")
        }
        user.lastTotpStep = step
        def codes = totpService.generateBackupCodes()
        user.totpRecoveryCodes = codes.hashed as String
        steamUserRepository.save(user)
        ResponseEntity.ok([
            enabled:        true,
            recoveryCodes:  codes.plaintext as List
        ])
    }

    /**
     * Returns how many unused backup codes the user has. Does NOT leak the
     * codes themselves (hashes are opaque). Surfaced in the 2FA section so
     * a user with 2 codes left sees "⚠ 2 backup codes remaining" and knows
     * to regenerate before running out.
     */
    @GetMapping("/2fa/recovery-status")
    ResponseEntity<Map> recoveryStatus(HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        int remaining = 0
        def raw = user.totpRecoveryCodes
        if (raw) {
            remaining = raw.trim().split(/\s+/).findAll { it }.size()
        }
        ResponseEntity.ok([
            enabled:        user.totpSecret != null,
            remainingCodes: remaining
        ])
    }

    private static String randomToken() {
        def b = new byte[16]
        RNG.nextBytes(b)
        b.encodeHex().toString()
    }
}
