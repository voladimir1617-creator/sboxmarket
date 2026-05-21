package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.exception.NotFoundException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.UserBlock
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.UserBlockRepository
import com.sboxmarket.service.UserBlockService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit coverage for the block-list service (batch 343). Repositories
 * are mocked — no Spring context, no DB. These specs lock in the
 * idempotent / self-block-rejected / target-must-exist / capped
 * semantics the rest of the codebase relies on.
 */
class UserBlockServiceSpec extends Specification {

    UserBlockRepository userBlockRepository = Mock()
    SteamUserRepository steamUserRepository = Mock()

    @Subject
    UserBlockService service = new UserBlockService(
        userBlockRepository: userBlockRepository,
        steamUserRepository: steamUserRepository
    )

    def "block creates a new row for a first-time block"() {
        given:
        userBlockRepository.existsBlock(10L, 20L) >> false
        steamUserRepository.findById(20L) >> Optional.of(new SteamUser(id: 20L, displayName: 'Bob'))
        userBlockRepository.countByBlocker(10L) >> 0L
        userBlockRepository.save(_) >> { UserBlock b -> b.id = 1L; b }

        when:
        def block = service.block(10L, 20L)

        then:
        1 * userBlockRepository.save({ UserBlock b ->
            b.blockerUserId == 10L && b.blockedUserId == 20L && b.createdAt != null
        })
        block.blockerUserId == 10L
        block.blockedUserId == 20L
    }

    def "block is idempotent — re-blocking the same user returns the existing row without saving"() {
        given:
        def existing = new UserBlock(id: 5L, blockerUserId: 10L, blockedUserId: 20L, createdAt: 1_700_000_000_000L)
        userBlockRepository.existsBlock(10L, 20L) >> true
        userBlockRepository.findByBlocker(10L) >> [existing]

        when:
        def block = service.block(10L, 20L)

        then:
        block.is(existing)
        0 * userBlockRepository.save(_)
        // Skips the target-exists probe too — no point hitting the user repo
        // when the block already exists.
        0 * steamUserRepository.findById(_)
    }

    def "block refuses self-block with a clear 400 instead of a constraint-violation 500"() {
        when:
        service.block(10L, 10L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'CANT_BLOCK_SELF'
        0 * userBlockRepository.save(_)
    }

    def "block rejects a null blocker or blocked id with INVALID_BLOCK before any repo call"() {
        when:
        service.block(blocker, blocked)

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_BLOCK'
        0 * userBlockRepository.existsBlock(_, _)
        0 * userBlockRepository.save(_)

        where:
        blocker | blocked
        null    | 20L
        10L     | null
        null    | null
    }

    def "block accepts the last slot exactly at the cap boundary (count == 99)"() {
        given: 'caller already has 99 blocks — the 100th is still allowed'
        userBlockRepository.existsBlock(10L, 20L) >> false
        steamUserRepository.findById(20L) >> Optional.of(new SteamUser(id: 20L))
        userBlockRepository.countByBlocker(10L) >> 99L
        userBlockRepository.save(_) >> { UserBlock b -> b.id = 1L; b }

        when:
        def block = service.block(10L, 20L)

        then: 'count 99 is below MAX_PER_USER (100) — the row is written'
        1 * userBlockRepository.save(_)
        block.blockedUserId == 20L
    }

    def "block 404s when the target user doesn't exist"() {
        given:
        userBlockRepository.existsBlock(10L, 999L) >> false
        steamUserRepository.findById(999L) >> Optional.empty()

        when:
        service.block(10L, 999L)

        then:
        thrown(NotFoundException)
        0 * userBlockRepository.save(_)
    }

    def "block rejects past the per-user cap (100) with a BLOCK_LIMIT error"() {
        given:
        userBlockRepository.existsBlock(10L, 20L) >> false
        steamUserRepository.findById(20L) >> Optional.of(new SteamUser(id: 20L))
        userBlockRepository.countByBlocker(10L) >> 100L

        when:
        service.block(10L, 20L)

        then:
        def e = thrown(BadRequestException)
        e.code == 'BLOCK_LIMIT'
        0 * userBlockRepository.save(_)
    }

    def "unblock deletes the pair and returns the delete count"() {
        given:
        userBlockRepository.deleteByPair(10L, 20L) >> 1

        when:
        def n = service.unblock(10L, 20L)

        then:
        n == 1
    }

    def "unblockAll wipes every block the user owns and returns the count (batch 355)"() {
        given:
        userBlockRepository.deleteByBlocker(10L) >> 7

        when:
        def n = service.unblockAll(10L)

        then:
        n == 7
    }

    def "unblockAll short-circuits on null user id without hitting the repo"() {
        when:
        def n = service.unblockAll(null)

        then:
        n == 0
        0 * userBlockRepository.deleteByBlocker(_)
    }

    def "unblock is idempotent — returns 0 when no row existed"() {
        given:
        userBlockRepository.deleteByPair(10L, 999L) >> 0

        when:
        def n = service.unblock(10L, 999L)

        then:
        n == 0
    }

    def "unblock short-circuits on a null id without hitting the repo"() {
        when:
        def a = service.unblock(null, 20L)
        def b = service.unblock(10L, null)

        then:
        a == 0
        b == 0
        0 * userBlockRepository.deleteByPair(_, _)
    }

    def "unblockAll returns 0 when the caller's block list is already empty"() {
        given:
        userBlockRepository.deleteByBlocker(10L) >> 0

        when:
        def n = service.unblockAll(10L)

        then:
        n == 0
    }

    def "listBlocked decorates each row with the target's displayName + avatarUrl"() {
        given:
        def b1 = new UserBlock(id: 1L, blockerUserId: 10L, blockedUserId: 20L, createdAt: 1_700_000_000_000L)
        def b2 = new UserBlock(id: 2L, blockerUserId: 10L, blockedUserId: 30L, createdAt: 1_700_000_100_000L)
        userBlockRepository.findByBlocker(10L) >> [b2, b1]
        steamUserRepository.findAllById(_) >> [
            new SteamUser(id: 20L, displayName: 'Bob',   avatarUrl: 'b.png'),
            new SteamUser(id: 30L, displayName: 'Carol', avatarUrl: 'c.png')
        ]

        when:
        def rows = service.listBlocked(10L)

        then:
        rows.size() == 2
        rows[0].blockedUserId == 30L
        rows[0].displayName == 'Carol'
        rows[0].avatarUrl == 'c.png'
        rows[0].createdAt == 1_700_000_100_000L
        rows[1].blockedUserId == 20L
        rows[1].displayName == 'Bob'
    }

    def "listBlocked short-circuits on null user id without hitting the repo"() {
        when:
        def rows = service.listBlocked(null)

        then:
        rows == []
        0 * userBlockRepository.findByBlocker(_)
    }

    def "listBlocked returns an empty list when the caller has blocked nobody"() {
        given:
        userBlockRepository.findByBlocker(10L) >> []

        when:
        def rows = service.listBlocked(10L)

        then:
        rows == []
        // No second round-trip when there are no rows to decorate.
        0 * steamUserRepository.findAllById(_)
    }

    def "listBlocked tolerates a target user that no longer exists — null name/avatar, no crash"() {
        given: 'the blocked user row is present but the SteamUser is gone'
        def orphan = new UserBlock(id: 1L, blockerUserId: 10L, blockedUserId: 20L, createdAt: 1_700_000_000_000L)
        userBlockRepository.findByBlocker(10L) >> [orphan]
        steamUserRepository.findAllById(_) >> []

        when:
        def rows = service.listBlocked(10L)

        then:
        rows.size() == 1
        rows[0].blockedUserId == 20L
        rows[0].displayName == null
        rows[0].avatarUrl == null
        rows[0].createdAt == 1_700_000_000_000L
    }

    def "listBlocked caps the rendered list at MAX_PER_USER even if a race left extra rows"() {
        given: 'the repo returns 105 rows — more than the 100 cap'
        def rows105 = (1..105).collect { i ->
            new UserBlock(id: i as Long, blockerUserId: 10L,
                blockedUserId: (1000L + i), createdAt: (1_700_000_000_000L + i))
        }
        userBlockRepository.findByBlocker(10L) >> rows105
        steamUserRepository.findAllById(_) >> []

        when:
        def result = service.listBlocked(10L)

        then: 'truncated to exactly MAX_PER_USER'
        result.size() == UserBlockService.MAX_PER_USER
    }

    def "isBlocked returns false for self-pair without hitting the repo"() {
        when:
        def result = service.isBlocked(10L, 10L)

        then:
        result == false
        0 * userBlockRepository.existsBlock(_, _)
    }

    def "isBlocked returns false when either id is null"() {
        when:
        def a = service.isBlocked(null, 20L)
        def b = service.isBlocked(10L, null)
        def c = service.isBlocked(null, null)

        then:
        !a; !b; !c
        0 * userBlockRepository.existsBlock(_, _)
    }

    def "isBlocked forwards to the repo probe"() {
        given:
        userBlockRepository.existsBlock(10L, 20L) >> true

        when:
        def result = service.isBlocked(10L, 20L)

        then:
        result == true
    }

    def "blockedIdsFor returns an empty list for null user without hitting the repo"() {
        when:
        def ids = service.blockedIdsFor(null)

        then:
        ids == []
        0 * userBlockRepository.findBlockedIdsForBlocker(_)
    }

    def "blockedIdsFor forwards the id-only projection straight from the repo"() {
        given:
        userBlockRepository.findBlockedIdsForBlocker(10L) >> [20L, 30L, 40L]

        when:
        def ids = service.blockedIdsFor(10L)

        then:
        ids == [20L, 30L, 40L]
    }

    def "countBlocked forwards to the dedicated COUNT query"() {
        given:
        userBlockRepository.countByBlocker(10L) >> 7L

        when:
        def n = service.countBlocked(10L)

        then:
        n == 7L
    }

    def "countBlocked returns 0 for a null user without hitting the repo"() {
        when:
        def n = service.countBlocked(null)

        then:
        n == 0L
        0 * userBlockRepository.countByBlocker(_)
    }
}
