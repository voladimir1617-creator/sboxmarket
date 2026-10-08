package com.sboxmarket.config

import groovy.util.logging.Slf4j
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Shuts every money and staff route on the PUBLIC DEMO profile.
 *
 * The demo is reachable by anyone, has no Stripe account, and nobody on it is
 * staff. Rather than trust each controller's own checks, the routes that move
 * money (deposits, withdrawals, seller payout onboarding, the Stripe webhook)
 * and the staff consoles (admin, CSR, database info, dev-login) answer a plain
 * 404 before any controller runs, the same answer as a route that does not
 * exist. Buying, listing, offers and the rest of the plain market stay open.
 *
 * Runs just after {@link CanonicalPathFilter}, so encoded or {@code ;}
 * smuggling has already been rejected. Matching is on the lower-cased path
 * with duplicate and trailing slashes collapsed, and both the raw URI and the
 * container-decoded servlet path are checked.
 */
@Component
@Profile('demo')
@Order(Ordered.HIGHEST_PRECEDENCE + 210)
@Slf4j
class PublicDemoFilter extends OncePerRequestFilter {

    /** Path prefixes shut on the demo; a prefix also covers everything under it. */
    static final List<String> BLOCKED_PREFIXES = [
        '/api/admin',
        '/api/csr',
        '/api/database',
        '/api/stripe',
        '/api/auth/steam/dev-login',
        '/api/wallet/deposit',
        '/api/wallet/confirm-deposit',
        '/api/wallet/withdraw',
        '/api/wallet/connect',
        '/h2-console',
        '/actuator',
    ].asImmutable()

    @Value('${sbox.public-demo.enabled:false}')
    boolean enabled

    static String normalize(String path) {
        if (path == null) return ''
        String p = path.toLowerCase(Locale.ROOT).replaceAll('/{2,}', '/')
        while (p.length() > 1 && p.endsWith('/')) p = p.substring(0, p.length() - 1)
        return p
    }

    static boolean isBlocked(String path) {
        String p = normalize(path)
        BLOCKED_PREFIXES.any { String b -> p == b || p.startsWith(b + '/') }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) {
        if (enabled) {
            String decoded = (req.servletPath ?: '') + (req.pathInfo ?: '')
            if (isBlocked(req.requestURI) || isBlocked(decoded)) {
                log.info('Public demo: refused {} {}', req.method, req.requestURI?.take(200))
                resp.status = 404
                resp.contentType = 'application/json'
                resp.writer.write('{"code":"NOT_AVAILABLE_ON_DEMO","message":"This is a demo copy of SkinBox. ' +
                    'Payments, withdrawals and staff tools are switched off here."}')
                return
            }
        }
        chain.doFilter(req, resp)
    }
}
