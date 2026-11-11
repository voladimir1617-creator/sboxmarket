package com.sboxmarket.service

import com.sboxmarket.model.Listing
import com.sboxmarket.repository.SteamUserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

/**
 * "Anonymous shop": a seller who turns it on (SteamUser.anonymousShop) is
 * shown on PUBLIC listing surfaces under a neutral label, with no name, no
 * avatar and no stall link. Only the listing JSON a visitor reads changes:
 * the listing rows themselves, trades, staff and admin views, disputes and
 * the buyer's own trade record keep the real counterparty.
 *
 * A masked listing carries `sellerAnonymous: true`, `sellerName` set to
 * {@link #LABEL}, no avatar, no presence or review aggregate (those would
 * point back at one seller), and `sellerUserId` = {@link #MASKED_SELLER_ID}.
 * That id is 0, not null, on purpose: across the client a null seller means
 * "SkinBox's own inventory, delivered in-platform", which an anonymous
 * seller's listing is not; it is a person's listing that needs the buyer's
 * trade URL. 0 is falsy in the SPA, so every stall link, avatar and trust
 * lookup guarded by `sellerUserId ?` stays off. The seller always sees
 * their own listings unmasked.
 *
 * Rows are mutated in place. Callers pass the DETACHED listings a
 * controller is about to serialise (open-in-view is off, so nothing writes
 * them back), as the existing seller-rating decoration already does.
 */
@Service
class AnonymousShopService {

    static final String LABEL = 'Anonymous seller'
    static final Long MASKED_SELLER_ID = 0L

    @Autowired SteamUserRepository steamUserRepository

    /** The ids among `sellerIds` that have Anonymous shop on. */
    Set<Long> anonymousAmong(Collection<Long> sellerIds) {
        def ids = (sellerIds ?: []).findAll { it != null && it != MASKED_SELLER_ID } as Set<Long>
        if (ids.isEmpty()) return [] as Set<Long>
        (steamUserRepository.findAnonymousShopIds(ids) ?: []) as Set<Long>
    }

    boolean isAnonymous(Long sellerId) {
        sellerId != null && !anonymousAmong([sellerId]).isEmpty()
    }

    /** Masks every listing whose seller is anonymous and is not the viewer. */
    List<Listing> mask(List<Listing> rows, Long viewerId) {
        if (rows == null || rows.isEmpty()) return rows
        def anon = anonymousAmong(rows*.sellerUserId)
        if (anon.isEmpty()) return rows
        rows.each { l ->
            if (l != null && l.sellerUserId != null && anon.contains(l.sellerUserId) && l.sellerUserId != viewerId) {
                maskOne(l)
            }
        }
        rows
    }

    Listing mask(Listing l, Long viewerId) {
        if (l == null) return l
        mask([l], viewerId)
        l
    }

    /** Same mask for sale rows built as maps (`sellerName`, `sellerUserId`). */
    List<Map> maskSaleRows(List<Map> rows, Long viewerId) {
        if (rows == null || rows.isEmpty()) return rows
        def anon = anonymousAmong(rows.collect { it.sellerUserId as Long })
        if (anon.isEmpty()) return rows
        rows.collect { r ->
            Long sid = r.sellerUserId as Long
            (sid != null && anon.contains(sid) && sid != viewerId)
                ? (r + [sellerName: LABEL, sellerUserId: MASKED_SELLER_ID, sellerAnonymous: true])
                : r
        }
    }

    private static void maskOne(Listing l) {
        l.sellerName = LABEL
        l.sellerAvatar = null
        l.sellerUserId = MASKED_SELLER_ID
        l.assetId = null
        l.sellerAnonymous = true
        l.sellerLastSeenAt = null
        l.sellerRating = null
        l.sellerReviewCount = null
    }
}
