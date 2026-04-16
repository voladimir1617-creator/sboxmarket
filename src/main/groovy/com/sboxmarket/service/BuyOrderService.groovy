package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.BuyOrder
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.BuyOrderRepository
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.security.BanGuard
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Standing buy orders — when a new listing is created (or an existing one has its
 * price lowered) this service finds all matching active orders and attempts an
 * auto-purchase using the first buyer who can afford it. Ordered by maxPrice DESC
 * then createdAt ASC so the buyer offering most and queued longest wins ties.
 */
@Service
@Slf4j
class BuyOrderService {

    @Autowired BuyOrderRepository buyOrderRepository
    @Autowired ItemRepository itemRepository
    @Autowired WalletRepository walletRepository
    @Autowired SteamUserRepository steamUserRepository
    @Autowired NotificationService notificationService
    @Autowired TextSanitizer textSanitizer
    @Autowired BanGuard banGuard

    // Lazy to break the circular dependency: PurchaseService has no inbound
    // references here, but we are *called from* ListingService which in turn
    // calls PurchaseService. Using @Lazy keeps Spring's graph happy.
    @Autowired @Lazy PurchaseService purchaseService

    @Transactional
    BuyOrder create(Long buyerUserId, String buyerName, Long itemId, String category,
                    String rarity, BigDecimal maxPrice, Integer quantity) {
        banGuard.assertNotBanned(buyerUserId)
        if (maxPrice == null || maxPrice <= BigDecimal.ZERO) {
            throw new BadRequestException("INVALID_PRICE", "Max price must be positive")
        }
        // Defense-in-depth upper cap. The DTO layer already enforces this
        // (@DecimalMax 100000), but services can be called without going
        // through a controller (scheduled jobs, other services, tests).
        if (maxPrice > new BigDecimal("100000")) {
            throw new BadRequestException("PRICE_TOO_HIGH", "Max price must not exceed \$100,000")
        }
        // Cap quantity at a defensive maximum so a buyer can't queue a
        // 1_000_000-item order that ties up the matching engine.
        int q = Math.min(Math.max(1, quantity ?: 1), 100)
        String snapName = null
        if (itemId != null) {
            def item = itemRepository.findById(itemId).orElse(null)
            if (item != null) snapName = item.name
        }
        // Whitelist the free-text fields so attacker input never lands in
        // the DB. We only use these for display — never for filtering — so
        // they can be plain-text-only.
        def safeCategory = (category in ['Hats','Jackets','Shirts','Pants','Gloves','Boots','Accessories']) ? category : null
        def safeRarity   = (rarity in ['Limited','Off-Market','Standard']) ? rarity : null

        def order = new BuyOrder(
            buyerUserId:      buyerUserId,
            buyerName:        textSanitizer.cleanShort(buyerName),
            itemId:           itemId,
            itemName:         textSanitizer.cleanShort(snapName),
            category:         safeCategory,
            rarity:           safeRarity,
            maxPrice:         maxPrice,
            quantity:         q,
            originalQuantity: q
        )
        buyOrderRepository.save(order)
    }

    List<BuyOrder> listForBuyer(Long buyerUserId) {
        buyOrderRepository.findByBuyer(buyerUserId)
    }

    @Transactional
    BuyOrder cancel(Long buyerUserId, Long orderId) {
        def o = buyOrderRepository.findById(orderId)
            .orElseThrow { new NotFoundException("BuyOrder", orderId) }
        if (o.buyerUserId != buyerUserId) {
            throw new ForbiddenException("Not your buy order")
        }
        o.status = "CANCELLED"
        o.updatedAt = System.currentTimeMillis()
        buyOrderRepository.save(o)
    }

    /**
     * Called by ListingService whenever a listing becomes visible. Walks matching
     * active orders and tries to fill them. Any exception during a single match is
     * swallowed so one failed auto-purchase never blocks the listing from going live.
     */
    @Transactional
    void tryMatch(Listing listing) {
        if (listing == null || listing.status != 'ACTIVE' || listing.listingType != 'BUY_NOW') return
        if (Boolean.TRUE.equals(listing.hidden)) return

        def candidates = buyOrderRepository.findMatching(
            listing.item?.id, listing.item?.category, listing.item?.rarity, listing.price
        )
        for (BuyOrder order : candidates) {
            if (order.quantity <= 0 || order.status != 'ACTIVE') continue
            if (listing.sellerUserId != null && listing.sellerUserId == order.buyerUserId) continue

            def user = steamUserRepository.findById(order.buyerUserId).orElse(null)
            if (user == null) continue
            def wallet = walletRepository.findByUsername("steam_${user.steamId64}")
            if (wallet == null || wallet.balance < listing.price) continue

            try {
                purchaseService.buy(wallet.id, order.buyerUserId, listing.id)
                order.quantity = Math.max(0, order.quantity - 1)
                order.updatedAt = System.currentTimeMillis()
                if (order.quantity == 0) order.status = 'FILLED'
                buyOrderRepository.save(order)
                notificationService.push(
                    order.buyerUserId,
                    'BUY_ORDER_FILLED',
                    "Buy order auto-filled · ${listing.item?.name}",
                    "Paid \$${listing.price.toPlainString()} (cap \$${order.maxPrice.toPlainString()})",
                    listing.id
                )
                log.info("Buy order ${order.id} auto-filled by listing ${listing.id}")
                return // listing is now sold; stop iterating
            } catch (Exception e) {
                log.warn("Buy order ${order.id} match attempt failed: ${e.message}")
            }
        }
    }
}
