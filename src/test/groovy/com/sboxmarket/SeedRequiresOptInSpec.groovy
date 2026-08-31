package com.sboxmarket

import org.springframework.core.env.Environment
import spock.lang.Specification
import spock.lang.Unroll

/**
 * The demo catalogue must be ASKED for, not merely un-forbidden.
 *
 * <h3>The hole this closes</h3>
 *
 * Two gates already guarded {@code SeedService}: {@code @Profile("!prod")} on
 * the {@code onStartup} runner, and {@link com.sboxmarket.config.LiveMoneyGuard}
 * vetoing on a {@code sk_live_} key. Both answer "is this OBVIOUSLY production?".
 * Neither answers "is this a developer box?", and the difference is the entire
 * risk: the operator's own instance runs the {@code default} profile with
 * {@code sk_test_} keys, so BOTH gates pass.
 *
 * Measured on a fresh boot of the real jar, 2026-08-31, default profile, no
 * flags — the seeder wrote 39 catalogue items, 85 ACTIVE listings, six invented
 * sellers holding 50 of them, 56 auction bids, 171 SOLD rows and 3,510
 * price-history points. Those SOLD rows and that 90-day series are what a real
 * buyer prices against, and the ACTIVE rows are buyable: the invented sellers
 * were deliberately given wallets so the buy path would not refuse them.
 *
 * "Not prod" was the wrong question because the absence of configuration is the
 * default state of every box nobody has configured yet — including the one the
 * operator is about to put a real item on. The gate is now fail-CLOSED: no
 * signal means no fabricated inventory.
 *
 * @see SboxMarketApplication#demoSeedRequested(org.springframework.core.env.Environment)
 */
class SeedRequiresOptInSpec extends Specification {

    private Environment env(List<String> profiles, String demoFlag) {
        Stub(Environment) {
            getActiveProfiles() >> (profiles as String[])
            getProperty('sbox.seed.demo-data') >> demoFlag
        }
    }

    @Unroll
    def "demoSeedRequested is #expected for profiles=#profiles flag=#flag — #why"() {
        expect:
        SboxMarketApplication.demoSeedRequested(env(profiles, flag)) == expected

        where:
        profiles     | flag      || expected | why
        // ── THE REGRESSION. This is the operator's actual running config.
        []           | null      || false    | 'bare default profile fabricates nothing'
        ['default']  | null      || false    | 'an explicit "default" is still not an opt-in'
        // ── Unrelated profiles must not imply consent either.
        ['prod']     | null      || false    | 'belt-and-braces: prod never seeds'
        ['staging']  | null      || false    | 'an unknown profile is not a dev box'
        ['docker']   | null      || false    | 'orchestrator profiles are not dev boxes'
        // ── Deliberately-activated developer profiles do imply it.
        ['dev']      | null      || true     | 'dev is activated on purpose'
        ['test']     | null      || true     | 'preserves the existing CI suite behaviour'
        ['dev','x']  | null      || true     | 'dev anywhere in the list counts'
        // ── An explicit property wins outright, in BOTH directions.
        []           | 'true'    || true     | 'opt in on the default profile'
        []           | 'TRUE'    || true     | 'case-insensitive'
        []           | ' true '  || true     | 'whitespace-tolerant'
        ['dev']      | 'false'   || false    | 'opt OUT even under dev — reproduce the empty book'
        ['test']     | 'false'   || false    | 'opt out even under test'
        []           | 'false'   || false    | 'explicit no'
        []           | 'yes'     || false    | 'only the literal "true" enables it'
        []           | ''        || false    | 'blank is not an opt-in'
    }

    def "a null Environment never seeds"() {
        expect: 'fail closed when we cannot tell where we are'
        !SboxMarketApplication.demoSeedRequested(null)
    }

    def "the gate helper exists and is statically reachable"() {
        given: 'guard the guard — a renamed helper makes every case above vacuous'
        def m = SboxMarketApplication.declaredMethods.find { it.name == 'demoSeedRequested' }

        expect:
        m != null
        java.lang.reflect.Modifier.isStatic(m.modifiers)
    }

    def "the startup runner actually consults the gate"() {
        given: 'a test that only checks the helper would pass while the runner ignored it'
        String src = new File('src/main/groovy/com/sboxmarket/SboxMarketApplication.groovy').text
        int runnerAt = src.indexOf('CommandLineRunner onStartup')

        expect: 'the call is inside the runner, not merely defined somewhere in the file'
        runnerAt >= 0
        src.indexOf('demoSeedRequested(env)', runnerAt) > runnerAt
        src.indexOf('seedService.seed()', runnerAt) > src.indexOf('demoSeedRequested(env)', runnerAt)
    }

    def "a purge runner is wired and is NOT restricted to non-prod"() {
        given: 'a seeded dev database can be promoted to prod; the cleanup must run there too'
        def m = SboxMarketApplication.declaredMethods.find { it.name == 'purgeDemoDataOnStartup' }

        expect:
        m != null
        m.getAnnotation(org.springframework.context.annotation.Profile) == null
    }
}
