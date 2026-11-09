package com.sboxmarket.service

import com.sboxmarket.model.EscrowedItem
import com.sboxmarket.repository.EscrowedItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.SteamUserRepository
import spock.lang.Specification

/**
 * A deposit past its Steam hold that never arrived used to ping only the
 * seller. Staff now get one alert per sweep for the rows that sweep escalated,
 * and the seller ping, the claim and the row state are untouched.
 */
class StuckEscrowStaffAlertSpec extends Specification {

    SteamEscrowService service
    SteamTradeBotService bot = Mock()
    EscrowedItemRepository escrowRepository = Mock()
    ListingRepository listingRepository = Mock()
    NotificationService notificationService = Mock()
    OpsAlertService ops = Mock()

    static final long DAY = 24L * 60L * 60L * 1000L

    def setup() {
        service = new SteamEscrowService()
        service.steamTradeBotService = bot
        service.escrowRepository = escrowRepository
        service.listingRepository = listingRepository
        service.steamUserRepository = Mock(SteamUserRepository)
        service.notificationService = notificationService
        service.opsAlertService = ops
        service.escrowSweepEnabled = true
        service.batchSize = 50
        service.holdGraceHours = 48
        service.holdEscalationBackoffMs = 6L * 60L * 60L * 1000L
        bot.enabled >> true
    }

    private static EscrowedItem held(long id) {
        long now = System.currentTimeMillis()
        new EscrowedItem(id: id, listingId: 100L + id, sellerUserId: 1L, assetId: '555',
                depositOfferId: "offer-${id}".toString(), custodyState: EscrowedItem.IN_ESCROW_HOLD,
                depositAcceptedAt: now - 20L * DAY, escrowEndsAt: now - 7L * DAY, updatedAt: 1000L + id)
    }

    def "escalated holds raise ONE urgent staff alert naming them, beside the unchanged seller ping"() {
        when:
        service.sweepOverdueEscrowHolds()

        then:
        1 * escrowRepository.findOverdueEscrowHolds(_ as Long, _ as Long, _) >> [held(1L), held(2L), held(3L)]
        1 * escrowRepository.claimOverdueHoldEscalation(1L, _, _, _ as Long) >> 1
        1 * escrowRepository.claimOverdueHoldEscalation(2L, _, _, _ as Long) >> 0
        1 * escrowRepository.claimOverdueHoldEscalation(3L, _, _, _ as Long) >> 1
        2 * notificationService.safePush(1L, _, _, { String b -> b.contains("haven't received it yet") }, _, _)
        1 * ops.alertAdmins(OpsAlertService.STUCK_ESCROW, OpsAlertService.STUCK_ESCROW,
                'Stuck deposits: 2 past Steam hold',
                { String b -> b.contains('escrow 1') && b.contains('escrow 3') && !b.contains('escrow 2') && b.contains('offer-1') },
                '/admin?tab=health', true)

        and: "no money or custody transition"
        0 * escrowRepository.claimTimeoutPendingDeposit(*_)
        0 * escrowRepository.save(_)
        0 * listingRepository.save(_)
    }

    def "no escalation, no staff alert"() {
        when:
        service.sweepOverdueEscrowHolds()

        then:
        1 * escrowRepository.findOverdueEscrowHolds(_ as Long, _ as Long, _) >> [held(1L)]
        1 * escrowRepository.claimOverdueHoldEscalation(1L, _, _, _ as Long) >> 0
        0 * ops.alertAdmins(*_)
    }

    def "a failing staff alert cannot break the sweep"() {
        when:
        service.sweepOverdueEscrowHolds()

        then:
        1 * escrowRepository.findOverdueEscrowHolds(_ as Long, _ as Long, _) >> [held(1L)]
        1 * escrowRepository.claimOverdueHoldEscalation(1L, _, _, _ as Long) >> 1
        1 * notificationService.safePush(1L, *_)
        1 * ops.alertAdmins(*_) >> { throw new RuntimeException('boom') }
        noExceptionThrown()
    }

    def "a long list is capped in the alert body"() {
        given:
        def rows = (1L..14L).collect { held(it) }

        when:
        service.sweepOverdueEscrowHolds()

        then:
        1 * escrowRepository.findOverdueEscrowHolds(_ as Long, _ as Long, _) >> rows
        14 * escrowRepository.claimOverdueHoldEscalation(*_) >> 1
        1 * ops.alertAdmins(_, _, 'Stuck deposits: 14 past Steam hold', { String b -> b.contains('and 4 more') && !b.contains('escrow 11 ') }, _, true)
    }

    def "stuckEscrowCounts reads the two counts with the grace applied"() {
        given:
        long before = System.currentTimeMillis()

        when:
        def c = service.stuckEscrowCounts()

        then:
        1 * escrowRepository.countOverdueEscrowHolds({ Long r -> r <= before - 48L * 3600_000L + 5000L && r >= before - 48L * 3600_000L - 5000L },
                { Long a -> a < before - 15L * DAY }) >> 4L
        1 * escrowRepository.countMissingFromBotInventory() >> 2L
        c.overdueEscrowHolds == 4L
        c.missingFromBotInventory == 2L
    }

    def "stuckEscrowCounts is null, not an exception, when the query fails"() {
        given:
        escrowRepository.countOverdueEscrowHolds(*_) >> { throw new RuntimeException('db') }

        expect:
        service.stuckEscrowCounts() == null
    }
}
