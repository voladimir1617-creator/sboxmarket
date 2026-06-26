package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Listing
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.SupportMessage
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.SupportTicketRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.AdminAuthorization
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Covers the admin moderation primitives: ban/unban, grant/revoke admin,
 * credit wallet, approve/reject withdrawal. All of these gate on
 * `requireAdmin` and the spec asserts that failure path AND the
 * downstream side effects.
 */
class AdminServiceSpec extends Specification {

    SteamUserRepository     steamUserRepository     = Mock()
    WalletRepository        walletRepository        = Mock()
    TransactionRepository   transactionRepository   = Mock()
    ListingRepository       listingRepository       = Mock()
    com.sboxmarket.repository.ListingReportRepository listingReportRepository = Mock()
    SupportTicketRepository supportTicketRepository = Mock()
    ItemRepository          itemRepository          = Mock()
    NotificationService     notificationService     = Mock()
    TextSanitizer           textSanitizer           = Mock() {
        medium(_) >> { String s -> s }
    }
    AdminAuthorization      adminAuthorization      = Mock()
    BanGuard                banGuard                = Mock()
    com.sboxmarket.service.EmailService emailService = Mock() {
        // Batch 623: mimic the real canSendSecurityTo gate (verified +
        // has email). Tests with `emailVerified: false` / null email
        // fixtures still see the gate close; no per-test stub churn.
        // Single-arg closure form: Spock passes `args` list directly.
        canSendSecurityTo(_) >> { args ->
            def user = args[0]
            user != null &&
            user.email && !user.email.isEmpty() &&
            Boolean.TRUE.equals(user.emailVerified)
        }
    }
    com.sboxmarket.repository.TradeRepository tradeRepository = Mock()
    com.sboxmarket.service.TradeService tradeService = Mock()
    com.sboxmarket.repository.WatchlistAlertRepository watchlistAlertRepository = Mock()
    com.sboxmarket.service.BuyOrderService buyOrderService = Mock()
    com.sboxmarket.repository.OfferRepository offerRepository = Mock()
    com.sboxmarket.repository.SupportMessageRepository supportMessageRepository = Mock()
    com.sboxmarket.repository.ReviewRepository reviewRepository = Mock()
    com.sboxmarket.repository.TradeMessageRepository tradeMessageRepository = Mock()
    com.sboxmarket.service.AuditService auditService = Mock()

    @Subject
    AdminService service = new AdminService(
        steamUserRepository      : steamUserRepository,
        walletRepository         : walletRepository,
        transactionRepository    : transactionRepository,
        listingRepository        : listingRepository,
        listingReportRepository  : listingReportRepository,
        supportTicketRepository  : supportTicketRepository,
        supportMessageRepository : supportMessageRepository,
        itemRepository           : itemRepository,
        notificationService      : notificationService,
        textSanitizer            : textSanitizer,
        adminAuthorization       : adminAuthorization,
        banGuard                 : banGuard,
        emailService             : emailService,
        auditService             : auditService,
        tradeRepository          : tradeRepository,
        tradeService             : tradeService,
        watchlistAlertRepository : watchlistAlertRepository,
        buyOrderService          : buyOrderService,
        offerRepository          : offerRepository,
        reviewRepository         : reviewRepository,
        tradeMessageRepository   : tradeMessageRepository
    )

    // ── ban / unban ───────────────────────────────────────────────

    def "banUser flips banned=true and cancels active listings"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222', displayName: 'Bob', role: 'USER', banned: false)
        def activeListing = new Listing(id: 100L, sellerUserId: 20L, status: 'ACTIVE')
        steamUserRepository.findById(20L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }
        listingRepository.findActiveBySeller(20L) >> [activeListing]
        listingRepository.saveAll(_) >> { args -> args[0] }

        when:
        def result = service.banUser(1L, 20L, 'bad behaviour')

        then:
        1 * adminAuthorization.requireAdmin(1L)
        result.banned == true
        result.banReason == 'bad behaviour'
        activeListing.status == 'CANCELLED'
        1 * notificationService.safePush(20L, 'ACCOUNT_BANNED', _, _, _, _)
    }

    def "banUser forbids self-ban"() {
        when:
        service.banUser(1L, 1L, 'oops')

        then:
        1 * adminAuthorization.requireAdmin(1L)
        thrown(BadRequestException)
    }

    def "banUser refuses to ban another admin"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222', role: 'ADMIN', banned: false)
        steamUserRepository.findById(20L) >> Optional.of(target)

        when:
        service.banUser(1L, 20L, 'nope')

        then:
        1 * adminAuthorization.requireAdmin(1L)
        thrown(BadRequestException)
    }

    def "banUser 404s for unknown target"() {
        given:
        steamUserRepository.findById(_) >> Optional.empty()

        when:
        service.banUser(1L, 999L, 'x')

        then:
        thrown(NotFoundException)
    }

    def "unbanUser clears banned flag + banReason"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222', banned: true, banReason: 'old')
        steamUserRepository.findById(20L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.unbanUser(1L, 20L)

        then:
        1 * adminAuthorization.requireAdmin(1L)
        result.banned == false
        result.banReason == null
    }

    def "banUser cancels the banned user's outgoing PENDING offers + pings the sellers (batch 598)"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222', displayName: 'Bob',
            role: 'USER', banned: false, sessionEpoch: 0L)
        steamUserRepository.findById(20L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }
        listingRepository.findActiveBySeller(20L) >> []
        // Two outgoing offers — one PENDING (must be cancelled),
        // one already REJECTED (must be left alone).
        def pendingOffer = new com.sboxmarket.model.Offer(
            id: 1L, buyerUserId: 20L, sellerUserId: 50L,
            itemName: 'Hat', status: 'PENDING',
            amount: new BigDecimal('40'))
        def rejectedOffer = new com.sboxmarket.model.Offer(
            id: 2L, buyerUserId: 20L, sellerUserId: 51L,
            itemName: 'Pants', status: 'REJECTED',
            amount: new BigDecimal('30'))
        // Batch 1030 — ban cascade now queries PENDING-only at the repo
        // layer. Non-PENDING row `rejectedOffer` is no longer surfaced
        // to the service; the test stub matches the narrowed fetch.
        offerRepository.findPendingByBuyer(20L) >> [pendingOffer]
        offerRepository.saveAll(_) >> { args -> args[0] }

        when:
        service.banUser(1L, 20L, 'reason')

        then:
        // Only the PENDING row flipped to CANCELLED.
        pendingOffer.status == 'CANCELLED'
        rejectedOffer.status == 'REJECTED'
        // Seller of the PENDING offer got a notification.
        1 * notificationService.push(50L, 'OFFER_REJECTED', _, _, 1L, '/offers')
        // Seller of the already-REJECTED offer was NOT re-notified.
        0 * notificationService.push(51L, 'OFFER_REJECTED', _, _, _, _)
    }

    def "banUser cancels the banned user's active buy orders (batch 598)"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222', displayName: 'Bob',
            role: 'USER', banned: false)
        steamUserRepository.findById(20L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }
        listingRepository.findActiveBySeller(20L) >> []

        when:
        service.banUser(1L, 20L, 'reason')

        then:
        1 * buyOrderService.cancelAllForUser(20L)
    }

    def "banUser bumps sessionEpoch so live sessions invalidate on next request (batch 592)"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222', displayName: 'Bob',
            role: 'USER', banned: false, sessionEpoch: 0L)
        steamUserRepository.findById(20L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }
        listingRepository.findActiveBySeller(20L) >> []
        def before = System.currentTimeMillis()

        when:
        def result = service.banUser(1L, 20L, 'reason')

        then:
        // Epoch must be set to a fresh timestamp — SessionEpochFilter
        // compares stashed vs live; the bump makes every stashed cookie
        // stale on its next round-trip.
        result.sessionEpoch != null
        result.sessionEpoch >= before
    }

    def "forceLogout bumps sessionEpoch without flipping the banned flag (batch 592)"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222', displayName: 'Bob',
            role: 'USER', banned: false, sessionEpoch: 100L)
        steamUserRepository.findById(20L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }
        def before = System.currentTimeMillis()

        when:
        def result = service.forceLogout(1L, 20L)

        then:
        1 * adminAuthorization.requireAdmin(1L)
        result.sessionEpoch >= before
        result.banned == false   // still NOT banned
    }

    def "forceLogout refuses self-logout (batch 592)"() {
        when:
        service.forceLogout(1L, 1L)

        then:
        1 * adminAuthorization.requireAdmin(1L)
        def ex = thrown(BadRequestException)
        ex.code == 'CANT_FORCE_LOGOUT_SELF'
    }

    def "forceLogout 404s for unknown target (batch 592)"() {
        given:
        steamUserRepository.findById(999L) >> Optional.empty()

        when:
        service.forceLogout(1L, 999L)

        then:
        thrown(NotFoundException)
    }

    def "forceLogout emails the user when they have a verified address on file (batch 702)"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222', displayName: 'Bob',
            role: 'USER', banned: false, sessionEpoch: 100L,
            email: 'bob@example.com', emailVerified: true)
        steamUserRepository.findById(20L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }
        emailService.canSendSecurityTo(target) >> true

        when:
        service.forceLogout(1L, 20L)

        then:
        1 * emailService.sendForceLogout('bob@example.com', 'Bob', null)
    }

    def "forceLogout silently skips the email when no address is on file"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222', displayName: 'Bob',
            role: 'USER', banned: false, sessionEpoch: 100L, email: null)
        steamUserRepository.findById(20L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }
        emailService.canSendSecurityTo(target) >> false

        when:
        service.forceLogout(1L, 20L)

        then:
        // No email on file → nothing to send. Must not throw NPE.
        0 * emailService.sendForceLogout(_, _, _)
    }

    def "forceLogout does NOT email an unverified third-party address (security regression)"() {
        // Regression for the gap before the canSendSecurityTo() rollout
        // on this path: previously `if (user.email)` was the only gate,
        // so a user who registered a victim's email but never proved
        // ownership would, on admin-initiated force-logout, leak the
        // account's displayName + a "your account was force-logged-out"
        // notice to the unrelated third party. Every other security
        // email in AdminService already passes through
        // `canSendSecurityTo`; this test pins force-logout to the same
        // rule so a future refactor can't quietly regress it.
        given:
        def target = new SteamUser(id: 21L, steamId64: '333', displayName: 'Eve',
            role: 'USER', banned: false, sessionEpoch: 100L,
            email: 'victim@example.com', emailVerified: false)
        steamUserRepository.findById(21L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }
        emailService.canSendSecurityTo(target) >> false

        when:
        service.forceLogout(1L, 21L)

        then:
        // sessionEpoch still bumped — the security action itself runs
        // regardless of the email path.
        target.sessionEpoch > 100L
        // But absolutely no mail to the unverified address.
        0 * emailService.sendForceLogout(_, _, _)
    }

    // ── grant / revoke admin ──────────────────────────────────────

    def "grantAdmin flips role to ADMIN and emails the verified target"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222', role: 'USER',
            email: 'p@x.io', emailVerified: true, displayName: 'Pat')
        steamUserRepository.findById(20L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.grantAdmin(1L, 20L)

        then:
        result.role == 'ADMIN'
        // Batch 320 — security alert on privilege grant. canSendSecurityTo
        // gate (mocked in this spec) keeps a no-email / unverified target
        // silent and lets the verified one through.
        1 * emailService.sendRoleGranted('p@x.io', 'Pat', 'ADMIN')
    }

    def "grantAdmin stays silent when target has no verified email"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222', role: 'USER',
            email: null, emailVerified: false)
        steamUserRepository.findById(20L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.grantAdmin(1L, 20L)

        then:
        0 * emailService.sendRoleGranted(_, _, _)
    }

    def "revokeAdmin flips role back to USER and emails the verified target"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222', role: 'ADMIN',
            email: 'p@x.io', emailVerified: true, displayName: 'Pat')
        steamUserRepository.findById(20L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }
        steamUserRepository.countByRole('ADMIN') >> 2   // not the last admin

        when:
        def result = service.revokeAdmin(1L, 20L)

        then:
        result.role == 'USER'
        1 * emailService.sendRoleRevoked('p@x.io', 'Pat', 'ADMIN')
    }

    def "revokeAdmin refuses to demote the last remaining admin (lockout guard)"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222', role: 'ADMIN',
            email: 'p@x.io', emailVerified: true, displayName: 'Pat')
        steamUserRepository.findById(20L) >> Optional.of(target)
        steamUserRepository.countByRole('ADMIN') >> 1   // the only admin

        when:
        service.revokeAdmin(1L, 20L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'LAST_ADMIN'
        0 * steamUserRepository.save(_)
    }

    def "revokeAdmin forbids self-revoke"() {
        when:
        service.revokeAdmin(1L, 1L)

        then:
        1 * adminAuthorization.requireAdmin(1L)
        thrown(BadRequestException)
    }

    // ── grant / revoke CSR ────────────────────────────────────────

    def "grantCsr flips USER role to CSR and emails the verified target"() {
        given:
        def target = new SteamUser(id: 21L, steamId64: '333', role: 'USER',
            email: 'c@x.io', emailVerified: true, displayName: 'Cam')
        steamUserRepository.findById(21L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.grantCsr(1L, 21L)

        then:
        result.role == 'CSR'
        1 * emailService.sendRoleGranted('c@x.io', 'Cam', 'CSR')
    }

    def "grantCsr refuses banned accounts"() {
        given:
        def target = new SteamUser(id: 21L, steamId64: '333', role: 'USER', banned: true)
        steamUserRepository.findById(21L) >> Optional.of(target)

        when:
        service.grantCsr(1L, 21L)

        then:
        thrown(BadRequestException)
    }

    def "grantCsr refuses ADMIN downgrade — force explicit revoke-admin first"() {
        given:
        def target = new SteamUser(id: 21L, steamId64: '333', role: 'ADMIN')
        steamUserRepository.findById(21L) >> Optional.of(target)

        when:
        service.grantCsr(1L, 21L)

        then:
        thrown(BadRequestException)
    }

    def "revokeCsr flips CSR back to USER and emails the verified target"() {
        given:
        def target = new SteamUser(id: 21L, steamId64: '333', role: 'CSR',
            email: 'c@x.io', emailVerified: true, displayName: 'Cam')
        steamUserRepository.findById(21L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.revokeCsr(1L, 21L)

        then:
        result.role == 'USER'
        1 * emailService.sendRoleRevoked('c@x.io', 'Cam', 'CSR')
    }

    def "revokeCsr refuses non-CSR accounts"() {
        given:
        def target = new SteamUser(id: 21L, steamId64: '333', role: 'USER')
        steamUserRepository.findById(21L) >> Optional.of(target)

        when:
        service.revokeCsr(1L, 21L)

        then:
        thrown(BadRequestException)
    }

    // ── reset 2FA ─────────────────────────────────────────────────

    def "reset2faFor wipes the secret, notifies the user, and writes an audit row"() {
        given:
        def target = new SteamUser(id: 22L, steamId64: '444', role: 'USER', totpSecret: 'ABCDEF', lastTotpStep: 123L, sessionEpoch: 0L)
        steamUserRepository.findById(22L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }
        def before = System.currentTimeMillis()

        when:
        def res = service.reset2faFor(1L, 22L, 'Lost phone — confirmed via Steam OpenID handshake')

        then:
        target.totpSecret == null
        target.lastTotpStep == null
        // Batch 592: 2FA reset bumps sessionEpoch to kick any attacker
        // session that might have survived the password-less login flow.
        target.sessionEpoch != null && target.sessionEpoch >= before
        res.totpEnabled == false
        1 * notificationService.safePush(22L, 'TWOFA_RESET', _, _, _, _)
    }

    // Adversarial bug hunt — recovery-code residue. /2fa/disable wipes
    // totpSecret, lastTotpStep AND totpRecoveryCodes atomically; admin
    // reset must do the same. Leaving the hash list on a row with
    // totpSecret == null violates the confirm2fa invariant ("2FA enabled
    // ↔ recovery codes exist") and leaves a stale-state landmine for any
    // future code path that learns to consume codes without the
    // totpSecret gate. Pin all three columns to null.
    def "reset2faFor also wipes orphaned recovery-code hashes — no residue"() {
        given:
        def target = new SteamUser(id: 22L, steamId64: '444', role: 'USER',
            totpSecret: 'ABCDEF', lastTotpStep: 123L,
            totpRecoveryCodes: 'aaa bbb ccc ddd eee fff ggg hhh iii jjj')
        steamUserRepository.findById(22L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.reset2faFor(1L, 22L, 'Lost phone')

        then:
        target.totpSecret == null
        target.lastTotpStep == null
        target.totpRecoveryCodes == null
    }

    def "reset2faFor fires a security-alert email to the user's verified address (batch 575)"() {
        given:
        def target = new SteamUser(
            id: 22L, steamId64: '444', displayName: 'Alice', role: 'USER',
            totpSecret: 'ABCDEF', lastTotpStep: 123L,
            email: 'alice@example.com', emailVerified: true)
        steamUserRepository.findById(22L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.reset2faFor(1L, 22L, 'Lost phone')

        then:
        1 * emailService.sendTwoFactorReset('alice@example.com', 'Alice', 'Lost phone')
    }

    def "reset2faFor skips the security email when the user has no verified address"() {
        given:
        def target = new SteamUser(
            id: 22L, steamId64: '444', displayName: 'Alice', role: 'USER',
            totpSecret: 'ABCDEF', email: 'alice@example.com', emailVerified: false)
        steamUserRepository.findById(22L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.reset2faFor(1L, 22L, 'note')

        then:
        0 * emailService.sendTwoFactorReset(*_)
    }

    def "reset2faFor refuses when the user has no TOTP to reset"() {
        given:
        def target = new SteamUser(id: 22L, steamId64: '444', role: 'USER', totpSecret: null)
        steamUserRepository.findById(22L) >> Optional.of(target)

        when:
        service.reset2faFor(1L, 22L, 'note')

        then:
        thrown(BadRequestException)
        0 * notificationService.push(_, _, _, _, _, _)
    }

    def "reset2faFor 404s for unknown user"() {
        given:
        steamUserRepository.findById(999L) >> Optional.empty()

        when:
        service.reset2faFor(1L, 999L, 'note')

        then:
        thrown(com.sboxmarket.exception.NotFoundException)
    }

    // ── admin-only user notes ────────────────────────────────────

    def "readAdminNotes returns the current value (or empty string)"() {
        given:
        def target = new SteamUser(id: 30L, steamId64: '5', adminNotes: 'flagged 2025-01')
        steamUserRepository.findById(30L) >> Optional.of(target)

        when:
        def res = service.readAdminNotes(1L, 30L)

        then:
        res.adminNotes == 'flagged 2025-01'
    }

    def "readAdminNotes returns '' when the field is null"() {
        given:
        def target = new SteamUser(id: 30L, steamId64: '5', adminNotes: null)
        steamUserRepository.findById(30L) >> Optional.of(target)

        expect:
        service.readAdminNotes(1L, 30L).adminNotes == ''
    }

    def "writeAdminNotes persists the value and audit-logs"() {
        given:
        def target = new SteamUser(id: 30L, steamId64: '5', adminNotes: null)
        steamUserRepository.findById(30L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        def res = service.writeAdminNotes(1L, 30L, 'chargeback Oct 2025 refunded $50')

        then:
        target.adminNotes == 'chargeback Oct 2025 refunded $50'
        res.adminNotes.contains('chargeback')
    }

    def "writeAdminNotes 404s for unknown user"() {
        given:
        steamUserRepository.findById(999L) >> Optional.empty()

        when:
        service.writeAdminNotes(1L, 999L, 'x')

        then:
        thrown(com.sboxmarket.exception.NotFoundException)
    }

    // ── ban/unban email hook ─────────────────────────────────────

    def "banUser emails the target when they have a verified email"() {
        given:
        def target = new SteamUser(id: 50L, steamId64: '123', role: 'USER',
            email: 'user@example.com', emailVerified: true, displayName: 'Bob')
        steamUserRepository.findById(50L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }
        listingRepository.findActiveBySeller(50L) >> []
        listingRepository.saveAll(_) >> { args -> args[0] }

        when:
        service.banUser(1L, 50L, 'chargeback')

        then:
        1 * emailService.sendAccountBanned('user@example.com', 'Bob', _, _)
    }

    def "banUser skips the email when the address is unverified"() {
        given:
        def target = new SteamUser(id: 50L, steamId64: '123', role: 'USER',
            email: 'user@example.com', emailVerified: false, displayName: 'Bob')
        steamUserRepository.findById(50L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }
        listingRepository.findActiveBySeller(50L) >> []
        listingRepository.saveAll(_) >> { args -> args[0] }

        when:
        service.banUser(1L, 50L, 'x')

        then:
        0 * emailService.sendAccountBanned(_, _, _, _)
    }

    def "banUser keeps going when the email hook throws"() {
        given:
        def target = new SteamUser(id: 50L, steamId64: '123', role: 'USER',
            email: 'user@example.com', emailVerified: true, displayName: 'Bob')
        steamUserRepository.findById(50L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }
        listingRepository.findActiveBySeller(50L) >> []
        listingRepository.saveAll(_) >> { args -> args[0] }
        emailService.sendAccountBanned(_, _, _, _) >> { throw new RuntimeException('SMTP down') }

        when:
        def res = service.banUser(1L, 50L, 'x')

        then:
        res.banned == true
        noExceptionThrown()
    }

    def "unbanUser emails the target when verified"() {
        given:
        def target = new SteamUser(id: 50L, steamId64: '123', banned: true,
            email: 'user@example.com', emailVerified: true, displayName: 'Bob')
        steamUserRepository.findById(50L) >> Optional.of(target)
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.unbanUser(1L, 50L)

        then:
        1 * emailService.sendAccountUnbanned('user@example.com', 'Bob')
    }

    // ── sendTestEmail ────────────────────────────────────────────

    def "sendTestEmail dispatches through EmailService.send"() {
        when:
        def res = service.sendTestEmail(1L, 'ops@example.com', 'Test subject', 'Test body')

        then:
        1 * emailService.send('ops@example.com', 'Test subject', 'Test body')
        res.sent == true
    }

    def "sendTestEmail refuses an invalid address"() {
        when:
        service.sendTestEmail(1L, bad, 'subj', 'body')

        then:
        thrown(BadRequestException)
        0 * emailService.send(_, _, _)

        where:
        bad << [null, '', '   ', 'not-an-email']
    }

    def "sendTestEmail surfaces an SMTP failure as BadRequest"() {
        given:
        emailService.send(_, _, _) >> { throw new RuntimeException('connection refused') }

        when:
        service.sendTestEmail(1L, 'ops@example.com', 'x', 'y')

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'SEND_FAILED'
    }

    // ── finalizeDeletion (GDPR) ──────────────────────────────────

    def "finalizeDeletion refuses when user has no pending deletion request"() {
        given:
        def target = new SteamUser(id: 60L, steamId64: '999', deletionRequestedAt: null)
        steamUserRepository.findById(60L) >> Optional.of(target)

        when:
        service.finalizeDeletion(1L, 60L)

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'NO_REQUEST'
    }

    def "finalizeDeletion refuses when there is a pending withdrawal"() {
        given:
        def target = new SteamUser(id: 60L, steamId64: '999', displayName: 'Ann',
            deletionRequestedAt: 1234L)
        def wallet = new Wallet(id: 500L, username: 'steam_999', balance: BigDecimal.ZERO)
        steamUserRepository.findById(60L) >> Optional.of(target)
        walletRepository.findByUsername('steam_999') >> wallet
        transactionRepository.countByWalletAndType(500L, 'WITHDRAW') >> 1L
        transactionRepository.findByWalletIdOrderByCreatedAtDesc(500L, _) >> [
            new Transaction(id: 1L, walletId: 500L, type: 'WITHDRAW', status: 'PENDING',
                amount: new BigDecimal('10'))
        ]

        when:
        service.finalizeDeletion(1L, 60L)

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'PENDING_WITHDRAWAL'
    }

    def "finalizeDeletion refuses when there are open trades"() {
        given:
        def target = new SteamUser(id: 60L, steamId64: '999', displayName: 'Ann',
            deletionRequestedAt: 1234L)
        steamUserRepository.findById(60L) >> Optional.of(target)
        walletRepository.findByUsername('steam_999') >> null
        tradeRepository.findByParticipant(60L) >> [
            new com.sboxmarket.model.Trade(id: 1L, state: 'PENDING_BUYER_CONFIRM')
        ]

        when:
        service.finalizeDeletion(1L, 60L)

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'OPEN_TRADES'
    }

    def "finalizeDeletion scrubs PII, bans, clears the request, cancels listings"() {
        given:
        def target = new SteamUser(id: 60L, steamId64: '999', displayName: 'Ann',
            email: 'ann@example.com', emailVerified: true, avatarUrl: 'http://x',
            totpSecret: 'ABC', deletionRequestedAt: 1234L)
        def activeListing = new Listing(id: 200L, sellerUserId: 60L, status: 'ACTIVE')
        steamUserRepository.findById(60L) >> Optional.of(target)
        walletRepository.findByUsername('steam_999') >> null
        tradeRepository.findByParticipant(60L) >> []
        listingRepository.findActiveBySeller(60L) >> [activeListing]
        listingRepository.saveAll(_) >> { args -> args[0] }
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        def res = service.finalizeDeletion(1L, 60L)

        then:
        res.finalised == true
        target.displayName == 'Deleted user #60'
        target.email == null
        target.avatarUrl == null
        target.totpSecret == null
        target.banned == true
        target.deletionRequestedAt == null
        activeListing.status == 'CANCELLED'
        // Batch 597: finalizeDeletion bumps sessionEpoch to kick any
        // still-live session (legit owner OR attacker who initiated
        // the compromise-recovery deletion request).
        target.sessionEpoch != null && target.sessionEpoch > 0L
    }

    // Adversarial bug hunt — recovery-code residue on GDPR delete. The
    // PII scrub at finalizeDeletion zeroes totpSecret + lastTotpStep but
    // (before this fix) left totpRecoveryCodes set, leaking the SHA-256
    // hashes of a deleted user's backup codes onto a row whose docstring
    // claims to "Scrub PII". Same invariant reset2faFor already enforces:
    // 2FA enabled ↔ recovery codes exist. Pin all three columns to null.
    def "finalizeDeletion also wipes orphaned recovery-code hashes — no residue"() {
        given:
        def target = new SteamUser(id: 60L, steamId64: '999', displayName: 'Ann',
            email: 'ann@example.com', emailVerified: true,
            totpSecret: 'ABCDEFGHIJKLMNOP', lastTotpStep: 123L,
            totpRecoveryCodes: 'aaa bbb ccc ddd eee fff ggg hhh iii jjj',
            deletionRequestedAt: 1234L)
        steamUserRepository.findById(60L) >> Optional.of(target)
        walletRepository.findByUsername('steam_999') >> null
        tradeRepository.findByParticipant(60L) >> []
        listingRepository.findActiveBySeller(60L) >> []
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.finalizeDeletion(1L, 60L)

        then:
        target.totpSecret == null
        target.lastTotpStep == null
        target.totpRecoveryCodes == null
    }

    def "finalizeDeletion wipes watchlist alerts + cancels buy orders (batch 313 cleanup)"() {
        // The sweeper-load leak: without this cleanup, WatchlistAlert
        // and BuyOrder rows for a deleted user keep getting scanned
        // forever. finalizeDeletion must flush both.
        given:
        def target = new SteamUser(id: 60L, steamId64: '999', displayName: 'Ann', deletionRequestedAt: 1234L)
        steamUserRepository.findById(60L) >> Optional.of(target)
        walletRepository.findByUsername('steam_999') >> null
        tradeRepository.findByParticipant(60L) >> []
        listingRepository.findActiveBySeller(60L) >> []
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        service.finalizeDeletion(1L, 60L)

        then:
        1 * watchlistAlertRepository.deleteByUser(60L)
        1 * buyOrderService.cancelAllForUser(60L)
    }

    def "finalizeDeletion scrubs user-authored free text — review comments + trade bodies (GDPR, audit P2)"() {
        // The deletion docstring promised a PII scrub, but the user's review
        // comments + trade chat bodies survived as plain text. They must now be
        // blanked while the row skeletons stay for the counterparty.
        given:
        def target = new SteamUser(id: 60L, steamId64: '999', displayName: 'Ann', deletionRequestedAt: 1234L)
        steamUserRepository.findById(60L) >> Optional.of(target)
        walletRepository.findByUsername('steam_999') >> null
        tradeRepository.findByParticipant(60L) >> []
        listingRepository.findActiveBySeller(60L) >> []
        steamUserRepository.save(_) >> { args -> args[0] }

        when:
        def res = service.finalizeDeletion(1L, 60L)

        then:
        1 * reviewRepository.blankCommentsByAuthor(60L)
        1 * tradeMessageRepository.blankBodiesBySender(60L)
        res.finalised == true
    }

    def "finalizeDeletion does NOT swallow a PII-scrub failure — it propagates so the deletion is atomic"() {
        // Self-review fix: the scrub is an @Modifying bulk UPDATE in the method's
        // own @Transactional context. A failure already marks the tx rollback-only,
        // so swallowing it would let the method return finalised:true while Spring
        // silently rolls the whole deletion back (user unbanned, PII intact). The
        // scrub must instead ABORT visibly so the admin retries — deletion and PII
        // scrub are atomic for an erasure request.
        given:
        def target = new SteamUser(id: 60L, steamId64: '999', displayName: 'Ann', deletionRequestedAt: 1234L)
        steamUserRepository.findById(60L) >> Optional.of(target)
        walletRepository.findByUsername('steam_999') >> null
        tradeRepository.findByParticipant(60L) >> []
        listingRepository.findActiveBySeller(60L) >> []
        steamUserRepository.save(_) >> { args -> args[0] }
        reviewRepository.blankCommentsByAuthor(60L) >> { throw new RuntimeException('db down') }

        when:
        service.finalizeDeletion(1L, 60L)

        then:
        def e = thrown(RuntimeException)
        e.message == 'db down'
    }

    def "finalizeDeletion swallows watchlist-alert cleanup failures"() {
        given:
        def target = new SteamUser(id: 60L, steamId64: '999', displayName: 'Ann', deletionRequestedAt: 1234L)
        steamUserRepository.findById(60L) >> Optional.of(target)
        walletRepository.findByUsername('steam_999') >> null
        tradeRepository.findByParticipant(60L) >> []
        listingRepository.findActiveBySeller(60L) >> []
        steamUserRepository.save(_) >> { args -> args[0] }
        watchlistAlertRepository.deleteByUser(60L) >> { throw new RuntimeException('db down') }

        when:
        def res = service.finalizeDeletion(1L, 60L)

        then:
        // Buy-order cleanup still ran even after alert-cleanup failed.
        1 * buyOrderService.cancelAllForUser(60L)
        res.finalised == true
        noExceptionThrown()
    }

    // ── approve / reject withdrawal ───────────────────────────────

    def "approveWithdrawal flips status COMPLETED and notifies owner"() {
        given:
        def tx = new Transaction(id: 1L, walletId: 500L, type: 'WITHDRAW', status: 'PENDING',
                                  amount: new BigDecimal("25"))
        transactionRepository.findById(1L) >> Optional.of(tx)
        transactionRepository.save(_) >> { args -> args[0] }
        walletRepository.findById(500L) >> Optional.of(new Wallet(id: 500L, username: 'steam_111'))
        steamUserRepository.findBySteamId64('111') >> null  // notifyWalletOwner no-ops

        when:
        def result = service.approveWithdrawal(1L, 1L, 'PAYOUT-REF-X')

        then:
        1 * adminAuthorization.requireAdmin(1L)
        1 * transactionRepository.claimApproveWithdrawal(1L, 'PAYOUT-REF-X', _) >> 1
        result.status == 'COMPLETED'
    }

    def "approveWithdrawal refuses non-WITHDRAW transactions"() {
        given:
        transactionRepository.findById(_) >> Optional.of(new Transaction(type: 'DEPOSIT', status: 'PENDING'))

        when:
        service.approveWithdrawal(1L, 1L, 'ref')

        then:
        thrown(BadRequestException)
    }

    def "approveWithdrawal refuses withdrawals already in a terminal state"() {
        given:
        transactionRepository.findById(_) >> Optional.of(new Transaction(type: 'WITHDRAW', status: 'COMPLETED'))

        when:
        service.approveWithdrawal(1L, 1L, 'ref')

        then:
        thrown(BadRequestException)
    }

    def "approveWithdrawal accepts canonical 'WITHDRAWAL' spelling too"() {
        given: 'a legacy PENDING row stored with the canonical WITHDRAWAL spelling'
        def tx = new Transaction(id: 1L, walletId: 500L, type: 'WITHDRAWAL', status: 'PENDING',
                                  amount: new BigDecimal("25"))
        transactionRepository.findById(1L) >> Optional.of(tx)
        transactionRepository.save(_) >> { args -> args[0] }
        walletRepository.findById(500L) >> Optional.of(new Wallet(id: 500L, username: 'steam_111'))
        steamUserRepository.findBySteamId64('111') >> null

        when:
        def result = service.approveWithdrawal(1L, 1L, 'PAYOUT-REF-Y')

        then: 'admin can approve — funds get released instead of being stranded'
        1 * adminAuthorization.requireAdmin(1L)
        1 * transactionRepository.claimApproveWithdrawal(1L, 'PAYOUT-REF-Y', _) >> 1
        result.status == 'COMPLETED'
    }

    def "rejectWithdrawal accepts canonical 'WITHDRAWAL' spelling too"() {
        given: 'a legacy PENDING row stored with the canonical WITHDRAWAL spelling'
        def tx = new Transaction(id: 1L, walletId: 500L, type: 'WITHDRAWAL', status: 'PENDING',
                                  amount: new BigDecimal("25"))
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: BigDecimal.ZERO)
        transactionRepository.findById(1L) >> Optional.of(tx)
        transactionRepository.save(_) >> { args -> args[0] }
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { args -> args[0] }
        transactionRepository.claimRejectWithdrawal(1L, _, _) >> 1

        when:
        def result = service.rejectWithdrawal(1L, 1L, 'bad payout details')

        then: 'admin can reject and the wallet gets refunded — the row would otherwise be unreachable'
        wallet.balance == new BigDecimal("25")
        result.status == 'FAILED'
        result.refunded == new BigDecimal("25")
    }

    def "rejectWithdrawal marks FAILED and refunds the wallet"() {
        given:
        def tx = new Transaction(id: 1L, walletId: 500L, type: 'WITHDRAW', status: 'PENDING',
                                  amount: new BigDecimal("25"))
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: new BigDecimal("0"))
        transactionRepository.findById(1L) >> Optional.of(tx)
        transactionRepository.save(_) >> { args -> args[0] }
        walletRepository.findById(500L) >> Optional.of(wallet)
        walletRepository.save(_) >> { args -> args[0] }
        transactionRepository.claimRejectWithdrawal(1L, _, _) >> 1

        when:
        def result = service.rejectWithdrawal(1L, 1L, 'KYC failed')

        then:
        wallet.balance == new BigDecimal("25")
        result.status == 'FAILED'
        result.refunded == new BigDecimal("25")
    }

    def "rejectWithdrawal does NOT double-credit when the atomic claim is lost (batch 1082)"() {
        given: 'a withdrawal whose row a concurrent cancel/reject already flipped out of PENDING'
        def tx = new Transaction(id: 1L, walletId: 500L, type: 'WITHDRAWAL', status: 'PENDING',
                                  amount: new BigDecimal("25"))
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: BigDecimal.ZERO)
        transactionRepository.findById(1L) >> Optional.of(tx)
        walletRepository.findById(500L) >> Optional.of(wallet)
        // The conditional UPDATE matched 0 rows — a sibling cancel/reject won
        // the PENDING→terminal flip first (and already refunded).
        transactionRepository.claimRejectWithdrawal(1L, _, _) >> 0

        when:
        service.rejectWithdrawal(1L, 1L, 'duplicate reject')

        then: 'the wallet is NOT refunded a second time; caller told it is no longer pending'
        def e = thrown(BadRequestException)
        e.code == 'NOT_PENDING'
        wallet.balance == BigDecimal.ZERO
        0 * walletRepository.save(_)
    }

    // ── dashboardStats (aggregate path) ───────────────────────────

    def "dashboardStats uses indexed aggregates, not findAll scans"() {
        given:
        steamUserRepository.count() >> 42L
        // These four aggregate calls are the O(1) replacement for the old
        // findAll-then-filter scans. They must all be hit exactly once.
        walletRepository.sumAllBalances() >> new BigDecimal("1234.56")
        transactionRepository.sumByTypeSinceCompleted('DEPOSIT', 'COMPLETED', _) >> new BigDecimal("200")
        transactionRepository.sumByTypeSinceCompleted('SALE',    'COMPLETED', _) >> new BigDecimal("150")
        transactionRepository.countByTypeStatus('WITHDRAW', 'PENDING') >> 3L
        transactionRepository.sumByTypeStatus('WITHDRAW', 'PENDING') >> new BigDecimal("75")
        supportTicketRepository.countOpen() >> 7L
        steamUserRepository.countBanned() >> 1L
        listingRepository.countActive() >> 88L
        // Trade-state probes — batch 110 added these to the dashboard
        // so ops sees disputes + escrow phases at a glance.
        tradeRepository.countByState('DISPUTED')              >> 2L
        tradeRepository.countByState('PENDING_SELLER_ACCEPT') >> 5L
        tradeRepository.countByState('PENDING_SELLER_SEND')   >> 3L
        tradeRepository.countByState('PENDING_BUYER_CONFIRM') >> 4L

        when:
        def stats = service.dashboardStats()

        then:
        // CRITICAL: the old full-table scans must NOT be called
        0 * walletRepository.findAll()
        0 * transactionRepository.findAll()
        0 * supportTicketRepository.findAll()
        0 * steamUserRepository.findAll()

        stats.users == 42L
        stats.totalEscrow == new BigDecimal("1234.56")
        stats.deposits24h == new BigDecimal("200.00")
        stats.sales24h == new BigDecimal("150.00")
        stats.pendingWithdrawals == 3L
        stats.pendingWithdrawalsAmount == new BigDecimal("75.00")
        stats.openTickets == 7L
        stats.bannedUsers == 1L
        stats.activeListings == 88L
        stats.disputedTrades      == 2L
        stats.pendingSellerAccept == 5L
        stats.pendingSellerSend   == 3L
        stats.pendingBuyerConfirm == 4L
    }

    // ── listWithdrawals (indexed) ─────────────────────────────────

    def "listWithdrawals uses the indexed sorted query and batch wallet lookup"() {
        given:
        def pendingTx = new Transaction(
            id: 1L, walletId: 500L, type: 'WITHDRAW', status: 'PENDING',
            amount: new BigDecimal("50"), currency: 'USD',
            createdAt: 1000L, stripeReference: 'acct_ext'
        )
        transactionRepository.findByTypeAndStatusPaged('WITHDRAW', 'PENDING', _) >> [pendingTx]
        // Batched wallet resolve — bug #37 fix. Per-row findById would
        // be an N+1, so the service now calls findAllById once.
        walletRepository.findAllById([500L]) >> [new Wallet(id: 500L, username: 'steam_111')]

        when:
        def result = service.listWithdrawals(null)

        then:
        0 * transactionRepository.findAll()
        0 * walletRepository.findById(_)
        result.size() == 1
        result[0].id == 1L
        result[0].status == 'PENDING'
        result[0].walletUsername == 'steam_111'
    }

    def "listWithdrawals respects an explicit status filter"() {
        when:
        def result = service.listWithdrawals('completed')

        then:
        1 * transactionRepository.findByTypeAndStatusPaged('WITHDRAW', 'COMPLETED', _) >> []
        result == []
    }

    // ── creditWallet ──────────────────────────────────────────────

    def "creditWallet bumps balance and records ADJUSTMENT_CREDIT"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222')
        def wallet = new Wallet(id: 500L, username: 'steam_222', balance: new BigDecimal("10"))
        steamUserRepository.findById(20L) >> Optional.of(target)
        walletRepository.findByUsername('steam_222') >> wallet
        walletRepository.save(_) >> { args -> args[0] }
        transactionRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.creditWallet(1L, 20L, new BigDecimal("50"), 'goodwill')

        then:
        wallet.balance == new BigDecimal("60")
        result.newBalance == new BigDecimal("60")
        1 * transactionRepository.save({ Transaction tx ->
            tx.type == 'ADJUSTMENT_CREDIT' && tx.amount == new BigDecimal("50")
        })
    }

    def "creditWallet records ADJUSTMENT_DEBIT for negative amounts"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222')
        def wallet = new Wallet(id: 500L, username: 'steam_222', balance: new BigDecimal("100"))
        steamUserRepository.findById(20L) >> Optional.of(target)
        walletRepository.findByUsername('steam_222') >> wallet
        walletRepository.save(_) >> { args -> args[0] }
        transactionRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.creditWallet(1L, 20L, new BigDecimal("-30"), 'reversal')

        then:
        wallet.balance == new BigDecimal("70")
        1 * transactionRepository.save({ Transaction tx ->
            tx.type == 'ADJUSTMENT_DEBIT' && tx.amount == new BigDecimal("30")
        })
    }

    def "creditWallet refuses zero amounts"() {
        when:
        service.creditWallet(1L, 20L, BigDecimal.ZERO, 'oops')

        then:
        thrown(BadRequestException)
    }

    def "creditWallet refuses adjustments that would drive balance negative"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222')
        def wallet = new Wallet(id: 500L, username: 'steam_222', balance: new BigDecimal("10"))
        steamUserRepository.findById(20L) >> Optional.of(target)
        walletRepository.findByUsername('steam_222') >> wallet

        when:
        service.creditWallet(1L, 20L, new BigDecimal("-50"), 'too much')

        then:
        thrown(BadRequestException)
    }

    def "creditWallet refuses per-call adjustment over the \$10k sanity cap (bug #40)"() {
        when:
        service.creditWallet(1L, 20L, new BigDecimal("10001"), 'typo')

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'ADJUSTMENT_TOO_LARGE'
        0 * walletRepository.save(_)
    }

    def "creditWallet refuses per-call debit over the sanity cap too (bug #40)"() {
        when:
        service.creditWallet(1L, 20L, new BigDecimal("-50000"), 'big refund')

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'ADJUSTMENT_TOO_LARGE'
    }

    def "creditWallet requires an audit note (bug #40)"() {
        when:
        service.creditWallet(1L, 20L, new BigDecimal("50"), note)

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'NOTE_REQUIRED'

        where:
        note << [null, '', '   ']
    }

    def "creditWallet sanitizes the note before persisting it into Transaction.description (bug #231)"() {
        // Note ends up in Transaction.description (rendered on user's
        // wallet-history page) AND in the push-notification body. Raw
        // operator input here is an XSS / HTML-injection vector and a
        // length DoS — every other admin reason field runs through
        // textSanitizer.medium(); creditWallet was the holdout.
        given:
        def target = new SteamUser(id: 20L, steamId64: '222')
        def wallet = new Wallet(id: 500L, username: 'steam_222', balance: new BigDecimal("10"), currency: 'USD')
        steamUserRepository.findById(20L) >> Optional.of(target)
        walletRepository.findByUsername('steam_222') >> wallet
        Transaction saved = null
        transactionRepository.save(_) >> { args -> saved = args[0]; saved }
        // Per-test sanitizer that returns a recognizable wrapped value,
        // so we can prove the raw operator input was routed through it.
        TextSanitizer scrubbing = Mock()
        scrubbing.medium(_) >> { String s -> '[clean] ' + (s ?: '') }
        service.textSanitizer = scrubbing

        when:
        service.creditWallet(1L, 20L, new BigDecimal("50"), '<script>alert(1)</script>')

        then:
        saved.description == 'Admin adjustment: [clean] <script>alert(1)</script>'
        1 * notificationService.push(20L, 'ADMIN_CREDIT', _ as String,
                '[clean] <script>alert(1)</script>', _, '/wallet')
    }

    // ── Reported-listings moderation loop ─────────────────────────

    def "forceCancelListing notifies every distinct reporter that their report was actioned"() {
        given:
        def item = new com.sboxmarket.model.Item(id: 7L, name: 'Wizard Hat', lowestPrice: new BigDecimal("10"))
        def listing = new Listing(id: 100L, sellerUserId: 999L, status: 'ACTIVE', item: item, reportCount: 3)
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        // Two reports from user 42 + one from user 99 → 2 distinct reporters.
        def reports = [
            new com.sboxmarket.model.ListingReport(listingId: 100L, reporterUserId: 42L, reason: 'Suspicious'),
            new com.sboxmarket.model.ListingReport(listingId: 100L, reporterUserId: 42L, reason: 'Other'),
            new com.sboxmarket.model.ListingReport(listingId: 100L, reporterUserId: 99L, reason: 'Suspicious'),
        ]
        listingReportRepository.findByListingIdOrderByCreatedAtDesc(100L) >> reports

        when:
        def res = service.forceCancelListing(1L, 100L, 'Policy violation')

        then:
        listing.status == 'CANCELLED'
        // Seller gets the original LISTING_REMOVED notification
        1 * notificationService.push(999L, 'LISTING_REMOVED', _, _, _, _)
        // Each distinct reporter gets exactly one REPORT_ACTIONED notification
        1 * notificationService.push(42L, 'REPORT_ACTIONED', _, _, _, _)
        1 * notificationService.push(99L, 'REPORT_ACTIONED', _, _, _, _)
    }

    def "forceCancelListing refunds the buyer when the listing has an open escrow trade (batch 309 bug fix)"() {
        // Before batch 309, admin force-cancel of a listing with an
        // in-escrow trade orphaned the trade and trapped the buyer's
        // funds — classic escrow-leak bug. Now tradeService.cancel
        // runs FIRST so the buyer is made whole.
        given:
        def item = new com.sboxmarket.model.Item(id: 7L, name: 'Wizard Hat')
        def listing = new Listing(id: 100L, sellerUserId: 999L, status: 'ACTIVE', item: item, reportCount: 0)
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        def openTrade = new com.sboxmarket.model.Trade(
            id: 500L, listingId: 100L, buyerUserId: 55L, sellerUserId: 999L,
            state: 'PENDING_BUYER_CONFIRM', price: new BigDecimal("50")
        )
        tradeRepository.findByListingId(100L) >> openTrade
        listingReportRepository.findByListingIdOrderByCreatedAtDesc(100L) >> []

        when:
        service.forceCancelListing(1L, 100L, 'Policy violation')

        then:
        // Trade-cancel invoked with the admin user id — adminAuthorization
        // path on TradeService.cancel will pass for a real admin.
        1 * tradeService.cancel(1L, 500L, { String r -> r?.contains('Staff removed listing') })
        listing.status == 'CANCELLED'
    }

    def "forceCancelListing skips the trade-refund branch when no open trade exists"() {
        given:
        def item = new com.sboxmarket.model.Item(id: 7L, name: 'Wizard Hat')
        def listing = new Listing(id: 100L, sellerUserId: 999L, status: 'ACTIVE', item: item, reportCount: 0)
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        tradeRepository.findByListingId(100L) >> null
        listingReportRepository.findByListingIdOrderByCreatedAtDesc(100L) >> []

        when:
        service.forceCancelListing(1L, 100L, 'Policy violation')

        then:
        0 * tradeService.cancel(_, _, _)
        listing.status == 'CANCELLED'
    }

    def "forceCancelListing swallows a failing trade-cancel so the listing still flips to CANCELLED"() {
        given:
        def item = new com.sboxmarket.model.Item(id: 7L, name: 'Wizard Hat')
        def listing = new Listing(id: 100L, sellerUserId: 999L, status: 'ACTIVE', item: item, reportCount: 0)
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        def openTrade = new com.sboxmarket.model.Trade(
            id: 500L, listingId: 100L, state: 'PENDING_SELLER_ACCEPT'
        )
        tradeRepository.findByListingId(100L) >> openTrade
        tradeService.cancel(_, _, _) >> { throw new RuntimeException('trade-cancel boom') }
        listingReportRepository.findByListingIdOrderByCreatedAtDesc(100L) >> []

        when:
        service.forceCancelListing(1L, 100L, 'Policy violation')

        then:
        // Admin can still pull the listing off-market even if the
        // trade-refund side-channel fails; staff have manual
        // compensation flows.
        listing.status == 'CANCELLED'
        noExceptionThrown()
    }

    def "forceCancelListing keeps working when the report-notification push throws"() {
        given:
        def item = new com.sboxmarket.model.Item(id: 7L, name: 'Wizard Hat')
        def listing = new Listing(id: 100L, sellerUserId: 999L, status: 'ACTIVE', item: item, reportCount: 1)
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        listingReportRepository.findByListingIdOrderByCreatedAtDesc(100L) >> [
            new com.sboxmarket.model.ListingReport(listingId: 100L, reporterUserId: 42L, reason: 'X')
        ]
        notificationService.push(42L, 'REPORT_ACTIONED', _, _, _, _) >> { throw new RuntimeException('downstream boom') }

        when:
        def res = service.forceCancelListing(1L, 100L, 'Policy violation')

        then:
        // Still succeeds despite the notification failure
        listing.status == 'CANCELLED'
        noExceptionThrown()
    }

    def "dismissListingReports clears the aggregate counter and notifies reporters"() {
        given:
        def item = new com.sboxmarket.model.Item(id: 7L, name: 'Wizard Hat')
        def listing = new Listing(id: 100L, sellerUserId: 999L, status: 'ACTIVE', item: item,
            reportCount: 4, lastReportedAt: 12345L)
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        listingReportRepository.findByListingIdOrderByCreatedAtDesc(100L) >> [
            new com.sboxmarket.model.ListingReport(listingId: 100L, reporterUserId: 42L, reason: 'Misread'),
            new com.sboxmarket.model.ListingReport(listingId: 100L, reporterUserId: 42L, reason: 'Misread'),
            new com.sboxmarket.model.ListingReport(listingId: 100L, reporterUserId: 99L, reason: 'Misread'),
        ]

        when:
        def res = service.dismissListingReports(1L, 100L, 'No policy violation')

        then:
        listing.reportCount == 0
        listing.lastReportedAt == null
        res.dismissed == 3
        res.distinctReporters == 2
        1 * notificationService.push(42L, 'REPORT_REVIEWED', _, _, _, _)
        1 * notificationService.push(99L, 'REPORT_REVIEWED', _, _, _, _)
    }

    def "dismissListingReports is a no-op on the counter when there are zero detail rows"() {
        given:
        def item = new com.sboxmarket.model.Item(id: 7L, name: 'Wizard Hat')
        def listing = new Listing(id: 100L, sellerUserId: 999L, status: 'ACTIVE', item: item,
            reportCount: 5, lastReportedAt: 12345L)
        listingRepository.findById(100L) >> Optional.of(listing)
        listingRepository.save(_) >> { args -> args[0] }
        listingReportRepository.findByListingIdOrderByCreatedAtDesc(100L) >> []

        when:
        def res = service.dismissListingReports(1L, 100L, 'phantom counter')

        then:
        listing.reportCount == 0
        res.dismissed == 0
        res.distinctReporters == 0
        0 * notificationService.push(_, 'REPORT_REVIEWED', _, _, _, _)
    }

    // ── listAllTickets search (batch 576) ───────────────────────────

    def "listAllTickets without a search term hits findForAdmin"() {
        given:
        supportTicketRepository.findForAdmin('WAITING_STAFF') >> [
            new com.sboxmarket.model.SupportTicket(id: 1L, userId: 10L, subject: 'stuck deposit', status: 'WAITING_STAFF', updatedAt: 1L)
        ]
        steamUserRepository.findAllById(_) >> []

        when:
        def rows = service.listAllTickets('WAITING_STAFF', null)

        then:
        rows.size() == 1
        0 * supportTicketRepository.searchForAdmin(*_)
    }

    def "listAllTickets with a search term hits searchForAdmin"() {
        given:
        supportTicketRepository.searchForAdmin('', 'deposit') >> [
            new com.sboxmarket.model.SupportTicket(id: 1L, userId: 10L, subject: 'stuck deposit', status: 'WAITING_STAFF', updatedAt: 1L)
        ]
        steamUserRepository.findAllById(_) >> []

        when:
        def rows = service.listAllTickets(null, 'deposit')

        then:
        rows.size() == 1
        0 * supportTicketRepository.findForAdmin(_)
    }

    def "listAllTickets truncates overly-long search strings + strips null bytes"() {
        given:
        def longQuery = 'x' * 200
        String captured = null
        supportTicketRepository.searchForAdmin(_, _) >> { args ->
            captured = args[1] as String
            []
        }

        when:
        service.listAllTickets('', longQuery + '\u0000bad')

        then:
        captured != null
        captured.length() == 100
        !captured.contains('\u0000')
    }

    // ── listTransactionsFor (batch 568) ─────────────────────────────

    def "listTransactionsFor requires an admin"() {
        given:
        1 * adminAuthorization.requireAdmin(99L) >> { throw new ForbiddenException('not admin') }

        when:
        service.listTransactionsFor(99L, 10L)

        then:
        thrown(ForbiddenException)
    }

    def "listTransactionsFor 404s for an unknown user id"() {
        given:
        steamUserRepository.findById(999L) >> Optional.empty()

        when:
        service.listTransactionsFor(1L, 999L)

        then:
        thrown(NotFoundException)
    }

    def "listTransactionsFor returns [] when the user has no wallet"() {
        given:
        def user = new SteamUser(id: 10L, steamId64: '123', displayName: 'Alice')
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_123') >> null

        when:
        def rows = service.listTransactionsFor(1L, 10L)

        then:
        rows == []
        0 * transactionRepository.findByWalletIdOrderByCreatedAtDesc(*_)
    }

    def "listTransactionsFor projects 100 most-recent transactions for fraud triage"() {
        given:
        def user   = new SteamUser(id: 10L, steamId64: '123', displayName: 'Alice')
        def wallet = new Wallet(id: 500L, username: 'steam_123', balance: new BigDecimal("50"))
        def tx1 = new Transaction(id: 1L, walletId: 500L, type: 'DEPOSIT',
            status: 'COMPLETED', amount: new BigDecimal("25.00"),
            currency: 'USD', description: 'Stripe session', stripeReference: 'cs_123',
            createdAt: 1000L, updatedAt: 1000L)
        def tx2 = new Transaction(id: 2L, walletId: 500L, type: 'PURCHASE',
            status: 'COMPLETED', amount: new BigDecimal("5.00"),
            currency: 'USD', description: 'Bought a hat',
            createdAt: 2000L, updatedAt: 2000L)
        steamUserRepository.findById(10L) >> Optional.of(user)
        walletRepository.findByUsername('steam_123') >> wallet
        transactionRepository.findByWalletIdOrderByCreatedAtDesc(500L,
            _ as org.springframework.data.domain.Pageable) >> [tx1, tx2]

        when:
        def rows = service.listTransactionsFor(1L, 10L)

        then:
        rows.size() == 2
        rows[0].id == 1L
        rows[0].type == 'DEPOSIT'
        rows[0].stripeReference == 'cs_123'
        rows[1].id == 2L
        rows[1].type == 'PURCHASE'
    }

    // ── freezeWallet / unfreezeWallet security email (batch 584) ────

    def "freezeWallet sends a security-alert email to the user's verified address"() {
        given:
        def target = new SteamUser(
            id: 10L, steamId64: '111', displayName: 'Alice',
            email: 'alice@example.com', emailVerified: true)
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: new BigDecimal("50.00"), frozen: false)
        steamUserRepository.findById(10L) >> Optional.of(target)
        walletRepository.findByUsername('steam_111') >> wallet
        walletRepository.save(_) >> { args -> args[0] }
        textSanitizer.medium(_) >> 'Suspicious chargeback pattern'

        when:
        service.freezeWallet(1L, 10L, 'Suspicious chargeback pattern')

        then:
        1 * emailService.sendWalletFrozen('alice@example.com', 'Alice', 'Suspicious chargeback pattern')
    }

    def "freezeWallet skips the email when the target has no verified email"() {
        given:
        def target = new SteamUser(
            id: 10L, steamId64: '111', displayName: 'Alice',
            email: 'alice@example.com', emailVerified: false)
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: new BigDecimal("50.00"), frozen: false)
        steamUserRepository.findById(10L) >> Optional.of(target)
        walletRepository.findByUsername('steam_111') >> wallet
        walletRepository.save(_) >> { args -> args[0] }

        when:
        service.freezeWallet(1L, 10L, 'reason')

        then:
        0 * emailService.sendWalletFrozen(*_)
    }

    def "unfreezeWallet sends the good-news email to the verified address"() {
        given:
        def target = new SteamUser(
            id: 10L, steamId64: '111', displayName: 'Alice',
            email: 'alice@example.com', emailVerified: true)
        def wallet = new Wallet(id: 500L, username: 'steam_111', balance: new BigDecimal("50.00"), frozen: true)
        steamUserRepository.findById(10L) >> Optional.of(target)
        walletRepository.findByUsername('steam_111') >> wallet
        walletRepository.save(_) >> { args -> args[0] }

        when:
        service.unfreezeWallet(1L, 10L)

        then:
        1 * emailService.sendWalletUnfrozen('alice@example.com', 'Alice')
    }

    // ── sendDirectMessage (batch 580) ───────────────────────────────

    def "sendDirectMessage requires an admin"() {
        given:
        1 * adminAuthorization.requireAdmin(99L) >> { throw new ForbiddenException('not admin') }

        when:
        service.sendDirectMessage(99L, 10L, 'hi', null, null)

        then:
        thrown(ForbiddenException)
    }

    def "sendDirectMessage rejects a banned target"() {
        given:
        def target = new SteamUser(id: 10L, steamId64: '111', banned: true)
        steamUserRepository.findById(10L) >> Optional.of(target)

        when:
        service.sendDirectMessage(1L, 10L, 'test', null, null)

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'USER_BANNED'
    }

    def "sendDirectMessage 404s for unknown target"() {
        given:
        steamUserRepository.findById(999L) >> Optional.empty()

        when:
        service.sendDirectMessage(1L, 999L, 'test', null, null)

        then:
        thrown(NotFoundException)
    }

    def "sendDirectMessage rejects empty title"() {
        given:
        def target = new SteamUser(id: 10L, steamId64: '111', banned: false)
        steamUserRepository.findById(10L) >> Optional.of(target)

        when:
        service.sendDirectMessage(1L, 10L, '   ', 'body', null)

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'EMPTY_TITLE'
    }

    def "sendDirectMessage pushes ADMIN_MESSAGE to the target user"() {
        given:
        def target = new SteamUser(id: 10L, steamId64: '111', banned: false)
        steamUserRepository.findById(10L) >> Optional.of(target)

        when:
        def res = service.sendDirectMessage(1L, 10L, 'Verify your email', 'Withdrawals require a verified email.', '/profile')

        then:
        1 * notificationService.push(10L, 'ADMIN_MESSAGE', 'Verify your email',
            'Withdrawals require a verified email.', null, '/profile')
        res.sent == true
        res.to == 10L
    }

    def "sendDirectMessage preserves the chained cause and does NOT leak the underlying error text"() {
        // Regression: the catch-and-rethrow path previously dropped the
        // cause AND interpolated `e.message` into the BadRequestException
        // — JPA / JDBC exceptions carry SQL fragments, constraint names,
        // and table names that should never leave the server. After the
        // fix the cause chain is preserved (so GlobalExceptionHandler can
        // log root cause) and the user-facing message is a fixed string.
        given:
        def target = new SteamUser(id: 10L, steamId64: '111', banned: false)
        steamUserRepository.findById(10L) >> Optional.of(target)
        def root = new RuntimeException(
            'could not execute statement; constraint [notification_pkey]; SQL [insert into notification ...]')
        notificationService.push(_, _, _, _, _, _) >> { throw root }

        when:
        service.sendDirectMessage(1L, 10L, 'Hi', 'Body', '/profile')

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'SEND_FAILED'
        // Message is the fixed safe copy — no SQL / constraint internals
        ex.message == 'Failed to deliver direct message'
        !ex.message.contains('SQL')
        !ex.message.contains('notification_pkey')
        // Chained cause survives so ops can trace the root from logs
        ex.cause.is(root)
    }

    // ── broadcastNotification (batch 566) ───────────────────────────

    def "broadcastNotification requires an admin"() {
        given:
        1 * adminAuthorization.requireAdmin(99L) >> { throw new ForbiddenException('not admin') }

        when:
        service.broadcastNotification(99L, 'hi', null, null)

        then:
        thrown(ForbiddenException)
    }

    def "broadcastNotification rejects an empty title with EMPTY_TITLE"() {
        when:
        service.broadcastNotification(1L, '   ', 'body', null)

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'EMPTY_TITLE'
    }

    def "broadcastNotification rejects a path that doesn't start with /"() {
        when:
        service.broadcastNotification(1L, 'Launch', 'body', 'help')  // missing leading slash

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'INVALID_PATH'
    }

    def "broadcastNotification pushes ADMIN_BROADCAST to every active user in batches"() {
        given:
        // Three users in the first batch, empty second batch signals end.
        steamUserRepository.findActiveUserIds(_ as org.springframework.data.domain.Pageable) >>> [
            [10L, 11L, 12L],
            []
        ]

        when:
        def res = service.broadcastNotification(1L, 'Feature launch', 'Check out the new database page', '/db')

        then:
        1 * notificationService.push(10L, 'ADMIN_BROADCAST', 'Feature launch', 'Check out the new database page', null, '/db')
        1 * notificationService.push(11L, 'ADMIN_BROADCAST', 'Feature launch', 'Check out the new database page', null, '/db')
        1 * notificationService.push(12L, 'ADMIN_BROADCAST', 'Feature launch', 'Check out the new database page', null, '/db')
        res.sent == 3
        res.batches == 1
    }

    def "listUsers with search delegates to searchByNameOrSteamId (batch 765)"() {
        when:
        service.listUsers('alice', null, null)

        then:
        1 * steamUserRepository.searchByNameOrSteamId('alice', _) >> []
        0 * steamUserRepository.listByRoleAndBanned(_, _, _)
        0 * steamUserRepository.findAll(_ as org.springframework.data.domain.Pageable)
    }

    def "listUsers with no filters hits findAll (fast path) (batch 765)"() {
        given:
        // Use a concrete PageImpl so the service can read `.content`
        // without needing a fancy mock.
        def emptyPage = new org.springframework.data.domain.PageImpl<SteamUser>([])

        when:
        service.listUsers(null, null, null)

        then:
        1 * steamUserRepository.findAll(_ as org.springframework.data.domain.Pageable) >> emptyPage
        0 * steamUserRepository.listByRoleAndBanned(_, _, _)
        0 * steamUserRepository.searchByNameOrSteamId(_, _)
    }

    def "listUsers with role filter calls listByRoleAndBanned (batch 765)"() {
        when:
        service.listUsers(null, 'ADMIN', false)

        then:
        1 * steamUserRepository.listByRoleAndBanned('ADMIN', false, _) >> []
        0 * steamUserRepository.findAll(_ as org.springframework.data.domain.Pageable)
    }

    def "listUsers banned-only passes banned=true with role=ANY (batch 765)"() {
        when:
        service.listUsers(null, null, true)

        then:
        1 * steamUserRepository.listByRoleAndBanned('ANY', true, _) >> []
    }

    def "listUsers role filter uppercases caller input (batch 765)"() {
        when:
        service.listUsers(null, 'csr', false)

        then:
        // Defensive against frontend typo / case drift — service
        // normalises to canonical uppercase before hitting the repo.
        1 * steamUserRepository.listByRoleAndBanned('CSR', false, _) >> []
    }

    def "broadcastNotification keeps pushing when one per-user call throws (batch 566)"() {
        given:
        steamUserRepository.findActiveUserIds(_ as org.springframework.data.domain.Pageable) >>> [[10L, 11L], []]
        notificationService.push(10L, 'ADMIN_BROADCAST', _, _, _, _) >> { throw new RuntimeException('push down') }

        when:
        def res = service.broadcastNotification(1L, 'Launch', null, null)

        then:
        1 * notificationService.push(11L, 'ADMIN_BROADCAST', _, _, _, _)
        // Failing push doesn't count toward `sent`, the rest still do.
        res.sent == 1
    }

    // ── clearDisputeHold (batch 467) ────────────────────────────────

    def "clearDisputeHold flips a DISPUTED deposit back to COMPLETED"() {
        given:
        def tx = new Transaction(id: 7L, walletId: 500L, type: 'DEPOSIT',
            status: 'DISPUTED', amount: new BigDecimal('25'))
        transactionRepository.findById(7L) >> Optional.of(tx)
        transactionRepository.save(_) >> { args -> args[0] }
        // No wallet owner resolvable — keeps the test focused on the
        // status flip; the notify/email side-effects no-op on null owner.
        walletRepository.findById(500L) >> Optional.empty()
        transactionRepository.countActiveDisputedDeposits(500L) >> 0L

        when:
        def res = service.clearDisputeHold(1L, 7L, 'won the chargeback')

        then:
        1 * adminAuthorization.requireAdmin(1L)
        tx.status == 'COMPLETED'
        res.status == 'COMPLETED'
        res.transactionId == 7L
    }

    def "clearDisputeHold refuses a transaction that is not DISPUTED"() {
        given:
        transactionRepository.findById(7L) >> Optional.of(
            new Transaction(id: 7L, walletId: 500L, type: 'DEPOSIT', status: 'COMPLETED'))

        when:
        service.clearDisputeHold(1L, 7L, 'reason')

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'NOT_DISPUTED'
        0 * transactionRepository.save(_)
    }

    def "clearDisputeHold 404s for an unknown transaction"() {
        given:
        transactionRepository.findById(999L) >> Optional.empty()

        when:
        service.clearDisputeHold(1L, 999L, 'reason')

        then:
        thrown(NotFoundException)
    }

    def "clearDisputeHold records the affected wallet owner as the audit subject"() {
        // Regression guard: the DISPUTE_CLEARED audit row used to pass a
        // hard-coded null subject, so the staff action was invisible to
        // the audit-by-subject filter + shipped a blank subject column
        // in the CSV. The subject must now resolve to the wallet owner.
        given:
        def tx = new Transaction(id: 7L, walletId: 500L, type: 'DEPOSIT',
            status: 'DISPUTED', amount: new BigDecimal('40'))
        transactionRepository.findById(7L) >> Optional.of(tx)
        transactionRepository.save(_) >> { args -> args[0] }
        def wallet = new Wallet(id: 500L, username: 'steam_888')
        walletRepository.findById(500L) >> Optional.of(wallet)
        steamUserRepository.findBySteamId64('888') >> new SteamUser(id: 88L, steamId64: '888')
        transactionRepository.countActiveDisputedDeposits(500L) >> 1L

        when:
        service.clearDisputeHold(1L, 7L, 'false positive')

        then:
        1 * auditService.log('DISPUTE_CLEARED', 1L, 88L, 7L, _ as String)
    }

    def "clearDisputeHold notifies the owner only when no disputes remain on the wallet"() {
        given:
        def tx = new Transaction(id: 7L, walletId: 500L, type: 'DEPOSIT',
            status: 'DISPUTED', amount: new BigDecimal('40'))
        transactionRepository.findById(7L) >> Optional.of(tx)
        transactionRepository.save(_) >> { args -> args[0] }
        def wallet = new Wallet(id: 500L, username: 'steam_888')
        walletRepository.findById(500L) >> Optional.of(wallet)
        steamUserRepository.findBySteamId64('888') >> new SteamUser(id: 88L, steamId64: '888')
        // One dispute still open after this clear → no "unblocked" ping.
        transactionRepository.countActiveDisputedDeposits(500L) >> 2L

        when:
        service.clearDisputeHold(1L, 7L, 'partial')

        then:
        0 * notificationService.push(_, 'DISPUTE_CLEARED', _, _, _, _)
    }

    def "clearDisputeHold survives an audit-log failure"() {
        given:
        def tx = new Transaction(id: 7L, walletId: 500L, type: 'DEPOSIT',
            status: 'DISPUTED', amount: new BigDecimal('40'))
        transactionRepository.findById(7L) >> Optional.of(tx)
        transactionRepository.save(_) >> { args -> args[0] }
        walletRepository.findById(500L) >> Optional.empty()
        transactionRepository.countActiveDisputedDeposits(500L) >> 0L
        auditService.log('DISPUTE_CLEARED', *_) >> { throw new RuntimeException('audit table locked') }

        when:
        def res = service.clearDisputeHold(1L, 7L, 'reason')

        then:
        // The status flip is the source of truth — a broken audit write
        // must not roll it back.
        res.status == 'COMPLETED'
        noExceptionThrown()
    }

    // ── approveWithdrawal: dispute-hold gate (batch 468) ─────────────

    def "approveWithdrawal refuses while the wallet has an unresolved deposit dispute"() {
        given:
        def tx = new Transaction(id: 1L, walletId: 500L, type: 'WITHDRAW',
            status: 'PENDING', amount: new BigDecimal('25'))
        transactionRepository.findById(1L) >> Optional.of(tx)
        transactionRepository.countActiveDisputedDeposits(500L) >> 1L

        when:
        service.approveWithdrawal(1L, 1L, 'ref')

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'DISPUTE_HOLD'
        // Must NOT have flipped the withdrawal — the gate fires first.
        tx.status == 'PENDING'
        0 * transactionRepository.save(_)
    }

    def "approveWithdrawal proceeds when no deposit dispute is open"() {
        given:
        def tx = new Transaction(id: 1L, walletId: 500L, type: 'WITHDRAW',
            status: 'PENDING', amount: new BigDecimal('25'))
        transactionRepository.findById(1L) >> Optional.of(tx)
        transactionRepository.save(_) >> { args -> args[0] }
        transactionRepository.countActiveDisputedDeposits(500L) >> 0L
        transactionRepository.claimApproveWithdrawal(1L, _, _) >> 1
        walletRepository.findById(500L) >> Optional.of(new Wallet(id: 500L, username: 'steam_111'))
        steamUserRepository.findBySteamId64('111') >> null

        when:
        def res = service.approveWithdrawal(1L, 1L, 'PAYOUT-X')

        then:
        res.status == 'COMPLETED'
    }

    // ── approveWithdrawal: wallet-freeze gate ───────────────────────
    //
    // freezeWallet's contract is "all money-in / money-out paths
    // refuse" — used for fraud holds and user-requested security
    // lockouts. The user-facing /api/wallet/withdraw endpoint already
    // enforces this at the controller layer. The admin approve path
    // didn't: staff would freeze a suspicious wallet, then another
    // admin (or the same admin on a stale queue) could click Approve
    // on the still-PENDING row queued BEFORE the freeze and release
    // the payout — silently bypassing the freeze. The withdraw queue
    // already surfaces `walletFrozen` to staff (listWithdrawals); this
    // gate makes the action match the visual signal. The correct
    // disposition for a frozen wallet's pending withdrawal is reject
    // (refunds the user) or unfreeze first.
    def "approveWithdrawal refuses when the wallet is frozen — staff lockout must not be bypassed via the approve path"() {
        given: 'a still-PENDING withdrawal on a now-frozen wallet'
        def tx = new Transaction(id: 1L, walletId: 500L, type: 'WITHDRAW',
            status: 'PENDING', amount: new BigDecimal('25.00'))
        def wallet = new Wallet(id: 500L, username: 'steam_111',
            balance: new BigDecimal('75.00'),
            frozen: true,
            frozenReason: 'fraud investigation')
        transactionRepository.findById(1L) >> Optional.of(tx)
        walletRepository.findById(500L) >> Optional.of(wallet)
        // The dispute-hold stub stays at zero so any throw here is
        // unambiguously from the freeze gate, not the dispute gate.
        transactionRepository.countActiveDisputedDeposits(500L) >> 0L

        when:
        service.approveWithdrawal(1L, 1L, 'PAYOUT-REF-Z')

        then: 'WALLET_FROZEN code surfaces with the staff reason so the operator sees why'
        def ex = thrown(BadRequestException)
        ex.code == 'WALLET_FROZEN'
        ex.message.contains('fraud investigation')

        and: 'the withdrawal is NOT flipped — no save, no payout reference set, no audit'
        tx.status == 'PENDING'
        tx.stripeReference == null
        0 * transactionRepository.save(_)
        0 * auditService.log(_, _, _, _, _)
    }

    def "approveWithdrawal still proceeds on a non-frozen wallet (gate is freeze-specific, not a blanket block)"() {
        given: 'identical fixtures to the frozen test but frozen=false'
        def tx = new Transaction(id: 1L, walletId: 500L, type: 'WITHDRAW',
            status: 'PENDING', amount: new BigDecimal('25.00'))
        def wallet = new Wallet(id: 500L, username: 'steam_111',
            balance: new BigDecimal('75.00'),
            frozen: false)
        transactionRepository.findById(1L) >> Optional.of(tx)
        walletRepository.findById(500L) >> Optional.of(wallet)
        transactionRepository.save(_) >> { args -> args[0] }
        transactionRepository.countActiveDisputedDeposits(500L) >> 0L
        transactionRepository.claimApproveWithdrawal(1L, _, _) >> 1
        steamUserRepository.findBySteamId64('111') >> null

        when:
        def res = service.approveWithdrawal(1L, 1L, 'PAYOUT-REF-OK')

        then: 'approval lands cleanly — the freeze gate did not over-block the happy path'
        noExceptionThrown()
        res.status == 'COMPLETED'
    }

    def "approveWithdrawal freeze gate tolerates a missing wallet row — falls through to the rest of the flow"() {
        // The existing approveWithdrawal happy-path test runs without an
        // explicit wallet stub (the wallet lookup returns null on the
        // Mock). The freeze check must therefore treat a null wallet as
        // "not frozen" instead of NPEing — otherwise a deleted-wallet
        // edge case would convert into a 500 mid-approval. Pin the
        // null-safe behaviour so the lookup-vs-throw shape stays correct.
        given:
        def tx = new Transaction(id: 1L, walletId: 500L, type: 'WITHDRAW',
            status: 'PENDING', amount: new BigDecimal('25'))
        transactionRepository.findById(1L) >> Optional.of(tx)
        // Wallet absent → freeze check defaults to "not frozen".
        walletRepository.findById(500L) >> Optional.empty()
        transactionRepository.save(_) >> { args -> args[0] }
        transactionRepository.countActiveDisputedDeposits(500L) >> 0L
        transactionRepository.claimApproveWithdrawal(1L, _, _) >> 1

        when:
        def res = service.approveWithdrawal(1L, 1L, 'PAYOUT-REF-NOWALLET')

        then:
        noExceptionThrown()
        res.status == 'COMPLETED'
    }

    // ── grantAdmin / grantCsr: banned + self guards ─────────────────

    def "grantAdmin refuses to promote a banned account"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222', role: 'USER', banned: true)
        steamUserRepository.findById(20L) >> Optional.of(target)

        when:
        service.grantAdmin(1L, 20L)

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'USER_BANNED'
        target.role == 'USER'   // unchanged
    }

    def "grantAdmin forbids self-grant"() {
        when:
        service.grantAdmin(1L, 1L)

        then:
        1 * adminAuthorization.requireAdmin(1L)
        def ex = thrown(BadRequestException)
        ex.code == 'CANT_GRANT_SELF'
    }

    def "grantCsr forbids self-grant"() {
        when:
        service.grantCsr(1L, 1L)

        then:
        1 * adminAuthorization.requireAdmin(1L)
        def ex = thrown(BadRequestException)
        ex.code == 'CANT_GRANT_SELF'
    }

    // ── forceReleaseTrade / forceCancelTrade: audit trail ───────────

    def "forceReleaseTrade refuses an already-settled trade"() {
        given:
        tradeRepository.findById(9L) >> Optional.of(
            new com.sboxmarket.model.Trade(id: 9L, state: 'VERIFIED'))

        when:
        service.forceReleaseTrade(1L, 9L, 'reason')

        then:
        def ex = thrown(BadRequestException)
        ex.code == 'ALREADY_SETTLED'
        0 * tradeService.adminRelease(*_)
    }

    def "forceReleaseTrade delegates to adminRelease and does NOT write a duplicate TRADE_FORCE_RELEASED audit row"() {
        // Regression guard: TradeService.adminRelease writes the
        // TRADE_FORCE_RELEASED audit row itself, so AdminService.forceReleaseTrade
        // must NOT write a second one. Before the fix this method logged
        // its own row on top of the one TradeService already wrote,
        // doubling every force-release in the per-actor audit filter.
        given:
        tradeRepository.findById(9L) >> Optional.of(
            new com.sboxmarket.model.Trade(id: 9L, state: 'PENDING_BUYER_CONFIRM'))
        tradeService.adminRelease(1L, 9L, 'buyer ghosted') >> new com.sboxmarket.model.Trade(
            id: 9L, state: 'VERIFIED', sellerUserId: 77L)

        when:
        def res = service.forceReleaseTrade(1L, 9L, 'buyer ghosted')

        then:
        res.state == 'VERIFIED'
        0 * auditService.log(com.sboxmarket.service.AuditService.TRADE_FORCE_RELEASED, *_)
    }

    def "forceCancelTrade delegates to cancel and writes a TRADE_FORCE_CANCELLED audit row subjected to the SELLER"() {
        // Regression guard: the audit subject must be the seller (the party
        // losing the goods / typically the misbehaving side a force-cancel
        // is intervening against), not the buyer (the victim being refunded).
        // Mirrors TRADE_FORCE_RELEASED in TradeService which also subjects the
        // seller. The pre-fix behaviour subjected the buyer, burying real
        // seller-misconduct trails on the wrong account's audit filter.
        given:
        tradeService.cancel(1L, 9L, _ as String) >> new com.sboxmarket.model.Trade(
            id: 9L, state: 'CANCELLED', buyerUserId: 66L, sellerUserId: 77L)

        when:
        def res = service.forceCancelTrade(1L, 9L, 'stuck escrow')

        then:
        res.state == 'CANCELLED'
        1 * auditService.log(com.sboxmarket.service.AuditService.TRADE_FORCE_CANCELLED,
            1L, 77L, 9L, _ as String)
        0 * auditService.log(com.sboxmarket.service.AuditService.TRADE_FORCE_CANCELLED,
            1L, 66L, 9L, _ as String)
    }

    // ── Support ticket actions: audit trail + double-resolve guard ───

    def "staffReply writes a TICKET_REPLIED audit row (actor=admin, subject=ticket owner)"() {
        given:
        textSanitizer.body(_) >> { String s -> s }
        textSanitizer.cleanShort(_) >> { String s -> s }
        steamUserRepository.findById(1L) >> Optional.of(new SteamUser(id: 1L, role: 'ADMIN', displayName: 'Root'))
        def ticket = new com.sboxmarket.model.SupportTicket(id: 7L, userId: 30L, status: 'WAITING_STAFF', subject: 'help')
        supportTicketRepository.findById(7L) >> Optional.of(ticket)
        supportTicketRepository.save(_) >> { args -> args[0] }
        supportMessageRepository.save(_) >> { args -> def m = args[0]; m.id = 1L; m }

        when:
        service.staffReply(1L, 7L, 'sorted for you')

        then:
        1 * adminAuthorization.requireAdmin(1L)
        ticket.status == 'WAITING_USER'
        1 * auditService.log('TICKET_REPLIED', 1L, 30L, 7L, _ as String)
    }

    def "closeTicket flips status to RESOLVED and writes a TICKET_CLOSED audit row"() {
        given:
        steamUserRepository.findById(1L) >> Optional.of(new SteamUser(id: 1L, role: 'ADMIN'))
        def ticket = new com.sboxmarket.model.SupportTicket(id: 7L, userId: 30L, status: 'WAITING_STAFF')
        supportTicketRepository.findById(7L) >> Optional.of(ticket)
        supportTicketRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.closeTicket(1L, 7L)

        then:
        1 * adminAuthorization.requireAdmin(1L)
        result.status == 'RESOLVED'
        1 * auditService.log('TICKET_CLOSED', 1L, 30L, 7L, _ as String)
    }

    def "closeTicket rejects an already-RESOLVED ticket (no silent re-resolve)"() {
        given:
        def ticket = new com.sboxmarket.model.SupportTicket(id: 7L, userId: 30L, status: 'RESOLVED', updatedAt: 1000L)
        supportTicketRepository.findById(7L) >> Optional.of(ticket)

        when:
        service.closeTicket(1L, 7L)

        then:
        1 * adminAuthorization.requireAdmin(1L)
        def e = thrown(BadRequestException)
        e.code == 'ALREADY_RESOLVED'

        and: 'the row is neither re-saved nor re-audited'
        ticket.updatedAt == 1000L
        0 * supportTicketRepository.save(_)
        0 * auditService.log(*_)
    }

    // ── userSummary — money/dispute/wallet snapshot ──────────────────
    // Drives the admin user-detail drawer. Mixes wallet balance, active
    // chargebacks, lifetime chargebacks, pending withdraws, openTrades,
    // ship-time signal, and (when wired) IP fan-out + active API keys.

    def "userSummary 404s for an unknown target user"() {
        given:
        steamUserRepository.findById(999L) >> Optional.empty()

        when:
        service.userSummary(999L)

        then:
        thrown(NotFoundException)
        0 * walletRepository.findByUsername(_)
    }

    def "userSummary returns a complete snapshot for a user with a wallet + open trades + disputes"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222',
            displayName: 'Bob', role: 'USER', banned: false,
            emailVerified: true, email: 'bob@x.test',
            totpSecret: 'JBSWY3DPEHPK3PXP', tradeUrl: 'https://steam/tradeoffer',
            createdAt: 1L)
        def wallet = new Wallet(id: 500L, username: 'steam_222',
            balance: new BigDecimal("42.50"), currency: 'USD',
            frozen: false, frozenReason: null, frozenAt: null)
        steamUserRepository.findById(20L) >> Optional.of(target)
        walletRepository.findByUsername('steam_222') >> wallet
        // Wallet-level signals.
        transactionRepository.countActiveDisputedDeposits(500L) >> 1L
        // 500-row transaction scan — mixed DEPOSIT/SALE/WITHDRAW history.
        // The service inspects: type='DEPOSIT' AND (DISPUTED or
        // DISPUTE_CLEARED in description) → lifetimeDisputes; type='WITHDRAW' AND
        // status='PENDING' → pendingWithdraw sum.
        transactionRepository.findByWalletIdOrderByCreatedAtDesc(500L, _) >> [
            new Transaction(id: 1L, walletId: 500L, type: 'DEPOSIT', status: 'DISPUTED',
                amount: new BigDecimal("100")),
            new Transaction(id: 2L, walletId: 500L, type: 'DEPOSIT', status: 'COMPLETED',
                description: 'DISPUTE_CLEARED by admin', amount: new BigDecimal("25")),
            new Transaction(id: 3L, walletId: 500L, type: 'SALE', status: 'COMPLETED',
                amount: new BigDecimal("8")),
            new Transaction(id: 4L, walletId: 500L, type: 'WITHDRAW', status: 'PENDING',
                amount: new BigDecimal("30")),
            new Transaction(id: 5L, walletId: 500L, type: 'WITHDRAWAL', status: 'PENDING',
                amount: new BigDecimal("12.50"))
        ]
        tradeRepository.countOpenByParticipant(20L) >> 4L
        // tradeService is wired in the @Subject so its ship-time methods
        // need stubs even when we don't care about the value.
        tradeService.typicalShipMs(20L) >> 7_200_000L   // 2h median
        tradeService.typicalShipSampleCount(20L) >> 12

        when:
        def out = service.userSummary(20L)

        then:
        out.userId == 20L
        out.steamId64 == '222'
        out.displayName == 'Bob'
        out.role == 'USER'
        out.banned == false
        out.emailVerified == true
        // twoFactorEnabled is a derived boolean — must be true here but
        // must NEVER leak the raw totpSecret value into the map.
        out.twoFactorEnabled == true
        !out.toString().contains('JBSWY3DPEHPK3PXP')
        out.tradeUrl == 'https://steam/tradeoffer'
        out.walletBalance == new BigDecimal("42.50")
        out.walletFrozen == false
        out.activeDisputes == 1L
        // BOTH DISPUTED and DISPUTE_CLEARED rows count toward the
        // lifetime tally — the cleared marker is the audit signature
        // the service leaves on tx.description so the fraud signal
        // survives the clear.
        out.lifetimeDisputes == 2L
        // Both WITHDRAW and the legacy WITHDRAWAL spelling are summed.
        out.pendingWithdrawAmt == new BigDecimal("42.50")
        out.openTrades == 4L
        out.typicalShipMs == 7_200_000L
        out.typicalShipSamples == 12
        // Unwired optional repos default to zero — not null, not NPE.
        out.distinctSignInIps30d == 0L
        out.activeApiKeys == 0L
    }

    def "userSummary degrades to zero counters when the user has no wallet"() {
        given:
        def target = new SteamUser(id: 20L, steamId64: '222', displayName: 'No-wallet Bob')
        steamUserRepository.findById(20L) >> Optional.of(target)
        walletRepository.findByUsername('steam_222') >> null
        tradeRepository.countOpenByParticipant(20L) >> 0L

        when:
        def out = service.userSummary(20L)

        then:
        // No wallet → countActiveDisputedDeposits / findByWalletIdOrderByCreatedAtDesc
        // are never queried. Every wallet-dependent figure is the zero default.
        0 * transactionRepository.countActiveDisputedDeposits(_)
        0 * transactionRepository.findByWalletIdOrderByCreatedAtDesc(_, _)
        out.walletBalance == null
        out.walletFrozen == false
        out.activeDisputes == 0L
        out.lifetimeDisputes == 0L
        out.pendingWithdrawAmt == BigDecimal.ZERO
    }

    def "userSummary survives a failing transaction scan by reporting zero, not throwing"() {
        // The wallet-scan branch is wrapped in try/catch so a flaky
        // DB doesn't tank the admin drawer. Pin the swallow behaviour.
        given:
        def target = new SteamUser(id: 20L, steamId64: '222')
        def wallet = new Wallet(id: 500L, username: 'steam_222', balance: new BigDecimal("10"))
        steamUserRepository.findById(20L) >> Optional.of(target)
        walletRepository.findByUsername('steam_222') >> wallet
        transactionRepository.countActiveDisputedDeposits(500L) >> 1L
        transactionRepository.findByWalletIdOrderByCreatedAtDesc(500L, _) >> {
            throw new RuntimeException('db pool exhausted')
        }
        tradeRepository.countOpenByParticipant(20L) >> 0L

        when:
        def out = service.userSummary(20L)

        then:
        noExceptionThrown()
        // activeDisputes is a separate aggregate query (succeeds), so it
        // still surfaces. The scan-derived figures degrade to zero.
        out.activeDisputes == 1L
        out.lifetimeDisputes == 0L
        out.pendingWithdrawAmt == BigDecimal.ZERO
    }

    def "userSummary surfaces sign-in IP fan-out and active API key count when the optional repos are wired"() {
        given:
        // Optional collaborators — wire them in just for this spec so
        // the post-fix branches (batch 603 / 703) get exercised.
        def auditLogRepo = Mock(com.sboxmarket.repository.AuditLogRepository)
        def apiKeyRepo   = Mock(com.sboxmarket.repository.ApiKeyRepository)
        service.auditLogRepository = auditLogRepo
        service.apiKeyRepository   = apiKeyRepo
        def target = new SteamUser(id: 20L, steamId64: '222')
        steamUserRepository.findById(20L) >> Optional.of(target)
        walletRepository.findByUsername('steam_222') >> null
        tradeRepository.countOpenByParticipant(20L) >> 0L
        auditLogRepo.countDistinctSignInIpsSince(20L, _) >> 11L
        apiKeyRepo.countActiveByUser(20L) >> 7L

        when:
        def out = service.userSummary(20L)

        then:
        out.distinctSignInIps30d == 11L
        out.activeApiKeys == 7L
    }

    // ── getTicket (admin variant) ────────────────────────────────────

    def "getTicket (admin) requires the admin gate"() {
        given:
        adminAuthorization.requireAdmin(99L) >> { throw new ForbiddenException('not admin') }

        when:
        service.getTicket(99L, 5L)

        then:
        thrown(ForbiddenException)
        // Gate fires before the repo is touched — a non-admin can't even
        // confirm the ticket exists, no enumeration leak.
        0 * supportTicketRepository.findById(_)
    }

    def "getTicket (admin) returns ticket + thread for an admin"() {
        given:
        def ticket = new com.sboxmarket.model.SupportTicket(id: 5L, userId: 30L,
            status: 'WAITING_STAFF', subject: 'help')
        def messages = [
            new SupportMessage(id: 1L, ticketId: 5L, author: 'USER', body: 'first'),
            new SupportMessage(id: 2L, ticketId: 5L, author: 'STAFF', body: 'reply')
        ]
        supportTicketRepository.findById(5L) >> Optional.of(ticket)
        supportMessageRepository.findByTicket(5L) >> messages

        when:
        def out = service.getTicket(1L, 5L)

        then:
        1 * adminAuthorization.requireAdmin(1L)
        out.ticket == ticket
        out.messages == messages
    }

    def "getTicket (admin) 404s for an unknown ticket id"() {
        given:
        supportTicketRepository.findById(999L) >> Optional.empty()

        when:
        service.getTicket(1L, 999L)

        then:
        1 * adminAuthorization.requireAdmin(1L)
        thrown(NotFoundException)
        0 * supportMessageRepository.findByTicket(_)
    }

    // ── assertNotBanned — pass-through pin ───────────────────────────
    // Kept as a thin delegate so old controller/service code that reads
    // `adminService.assertNotBanned(uid)` keeps compiling. A regression
    // that dropped the call (e.g. an over-eager refactor) would silently
    // let banned users perform every state-changing op.

    def "assertNotBanned delegates straight to the BanGuard for the given uid"() {
        when:
        service.assertNotBanned(42L)

        then:
        1 * banGuard.assertNotBanned(42L)
    }

    def "assertNotBanned propagates the BanGuard's ForbiddenException untouched"() {
        given:
        banGuard.assertNotBanned(42L) >> { throw new ForbiddenException('banned: spam') }

        when:
        service.assertNotBanned(42L)

        then:
        def e = thrown(ForbiddenException)
        e.message.contains('spam')
    }

    // ── promoteBootstrapAdmin — the env-var elevation path ──────────
    // This is the SECURITY-CRITICAL path: it's the one place a USER row
    // can flip to ADMIN without an admin already in the loop. The guard
    // rails: only fires when the bootstrap-ids env-var is set AND the
    // user's steamId64 matches AND they aren't already an admin.

    def "promoteBootstrapAdmin is a no-op when the bootstrap env var is blank"() {
        given:
        service.bootstrapIds = ''
        def user = new SteamUser(id: 20L, steamId64: '111', role: 'USER')

        when:
        service.promoteBootstrapAdmin(user)

        then:
        user.role == 'USER'
        0 * steamUserRepository.save(_)
    }

    def "promoteBootstrapAdmin is a no-op when the bootstrap env var is null"() {
        given:
        service.bootstrapIds = null
        def user = new SteamUser(id: 20L, steamId64: '111', role: 'USER')

        when:
        service.promoteBootstrapAdmin(user)

        then:
        user.role == 'USER'
        0 * steamUserRepository.save(_)
    }

    def "promoteBootstrapAdmin is a no-op for a null user"() {
        given:
        service.bootstrapIds = '111,222'

        when:
        service.promoteBootstrapAdmin(null)

        then:
        noExceptionThrown()
        0 * steamUserRepository.save(_)
    }

    def "promoteBootstrapAdmin is a no-op when the user has no steamId64"() {
        given:
        service.bootstrapIds = '111,222'
        def user = new SteamUser(id: 20L, steamId64: null, role: 'USER')

        when:
        service.promoteBootstrapAdmin(user)

        then:
        user.role == 'USER'
        0 * steamUserRepository.save(_)
    }

    def "promoteBootstrapAdmin flips role to ADMIN when the user's steamId64 is in the env list"() {
        given:
        service.bootstrapIds = '111,222,333'
        def user = new SteamUser(id: 20L, steamId64: '222', role: 'USER')

        when:
        service.promoteBootstrapAdmin(user)

        then:
        user.role == 'ADMIN'
        1 * steamUserRepository.save(user)
    }

    def "promoteBootstrapAdmin trims whitespace around env-var entries"() {
        // The env var is human-edited (deployment config) — a stray space
        // around a comma must not block elevation.
        given:
        service.bootstrapIds = ' 111 ,  222  , 333 '
        def user = new SteamUser(id: 20L, steamId64: '222', role: 'USER')

        when:
        service.promoteBootstrapAdmin(user)

        then:
        user.role == 'ADMIN'
        1 * steamUserRepository.save(user)
    }

    def "promoteBootstrapAdmin does NOT elevate a non-matching steamId64"() {
        given:
        service.bootstrapIds = '111,222'
        def user = new SteamUser(id: 20L, steamId64: '999', role: 'USER')

        when:
        service.promoteBootstrapAdmin(user)

        then:
        user.role == 'USER'
        0 * steamUserRepository.save(_)
    }

    def "promoteBootstrapAdmin is idempotent — re-running for an already-ADMIN user is a silent no-op"() {
        // Called on every login (via SteamAuthService.upsertUser). The
        // already-admin short-circuit avoids a wasted save() on every
        // login for the bootstrap operator.
        given:
        service.bootstrapIds = '222'
        def user = new SteamUser(id: 20L, steamId64: '222', role: 'ADMIN')

        when:
        service.promoteBootstrapAdmin(user)

        then:
        user.role == 'ADMIN'
        0 * steamUserRepository.save(_)
    }

    // ── listDisputedDeposits (batch 461 / 515 fraud-signal enrichment) ──

    def "listDisputedDeposits returns an empty list when no deposits are disputed"() {
        given:
        transactionRepository.findByTypeAndStatusPaged('DEPOSIT', 'DISPUTED', _) >> []

        when:
        def rows = service.listDisputedDeposits()

        then:
        rows == []
        // No row hydration / steam lookup when the query came back empty.
        0 * walletRepository.findAllById(_)
        0 * steamUserRepository.findBySteamId64(_)
    }

    def "listDisputedDeposits batches wallet + user lookups and enriches each row with fraud signals"() {
        given:
        def disputed = new Transaction(
            id: 1L, walletId: 500L, type: 'DEPOSIT', status: 'DISPUTED',
            amount: new BigDecimal("50"), currency: 'USD',
            description: 'card chargeback', createdAt: 1000L)
        transactionRepository.findByTypeAndStatusPaged('DEPOSIT', 'DISPUTED', _) >> [disputed]
        walletRepository.findAllById([500L]) >> [
            new Wallet(id: 500L, username: 'steam_222', balance: new BigDecimal("10"), frozen: true)
        ]
        steamUserRepository.findBySteamId64('222') >> new SteamUser(
            id: 20L, steamId64: '222', displayName: 'Bob', banned: false,
            emailVerified: true, createdAt: 500L)
        // Second-pass tx scan for the lifetime-disputes signal.
        transactionRepository.findByWalletIdOrderByCreatedAtDesc(500L, _) >> [
            new Transaction(id: 1L, walletId: 500L, type: 'DEPOSIT', status: 'DISPUTED'),
            new Transaction(id: 7L, walletId: 500L, type: 'DEPOSIT', status: 'COMPLETED',
                description: 'DISPUTE_CLEARED by admin (false positive)')
        ]

        when:
        def rows = service.listDisputedDeposits()

        then:
        // N+1 guard — wallet lookup batched, user lookup batched per
        // distinct steamId. NEVER a findAll() scan.
        0 * walletRepository.findAll()
        0 * transactionRepository.findAll()
        rows.size() == 1
        rows[0].id == 1L
        rows[0].walletUsername == 'steam_222'
        rows[0].userId == 20L
        rows[0].userDisplayName == 'Bob'
        rows[0].walletFrozen == true
        rows[0].walletBalance == new BigDecimal("10")
        rows[0].userEmailVerified == true
        // Both the currently-DISPUTED row AND the DISPUTE_CLEARED row
        // count toward the lifetime tally — staff can spot a serial
        // chargeback offender even after past disputes are cleared.
        rows[0].lifetimeDisputes == 2L
    }

    def "listDisputedDeposits caps the result at the 200-row hard limit"() {
        // Hard cap protects the admin dashboard render from a chargeback
        // surge. Past 200, the audit log + CSV are the better surface.
        given:
        org.springframework.data.domain.Pageable seenPage = null
        transactionRepository.findByTypeAndStatusPaged('DEPOSIT', 'DISPUTED', _) >> { args ->
            seenPage = args[2]
            []
        }

        when:
        service.listDisputedDeposits()

        then:
        seenPage.pageSize == 200
    }

    def "listDisputedDeposits degrades gracefully when the per-row lifetime scan throws"() {
        given:
        def disputed = new Transaction(id: 1L, walletId: 500L, type: 'DEPOSIT',
            status: 'DISPUTED', amount: new BigDecimal("50"))
        transactionRepository.findByTypeAndStatusPaged('DEPOSIT', 'DISPUTED', _) >> [disputed]
        walletRepository.findAllById([500L]) >> [new Wallet(id: 500L, username: 'steam_222')]
        steamUserRepository.findBySteamId64('222') >> new SteamUser(id: 20L, steamId64: '222')
        // The lifetime-disputes scan blows up — must not tank the whole
        // dashboard, just degrade that one column to 0.
        transactionRepository.findByWalletIdOrderByCreatedAtDesc(500L, _) >> {
            throw new RuntimeException('connection refused')
        }

        when:
        def rows = service.listDisputedDeposits()

        then:
        noExceptionThrown()
        rows.size() == 1
        rows[0].lifetimeDisputes == 0L
    }
}
