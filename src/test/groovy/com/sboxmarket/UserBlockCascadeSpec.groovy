package com.sboxmarket

import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.UserBlock
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.repository.UserBlockRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification

/**
 * Locks in the cascade behaviour added in V68 + the corresponding
 * @ManyToOne stubs on the UserBlock entity. Two scenarios:
 *
 *   1. Deleting the BLOCKER steam_users row drops every block they
 *      created. Previously V40 declared user_blocks.blocker_user_id as
 *      a plain BIGINT NOT NULL (no FK), so a hard-delete of a banned
 *      account left phantom blocker rows pointing at a nonexistent id.
 *   2. Deleting the BLOCKED steam_users row drops every row that
 *      targeted them. Same root cause — V40 had no FK on either side.
 *
 * Exercises the live JPA → H2 path so the assertion is "the DB actually
 * cascades", not "the service code happens to call deleteByBlocker".
 * If anyone deletes the @ManyToOne stubs from UserBlock.groovy (which
 * is what drives Hibernate to emit the FK on H2) these specs fail with
 * orphan rows surviving the parent delete.
 */
@SpringBootTest
@ActiveProfiles("test")
class UserBlockCascadeSpec extends Specification {

    @Autowired SteamUserRepository steamUserRepository
    @Autowired UserBlockRepository userBlockRepository

    private SteamUser seedUser(String tag) {
        def uniq = String.valueOf(System.nanoTime()) + tag
        steamUserRepository.save(new SteamUser(
            steamId64:   "76561199" + uniq.substring(Math.max(0, uniq.length() - 9)),
            displayName: "Cascade-${tag}"
        ))
    }

    def "deleting the blocker cascades — every block they created is wiped"() {
        given:
        def blocker  = seedUser('Blocker')
        def victim1  = seedUser('V1')
        def victim2  = seedUser('V2')
        userBlockRepository.save(new UserBlock(
            blockerUserId: blocker.id, blockedUserId: victim1.id, createdAt: 1L))
        userBlockRepository.save(new UserBlock(
            blockerUserId: blocker.id, blockedUserId: victim2.id, createdAt: 2L))
        assert userBlockRepository.countByBlocker(blocker.id) == 2L

        when:
        steamUserRepository.deleteById(blocker.id)
        steamUserRepository.flush()

        then:
        // Both block rows the deleted user created are gone — no orphans
        // pointing at the now-nonexistent blocker id.
        userBlockRepository.countByBlocker(blocker.id) == 0L
        userBlockRepository.findBlockerIdsForBlocked(victim1.id) == []
        userBlockRepository.findBlockerIdsForBlocked(victim2.id) == []

        cleanup:
        steamUserRepository.deleteById(victim1.id)
        steamUserRepository.deleteById(victim2.id)
    }

    def "deleting the blocked target cascades — every row aimed at them is wiped"() {
        given:
        def target   = seedUser('Target')
        def hater1   = seedUser('H1')
        def hater2   = seedUser('H2')
        userBlockRepository.save(new UserBlock(
            blockerUserId: hater1.id, blockedUserId: target.id, createdAt: 1L))
        userBlockRepository.save(new UserBlock(
            blockerUserId: hater2.id, blockedUserId: target.id, createdAt: 2L))
        assert userBlockRepository.findBlockerIdsForBlocked(target.id).size() == 2

        when:
        steamUserRepository.deleteById(target.id)
        steamUserRepository.flush()

        then:
        // Both rows that targeted the deleted user are gone — the haters
        // don't retain dead-FK rows on their personal block lists.
        userBlockRepository.findBlockerIdsForBlocked(target.id) == []
        userBlockRepository.countByBlocker(hater1.id) == 0L
        userBlockRepository.countByBlocker(hater2.id) == 0L

        cleanup:
        steamUserRepository.deleteById(hater1.id)
        steamUserRepository.deleteById(hater2.id)
    }
}
