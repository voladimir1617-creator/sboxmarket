package com.sboxmarket.model

import jakarta.persistence.*

/**
 * Cluster-wide dedup ledger for {@code FraudAnalysisService.sweepAndPushFraudSignals}.
 *
 * The fraud sweeper runs on every pod via {@code @Scheduled(fixedDelay)},
 * which only serialises ticks within a single JVM. Pre-fix, every pod
 * independently computed the same HIGH-severity signal list and each
 * fanned out a bell to every admin — two pods = 2x duplicate bells per
 * HIGH signal, N pods = N×. The exact failure mode that wave 112 closed
 * for the watchlist sweeper via {@code claimForFiring}.
 *
 * One row per signature. The repository's INSERT is the authoritative
 * gate: whichever pod's INSERT lands first persists the row; the loser
 * gets a "no rows affected" reply (Postgres ON CONFLICT DO NOTHING) and
 * bails BEFORE invoking notificationService.push. A daily retention
 * sweep prunes rows older than the 24h fraud window so the table never
 * grows unbounded.
 */
@Entity
@Table(name = "fraud_signal_claims")
class FraudSignalClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id

    /** Composite key the sweeper hashes —
     *  {@code (type, userId, signatureIp, bucketedCount, dedupKey)}.
     *  Backed by a UNIQUE index so concurrent pods race the INSERT and
     *  exactly one wins. */
    @Column(nullable = false, length = 512)
    String signature

    @Column(name = "claimed_at", nullable = false)
    Long claimedAt
}
