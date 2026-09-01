package com.sboxmarket

import spock.lang.Specification

/**
 * The Sell modal must SHOW the seller why his inventory came back empty.
 *
 * ── The defect this pins ──────────────────────────────────────────────────
 * SteamInventoryController distinguishes seven causes and sends `reason`,
 * `unreadable` and a remedy sentence in `message`. The Sell modal read NONE
 * of them: it branched on `error` and `blocked` only, so every server-
 * diagnosed cause collapsed into one of two strings.
 *
 * The worst case is not cosmetic. A private profile trips the negative cache,
 * so the server sets `blocked` alongside reason='private_profile'. Because the
 * UI tested `blocked` first, that seller was told "try again in ~5 minutes" —
 * advice that can never come true, since no amount of waiting makes a private
 * inventory readable. The operator is about to be seller #1; if his profile is
 * private on the first attempt, this is the screen he gets.
 *
 * The server side of this contract is pinned behaviourally over real HTTP in
 * SellFlowIntegrationSpec. This spec pins the client half — that the shipped
 * modals.js actually CONSUMES those fields, which is the half that was missing.
 */
class SellInventoryReasonSurfacedSpec extends Specification {

    private static String modalsJs() {
        def f = new File('src/main/resources/static/js/modals.js')
        assert f.exists(), "modals.js not found at ${f.absolutePath}"
        return f.getText('UTF-8')
    }

    def "the Sell modal reads the server's reason, unreadable flag and message"() {
        given:
        def js = modalsJs()

        expect: "it consults the typed cause rather than only error/blocked"
        js.contains('steamData?.reason')

        and: "it consults `unreadable` — the flag separating 'we asked and the answer was zero' from 'we could not get an answer'"
        js.contains('steamData?.unreadable')

        and: "it renders the server's remedy sentence"
        js.contains('steamData?.message')
    }

    def "a private profile is identified specifically, not folded into the rate-limit branch"() {
        given:
        def js = modalsJs()

        expect: "there is an explicit private_profile branch"
        js.contains("'private_profile'")

        and: "and it is evaluated BEFORE the generic blocked/rate-limit fallback, so a private profile is never told to wait"
        js.indexOf("'private_profile'") < js.indexOf("steamData?.blocked")
    }

    def "an unreadable inventory is never reported as owning nothing"() {
        given:
        def js = modalsJs()
        // The 'you own nothing' title must be reachable only when the read
        // actually succeeded. Pin that the unreadable branch is chosen first.
        int unreadableBranch = js.indexOf("unreadable   ? \"We couldn't read your Steam inventory\"")
        int ownNothingTitle  = js.indexOf("'No s&box items in your Steam inventory'")

        expect: "both branches exist"
        unreadableBranch > 0
        ownNothingTitle > 0

        and: "and 'we could not read it' wins over 'you own nothing'"
        unreadableBranch < ownNothingTitle
    }
}
