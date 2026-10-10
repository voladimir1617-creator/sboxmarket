package com.sboxmarket

import spock.lang.Specification

/**
 * The mechanism behind the comment.
 *
 * design.css carries a block header claiming "DesignTokenContractSpec fails the
 * build if a rung goes missing again or is declared twice." When that sentence
 * was written the spec did not exist — the guarantee was prose. This file is the
 * guarantee.
 *
 * WHAT IT GUARDS, and why it is not cosmetic:
 *
 * An undeclared custom property is the CSS form of the defect this codebase
 * keeps paying for — a missing signal rendered as a healthy one. `padding:
 * var(--s-24)` where --s-24 was never declared does not throw, does not warn,
 * and does not fall back: the declaration is dropped at computed-value time and
 * the element renders with zero padding. The page still paints. It just paints
 * wrong, and nothing anywhere says so.
 *
 * That was the measured state of this stylesheet: a browser probe of the
 * running app resolved all 32 of --s-4…--s-80, --t-10…--t-60, --r-xl,
 * --shadow-3, --ink-5, --blue-glow, --container and --font-mono to the empty
 * string, while `design/_design.css` — the design system these pages are
 * supposed to be built from — declares every one of them. Any markup lifted out
 * of design/ collapsed silently.
 *
 * Read from the CLASSPATH, not from src/. build/resources/main is what the
 * server actually serves, so an edit that never made it through processResources
 * fails here instead of passing against a source file nobody is shipping.
 */
class DesignTokenContractSpec extends Specification {

    /** `var(--name)` with NO comma — i.e. no fallback. These are the fatal ones. */
    private static final java.util.regex.Pattern VAR_NO_FALLBACK =
            ~/var\(\s*(--[A-Za-z0-9_-]+)\s*\)/

    /** A custom-property declaration: `--name:` */
    private static final java.util.regex.Pattern DECLARATION =
            ~/(--[A-Za-z0-9_-]+)\s*:/

    /**
     * The rungs that must have exactly one home. Every name here is either a
     * scale from design/_design.css or a rung the app added to that scale; the
     * point of the list is that each is declared ONCE, so changing a value
     * changes the app rather than one of four competing copies.
     */
    private static final List<String> LADDER_RUNGS = [
            // spacing (design/_design.css) + --s-48, the app's extra rung
            '--s-4', '--s-8', '--s-10', '--s-12', '--s-14', '--s-16', '--s-18',
            '--s-20', '--s-22', '--s-24', '--s-28', '--s-32', '--s-40', '--s-48',
            '--s-56', '--s-80',
            // type (design/_design.css) + the sizes the shipped --fs-* ladders use
            '--t-10', '--t-11', '--t-12', '--t-13', '--t-14', '--t-15', '--t-16',
            '--t-18', '--t-20', '--t-22', '--t-24', '--t-28', '--t-32', '--t-40',
            '--t-42', '--t-60',
            // radius — the one ladder that absorbed four legacy sets
            '--r-xs', '--r-sm', '--r-md', '--r-lg', '--r-xl',
            '--r-ctl', '--r-modal', '--r-pill',
    ].asImmutable()

    /** Named by design/_design.css and referenced by markup copied out of design/. */
    private static final List<String> DESIGN_SYSTEM_TOKENS = [
            '--bg', '--bg-1', '--bg-2', '--bg-3', '--line', '--line-2',
            '--ink', '--ink-2', '--ink-3', '--ink-4', '--ink-5',
            '--blue', '--blue-d', '--blue-soft', '--blue-glow',
            '--up', '--down', '--warn',
            '--shadow-1', '--shadow-2', '--shadow-3',
            '--nav-h', '--sub-h', '--container',
            '--font-sans', '--font-mono',
    ].asImmutable()

    private static String read(String classpathPath) {
        def url = DesignTokenContractSpec.getResource(classpathPath)
        assert url != null: "not on the classpath: ${classpathPath} — processResources did not ship it"
        url.getText('UTF-8')
    }

    /**
     * Comments must go before anything is counted. design.css carries prose that
     * quotes token names and even whole declarations; counting those would let a
     * rung "exist" because someone wrote about it.
     */
    private static String stripComments(String css) {
        css.replaceAll(/(?s)\/\*.*?\*\//, '')
    }

    /** design.css + fonts.css + the inline critical block in index.html. */
    private static String allDeclarationSources() {
        stripComments(read('/static/css/design.css')) +
                '\n' + stripComments(read('/static/css/fonts.css')) +
                '\n' + stripComments(read('/static/index.html'))
    }

    private static Map<String, Integer> declarationCounts() {
        def counts = [:].withDefault { 0 }
        def m = DECLARATION.matcher(allDeclarationSources())
        while (m.find()) { counts[m.group(1)] = counts[m.group(1)] + 1 }
        counts
    }

    def "every var() without a fallback names a property that is actually declared"() {
        given: "the shipped stylesheet, comments removed"
        def css = stripComments(read('/static/css/design.css'))
        def declared = declarationCounts().keySet()

        when: "every fallback-less reference is collected"
        def referenced = [] as Set
        def m = VAR_NO_FALLBACK.matcher(css)
        while (m.find()) { referenced << m.group(1) }

        then: "none of them is undeclared"
        // An undeclared one does not error — it drops the whole declaration and
        // the element renders as if the rule had never been written.
        referenced.size() > 50   // positive control: the matcher found real work
        def unresolved = (referenced - declared).sort()
        unresolved.isEmpty() ||
                { throw new AssertionError(
                        "${unresolved.size()} custom propert${unresolved.size() == 1 ? 'y is' : 'ies are'} " +
                        "referenced with no fallback and never declared, so ${unresolved.size() == 1 ? 'it' : 'they'} " +
                        "silently resolve to the empty string and the declarations using " +
                        "${unresolved.size() == 1 ? 'it' : 'them'} are dropped:\n  " + unresolved.join('\n  ')) }()
    }

    def "each design-system ladder rung is declared exactly once"() {
        given:
        def counts = declarationCounts()

        expect: "one source of truth per rung — not missing (renders as empty), not duplicated (two homes for one number)"
        def wrong = LADDER_RUNGS.findAll { counts[it] != 1 }
                .collectEntries { [(it): counts[it]] }
        wrong.isEmpty() ||
                { throw new AssertionError(
                        "ladder rungs must be declared exactly once; these are not:\n  " +
                        wrong.collect { k, v -> "${k}: declared ${v} time${v == 1 ? '' : 's'}" }.join('\n  ')) }()
    }

    def "the tokens design/_design.css names are all present in the app"() {
        given:
        def declared = declarationCounts().keySet()

        expect: "markup copied out of design/ resolves rather than collapsing"
        def missing = DESIGN_SYSTEM_TOKENS.findAll { !declared.contains(it) }.sort()
        missing.isEmpty() ||
                { throw new AssertionError(
                        "design/_design.css declares ${missing.size()} token(s) the app does not, " +
                        "so any page built from the design system loses them silently:\n  " + missing.join('\n  ')) }()
    }

    /**
     * Byte ranges covered by a conditional at-rule (@media / @supports /
     * @container), found by real brace matching rather than by regex.
     *
     * This exists because the first version of the colour-scheme test below was
     * decoration. It asked `css =~ /:root\s*\{[^}]*color-scheme:\s*dark/` and
     * passed — on line 5992, which reads
     *     @media (prefers-color-scheme: dark) { :root { color-scheme: dark; } }
     * i.e. the exact conditional declaration the test was written to reject.
     * Deleting the real unconditional one left the suite green. A regex cannot
     * tell "inside an at-rule" from "at the top level"; it has to be counted.
     */
    private static List<List<Integer>> conditionalRanges(String css) {
        def ranges = []
        def matcher = (css =~ /@(?:media|supports|container)\b/)
        while (matcher.find()) {
            int open = css.indexOf('{' as char as int, matcher.end())
            if (open < 0) continue
            int depth = 0, i = open
            while (i < css.length()) {
                char c = css.charAt(i)
                if (c == '{') { depth++ }
                else if (c == '}') { depth--; if (depth == 0) break }
                i++
            }
            ranges << [matcher.start(), Math.min(i, css.length() - 1)]
        }
        ranges
    }

    private static boolean insideConditional(List<List<Integer>> ranges, int idx) {
        ranges.any { idx >= it[0] && idx <= it[1] }
    }

    /** The selector text of the rule block containing `idx`. */
    private static String enclosingSelector(String css, int idx) {
        int depth = 0, i = idx
        while (i > 0) {                     // walk back to this block's own '{'
            char c = css.charAt(i)
            if (c == '}') { depth++ }
            else if (c == '{') { if (depth == 0) break; depth-- }
            i--
        }
        int open = i
        int start = Math.max(
                css.lastIndexOf('}' as char as int, open - 1),
                css.lastIndexOf('{' as char as int, open - 1))
        css.substring(start + 1, open).trim()
    }

    // ── The shipped-vs-committed guard, over the WHOLE tokenised graph ───────
    //
    // CorrelationIdFilter serves any `/js/**` or `/css/**` URL that carries a
    // `?v=` token as `public, max-age=31536000, immutable`. A browser holding
    // such a URL does not revalidate it for a YEAR — there is no conditional
    // GET, no 304, no way for a deploy to reach it. The token in the referrer
    // IS the cache key, so moving the token is the only cache-bust there is.
    //
    // WHY THIS IS A GRAPH AND NOT A PAIR.
    //
    // The first version of this guard recorded ONE pair — design.css's hash
    // against its ?v= in index.html — and that is how the defect survived it.
    // The tokenised assets form a chain four links deep:
    //
    //     index.html ──?v=──> css/design.css
    //     index.html ──?v=──> js/main.js ──?v=──> js/app.js ──?v=──> js/modals.js
    //                                                  └────?v=──> js/cards.js
    //                                                        modals.js ──?v=──> cards.js
    //
    // and every referrer in it is ITSELF tokenised. So a fix applied to an
    // inner token is invisible to exactly the browsers it was for: measured on
    // this repo, `app.js?v=239` and `modals.js?v=211` were byte-identical to
    // the merge base while BOTH branches had rewritten BOTH files, and the
    // repair for that bumped app.js 239→240 *inside main.js* while leaving
    // `/js/main.js?v=209` in index.html. main.js is tokenised too, so a
    // returning browser never re-fetched main.js and never learned the inner
    // token had moved. The fix was committed, correct, and inert.
    //
    // HOW THE RECORD FORCES THE WHOLE CHAIN TO MOVE.
    //
    // The hash recorded against an asset is a SUBTREE hash: its own bytes plus
    // the subtree hash of every tokenised asset it references. Change cards.js
    // and the subtree hash of modals.js, app.js AND main.js all move with it,
    // so every token above it has to be re-recorded too — which is the same
    // work as bumping it. A chain that moves in one place cannot be recorded.
    //
    // The graph is DISCOVERED, not listed, so a newly tokenised asset is
    // covered the moment someone adds `?v=` to it, and an asset that loses its
    // token shows up as a missing key rather than as silence.
    //
    // IF YOU ARE HERE AFTER EDITING ANY OF THESE FILES: bump the `?v=` on the
    // asset you changed AND on every asset above it in the chain, then paste
    // the block the failure message prints. Do not paste the hashes without
    // bumping the tokens — that records the breakage instead of fixing it.
    private static final Map<String, List<String>> RECORDED_TOKENISED_ASSETS = [
            '/static/css/design.css': ['e9429587f17e', '326'],
            '/static/js/app.js': ['089e9f2de256', '267'],
            '/static/js/cards.js': ['deaabccbee74', '11'],
            '/static/js/main.js': ['2712ad532e6c', '236'],
            '/static/js/modals.js': ['7ac5d6d2e543', '237'],
    ].asImmutable()

    /**
     * A reference of the form `…name.js?v=NNN` / `…name.css?v=NNN`, as written
     * in an `import` specifier, an `href` or a `src`. The leading quote or
     * paren keeps it from matching the prose in this file's own comments when
     * the scanner is pointed at a source file that discusses tokens.
     */
    private static final java.util.regex.Pattern TOKENISED_REF =
            ~/["'(]\s*([A-Za-z0-9_.\/-]+\.(?:js|css|mjs))\?v=([0-9A-Za-z._-]+)/

    /** Resolve a reference exactly as a browser would, relative to its referrer. */
    private static String resolveRef(String referrer, String ref) {
        if (ref.startsWith('/')) return '/static' + ref
        def dir = referrer.substring(0, referrer.lastIndexOf('/'))
        def out = []
        (dir + '/' + ref).split('/').each { String seg ->
            if (seg == '' || seg == '.') return
            if (seg == '..') { if (!out.isEmpty()) out.removeAt(out.size() - 1); return }
            out << seg
        }
        '/' + out.join('/')
    }

    /**
     * Every tokenised edge reachable from index.html, as
     * {@code [referrer, asset, token]}. Read from the CLASSPATH, so a referrer
     * that never made it through processResources fails in {@code read()}
     * rather than being walked past.
     */
    private static List<List<String>> tokenisedEdges() {
        List<List<String>> edges = []
        Set<String> visited = new LinkedHashSet<String>()
        List<String> queue = ['/static/index.html']
        while (!queue.isEmpty()) {
            String from = queue.remove(0)
            if (!visited.add(from)) continue
            def m = TOKENISED_REF.matcher(read(from))
            while (m.find()) {
                String target = resolveRef(from, m.group(1))
                edges << [from, target, m.group(2)]
                queue << target
            }
        }
        edges
    }

    /** referrer -> its tokenised children, deduplicated and ordered. */
    private static Map<String, List<String>> childMap(List<List<String>> edges) {
        Map<String, List<String>> kids = [:]
        edges.each { kids.get(it[0], []) << it[1] }
        kids.each { k, v -> kids[k] = v.unique().toSorted() }
        kids
    }

    private static String sha12(String s) {
        java.security.MessageDigest.getInstance('SHA-256')
                .digest(s.getBytes('UTF-8')).encodeHex().toString().substring(0, 12)
    }

    /**
     * An asset's own bytes plus the subtree hash of every tokenised asset it
     * references. CR is stripped first so a CRLF checkout does not read as a
     * content change (core.autocrlf=true on the machines this is built on).
     */
    private static String subtreeHash(String path, Map<String, List<String>> kids, List<String> onPath) {
        assert !onPath.contains(path):
                "cycle in the tokenised asset graph: ${(onPath + [path]).join(' -> ')}"
        onPath << path
        def acc = new StringBuilder(read(path).replace('\r', ''))
        (kids[path] ?: []).each { String kid ->
            acc.append('\u0000').append(kid).append('=').append(subtreeHash(kid, kids, onPath))
        }
        onPath.removeAt(onPath.size() - 1)
        sha12(acc.toString())
    }

    def "every tokenised asset, and every token ABOVE it in the chain, moves when its content moves"() {
        given:
        def edges = tokenisedEdges()
        def kids = childMap(edges)
        def assets = edges.collect { it[1] }.unique().toSorted()

        expect: "positive control — the walk found the chain rather than nothing"
        // A scanner that matches nothing records an empty map and passes for
        // free, which is how this class of guard usually dies.
        edges.size() >= 5
        assets.size() >= 4
        assets.contains('/static/css/design.css')
        assets.contains('/static/js/main.js')

        and: "no asset is reachable under two different tokens"
        // Two tokens for one file is two immutable cache entries for one file,
        // and a browser that holds the stale one is never told. cards.js is
        // imported from both app.js and modals.js, so this is live coupling.
        def split = edges.groupBy { it[1] }
                .findAll { asset, es -> es.collect { it[2] }.unique().size() > 1 }
        split.isEmpty() ||
                { throw new AssertionError(
                        "an asset is referenced under more than one ?v= token, so one immutable " +
                        "cache entry per token exists and a browser holding the stale one is never " +
                        "told:\n  " + split.collect { asset, es ->
                            asset + '\n    ' + es.collect { "${it[0]} -> ?v=${it[2]}" }.join('\n    ')
                        }.join('\n  ')) }()

        and: "the recorded (subtree-hash, token) pair still holds for every tokenised asset"
        def actual = assets.collectEntries { String a ->
            [(a): [subtreeHash(a, kids, []), edges.find { it[1] == a }[2]]]
        }
        actual == RECORDED_TOKENISED_ASSETS ||
                { throw new AssertionError(
                        "the tokenised asset graph no longer matches its record.\n\n" +
                        "Any `/js/**` or `/css/**` URL with a ?v= is served max-age=31536000, immutable,\n" +
                        "so a returning browser will NOT revalidate it for a year. The token in the\n" +
                        "referrer is the only cache key there is, and every referrer below is itself\n" +
                        "tokenised - so bumping an inner token alone is inert.\n\n" +
                        (assets + RECORDED_TOKENISED_ASSETS.keySet()).unique().toSorted().collect { String a ->
                            def was = RECORDED_TOKENISED_ASSETS[a]
                            def now = actual[a]
                            def mark = (was == now ? '    ' : ' -> ')
                            mark + a + '\n' +
                            mark + '    recorded ' + (was == null ? '(not recorded - newly tokenised?)' : "subtree ${was[0]}  ?v=${was[1]}") + '\n' +
                            mark + '    now      ' + (now == null ? '(absent - did it lose its ?v= token?)' : "subtree ${now[0]}  ?v=${now[1]}")
                        }.join('\n') + "\n\n" +
                        "The lines marked `->` moved. For EACH of them: bump its ?v= in the file that\n" +
                        "references it, and bump the ?v= of every asset ABOVE it in the chain too\n" +
                        "(an inner bump nobody re-fetches the outer file to see is the defect, not the\n" +
                        "fix). Then re-run and paste this block:\n\n" +
                        "    private static final Map<String, List<String>> RECORDED_TOKENISED_ASSETS = [\n" +
                        actual.collect { k, v -> "            '${k}': ['${v[0]}', '${v[1]}']," }.join('\n') + "\n" +
                        "    ].asImmutable()\n\n" +
                        "Verify on the RESPONSE HEADERS of the running app, not by reading source:\n" +
                        "  curl -sI 'http://localhost:PORT/js/main.js?v=<new>'  ->  200 + immutable\n" +
                        "  curl -s  'http://localhost:PORT/' | grep -o 'main.js?v=[0-9]*'") }()
    }

    def "the could-not-load styling still keys on a testid the app actually emits"() {
        given: "the selector design.css uses to make a fault look unlike an empty shelf"
        def css = stripComments(read('/static/css/design.css'))
        def app = read('/static/js/app.js')

        when: 'every [data-testid$="..."] suffix the stylesheet keys on is collected'
        def suffixes = [] as Set
        def m = (css =~ /\[data-testid\$=\s*["']([^"']+)["']\s*\]/)
        while (m.find()) { suffixes << m.group(1) }

        then:
        // This is a coupling across two files owned by two different streams.
        // app.js decides whether a surface is empty or faulted and marks it
        // ('market-load-error' vs 'market-empty'); design.css is the only thing
        // that makes the two LOOK different — same neutral .empty-state markup
        // otherwise. Rename the testid on the JS side and nothing fails, no
        // console warning appears, and a backend 500 silently goes back to
        // being pixel-identical to "there is nothing here" — which reads as
        // "nobody is selling" and sends the user away instead of retrying.
        !suffixes.isEmpty()
        def orphaned = suffixes.findAll { suffix -> !(app =~ /["'][A-Za-z0-9_-]*\Q${suffix}\E["']/) }.sort()
        orphaned.isEmpty() ||
                { throw new AssertionError(
                        "design.css styles the could-not-load state via a testid suffix that app.js no " +
                        "longer emits: ${orphaned.join(', ')}.\n" +
                        "The rule is now dead, so a failed fetch renders identically to an empty result.\n" +
                        "Either restore the testid in app.js or update the selector in design.css.") }()
    }

    def "the colour scheme is stated unconditionally, not only when the OS already agrees"() {
        given:
        def css = stripComments(read('/static/css/design.css'))
        def conditionals = conditionalRanges(css)

        when: "every `color-scheme: dark` is located and classified"
        def unconditionalOnRoot = []
        def onlyConditional = []
        def m = (css =~ /color-scheme\s*:\s*dark\b/)
        while (m.find()) {
            def where = [index: m.start(), selector: enclosingSelector(css, m.start())]
            if (insideConditional(conditionals, m.start())) { onlyConditional << where }
            else if (where.selector.contains(':root') || where.selector.contains('html')) { unconditionalOnRoot << where }
        }

        then: "at least one sits on :root outside every at-rule"
        // Declared only inside `@media (prefers-color-scheme: dark)`, this does
        // nothing for the user whose OS is set to light: the UA then paints form
        // controls, scrollbars, spinners and the canvas light while the
        // stylesheet paints a dark ground — one theme's controls on the other
        // theme's background. The app is single-scheme dark by decision, so it
        // has to say so unconditionally.
        conditionals.size() > 10                 // positive control: the scanner found at-rules
        !onlyConditional.isEmpty()               // positive control: the classifier found the L5992 case
        !unconditionalOnRoot.isEmpty() ||
                { throw new AssertionError(
                        "no unconditional `color-scheme: dark` on :root. " +
                        "${onlyConditional.size()} declaration(s) exist but every one is inside an " +
                        "@media/@supports block, so a light-preference OS never receives it.") }()
    }
}
