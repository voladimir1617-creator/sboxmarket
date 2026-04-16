package com.sboxmarket

import com.sboxmarket.config.RateLimitFilter
import jakarta.servlet.FilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit coverage for the token-bucket rate limiter. We call the filter
 * directly with MockHttpServletRequest so the thresholds exercise
 * without a running server.
 *
 * Budgets under test (from RateLimitFilter):
 *   - MAX_REQ  = 20  per 10s  (write surfaces)
 *   - MAX_READ = 120 per 10s  (search-ish GET surfaces)
 *   - MAX_ENUM = 40  per 10s  (enumeration-sensitive GETs: items/{id}, stall/{id})
 *
 * Health probe is always exempt regardless of rate.
 */
class RateLimitFilterSpec extends Specification {

    @Subject
    RateLimitFilter filter = new RateLimitFilter()

    FilterChain chain = Mock()

    private MockHttpServletRequest get(String path, String ip = '10.0.0.1') {
        def r = new MockHttpServletRequest('GET', path)
        r.remoteAddr = ip
        r
    }

    private MockHttpServletRequest post(String path, String ip = '10.0.0.1') {
        def r = new MockHttpServletRequest('POST', path)
        r.remoteAddr = ip
        r
    }

    def "health probe is never rate-limited"() {
        given:
        def req = get('/api/health')
        def resp = new MockHttpServletResponse()

        when: "hammered far past any reasonable budget"
        (1..200).each { filter.doFilter(req, new MockHttpServletResponse(), chain) }

        then:
        200 * chain.doFilter(_, _)
    }

    def "unguarded GET falls through to the chain"() {
        given:
        def req = get('/api/auth/steam/me')
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(req, resp, chain)

        then:
        1 * chain.doFilter(req, resp)
    }

    def "enumeration endpoint /api/items/{id} caps at 40 requests per 10s window"() {
        given:
        def resp
        int allowed = 0
        int blocked = 0

        when: "45 consecutive requests to /api/items/1 from the same IP"
        (1..45).each {
            resp = new MockHttpServletResponse()
            filter.doFilter(get('/api/items/1'), resp, chain)
            if (resp.status == 429) blocked++ else allowed++
        }

        then: "first 40 pass, next 5 get rate-limited"
        allowed == 40
        blocked == 5
    }

    def "rate-limit 429 includes a Retry-After header and structured body"() {
        given:
        // Burn through the budget
        (1..40).each { filter.doFilter(get('/api/items/99'), new MockHttpServletResponse(), chain) }
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(get('/api/items/99'), resp, chain)

        then:
        resp.status == 429
        resp.getHeader('Retry-After') != null
        resp.contentAsString.contains('"code":"RATE_LIMITED"')
    }

    def "write surface budget is tighter (20/10s) than enumeration budget"() {
        given:
        int allowed = 0
        int blocked = 0

        when: "25 POSTs to /api/offers from the same IP"
        (1..25).each {
            def resp = new MockHttpServletResponse()
            filter.doFilter(post('/api/offers'), resp, chain)
            if (resp.status == 429) blocked++ else allowed++
        }

        then: "first 20 pass, next 5 hit 429"
        allowed == 20
        blocked == 5
    }

    def "different IPs have independent budgets"() {
        when: "Each IP burns its own bucket separately"
        (1..40).each {
            filter.doFilter(get('/api/items/1', '10.0.0.1'), new MockHttpServletResponse(), chain)
        }
        def r2 = new MockHttpServletResponse()
        filter.doFilter(get('/api/items/1', '10.0.0.2'), r2, chain)

        then: "Second IP is fresh — passes cleanly"
        r2.status == 200
    }

    def "search param on /api/listings activates the READ budget"() {
        given:
        int allowed = 0
        int blocked = 0

        when: "125 GETs with ?search= from the same IP"
        (1..125).each {
            def r = get('/api/listings')
            r.addParameter('search', 'hat')
            def resp = new MockHttpServletResponse()
            filter.doFilter(r, resp, chain)
            if (resp.status == 429) blocked++ else allowed++
        }

        then: "first 120 pass (MAX_READ), next 5 hit 429"
        allowed == 120
        blocked == 5
    }

    def "plain /api/listings without search is NOT limited (normal browsing)"() {
        given:
        int allowed = 0

        when: "200 plain GETs — more than any limiter budget"
        (1..200).each {
            def resp = new MockHttpServletResponse()
            filter.doFilter(get('/api/listings'), resp, chain)
            if (resp.status != 429) allowed++
        }

        then: "all 200 pass (not on any guarded surface)"
        allowed == 200
    }

    def "X-Forwarded-For is honoured so requests behind a proxy keyed per real IP"() {
        given:
        def first = get('/api/items/7')
        first.addHeader('X-Forwarded-For', '203.0.113.1')
        def second = get('/api/items/7')
        second.addHeader('X-Forwarded-For', '203.0.113.2')

        when: "Burn the first IP's budget via its XFF header"
        (1..40).each {
            def r = get('/api/items/7')
            r.addHeader('X-Forwarded-For', '203.0.113.1')
            filter.doFilter(r, new MockHttpServletResponse(), chain)
        }
        def resp = new MockHttpServletResponse()
        filter.doFilter(second, resp, chain)

        then: "Second IP is fresh even though the shared proxy is the direct peer"
        resp.status == 200
    }

    def "offer thread GET is enumeration-guarded (bug #13 follow-up)"() {
        given:
        int allowed = 0
        int blocked = 0

        when: "walk /api/offers/thread/1 … /api/offers/thread/50 from one IP"
        (1..50).each { id ->
            def resp = new MockHttpServletResponse()
            filter.doFilter(get("/api/offers/thread/${id}"), resp, chain)
            if (resp.status == 429) blocked++ else allowed++
        }

        then: "MAX_ENUM caps the walk at 40 before the 41st hit gets 429"
        allowed == 40
        blocked == 10
    }

    def "bid history GET is enumeration-guarded (bug #14 follow-up)"() {
        given:
        int allowed = 0
        int blocked = 0

        when: "walk /api/bids/listing/{id} from one IP"
        (1..50).each { id ->
            def resp = new MockHttpServletResponse()
            filter.doFilter(get("/api/bids/listing/${id}"), resp, chain)
            if (resp.status == 429) blocked++ else allowed++
        }

        then:
        allowed == 40
        blocked == 10
    }

    def "reviews-by-user GET is enumeration-guarded"() {
        given:
        int allowed = 0
        int blocked = 0

        when: "walk /api/reviews/user/{id} from one IP"
        (1..50).each { id ->
            def resp = new MockHttpServletResponse()
            filter.doFilter(get("/api/reviews/user/${id}"), resp, chain)
            if (resp.status == 429) blocked++ else allowed++
        }

        then:
        allowed == 40
        blocked == 10
    }
}
