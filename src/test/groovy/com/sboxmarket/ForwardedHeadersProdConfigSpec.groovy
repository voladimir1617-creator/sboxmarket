package com.sboxmarket

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.filter.ForwardedHeaderFilter
import org.yaml.snakeyaml.Yaml
import spock.lang.Specification

/**
 * Pins the production reverse-proxy posture.
 *
 * The live topology is Cloudflare tunnel -> nginx -> app, and the
 * cloudflared->nginx hop is plain HTTP. nginx stamps
 * `X-Forwarded-Proto: $scheme`, which is `http` on that internal hop even
 * though TLS is mandatory at the Cloudflare edge. Unless Spring is told to
 * trust X-Forwarded-* via `server.forward-headers-strategy`, every servlet
 * request looks like plain HTTP to the app, so:
 *
 *   * HttpServletResponse.sendRedirect() (SteamAuthController login bounce
 *     + /return) builds `Location: http://skinbox.market/...` — a scheme
 *     downgrade behind the HTTPS edge,
 *   * request.isSecure() is false (Secure-cookie / scheme-aware logic
 *     thinks the request arrived insecurely),
 *   * absolute-URL builders emit http:// links.
 *
 * `framework` activates Spring's {@link ForwardedHeaderFilter}, which
 * rewrites the request scheme/host/port from the forwarded headers BEFORE
 * any controller runs. This spec pins (1) that the prod profile actually
 * sets the strategy to `framework`, and (2) that the filter the strategy
 * installs does the scheme/host rewrite + https redirect we rely on — so
 * if someone deletes the property OR a Spring upgrade changes the filter's
 * behaviour, a test goes red instead of prod silently regressing.
 *
 * Pure unit spec (no Spring context) — same lightweight style as
 * CorrelationIdFilterSpec / SitemapControllerSpec.
 */
class ForwardedHeadersProdConfigSpec extends Specification {

    def "application-prod.yml sets server.forward-headers-strategy to framework"() {
        given: 'the shipped prod profile yaml'
        def stream = getClass().getResourceAsStream('/application-prod.yml')

        expect: 'it is on the classpath'
        stream != null

        when: 'we parse the server.forward-headers-strategy node'
        def root = new Yaml().load(stream) as Map
        def server = root.get('server') as Map
        def raw = server?.get('forward-headers-strategy')?.toString()

        then: 'the property is declared'
        raw != null

        and: 'and resolves to `framework` (the default of the ${...:framework} placeholder)'
        // Stored as `${SERVER_FORWARD_HEADERS_STRATEGY:framework}` so an
        // operator can override, but the baked-in default MUST be framework
        // — an unset/empty default is the exact prod bug this pins against.
        def defaulted = raw.replaceFirst(/^\$\{[^:}]+:?/, '').replaceFirst(/}$/, '')
        defaulted == 'framework'
    }

    def "ForwardedHeaderFilter (what `framework` installs) rewrites X-Forwarded-Proto:https so the request is secure"() {
        given:
        def filter = new ForwardedHeaderFilter()
        def req = new MockHttpServletRequest('GET', '/api/auth/steam/login')
        // Reproduces the cloudflared -> nginx -> app hop: the container
        // receives plain HTTP, but Cloudflare's X-Forwarded-Proto says https.
        req.scheme = 'http'
        req.secure = false
        req.serverName = 'localhost'
        req.serverPort = 8082
        req.addHeader('X-Forwarded-Proto', 'https')
        req.addHeader('X-Forwarded-Host', 'skinbox.market')
        def resp = new MockHttpServletResponse()

        and: 'a chain that inspects the (possibly wrapped) request'
        boolean sawSecure = false
        String sawScheme = null
        String sawServerName = null
        FilterChain chain = new FilterChain() {
            @Override
            void doFilter(jakarta.servlet.ServletRequest r, jakarta.servlet.ServletResponse s) {
                def hr = r as HttpServletRequest
                sawSecure = hr.isSecure()
                sawScheme = hr.scheme
                sawServerName = hr.serverName
            }
        }

        when:
        filter.doFilter(req, resp, chain)

        then: 'downstream code (controllers, security filters) sees https'
        sawSecure
        sawScheme == 'https'
        sawServerName == 'skinbox.market'
    }

    def "ForwardedHeaderFilter makes sendRedirect emit an https Location behind the proxy"() {
        // The concrete SteamAuthController failure mode: a relative
        // sendRedirect() Location is resolved against the request scheme.
        // Without the forwarded-header rewrite that scheme is http; with it
        // the browser is sent to https.
        given:
        def filter = new ForwardedHeaderFilter()
        def req = new MockHttpServletRequest('GET', '/api/auth/steam/return')
        req.scheme = 'http'
        req.secure = false
        req.serverName = 'localhost'
        req.serverPort = 8082
        req.addHeader('X-Forwarded-Proto', 'https')
        req.addHeader('X-Forwarded-Host', 'skinbox.market')
        def resp = new MockHttpServletResponse()

        and: 'a chain that issues a same-app redirect with a relative path'
        FilterChain chain = new FilterChain() {
            @Override
            void doFilter(jakarta.servlet.ServletRequest r, jakarta.servlet.ServletResponse s) {
                (s as HttpServletResponse).sendRedirect('/?login=success')
            }
        }

        when:
        filter.doFilter(req, resp, chain)

        then: 'the absolutised Location is https on the public host, never a http downgrade'
        def location = resp.getHeader('Location')
        location != null
        !location.startsWith('http://')
        // MockHttpServletResponse absolutises a relative sendRedirect against
        // the (now-rewritten) request URL, so the host+scheme are the
        // forwarded ones.
        location.startsWith('https://skinbox.market')
    }
}
