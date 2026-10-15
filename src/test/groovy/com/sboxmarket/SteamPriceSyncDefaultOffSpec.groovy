package com.sboxmarket

import com.sboxmarket.repository.ItemRepository
import com.sboxmarket.service.SteamMarketPriceService
import org.springframework.beans.factory.annotation.Value
import org.yaml.snakeyaml.Yaml
import spock.lang.Specification

/**
 * The Steam price sync is opt-in (owner's call, 2026-10-15): seed prices
 * are settled, so nothing should rewrite them from Steam unless an
 * operator sets STEAM_PRICE_SYNC_ENABLED=true.
 */
class SteamPriceSyncDefaultOffSpec extends Specification {

    def "the @Value fallback and the field default are both off"() {
        when:
        def field = SteamMarketPriceService.getDeclaredField('steamSyncEnabled')

        then:
        field.getAnnotation(Value).value() == '${app.steam-price-sync.enabled:false}'
        !new SteamMarketPriceService().steamSyncEnabled
    }

    def "application.yml ships the sync off unless STEAM_PRICE_SYNC_ENABLED is set"() {
        when:
        def text = getClass().getResourceAsStream('/application.yml').text
        def docs = new Yaml().loadAll(text).findAll { it instanceof Map }
        def app = docs.find { it.app?.'steam-price-sync' }?.app

        then:
        app.'steam-price-sync'.enabled == '${STEAM_PRICE_SYNC_ENABLED:false}'
    }

    def "a service left at its default never touches the item repository"() {
        given:
        ItemRepository repo = Mock()
        def svc = new SteamMarketPriceService(itemRepository: repo)

        when:
        svc.syncPricesFromSteam()

        then:
        0 * repo._
    }
}
