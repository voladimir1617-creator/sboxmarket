package com.sboxmarket

import com.sboxmarket.model.Trade
import com.sboxmarket.service.TradeService
import spock.lang.Specification

import java.lang.reflect.Method

/**
 * Pins {@code TradeService.tradeToMap}'s {@code counterpartySteamProfileUrl} to
 * a plain {@link String}, not a Groovy {@code GString}.
 *
 * <h3>The defect this closes</h3>
 *
 * The field was built as {@code "https://steamcommunity.com/profiles/${id}"}
 * and dropped straight into an UNTYPED {@code Map} value. A GString in an
 * {@code Object} slot is not coerced to String, so Jackson serialised the
 * {@code GStringImpl} object itself — measured live 2026-09-01 the
 * {@code /api/trades} JSON carried
 * {@code "counterpartySteamProfileUrl":{"values":[…],"strings":[…],"bytes":…}}.
 * The SPA then set {@code href} to that object, so the trade-safety
 * "verify ↗" link and the counterparty profile link both rendered
 * {@code href="[object Object]"} — a dead link on the exact surface that
 * instructs sellers to vet the counterparty's Steam account before sending
 * items.
 *
 * <p>Verified RED by dropping the {@code .toString()} in
 * {@link TradeService}: the field is then a {@code GString}, and both the
 * type assertion and the {@code startsWith} (GString has no such contract via
 * {@code instanceof String}) fail.</p>
 */
class TradeCounterpartySteamProfileUrlSpec extends Specification {

    private Map invokeTradeToMap(String counterpartySteamId) {
        Method m = TradeService.getDeclaredMethod('tradeToMap',
            Trade, String, String, String, String, Map, long,
            com.sboxmarket.model.TradeMessage, Map, Long)
        m.setAccessible(true)
        Trade t = new Trade(id: 1L, buyerUserId: 10L, sellerUserId: 20L,
            price: new BigDecimal('1.08'), state: 'PENDING_SELLER_SEND')
        // args: (t, counterpartyTradeUrl, counterpartyName, counterpartySteamId,
        //        counterpartyAvatarUrl, ratingSummary, unreadCount, lastMessage,
        //        itemDecor, viewerUserId)
        return (Map) m.invoke(new TradeService(),
            t, 'https://steamcommunity.com/tradeoffer/new/?partner=1&token=x',
            'Counterparty', counterpartySteamId, null, null, 0L, null, null, 10L)
    }

    def "counterpartySteamProfileUrl is a plain String, not a GString"() {
        when:
        def url = invokeTradeToMap('76561199000000002').counterpartySteamProfileUrl

        then: 'a real java.lang.String survives Jackson serialization into the untyped map'
        url instanceof String
        url == 'https://steamcommunity.com/profiles/76561199000000002'
    }

    def "is null when the counterparty has no resolved Steam id"() {
        expect:
        invokeTradeToMap(null).counterpartySteamProfileUrl == null
    }
}
