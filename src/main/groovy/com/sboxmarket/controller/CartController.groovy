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
    @Autowired com.sboxmarket.repository.ListingRepository listingRepository
    @Autowired(required = false) com.sboxmarket.service.CartService cartService

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

        // Optional per-row price-check payload — guards against a seller
        // raising their price after the user sees the cart-confirm modal
        // but before the checkout POST lands. Shape:
        //   {listingIds: [...], expectedPrices: {"<listingId>": "9.99"}}
        // Rows whose server-side price differs from the client's snapshot
        // fail with PRICE_CHANGED instead of being silently debited at
        // the new price. The freshness probe on the client shows a banner
        // before Confirm, but the server check is the belt-and-braces
        // defence against a within-second race.
        Map<Long, BigDecimal> expectedPrices = [:]
        def rawExpected = body?.expectedPrices
        if (rawExpected instanceof Map) {
            rawExpected.each { k, v ->
                try {
                    expectedPrices[Long.valueOf(k.toString())] = new BigDecimal(v.toString())
                } catch (Exception ignored) {
                    // Silently drop malformed entries — client freshness
                    // probe is best-effort; a bad token shouldn't 400 the
                    // whole cart.
                }
            }
        }

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
                // Per-row price-match guard — if the client sent an
                // expected price and the server-side price has drifted
                // in the last second (seller edit), reject BEFORE
                // calling PurchaseService.buy so the wallet isn't
                // debited at a surprise amount. Short-circuit via
                // listingRepository.findById — service-layer guards
                // (status, hidden) still run inside buy().
                def expected = expectedPrices[id]
                if (expected != null) {
                    def lOpt = listingRepository.findById(id)
                    if (lOpt.isPresent()) {
                        def listing = lOpt.get()
                        if (listing.price != null && listing.price.compareTo(expected) != 0) {
                            results << [listingId: id, status: 'FAILED',
                                        code: 'PRICE_CHANGED',
                                        error: "Price moved from \$${expected} to \$${listing.price} — refresh and retry",
                                        expected: expected, actual: listing.price]
                            continue
                        }
                    }
                }
                def res = purchaseService.buy(wallet.id, userId, id)
                results << [listingId: id, status: 'OK', newBalance: res.newBalance]
                successCount++
                def price = res.listing?.price
                if (price != null) totalSpent = totalSpent + price
            } catch (InsufficientBalanceException e) {
                // Batch 964 — attach structured amounts per row so the
                // frontend can sum per-row shortfalls without re-deriving
                // them from cart totals (client snapshot) + wallet balance
                // (might be stale mid-checkout). Mirrors batch 963's
                // top-level INSUFFICIENT_BALANCE details shape.
                def shortfall = (e.required != null && e.available != null)
                                    ? (e.required - e.available) : null
                results << [
                    listingId: id, status: 'FAILED', code: 'INSUFFICIENT_BALANCE',
                    error: e.message,
                    details: [required: e.required, available: e.available, shortfall: shortfall]
                ]
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

        // Scrub successful rows from the server-side cart so the next
        // /api/cart GET (and any other device) doesn't show ghosts that
        // are already owned. Failed rows stay so the user can retry.
        if (cartService != null) {
            results.findAll { it.status == 'OK' }.each {
                try { cartService.remove(userId, it.listingId as Long) }
                catch (Exception e) { log.warn("cart-row scrub failed for ${it.listingId}: ${e.message}") }
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

    // ── Server-side cart persistence (V31) ──────────────────────────

    /** Listing ids in the user's cart, oldest-added first. */
    @GetMapping
    ResponseEntity<List<Long>> list(HttpServletRequest req) {
        def uid = requireUser(req)
        if (cartService == null) return ResponseEntity.ok([])
        ResponseEntity.ok(cartService.list(uid))
    }

    /** Add a listing to the user's cart. Idempotent — `added: false`
     *  means it was already there. Returns the post-state ids so the
     *  client trusts-but-verifies its optimistic update. */
    @PostMapping('/{listingId}')
    ResponseEntity<Map> addToCart(@PathVariable Long listingId, HttpServletRequest req) {
        def uid = requireUser(req)
        if (cartService == null) {
            throw new BadRequestException('UNSUPPORTED', 'Cart persistence not available')
        }
        def added = cartService.add(uid, listingId)
        ResponseEntity.ok([
            listingId: listingId,
            added:     added,
            ids:       cartService.list(uid)
        ])
    }

    @DeleteMapping('/{listingId}')
    ResponseEntity<Map> removeFromCart(@PathVariable Long listingId, HttpServletRequest req) {
        def uid = requireUser(req)
        if (cartService == null) return ResponseEntity.ok([listingId: listingId, removed: false, ids: []])
        def removed = cartService.remove(uid, listingId)
        ResponseEntity.ok([
            listingId: listingId,
            removed:   removed,
            ids:       cartService.list(uid)
        ])
    }

    /** Clear the entire cart. Called when the user explicitly hits
     *  "Empty cart". Cheap idempotent — returns `removed: 0` if it
     *  was already empty. */
    @DeleteMapping
    ResponseEntity<Map> clear(HttpServletRequest req) {
        def uid = requireUser(req)
        if (cartService == null) return ResponseEntity.ok([removed: 0])
        ResponseEntity.ok([removed: cartService.clear(uid)])
    }

    /** One-shot bridge: POST localStorage cart ids → server merges →
     *  returns the authoritative post-merge list. The frontend calls
     *  this once per (user, browser) pair via a per-user localStorage
     *  flag so it doesn't re-merge every session. */
    @PostMapping('/bulk')
    ResponseEntity<Map> bulkMerge(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        if (cartService == null) return ResponseEntity.ok([ids: []])
        def raw = body?.ids as List
        def ids = (raw ?: []).collect {
            try { it == null ? null : Long.valueOf(it.toString()) } catch (Exception ignored) { null }
        }.findAll { it != null }
        ResponseEntity.ok([ids: cartService.bulkMerge(uid, ids)])
    }
}
