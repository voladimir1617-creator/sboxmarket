package com.sboxmarket

import com.sboxmarket.service.ZeroResultSearchTracker
import spock.lang.Specification

class ZeroResultSearchTrackerSpec extends Specification {

    ZeroResultSearchTracker tracker = new ZeroResultSearchTracker()

    def "terms are trimmed, lower-cased, spaces collapsed and cut to 60 chars"() {
        expect:
        ZeroResultSearchTracker.normalise('  Red   CAP ') == 'red cap'
        ZeroResultSearchTracker.normalise('   ') == null
        ZeroResultSearchTracker.normalise(null) == null
        ZeroResultSearchTracker.normalise('x' * 200).length() == 60
    }

    def "the same requester's repeat within the window counts once; other requesters count"() {
        when:
        tracker.record('Red Cap', 'a', 1_000L)
        tracker.record('red cap', 'a', 2_000L)      // grid re-poll
        tracker.record('red cap', 'b', 2_000L)
        tracker.record('red cap', 'a', 1_000L + ZeroResultSearchTracker.REPEAT_WINDOW_MS)
        tracker.record('boots', 'a', 3_000L)

        then:
        tracker.top() == [[term: 'red cap', count: 3L], [term: 'boots', count: 1L]]
    }

    def "top is capped and the tally stops taking new terms once full"() {
        given:
        (1..ZeroResultSearchTracker.MAX_TERMS).each { tracker.record("t${it}", 'a', 1L) }

        expect:
        !tracker.record('one too many', 'a', 1L)
        tracker.size() == ZeroResultSearchTracker.MAX_TERMS
        tracker.record('t1', 'b', 1L)
        tracker.top(3).first() == [term: 't1', count: 2L]
        tracker.top(10).size() == 10
    }
}
