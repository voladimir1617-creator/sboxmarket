package com.sboxmarket.config

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

import java.net.InetAddress

/**
 * Single source of truth for the REAL client IP behind the reverse proxy.
 *
 * Forwarded client-IP headers (CF-Connecting-IP, X-Forwarded-For) are honoured
 * ONLY when the immediate TCP peer (req.remoteAddr) is itself a trusted reverse
 * proxy — otherwise a direct client could set those headers to forge an IP. This
 * is the same spoofing-resistant algorithm RateLimitFilter uses (task #234);
 * extracted here so AuditService (audit-trail integrity) and ItemController
 * (view-count dedup) resolve the client IP the SAME safe way instead of reading
 * the headers unconditionally. The trusted-proxy CIDR list is the SAME property
 * the rate limiter reads, so the two can never disagree. (integrity-audit fix)
 */
@Component
class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver)

    @Value('${sbox.ratelimit.trusted-proxies:127.0.0.0/8,::1/128}')
    private String trustedProxiesProp = '127.0.0.0/8,::1/128'

    private volatile List<Cidr> trustedCidrsCache

    private static class Cidr {
        final byte[] network
        final int prefix
        Cidr(byte[] network, int prefix) { this.network = network; this.prefix = prefix }
        boolean contains(byte[] ip) {
            if (ip == null || ip.length != network.length) return false
            int fullBytes = (prefix / 8) as int
            for (int i = 0; i < fullBytes; i++) {
                if (ip[i] != network[i]) return false
            }
            int remBits = prefix % 8
            if (remBits != 0) {
                int mask = (0xFF << (8 - remBits)) & 0xFF
                if ((ip[fullBytes] & mask) != (network[fullBytes] & mask)) return false
            }
            true
        }
    }

    private List<Cidr> trustedCidrs() {
        def c = trustedCidrsCache
        if (c == null) {
            synchronized (this) {
                c = trustedCidrsCache
                if (c == null) {
                    c = parseCidrs(trustedProxiesProp)
                    trustedCidrsCache = c
                }
            }
        }
        c
    }

    private List<Cidr> parseCidrs(String csv) {
        def out = new ArrayList<Cidr>()
        for (String part : (csv ?: '').split(',')) {
            def p = part?.trim()
            if (!p) continue
            try {
                String ipPart
                int prefix
                int slash = p.indexOf('/')
                if (slash >= 0) {
                    ipPart = p.substring(0, slash).trim()
                    prefix = Integer.parseInt(p.substring(slash + 1).trim())
                } else {
                    ipPart = p
                    prefix = -1
                }
                byte[] net = InetAddress.getByName(ipPart).address
                int maxBits = net.length * 8
                if (prefix < 0 || prefix > maxBits) prefix = maxBits
                out.add(new Cidr(net, prefix))
            } catch (Exception e) {
                log.warn("Ignoring invalid trusted-proxy CIDR: ${p}")
            }
        }
        out
    }

    /**
     * True when `ip` is a literal address inside a trusted-proxy CIDR (or any
     * loopback address). Only ever called on req.remoteAddr (a literal socket
     * IP), so InetAddress never triggers a DNS lookup.
     */
    boolean isTrustedProxy(String ip) {
        if (!ip) return false
        byte[] b
        try {
            b = InetAddress.getByName(ip).address
        } catch (Exception ignored) {
            return false
        }
        try {
            if (InetAddress.getByAddress(b).isLoopbackAddress()) return true
        } catch (Exception ignored) { }
        for (Cidr c : trustedCidrs()) {
            if (c.contains(b)) return true
        }
        false
    }

    /**
     * The real client IP. Forwarded headers (CF-Connecting-IP then X-Forwarded-For)
     * are honoured ONLY when the immediate peer is a trusted proxy; otherwise the
     * raw socket address is returned so a direct client can't forge it. Never null
     * (falls back to '' / remoteAddr).
     */
    String resolve(HttpServletRequest req) {
        if (req == null) return ''
        def remote = req.remoteAddr
        if (!isTrustedProxy(remote)) {
            return remote ?: ''
        }
        def cf = req.getHeader('CF-Connecting-IP')
        if (cf && !cf.trim().isEmpty()) return cf.trim()
        def xff = req.getHeader('X-Forwarded-For')
        if (xff) {
            // First non-empty hop. A crafted `,`/`,,` header splits to a
            // zero-length array, so guard against the empty-token case.
            for (String tok : xff.split(',')) {
                def t = tok?.trim()
                if (t) return t
            }
        }
        remote ?: ''
    }
}
