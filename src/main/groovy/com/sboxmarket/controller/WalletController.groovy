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
        def wallet = currentWallet(req)
        if (wallet == null) return ResponseEntity.notFound().build()
        def user = currentUser(req)
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
            pendingDepositCt:   pendingRows.count { it.type == 'DEPOSIT' }
        ])
    }

    @GetMapping("/transactions")
    ResponseEntity<List<Transaction>> getTransactions(HttpServletRequest req) {
        def wallet = currentWallet(req)
        if (wallet == null) return ResponseEntity.ok([])
        // SQL-level LIMIT 500 so only 500 rows leave the database.
        // Previous code loaded everything then sliced in memory.
        def txs = transactionRepository.findByWalletIdOrderByCreatedAtDesc(
            wallet.id, org.springframework.data.domain.PageRequest.of(0, 500))
        ResponseEntity.ok(txs)
    }

    /** CSV export of the signed-in user's full transaction history. Intended
     *  for tax / accounting use — each row is a single ledger entry so the
     *  file is import-ready for spreadsheets. Capped at 5000 rows; users
     *  with more history can open a support ticket for a full dump. */
    @GetMapping(value = "/transactions.csv", produces = "text/csv")
    ResponseEntity<String> exportTransactionsCsv(HttpServletRequest req) {
        def user = currentUser(req)
        if (user == null) throw new UnauthorizedException("Sign in to export your transactions")
        def wallet = currentWallet(req)
        def rows = wallet == null ? [] : transactionRepository.findByWalletIdOrderByCreatedAtDesc(
            wallet.id, org.springframework.data.domain.PageRequest.of(0, 5000))
        def sb = new StringBuilder()
        sb.append("id,date,type,status,amount,currency,description,listingId,reference\n")
        def df = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        df.timeZone = java.util.TimeZone.getTimeZone("UTC")
        def csvEscape = { String v ->
            if (v == null) return ''
            if (v.contains(',') || v.contains('"') || v.contains('\n')) {
                return '"' + v.replace('"', '""') + '"'
            }
            v
        }
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
        return ResponseEntity.ok()
            .header('Content-Disposition',
                "attachment; filename=\"skinbox-transactions-${df.format(new Date()).replace(':','-')}.csv\"")
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
