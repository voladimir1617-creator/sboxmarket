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
        // Deliberately minimal response — we used to leak steamId64 and the
        // Stripe publishable key on every wallet fetch. publishableKey now
        // only leaves the server inside the deposit-session response, and
        // steamId64 is only returned via /api/auth/steam/me which the Profile
        // modal uses directly.
        ResponseEntity.ok([
            id        : wallet.id,
            username  : user?.displayName ?: wallet.username,
            avatarUrl : user?.avatarUrl,
            loggedIn  : user != null,
            balance   : wallet.balance,
            currency  : wallet.currency,
            stripeLive: stripeService.isLive()
        ])
    }

    @GetMapping("/transactions")
    ResponseEntity<List<Transaction>> getTransactions(HttpServletRequest req) {
        def wallet = currentWallet(req)
        if (wallet == null) return ResponseEntity.ok([])
        // Hard-cap at 500 so a user with years of history doesn't ship
        // thousands of rows in one JSON response. The Transactions tab
        // client-side paginates within this set.
        def txs = transactionRepository.findByWalletIdOrderByCreatedAtDesc(wallet.id)
        ResponseEntity.ok(txs.size() > 500 ? txs.subList(0, 500) : txs)
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
