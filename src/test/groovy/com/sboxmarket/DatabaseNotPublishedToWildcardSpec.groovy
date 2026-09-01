package com.sboxmarket

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.FileSystemResource
import spock.lang.Specification

/**
 * <b>The wallet database must not be published to the wildcard, and its
 * password must not have a default.</b>
 *
 * <h3>The exposure this closes</h3>
 *
 * {@code docker-compose.yml} shipped the Postgres service as:
 *
 * <pre>
 *   db:
 *     environment:
 *       POSTGRES_USER: skinbox
 *       POSTGRES_PASSWORD: ${DB_PASSWORD:-skinbox}
 *       POSTGRES_DB: skinbox
 *     ports:
 *       - "5433:5432"
 * </pre>
 *
 * Two independent defects, and either alone would be enough:
 *
 * <ol>
 *   <li><b>The port was published to every interface.</b> Docker's short
 *       {@code "5433:5432"} syntax with no host IP binds the WILDCARD. And the
 *       host firewall is not a backstop — on Linux, Docker inserts DNAT rules
 *       into the {@code DOCKER} chain, consulted BEFORE the host's {@code INPUT}
 *       rules, so a published port answers even when ufw/firewalld is set to
 *       refuse it.</li>
 *   <li><b>The password was a committed literal.</b> User {@code skinbox},
 *       database {@code skinbox}, password {@code skinbox} — all three in a
 *       tracked file.</li>
 * </ol>
 *
 * Together: {@code psql -h <host> -p 5433 -U skinbox -d skinbox}, password
 * {@code skinbox}, from anywhere that can route to the host — full read/write
 * on wallets, listings, sessions and the Stripe Connect columns.
 *
 * <h3>Why the default was the guaranteed outcome, not an edge case</h3>
 *
 * {@code DB_PASSWORD} appeared NOWHERE else in the repository — not in
 * {@code deploy/skinbox.env.example}, not in {@code deploy/RUNBOOK.md}, not in
 * the launch checklist. The RUNBOOK's own deploy line is
 * {@code docker compose --env-file deploy/skinbox.env up -d}, and that env file
 * had no {@code DB_PASSWORD} to supply. So the operator was never asked for a
 * value and the committed default is what every documented deploy would have
 * used. (The example file now carries {@code DB_PASSWORD=CHANGE_ME}; a
 * fail-closed guard with no documented way to satisfy it is just a trap.)
 *
 * <h3>The asymmetry that hid it — the same one as the H2 hole</h3>
 *
 * This file already knew the fail-closed form. {@code STRIPE_SECRET_KEY} uses
 * {@code ${VAR:?message}} and refuses to start without a value, with a careful
 * comment explaining why a default was dangerous. Two lines above it, the
 * database that holds every wallet balance took the fail-OPEN form. The
 * dangerous thing sat next to the correctly-locked thing, which is exactly how
 * the H2 {@code AUTO_SERVER} port hid behind a correctly-bound HTTP 8082 in
 * {@code 502ab8f}.
 *
 * <h3>Why loopback rather than removing the mapping</h3>
 *
 * The app never needed the published port: it reaches Postgres over the compose
 * network at {@code jdbc:postgresql://db:5432/skinbox} — service name, container
 * port. The mapping exists only so a human can attach a SQL client to the host.
 * {@code 127.0.0.1:5433:5432} keeps that and deletes the off-box reach. Same
 * shape as the H2 fix: keep the capability, remove the reach.
 *
 * <h3>What this spec can and cannot prove</h3>
 *
 * It asserts the SHIPPED DEPLOYMENT DESCRIPTOR. Nothing on the audited machine
 * runs this compose stack — the app runs from a jar under the default profile
 * with H2 — so unlike the H2 finding there is no running socket to pair a
 * refusal against. This is a latent defect on the documented deploy path, and
 * the file is the only place it can be pinned.
 *
 * <p>Verified RED by re-introducing each half independently: restoring
 * {@code "5433:5432"} fails the bind cases, and restoring
 * {@code ${DB_PASSWORD:-skinbox}} fails the password cases.</p>
 */
class DatabaseNotPublishedToWildcardSpec extends Specification {

    private static final File COMPOSE = new File('docker-compose.yml')

    /** Flatten the compose YAML the way Spring binds it: services.db.ports[0]. */
    private static Properties compose() {
        def f = new YamlPropertiesFactoryBean()
        f.setResources(new FileSystemResource(COMPOSE))
        f.afterPropertiesSet()
        f.getObject()
    }

    /**
     * The compose file with FULL-LINE {@code #} comments removed.
     *
     * <p>This matters, and the first draft of this spec got it wrong in a way
     * worth recording. The comments added alongside the fix quote the old
     * {@code ${DB_PASSWORD:-skinbox}} form in order to explain it, so a regex
     * run over the raw text matched the PROSE and failed against the FIXED
     * file. The mirror-image failure is the dangerous one: a fix written only
     * inside a comment would have passed a raw-text assertion.</p>
     *
     * <p>So every textual assertion below runs over configuration only. Only
     * whole-line comments are stripped — no value in this file carries a
     * trailing {@code #}, and guessing at inline comments would risk truncating
     * a real value.</p>
     */
    private static String activeConfig() {
        COMPOSE.readLines('UTF-8')
               .findAll { !it.trim().startsWith('#') }
               .join('\n')
    }

    /** Every host-side port mapping declared by any service, as plain strings. */
    private static List<String> publishedPorts() {
        Properties props = compose()
        List<String> out = new ArrayList<String>()
        for (String key : props.stringPropertyNames()) {
            if (key ==~ /services\..+\.ports\[\d+\]/) {
                out.add(String.valueOf(props.getProperty(key)))
            }
        }
        return out
    }

    /** Host-side port mappings for one named service. */
    private static List<String> portsOf(String service) {
        Properties props = compose()
        List<String> out = new ArrayList<String>()
        for (String key : props.stringPropertyNames()) {
            if (key ==~ /services\.\Q${service}\E\.ports\[\d+\]/) {
                out.add(String.valueOf(props.getProperty(key)))
            }
        }
        return out
    }

    def "the compose file exists and actually parsed — guards against a vacuous pass"() {
        expect: 'the file is present'
        COMPOSE.exists()

        and: 'it really parsed into services, so an empty result below means "clean" not "unread"'
        !compose().stringPropertyNames().findAll { it.startsWith('services.') }.isEmpty()

        and: 'the db service is still the postgres one this spec is about'
        compose().getProperty('services.db.image')?.startsWith('postgres')

        and: 'and it publishes at least one port, so the port assertions have something to bite on'
        !publishedPorts().isEmpty()
    }

    def "the Postgres port is published to loopback only, never the wildcard"() {
        given:
        List<String> dbPorts = portsOf('db')

        expect: 'the db service publishes something (otherwise this proves nothing)'
        !dbPorts.isEmpty()

        and: 'every db mapping is host-scoped to loopback'
        dbPorts.every { String m -> m.startsWith('127.0.0.1:') || m.startsWith('localhost:') }

        and: 'specifically NOT the bare "5433:5432" that binds every interface'
        !dbPorts.contains('5433:5432')
    }

    def "no service publishes a database port to the wildcard"() {
        given: 'host-side port of each mapping — the segment before the container port'
        def dbPortNumbers = ['5432', '5433', '3306', '27017', '6379', '1433']

        expect:
        publishedPorts().every { String mapping ->
            def parts = mapping.split(':')
            // "5433:5432" -> host side is parts[0]; "127.0.0.1:5433:5432" -> parts[0] is the IP.
            boolean loopbackScoped = mapping.startsWith('127.0.0.1:') || mapping.startsWith('localhost:')
            String containerPort = parts[-1]
            // A database container port published without a loopback host IP is the defect.
            loopbackScoped || !dbPortNumbers.contains(containerPort)
        }
    }

    def "DB_PASSWORD has no fail-open default anywhere in the compose file"() {
        given: 'configuration only — comments quote the old form to explain it'
        String config = activeConfig()

        expect: 'the variable is actually referenced, so this cannot pass by absence'
        config.contains('DB_PASSWORD')

        and: 'never the :- form, which substitutes a committed literal when unset'
        !(config =~ /\$\{DB_PASSWORD:-/)

        and: 'and the old committed password is gone as a default value'
        !config.contains('DB_PASSWORD:-skinbox')
    }

    def "both DB_PASSWORD sites are fail-closed, so Postgres and the app cannot drift apart"() {
        given:
        String config = activeConfig()

        when: 'count every reference and every fail-closed reference'
        int references = (config =~ /\$\{DB_PASSWORD[:}]/).count
        int failClosed = (config =~ /\$\{DB_PASSWORD:\?/).count

        then: 'both the db service and the app service reference it'
        references >= 2

        and: 'and every single reference is the fail-closed form'
        failClosed == references
    }

    def "the comment-stripping itself works — otherwise the two assertions above are vacuous"() {
        given:
        String raw = COMPOSE.getText('UTF-8')
        String config = activeConfig()

        expect: 'the file really does carry full-line comments'
        raw.readLines().any { it.trim().startsWith('#') }

        and: 'stripping them actually removed something'
        config.length() < raw.length()

        and: 'the prose mention of the OLD form survives in the raw text but not in the config'
        raw.contains('${DB_PASSWORD:-skinbox}')
        !config.contains('${DB_PASSWORD:-skinbox}')

        and: 'while the ACTIVE fail-closed references survive the strip'
        (config =~ /\$\{DB_PASSWORD:\?/).count >= 2
    }

    def "the operator is told how to satisfy the requirement — a guard with no documented answer is a trap"() {
        given:
        def example = new File('deploy/skinbox.env.example')

        expect:
        example.exists()

        and: 'the example env file names the variable compose actually interpolates'
        example.getText('UTF-8').contains('DB_PASSWORD=')
    }
}
