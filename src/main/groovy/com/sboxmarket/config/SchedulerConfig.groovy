package com.sboxmarket.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.ScheduledTaskRegistrar

/**
 * Spring Boot defaults to a single-threaded task scheduler. With the
 * marketplace's ~20 @Scheduled methods — the hot ones being
 * AuctionEventBus.heartbeat (20s), BidService.sweepExpired (30s),
 * BidService.* (120s), ListingFloorRefreshService.refreshAllFloors (60s),
 * WatchlistAlertService (5min) and TradeService.sweepPendingConfirm
 * (15min) — a single thread would let the long-running Steam syncs
 * (SteamSyncService.syncAllUsers paces ~15 min of Thread.sleep'd HTTP,
 * SteamMarketPriceService.syncPricesFromSteam ~11 min) block the auction
 * sweeper the entire time: auctions don't settle, trades don't
 * auto-release, prices don't sync.
 *
 * Fix: a multi-thread pool so a long sync can't starve the frequent
 * sweepers. 8 threads — the six hot sweepers each get a thread even
 * while BOTH long Steam syncs are simultaneously occupying one apiece.
 *
 * IMPORTANT — the scheduler is exposed as a Spring @Bean (not just
 * `new`'d inside configureTasks). A scheduler handed to the registrar
 * via setTaskScheduler() is NOT lifecycle-managed by Spring: the
 * ScheduledTaskRegistrar only destroys a scheduler it created itself.
 * An unmanaged ThreadPoolTaskScheduler is never shut down at context
 * close, so its non-daemon worker threads are hard-killed by JVM exit
 * mid-task — a money-touching sweep (TradeService.sweepPendingConfirm,
 * wallet writes) could be torn down half-finished, and the
 * InterruptedException-aware loops in SteamSyncService /
 * SteamMarketPriceService never get the interrupt they wait for.
 * Declaring it a @Bean makes Spring invoke DisposableBean.destroy() on
 * shutdown; waitForTasksToCompleteOnShutdown + awaitTerminationSeconds
 * give in-flight tasks a bounded window to drain cleanly.
 */
@Configuration
class SchedulerConfig implements SchedulingConfigurer {

    // ThreadPoolTaskScheduler implements DisposableBean, so registering it
    // as a @Bean is sufficient for Spring to call destroy() (→ shutdown())
    // at context close — that is the whole point of making it a managed
    // bean rather than a bare `new` inside configureTasks().
    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
        def pool = new ThreadPoolTaskScheduler()
        pool.poolSize = 8
        pool.threadNamePrefix = 'sbox-sched-'
        // Graceful shutdown: on context close let a running sweep finish
        // rather than interrupting it mid-transaction, but cap the wait so
        // a wedged task can't hang the JVM forever. 30s comfortably covers
        // a sweep's DB round-trips; the long Steam syncs exceed it and
        // will be interrupted — their loops catch InterruptedException and
        // break cleanly, so partial progress is already durable.
        pool.waitForTasksToCompleteOnShutdown = true
        pool.awaitTerminationSeconds = 30
        pool.initialize()
        return pool
    }

    @Override
    void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(taskScheduler())
    }
}
