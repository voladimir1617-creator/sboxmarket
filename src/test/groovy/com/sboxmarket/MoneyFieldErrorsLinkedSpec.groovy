package com.sboxmarket

import spock.lang.Specification

/**
 * Screen-reader half of the money forms (WCAG 3.3.1 / 3.3.2 / 4.1.3). Nothing
 * visible: the wallet, offer and sell amount fields point at their error line
 * and flag themselves invalid while it shows, the wallet error is announced,
 * and the withdraw 2FA code and transaction search fields have names.
 */
class MoneyFieldErrorsLinkedSpec extends Specification {

    private static String modalsJs() {
        def f = new File('src/main/resources/static/js/modals.js')
        assert f.exists(), "modals.js not found at ${f.absolutePath}"
        return f.getText('UTF-8')
    }

    def "each money form's error line has an id its amount field points at"() {
        given:
        def js = modalsJs()

        expect:
        js.contains("id: '${id}'")
        js.count("'aria-describedby': ") >= 4
        js.contains("? '${id}' : undefined")

        where:
        id << ['wallet-error-msg', 'offer-error-msg', 'sell-error-msg']
    }

    def "the wallet error is announced and the 2FA code and transaction search are named"() {
        given:
        def js = modalsJs()

        expect:
        js.contains("className: 'wallet-error', id: 'wallet-error-msg', role: 'alert'")
        js.contains("'aria-label': '2FA code'")
        js.count("'aria-label': 'Search transactions'") >= 2
        js.count("'aria-invalid': ") >= 4
    }
}
