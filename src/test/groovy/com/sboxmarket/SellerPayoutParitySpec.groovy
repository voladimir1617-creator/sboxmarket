package com.sboxmarket

import com.sboxmarket.model.Trade
import com.sboxmarket.repository.TradeRepository
import com.sboxmarket.service.NotificationService
import com.sboxmarket.service.TradeService
import com.sboxmarket.service.security.BanGuard
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

/**
 * The "seller receives" line must be the number the server will actually pay.
 *
 * ── The defect this pins ──────────────────────────────────────────────────
 * {@code TradeService.FEE_RATE} is the single source of truth for the take
 * rate, and it is server-side. The frontend did not use it: eight separate
 * places hardcoded {@code 0.02} / {@code 0.98} with a DIFFERENT rounding rule.
 *
 *   server : feeAmount = (price * FEE_RATE).setScale(2, ROUND_HALF_UP)
 *            credit    = price - feeAmount            // round the FEE, subtract
 *   the UI : fmt(price * 0.98)                        // subtract, round the PAYOUT
 *
 * Those two disagree whenever the fee lands exactly on half a cent — every
 * price ending .25 or .75. Measured across the 200,000 prices from $0.01 to
 * $2,000.00 they disagreed on 3,711 of them (1.86%), and the screen was one
 * cent HIGH on every single one: $1.25 displayed "$1.23" while the server
 * credited $1.22. Rounding went the PLATFORM's way, which is the direction
 * nobody complains about until they do.
 *
 * ── What this spec holds down ─────────────────────────────────────────────
 *  1. the SERVER's rule really is round-the-fee-then-subtract (driven through
 *     the real {@code TradeService.open}, not restated);
 *  2. the UI ships exactly ONE payout helper, and it mirrors that order;
 *  3. the UI's rate constant still equals the server's {@code FEE_RATE};
 *  4. NO shipped script re-derives a payout by hand — because eight hand-
 *     written copies is how this bug happened, and how it would come back;
 *  5. all of the above hold for the artefact the browser is actually SERVED
 *     ({@code build/resources/main/…}), not just the copy in {@code src/}.
 *
 * (5) is not paranoia. The server serves {@code build/resources/main/}; a fix
 * present only in {@code src/} changes nothing a user sees, and a check that
 * only reads {@code src/} would report green for a site still shipping the bug.
 */
class SellerPayoutParitySpec extends Specification {

    // ── the frontend + backend artefacts under test ───────────────────────

    private static final List<String> UI_SCRIPT_DIRS = [
        'src/main/resources/static/js',            // the source of truth
        'build/resources/main/static/js'           // what the browser is SERVED
    ]

    private static File file(String path) {
        def f = new File(path)
        assert f.exists(), "expected ${path} to exist at ${f.absolutePath}"
        return f
    }

    private static String text(String path) { file(path).getText('UTF-8') }

    /** Source with whole-line comments removed, so the prose EXPLAINING the
     *  old bug is not mistaken for the old bug. Only leading `//` and JSDoc
     *  continuation lines are stripped — never mid-line, so a `0.02` sitting
     *  inside real code (or inside a URL) can never be hidden from the scan. */
    private static String codeOnly(String src) {
        src.readLines()
           .findAll { String l -> def t = l.trim(); !(t.startsWith('//') || t.startsWith('*') || t.startsWith('/*')) }
           .join('\n')
    }

    /** Every shipped script, keyed by "<dir>/<name>". */
    private static Map<String, String> allUiScripts() {
        Map<String, String> out = [:]
        UI_SCRIPT_DIRS.each { String dir ->
            def d = new File(dir)
            assert d.isDirectory(), "expected shipped scripts at ${d.absolutePath} — " +
                "if build/resources/main is missing, the served artefact is UNCHECKED and this spec is worthless"
            d.listFiles({ File f -> f.name.endsWith('.js') } as FileFilter)
             .each { File f -> out["${dir}/${f.name}".toString()] = f.getText('UTF-8') }
        }
        assert out.size() >= 2
        return out
    }

    // ── 1. the SERVER's rule, driven for real ─────────────────────────────

    TradeRepository     tradeRepository     = Mock()
    NotificationService notificationService = Mock()
    BanGuard            banGuard            = Mock()

    @Subject
    TradeService tradeService = new TradeService(
        tradeRepository     : tradeRepository,
        notificationService : notificationService,
        banGuard            : banGuard
    )

    private Trade openAt(String price) {
        Trade saved = null
        tradeRepository.save(_) >> { Trade t -> saved = t; t }
        tradeService.open(100L, 1L, 'AK-47 | Redline', 10L, 500L, 20L, 600L, new BigDecimal(price))
        assert saved != null
        return saved
    }

    @Unroll
    def "server: a #price sale charges a #fee fee and credits the seller #credit"() {
        when:
        def t = openAt(price)

        then: "the fee is rounded to the cent, THEN subtracted — not the other way round"
        t.feeAmount == new BigDecimal(fee)

        and: "so the seller's actual credit is:"
        (t.price - t.feeAmount) == new BigDecimal(credit)

        where:
        // Every one of these is a price whose 2% lands on exactly half a cent
        // — the split where the two orders of operations diverge. The last row
        // is an ordinary price, present so a broken harness can't pass by
        // accident on ties alone.
        price     | fee    | credit
        '0.25'    | '0.01' | '0.24'
        '0.75'    | '0.02' | '0.73'
        '1.25'    | '0.03' | '1.22'
        '1.75'    | '0.04' | '1.71'
        '2.25'    | '0.05' | '2.20'
        '5.25'    | '0.11' | '5.14'
        '12.75'   | '0.26' | '12.49'
        '100.00'  | '2.00' | '98.00'
    }

    def "server: the naive 'price times 0.98' answer is genuinely a different number"() {
        // Guards the spec itself. If this ever stops holding, the two rules
        // have converged and the rows above no longer discriminate — the
        // whole spec would be passing vacuously.
        when:
        def t = openAt('1.25')

        then:
        (t.price - t.feeAmount) == new BigDecimal('1.22')

        and: "while subtract-then-round-the-payout gives 1.23"
        (t.price * new BigDecimal('0.98')).setScale(2, BigDecimal.ROUND_HALF_UP) == new BigDecimal('1.23')
    }

    // ── 2. the UI ships ONE helper, in the server's order ─────────────────

    def "the UI exports a shared payout helper instead of eight hand-written copies"() {
        given:
        def utils = text('src/main/resources/static/js/utils.js')

        expect: "a single exported fee helper"
        utils.contains('export const platformFee')

        and: "a single exported payout helper"
        utils.contains('export const sellerPayout')

        and: "and an aggregate helper — the fee is charged PER TRADE, so a stall's" +
             " net is the sum of each sale's payout, not the payout of the summed gross"
        utils.contains('export const sellerPayoutTotal')
    }

    def "the payout helper rounds the FEE and subtracts, mirroring the server"() {
        given:
        def utils = codeOnly(text('src/main/resources/static/js/utils.js'))

        expect: "the fee is what gets rounded to the cent"
        utils.contains('return Math.round(p * PLATFORM_FEE_PERCENT) / 100;')

        and: "and the payout is gross-cents minus fee-cents — integer cents throughout, so" +
             " no float dust and no second rounding can reach the rendered figure"
        utils.contains('return (Math.round(p * 100) - Math.round(p * PLATFORM_FEE_PERCENT)) / 100;')

        and: "the helper never reconstitutes the old subtract-first form"
        !utils.contains('* 0.98')
        !utils.contains('*0.98')
    }

    // ── 3. the UI's rate is still the SERVER's rate ───────────────────────

    def "the UI's fee constant is the same rate as TradeService.FEE_RATE"() {
        given: "the rate the server actually charges, read from its own source"
        def server = text('src/main/groovy/com/sboxmarket/service/TradeService.groovy')
        def m = (server =~ /FEE_RATE\s*=\s*new BigDecimal\('([0-9.]+)'\)/)
        assert m.find(), 'could not locate TradeService.FEE_RATE — has it been renamed?'
        def serverRate = new BigDecimal(m.group(1))

        and: "and the rate the UI quotes, read from its own source"
        def utils = text('src/main/resources/static/js/utils.js')
        def u = (utils =~ /PLATFORM_FEE_PERCENT\s*=\s*([0-9.]+)/)
        assert u.find(), 'could not locate PLATFORM_FEE_PERCENT in utils.js'
        def uiPercent = new BigDecimal(u.group(1))

        expect: "they are the same rate. A server-side fee change that is not mirrored" +
                " here makes every quoted payout wrong the moment it deploys."
        uiPercent.divide(new BigDecimal('100')) == serverRate.stripTrailingZeros()
    }

    // ── 4. + 5. nothing re-derives a payout by hand, in EITHER artefact ───

    @Unroll
    def "no shipped script re-derives a payout by hand (#form)"() {
        given:
        def offenders = allUiScripts().findAll { name, src ->
            // utils.js is where the arithmetic is SUPPOSED to live.
            !name.endsWith('/utils.js') && (codeOnly(src) =~ pattern).find()
        }.keySet()

        expect: "the fee lives in exactly one place; a hand-written copy is how this came back"
        offenders.isEmpty()

        where:
        form            | pattern
        'price * 0.98'  | /\*\s*0\.98/
        '0.98 * price'  | /0\.98\s*\*/
        'price * 0.02'  | /\*\s*0\.02/
        '0.02 * price'  | /0\.02\s*\*/
    }

    def "the SERVED artefact carries the helper too, not just the copy in src"() {
        // The trap this closes: the app serves build/resources/main/, so a fix
        // that lives only in src/ changes nothing the browser receives.
        given:
        def served = text('build/resources/main/static/js/utils.js')

        expect:
        served.contains('export const sellerPayout')
        served.contains('return (Math.round(p * 100) - Math.round(p * PLATFORM_FEE_PERCENT)) / 100;')

        and: "and it is byte-identical to source — a stale build is a silent revert"
        served == text('src/main/resources/static/js/utils.js')
    }

    @Unroll
    def "#site consumes the shared helper"() {
        given:
        def src = text("src/main/resources/static/js/${f}")

        expect: "it imports the helper"
        src.contains(imported)

        and: "and calls it"
        (src =~ call).find()

        where:
        site                       | f           | imported        | call
        'the trade-confirm dialog' | 'modals.js' | 'sellerPayout'  | /fmt\(sellerPayout\(confirmTrade\.price/
        'the incoming-offer net'   | 'modals.js' | 'sellerPayout'  | /const net = sellerPayout\(amt\)/
        'the sell-price preview'   | 'modals.js' | 'platformFee'   | /const fee = platformFee\(p\)/
        'the stall earnings total' | 'modals.js' | 'sellerPayoutTotal' | /const soldNet = sellerPayoutTotal\(/
        'the relist payout delta'  | 'modals.js' | 'sellerPayout'  | /const netNew = sellerPayout\(newP\)/
        'the homepage fee calc'    | 'app.js'    | 'platformFee'   | /const fee = platformFee\(amt\)/
    }
}
