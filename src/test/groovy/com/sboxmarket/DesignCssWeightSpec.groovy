package com.sboxmarket

import spock.lang.Specification

/**
 * design.css is render-blocking: every page waits for all of it before the
 * first paint, so on a phone its weight IS the blank-screen time.
 *
 * Most of it was rules for class names no served page ever emits (the
 * csfloat-* utility sets, retired layouts). Those rules are being removed in
 * slices, each one checked to change zero pixels at 1440 and 390 wide. This
 * ceiling keeps the bytes that were cut from quietly coming back.
 *
 * IF THIS FAILS because you added real styles: first look for rules your change
 * made dead and delete them; if the new size is genuine, raise CEILING_BYTES to
 * the new size plus a little headroom, in the same commit.
 *
 * Read from the CLASSPATH, like DesignTokenContractSpec, so it measures the
 * file the server actually ships.
 */
class DesignCssWeightSpec extends Specification {

    /** main was 4,824,822 bytes before the first slice, 4,372,884 before the second, 3,872,385 before the third, 3,369,033 before the fourth and 2,865,407 before the fifth. */
    static final long CEILING_BYTES = 2_340_000L

    def "design.css stays under its byte ceiling"() {
        given:
        def url = DesignCssWeightSpec.getResource('/static/css/design.css')
        long bytes = url == null ? -1L : url.bytes.length

        expect:
        url != null
        bytes > 1_000_000L      // positive control: this is the real stylesheet, not a stub
        bytes <= CEILING_BYTES ||
                { throw new AssertionError(
                        "design.css is ${bytes} bytes, over its ${CEILING_BYTES}-byte ceiling. " +
                        "It blocks first paint on every page; see the note at the top of this spec.") }()
    }
}
