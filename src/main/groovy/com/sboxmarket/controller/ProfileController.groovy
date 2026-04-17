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
    @Autowired(required = false) com.sboxmarket.repository.BuyOrderRepository buyOrderRepository
    @Autowired(required = false) com.sboxmarket.repository.BidRepository bidRepository
    @Autowired(required = false) com.sboxmarket.repository.ReviewRepository reviewRepository
    @Autowired(required = false) com.sboxmarket.repository.NotificationRepository notificationRepository

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
        sb.append('id,date,role,listingId,status,amount,counterpartyId\n')
        def df = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        df.timeZone = java.util.TimeZone.getTimeZone('UTC')
        def csvEscape = { String v ->
            if (v == null) return ''
            if (v.contains(',') || v.contains('"') || v.contains('\n')) {
                return '"' + v.replace('"', '""') + '"'
            }
            v
        }
        rows.each { o ->
            def isBuyer = (o.buyerUserId == uid)
            def role = isBuyer ? 'BUYER' : 'SELLER'
            def counterparty = isBuyer ? o.sellerUserId : o.buyerUserId
            sb.append(o.id ?: '').append(',')
              .append(df.format(new Date(o.createdAt ?: 0))).append(',')
              .append(role).append(',')
              .append(o.listingId ?: '').append(',')
              .append(csvEscape(o.status ?: '')).append(',')
              .append((o.amount ?: BigDecimal.ZERO).toPlainString()).append(',')
              .append(counterparty ?: '').append('\n')
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
        sb.append('id,date,state,role,counterparty,itemName,price,fee,net\n')
        def df = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        df.timeZone = java.util.TimeZone.getTimeZone('UTC')
        def csvEscape = { String v ->
            if (v == null) return ''
            if (v.contains(',') || v.contains('"') || v.contains('\n')) {
                return '"' + v.replace('"', '""') + '"'
            }
            v
        }
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
              .append(net.toPlainString()).append('\n')
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
        user.email = textSanitizer.cleanShort(emailRaw)
        user.emailVerified = false
        user.emailVerificationToken = randomToken()
        steamUserRepository.save(user)
        // Hand the token off to EmailService — it either sends a real
        // email (SMTP configured) or logs it for local dev / CI. We only
        // echo the token back in the JSON when SMTP is NOT wired up, so
        // a real production instance with mail enabled never leaks it.
        emailService.sendVerification(user.email, user.emailVerificationToken)
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
        def raw = (body?.tradeUrl as String ?: '').trim()
        if (raw.isEmpty()) {
            user.tradeUrl = null
        } else {
            if (!TRADE_URL_RE.matcher(raw).matches()) {
                throw new BadRequestException("INVALID_TRADE_URL",
                    "Paste your Steam trade URL — it looks like https://steamcommunity.com/tradeoffer/new/?partner=…&token=…")
            }
            user.tradeUrl = raw
        }
        steamUserRepository.save(user)
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
            if (fresh && emailService != null && Boolean.TRUE.equals(user.emailVerified) && user.email) {
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

    /** Cancel a pending deletion request. */
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
        user.emailVerificationToken = randomToken()
        steamUserRepository.save(user)
        emailService.sendVerification(user.email, user.emailVerificationToken)
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
        user.emailVerified = true
        user.emailVerificationToken = null
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
        steamUserRepository.save(user)
        ResponseEntity.ok([enabled: true])
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
            throw new BadRequestException("INVALID_CODE", "Provide a valid 2FA code to disable 2FA")
        }
        user.totpSecret = null
        user.lastTotpStep = null
        steamUserRepository.save(user)
        ResponseEntity.ok([enabled: false])
    }

    private static String randomToken() {
        def b = new byte[16]
        RNG.nextBytes(b)
        b.encodeHex().toString()
    }
}
