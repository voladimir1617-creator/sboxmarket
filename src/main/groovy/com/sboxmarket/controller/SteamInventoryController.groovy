package com.sboxmarket.controller

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.service.ListingService
import com.sboxmarket.service.SteamInventoryService
import com.sboxmarket.service.SteamSyncService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Bridges the user's Steam inventory into sboxmarket:
 *
 * - `GET  /api/steam/inventory` — returns the live Steam inventory for the
 *   signed-in user's steamId64, enriched with whether each item already has
 *   a matching catalogue entry (so the frontend can show "new" badges).
 * - `POST /api/steam/sync` — forces an immediate inventory + profile resync
 *   without waiting for the 20-minute scheduler tick.
 * - `POST /api/steam/list` — creates a listing from an item in the user's
 *   Steam inventory. Matches by name against our `Item` catalogue; if no
 *   matching catalogue entry exists, we auto-create one (with category
 *   inferred from Steam tags) so the first person to list a new cosmetic
 *   also adds it to the database.
 */
@RestController
@RequestMapping("/api/steam")
@Slf4j
class SteamInventoryController {

    @Autowired SteamInventoryService steamInventoryService
    @Autowired SteamSyncService steamSyncService
    @Autowired SteamUserRepository steamUserRepository
    @Autowired ItemRepository itemRepository
    @Autowired ListingService listingService

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    @GetMapping("/inventory")
    ResponseEntity<Map> inventory(HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        def items = steamInventoryService.fetchInventory(user.steamId64)

        // Pull ONLY the catalogue rows whose lowercase name matches an
        // item in the user's inventory. Previously this was
        // `itemRepository.findAll()` on every request, which pulled the
        // entire catalogue per inventory fetch — fine at 80 items but
        // linear in the catalogue size as it grows. The bulk indexed
        // query hits `idx_items_name` directly.
        def lowerNames = items.collect { (it.name ?: '').toString().toLowerCase() }
                              .findAll { it }
                              .unique()
        def catalogue = lowerNames.isEmpty() ? [:] :
            itemRepository.findByNamesLowerIn(lowerNames).collectEntries { [(it.name?.toLowerCase()): it] }

        def enriched = items.collect { s ->
            def existing = catalogue[(s.name ?: '').toString().toLowerCase()]
            [
                assetId:     s.assetId,
                name:        s.name,
                type:        s.type,
                iconUrl:     s.iconUrl,
                tradable:    s.tradable,
                marketable:  s.marketable,
                category:    existing?.category ?: steamInventoryService.inferCategory(s),
                rarity:      existing?.rarity ?: 'Standard',
                catalogueId: existing?.id,
                suggestedPrice: existing?.lowestPrice ?: BigDecimal.ZERO
            ]
        }
        ResponseEntity.ok([
            items:         enriched,
            count:         enriched.size(),
            lastSyncedAt:  user.lastSyncedAt,
            steamId64:     user.steamId64
        ])
    }

    @PostMapping("/sync")
    ResponseEntity<Map> sync(HttpServletRequest req) {
        ResponseEntity.ok(steamSyncService.syncNow(requireUser(req)))
    }

    @PostMapping("/list")
    ResponseEntity<Map> listFromSteam(@RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        def assetId = body?.assetId?.toString()
        def priceRaw = body?.price
        if (!assetId)  throw new BadRequestException("INVALID_ASSET", "assetId is required")
        if (!priceRaw) throw new BadRequestException("INVALID_PRICE", "price is required")
        BigDecimal price
        try {
            price = new BigDecimal(priceRaw.toString())
        } catch (NumberFormatException ignored) {
            throw new BadRequestException("INVALID_PRICE", "price must be a valid number")
        }
        if (price <= BigDecimal.ZERO) throw new BadRequestException("INVALID_PRICE", "price must be positive")
        // Mirror the DTO-layer cap used on /api/listings/sell and the
        // rest of the trading surface so a user can't list a Steam item
        // at $1,000,000,000 by bypassing the frontend form.
        if (price > new BigDecimal("100000")) {
            throw new BadRequestException("PRICE_TOO_HIGH", "price must not exceed \$100,000")
        }
        // Defensive assetId cap — Steam asset ids are short numeric
        // strings (typically 10-20 chars). Anything longer is either a
        // probe or a crafted payload.
        if (assetId.length() > 32) {
            throw new BadRequestException("INVALID_ASSET", "assetId is too long")
        }

        // Re-fetch the live inventory so we can't list something the user
        // doesn't currently own (or has already moved out of Steam).
        def inv = steamInventoryService.fetchInventory(user.steamId64)
        def steamItem = inv.find { (it.assetId as String) == assetId }
        if (steamItem == null) {
            throw new BadRequestException("NOT_IN_INVENTORY", "Asset $assetId was not found in your Steam inventory. Try /api/steam/sync first.")
        }
        if (!steamItem.tradable) {
            throw new BadRequestException("NOT_TRADABLE", "This item is not tradable on Steam right now.")
        }

        def name = (steamItem.name ?: '').toString()
        // Indexed lookup via the `idx_items_name` functional index on
        // `LOWER(name)` — O(log N) instead of the previous full scan.
        def item = itemRepository.findByNameIgnoreCase(name)
        if (item == null) {
            // Auto-create a catalogue entry so brand-new items become listable
            item = itemRepository.save(new Item(
                name:        name.take(255),
                category:    steamInventoryService.inferCategory(steamItem),
                rarity:      'Standard',
                imageUrl:    steamItem.iconUrl as String,
                accentColor: '#13192a',
                lowestPrice: price,
                steamPrice:  price,
                supply:      1,
                totalSold:   0,
                trendPercent: 0
            ))
            log.info("Auto-created catalogue item \"${item.name}\" from Steam inventory of ${user.steamId64}")
        }

        def listing = new Listing(
            item:         item,
            price:        price,
            sellerName:   user.displayName ?: ("Player_" + user.steamId64.takeRight(6)),
            sellerAvatar: (user.displayName ?: 'US').take(2).toUpperCase(),
            condition:    '',
            rarityScore:  BigDecimal.ZERO,
            status:       'ACTIVE',
            sellerUserId: uid,
            listingType:  'BUY_NOW'
        )
        def saved = listingService.createListing(listing)
        ResponseEntity.ok([
            listingId: saved.id,
            itemId:    item.id,
            price:     saved.price,
            status:    saved.status
        ])
    }
}
