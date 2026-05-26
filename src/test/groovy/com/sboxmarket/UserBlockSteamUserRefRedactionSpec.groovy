package com.sboxmarket

import com.fasterxml.jackson.databind.ObjectMapper
import com.sboxmarket.model.SteamUser
import com.sboxmarket.model.UserBlock
import spock.lang.Specification

/**
 * Pins the privacy contract on UserBlock serialization: the SteamUser
 * association stubs (`blockerRef` / `blockedRef`) are Hibernate-schema
 * plumbing — they exist only so the H2 test-profile schema gets the same
 * FK + ON DELETE CASCADE as the Postgres V68 migration. They MUST NEVER
 * reach the wire.
 *
 * Why this is a hard contract:
 *   - SteamUser has a long tail of fields without their own @JsonIgnore
 *     (email, banReason, lastSyncedAt, lastLoginAt, mutedEmailKinds,
 *     deletionRequestedAt, tradeUrl, emailNotificationsEnabled, etc.)
 *     that are only safe to leak via the controlled Map projection
 *     UserBlockService.listBlocked builds (displayName, avatarUrl,
 *     blockedUserId, createdAt).
 *   - The moment ANY endpoint returns a raw UserBlock — an admin
 *     debug endpoint, a future "who blocked me" staff lookup, a
 *     `UserBlockRepository.findAll()` reflexive dump, the GDPR export
 *     swapping its projection for the entity — Jackson would walk both
 *     LAZY proxies and emit BOTH parties' email addresses + ban reasons
 *     in plain JSON.
 *   - The block list itself is private state — even leaking displayName
 *     under the wrong session is bad, but emitting raw email addresses
 *     of every user a given user has ever blocked is GDPR-grade bad.
 *
 * A pure-Jackson check (no SpringBoot) — fastest possible regression pin.
 * Mirrors `ListingBidderIdRedactionSpec` / `ListingEndingSoonNotifiedRedactionSpec`.
 */
class UserBlockSteamUserRefRedactionSpec extends Specification {

    def "UserBlock.blockerRef and UserBlock.blockedRef never serialize to JSON"() {
        given: 'a UserBlock with both SteamUser refs hydrated to entities that carry the full PII fan-out'
        def blocker = new SteamUser(
            id:                   10L,
            steamId64:            '76561198000000010',
            displayName:          'BlockerDisplayName',
            avatarUrl:            'https://example.com/blocker.png',
            email:                'blocker-secret@example.com',
            banReason:            'flagged-for-review',
            lastLoginAt:          1_700_000_000_000L,
            lastSyncedAt:         1_700_000_100_000L,
            mutedEmailKinds:      'TRADES,AUCTIONS',
            deletionRequestedAt:  1_700_000_200_000L,
            tradeUrl:             'https://steamcommunity.com/tradeoffer/new/?partner=1&token=secret'
        )
        def blocked = new SteamUser(
            id:                   20L,
            steamId64:            '76561198000000020',
            displayName:          'BlockedDisplayName',
            avatarUrl:            'https://example.com/blocked.png',
            email:                'blocked-victim@example.com',
            banReason:            'spam-rage',
            lastLoginAt:          1_700_000_300_000L,
            lastSyncedAt:         1_700_000_400_000L,
            mutedEmailKinds:      'WATCHLIST',
            deletionRequestedAt:  1_700_000_500_000L,
            tradeUrl:             'https://steamcommunity.com/tradeoffer/new/?partner=2&token=alsosecret'
        )
        def block = new UserBlock(
            id:             1L,
            blockerUserId:  10L,
            blockedUserId:  20L,
            createdAt:      1_700_000_600_000L,
            blockerRef:     blocker,
            blockedRef:     blocked
        )
        def mapper = new ObjectMapper()

        when:
        String json = mapper.writeValueAsString(block)

        then: 'neither ref key surfaces — the SteamUser graph stays server-side'
        !json.contains('blockerRef')
        !json.contains('blockedRef')

        and: 'no PII from either side bleeds through any back channel'
        !json.contains('blocker-secret@example.com')
        !json.contains('blocked-victim@example.com')
        !json.contains('flagged-for-review')
        !json.contains('spam-rage')
        !json.contains('TRADES,AUCTIONS')
        !json.contains('WATCHLIST')
        !json.contains('partner=1&token=secret')
        !json.contains('partner=2&token=alsosecret')
        !json.contains('76561198000000010')
        !json.contains('76561198000000020')

        and: 'the public scalar id columns ARE still serialized — they are the contract'
        json.contains('"blockerUserId":10')
        json.contains('"blockedUserId":20')
        json.contains('"createdAt":1700000600000')
    }
}
