package com.sboxmarket

import com.sboxmarket.model.BuyOrder
import com.sboxmarket.repository.BuyOrderRepository
import com.sboxmarket.service.BuyOrderService
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject

/**
 * Regression pin for the cancel↔fill race in
 * {@link BuyOrderService#cancel}.
 *
 * The bug (pre-fix): cancel() read the buy order with a plain
 * `findById` — NO pessimistic row lock — while the auto-fill path
 * (tryMatch / tryFillFromExisting) X-locks the row via
 * `findByIdForUpdate`. A concurrent fill+cancel could:
 *
 *   1. Cancel reads the row, sees ACTIVE, qty=1.
 *   2. Fill acquires the X-lock, sees ACTIVE qty=1, runs
 *      PurchaseService.buy (debits wallet, ships listing → SOLD,
 *      creates the Trade row), decrements qty to 0, sets status to
 *      FILLED, commits.
 *   3. Cancel then writes status=CANCELLED on its stale snapshot,
 *      overwriting the FILLED flag.
 *
 * Net result: the buyer paid AND received the item, but their order
 * shows CANCELLED in the Profile tab and the CSV export — the audit
 * trail lies, and a paranoid buyer reading "CANCELLED" alongside a
 * wallet debit they didn't expect rightly opens a fraud ticket.
 *
 * The fix re-loads the row via `findByIdForUpdate` so cancel and a
 * concurrent fill serialise on the same row lock — when fill wins,
 * cancel's locked re-load sees status=FILLED and bails through the
 * existing NOT_ACTIVE guard.
 */
class BuyOrderServiceCancelFillRaceSpec extends Specification {

    BuyOrderRepository buyOrderRepository = Mock()
    NotificationService notificationService = Mock()
    TextSanitizer textSanitizer = Mock() { cleanShort(_) >> { String s -> s } }
    BanGuard banGuard = Mock()

    @Subject
    BuyOrderService service = new BuyOrderService(
        buyOrderRepository:  buyOrderRepository,
        notificationService: notificationService,
        textSanitizer:       textSanitizer,
        banGuard:            banGuard
    )

    def "cancel re-loads under findByIdForUpdate so a concurrent fill is not clobbered"() {
        given: "the buyer requests cancel on an order that has, between the unlocked probe read and the locked re-load, been filled by a concurrent matching event"
        // Probe row: what the un-locked findById sees BEFORE the fill commits.
        def probe = new BuyOrder(id: 7L, buyerUserId: 10L, status: 'ACTIVE', quantity: 1)
        // Locked re-load: what the X-locked findByIdForUpdate returns AFTER
        // the concurrent fill committed status=FILLED, qty=0. The
        // fix MUST use this row when deciding whether to flip the status,
        // not the stale probe row.
        def lockedFilled = new BuyOrder(id: 7L, buyerUserId: 10L, status: 'FILLED', quantity: 0)
        buyOrderRepository.findById(7L) >> Optional.of(probe)
        buyOrderRepository.findByIdForUpdate(7L) >> lockedFilled

        when:
        service.cancel(10L, 7L)

        then: "the NOT_ACTIVE guard on the locked re-load trips — cancel refuses to clobber the just-FILLED order"
        def e = thrown(com.sboxmarket.exception.BadRequestException)
        e.code == 'NOT_ACTIVE'

        and: "the order is NOT saved with status=CANCELLED — the fill's FILLED status survives"
        0 * buyOrderRepository.save({ BuyOrder o -> o.status == 'CANCELLED' })
    }

    def "cancel still proceeds when the locked re-load shows the order is genuinely still ACTIVE"() {
        given: "no concurrent fill — both the probe and the locked re-load see ACTIVE"
        def probe  = new BuyOrder(id: 7L, buyerUserId: 10L, status: 'ACTIVE', quantity: 1)
        def locked = new BuyOrder(id: 7L, buyerUserId: 10L, status: 'ACTIVE', quantity: 1)
        buyOrderRepository.findById(7L) >> Optional.of(probe)
        buyOrderRepository.findByIdForUpdate(7L) >> locked
        buyOrderRepository.save(_) >> { BuyOrder o -> o }

        when:
        def result = service.cancel(10L, 7L)

        then: "cancel flips the locked row (not the stale probe) and persists"
        result.status == 'CANCELLED'
        result.is(locked)
    }
}
