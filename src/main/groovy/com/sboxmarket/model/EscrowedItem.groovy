package com.sboxmarket.model

import jakarta.persistence.*

/**
 * Custody record for a single real Steam asset (app 590830) that the
 * marketplace bot escrows on a seller's behalf.
 *
 * ── Why a side table (not columns on Listing) ─────────────────────────────
 * The asset id, the deposit/return Steam trade-offer ids, and the custody
 * lifecycle are *escrow* concerns, not listing-display concerns:
 *   - Listing is serialised to every anonymous marketplace viewer; the
 *     codebase is meticulous about NOT leaking internal fields (every
 *     sensitive column on Listing/SteamUser is @JsonIgnore'd). Keeping the
 *     bot-held assetId + offer ids off Listing keeps them off the wire by
 *     construction.
 *   - A held asset's lifecycle (PENDING_DEPOSIT → IN_CUSTODY → DELIVERED /
 *     RETURNED) is independent of a single Listing row, and mirrors the
 *     side-table pattern already chosen for {@link SteamDeliveryAttempt}
 *     (per-trade offer tracking), keeping the whole bot-escrow subsystem
 *     self-contained and the Listing / Trade entities untouched.
 *
 * One row per listing (the listing that triggered the deposit). When a
 * listing is bought, {@link com.sboxmarket.service.SteamDeliveryService}
 * resolves the held assetId from the row keyed by the trade's listingId to
 * send the real item to the buyer. When a listing is cancelled / expires /
 * goes unsold, the bot sends the held asset back to the seller and the row
 * flips to RETURNED.
 *
 * Backed by the escrowed_items table (Flyway V210). This table never touches
 * wallet balances — money/escrow release stays on `trades`, owned by
 * TradeService.
 */
@Entity
@Table(name = "escrowed_items")
class EscrowedItem {

    /** Custody lifecycle. */
    static final String PENDING_DEPOSIT = 'PENDING_DEPOSIT'
    static final String IN_CUSTODY      = 'IN_CUSTODY'
    static final String DELIVERED       = 'DELIVERED'
    static final String RETURNED        = 'RETURNED'
    static final String FAILED          = 'FAILED'

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    /** The listing this deposit backs. One escrow row per listing. */
    @Column(name = "listing_id", nullable = false)
    Long listingId

    /** Seller who deposited the item (SteamUser.id). */
    @Column(name = "seller_user_id")
    Long sellerUserId

    /** The concrete Steam asset id (app 590830) being escrowed. This is the
     *  asset the seller currently owns; once IN_CUSTODY the bot holds an asset
     *  of the same item — see {@link #heldAssetId} for the bot-side id. */
    @Column(name = "asset_id", length = 64)
    String assetId

    /** Asset id as it appears in the BOT's inventory after the deposit lands.
     *  Steam preserves the asset id across most trades, but not always; this
     *  is reconciled from the bot inventory on custody-confirm and is the id
     *  the delivery leg actually sends onward. Falls back to {@link #assetId}
     *  when reconciliation hasn't run / couldn't match. */
    @Column(name = "held_asset_id", length = 64)
    String heldAssetId

    /** Item type name (market_hash_name) — used to reconcile the held asset in
     *  the bot inventory when Steam reassigns the asset id on receipt. */
    @Column(name = "market_hash_name", length = 255)
    String marketHashName

    /** Steam trade-offer id of the DEPOSIT request (bot → seller, requests the
     *  item). Polled until accepted. */
    @Column(name = "deposit_offer_id", length = 64)
    String depositOfferId

    /** Steam trade-offer id of the RETURN offer (bot → seller, gives the item
     *  back). Set only when custody → RETURNED is initiated. */
    @Column(name = "return_offer_id", length = 64)
    String returnOfferId

    /** When a return to the seller was FIRST asked for (listing cancelled /
     *  auction expired unsold / trade cancelled), whether or not the bot call
     *  succeeded. NULL — the default, and the state of every live for-sale
     *  listing — means nobody has asked for this item back.
     *
     *  This is what makes a FAILED return recoverable. Steam rate-limits trade
     *  APIs routinely, so {@code returnToSeller} returning false on a
     *  RATE_LIMITED / NOT_READY / TRANSPORT_ERROR is an ordinary event, not an
     *  exotic one — and every one of its four call sites is best-effort and
     *  discards the result. Before this field the row went back to looking
     *  exactly like a live listing, so no sweep could tell "the bot holds this
     *  because it is for sale" apart from "the bot holds this because the
     *  return failed", and a real user's real item stayed in the bot's
     *  inventory permanently and silently. */
    @Column(name = "return_requested_at")
    Long returnRequestedAt

    /** Number of return attempts made so far. Also the compare-and-swap token
     *  the multi-pod retry claim races on — the row stays IN_CUSTODY across a
     *  retry, so unlike {@code claimTimeoutPendingDeposit} there is no state
     *  transition to claim, and the attempt counter stands in for one. */
    @Column(name = "return_attempts", nullable = false)
    Integer returnAttempts = 0

    /** PENDING_DEPOSIT, IN_CUSTODY, DELIVERED, RETURNED, FAILED. */
    @Column(name = "custody_state", length = 24, nullable = false)
    String custodyState = PENDING_DEPOSIT

    /** Last error surfaced by the bot/sidecar on this custody record. */
    @Column(name = "last_error", length = 500)
    String lastError

    @Column(name = "created_at", nullable = false)
    Long createdAt = System.currentTimeMillis()

    @Column(name = "updated_at", nullable = false)
    Long updatedAt = System.currentTimeMillis()
}
