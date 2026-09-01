package com.sboxmarket

import spock.lang.Specification

/**
 * Pins {@link SboxMarketApplication#hardenEmbeddedH2Bind()} to bind the
 * embedded H2 AUTO_SERVER to loopback.
 *
 * <h3>The exposure this closes</h3>
 *
 * The dev datasource URL carries {@code AUTO_SERVER=TRUE} (application.yml),
 * which starts an H2 TCP server the moment the file DB is opened. H2's default
 * bind is the wildcard, so on a multi-homed box the listener answered on the
 * machine's LAN address and its Tailscale address (measured 2026-09-01:
 * {@code 100.82.162.44:65359} accepted TCP from off-box while HTTP port 8082
 * refused the same peer). The DB password is empty ({@code SA} / none), so the
 * only gate is the random per-session key in {@code data/sboxmarket.lock.db} —
 * and with that key a client read every wallet balance over the port. Binding
 * the server to loopback removes the off-box surface regardless of the key.
 *
 * <p>Verified RED by neutering {@code hardenEmbeddedH2Bind()} to a no-op /
 * {@code return null} — the loopback and idempotency cases then fail.</p>
 *
 * @see SboxMarketApplication#hardenEmbeddedH2Bind()
 */
class H2BindHardeningSpec extends Specification {

    // The helper mutates a PROCESS-WIDE system property. Snapshot and restore it
    // so this spec cannot leak `h2.bindAddress` into the rest of the suite.
    private String saved

    def setup()   { saved = System.getProperty('h2.bindAddress'); System.clearProperty('h2.bindAddress') }
    def cleanup() {
        if (saved == null) System.clearProperty('h2.bindAddress')
        else System.setProperty('h2.bindAddress', saved)
    }

    def "binds the embedded H2 server to loopback when the operator has not pinned it"() {
        given: 'no h2.bindAddress set (the default state that exposed the port)'
        System.clearProperty('h2.bindAddress')

        when:
        String effective = SboxMarketApplication.hardenEmbeddedH2Bind()

        then: 'the embedded server is confined to loopback'
        effective == '127.0.0.1'
        System.getProperty('h2.bindAddress') == '127.0.0.1'
    }

    def "respects an explicit operator override and never widens it"() {
        given: 'the operator pinned the bind address themselves via -Dh2.bindAddress'
        System.setProperty('h2.bindAddress', '10.0.0.5')

        when:
        String effective = SboxMarketApplication.hardenEmbeddedH2Bind()

        then: 'the explicit value is preserved, not overwritten with loopback'
        effective == '10.0.0.5'
        System.getProperty('h2.bindAddress') == '10.0.0.5'
    }

    def "is idempotent — a second call keeps loopback"() {
        given:
        System.clearProperty('h2.bindAddress')

        when:
        SboxMarketApplication.hardenEmbeddedH2Bind()
        String second = SboxMarketApplication.hardenEmbeddedH2Bind()

        then:
        second == '127.0.0.1'
        System.getProperty('h2.bindAddress') == '127.0.0.1'
    }
}
