package com.sboxmarket.util

import spock.lang.Specification

class TradeUrlOwnershipSpec extends Specification {

    def "a trade URL is owned only by the account its partner id derives from"() {
        expect:
        TradeUrlOwnership.ownedBy(url, sid) == owned

        where:
        url                                                                  | sid                 | owned
        'https://steamcommunity.com/tradeoffer/new/?partner=11&token=abc'   | '76561197960265739' | true
        'https://steamcommunity.com/tradeoffer/new/?partner=12&token=abc'   | '76561197960265739' | false
        'https://steamcommunity.com/tradeoffer/new/?token=abc&partner=11'   | '76561197960265739' | true
        'https://steamcommunity.com/tradeoffer/new/?partner=111&token=abc'  | '76561197960265739' | false
        'https://steamcommunity.com/tradeoffer/new/?partner=11&token=abc'   | null                | true
        null                                                                 | '76561197960265739' | true
        'https://steamcommunity.com/tradeoffer/new/?token=abc'              | '76561197960265739' | true
        'https://steamcommunity.com/tradeoffer/new/?partner=11&token=abc'   | 'not-a-number'      | true
    }
}
