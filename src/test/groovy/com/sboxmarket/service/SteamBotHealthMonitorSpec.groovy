package com.sboxmarket.service

import groovy.json.JsonOutput
import spock.lang.Specification

/**
 * Bot-offline staff alert: one alert per outage after N failed checks in a
 * row, one "back online" when it recovers, nothing in between, and inert when
 * the bot is not configured.
 */
class SteamBotHealthMonitorSpec extends Specification {

    SteamTradeBotService bot = Mock()
    OpsAlertService ops = Mock()
    SteamBotHealthMonitor monitor = new SteamBotHealthMonitor(
            steamTradeBotService: bot, opsAlertService: ops, failureThreshold: 3, monitorEnabled: true)

    private static SteamBotResult healthy() {
        SteamBotResult.success([ok: true, loggedIn: true, ready: true, lastError: null, pendingConfirmations: 2])
    }

    private static SteamBotResult loggedOut() {
        SteamBotResult.success([ok: true, loggedIn: false, ready: false, lastError: 'LoggedInElsewhere', pendingConfirmations: 0])
    }

    private static SteamBotResult down() {
        SteamBotResult.error('TRANSPORT_ERROR', 'Connection refused')
    }

    def "inert when the bot is not configured: no health call, no alert"() {
        given:
        bot.enabled >> false

        when:
        5.times { monitor.checkBotHealth() }

        then:
        0 * bot.health()
        0 * ops.alertAdmins(*_)
    }

    def "inert when the monitor is switched off"() {
        given:
        monitor.monitorEnabled = false
        bot.enabled >> true

        when:
        monitor.checkBotHealth()

        then:
        0 * bot.health()
    }

    def "healthy checks never alert"() {
        given:
        bot.enabled >> true
        bot.health() >> healthy()

        when:
        10.times { monitor.checkBotHealth() }

        then:
        0 * ops.alertAdmins(*_)
        monitor.snapshot().online == true
        monitor.snapshot().pendingConfirmations == 2
    }

    def "alerts once after the threshold, stays quiet for the rest of the outage, and sends one back-online"() {
        given:
        bot.enabled >> true

        when: "two failures: below the threshold"
        monitor.checkBotHealth()
        monitor.checkBotHealth()

        then:
        2 * bot.health() >> down()
        0 * ops.alertAdmins(*_)

        when: "the third in a row"
        monitor.checkBotHealth()

        then:
        1 * bot.health() >> down()
        1 * ops.alertAdmins(OpsAlertService.BOT_OFFLINE, OpsAlertService.BOT_OFFLINE, 'Steam bot offline',
                { String b -> b.contains('3 health checks') && b.contains('TRANSPORT_ERROR') }, '/admin?tab=health', true) >> 1
        monitor.snapshot().alerting
        monitor.snapshot().online == false

        when: "the outage keeps going"
        20.times { monitor.checkBotHealth() }

        then: "no alert spam"
        20 * bot.health() >> down()
        0 * ops.alertAdmins(*_)

        when: "it recovers"
        monitor.checkBotHealth()
        monitor.checkBotHealth()

        then: "exactly one back-online"
        2 * bot.health() >> healthy()
        1 * ops.alertAdmins(OpsAlertService.BOT_ONLINE, OpsAlertService.BOT_ONLINE, 'Steam bot back online', _, '/admin?tab=health', true) >> 1
        !monitor.snapshot().alerting
        monitor.snapshot().consecutiveFailures == 0
    }

    def "a sidecar that answers but is logged out of Steam counts as offline"() {
        given:
        bot.enabled >> true
        bot.health() >> loggedOut()

        when:
        3.times { monitor.checkBotHealth() }

        then:
        1 * ops.alertAdmins(OpsAlertService.BOT_OFFLINE, _, _, { String b -> b.contains('not logged in') }, _, true) >> 1
        monitor.snapshot().lastError == 'LoggedInElsewhere'
        monitor.snapshot().loggedIn == false
    }

    def "a healthy check in the middle resets the run"() {
        given:
        bot.enabled >> true

        when:
        monitor.checkBotHealth()
        monitor.checkBotHealth()
        monitor.checkBotHealth()
        monitor.checkBotHealth()

        then:
        4 * bot.health() >>> [down(), down(), healthy(), down()]
        0 * ops.alertAdmins(*_)
        monitor.consecutiveFailures == 1
    }

    def "a fan-out that reached nobody is retried next tick instead of lost"() {
        given:
        bot.enabled >> true
        bot.health() >> down()

        when:
        3.times { monitor.checkBotHealth() }

        then:
        1 * ops.alertAdmins(OpsAlertService.BOT_OFFLINE, *_) >> 0

        when:
        monitor.checkBotHealth()

        then:
        1 * ops.alertAdmins(OpsAlertService.BOT_OFFLINE, *_) >> 1

        when:
        monitor.checkBotHealth()

        then:
        0 * ops.alertAdmins(*_)
    }

    def "no recovery alert when no offline alert was ever sent"() {
        given:
        bot.enabled >> true

        when:
        monitor.checkBotHealth()
        monitor.checkBotHealth()

        then:
        2 * bot.health() >>> [down(), healthy()]
        0 * ops.alertAdmins(*_)
    }

    def "SteamTradeBotService.health() GETs /health and is disabled without a base url"() {
        given:
        def svc = Spy(SteamTradeBotService)
        svc.baseUrl = 'http://localhost:4000/'
        svc.apiToken = 't'

        when:
        def r = svc.health()

        then:
        1 * svc.doRequest('GET', 'http://localhost:4000/health', null, _ as Long) >>
                new SteamTradeBotService.RawResponse(status: 200, body: JsonOutput.toJson([ok: true, loggedIn: true, ready: true]))
        r.ok
        r.raw.loggedIn == true

        when:
        svc.baseUrl = ''
        def off = svc.health()

        then:
        0 * svc.doRequest(*_)
        off.errorCode == 'DISABLED'
    }
}
