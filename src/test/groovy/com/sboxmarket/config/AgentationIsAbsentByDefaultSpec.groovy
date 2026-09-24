package com.sboxmarket.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import spock.lang.Specification

import java.nio.charset.StandardCharsets

/**
 * <b>The Agentation dev toolbar must not exist in a process that did not ask
 * for it.</b> This is the load-bearing spec for the whole feature.
 *
 * It asserts absence four independent ways, because any one of them alone could
 * pass for the wrong reason:
 *
 * <ol>
 *   <li>the served HTML carries no script tag, no asset URL and no mount node;</li>
 *   <li>{@code /__agentation/**} is a 404, so the bundle is unreachable even if
 *       someone knows the path;</li>
 *   <li>no bean of either filter type exists — the configuration class was
 *       never processed, rather than processed and disabled;</li>
 *   <li>and the injection mechanism, exercised directly, <b>does</b> work — so
 *       (1) is absence-by-gate and not absence-because-broken.</li>
 * </ol>
 *
 * Point (4) is there because this repo's recurring defect is a missing signal
 * read as a positive result. A spec that only asserts "the string is not in the
 * page" stays green if the feature never worked at all, and would then be
 * decoration.
 *
 * <h3>How to see this spec fail</h3>
 *
 * Delete {@code @Conditional(AgentationDevGate.Enabled)} from
 * {@link AgentationDevConfig} and re-run: the configuration is then
 * unconditional, the filters register in every context, and assertions 1–3
 * fail.
 *
 * <h3>Profiles are irrelevant here, on purpose</h3>
 *
 * This runs under {@code @ActiveProfiles('test')} and the gate is still shut,
 * because the gate does not read profiles or Spring properties at all — see
 * {@link AgentationDevGateSpec}. There is no profile, and no properties file on
 * the config-data search path, that can arm it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles('test')
class AgentationIsAbsentByDefaultSpec extends Specification {

    @Autowired MockMvc mockMvc
    @Autowired ApplicationContext ctx

    /**
     * Every string that would betray the toolbar in a response body.
     *
     * <p>NOT {@code react.production.min.js}: this page has legitimately
     * carried its own
     * {@code <script src="https://unpkg.com/react@18.2.0/umd/react.production.min.js">}
     * since long before this feature existed. An earlier draft of this list
     * included that filename and failed here — correctly, but for the app's own
     * CDN tag rather than for anything Agentation added. Matching on it would
     * be a fingerprint that is not specific to the thing being detected, which
     * is how a guard starts reporting a result it did not measure. The
     * vendored copies are served under {@link AgentationDevGate#ASSET_PREFIX},
     * so that prefix already covers them and covers nothing else.
     */
    private static final List<String> FINGERPRINTS = [
        'agentation',
        AgentationDevGate.ASSET_PREFIX,
        AgentationDevGate.MOUNT_ID,
    ].asImmutable()

    /**
     * {@code /index.html}, not {@code /}. Spring Boot maps {@code /} to a
     * {@code forward:index.html} view, and MockMvc does not execute forwards —
     * it records {@code forwardedUrl} and leaves the body EMPTY. An assertion
     * that the toolbar is absent from an empty body is exactly the
     * "absence read as success" result this spec exists to refuse, so the
     * {@code </body>} check below is what stops it, and the request is aimed at
     * the resource the forward names.
     */
    def "the default-profile HTML contains no trace of the toolbar"() {
        when:
        def res = mockMvc.perform(MockMvcRequestBuilders.get('/index.html').accept('text/html'))
                         .andReturn().response
        String body = res.getContentAsString(StandardCharsets.UTF_8)

        then: 'the page really is the app, so the assertion below is not passing on an empty body'
        res.status == 200
        body.toLowerCase().contains('</body>')

        and:
        FINGERPRINTS.every { !body.toLowerCase().contains(it.toLowerCase()) }
    }

    def "and / really does forward to the page that was just checked"() {
        expect:
        mockMvc.perform(MockMvcRequestBuilders.get('/').accept('text/html'))
               .andReturn().response.forwardedUrl == 'index.html'
    }

    def "the vendored bundle is unreachable — #path"() {
        expect:
        mockMvc.perform(MockMvcRequestBuilders.get(path)).andReturn().response.status == 404

        where:
        path << [
            AgentationDevGate.ASSET_PREFIX + 'mount.mjs',
            AgentationDevGate.ASSET_PREFIX + 'agentation.mjs',
            AgentationDevGate.ASSET_PREFIX + 'react.production.min.js',
        ]
    }

    def "neither filter is a bean — the configuration class was never processed"() {
        expect:
        ctx.getBeanNamesForType(AgentationAssetFilter).length == 0
        ctx.getBeanNamesForType(AgentationInjectionFilter).length == 0
        ctx.getBeanNamesForType(AgentationDevConfig).length == 0
    }

    def "the injection mechanism itself works — so the absence above is the gate, not a broken feature"() {
        given:
        def filter = new AgentationInjectionFilter('SkinBox')
        def req = new MockHttpServletRequest('GET', '/')
        req.addHeader('Accept', 'text/html')
        def resp = new MockHttpServletResponse()
        FilterChain chain = { HttpServletRequest rq, HttpServletResponse rs ->
            rs.contentType = 'text/html;charset=UTF-8'
            rs.writer.write('<html><body><h1>hi</h1></body></html>')
            rs.writer.flush()
        } as FilterChain

        when:
        filter.doFilter(req, resp, chain)
        String out = resp.getContentAsString()

        then:
        out.contains(AgentationDevGate.ASSET_PREFIX + 'mount.mjs')
        out.contains('type="module"')
        out.indexOf(AgentationDevGate.ASSET_PREFIX) < out.toLowerCase().lastIndexOf('</body>')
        out.contains('<h1>hi</h1>')

        and: 'no INLINE script — the app sends script-src self with no unsafe-inline and no nonce'
        !(out =~ /(?s)<script(?![^>]*\bsrc=)[^>]*>\s*\S/)

        and: '''the Content-Type SURVIVES the rewrite.

           Measured, in a browser, against the running app on 8096: the injected page came
           back with NO Content-Type header at all, and because the app also sends
           `X-Content-Type-Options: nosniff` Chrome refused to sniff and rendered the entire
           SPA as plain text. curl did not notice — it printed the same bytes either way, and
           the snippet was present in them, so every check short of looking at a rendered page
           said this worked.

           The cause is that ContentCachingResponseWrapper INTERCEPTS setContentType: the
           handler downstream sets it on the wrapper, and it only reaches the real response
           inside copyBodyToResponse() — which this filter deliberately does not call on the
           path where it rewrites the body. So the filter has to carry the header across
           itself. The chain below sets the content type on the wrapper exactly as a real
           handler does, so this assertion fails if that line is ever removed.'''
        resp.contentType?.toLowerCase()?.contains('text/html')
    }
}
