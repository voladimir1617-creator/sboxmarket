package com.sboxmarket

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
import com.sboxmarket.service.LoadoutService
import com.sboxmarket.service.TextSanitizer
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit coverage for the Loadout Lab.
 *
 * create: sanitises + seeds 8 empty slots, defaults visibility to PUBLIC
 * setSlot: ownership check + pull item details snapshot
 * toggleLock: lock/unlock state flip
 * autoGenerate: budget-aware cheapest-per-category selection, respects locks
 * delete: ownership check
 * favorite: counter bump
 */
class LoadoutServiceSpec extends Specification {

    LoadoutRepository         loadoutRepository         = Mock()
    LoadoutSlotRepository     loadoutSlotRepository     = Mock()
    LoadoutFavoriteRepository loadoutFavoriteRepository = Mock()
    ItemRepository            itemRepository            = Mock()
    TextSanitizer             textSanitizer = Mock() {
        cleanShort(_) >> { String s -> s }
        medium(_)     >> { String s -> s }
    }
    com.sboxmarket.service.security.BanGuard banGuard = Mock()

    @Subject
    LoadoutService service = new LoadoutService(
        loadoutRepository        : loadoutRepository,
        loadoutSlotRepository    : loadoutSlotRepository,
        loadoutFavoriteRepository: loadoutFavoriteRepository,
        itemRepository           : itemRepository,
        textSanitizer            : textSanitizer,
        banGuard                 : banGuard
    )

    // ── create ────────────────────────────────────────────────────

    def "create saves the loadout and seeds 8 empty slots"() {
        given:
        def savedSlots = []
        loadoutRepository.save(_) >> { args -> def l = args[0]; l.id = 1L; l }
        loadoutSlotRepository.save(_) >> { args -> savedSlots << args[0]; args[0] }

        when:
        def loadout = service.create(10L, 'Alice', 'My Crates', 'A description', 'PUBLIC')

        then:
        loadout.name == 'My Crates'
        loadout.visibility == 'PUBLIC'
        savedSlots.size() == 8
        savedSlots*.slot.containsAll(['Hats','Jackets','Shirts','Pants','Gloves','Boots','Accessories','Wild'])
    }

    def "create defaults visibility to PUBLIC for unknown values"() {
        given:
        loadoutRepository.save(_) >> { args -> def l = args[0]; l.id = 1L; l }
        loadoutSlotRepository.save(_) >> { args -> args[0] }

        when:
        def loadout = service.create(10L, 'Alice', 'Crate', '', 'DRAFT')

        then:
        loadout.visibility == 'PUBLIC'
    }

    def "create refuses empty loadout name"() {
        given:
        textSanitizer.cleanShort(_) >> { String s -> s == '' ? '' : s }

        when:
        service.create(10L, 'Alice', '', 'body', 'PUBLIC')

        then:
        thrown(BadRequestException)
    }

    // ── setSlot ───────────────────────────────────────────────────

    def "setSlot picks up item details snapshot for the owner"() {
        given:
        def loadout = new Loadout(id: 1L, ownerUserId: 10L)
        def item = new Item(id: 42L, name: 'Wizard Hat', iconEmoji: '🧙', lowestPrice: new BigDecimal("30"))
        loadoutRepository.findById(1L) >> Optional.of(loadout)
        loadoutSlotRepository.findByLoadout(1L) >> [new LoadoutSlot(loadoutId: 1L, slot: 'Hats')]
        itemRepository.findById(42L) >> Optional.of(item)
        loadoutSlotRepository.save(_) >> { args -> args[0] }
        loadoutRepository.save(_) >> { args -> args[0] }

        when:
        def slot = service.setSlot(10L, 1L, 'Hats', 42L)

        then:
        slot.itemId == 42L
        slot.itemName == 'Wizard Hat'
        slot.itemEmoji == '🧙'
        slot.snapshotPrice == new BigDecimal("30")
    }

    def "setSlot clears the slot when itemId is null"() {
        given:
        def loadout = new Loadout(id: 1L, ownerUserId: 10L)
        loadoutRepository.findById(_) >> Optional.of(loadout)
        loadoutSlotRepository.findByLoadout(_) >> [new LoadoutSlot(loadoutId: 1L, slot: 'Hats', itemId: 99L, itemName: 'old')]
        loadoutSlotRepository.save(_) >> { args -> args[0] }
        loadoutRepository.save(_) >> { args -> args[0] }

        when:
        def slot = service.setSlot(10L, 1L, 'Hats', null)

        then:
        slot.itemId == null
        slot.itemName == null
        slot.snapshotPrice == BigDecimal.ZERO
    }

    def "setSlot forbids a non-owner"() {
        given:
        loadoutRepository.findById(_) >> Optional.of(new Loadout(id: 1L, ownerUserId: 10L))

        when:
        service.setSlot(99L, 1L, 'Hats', 42L)

        then:
        thrown(ForbiddenException)
    }

    def "setSlot refuses an unknown slot name"() {
        given:
        loadoutRepository.findById(_) >> Optional.of(new Loadout(id: 1L, ownerUserId: 10L))

        when:
        service.setSlot(10L, 1L, 'Cape', 42L)

        then:
        thrown(BadRequestException)
    }

    def "setSlot 404s on unknown item id"() {
        given:
        loadoutRepository.findById(_) >> Optional.of(new Loadout(id: 1L, ownerUserId: 10L))
        loadoutSlotRepository.findByLoadout(_) >> []
        itemRepository.findById(_) >> Optional.empty()

        when:
        service.setSlot(10L, 1L, 'Hats', 999L)

        then:
        thrown(NotFoundException)
    }

    // ── toggleLock ────────────────────────────────────────────────

    def "toggleLock flips locked state for the owner"() {
        given:
        def slot = new LoadoutSlot(loadoutId: 1L, slot: 'Hats', locked: false)
        loadoutRepository.findById(_) >> Optional.of(new Loadout(id: 1L, ownerUserId: 10L))
        loadoutSlotRepository.findByLoadout(_) >> [slot]
        loadoutSlotRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.toggleLock(10L, 1L, 'Hats')

        then:
        result.locked == true
    }

    // ── autoGenerate ──────────────────────────────────────────────

    def "autoGenerate fills unlocked slots with cheapest items within budget (bug #59)"() {
        given:
        def loadout = new Loadout(id: 1L, ownerUserId: 10L)
        def slotHats = new LoadoutSlot(loadoutId: 1L, slot: 'Hats',    locked: false)
        def slotLocked = new LoadoutSlot(loadoutId: 1L, slot: 'Shirts', locked: true, itemId: 77L, snapshotPrice: new BigDecimal("5"))
        loadoutRepository.findById(_) >> Optional.of(loadout)
        loadoutSlotRepository.findByLoadout(_) >>> [[slotHats, slotLocked], [slotHats, slotLocked]]
        // Indexed JPQL replaces the old findAll() full-catalogue fetch.
        itemRepository.findCheapestInBudget('Hats', _, _) >> [
            new Item(id: 1L, name: 'Cheap Hat', category: 'Hats', lowestPrice: new BigDecimal("10"))
        ]
        loadoutSlotRepository.save(_) >> { args -> args[0] }
        loadoutRepository.save(_) >> { args -> args[0] }

        when:
        service.autoGenerate(10L, 1L, new BigDecimal("20"))

        then:
        // Unlocked Hats slot picked the cheapest (id 1)
        slotHats.itemId == 1L
        slotHats.snapshotPrice == new BigDecimal("10")
        // Locked Shirts slot untouched — query never fires for it
        slotLocked.itemId == 77L
        0 * itemRepository.findAll()
    }

    def "autoGenerate forbids a non-owner"() {
        given:
        loadoutRepository.findById(_) >> Optional.of(new Loadout(id: 1L, ownerUserId: 10L))

        when:
        service.autoGenerate(99L, 1L, new BigDecimal("100"))

        then:
        thrown(ForbiddenException)
    }

    // ── delete ────────────────────────────────────────────────────

    def "delete wipes slots and the loadout for the owner"() {
        given:
        def loadout = new Loadout(id: 1L, ownerUserId: 10L)
        loadoutRepository.findById(1L) >> Optional.of(loadout)

        when:
        service.delete(10L, 1L)

        then:
        1 * loadoutSlotRepository.deleteByLoadoutId(1L)
        1 * loadoutRepository.delete(loadout)
    }

    def "delete forbids non-owner"() {
        given:
        loadoutRepository.findById(_) >> Optional.of(new Loadout(id: 1L, ownerUserId: 10L))

        when:
        service.delete(99L, 1L)

        then:
        thrown(ForbiddenException)
    }

    // ── toggleFavorite ────────────────────────────────────────────

    def "toggleFavorite adds a star for a fresh viewer and bumps the counter"() {
        given:
        def loadout = new Loadout(id: 1L, visibility: 'PUBLIC', ownerUserId: 10L, favorites: 3)
        loadoutRepository.findById(1L) >> Optional.of(loadout)
        loadoutFavoriteRepository.findByUserAndLoadout(77L, 1L) >> null
        loadoutFavoriteRepository.countByLoadout(1L) >> 4L
        loadoutRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.toggleFavorite(77L, 1L)

        then:
        1 * loadoutFavoriteRepository.save({ LoadoutFavorite f -> f.userId == 77L && f.loadoutId == 1L })
        0 * loadoutFavoriteRepository.deleteByUserAndLoadout(*_)
        result == [id: 1L, favorites: 4, favorited: true]
        loadout.favorites == 4
    }

    def "toggleFavorite removes the star when viewer already favorited (no double-counting)"() {
        given:
        def loadout = new Loadout(id: 1L, visibility: 'PUBLIC', ownerUserId: 10L, favorites: 4)
        loadoutRepository.findById(1L) >> Optional.of(loadout)
        loadoutFavoriteRepository.findByUserAndLoadout(77L, 1L) >> new LoadoutFavorite(id: 9L, userId: 77L, loadoutId: 1L)
        loadoutFavoriteRepository.countByLoadout(1L) >> 3L
        loadoutRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.toggleFavorite(77L, 1L)

        then:
        1 * loadoutFavoriteRepository.deleteByUserAndLoadout(77L, 1L) >> 1
        0 * loadoutFavoriteRepository.save(_)
        result == [id: 1L, favorites: 3, favorited: false]
        loadout.favorites == 3
    }

    def "toggleFavorite rejects unauthenticated callers"() {
        when:
        service.toggleFavorite(null, 1L)

        then:
        thrown(UnauthorizedException)
        0 * loadoutRepository.findById(_)
    }

    def "toggleFavorite hides PRIVATE loadouts from non-owners behind a 404"() {
        given:
        def loadout = new Loadout(id: 1L, visibility: 'PRIVATE', ownerUserId: 10L, favorites: 0)
        loadoutRepository.findById(1L) >> Optional.of(loadout)

        when:
        service.toggleFavorite(77L, 1L)

        then:
        thrown(NotFoundException)
        0 * loadoutFavoriteRepository.save(_)
        0 * loadoutFavoriteRepository.deleteByUserAndLoadout(*_)
    }

    def "toggleFavorite lets the owner favorite their own PRIVATE loadout"() {
        given:
        def loadout = new Loadout(id: 1L, visibility: 'PRIVATE', ownerUserId: 10L, favorites: 0)
        loadoutRepository.findById(1L) >> Optional.of(loadout)
        loadoutFavoriteRepository.findByUserAndLoadout(10L, 1L) >> null
        loadoutFavoriteRepository.countByLoadout(1L) >> 1L
        loadoutRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.toggleFavorite(10L, 1L)

        then:
        1 * loadoutFavoriteRepository.save(_)
        result.favorited == true
        result.favorites == 1
    }

    // ── getWithSlots ──────────────────────────────────────────────

    def "getWithSlots returns both halves for a PUBLIC loadout"() {
        given:
        def loadout = new Loadout(id: 1L, name: 'x', visibility: 'PUBLIC', ownerUserId: 10L)
        loadoutRepository.findById(1L) >> Optional.of(loadout)
        loadoutSlotRepository.findByLoadout(1L) >> [new LoadoutSlot(loadoutId: 1L, slot: 'Hats')]

        when:
        def result = service.getWithSlots(1L)

        then:
        result.loadout == loadout
        result.slots.size() == 1
    }

    def "getWithSlots lets the owner see their own PRIVATE loadout"() {
        given:
        def loadout = new Loadout(id: 1L, name: 'x', visibility: 'PRIVATE', ownerUserId: 10L)
        loadoutRepository.findById(1L) >> Optional.of(loadout)
        loadoutSlotRepository.findByLoadout(1L) >> [new LoadoutSlot(loadoutId: 1L, slot: 'Hats')]

        when:
        def result = service.getWithSlots(1L, 10L)

        then:
        result.loadout == loadout
        result.slots.size() == 1
    }

    def "getWithSlots hides PRIVATE loadouts from anonymous viewers behind a 404"() {
        given:
        loadoutRepository.findById(1L) >> Optional.of(
            new Loadout(id: 1L, name: 'x', visibility: 'PRIVATE', ownerUserId: 10L)
        )

        when:
        service.getWithSlots(1L, null)

        then:
        thrown(NotFoundException)
        0 * loadoutSlotRepository.findByLoadout(_)
    }

    def "getWithSlots hides PRIVATE loadouts from a logged-in third party"() {
        given:
        loadoutRepository.findById(1L) >> Optional.of(
            new Loadout(id: 1L, name: 'x', visibility: 'PRIVATE', ownerUserId: 10L)
        )

        when:
        service.getWithSlots(1L, 77L)

        then:
        thrown(NotFoundException)
    }

    def "getWithSlots 404s when loadout id is unknown"() {
        given:
        loadoutRepository.findById(_) >> Optional.empty()

        when:
        service.getWithSlots(999L)

        then:
        thrown(NotFoundException)
    }
}
