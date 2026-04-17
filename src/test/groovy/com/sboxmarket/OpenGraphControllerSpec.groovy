package com.sboxmarket

import com.sboxmarket.controller.OpenGraphController
import com.sboxmarket.model.Item
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.ItemRepository
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

    @Subject
    OpenGraphController controller = new OpenGraphController(
        itemRepository:      itemRepository,
        steamUserRepository: steamUserRepository,
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
        def body = controller.itemPage('999').body as String

        then:
        // Unchanged — original tags, no rewrite happened.
        body.contains('SkinBox — s&box Skin Marketplace')
        body.contains('og:title" content="SkinBox"')
    }

    def "itemPage returns the raw template when id is not numeric"() {
        when:
        def body = controller.itemPage('not-a-number').body as String

        then:
        // No repo call — the NumberFormatException short-circuits.
        0 * itemRepository.findById(_)
        body.contains('SkinBox — s&box Skin Marketplace')
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

    def "stallPage falls back to template when user is unknown"() {
        given:
        steamUserRepository.findById(999L) >> Optional.empty()

        when:
        def body = controller.stallPage('999').body as String

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
