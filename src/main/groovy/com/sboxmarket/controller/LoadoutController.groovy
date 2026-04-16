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
    ResponseEntity<List<Loadout>> discover(@RequestParam(required = false) String search) {
        ResponseEntity.ok(loadoutService.listPublic(search))
    }

    @GetMapping("/mine")
    ResponseEntity<List<Loadout>> mine(HttpServletRequest req) {
        ResponseEntity.ok(loadoutService.listMine(requireUser(req)))
    }

    @GetMapping("/{id}")
    ResponseEntity<Map> get(@PathVariable Long id, HttpServletRequest req) {
        def viewer = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        ResponseEntity.ok(loadoutService.getWithSlots(id, viewer))
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
