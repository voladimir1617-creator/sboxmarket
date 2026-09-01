package com.sboxmarket

import com.sboxmarket.config.ProdConfigValidator
import com.sboxmarket.service.EmailService
import org.springframework.beans.factory.annotation.Value
import spock.lang.Specification
import spock.lang.Unroll

import java.lang.reflect.Field

/**
 * The unsubscribe HMAC must never be signed with a value that is in this
 * repository.
 *
 * <h3>The same fail-open shape as the dev-login door</h3>
 *
 * {@code EmailService.unsubscribeSecret} carried
 * {@code @Value('${app.unsubscribe.secret:dev-only-do-not-use-in-production-7f3a9c}')}.
 * {@code app.unsubscribe.secret} is <b>absent from {@code application.yml}</b>
 * and is only supplied by {@code application-prod.yml}, so on the profile that
 * actually runs — {@code default} — the committed literal WAS the secret.
 *
 * Unsubscribe tokens are {@code base64url(HMAC-SHA256(secret, lowercased-email))}
 * and deliberately carry no timestamp, so anyone who can read this repository
 * could mint a valid token for any address and silently mute that user's
 * notifications: price alerts, outbid notices, sign-in-from-a-new-device
 * warnings. No wallet access, low impact — and the identical shape: a value
 * that is MISSING is filled in with a permissive committed default instead of
 * being refused.
 *
 * The field's own comment claimed the opposite was happening — "signatures
 * generated in one dev run don't validate in another (good — no accidental
 * prod bypass)". A constant in public source is the same on every run, on
 * every machine, forever. The fix makes the comment true.
 */
class UnsubscribeSecretNotCommittedSpec extends Specification {

    /** Built the way Spring builds it on the default profile: nothing supplied,
     *  because nothing supplies it. */
    private static EmailService unconfigured(String supplied = null) {
        def svc = new EmailService(unsubscribeSecret: supplied, publicUrl: 'http://localhost:8080')
        svc.init()
        svc
    }

    /** What an attacker computes with nothing but a clone of this repository. */
    private static String forge(String email, String secret) {
        def mac = javax.crypto.Mac.getInstance('HmacSHA256')
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes('UTF-8'), 'HmacSHA256'))
        java.util.Base64.urlEncoder.withoutPadding()
            .encodeToString(mac.doFinal(email.getBytes('UTF-8')))
    }

    // ── THE FORGERY ─────────────────────────────────────────────────

    /**
     * The whole point, driven end to end: compute a token from the published
     * literal alone and offer it to a default-profile service.
     */
    def "a token forged from the published placeholder does not verify"() {
        given: 'the literal is genuinely in this repository — proven, not assumed'
        String published = ProdConfigValidator.DEV_UNSUBSCRIBE_PLACEHOLDER
        String validatorSrc = new File('src/main/groovy/com/sboxmarket/config/ProdConfigValidator.groovy').text

        and: 'an ordinary unconfigured deployment'
        def svc = unconfigured()
        String victim = 'victim@example.com'

        expect: 'anyone with a clone can read the literal'
        validatorSrc.contains(published)

        and: 'and the token they can compute from it is rejected'
        !svc.verifyUnsubscribeToken(victim, forge(victim, published))

        and: 'sanity: the service DOES verify its own token, so the rejection is about the secret'
        svc.verifyUnsubscribeToken(victim, svc.unsubscribeToken(victim))
    }

    def "typing the published placeholder into the config does not make it a secret"() {
        given: '''ProdConfigValidator already refuses to BOOT on this value — but it is
                  @Profile("prod") and the profile that actually runs is `default`, which
                  is exactly the half-closed shape that made sk_live_replace_me survive.'''
        def svc = unconfigured(ProdConfigValidator.DEV_UNSUBSCRIBE_PLACEHOLDER)
        String victim = 'victim@example.com'

        expect: 'the supplied value was discarded'
        svc.unsubscribeSecret != ProdConfigValidator.DEV_UNSUBSCRIBE_PLACEHOLDER

        and: 'so the forgery still fails'
        !svc.verifyUnsubscribeToken(victim, forge(victim, ProdConfigValidator.DEV_UNSUBSCRIBE_PLACEHOLDER))
    }

    @Unroll
    def "an absent secret (#label) is replaced with unguessable material"() {
        given:
        def svc = unconfigured(supplied)

        expect: 'a real secret is present'
        svc.unsubscribeSecret != null
        svc.unsubscribeSecret.trim().length() >= 32

        and: 'and it is not the thing that was supplied'
        svc.unsubscribeSecret != supplied

        where:
        label              | supplied
        'null'             | null
        'empty'            | ''
        'whitespace only'  | '   '
        'the placeholder'  | ProdConfigValidator.DEV_UNSUBSCRIBE_PLACEHOLDER
    }

    def "two unconfigured processes do not share a secret"() {
        given: '''what the old field comment CLAIMED was happening. It was not: a constant
                  is the same everywhere. Now it is true, and it is what makes a leaked
                  link from one machine worthless on another.'''
        def a = unconfigured()
        def b = unconfigured()
        String victim = 'victim@example.com'

        expect:
        a.unsubscribeSecret != b.unsubscribeSecret
        !b.verifyUnsubscribeToken(victim, a.unsubscribeToken(victim))
    }

    // ── AND THE CONFIGURED PATH IS UNTOUCHED ────────────────────────

    def "an explicitly configured secret is honoured verbatim, so prod links stay stable"() {
        given: '''the fix must not quietly randomise a deployment that DID configure one —
                  that would break every outstanding unsubscribe link on every restart,
                  which is a real user-visible regression rather than a security win.'''
        String configured = 'a-real-random-prod-secret-' + ('9' * 16)
        def one = unconfigured(configured)
        def two = unconfigured(configured)
        String victim = 'victim@example.com'

        expect: 'kept exactly'
        one.unsubscribeSecret == configured

        and: 'and two instances with the same configured secret agree, as prod requires'
        two.verifyUnsubscribeToken(victim, one.unsubscribeToken(victim))
    }

    // ── THE DEFAULT ITSELF, ASSERTED OVER THE ANNOTATION ────────────

    /**
     * Read from the compiled {@code @Value} annotation, not from the source
     * text. A source-text scan for the literal would ALSO match the long
     * explanatory comment that now sits above the field describing the bug —
     * i.e. it would fail on a correct fix and pass on a fix written only in a
     * comment. The annotation is the configuration; the comment is prose.
     */
    def "the @Value default is empty — there is no committed fallback left"() {
        given:
        Field f = EmailService.getDeclaredField('unsubscribeSecret')
        Value v = f.getAnnotation(Value)

        expect: 'the annotation is on the FIELD, which is how Spring injects it'
        v != null

        and: 'and it names the property with an EMPTY default'
        v.value() == '${app.unsubscribe.secret:}'

        and: 'so the published literal is not reachable as a fallback'
        !v.value().contains(ProdConfigValidator.DEV_UNSUBSCRIBE_PLACEHOLDER)
    }

    def "application.yml still does not define the secret, and prod still requires it with no default"() {
        given: 'comment-stripped, so a warning comment naming the key cannot satisfy or break this'
        String base = stripHashComments(new File('src/main/resources/application.yml').text)
        String prod = stripHashComments(new File('src/main/resources/application-prod.yml').text)

        expect: '''the base profile must NOT ship a value. Adding one here would recreate
                   the bug in a new place: a committed literal that every unconfigured
                   deployment inherits.'''
        !base.contains('unsubscribe:')
        !base.contains('app.unsubscribe.secret')

        and: 'prod maps it to a mandatory env var — ${VAR} with no `:default`'
        prod.contains('secret: ${APP_UNSUBSCRIBE_SECRET}')
        !(prod =~ /APP_UNSUBSCRIBE_SECRET\s*:/).find()
    }

    def "SELF-TEST: the comment stripper strips, so the assertions above are not vacuous"() {
        given:
        String sample = 'a: 1   # unsubscribe: fake\n# app.unsubscribe.secret: fake\nb: 2'

        when:
        String stripped = stripHashComments(sample)

        then:
        !stripped.contains('unsubscribe')
        stripped.contains('a: 1')
        stripped.contains('b: 2')
    }

    private static String stripHashComments(String text) {
        text.readLines().collect { line ->
            boolean inSingle = false, inDouble = false
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i)
                if (c == ('\'' as char) && !inDouble) inSingle = !inSingle
                else if (c == ('"' as char) && !inSingle) inDouble = !inDouble
                else if (c == ('#' as char) && !inSingle && !inDouble) return line.substring(0, i)
            }
            return line
        }.join('\n')
    }
}
