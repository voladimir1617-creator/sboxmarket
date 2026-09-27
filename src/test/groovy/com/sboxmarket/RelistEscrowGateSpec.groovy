package com.sboxmarket

import com.sboxmarket.config.GlobalExceptionHandler
import com.sboxmarket.exception.ApiException
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.Item
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.service.BuyOrderService
import com.sboxmarket.service.SellService
import com.sboxmarket.service.SteamEscrowService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.security.BanGuard
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import spock.lang.Specification
import spock.lang.Subject

/**
 * The escrow pre-flight gate on SellService.relist (POST /api/listings/sell).
 *
 * ── What this prevents ────────────────────────────────────────────────────
 * relist creates its fresh row with a hardcoded status of ACTIVE and never asks
 * for a deposit. The first-list path (SteamInventoryController.listFromSteam)
 * creates PENDING_ESCROW and calls requestDepositForListing, so the bot holds
 * the item before anyone can buy it. With the bot live, that asymmetry made a
 * RELISTED item buyable while the bot held nothing: the buyer pays, a Trade
 * opens, SteamDeliveryService.resolveAssetId finds no custody row, records
 * NO_ASSET_ID, and the sale stalls until the 3-day auto-cancel refunds the
 * buyer. The money is safe; the marketplace advertising an item it cannot
 * deliver is not.
 *
 * ── Why the gate refuses rather than routing into the escrow path ─────────
 * requestDepositForListing needs a CURRENT, REAL, seller-owned Steam asset id
 * to hand the bot. A relist has none — house/platform items never had one, and
 * for a Steam-sourced item the stored assetId belongs to the ORIGINAL seller
 * (Steam reassigns asset ids on every trade, which is exactly why
 * SteamEscrowService.fetchBotInventoryIndex indexes by market_hash_name as well
 * as by id). Feeding either into the escrow path is worse than the gap: relist
 * flips the source row to RELISTED first, the fresh row would hold in
 * PENDING_ESCROW against an unfillable deposit, and sweepStalePendingDeposits
 * would cancel it 24h later WITHOUT returning the item — so the seller's item
 * disappears from their platform inventory for good.
 *
 * ── The gate must NOT fire with the bot off ──────────────────────────────
 * Escrow is disabled whenever STEAM_BOT_BASE_URL is unset — dev, test, CI, and
 * production as it stands today. Listing from inventory is the project's only
 * live seller capability, so every disabled-mode assertion below is a
 * regression guard on real revenue, not a formality.
 */
class RelistEscrowGateSpec extends Specification {

    ListingRepository  listingRepository  = Mock()
    TradeRepository    tradeRepository    = Mock()
    BuyOrderService    buyOrderService    = Mock()
    BanGuard           banGuard           = Mock()
    SteamEscrowService steamEscrowService = Mock()
    TextSanitizer      textSanitizer      = Mock() {
        cleanShort(_) >> { String s -> s }
        clean(_, _) >> { args -> args[0] as String }
    }

    @Subject
    SellService service = new SellService(
        listingRepository : listingRepository,
        tradeRepository   : tradeRepository,
        buyOrderService   : buyOrderService,
        banGuard          : banGuard,
        textSanitizer     : textSanitizer,
        steamEscrowService: steamEscrowService
    )

    /** A SOLD row sitting in user 10's platform inventory, ready to relist. */
    private Listing owned(Map args = [:]) {
        new Listing(
            id          : args.id ?: 50L,
            item        : new Item(id: 1L, name: 'Wizard Hat'),
            price       : new BigDecimal('40'),
            buyerUserId : args.owner ?: 10L,
            sellerUserId: args.originalSeller ?: 99L,
            status      : args.status ?: 'SOLD',
            assetId     : args.assetId,
            rarityScore : new BigDecimal('0.5')
        )
    }

    // ── escrow LIVE: refuse, and refuse before touching anything ──────────

    def "escrow live: relist is refused BEFORE any listing row is read or written"() {
        given:
        steamEscrowService.escrowEnabled >> true

        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal('80'))

        then: 'a 400 the caller can act on, not a 200 for something that did not happen'
        def e = thrown(BadRequestException)
        e.code == 'RELIST_NEEDS_STEAM_DEPOSIT'

        and: 'the message names the path that actually works'
        e.message.contains('Steam tab')

        and: 'no row was even loaded — nothing to half-mutate and nothing to roll back'
        0 * listingRepository.findById(_)
        0 * listingRepository.save(_)

        and: 'the bot was never asked for a deposit it has no asset id to address'
        0 * steamEscrowService.requestDepositForListing(_, _, _)

        and: 'no undeliverable listing was published to buy-order matching'
        0 * buyOrderService.tryMatch(_)
    }

    def "escrow live: the item stays in the seller's inventory — the source row is NOT flipped to RELISTED"() {
        given: 'a real inventory row the seller can still see and still sell from the Steam tab'
        steamEscrowService.escrowEnabled >> true
        def source = owned()
        listingRepository.findById(50L) >> Optional.of(source)

        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal('80'))

        then:
        thrown(BadRequestException)

        and: 'still SOLD — findOwnedBy keeps returning it, so the seller has not lost the item'
        source.status == 'SOLD'
        source.buyerUserId == 10L
    }

    def "escrow live: a stale stored assetId is not a licence to escrow — refused all the same"() {
        given: 'a Steam-sourced item; the stored id belongs to the ORIGINAL seller, not this one'
        steamEscrowService.escrowEnabled >> true
        listingRepository.findById(50L) >> Optional.of(owned(assetId: '111222333'))

        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal('80'))

        then:
        thrown(BadRequestException)

        and: 'we never hand a bot a copy this seller does not own'
        0 * steamEscrowService.requestDepositForListing(_, _, _)
    }

    def "escrow live: the refusal is a 400 with a specific code, so no client can render it as success"() {
        given: 'writeJson surfaces {error, code} for any non-2xx; both relist call sites branch on it'
        steamEscrowService.escrowEnabled >> true

        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal('80'))

        then:
        def e = thrown(ApiException)
        e.status == HttpStatus.BAD_REQUEST

        and: 'not the generic BAD_REQUEST bucket — the UI can special-case this one'
        e.code == 'RELIST_NEEDS_STEAM_DEPOSIT'
    }

    def "escrow live: the seller actually READS the message in production, not 'Request could not be completed'"() {
        given: '''the real handler, wired the way production is
                  (security.verbose-errors defaults to false)'''
        steamEscrowService.escrowEnabled >> true
        def handler = new GlobalExceptionHandler(verboseErrors: false)

        when: 'the gate throws and the response is rendered by the real handler'
        ApiException thrownEx = null
        try {
            service.relist(10L, 'Alice', 50L, new BigDecimal('80'))
        } catch (ApiException ex) {
            thrownEx = ex
        }
        def resp = handler.handleApi(thrownEx, new MockHttpServletRequest('POST', '/api/listings/sell'))

        then: 'the gate did fire'
        thrownEx != null

        and: '''genericMessage() swallows any domain message longer than 140 chars into
                "Request could not be completed". A refusal the seller cannot read is
                visible but useless — the exact defect in a different costume. This
                round-trips through the REAL handler rather than counting characters,
                so it stays honest if that threshold ever moves.'''
        resp.body.message == thrownEx.message
        resp.body.message != 'Request could not be completed'

        and: 'and what survives still names the action that works'
        resp.body.message.contains('Steam tab')

        and: 'the machine-readable code is never rewritten'
        resp.body.code == 'RELIST_NEEDS_STEAM_DEPOSIT'
        resp.statusCode == HttpStatus.BAD_REQUEST
    }

    def "escrow live: a banned seller is still told they are banned, not sent to the Steam tab"() {
        given: 'ordering matters — banGuard runs first, exactly as on the first-list path'
        steamEscrowService.escrowEnabled >> true
        banGuard.assertNotBanned(10L) >> { throw new RuntimeException('banned') }

        when:
        service.relist(10L, 'Alice', 50L, new BigDecimal('80'))

        then:
        def e = thrown(RuntimeException)
        e.message == 'banned'
    }

    def "escrow live: the gate fires even on input that would have failed validation anyway"() {
        given: 'no second-guessing — a doomed request is refused for the real reason'
        steamEscrowService.escrowEnabled >> true

        when:
        service.relist(10L, 'Alice', 50L, badPrice)

        then:
        def e = thrown(BadRequestException)
        e.code == 'RELIST_NEEDS_STEAM_DEPOSIT'

        where:
        badPrice << [null, BigDecimal.ZERO, new BigDecimal('-5'), new BigDecimal('100001')]
    }

    // ── escrow DISABLED: today's production must be untouched ─────────────

    def "escrow DISABLED: relist still creates a live ACTIVE listing (the only shipped seller capability)"() {
        given: 'the bot is unconfigured — exactly production today'
        steamEscrowService.escrowEnabled >> false
        def source = owned()
        listingRepository.findById(50L) >> Optional.of(source)
        listingRepository.save(_) >> { args -> def l = args[0]; l.id = l.id ?: 100L; l }

        when:
        def fresh = service.relist(10L, 'Alice', 50L, new BigDecimal('80'))

        then: 'unchanged behaviour, end to end'
        fresh.status == 'ACTIVE'
        fresh.price == new BigDecimal('80')
        fresh.sellerUserId == 10L
        source.status == 'RELISTED'

        and: 'and it still reaches the matching engine'
        1 * buyOrderService.tryMatch(_)

        and: 'the disabled path never opens an escrow conversation'
        0 * steamEscrowService.requestDepositForListing(_, _, _)
    }

    def "escrow DISABLED: an auction relist is unchanged too"() {
        given:
        steamEscrowService.escrowEnabled >> false
        listingRepository.findById(50L) >> Optional.of(owned())
        listingRepository.save(_) >> { args -> def l = args[0]; l.id = l.id ?: 100L; l }

        when:
        def fresh = service.relist(10L, 'Alice', 50L, new BigDecimal('80'), 'AUCTION', 24L)

        then:
        fresh.status == 'ACTIVE'
        fresh.listingType == 'AUCTION'
        fresh.expiresAt != null
    }

    def "no escrow bean at all: the gate is inert rather than exploding"() {
        given: 'CI / plain-property Spock wiring constructs SellService without the optional bean'
        def bare = new SellService(
            listingRepository: listingRepository,
            tradeRepository  : tradeRepository,
            buyOrderService  : buyOrderService,
            banGuard         : banGuard,
            textSanitizer    : textSanitizer)
        listingRepository.findById(50L) >> Optional.of(owned())
        listingRepository.save(_) >> { args -> def l = args[0]; l.id = l.id ?: 100L; l }

        when:
        def fresh = bare.relist(10L, 'Alice', 50L, new BigDecimal('80'))

        then: 'no NPE from the null bean — we reach the ordinary flow'
        fresh.status == 'ACTIVE'
    }
}
