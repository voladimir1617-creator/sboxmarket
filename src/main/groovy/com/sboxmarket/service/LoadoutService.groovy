package com.sboxmarket.service

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.ForbiddenException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.Item
import com.sboxmarket.model.Loadout
import com.sboxmarket.model.LoadoutFavorite
import com.sboxmarket.model.LoadoutSlot
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.LoadoutFavoriteRepository
import com.sboxmarket.repository.LoadoutRepository
import com.sboxmarket.repository.LoadoutSlotRepository
import com.sboxmarket.service.security.BanGuard
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Loadout Lab. Users can curate up to 8 s&box slots, publish publicly for the
 * Discover tab, or keep private. The AI-Generate action picks the cheapest active
 * listing per category within a budget, filling any unlocked slots.
 */
@Service
@Slf4j
class LoadoutService {

    static final List<String> SLOTS = ['Hats','Jackets','Shirts','Pants','Gloves','Boots','Accessories','Wild']

    @Autowired LoadoutRepository loadoutRepository
    @Autowired LoadoutSlotRepository loadoutSlotRepository
    @Autowired LoadoutFavoriteRepository loadoutFavoriteRepository
    @Autowired ItemRepository itemRepository
    @Autowired TextSanitizer textSanitizer
    @Autowired BanGuard banGuard

    @Transactional
    Loadout create(Long ownerUserId, String ownerName, String name, String description, String visibility) {
        banGuard.assertNotBanned(ownerUserId)
        def cleanName = textSanitizer.cleanShort(name)
        def cleanDesc = textSanitizer.medium(description)
        def cleanOwner = textSanitizer.cleanShort(ownerName)
        if (!cleanName || cleanName.isEmpty()) throw new BadRequestException("INVALID_NAME", "Loadout name is required")
        def allowedVisibility = (visibility in ['PUBLIC','PRIVATE']) ? visibility : 'PUBLIC'
        def loadout = new Loadout(
            ownerUserId: ownerUserId,
            ownerName:   cleanOwner,
            name:        cleanName,
            description: cleanDesc,
            visibility:  allowedVisibility
        )
        loadoutRepository.save(loadout)
        // Seed empty slots
        SLOTS.each { slotName ->
            loadoutSlotRepository.save(new LoadoutSlot(loadoutId: loadout.id, slot: slotName))
        }
        loadout
    }

    List<Loadout> listPublic(String search) {
        def page = org.springframework.data.domain.PageRequest.of(0, 200)
        search ? loadoutRepository.searchPublic(search, page) : loadoutRepository.findPublic(page)
    }

    List<Loadout> listMine(Long ownerUserId) {
        loadoutRepository.findByOwner(ownerUserId)
    }

    /**
     * Fetch a loadout with its slots. PRIVATE loadouts are only visible
     * to their owner — any other viewer (anonymous or otherwise) gets a
     * NotFoundException so we neither confirm nor deny the loadout's
     * existence. A plain 404 prevents loadout-id enumeration from
     * discovering which ids belong to hidden sets.
     */
    Map getWithSlots(Long id, Long viewerUserId = null) {
        def loadout = loadoutRepository.findById(id).orElseThrow { new NotFoundException("Loadout", id) }
        if (loadout.visibility == 'PRIVATE' && loadout.ownerUserId != viewerUserId) {
            throw new NotFoundException("Loadout", id)
        }
        def slots = loadoutSlotRepository.findByLoadout(id)
        [loadout: loadout, slots: slots]
    }

    @Transactional
    LoadoutSlot setSlot(Long ownerUserId, Long loadoutId, String slot, Long itemId) {
        def loadout = loadoutRepository.findById(loadoutId)
            .orElseThrow { new NotFoundException("Loadout", loadoutId) }
        if (loadout.ownerUserId != ownerUserId) throw new ForbiddenException("Not your loadout")
        if (!(slot in SLOTS)) throw new BadRequestException("INVALID_SLOT", "Unknown slot")

        def existing = loadoutSlotRepository.findByLoadout(loadoutId).find { it.slot == slot }
        def target = existing ?: new LoadoutSlot(loadoutId: loadoutId, slot: slot)

        if (itemId != null) {
            def item = itemRepository.findById(itemId).orElse(null)
            if (item == null) throw new NotFoundException("Item", itemId)
            target.itemId = item.id
            target.itemName = item.name
            target.itemEmoji = item.iconEmoji
            target.snapshotPrice = item.lowestPrice ?: BigDecimal.ZERO
        } else {
            target.itemId = null
            target.itemName = null
            target.itemEmoji = null
            target.snapshotPrice = BigDecimal.ZERO
        }
        loadoutSlotRepository.save(target)
        recalcTotal(loadout)
        target
    }

    @Transactional
    LoadoutSlot toggleLock(Long ownerUserId, Long loadoutId, String slot) {
        def loadout = loadoutRepository.findById(loadoutId)
            .orElseThrow { new NotFoundException("Loadout", loadoutId) }
        if (loadout.ownerUserId != ownerUserId) throw new ForbiddenException("Not your loadout")
        def target = loadoutSlotRepository.findByLoadout(loadoutId).find { it.slot == slot }
        if (target == null) throw new NotFoundException("Slot", 0)
        target.locked = !target.locked
        loadoutSlotRepository.save(target)
    }

    /**
     * Fill every unlocked slot with the cheapest available item in that category
     * without overshooting `budget`. Returns the fresh slots list.
     */
    @Transactional
    List<LoadoutSlot> autoGenerate(Long ownerUserId, Long loadoutId, BigDecimal budget) {
        def loadout = loadoutRepository.findById(loadoutId)
            .orElseThrow { new NotFoundException("Loadout", loadoutId) }
        if (loadout.ownerUserId != ownerUserId) throw new ForbiddenException("Not your loadout")

        def slots = loadoutSlotRepository.findByLoadout(loadoutId)
        def remaining = budget ?: new BigDecimal("10000")
        def onePage = org.springframework.data.domain.PageRequest.of(0, 1)

        slots.each { slot ->
            if (slot.locked && slot.itemId != null) return
            def category = slot.slot == 'Wild' ? '' : slot.slot
            // One indexed SELECT per slot — cheapest item in the category
            // that fits the remaining budget. The old path loaded every
            // catalogue row into memory and filtered per slot; now we
            // fetch exactly 1 row via `PageRequest.of(0, 1)`.
            def pool = itemRepository.findCheapestInBudget(category, remaining, onePage)
            def pick = pool.isEmpty() ? null : pool.first()
            if (pick != null) {
                slot.itemId = pick.id
                slot.itemName = pick.name
                slot.itemEmoji = pick.iconEmoji
                slot.snapshotPrice = pick.lowestPrice
                remaining = remaining - pick.lowestPrice
            }
            loadoutSlotRepository.save(slot)
        }
        recalcTotal(loadout)
        loadoutSlotRepository.findByLoadout(loadoutId)
    }

    @Transactional
    void delete(Long ownerUserId, Long loadoutId) {
        def loadout = loadoutRepository.findById(loadoutId)
            .orElseThrow { new NotFoundException("Loadout", loadoutId) }
        if (loadout.ownerUserId != ownerUserId) throw new ForbiddenException("Not your loadout")
        loadoutSlotRepository.deleteByLoadoutId(loadoutId)
        loadoutRepository.delete(loadout)
    }

    /**
     * Toggle favorite state for a (user, loadout) pair. Hitting this
     * endpoint with a user who has already favorited removes the star;
     * a fresh user adds one. Requires a logged-in user — the controller
     * enforces auth before we get here. The aggregate `favorites` count
     * on the Loadout row is kept in sync from the junction table so
     * the Discover sort doesn't need to JOIN on every query. Returns a
     * map with the new count and whether the viewer is now favoriting.
     *
     * Private loadouts cannot be favorited by non-owners — same
     * enumeration defense as getWithSlots.
     */
    @Transactional
    Map toggleFavorite(Long viewerUserId, Long loadoutId) {
        if (viewerUserId == null) throw new UnauthorizedException()
        def loadout = loadoutRepository.findById(loadoutId)
            .orElseThrow { new NotFoundException("Loadout", loadoutId) }
        if (loadout.visibility == 'PRIVATE' && loadout.ownerUserId != viewerUserId) {
            throw new NotFoundException("Loadout", loadoutId)
        }

        def existing = loadoutFavoriteRepository.findByUserAndLoadout(viewerUserId, loadoutId)
        boolean favorited
        if (existing != null) {
            loadoutFavoriteRepository.deleteByUserAndLoadout(viewerUserId, loadoutId)
            favorited = false
        } else {
            loadoutFavoriteRepository.save(new LoadoutFavorite(
                userId:    viewerUserId,
                loadoutId: loadoutId
            ))
            favorited = true
        }

        long count = loadoutFavoriteRepository.countByLoadout(loadoutId)
        loadout.favorites = (int) count
        loadoutRepository.save(loadout)

        [id: loadoutId, favorites: (int) count, favorited: favorited]
    }

    private void recalcTotal(Loadout loadout) {
        def slots = loadoutSlotRepository.findByLoadout(loadout.id)
        def total = slots.sum { it.snapshotPrice ?: BigDecimal.ZERO } ?: BigDecimal.ZERO
        loadout.totalValue = total instanceof BigDecimal ? total : new BigDecimal(total.toString())
        loadout.updatedAt = System.currentTimeMillis()
        loadoutRepository.save(loadout)
    }
}
