package com.sboxmarket.controller

import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.LoadoutRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.ListingService
import groovy.util.logging.Slf4j
import jakarta.annotation.PostConstruct
import jakarta.servlet.http.HttpServletRequest
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
    @Autowired LoadoutRepository loadoutRepository
    @Autowired(required = false) ListingService listingService

    @Value('${app.public-url:http://localhost:8080}')
    String publicUrl

    /** Contents of /static/index.html. Cached, but re-read whenever the
     *  underlying file's mtime changes so a cache-busted asset version
     *  (index.html's `?v=` bump) reaches OG-rendered routes WITHOUT a server
     *  restart. Previously the template was frozen at @PostConstruct, so
     *  every OG route (/market, /item, /db, /faq, /wallet, …) served whatever
     *  index.html existed at boot — i.e. a STALE `?v=` that made browsers
     *  load an OLD cached design.css/main.js on every page except the
     *  SPA-fallback home route, which reads the file live. In a packaged jar
     *  the resource mtime is constant (the jar's), so this loads exactly once
     *  there — a redeploy ships a new jar anyway. */
    private volatile String template = null
    private volatile long templateMtime = -1L

    /** Test seam: when true, refreshTemplate() leaves `template` untouched.
     *  A pure unit test injects a controlled fixture via `controller.template
     *  = ...`; without this pin the per-handler refreshTemplate() (which now
     *  runs on every request so a `?v=` bump reaches all routes without a
     *  restart) finds the real `static/index.html` on the test classpath and
     *  clobbers the fixture. Production never sets this — it always wants the
     *  live on-disk template. */
    boolean templatePinned = false

    @PostConstruct
    void loadTemplate() {
        refreshTemplate()
    }

    /** Reload the template if index.html changed on disk. Steady-state cost
     *  is a single stat (mtime check) with no lock; the file is only re-read
     *  + the monitor only entered when the mtime actually changes. */
    private void refreshTemplate() {
        if (templatePinned) return
        try {
            def res = new ClassPathResource('static/index.html')
            long mtime
            try { mtime = res.lastModified() } catch (Exception ignore) { mtime = -1L }
            // Unchanged (or mtime unavailable inside a jar AND already loaded) → nothing to do.
            if (template != null && (mtime <= 0L || mtime == templateMtime)) {
                return
            }
            synchronized (this) {
                if (template == null || (mtime > 0L && mtime != templateMtime)) {
                    template = res.inputStream.getText(StandardCharsets.UTF_8.name())
                    templateMtime = mtime
                }
            }
        } catch (Exception e) {
            log.warn("Could not (re)load index.html template for OG tag injection: ${e.message}")
        }
    }

    /**
     * Resolve the canonical base URL for OG tags from the actual request.
     * Same logic as `SitemapController.resolveBaseUrl` — the same container
     * serves http://localhost:8082 for local dev AND https://skinbox.market
     * via Cloudflare tunnel. Pre-fix, social shares of /item/{id} URLs
     * leaked `http://localhost:8082/item/...` into Discord / Facebook /
     * Twitter previews. Honors `X-Forwarded-{Proto,Host}` set by Cloudflare,
     * falls back to the direct `Host` header, finally the env var.
     *
     * Production-host scheme upgrade: the cloudflared → nginx hop is
     * plain HTTP, and nginx overwrites `X-Forwarded-Proto` with its own
     * `$scheme` variable — which is `http`, not the original Cloudflare
     * `https`. Result: every shared item URL leaked as `http://skinbox.market/...`
     * even though TLS is mandatory at the edge. Detect the production host
     * and force https — local dev (`localhost:8082`) is unaffected.
     */
    String resolveBaseUrl(HttpServletRequest req) {
        if (req != null) {
            String proto = req.getHeader('X-Forwarded-Proto') ?: req.getHeader('X-Forwarded-Scheme') ?: req.scheme
            String host = req.getHeader('X-Forwarded-Host') ?: req.getHeader('Host') ?: req.serverName
            // Host-header injection guard (sibling of SitemapController's
            // SAFE_HOST). The Host / X-Forwarded-Host value is
            // attacker-controllable (any request that bypasses the WAF,
            // or a non-prod proxy hop, can supply anything). An
            // attacker who sets `X-Forwarded-Host: phishing.com` would
            // have the og:url + canonical land as `https://phishing.com
            // /item/1` — Google / Discord / Twitter render the SkinBox
            // OG card but anchor the canonical at phishing.com, which
            // Google's canonical-consolidation honours and indexes the
            // attacker's domain as the source-of-truth for the URL. The
            // page-content `escape()` neuters XML-style breakouts, but
            // a bare hostname like `phishing.com` survives escape() and
            // lands in the URL verbatim. Strict allowlist regex —
            // hostname[:port], ASCII only, no URL/XML metachars — and
            // fall back to `publicUrl` when it fails. Same shape as
            // SitemapController so behaviour is identical across the
            // two OG surfaces.
            if (host && !SAFE_HOST.matcher(host).matches()) {
                log.warn("OpenGraph: rejecting malformed Host header (host-injection guard); falling back to publicUrl")
                host = null
            }
            if (proto && !(proto == 'http' || proto == 'https')) {
                proto = null
            }
            // Production host always serves over HTTPS — Cloudflare HSTS
            // upgrades any plain-HTTP attempt before it reaches us. Honor
            // that invariant when the upstream nginx hop has stamped
            // `http` on `X-Forwarded-Proto`.
            if (host && (host.equalsIgnoreCase('skinbox.market') || host.equalsIgnoreCase('www.skinbox.market'))) {
                proto = 'https'
            }
            if (proto && host) {
                String url = "${proto}://${host}".toString()
                return url.endsWith('/') ? url.substring(0, url.length() - 1) : url
            }
        }
        return publicUrl.endsWith('/') ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl
    }

    /** Mirror of SitemapController.SAFE_HOST — see that constant's
     *  docstring for the host-header-injection rationale. ASCII only,
     *  no URL/XML metachars, hostname[:port] shape. */
    private static final java.util.regex.Pattern SAFE_HOST = ~/^[A-Za-z0-9]([A-Za-z0-9\-]{0,62}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9\-]{0,62}[A-Za-z0-9])?)*(:[0-9]{1,5})?$/

    // Batch 968 — register both `/item/{id}` and `/item/{id}/` so a
    // trailing-slash URL (crawler inbound link, copy-paste, old-school
    // site convention) doesn't 404. Spring MVC's path-pattern parser
    // (default in Spring Boot 3) no longer auto-matches the trailing
    // slash variant. Same treatment on stall + loadout below.
    @GetMapping(value = ['/item/{id}', '/item/{id}/'], produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> itemPage(@PathVariable String id, HttpServletRequest req) {
        refreshTemplate()
        if (template == null) {
            // Template never loaded — let the SPA fallback handler serve it.
            return ResponseEntity.status(404).body('')
        }
        Long itemId
        try { itemId = Long.parseLong(id) }
        catch (NumberFormatException ignored) {
            // Non-numeric id → noindex shell. Crawlers honour the meta
            // tag and drop the URL; users see the SPA's branded "Item
            // not found" panel without a console-level document 404.
            return notFoundSpaShell()
        }
        def item = itemRepository.findById(itemId).orElse(null)
        if (item == null) {
            return notFoundSpaShell()
        }
        def base = resolveBaseUrl(req)
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
            // Strip the home logo's stale og:image:width/height/type (512x512 /
            // image/png from index.html) — the item image is a 184x184 JPEG
            // avatar / arbitrary economy PNG, so declaring 512x512 PNG makes
            // Facebook/Discord/LinkedIn render the share card distorted or reject
            // the content-type mismatch. Real dims aren't known at request time;
            // drop the hints and let the platform measure the image. (audit P2)
            .replaceFirst(/\s*<meta property="og:image:width"[^>]*>/,  '')
            .replaceFirst(/\s*<meta property="og:image:height"[^>]*>/, '')
            .replaceFirst(/\s*<meta property="og:image:type"[^>]*>/,   '')
            .replaceFirst(/<meta property="og:url"[^>]*>/,         Q("<meta property=\"og:url\" content=\"${escape(url)}\">"))
            .replaceFirst(/<meta property="og:type"[^>]*>/,        Q("<meta property=\"og:type\" content=\"product\">"))
            // Batch 783 — per-item canonical URL. Google collapses
            // duplicate URLs (e.g. /item/42?utm_source=twitter) onto
            // the canonical link, so every item has exactly one
            // indexable page rather than a long tail of query-param
            // variants splitting link equity.
            .replaceFirst(/<link rel="canonical"[^>]*>/,           Q("<link rel=\"canonical\" href=\"${escape(url)}\">"))
            .replaceFirst(/<meta name="twitter:title"[^>]*>/,      Q("<meta name=\"twitter:title\" content=\"${escape(title)}\">"))
            // Batch 650 — sync twitter:description + twitter:image with
            // the og:* values so X / Twitter cards no longer fall back
            // to the brand favicon + generic blurb from index.html.
            .replaceFirst(/<meta name="twitter:description"[^>]*>/, Q("<meta name=\"twitter:description\" content=\"${escape(desc)}\">"))
            .replaceFirst(/<meta name="twitter:image"[^>]*>/,       Q("<meta name=\"twitter:image\" content=\"${escape(image)}\">"))
            .replaceFirst(/<meta name="description"[^>]*>/,        Q("<meta name=\"description\" content=\"${escape(desc)}\">"))

        // Inject schema.org Product JSON-LD before </head> so Google's
        // rich-results parser can surface price + availability directly
        // in search. Intentionally conservative — no rating aggregate
        // (per-item reviews don't exist yet, aggregating by seller would
        // be misleading), no SKU (item ids are not treated as merchant
        // SKUs). Out-of-stock items drop the price block and mark
        // availability=OutOfStock so Google doesn't show stale pricing.
        def jsonLd = itemJsonLd(item, url, image, name)

        // og:price:amount + og:price:currency — Facebook's Open Graph
        // Product object spec recommends both for rich shopping
        // previews. The static template doesn't ship placeholders for
        // these (every other route would render an empty meta), so we
        // inject them inline only on in-stock items. og:availability
        // mirrors the JSON-LD signal so Discord / FB / Pinterest can
        // display "in stock" badges without parsing schema.org. og:image:alt
        // gives the screen-reader story for the social-card image.
        def ogProductTags = new StringBuilder()
        ogProductTags.append('  <meta property="og:image:alt" content="').append(escape(name)).append(' on SkinBox">\n')
        if (item.lowestPrice != null && item.lowestPrice > BigDecimal.ZERO) {
            def priceAmt = item.lowestPrice.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString()
            ogProductTags.append('  <meta property="og:price:amount" content="').append(priceAmt).append('">\n')
            ogProductTags.append('  <meta property="og:price:currency" content="USD">\n')
            ogProductTags.append('  <meta property="product:price:amount" content="').append(priceAmt).append('">\n')
            ogProductTags.append('  <meta property="product:price:currency" content="USD">\n')
            ogProductTags.append('  <meta property="og:availability" content="instock">\n')
        } else {
            ogProductTags.append('  <meta property="og:availability" content="oos">\n')
        }
        out = out.replace('</head>',
            "${ogProductTags}  <script type=\"application/ld+json\">${jsonLd}</script>\n</head>")

        ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            // `private` not `public` — every OG response carries the
            // CsrfFilter's Set-Cookie header for first-load users (it
            // mints `sbox_csrf` if no cookie is present). A shared
            // cache like Cloudflare that captured the response would
            // serve user A's cookie to user B on the cache hit,
            // collapsing two visitors onto the same CSRF token and
            // defeating per-user CSRF protection downstream. Per-user
            // browser cache (`private`) still gets the 5-min benefit
            // for refreshes; only the multi-tenant shared cache is
            // excluded — and crawlers, the primary cacheable consumer
            // here, don't carry cookies anyway.
            .header('Cache-Control', 'private, max-age=300')
            .body(out)
    }

    /**
     * Build a schema.org Product JSON-LD string for an item.
     * Lives as a private method so the test suite can exercise the
     * edge cases without needing the full itemPage round-trip.
     */
    private static String itemJsonLd(com.sboxmarket.model.Item item, String url, String image, String escapedName) {
        def inStock = item.lowestPrice != null && item.lowestPrice > BigDecimal.ZERO
        def sb = new StringBuilder()
        sb.append('{"@context":"https://schema.org","@type":"Product"')
        sb.append(',"name":"').append(jsonEscape(item.name ?: 'Item')).append('"')
        sb.append(',"url":"').append(jsonEscape(url)).append('"')
        // Google requires `image` on Product — emit the resolved absolute URL
        // (caller has already fallen back to the brand logo when the item
        // has no imageUrl), never the raw `item.imageUrl` which could be
        // null or a relative path. Without this, Search Console flagged
        // out-of-stock items as "Missing field 'image'" structured-data
        // errors.
        if (image) {
            sb.append(',"image":"').append(jsonEscape(image)).append('"')
        }
        if (item.category) {
            sb.append(',"category":"').append(jsonEscape(item.category)).append('"')
        }
        sb.append(',"brand":{"@type":"Brand","name":"SkinBox"}')
        sb.append(',"offers":{"@type":"Offer"')
        sb.append(',"url":"').append(jsonEscape(url)).append('"')
        sb.append(',"priceCurrency":"USD"')
        if (inStock) {
            sb.append(',"price":"').append(item.lowestPrice.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString()).append('"')
            sb.append(',"availability":"https://schema.org/InStock"')
        } else {
            sb.append(',"availability":"https://schema.org/OutOfStock"')
        }
        sb.append(',"itemCondition":"https://schema.org/UsedCondition"')
        sb.append('}}')
        sb.toString()
    }

    /**
     * Escape JSON-string control chars + the quote/backslash that would
     * otherwise break out of the literal. No unicode-escape for BMP
     * characters — browsers accept UTF-8 JSON-LD natively — but control
     * chars (0x00–0x1f) get \uXXXX so they can't smuggle a newline that
     * breaks the <script> block. Null-safe.
     */
    private static String jsonEscape(String s) {
        if (s == null) return ''
        def sb = new StringBuilder(s.length() + 16)
        s.each { String c ->
            switch (c) {
                case '"':   sb.append('\\"');   break
                case '\\':  sb.append('\\\\'); break
                case '\n':  sb.append('\\n');  break
                case '\r':  sb.append('\\r');  break
                case '\t':  sb.append('\\t');  break
                case '<':   sb.append('\\u003c'); break   // defense-in-depth vs </script> break-out
                case '>':   sb.append('\\u003e'); break
                default:
                    int ch = c.charAt(0)
                    if (ch < 0x20) {
                        sb.append(String.format('\\u%04x', ch))
                    } else {
                        sb.append(c)
                    }
            }
        }
        sb.toString()
    }

    /** Same treatment for /stall/{id} — seller stall shares. */
    @GetMapping(value = ['/stall/{id}', '/stall/{id}/'], produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> stallPage(@PathVariable String id, HttpServletRequest req) {
        refreshTemplate()
        if (template == null) return ResponseEntity.status(404).body('')
        Long userId
        try { userId = Long.parseLong(id) }
        catch (NumberFormatException ignored) {
            return notFoundSpaShell()
        }
        def user = steamUserRepository.findById(userId).orElse(null)
        if (user == null) {
            return notFoundSpaShell()
        }
        // A banned seller's stall renders only a "suspended" banner — no
        // listings, no real content. SitemapController already excludes
        // banned sellers from /sitemap.xml for exactly this reason; serve
        // the noindex shell here too so a crawler reaching /stall/{id} via
        // any other inbound link doesn't index an empty page (or attach a
        // Store rich-result + canonical to it). Same shell as an unknown
        // id, so a banned-vs-missing distinction can't be probed by status.
        if (Boolean.TRUE.equals(user.banned)) {
            return notFoundSpaShell()
        }
        def base = resolveBaseUrl(req)
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
            // Strip stale og:image:width/height/type (512x512/png home logo) —
            // the stall avatar is a 184x184 JPEG, so the dims/type lie. (audit P2)
            .replaceFirst(/\s*<meta property="og:image:width"[^>]*>/,  '')
            .replaceFirst(/\s*<meta property="og:image:height"[^>]*>/, '')
            .replaceFirst(/\s*<meta property="og:image:type"[^>]*>/,   '')
            .replaceFirst(/<meta property="og:url"[^>]*>/,         Q("<meta property=\"og:url\" content=\"${escape(url)}\">"))
            .replaceFirst(/<meta property="og:type"[^>]*>/,        Q("<meta property=\"og:type\" content=\"profile\">"))
            // Batch 783 — per-stall canonical URL (same reasoning as item).
            .replaceFirst(/<link rel="canonical"[^>]*>/,           Q("<link rel=\"canonical\" href=\"${escape(url)}\">"))
            .replaceFirst(/<meta name="twitter:title"[^>]*>/,      Q("<meta name=\"twitter:title\" content=\"${escape(title)}\">"))
            .replaceFirst(/<meta name="twitter:description"[^>]*>/, Q("<meta name=\"twitter:description\" content=\"${escape(desc)}\">"))
            .replaceFirst(/<meta name="twitter:image"[^>]*>/,       Q("<meta name=\"twitter:image\" content=\"${escape(image)}\">"))
            .replaceFirst(/<meta name="description"[^>]*>/,        Q("<meta name=\"description\" content=\"${escape(desc)}\">"))

        // schema.org Store JSON-LD — gives Google / Bing a structured
        // snapshot of the seller's stall so stall URLs can surface as
        // "Store" rich results. Kept minimal: name, URL, image, and a
        // brand pointer back to SkinBox so the aggregator shows the
        // parent marketplace.
        def sb = new StringBuilder()
        sb.append('{"@context":"https://schema.org","@type":"Store"')
        sb.append(',"name":"').append(jsonEscape((user.displayName ?: 'Seller') + "'s Stall")).append('"')
        sb.append(',"url":"').append(jsonEscape(url)).append('"')
        // Google requires `image` on Store/LocalBusiness — emit the resolved
        // absolute URL (caller has already fallen back to the brand logo
        // when the seller has no avatar). Previously the field was dropped
        // when `user.avatarUrl` was null, which surfaced as "Missing field
        // 'image'" rich-result warnings on avatarless stalls.
        if (image) {
            sb.append(',"image":"').append(jsonEscape(image)).append('"')
        }
        sb.append(',"brand":{"@type":"Brand","name":"SkinBox"}')
        sb.append('}')
        out = out.replace('</head>', "  <script type=\"application/ld+json\">${sb}</script>\n</head>")

        ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            // `private` not `public` — every OG response carries the
            // CsrfFilter's Set-Cookie header for first-load users (it
            // mints `sbox_csrf` if no cookie is present). A shared
            // cache like Cloudflare that captured the response would
            // serve user A's cookie to user B on the cache hit,
            // collapsing two visitors onto the same CSRF token and
            // defeating per-user CSRF protection downstream. Per-user
            // browser cache (`private`) still gets the 5-min benefit
            // for refreshes; only the multi-tenant shared cache is
            // excluded — and crawlers, the primary cacheable consumer
            // here, don't carry cookies anyway.
            .header('Cache-Control', 'private, max-age=300')
            .body(out)
    }

    /**
     * FAQPage JSON-LD for /faq so Google can render inline FAQ accordions
     * in search results. The Q&A pairs are kept in sync with `FaqModal`
     * in `modals.js` — both surfaces are authoritative, edit both when
     * either changes. Trade-off: a little duplication for significant
     * SEO value (FAQ rich results often double CTR versus a plain link).
     */
    private static final List<List<String>> FAQ_PAIRS = [
        ['What is SkinBox?',
         "SkinBox is a peer-to-peer marketplace for s&box cosmetic items. Every listing comes from a real seller who sets their own price — we're the middle layer that makes transactions safe, fast, and cheaper than going through the Steam store."],
        ['How do I sign in?',
         "Click the 'Sign in through Steam' button in the top-right. You'll bounce to steamcommunity.com, approve the login, and land back here already authenticated. Your Steam password never touches our servers — everything goes through OpenID."],
        ['How do I buy something?',
         "Top up your wallet first, then click any item and hit Buy. Funds are charged from your balance instantly — there's no bid-and-wait or 7-day trade hold like the Steam market."],
        ['How does depositing work?',
         "Open your Wallet, pick Deposit, enter an amount (anything from \$1 to \$10,000), and you'll be handed to Stripe's checkout page. Once the payment clears, our webhook credits your balance automatically."],
        ['How do withdrawals work?',
         "From your Wallet, pick Withdraw, enter a destination (Stripe Connect id, email, or a note) and the amount. Your balance is debited immediately and the payout is processed within 24 hours. A small network fee may apply depending on destination."],
        ['Why is SkinBox cheaper than Steam?',
         "Steam charges 12% in platform fees on Workshop sales and forces sellers into their pricing ladder. On SkinBox, sellers set whatever price they like — usually 10-30% below what the Steam store asks."],
        ['Do s&box items have wear levels?',
         "No. That's a Counter-Strike thing. s&box cosmetics are single items without Factory-New / Field-Tested / Battle-Scarred variants — closer to how Rust skins work."],
        ['Can I sell the items I own?',
         "Yes. Anything you've bought on SkinBox appears under Sell Items. Pick an item, choose Buy Now or Auction, set a price (or starting bid + duration), and it goes live in your stall. When it sells, the buyer's payment (minus a 2% platform fee) drops straight into your wallet."],
        ['How do auctions work?',
         "Auction listings show a countdown + current-bid input. Minimum bid is the current price plus \$0.05, and bidders can set an auto-bid cap so the bot keeps raising for them. Anti-snipe extends the auction by 30 seconds if a bid lands in the final minute. When the timer runs out the winner's wallet is charged automatically."],
        ['How do I run an auction as a seller?',
         "In the Sell Items flow, toggle the Listing type to Auction. Pick a duration (6h through 7 days), set a starting bid, and hit List. Anti-snipe extends the close by 30 seconds whenever a bid lands in the final 30 seconds. You can cancel any time, but once bids land you can't change the starting price — cancel + relist instead. No bids by the end? The item returns to your inventory automatically."],
        ['What is a Stall?',
         "Your Stall is your personal storefront — the list of items you currently have up for sale. Other users can browse it via your profile. You can cancel any listing from My Stall and the item returns to your inventory."],
        ['What are Offers?',
         "Offers are non-binding price suggestions. A buyer can propose less than your asking price; you get a notification and can accept or reject from the Offers tab."],
        ['Is my money safe?',
         "Deposits go through Stripe, the same processor used by millions of websites. We never store card details — only the amount and a Stripe reference. Withdrawal requests are logged and reviewed before payout. All balances are held in USD."]
    ]

    /**
     * /market is the canonical marketplace route — `/search` is a back-compat
     * alias the SPA routes to the same component. Without this handler, both
     * URLs served the static index.html with `<link rel="canonical" href="/">`
     * which collapsed /market into the marketing landing for SEO + social
     * shares. This handler emits per-route canonical + OG so Google indexes
     * /market as a distinct surface and Discord/Twitter shares of /market
     * preview as the marketplace, not the home hero.
     */
    /**
     * Helper: emit a static-content SPA route page with a per-route
     * canonical URL + OG bundle. Used for every public SPA route that
     * has no dynamic data (database/help/loadout-index/affiliate). Without
     * this, every route fell through to the static index.html with
     * `<link rel="canonical" href="/">` which collapsed all of them
     * into the home page in search-engine indexes.
     */
    private ResponseEntity<String> spaStaticPage(HttpServletRequest req, String path, String title, String desc, String ogType = 'website') {
        refreshTemplate()
        if (template == null) return ResponseEntity.status(404).body('')
        def base = resolveBaseUrl(req)
        def url = base + path

        def Q = java.util.regex.Matcher.&quoteReplacement
        def out = template
            .replaceFirst(/<title>[^<]*<\/title>/, Q("<title>${escape(title)}</title>"))
            .replaceFirst(/<meta property="og:title"[^>]*>/,        Q("<meta property=\"og:title\" content=\"${escape(title)}\">"))
            .replaceFirst(/<meta property="og:description"[^>]*>/,  Q("<meta property=\"og:description\" content=\"${escape(desc)}\">"))
            .replaceFirst(/<meta property="og:url"[^>]*>/,          Q("<meta property=\"og:url\" content=\"${escape(url)}\">"))
            .replaceFirst(/<meta property="og:type"[^>]*>/,         Q("<meta property=\"og:type\" content=\"${escape(ogType)}\">"))
            .replaceFirst(/<link rel="canonical"[^>]*>/,            Q("<link rel=\"canonical\" href=\"${escape(url)}\">"))
            .replaceFirst(/<meta name="twitter:title"[^>]*>/,       Q("<meta name=\"twitter:title\" content=\"${escape(title)}\">"))
            .replaceFirst(/<meta name="twitter:description"[^>]*>/, Q("<meta name=\"twitter:description\" content=\"${escape(desc)}\">"))
            .replaceFirst(/<meta name="description"[^>]*>/,         Q("<meta name=\"description\" content=\"${escape(desc)}\">"))

        ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            // `private` not `public` — see itemPage's same-rationale
            // comment. The Set-Cookie collision risk under a shared CDN
            // cache applies to every OG-rendered route, not just /item.
            .header('Cache-Control', 'private, max-age=3600')
            .body(out)
    }

    @GetMapping(value = ['/db', '/db/'], produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> dbPage(HttpServletRequest req) {
        return spaStaticPage(req, '/db',
            'Item Database · SkinBox',
            "The complete s&box skin catalogue — every Workshop item indexed with supply, sale count, view count, and floor price. Filter by category and rarity, click any row to open the listing detail.")
    }

    @GetMapping(value = ['/help', '/help/'], produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> helpPage(HttpServletRequest req) {
        return spaStaticPage(req, '/help',
            'Help Center · SkinBox',
            "Step-by-step guide to SkinBox — sign in with Steam, top up your wallet, browse + bargain + buy, list from your inventory, withdraw earnings. Plus FAQs and keyboard shortcuts.")
    }

    @GetMapping(value = ['/loadout', '/loadout/'], produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> loadoutIndexPage(HttpServletRequest req) {
        return spaStaticPage(req, '/loadout',
            'Loadout Lab · SkinBox',
            "Curate your s&box look — drag listings into named slots, browse public loadouts, auto-generate from a budget, share via copy-link. CSFloat-style outfit builder for s&box cosmetics.")
    }

    @GetMapping(value = ['/affiliate', '/affiliate/'], produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> affiliatePage(HttpServletRequest req) {
        return spaStaticPage(req, '/affiliate',
            'Affiliate Program · SkinBox',
            "Earn a cut of every trade you bring to SkinBox. Open to creators with 5K+ YouTube subs, 2K+ X/Twitter, 1K+ Twitch followers, or 10K+ Steam group members. Apply with a real audience.")
    }

    @GetMapping(value = ['/market', '/market/', '/search', '/search/'], produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> marketPage(HttpServletRequest req) {
        refreshTemplate()
        if (template == null) return ResponseEntity.status(404).body('')
        def base = resolveBaseUrl(req)
        def title = 'Marketplace · SkinBox'
        def desc = 'Browse every active s&box skin listing — filter by category, rarity, and price. Live auctions, instant Buy Now, escrowed Stripe checkout, 2% fees.'
        def url = base + '/market'

        def Q = java.util.regex.Matcher.&quoteReplacement
        def out = template
            .replaceFirst(/<title>[^<]*<\/title>/, Q("<title>${escape(title)}</title>"))
            .replaceFirst(/<meta property="og:title"[^>]*>/,        Q("<meta property=\"og:title\" content=\"${escape(title)}\">"))
            .replaceFirst(/<meta property="og:description"[^>]*>/,  Q("<meta property=\"og:description\" content=\"${escape(desc)}\">"))
            .replaceFirst(/<meta property="og:url"[^>]*>/,          Q("<meta property=\"og:url\" content=\"${escape(url)}\">"))
            .replaceFirst(/<meta property="og:type"[^>]*>/,         Q("<meta property=\"og:type\" content=\"website\">"))
            .replaceFirst(/<link rel="canonical"[^>]*>/,            Q("<link rel=\"canonical\" href=\"${escape(url)}\">"))
            .replaceFirst(/<meta name="twitter:title"[^>]*>/,       Q("<meta name=\"twitter:title\" content=\"${escape(title)}\">"))
            .replaceFirst(/<meta name="twitter:description"[^>]*>/, Q("<meta name=\"twitter:description\" content=\"${escape(desc)}\">"))
            .replaceFirst(/<meta name="description"[^>]*>/,         Q("<meta name=\"description\" content=\"${escape(desc)}\">"))

        ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            // `private` not `public` — see itemPage's same-rationale
            // comment. The Set-Cookie collision risk under a shared CDN
            // cache applies to every OG-rendered route, not just /item.
            .header('Cache-Control', 'private, max-age=3600')
            .body(out)
    }

    @GetMapping(value = ['/faq', '/faq/'], produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> faqPage(HttpServletRequest req) {
        refreshTemplate()
        if (template == null) return ResponseEntity.status(404).body('')
        def base = resolveBaseUrl(req)
        def title = 'FAQ · SkinBox'
        def desc = 'Answers to the most common SkinBox questions — signing in, depositing, buying, selling, withdrawing, and how s&box items differ from CS skins.'
        def url = base + '/faq'

        def Q = java.util.regex.Matcher.&quoteReplacement
        def out = template
            .replaceFirst(/<title>[^<]*<\/title>/, Q("<title>${escape(title)}</title>"))
            .replaceFirst(/<meta property="og:title"[^>]*>/,       Q("<meta property=\"og:title\" content=\"${escape(title)}\">"))
            .replaceFirst(/<meta property="og:description"[^>]*>/, Q("<meta property=\"og:description\" content=\"${escape(desc)}\">"))
            .replaceFirst(/<meta property="og:url"[^>]*>/,         Q("<meta property=\"og:url\" content=\"${escape(url)}\">"))
            .replaceFirst(/<meta property="og:type"[^>]*>/,        Q("<meta property=\"og:type\" content=\"website\">"))
            // Batch 783 — per-FAQ canonical URL.
            .replaceFirst(/<link rel="canonical"[^>]*>/,           Q("<link rel=\"canonical\" href=\"${escape(url)}\">"))
            .replaceFirst(/<meta name="twitter:title"[^>]*>/,      Q("<meta name=\"twitter:title\" content=\"${escape(title)}\">"))
            .replaceFirst(/<meta name="twitter:description"[^>]*>/, Q("<meta name=\"twitter:description\" content=\"${escape(desc)}\">"))
            .replaceFirst(/<meta name="description"[^>]*>/,        Q("<meta name=\"description\" content=\"${escape(desc)}\">"))

        // Build FAQPage JSON-LD. Each Q&A becomes a `Question` node with
        // a nested `Answer`. Google needs at least one Q&A but there's
        // no upper bound — we ship all of them.
        def sb = new StringBuilder()
        sb.append('{"@context":"https://schema.org","@type":"FAQPage","mainEntity":[')
        FAQ_PAIRS.eachWithIndex { pair, i ->
            if (i > 0) sb.append(',')
            sb.append('{"@type":"Question","name":"').append(jsonEscape(pair[0])).append('"')
            sb.append(',"acceptedAnswer":{"@type":"Answer","text":"').append(jsonEscape(pair[1])).append('"}}')
        }
        sb.append(']}')
        out = out.replace('</head>', "  <script type=\"application/ld+json\">${sb}</script>\n</head>")

        ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            // `private` not `public` — see itemPage's same-rationale
            // comment. The Set-Cookie collision risk under a shared CDN
            // cache applies to every OG-rendered route, not just /item.
            .header('Cache-Control', 'private, max-age=3600')
            .body(out)
    }

    /**
     * Same treatment for /loadout/{id} — the Loadout Lab "Copy link" button
     * surfaces this URL on Discord / Twitter / Steam groups, and until now
     * the preview was the generic site card. Private loadouts fall through
     * to the default preview so we never leak the owner's name / value from
     * a protected resource.
     */
    @GetMapping(value = ['/loadout/{id}', '/loadout/{id}/'], produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> loadoutPage(@PathVariable String id, HttpServletRequest req) {
        refreshTemplate()
        if (template == null) return ResponseEntity.status(404).body('')
        Long loadoutId
        try { loadoutId = Long.parseLong(id) }
        catch (NumberFormatException ignored) {
            return notFoundSpaShell()
        }
        def loadout = loadoutRepository.findById(loadoutId).orElse(null)
        if (loadout == null || loadout.visibility != 'PUBLIC') {
            // Unknown or PRIVATE → noindex shell, never leak loadout details.
            // PRIVATE loadouts intentionally get the same shell as unknown
            // ones so an attacker can't enumerate private loadout ids by
            // HTTP status difference.
            return notFoundSpaShell()
        }
        def base = resolveBaseUrl(req)
        def url = base + '/loadout/' + loadoutId
        def name = escape(loadout.name ?: 'Loadout')
        def ownerName = escape(loadout.ownerName ?: 'a SkinBox user')
        def valueStr = (loadout.totalValue != null && loadout.totalValue > BigDecimal.ZERO)
            ? '$' + loadout.totalValue.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString()
            : null
        def title = "${name} · SkinBox Loadout"
        def desc = valueStr
            ? "${name} by ${ownerName} — ${valueStr} total value. Pick any slot to shop the listing on SkinBox."
            : "${name} by ${ownerName} — browse this loadout on SkinBox."
        // No slot image pipeline for preview yet; default to the site logo
        // so the embed card still renders on Discord / Twitter.
        def image = base + '/img/favicon-512.png'

        def Q = java.util.regex.Matcher.&quoteReplacement
        def out = template
            .replaceFirst(/<title>[^<]*<\/title>/, Q("<title>${escape(title)}</title>"))
            .replaceFirst(/<meta property="og:title"[^>]*>/,       Q("<meta property=\"og:title\" content=\"${escape(title)}\">"))
            .replaceFirst(/<meta property="og:description"[^>]*>/, Q("<meta property=\"og:description\" content=\"${escape(desc)}\">"))
            .replaceFirst(/<meta property="og:image"[^>]*>/,       Q("<meta property=\"og:image\" content=\"${escape(image)}\">"))
            .replaceFirst(/<meta property="og:url"[^>]*>/,         Q("<meta property=\"og:url\" content=\"${escape(url)}\">"))
            .replaceFirst(/<meta property="og:type"[^>]*>/,        Q("<meta property=\"og:type\" content=\"article\">"))
            // Batch 783 — per-loadout canonical URL.
            .replaceFirst(/<link rel="canonical"[^>]*>/,           Q("<link rel=\"canonical\" href=\"${escape(url)}\">"))
            .replaceFirst(/<meta name="twitter:title"[^>]*>/,      Q("<meta name=\"twitter:title\" content=\"${escape(title)}\">"))
            .replaceFirst(/<meta name="twitter:description"[^>]*>/, Q("<meta name=\"twitter:description\" content=\"${escape(desc)}\">"))
            .replaceFirst(/<meta name="twitter:image"[^>]*>/,       Q("<meta name=\"twitter:image\" content=\"${escape(image)}\">"))
            .replaceFirst(/<meta name="description"[^>]*>/,        Q("<meta name=\"description\" content=\"${escape(desc)}\">"))

        // og:image:alt — screen-reader story for the social-card image,
        // mirroring the item + stall paths. The static template ships no
        // placeholder for it (every other route would render an empty
        // meta), so inject it inline before </head> alongside the JSON-LD.
        def ogLoadoutTags = new StringBuilder()
        ogLoadoutTags.append('  <meta property="og:image:alt" content="').append(escape(name)).append(' loadout on SkinBox">\n')

        // schema.org CreativeWork JSON-LD — gives Google / Bing a
        // structured snapshot of the loadout so loadout URLs can surface
        // as rich results. Kept minimal (same spirit as the Store JSON-LD
        // on /stall): name, URL, image, author, and a brand pointer back
        // to SkinBox. No per-slot ItemList — the slot image pipeline for
        // previews doesn't exist yet, so an ItemList would have no items.
        def jsonLd = loadoutJsonLd(loadout, url, image)
        out = out.replace('</head>',
            "${ogLoadoutTags}  <script type=\"application/ld+json\">${jsonLd}</script>\n</head>")

        ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            // `private` not `public` — every OG response carries the
            // CsrfFilter's Set-Cookie header for first-load users (it
            // mints `sbox_csrf` if no cookie is present). A shared
            // cache like Cloudflare that captured the response would
            // serve user A's cookie to user B on the cache hit,
            // collapsing two visitors onto the same CSRF token and
            // defeating per-user CSRF protection downstream. Per-user
            // browser cache (`private`) still gets the 5-min benefit
            // for refreshes; only the multi-tenant shared cache is
            // excluded — and crawlers, the primary cacheable consumer
            // here, don't carry cookies anyway.
            .header('Cache-Control', 'private, max-age=300')
            .body(out)
    }

    /**
     * Build a schema.org CreativeWork JSON-LD string for a loadout.
     * Lives as a private method so the test suite can exercise the
     * edge cases without needing the full loadoutPage round-trip.
     */
    private static String loadoutJsonLd(com.sboxmarket.model.Loadout loadout, String url, String image) {
        def sb = new StringBuilder()
        sb.append('{"@context":"https://schema.org","@type":"CreativeWork"')
        sb.append(',"name":"').append(jsonEscape(loadout.name ?: 'Loadout')).append('"')
        sb.append(',"url":"').append(jsonEscape(url)).append('"')
        sb.append(',"image":"').append(jsonEscape(image)).append('"')
        if (loadout.ownerName) {
            sb.append(',"author":{"@type":"Person","name":"').append(jsonEscape(loadout.ownerName)).append('"}')
        }
        sb.append(',"brand":{"@type":"Brand","name":"SkinBox"}')
        sb.append('}')
        sb.toString()
    }

    /**
     * Batch 808 — rewrite the shell template to emit `noindex, nofollow`
     * on authenticated / personal routes. robots.txt already disallows
     * these paths, but defense-in-depth says the shipped HTML should
     * also carry a proper meta directive so a misbehaving crawler
     * (ignoring robots.txt) still doesn't index the empty shell. The
     * shell is personalised client-side by the SPA, so indexing any of
     * these URLs would capture a signed-out empty-state anyway.
     *
     * Covered routes: /profile, /wallet, /cart, /sell, /me/stall,
     * /offers, /buy-orders, /notifications, /watchlist, /support,
     * /settings, /admin, /csr. Also /loadout list view (but NOT the
     * individual /loadout/{id} which has its own indexable OG page).
     */
    @GetMapping(value = ['/profile', '/profile/',
                         '/wallet', '/wallet/',
                         '/cart', '/cart/',
                         '/sell', '/sell/',
                         '/me/stall', '/me/stall/',
                         '/offers', '/offers/',
                         '/buy-orders', '/buy-orders/',
                         '/notifications', '/notifications/',
                         '/watchlist', '/watchlist/',
                         '/support', '/support/',
                         '/settings', '/settings/',
                         '/admin', '/admin/',
                         '/csr', '/csr/'],
                produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> privateShell() {
        refreshTemplate()
        if (template == null) return ResponseEntity.status(404).body('')
        def Q = java.util.regex.Matcher.&quoteReplacement
        def out = template
            .replaceFirst(/<meta name="robots"[^>]*>/,
                          Q('<meta name="robots" content="noindex, nofollow">'))
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .header('Cache-Control', 'no-cache, must-revalidate')
            .body(out)
    }

    /**
     * Serve the SPA shell for a missing entity URL with a 200 status and
     * a `noindex, nofollow` robots meta. The SPA's client-side 404 panel
     * still renders the branded "stall/loadout/item not found" empty
     * state. Why 200 instead of 404: the browser console logs every
     * document-level non-2xx as "Failed to load resource", which read
     * as a real bug to anyone tailing the console on a routine dead-link
     * landing. SEO impact is preserved because `robots="noindex"` is a
     * sufficient de-indexing signal for crawlers; we no longer need the
     * 404 status as a secondary signal. Cache-Control is no-cache so a
     * later sync that resurrects the entity isn't masked by a stale 404
     * preview in the CDN.
     */
    private ResponseEntity<String> notFoundSpaShell() {
        refreshTemplate()
        if (template == null) return ResponseEntity.status(404).body('')
        def Q = java.util.regex.Matcher.&quoteReplacement
        def out = template
            .replaceFirst(/<meta name="robots"[^>]*>/,
                          Q('<meta name="robots" content="noindex, nofollow">'))
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header('Cache-Control', 'no-cache, must-revalidate')
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
