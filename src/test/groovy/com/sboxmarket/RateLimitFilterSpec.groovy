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

    private MockHttpServletRequest getAs(String path, String ip, Long userId) {
        def r = new MockHttpServletRequest('GET', path)
        r.remoteAddr = ip
        def sess = new org.springframework.mock.web.MockHttpSession()
        if (userId != null) sess.setAttribute('steamUserId', userId)
        r.session = sess
        r
    }

    private MockHttpServletRequest opt(String path, String ip = '10.0.0.1') {
        def r = new MockHttpServletRequest('OPTIONS', path)
        r.remoteAddr = ip
        r
    }

    def "OPTIONS preflight requests are never rate-limited (batch 971)"() {
        // A user on a third-party page (Valuer extension on
        // steamcommunity.com) doing 20 rapid clicks fires 20 preflight
        // probes + 20 real POSTs. If preflights burned write tokens
        // the 21st preflight would 429 before the actual POST ever ran
        // — even though the real mutation budget hasn't been touched.
        given:
        def responses = (1..50).collect { new MockHttpServletResponse() }

        when: "hammer OPTIONS on a write-budgeted prefix"
        responses.each { resp ->
            filter.doFilter(opt('/api/listings/42/buy'), resp, chain)
        }

        then: "every preflight passes through — none rate-limited"
        50 * chain.doFilter(_, _)
        responses.every { it.status != 429 }
    }

    def "OPTIONS preflight does not decrement the write bucket (batch 971)"() {
        // Fire 10 OPTIONS first; the real POST budget should still be
        // 20/10s so 20 POSTs go through.
        given:
        (1..10).each {
            filter.doFilter(opt('/api/listings/42/buy'), new MockHttpServletResponse(), chain)
        }
        int allowed = 0, blocked = 0

        when:
        (1..25).each {
            def resp = new MockHttpServletResponse()
            filter.doFilter(post('/api/listings/42/buy'), resp, chain)
            if (resp.status == 429) blocked++ else allowed++
        }

        then: "POST budget is intact — 20 pass, 5 hit 429"
        allowed == 20
        blocked == 5
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

    def "guarded 2xx responses carry X-RateLimit-* header trio (batch 545)"() {
        given:
        def resp = new MockHttpServletResponse()

        when: "first guarded request of the window"
        filter.doFilter(get('/api/items/1'), resp, chain)

        then:
        resp.status != 429
        resp.getHeader('X-RateLimit-Limit') == '40'       // MAX_ENUM
        resp.getHeader('X-RateLimit-Remaining') == '39'   // one burnt, 39 left
        resp.getHeader('X-RateLimit-Reset') != null
    }

    def "signed-in users bucket separately from other users on the same IP (batch 547)"() {
        given:
        // Two different users behind the SAME shared-NAT IP. User A
        // burns the enumeration budget on their own bucket; user B
        // should still have a full 40 requests left.
        def sharedIp = '203.0.113.42'

        when: "user A burns the full enumeration budget"
        (1..40).each {
            filter.doFilter(getAs('/api/items/1', sharedIp, 100L), new MockHttpServletResponse(), chain)
        }
        def userB = new MockHttpServletResponse()
        filter.doFilter(getAs('/api/items/1', sharedIp, 200L), userB, chain)

        then:
        userB.status != 429
        // User B's bucket is fresh — 39 of 40 remaining after one hit
        userB.getHeader('X-RateLimit-Remaining') == '39'
    }

    def "anonymous requests still key on IP so enumeration guard holds (batch 547)"() {
        given:
        def ip = '203.0.113.99'

        when: "two anonymous sessions on the same IP share one bucket"
        (1..40).each {
            filter.doFilter(get('/api/items/1', ip), new MockHttpServletResponse(), chain)
        }
        // Same IP, no session attribute → same ip-keyed bucket
        def resp = new MockHttpServletResponse()
        filter.doFilter(get('/api/items/1', ip), resp, chain)

        then:
        resp.status == 429
    }

    def "429 response also carries X-RateLimit-* headers (batch 545)"() {
        given:
        (1..40).each { filter.doFilter(get('/api/items/99'), new MockHttpServletResponse(), chain) }
        def resp = new MockHttpServletResponse()

        when:
        filter.doFilter(get('/api/items/99'), resp, chain)

        then:
        resp.status == 429
        resp.getHeader('X-RateLimit-Limit') == '40'
        resp.getHeader('X-RateLimit-Remaining') == '0'
        resp.getHeader('X-RateLimit-Reset') != null
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

    def "POST /api/notifications/read-all is rate-limited (batch 960)"() {
        given:
        // Bell-dropdown bulk writes touch every unread row. Uncapped, a
        // hostile authenticated client could hammer /read-all / /clear-read
        // / /delete-batch and thrash the DB with full-table UPDATE / DELETE
        // scans. 20/10s matches the other write surfaces.
        int allowed = 0
        int blocked = 0

        when:
        (1..25).each {
            def resp = new MockHttpServletResponse()
            filter.doFilter(post('/api/notifications/read-all'), resp, chain)
            if (resp.status == 429) blocked++ else allowed++
        }

        then:
        allowed == 20
        blocked == 5
    }

    def "POST /api/steam/sync is rate-limited (write-surface budget)"() {
        given:
        int allowed = 0
        int blocked = 0

        when: "25 Steam sync hammers from the same IP"
        (1..25).each {
            def resp = new MockHttpServletResponse()
            filter.doFilter(post('/api/steam/sync'), resp, chain)
            if (resp.status == 429) blocked++ else allowed++
        }

        then: "first 20 pass, next 5 hit 429 — prevents fan-out against Steam's inventory endpoint"
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

    def "POST /api/profile/email/resend is in the 20/10s write bucket (batch 133 — email-spam DoS)"() {
        given:
        int allowed = 0
        int blocked = 0

        when: "spam the resend endpoint from one IP past the write budget"
        (1..30).each {
            def resp = new MockHttpServletResponse()
            filter.doFilter(post('/api/profile/email/resend'), resp, chain)
            if (resp.status == 429) blocked++ else allowed++
        }

        then:
        // MAX_REQ = 20 per 10s. Extras get 429s.
        allowed == 20
        blocked == 10
    }

    def "batch 134 new write prefixes fall into the write bucket"() {
        // One bucket per (ip|surface-prefix) — each prefix gets its own
        // 20-request runway even if we hit the same IP. Sweeping the new
        // prefixes proves each surface is individually rate-limited.
        given:
        def prefixes = [
            '/api/reviews',
            '/api/cart/checkout',
            '/api/trades/1/accept',
            '/api/loadouts',
            '/api/api-keys'
        ]

        when: "each endpoint independently hits 429 after 20 rapid writes"
        def results = prefixes.collect { p ->
            int allowed = 0
            int blocked = 0
            (1..25).each {
                def resp = new MockHttpServletResponse()
                filter.doFilter(post(p, '10.0.0.2'), resp, chain)
                if (resp.status == 429) blocked++ else allowed++
            }
            [p: p, allowed: allowed, blocked: blocked]
        }

        then:
        results.every { it.allowed == 20 && it.blocked == 5 }
    }

    def "batch 562 watchlist / alerts / saved-searches / follows are in the write bucket"() {
        // Social-feature surfaces added to GUARDED_PREFIXES in batch 562.
        // Uncapped, a hostile client could churn inserts until the DB
        // cap kicks in at MAX_PER_USER rows — each POST costs one
        // INSERT on the hot path until then. Sweep each prefix from its
        // own IP so no cross-surface state bleeds through.
        given:
        def prefixes = [
            '/api/watchlist',
            '/api/watchlist/alerts',
            '/api/saved-searches',
            '/api/follows'
        ]

        when: "each endpoint independently hits 429 after 20 rapid writes"
        def results = prefixes.collect { p ->
            int allowed = 0
            int blocked = 0
            // One fresh ip per surface so the test is order-independent.
            def ip = '10.0.5.' + (prefixes.indexOf(p) + 1)
            (1..25).each {
                def resp = new MockHttpServletResponse()
                filter.doFilter(post(p, ip), resp, chain)
                if (resp.status == 429) blocked++ else allowed++
            }
            [p: p, allowed: allowed, blocked: blocked]
        }

        then:
        results.every { it.allowed == 20 && it.blocked == 5 }
    }
}
