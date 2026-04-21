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
     *  index.html has, which is all the regex replacer cares about. */
    private static final String TEMPLATE = '''<!DOCTYPE html>
<html>
<head>
  <title>SkinBox — s&box Skin Marketplace</title>
  <meta name="description" content="Generic marketplace tagline">
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

    // ── /item/{id} ───────────────────────────────────────────────

    def "itemPage rewrites OG tags with the item's name + floor price"() {
        given:
        def item = new Item(id: 7L, name: 'Wizard Hat', lowestPrice: new BigDecimal('4.98'),
            imageUrl: 'https://example.com/wizard.png')
        itemRepository.findById(7L) >> Optional.of(item)

        when:
        def response = controller.itemPage('7')
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
        def body = controller.itemPage('7').body as String

        then:
        body.contains('Out of stock')
        body.contains('og:image" content="https://skinbox.test/img/favicon-512.png"')
    }

    def "itemPage returns the raw template when item id is unknown"() {
        given:
        itemRepository.findById(999L) >> Optional.empty()

        when:
        def resp = controller.itemPage('999')

        then:
        // Batch 967 — 404 status so crawlers drop the URL from their
        // index. Body stays the SPA shell so the client still renders
        // the friendly not-found modal.
        resp.statusCodeValue == 404
        def body = resp.body as String
        body.contains('SkinBox — s&box Skin Marketplace')
        body.contains('og:title" content="SkinBox"')
    }

    def "itemPage returns the raw template when id is not numeric"() {
        when:
        def resp = controller.itemPage('not-a-number')

        then:
        // No repo call — the NumberFormatException short-circuits.
        0 * itemRepository.findById(_)
        // Batch 967 — 404 for garbage-id pages too.
        resp.statusCodeValue == 404
        def body = resp.body as String
        body.contains('SkinBox — s&box Skin Marketplace')
    }

    // ── JSON-LD Product schema injection ────────────────────────

    def "itemPage injects schema.org Product JSON-LD with price + availability for in-stock items"() {
        given:
        def item = new Item(id: 7L, name: 'Wizard Hat', category: 'Hats',
            lowestPrice: new BigDecimal('4.98'), imageUrl: 'https://example.com/wizard.png')
        itemRepository.findById(7L) >> Optional.of(item)

        when:
        def body = controller.itemPage('7').body as String

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
        def body = controller.itemPage('7').body as String

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
        def body = controller.itemPage('7').body as String

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
        def body = controller.stallPage('42').body as String

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
        def body = controller.stallPage('42').body as String

        then:
        body.contains('"@type":"Store"')
        body.contains('"name":"AliceBob\'s Stall"')
        body.contains('"url":"https://skinbox.test/stall/42"')
        body.contains('"image":"https://steamcdn.example/ab.jpg"')
        body.contains('"brand":{"@type":"Brand","name":"SkinBox"}')
    }

    def "stallPage falls back to template when user is unknown"() {
        given:
        steamUserRepository.findById(999L) >> Optional.empty()

        when:
        def resp = controller.stallPage('999')

        then:
        // Batch 967 — 404 for unknown stall ids so crawlers drop them.
        resp.statusCodeValue == 404
        (resp.body as String).contains('og:title" content="SkinBox"')
    }

    def "stallPage returns 404 for non-numeric id (batch 967)"() {
        when:
        def resp = controller.stallPage('notanumber')

        then:
        0 * steamUserRepository.findById(_)
        resp.statusCodeValue == 404
    }

    def "loadoutPage returns 404 when id is unknown OR loadout is PRIVATE (batch 967)"() {
        given:
        loadoutRepository.findById(100L) >> Optional.empty()
        loadoutRepository.findById(101L) >> Optional.of(
            new com.sboxmarket.model.Loadout(id: 101L, name: 'Secret', visibility: 'PRIVATE'))

        when:
        def missingResp = controller.loadoutPage('100')
        def privateResp = controller.loadoutPage('101')

        then:
        // Enumeration-defence: unknown and PRIVATE both return 404 so an
        // attacker can't distinguish "id exists but private" from "id
        // doesn't exist" by HTTP status.
        missingResp.statusCodeValue == 404
        privateResp.statusCodeValue == 404
    }

    // ── /faq ─────────────────────────────────────────────────────

    def "faqPage injects FAQPage JSON-LD with all Q&A pairs"() {
        when:
        def body = controller.faqPage().body as String

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
        def body = controller.loadoutPage('11').body as String

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
        def body = controller.loadoutPage('11').body as String

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
        def body = controller.loadoutPage('11').body as String

        then:
        // Falls back to default template — none of the private data appears.
        !body.contains('Secret Build')
        !body.contains('9999.99')
        body.contains('og:title" content="SkinBox"')
    }

    def "loadoutPage falls back to template when loadout is unknown"() {
        given:
        loadoutRepository.findById(999L) >> Optional.empty()

        when:
        def body = controller.loadoutPage('999').body as String

        then:
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
        def body = controller.itemPage('7').body as String

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
        def body = controller.itemPage('7').body as String

        then:
        // Pre-fix this threw "No group 4" because $4.00 in the replacement
        // string was interpreted as a capture-group backreference.
        noExceptionThrown()
        body.contains('$4.00')
    }
}
