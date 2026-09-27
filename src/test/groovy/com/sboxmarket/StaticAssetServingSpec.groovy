package com.sboxmarket

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Pins WebConfig.addResourceHandlers so the static asset directories actually
 * serve their files. Regression guard for f6f7548 ("perf(static): browser-cache
 * static assets 7d"), which registered ONE shared `classpath:/static/` location
 * across `/css/**`, `/js/**`, `/img/**`, `/fonts/**`.
 *
 * Spring strips a handler's literal prefix (`/css/`) and resolves only the `**`
 * remainder (`design.css`) against the location — so the shared location made
 * `/css/design.css` look for `static/design.css` (wrong dir) and 404. EVERY
 * CSS / JS / img on the site was dead, yet the SPA shell still 200'd via the
 * `/**` handler, so a `curl /` smoke-test passed while the live site served a
 * styleless, script-less skeleton to every visitor. There was no test over
 * static-asset serving, so it shipped silently — this spec closes that gap.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaticAssetServingSpec extends Specification {

    @Autowired MockMvc mockMvc

    @Unroll
    def "GET #path serves 200#ctNote (real file under its asset dir)"() {
        when:
        def res = mockMvc.perform(MockMvcRequestBuilders.get(path)).andReturn().response

        then:
        res.status == 200
        ct == null || res.contentType?.toLowerCase()?.contains(ct)

        where:
        path              | ct
        '/css/design.css' | 'text/css'
        '/js/main.js'     | 'javascript'
        '/js/app.js'      | 'javascript'
        '/img/logo.svg'   | 'svg'
        '/favicon.ico'    | null        // static-root file, served via the '/**' handler

        ctNote = ct ? " ($ct)" : ""
    }

    def "the f6f7548 prefix-loss is gone: /css/<root-file> must NOT resolve to the static root"() {
        // Before the fix, '/css/index.html' stripped '/css/' and resolved
        // 'index.html' against 'classpath:/static/', serving the SPA shell as a
        // stylesheet (200 text/html). With a per-directory location it correctly
        // resolves to static/css/index.html, which does not exist → 404. If this
        // ever 200s again, the shared-location regression is back.
        when:
        def res = mockMvc.perform(MockMvcRequestBuilders.get('/css/index.html')).andReturn().response

        then:
        res.status == 404
    }
}
