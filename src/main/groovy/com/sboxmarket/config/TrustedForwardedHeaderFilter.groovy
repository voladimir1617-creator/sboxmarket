package com.sboxmarket.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.ForwardedHeaderFilter

/**
 * Spring's ForwardedHeaderFilter (what `server.forward-headers-strategy:
 * framework` installs in the prod profile) sets `request.remoteAddr` to the
 * LEFTMOST `X-Forwarded-For` entry, or to `Forwarded: for=`, and trusts them
 * from anyone. Cloudflare and nginx append to a client-sent X-Forwarded-For
 * rather than replace it, so the leftmost entry is whatever the client typed.
 * Every rate-limit bucket keyed on the client IP (sign-in, unsubscribe,
 * client-errors, search) reset on a fresh fake header per request, and
 * audit/sign-in IPs were forgeable.
 *
 * This subclass cleans the headers before handing the request to the stock
 * filter:
 *   - the TCP peer is not a trusted proxy ({@link ClientIpResolver}) →
 *     every forwarded header is dropped, the socket address stands;
 *   - the peer is a trusted proxy → `Forwarded` is dropped and
 *     `X-Forwarded-For` is replaced by ONE address: Cloudflare's
 *     `CF-Connecting-IP` (Cloudflare overwrites any client value), else the
 *     rightmost hop that is not itself a trusted proxy, else the peer.
 * Scheme and host (`X-Forwarded-Proto`/`-Host`) from a trusted proxy still
 * flow through unchanged, which is what the prod profile turned this on for.
 */
class TrustedForwardedHeaderFilter extends ForwardedHeaderFilter {

    static final Set<String> FORWARDED_HEADERS = ([
        'forwarded', 'x-forwarded-for', 'x-forwarded-host', 'x-forwarded-port',
        'x-forwarded-proto', 'x-forwarded-prefix', 'x-forwarded-ssl'
    ] as Set).asImmutable()

    private final ClientIpResolver ipResolver

    TrustedForwardedHeaderFilter(ClientIpResolver ipResolver) {
        this.ipResolver = ipResolver
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Always run: a request whose only forwarded header is one we are
        // about to drop still has to go through the cleaning below.
        false
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) {
        super.doFilterInternal(clean(request), response, chain)
    }

    HttpServletRequest clean(HttpServletRequest request) {
        String peer = request.remoteAddr
        if (!ipResolver.isTrustedProxy(peer)) {
            return new HeaderOverrideRequest(request, FORWARDED_HEADERS, null)
        }
        String client = clientBehindTrustedProxy(request, peer)
        return new HeaderOverrideRequest(request, ['forwarded'] as Set, client)
    }

    private String clientBehindTrustedProxy(HttpServletRequest request, String peer) {
        String cf = request.getHeader('CF-Connecting-IP')?.trim()
        if (cf) return cf
        List<String> hops = []
        for (Enumeration<String> e = request.getHeaders('X-Forwarded-For'); e?.hasMoreElements();) {
            for (String tok : (e.nextElement() ?: '').split(',')) {
                String t = tok.trim()
                if (t) hops << t
            }
        }
        for (int i = hops.size() - 1; i >= 0; i--) {
            if (!ipResolver.isTrustedProxy(hops[i])) return hops[i]
        }
        return peer
    }

    /** Drops the named headers (case-insensitive) and, when `xffValue` is
     *  set, presents it as the only X-Forwarded-For value. */
    static class HeaderOverrideRequest extends HttpServletRequestWrapper {
        private final Set<String> dropped
        private final String xffValue

        HeaderOverrideRequest(HttpServletRequest req, Set<String> dropped, String xffValue) {
            super(req)
            this.dropped = dropped.collect { it.toLowerCase() } as Set
            this.xffValue = xffValue
        }

        private boolean isXff(String name) { name != null && name.equalsIgnoreCase('X-Forwarded-For') }
        private boolean isDropped(String name) { name != null && dropped.contains(name.toLowerCase()) }

        @Override
        String getHeader(String name) {
            if (xffValue != null && isXff(name)) return xffValue
            if (isDropped(name)) return null
            super.getHeader(name)
        }

        @Override
        Enumeration<String> getHeaders(String name) {
            if (xffValue != null && isXff(name)) return Collections.enumeration([xffValue])
            if (isDropped(name)) return Collections.emptyEnumeration()
            super.getHeaders(name)
        }

        @Override
        Enumeration<String> getHeaderNames() {
            List<String> names = []
            for (Enumeration<String> e = super.getHeaderNames(); e?.hasMoreElements();) {
                String n = e.nextElement()
                if (isXff(n)) continue
                if (!isDropped(n)) names << n
            }
            if (xffValue != null) names << 'X-Forwarded-For'
            Collections.enumeration(names)
        }
    }
}
