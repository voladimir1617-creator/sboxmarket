package com.sboxmarket.controller

import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.LoadoutRepository
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
    @Autowired LoadoutRepository loadoutRepository
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

    // Batch 968 — register both `/item/{id}` and `/item/{id}/` so a
    // trailing-slash URL (crawler inbound link, copy-paste, old-school
    // site convention) doesn't 404. Spring MVC's path-pattern parser
    // (default in Spring Boot 3) no longer auto-matches the trailing
    // slash variant. Same treatment on stall + loadout below.
    @GetMapping(value = ['/item/{id}', '/item/{id}/'], produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> itemPage(@PathVariable String id) {
        if (template == null) {
            // Template never loaded — let the SPA fallback handler serve it.
            return ResponseEntity.status(404).body('')
        }
        Long itemId
        try { itemId = Long.parseLong(id) }
        catch (NumberFormatException ignored) {
            // Non-numeric id → 404. Previously returned 200 with the
            // default template; Google would index /item/foo as a real
            // page forever. Body stays the SPA shell so client-side JS
            // still renders the not-found modal on the user's browser.
            return ResponseEntity.status(404).contentType(MediaType.TEXT_HTML).body(template)
        }
        def item = itemRepository.findById(itemId).orElse(null)
        if (item == null) {
            // Batch 967 — return real 404 so crawlers drop the URL
            // from their index instead of preserving a phantom page.
            // SPA still gets the template body for JS-side rendering
            // of the friendly "item not found" modal.
            return ResponseEntity.status(404).contentType(MediaType.TEXT_HTML).body(template)
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
        out = out.replace('</head>', "  <script type=\"application/ld+json\">${jsonLd}</script>\n</head>")

        ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .header('Cache-Control', 'public, max-age=300')
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
        if (item.imageUrl) {
            sb.append(',"image":"').append(jsonEscape(item.imageUrl)).append('"')
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
    ResponseEntity<String> stallPage(@PathVariable String id) {
        if (template == null) return ResponseEntity.status(404).body('')
        Long userId
        try { userId = Long.parseLong(id) }
        catch (NumberFormatException ignored) {
            // Batch 967 — 404 instead of 200 so crawlers drop the URL.
            return ResponseEntity.status(404).contentType(MediaType.TEXT_HTML).body(template)
        }
        def user = steamUserRepository.findById(userId).orElse(null)
        if (user == null) {
            return ResponseEntity.status(404).contentType(MediaType.TEXT_HTML).body(template)
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
        if (user.avatarUrl) {
            sb.append(',"image":"').append(jsonEscape(user.avatarUrl)).append('"')
        }
        sb.append(',"brand":{"@type":"Brand","name":"SkinBox"}')
        sb.append('}')
        out = out.replace('</head>', "  <script type=\"application/ld+json\">${sb}</script>\n</head>")

        ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .header('Cache-Control', 'public, max-age=300')
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
         "Click the blue Steam button in the top-right. You'll bounce to steamcommunity.com, approve the login, and land back here already authenticated. Your Steam password never touches our servers — everything goes through OpenID."],
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

    @GetMapping(value = ['/faq', '/faq/'], produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> faqPage() {
        if (template == null) return ResponseEntity.status(404).body('')
        def base = publicUrl.endsWith('/') ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl
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
            .header('Cache-Control', 'public, max-age=3600')
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
    ResponseEntity<String> loadoutPage(@PathVariable String id) {
        if (template == null) return ResponseEntity.status(404).body('')
        Long loadoutId
        try { loadoutId = Long.parseLong(id) }
        catch (NumberFormatException ignored) {
            // Batch 967 — 404 instead of 200 for non-numeric ids.
            return ResponseEntity.status(404).contentType(MediaType.TEXT_HTML).body(template)
        }
        def loadout = loadoutRepository.findById(loadoutId).orElse(null)
        if (loadout == null || loadout.visibility != 'PUBLIC') {
            // Unknown or PRIVATE → default preview, never leak loadout details.
            // Batch 967 — 404 so crawlers drop the URL. PRIVATE loadouts
            // intentionally get the same 404 as unknown ones so an
            // attacker can't enumerate private loadout ids by HTTP status.
            return ResponseEntity.status(404).contentType(MediaType.TEXT_HTML).body(template)
        }
        def base = publicUrl.endsWith('/') ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl
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

        ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .header('Cache-Control', 'public, max-age=300')
            .body(out)
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
