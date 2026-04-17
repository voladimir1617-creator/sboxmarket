package com.sboxmarket.controller

import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.ListingService
import groovy.util.logging.Slf4j
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

import java.nio.charset.StandardCharsets

/**
 * Dynamic OG-tag injection for /item/{id} URLs.
 *
 * Every SPA route used to forward to the static index.html, which meant
 * a link posted into Discord / Twitter / Slack rendered the generic
 * "SkinBox — s&box Skin Marketplace" preview regardless of which item
 * was actually shared. This controller intercepts /item/{id}, fetches
 * the item, and rewrites the OG tags so the rich preview shows the
 * item's name + price + image. The client-side SPA picks up from
 * there once the page loads — no behavioural change for the user.
 *
 * Falls back to the original static HTML if the item id is unknown or
 * anything goes wrong reading the template, so bots always get a
 * 200 with at least the default preview.
 */
@RestController
@Slf4j
class OpenGraphController {

    @Autowired ItemRepository itemRepository
    @Autowired SteamUserRepository steamUserRepository
    @Autowired(required = false) ListingService listingService

    @Value('${app.public-url:http://localhost:8080}')
    String publicUrl

    /** Full contents of /static/index.html loaded once at startup. */
    private String template = null

    @PostConstruct
    void loadTemplate() {
        try {
            def res = new ClassPathResource('static/index.html')
            template = res.inputStream.getText(StandardCharsets.UTF_8.name())
        } catch (Exception e) {
            log.warn("Could not load index.html template for OG tag injection: ${e.message}")
        }
    }

    @GetMapping(value = '/item/{id}', produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> itemPage(@PathVariable String id) {
        if (template == null) {
            // Template never loaded — let the SPA fallback handler serve it.
            return ResponseEntity.status(404).body('')
        }
        Long itemId
        try { itemId = Long.parseLong(id) }
        catch (NumberFormatException ignored) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(template)
        }
        def item = itemRepository.findById(itemId).orElse(null)
        if (item == null) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(template)
        }
        def base = publicUrl.endsWith('/') ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl
        def url = base + '/item/' + itemId
        def name = escape(item.name ?: 'Item')
        def priceStr = (item.lowestPrice != null && item.lowestPrice > BigDecimal.ZERO)
            ? '$' + item.lowestPrice.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString()
            : 'Out of stock'
        def desc = "${name} — floor price ${priceStr} on SkinBox. " +
                   "Browse live listings, trade safely with Stripe-secured checkout and 2% fees."
        def image = item.imageUrl ?: (base + '/img/favicon-512.png')
        def title = "${name} · ${priceStr} · SkinBox"

        // String-replace the static OG + Twitter tags + <title>. Cheap
        // text substitution — avoids pulling in a full templating engine
        // for what's essentially a head-section patch.
        //
        // IMPORTANT: Matcher.quoteReplacement every replacement that
        // might contain `$` — Groovy/Java String.replaceFirst treats
        // `$N` in the replacement as a capture-group backreference, so
        // an unescaped "$4.00" item price throws IndexOutOfBoundsException.
        def Q = java.util.regex.Matcher.&quoteReplacement
        def out = template
            .replaceFirst(/<title>[^<]*<\/title>/, Q("<title>${escape(title)}</title>"))
            .replaceFirst(/<meta property="og:title"[^>]*>/,       Q("<meta property=\"og:title\" content=\"${escape(title)}\">"))
            .replaceFirst(/<meta property="og:description"[^>]*>/, Q("<meta property=\"og:description\" content=\"${escape(desc)}\">"))
            .replaceFirst(/<meta property="og:image"[^>]*>/,       Q("<meta property=\"og:image\" content=\"${escape(image)}\">"))
            .replaceFirst(/<meta property="og:url"[^>]*>/,         Q("<meta property=\"og:url\" content=\"${escape(url)}\">"))
            .replaceFirst(/<meta property="og:type"[^>]*>/,        Q("<meta property=\"og:type\" content=\"product\">"))
            .replaceFirst(/<meta name="twitter:title"[^>]*>/,      Q("<meta name=\"twitter:title\" content=\"${escape(title)}\">"))
            .replaceFirst(/<meta name="description"[^>]*>/,        Q("<meta name=\"description\" content=\"${escape(desc)}\">"))

        ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .header('Cache-Control', 'public, max-age=300')
            .body(out)
    }

    /** Same treatment for /stall/{id} — seller stall shares. */
    @GetMapping(value = '/stall/{id}', produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> stallPage(@PathVariable String id) {
        if (template == null) return ResponseEntity.status(404).body('')
        Long userId
        try { userId = Long.parseLong(id) }
        catch (NumberFormatException ignored) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(template)
        }
        def user = steamUserRepository.findById(userId).orElse(null)
        if (user == null) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(template)
        }
        def base = publicUrl.endsWith('/') ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl
        def url = base + '/stall/' + userId
        def name = escape(user.displayName ?: 'Seller')
        def title = "${name}'s Stall · SkinBox"
        def desc = "Browse ${name}'s active listings on SkinBox. Secure escrow, 2% fees, Stripe payouts."
        def image = user.avatarUrl ?: (base + '/img/favicon-512.png')

        def Q = java.util.regex.Matcher.&quoteReplacement
        def out = template
            .replaceFirst(/<title>[^<]*<\/title>/, Q("<title>${escape(title)}</title>"))
            .replaceFirst(/<meta property="og:title"[^>]*>/,       Q("<meta property=\"og:title\" content=\"${escape(title)}\">"))
            .replaceFirst(/<meta property="og:description"[^>]*>/, Q("<meta property=\"og:description\" content=\"${escape(desc)}\">"))
            .replaceFirst(/<meta property="og:image"[^>]*>/,       Q("<meta property=\"og:image\" content=\"${escape(image)}\">"))
            .replaceFirst(/<meta property="og:url"[^>]*>/,         Q("<meta property=\"og:url\" content=\"${escape(url)}\">"))
            .replaceFirst(/<meta property="og:type"[^>]*>/,        Q("<meta property=\"og:type\" content=\"profile\">"))
            .replaceFirst(/<meta name="twitter:title"[^>]*>/,      Q("<meta name=\"twitter:title\" content=\"${escape(title)}\">"))
            .replaceFirst(/<meta name="description"[^>]*>/,        Q("<meta name=\"description\" content=\"${escape(desc)}\">"))

        ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .header('Cache-Control', 'public, max-age=300')
            .body(out)
    }

    /**
     * Escape HTML-meaningful characters for use inside an attribute
     * value. Regex also drops control chars so a crafted item name with
     * null bytes can't break the template. Null-safe.
     */
    private static String escape(String s) {
        if (s == null) return ''
        // Escape & ONLY when it isn't already part of a valid HTML entity.
        // Item names can round-trip through storage carrying existing
        // entities ("Mob Boss Waistcoat &amp; Shirt"); re-escaping
        // doubles them ("&amp;amp;"). The negative lookahead matches
        // the common numeric/named entity shapes.
        s.replaceAll(/&(?!(amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);)/, '&amp;')
         .replace('<', '&lt;')
         .replace('>', '&gt;')
         .replace('"', '&quot;')
         .replace("'", '&#39;')
         .replaceAll(/[\x00-\x1f]/, '')
    }
}
