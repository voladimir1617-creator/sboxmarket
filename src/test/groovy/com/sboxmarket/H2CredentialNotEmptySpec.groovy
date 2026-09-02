package com.sboxmarket

import com.sboxmarket.config.H2CredentialGuard
import org.springframework.mock.env.MockEnvironment
import spock.lang.Specification
import spock.lang.Unroll

/**
 * <b>A listening H2 with a blank password is refused at startup.</b>
 *
 * <h3>Why the bind was not enough on its own</h3>
 *
 * {@code h2.bindAddress=127.0.0.1} confines the auto-server to loopback, and
 * that is verified in the running process. But it was the ONLY control: the jar
 * still shipped {@code AUTO_SERVER=TRUE} with {@code sa} and an empty password,
 * so one config slip — a {@code SERVER_ADDRESS} change, an {@code h2.bindAddress}
 * override — re-exposed an unauthenticated database holding real wallet rows.
 *
 * <h3>What the server key does and does not do (measured 2026-09-02)</h3>
 *
 * H2's {@code TcpServer.checkKeyAndGetDatabaseName} refuses any connection whose
 * database name is not the key the auto-server registered. Five path-shaped
 * guesses were each refused with {@code 28000}. But the key is the {@code id}
 * property of {@code data/sboxmarket.lock.db} — a cleartext file beside the
 * database — and connecting with that value as the database name authenticated
 * as {@code SA} <b>with an empty password</b> and read every {@code STEAM_USERS}
 * row. A rendezvous token stored next to the data is not a credential.
 *
 * <h3>The rule</h3>
 *
 * Not "always set a password", which would break the {@code mem:} suite and
 * every Postgres deployment. The refused thing is the COMBINATION of an H2 that
 * listens and a credential that is blank. Both exits are safe — a real password,
 * or no auto-server — so there is no fail-open branch.
 *
 * <p>Verified RED by weakening {@link H2CredentialGuard#violation} to
 * {@code return null}: the refusal cases below then fail by name.</p>
 */
class H2CredentialNotEmptySpec extends Specification {

    /** The URL this app actually deploys with, from application.yml. */
    static final String DEPLOYED_URL =
        'jdbc:h2:file:./data/sboxmarket;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE;MODE=PostgreSQL'

    /** The URL the entire test suite runs on. */
    static final String TEST_URL =
        'jdbc:h2:mem:sboxmarket-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE'

    static final String REAL_PASSWORD = 'a-real-random-password-0123456789'

    private static String readOrFail(String path) {
        def f = new File(path)
        assert f.exists(), "expected ${path} to exist — cannot verify the credential without it"
        f.getText('UTF-8')
    }

    // ── the refusal ─────────────────────────────────────────────────

    def "the deployed AUTO_SERVER url with an empty password is REFUSED"() {
        expect: 'exactly the configuration that shipped, and it does not boot'
        H2CredentialGuard.violation(DEPLOYED_URL, '') != null
    }

    def "the refusal names both ways out, so the operator is not left guessing"() {
        given:
        String msg = H2CredentialGuard.violation(DEPLOYED_URL, '')

        expect: 'the password route'
        msg.contains('SPRING_DATASOURCE_PASSWORD')

        and: 'and the remove-the-listener route'
        msg.contains('AUTO_SERVER=TRUE')

        and: 'and it points at the backup consequence rather than hiding it'
        msg.contains('BACKUP TO')
    }

    def "postProcessEnvironment actually aborts startup — the wiring, not just the rule"() {
        given: 'an environment resolved exactly as the default profile resolves it'
        def env = new MockEnvironment()
        env.setProperty(H2CredentialGuard.PROP_URL, DEPLOYED_URL)
        env.setProperty(H2CredentialGuard.PROP_PASSWORD, '')

        when:
        new H2CredentialGuard().postProcessEnvironment(env, null)

        then: 'the application refuses to start rather than falling back to empty'
        def e = thrown(IllegalStateException)
        e.message.contains('REFUSING TO START')
    }

    def "a real password on the deployed url boots"() {
        given:
        def env = new MockEnvironment()
        env.setProperty(H2CredentialGuard.PROP_URL, DEPLOYED_URL)
        env.setProperty(H2CredentialGuard.PROP_PASSWORD, REAL_PASSWORD)

        when:
        new H2CredentialGuard().postProcessEnvironment(env, null)

        then:
        noExceptionThrown()
        H2CredentialGuard.violation(DEPLOYED_URL, REAL_PASSWORD) == null
    }

    @Unroll
    def "a password that is #label does not count as a credential"() {
        expect:
        H2CredentialGuard.violation(DEPLOYED_URL, supplied) != null

        where:
        label                      | supplied
        'null'                     | null
        'empty'                    | ''
        'whitespace only'          | '        '
        'the username'             | 'sa'
        'a short placeholder'      | 'changeme'
        'one char under the floor' | ('x' * (H2CredentialGuard.MIN_PASSWORD_CHARS - 1))
    }

    def "a password exactly at the floor is accepted — the boundary is not off by one"() {
        expect:
        H2CredentialGuard.violation(DEPLOYED_URL, 'y' * H2CredentialGuard.MIN_PASSWORD_CHARS) == null
    }

    // ── the second exit: no listener, no requirement ────────────────

    def "removing AUTO_SERVER removes the requirement, because it removes the listener"() {
        given: 'the same file database, served to nobody'
        String noServer = 'jdbc:h2:file:./data/sboxmarket;DB_CLOSE_DELAY=-1;MODE=PostgreSQL'

        expect:
        !H2CredentialGuard.opensNetworkListener(noServer)
        H2CredentialGuard.violation(noServer, '') == null
    }

    // ── scope: what must NOT be broken ──────────────────────────────

    def "the in-memory test datasource is untouched — the whole suite runs on it"() {
        expect: 'a mem: database never opens a TCP server, flag or no flag'
        !H2CredentialGuard.opensNetworkListener(TEST_URL)
        H2CredentialGuard.violation(TEST_URL, '') == null

        and: 'not even when someone writes AUTO_SERVER on a mem: url'
        !H2CredentialGuard.opensNetworkListener('jdbc:h2:mem:x;AUTO_SERVER=TRUE')
    }

    def "the test profile config really is the mem: url this spec claims it is"() {
        given: 'read from the file, so the exemption above cannot drift away from reality'
        String testYml = readOrFail('src/test/resources/application-test.yml')

        expect:
        testYml.contains('jdbc:h2:mem:sboxmarket-test')
    }

    @Unroll
    def "a non-H2 datasource (#label) is none of this guard's business"() {
        expect:
        !H2CredentialGuard.opensNetworkListener(url)
        H2CredentialGuard.violation(url, '') == null

        where:
        label      | url
        'postgres' | 'jdbc:postgresql://db.host:5432/skinbox'
        'mysql'    | 'jdbc:mysql://db.host:3306/skinbox'
        'null'     | null
    }

    @Unroll
    def "a served H2 url (#label) needs a password even without AUTO_SERVER"() {
        expect: 'the password crosses a socket, which is the fact that matters'
        H2CredentialGuard.opensNetworkListener(url)
        H2CredentialGuard.violation(url, '') != null

        where:
        label | url
        'tcp' | 'jdbc:h2:tcp://127.0.0.1:9092/./data/sboxmarket'
        'ssl' | 'jdbc:h2:ssl://127.0.0.1:9092/./data/sboxmarket'
    }

    def "the AUTO_SERVER test is case- and whitespace-insensitive"() {
        expect: 'h2 does not care about case here, so neither may the guard'
        H2CredentialGuard.opensNetworkListener('jdbc:h2:file:./data/x;auto_server=true')
        H2CredentialGuard.opensNetworkListener('jdbc:h2:file:./data/x;AUTO_SERVER = TRUE')
    }

    // ── the shipped config ──────────────────────────────────────────

    def "application.yml ships NO password literal — only an empty-defaulting placeholder"() {
        given: 'comment-stripped, so prose naming the key cannot satisfy or break this'
        String base = stripHashComments(readOrFail('src/main/resources/application.yml'))

        expect: 'the key is still present — an absent key would break prod resolution'
        base.contains('password: ${SPRING_DATASOURCE_PASSWORD:}')

        and: '''and there is no committed fallback value after the colon. `}` is
                itself non-whitespace, so the character class must exclude it or
                this assertion fails on the CORRECT config.'''
        !(base =~ /SPRING_DATASOURCE_PASSWORD:\s*[^}\s]/).find()
    }

    def "application-prod.yml keeps the env var MANDATORY — no default at all"() {
        given:
        String prod = stripHashComments(readOrFail('src/main/resources/application-prod.yml'))

        expect: 'a bare ${VAR}, so prod cannot resolve it silently'
        prod.contains('${SPRING_DATASOURCE_PASSWORD}')
    }

    def "the guard is REGISTERED, not merely written — a class nothing loads is decoration"() {
        given: '''this repo has shipped a correct fix that nothing called. The
                  EnvironmentPostProcessor only runs because this file names it.'''
        String factories = stripHashComments(
            readOrFail('src/main/resources/META-INF/spring.factories'))

        expect: '''comment-stripped: a registration that has been COMMENTED OUT is
                   not a registration, and a raw contains() would have passed on it.'''
        factories.contains('org.springframework.boot.env.EnvironmentPostProcessor')
        factories.contains('com.sboxmarket.config.H2CredentialGuard')
    }

    /**
     * The one above reads a file by relative path and proves the REPOSITORY
     * contains some text. This one proves the thing that actually matters: that
     * the registration is on the CLASSPATH at the exact resource location
     * {@code SpringFactoriesLoader} reads, and that it PARSES — via
     * {@code java.util.Properties}, which is the same parser Spring uses. A file
     * that is malformed, commented out, or not copied into the artefact fails
     * here and passes there.
     */
    def "Spring's own resource location and parser resolve the guard"() {
        given:
        def url = getClass().classLoader.getResource('META-INF/spring.factories')

        expect: 'present where SpringFactoriesLoader looks, not merely in src/'
        url != null

        when:
        def props = new Properties()
        url.withInputStream { props.load(it) }
        def registered = props.getProperty('org.springframework.boot.env.EnvironmentPostProcessor')
                              ?.split(',')*.trim() ?: []

        then: 'the key parses and names this guard'
        registered.contains('com.sboxmarket.config.H2CredentialGuard')
    }

    // ── the honest limitation ───────────────────────────────────────

    def "the guard does NOT change any existing database's password"() {
        given: '''Measured: an H2 file database stores its own users. Setting
                  SPRING_DATASOURCE_PASSWORD on a database whose SA password is
                  still empty makes the app fail to CONNECT (28000), it does not
                  migrate anything. Anyone reading this fix as "the database now
                  has a password" is reading it wrong — the ALTER USER in
                  deploy/RUNBOOK.md is a separate, operator-run step.'''
        expect: 'the guard is a pure function of configuration, and touches no database'
        H2CredentialGuard.violation(DEPLOYED_URL, REAL_PASSWORD) == null
    }

    def "SELF-TEST: the comment stripper strips, so the assertions above are not vacuous"() {
        given:
        String sample = 'a: 1   # password: ${SPRING_DATASOURCE_PASSWORD:hunter2}\nb: 2'

        when:
        String stripped = stripHashComments(sample)

        then:
        !stripped.contains('SPRING_DATASOURCE_PASSWORD')
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
