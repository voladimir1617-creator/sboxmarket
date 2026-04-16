package com.sboxmarket.controller

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.InsufficientBalanceException
import com.sboxmarket.exception.ListingNotAvailableException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.Wallet
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.PurchaseService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.annotation.*

/**
 * Bulk-buy endpoint — the shopping cart lives in the browser's localStorage,
 * and when the user hits Checkout the frontend POSTs the full list of
 * listing ids here. The server calls `PurchaseService.buy` once per listing
 * in order, collecting per-row results so the UI can tell the user exactly
 * which lines succeeded.
 *
 * Transactional semantics: each buy runs in its own transaction — a failure
 * on row 4 does NOT roll back rows 1–3. This matches real-world e-commerce
 * where a partial success is still useful to the buyer.
 */
@RestController
@RequestMapping("/api/cart")
@Slf4j
class CartController {

    @Autowired PurchaseService purchaseService
    @Autowired WalletRepository walletRepository
    @Autowired SteamUserRepository steamUserRepository

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    @PostMapping("/checkout")
    ResponseEntity<Map> checkout(@RequestBody Map body, HttpServletRequest req) {
        def userId = requireUser(req)
        def raw = body?.listingIds
        if (!(raw instanceof List) || raw.isEmpty()) {
            throw new BadRequestException("EMPTY_CART", "Cart is empty")
        }
        if (raw.size() > 50) {
            throw new BadRequestException("CART_TOO_LARGE", "Cart is capped at 50 items")
        }
        def ids = raw.collect { (it as Number).longValue() }

        def user = steamUserRepository.findById(userId).orElseThrow { new UnauthorizedException("Unknown user") }
        def wallet = walletRepository.findByUsername("steam_${user.steamId64}")
        if (wallet == null) {
            wallet = walletRepository.save(new Wallet(username: "steam_${user.steamId64}", balance: BigDecimal.ZERO))
        }

        def results = []
        def successCount = 0
        def totalSpent = BigDecimal.ZERO
        for (Long id : ids) {
            try {
                def res = purchaseService.buy(wallet.id, userId, id)
                results << [listingId: id, status: 'OK', newBalance: res.newBalance]
                successCount++
                def price = res.listing?.price
                if (price != null) totalSpent = totalSpent + price
            } catch (InsufficientBalanceException e) {
                results << [listingId: id, status: 'FAILED', code: 'INSUFFICIENT_BALANCE', error: e.message]
            } catch (ListingNotAvailableException e) {
                results << [listingId: id, status: 'FAILED', code: 'LISTING_NOT_AVAILABLE', error: 'Listing is no longer available']
            } catch (ObjectOptimisticLockingFailureException e) {
                results << [listingId: id, status: 'FAILED', code: 'LISTING_NOT_AVAILABLE', error: 'Listing is no longer available']
            } catch (NotFoundException e) {
                results << [listingId: id, status: 'FAILED', code: 'NOT_FOUND', error: 'Listing not found']
            } catch (ForbiddenException e) {
                results << [listingId: id, status: 'FAILED', code: 'FORBIDDEN', error: e.message]
            } catch (BadRequestException e) {
                results << [listingId: id, status: 'FAILED', code: e.code ?: 'BAD_REQUEST', error: e.message]
            } catch (Exception e) {
                // Anything else is an unexpected internal failure — log it
                // with the correlation id but don't leak the raw message
                // (which can contain stack frames, SQL detail, etc.) to
                // the client. The per-row generic error lets the buyer
                // retry that one line without the whole cart failing.
                log.error("cart checkout row failed (listingId=${id})", e)
                results << [listingId: id, status: 'FAILED', code: 'INTERNAL_ERROR', error: 'Could not complete this purchase']
            }
        }

        ResponseEntity.ok([
            total:       ids.size(),
            successful:  successCount,
            failed:      ids.size() - successCount,
            totalSpent:  totalSpent.setScale(2, BigDecimal.ROUND_HALF_UP),
            results:     results
        ])
    }
}
