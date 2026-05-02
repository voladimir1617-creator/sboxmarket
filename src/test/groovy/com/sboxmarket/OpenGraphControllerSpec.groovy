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
        controller.template = TEMPLATE
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
