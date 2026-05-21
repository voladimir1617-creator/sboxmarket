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
        loadoutRepository.findCheapestInBudgetExcluding('Hats', _, _, _) >> [
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

    def "autoGenerate subtracts a locked slot's price from the budget so total spend stays within the ceiling"() {
        given:
        def loadout = new Loadout(id: 1L, ownerUserId: 10L)
        def slotHats   = new LoadoutSlot(loadoutId: 1L, slot: 'Hats',   locked: false)
        def slotLocked = new LoadoutSlot(loadoutId: 1L, slot: 'Shirts', locked: true,
            itemId: 77L, snapshotPrice: new BigDecimal("90"))
        loadoutRepository.findById(_) >> Optional.of(loadout)
        loadoutSlotRepository.findByLoadout(_) >>> [[slotHats, slotLocked], [slotHats, slotLocked]]
        BigDecimal budgetSeen = null
        loadoutRepository.findCheapestInBudgetExcluding('Hats', _, _, _) >> { args ->
            budgetSeen = args[1]
            []
        }
        loadoutSlotRepository.save(_) >> { args -> args[0] }
        loadoutRepository.save(_) >> { args -> args[0] }

        when: "budget is \$100 but a locked slot already holds a \$90 item"
        service.autoGenerate(10L, 1L, new BigDecimal("100"))

        then: "only \$10 of headroom is offered to the cheapest-item query"
        budgetSeen == new BigDecimal("10")
    }

    def "autoGenerate clamps the remaining budget at zero when locked slots already exceed it"() {
        given:
        def loadout = new Loadout(id: 1L, ownerUserId: 10L)
        def slotHats   = new LoadoutSlot(loadoutId: 1L, slot: 'Hats',   locked: false)
        def slotLocked = new LoadoutSlot(loadoutId: 1L, slot: 'Shirts', locked: true,
            itemId: 77L, snapshotPrice: new BigDecimal("250"))
        loadoutRepository.findById(_) >> Optional.of(loadout)
        loadoutSlotRepository.findByLoadout(_) >>> [[slotHats, slotLocked], [slotHats, slotLocked]]
        BigDecimal budgetSeen = null
        loadoutRepository.findCheapestInBudgetExcluding('Hats', _, _, _) >> { args ->
            budgetSeen = args[1]
            []
        }
        loadoutSlotRepository.save(_) >> { args -> args[0] }
        loadoutRepository.save(_) >> { args -> args[0] }

        when:
        service.autoGenerate(10L, 1L, new BigDecimal("100"))

        then: "never a negative budget passed to the query"
        budgetSeen == BigDecimal.ZERO
    }

    def "autoGenerate forbids a non-owner"() {
        given:
        loadoutRepository.findById(_) >> Optional.of(new Loadout(id: 1L, ownerUserId: 10L))

        when:
        service.autoGenerate(99L, 1L, new BigDecimal("100"))

        then:
        thrown(ForbiddenException)
    }

    // ── autoGenerate: never repeats an item (bug #1) ───────────────

    /**
     * Regression for bug #1 — the `Wild` slot uses `category = ''` so the
     * cheapest-item query would otherwise return the globally cheapest
     * catalogue item, which is almost always the same item already placed
     * in its own category slot (e.g. the cheapest Hat lands in BOTH the
     * Hats slot and the Wild slot). The fill must exclude already-picked
     * ids so no item appears twice — CSFloat's loadout lab never repeats.
     */
    def "autoGenerate never places the same item in two slots (the Wild-slot duplicate)"() {
        given:
        def loadout = new Loadout(id: 1L, ownerUserId: 10L)
        def slotHats = new LoadoutSlot(loadoutId: 1L, slot: 'Hats', locked: false)
        def slotWild = new LoadoutSlot(loadoutId: 1L, slot: 'Wild', locked: false)
        loadoutRepository.findById(_) >> Optional.of(loadout)
        loadoutSlotRepository.findByLoadout(_) >>> [[slotHats, slotWild], [slotHats, slotWild]]
        // Catalogue: the cheapest item overall is a Hat ($10). The mock
        // honours the `exclude` arg — exactly what the real JPQL
        // `i.id NOT IN :exclude` clause does — and returns the cheapest
        // *remaining* row.
        def catalogue = [
            new Item(id: 1L, name: 'Cheap Hat',  category: 'Hats',  lowestPrice: new BigDecimal("10")),
            new Item(id: 2L, name: 'Cheap Tee',  category: 'Shirts', lowestPrice: new BigDecimal("15"))
        ]
        loadoutRepository.findCheapestInBudgetExcluding(_, _, _, _) >> { args ->
            String category = args[0]
            BigDecimal budget = args[1]
            Collection<Long> exclude = args[2]
            def hit = catalogue
                .findAll { (category == '' || it.category == category) }
                .findAll { it.lowestPrice <= budget }
                .findAll { !exclude.contains(it.id) }
                .sort { it.lowestPrice }
            hit.isEmpty() ? [] : [hit.first()]
        }
        loadoutSlotRepository.save(_) >> { args -> args[0] }
        loadoutRepository.save(_) >> { args -> args[0] }

        when: "a generous budget that easily covers both items"
        service.autoGenerate(10L, 1L, new BigDecimal("1000"))

        then: "Hats slot took the cheapest Hat"
        slotHats.itemId == 1L
        and: "Wild slot did NOT re-pick that same Hat — it got the next item"
        slotWild.itemId == 2L
        and: "no item id appears in more than one slot"
        [slotHats.itemId, slotWild.itemId].toSet().size() == 2
    }

    /**
     * Bug #1 — the exclude collection handed to the query must grow as
     * slots are filled. The first pick passes a sentinel (never empty,
     * since JPQL forbids `NOT IN ()`); each subsequent pick must include
     * every id picked so far.
     */
    def "autoGenerate feeds each previously-picked id into the exclude set"() {
        given:
        def loadout = new Loadout(id: 1L, ownerUserId: 10L)
        def slotHats   = new LoadoutSlot(loadoutId: 1L, slot: 'Hats',   locked: false)
        def slotShirts = new LoadoutSlot(loadoutId: 1L, slot: 'Shirts', locked: false)
        def slotWild   = new LoadoutSlot(loadoutId: 1L, slot: 'Wild',   locked: false)
        loadoutRepository.findById(_) >> Optional.of(loadout)
        loadoutSlotRepository.findByLoadout(_) >>> [
            [slotHats, slotShirts, slotWild], [slotHats, slotShirts, slotWild]]
        def excludesSeen = []
        loadoutRepository.findCheapestInBudgetExcluding(_, _, _, _) >> { args ->
            String category = args[0]
            excludesSeen << new ArrayList<Long>(args[2] as Collection)
            if (category == 'Hats')   return [new Item(id: 11L, name: 'Hat', category: 'Hats',   lowestPrice: new BigDecimal("5"))]
            if (category == 'Shirts') return [new Item(id: 22L, name: 'Tee', category: 'Shirts', lowestPrice: new BigDecimal("5"))]
            // Wild — anything not already picked
            return [new Item(id: 33L, name: 'Misc', category: 'Boots', lowestPrice: new BigDecimal("5"))]
        }
        loadoutSlotRepository.save(_) >> { args -> args[0] }
        loadoutRepository.save(_) >> { args -> args[0] }

        when:
        service.autoGenerate(10L, 1L, new BigDecimal("1000"))

        then: "first pick excludes only the sentinel (no real ids yet)"
        excludesSeen[0] == [-1L]
        and: "second pick excludes the Hat just placed"
        excludesSeen[1] == [11L]
        and: "third (Wild) pick excludes both prior picks"
        excludesSeen[2].toSet() == [11L, 22L].toSet()
        and: "all three slots ended up with distinct items"
        [slotHats.itemId, slotShirts.itemId, slotWild.itemId].toSet() == [11L, 22L, 33L].toSet()
    }

    /**
     * Bug #1 — a locked, already-filled slot's item must also be excluded
     * so an unlocked slot can't duplicate a locked pick.
     */
    def "autoGenerate excludes a locked slot's item from the unlocked fill"() {
        given:
        def loadout = new Loadout(id: 1L, ownerUserId: 10L)
        def slotLocked = new LoadoutSlot(loadoutId: 1L, slot: 'Hats', locked: true,
            itemId: 99L, snapshotPrice: new BigDecimal("5"))
        def slotWild = new LoadoutSlot(loadoutId: 1L, slot: 'Wild', locked: false)
        loadoutRepository.findById(_) >> Optional.of(loadout)
        loadoutSlotRepository.findByLoadout(_) >>> [[slotLocked, slotWild], [slotLocked, slotWild]]
        Collection<Long> wildExclude = null
        loadoutRepository.findCheapestInBudgetExcluding('', _, _, _) >> { args ->
            wildExclude = args[2] as Collection
            [new Item(id: 7L, name: 'Other', category: 'Boots', lowestPrice: new BigDecimal("5"))]
        }
        loadoutSlotRepository.save(_) >> { args -> args[0] }
        loadoutRepository.save(_) >> { args -> args[0] }

        when:
        service.autoGenerate(10L, 1L, new BigDecimal("1000"))

        then: "the locked slot's item id is in the exclude set for the Wild query"
        wildExclude.contains(99L)
        and: "the Wild slot got a different item, not the locked one"
        slotWild.itemId == 7L
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

    // ── autoGenerate: unlocked slots are cleared when nothing fits ───
    //
    // The documented contract is "budget is the TOTAL set spend" — the
    // locked-slot subtraction exists purely to stop the total overshooting.
    // An unlocked slot the user pre-filled is owned by auto-generate; if no
    // catalogue item fits the remaining budget, leaving the old item in
    // place would push the loadout total past the requested ceiling (the
    // old item's price never counts against `remaining`). So an unfillable
    // unlocked slot must be cleared, not left stale.

    def "autoGenerate clears an unlocked pre-filled slot when nothing fits the budget"() {
        given:
        def loadout = new Loadout(id: 1L, ownerUserId: 10L)
        // Hats slot was hand-filled with an $80 item and left UNLOCKED.
        def slotHats = new LoadoutSlot(loadoutId: 1L, slot: 'Hats', locked: false,
            itemId: 80L, itemName: 'Pricey Hat', itemEmoji: '🎩',
            snapshotPrice: new BigDecimal("80"))
        loadoutRepository.findById(_) >> Optional.of(loadout)
        loadoutSlotRepository.findByLoadout(_) >>> [[slotHats], [slotHats]]
        // Budget is only $30 — no Hat in the catalogue fits.
        loadoutRepository.findCheapestInBudgetExcluding('Hats', _, _, _) >> []
        loadoutSlotRepository.save(_) >> { args -> args[0] }
        loadoutRepository.save(_) >> { args -> args[0] }

        when:
        service.autoGenerate(10L, 1L, new BigDecimal("30"))

        then: "the stale over-budget item is wiped so the total can't overshoot"
        slotHats.itemId == null
        slotHats.itemName == null
        slotHats.itemEmoji == null
        slotHats.snapshotPrice == BigDecimal.ZERO
    }

    def "autoGenerate overwrites (does not clear) an unlocked pre-filled slot when an item fits"() {
        given:
        def loadout = new Loadout(id: 1L, ownerUserId: 10L)
        def slotHats = new LoadoutSlot(loadoutId: 1L, slot: 'Hats', locked: false,
            itemId: 80L, itemName: 'Pricey Hat', snapshotPrice: new BigDecimal("80"))
        loadoutRepository.findById(_) >> Optional.of(loadout)
        loadoutSlotRepository.findByLoadout(_) >>> [[slotHats], [slotHats]]
        loadoutRepository.findCheapestInBudgetExcluding('Hats', _, _, _) >> [
            new Item(id: 5L, name: 'Cheap Hat', category: 'Hats', iconEmoji: '🧢',
                lowestPrice: new BigDecimal("12"))
        ]
        loadoutSlotRepository.save(_) >> { args -> args[0] }
        loadoutRepository.save(_) >> { args -> args[0] }

        when:
        service.autoGenerate(10L, 1L, new BigDecimal("50"))

        then: "the slot is re-picked, not cleared"
        slotHats.itemId == 5L
        slotHats.snapshotPrice == new BigDecimal("12")
    }

    def "autoGenerate clearing an already-empty unlocked slot is a harmless no-op"() {
        given:
        def loadout = new Loadout(id: 1L, ownerUserId: 10L)
        def slotHats = new LoadoutSlot(loadoutId: 1L, slot: 'Hats', locked: false)
        loadoutRepository.findById(_) >> Optional.of(loadout)
        loadoutSlotRepository.findByLoadout(_) >>> [[slotHats], [slotHats]]
        loadoutRepository.findCheapestInBudgetExcluding('Hats', _, _, _) >> []
        loadoutSlotRepository.save(_) >> { args -> args[0] }
        loadoutRepository.save(_) >> { args -> args[0] }

        when:
        service.autoGenerate(10L, 1L, new BigDecimal("5"))

        then:
        slotHats.itemId == null
        slotHats.snapshotPrice == BigDecimal.ZERO
    }

    def "autoGenerate never clears a LOCKED slot even when it would be over budget"() {
        given:
        def loadout = new Loadout(id: 1L, ownerUserId: 10L)
        // Locked + filled: must survive untouched, query never fires for it.
        def slotLocked = new LoadoutSlot(loadoutId: 1L, slot: 'Hats', locked: true,
            itemId: 99L, itemName: 'Kept Hat', snapshotPrice: new BigDecimal("500"))
        loadoutRepository.findById(_) >> Optional.of(loadout)
        loadoutSlotRepository.findByLoadout(_) >>> [[slotLocked], [slotLocked]]
        loadoutSlotRepository.save(_) >> { args -> args[0] }
        loadoutRepository.save(_) >> { args -> args[0] }

        when:
        service.autoGenerate(10L, 1L, new BigDecimal("10"))

        then: "locked slot is preserved as-is"
        slotLocked.itemId == 99L
        slotLocked.itemName == 'Kept Hat'
        slotLocked.snapshotPrice == new BigDecimal("500")
        and: "the catalogue is never queried for a locked slot"
        0 * loadoutRepository.findCheapestInBudgetExcluding('Hats', _, _, _)
    }

    // ── create / clone: per-user loadout cap ────────────────────────

    def "create rejects a user already at the loadout cap"() {
        given:
        loadoutRepository.countByOwnerUserId(10L) >> LoadoutService.MAX_LOADOUTS_PER_USER

        when:
        service.create(10L, 'Alice', 'One more', '', 'PUBLIC')

        then:
        def e = thrown(BadRequestException)
        e.code == 'LOADOUT_CAP'
        0 * loadoutRepository.save(_)
    }

    def "create allows a user one below the cap"() {
        given:
        loadoutRepository.countByOwnerUserId(10L) >> (LoadoutService.MAX_LOADOUTS_PER_USER - 1)
        loadoutRepository.save(_) >> { args -> def l = args[0]; l.id = 1L; l }
        loadoutSlotRepository.save(_) >> { args -> args[0] }

        when:
        def loadout = service.create(10L, 'Alice', 'Just in time', '', 'PUBLIC')

        then:
        loadout.name == 'Just in time'
    }

    def "create skips the cap check entirely for a null owner (internal seeding)"() {
        given:
        loadoutRepository.save(_) >> { args -> def l = args[0]; l.id = 1L; l }
        loadoutSlotRepository.save(_) >> { args -> args[0] }

        when:
        service.create(null, 'system', 'Seed', '', 'PUBLIC')

        then:
        0 * loadoutRepository.countByOwnerUserId(_)
    }

    def "create rejects a banned user before touching the repository"() {
        given:
        banGuard.assertNotBanned(10L) >> { throw new ForbiddenException("banned") }

        when:
        service.create(10L, 'Alice', 'x', '', 'PUBLIC')

        then:
        thrown(ForbiddenException)
        0 * loadoutRepository.countByOwnerUserId(_)
        0 * loadoutRepository.save(_)
    }

    def "clone rejects a cloner already at the loadout cap"() {
        given:
        loadoutRepository.countByOwnerUserId(77L) >> LoadoutService.MAX_LOADOUTS_PER_USER

        when:
        service.clone(77L, 5L, 'Bob')

        then:
        def e = thrown(BadRequestException)
        e.code == 'LOADOUT_CAP'
        0 * loadoutRepository.findById(_)
    }

    def "clone rejects a banned cloner before reading the source loadout"() {
        given:
        banGuard.assertNotBanned(77L) >> { throw new ForbiddenException("banned") }

        when:
        service.clone(77L, 5L, 'Bob')

        then:
        thrown(ForbiddenException)
        0 * loadoutRepository.countByOwnerUserId(_)
        0 * loadoutRepository.findById(_)
    }

    // ── setSlot: total recalculation + locked-slot setting ──────────

    def "setSlot recalculates the loadout total from all slot snapshot prices"() {
        given:
        def loadout = new Loadout(id: 1L, ownerUserId: 10L, totalValue: BigDecimal.ZERO)
        def item = new Item(id: 42L, name: 'Hat', iconEmoji: '🧢', lowestPrice: new BigDecimal("30"))
        loadoutRepository.findById(1L) >> Optional.of(loadout)
        // After save, recalcTotal re-reads the slots — Hats now $30, Pants $20.
        def hatsSlot = new LoadoutSlot(loadoutId: 1L, slot: 'Hats')
        loadoutSlotRepository.findByLoadout(1L) >>> [
            [hatsSlot, new LoadoutSlot(loadoutId: 1L, slot: 'Pants', itemId: 7L, snapshotPrice: new BigDecimal("20"))],
            [new LoadoutSlot(loadoutId: 1L, slot: 'Hats', itemId: 42L, snapshotPrice: new BigDecimal("30")),
             new LoadoutSlot(loadoutId: 1L, slot: 'Pants', itemId: 7L, snapshotPrice: new BigDecimal("20"))]
        ]
        itemRepository.findById(42L) >> Optional.of(item)
        loadoutSlotRepository.save(_) >> { args -> args[0] }
        loadoutRepository.save(_) >> { args -> args[0] }

        when:
        service.setSlot(10L, 1L, 'Hats', 42L)

        then:
        loadout.totalValue == new BigDecimal("50")
    }

    def "setSlot can target a slot row that doesn't exist yet (creates it)"() {
        given:
        def loadout = new Loadout(id: 1L, ownerUserId: 10L)
        def item = new Item(id: 42L, name: 'Hat', iconEmoji: '🧢', lowestPrice: new BigDecimal("9"))
        loadoutRepository.findById(1L) >> Optional.of(loadout)
        // No existing 'Boots' slot in the result set.
        loadoutSlotRepository.findByLoadout(1L) >> []
        itemRepository.findById(42L) >> Optional.of(item)
        LoadoutSlot saved = null
        loadoutSlotRepository.save(_) >> { args -> saved = args[0]; args[0] }
        loadoutRepository.save(_) >> { args -> args[0] }

        when:
        def slot = service.setSlot(10L, 1L, 'Boots', 42L)

        then:
        slot.slot == 'Boots'
        slot.itemId == 42L
        slot.loadoutId == 1L
    }

    def "setSlot 404s for an unknown loadout id"() {
        given:
        loadoutRepository.findById(_) >> Optional.empty()

        when:
        service.setSlot(10L, 999L, 'Hats', 42L)

        then:
        thrown(NotFoundException)
    }

    // ── toggleLock: edge cases ──────────────────────────────────────

    def "toggleLock unlocks a slot that was already locked"() {
        given:
        def slot = new LoadoutSlot(loadoutId: 1L, slot: 'Hats', locked: true)
        loadoutRepository.findById(_) >> Optional.of(new Loadout(id: 1L, ownerUserId: 10L))
        loadoutSlotRepository.findByLoadout(_) >> [slot]
        loadoutSlotRepository.save(_) >> { args -> args[0] }

        when:
        def result = service.toggleLock(10L, 1L, 'Hats')

        then:
        result.locked == false
    }

    def "toggleLock forbids a non-owner"() {
        given:
        loadoutRepository.findById(_) >> Optional.of(new Loadout(id: 1L, ownerUserId: 10L))

        when:
        service.toggleLock(99L, 1L, 'Hats')

        then:
        thrown(ForbiddenException)
        0 * loadoutSlotRepository.save(_)
    }

    def "toggleLock 404s for an unknown loadout id"() {
        given:
        loadoutRepository.findById(_) >> Optional.empty()

        when:
        service.toggleLock(10L, 999L, 'Hats')

        then:
        thrown(NotFoundException)
    }

    def "toggleLock 404s when the named slot row is missing"() {
        given:
        loadoutRepository.findById(_) >> Optional.of(new Loadout(id: 1L, ownerUserId: 10L))
        loadoutSlotRepository.findByLoadout(_) >> [new LoadoutSlot(loadoutId: 1L, slot: 'Hats')]

        when:
        service.toggleLock(10L, 1L, 'Boots')

        then:
        thrown(NotFoundException)
        0 * loadoutSlotRepository.save(_)
    }

    // ── decorate: preview batching ──────────────────────────────────

    def "decorate returns an empty list for null input without hitting any repo"() {
        when:
        def out = service.decorate(null)

        then:
        out == []
        0 * loadoutSlotRepository.findByLoadoutIdIn(_)
        0 * itemRepository.findAllById(_)
    }

    def "decorate returns an empty list for empty input without hitting any repo"() {
        when:
        def out = service.decorate([])

        then:
        out == []
        0 * loadoutSlotRepository.findByLoadoutIdIn(_)
        0 * itemRepository.findAllById(_)
    }

    def "decorate batches slots and item decor in one query each, regardless of loadout count"() {
        given:
        def l1 = new Loadout(id: 1L, name: 'A', visibility: 'PUBLIC', ownerUserId: 10L)
        def l2 = new Loadout(id: 2L, name: 'B', visibility: 'PUBLIC', ownerUserId: 11L)
        def slots = [
            new LoadoutSlot(id: 100L, loadoutId: 1L, slot: 'Hats', itemId: 7L,
                itemName: 'Hat', snapshotPrice: new BigDecimal("10")),
            new LoadoutSlot(id: 101L, loadoutId: 1L, slot: 'Pants'),   // empty
            new LoadoutSlot(id: 102L, loadoutId: 2L, slot: 'Boots', itemId: 8L,
                itemName: 'Boot', snapshotPrice: new BigDecimal("20"))
        ]

        when:
        def out = service.decorate([l1, l2])

        then: "exactly one bulk slot query and one bulk item query"
        1 * loadoutSlotRepository.findByLoadoutIdIn([1L, 2L]) >> slots
        1 * itemRepository.findAllById({ it.toSet() == [7L, 8L].toSet() }) >> [
            new Item(id: 7L, imageUrl: 'hat.png', accentColor: '#aaa'),
            new Item(id: 8L, imageUrl: 'boot.png', accentColor: '#bbb')
        ]

        and: "loadout 1 preview carries only the filled Hats slot, decorated with image + color"
        out[0].id == 1L
        out[0].previewItems.size() == 1
        out[0].previewItems[0].slot == 'Hats'
        out[0].previewItems[0].imageUrl == 'hat.png'
        out[0].previewItems[0].accentColor == '#aaa'
        out[0].filledSlots == 1
        out[0].slotCount == 8

        and: "loadout 2 preview carries its single filled Boots slot"
        out[1].previewItems.size() == 1
        out[1].previewItems[0].slot == 'Boots'
        out[1].previewItems[0].imageUrl == 'boot.png'
    }

    def "decorate orders preview items in canonical slot order, not slot-row id order"() {
        given:
        def l = new Loadout(id: 1L, name: 'A', visibility: 'PUBLIC', ownerUserId: 10L)
        // Rows arrive Boots-before-Hats (e.g. lower id), but canonical order
        // is Hats(0) ... Boots(5). decorate must emit Hats first.
        def slots = [
            new LoadoutSlot(id: 50L, loadoutId: 1L, slot: 'Boots', itemId: 8L, itemName: 'Boot'),
            new LoadoutSlot(id: 51L, loadoutId: 1L, slot: 'Hats',  itemId: 7L, itemName: 'Hat')
        ]
        loadoutSlotRepository.findByLoadoutIdIn(_) >> slots
        itemRepository.findAllById(_) >> []

        when:
        def out = service.decorate([l])

        then:
        out[0].previewItems*.slot == ['Hats', 'Boots']
    }

    def "decorate skips the item query when no slot is filled"() {
        given:
        def l = new Loadout(id: 1L, name: 'A', visibility: 'PUBLIC', ownerUserId: 10L)
        loadoutSlotRepository.findByLoadoutIdIn(_) >> [
            new LoadoutSlot(id: 1L, loadoutId: 1L, slot: 'Hats')   // empty
        ]

        when:
        def out = service.decorate([l])

        then:
        out[0].previewItems == []
        out[0].filledSlots == 0
        0 * itemRepository.findAllById(_)
    }

    def "decorate tolerates a slot whose itemId no longer resolves to an Item"() {
        given:
        def l = new Loadout(id: 1L, name: 'A', visibility: 'PUBLIC', ownerUserId: 10L)
        loadoutSlotRepository.findByLoadoutIdIn(_) >> [
            new LoadoutSlot(id: 1L, loadoutId: 1L, slot: 'Hats', itemId: 404L,
                itemName: 'Ghost', snapshotPrice: new BigDecimal("5"))
        ]
        // The referenced item was deleted from the catalogue.
        itemRepository.findAllById(_) >> []

        when:
        def out = service.decorate([l])

        then: "the slot still appears, falling back to the snapshot name with null decor"
        out[0].previewItems.size() == 1
        out[0].previewItems[0].itemName == 'Ghost'
        out[0].previewItems[0].imageUrl == null
        out[0].previewItems[0].accentColor == null
    }

    // ── listPublic search routing ───────────────────────────────────

    def "listPublic routes to findPublic when no search term is given"() {
        when:
        service.listPublic(null)

        then:
        1 * loadoutRepository.findPublic(_) >> []
        0 * loadoutRepository.searchPublic(_, _)
    }

    def "listPublic routes to searchPublic when a search term is given"() {
        when:
        service.listPublic('runner')

        then:
        1 * loadoutRepository.searchPublic('runner', _) >> []
        0 * loadoutRepository.findPublic(_)
    }

    // ── getWithSlots decorates slots with item image + accent ───────

    def "getWithSlots enriches filled slots with item image and accent color"() {
        given:
        def loadout = new Loadout(id: 1L, name: 'x', visibility: 'PUBLIC', ownerUserId: 10L)
        loadoutRepository.findById(1L) >> Optional.of(loadout)
        loadoutSlotRepository.findByLoadout(1L) >> [
            new LoadoutSlot(id: 5L, loadoutId: 1L, slot: 'Hats', itemId: 7L,
                itemName: 'Hat', snapshotPrice: new BigDecimal("10"), locked: true),
            new LoadoutSlot(id: 6L, loadoutId: 1L, slot: 'Pants')   // empty
        ]
        itemRepository.findAllById([7L]) >> [
            new Item(id: 7L, imageUrl: 'hat.png', accentColor: '#abc')
        ]

        when:
        def result = service.getWithSlots(1L)

        then:
        def hats = result.slots.find { it.slot == 'Hats' }
        hats.itemImageUrl == 'hat.png'
        hats.itemAccentColor == '#abc'
        hats.locked == true
        and: "the empty slot carries null decor and no image"
        def pants = result.slots.find { it.slot == 'Pants' }
        pants.itemImageUrl == null
        pants.itemId == null
    }

    def "getWithSlots skips the item query when every slot is empty"() {
        given:
        def loadout = new Loadout(id: 1L, name: 'x', visibility: 'PUBLIC', ownerUserId: 10L)
        loadoutRepository.findById(1L) >> Optional.of(loadout)
        loadoutSlotRepository.findByLoadout(1L) >> [new LoadoutSlot(id: 1L, loadoutId: 1L, slot: 'Hats')]

        when:
        service.getWithSlots(1L)

        then:
        0 * itemRepository.findAllById(_)
    }

    // ── clone: snapshot price is re-pulled, locks dropped ───────────

    def "clone drops a stale source slot whose item no longer exists in the catalogue"() {
        given:
        def source = new Loadout(id: 5L, ownerUserId: 10L, visibility: 'PUBLIC', name: 'Set')
        def sourceSlots = [
            new LoadoutSlot(loadoutId: 5L, slot: 'Hats', itemId: 404L, itemName: 'Ghost',
                snapshotPrice: new BigDecimal("99"))
        ]
        loadoutRepository.findById(5L) >> Optional.of(source)
        loadoutSlotRepository.findByLoadout(5L) >> sourceSlots
        itemRepository.findById(404L) >> Optional.empty()   // catalogue row gone
        def savedSlots = []
        loadoutRepository.save(_) >> { args -> def l = args[0]; if (l.id == null) l.id = 99L; l }
        loadoutSlotRepository.save(_) >> { args -> savedSlots << args[0]; args[0] }
        loadoutSlotRepository.findByLoadout(99L) >> { savedSlots.findAll { it.loadoutId == 99L } }

        when:
        def copy = service.clone(77L, 5L, 'Bob')

        then: "8 fresh slots seeded, but the Hats slot is empty — the dead item didn't carry over"
        savedSlots.size() == 8
        def hats = savedSlots.find { it.slot == 'Hats' }
        hats.itemId == null
        hats.snapshotPrice == BigDecimal.ZERO
    }

    def "clone truncates an over-long source name before appending the (copy) suffix"() {
        given:
        def longName = 'L' * 140
        def source = new Loadout(id: 5L, ownerUserId: 10L, visibility: 'PUBLIC', name: longName)
        loadoutRepository.findById(5L) >> Optional.of(source)
        loadoutSlotRepository.findByLoadout(5L) >> []
        loadoutRepository.save(_) >> { args -> def l = args[0]; l.id = 99L; l }
        loadoutSlotRepository.save(_) >> { args -> args[0] }
        loadoutSlotRepository.findByLoadout(99L) >> []

        when:
        def copy = service.clone(10L, 5L, 'Alice')

        then: "the final name stays inside the 100-char column limit"
        copy.name.length() <= 100
        copy.name.endsWith('(copy)')
    }
}
