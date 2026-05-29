package com.sboxmarket

import com.sboxmarket.model.Trade
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.repository.TransactionRepository
import com.sboxmarket.repository.WalletRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.TradeService
import com.sboxmarket.service.security.AdminAuthorization
import com.sboxmarket.service.security.BanGuard
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import spock.lang.Specification
import spock.lang.Subject

/**
 * Pagination-cap coverage for {@link TradeService#listForUser} — bug
 * class #9. Pre-fix this method called the UNBOUNDED
 * {@code findByParticipant(uid)} JPQL, so a power-user with thousands
 * of historical trades would force the server to serialise the entire
 * history on every call (memory + JSON-marshal blowup). The sibling
 * {@code listForUserWithCounterparty} already capped via
 * {@code findByParticipantPaged} + {@link TradeService#TRADE_LIST_CAP};
 * this method was the matching outlier on the service surface.
 *
 * The spec FAILS pre-fix (the unbounded overload is invoked) and
 * PASSES post-fix (the paged overload is invoked with a 200-row
 * PageRequest).
 */
class TradeServiceListForUserPaginationSpec extends Specification {

    TradeRepository       tradeRepository       = Mock()
    WalletRepository      walletRepository      = Mock()
    TransactionRepository transactionRepository = Mock()
    ListingRepository     listingRepository     = Mock()
    NotificationService   notificationService   = Mock()
    BanGuard              banGuard              = Mock()
    AdminAuthorization    adminAuthorization    = Mock()
    com.sboxmarket.repository.ItemRepository itemRepository = Mock()
    com.sboxmarket.repository.SteamUserRepository steamUserRepository = Mock() {
        findAllById(_) >> []
    }
    TextSanitizer textSanitizer = Mock() {
        medium(_) >> { String s -> s ?: '' }
    }

    @Subject
    TradeService service = new TradeService(
        tradeRepository       : tradeRepository,
        walletRepository      : walletRepository,
        transactionRepository : transactionRepository,
        listingRepository     : listingRepository,
        notificationService   : notificationService,
        banGuard              : banGuard,
        adminAuthorization    : adminAuthorization,
        steamUserRepository   : steamUserRepository,
        textSanitizer         : textSanitizer,
        itemRepository        : itemRepository,
        autoReleaseDays       : 8L,
        sellerResponseDays    : 3L
    )

    def "listForUser caps via the paged repo overload at TRADE_LIST_CAP rows"() {
        when:
        def out = service.listForUser(42L)

        then:
        // Post-fix: paged overload invoked with a PageRequest sized
        // exactly to TRADE_LIST_CAP. Pre-fix this expectation fails
        // because the unbounded overload was called instead.
        1 * tradeRepository.findByParticipantPaged(42L, { Pageable p ->
            p.pageNumber == 0 && p.pageSize == TradeService.TRADE_LIST_CAP
        }) >> []
        // And the unbounded overload must NOT be called.
        0 * tradeRepository.findByParticipant(_)
        out == []
    }

    def "listForUser returns the rows the paged repo overload yields"() {
        given:
        def t1 = new Trade(id: 1L, listingId: 100L, itemId: 1L,
            buyerUserId: 42L, sellerUserId: 99L,
            price: new BigDecimal('10.00'), state: 'VERIFIED')
        def t2 = new Trade(id: 2L, listingId: 101L, itemId: 1L,
            buyerUserId: 99L, sellerUserId: 42L,
            price: new BigDecimal('20.00'), state: 'PENDING_SELLER_SEND')

        when:
        def out = service.listForUser(42L)

        then:
        1 * tradeRepository.findByParticipantPaged(42L, _ as Pageable) >> [t1, t2]
        out.size() == 2
        out[0].id == 1L
        out[1].id == 2L
    }
}
