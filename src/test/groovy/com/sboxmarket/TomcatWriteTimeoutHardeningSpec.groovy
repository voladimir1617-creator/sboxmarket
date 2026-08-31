package com.sboxmarket

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource
import spock.lang.Specification
import spock.lang.Unroll

/**
 * The base profile must bound how long one client can pin a worker thread.
 *
 * <h3>The wedge this pins (measured 2026-08-31)</h3>
 *
 * A long-lived instance stops answering: TCP still accepts, nothing responds,
 * a 60-second client records HTTP 000. The thread dump is distinctive — the
 * acceptor and poller are healthy, there are no BLOCKED threads and no
 * deadlock, but every worker is parked and most sit at {@code cpu=0.00ms},
 * having been spawned and never reached application code.
 *
 * The cause is not a leak. Tomcat writes a response body with a BLOCKING
 * write and pins the worker for its whole duration:
 * <pre>
 *   org.apache.tomcat.util.net.NioEndpoint$NioSocketWrapper.doWrite
 *     -&gt; java.lang.Object.wait()
 * </pre>
 * A client that stops reading — a backgrounded tab, a dead mobile link, a
 * dropped tunnel hop, an aborted fetch — strands its worker there. This app
 * ships large bodies (a 200-row {@code /api/listings} is ~74KB, plus the admin
 * CSV exports and the sitemap), so a stalled reader holds a thread for a long
 * time. Enough of them and all {@code threads.max} workers are pinned; further
 * connections then sit in the kernel accept queue, completing the TCP handshake
 * while the application never sees them. That is exactly "TCP accepts, never
 * responds".
 *
 * <h3>Why the test reads the BASE file</h3>
 *
 * {@code application-prod.yml} always had these keys. {@code application.yml}
 * did not — and the {@code default} profile is what actually runs (the
 * operator's instance boots with {@code profiles: default}). The hardening was
 * present exactly where it was not needed and absent where the traffic was, so
 * asserting it on the prod file alone would restate the bug rather than catch
 * it. Both are asserted below.
 *
 * <h3>The measurement</h3>
 *
 * Identical load (400 slowloris + 250 non-reading readers), same jar, same
 * data, the ONLY difference being these keys:
 * <ul>
 *   <li>without — 200/200 workers pinned, 50 stuck in {@code doWrite},
 *       peak probe latency <b>53.87s</b></li>
 *   <li>with    —  53 workers,            0 stuck in {@code doWrite},
 *       peak probe latency <b>0.03s</b></li>
 * </ul>
 */
class TomcatWriteTimeoutHardeningSpec extends Specification {

    /** Resolve a YAML file to flat dotted properties, the way Spring binds it. */
    private static Properties load(String path) {
        def f = new YamlPropertiesFactoryBean()
        f.setResources(new ClassPathResource(path))
        f.afterPropertiesSet()
        f.getObject()
    }

    /** Strip a `${VAR:default}` placeholder down to its default so we can
     *  assert the SHIPPED value, not the placeholder text. A bare value
     *  passes through unchanged. */
    private static String defaultOf(String raw) {
        if (raw == null) return null
        def m = (raw =~ /^\$\{[^:}]+:([^}]*)\}$/)
        m.matches() ? m.group(1) : raw
    }

    /** Duration strings here are `20s` / `15s` / `10s` style. */
    private static long seconds(String v) {
        def m = (v =~ /^(\d+)s$/)
        if (m.matches()) return Long.parseLong(m.group(1))
        def ms = (v =~ /^(\d+)ms$/)
        if (ms.matches()) return Long.parseLong(ms.group(1)) / 1000L
        Long.parseLong(v) / 1000L      // bare millis
    }

    @Unroll
    def "#file bounds server.tomcat.#key so a stalled client cannot pin a worker forever"() {
        given:
        def props = load(file)
        def raw = props.getProperty("server.tomcat.${key}" as String)

        expect: 'the key is present at all — its absence is the entire defect'
        raw != null

        and: 'it resolves to a positive, bounded number of seconds'
        def secs = seconds(defaultOf(raw as String))
        secs > 0
        secs <= 60      // anything longer is not a bound in any useful sense

        where:
        file                     | key
        'application.yml'        | 'connection-timeout'
        'application.yml'        | 'keep-alive-timeout'
        'application-prod.yml'   | 'connection-timeout'
        'application-prod.yml'   | 'keep-alive-timeout'
    }

    def "the base profile caps the worker pool explicitly"() {
        given: 'an unbounded-in-practice pool just moves the cliff, it does not remove it'
        def props = load('application.yml')

        expect:
        props.getProperty('server.tomcat.threads.max') != null
        Integer.parseInt(defaultOf(props.getProperty('server.tomcat.threads.max') as String)) > 0
    }

    def "the base profile is not laxer than prod"() {
        given: 'prod was already hardened; the base file must not silently undo it'
        def base = load('application.yml')
        def prod = load('application-prod.yml')

        when:
        long baseTimeout = seconds(defaultOf(base.getProperty('server.tomcat.connection-timeout') as String))
        long prodTimeout = seconds(defaultOf(prod.getProperty('server.tomcat.connection-timeout') as String))

        then: 'base may be more generous for slow local clients, but still bounded'
        baseTimeout > 0
        prodTimeout > 0
        baseTimeout <= 3 * prodTimeout
    }
}
