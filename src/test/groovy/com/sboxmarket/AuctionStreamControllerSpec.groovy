package com.sboxmarket

import com.sboxmarket.controller.AuctionStreamController
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.Listing
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.service.AuctionEventBus
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the live-auction SSE endpoint. The load-bearing concern:
 * the stream mirrors a *public* auction surface, so a bad / hidden /
 * non-auction listing id must 404 rather than open a stream — otherwise
 * an anonymous client could subscribe to any listing id and the
 * AuctionEventBus fan-out would leak live bid activity on private rows.
 */
class AuctionStreamControllerSpec extends Specification {

    AuctionEventBus   bus               = Mock()
    ListingRepository listingRepository = Mock()

    @Subject
    AuctionStreamController controller = new AuctionStreamController(
        bus              : bus,
        listingRepository: listingRepository
    )

    private Listing auction(Map args = [:]) {
        new Listing(
            id:          args.id ?: 100L,
            listingType: args.type ?: 'AUCTION',
            hidden:      args.hidden ?: false,
            status:      args.status ?: 'ACTIVE'
        )
    }

    def "subscribe 404s on a null listing id"() {
        when:
        controller.subscribe(null)

        then:
        thrown(NotFoundException)
        0 * bus.subscribe(_)
    }

    def "subscribe 404s on a non-positive listing id"() {
        when:
        controller.subscribe(id)

        then:
        thrown(NotFoundException)
        0 * bus.subscribe(_)

        where:
        id << [0L, -1L]
    }

    def "subscribe 404s when the listing does not exist"() {
        given:
        1 * listingRepository.findById(42L) >> Optional.empty()

        when:
        controller.subscribe(42L)

        then:
        thrown(NotFoundException)
        0 * bus.subscribe(_)
    }

    def "subscribe 404s on a hidden listing — no SSE leak of off-market rows"() {
        given:
        1 * listingRepository.findById(42L) >> Optional.of(auction(id: 42L, hidden: true))

        when:
        controller.subscribe(42L)

        then:
        thrown(NotFoundException)
        0 * bus.subscribe(_)
    }

    def "subscribe 404s on a plain BUY_NOW listing — only auctions stream"() {
        given:
        1 * listingRepository.findById(42L) >> Optional.of(auction(id: 42L, type: 'BUY_NOW'))

        when:
        controller.subscribe(42L)

        then:
        thrown(NotFoundException)
        0 * bus.subscribe(_)
    }

    def "subscribe opens a stream for a visible active auction"() {
        given:
        def emitter = new SseEmitter(0L)
        1 * listingRepository.findById(42L) >> Optional.of(auction(id: 42L))
        1 * bus.subscribe(42L) >> emitter

        when:
        def result = controller.subscribe(42L)

        then:
        result.is(emitter)
    }
}
