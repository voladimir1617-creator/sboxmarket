package com.sboxmarket

import com.sboxmarket.model.EscrowedItem
import com.sboxmarket.model.Trade
import com.sboxmarket.repository.EscrowedItemRepository
import com.sboxmarket.repository.TradeRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * The nav-avatar "needs your attention" count (pending-actions sellerTrades)
 * counted a bot-delivered sale waiting in PENDING_SELLER_SEND as the seller's
 * to-do, while the trade row told the same seller "no action needed": the bot
 * holds the skin and sends the offer itself. Only a sale the seller has to send
 * by hand is theirs to act on.
 */
@SpringBootTest
@ActiveProfiles("test")
class SellerPendingBotSaleDbSpec extends Specification {

    @Autowired TradeRepository tradeRepository
    @Autowired EscrowedItemRepository escrowRepository

    List<Long> tradeIds = []
    List<Long> escrowIds = []
    long n

    def setup() { n = System.nanoTime() % 1_000_000_000L }

    def cleanup() {
        tradeRepository.deleteAllById(tradeIds)
        escrowRepository.deleteAllById(escrowIds)
    }

    private void trade(long sellerId, long listingId, String state) {
        tradeIds << tradeRepository.save(new Trade(listingId: listingId, itemId: 1L, itemName: 'Badge Hat',
                buyerUserId: sellerId + 1, sellerUserId: sellerId, price: 5.00G, state: state)).id
    }

    private void custody(long sellerId, long listingId, String custodyState) {
        escrowIds << escrowRepository.save(new EscrowedItem(listingId: listingId, sellerUserId: sellerId,
                assetId: "88${n}${listingId % 10}", heldAssetId: "88${n}${listingId % 10}",
                marketHashName: 'Badge Hat', custodyState: custodyState)).id
    }

    def "a sale the bot is delivering is not counted as the seller's pending action"() {
        given:
        long seller = 700_000_000_000L + n
        long base = 910_000_000_000L + n * 10
        trade(seller, base + 1, 'PENDING_SELLER_ACCEPT')      // seller must accept
        trade(seller, base + 2, 'PENDING_SELLER_SEND')        // seller sends by hand
        custody(seller, base + 3, EscrowedItem.IN_CUSTODY)
        trade(seller, base + 3, 'PENDING_SELLER_SEND')        // bot is delivering
        trade(seller, base + 4, 'PENDING_BUYER_CONFIRM')      // buyer's turn

        expect:
        tradeRepository.countSellerPending(seller) == 2L
    }
}
