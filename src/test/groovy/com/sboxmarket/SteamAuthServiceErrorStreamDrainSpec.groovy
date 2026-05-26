package com.sboxmarket

import com.sboxmarket.service.SteamAuthService
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asserts that fetchViaWebApi and fetchViaPublicXml drain the error
 * stream on the 4xx permanent-failure path so the underlying socket can
 * return to the JVM keep-alive pool instead of being closed (or worse,
 * sitting half-read until GC reclaims the wrapper).
 *
 * Steam returns 403 for private profiles — common on every login of a
 * user with a non-public profile — so a never-drained error stream
 * slow-leaks file descriptors on a long-lived container under sustained
 * login pressure. The 5xx path was already draining; the 4xx path was
 * not. This spec proves both paths now drain by:
 *
 *  1. Spinning up an in-process HttpServer that returns 4xx with a
 *     non-empty body and counts how many TCP accept()s it sees.
 *  2. Hitting the endpoint N times via the production methods (URL
 *     overridden via the protected hooks added for testability).
 *  3. Asserting:
 *     a) every call returns null cleanly (no exception escapes),
 *     b) the keep-alive socket gets reused — accept count is strictly
 *        less than the call count, which is only possible if the error
 *        stream was fully drained.
 *
 * Without the drain, the JDK's HttpURLConnection cannot return the
 * socket to the pool, so each call opens a fresh TCP connection and
 * `accepts == calls`. With the drain, the second+ call reuses the
 * pooled socket and `accepts < calls`.
 */
class SteamAuthServiceErrorStreamDrainSpec extends Specification {

    HttpServer server
    AtomicInteger accepts = new AtomicInteger(0)
    int port

    def setup() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        port = server.address.port
        // The /web path stands in for Steam Web API; /xml for the public
        // profile XML endpoint. Both return 403 with a body large enough
        // that the drain is observable (a zero-length body would make
        // the pool-return question moot).
        server.createContext('/web', new ForbiddenHandler(accepts: accepts))
        server.createContext('/xml', new ForbiddenHandler(accepts: accepts))
        server.executor = null
        server.start()
    }

    def cleanup() {
        // Stop with a small delay so in-flight exchanges can flush, then
        // null the field so other specs don't see a dangling server.
        server?.stop(0)
        server = null
    }

    /**
     * Subclass that redirects both endpoint URLs at the local HttpServer.
     * Production behaviour is untouched — the prod methods still return
     * the real Steam URLs when called.
     */
    private SteamAuthService localService() {
        new SteamAuthService(steamApiKey: 'test-key') {
            @Override
            protected String webApiUrl(String steamId64) {
                "http://127.0.0.1:${port}/web?steamids=${steamId64}".toString()
            }
            @Override
            protected String publicXmlUrl(String steamId64) {
                "http://127.0.0.1:${port}/xml?id=${steamId64}".toString()
            }
        }
    }

    def "fetchViaWebApi drains the 4xx error stream so the keep-alive socket is reused"() {
        given:
        def svc = localService()
        int CALLS = 5

        when: "we fire several requests in a row at an endpoint that returns 403"
        def results = (1..CALLS).collect { svc.fetchViaWebApi('7656119796028793' + it) }

        then: "every call returns null cleanly — no exception escapes"
        results.every { it == null }

        and: "the keep-alive pool reused at least one socket — accepts < calls"
        // If the error stream were not drained, every call would open a
        // fresh TCP connection (accepts == CALLS). The drain makes the
        // socket reusable, so accepts is strictly less than CALLS.
        accepts.get() < CALLS
    }

    def "fetchViaPublicXml drains the 4xx error stream so the keep-alive socket is reused"() {
        given:
        def svc = localService()
        int CALLS = 5

        when:
        def results = (1..CALLS).collect { svc.fetchViaPublicXml('7656119796028793' + it) }

        then:
        results.every { it == null }

        and:
        accepts.get() < CALLS
    }

    /**
     * Tiny 403 handler with a non-trivial body. The body matters: the
     * keep-alive pool can only return a socket if the application has
     * read every byte of the response, so a zero-length body would make
     * this test pass trivially regardless of the fix.
     */
    static class ForbiddenHandler implements HttpHandler {
        AtomicInteger accepts
        // Count the *first* exchange on each underlying TCP connection.
        // The HttpServer reuses the same handler instance across all
        // exchanges so we can't count instances directly — instead we
        // observe the remote port, which is unique per TCP connection
        // (the JDK client picks a fresh ephemeral port for each new
        // socket but reuses the same one across pooled requests).
        private final Set<Integer> seenRemotePorts = Collections.synchronizedSet(new HashSet<Integer>())

        @Override
        void handle(HttpExchange exchange) throws IOException {
            int remotePort = exchange.remoteAddress.port
            if (seenRemotePorts.add(remotePort)) {
                accepts.incrementAndGet()
            }
            byte[] body = ('{"error":"forbidden — private profile or bad key"}' * 8)
                .getBytes(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(403, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
    }
}
