package com.sboxmarket

import com.sboxmarket.controller.DatabaseController
import com.sboxmarket.model.Item
import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.repository.ListingRepository
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the public catalogue browse endpoint. The controller
 * is a thin router — all the interesting behavior sits in the input-
 * sanitisation layer:
 *
 *   - `q` / `category` / `rarity` null-byte stripping + length caps
 *     (100 / 40 / 40). A 100 kB `q=AAAAA…` must not burn a full scan.
 *   - `search` alias falls through when canonical `q` is blank.
 *   - Category + rarity canonicalisation (case-insensitive, unknown
 *     value → 'All').
 *   - Sort whitelist with 'rarest' as the default; unknown sort →
 *     'rarest'. Case-insensitive normalisation.
 *   - Price-range sanitiser: negative and above-$100k values → null,
 *     min>max auto-swapped.
 *   - `limit` clamped to 1..500, `offset` ≥ 0.
 *   - `listedOnly=true` routes to `searchListedCatalogue()`;
 *     everything else uses `searchCatalogue()`.
 *   - Response envelope `{items, total, limit, offset, indexed}` +
 *     public 60s cache header.
 *
 * Batch 1068 — added to close the coverage gap identified by walking
 * the controller list vs. the test directory.
 */
class DatabaseControllerSpec extends Specification {

    ItemRepository    itemRepository    = Mock()
    ListingRepository listingRepository = Mock()

    @Subject
    DatabaseController controller = new DatabaseController(
        itemRepository   : itemRepository,
        listingRepository: listingRepository
    )

    private PageImpl<Item> pageOf(List<Item> items, long total = items.size()) {
        new PageImpl<>(items, PageRequest.of(0, Math.max(items.size(), 1)), total)
    }

    def "default invocation returns {items, total, limit, offset, indexed} envelope"() {
        given:
        def rows = [new Item(id: 1L)]
        1 * itemRepository.searchCatalogue('', '', '', null, null, _ as Pageable) >> pageOf(rows, 42L)
        1 * itemRepository.count() >> 80L

        when:
        def resp = controller.list(null, null, 'All', 'All', 'rarest', false, null, null, 60, 0)

        then:
        resp.body.items == rows
        resp.body.total == 42L
        resp.body.limit == 60
        resp.body.offset == 0
        resp.body.indexed == 80L
    }

    def "carries public 60s cache header for CDN fan-out"() {
        given:
        1 * itemRepository.searchCatalogue(_, _, _, _, _, _) >> pageOf([])
        _ * itemRepository.count() >> 0L

        when:
        def resp = controller.list(null, null, 'All', 'All', 'rarest', false, null, null, 60, 0)

        then:
        def cc = resp.headers.getFirst('Cache-Control')
        cc?.contains('public')
        cc?.contains('max-age=60')
    }

    def "falls back to `search` alias when canonical `q` is blank"() {
        given:
        1 * itemRepository.searchCatalogue('hat', '', '', null, null, _) >> pageOf([])
        _ * itemRepository.count() >> 0L

        when:
        controller.list('', 'hat', 'All', 'All', 'rarest', false, null, null, 60, 0)

        then:
        true  // mock verifies the fallthrough value
    }

    def "uses canonical `q` when both q and search are provided"() {
        given:
        1 * itemRepository.searchCatalogue('hat', '', '', null, null, _) >> pageOf([])
        _ * itemRepository.count() >> 0L

        when:
        controller.list('hat', 'IGNORED', 'All', 'All', 'rarest', false, null, null, 60, 0)

        then:
        true
    }

    def "strips null bytes from q / category / rarity"() {
        given:
        1 * itemRepository.searchCatalogue('hat', 'Hats', '', null, null, _) >> pageOf([])
        _ * itemRepository.count() >> 0L

        when: "caller sends embedded \\u0000 — must not reach the JPQL query"
        controller.list('h\u0000at', null, 'Hat\u0000s', 'All', 'rarest', false, null, null, 60, 0)

        then:
        true  // mock verifies the null byte was scrubbed
    }

    def "caps `q` to 100 chars to bound a crafted 100KB payload"() {
        given:
        String clipped = null
        1 * itemRepository.searchCatalogue(_, _, _, _, _, _) >> { args ->
            clipped = args[0]
            pageOf([])
        }
        _ * itemRepository.count() >> 0L

        when:
        controller.list('x' * 10_000, null, 'All', 'All', 'rarest', false, null, null, 60, 0)

        then:
        clipped.length() == 100
    }

    def "unrecognised sort → 'rarest' (default asc by supply)"() {
        given:
        Pageable capturedPage = null
        1 * itemRepository.searchCatalogue(_, _, _, _, _, _) >> { args ->
            capturedPage = args[5]
            pageOf([])
        }
        _ * itemRepository.count() >> 0L

        when:
        controller.list(null, null, 'All', 'All', 'not-a-sort', false, null, null, 60, 0)

        then:
        def order = capturedPage.sort.iterator().next()
        order.property == 'supply'
        order.direction == Sort.Direction.ASC
    }

    def "sort is case-insensitive (PRICE_DESC normalises to price_desc)"() {
        given:
        Pageable capturedPage = null
        1 * itemRepository.searchCatalogue(_, _, _, _, _, _) >> { args ->
            capturedPage = args[5]
            pageOf([])
        }
        _ * itemRepository.count() >> 0L

        when:
        controller.list(null, null, 'All', 'All', 'PRICE_DESC', false, null, null, 60, 0)

        then:
        def order = capturedPage.sort.iterator().next()
        order.property == 'lowestPrice'
        order.direction == Sort.Direction.DESC
    }

    def "unrecognised category → 'All' (sanitised to empty filter token)"() {
        given:
        String capturedCat = null
        1 * itemRepository.searchCatalogue(_, _, _, _, _, _) >> { args ->
            capturedCat = args[1]
            pageOf([])
        }
        _ * itemRepository.count() >> 0L

        when: 'attacker probes a 50-char category string'
        controller.list(null, null, 'x' * 50, 'All', 'rarest', false, null, null, 60, 0)

        then: '>40 char category rewritten to All, then to empty-string filter token'
        capturedCat == ''
    }

    def "canonicalises lowercase category input (Hats / HATS / hats all map to Hats)"() {
        given:
        String capturedCat = null
        1 * itemRepository.searchCatalogue(_, _, _, _, _, _) >> { args ->
            capturedCat = args[1]
            pageOf([])
        }
        _ * itemRepository.count() >> 0L

        when:
        controller.list(null, null, 'hats', 'All', 'rarest', false, null, null, 60, 0)

        then:
        capturedCat == 'Hats'
    }

    def "price sanitiser swaps min > max and drops out-of-range values"() {
        given:
        BigDecimal capMin = null
        BigDecimal capMax = null
        1 * itemRepository.searchCatalogue(_, _, _, _, _, _) >> { args ->
            capMin = args[3]
            capMax = args[4]
            pageOf([])
        }
        _ * itemRepository.count() >> 0L

        when: 'caller fat-fingered min=50, max=5 → server swaps to min=5, max=50'
        controller.list(null, null, 'All', 'All', 'rarest', false,
                        new BigDecimal('50'), new BigDecimal('5'), 60, 0)

        then:
        capMin == new BigDecimal('5')
        capMax == new BigDecimal('50')
    }

    def "drops negative prices → null"() {
        given:
        BigDecimal capMin = null
        BigDecimal capMax = null
        1 * itemRepository.searchCatalogue(_, _, _, _, _, _) >> { args ->
            capMin = args[3]
            capMax = args[4]
            pageOf([])
        }
        _ * itemRepository.count() >> 0L

        when:
        controller.list(null, null, 'All', 'All', 'rarest', false,
                        new BigDecimal('-10'), new BigDecimal('5'), 60, 0)

        then:
        capMin == null
        capMax == new BigDecimal('5')
    }

    def "drops out-of-range upper price → null"() {
        given:
        BigDecimal capMax = null
        1 * itemRepository.searchCatalogue(_, _, _, _, _, _) >> { args ->
            capMax = args[4]
            pageOf([])
        }
        _ * itemRepository.count() >> 0L

        when:
        controller.list(null, null, 'All', 'All', 'rarest', false,
                        null, new BigDecimal('999999'), 60, 0)

        then:
        capMax == null
    }

    def "clamps limit to 1..500"() {
        given:
        int seenLimit = -1
        1 * itemRepository.searchCatalogue(_, _, _, _, _, _) >> { args ->
            seenLimit = (args[5] as Pageable).pageSize
            pageOf([])
        }
        _ * itemRepository.count() >> 0L

        when:
        def resp = controller.list(null, null, 'All', 'All', 'rarest', false, null, null, 9999, 0)

        then:
        seenLimit == 500
        resp.body.limit == 500
    }

    def "clamps negative offset to 0"() {
        given:
        1 * itemRepository.searchCatalogue(_, _, _, _, _, _) >> pageOf([])
        _ * itemRepository.count() >> 0L

        when:
        def resp = controller.list(null, null, 'All', 'All', 'rarest', false, null, null, 60, -9000)

        then:
        resp.body.offset == 0
    }

    def "listedOnly=true routes to searchListedCatalogue()"() {
        given:
        1 * itemRepository.searchListedCatalogue(_, _, _, _, _, _) >> pageOf([])
        0 * itemRepository.searchCatalogue(_, _, _, _, _, _)
        _ * itemRepository.count() >> 0L

        when:
        controller.list(null, null, 'All', 'All', 'rarest', true, null, null, 60, 0)

        then:
        true
    }

    def "listedOnly=false (default) routes to searchCatalogue()"() {
        given:
        1 * itemRepository.searchCatalogue(_, _, _, _, _, _) >> pageOf([])
        0 * itemRepository.searchListedCatalogue(_, _, _, _, _, _)
        _ * itemRepository.count() >> 0L

        when:
        controller.list(null, null, 'All', 'All', 'rarest', false, null, null, 60, 0)

        then:
        true
    }

    def "non-zero offset calculates pageNumber correctly"() {
        given:
        Pageable capPageable = null
        1 * itemRepository.searchCatalogue(_, _, _, _, _, _) >> { args ->
            capPageable = args[5]
            pageOf([])
        }
        _ * itemRepository.count() >> 0L

        when: 'offset=120, limit=60 → page 2 (0-indexed)'
        controller.list(null, null, 'All', 'All', 'rarest', false, null, null, 60, 120)

        then:
        capPageable.pageNumber == 2
        capPageable.pageSize == 60
    }

    def "every sort carries a stable id-ASC tiebreaker so tie-heavy pages don't drift"() {
        given:
        Pageable capPageable = null
        1 * itemRepository.searchCatalogue(_, _, _, _, _, _) >> { args ->
            capPageable = args[5]
            pageOf([])
        }
        _ * itemRepository.count() >> 0L

        when: 'rarest sort — every seeded item shares supply 0'
        controller.list(null, null, 'All', 'All', 'rarest', false, null, null, 60, 0)

        then: 'primary sort is supply ASC, with id ASC appended as the tiebreaker'
        def orders = capPageable.sort.toList()
        orders.size() == 2
        orders[0].property == 'supply'
        orders[1].property == 'id'
        orders[1].direction == Sort.Direction.ASC
    }

    def "non-aligned offset is snapped down to a page boundary and echoed back"() {
        given:
        Pageable capPageable = null
        1 * itemRepository.searchCatalogue(_, _, _, _, _, _) >> { args ->
            capPageable = args[5]
            pageOf([])
        }
        _ * itemRepository.count() >> 0L

        when: 'offset=130, limit=60 → snaps to page 2 (offset 120)'
        def resp = controller.list(null, null, 'All', 'All', 'rarest', false, null, null, 60, 130)

        then: 'page is computed from the aligned offset and the body echoes it'
        capPageable.pageNumber == 2
        capPageable.pageSize == 60
        resp.body.offset == 120
    }

    def "whitespace-only `q` is trimmed to the empty filter token (no `LIKE '%   %'` dead grid)"() {
        given: 'a user typing only spaces into the search box must NOT filter the catalogue'
        String capturedQ = null
        1 * itemRepository.searchCatalogue(_, _, _, _, _, _) >> { args ->
            capturedQ = args[0]
            pageOf([])
        }
        _ * itemRepository.count() >> 0L

        when:
        controller.list('   ', null, 'All', 'All', 'rarest', false, null, null, 60, 0)

        then: 'trimmed away → empty-string filter token, not a `%   %` LIKE term'
        capturedQ == ''
    }

    def "leading/trailing whitespace is trimmed off a real `q` term"() {
        given:
        String capturedQ = null
        1 * itemRepository.searchCatalogue(_, _, _, _, _, _) >> { args ->
            capturedQ = args[0]
            pageOf([])
        }
        _ * itemRepository.count() >> 0L

        when: "caller sends '  hat  ' — padding must not survive into the LIKE term"
        controller.list('  hat  ', null, 'All', 'All', 'rarest', false, null, null, 60, 0)

        then: 'core term reaches the query without the surrounding spaces'
        capturedQ == 'hat'
    }

    def "padded `search` alias is trimmed when it falls through for a blank `q`"() {
        given:
        String capturedQ = null
        1 * itemRepository.searchCatalogue(_, _, _, _, _, _) >> { args ->
            capturedQ = args[0]
            pageOf([])
        }
        _ * itemRepository.count() >> 0L

        when: "q is blank so the padded `search` alias is adopted, then trimmed"
        controller.list('', '  hat  ', 'All', 'All', 'rarest', false, null, null, 60, 0)

        then:
        capturedQ == 'hat'
    }
}
