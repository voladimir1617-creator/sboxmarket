package com.sboxmarket.controller

import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

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

    @Value('${app.public-url:http://localhost:8080}')
    String publicUrl

    private static final List<Map<String, String>> STATIC_URLS = [
        [loc: '/',                          freq: 'hourly',  priority: '1.0'],
        [loc: '/search',                    freq: 'hourly',  priority: '0.9'],
        [loc: '/db',                        freq: 'daily',   priority: '0.8'],
        [loc: '/loadout',                   freq: 'daily',   priority: '0.6'],
        [loc: '/help',                      freq: 'monthly', priority: '0.5'],
        [loc: '/faq',                       freq: 'monthly', priority: '0.5'],
        [loc: '/legal/terms.html',          freq: 'yearly',  priority: '0.3'],
        [loc: '/legal/trade-safety.html',   freq: 'yearly',  priority: '0.3'],
        [loc: '/legal/disclaimer.html',     freq: 'yearly',  priority: '0.3'],
        [loc: '/legal/acceptable-use.html', freq: 'yearly',  priority: '0.3'],
        [loc: '/legal/cookies.html',        freq: 'yearly',  priority: '0.3']
    ]

    @GetMapping(value = '/sitemap.xml', produces = 'application/xml')
    ResponseEntity<String> sitemap() {
        def base = publicUrl.endsWith('/') ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl
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
        // Item detail pages — capped at 10k. ItemRepository.findAll()
        // returns every row; that's fine at our catalogue size (~80 items)
        // but the take(10000) guard is the upper bound for when sboxmarket
        // grows into a 50M-row catalogue. At that point we'd split into a
        // sitemap index.
        try {
            def items = itemRepository.findAll().take(10_000)
            items.each { item ->
                sb.append('  <url>\n')
                  .append('    <loc>').append(base).append('/item/').append(item.id).append('</loc>\n')
                  .append('    <changefreq>daily</changefreq>\n')
                  .append('    <priority>0.7</priority>\n')
                  .append('  </url>\n')
            }
        } catch (Exception e) {
            log.warn("Sitemap item generation failed; returning static URLs only: ${e.message}")
        }
        // Seller stall pages — only include sellers with at least 1
        // completed sale. Guards against dumping every registered
        // account (including dormant ones) into the sitemap, which
        // would thin out the index value of the real active stalls.
        // Reuses the existing topSellers aggregate with minSold=1.
        try {
            if (listingRepository != null) {
                def sellerRows = listingRepository.topSellers(1L,
                    org.springframework.data.domain.PageRequest.of(0, 10_000))
                sellerRows.each { row ->
                    def sellerId = row[0] as Long
                    if (sellerId != null) {
                        sb.append('  <url>\n')
                          .append('    <loc>').append(base).append('/stall/').append(sellerId).append('</loc>\n')
                          .append('    <changefreq>weekly</changefreq>\n')
                          .append('    <priority>0.5</priority>\n')
                          .append('  </url>\n')
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Sitemap stall generation failed; skipping: ${e.message}")
        }
        sb.append('</urlset>\n')
        ResponseEntity.ok()
            .header('Content-Type', 'application/xml; charset=utf-8')
            .header('Cache-Control', 'public, max-age=3600')
            .body(sb.toString())
    }
}
