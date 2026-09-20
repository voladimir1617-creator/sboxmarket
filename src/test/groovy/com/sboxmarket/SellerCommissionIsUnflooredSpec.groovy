package com.sboxmarket

import com.sboxmarket.model.Trade
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.TradeProtectionService
import com.sboxmarket.service.TradeService
import com.sboxmarket.service.security.AdminAuthorization
import com.sboxmarket.service.security.BanGuard
import com.sboxmarket.service.TextSanitizer
import spock.lang.Specification

/**
 * Two 2% rates that are NOT one rate written twice.
 *
 * <h3>The report, and the correction</h3>
 *
 * Flagged 2026-09-20 as a fee defect: "{@code TradeService.FEE_RATE} is a 2%
 * with no floor while {@code TradeProtectionService} floors the identical 2%
 * at USD 0.25 — the same rate implemented two ways." The premise is wrong,
 * and the check is worth keeping anyway.
 *
 * They are two DIFFERENT charges that share a headline number:
 *
 * <ul>
 *   <li>{@code FEE_RATE} is the platform's commission. Compulsory, taken
 *       from the SELLER on every release, and the divisor of the payout
 *       break-even in {@code PlatformLedgerService.perAccountBreakEvenGmv}.
 *       Flooring it is a price rise on every trade and breaks that
 *       derivation.</li>
 *   <li>{@code PROTECTION_RATE} is an OPTIONAL premium the BUYER pays to
 *       opt in to cover. A floor is coherent on a product nobody is forced
 *       to buy.</li>
 * </ul>
 *
 * <h3>Why a test and not just a comment</h3>
 *
 * The real hazard is the "tidy-up" commit: someone reads two {@code 0.02}
 * constants, concludes one is a copy, and unifies them — which either adds a
 * USD 0.25 floor to every sale on the platform or removes the floor from the
 * insurance product. These pin BOTH behaviours so that commit fails here
 * with the reason attached, instead of shipping a silent repricing.
 *
 * <h3>The consequence that IS worth knowing</h3>
 *
 * The commission is unfloored AND rounded HALF_UP, so a trade priced below
 * USD 0.25 yields a fee of USD 0.00. The platform runs those trades for
 * nothing. That is pinned below as a deliberate pricing fact rather than
 * left to be rediscovered as a surprise.
 */
class SellerCommissionIsUnflooredSpec extends Specification {

    TradeRepository       tradeRepository       = Mock()
    WalletRepository      walletRepository      = Mock()
    TransactionRepository transactionRepository = Mock()
    SteamUserRepository   steamUserRepository   = Mock()
    BanGuard              banGuard              = Mock()
    AdminAuthorization    adminAuthorization    = Mock()
    TextSanitizer         textSanitizer         = Mock()

    TradeService trades = new TradeService(
        tradeRepository:       tradeRepository,
        walletRepository:      walletRepository,
        transactionRepository: transactionRepository,
        steamUserRepository:   steamUserRepository,
        banGuard:              banGuard,
        adminAuthorization:    adminAuthorization,
        textSanitizer:         textSanitizer,
        autoReleaseDays:       8L,
        sellerResponseDays:    3L
    )

    TradeProtectionService protection = new TradeProtectionService()

    List<Trade> saved = []

    def setup() {
        tradeRepository.save(_) >> { Trade t -> saved << t; t.id = saved.size() as Long; t }
    }

    /** The commission actually written onto a real trade row. */
    private BigDecimal commissionOn(BigDecimal price) {
        saved.clear()
        trades.open(100L, 1L, 'Wizard Hat', 10L, 500L, 20L, 600L, price)
        saved[0].feeAmount
    }

    // ── The commission has no floor ─────────────────────────────────

    def "the seller commission is a flat 2% with no floor under it"() {
        expect: "no MIN_FEE anywhere in this leg — 2% is 2% all the way down"
        commissionOn(new BigDecimal('100.00')) == new BigDecimal('2.00')
        commissionOn(new BigDecimal('10.00'))  == new BigDecimal('0.20')
        commissionOn(new BigDecimal('1.00'))   == new BigDecimal('0.02')
    }

    def "below the rounding floor the platform earns nothing, and that is the shipped price"() {
        expect: "2% of \$0.12 is \$0.0024, which HALF_UP to cents is zero"
        commissionOn(new BigDecimal('0.12')) == new BigDecimal('0.00')

        and: "\$0.25 is the first price that yields a cent"
        commissionOn(new BigDecimal('0.24')) == new BigDecimal('0.00')
        commissionOn(new BigDecimal('0.25')) == new BigDecimal('0.01')

        and: "if this ever becomes a floored fee, it is a repricing of the whole book " +
             "and must be a deliberate decision, not a tidy-up of two similar constants"
        commissionOn(new BigDecimal('0.12')) != TradeProtectionService.MIN_FEE
    }

    // ── The protection premium does have one ────────────────────────

    def "the protection premium IS floored, because it is an opt-in product"() {
        expect: "2% above the floor"
        protection.quote(new BigDecimal('100.00')) == new BigDecimal('2.00')

        and: "the floor below it"
        protection.quote(new BigDecimal('10.00')) == new BigDecimal('0.25')
        protection.quote(new BigDecimal('0.12'))  == new BigDecimal('0.25')

        and: "the floor is a real constant, not an accident of rounding"
        TradeProtectionService.MIN_FEE == new BigDecimal('0.25')
    }

    // ── The divergence, stated as one assertion ─────────────────────

    def "on the same cheap item the two charges disagree, deliberately"() {
        given: "an item priced below the protection floor"
        def price = new BigDecimal('5.00')

        expect: "the commission is the plain percentage"
        commissionOn(price) == new BigDecimal('0.10')

        and: "the optional premium is the floor, 2.5x the commission on the same item"
        protection.quote(price) == new BigDecimal('0.25')

        and: "they share a rate and nothing else — and the shared rate is not a shared constant"
        TradeService.FEE_RATE == TradeProtectionService.PROTECTION_RATE
        !TradeService.FEE_RATE.is(TradeProtectionService.PROTECTION_RATE)
    }

    def "the commission rate is the one the payout break-even divides by"() {
        expect: "there is exactly one take rate, and the ledger reads it rather than copying it"
        new com.sboxmarket.service.PlatformLedgerService().takeRate() == TradeService.FEE_RATE

        and: "the protection premium is NOT that rate's second home — a change to the " +
             "insurance price must not silently move the payout break-even"
        new com.sboxmarket.service.PlatformLedgerService().takeRate() != TradeProtectionService.MIN_FEE
    }
}
