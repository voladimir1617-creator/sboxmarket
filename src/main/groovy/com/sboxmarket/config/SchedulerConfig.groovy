package com.sboxmarket.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.ScheduledTaskRegistrar

/**
 * Spring Boot defaults to a single-threaded task scheduler. With many
 * @Scheduled methods (BidService.sweepExpired at 30s, TradeService.
 * sweepPendingConfirm at 15min, SboxApiService.scheduledSync at 30min,
 * SteamSyncService.syncAllUsers at 20min, SteamMarketPriceService at
 * 30min, ListingFloorRefreshService at 60s), the long-running Steam
 * sync (up to 15 minutes of Thread.sleep-paced HTTP calls) blocks the
 * auction sweeper the entire time — auctions don't settle, trades
 * don't auto-release, and prices don't sync.
 *
 * Fix: 6-thread pool so every scheduled task gets its own thread and
 * none can starve the others. Bumped from 4 in the price-refresh
 * batch — the new ListingFloorRefreshService runs every 60s and we
 * don't want it queued behind the 11-minute Steam sync.
 */
@Configuration
class SchedulerConfig implements SchedulingConfigurer {

    @Override
    void configureTasks(ScheduledTaskRegistrar registrar) {
        def pool = new ThreadPoolTaskScheduler()
        pool.poolSize = 6
        pool.threadNamePrefix = 'sbox-sched-'
        pool.initialize()
        registrar.setTaskScheduler(pool)
    }
}
