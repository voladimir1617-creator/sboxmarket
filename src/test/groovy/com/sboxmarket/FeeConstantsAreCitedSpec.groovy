package com.sboxmarket

import spock.lang.Specification
import spock.lang.Unroll

import java.util.regex.Pattern

/**
 * Every processor charge in the fee model is the figure Stripe publishes,
 * and every one of them says where that figure came from.
 *
 * <h3>What was wrong</h3>
 *
 * Nothing, as it turned out — but nobody could tell. An audit on 2026-09-20
 * found that not one of the six processor figures carried a citation: not
 * the two deposit legs, not the two payout legs, not the USD 2.00
 * per-account charge, not the USD 15.00 dispute fee. All asserted, none
 * sourced. The sibling defect in the same week was a USD 101.95 break-even
 * that entered as prose in commit 8f9b725 and that nothing computed; the
 * real figure is {@code 2.00 / 0.02 = 100.00} exactly. An uncited number is
 * indistinguishable from an invented one until somebody re-derives it, and
 * these six decide the product's break-even, both minimums, and what a
 * seller is told they will receive.
 *
 * <h3>What this pins</h3>
 *
 * <ol>
 *   <li><b>The value.</b> Each shipped default equals the figure on Stripe's
 *       own US pricing pages, read 2026-09-20 — quoted verbatim in the
 *       {@code where:} block below, so a reviewer checks a string rather
 *       than re-doing the research.</li>
 *   <li><b>The citation.</b> Each figure carries a {@code stripe.com/…}
 *       source and an ISO read date within the comment block above it, in
 *       BOTH the config and the service that binds it. This is the half
 *       that decays: a value can be corrected in a minute, but a number
 *       whose provenance was never written down costs the same audit
 *       again every time it is questioned.</li>
 * </ol>
 *
 * <h3>How to re-check</h3>
 *
 * Open the two URLs, compare the six figures, and move the read date. If a
 * figure has moved, change the default AND the quote here in the same
 * commit — they are pinned to each other on purpose.
 *
 * <h3>What is deliberately NOT pinned here</h3>
 *
 * {@code platform.max-fee-share}, {@code TradeService.FEE_RATE} and
 * {@code TradeProtectionService.PROTECTION_RATE}/{@code MIN_FEE} are the
 * platform's own pricing decisions. They have no external source and this
 * spec must not grow one for them by imitation — {@code
 * SellerCommissionIsUnflooredSpec} is where those live.
 */
class FeeConstantsAreCitedSpec extends Specification {

    static final File YML    = new File('src/main/resources/application.yml')
    static final File LEDGER = new File('src/main/groovy/com/sboxmarket/service/PlatformLedgerService.groovy')

    /** A citation is a stripe.com source plus the ISO date it was read. */
    static final Pattern CITATION = ~/stripe\.com\/[a-z\/]+/
    static final Pattern READ_ON  = ~/read 20\d\d-\d\d-\d\d/

    /**
     * The N lines above a match, where a citation comment must live.
     *
     * 60, not 10: two of these javadocs run past forty lines because they
     * carry a correction as well as a citation, and a window that only
     * reaches the last paragraph would fail a field that IS cited. It is
     * still a locality constraint — a citation somewhere else in the file
     * does not satisfy it.
     */
    private static String contextAbove(String text, int matchStart, int lines = 60) {
        def head = text.substring(0, matchStart)
        def all  = head.readLines()
        all.subList(Math.max(0, all.size() - lines), all.size()).join('\n')
    }

    @Unroll
    def "platform.#key ships #expected — #quote"() {
        given: "the shipped default in application.yml"
        def text = YML.text
        def m = (text =~ /(?m)^\s*${Pattern.quote(key)}:\s*\$\{[A-Z_]+:([0-9.]+)\}\s*$/)

        expect: "the key is present with a literal default (a missing key is not a pass)"
        m.find()

        and: "and that default is the figure Stripe publishes for the United States"
        m.group(1) == expected

        and: "and the comment block above it says where the figure came from, and when it was read"
        def ctx = contextAbove(text, m.start())
        CITATION.matcher(ctx).find()
        READ_ON.matcher(ctx).find()

        where:
        key                          | expected | quote
        'processing-fee-percent'     | '2.9'    | 'stripe.com/pricing: "2.9% + 30c per successful transaction for domestic cards"'
        'processing-fee-fixed'       | '0.30'   | 'stripe.com/pricing: "2.9% + 30c per successful transaction for domestic cards"'
        'payout-fee-percent'         | '0.25'   | 'stripe.com/connect/pricing: "0.25% + 25c per payout sent"'
        'payout-fee-fixed'           | '0.25'   | 'stripe.com/connect/pricing: "0.25% + 25c per payout sent"'
        'payout-account-monthly-fee' | '2.00'   | 'stripe.com/connect/pricing: "$2 per monthly active account"'
        'dispute-fee'                | '15.00'  | 'stripe.com/pricing: "Dispute received fee $15.00 for each dispute you receive"'
    }

    @Unroll
    def "PlatformLedgerService.#field carries the same citation the config does"() {
        given: "the @Value-bound field and the javadoc above it"
        def text = LEDGER.text
        def m = (text =~ /(?m)^\s*@Value\('\$\{platform\.[a-z-]+:[0-9.]+\}'\)\s*BigDecimal\s+${Pattern.quote(field)}\b/)

        expect: "the field binds a platform.* key with a literal default"
        m.find()

        and: "and its javadoc names the source and the date it was read"
        def ctx = contextAbove(text, m.start())
        CITATION.matcher(ctx).find()
        READ_ON.matcher(ctx).find()

        where:
        field << ['processingFeePercent', 'processingFeeFixed',
                  'payoutFeePercent', 'payoutFeeFixed',
                  'payoutAccountMonthlyFee', 'disputeFee']
    }

    /**
     * No shipped surface may tell a user that moving money is free.
     *
     * <h4>What was wrong</h4>
     *
     * The pass-through change made the user bear Stripe's cost, and the copy
     * was never moved with it. On 2026-09-20 five surfaces still said the
     * opposite of what the money path does, two of them legal pages:
     *
     * <ul>
     *   <li>the FAQ: "Deposits and withdrawals are free."</li>
     *   <li>the FAQ again: "Stripe merchant fees are paid by SkinBox, not
     *       the user" and "that's the only fee"</li>
     *   <li>legal/refunds.html: "Withdrawals are free — 100% of the
     *       requested amount reaches your bank"</li>
     *   <li>legal/terms.html: "Deposits and withdrawals are free — Stripe
     *       processor fees on deposits are absorbed by SkinBox"</li>
     * </ul>
     *
     * A $10 first-of-month withdrawal delivers $7.73. "100% of the requested
     * amount reaches your bank", on a refunds policy page, is the most
     * expensive sentence in the repo: it is a written promise, it is false,
     * and the user reading it is deciding whether to trust the platform with
     * money. This is the same defect class as the wallet preview that
     * promised "you receive $9.73" against an actual $7.73, except that one
     * was a computation and these were prose, which is why no test caught
     * them.
     *
     * <h4>Not covered here, and deliberately</h4>
     *
     * Two more surfaces carry the same false claim and are NOT asserted on:
     * {@code static/js/app.js} (the sell calculator's fee-calc-note) and
     * {@code static/js/modals.js} (the withdrawal FAQ answer, which
     * contradicts the correct disclosure elsewhere in the same file). Both
     * files were dirty with other agents' work when this was written, so
     * correcting them would have meant committing someone else's unfinished
     * change. Adding them here before they are fixed would ship a spec that
     * cannot pass. Extend the {@code where:} block the moment they are.
     */
    @Unroll
    def "#file does not tell a user that moving money is free"() {
        given: "a surface a user reads before deciding to fund a wallet"
        def text = new File(file).text

        expect: "no claim that a deposit or a withdrawal is free"
        !(text =~ /(?i)(deposit|withdrawal)s?[^.<]{0,80}\bare free\b/)

        and: "no promise that the full requested amount arrives"
        !(text =~ /(?i)100% of the requested/)

        and: "no claim that the platform absorbs the processor's charge"
        !(text =~ /(?i)(processor|merchant|stripe)[^.<]{0,80}fees?[^.<]{0,40}(absorbed|paid) by SkinBox/)

        where:
        file << ['src/main/resources/static/js/help-modal.js',
                 'src/main/resources/static/legal/terms.html',
                 'src/main/resources/static/legal/refunds.html']
    }

    def "the second dispute fee is recorded as a known gap, not silently absent"() {
        given: 'Stripe bills TWO $15.00 dispute fees, and the model books one'
        def text = YML.text

        expect: "stripe.com/pricing, read 2026-09-20: 'Dispute countered fee \$15.00 for each dispute " +
                "you respond to manually. You get this fee back for won disputes. You don't get this fee " +
                "back for lost disputes.' A contested-and-lost dispute therefore costs \$30.00, not \$15.00. " +
                "The model books the unconditional 'received' leg only; the config must say so rather than " +
                "leave the reader to assume \$15.00 is the whole exposure."
        text.contains('Dispute countered fee')
    }
}
