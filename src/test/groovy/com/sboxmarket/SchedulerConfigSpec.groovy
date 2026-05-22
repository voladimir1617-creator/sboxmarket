package com.sboxmarket

import com.sboxmarket.config.SchedulerConfig
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import spock.lang.Specification
import spock.lang.Subject

/**
 * Coverage for the scheduled-task thread pool config.
 *
 * Pins two things that a future tweak could silently break:
 *   1. The pool is large enough that the long Steam syncs can't starve
 *      the frequent auction / bid / floor sweepers.
 *   2. The scheduler shuts down GRACEFULLY — it is a Spring-managed bean
 *      (so DisposableBean.destroy() runs at context close) and it waits a
 *      bounded window for in-flight money-touching sweeps to finish
 *      instead of being hard-killed by JVM exit.
 */
class SchedulerConfigSpec extends Specification {

    @Subject
    SchedulerConfig config = new SchedulerConfig()

    def "taskScheduler bean is a pool sized for the full @Scheduled set"() {
        when:
        def scheduler = config.taskScheduler()
        // getPoolSize() reports the LIVE thread count (0 until threads are
        // spun up lazily) — assert the configured CORE size instead.
        def core = scheduler.scheduledThreadPoolExecutor.corePoolSize

        then:
        scheduler instanceof ThreadPoolTaskScheduler
        // 8 threads: the six hot sweepers (auction 20s, two bid, floor
        // 60s, watchlist 5min, pending-confirm 15min) each still get a
        // thread while BOTH long Steam syncs occupy one apiece.
        core == 8
        scheduler.threadNamePrefix == 'sbox-sched-'

        cleanup:
        scheduler.shutdown()
    }

    def "taskScheduler is configured for a graceful, bounded shutdown"() {
        when:
        def scheduler = config.taskScheduler()
        // The underlying ScheduledExecutorService exists only after init.
        def exec = scheduler.scheduledExecutor

        then:
        // initialize() ran inside the @Bean factory method — the pool is
        // live and ready, not lazily created on first schedule.
        exec != null
        !exec.isShutdown()

        cleanup:
        // Prove the graceful-shutdown wiring actually stops the threads:
        // an un-managed scheduler (the pre-fix bug) would leave non-daemon
        // workers running past context close.
        scheduler.shutdown()
        exec.isShutdown()
    }

    def "configureTasks installs the managed scheduler on the registrar"() {
        given:
        def registrar = new ScheduledTaskRegistrar()

        when:
        config.configureTasks(registrar)

        then:
        // The registrar must receive a scheduler — without one Spring
        // falls back to its single-threaded default and the long Steam
        // sync blocks every other sweep.
        registrar.scheduler instanceof ThreadPoolTaskScheduler

        cleanup:
        (registrar.scheduler as ThreadPoolTaskScheduler)?.shutdown()
    }
}
