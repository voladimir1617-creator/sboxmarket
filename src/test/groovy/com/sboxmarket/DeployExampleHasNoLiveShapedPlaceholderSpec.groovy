package com.sboxmarket

import com.sboxmarket.config.MoneyMode
import spock.lang.Specification
import spock.lang.Unroll

/**
 * <b>The file whose job is to be copied must not ship a value shaped like a
 * real key.</b>
 *
 * {@code deploy/skinbox.env.example} shipped
 * {@code STRIPE_SECRET_KEY=sk_live_CHANGE_ME}. Every guard in this codebase
 * classifies a Stripe key by its PREFIX — {@link MoneyMode#ofKey} deliberately
 * does not look at length (see {@code MoneyModeSpec}: "short live keys stay
 * LIVE here on purpose") — so copying the example unedited produced a
 * deployment that reported itself LIVE with no real key behind it. The file's
 * own header warns against exactly this ("do not put a placeholder sk_live_
 * value here to get past the error") while the line 33 lines below it did it.
 *
 * It also defeated a guard the header cites: {@code docker compose} refuses to
 * start when {@code STRIPE_SECRET_KEY} has no value, and {@code sk_live_CHANGE_ME}
 * <i>is</i> a value.
 *
 * <h3>Why the fix is here and not in MoneyMode</h3>
 *
 * The tempting fix is to teach {@link MoneyMode} that {@code CHANGE_ME} is a
 * placeholder. That is the wrong place, for two reasons:
 *
 * <ol>
 *   <li>{@link MoneyMode#SIMULATED} is the <i>only</i> classification that
 *       authorises the in-process fabricated deposit
 *       ({@link MoneyMode#devFallbackAuthorized}). A placeholder detector that
 *       can output SIMULATED is a free-money hole one false positive wide.
 *       Widening it is how a real key gets misread as fake.</li>
 *   <li>The defect is in the artefact, not the classifier. A test that reads
 *       the artefact can never misclassify a real key, because it never sees
 *       one — and it stays true for the NEXT placeholder token, which will not
 *       be spelled {@code CHANGE_ME}.</li>
 * </ol>
 *
 * So the rule pinned here is shape-based and total: no value in the example may
 * wear a Stripe prefix, and the secret-key placeholder must classify
 * {@link MoneyMode#INDETERMINATE} — which authorises nothing on any profile —
 * rather than {@link MoneyMode#SIMULATED}, which authorises the fabricated
 * deposit, or {@link MoneyMode#LIVE}, which claims a key that is not there.
 */
class DeployExampleHasNoLiveShapedPlaceholderSpec extends Specification {

    static final String EXAMPLE = 'deploy/skinbox.env.example'

    /** Parsed KEY -> VALUE, comments and blank lines dropped. Fails rather
     *  than returning an empty map if the file is missing: "I cannot read it"
     *  must never pass as "it is clean". */
    private static Map<String, String> settings() {
        def f = new File(EXAMPLE)
        assert f.exists(), "expected ${EXAMPLE} to exist — cannot audit placeholders in a file that is not there"
        Map<String, String> out = [:]
        f.getText('UTF-8').readLines().each { String line ->
            String t = line.trim()
            if (!t || t.startsWith('#') || !t.contains('=')) return
            int i = t.indexOf('=')
            out[t.substring(0, i).trim()] = t.substring(i + 1).trim()
        }
        assert !out.isEmpty(), "parsed no settings out of ${EXAMPLE} — the parser or the file is broken"
        out
    }

    def "the example ships a STRIPE_SECRET_KEY placeholder that classifies INDETERMINATE"() {
        given:
        def value = settings()['STRIPE_SECRET_KEY']

        expect: 'the line is present at all — a deleted line is not a fix'
        value != null

        and: 'it is NOT read as a usable key of either mode'
        MoneyMode.ofKey(value) != MoneyMode.LIVE
        MoneyMode.ofKey(value) != MoneyMode.TEST

        and: 'and NOT as SIMULATED either — that is the one value that authorises free credit'
        MoneyMode.ofKey(value) != MoneyMode.SIMULATED
        !MoneyMode.ofKey(value).devFallbackAuthorized()

        and: 'leaving exactly one answer: authorise nothing, on every profile'
        MoneyMode.ofKey(value) == MoneyMode.INDETERMINATE
    }

    @Unroll
    def "#key in the example does not wear a Stripe prefix"() {
        given:
        def value = settings()[key]

        expect: 'the line is present at all — a deleted line is not a fix'
        value != null

        and: 'no live/test secret prefix — those are what MoneyMode keys on'
        (MoneyMode.LIVE_PREFIXES + MoneyMode.TEST_PREFIXES).every { !value.startsWith(it) }

        and: 'nor a publishable or webhook prefix — ProdConfigValidator keys on those'
        !value.startsWith('pk_live_')
        !value.startsWith('pk_test_')
        !value.startsWith('whsec_')

        where:
        key << ['STRIPE_SECRET_KEY', 'STRIPE_PUBLISHABLE_KEY', 'STRIPE_WEBHOOK_SECRET']
    }

    def "NO value anywhere in the example wears a Stripe secret prefix"() {
        // The per-key cases above only cover the three keys we know about. This
        // one catches a fourth Stripe variable added later with the same
        // plausible-looking placeholder.
        given:
        def offenders = settings().findAll { k, v ->
            (MoneyMode.LIVE_PREFIXES + MoneyMode.TEST_PREFIXES).any { v.startsWith(it) }
        }

        expect:
        offenders.isEmpty()

        and: 'named in the failure message rather than just counted'
        offenders.keySet() == [] as Set
    }

    def "the header's warning is still present — the rule must be readable, not only enforced"() {
        given:
        def raw = new File(EXAMPLE)

        expect:
        raw.exists()
        raw.getText('UTF-8').contains('do not put a placeholder sk_live_ value here')
    }
}
