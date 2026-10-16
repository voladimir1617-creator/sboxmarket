package com.sboxmarket

import spock.lang.Specification

/**
 * Pass 44 copy checks.
 *  - The "Is my money safe?" answer named the session cookie SameSite-strict;
 *    production sets Lax (Strict breaks the Steam and Stripe redirects).
 *  - The "Mine hidden" chip's tooltip said the user's listings were showing.
 */
class HelpCopyMatchesConfigSpec extends Specification {

    def "the help answer names the SameSite mode production actually sets"() {
        given:
        def prod = new File('src/main/resources/application-prod.yml').text
        def help = new File('src/main/resources/static/js/help-modal.js').text

        expect:
        prod =~ /(?m)^\s*same-site:\s*lax\s*$/
        !help.contains('SameSite-strict')
        help.contains('SameSite=Lax in production')
    }

    def "the Mine hidden chip's tooltip says the listings are hidden"() {
        given:
        def app = new File('src/main/resources/static/js/app.js').text

        expect:
        !app.contains("'Showing your own listings — click to hide them from the grid'")
        app.contains("'Your listings are hidden — click to show them again'")
    }
}
