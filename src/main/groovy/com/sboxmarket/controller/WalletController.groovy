package com.sboxmarket.controller

import com.sboxmarket.dto.request.DepositRequest
import com.sboxmarket.dto.request.WithdrawRequest
import com.sboxmarket.exception.InsufficientBalanceException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.Transaction
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.StripeService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/wallet")
@Slf4j
class WalletController {

    @Autowired WalletRepository walletRepository
    @Autowired TransactionRepository transactionRepository
    @Autowired SteamUserRepository steamUserRepository
    @Autowired StripeService stripeService
    @Autowired com.sboxmarket.service.TotpService totpService

    /** Rolling 24-hour withdrawal cap. Sum of PENDING + COMPLETED
     *  withdrawals in any 24h window — protects against compromised
     *  accounts draining the full wallet balance before the owner
     *  notices. Configurable via env so ops can tune during live
     *  incidents. Default $5,000 matches CSFloat's published policy
     *  and keeps the happy path (power sellers cashing out weekly
     *  earnings) unaffected. */
    @org.springframework.beans.factory.annotation.Value('${wallet.daily-withdrawal-cap:5000}')
    BigDecimal dailyWithdrawalCap

    /** Demo fallback — used when no one is logged in so the marketplace stays browsable. */
    private static final Long DEMO_WALLET_ID = 1L

    /** Resolve the wallet for the current session, falling back to the demo wallet. */
    @Transactional
    protected Wallet currentWallet(HttpServletRequest req) {
        def userId = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (userId != null) {
            def user = steamUserRepository.findById(userId).orElse(null)
            if (user != null) {
                def w = walletRepository.findByUsername("steam_" + user.steamId64)
                if (w == null) {
                    w = walletRepository.save(new Wallet(
                        username: "steam_" + user.steamId64,
                        balance : BigDecimal.ZERO
                    ))
                }
                return w
            }
        }
        walletRepository.findById(DEMO_WALLET_ID).orElse(null)
    }

    protected SteamUser currentUser(HttpServletRequest req) {
        def userId = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (userId == null) return null
        steamUserRepository.findById(userId).orElse(null)
    }

    @GetMapping
    ResponseEntity<Map> getWallet(HttpServletRequest req) {
        def user = currentUser(req)
        // Batch 976 — anonymous callers get a zero-balance snapshot
        // instead of the persisted demo wallet row. Previously the
        // controller fell through to `walletRepository.findById(1L)`
        // — the seeded demo wallet — and returned its id + live
        // balance + daily-cap usage. That leaked the demo wallet's
        // state to every logged-out visitor (and the "$250" balance
        // confused real users who had just signed out: the hero chip
        // re-populated with the demo number instead of going to $0).
        // Keeping the endpoint responsive for anonymous polling (the
        // SPA hero + nav chip call it on every page load) matters
        // more than a 401 here — the frontend already gates write
        // actions on `me != null`, so a safe read with zeroed state
        // is the right middle ground.
        if (user == null) {
            return ResponseEntity.ok([
                id:                     null,
                username:               null,
                avatarUrl:              null,
                loggedIn:               false,
                balance:                BigDecimal.ZERO,
                currency:               'USD',
                stripeLive:             stripeService.isLive(),
                pendingWithdrawAmt:     BigDecimal.ZERO,
                pendingWithdrawCt:      0,
                pendingDepositAmt:      BigDecimal.ZERO,
                pendingDepositCt:       0,
                dailyWithdrawCap:       dailyWithdrawalCap,
                dailyWithdrawUsed:      BigDecimal.ZERO,
                dailyWithdrawRemaining: dailyWithdrawalCap ?: BigDecimal.ZERO,
                dailyWithdrawOldestAt:  null,
                disputeHoldCount:       0L,
                dailyDepositCap:        stripeService.dailyDepositCap ?: BigDecimal.ZERO,
                dailyDepositUsed:       BigDecimal.ZERO,
                dailyDepositRemaining:  stripeService.dailyDepositCap ?: BigDecimal.ZERO,
                dailyDepositOldestAt:   null,
                frozen:                 false,
                frozenReason:           null,
                frozenAt:               null
            ])
        }
        def wallet = currentWallet(req)
        if (wallet == null) return ResponseEntity.notFound().build()
        // Compute in-flight totals so the wallet hero can render a
        // "WITHDRAWAL PENDING · $X" chip. Without this the user saw their
        // balance drop on request and had no feedback that a payout was
        // actually scheduled.
        def pendingRows = transactionRepository.findPendingByWallet(wallet.id)
        def pendingWithdrawAmt = pendingRows
            .findAll { it.type == 'WITHDRAW' || it.type == 'WITHDRAWAL' }
            .inject(BigDecimal.ZERO) { sum, t -> sum + (t.amount ?: BigDecimal.ZERO) }
        def pendingDepositAmt = pendingRows
            .findAll { it.type == 'DEPOSIT' }
            .inject(BigDecimal.ZERO) { sum, t -> sum + (t.amount ?: BigDecimal.ZERO) }
        // 24h withdrawal-cap usage (batch 357) — exposes how much of
        // the rolling daily cap the user has already committed so the
        // Withdraw form can render "You have $X remaining today" up
        // front instead of surfacing a WITHDRAW_DAILY_CAP error only
        // at submit time.
        def since = System.currentTimeMillis() - (24L * 60L * 60L * 1000L)
        def used24h = transactionRepository.sumWithdrawalsSince(wallet.id, since) ?: BigDecimal.ZERO
        def remaining24h = (dailyWithdrawalCap ?: BigDecimal.ZERO) - used24h
        if (remaining24h < BigDecimal.ZERO) remaining24h = BigDecimal.ZERO
        // Earliest row in the 24h window — lets the UI compute a real
        // "cap resets in XhYm" countdown instead of a blanket "try in 24h"
        // (batch 753). Null when the user has no withdrawals in-window.
        def oldestWithdrawalAt = transactionRepository.earliestWithdrawalSince(wallet.id, since)
        // Active-chargeback hold count (batch 465) — exposes the
        // active-disputed-deposits count so the Withdraw form can
        // render a "Withdrawals paused" notice up front instead of
        // surfacing the WITHDRAW_DISPUTE_HOLD error only at submit.
        long disputeHoldCount = transactionRepository.countActiveDisputedDeposits(wallet.id)
        // 24h deposit-cap usage (batch 497) — mirrors the withdrawal
        // cap surfacing so the Deposit modal can show "You have $X
        // remaining today" before the user hits submit instead of
        // failing the Checkout session creation with DEPOSIT_DAILY_CAP.
        def depositUsed24h = transactionRepository.sumDepositsSince(wallet.id, since) ?: BigDecimal.ZERO
        def depositCap = stripeService.dailyDepositCap ?: BigDecimal.ZERO
        def depositRemaining24h = depositCap - depositUsed24h
        if (depositRemaining24h < BigDecimal.ZERO) depositRemaining24h = BigDecimal.ZERO
        def oldestDepositAt = transactionRepository.earliestDepositSince(wallet.id, since)
        // Deliberately minimal response — we used to leak steamId64 and the
        // Stripe publishable key on every wallet fetch. publishableKey now
        // only leaves the server inside the deposit-session response, and
        // steamId64 is only returned via /api/auth/steam/me which the Profile
        // modal uses directly.
        ResponseEntity.ok([
            id:                 wallet.id,
            username:           user?.displayName ?: wallet.username,
            avatarUrl:          user?.avatarUrl,
            loggedIn:           user != null,
            balance:            wallet.balance,
            currency:           wallet.currency,
            stripeLive:         stripeService.isLive(),
            pendingWithdrawAmt: pendingWithdrawAmt,
            pendingWithdrawCt:  pendingRows.count { it.type in ['WITHDRAW','WITHDRAWAL'] },
            pendingDepositAmt:  pendingDepositAmt,
            pendingDepositCt:   pendingRows.count { it.type == 'DEPOSIT' },
            dailyWithdrawCap:   dailyWithdrawalCap,
            dailyWithdrawUsed:  used24h,
            dailyWithdrawRemaining: remaining24h,
            dailyWithdrawOldestAt: oldestWithdrawalAt,
            disputeHoldCount:   disputeHoldCount,
            dailyDepositCap:    depositCap,
            dailyDepositUsed:   depositUsed24h,
            dailyDepositRemaining: depositRemaining24h,
            dailyDepositOldestAt: oldestDepositAt,
            frozen:             Boolean.TRUE.equals(wallet.frozen),
            frozenReason:       wallet.frozenReason,
            frozenAt:           wallet.frozenAt
        ])
    }

    /** Buyer-side spending summary — mirrors the seller-earnings strip
     *  on /me/stall (batch 605) but for purchases. Returns PURCHASE
     *  totals + counts across three time windows (7d / 30d / lifetime)
     *  so the wallet hero can surface at-a-glance spending rhythm
     *  without the user scrolling the transaction ledger and
     *  hand-summing.
     *
     *  Gross price (what left the wallet), not net of refunds — refunds
     *  are logged as their own `REFUND` rows, so subtracting them would
     *  conflate concepts. If the user wants net, the transaction
     *  history exposes both. Signed-in only — anon callers get 401
     *  because spending history is PII.
     *
     *  Six indexed aggregates in one round-trip; cheap enough that the
     *  filter's `no-store` on `/api/wallet/*` is the right choice and
     *  there's no per-request caching to worry about. */
    @GetMapping("/spend")
    ResponseEntity<Map> getSpendSummary(HttpServletRequest req) {
        def userId = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (userId == null) throw new UnauthorizedException()
        def wallet = currentWallet(req)
        if (wallet == null || wallet.id == DEMO_WALLET_ID) {
            // Demo wallet never belongs to a signed-in user, so a real
            // session with no real wallet yet means "zero history".
            return ResponseEntity.ok([
                spentLifetime: BigDecimal.ZERO, purchasesLifetime: 0L,
                spent30d:      BigDecimal.ZERO, purchases30d:      0L,
                spent7d:       BigDecimal.ZERO, purchases7d:       0L,
                spent24h:      BigDecimal.ZERO, purchases24h:      0L
            ])
        }
        long now = System.currentTimeMillis()
        long window24h = now - 24L * 60L * 60L * 1000L
        long window7d  = now - 7L  * 24L * 60L * 60L * 1000L
        long window30d = now - 30L * 24L * 60L * 60L * 1000L
        // Batch 873 — buyer-side 24h window mirrors the seller-side
        // /my-stall/earnings 24h chip (batch 872). Eight aggregates in
        // one round-trip; still cheap on the partial index.
        ResponseEntity.ok([
            spentLifetime:     transactionRepository.sumByWalletAndType(wallet.id, 'PURCHASE', true) ?: BigDecimal.ZERO,
            purchasesLifetime: transactionRepository.countCompletedByWalletAndType(wallet.id, 'PURCHASE'),
            spent30d:          transactionRepository.sumCompletedByWalletTypeSince(wallet.id, 'PURCHASE', window30d) ?: BigDecimal.ZERO,
            purchases30d:      transactionRepository.countCompletedByWalletTypeSince(wallet.id, 'PURCHASE', window30d),
            spent7d:           transactionRepository.sumCompletedByWalletTypeSince(wallet.id, 'PURCHASE', window7d) ?: BigDecimal.ZERO,
            purchases7d:       transactionRepository.countCompletedByWalletTypeSince(wallet.id, 'PURCHASE', window7d),
            spent24h:          transactionRepository.sumCompletedByWalletTypeSince(wallet.id, 'PURCHASE', window24h) ?: BigDecimal.ZERO,
            purchases24h:      transactionRepository.countCompletedByWalletTypeSince(wallet.id, 'PURCHASE', window24h)
        ])
    }

    @GetMapping("/transactions")
    ResponseEntity<List<Transaction>> getTransactions(HttpServletRequest req) {
        // Batch 977 — short-circuit before falling through to the demo
        // wallet. Pre-fix, anonymous callers received the demo wallet's
        // transaction history (same leak class as batch 976's
        // `/api/wallet`). Empty today because nothing writes to the demo
        // wallet, but any future admin-credit or test seeding would
        // silently become public. Matches the auth pattern on /spend +
        // /transactions.csv.
        def userId = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (userId == null) return ResponseEntity.ok([])
        def wallet = currentWallet(req)
        if (wallet == null) return ResponseEntity.ok([])
        // SQL-level LIMIT 500 so only 500 rows leave the database.
        // Previous code loaded everything then sliced in memory.
        def txs = transactionRepository.findByWalletIdOrderByCreatedAtDesc(
            wallet.id, org.springframework.data.domain.PageRequest.of(0, 500))
        ResponseEntity.ok(txs)
    }

    /** CSV export of the signed-in user's transaction history. Intended
     *  for tax / accounting use — each row is a single ledger entry so
     *  the file is import-ready for spreadsheets. Capped at 5000 rows;
     *  users with more history can open a support ticket for a full
     *  dump.
     *
     *  Batch 642: optional `?month=YYYY-MM` parameter scopes the export
     *  to a single calendar month (UTC). The UI exposes this when the
     *  Transactions tab has a month filter active — users doing tax
     *  work can download just the month they need instead of the full
     *  history. Month is parsed strictly; a malformed value falls
     *  through to the full-history export so a bookmarked URL never
     *  silently 400s.
     */
    @GetMapping(value = "/transactions.csv", produces = "text/csv")
    ResponseEntity<String> exportTransactionsCsv(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String month,
            HttpServletRequest req) {
        def user = currentUser(req)
        if (user == null) throw new UnauthorizedException("Sign in to export your transactions")
        def wallet = currentWallet(req)
        def rows = wallet == null ? [] : transactionRepository.findByWalletIdOrderByCreatedAtDesc(
            wallet.id, org.springframework.data.domain.PageRequest.of(0, 5000))

        // Month-scope filter (batch 642). YYYY-MM → UTC [start, end)
        // of the month. Invalid strings silently drop back to no-filter
        // so a mistyped bookmark still returns the full history.
        Long startMs = null, endMs = null
        String monthSuffix = ''
        if (month != null && month.matches(/^\d{4}-(0[1-9]|1[0-2])$/)) {
            def parts = month.split('-')
            int y = Integer.parseInt(parts[0])
            int m = Integer.parseInt(parts[1])
            def cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(y, m - 1, 1, 0, 0, 0)
            startMs = cal.timeInMillis
            cal.add(java.util.Calendar.MONTH, 1)
            endMs = cal.timeInMillis
            monthSuffix = "-${month}"
            rows = rows.findAll { tx ->
                def t = (tx.createdAt ?: 0L) as long
                t >= startMs && t < endMs
            }
        }
        def sb = new StringBuilder()
        // Leading metadata row makes it explicit that every numeric value
        // in this CSV is denominated in USD raw, regardless of the user's
        // selected display currency in the SPA. An accountant importing
        // the file into a multi-currency spreadsheet can see the source
        // currency at a glance and apply their own FX conversion if their
        // ledger is in another currency. The amount column header also
        // carries "(USD)" so a row-level scan still surfaces the unit.
        sb.append("# currency: USD\n")
        sb.append("id,date,type,status,amount (USD),currency,description,listingId,reference\n")
        def df = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        df.timeZone = java.util.TimeZone.getTimeZone("UTC")
        // Batch 978 — shared csv-safe escape lives in com.sboxmarket.util.CsvUtil.
        // Defends against OWASP CSV injection by prefixing formula-trigger
        // first chars (= + - @ \t \r) with a single quote so Excel /
        // LibreOffice / Google Sheets render as literal text.
        def csvEscape = com.sboxmarket.util.CsvUtil.&safeCell
        rows.each { tx ->
            sb.append(tx.id ?: '').append(',')
              .append(df.format(new Date(tx.createdAt ?: 0))).append(',')
              .append(csvEscape(tx.type ?: '')).append(',')
              .append(csvEscape(tx.status ?: '')).append(',')
              .append(tx.amount?.toPlainString() ?: '').append(',')
              .append(csvEscape(tx.currency ?: '')).append(',')
              .append(csvEscape(tx.description ?: '')).append(',')
              .append(tx.listingId ?: '').append(',')
              .append(csvEscape(tx.stripeReference ?: '')).append('\n')
        }
        def filename = "skinbox-transactions${monthSuffix}-${df.format(new Date()).replace(':','-')}.csv"
        return ResponseEntity.ok()
            .header('Content-Disposition', "attachment; filename=\"${filename}\"")
            .header('Content-Type', 'text/csv; charset=utf-8')
            .header('Cache-Control', 'no-store')
            .body(sb.toString())
    }

    @PostMapping("/deposit")
    ResponseEntity<Map> deposit(@Valid @RequestBody DepositRequest body, HttpServletRequest req) {
        // Anonymous callers fall through `currentWallet()` to the demo
        // wallet — the anonymous-browsable marketplace is a deliberate UX
        // choice, but deposits are a real money-in flow that MUST be
        // tied to an actual user. Gate on `currentUser(req)` first so the
        // Stripe Checkout Session always targets a real wallet, never
        // the demo id.
        def user = currentUser(req)
        if (user == null) throw new UnauthorizedException("Sign in to deposit")
        def wallet = currentWallet(req)
        if (wallet == null) throw new UnauthorizedException("Sign in to deposit")
        // Wallet freeze gate (batch 509). Refuses money-in on a staff-
        // frozen wallet. Same pattern as the dispute-hold gate on
        // withdraw — fail loud with a specific code so the UI can
        // render a clear banner instead of a generic error.
        if (Boolean.TRUE.equals(wallet.frozen)) {
            throw new com.sboxmarket.exception.BadRequestException("WALLET_FROZEN",
                "Your wallet is frozen by staff" +
                    (wallet.frozenReason ? ": ${wallet.frozenReason}" : '') +
                    ". Open a support ticket to resolve.")
        }
        def result = stripeService.createDepositSession(wallet.id, body.amount)
        ResponseEntity.ok(result)
    }

    @PostMapping("/withdraw")
    @Transactional
    ResponseEntity<Map> withdraw(@Valid @RequestBody WithdrawRequest body, HttpServletRequest req) {
        def user = currentUser(req)
        if (user == null) throw new UnauthorizedException("Sign in to withdraw")
        def wallet = currentWallet(req)
        if (wallet == null) throw new UnauthorizedException("Sign in to withdraw")

        // Wallet freeze gate (batch 509). Mirror of the deposit freeze
        // check — both money paths refuse while the wallet is frozen.
        if (Boolean.TRUE.equals(wallet.frozen)) {
            throw new com.sboxmarket.exception.BadRequestException("WALLET_FROZEN",
                "Your wallet is frozen by staff" +
                    (wallet.frozenReason ? ": ${wallet.frozenReason}" : '') +
                    ". Open a support ticket to resolve.")
        }

        // Email verification gate. Withdrawals are the most sensitive
        // money-out flow on the platform — gating them on a verified
        // email address gives us a recovery channel for disputes,
        // feeds the rejection-email notification pipeline, and raises
        // the bar for account-takeover fraud. Admin-initiated manual
        // payouts can still bypass this via the approveWithdrawal
        // path (they go through the admin panel, not /api/wallet/withdraw).
        if (!user.email) {
            throw new com.sboxmarket.exception.BadRequestException("EMAIL_REQUIRED",
                "Add an email address on your profile before requesting a withdrawal — we use it to confirm payout details and flag disputes.")
        }
        if (!Boolean.TRUE.equals(user.emailVerified)) {
            throw new com.sboxmarket.exception.BadRequestException("EMAIL_NOT_VERIFIED",
                "Verify your email address before requesting a withdrawal. Check Profile → Personal Info for the verify link.")
        }

        // If the user has 2FA enabled, require a fresh 6-digit code on the
        // request. This is our second-factor gate on the most sensitive
        // money-out flow — session cookies alone are not enough.
        if (user.totpSecret) {
            def code = (body.totpCode as String ?: '').trim()
            if (!code) {
                throw new com.sboxmarket.exception.BadRequestException("TOTP_REQUIRED",
                    "Two-factor code required for withdrawals")
            }
            def step = totpService.verify(user.totpSecret, code, user.lastTotpStep)
            if (step < 0) {
                throw new com.sboxmarket.exception.BadRequestException("TOTP_INVALID",
                    "Invalid or reused 2FA code")
            }
            user.lastTotpStep = step
            steamUserRepository.save(user)
        }

        if (wallet.balance < body.amount) {
            throw new InsufficientBalanceException(body.amount, wallet.balance)
        }
        // Active-chargeback gate (batch 465). When Stripe has flagged any
        // deposit on this wallet as DISPUTED, refuse new withdrawals until
        // staff resolve the chargeback. Otherwise the attacker pattern is
        // trivial: deposit on a stolen card, dispute via the bank,
        // withdraw the credit before we notice the chargeback notification.
        // Cleared once staff manually transitions the DISPUTED row in the
        // admin panel.
        long disputed = transactionRepository.countActiveDisputedDeposits(wallet.id)
        if (disputed > 0) {
            throw new com.sboxmarket.exception.BadRequestException("WITHDRAW_DISPUTE_HOLD",
                "Withdrawals are paused while you have ${disputed} unresolved deposit dispute${disputed == 1 ? '' : 's'} on file. " +
                "Open a support ticket if you believe this is a mistake.")
        }
        // Rolling 24-hour withdrawal cap (batch 357). Sum existing
        // PENDING + COMPLETED withdrawals from the last 24h and reject
        // if adding this request would exceed the configured cap. This
        // is the fraud ceiling on compromised-account drain attacks —
        // even if an attacker gets the session cookie AND the 2FA code,
        // they can't bleed the wallet past the daily cap in one session.
        if (dailyWithdrawalCap != null && dailyWithdrawalCap > BigDecimal.ZERO) {
            def since = System.currentTimeMillis() - (24L * 60L * 60L * 1000L)
            def sum = transactionRepository.sumWithdrawalsSince(wallet.id, since) ?: BigDecimal.ZERO
            if ((sum + body.amount) > dailyWithdrawalCap) {
                def remaining = dailyWithdrawalCap - sum
                if (remaining < BigDecimal.ZERO) remaining = BigDecimal.ZERO
                throw new com.sboxmarket.exception.BadRequestException("WITHDRAW_DAILY_CAP",
                    "Daily withdrawal cap of \$${dailyWithdrawalCap.toPlainString()} would be exceeded. " +
                    "\$${sum.toPlainString()} already requested in the last 24h · \$${remaining.toPlainString()} available. " +
                    "Try again in 24h or contact support for a manual payout.")
            }
        }
        def tx = stripeService.requestWithdrawal(wallet.id, body.amount, body.destination ?: "")
        def reloaded = walletRepository.findById(wallet.id)
                .orElseThrow { new NotFoundException("Wallet", wallet.id) }
        ResponseEntity.ok([
            transactionId: tx.id,
            status       : tx.status,
            newBalance   : reloaded.balance
        ])
    }

    /** Self-cancel a PENDING withdrawal. Credits the wallet back and
     *  flips the row to CANCELLED. Only works on rows the caller owns;
     *  staff-side rejections still go through /api/admin/withdrawals/reject. */
    @PostMapping("/withdraw/{id}/cancel")
    ResponseEntity<Map> cancelWithdraw(@PathVariable Long id, HttpServletRequest req) {
        def user = currentUser(req)
        if (user == null) throw new UnauthorizedException("Sign in to cancel a withdrawal")
        def wallet = currentWallet(req)
        if (wallet == null) throw new UnauthorizedException("Sign in to cancel a withdrawal")
        try {
            def result = stripeService.cancelPendingWithdrawal(wallet.id, id)
            ResponseEntity.ok(result)
        } catch (IllegalStateException e) {
            throw new com.sboxmarket.exception.BadRequestException("CANNOT_CANCEL", e.message)
        } catch (IllegalArgumentException e) {
            throw new com.sboxmarket.exception.BadRequestException("INVALID_TX", e.message)
        } catch (NoSuchElementException e) {
            throw new com.sboxmarket.exception.NotFoundException("Transaction", id)
        }
    }

    @PostMapping("/confirm-deposit")
    ResponseEntity<Map> confirmDeposit(@RequestParam String sessionId, HttpServletRequest req) {
        // Completing a deposit credits a wallet — the session-wallet
        // metadata check inside StripeService binds the credit to the
        // wallet that created the session, but there's no reason an
        // anonymous caller should be triggering that code path at all.
        // Require a real logged-in user before touching Stripe.
        def user = currentUser(req)
        if (user == null) throw new UnauthorizedException("Sign in to confirm a deposit")
        stripeService.completeDeposit(sessionId)
        def wallet = currentWallet(req)
        ResponseEntity.ok([newBalance: wallet?.balance ?: BigDecimal.ZERO])
    }
}
