package com.sboxmarket

import com.sboxmarket.controller.OpenGraphController
import com.sboxmarket.model.Item
import com.sboxmarket.model.Loadout
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.LoadoutRepository
import com.sboxmarket.repository.SteamUserRepository
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit-level coverage for the OG-tag injector. Exercises the happy path
 * (real item/user → rewritten tags), the fallback paths (unknown id,
 * unparseable id), and the escape() corner cases that have bit us
 * before (item prices containing `$`, names with HTML entities).
 */
class OpenGraphControllerSpec extends Specification {

    ItemRepository       itemRepository       = Mock()
    SteamUserRepository  steamUserRepository  = Mock()
    LoadoutRepository    loadoutRepository    = Mock()

    @Subject
    OpenGraphController controller = new OpenGraphController(
        itemRepository:      itemRepository,
        steamUserRepository: steamUserRepository,
        loadoutRepository:   loadoutRepository,
        publicUrl:           'https://skinbox.test'
    )

    /** Minimal valid SPA template — matches the shape the real
     *  index.html has, which is all the regex replacer cares about.
     *  Includes a `<meta name="robots">` so notFoundSpaShell() can flip
     *  it to `noindex, nofollow` (batch 968). */
    private static final String TEMPLATE = '''<!DOCTYPE html>
<html>
<head>
  <title>SkinBox — s&box Skin Marketplace</title>
  <meta name="description" content="Generic marketplace tagline">
  <meta name="robots" content="index, follow">
  <meta property="og:title" content="SkinBox">
  <meta property="og:description" content="Generic marketplace tagline">
  <meta property="og:image" content="https://skinbox.test/img/favicon-512.png">
  <meta property="og:url" content="https://skinbox.test">
  <meta property="og:type" content="website">
  <meta name="twitter:title" content="SkinBox">
</head>
<body></body>
</html>'''

    def setup() {
        // Inject the template directly — PostConstruct's classpath read
        // isn't available in a pure unit test. Template load is mocked.
        // PIN it: the per-handler refreshTemplate() (added so a `?v=` bump
        // reaches every route without a restart) reads the real
        // static/index.html off the test classpath and would otherwise
        // clobber this fixture with the live marketing title.
        controller.template = TEMPLATE
        controller.templatePinned = true
    }

    /**
     * Default request fixture matching the publicUrl host so the
     * existing assertions ("og:url contains https://skinbox.test/...")
     * keep working after the controller switched to deriving the base
     * from the request rather than the env var.
     */
    private org.springframework.mock.web.MockHttpServletRequest req() {
        def r = new org.springframework.mock.web.MockHttpServletRequest('GET', '/item/1')
        r.scheme = 'https'
        r.serverName = 'skinbox.test'
        r.addHeader('Host', 'skinbox.test')
        return r
    }

    // ── /item/{id} ───────────────────────────────────────────────

    def "itemPage rewrites OG tags with the item's name + floor price"() {
        given:
        def item = new Item(id: 7L, name: 'Wizard Hat', lowestPrice: new BigDecimal('4.98'),
            imageUrl: 'https://example.com/wizard.png')
        itemRepository.findById(7L) >> Optional.of(item)

        when:
        def response = controller.itemPage('7', req())
        def body = response.body as String

        then:
        response.statusCode.value() == 200
        body.contains('<title>Wizard Hat · $4.98 · SkinBox</title>')
        body.contains('og:title" content="Wizard Hat · $4.98 · SkinBox"')
        body.contains('og:url" content="https://skinbox.test/item/7"')
        body.contains('og:type" content="product"')
        body.contains('twitter:title" content="Wizard Hat · $4.98 · SkinBox"')
        body.contains('og:image" content="https://example.com/wizard.png"')
    }

    def "itemPage shows 'Out of stock' when the item has no floor price"() {
        given:
        def item = new Item(id: 7L, name: 'Wizard Hat', lowestPrice: null, imageUrl: null)
        itemRepository.findById(7L) >> Optional.of(item)

        when:
        def body = controller.itemPage('7', req()).body as String

        then:
        body.contains('Out of stock')
        body.contains('og:image" content="https://skinbox.test/img/favicon-512.png"')
    }

    def "itemPage returns 200 noindex shell when item id is unknown (batch 968)"() {
        given:
        itemRepository.findById(999L) >> Optional.empty()

        when:
        def resp = controller.itemPage('999', req())

        then:
        // Batch 968 — 200 + noindex/nofollow shell. Browser console no
        // longer logs a document-level 404 for routine dead links;
        // crawlers honour the robots meta and drop the URL anyway.
        resp.statusCodeValue == 200
        def body = resp.body as String
        body.contains('<meta name="robots" content="noindex, nofollow">')
        // Generic shell — no per-item OG mutation should have run.
        body.contains('og:title" content="SkinBox"')
    }

    def "itemPage returns 200 noindex shell when id is not numeric (batch 968)"() {
        when:
        def resp = controller.itemPage('not-a-number', req())

        then:
        // No repo call — the NumberFormatException short-circuits.
        0 * itemRepository.findById(_)
        resp.statusCodeValue == 200
        def body = resp.body as String
        body.contains('<meta name="robots" content="noindex, nofollow">')
        body.contains('og:title" content="SkinBox"')
    }

    // ── JSON-LD Product schema injection ────────────────────────

    def "itemPage injects schema.org Product JSON-LD with price + availability for in-stock items"() {
        given:
        def item = new Item(id: 7L, name: 'Wizard Hat', category: 'Hats',
            lowestPrice: new BigDecimal('4.98'), imageUrl: 'https://example.com/wizard.png')
        itemRepository.findById(7L) >> Optional.of(item)

        when:
        def body = controller.itemPage('7', req()).body as String

        then:
        body.contains('<script type="application/ld+json">')
        body.contains('"@context":"https://schema.org"')
        body.contains('"@type":"Product"')
        body.contains('"name":"Wizard Hat"')
        body.contains('"category":"Hats"')
        body.contains('"price":"4.98"')
        body.contains('"priceCurrency":"USD"')
        body.contains('"availability":"https://schema.org/InStock"')
        // Injected INSIDE the head, not after.
        body.indexOf('application/ld+json') < body.indexOf('</head>')
    }

    def "itemPage marks OutOfStock availability and omits price for items without a floor"() {
        given:
        def item = new Item(id: 7L, name: 'Rare Hat', lowestPrice: null, imageUrl: null)
        itemRepository.findById(7L) >> Optional.of(item)

        when:
        def body = controller.itemPage('7', req()).body as String

        then:
        body.contains('"availability":"https://schema.org/OutOfStock"')
        !body.contains('"price":')
    }

    // ── og:price + og:availability + og:image:alt injection ────

    def "itemPage injects og:price:amount + og:price:currency + og:availability for in-stock items"() {
        given:
        // Facebook's Open Graph Product spec recommends both og:price:* and
        // product:price:* tags. Pinterest reads product:price:*; Discord +
        // Slack key off og:price:*. Without these, share previews showed
        // a title + image but no price chip — buyers had to click through
        // to know the floor.
        def item = new Item(id: 7L, name: 'Wizard Hat', lowestPrice: new BigDecimal('4.98'),
            imageUrl: 'https://example.com/wizard.png')
        itemRepository.findById(7L) >> Optional.of(item)

        when:
        def body = controller.itemPage('7', req()).body as String

        then:
        body.contains('<meta property="og:price:amount" content="4.98">')
        body.contains('<meta property="og:price:currency" content="USD">')
        body.contains('<meta property="product:price:amount" content="4.98">')
        body.contains('<meta property="product:price:currency" content="USD">')
        body.contains('<meta property="og:availability" content="instock">')
        body.contains('<meta property="og:image:alt" content="Wizard Hat on SkinBox">')
    }

    def "itemPage drops og:price tags but keeps og:availability=oos for out-of-stock items"() {
        given:
        def item = new Item(id: 7L, name: 'Rare Hat', lowestPrice: null, imageUrl: null)
        itemRepository.findById(7L) >> Optional.of(item)

        when:
        def body = controller.itemPage('7', req()).body as String

        then:
        // No price tags when there's nothing to quote — leaving them at
        // "0.00" would mislead crawlers into showing a free-item card.
        !body.contains('og:price:amount')
        !body.contains('og:price:currency')
        body.contains('<meta property="og:availability" content="oos">')
    }

    // ── Production-host scheme upgrade ──────────────────────────

    def "resolveBaseUrl forces https when host is the production domain even if X-Forwarded-Proto is http"() {
        given:
        // Reproduces the bug: nginx hop overwrites X-Forwarded-Proto with
        // its own $scheme (`http`) because cloudflared → nginx is plain.
        // Production share previews leaked `http://skinbox.market/...`
        // even though the actual user TLS is enforced upstream.
        def r = new org.springframework.mock.web.MockHttpServletRequest('GET', '/item/3')
        r.addHeader('Host', 'skinbox.market')
        r.addHeader('X-Forwarded-Proto', 'http')
        r.scheme = 'http'

        when:
        def base = controller.resolveBaseUrl(r)

        then:
        base == 'https://skinbox.market'
    }

    def "resolveBaseUrl preserves http for local dev hosts"() {
        given:
        def r = new org.springframework.mock.web.MockHttpServletRequest('GET', '/item/3')
        r.addHeader('Host', 'localhost:8082')
        r.scheme = 'http'

        when:
        def base = controller.resolveBaseUrl(r)

        then:
        base == 'http://localhost:8082'
    }

    def "resolveBaseUrl rejects malformed X-Forwarded-Host (host-injection guard) and falls back to publicUrl"() {
        // Mirror of SitemapHostInjectionSpec for the OG surface. An
        // attacker who sets X-Forwarded-Host to a non-bare-hostname
        // value would (pre-fix) get that value spliced into og:url +
        // canonical via `${proto}://${host}`. The page-content
        // `escape()` neuters XML breakouts, but a plain `phishing.com`
        // hostname survives escape() and lands in the URL verbatim —
        // Google honours the canonical it sees, and the attacker's
        // domain gets credited as the source-of-truth for SkinBox
        // pages. Strict allowlist regex falls back to publicUrl when
        // the inbound Host fails the bare-hostname shape.
        given:
        controller.publicUrl = 'https://skinbox.market'
        def r = new org.springframework.mock.web.MockHttpServletRequest('GET', '/item/3')
        r.addHeader('X-Forwarded-Host', badHost)
        r.addHeader('X-Forwarded-Proto', 'https')
        r.scheme = 'http'

        when:
        def base = controller.resolveBaseUrl(r)

        then: 'no part of the hostile header survives — base resolves to the trusted publicUrl'
        base == 'https://skinbox.market'
        !base.contains('<')
        !base.contains('>')
        !base.contains('phishing')
        !base.contains('javascript')

        where:
        badHost << [
            'a.com</loc><loc>https://phishing.com',
            'phishing.com#@skinbox.market',
            'evil.com">attacker',
            'javascript:alert(1)',
            'skinbox.market/extra/path',
            'skinbox.market?evil=1',
            '<script>alert(1)</script>',
            'a.com\r\nX-Injected: 1',
            '  skinbox.market'
        ]
    }

    def "resolveBaseUrl rejects X-Forwarded-Proto values that aren't http or https"() {
        given:
        controller.publicUrl = 'https://skinbox.market'
        def r = new org.springframework.mock.web.MockHttpServletRequest('GET', '/item/3')
        r.addHeader('Host', 'localhost:8082')
        // Attacker-controlled. Pre-fix this would land verbatim as the
        // scheme half of `${proto}://${host}/`.
        r.addHeader('X-Forwarded-Proto', 'javascript')
        r.scheme = 'http'

        when:
        def base = controller.resolveBaseUrl(r)

        then: 'invalid proto is nulled — the proto+host branch skips → publicUrl fallback'
        // Per resolveBaseUrl: the falsy-coalesce picks up X-Forwarded-
        // Proto = 'javascript', validation nulls it, the proto+host
        // branch is skipped (proto is null), and we drop to the
        // publicUrl fallback. The important assertion is that
        // `javascript` never lands in the base URL.
        base == 'https://skinbox.market'
        !base.contains('javascript')
    }

    def "JSON-LD escapes HTML-breaking characters in item names so </script> can't be smuggled in"() {
        given:
        // Adversarial item name that would otherwise close the <script>
        // block and inject arbitrary HTML via DOM.innerHTML-ish parsing.
        def item = new Item(id: 7L, name: '</script><img src=x>', imageUrl: null,
            lowestPrice: new BigDecimal('1.00'))
        itemRepository.findById(7L) >> Optional.of(item)

        when:
        def body = controller.itemPage('7', req()).body as String

        then:
        // Extract just the payload INSIDE the <script>…</script> block
        // (the closing </script> tag is supposed to exist — we're checking
        // that no *extra* </script> appears inside the JSON payload,
        // which would let an adversarial item name break out of the
        // <script> context and inject HTML).
        def startTag = '<script type="application/ld+json">'
        def ldStart = body.indexOf(startTag) + startTag.length()
        def ldEnd   = body.indexOf('</script>', ldStart)
        def ld      = body.substring(ldStart, ldEnd)
        !ld.contains('</script>')
        !ld.contains('<img')
        // The characters survive as unicode-escaped forms.
        ld.contains('\\u003c')
    }

    // ── /stall/{id} ──────────────────────────────────────────────

    def "stallPage rewrites OG tags with the seller's display name + avatar"() {
        given:
        def user = new SteamUser(id: 42L, displayName: 'AliceBob',
            avatarUrl: 'https://steamcdn.example/ab.jpg', steamId64: '76561001')
        steamUserRepository.findById(42L) >> Optional.of(user)

        when:
        def body = controller.stallPage('42', req()).body as String

        then:
        body.contains('<title>AliceBob&#39;s Stall · SkinBox</title>')
        body.contains('og:title" content="AliceBob&#39;s Stall · SkinBox"')
        body.contains('og:url" content="https://skinbox.test/stall/42"')
        body.contains('og:type" content="profile"')
        body.contains('og:image" content="https://steamcdn.example/ab.jpg"')
    }

    def "stallPage injects schema.org Store JSON-LD with seller name + avatar"() {
        given:
        def user = new SteamUser(id: 42L, displayName: 'AliceBob',
            avatarUrl: 'https://steamcdn.example/ab.jpg', steamId64: '76561001')
        steamUserRepository.findById(42L) >> Optional.of(user)

        when:
        def body = controller.stallPage('42', req()).body as String

        then:
        body.contains('"@type":"Store"')
        body.contains('"name":"AliceBob\'s Stall"')
        body.contains('"url":"https://skinbox.test/stall/42"')
        body.contains('"image":"https://steamcdn.example/ab.jpg"')
        body.contains('"brand":{"@type":"Brand","name":"SkinBox"}')
    }

    def "stallPage returns 200 noindex shell for a banned seller — parity with SitemapController's banned-seller exclusion"() {
        given:
        // A banned seller's stall renders only a "suspended" banner; the
        // sitemap already drops banned sellers, so the page itself must
        // also be noindex — and must not emit a Store JSON-LD / canonical
        // for an effectively empty page.
        def user = new SteamUser(id: 42L, displayName: 'BadActor',
            avatarUrl: 'https://steamcdn.example/ba.jpg', steamId64: '76561002', banned: true)
        steamUserRepository.findById(42L) >> Optional.of(user)

        when:
        def resp = controller.stallPage('42', req())
        def body = resp.body as String

        then:
        resp.statusCodeValue == 200
        body.contains('<meta name="robots" content="noindex, nofollow">')
        // No per-stall OG mutation should have run — generic shell only.
        body.contains('og:title" content="SkinBox"')
        !body.contains('"@type":"Store"')
        // The banned seller's display name must not be promoted into OG tags.
        !body.contains('BadActor')
    }

    def "stallPage returns 200 noindex shell when user is unknown (batch 968)"() {
        given:
        steamUserRepository.findById(999L) >> Optional.empty()

        when:
        def resp = controller.stallPage('999', req())

        then:
        resp.statusCodeValue == 200
        def body = resp.body as String
        body.contains('<meta name="robots" content="noindex, nofollow">')
        body.contains('og:title" content="SkinBox"')
    }

    def "stallPage returns 200 noindex shell for non-numeric id (batch 968)"() {
        when:
        def resp = controller.stallPage('notanumber', req())

        then:
        0 * steamUserRepository.findById(_)
        resp.statusCodeValue == 200
        def body = resp.body as String
        body.contains('<meta name="robots" content="noindex, nofollow">')
    }

    def "loadoutPage returns 200 noindex shell when id is unknown OR loadout is PRIVATE (batch 968)"() {
        given:
        loadoutRepository.findById(100L) >> Optional.empty()
        loadoutRepository.findById(101L) >> Optional.of(
            new com.sboxmarket.model.Loadout(id: 101L, name: 'Secret', visibility: 'PRIVATE'))

        when:
        def missingResp = controller.loadoutPage('100', req())
        def privateResp = controller.loadoutPage('101', req())

        then:
        // Enumeration-defence: unknown and PRIVATE both return the same
        // 200 noindex shell so an attacker can't distinguish "id exists
        // but private" from "id doesn't exist" by HTTP status or body.
        missingResp.statusCodeValue == 200
        privateResp.statusCodeValue == 200
        def missingBody = missingResp.body as String
        def privateBody = privateResp.body as String
        missingBody.contains('<meta name="robots" content="noindex, nofollow">')
        privateBody.contains('<meta name="robots" content="noindex, nofollow">')
        // Private loadout name must not leak.
        !privateBody.contains('Secret')
    }

    // ── /faq ─────────────────────────────────────────────────────

    def "faqPage injects FAQPage JSON-LD with all Q&A pairs"() {
        when:
        def body = controller.faqPage(req()).body as String

        then:
        body.contains('<title>FAQ · SkinBox</title>')
        body.contains('"@type":"FAQPage"')
        body.contains('"@type":"Question"')
        body.contains('"@type":"Answer"')
        // Includes the most prominent marketing question as a smoke-test.
        body.contains('What is SkinBox?')
        body.contains('How do I buy something?')
    }

    // ── /loadout/{id} ────────────────────────────────────────────

    def "loadoutPage rewrites OG tags with the loadout's name, owner, and total value"() {
        given:
        def loadout = new Loadout(id: 11L, name: 'Street Runner', ownerName: 'Neo',
            visibility: 'PUBLIC', totalValue: new BigDecimal('123.45'))
        loadoutRepository.findById(11L) >> Optional.of(loadout)

        when:
        def body = controller.loadoutPage('11', req()).body as String

        then:
        body.contains('<title>Street Runner · SkinBox Loadout</title>')
        body.contains('og:title" content="Street Runner · SkinBox Loadout"')
        body.contains('og:url" content="https://skinbox.test/loadout/11"')
        body.contains('og:type" content="article"')
        body.contains('$123.45')
        body.contains('by Neo')
    }

    def "loadoutPage injects og:image:alt + CreativeWork JSON-LD for SEO parity with item + stall"() {
        given:
        // Parity gap: /item emits og:image:alt + Product JSON-LD and
        // /stall emits Store JSON-LD, but /loadout shipped neither —
        // so loadout shares had no screen-reader alt and Google had no
        // structured snapshot to surface as a rich result.
        def loadout = new Loadout(id: 11L, name: 'Street Runner', ownerName: 'Neo',
            visibility: 'PUBLIC', totalValue: new BigDecimal('123.45'))
        loadoutRepository.findById(11L) >> Optional.of(loadout)

        when:
        def body = controller.loadoutPage('11', req()).body as String

        then:
        body.contains('<meta property="og:image:alt" content="Street Runner loadout on SkinBox">')
        body.contains('"@type":"CreativeWork"')
        body.contains('"name":"Street Runner"')
        body.contains('"url":"https://skinbox.test/loadout/11"')
        body.contains('"author":{"@type":"Person","name":"Neo"}')
        body.contains('"brand":{"@type":"Brand","name":"SkinBox"}')
        body.contains('<script type="application/ld+json">')
    }

    def "loadoutPage omits value line when the loadout has zero total"() {
        given:
        def loadout = new Loadout(id: 11L, name: 'Empty Rack', ownerName: 'Alice',
            visibility: 'PUBLIC', totalValue: BigDecimal.ZERO)
        loadoutRepository.findById(11L) >> Optional.of(loadout)

        when:
        def body = controller.loadoutPage('11', req()).body as String

        then:
        !body.contains('$0.00')
        body.contains('by Alice')
    }

    def "loadoutPage never leaks details for a PRIVATE loadout"() {
        given:
        def loadout = new Loadout(id: 11L, name: 'Secret Build', ownerName: 'Neo',
            visibility: 'PRIVATE', totalValue: new BigDecimal('9999.99'))
        loadoutRepository.findById(11L) >> Optional.of(loadout)

        when:
        def resp = controller.loadoutPage('11', req())
        def body = resp.body as String

        then:
        // Returns 200 + noindex shell — none of the private data appears.
        resp.statusCodeValue == 200
        body.contains('<meta name="robots" content="noindex, nofollow">')
        !body.contains('Secret Build')
        !body.contains('9999.99')
        !body.contains('Neo')
    }

    def "loadoutPage returns 200 noindex shell when loadout is unknown (batch 968)"() {
        given:
        loadoutRepository.findById(999L) >> Optional.empty()

        when:
        def resp = controller.loadoutPage('999', req())
        def body = resp.body as String

        then:
        resp.statusCodeValue == 200
        body.contains('<meta name="robots" content="noindex, nofollow">')
        body.contains('og:title" content="SkinBox"')
    }

    // ── escape() regression cases ────────────────────────────────

    def "item name containing existing HTML entity doesn't double-escape"() {
        given:
        // Real-world source had "Mob Boss Waistcoat &amp; Shirt"
        def item = new Item(id: 7L, name: 'Mob Boss Waistcoat &amp; Shirt',
            lowestPrice: new BigDecimal('4.98'), imageUrl: null)
        itemRepository.findById(7L) >> Optional.of(item)

        when:
        def body = controller.itemPage('7', req()).body as String

        then:
        // Single-escaped, not double-escaped ("&amp;amp;" would be the bug).
        body.contains('Mob Boss Waistcoat &amp; Shirt')
        !body.contains('&amp;amp;')
    }

    def 'item price with dollar-sign character does not break the regex replacement (was IndexOutOfBoundsException)'() {
        given:
        def item = new Item(id: 7L, name: 'Luxury Hat',
            lowestPrice: new BigDecimal('4.00'), imageUrl: null)
        itemRepository.findById(7L) >> Optional.of(item)

        when:
        def body = controller.itemPage('7', req()).body as String

        then:
        // Pre-fix this threw "No group 4" because $4.00 in the replacement
        // string was interpreted as a capture-group backreference.
        noExceptionThrown()
        body.contains('$4.00')
    }

    def "Cloudflare X-Forwarded-Host overrides plain Host so social shares get the prod URL (2026-05-01)"() {
        // Same root cause as the SitemapController fix — pre-fix, OG/Twitter
        // tags emitted `og:url" content="http://localhost:8082/item/7"`
        // because APP_PUBLIC_URL was the only signal. Now we honour
        // X-Forwarded-{Proto,Host} from Cloudflare's tunnel hop.
        given:
        def item = new Item(id: 7L, name: 'Wizard Hat', lowestPrice: new BigDecimal('4.98'),
            imageUrl: 'https://example.com/wizard.png')
        itemRepository.findById(7L) >> Optional.of(item)
        def r = new org.springframework.mock.web.MockHttpServletRequest('GET', '/item/7')
        r.scheme = 'http'
        r.addHeader('Host', 'localhost:8082')
        r.addHeader('X-Forwarded-Proto', 'https')
        r.addHeader('X-Forwarded-Host', 'skinbox.market')

        when:
        def body = controller.itemPage('7', r).body as String

        then:
        body.contains('og:url" content="https://skinbox.market/item/7"')
        // Critical regression: must NOT leak localhost into a social share preview
        !body.contains('localhost:8082')
        !body.contains('http://localhost')
    }
}
