package com.sboxmarket

import com.sboxmarket.controller.TradeSafetyController
import com.sboxmarket.service.SteamEscrowService
import com.sboxmarket.service.SteamTradeBotService
import spock.lang.Specification
import spock.lang.Unroll

/**
 * The safety page must never tell a seller to refuse the platform's own trade
 * offer.
 *
 * ── The defect this pins ──────────────────────────────────────────────────
 * {@code /legal/trade-safety.html} stated, flatly and unconditionally:
 *
 *     "SkinBox is non-custodial. We never hold your skins... No bot account
 *      ever touches the items."
 *     "Never accept a trade offer from a SkinBox 'bot' — we don't have one.
 *      ...If someone DMs you claiming to be 'the SkinBox bot', it is a scam."
 *
 * The instant {@code STEAM_BOT_BASE_URL} is set,
 * {@code SteamEscrowService.requestDepositForListing} sends every seller
 * exactly that offer, to take their item into bot custody. So one config value
 * turns a safety page into a page that (a) misstates who holds the items and
 * (b) trains sellers to decline the deposit offer their own listing depends on
 * — and nothing in the codebase connected the config to the copy.
 *
 * The page was TRUE at the time this was found, because the bot was off. That
 * is precisely what made it dangerous: it was a factual custody claim with a
 * silent expiry date and no alarm attached.
 *
 * ── What this spec holds down ─────────────────────────────────────────────
 * The load-bearing one is {@code "enabling escrow cannot leave a false custody
 * claim standing"}: it builds the REAL escrow stack with a bot base URL set —
 * i.e. it does the exact thing the operator will do on the day he turns the bot
 * on — and fails if the page still says non-custodial. The remaining tests stop
 * the claim being re-hardcoded anywhere it could escape that check.
 */
class CustodyClaimHonestySpec extends Specification {

    private static final String TEMPLATE = 'src/main/resources/static/legal/trade-safety.html'

    /** Sentences that are FALSE the moment the escrow bot is switched on. */
    private static final Map<String, String> FALSE_WHEN_CUSTODIAL = [
        'the non-custodial claim'          : 'non-custodial',
        'the "we never hold your skins"'   : 'We never hold your skins',
        'the "no bot touches items"'       : 'No bot account ever touches the items',
        'the "we have no bot"'             : "we don't have one",
        'the "no middle man exists"'       : 'There is no such thing on SkinBox',
    ]

    private static String text(String path) {
        def f = new File(path)
        assert f.exists(), "expected ${path} at ${f.absolutePath}"
        f.getText('UTF-8')
    }

    /** The real stack, wired exactly as production wires it. `baseUrl` is the
     *  value of STEAM_BOT_BASE_URL; blank means the bot is off. */
    private static TradeSafetyController controllerWithBotUrl(String baseUrl) {
        def bot = new SteamTradeBotService(baseUrl: baseUrl)
        def escrow = new SteamEscrowService(steamTradeBotService: bot)
        return new TradeSafetyController(steamEscrowService: escrow)
    }

    // ── the one that matters ──────────────────────────────────────────────

    @Unroll
    def "enabling escrow cannot leave #claim standing"() {
        given: "the operator sets STEAM_BOT_BASE_URL — the single act that makes the bot start requesting sellers' items"
        def controller = controllerWithBotUrl('http://127.0.0.1:4000')

        expect: "the platform now genuinely takes custody"
        controller.custodial
        controller.custodyMode() == TradeSafetyController.MODE_BOT_CUSTODY

        when: "the safety page is rendered"
        def page = controller.render(text(TEMPLATE))

        then: "it does not carry a sentence that is now false"
        !page.contains(sentence)

        where:
        claim << FALSE_WHEN_CUSTODIAL.keySet()
        sentence << FALSE_WHEN_CUSTODIAL.values()
    }

    def "under bot custody the page tells the seller the bot's offer is real, and how to check it"() {
        given:
        def page = controllerWithBotUrl('http://127.0.0.1:4000').render(text(TEMPLATE))

        expect: "it says we hold listed items"
        page.contains('SkinBox holds listed items in escrow')

        and: "it warns the seller to expect an offer FROM us, rather than calling one a scam"
        page.contains('SkinBox will send you trade offers')

        and: "and it still gives a concrete way to tell ours from an impostor's — the point of the original rule"
        page.contains('/profile/trades')
        page.toLowerCase().contains('scam')
    }

    // ── the claim is made conditional, not deleted ────────────────────────

    @Unroll
    def "with the bot OFF the page still gives the original advice: #claim"() {
        given: "no STEAM_BOT_BASE_URL — the mode the operator is launching in"
        def controller = controllerWithBotUrl('')

        expect:
        !controller.custodial
        controller.custodyMode() == TradeSafetyController.MODE_NON_CUSTODIAL

        and: "the safety advice that is TRUE in this mode is still on the page — the fix was to make the claim conditional, not to delete it"
        controller.render(text(TEMPLATE)).contains(sentence)

        where:
        claim << FALSE_WHEN_CUSTODIAL.keySet()
        sentence << FALSE_WHEN_CUSTODIAL.values()
    }

    // ── the mode is read from the thing that actually sends the offer ─────

    def "custody mode is derived from the same flag that gates the deposit offer"() {
        // Not a parallel copy of the condition — the SAME predicate
        // requestDepositForListing consults before sending a seller an offer.
        given:
        def bot = new SteamTradeBotService(baseUrl: url)
        def escrow = new SteamEscrowService(steamTradeBotService: bot)

        expect:
        new TradeSafetyController(steamEscrowService: escrow).custodial == escrow.escrowEnabled

        where:
        url << ['http://127.0.0.1:4000', '', '   ', null]
    }

    // ── a bypass cannot serve a false claim ───────────────────────────────

    @Unroll
    def "the raw template carries no unconditional custody claim (#claim)"() {
        given: "the file as it sits on disk, with no controller involved"
        def raw = text(TEMPLATE)

        expect: "the mode-dependent sentences are not baked in ANYWHERE in the file —" +
                " comments included, because the whole file is shipped to the browser and a" +
                " claim in a comment is still readable in view-source. If the static resource" +
                " handler ever serves this file directly, the reader gets a page with no" +
                " custody claim — incomplete, but never false."
        !raw.contains(sentence)

        and: "they are supplied by a placeholder instead"
        raw.contains('<!--SB:CUSTODY_CALLOUT-->')
        raw.contains('<!--SB:CUSTODY_BOT_RULE-->')

        where:
        claim << FALSE_WHEN_CUSTODIAL.keySet()
        sentence << FALSE_WHEN_CUSTODIAL.values()
    }

    // ── the SPA's product copy is gated the same way ──────────────────────

    @Unroll
    def "no shipped script hardcodes a custody claim (#file)"() {
        given: "every JS bundle except utils.js, where the mode-gated variants live"
        def src = new File(dir, file).getText('UTF-8')
        def code = src.readLines()
                      .findAll { String l -> def t = l.trim(); !(t.startsWith('//') || t.startsWith('*') || t.startsWith('/*')) }
                      .join('\n')

        expect: "the home hero, the trust tile and the affiliate blurb all read the" +
                " mode from the server now; a hardcoded claim here would outlive the config"
        !code.toLowerCase().contains('non-custodial')
        !code.contains('never holds custody')

        where:
        [dir, file] << [
            ['src/main/resources/static/js', 'build/resources/main/static/js'],
            ['app.js', 'modals.js', 'api.js', 'cards.js', 'csfloat-modals.js',
             'help-modal.js', 'info-modal.js', 'primitives.js', 'staff-modals.js']
        ].combinations()
    }

    def "utils.js keeps BOTH variants, so the claim survives — it is chosen, not deleted"() {
        given:
        def utils = text('src/main/resources/static/js/utils.js')

        expect: "the non-custodial wording still exists, behind the mode gate"
        utils.contains("tileTitle: 'Non-Custodial'")
        utils.contains("mode === 'NON_CUSTODIAL'")

        and: "so does the custodial wording"
        utils.contains("mode === 'BOT_CUSTODY'")

        and: "and the default, before the server has answered, claims neither"
        def neutral = utils.substring(utils.indexOf('Mode-neutral'))
        !neutral.toLowerCase().contains('non-custodial')
    }

}
