package com.sboxmarket.controller

import com.sboxmarket.dto.request.CreateLoadoutRequest
import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.Loadout
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.LoadoutService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/loadouts")
@Slf4j
class LoadoutController {

    @Autowired LoadoutService loadoutService
    @Autowired SteamUserRepository steamUserRepository

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    @GetMapping("/discover")
    ResponseEntity<List<Loadout>> discover(@RequestParam(required = false) String search,
                                           // Batch 987 — `q` alias for cross-endpoint
                                           // param consistency (batches 985-986).
                                           @RequestParam(required = false) String q) {
        if ((search == null || search.isBlank()) && q != null && !q.isBlank()) search = q
        if (search != null) search = search.replace('\u0000', '')
        if (search != null && search.length() > 100) search = search.substring(0, 100)
        ResponseEntity.ok()
                .header("Cache-Control", "public, max-age=60")
                .body(loadoutService.listPublic(search))
    }

    @GetMapping("/mine")
    ResponseEntity<List<Loadout>> mine(HttpServletRequest req) {
        ResponseEntity.ok(loadoutService.listMine(requireUser(req)))
    }

    /** Loadouts the caller has favorited, newest-favorite first. Closes
     *  the favorite loop — previously users could star loadouts but had
     *  no way to re-find them without walking Discover again. Signed-in
     *  only; anon viewers get 401 because the favorite-set is per-user
     *  PII. Private re-privatized loadouts are filtered server-side. */
    @GetMapping("/favorites")
    ResponseEntity<List<Loadout>> favorites(HttpServletRequest req) {
        ResponseEntity.ok(loadoutService.listFavorites(requireUser(req)))
    }

    @GetMapping("/{id}")
    ResponseEntity<Map> get(@PathVariable Long id, HttpServletRequest req) {
        def viewer = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        // Boss QA cycle 4 B1 — when the requested id is missing OR private-
        // from-this-viewer, fall back to the lowest-id PUBLIC loadout instead
        // of returning a `{notFound: true}` sentinel. The H2 IDENTITY sequence
        // can advance past 1-2 if a user-created loadout was deleted before
        // the public seed ran, leaving /loadout/1 visually broken. Returning
        // the curated lowest-id public loadout makes deep-link URLs always
        // render something useful while preserving anti-enumeration: every
        // missing-or-private id returns the same fallback shape, so an
        // attacker can't tell missing from private.
        try {
            return ResponseEntity.ok()
                .header('Cache-Control', 'no-cache, must-revalidate')
                .body(loadoutService.getWithSlots(id, viewer))
        } catch (com.sboxmarket.exception.NotFoundException ignore) {
            def publics = loadoutService.listPublic(null)
            if (publics != null && !publics.isEmpty()) {
                def fallback = publics.sort { a, b -> (a?.id ?: 0L) <=> (b?.id ?: 0L) }.first()
                try {
                    Map body = (Map) loadoutService.getWithSlots(fallback.id, viewer)
                    body.put('redirectedFrom', id)
                    return ResponseEntity.ok()
                        .header('Cache-Control', 'no-cache, must-revalidate')
                        .body(body)
                } catch (com.sboxmarket.exception.NotFoundException ignore2) {
                    // Race — public loadout vanished between listing and fetch.
                    // Fall through to the legacy sentinel so the SPA can render
                    // its branded "not found" empty state.
                }
            }
            return ResponseEntity.ok()
                .header('Cache-Control', 'no-cache, must-revalidate')
                .body([notFound: true, id: id])
        }
    }

    @PostMapping
    ResponseEntity<Loadout> create(@Valid @RequestBody CreateLoadoutRequest body, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        ResponseEntity.ok(loadoutService.create(uid, user.displayName ?: "Player",
            body.name, body.description, body.visibility))
    }

    @PutMapping("/{id}/slot/{slot}")
    ResponseEntity<Map> setSlot(@PathVariable Long id, @PathVariable String slot,
                                @RequestBody Map body, HttpServletRequest req) {
        def itemId = body?.itemId == null ? null : Long.valueOf(body.itemId.toString())
        def s = loadoutService.setSlot(requireUser(req), id, slot, itemId)
        ResponseEntity.ok([slot: s.slot, itemId: s.itemId, itemName: s.itemName, snapshotPrice: s.snapshotPrice])
    }

    @PostMapping("/{id}/slot/{slot}/lock")
    ResponseEntity<Map> toggleLock(@PathVariable Long id, @PathVariable String slot, HttpServletRequest req) {
        def s = loadoutService.toggleLock(requireUser(req), id, slot)
        ResponseEntity.ok([slot: s.slot, locked: s.locked])
    }

    @PostMapping("/{id}/generate")
    ResponseEntity<List> autoGenerate(@PathVariable Long id, @RequestBody(required = false) Map body, HttpServletRequest req) {
        BigDecimal budget = null
        if (body?.budget != null) {
            try {
                budget = new BigDecimal(body.budget.toString())
            } catch (NumberFormatException ignored) {
                throw new com.sboxmarket.exception.BadRequestException("INVALID_BUDGET", "budget must be a valid number")
            }
            if (budget <= BigDecimal.ZERO) {
                throw new com.sboxmarket.exception.BadRequestException("INVALID_BUDGET", "budget must be positive")
            }
            if (budget > new BigDecimal("100000")) {
                throw new com.sboxmarket.exception.BadRequestException("BUDGET_TOO_HIGH", "budget must not exceed \$100,000")
            }
        }
        ResponseEntity.ok(loadoutService.autoGenerate(requireUser(req), id, budget))
    }

    /**
     * Duplicate a PUBLIC loadout (or the viewer's own private one) into the
     * viewer's stable of loadouts. The copy starts as PRIVATE and unlocked
     * so the new owner can rename + retune before publishing. Returns the
     * fresh Loadout so the frontend can navigate straight to the copy.
     */
    /**
     * Owner-only metadata update. Accepts any subset of { name, description,
     * visibility } — unsupplied keys are left untouched. Used both for the
     * "Rename" affordance on the owner's loadout view and for flipping a
     * cloned PRIVATE loadout to PUBLIC once the owner's happy with it.
     */
    @PutMapping("/{id}")
    ResponseEntity<Loadout> update(@PathVariable Long id, @RequestBody Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def name        = body?.name        as String
        def description = body?.description as String
        def visibility  = body?.visibility  as String
        ResponseEntity.ok(loadoutService.update(uid, id, name, description, visibility))
    }

    @PostMapping("/{id}/clone")
    ResponseEntity<Loadout> clone(@PathVariable Long id, HttpServletRequest req) {
        def uid = requireUser(req)
        def user = steamUserRepository.findById(uid).orElseThrow { new UnauthorizedException("Unknown user") }
        ResponseEntity.ok(loadoutService.clone(uid, id, user.displayName ?: "Player"))
    }

    @PostMapping("/{id}/favorite")
    ResponseEntity<Map> favorite(@PathVariable Long id, HttpServletRequest req) {
        ResponseEntity.ok(loadoutService.toggleFavorite(requireUser(req), id))
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Map> delete(@PathVariable Long id, HttpServletRequest req) {
        loadoutService.delete(requireUser(req), id)
        ResponseEntity.ok([ok: true])
    }
}
