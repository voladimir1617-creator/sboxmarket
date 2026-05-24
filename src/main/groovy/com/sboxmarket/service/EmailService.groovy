package com.sboxmarket.service

import com.sboxmarket.model.SteamUser
import groovy.util.logging.Slf4j
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service

/**
 * Transport-agnostic email sender for verification links, password-less
 * sign-in tokens, and similar one-off system emails.
 *
 * Two modes, selected automatically by inspecting the Spring mail config:
 *
 *  1) **SMTP** — when `spring.mail.host` (aka `SMTP_HOST`) is set, send via
 *     the standard {@link JavaMailSender}. Works with Postmark, SES SMTP,
 *     Mailgun, Resend (SMTP endpoint), Gmail app passwords — anything that
 *     speaks plain RFC-5321.
 *
 *  2) **Log sink** — otherwise, log the full message body at INFO level.
 *     This keeps local dev, CI, and any prod deploy that hasn't yet been
 *     handed SMTP credentials fully functional: the verification token
 *     ends up in the server log instead of an inbox. Operators can still
 *     complete account setup by reading the log.
 *
 * No Spring profile gymnastics — one bean, one decision at startup, log
 * which mode it landed in so an operator can confirm at a glance.
 */
@Service
@Slf4j
class EmailService {

    @Autowired(required = false)
    JavaMailSender mailSender

    @Value('${spring.mail.host:}')
    String smtpHost

    @Value('${app.email.from:no-reply@skinbox.local}')
    String fromAddress

    @Value('${app.email.from-name:SkinBox}')
    String fromName

    /** Reply-to address for every transactional email — keeps the
     *  no-reply From: address (better deliverability, fewer auto-replies
     *  to the relay) while still letting users hit Reply and reach
     *  a real human. Set on every outbound mail in send(). */
    @Value('${app.email.reply-to:support@skinbox.market}')
    String replyToAddress

    @Value('${app.public-url:http://localhost:8080}')
    String publicUrl

    /** HMAC secret for signed one-click unsubscribe URLs (batch 891).
     *  Picked up from env in prod; dev defaults to a nonsense string so
     *  signatures generated in one dev run don't validate in another
     *  (good — no accidental prod bypass). Change this and every
     *  outstanding unsubscribe link stops working, which is the right
     *  behavior if a prod secret is ever leaked. */
    @Value('${app.unsubscribe.secret:dev-only-do-not-use-in-production-7f3a9c}')
    String unsubscribeSecret

    private boolean smtpReady = false

    @PostConstruct
    void init() {
        smtpReady = mailSender != null && smtpHost && !smtpHost.isBlank()
        // Batch 895 — trim any trailing slash on the public URL once so
        // every email template can safely use `${publicUrl}/path` without
        // producing a double-slash when the config ends with `/`.
        if (publicUrl && publicUrl.endsWith('/')) {
            publicUrl = publicUrl.substring(0, publicUrl.length() - 1)
        }
        if (smtpReady) {
            log.info("EmailService: SMTP enabled via {} — sending real mail from {}", smtpHost, fromAddress)
        } else {
            log.warn("EmailService: no SMTP host configured — running in LOG-SINK mode. " +
                     "Verification tokens will appear in the server log. Set SMTP_HOST to enable delivery.")
        }
    }

    // ── Per-bucket mute check ───────────────────────────────────────

    /** The set of buckets a user can opt out of via the Profile UI.
     *  Transactional emails (verification, ban/unban, withdrawal
     *  approval/rejection, deletion request) intentionally bypass the
     *  mute check — those are legal-adjacent and must always deliver. */
    static final Set<String> MUTABLE_EMAIL_BUCKETS = ['TRADES','AUCTIONS','WATCHLIST','FOLLOWS','MATCHES'].toSet()

    /**
     * True when the given user has silenced this email bucket. Only the
     * "mutable" buckets above are consulted; any unknown bucket name
     * returns false (fail-open so a future new email kind isn't
     * silently suppressed by stale data on the user row).
     *
     * Call sites:
     *   BidService         → AUCTIONS  (outbid, won)
     *   TradeService       → TRADES    (sale completed)
     *   WatchlistAlertSvc  → WATCHLIST (price drop)
     *   SellerFollowSvc    → FOLLOWS   (new listing)
     *   SavedSearchService → MATCHES   (saved-search match)
     */
    boolean isBucketMuted(SteamUser user, String bucket) {
        if (user == null || bucket == null) return false
        if (!MUTABLE_EMAIL_BUCKETS.contains(bucket)) return false
        def raw = user.mutedEmailKinds
        if (!raw) return false
        def parts = raw.split(/,/).collect { it?.trim() }.findAll { it }
        parts.contains(bucket)
    }

    /**
     * DRY gate check shared by every transactional-email call site.
     * Before this helper the same 4-predicate expression
     *   `user != null && user.email && Boolean.TRUE.equals(user.emailVerified)
     *    && Boolean.TRUE.equals(user.emailNotificationsEnabled)
     *    && !emailService.isBucketMuted(user, bucket)`
     * was repeated 20+ times across services (batch 621 audit). That's
     * the definition of a DRY violation — changing the gate semantics
     * meant touching every call site and risking a miss.
     *
     * Use this whenever the outgoing email is opt-out-able. Security
     * alerts (ban, 2FA reset, wallet freeze, trade-URL change, new
     * sign-in location) deliberately bypass the gate — they must
     * always deliver — and keep calling `send()` directly.
     */
    boolean canSendTo(SteamUser user, String bucket) {
        if (user == null) return false
        if (user.email == null || user.email.isEmpty()) return false
        if (!Boolean.TRUE.equals(user.emailVerified)) return false
        if (!Boolean.TRUE.equals(user.emailNotificationsEnabled)) return false
        return !isBucketMuted(user, bucket)
    }

    /**
     * Security-alert gate — for emails that deliberately bypass the
     * opt-out toggle + bucket mute. Only fires when the user has a
     * verified email address; nothing else. Use this for ban, 2FA
     * reset, wallet freeze, email change, new sign-in location, etc.
     * — alerts the user can't legally / shouldn't be able to silence.
     *
     * Extracted in batch 623 from the 9+ inline
     * `user != null && Boolean.TRUE.equals(user.emailVerified) && user.email`
     * duplicates across AdminService / StripeService / ProfileController.
     */
    boolean canSendSecurityTo(SteamUser user) {
        if (user == null) return false
        if (user.email == null || user.email.isEmpty()) return false
        return Boolean.TRUE.equals(user.emailVerified)
    }

    /** Format an amount as "$X.XX (USD)" for in-body currency display.
     *  Per the email-audit policy: emails are async + may render hours
     *  later in any timezone, but the currency itself is always USD —
     *  surface that explicitly in money-mention bodies so a user
     *  reading two notifications back-to-back doesn't have to wonder
     *  whether one of them switched units. Always shows two decimals
     *  for visual consistency across rounded + fractional amounts.
     *  Subject lines keep the bare $X.XX form to stay under 45 chars. */
    static String usd(BigDecimal amount) {
        def b = amount ?: BigDecimal.ZERO
        return '$' + b.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + ' (USD)'
    }

    /** Send the verification email for a new/changed address. */
    void sendVerification(String toEmail, String token) {
        if (!toEmail) return
        // Batch 893 — history-API path, not hash-routing. Previously
        // `/#/profile?verify=<token>` landed the user at `/` with the
        // token in the fragment, which the SPA never read — clicking
        // the email link had no effect and users had to manually copy
        // the token into the Profile input. Now goes to a real
        // history-routed URL; ProfileModal auto-submits on mount.
        def link = "${publicUrl}/profile?verify=${token}"
        def subject = 'Confirm your SkinBox email'
        def body = """\
Hi there,

Please confirm this address so we can reach you about trade activity, security alerts, and support tickets.

Confirm: ${link}

Or paste this code into the verification box on your profile page: ${token}

If you didn't ask for this, you can safely ignore this message — nothing will change on the account.

— SkinBox
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Security alert on Steam trade-URL change (batch 513). Any future
     *  purchase / auction win sends the item to whatever URL is on file,
     *  so an attacker with a stolen session could redirect shipments by
     *  swapping this. Fires to the verified email whenever the URL
     *  actually moves. */
    void sendTradeUrlChanged(String toEmail, String displayName, String newTradeUrl) {
        if (!toEmail) return
        def subject = "Security alert: Steam trade URL updated"
        def snippet = (newTradeUrl ?: '(removed)').take(120)
        def body = """\
Hi ${displayName ?: 'there'},

Your Steam trade URL on SkinBox was just ${newTradeUrl ? 'changed' : 'removed'}.

${newTradeUrl ? 'New URL: ' + snippet : 'The URL has been cleared — you can no longer receive items on SkinBox until you set one again.'}

Any future purchase or auction win ships the item to the trade URL on
file, so if this change wasn't you, an attacker could be redirecting
shipments to their own Steam account.

If this WASN'T you:
  1. Change your Steam password immediately.
  2. Sign out of every SkinBox session from Profile → Security.
  3. Restore your real trade URL from Profile → Steam settings.
  4. Reply to this email — we'll freeze the wallet and review activity.

If it was you, no action needed.

— The SkinBox security team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Security alert when the user disables their own 2FA (batch 512).
     *  Any session — including a stolen one — can disable 2FA by
     *  providing a live TOTP code OR burning a recovery code. If that
     *  action wasn't the owner, the account is compromised and they
     *  need to rotate Steam passwords, kill sessions, and re-enrol. */
    void send2faDisabled(String toEmail, String displayName) {
        if (!toEmail) return
        // ≤45 chars; was "Security alert: 2FA was disabled on your SkinBox account" (56).
        def subject = "⚠ Security alert · 2FA disabled"
        def body = """\
Hi ${displayName ?: 'there'},

Two-factor authentication was just turned OFF on your SkinBox account.

If this was you, no action needed.

If it WASN'T you, act fast — somebody has access to your account:
  1. Change your Steam password.
  2. Sign out of every SkinBox session from Profile → Security.
  3. Re-enable 2FA from Profile → 2FA.
  4. Reply to this email — we'll review the account activity.

This is an automated security notification. You can't turn it off.

— The SkinBox security team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Security alert to the OLD verified address when a user changes
     *  their email (batch 512). Closes the account-takeover vector
     *  where an attacker with a stolen session quietly swaps the
     *  recovery channel before locking the real owner out. If this
     *  lands in the wrong inbox, the user hits Reply and support
     *  rolls the change back. */
    void sendEmailChanged(String oldEmail, String displayName, String newEmail) {
        if (!oldEmail || !newEmail) return
        def subject = "Security alert: SkinBox email changed"
        def body = """\
Hi ${displayName ?: 'there'},

Heads up — the email address on your SkinBox account was just changed from
${oldEmail} to ${newEmail}.

If this was you, no action needed. The new address has been sent a
confirmation link and will become the active recovery channel once
confirmed.

If this WASN'T you:
  1. Reply to this email from this inbox (we can verify the chain
     because it's going to your old verified address).
  2. We'll roll the change back and lock the account while we
     investigate.
  3. Review your Steam account's "devices and sessions" — the attacker
     likely still has a live cookie.

This is an automated alert we send on every email change as a security
courtesy. You can't turn it off — it's going to the OLD address, which
is exactly the one we need to reach if your account is being taken over.

— The SkinBox security team
""".stripIndent()
        send(oldEmail, subject, body)
    }

    /** Refund-issued notification (batch 522). Fires alongside the
     *  REFUND_ISSUED push when a deposit is refunded (either via the
     *  admin panel or directly from the Stripe Dashboard) so the user
     *  sees the balance drop with clear context instead of opening
     *  Wallet → Transactions the next day wondering what happened. */
    void sendRefundIssued(String toEmail, String displayName, BigDecimal amount, BigDecimal newBalance) {
        if (!toEmail || amount == null) return
        def subject = "Deposit refunded · \$${amount.toPlainString()}"
        def body = """\
Hi ${displayName ?: 'there'},

A ${usd(amount)} deposit on your SkinBox wallet has been refunded
back to your original payment method. Your new SkinBox balance is
${usd(newBalance)}.

Stripe typically settles the refund to your card in 5-10 business days.

If you were expecting this refund, no action needed. If this came as a
surprise, reply to this email and we'll pull up the transaction.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Chargeback-opened notification (batch 521). Mirrors the push
     *  shape: two scenarios covered — the user filed the chargeback
     *  (friendly fraud) or their card was compromised. Either way we
     *  lock the wallet and the email gives a clear "this is what's
     *  happening + what to do" playbook. Goes to the verified email
     *  even if the in-app bell was missed. */
    void sendChargebackOpened(String toEmail, String displayName, BigDecimal amount) {
        if (!toEmail) return
        def subject = "Deposit disputed · SkinBox wallet on hold"
        def body = """\
Hi ${displayName ?: 'there'},

A chargeback was filed against your \$${(amount ?: BigDecimal.ZERO).toPlainString()} SkinBox
deposit. While the dispute is open, your wallet cannot withdraw, buy, or
send offers.

If you filed this yourself: thanks for letting us know. The hold clears
automatically once your bank closes the dispute.

If you DID NOT file this (the chargeback came from your bank without
your say-so), somebody may have used your card or taken over your
account. Act now:
  1. Call your bank and confirm — they'll tell you whether the dispute
     came from you or from a fraud-watch system.
  2. Change your Steam password and review devices / sessions.
  3. Reply to this email and we'll review + lock the account for safety.

Either way, the withdrawal hold stays in place until the dispute closes.
This usually takes 7-14 business days on Stripe's side.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Dispute-cleared notification (batch 520). The user was blocked
     *  from withdrawing while a chargeback on their deposit was open;
     *  now that staff cleared the hold, we tell them they can withdraw
     *  again. Goes out even to inactive users so they don't miss the
     *  money-gate state change if they stopped checking the bell. */
    void sendDisputeCleared(String toEmail, String displayName) {
        if (!toEmail) return
        def subject = "Withdrawals re-enabled on your SkinBox account"
        def body = """\
Hi ${displayName ?: 'there'},

Good news — the deposit dispute on your SkinBox wallet has been resolved
in your favour. Your withdrawal hold has been lifted and you can now
request payouts again from Wallet → Withdraw.

If you'd previously queued a withdrawal that got blocked, re-submit it
from the wallet page.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Account-suspended notice. Fires from AdminService.banUser when the
     *  target has a verified email. Plain-text so the template doesn't
     *  render weirdly in email clients that don't speak multipart. */
    void sendAccountBanned(String toEmail, String displayName, String reason, String appealUrl) {
        if (!toEmail) return
        def subject = 'Your SkinBox account has been suspended'
        def body = """\
Hi ${displayName ?: 'there'},

Your SkinBox account has been suspended by our moderation team.

Reason: ${reason ?: 'Policy violation'}

What this means:
  · Your active listings have been cancelled.
  · Open trades have been rolled back to the other party.
  · You can't sign in to SkinBox while the suspension is in effect.

If you believe this was a mistake, you can appeal by replying to this
email${appealUrl ? ' or by visiting ' + appealUrl : ''}. Appeals are reviewed within 1-2 business days.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Withdrawal approved — funds released to the destination. Fires
     *  from AdminService.approveWithdrawal. Payout processors can take a
     *  business day or two to settle so the email gives the user a
     *  concrete reference to follow up on. */
    void sendWithdrawalApproved(String toEmail, String displayName, BigDecimal amount, String payoutRef) {
        if (!toEmail || amount == null) return
        def subject = "Withdrawal approved · \$${amount.toPlainString()}"
        def body = """\
Hi ${displayName ?: 'there'},

Your ${usd(amount)} withdrawal has been approved and released.

Payout reference: ${payoutRef ?: '(none)'}

Settlement typically clears within 1-2 business days depending on your
payout destination. You can track the transaction from Wallet →
Transactions at any time.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Withdrawal rejected — the amount was credited back to the wallet.
     *  Fires from AdminService.rejectWithdrawal. */
    void sendWithdrawalRejected(String toEmail, String displayName, BigDecimal amount, String reason) {
        if (!toEmail || amount == null) return
        def subject = "Withdrawal rejected · funds refunded"
        def body = """\
Hi ${displayName ?: 'there'},

Your ${usd(amount)} withdrawal request was rejected and the
full amount has been credited back to your SkinBox wallet.

Reason: ${reason ?: 'See the Transactions tab for details.'}

You can open a new withdrawal from Wallet → Withdraw once the underlying
issue is resolved. If you need help, reply to this email or open a
support ticket.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Watchlist price alert fired — the item's floor dropped at or
     *  below the user's target. Time-sensitive (prices move fast) so
     *  this goes out via email alongside the in-app notification.
     *
     *  Restock-mode alerts encode targetPrice ≥ 99999 as "any future
     *  listing." For those the body switches from a price-comparison
     *  blurb to a restock-style one — we don't want the user to read
     *  "at or below your $100,000 alert target" and think a typo set
     *  their threshold absurdly high. */
    void sendPriceDrop(String toEmail, String displayName, String itemName,
                       BigDecimal currentFloor, BigDecimal targetPrice, String itemUrl) {
        if (!toEmail || itemName == null) return
        boolean isRestock = targetPrice != null &&
            targetPrice.compareTo(new BigDecimal('99999')) >= 0
        def subject = isRestock
            ? "Restock · ${itemName}"
            : "Price drop · ${itemName}"
        def floorStr = (currentFloor ?: BigDecimal.ZERO).toPlainString()
        def coreLine = isRestock
            ? "${itemName} is back in stock — listed at \$${floorStr}."
            : "${itemName} just dropped to \$${floorStr} —\nat or below your \$${(targetPrice ?: BigDecimal.ZERO).toPlainString()} alert target."
        def body = """\
Hi ${displayName ?: 'there'},

${coreLine}

Listings move fast. See the item${itemUrl ? ': ' + itemUrl : '.'}

You can manage or cancel your active alerts from the Watchlist page.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Staff replied on a support ticket (batch 475). Pings the user
     *  by email so they know to come back. Body includes a snippet of
     *  the staff reply so the user can decide whether it's urgent
     *  before clicking through. */
    void sendSupportReply(String toEmail, String displayName, Long ticketId, String subject, String snippet) {
        if (!toEmail || ticketId == null) return
        def emailSubject = "Support reply · ticket #${ticketId}" + (subject ? ' · ' + subject.take(60) : '')
        def body = """\
Hi ${displayName ?: 'there'},

A SkinBox support agent has replied to your ticket #${ticketId}${subject ? ' (' + subject + ')' : ''}:

${snippet ? '> ' + snippet.take(300).readLines().join('\n> ') : '(reply contents available in the app)'}

Open the ticket to read the full reply and respond:
${publicUrl}/support

— The SkinBox team
""".stripIndent()
        send(toEmail, emailSubject, body)
    }

    /** Seller you follow just posted a new listing. Low-priority so we
     *  only send when the user opted in to email notifications. */
    void sendNewListingFromSeller(String toEmail, String displayName, String sellerName,
                                  String itemName, BigDecimal price, String itemUrl) {
        if (!toEmail || sellerName == null) return
        def subject = "${sellerName} just listed ${itemName ?: 'a new item'}"
        def body = """\
Hi ${displayName ?: 'there'},

${sellerName} — a seller you follow — just listed ${itemName ?: 'a new item'}${
    price != null ? ' for \$' + price.toPlainString() : ''
}.

Take a look${itemUrl ? ': ' + itemUrl : '.'}

You're getting this because you followed the seller. Unfollow from the
seller's stall page or turn off email notifications in Profile → Settings.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Auction outbid. Fires from BidService.placeBid when a higher bid
     *  displaces an existing top bid. Gives the previous top bidder a
     *  chance to respond before the timer closes. */
    void sendAuctionOutbid(String toEmail, String displayName, String itemName, BigDecimal newTopBid, String itemUrl) {
        if (!toEmail) return
        def subject = "Outbid on ${itemName ?: 'auction'}"
        def body = """\
Hi ${displayName ?: 'there'},

You've been outbid on ${itemName ?: 'an auction you were winning'}.

Current top bid: \$${(newTopBid ?: BigDecimal.ZERO).toPlainString()}

You can place a new bid or set an auto-bid cap from the item detail
page${itemUrl ? ': ' + itemUrl : '.'}

If you've already set an auto-bid that hasn't been exhausted, our bot
may have re-raised on your behalf since this email was sent — check the
listing for the live state.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Wallet freeze alert (batch 584). Security-grade alert — bypasses
     *  the `emailNotificationsEnabled` + bucket-mute gate for the same
     *  reason as 2FA-reset + email-change: a user can't opt out of
     *  finding out their wallet just got blocked. */
    void sendWalletFrozen(String toEmail, String displayName, String reason) {
        if (!toEmail) return
        // ≤45 chars; was 47.
        def subject = "⚠ Security alert · Wallet frozen"
        def cleanReason = (reason ?: '').trim()
        def body = """\
Hi ${displayName ?: 'there'},

A SkinBox admin has frozen your wallet. You won't be able to deposit, withdraw, or purchase while the freeze is in effect. Existing listings + trades-in-flight are unaffected.

${cleanReason ? 'Staff note: ' + cleanReason + '\n\n' : ''}\
If you think this was a mistake, open a ticket at ${publicUrl}/support and staff will review.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** New API key minted (batch 691). Security alert — a compromised
     *  session could otherwise mint a long-lived API key silently and
     *  keep pilfering inventory / funds even after the victim's 2FA
     *  reset. This email lets the user spot an unauthorised mint
     *  immediately. Non-opt-out (same policy as every other security
     *  alert). Body calls out the scope so an RO key doesn't read as
     *  full-access alarm, but still nudges the user to revoke if it
     *  wasn't them. */
    void sendApiKeyMinted(String toEmail, String displayName, String label, String scope, String prefix) {
        if (!toEmail) return
        // Subject stays ≤45 chars; longer scope description moved into the body.
        def scopeSuffix = (scope == 'RO') ? ' (read-only)' : ' (full access · can buy, sell, move funds)'
        def subjectScope = (scope == 'RO') ? ' (read-only)' : ' (full access)'
        def subject = "⚠ Security alert · API key minted${subjectScope}"
        def body = """\
Hi ${displayName ?: 'there'},

A new API key was just minted for your SkinBox account.

Label:  ${label ?: '(no label)'}
Prefix: ${prefix ?: '(unknown)'}…
Scope:  ${scope ?: 'RW'}${scopeSuffix}

If you minted this yourself — great, you can ignore this email. The raw token was shown to you once on the Profile → Developers tab; store it somewhere safe (it won't be displayed again).

If you DID NOT mint this key:
  1. Sign in to SkinBox immediately.
  2. Go to Profile → Developers and revoke every API key you don't recognise.
  3. Sign out of all devices from Profile → Personal.
  4. Rotate your 2FA device via Profile → 2FA.
  5. Reply to this email — staff will audit your account for unauthorised activity.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Wallet unfreeze notice (batch 584). Good-news counterpart to
     *  sendWalletFrozen. Same non-opt-out security-email policy. */
    void sendWalletUnfrozen(String toEmail, String displayName) {
        if (!toEmail) return
        def subject = "Your wallet has been unfrozen"
        def body = """\
Hi ${displayName ?: 'there'},

Staff has lifted the freeze on your wallet. You can deposit, withdraw, and purchase again immediately.

Open your wallet: ${publicUrl}/wallet

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Security alert — an admin reset the user's 2FA (batch 575).
     *  Deliberately not gated on `emailNotificationsEnabled` / bucket
     *  mutes — security alerts ignore the opt-out for the same
     *  reason as email-change / trade-url-change alerts: an attacker
     *  who flipped the pref off can't thereby silence the alarm.
     *  Body is blunt: what happened, what to do next, how to escalate
     *  if it wasn't authorised. */
    void sendTwoFactorReset(String toEmail, String displayName, String adminNote) {
        if (!toEmail) return
        def subject = "⚠ Security alert · Your 2FA was reset"
        def cleanNote = (adminNote ?: '').trim()
        def body = """\
Hi ${displayName ?: 'there'},

Two-factor authentication on your SkinBox account was just reset by staff.

${cleanNote ? 'Staff note: ' + cleanNote + '\n\n' : ''}\
If this was you:
  • Sign in and re-enrol 2FA from Profile → Security → Enable 2FA.
  • Your old authenticator codes no longer work.

If this was NOT you:
  • Reply to this email or open a ticket at ${publicUrl}/support immediately.
  • We'll lock the account and investigate.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Admin force-logout alert (batch 702). Mirrors the 2FA-reset /
     *  wallet-frozen / new-sign-in pattern: an admin action that
     *  affects the user's security must reach them out-of-band so a
     *  compromised session can't hide the change. Non-opt-out —
     *  security alerts always fire. Body explains what just happened
     *  + what the user should do (re-sign-in, rotate 2FA if anything
     *  smells off, open a ticket if it wasn't them). */
    void sendForceLogout(String toEmail, String displayName, String adminNote) {
        if (!toEmail) return
        // ≤45 chars; was 46.
        def subject = "⚠ Security alert · Sessions revoked"
        def cleanNote = (adminNote ?: '').trim()
        def body = """\
Hi ${displayName ?: 'there'},

A SkinBox admin has revoked every active session on your account. You've been signed out of every device / API key bearer currently holding a session cookie. Your account itself is NOT banned — you can sign in again whenever you're ready.

${cleanNote ? 'Staff note: ' + cleanNote + '\n\n' : ''}\
Common reasons:
  • Staff spotted suspicious activity and pre-emptively revoked sessions while they investigate.
  • You asked support to clear a stuck or hijacked session.
  • Part of triage on a reported account.

What to do:
  1. Sign in fresh with Steam at ${publicUrl}.
  2. If you have 2FA enabled, you'll need to present a fresh code as usual.
  3. If this came out of the blue, reply to this email — staff may have more context or may be mid-investigation.

This alert cannot be disabled — security-critical events always fire.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Buy-order auto-filled (batch 574). Fires from BuyOrderService.tryMatch
     *  AND create() initial sweep when a standing buy order catches a
     *  matching listing. Buyer's wallet was just debited; they need to
     *  know the money's gone and into what. Includes the fill price +
     *  the cap they'd set so they can verify the engine didn't overpay. */
    void sendBuyOrderFilled(String toEmail, String displayName, String itemName,
                            BigDecimal fillPrice, BigDecimal maxCap, String itemUrl) {
        if (!toEmail) return
        def subject = "Buy order filled · ${itemName ?: 'item'}"
        def body = """\
Hi ${displayName ?: 'there'},

Your standing buy order for ${itemName ?: 'an item'} just auto-filled.

Paid:         ${usd(fillPrice)}
Your cap was: ${usd(maxCap)}

The seller has been notified to send the Steam trade offer. Track the trade from Profile → Trades, or open the item${itemUrl ? ': ' + itemUrl : '.'}

If anything looks off (wrong price, wrong item) open a dispute from the trade row and staff will review.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Auction expired without a valid winner (batch 607). Three
     *  scenarios from `BidService.settle`: zero bids, top-bidder
     *  banned/deleted, or top-bidder couldn't pay at settle time. In
     *  all three cases the item returns to the seller's inventory and
     *  a bell push fires, but the seller was almost certainly offline
     *  for the hours-to-days the auction ran — email closes the loop
     *  so they know to relist. Gated on AUCTIONS bucket + verified
     *  email like every other auction email. */
    void sendAuctionExpired(String toEmail, String displayName, String itemName,
                            String reason, Long listingId) {
        if (!toEmail) return
        def subject = "Auction ended without a sale · ${itemName ?: 'your auction'}"
        def body = """\
Hi ${displayName ?: 'there'},

Your auction for ${itemName ?: 'the item'} has ended.

${reason ?: 'No valid winning bid was recorded.'}

The item has been returned to your Platform Inventory and is ready to
relist at a different price or as a Buy Now listing:
${publicUrl}/sell

You're getting this because you posted an auction listing. Mute the
"Auction activity" bucket in Profile → Email notifications to stop
these.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** New sign-in location security alert (batch 606). Fires from
     *  `SteamAuthController.loginReturn` when a successful Steam sign-in
     *  comes from an IP that hasn't been seen on this user's audit
     *  history in the last 30 days. Deliberately NOT gated on the
     *  notification-opt-out toggle — same policy as ban / wallet-freeze
     *  / 2FA-reset / email-change: security alerts are intentionally
     *  non-mutable. Body surfaces the IP + user-agent snippet + a clear
     *  "revoke all sessions" CTA in case the user didn't initiate the
     *  sign-in.
     */
    void sendNewSignIn(String toEmail, String displayName, String ip, String userAgent) {
        if (!toEmail) return
        // ≤45 chars; was 55.
        def subject = '⚠ Security alert · New sign-in detected'
        def uaSnippet = (userAgent ?: '(unknown device)').take(120)
        def ipLabel = ip ?: '(unknown IP)'
        def body = """\
Hi ${displayName ?: 'there'},

We detected a successful Steam sign-in to your SkinBox account from a
new IP address. If this was you (new device, travel, VPN toggle, etc.),
no action is needed.

  · IP:     ${ipLabel}
  · Device: ${uaSnippet}

If this WASN'T you, someone may have access to your Steam cookie /
device. Immediately:

  1. Open Profile → Personal → "Sign out everywhere" to revoke every
     live session on the account.
  2. Re-enrol 2FA if you hadn't already.
  3. Open a support ticket with category "Account" so staff can review
     the access pattern.

This alert cannot be disabled — security-critical events always fire.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Trade auto-released to the seller after the buyer went silent
     *  through the 8-day confirmation window (batch 601). Fires from
     *  `TradeService.sweepPendingConfirm` → `release(auto=true)`. Buyer-
     *  side; the seller-side "Sale completed" email still fires via
     *  `sendSaleCompleted`. A buyer idle 8 days probably didn't see the
     *  bell — this email closes the loop so they know the item is
     *  considered delivered + the funds left escrow. Includes a dispute
     *  CTA in case the trade actually went wrong but the buyer forgot
     *  to flag it. Gated on verified email + TRADES bucket like every
     *  other trade email. */
    void sendTradeAutoReleased(String toEmail, String displayName, String itemName, Long tradeId) {
        if (!toEmail) return
        def subject = "Trade auto-verified · ${itemName ?: 'trade #' + tradeId}"
        def body = """\
Hi ${displayName ?: 'there'},

We haven't heard from you in 8 days, so we automatically released the
escrow on your trade for ${itemName ?: 'this item'} to the seller.

If you received the item, you're done — nothing more to do.

If the seller never sent the item (or sent the wrong one), open a
ticket from:
${publicUrl}/profile?tab=trades

Staff will review the case even after auto-release. Please act within
30 days — older trades are harder to re-open.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Buy-order auto-expired after 30 days of idleness (batch 596).
     *  Fires from BuyOrderService.sweepStaleBuyOrders. A user who was
     *  away for 30 days is exactly the one who needs the email — they
     *  haven't been on the site to see the bell. Surfaces the item
     *  name + cap so they can re-create the order in one glance. */
    void sendBuyOrderExpired(String toEmail, String displayName, String itemName,
                             BigDecimal maxCap, String itemUrl) {
        if (!toEmail) return
        def subject = "Buy order auto-expired · ${itemName ?: 'item'}"
        def body = """\
Hi ${displayName ?: 'there'},

Your standing buy order for ${itemName ?: 'an item'} sat idle for 30 days and has been automatically expired. No funds were moved — the hold on your wallet has been released.

Your cap was: \$${(maxCap ?: BigDecimal.ZERO).toPlainString()}

If you still want the item, re-create the buy order from:
${publicUrl}/profile?tab=buyorders

Or open the item directly${itemUrl ? ': ' + itemUrl : '.'}

You're getting this because buy-order results are part of your trade pipeline. Mute the "Trade activity" bucket in Profile → Email notifications to stop these.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Trade cancelled — fires from manual cancel + the day-3 auto-cancel
     *  sweeper (batch 573). Buyer's wallet already refunded; seller's
     *  listing already returned to inventory. Body differs by role so
     *  a buyer reads "refund is back" and a seller reads "relist to try
     *  again." Gated on verified email + notification opt-in + TRADES
     *  bucket unmuted like every other trade email. */
    void sendTradeCancelled(String toEmail, String displayName, String itemName,
                            String reason, String role, Long tradeId) {
        if (!toEmail) return
        def isBuyer = (role ?: '').toLowerCase() == 'buyer'
        def subject = "Trade cancelled · ${itemName ?: 'trade #' + tradeId}"
        def cleanReason = (reason ?: '').trim()
        def body = """\
Hi ${displayName ?: 'there'},

Your trade for ${itemName ?: 'the item'} was cancelled.

${cleanReason ? 'Reason: ' + cleanReason + '\n\n' : ''}\
${isBuyer
    ? 'Your wallet has been refunded the full amount — check Profile → Wallet to confirm. No further action needed on your end.'
    : 'The item is back in your stall inventory. Relist it from Profile → Sold → Relist if you still want to sell.'}

Any questions? Reply to this email (it threads into support) or open a ticket in-app: ${publicUrl}/support

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Auction ending in ~N minutes (batch 572). Fires once per auction
     *  inside the 10-minute close window to every bidder + watcher with
     *  a verified email. The bell push reaches active users; this catches
     *  the rest. Gated on the `AUCTIONS` mute bucket — a heavy auction
     *  watcher who opts out stays quiet while still getting trade /
     *  wallet / dispute emails. */
    void sendAuctionEnding(String toEmail, String displayName, String itemName,
                           BigDecimal topBid, long minutesLeft, String itemUrl) {
        if (!toEmail) return
        def subject = "Ending in ~${minutesLeft}m · ${itemName ?: 'an auction'}"
        def body = """\
Hi ${displayName ?: 'there'},

${itemName ?: 'An auction'} you're watching / have bid on is ending in about ${minutesLeft} minute${minutesLeft == 1L ? '' : 's'}.

Current top bid: \$${(topBid ?: BigDecimal.ZERO).toPlainString()}

If you want to raise your bid or confirm your auto-bid cap, open the item now${itemUrl ? ': ' + itemUrl : '.'}

Anti-snipe rules: a bid in the final 30 seconds extends the close by another 30 seconds so nobody wins purely on timing. A single last-second click still has a fair chance.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Auction won. Fires from BidService.settleAuction when the timer
     *  closes and the user's bid carried the auction. Charged amount
     *  is already debited from their wallet at settlement. */
    void sendAuctionWon(String toEmail, String displayName, String itemName, BigDecimal finalPrice, String itemUrl) {
        if (!toEmail) return
        def subject = "You won · ${itemName ?: 'auction'}"
        def body = """\
Hi ${displayName ?: 'there'},

You won the auction for ${itemName ?: 'an item'} at ${usd(finalPrice)}.

The seller has been notified and will send the Steam trade offer within
the escrow window (typically 8 days). You can track the trade from
Profile → Trades${itemUrl ? ' or open the item: ' + itemUrl : '.'}

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Sale completed — seller notification that escrow released on a
     *  trade and the net credit (price − platform fee) hit their wallet.
     *  Fires from TradeService.release(), which runs on explicit
     *  buyerConfirm AND on the 8-day auto-release sweep. Sellers who
     *  close a sale and walk away need this email to know the money
     *  actually arrived — the in-app TRADE_VERIFIED notification is
     *  easy to miss for days. */
    void sendSaleCompleted(String toEmail, String displayName, String itemName,
                           BigDecimal netCredit, String walletUrl) {
        if (!toEmail) return
        def subject = "Sale completed · ${itemName ?: 'your listing'}"
        def body = """\
Hi ${displayName ?: 'there'},

The buyer confirmed receipt of ${itemName ?: 'your item'} and funds have
been released from escrow. Your wallet was credited ${usd(netCredit)}
(sale price minus the 2% platform fee).

You can cash out to Stripe from your wallet at any time${walletUrl ? ': ' + walletUrl : '.'}

Payouts typically clear in 1-2 business days.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Counterparty-facing email when a trade dispute is filed against
     *  them (batch 567). The counterparty needs to respond to staff or
     *  the trade stays frozen; the bell push alone is easy to miss. Body
     *  prioritizes action ("respond in-app") over handholding. Gated the
     *  same as other transactional sends — verified email + global
     *  notification opt-in + TRADES bucket unmuted. */
    void sendTradeDisputed(String toEmail, String displayName, String itemName,
                           String filerRole, String disputeNote, Long tradeId) {
        if (!toEmail) return
        def subject = "Trade dispute · ${itemName ?: 'trade #' + tradeId}"
        def snippet = (disputeNote ?: '').trim()
        if (snippet.length() > 300) snippet = snippet.substring(0, 297) + '…'
        def body = """\
Hi ${displayName ?: 'there'},

The ${(filerRole ?: 'counterparty').toLowerCase()} on ${itemName ?: 'your trade'} just filed a dispute. The escrow is frozen pending staff review.

${snippet ? 'Reason given:\n\n> ' + snippet.readLines().join('\n> ') + '\n' : ''}
Open the trade and reply in-app to help staff resolve this quickly. Silence delays the review:
${publicUrl}/profile?tab=trades

If you believe the dispute is fraudulent or retaliatory, flag that directly in the trade chat — staff reads both sides.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Buyer-facing email when the seller marks the Steam trade offer
     *  sent (batch 565). The buyer needs to go to steamcommunity.com,
     *  accept the pending offer, and come back to click Confirm. If
     *  they miss the bell push they'll get pinged here too. Body
     *  spells out the three-step action list so a buyer not in-app
     *  has everything they need. Gated the same as other transactional
     *  sends. */
    void sendTradeSent(String toEmail, String displayName, String itemName,
                       String sellerName, Long tradeId) {
        if (!toEmail) return
        def tradeRef = tradeId ? " (trade #${tradeId})" : ''
        def subject = "Steam trade offer sent · ${itemName ?: 'your purchase'}"
        def body = """\
Hi ${displayName ?: 'there'},

${sellerName ?: 'The seller'} just sent the Steam trade offer for ${itemName ?: 'your purchase'}${tradeRef}.

Next steps:
  1. Open steamcommunity.com → Inventory → Trade Offers
  2. Accept the incoming offer from ${sellerName ?: 'the seller'}
  3. Come back to SkinBox and click "Confirm receipt" on the trade

You have 8 days before the trade auto-releases — plenty of time:
${publicUrl}/profile?tab=trades

If the item doesn't match the listing or something feels off, open a dispute from the trade row.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Buyer-facing purchase receipt (batch 571). Fires from
     *  PurchaseService.buy the moment a Buy Now / cart-checkout /
     *  buy-order fill lands. Pairs with sendTradeOpened (seller-side,
     *  batch 564) so both parties get email the moment the trade
     *  opens. Gated the same as every other transactional trade
     *  email. No dollar math past the sale price — the platform fee
     *  is a seller-side deduction and doesn't belong on a buyer
     *  receipt. */
    void sendPurchaseReceipt(String toEmail, String displayName, String itemName,
                             String sellerName, BigDecimal price, Long listingId) {
        if (!toEmail) return
        def subject = "Purchase confirmed · ${itemName ?: 'your item'}"
        def sellerLine = sellerName ? " from ${sellerName}" : ''
        def body = """\
Hi ${displayName ?: 'there'},

You purchased ${itemName ?: 'an item'}${sellerLine} for ${usd(price)}.

The funds are held in escrow until the seller sends the Steam trade offer and you confirm receipt. Expect an email or bell notification when the seller marks it sent — then head to steamcommunity.com to accept the offer.

Track the trade's status any time:
${publicUrl}/profile?tab=trades

If the seller doesn't respond within 3 days, the trade auto-cancels and you'll be fully refunded.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Seller-facing email on a fresh sale (batch 564). Fires alongside
     *  the TRADE_REQUESTED bell push the moment a buyer hits Buy Now /
     *  cart checkout / buy-order fill. Critical for sellers who aren't
     *  in-app — without this, a sale goes silent for up to 24 hours
     *  until the slow-seller sweeper warns the buyer + nudges the
     *  seller. Gated on verified email + global notification pref +
     *  TRADES bucket mute (same rules as sendSaleCompleted). */
    void sendTradeOpened(String toEmail, String displayName, String itemName,
                         String buyerName, BigDecimal price, Long tradeId) {
        if (!toEmail) return
        def tradeRef = tradeId ? " (trade #${tradeId})" : ''
        def subject = "New sale · ${itemName ?: 'your listing'}"
        def body = """\
Hi ${displayName ?: 'there'},

${buyerName ?: 'A buyer'} just bought ${itemName ?: 'your item'} for ${usd(price)}${tradeRef}.

The funds are held in escrow until you:
  1. Accept the trade
  2. Send the Steam trade offer to the buyer
  3. They confirm receipt

Trades auto-cancel if the seller doesn't respond within 3 days, so please action it soon:
${publicUrl}/profile?tab=trades

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Offer received — fires from OfferService.makeOffer when a buyer
     *  lodges a fresh offer on one of the seller's listings. Previously
     *  the seller only saw the offer next time they opened the Offers
     *  tab; for a sleeping seller that could delay response by hours or
     *  days. Gated on the TRADES bucket since offers are part of the
     *  transactional pipeline. */
    void sendOfferReceived(String toEmail, String displayName, String buyerName,
                           String itemName, BigDecimal amount, BigDecimal askingPrice,
                           String buyerNote) {
        if (!toEmail || amount == null) return
        def priceStr = amount.toPlainString()
        def askStr = askingPrice != null ? askingPrice.toPlainString() : null
        def subject = "New offer · \$${priceStr} on ${itemName ?: 'your listing'}"
        def cleanNote = buyerNote?.trim() ?: null
        def noteBlock = cleanNote
            ? "\nBuyer's note:\n  \"${cleanNote.length() > 240 ? cleanNote.substring(0, 237) + '…' : cleanNote}\"\n"
            : ''
        def body = """\
Hi ${displayName ?: 'there'},

${buyerName ?: 'A buyer'} just made an offer on your listing.

  · Item:        ${itemName ?: 'your listing'}
  · Offer:       \$${priceStr}${askStr ? " (asking \$${askStr})" : ''}
${noteBlock}
Accept, counter, or reject from:
${publicUrl}/offers

Offers auto-decline after 7 days with no action. Counter-offers keep
the conversation going without the offer expiring.

You're getting this because offers are part of your trade pipeline.
Mute the "Trade activity" bucket in Profile → Email notifications to
stop these.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Offer countered — fires from OfferService.counterOffer when a
     *  seller counters the buyer's offer. The ball is in the buyer's
     *  court now; without this email a sleeping buyer may miss the
     *  counter entirely and let it expire. Gated on the TRADES bucket. */
    void sendOfferCountered(String toEmail, String displayName, String itemName,
                            BigDecimal yourOffer, BigDecimal sellerCounter,
                            String sellerNote) {
        if (!toEmail || sellerCounter == null) return
        def subject = "Seller countered · \$${sellerCounter.toPlainString()} on ${itemName ?: 'your offer'}"
        def cleanNote = sellerNote?.trim() ?: null
        def noteBlock = cleanNote
            ? "\nSeller's note:\n  \"${cleanNote.length() > 240 ? cleanNote.substring(0, 237) + '…' : cleanNote}\"\n"
            : ''
        def body = """\
Hi ${displayName ?: 'there'},

The seller came back with a counter-offer.

  · Item:               ${itemName ?: 'your offer'}
  · Your offer:         ${yourOffer != null ? '$' + yourOffer.toPlainString() : '—'}
  · Seller's counter:   \$${sellerCounter.toPlainString()}
${noteBlock}
Accept, counter again, or walk away from:
${publicUrl}/offers

You're getting this because counter-offers are part of your trade
pipeline. Mute the "Trade activity" bucket in Profile → Email
notifications to stop these.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Offer accepted — fires from OfferService.acceptOffer when the
     *  seller accepts the buyer's offer (or the buyer accepts the
     *  seller's counter). Confirms the trade ID and the 3-day escrow
     *  timeline so the buyer knows when their wallet hold drops if
     *  the seller goes silent. Gated on the TRADES bucket. */
    void sendOfferAccepted(String toEmail, String displayName, String itemName,
                           BigDecimal amount, Long tradeId) {
        if (!toEmail || amount == null) return
        def subject = "Offer accepted · ${itemName ?: 'your trade'} · trade #${tradeId ?: '—'}"
        def body = """\
Hi ${displayName ?: 'there'},

Your ${usd(amount)} offer on ${itemName ?: 'the listing'} was accepted.
A trade has been opened${tradeId ? " — #${tradeId}" : ''} and ${usd(amount)} is now in escrow.

What happens next:
  · The seller has 3 days to send you the Steam trade offer.
  · Confirm receipt in Trades to release escrow to the seller.
  · If the seller goes silent, your wallet is auto-refunded after 3 days.

Open the trade${tradeId ? " (#${tradeId})" : ''} from:
${publicUrl}/profile?tab=trades

You're getting this because offers are part of your trade pipeline.
Mute the "Trade activity" bucket in Profile → Email notifications to
stop these.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Offer rejected — fires from OfferService.rejectOffer when a
     *  seller declines the buyer's offer. Surfaces the optional
     *  seller-supplied reason so the buyer knows whether to re-offer
     *  at a higher amount or move on. Gated on the TRADES bucket. */
    void sendOfferRejected(String toEmail, String displayName, String itemName,
                           BigDecimal amount, String sellerReply) {
        if (!toEmail || amount == null) return
        def subject = "Offer declined · ${itemName ?: 'listing'}"
        def cleanReply = sellerReply?.trim() ?: null
        def replyBlock = cleanReply
            ? "\nSeller's note:\n  \"${cleanReply.length() > 240 ? cleanReply.substring(0, 237) + '…' : cleanReply}\"\n"
            : ''
        def body = """\
Hi ${displayName ?: 'there'},

The seller declined your ${usd(amount)} offer on ${itemName ?: 'their listing'}.
${replyBlock}
If you still want the item, head back to the listing and try a higher
offer or use Buy Now:
${publicUrl}/offers

You're getting this because offers are part of your trade pipeline.
Mute the "Trade activity" bucket in Profile → Email notifications to
stop these.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Saved-search match. Fires from SavedSearchService when a fresh
     *  listing matches one of the user's persisted presets (batch 266 +
     *  267). Low-priority engagement email — gated on the MATCHES
     *  bucket so a power user with 10 narrow presets can opt out
     *  without losing trade / auction emails. */
    void sendSavedSearchMatch(String toEmail, String displayName, String presetName,
                              String itemName, BigDecimal price, String itemUrl) {
        if (!toEmail || presetName == null) return
        def subject = "Match for \"${presetName}\" · ${itemName ?: 'new listing'}"
        def body = """\
Hi ${displayName ?: 'there'},

A fresh listing just matched your saved search "${presetName}".

  · ${itemName ?: 'New item'}${price != null ? ' — \$' + price.toPlainString() : ''}

View the listing${itemUrl ? ': ' + itemUrl : '.'}

You're getting this because you saved this search on SkinBox. Manage or
delete the preset from the marketplace search bar's Saved Searches
dropdown, or mute the "Saved-search matches" email category in
Profile → Email notifications.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Deletion-request receipt. Fires from ProfileController when a
     *  user first submits a deletion request — confirms the request
     *  landed + gives them a cancel window. */
    void sendDeletionRequested(String toEmail, String displayName) {
        if (!toEmail) return
        def subject = 'Your SkinBox deletion request was received'
        def body = """\
Hi ${displayName ?: 'there'},

We've received your request to delete your SkinBox account. Staff will
review + finalise within 1-2 business days.

If you change your mind, you can cancel the request before finalisation
from Profile → Personal → Delete account → Cancel deletion request.

What happens on finalise:
  · Display name, avatar, email, trade URL cleared.
  · Two-factor authentication wiped.
  · Account locked — no future sign-in.
  · Listings, trades, and transaction history stay for audit records.

If you didn't request this, cancel the request immediately and open a
support ticket — someone may have accessed your account.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Account-reinstated notice. Fires from AdminService.unbanUser. */
    void sendAccountUnbanned(String toEmail, String displayName) {
        if (!toEmail) return
        def subject = 'Your SkinBox account is active again'
        def body = """\
Hi ${displayName ?: 'there'},

Your SkinBox account has been reinstated and you can sign in as normal.
Listings you had cancelled during the suspension were not auto-restored —
you'll need to re-list anything you want back on the market.

Thanks for your patience.

— The SkinBox team
""".stripIndent()
        send(toEmail, subject, body)
    }

    /** Dedicated executor for SMTP sends (batch 508). Previously every
     *  `send()` call blocked the Tomcat worker that triggered it — a
     *  10s SMTP timeout during account-creation / ban / withdrawal
     *  meant 10s of HTTP hang for the user AND one less worker
     *  available to serve concurrent requests. Now the caller returns
     *  immediately and the actual `mailSender.send` runs on this pool.
     *
     *  @Async isn't used directly on `send()` because all the public
     *  `sendXxx` methods on this class are self-callers — the Spring
     *  proxy doesn't interpose on intra-class calls, so @Async would
     *  silently stay synchronous. Using an explicit executor sidesteps
     *  the proxy-gotcha and gets the offload behaviour regardless of
     *  how the callers structure their chains.
     *
     *  Bounded pool (4 threads) + 256-capacity queue keeps memory
     *  predictable under burst load. CallerRuns policy means an
     *  overflow falls back to synchronous send rather than dropping
     *  the message silently — better to slow-down than silently lose
     *  a ban/withdrawal email. */
    private final java.util.concurrent.ThreadPoolExecutor smtpExecutor =
        new java.util.concurrent.ThreadPoolExecutor(
            2, 4, 60L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<Runnable>(256),
            new java.util.concurrent.ThreadFactory() {
                private final java.util.concurrent.atomic.AtomicLong idx = new java.util.concurrent.atomic.AtomicLong(0)
                @Override
                Thread newThread(Runnable r) {
                    def t = new Thread(r, "smtp-${idx.incrementAndGet()}")
                    t.daemon = true
                    return t
                }
            },
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy())

    /** In-flight task counter — incremented when a send job is submitted,
     *  decremented inside the runnable's finally block. ThreadPoolExecutor's
     *  activeCount + queue.isEmpty() probe is racy: a worker can dequeue
     *  a task before its activeCount is bumped, leaving a brief window
     *  where awaitSmtpForTests sees both 0 and returns prematurely (the
     *  send fires *after* the test's then-block runs, so Spock records 0
     *  invocations even though the email actually went out). This counter
     *  closes that window — increment happens on the submitter thread
     *  before submit() returns, decrement happens inside the worker's
     *  finally so a thrown exception still releases the wait. */
    private final java.util.concurrent.atomic.AtomicInteger smtpPending =
        new java.util.concurrent.atomic.AtomicInteger(0)

    /** Test hook (batch 508) — waits for any pending SMTP work to drain
     *  so tests can assert on mock invocations without sleeping.
     *  Package-private in spirit; public because Groovy Spec inheritance
     *  needs the visibility and there's no harm exposing a synchronous
     *  no-op for production callers. */
    void awaitSmtpForTests() {
        long deadline = System.currentTimeMillis() + 5000L
        while (System.currentTimeMillis() < deadline) {
            if (smtpPending.get() == 0
                    && smtpExecutor.activeCount == 0
                    && smtpExecutor.queue.isEmpty()) return
            Thread.sleep(5)
        }
    }

    /** RFC 5322 says subjects "should not" exceed 998 octets; in practice
     *  mail clients truncate at ~78 visual chars and many SMTP relays
     *  silently reject anything over a few KB. We cap at 200 chars so a
     *  user-supplied display name or item name like
     *  `${displayName} ${itemName}` (both potentially attacker-controlled
     *  via Steam display name) can't push the line past what the relay
     *  will accept AND can't be used to amplify the payload size of an
     *  outbound mail. Truncation appends "…" so the reader sees the cut. */
    static final int MAX_SUBJECT_CHARS = 200

    /** Hard cap on the body length BEFORE we append the boilerplate
     *  footer. A user with a 10MB display name could otherwise drive a
     *  >10MB outbound mail, choking the SMTP relay and burning quota at
     *  ~1k bytes per legit email. 64 KiB is well above every legitimate
     *  SkinBox template (longest is ~2 KiB) and below the 64KB
     *  defaults many providers (Postmark, Mailgun) reject at. */
    static final int MAX_BODY_CHARS = 64 * 1024

    /** Truncate the given string to `cap` chars, appending an ellipsis
     *  if the cut actually fired. Returns the original when null/empty
     *  or already inside the cap — no allocation in the common case. */
    private static String cap(String s, int cap) {
        if (s == null) return s
        if (s.length() <= cap) return s
        return s.substring(0, cap - 1) + '…'
    }

    /** Mask the local-part of an email for log output: `voladimir1617@gmail.com`
     *  → `v***@gmail.com`. Stops the audit log line from being a
     *  PII-leaking exfil target if a logs index is ever exposed (Datadog
     *  search, Elastic snapshot, etc). Falls back to the raw value if
     *  the address doesn't contain `@` so an admin can still grep for
     *  a malformed token. */
    private static String maskEmail(String e) {
        if (!e) return e
        int at = e.indexOf('@')
        if (at <= 0) return e
        // Substring (1 char) rather than charAt → in Groovy a bare char
        // primitive does not have a String `+` overload, so charAt would
        // throw `No signature of method: Character.plus`. The substring
        // form costs one extra String alloc and works on every JVM.
        return e.substring(0, 1) + '***' + e.substring(at)
    }

    /** Pre-compiled regex matching anything that looks like an email
     *  address embedded in a free-form string. Used by
     *  {@link #scrubEmails} to redact PII from exception messages before
     *  they hit the log. The pattern is intentionally a bit broader than
     *  RFC 5322 so a non-conforming address quoted back by an SMTP relay
     *  (e.g. `<Foo Bar@example.com>`) still gets caught. */
    private static final java.util.regex.Pattern EMAIL_IN_TEXT =
        java.util.regex.Pattern.compile(
            /[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}/)

    /** Strip every email-address-looking substring from a free-form
     *  string, replacing each match with the same `x***@domain` mask
     *  {@link #maskEmail} produces for the recipient log line.
     *
     *  Why this exists: when an SMTP send fails, the underlying
     *  {@code jakarta.mail.SendFailedException} / Spring
     *  {@code MailSendException} embeds the failing recipient(s) in
     *  the exception message (e.g. `550 5.1.1 <user@example.com>:
     *  Recipient address rejected`). The retry / final-failure log
     *  lines mask the {@code to} arg via {@link #maskEmail} but the
     *  exception message immediately undid that masking, leaking the
     *  full address into ops logs — exactly the PII vector the
     *  recipient-mask exists to defend against. Run the exception
     *  message through this scrubber before logging it. */
    private static String scrubEmails(String s) {
        if (s == null || s.isEmpty()) return s
        if (s.indexOf('@') < 0) return s
        def m = EMAIL_IN_TEXT.matcher(s)
        def sb = new StringBuffer(s.length())
        while (m.find()) {
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(maskEmail(m.group())))
        }
        m.appendTail(sb)
        return sb.toString()
    }

    /** Per-process dedupe ledger (batch 1102). If a service races itself
     *  — e.g. PurchaseService fires `sendPurchaseReceipt` twice because
     *  a retry handler kicked in before the original committed, or a
     *  webhook delivers the same Stripe event back-to-back — we'd
     *  otherwise send the user two identical emails. Key is a hash of
     *  (to,subject,body); a 60-second TTL is more than enough to swallow
     *  any sane retry storm while still letting genuine resends (e.g. a
     *  user requesting a fresh verification email) get through. Bounded
     *  to ~4096 entries so a long-running prod node doesn't accumulate
     *  unbounded memory. */
    private final java.util.Map<String, Long> recentSendKeys =
        java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, Long>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> e) {
                    return size() > 4096
                }
            } as java.util.LinkedHashMap<String, Long>)

    private static final long DEDUPE_WINDOW_MS = 60_000L

    /** Returns true the first time we see this (to,subject,body) tuple
     *  inside the dedupe window; false on a duplicate, in which case
     *  the caller drops the send. SHA-256 keeps the key small (44 chars
     *  base64) regardless of body length so the ledger memory stays
     *  bounded even when an attacker passes a huge body. */
    private boolean firstSeen(String to, String subject, String body) {
        String key
        try {
            def md = java.security.MessageDigest.getInstance('SHA-256')
            md.update((to ?: '').getBytes('UTF-8'))
            md.update((byte) 0)
            md.update((subject ?: '').getBytes('UTF-8'))
            md.update((byte) 0)
            md.update((body ?: '').getBytes('UTF-8'))
            key = java.util.Base64.urlEncoder.withoutPadding().encodeToString(md.digest())
        } catch (Exception ignore) {
            // If MessageDigest is unavailable for any reason, fail open
            // — better to risk a rare duplicate than to silently drop a
            // legitimate email under crypto-runtime weirdness.
            return true
        }
        long now = System.currentTimeMillis()
        synchronized (recentSendKeys) {
            Long seen = recentSendKeys.get(key)
            if (seen != null && (now - seen) < DEDUPE_WINDOW_MS) {
                return false
            }
            recentSendKeys.put(key, now)
            return true
        }
    }

    /** Number of attempts (1 = no retry) the worker makes for a single
     *  send before giving up. 3 attempts handles the common transient
     *  pattern (TCP RST, brief relay 4xx, DNS hiccup) without taking
     *  the smtp-N worker offline for too long when the relay is genuinely
     *  down. */
    private static final int SMTP_MAX_ATTEMPTS = 3

    /** Initial backoff between attempts; doubles on each retry. Capped
     *  small enough that the worker thread isn't held up for the duration
     *  of a multi-minute relay outage but long enough that a TCP RST
     *  doesn't immediately re-RST. */
    private static final long SMTP_INITIAL_BACKOFF_MS = 100L
    private static final long SMTP_MAX_BACKOFF_MS = 800L

    /** Generic sender — used by sendVerification plus any future one-off.
     *  Offloads the mailSender.send() call to the smtpExecutor so the
     *  caller doesn't pay the SMTP round-trip on its HTTP worker thread.
     *
     *  Batch 892 — upgraded from SimpleMailMessage to MimeMessage so we
     *  can attach `List-Unsubscribe` + `List-Unsubscribe-Post` headers
     *  (RFC 8058). Gmail / Outlook / Fastmail surface a dedicated
     *  "Unsubscribe" button in the message header when these are set.
     *  Falls back to no-unsubscribe-header on messages where the
     *  recipient email lookup fails (defense: don't block the send).
     *
     *  Batch 1102 — defends against four delivery-bug classes the audit
     *  surfaced:
     *    1. Transient SMTP failure → bounded retry with exponential
     *       backoff. Previously a 1-second TCP blip during account
     *       creation meant the verification email was lost forever.
     *    2. Retry without backoff (would tight-loop a hung relay) →
     *       SMTP_INITIAL_BACKOFF_MS * 2^attempt, capped.
     *    3. Same email sent twice if a caller (or our own retry) races
     *       → SHA-256 of (to,subject,body) in `recentSendKeys` with a
     *       60s TTL drops the duplicate.
     *    4. Uncapped subject/body letting a user trigger huge mail
     *       payloads via a long Steam display name → MAX_SUBJECT_CHARS
     *       / MAX_BODY_CHARS truncate before send. */
    void send(String to, String subject, String body) {
        if (!to || !subject || !body) return
        // Length caps applied at the boundary so every template benefits
        // without having to remember to clamp at the call site. Subject
        // first (user-visible truncation matters most), then body.
        final String safeSubject = cap(subject, MAX_SUBJECT_CHARS)
        final String safeBody    = cap(body,    MAX_BODY_CHARS)
        // Dedupe BEFORE we touch the executor — saves a thread-pool
        // submit + the queue slot when a caller fires the same email
        // twice. Log the suppression so ops can trace a missing email
        // back to a duplicate caller bug.
        if (!firstSeen(to, safeSubject, safeBody)) {
            log.info("EmailService: dropping duplicate '{}' to {} (within {}ms window)",
                     safeSubject, maskEmail(to), DEDUPE_WINDOW_MS)
            return
        }
        if (smtpReady) {
            def finalBody = appendFooter(safeBody, to)
            def lower = to.trim().toLowerCase()
            def tok = unsubscribeToken(lower)
            def base = (publicUrl ?: 'https://skinbox.market').with { u -> u.endsWith('/') ? u[0..-2] : u }
            // Derive the unsubscribe mailto domain from the From: address.
            // Previously this was derived from publicUrl, which is
            // `http://localhost:8080` in dev and contains a port — and
            // `unsubscribe@localhost:8080` is a malformed RFC-5321
            // mailbox. The From address is always a real email so its
            // domain is always a real mail domain.
            def fromDomain = 'skinbox.market'
            if (fromAddress) {
                int at = fromAddress.indexOf('@')
                if (at > 0 && at < fromAddress.length() - 1) {
                    fromDomain = fromAddress.substring(at + 1).trim()
                }
            }
            smtpPending.incrementAndGet()
            smtpExecutor.submit({
                try {
                    int attempt = 0
                    long backoff = SMTP_INITIAL_BACKOFF_MS
                    Exception lastError = null
                    while (attempt < SMTP_MAX_ATTEMPTS) {
                        attempt++
                        try {
                            def mime = mailSender.createMimeMessage()
                            def helper = new org.springframework.mail.javamail.MimeMessageHelper(mime, 'UTF-8')
                            helper.setFrom(fromAddress)
                            helper.setTo(to)
                            helper.setSubject(safeSubject)
                            helper.setText(finalBody, false)
                            // Reply-to so users replying to a no-reply From: still
                            // route to the support inbox. Guarded against an
                            // empty/blank config so a misconfigured deploy
                            // doesn't 500 the send.
                            if (replyToAddress && !replyToAddress.isBlank()) {
                                try { helper.setReplyTo(replyToAddress) } catch (ignore) { /* fall through */ }
                            }
                            // List-Unsubscribe header per RFC 8058. The mailto
                            // half lets clients without webhook support fall
                            // back to a no-op inbound address (ignored since we
                            // also surface the URL). The https half is what
                            // Gmail/Outlook actually click. One-Click-Post
                            // tells the client it can POST to the URL without
                            // user confirmation — the endpoint is idempotent
                            // and validates the HMAC before doing anything.
                            if (tok) {
                                def enc = java.net.URLEncoder.encode(lower, 'UTF-8')
                                def url = "${base}/api/unsubscribe?email=${enc}&t=${tok}"
                                mime.setHeader('List-Unsubscribe',
                                    "<mailto:unsubscribe@${fromDomain}?subject=unsubscribe>, <${url}>")
                                mime.setHeader('List-Unsubscribe-Post', 'List-Unsubscribe=One-Click')
                            }
                            mailSender.send(mime)
                            // PII-conscious audit line — mask the recipient
                            // so a logs index doesn't double as a customer
                            // email DB. Subject stays unmasked so ops can
                            // grep for the template name + truncated bodies.
                            log.info("EmailService: sent '{}' to {} (attempt {}/{})",
                                     safeSubject, maskEmail(to), attempt, SMTP_MAX_ATTEMPTS)
                            return // success — break out of the retry loop
                        } catch (Exception e) {
                            lastError = e
                            if (attempt < SMTP_MAX_ATTEMPTS) {
                                // Scrub recipient PII from the exception
                                // message before logging — Spring /
                                // jakarta.mail embed the failing address in
                                // the message, which would otherwise undo
                                // the maskEmail() on the `to` arg.
                                log.warn("EmailService: SMTP send attempt {}/{} failed for {} — {}: {}; retrying in {}ms",
                                         attempt, SMTP_MAX_ATTEMPTS, maskEmail(to),
                                         e.class.simpleName, scrubEmails(e.message), backoff)
                                try { Thread.sleep(backoff) } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt()
                                    break
                                }
                                backoff = Math.min(backoff * 2L, SMTP_MAX_BACKOFF_MS)
                            }
                        }
                    }
                    // Exhausted the retry budget — log loudly so ops sees
                    // the user-impacting failure. The dedupe ledger keeps
                    // the failed-send slot for the full TTL, which means a
                    // caller-side retry within 60s is also suppressed; that
                    // is intentional, the caller should not be retrying a
                    // dead relay. After the TTL expires a fresh attempt
                    // will be allowed.
                    // Recipient PII scrubbed from the exception message
                    // and the stack-trace arg dropped entirely (the stack
                    // includes the recipient via jakarta.mail's
                    // SendFailedException.toString()). Class name +
                    // scrubbed message still let ops diagnose the relay
                    // failure without leaking customer addresses into a
                    // logs index.
                    log.error("EmailService: SMTP send FAILED for {} after {} attempts — {}: {}",
                              maskEmail(to), SMTP_MAX_ATTEMPTS,
                              lastError?.class?.simpleName,
                              scrubEmails(lastError?.message))
                    // Never throw from the email path — a broken relay must not
                    // 500 the caller (account creation, password reset). The
                    // token is still persisted, the user can retry, and ops
                    // will see the error in the log.
                } finally {
                    smtpPending.decrementAndGet()
                }
            } as Runnable)
        } else {
            log.info("[email/log-sink] to={} subject={}\n---\n{}\n---",
                     maskEmail(to), safeSubject, appendFooter(safeBody, to))
        }
    }

    /**
     * Deterministic one-click-unsubscribe token for a recipient email.
     * Batch 891 — embedded in every footer so Gmail / Outlook / Fastmail
     * show their built-in unsubscribe UI, AND so the /api/unsubscribe/
     * endpoint can validate without a session (the user reads the email
     * from a different device than the one they signed up on, or they
     * don't have login cookies anymore, etc).
     *
     * Token = base64url(HMAC-SHA256(unsubscribeSecret, lowercased-email)).
     * Deterministic (no timestamp) so the same email always produces the
     * same link — simpler ops, no DB storage, revocation is by rotating
     * the secret. Side effect: an old leaked link keeps working until
     * secret rotation; the trade-off is worth it for the simplicity.
     */
    String unsubscribeToken(String emailLower) {
        if (!emailLower) return null
        try {
            def mac = javax.crypto.Mac.getInstance('HmacSHA256')
            mac.init(new javax.crypto.spec.SecretKeySpec(
                (unsubscribeSecret ?: '').getBytes('UTF-8'), 'HmacSHA256'))
            def raw = mac.doFinal(emailLower.getBytes('UTF-8'))
            // URL-safe base64 without padding — short enough to fit in a
            // mail footer without wrapping.
            return java.util.Base64.urlEncoder.withoutPadding().encodeToString(raw)
        } catch (Exception e) {
            log.warn("unsubscribeToken compute failed: ${e.message}")
            return null
        }
    }

    /** Verify a (email, token) pair. Constant-time compare. */
    boolean verifyUnsubscribeToken(String emailLower, String token) {
        if (!emailLower || !token) return false
        def expected = unsubscribeToken(emailLower)
        if (expected == null) return false
        // MessageDigest.isEqual is constant-time-ish and handles different
        // length strings without early-returning.
        try {
            return java.security.MessageDigest.isEqual(
                expected.getBytes('UTF-8'), token.getBytes('UTF-8'))
        } catch (Exception ignore) { return false }
    }

    /**
     * Appends a consistent preferences/help/copyright footer to every
     * outgoing email body. CAN-SPAM / GDPR best practice: every
     * engagement email must surface how to turn it off. Operational
     * emails keep the footer too so the format stays uniform and the
     * recipient always has a path back to account settings. Guarded so
     * an unexpected publicUrl or date-format failure never throws out
     * of the email path (send() already swallows send errors, but a
     * crash inside footer-build would leak up before the try block).
     */
    private String appendFooter(String body, String to = null) {
        try {
            def base = publicUrl ?: 'https://skinbox.market'
            if (base.endsWith('/')) base = base.substring(0, base.length() - 1)
            // Batch 891/892 — one-click unsubscribe link per recipient.
            // Per CAN-SPAM + RFC 8058: the recipient must have a way to
            // opt out without logging in. The URL is deterministic per
            // email (HMAC-signed; see unsubscribeToken) so we don't need
            // DB storage. Clicking it flips emailNotificationsEnabled
            // without auth. Gmail/Outlook surface a dedicated
            // "Unsubscribe" button via the List-Unsubscribe header set
            // by send() above; the plain-text footer URL is the
            // explicit fallback for mail clients that don't render the
            // RFC-8058 button.
            String unsubLine = ''
            if (to) {
                def lower = to.trim().toLowerCase()
                def tok = unsubscribeToken(lower)
                if (tok) {
                    def enc = java.net.URLEncoder.encode(lower, 'UTF-8')
                    unsubLine = "\nOne-click unsubscribe: " + base +
                                "/api/unsubscribe?email=" + enc + "&t=" + tok
                }
            }
            // Footer surfaces the unsubscribe (CAN-SPAM), preferences,
            // support path, and the trademark/affiliation disclaimer.
            // The disclaimer is required because s&box is a Facepunch
            // game — every email mentions skin trading and Facepunch
            // hasn't endorsed this marketplace, so we say so explicitly.
            return body + "\n\n—\nManage your email preferences: " + base + "/settings" +
                   unsubLine +
                   "\nNeed help? Reply to this email or visit " + base + "/support" +
                   "\n\n© 2026 SkinBox · Not affiliated with Facepunch\n"
        } catch (Exception ignore) {
            return body
        }
    }

    /** Exposed so ProfileController can decide whether to echo tokens
     *  back in the HTTP response (dev aid) or not (prod). */
    boolean isSmtpReady() { smtpReady }
}
