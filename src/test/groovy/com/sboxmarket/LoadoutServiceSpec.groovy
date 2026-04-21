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

    // ── update ────────────────────────────────────────────────────

    def "update applies partial patch — name-only leaves description + visibility alone"() {
        given:
        def loadout = new Loadout(id: 1L, ownerUserId: 10L, visibility: 'PRIVATE',
            name: 'Old name', description: 'keep me', totalValue: BigDecimal.ZERO)
        loadoutRepository.findById(1L) >> Optional.of(loadout)
        loadoutRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.update(10L, 1L, 'Fresh name', null, null)

        then:
        result.name == 'Fresh name'
        result.description == 'keep me'
        result.visibility == 'PRIVATE'
    }

    def "update lets owner flip visibility PRIVATE → PUBLIC (the clone-then-publish flow)"() {
        given:
        def loadout = new Loadout(id: 1L, ownerUserId: 10L, visibility: 'PRIVATE', name: 'x')
        loadoutRepository.findById(1L) >> Optional.of(loadout)
        loadoutRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.update(10L, 1L, null, null, 'PUBLIC')

        then:
        result.visibility == 'PUBLIC'
    }

    def "update forbids a non-owner"() {
        given:
        loadoutRepository.findById(_) >> Optional.of(new Loadout(id: 1L, ownerUserId: 10L))

        when:
        service.update(99L, 1L, 'Hijack', null, null)

        then:
        thrown(ForbiddenException)
    }

    def "update rejects an empty name (after sanitization)"() {
        given:
        def loadout = new Loadout(id: 1L, ownerUserId: 10L)
        loadoutRepository.findById(1L) >> Optional.of(loadout)

        when:
        service.update(10L, 1L, '', null, null)

        then:
        thrown(BadRequestException)
    }

    def "update rejects an unknown visibility value"() {
        given:
        loadoutRepository.findById(1L) >> Optional.of(new Loadout(id: 1L, ownerUserId: 10L))

        when:
        service.update(10L, 1L, null, null, 'UNLISTED')

        then:
        thrown(BadRequestException)
    }

    // ── clone ─────────────────────────────────────────────────────

    def "clone copies a PUBLIC loadout into a fresh PRIVATE one for a different user"() {
        given:
        def source = new Loadout(id: 5L, ownerUserId: 10L, visibility: 'PUBLIC',
            name: 'Street Runner', description: 'fast + cheap',
            totalValue: new BigDecimal('30'))
        def sourceSlots = [
            new LoadoutSlot(loadoutId: 5L, slot: 'Hats',   itemId: 1L, itemName: 'Cap', itemEmoji: '🧢', snapshotPrice: new BigDecimal('10'), locked: true),
            new LoadoutSlot(loadoutId: 5L, slot: 'Shirts', itemId: 2L, itemName: 'Tee', itemEmoji: '👕', snapshotPrice: new BigDecimal('20'))
        ]
        def hat = new Item(id: 1L, name: 'Cap', iconEmoji: '🧢', lowestPrice: new BigDecimal('12'))  // catalogue moved
        def tee = new Item(id: 2L, name: 'Tee', iconEmoji: '👕', lowestPrice: new BigDecimal('25'))  // catalogue moved
        loadoutRepository.findById(5L) >> Optional.of(source)
        loadoutSlotRepository.findByLoadout(5L) >> sourceSlots
        itemRepository.findById(1L) >> Optional.of(hat)
        itemRepository.findById(2L) >> Optional.of(tee)
        def savedLoadouts = []
        def savedSlots = []
        loadoutRepository.save(_) >> { args -> def l = args[0]; if (l.id == null) l.id = 99L; savedLoadouts << l; l }
        loadoutSlotRepository.save(_) >> { args -> savedSlots << args[0]; args[0] }
        loadoutSlotRepository.findByLoadout(99L) >> {
            savedSlots.findAll { it.loadoutId == 99L }
        }

        when:
        def copy = service.clone(77L, 5L, 'Bob')

        then:
        copy.ownerUserId == 77L
        copy.visibility == 'PRIVATE'                    // starts private
        copy.name.endsWith('(copy)')                    // marked as a copy
        copy.description == 'fast + cheap'
        // Two filled slots copied, six empty slots seeded; locks NOT copied.
        savedSlots.size() == 8
        def filled = savedSlots.findAll { it.itemId != null }
        filled*.slot.sort() == ['Hats', 'Shirts']
        filled.every { it.locked == null || !it.locked }
        // Snapshot prices come from the CURRENT catalogue, not the source's cache.
        filled.find { it.slot == 'Hats' }.snapshotPrice == new BigDecimal('12')
        filled.find { it.slot == 'Shirts' }.snapshotPrice == new BigDecimal('25')
    }

    def "clone refuses anonymous callers"() {
        when:
        service.clone(null, 1L, 'anon')

        then:
        thrown(UnauthorizedException)
        0 * loadoutRepository.findById(_)
    }

    def "clone hides PRIVATE source from a third-party cloner behind a 404"() {
        given:
        def source = new Loadout(id: 5L, ownerUserId: 10L, visibility: 'PRIVATE', name: 'Secret')
        loadoutRepository.findById(5L) >> Optional.of(source)

        when:
        service.clone(77L, 5L, 'Bob')

        then:
        thrown(NotFoundException)
        0 * loadoutSlotRepository.findByLoadout(_)
    }

    def "clone lets the owner duplicate their own PRIVATE loadout"() {
        given:
        def source = new Loadout(id: 5L, ownerUserId: 10L, visibility: 'PRIVATE', name: 'My draft')
        loadoutRepository.findById(5L) >> Optional.of(source)
        loadoutSlotRepository.findByLoadout(5L) >> []
        loadoutRepository.save(_) >> { args -> def l = args[0]; l.id = 99L; l }
        loadoutSlotRepository.save(_) >> { args -> args[0] }
        loadoutSlotRepository.findByLoadout(99L) >> []

        when:
        def copy = service.clone(10L, 5L, 'Alice')

        then:
        copy.ownerUserId == 10L
        copy.visibility == 'PRIVATE'
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

    // ── favorited flag on getWithSlots (batch 917) ──────────────────

    def "getWithSlots returns favorited=false for anonymous viewers without hitting the favorites repo"() {
        given:
        def loadout = new Loadout(id: 1L, name: 'x', visibility: 'PUBLIC', ownerUserId: 10L)
        loadoutRepository.findById(1L) >> Optional.of(loadout)
        loadoutSlotRepository.findByLoadout(1L) >> []

        when:
        def result = service.getWithSlots(1L, null)

        then:
        result.favorited == false
        0 * loadoutFavoriteRepository.findByUserAndLoadout(_, _)
    }

    def "getWithSlots returns favorited=false for owners without hitting the favorites repo"() {
        given:
        def loadout = new Loadout(id: 1L, name: 'x', visibility: 'PUBLIC', ownerUserId: 10L)
        loadoutRepository.findById(1L) >> Optional.of(loadout)
        loadoutSlotRepository.findByLoadout(1L) >> []

        when:
        def result = service.getWithSlots(1L, 10L)

        then:
        result.favorited == false
        0 * loadoutFavoriteRepository.findByUserAndLoadout(_, _)
    }

    def "getWithSlots returns favorited=true when a signed-in non-owner has the row starred"() {
        given:
        def loadout = new Loadout(id: 1L, name: 'x', visibility: 'PUBLIC', ownerUserId: 10L)
        loadoutRepository.findById(1L) >> Optional.of(loadout)
        loadoutSlotRepository.findByLoadout(1L) >> []
        loadoutFavoriteRepository.findByUserAndLoadout(77L, 1L) >>
            new LoadoutFavorite(userId: 77L, loadoutId: 1L)

        when:
        def result = service.getWithSlots(1L, 77L)

        then:
        result.favorited == true
    }

    def "getWithSlots returns favorited=false when a signed-in non-owner has NOT starred the row"() {
        given:
        def loadout = new Loadout(id: 1L, name: 'x', visibility: 'PUBLIC', ownerUserId: 10L)
        loadoutRepository.findById(1L) >> Optional.of(loadout)
        loadoutSlotRepository.findByLoadout(1L) >> []
        loadoutFavoriteRepository.findByUserAndLoadout(77L, 1L) >> null

        when:
        def result = service.getWithSlots(1L, 77L)

        then:
        result.favorited == false
    }

    // ── listFavorites (batch 303) ───────────────────────────────────

    def "listFavorites returns loadouts in the order ids came back from the favorite repo"() {
        given:
        loadoutFavoriteRepository.findLoadoutIdsByUser(10L) >> [5L, 3L, 9L]
        def a = new Loadout(id: 5L, name: 'A', visibility: 'PUBLIC', ownerUserId: 99L)
        def b = new Loadout(id: 3L, name: 'B', visibility: 'PUBLIC', ownerUserId: 98L)
        def c = new Loadout(id: 9L, name: 'C', visibility: 'PUBLIC', ownerUserId: 97L)
        // Repo returns in arbitrary order; service must re-order by the id list.
        loadoutRepository.findAllById([5L, 3L, 9L]) >> [c, a, b]

        when:
        def out = service.listFavorites(10L)

        then:
        out*.id == [5L, 3L, 9L]
    }

    def "listFavorites drops loadouts the user can no longer access (re-privatized by someone else)"() {
        given:
        loadoutFavoriteRepository.findLoadoutIdsByUser(10L) >> [5L, 3L]
        def visible = new Loadout(id: 5L, name: 'A', visibility: 'PUBLIC', ownerUserId: 99L)
        def reprivatized = new Loadout(id: 3L, name: 'B', visibility: 'PRIVATE', ownerUserId: 99L)
        loadoutRepository.findAllById([5L, 3L]) >> [visible, reprivatized]

        when:
        def out = service.listFavorites(10L)

        then:
        out*.id == [5L]
    }

    def "listFavorites keeps the user's OWN re-privatized favorites visible"() {
        given:
        loadoutFavoriteRepository.findLoadoutIdsByUser(10L) >> [7L]
        def ownedPrivate = new Loadout(id: 7L, name: 'Mine', visibility: 'PRIVATE', ownerUserId: 10L)
        loadoutRepository.findAllById([7L]) >> [ownedPrivate]

        when:
        def out = service.listFavorites(10L)

        then:
        out*.id == [7L]
    }

    def "listFavorites returns empty when the user has no favorites, without a second repo hit"() {
        given:
        loadoutFavoriteRepository.findLoadoutIdsByUser(10L) >> []

        when:
        def out = service.listFavorites(10L)

        then:
        out == []
        0 * loadoutRepository.findAllById(_)
    }

    def "listFavorites short-circuits on null user id without hitting either repo"() {
        when:
        def out = service.listFavorites(null)

        then:
        out == []
        0 * loadoutFavoriteRepository.findLoadoutIdsByUser(_)
        0 * loadoutRepository.findAllById(_)
    }

    // ── adminDelete (batch 583) ─────────────────────────────────────

    def "adminDelete removes the loadout + slots regardless of owner"() {
        given:
        def loadout = new Loadout(id: 42L, ownerUserId: 10L, name: 'bad name')
        loadoutRepository.findById(42L) >> Optional.of(loadout)

        when:
        service.adminDelete(1L, 42L, 'TOS violation')

        then:
        1 * loadoutSlotRepository.deleteByLoadoutId(42L)
        1 * loadoutRepository.delete(loadout)
    }

    def "adminDelete pushes LOADOUT_DELETED to the owner with the staff reason"() {
        given:
        def notifier = Mock(com.sboxmarket.service.NotificationService)
        def auditor = Mock(com.sboxmarket.service.AuditService)
        service.notificationService = notifier
        service.auditService = auditor
        def loadout = new Loadout(id: 42L, ownerUserId: 10L, name: 'bad name')
        loadoutRepository.findById(42L) >> Optional.of(loadout)

        when:
        service.adminDelete(1L, 42L, 'Violates community guidelines')

        then:
        1 * notifier.push(10L, 'LOADOUT_DELETED', _, 'Violates community guidelines', 42L, '/loadout')
        1 * auditor.log('LOADOUT_DELETED_STAFF', 1L, 10L, 42L, _)
    }

    def "adminDelete 404s for an unknown loadout id"() {
        given:
        loadoutRepository.findById(999L) >> Optional.empty()

        when:
        service.adminDelete(1L, 999L, 'note')

        then:
        thrown(NotFoundException)
    }
}
