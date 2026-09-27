package com.sboxmarket

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource
import spock.lang.Specification
import spock.lang.Unroll

/**
 * <b>The default profile must bind loopback. The wildcard is prod's alone.</b>
 *
 * <h3>What was measured (2026-09-01)</h3>
 *
 * Spring Boot's default for {@code server.address} is "unset", which makes
 * Tomcat bind the wildcard — every interface the machine has. On the operator's
 * box, with the app running under the {@code default} profile, that was not an
 * abstraction:
 *
 * <ul>
 *   <li>{@code http://192.168.68.70:8082/} — the house LAN — answered 200.</li>
 *   <li>{@code http://100.82.162.44:8082/} — the Tailscale tailnet, i.e. every
 *       device the operator owns — same socket.</li>
 *   <li>{@code http://172.29.80.1:8082/} — the WSL/Hyper-V vSwitch.</li>
 *   <li>{@code /api/auth/steam/dev-login} answered <b>302 with a live session</b>
 *       on those addresses, not just on loopback.</li>
 * </ul>
 *
 * There is no inbound firewall rule for 8082, so nothing else was stopping it.
 *
 * <h3>Why the fix is the bind and not another guard</h3>
 *
 * The 302 is <i>correct</i>. {@code dev-login} is gated on
 * {@link com.sboxmarket.config.MoneyMode#handlesRealMoney()}; this deployment
 * carries the committed {@code sk_test_replace_me} placeholder, so it
 * classifies {@link com.sboxmarket.config.MoneyMode#SIMULATED} and the
 * scaffolding is deliberately enabled — for a DEVELOPER LAPTOP. The entire
 * SIMULATED contract assumes nobody but the developer can reach the port.
 *
 * The wildcard bind broke that premise silently. So the scaffolding is not the
 * defect; its REACHABILITY is, and the repair belongs at the socket. Adding a
 * seventeenth guard would have left the premise broken for the next affordance
 * written against it.
 *
 * <h3>CORRECTION, same day: the bind was the only layer, and it has a bypass</h3>
 *
 * The paragraph above is right about where the reachability repair belongs and
 * wrong to have stopped there. A Cloudflare tunnel connects <b>from</b>
 * loopback, and {@code ~/.cloudflared/config.yml} already maps
 * {@code skinbox.market -> http://localhost:8082} with DNS pointing at it — so
 * this bind, correct as it is, does not stand between that door and the public
 * internet. "The scaffolding is not the defect" was a claim about a premise
 * that had exactly one layer holding it up.
 *
 * {@code dev-login} is therefore now closed by default as well, on an
 * affirmative opt-in rather than on the absence of a live Stripe key — see
 * {@link com.sboxmarket.config.DevLoginGate} and {@code DevLoginRequiresOptInSpec}.
 * That is not a seventeenth guard replacing this one: this spec still owns
 * reachability, and it still matters for every other route the app serves.
 *
 * <h3>Why the two profiles differ</h3>
 *
 * A container MUST bind the wildcard or Docker's published
 * {@code ${APP_PORT:-8082}:8082} has nothing to forward to — the same class of
 * failure as the 8080/8082 mismatch recorded in the Dockerfile HEALTHCHECK
 * comment. So {@code prod} — a profile whose whole job is to be deployed behind
 * nginx, and on which every piece of dev scaffolding is already dead — opts in
 * explicitly. The default profile makes no such declaration, so it gets the
 * closed answer. That is the same asymmetry {@code MoneyMode} uses: when the
 * deployment has not said what it is, choose the safe branch.
 *
 * <h3>Note on what this spec can and cannot prove</h3>
 *
 * This asserts the SHIPPED CONFIGURATION. It cannot prove the running process
 * honours it — a JVM holds the config it started with. That half is proved
 * against the live socket at restart time (see deploy/RUNBOOK.md), by observing
 * a connection to a non-loopback address be REFUSED while loopback answers 200.
 * Both halves are needed; neither substitutes for the other.
 */
class LoopbackBindDefaultSpec extends Specification {

    /** Resolve a YAML file to flat dotted properties, the way Spring binds it. */
    private static Properties load(String path) {
        def f = new YamlPropertiesFactoryBean()
        f.setResources(new ClassPathResource(path))
        f.afterPropertiesSet()
        f.getObject()
    }

    /** Strip a `${VAR:default}` placeholder down to its default so we assert
     *  the SHIPPED value rather than the placeholder text. */
    private static String defaultOf(String raw) {
        if (raw == null) return null
        def m = (raw =~ /^\$\{[^:}]+:([^}]*)\}$/)
        m.matches() ? m.group(1) : raw
    }

    def "the base profile pins server.address, and pins it to loopback"() {
        given: 'the key being ABSENT is the whole defect — Spring then binds the wildcard'
        def raw = load('application.yml').getProperty('server.address')

        expect: 'present at all'
        raw != null

        and: 'and resolving to a loopback literal — not 0.0.0.0, not a LAN address'
        def addr = defaultOf(raw as String)
        InetAddress.getByName(addr).isLoopbackAddress()
    }

    @Unroll
    def "the base profile's bind address is not #bad"() {
        given: '''enumerated rather than left to the loopback check alone: these are the
                  values a well-meaning edit would reach for, and each one republishes the
                  dev-login door to a real network.'''
        def addr = defaultOf(load('application.yml').getProperty('server.address') as String)

        expect:
        addr != bad

        where:
        bad << ['0.0.0.0', '::', '*', '', '192.168.68.70', '100.82.162.44']
    }

    def "the base profile leaves an env override, so a deliberate LAN demo is still possible"() {
        given: '''a hard-coded literal would be a worse fix than the bug: the operator would
                  edit the file to demo on the LAN and the edit would outlive the demo.
                  A ${SERVER_ADDRESS:...} placeholder makes the exposure a per-run decision.'''
        def raw = load('application.yml').getProperty('server.address') as String

        expect:
        raw ==~ /^\$\{SERVER_ADDRESS:[^}]*\}$/
    }

    def "prod overrides to the wildcard, because a container must"() {
        given: '''the asymmetry is deliberate and needs pinning in BOTH directions — a later
                  edit that "consistently" applied loopback everywhere would make every
                  containerised deploy unreachable, and the Dockerfile HEALTHCHECK would be
                  the only thing that noticed.'''
        def raw = load('application-prod.yml').getProperty('server.address')

        expect:
        raw != null
        def addr = defaultOf(raw as String)
        !InetAddress.getByName(addr).isLoopbackAddress()
        addr == '0.0.0.0'
    }

    def "the two profiles genuinely disagree — this spec is not asserting one value twice"() {
        given: '''guards the spec itself: if a refactor pointed both reads at the same file,
                  every assertion above would still pass while proving nothing.'''
        def base = defaultOf(load('application.yml').getProperty('server.address') as String)
        def prod = defaultOf(load('application-prod.yml').getProperty('server.address') as String)

        expect:
        base != prod
        InetAddress.getByName(base).isLoopbackAddress()
        !InetAddress.getByName(prod).isLoopbackAddress()
    }
}
