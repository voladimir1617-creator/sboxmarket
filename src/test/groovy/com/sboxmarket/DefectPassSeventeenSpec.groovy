package com.sboxmarket

import com.sboxmarket.model.WatchlistAlert
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.WatchlistAlertRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.WatchlistAlertService
import spock.lang.Specification

/**
 * Defect pass 17: price alerts.
 */
class DefectPassSeventeenSpec extends Specification {

    WatchlistAlertRepository repo = Mock()
    ListingRepository listingRepository = Mock()
    NotificationService notificationService = Mock()
    WatchlistAlertService service = new WatchlistAlertService(
        repo: repo, itemRepository: Mock(ItemRepository),
        notificationService: notificationService, listingRepository: listingRepository)

    private Object[] row(WatchlistAlert a, String floor) {
        [a, new BigDecimal(floor), 'Wizard Hat'] as Object[]
    }

    def "a user's own listing under their alert target does not fire or use up the alert"() {
        given:
        def a = new WatchlistAlert(id: 1L, userId: 7L, itemId: 3L, targetPrice: new BigDecimal('10'), status: 'ACTIVE')
        repo.findTriggered() >> [row(a, '9')]
        listingRepository.minPriceForItemBySeller(3L, 7L) >> new BigDecimal('9')
        listingRepository.minPriceForItemExcludingSeller(3L, 7L) >> new BigDecimal('12')

        when:
        service.sweep()

        then:
        0 * repo.claimForFiring(_, _)
        0 * notificationService.push(*_)
        a.status == 'ACTIVE'
    }

    def "another seller under the target still fires, quoting their price"() {
        given:
        def a = new WatchlistAlert(id: 1L, userId: 7L, itemId: 3L, targetPrice: new BigDecimal('10'), status: 'ACTIVE')
        repo.findTriggered() >> [row(a, '8')]
        listingRepository.minPriceForItemBySeller(3L, 7L) >> new BigDecimal('8')
        listingRepository.minPriceForItemExcludingSeller(3L, 7L) >> new BigDecimal('9.50')

        when:
        service.sweep()

        then:
        1 * repo.claimForFiring(1L, _) >> 1
        1 * notificationService.push(7L, 'WATCHLIST_PRICE_DROP', _, { it.contains('9.50') }, 3L, '/item/3')
    }
}
