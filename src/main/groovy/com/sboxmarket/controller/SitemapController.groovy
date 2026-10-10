package com.sboxmarket.controller

import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import com.sboxmarket.repository.LoadoutRepository
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Dynamic sitemap.xml — the static file used to list only top-level nav
 * URLs, which meant every item detail page was invisible to search
 * engines. This endpoint includes every catalogue item so /item/:id
 * URLs get indexed as the catalogue grows.
 *
 * Caps at 10_000 item URLs per the sitemap spec (50_000 total URL limit
 * with a 50MB size cap — we stay well under either). If the catalogue
 * ever exceeds that, split into a sitemap index.
 */
@RestController
@Slf4j
class SitemapController {

    @Autowired ItemRepository itemRepository
    @Autowired(required = false) ListingRepository listingRepository
    @Autowired(required = false) LoadoutRepository loadoutRepository
    @Autowired(required = false) com.sboxmarket.repository.SteamUserRepository steamUserRepository

    @Value('${app.public-url:http://localhost:8080}')
    String publicUrl

    private static final List<Map<String, String>> STATIC_URLS = [
        [loc: '/',                          freq: 'hourly',  priority: '1.0'],
        // /market is the canonical marketplace route (PWA start_url + OpenSearch
        // template both point here). /search is a back-compat alias whose page
        // declares /market as canonical, so it stays out of the sitemap.
        [loc: '/market',                    freq: 'hourly',  priority: '0.9'],
        [loc: '/db',                        freq: 'daily',   priority: '0.8'],
        [loc: '/loadout',                   freq: 'daily',   priority: '0.6'],
        [loc: '/help',                      freq: 'monthly', priority: '0.5'],
        [loc: '/faq',                       freq: 'monthly', priority: '0.5'],
        // Batch 809 — Affiliate program page. Public recruitment
        // surface — creators search for "SkinBox affiliate" and expect
        // an indexable page with requirements + apply CTA. The footer
        // link on every page already points here; sitemap should too.
        [loc: '/affiliate',                 freq: 'monthly', priority: '0.5'],
        // Public status + changelog pages — both are static HTML with
        // real SEO value. /changelog.html is shipped with OG tags
        // (batch 707) so external shares render a rich preview; /status.html
        // pulls the live /api/ready probe so crawlers see honest uptime copy.
        [loc: '/changelog.html',            freq: 'weekly',  priority: '0.4'],
        [loc: '/status.html',               freq: 'daily',   priority: '0.3'],
        [loc: '/legal/terms.html',          freq: 'yearly',  priority: '0.3'],
        [loc: '/legal/privacy.html',        freq: 'yearly',  priority: '0.3'],
        [loc: '/legal/refunds.html',        freq: 'yearly',  priority: '0.3'],
        [loc: '/legal/trade-safety.html',   freq: 'yearly',  priority: '0.3'],
        [loc: '/legal/disclaimer.html',     freq: 'yearly',  priority: '0.3'],
        [loc: '/legal/acceptable-use.html', freq: 'yearly',  priority: '0.3'],
        [loc: '/legal/cookies.html',        freq: 'yearly',  priority: '0.3'],
        [loc: '/legal/responsible-disclosure.html', freq: 'yearly', priority: '0.3']
    ]

    @GetMapping(value = '/sitemap.xml', produces = 'application/xml')
    ResponseEntity<String> sitemap(HttpServletRequest req) {
        // Derive base URL from the actual request, not the env var.
        // Same container serves http://localhost:8082 (dev) AND
        // https://skinbox.market (Cloudflare tunnel) — the env var
        // can only be one of those, but the request itself tells us
        // exactly which audience is asking. Falls back to publicUrl
        // when no Host header (shouldn't happen in HTTP/1.1+ but
        // belt-and-suspenders).
        def base = resolveBaseUrl(req)
        def sb = new StringBuilder()
        sb.append('<?xml version="1.0" encoding="UTF-8"?>\n')
        sb.append('<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n')
        STATIC_URLS.each { u ->
            sb.append('  <url>\n')
              .append('    <loc>').append(base).append(u.loc).append('</loc>\n')
              .append('    <changefreq>').append(u.freq).append('</changefreq>\n')
              .append('    <priority>').append(u.priority).append('</priority>\n')
              .append('  </url>\n')
        }
        // Item detail pages — capped at 10k. Batch 1016 swapped from
        // `findAll().take(10_000)` (which hydrated EVERY catalogue row
        // before truncating) to a paged JPQL query capped at 10k via
        // PageRequest — SQL-level LIMIT, so the DB does the trim. At the
        // current ~80-item catalogue this is a no-op perf-wise, but at
        // 50M rows it goes from "OOM killer" to "10k rows, one scan."
        // Beyond 10k we'd split into a sitemap index (one sitemap per
        // 10k-row chunk).
        try {
            def items = itemRepository.findAllForSitemap(
                org.springframework.data.domain.PageRequest.of(0, 10_000))
            items.each { item ->
                // View-count driven priority (batch 420). Items with real
                // interest bubble up the sitemap so search engines crawl
                // the popular ones first. 0.9 for hot items (>=100 views),
                // 0.8 for warm (>=25), 0.7 default. Stays under the
                // homepage (1.0) so crawlers keep that as the entry point.
                long views = (item.viewCount ?: 0L) as long
                def priority = views >= 100 ? '0.9'
                             : views >= 25  ? '0.8'
                             :                '0.7'
                sb.append('  <url>\n')
                  .append('    <loc>').append(base).append('/item/').append(item.id).append('</loc>\n')
                appendLastmod(sb, item.createdAt)
                sb.append('    <changefreq>daily</changefreq>\n')
                  .append('    <priority>').append(priority).append('</priority>\n')
                  .append('  </url>\n')
            }
        } catch (Exception e) {
            log.warn("Sitemap item generation failed; returning static URLs only: ${e.message}")
        }
        // Seller stall pages — include every seller with at least one
        // non-hidden listing, regardless of status (batch 667). The
        // prior implementation gated on `topSellers(1L)` (at least one
        // COMPLETED sale) which meant a freshly-launched marketplace
        // had ZERO stall URLs in the sitemap until the first sale
        // closed — a chicken-and-egg SEO gap. Active-listing sellers
        // have real public content (the stall grid) worth indexing.
        // Banned sellers are still excluded — their stall renders a
        // "suspended" banner with no real content, indexing it just
        // dilutes the sitemap's signal-to-noise ratio for crawlers.
        try {
            if (listingRepository != null) {
                def sellerIds = listingRepository.findSellerIdsWithAnyListing(
                    org.springframework.data.domain.PageRequest.of(0, 10_000))
                    .findAll { it != null }
                Set<Long> bannedIds = new HashSet<>()
                // Batch 965 — also capture each seller's lastSyncedAt for
                // the <lastmod> tag. Falls back to createdAt if the user
                // has never had a Steam sync (brand-new account).
                Map<Long, Long> sellerLastMod = [:]
                if (steamUserRepository != null && !sellerIds.isEmpty()) {
                    steamUserRepository.findAllById(sellerIds).each { u ->
                        if (Boolean.TRUE.equals(u.banned)) bannedIds << u.id
                        sellerLastMod[u.id as Long] = (u.lastSyncedAt ?: u.createdAt ?: 0L) as Long
                    }
                }
                sellerIds.each { sellerId ->
                    if (bannedIds.contains(sellerId)) return
                    sb.append('  <url>\n')
                      .append('    <loc>').append(base).append('/stall/').append(sellerId).append('</loc>\n')
                    appendLastmod(sb, sellerLastMod[sellerId as Long])
                    sb.append('    <changefreq>weekly</changefreq>\n')
                      .append('    <priority>0.5</priority>\n')
                      .append('  </url>\n')
                }
            }
        } catch (Exception e) {
            log.warn("Sitemap stall generation failed; skipping: ${e.message}")
        }
        // PUBLIC loadout URLs — the Loadout Lab's copy-link feature
        // (`/loadout/{id}`) gets rich OG previews via OpenGraphController,
        // so search engines should know about the individual loadout
        // pages too. PRIVATE loadouts are excluded by the repository
        // query. Cap at 5_000 — plenty of room under the sitemap spec's
        // 50k URL limit for the combined item+stall+loadout set.
        try {
            if (loadoutRepository != null) {
                def loadouts = loadoutRepository.findPublic(
                    org.springframework.data.domain.PageRequest.of(0, 5_000))
                loadouts.each { l ->
                    sb.append('  <url>\n')
                      .append('    <loc>').append(base).append('/loadout/').append(l.id).append('</loc>\n')
                    appendLastmod(sb, (l.updatedAt ?: l.createdAt) as Long)
                    sb.append('    <changefreq>weekly</changefreq>\n')
                      .append('    <priority>0.4</priority>\n')
                      .append('  </url>\n')
                }
            }
        } catch (Exception e) {
            log.warn("Sitemap loadout generation failed; skipping: ${e.message}")
        }
        sb.append('</urlset>\n')
        ResponseEntity.ok()
            .header('Content-Type', 'application/xml; charset=utf-8')
            .header('Cache-Control', 'public, max-age=3600')
            .body(sb.toString())
    }

    /**
     * Strict shape for an inbound Host / X-Forwarded-Host header.
     * Accepts only hostname[:port] with hostname = label.label.label
     * (each label = alphanum + internal hyphens) and an optional 1-5
     * digit port. Crucially REJECTS anything containing XML/URL
     * meta-characters — `<`, `>`, `"`, `'`, `/`, `&`, spaces, CR/LF —
     * which would otherwise be concatenated directly into the
     * `<loc>...</loc>` body. A request with
     *   X-Forwarded-Host: a.com</loc><loc>https://phishing.com
     * would have been stitched into the sitemap verbatim and (because
     * the response is `Cache-Control: public, max-age=3600`) cached by
     * Cloudflare for an hour, served to Googlebot, and indexed as a
     * SkinBox URL pointing at the attacker. Reject the header outright
     * and fall back to the trusted `publicUrl` constant rather than try
     * to escape — the XML/URL escaping rules for `<loc>` differ in
     * subtle ways and a deny-list invites bypass.
     *
     * Anchored end-to-end. ASCII only; we never serve from an IDN host.
     */
    private static final java.util.regex.Pattern SAFE_HOST = ~/^[A-Za-z0-9]([A-Za-z0-9\-]{0,62}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9\-]{0,62}[A-Za-z0-9])?)*(:[0-9]{1,5})?$/

    /**
     * Build the canonical base URL ("https://skinbox.market") from the
     * incoming request. Honors X-Forwarded-{Proto,Host} so Cloudflare-
     * tunneled requests get https://skinbox.market even though the
     * underlying tunnel hop is plain HTTP. Falls back to the configured
     * APP_PUBLIC_URL when no Host header is available OR when the
     * inbound Host fails SAFE_HOST validation (host-header injection
     * guard — see SAFE_HOST docs).
     *
     * Strips trailing slash so callers can `base + '/path'` cleanly.
     * Package-scope so spec can exercise the resolution.
     */
    String resolveBaseUrl(HttpServletRequest req) {
        String proto = req.getHeader('X-Forwarded-Proto')
            ?: req.getHeader('X-Forwarded-Scheme')
            ?: req.scheme
        String host = req.getHeader('X-Forwarded-Host')
            ?: req.getHeader('Host')
            ?: req.serverName
        // Host-header injection guard. The Host / X-Forwarded-Host value
        // is attacker-controllable (Cloudflare sets a clean hostname,
        // but a request that bypasses the WAF — or any non-prod proxy
        // hop — can submit anything). Reject anything that isn't a
        // bare hostname[:port], fall back to publicUrl so a malicious
        // header can't poison the cached sitemap.xml served to Googlebot.
        if (host && !SAFE_HOST.matcher(host).matches()) {
            log.warn("Sitemap: rejecting malformed Host header (host-injection guard); falling back to publicUrl")
            host = null
        }
        if (proto && !(proto == 'http' || proto == 'https')) {
            // X-Forwarded-Proto is similarly attacker-controllable and
            // gets concatenated into the scheme://host:port URL. Allow
            // only the two real-world values; anything else falls back.
            proto = null
        }
        // Production-host scheme upgrade — cloudflared → nginx is plain HTTP
        // and nginx overwrites `X-Forwarded-Proto` with its own `$scheme`
        // (`http`). Sitemap entries previously leaked as `http://skinbox.market/...`
        // which fed crawlers a non-canonical URL. Force https for the
        // production host; local dev (`localhost:8082`) is unaffected.
        if (host && (host.equalsIgnoreCase('skinbox.market') || host.equalsIgnoreCase('www.skinbox.market'))) {
            proto = 'https'
        }
        if (host && proto) {
            // Strip port from host if it's the default for the scheme
            // (Cloudflare always sends just the hostname, but local dev
            // would send "localhost:8082" which we want to keep).
            String url = "${proto}://${host}".toString()
            return url.endsWith('/') ? url.substring(0, url.length() - 1) : url
        }
        return publicUrl.endsWith('/') ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl
    }

    /**
     * Append a `<lastmod>` tag formatted as ISO-8601 date (YYYY-MM-DD).
     * Google + Bing both accept this shorter form (W3C Date) and it
     * compresses 10× vs the full RFC 3339 timestamp — less bandwidth
     * for the same crawl signal. Silently skipped when the epoch is
     * null, zero, or clearly invalid (e.g. mis-populated sentinel).
     * Package-scope so SitemapControllerSpec can exercise the format.
     */
    static void appendLastmod(StringBuilder sb, Long epochMs) {
        if (epochMs == null || epochMs <= 0L) return
        try {
            def iso = DateTimeFormatter.ISO_LOCAL_DATE.format(
                Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC))
            sb.append('    <lastmod>').append(iso).append('</lastmod>\n')
        } catch (Exception ignore) {
            // Bad timestamp → omit the tag rather than 500 the sitemap.
        }
    }
}
