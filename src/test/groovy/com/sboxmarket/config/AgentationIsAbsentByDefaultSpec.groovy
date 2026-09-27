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
     * {@code /index.html}, not {@code /}. {@code /} is a forward (Spring Boot's
     * welcome-page mapping first, {@code WebConfig.SPA_ROUTES} since commit
     * {@code 3286321}), and MockMvc does not execute forwards —
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

    /**
     * The forward target as a request path, so the page can actually be
     * fetched. MockMvc does not execute forwards — it records the view name and
     * leaves the body empty — so requesting the target is the only way to
     * assert on what {@code /} SERVES.
     */
    private static String asRequestPath(String forwardedUrl) {
        forwardedUrl == null ? null : (forwardedUrl.startsWith('/') ? forwardedUrl : '/' + forwardedUrl)
    }

    /**
     * <b>This case used to assert a string, and the string moved.</b>
     *
     * It read {@code forwardedUrl == 'index.html'}, which was true when it was
     * written: {@code /} was not matched by the depth-1 glob
     * {@code /{path:[^.]*}}, so it fell through to Spring Boot's
     * {@code WelcomePageHandlerMapping}, whose view name has NO leading slash.
     * Commit {@code 3286321} replaced those globs with an enumeration of the
     * routes that exist, {@code SPA_ROUTES}, whose first entry is {@code '/'}
     * and which registers {@code forward:/index.html} — WITH the slash. The
     * behaviour was unchanged; only the spelling of Spring's view name moved,
     * and this spec went red for it.
     *
     * A guard that fails on a rename it does not care about gets edited to
     * agree with whatever it found, and the next edit is the one that matters.
     * So the assertion is now the behaviour: {@code /} resolves, and the page
     * it resolves TO is fetched and checked — for being the app at all, and for
     * carrying no trace of the toolbar. Either spelling of the view name
     * passes; a {@code /} that stops serving the app, or an app shell that
     * starts carrying the toolbar, does not.
     */
    def "/ resolves to the app shell, and that page carries no trace of the toolbar"() {
        given: 'the root route, requested the way a browser requests it'
        def root = mockMvc.perform(MockMvcRequestBuilders.get('/').accept('text/html')).andReturn().response

        expect: '/ is answered — not a redirect, not a 404'
        root.status == 200

        and: 'by a forward, because the shell is a static resource rather than a rendered view'
        // Deliberately NOT compared against a literal: `index.html` and
        // `/index.html` are the same resource and this spec has no stake in
        // which spelling Spring produces.
        asRequestPath(root.forwardedUrl) != null

        and: 'and the page that forward names really is this app, not an error page'
        def target = mockMvc.perform(
                MockMvcRequestBuilders.get(asRequestPath(root.forwardedUrl)).accept('text/html')
        ).andReturn().response
        target.status == 200
        String body = target.getContentAsString(StandardCharsets.UTF_8)
        body.toLowerCase().contains('</body>')
        // Positive control with teeth: the shell must load the app's own entry
        // module. `</body>` alone would be satisfied by the 404 view.
        body =~ /src="\/js\/main\.js\?v=\d+"/

        and: 'no script tag, no asset URL and no mount node on the page / actually serves'
        FINGERPRINTS.findAll { body.toLowerCase().contains(it.toLowerCase()) } == []
    }

    /**
     * <h4>Why this is a served-HTML check and not a DOM query</h4>
     *
     * The obvious browser-side check — {@code document.querySelector('#' +
     * MOUNT_ID)} — is worthless in both directions: the toolbar renders inside
     * a SHADOW ROOT, so that query returns {@code null} on a page where the
     * toolbar is present and working. A sibling established the decisive
     * browser check instead: <b>the page must contain zero shadow roots</b>
     * (walk every element and count {@code el.shadowRoot != null}). Measured on
     * the running app with {@code SBOX_AGENTATION} unset: 0 shadow roots, and
     * {@code /__agentation/mount.mjs} 404. With it set, the mount node's shadow
     * root appears. The count is recorded here so nobody re-derives the
     * querySelector check and reads its null as absence.
     *
     * This spec asserts the same absence one layer earlier, in the bytes the
     * server sends, where the mount node cannot hide inside a shadow root
     * because it has not been attached yet.
     */
    def "the whole /__agentation/** prefix is unreachable — #path"() {
        expect:
        mockMvc.perform(MockMvcRequestBuilders.get(path)).andReturn().response.status == 404

        where:
        path << [
            // The three real bundle files.
            AgentationDevGate.ASSET_PREFIX + 'mount.mjs',
            AgentationDevGate.ASSET_PREFIX + 'agentation.mjs',
            AgentationDevGate.ASSET_PREFIX + 'react.production.min.js',
            // …and names that are not in the bundle map at all, so this is
            // "the prefix is unreachable" rather than "these three filenames
            // are". A filter registered by mistake would serve, or at least
            // handle, these; nothing does.
            AgentationDevGate.ASSET_PREFIX + 'anything.mjs',
            AgentationDevGate.ASSET_PREFIX + 'nested/deeper/asset.js',
        ]
    }

    /**
     * The bare prefix is handled separately because <b>404 is the wrong
     * assertion for it under MockMvc, and asserting it would have been
     * asserting an artefact.</b>
     *
     * {@code /__agentation/} contains no dot, so it matches
     * {@code WebConfig}'s depth-1 trailing-slash glob
     * {@code /{path:[^.]*}/} and forwards to {@link WebConfig#SPA_NOT_FOUND_PATH},
     * where {@code SpaNotFoundController} sets {@code 404}. MockMvc does not
     * execute forwards, so it records <b>200 with an empty body</b> — measured.
     * The asset-shaped paths above are genuine 404s because they carry a dot
     * and so reach the resource handler directly.
     *
     * Demanding 404 here would fail on a MockMvc artefact and invite someone to
     * "fix" routing that is correct; accepting the 200 would record a leak that
     * does not exist. What is both decidable and load-bearing is WHERE the
     * request goes and that nothing of its own is served.
     */
    def "the bare prefix lands on the app's own not-found, serving nothing of its own"() {
        when:
        def res = mockMvc.perform(MockMvcRequestBuilders.get(AgentationDevGate.ASSET_PREFIX))
                         .andReturn().response

        then: 'a real 404, or a forward to the route that produces one'
        res.status == 404 || res.forwardedUrl == WebConfig.SPA_NOT_FOUND_PATH

        and: 'and not one byte of its own, whichever of those two it was'
        res.getContentAsString(StandardCharsets.UTF_8).isEmpty()
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
