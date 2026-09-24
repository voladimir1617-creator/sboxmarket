package com.sboxmarket.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Serves the vendored Agentation dev bundle under {@code /__agentation/**}.
 *
 * <h3>Why a filter reading the FILESYSTEM, and not {@code static/}</h3>
 *
 * Two reasons, both measured in this repo.
 *
 * <ol>
 *   <li><b>Nothing ships.</b> The bundle lives in {@code <repo>/devtools/agentation/},
 *       which is outside every source set, so {@code processResources} never
 *       copies it and {@code sboxmarket-1.0.0.jar} contains zero Agentation
 *       bytes. Had it gone under {@code src/main/resources/static/}, the files
 *       would be in the production artifact and reachable by URL even with this
 *       filter absent — "not referenced" is not "not served".</li>
 *   <li><b>No stale copy.</b> Spring serves {@code build/resources/main/static/**},
 *       a snapshot taken by the last build. A sweep in this repo reported GREEN
 *       for every mutation because the browser never saw an edit to {@code src/}.
 *       Reading the bundle off disk means the browser always gets the bytes that
 *       are actually there.</li>
 * </ol>
 *
 * <h3>Traversal</h3>
 *
 * The request path is matched against a fixed {@link #ALLOWED} set of exact file
 * names — not sanitised, not normalised, <i>enumerated</i>. {@code ../} cannot
 * express a name in that set, so there is no traversal surface to get wrong.
 *
 * This filter only exists when {@link AgentationDevGate} is open; in every other
 * process {@code /__agentation/**} falls through to the normal 404.
 */
class AgentationAssetFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AgentationAssetFilter)

    /** Every file the page may fetch, mapped to the type it is served as.
     *  A name absent from this map is a 404 even while the gate is open. */
    static final Map<String, String> ALLOWED = [
        'react.production.min.js'    : 'text/javascript;charset=UTF-8',
        'react-dom.production.min.js': 'text/javascript;charset=UTF-8',
        'agentation.mjs'             : 'text/javascript;charset=UTF-8',
        'react-shim.mjs'             : 'text/javascript;charset=UTF-8',
        'react-dom-shim.mjs'         : 'text/javascript;charset=UTF-8',
        'jsx-runtime-shim.mjs'       : 'text/javascript;charset=UTF-8',
        'mount.mjs'                  : 'text/javascript;charset=UTF-8',
        'LICENSE-react.txt'          : 'text/plain;charset=UTF-8',
        'LICENSE-agentation.txt'     : 'text/plain;charset=UTF-8',
    ].asImmutable()

    /** Process-environment override for the bundle directory, for the case
     *  where the app is launched from somewhere other than the repo root. */
    static final String DIR_ENV_VAR = 'SBOX_AGENTATION_DIR'

    /** Resolved once at construction so the log line naming the directory is
     *  printed at startup, where the operator will see it, rather than on the
     *  first request. Null when nothing was found — then every asset 404s and
     *  the log says which paths were tried. */
    private final Path bundleDir

    AgentationAssetFilter(String dirOverride) {
        this.bundleDir = resolveBundleDir(dirOverride)
        if (bundleDir == null) {
            log.warn('[agentation] bundle directory NOT FOUND — tried {} — toolbar assets will 404',
                     candidates(dirOverride).join(', '))
        } else {
            log.info('[agentation] serving dev toolbar assets from {}', bundleDir)
        }
    }

    static List<Path> candidates(String dirOverride) {
        List<Path> out = []
        if (dirOverride?.trim()) out << Paths.get(dirOverride.trim())
        Path cwd = Paths.get('').toAbsolutePath()
        out << cwd.resolve('devtools').resolve('agentation')
        out << cwd.getParent()?.resolve('devtools')?.resolve('agentation')
        out.findAll { it != null }
    }

    static Path resolveBundleDir(String dirOverride) {
        candidates(dirOverride).find { Files.isDirectory(it) && Files.isRegularFile(it.resolve('mount.mjs')) }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        !request.requestURI?.startsWith(AgentationDevGate.ASSET_PREFIX)
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) {
        String name = req.requestURI.substring(AgentationDevGate.ASSET_PREFIX.length())
        String contentType = ALLOWED[name]
        if (contentType == null || bundleDir == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            return
        }
        Path file = bundleDir.resolve(name)
        if (!Files.isRegularFile(file)) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            return
        }
        byte[] body = Files.readAllBytes(file)
        resp.setStatus(HttpServletResponse.SC_OK)
        resp.setContentType(contentType)
        resp.setContentLength(body.length)
        // A dev tool the operator may be editing: never let a proxy or the
        // browser hold a copy that outlives the edit.
        resp.setHeader('Cache-Control', 'no-store')
        resp.outputStream.write(body)
    }
}
