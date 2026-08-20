package com.sboxmarket

import org.springframework.context.annotation.Profile
import spock.lang.Specification

import java.lang.reflect.Method

/**
 * The demo catalogue must never reach production.
 *
 * `SeedService` fabricates six sellers with invented Steam IDs, GIVES THEM WALLETS, and
 * lists hundreds of items they do not own. Every guard inside it is `if (count() > 0)
 * return` — an idempotency probe, not a production gate — so on a fresh prod database
 * every count is 0 and all of it runs.
 *
 * Measured 2026-08-19 on the running app: 45 of 45 listings belonged to the six seed
 * sellers. Zero real inventory. With live Stripe keys a real buyer deposits real money,
 * buys an item that does not exist, and waits three days for an auto-cancel that refunds
 * to their WALLET, not their card.
 *
 * The wallets are deliberate: SeedService's own comment says they were added because
 * "EVERY P2P buy of a seeded listing 400'd SELLER_WALLET_MISSING". The one thing stopping
 * people buying fictional items was removed on purpose, so the gate must live at the
 * wiring point.
 */
class SeedNeverRunsInProdSpec extends Specification {

    private static Method runnerBean() {
        SboxMarketApplication.declaredMethods.find { it.name == 'onStartup' }
    }

    def "the seed runner bean exists at all"() {
        expect: "guard the guard — a renamed bean would make every assertion below vacuous"
        runnerBean() != null
    }

    def "the seed runner is excluded from the prod profile"() {
        given:
        Profile profile = runnerBean().getAnnotation(Profile)

        expect: "without this a fresh production database is seeded with fake sellers"
        profile != null
        profile.value().toList().contains('!prod')
    }

    def "SeedService is wired in exactly one place"() {
        given: "a second wiring point would route around the gate entirely"
        File src = new File('src/main/groovy/com/sboxmarket')
        int wirings = 0
        src.eachFileRecurse { File f ->
            if (f.name.endsWith('.groovy') && f.name != 'SeedService.groovy') {
                f.text.eachLine { String line ->
                    if (line =~ /seedService\s*\.\s*seed\s*\(/) {
                        wirings++
                    }
                }
            }
        }

        expect:
        wirings == 1
    }
}
