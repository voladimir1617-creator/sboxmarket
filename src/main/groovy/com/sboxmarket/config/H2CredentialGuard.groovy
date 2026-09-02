package com.sboxmarket.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment

/**
 * <b>An H2 that listens on a socket may not have a blank password.</b>
 *
 * <h3>What was measured (2026-09-02)</h3>
 *
 * The dev datasource URL carries {@code AUTO_SERVER=TRUE}, which starts an H2
 * TCP server the moment the file database is opened, and the shipped credential
 * is {@code sa} with an EMPTY password. Both halves were verified against the
 * running process:
 *
 * <ul>
 *   <li>The server key that {@code TcpServer.checkKeyAndGetDatabaseName}
 *       demands is <b>the {@code id} property inside
 *       {@code data/sboxmarket.lock.db}</b> — proven by reading that value and
 *       connecting with it as the database name:
 *       {@code jdbc:h2:tcp://127.0.0.1:<port>/<id>} authenticated as {@code SA}
 *       <b>with an empty password</b> and read every row of {@code STEAM_USERS}.</li>
 *   <li>Five plausible path-shaped guesses for that key
 *       ({@code ./data/sboxmarket}, {@code data/sboxmarket}, the absolute path,
 *       {@code sboxmarket}, {@code test}) were each refused with
 *       {@code 28000 Wrong user name or password}.</li>
 * </ul>
 *
 * So the key check is real but narrow: it stops a blind peer who has only the
 * port. It stops nobody who can read one world-readable file sitting in the
 * same directory as the database it guards. It is a rendezvous token, not a
 * credential, and this database was already read once through exactly that gap.
 *
 * <h3>Why this is not a third guard over a fact another control owns</h3>
 *
 * {@link com.sboxmarket.SboxMarketApplication#hardenEmbeddedH2Bind()} owns
 * <i>from where</i> a client may connect. This owns <i>who</i> the client must
 * be. They are orthogonal facts, and — the test this repo learned to apply
 * after a committed Steam ID and the validator that would have caught it were
 * switched by one lost {@code SPRING_PROFILES_ACTIVE} — they cannot fail
 * together: the bind is a system property set in {@code main()}, the password
 * arrives in {@code SPRING_DATASOURCE_PASSWORD}. No single missing variable
 * disables both.
 *
 * <h3>Why a password rather than deleting AUTO_SERVER</h3>
 *
 * Deleting {@code AUTO_SERVER=TRUE} removes today's listener but leaves the
 * empty password exactly where it is, so the credential stays a formality and
 * the hazard returns with the next URL that serves. It also breaks the only
 * working way to copy this database while the app holds it: measured, an
 * embedded open of the live file without the auto-server fallback is refused
 * with {@code 90020 Database may be already in use … use the server mode}, and
 * the {@code BACKUP TO} that produced a verified 112 KB archive ran <i>through</i>
 * that server. See {@code deploy/RUNBOOK.md}.
 *
 * <p>So the rule here is not "always set a password". It is that the
 * <b>combination</b> of a listening H2 and a blank credential is refused. Both
 * ways out are safe: give the auto-server a real password, or do not run an
 * auto-server. There is no fail-open branch.</p>
 *
 * <h3>Why an EnvironmentPostProcessor</h3>
 *
 * It has to run before Hikari opens the first connection, and it has to be
 * impossible to drop. A {@code -D} flag on a launch line can be lost — that is
 * precisely why the bind is set programmatically — so this is registered in
 * {@code META-INF/spring.factories} and runs on every {@code SpringApplication},
 * including tests, not only via {@code main()}. Ordered
 * {@link Ordered#LOWEST_PRECEDENCE} so Spring's own
 * {@code ConfigDataEnvironmentPostProcessor} has already merged
 * {@code application.yml} and the active profile's overrides — this reads the
 * <i>resolved</i> configuration, which is the only version that matters when
 * shipped YAML can override a Java field initialiser.
 *
 * <h3>Scope</h3>
 *
 * Inert for Postgres (not an H2 URL), and inert for the {@code mem:} databases
 * the whole test suite uses — all 26 {@code @SpringBootTest} specs carry
 * {@code @ActiveProfiles}, and an in-memory H2 never opens a TCP server.
 */
class H2CredentialGuard implements EnvironmentPostProcessor, Ordered {

    static final String PROP_URL      = 'spring.datasource.url'
    static final String PROP_PASSWORD = 'spring.datasource.password'

    /**
     * Minimum characters in a password guarding a listening database.
     *
     * A non-empty check alone would accept {@code sa}, {@code h2} or
     * {@code changeme} and call the credential "real". The floor exists to
     * catch a placeholder or a truncated paste by SHAPE rather than by
     * maintaining a blocklist of bad passwords — the same reasoning, and the
     * same kind of threshold, as {@link ProdConfigValidator#STRIPE_MIN_BODY_CHARS}.
     */
    static final int MIN_PASSWORD_CHARS = 16

    @Override
    int getOrder() { Ordered.LOWEST_PRECEDENCE }

    /**
     * Does this JDBC URL cause an H2 TCP server to exist, or talk to one?
     *
     * Three cases matter:
     * <ul>
     *   <li>{@code jdbc:h2:tcp:} / {@code jdbc:h2:ssl:} — a served H2; the
     *       password crosses a socket.</li>
     *   <li>a file-mode H2 with {@code AUTO_SERVER=TRUE} — the embedded engine
     *       starts a listener of its own. This is the deployed case.</li>
     *   <li>{@code mem:} — never serves, even with the flag, so it is excluded
     *       rather than left to the flag test.</li>
     * </ul>
     */
    static boolean opensNetworkListener(String url) {
        if (url == null) return false
        String u = url.trim().toLowerCase()
        if (!u.startsWith('jdbc:h2:')) return false
        if (u.startsWith('jdbc:h2:tcp:') || u.startsWith('jdbc:h2:ssl:')) return true
        if (u.contains('mem:')) return false
        return (u =~ /auto_server\s*=\s*true/).find()
    }

    /** True when the supplied password is absent, blank, or too short to be real. */
    static boolean isWeak(String password) {
        password == null || password.trim().length() < MIN_PASSWORD_CHARS
    }

    /**
     * The whole decision as a pure function, so it is unit-testable with no
     * Spring context — the shape {@link LiveMoneyGuard#isRealMoney} and
     * {@link MoneyResetGate#optInGranted} already use here.
     *
     * @return null when the configuration is safe, otherwise the refusal text.
     */
    static String violation(String url, String password) {
        if (!opensNetworkListener(url)) return null
        if (!isWeak(password)) return null
        return 'REFUSING TO START: this datasource opens an H2 network listener ' +
               "(${url}) but spring.datasource.password is blank or shorter than " +
               "${MIN_PASSWORD_CHARS} characters. An H2 auto-server authenticates callers with the " +
               'database password; the only other thing in front of it is the server key, ' +
               'which is stored in cleartext in data/sboxmarket.lock.db next to the database ' +
               'it guards — this database was already read once through that gap. ' +
               'Choose ONE: (a) set SPRING_DATASOURCE_PASSWORD to a real secret AND migrate the ' +
               "existing database to match (ALTER USER SA SET PASSWORD '<secret>'), or " +
               '(b) remove AUTO_SERVER=TRUE from SPRING_DATASOURCE_URL, which removes the ' +
               'listener entirely — but also removes the only way to BACKUP TO a copy while ' +
               'the app is running. See deploy/RUNBOOK.md, "H2 backup and the auto-server".'
    }

    @Override
    void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String message = violation(
            environment.getProperty(PROP_URL),
            environment.getProperty(PROP_PASSWORD))
        if (message != null) throw new IllegalStateException(message)
    }
}
