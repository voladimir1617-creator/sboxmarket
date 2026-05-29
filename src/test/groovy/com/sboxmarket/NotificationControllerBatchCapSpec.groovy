package com.sboxmarket

import com.sboxmarket.controller.NotificationController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.service.NotificationService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

/**
 * Pins the controller-side input cap on `/read-batch` and `/delete-batch`.
 *
 * Before this guard, the controller iterated and `Long.valueOf`-parsed
 * EVERY entry in `body.ids` before handing the list to the service, and
 * only then did the service trim to the first 500. A hostile authenticated
 * client posting 250k numeric ids (the rough headroom under the 2MB
 * BodySizeLimitFilter cap) forced ~250k Long.valueOf calls and a
 * findAll/unique double-pass over the full list per request before any
 * cap took effect. The fix caps the raw collection at MAX_BATCH_IDS
 * (500) BEFORE parsing — the dropped suffix is never seen.
 *
 * These specs exercise the controller in isolation with a mocked service:
 * the assertion is the SIZE of the list handed to the service (proves
 * the cap landed at the controller, not later in the call chain).
 */
class NotificationControllerBatchCapSpec extends Specification {

    NotificationService notificationService = Mock()

    @Subject
    NotificationController controller = new NotificationController(
        notificationService: notificationService
    )

    HttpServletRequest req = Mock()
    HttpSession        ses = Mock()

    private void authedSession(long uid = 100L) {
        1 * req.session >> ses
        1 * ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> uid
    }

    def "MAX_BATCH_IDS is the documented 500 cap"() {
        expect: 'pin the constant so anyone moving the service cap also moves this'
        NotificationController.MAX_BATCH_IDS == 500
    }

    def "readBatch() caps a hostile 100k-id payload at MAX_BATCH_IDS BEFORE parsing"() {
        given: 'a hostile authenticated caller ships 100k ids — well under 2MB raw'
        authedSession(100L)
        List<Long> capturedIds = null
        1 * notificationService.markReadByIds(100L, _) >> { args ->
            capturedIds = args[1]
            0
        }
        def hostileIds = (1L..100_000L) as List

        when:
        def resp = controller.readBatch([ids: hostileIds], req)

        then: 'service sees only the first 500 ids, not 100k'
        capturedIds.size() == NotificationController.MAX_BATCH_IDS
        capturedIds.first() == 1L
        capturedIds.last() == 500L
        resp.body == [flipped: 0]
    }

    def "deleteBatch() caps a hostile 100k-id payload at MAX_BATCH_IDS BEFORE parsing"() {
        given:
        authedSession(100L)
        List<Long> capturedIds = null
        1 * notificationService.deleteReadByIds(100L, _) >> { args ->
            capturedIds = args[1]
            0
        }
        def hostileIds = (1L..100_000L) as List

        when:
        def resp = controller.deleteBatch([ids: hostileIds], req)

        then:
        capturedIds.size() == NotificationController.MAX_BATCH_IDS
        capturedIds.first() == 1L
        capturedIds.last() == 500L
        resp.body == [deleted: 0]
    }

    def "readBatch() cap drops the SUFFIX, not random entries — first 500 ids preserved in order"() {
        given: 'mix stringified and Long ids so the cap-then-parse path is exercised'
        authedSession(100L)
        List<Long> capturedIds = null
        1 * notificationService.markReadByIds(100L, _) >> { args ->
            capturedIds = args[1]
            0
        }
        // First 500 are stringified longs '1'..'500'; the next 100 we want
        // dropped completely so they never become Long objects.
        def payload = []
        (1L..500L).each { payload << it.toString() }
        (501L..600L).each { payload << it.toString() }

        when:
        controller.readBatch([ids: payload], req)

        then: 'service got exactly the first 500, in order'
        capturedIds.size() == 500
        capturedIds.first() == 1L
        capturedIds.last() == 500L
    }

    def "readBatch() under-cap payloads still parse the whole list"() {
        given: 'small legitimate payloads must not regress — full list reaches the service'
        authedSession(100L)
        List<Long> capturedIds = null
        1 * notificationService.markReadByIds(100L, _) >> { args ->
            capturedIds = args[1]
            5
        }

        when:
        def resp = controller.readBatch([ids: [1L, 2L, 3L, 4L, 5L]], req)

        then:
        capturedIds == [1L, 2L, 3L, 4L, 5L]
        resp.body == [flipped: 5]
    }

    def "deleteBatch() under-cap payloads still parse the whole list"() {
        given:
        authedSession(100L)
        1 * notificationService.deleteReadByIds(100L, [1L, 2L, 3L]) >> 3

        when:
        def resp = controller.deleteBatch([ids: [1L, 2L, 3L]], req)

        then:
        resp.body == [deleted: 3]
    }

    def "readBatch() cap still drops malformed tokens within the kept window"() {
        given: 'first 500 entries include some bad tokens — they must still be filtered'
        authedSession(100L)
        List<Long> capturedIds = null
        1 * notificationService.markReadByIds(100L, _) >> { args ->
            capturedIds = args[1]
            0
        }
        def payload = ['1', 'bad', null, '2', '3']
        // Pad to a total of 600 ids so we exercise both the cap AND the
        // malformed-token drop in the same call.
        (4L..598L).each { payload << it.toString() }

        when:
        controller.readBatch([ids: payload], req)

        then: 'kept the first 500 raw entries — minus null + "bad" = 498 Longs'
        capturedIds.size() == 498
        capturedIds.first() == 1L
        capturedIds.contains(2L)
        capturedIds.contains(3L)
    }
}
