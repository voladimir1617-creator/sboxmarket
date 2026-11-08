package com.sboxmarket

import com.sboxmarket.model.EscrowedItem
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.EscrowedItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.AuditService
import com.sboxmarket.service.FraudAnalysisService
import com.sboxmarket.service.SteamBotResult
import com.sboxmarket.service.SteamEscrowService
import com.sboxmarket.service.SteamInventoryService
import com.sboxmarket.service.SteamTradeBotService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * The declined-deposit queries against H2: which rows the re-check sweep
 * picks up, that the claim lets a row be checked once only, and the per-seller
 * fast-decline count the fraud rollup reads.
 *
 * Rows here are declined days in the past and the sweep is run with a one-day
 * delay, so declines written by other specs sharing this database (minutes
 * old at most) are never in its candidate set.
 */
@SpringBootTest
@ActiveProfiles("test")
class DeclinedDepositRecheckDbSpec extends Specification {

    @Autowired EscrowedItemRepository escrowRepository
    @Autowired SteamUserRepository steamUserRepository
    @Autowired ListingRepository listingRepository
    @Autowired FraudAnalysisService fraudAnalysisService

    static final long MIN = 60_000L
    static final long DAY = 24L * 60L * MIN

    private SteamUser seller(String tag) {
        String uniq = String.valueOf(System.nanoTime())
        steamUserRepository.save(new SteamUser(
                steamId64: "76561198" + uniq.substring(uniq.length() - 9),
                displayName: "DeclineDb-${tag}-${uniq}"))
    }

    private EscrowedItem row(Long sellerId, Map o) {
        escrowRepository.save(new EscrowedItem(
                listingId: (o.listingId ?: System.nanoTime() % 1_000_000_000L) as Long,
                sellerUserId: sellerId,
                assetId: (o.assetId ?: '555') as String,
                marketHashName: 'DeclineDb Hat',
                depositOfferId: 'offer-' + System.nanoTime(),
                custodyState: (o.state ?: EscrowedItem.FAILED) as String,
                depositOfferSentAt: o.sentAt as Long,
                depositDeclinedAt: o.declinedAt as Long,
                declineCheck: o.check as String))
    }

    def "findDeclineRechecksDue returns only declined, unchecked rows past the cutoff"() {
        given:
        long now = System.currentTimeMillis()
        def s = seller('due')
        def due      = row(s.id, [declinedAt: now - 3 * DAY])
        def tooNew   = row(s.id, [declinedAt: now - 1 * MIN])
        def checked  = row(s.id, [declinedAt: now - 3 * DAY, check: EscrowedItem.DECLINE_UNKNOWN])
        def expired  = row(s.id, [declinedAt: null])

        when:
        def ids = escrowRepository.findDeclineRechecksDue(now - 2 * DAY, PageRequest.of(0, 500))*.id

        then:
        ids.contains(due.id)
        !ids.contains(tooNew.id)
        !ids.contains(checked.id)
        !ids.contains(expired.id)
    }

    def "the claim lets a row be re-checked exactly once"() {
        given:
        def s = seller('claim')
        def e = row(s.id, [declinedAt: System.currentTimeMillis() - 3 * DAY])

        expect:
        escrowRepository.claimDeclineRecheck(e.id) == 1
        escrowRepository.claimDeclineRecheck(e.id) == 0
        escrowRepository.recordDeclineRecheck(e.id, EscrowedItem.DECLINE_GONE) == 1
        escrowRepository.recordDeclineRecheck(e.id, EscrowedItem.DECLINE_PRESENT) == 0
        escrowRepository.findById(e.id).get().declineCheck == EscrowedItem.DECLINE_GONE
    }

    def "the sweep checks a due row once, writes the audit row when the item is gone, and never again"() {
        given:
        long now = System.currentTimeMillis()
        def s = seller('sweep')
        def e = row(s.id, [declinedAt: now - 3 * DAY, assetId: '424242'])

        SteamTradeBotService bot = Mock()
        SteamInventoryService inventory = Mock()
        AuditService audit = Mock()
        def svc = new SteamEscrowService()
        svc.steamTradeBotService = bot
        svc.escrowRepository = escrowRepository
        svc.listingRepository = listingRepository
        svc.steamUserRepository = steamUserRepository
        svc.steamInventoryService = inventory
        svc.auditService = audit
        svc.escrowSweepEnabled = true
        svc.batchSize = 500
        svc.declineRecheckDelayMs = DAY
        bot.enabled >> true
        bot.fetchBotInventory() >> SteamBotResult.success([ok: true, items: []])
        inventory.fetchInventory(s.steamId64) >> [[assetId: '1']]
        inventory.lastOutcomeFor(s.steamId64) >> [outcome: SteamInventoryService.OUTCOME_OK]

        when:
        svc.sweepDeclinedDepositRechecks()
        svc.sweepDeclinedDepositRechecks()

        then:
        1 * audit.log(AuditService.DEPOSIT_DECLINED_ITEM_GONE, null, s.id, e.id, { it.contains('424242') })
        escrowRepository.findById(e.id).get().declineCheck == EscrowedItem.DECLINE_GONE
    }

    def "fast declines are counted per seller: within 5 min, within 7 days, 2 or more"() {
        given:
        long now = System.currentTimeMillis()
        def fast = seller('fast')
        def once = seller('once')
        def slow = seller('slow')
        def old  = seller('old')
        // Two declines 1 and 4 minutes after the offer, plus one slow one.
        row(fast.id, [sentAt: now - 2 * DAY, declinedAt: now - 2 * DAY + 1 * MIN])
        row(fast.id, [sentAt: now - 1 * DAY, declinedAt: now - 1 * DAY + 4 * MIN])
        row(fast.id, [sentAt: now - 1 * DAY, declinedAt: now - 1 * DAY + 30 * MIN])
        // Only one fast decline.
        row(once.id, [sentAt: now - DAY, declinedAt: now - DAY + MIN])
        // Two declines, both slow.
        row(slow.id, [sentAt: now - DAY, declinedAt: now - DAY + 6 * MIN])
        row(slow.id, [sentAt: now - DAY, declinedAt: now - DAY + 10 * MIN])
        // Two fast declines, both older than 7 days.
        row(old.id, [sentAt: now - 9 * DAY, declinedAt: now - 9 * DAY + MIN])
        row(old.id, [sentAt: now - 8 * DAY, declinedAt: now - 8 * DAY + MIN])

        when:
        def hits = escrowRepository.countFastDeclinesBySeller(now - 7 * DAY, 5 * MIN, 2L)
        def bySeller = hits.collectEntries { Object[] h -> [(h[0] as Long): (h[1] as Number).longValue()] }

        then:
        bySeller[fast.id] == 2L
        !bySeller.containsKey(once.id)
        !bySeller.containsKey(slow.id)
        !bySeller.containsKey(old.id)

        when:
        def signals = fraudAnalysisService.computeSignals().findAll { it.type == 'FAST_DEPOSIT_DECLINES' }

        then:
        signals*.userId.contains(fast.id)
        !signals*.userId.contains(once.id)
        signals.find { it.userId == fast.id }.severity == 'MED'
    }
}
