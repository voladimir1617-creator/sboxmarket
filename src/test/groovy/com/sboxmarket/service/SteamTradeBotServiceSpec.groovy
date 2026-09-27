package com.sboxmarket.service

import groovy.json.JsonOutput
import spock.lang.Specification

/**
 * Unit tests for SteamTradeBotService. The HTTP transport (doRequest) is stubbed via a
 * Spock Spy so no live sidecar is needed.
 */
class SteamTradeBotServiceSpec extends Specification {

    SteamTradeBotService service

    def setup() {
        service = Spy(SteamTradeBotService)
        service.baseUrl = 'http://localhost:4000'
        service.apiToken = 'test-token'
    }

    private static SteamTradeBotService.RawResponse raw(int status, Map body) {
        return new SteamTradeBotService.RawResponse(status: status, body: JsonOutput.toJson(body))
    }

    def "disabled when base url is blank"() {
        given:
        service.baseUrl = ''

        expect:
        !service.enabled

        when:
        def r = service.sendOffer('https://steamcommunity.com/tradeoffer/new/?partner=1&token=x', ['123'], 'hi')

        then:
        // No HTTP call is made in disabled mode.
        0 * service.doRequest(_, _, _)
        !r.ok
        r.errorCode == 'DISABLED'
    }

    def "enabled when base url is set"() {
        expect:
        service.enabled
    }

    def "sendOffer posts to /offers/send and maps a sent result"() {
        when:
        def r = service.sendOffer('https://steamcommunity.com/tradeoffer/new/?partner=1&token=x', ['555'], 'deliver')

        then:
        1 * service.doRequest('POST', 'http://localhost:4000/offers/send', _) >> { String m, String url, String b ->
            assert b.contains('partnerTradeUrl')
            assert b.contains('555')
            raw(200, [ok: true, offerId: '987', status: 'sent', confirmed: true])
        }
        r.ok
        r.offerId == '987'
        r.status == 'sent'
        r.confirmed
        r.sent
    }

    def "requestItems posts to /offers/request (deposit leg) and maps the offer id"() {
        when:
        def r = service.requestItems('https://steamcommunity.com/tradeoffer/new/?partner=1&token=x', ['555'], 'deposit')

        then:
        1 * service.doRequest('POST', 'http://localhost:4000/offers/request', _) >> { String m, String url, String b ->
            assert b.contains('partnerTradeUrl')
            assert b.contains('555')
            raw(200, [ok: true, offerId: '444', status: 'sent', confirmed: true])
        }
        r.ok
        r.offerId == '444'
        r.status == 'sent'
    }

    def "requestItems validates inputs without calling transport"() {
        when:
        def r1 = service.requestItems(null, ['1'], 'm')
        def r2 = service.requestItems('url', [], 'm')

        then:
        0 * service.doRequest(_, _, _)
        !r1.ok && r1.errorCode == 'BAD_REQUEST'
        !r2.ok && r2.errorCode == 'BAD_REQUEST'
    }

    def "requestItems is disabled when base url is blank"() {
        given:
        service.baseUrl = ''

        when:
        def r = service.requestItems('https://steamcommunity.com/tradeoffer/new/?partner=1&token=x', ['555'], 'm')

        then:
        0 * service.doRequest(_, _, _)
        !r.ok && r.errorCode == 'DISABLED'
    }

    def "sendOffer validates inputs without calling transport"() {
        when:
        def r1 = service.sendOffer(null, ['1'], 'm')
        def r2 = service.sendOffer('url', [], 'm')

        then:
        0 * service.doRequest(_, _, _)
        !r1.ok && r1.errorCode == 'BAD_REQUEST'
        !r2.ok && r2.errorCode == 'BAD_REQUEST'
    }

    def "getOfferStatus maps an accepted offer"() {
        when:
        def r = service.getOfferStatus('987')

        then:
        1 * service.doRequest('GET', 'http://localhost:4000/offers/987', null) >> raw(200, [ok: true, offerId: '987', status: 'accepted'])
        r.ok
        r.accepted
        !r.terminalFailure
    }

    def "getOfferStatus maps a declined offer as terminal failure"() {
        when:
        def r = service.getOfferStatus('987')

        then:
        1 * service.doRequest('GET', 'http://localhost:4000/offers/987', null) >> raw(200, [ok: true, offerId: '987', status: 'declined'])
        r.ok
        !r.accepted
        r.terminalFailure
    }

    def "getOfferStatus maps in_escrow"() {
        when:
        def r = service.getOfferStatus('987')

        then:
        1 * service.doRequest('GET', 'http://localhost:4000/offers/987', null) >> raw(200, [ok: true, status: 'in_escrow'])
        r.ok
        r.inEscrow
        !r.accepted
    }

    def "non-2xx with normalized error code is surfaced"() {
        when:
        def r = service.getOfferStatus('987')

        then:
        1 * service.doRequest('GET', 'http://localhost:4000/offers/987', null) >> raw(429, [ok: false, error: 'RATE_LIMITED', message: 'slow down'])
        !r.ok
        r.errorCode == 'RATE_LIMITED'
        r.message == 'slow down'
    }

    def "transport IOException becomes a typed TRANSPORT_ERROR (never throws)"() {
        when:
        def r = service.getOfferStatus('987')

        then:
        1 * service.doRequest(_, _, _) >> { throw new IOException('connection refused') }
        !r.ok
        r.errorCode == 'TRANSPORT_ERROR'
        r.message.contains('connection refused')
    }

    def "connect timeout becomes TIMEOUT"() {
        when:
        def r = service.fetchBotInventory()

        then:
        1 * service.doRequest('GET', 'http://localhost:4000/inventory', null) >> { throw new java.net.http.HttpConnectTimeoutException('connect timed out') }
        !r.ok
        r.errorCode == 'TIMEOUT'
    }

    def "non-JSON body becomes BAD_RESPONSE"() {
        when:
        def r = service.fetchBotInventory()

        then:
        1 * service.doRequest('GET', 'http://localhost:4000/inventory', null) >> new SteamTradeBotService.RawResponse(status: 200, body: '<html>oops</html>')
        !r.ok
        r.errorCode == 'BAD_RESPONSE'
    }

    def "acceptIncoming posts to the accept endpoint"() {
        when:
        def r = service.acceptIncoming('424242')

        then:
        1 * service.doRequest('POST', 'http://localhost:4000/offers/incoming/424242/accept', null) >> raw(200, [ok: true, offerId: '424242', status: 'accepted', confirmed: true])
        r.ok
        r.status == 'accepted'
    }

    def "fetchBotInventory returns raw items"() {
        when:
        def r = service.fetchBotInventory()

        then:
        1 * service.doRequest('GET', 'http://localhost:4000/inventory', null) >> raw(200, [ok: true, count: 2, items: [[assetId: '1'], [assetId: '2']]])
        r.ok
        r.raw.count == 2
        r.raw.items.size() == 2
    }

    def "base url trailing slash is trimmed"() {
        given:
        service.baseUrl = 'http://localhost:4000/'

        when:
        def r = service.getOfferStatus('5')

        then:
        1 * service.doRequest('GET', 'http://localhost:4000/offers/5', null) >> raw(200, [ok: true, status: 'active'])
        r.ok
    }
}
