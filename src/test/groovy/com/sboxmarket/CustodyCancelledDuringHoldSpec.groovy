package com.sboxmarket

import com.sboxmarket.model.EscrowedItem
import com.sboxmarket.repository.EscrowedItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.service.SteamEscrowService
import com.sboxmarket.service.SteamTradeBotService
import spock.lang.Specification

/**
 * A seller who cancels while Steam still holds the deposit (trade hold or
 * Trade Protection) has a return queued. My Stall must show it under
 * "Coming back to your Steam" instead of dropping it from view.
 */
class CustodyCancelledDuringHoldSpec extends Specification {

    EscrowedItemRepository escrowRepository = Mock()
    ListingRepository listingRepository = Mock()

    SteamTradeBotService bot = Stub() { isEnabled() >> true }
    SteamEscrowService service = new SteamEscrowService(escrowRepository: escrowRepository,
        listingRepository: listingRepository, steamTradeBotService: bot)

    def "a queued return during a hold or Trade Protection shows as waiting for Steam"() {
        given:
        long later = System.currentTimeMillis() + 3L * 86_400_000L
        escrowRepository.findBySellerUserIdAndCustodyStateIn(5L, { it.containsAll([EscrowedItem.IN_ESCROW_HOLD, EscrowedItem.IN_TRADE_PROTECTION]) }) >> [
            new EscrowedItem(listingId: 1L, sellerUserId: 5L, custodyState: EscrowedItem.IN_ESCROW_HOLD,
                returnRequestedAt: 100L, escrowEndsAt: later, marketHashName: 'Hold Hat'),
            new EscrowedItem(listingId: 2L, sellerUserId: 5L, custodyState: EscrowedItem.IN_TRADE_PROTECTION,
                returnRequestedAt: 200L, protectedUntil: later, marketHashName: 'Guard Boots'),
            // Still in Trade Protection, never cancelled: an ordinary pending deposit.
            new EscrowedItem(listingId: 3L, sellerUserId: 5L, custodyState: EscrowedItem.IN_TRADE_PROTECTION,
                protectedUntil: later, marketHashName: 'Live Soon')
        ]
        listingRepository.findById(_) >> Optional.empty()

        when:
        def view = service.custodyForSeller(5L)

        then:
        view.heldListingIds == []
        view.returning*.listingId == [2L, 1L]
        view.returning.every { it.state == 'WAITING_FOR_STEAM' }
        view.returning.every { it.message.contains('sends it back to you') }
    }
}
