package com.sboxmarket.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper

import java.nio.charset.StandardCharsets

/**
 * Injects the Agentation dev toolbar into HTML responses — and only ever runs
 * in a process whose environment asked for it (see {@link AgentationDevGate}).
 *
 * <h3>Why the markup is injected rather than committed to index.html</h3>
 *
 * If the script tags lived in {@code src/main/resources/static/index.html} they
 * would be in the shipped artifact, and "off in production" would then rest on
 * a runtime branch inside the page. Injecting means the default-profile
 * response is byte-identical to today's, and the absence of the toolbar is a
 * property of the bean graph rather than of a conditional in the HTML.
 *
 * It also keeps the change out of {@code index.html}, which other streams are
 * editing concurrently.
 *
 * <h3>Content-Security-Policy</h3>
 *
 * {@code CorrelationIdFilter} sends {@code script-src 'self'} with no
 * {@code 'unsafe-inline'} and no nonce. So every tag below is an EXTERNAL,
 * same-origin {@code src} — there is no inline script and no inline
 * {@code <script type="importmap">} (an import map is itself an inline script
 * and would be blocked). That is why the vendored bundle rewrites its own bare
 * specifiers to relative paths instead of relying on a map. The CSP header is
 * NOT modified by this filter in either direction; {@code style-src} already
 * carries {@code 'unsafe-inline'}, which is what React's inline style props and
 * the toolbar's injected {@code <style>} need.
 *
 * <h3>Two mechanics that are easy to get wrong</h3>
 *
 * <ul>
 *   <li><b>304s.</b> {@code index.html} is served by the static resource
 *       handler, which honours {@code If-Modified-Since}/{@code If-None-Match}
 *       and answers 304 with no body — nothing to inject into, and the browser
 *       reuses its un-injected copy. The request is therefore wrapped to hide
 *       those two headers, so an HTML navigation always gets a full 200.</li>
 *   <li><b>Buffering.</b> {@link ContentCachingResponseWrapper} captures the
 *       body without committing the real response ({@code flushBuffer()} is a
 *       deliberate no-op there), so the {@code Content-Length} can still be
 *       corrected after the body is rewritten.</li>
 * </ul>
 */
class AgentationInjectionFilter extends OncePerRequestFilter {

    /** Conditional-request headers hidden from the downstream handler so an
     *  HTML navigation cannot come back as a bodyless 304. */
    private static final List<String> CONDITIONAL_HEADERS = ['if-modified-since', 'if-none-match'].asImmutable()

    private final String snippet

    AgentationInjectionFilter(String appName) {
        String app = java.net.URLEncoder.encode(appName ?: 'app', StandardCharsets.UTF_8)
        // ONE tag. mount.mjs bootstraps the rest itself, which is what lets it
        // REUSE the React this page already has rather than replacing it: as of
        // this writing index.html loads React + ReactDOM 18.2.0 UMD from unpkg
        // with SRI, a module script is deferred and therefore runs after those
        // classic scripts, and swapping window.React underneath a mid-render
        // application to hang a dev toolbar off it would be a bad trade. The
        // vendored 18.3.1 copies are the offline fallback only.
        this.snippet = """
<!-- Agentation dev toolbar. Present ONLY because ${AgentationDevGate.OPT_IN_ENV_VAR}=${AgentationDevGate.OPT_IN_VALUE}
     was in this process's environment at startup. See AGENTATION.md. -->
<script type="module" src="${AgentationDevGate.ASSET_PREFIX}mount.mjs?app=${app}"></script>
"""
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only page navigations. Narrowing here rather than in doFilterInternal
        // keeps the response wrapper (and the 304 suppression) off every API
        // call, image and stylesheet.
        String accept = request.getHeader('Accept')
        String uri = request.requestURI ?: ''
        if (uri.startsWith(AgentationDevGate.ASSET_PREFIX)) return true
        !(accept?.toLowerCase()?.contains('text/html'))
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) {
        HttpServletRequest unconditional = new HttpServletRequestWrapper(req) {
            @Override String getHeader(String name) {
                CONDITIONAL_HEADERS.contains(name?.toLowerCase()) ? null : super.getHeader(name)
            }
            @Override Enumeration<String> getHeaders(String name) {
                CONDITIONAL_HEADERS.contains(name?.toLowerCase()) ? Collections.enumeration([]) : super.getHeaders(name)
            }
            @Override long getDateHeader(String name) {
                CONDITIONAL_HEADERS.contains(name?.toLowerCase()) ? -1L : super.getDateHeader(name)
            }
        }

        ContentCachingResponseWrapper cached = new ContentCachingResponseWrapper(resp)
        chain.doFilter(unconditional, cached)

        byte[] body = cached.contentAsByteArray
        String contentType = cached.contentType
        int marker = -1
        String html = null
        if (body.length > 0 && contentType?.toLowerCase()?.contains('text/html')) {
            // ISO-8859-1 on purpose, for BOTH directions. It maps every byte 0x00-0xFF to one
            // char and back, so the original body survives this round trip byte for byte
            // whatever it was really encoded in — and the snippet is pure ASCII. Decoding as
            // UTF-8 would corrupt a page served in any other charset, and there is no reason
            // for this filter to have an opinion about the page's encoding at all.
            html = new String(body, StandardCharsets.ISO_8859_1)
            marker = html.toLowerCase().lastIndexOf('</body>')
        }

        if (marker < 0) {
            // Not HTML, empty, or no </body> to anchor on — pass the original
            // bytes through untouched rather than guessing where to put it.
            cached.copyBodyToResponse()
            return
        }

        byte[] out = (html.substring(0, marker) + snippet + html.substring(marker))
                        .getBytes(StandardCharsets.ISO_8859_1)

        // CARRY THE CONTENT TYPE ACROSS BY HAND. ContentCachingResponseWrapper intercepts the
        // header the downstream handler sets and only applies it inside copyBodyToResponse(),
        // which this branch deliberately does not call. Without this line the rewritten page
        // goes out with NO Content-Type at all; the app also sends
        // `X-Content-Type-Options: nosniff`, so the browser refuses to guess and renders the
        // entire SPA as plain text. Measured in a browser against the running app — curl saw
        // nothing wrong, because the bytes were right and only the header was missing.
        if (contentType) resp.setContentType(contentType)
        resp.setContentLength(out.length)
        resp.setHeader('Cache-Control', 'no-store')
        resp.outputStream.write(out)
    }
}
