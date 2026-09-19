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
    @Autowired com.sboxmarket.service.TextSanitizer textSanitizer
    // BanGuard wasn't wired here — every other state-changing surface in
    // the app (SteamInventoryController.listFromSteam, SupportController's
    // fraud-report endpoint, every BidService / BuyOrderService / OfferService
    // write op) already calls `assertNotBanned` first. Per AdminService's
    // header contract ("every state-changing endpoint in the app MUST call
    // assertNotBanned(userId) first"), the wallet money paths were the last
    // hole: `banUser` flips `user.banned=true` and cancels listings + offers,
    // but it does NOT freeze the wallet (`wallet.frozen` stays false). So a
    // banned user could still drain their wallet via /withdraw before staff
    // applied a separate freeze — exactly the surface the bump in
    // sessionEpoch was meant to close (banned cookies invalidated on next
    // /api/* hit), except /api/wallet/* writes weren't enforcing the ban.
    // Required=false so existing test wiring that constructs the controller
    // with the six collaborators above still compiles; the `?.` short-circuit
    // in each call site makes the guard a no-op when the bean isn't injected,
    // mirroring the SteamInventoryController posture.
    @Autowired(required = false) com.sboxmarket.service.security.BanGuard banGuard

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
                def uname = "steam_" + user.steamId64
                def w = walletRepository.findByUsername(uname)
                if (w == null) {
                    // TOCTOU race: the profile/wallet page fires several wallet
                    // endpoints in parallel (/api/wallet, /api/wallet/transactions,
                    // spend-summary). On a user's FIRST-EVER wallet access the row
                    // doesn't exist yet, so every concurrent request misses the
                    // find above and races the INSERT against the WALLETS(username)
                    // unique index — the losers threw DataIntegrityViolationException
                    // and 500'd the page. Catch the loser's violation and re-read the
                    // winner's committed row. (currentWallet runs without an active
                    // tx on the read paths — self-invoked, so its @Transactional is
                    // bypassed — so the failed save rolls back on its own and the
                    // re-find sees the committed wallet.)
                    try {
                        w = walletRepository.save(new Wallet(
                            username: uname,
                            balance : BigDecimal.ZERO
                        ))
                    } catch (org.springframework.dao.DataIntegrityViolationException race) {
                        w = walletRepository.findByUsername(uname)
                    }
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
            // Pass-through processor fees. The wallet modal renders a live
            // "you receive $X" preview from these as the user types, which is
            // the ONLY place a deposit's true credit is visible before the
            // Stripe redirect — a form promising $100 that credits $96.80 is
            // how you buy chargebacks. Rates, not amounts: the authoritative
            // per-transaction figures come back on the /deposit and /withdraw
            // responses, computed server-side from this same config.
            //
            // Not a secret. These are the terms of the transaction; the user
            // is entitled to them before they commit, and Stripe's own
            // published rates are where they came from.
            feeSchedule:        stripeService.passThroughFeeSchedule(),
            // The withdrawal minimum that applies to THIS wallet right now.
            // feeSchedule carries both figures, but only the server knows
            // which one binds: the per-account charge falls once a calendar
            // month, so the minimum is higher on this wallet's first payout
            // of the month than on its second. A client that guessed would
            // either over-refuse a legitimate withdrawal or promise one the
            // server will bounce, and both are worse than no hint at all.
            minWithdrawalNow:   stripeService.minWithdrawal(
                                    !stripeService.perAccountChargeAlreadyBilled(wallet.id)),
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
        // Batch 977's short-circuit only covered `userId == null`. A
        // session whose `steamUserId` points at a since-deleted user
        // still passes the gate above, but currentWallet() then fails to
        // resolve the user row and falls through to the persisted demo
        // wallet (findById(DEMO_WALLET_ID)) — leaking its ledger again,
        // the exact class of bug batch 977 set out to close. Reject the
        // demo wallet explicitly so a stale session gets an empty list,
        // matching the `wallet.id == DEMO_WALLET_ID` guard on /spend.
        if (wallet == null || wallet.id == DEMO_WALLET_ID) return ResponseEntity.ok([])
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
        // Ban guard — banned users can still log in (to read the ban
        // reason + open an appeal ticket) but must not be able to push
        // fresh money through Stripe. The deposit path was the matching
        // hole to /withdraw below; without it a banned user could keep
        // funding their wallet for chargeback farming or just to clutter
        // the ledger. Throws ForbiddenException → 403.
        banGuard?.assertNotBanned(user.id)
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
        // Ban guard — the load-bearing one. banUser() does NOT freeze the
        // wallet (`wallet.frozen` stays false), so without this gate a
        // banned user can drain their wallet via /withdraw before staff
        // separately freezes them. Runs BEFORE every other gate so a
        // banned user never trips email/TOTP/cap math (and never burns
        // their TOTP code on a doomed request — same posture batch 832's
        // dispute-hold short-circuit established). Throws ForbiddenException
        // → 403, distinct from the 401 a logged-out caller gets.
        banGuard?.assertNotBanned(user.id)
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

        // Normalise to whole cents BEFORE any guard runs. The DTO
        // @DecimalMin/@DecimalMax cap the magnitude but NOT the scale —
        // a body like {"amount": 1.001} reaches us at 3dp. StripeService.
        // requestWithdrawal already does this same setScale(2, HALF_UP)
        // immediately before debiting (see comment at requestWithdrawal:
        // 487-492), so the wallet ledger is always 2dp-clean. But all the
        // guards below (balance check, 24h cap math) were running against
        // the RAW sub-cent value, producing two user-hostile over-rejections:
        //   • $1.00 balance + body.amount=1.001 → InsufficientBalance,
        //     even though the service would have normalised to $1.00 and
        //     succeeded cleanly.
        //   • sum=$4999.99 + body.amount=0.014 → WITHDRAW_DAILY_CAP
        //     ($5000.004 > $5000), even though the service would have
        //     normalised to $0.01 → $5000.00 == cap, allowed.
        // Normalising once here makes every guard agree with what
        // requestWithdrawal will actually persist, and keeps the
        // pre-flight + side-effect numbers identical.
        BigDecimal amount = body.amount.setScale(2, java.math.RoundingMode.HALF_UP)

        if (wallet.balance < amount) {
            throw new InsufficientBalanceException(amount, wallet.balance)
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
            if ((sum + amount) > dailyWithdrawalCap) {
                def remaining = dailyWithdrawalCap - sum
                if (remaining < BigDecimal.ZERO) remaining = BigDecimal.ZERO
                throw new com.sboxmarket.exception.BadRequestException("WITHDRAW_DAILY_CAP",
                    "Daily withdrawal cap of \$${dailyWithdrawalCap.toPlainString()} would be exceeded. " +
                    "\$${sum.toPlainString()} already requested in the last 24h · \$${remaining.toPlainString()} available. " +
                    "Try again in 24h or contact support for a manual payout.")
            }
        }
        // If the user has 2FA enabled, require a fresh 6-digit code on the
        // request. This is our second-factor gate on the most sensitive
        // money-out flow — session cookies alone are not enough.
        //
        // Verified LAST, immediately before the wallet debit: the TOTP
        // replay-guard advances `lastTotpStep` and persists it, which
        // burns the code. If this ran before the balance / dispute-hold /
        // daily-cap checks (its previous position), a user who entered a
        // valid code but then tripped one of those read-only gates would
        // have their code consumed anyway and be forced to wait ~30s for
        // the next authenticator code just to retry a corrected amount.
        // All the gates above are side-effect-free throws, so deferring
        // the TOTP consumption to here is safe.
        if (user.totpSecret) {
            def code = (body.totpCode as String ?: '').trim()
            if (!code) {
                throw new com.sboxmarket.exception.BadRequestException("TOTP_REQUIRED",
                    "Two-factor code required for withdrawals")
            }
            // Brute-force lockout BEFORE verify (a 6-digit code is a 1M space;
            // the per-user /api/wallet rate limit alone allows ~172k guesses/day
            // — without a cap a hijacked session brute-forces the withdraw 2FA
            // gate in ~48h and drains to the daily cap). Checked first so a
            // locked attacker can't keep guessing AND a guess while locked never
            // consumes a code. Shares TotpService's per-user counter.
            long lockMs = totpService.lockoutRemainingMs(user.id)
            if (lockMs > 0) {
                throw new com.sboxmarket.exception.BadRequestException("TOTP_LOCKED",
                    "Too many invalid 2FA codes. Try again in ${(long) Math.ceil(lockMs / 60000.0d)} minute(s).".toString())
            }
            def step = totpService.verify(user.totpSecret, code, user.lastTotpStep)
            if (step < 0) {
                totpService.recordFail(user.id)
                throw new com.sboxmarket.exception.BadRequestException("TOTP_INVALID",
                    "Invalid or reused 2FA code")
            }
            totpService.clearFails(user.id)
            user.lastTotpStep = step
            steamUserRepository.save(user)
        }

        // Concurrent-withdraw race-loss guard. Two simultaneous /withdraw
        // requests for the same wallet (two tabs hitting submit at once, a
        // double-tap from a flaky network, a malicious double-submit) both
        // pass the balance / cap / freeze gates above against the same wallet
        // snapshot, then race on the Wallet @Version inside
        // requestWithdrawal's debit-and-save. Hibernate aborts the loser
        // with ObjectOptimisticLockingFailureException — the loser's
        // outer @Transactional rolls back fully (no debit, no PENDING row),
        // so there is NEVER a double-debit. But without this catch, the
        // user sees a raw 500 even though nothing went wrong on their end:
        // their other request DID succeed and they just need to try this
        // one again. Translate to a clean retryable code so the SPA can
        // toast "try again" instead of "internal error". Mirrors the
        // confirmDeposit race-loss handling pattern.
        // Sanitize the user-supplied destination ref BEFORE it lands in
        // Transaction.stripeReference. The DTO @Size caps the length but
        // leaves the value byte-for-byte: a destination like
        // `acct_x\r\n[INFO] Admin deleted user 5` then gets concatenated
        // verbatim into the audit-log summary
        // (StripeService.requestWithdrawal → "Withdrawal $X requested from
        // wallet w → ${destinationRef}") and into the user-facing
        // notification body fired by AdminService.approveWithdrawal
        // ("Reference: ${tx.stripeReference}"), enabling log-line
        // forgery and HTML payload survival into the GDPR /export JSON.
        // Same defense-in-depth every other free-text body field gets
        // (stallBio, trade dispute reason, offer message). Empty string
        // when nothing was supplied so the StripeService Elvis below
        // still falls back to "manual".
        def cleanDest = textSanitizer.cleanShort(body.destination as String) ?: ''
        try {
            def tx = stripeService.requestWithdrawal(wallet.id, amount, cleanDest)
            // Belt-and-braces flush. The PRIMARY @Version check now fires
            // INSIDE requestWithdrawal, which flushes the debit BEFORE
            // Transfer.create so a race-loss aborts before any money moves
            // (see StripeService.requestWithdrawal — do NOT remove that inner
            // flush in favour of this one: by the time control returns here,
            // Transfer.create has already run, and a rollback would restore
            // the balance while the payout had already left). This secondary
            // flush forces any remaining pending @Version UPDATE to surface
            // inside this catch (→ WITHDRAW_RACE) rather than escaping as a
            // 500 at outer-tx commit. Spring's flush-mode-AUTO also flushes
            // before the findById below; explicit is safer across Hibernate
            // versions and entity-type queries.
            walletRepository.flush()
            def reloaded = walletRepository.findById(wallet.id)
                    .orElseThrow { new NotFoundException("Wallet", wallet.id) }
            // Echo the pass-through payout fee and the NET actually sent.
            // `newBalance` alone tells the user what left their wallet but
            // not what lands in their bank — and under pass-through pricing
            // those are different numbers. Read off the persisted row, not
            // recomputed, so this can never disagree with what was paid out.
            // Zero on the dev path and on any pre-pass-through row.
            def payoutFee = (tx.feeAmount ?: BigDecimal.ZERO) as BigDecimal
            return ResponseEntity.ok([
                transactionId: tx.id,
                status       : tx.status,
                newBalance   : reloaded.balance,
                amount       : tx.amount,
                processingFee: payoutFee,
                netPayout    : (tx.amount ?: BigDecimal.ZERO) - payoutFee
            ])
        } catch (org.springframework.dao.OptimisticLockingFailureException raceLost) {
            log.info("withdraw lost wallet @Version race for wallet ${wallet.id} — concurrent withdraw committed first")
            throw new com.sboxmarket.exception.BadRequestException("WITHDRAW_RACE",
                "Another withdrawal request was processed at the same time. Refresh your balance and try again.")
        }
    }

    /** Self-cancel a PENDING withdrawal. Credits the wallet back and
     *  flips the row to CANCELLED. Only works on rows the caller owns;
     *  staff-side rejections still go through /api/admin/withdrawals/reject. */
    @PostMapping("/withdraw/{id}/cancel")
    ResponseEntity<Map> cancelWithdraw(@PathVariable Long id, HttpServletRequest req) {
        def user = currentUser(req)
        if (user == null) throw new UnauthorizedException("Sign in to cancel a withdrawal")
        // Ban guard — cancel is a state change (flips tx PENDING→CANCELLED
        // and credits the wallet). Banned users must hit a 403 here even
        // though the operation is "user-favourable" (refunding to their own
        // wallet) — leaving cancel open while /withdraw is gated would let
        // a banned user replay withdraw/cancel pairs to e.g. poke at
        // race-loss codepaths or just churn the audit log.
        banGuard?.assertNotBanned(user.id)
        def wallet = currentWallet(req)
        if (wallet == null) throw new UnauthorizedException("Sign in to cancel a withdrawal")
        try {
            // cancelWithdraw is NOT @Transactional — cancelPendingWithdrawal
            // opens its own tx and commits before returning, so any Wallet
            // @Version race (vs an admin reject, or a duplicate self-cancel
            // tab) surfaces as OptimisticLockingFailureException FROM the
            // call below, inside this catch. No outer-tx commit-time escape
            // hatch to worry about here (unlike /withdraw, which IS
            // @Transactional and needs an explicit flush).
            def result = stripeService.cancelPendingWithdrawal(wallet.id, id)
            ResponseEntity.ok(result)
        } catch (IllegalStateException e) {
            // status != PENDING (already paid out / cancelled).
            throw new com.sboxmarket.exception.BadRequestException("CANNOT_CANCEL", e.message)
        } catch (IllegalArgumentException e) {
            // the tx exists but is not a withdrawal.
            throw new com.sboxmarket.exception.BadRequestException("INVALID_TX", e.message)
        } catch (org.springframework.dao.OptimisticLockingFailureException raceLost) {
            // Cancel races admin-side reject (or another tab's cancel) on the
            // same tx. cancelPendingWithdrawal does wallet.balance += refund
            // then saves; the loser's Wallet @Version flip aborts. No
            // double-credit (the rollback wipes the entire transaction), but
            // a raw 500 confuses the user since their cancel may already have
            // landed via the other path. Re-read the wallet — if the tx is
            // already CANCELLED/REJECTED, the user's intent succeeded; either
            // way, surface a clean retryable code rather than 500.
            log.info("cancelWithdraw lost @Version race for wallet ${wallet.id} tx ${id} — concurrent state change won")
            throw new com.sboxmarket.exception.BadRequestException("CANCEL_RACE",
                "Withdrawal state changed during cancel. Refresh and try again — your cancel may already have been processed.")
        }
        // A missing tx (NotFoundException -> 404) and a cross-wallet attempt
        // (ForbiddenException -> 403) are now thrown directly by
        // StripeService.cancelPendingWithdrawal — both are ApiExceptions that
        // GlobalExceptionHandler maps with the right status, so they need no
        // catch/remap here and propagate untouched. (2026-05-21)
    }

    /* ── STRIPE CONNECT (payout onboarding / KYC) ────────────────────
     * Real money-out requires the seller to onboard onto a Stripe Connect
     * Express account (which is also the KYC / identity step). These two
     * endpoints drive that flow; the actual Transfer happens later in
     * StripeService.requestWithdrawal once payouts are enabled.
     */

    /** Start (or resume) Stripe Connect onboarding for the signed-in
     *  user's wallet and return the hosted onboarding URL the SPA should
     *  redirect to. Reuses the same auth + ban + freeze posture as the
     *  withdraw path — onboarding is a precondition for moving money out,
     *  so a banned / frozen user must not be able to start it. */
    @PostMapping("/connect/onboard")
    ResponseEntity<Map> connectOnboard(HttpServletRequest req) {
        def user = currentUser(req)
        if (user == null) throw new UnauthorizedException("Sign in to set up payouts")
        // Ban guard — onboarding is the gateway to money-out; banned users
        // must hit a 403 here just like they do on /withdraw.
        banGuard?.assertNotBanned(user.id)
        def wallet = currentWallet(req)
        if (wallet == null) throw new UnauthorizedException("Sign in to set up payouts")
        // Freeze gate — mirror /withdraw + /deposit: a staff-frozen wallet
        // can't start payout onboarding either.
        if (Boolean.TRUE.equals(wallet.frozen)) {
            throw new com.sboxmarket.exception.BadRequestException("WALLET_FROZEN",
                "Your wallet is frozen by staff" +
                    (wallet.frozenReason ? ": ${wallet.frozenReason}" : '') +
                    ". Open a support ticket to resolve.")
        }
        def result = stripeService.createConnectOnboardingLink(wallet.id)
        ResponseEntity.ok(result)
    }

    /** Connect onboarding status for the signed-in user — drives the
     *  Wallet page's "Set up payouts" vs "Payouts enabled" UI. Returns
     *  payoutsEnabled + whether onboarding is still needed. Signed-in
     *  only (payout state is account-specific). */
    @GetMapping("/connect/status")
    ResponseEntity<Map> connectStatus(HttpServletRequest req) {
        def user = currentUser(req)
        if (user == null) throw new UnauthorizedException("Sign in to view payout status")
        def wallet = currentWallet(req)
        if (wallet == null || wallet.id == DEMO_WALLET_ID) {
            // No real wallet yet — report a clean "needs onboarding" state
            // rather than leaking the demo wallet's payout flags.
            return ResponseEntity.ok([
                live:             stripeService.isLive(),
                hasAccount:       false,
                payoutsEnabled:   false,
                onboardingNeeded: true,
                accountId:        null
            ])
        }
        ResponseEntity.ok(stripeService.connectStatus(wallet.id))
    }

    @PostMapping("/confirm-deposit")
    ResponseEntity<Map> confirmDeposit(@RequestBody(required = false) Map body, HttpServletRequest req) {
        // sessionId moved from a query PARAM to the request BODY so the Stripe
        // Checkout Session id stops landing in our server access logs, the
        // Referer header, and proxy/CDN caches (it's still in the browser URL
        // from Stripe's own redirect, but that's Stripe's flow, not ours). The
        // length cap + empty guard below preserve the old @RequestParam's
        // reject-on-missing/oversized behaviour. (frontend-audit fix)
        String sessionId = (body?.get('sessionId') as String)?.trim() ?: ''
        // Completing a deposit credits a wallet — the session-wallet
        // metadata check inside StripeService binds the credit to the
        // wallet that created the session, but there's no reason an
        // anonymous caller should be triggering that code path at all.
        // Require a real logged-in user before touching Stripe.
        def user = currentUser(req)
        if (user == null) throw new UnauthorizedException("Sign in to confirm a deposit")
        // Ban guard — mirrors /deposit. If a user was banned AFTER
        // creating the Checkout session but BEFORE the synchronous
        // confirm-deposit fires, we don't want their browser to drive
        // the credit through. The webhook will still complete the
        // deposit (it has no caller context) — the money belongs to
        // the user — but the synchronous user-driven path must reject.
        banGuard?.assertNotBanned(user.id)
        // sessionId is required — reject AFTER the auth + ban gates so an
        // anonymous/banned caller still gets 401/403 (not a 400 that would leak
        // that the endpoint exists). Preserves the old @RequestParam's
        // reject-on-missing behaviour. (frontend-audit fix)
        if (sessionId.isEmpty()) {
            throw new com.sboxmarket.exception.BadRequestException("INVALID_SESSION_ID", "sessionId is required")
        }
        // Upstream length cap on the session id. Stripe Checkout Session
        // ids are <=66 chars in practice (e.g. cs_test_a1B2…); rejecting
        // pathological lengths here keeps a hostile client from forcing
        // StripeService to allocate / log / round-trip a megabyte string
        // just to fail. SHORT_LABEL (200) leaves ~3x headroom over the
        // longest id Stripe currently emits. Stable code so the SPA can
        // branch deterministically.
        com.sboxmarket.util.InputLimits.requireMax(
            sessionId, com.sboxmarket.util.InputLimits.SHORT_LABEL,
            "INVALID_SESSION_ID", "sessionId")
        // Resolve the wallet id BEFORE completeDeposit so we can re-read
        // the row by primary key afterwards (see the post-credit re-read
        // below). currentWallet() also lazily creates the wallet row for
        // a brand-new Steam user, so this call guarantees the id exists.
        def wallet = currentWallet(req)
        // completeDeposit() is also reachable from the Stripe webhook
        // (checkout.session.completed). When both fire near-simultaneously
        // for the same session they race on the wallet row: the Wallet
        // @Version guard means exactly one credit lands and the loser's
        // transaction rolls back with ObjectOptimisticLockingFailureException
        // — so there is NEVER a double-credit. But the loser is this
        // synchronous /confirm-deposit call, and surfacing a raw 500 to a
        // user whose deposit actually succeeded is misleading. Swallow the
        // lock-contention loss: the webhook (or the other racer) committed
        // the credit, so re-reading the wallet below returns the correct
        // post-credit balance. Any other exception still propagates.
        try {
            stripeService.completeDeposit(sessionId)
        } catch (org.springframework.dao.OptimisticLockingFailureException raceLost) {
            log.info("confirm-deposit for session ${sessionId} lost the credit race to the webhook — deposit already applied")
        }
        // Re-read the wallet by id AFTER completeDeposit returns (batch
        // 657 fix / BUG 4). The `wallet` snapshot taken above can be
        // stale: completeDeposit — or the webhook racing it on another
        // thread microseconds earlier — commits the credit in a separate
        // transaction, and the controller method itself is NOT
        // @Transactional, so the snapshot above never sees that commit.
        // A fresh findById issues a new SELECT that reflects the
        // committed credit, so the user sees their real post-deposit
        // balance instead of the pre-credit number.
        def credited = (wallet?.id != null)
            ? walletRepository.findById(wallet.id).orElse(wallet)
            : wallet
        ResponseEntity.ok([newBalance: credited?.balance ?: BigDecimal.ZERO])
    }
}
