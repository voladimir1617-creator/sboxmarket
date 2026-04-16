package com.sboxmarket

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.CommandLineRunner
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.EnableScheduling
import com.sboxmarket.service.SeedService
import groovy.util.logging.Slf4j

@SpringBootApplication
@EnableScheduling
@Slf4j
class SboxMarketApplication {

    static void main(String[] args) {
        def ctx = SpringApplication.run(SboxMarketApplication, args)
        // Read the actual server.port from the Spring environment rather
        // than hardcoding 8080 — the startup banner was misleading when a
        // test or docker deploy overrode SERVER_PORT.
        def env = ctx.getBean(Environment)
        def port = env.getProperty('server.port', '8080')
        def activeProfiles = env.activeProfiles?.join(',') ?: 'default'
        log.info("🎮 SBoxMarket started — http://localhost:${port} (profiles: ${activeProfiles})")
    }

    @Bean
    CommandLineRunner onStartup(SeedService seedService) {
        return { args -> seedService.seed() } as CommandLineRunner
    }
}
