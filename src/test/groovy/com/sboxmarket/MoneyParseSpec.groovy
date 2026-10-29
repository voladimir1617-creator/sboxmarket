package com.sboxmarket

import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.util.MoneyParse
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Unroll

import java.math.RoundingMode
import java.util.concurrent.TimeUnit

/**
 * MoneyParse is the bounded parse every client-supplied money string goes
 * through. `new BigDecimal("1e-100000000")` is cheap, but the
 * `setScale(2, HALF_UP)` that follows has to build 10^100000000 and pins a
 * request thread for minutes. These features pin that such input is
 * refused fast, with a 400 code, before any arithmetic, while every
 * ordinary amount (including browser float artifacts) still parses.
 */
class MoneyParseSpec extends Specification {

    @Unroll
    @Timeout(value = 500, unit = TimeUnit.MILLISECONDS)
    def "huge-exponent input '#raw' is rejected fast with INVALID_PRICE"() {
        when:
        MoneyParse.parse(raw, 'price')

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_PRICE'
        // Never echo the attacker's string back.
        !e.message.contains(raw.toString())

        where:
        raw << ['1e-100000000', '1e100000000', '0e-100000000', '1E-2147483647',
                '-1e-100000000', '1e11', '0.000000000000000000001']
    }

    @Unroll
    def "normal amount '#raw' parses to #expected"() {
        when:
        def bd = MoneyParse.parse(raw, 'price')

        then:
        bd == expected
        // And the follow-up cents normalisation is safe on it.
        bd.setScale(2, RoundingMode.HALF_UP) != null

        where:
        raw                    || expected
        '19.99'                || new BigDecimal('19.99')
        ' 5 '                  || new BigDecimal('5')
        '0.01'                 || new BigDecimal('0.01')
        '100000'               || new BigDecimal('100000')
        '1e3'                  || new BigDecimal('1000')
        '-5'                   || new BigDecimal('-5')
        '0.30000000000000004'  || new BigDecimal('0.30000000000000004')
        12                     || new BigDecimal('12')
        12.5d                  || new BigDecimal('12.5')
        new BigDecimal('7.25') || new BigDecimal('7.25')
    }

    def "a browser float artifact like 0.19999999999999996 still parses (scale 17 is allowed)"() {
        expect:
        MoneyParse.parse(0.19999999999999996d, 'maxDiscount') == new BigDecimal('0.19999999999999996')
    }

    @Timeout(value = 500, unit = TimeUnit.MILLISECONDS)
    def "input longer than MAX_LENGTH is rejected before parsing"() {
        given:
        def longDigits = '1' * (MoneyParse.MAX_LENGTH + 1)

        when:
        MoneyParse.parse(longDigits, 'amount', 'INVALID_AMOUNT')

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_AMOUNT'
    }

    def "a 1 MB digit string is rejected without parsing it"() {
        when:
        MoneyParse.parse('9' * 1_000_000, 'price')

        then:
        thrown(BadRequestException)
    }

    @Unroll
    def "non-numeric / blank / null input '#raw' is a 400 with the caller's code"() {
        when:
        MoneyParse.parse(raw, 'amount', 'INVALID_AMOUNT')

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_AMOUNT'

        where:
        raw << [null, '', '   ', 'abc', '1,000', 'NaN', 'Infinity', Double.NaN, [1, 2]]
    }

    @Timeout(value = 500, unit = TimeUnit.MILLISECONDS)
    def "requireSane refuses an already-bound BigDecimal with a huge scale (Jackson DTO path)"() {
        when:
        MoneyParse.requireSane(new BigDecimal('0e-100000000'), 'maxDiscount', 'INVALID_DISCOUNT')

        then:
        def e = thrown(BadRequestException)
        e.code == 'INVALID_DISCOUNT'
    }

    def "requireSane passes null and ordinary values through unchanged"() {
        expect:
        MoneyParse.requireSane(null, 'x') == null
        MoneyParse.requireSane(new BigDecimal('12.34'), 'x') == new BigDecimal('12.34')
    }

    def "bounds sit at the documented edges"() {
        expect:
        MoneyParse.isSane(BigDecimal.ONE.movePointLeft(MoneyParse.MAX_SCALE))
        !MoneyParse.isSane(BigDecimal.ONE.movePointLeft(MoneyParse.MAX_SCALE + 1))
        MoneyParse.isSane(new BigDecimal(BigInteger.ONE, MoneyParse.MIN_SCALE))
        !MoneyParse.isSane(new BigDecimal(BigInteger.ONE, MoneyParse.MIN_SCALE - 1))
        MoneyParse.isSane(new BigDecimal('1' * MoneyParse.MAX_PRECISION))
        !MoneyParse.isSane(new BigDecimal('1' * (MoneyParse.MAX_PRECISION + 1)))
    }
}
