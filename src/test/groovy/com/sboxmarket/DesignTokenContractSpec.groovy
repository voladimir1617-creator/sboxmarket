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

    // ── The shipped-vs-committed guard ───────────────────────────────────────
    //
    // index.html loads the stylesheet as `/css/design.css?v=NNN` and the static
    // handler sets a 7-day browser cache. The token is hand-maintained; the
    // comment above it in index.html just says "bump on every CSS change".
    //
    // Nothing enforced that, and it is not hypothetical: the focus-ring and
    // nav fixes in this stylesheet were verified byte-correct over HTTP and
    // still did not appear in the browser, because the token had not moved and
    // the cached copy was served for a page that had already been visited. A
    // CSS fix that ships without a token bump reaches no returning visitor for
    // a week, while every check a developer runs — the file on disk, the file
    // over curl, the test suite — says it shipped.
    //
    // So the pair is recorded. Change design.css and this fails until the token
    // moves too. IF YOU ARE HERE AFTER EDITING design.css: bump ?v= in
    // static/index.html, then paste the two values the failure message prints.
    private static final String RECORDED_CSS_SHA = '8f9a7261e505'
    private static final String RECORDED_ASSET_TOKEN = '302'

    private static String cssHash() {
        def text = read('/static/css/design.css').replace('\r', '')
        java.security.MessageDigest.getInstance('SHA-256')
                .digest(text.getBytes('UTF-8'))
                .encodeHex().toString().substring(0, 12)
    }

    private static String assetToken() {
        def m = (read('/static/index.html') =~ /design\.css\?v=([^"']+)/)
        m.find() ? m.group(1) : null
    }

    def "a change to design.css moves the cache-busting token with it"() {
        given:
        def hash = cssHash()
        def token = assetToken()

        expect:
        token != null
        (hash == RECORDED_CSS_SHA && token == RECORDED_ASSET_TOKEN) ||
                { throw new AssertionError(
                        "design.css and/or the ?v= token changed without the recorded pair being updated.\n" +
                        "Returning visitors hold a 7-day cached copy keyed on that token, so a CSS change\n" +
                        "that does not move it is invisible to them however correct the file is.\n\n" +
                        "  design.css sha256-12 : recorded ${RECORDED_CSS_SHA} -> now ${hash}\n" +
                        "  index.html ?v=        : recorded ${RECORDED_ASSET_TOKEN} -> now ${token}\n\n" +
                        "Fix: bump ?v= in src/main/resources/static/index.html (it must differ from\n" +
                        "${RECORDED_ASSET_TOKEN}), then update this spec:\n" +
                        "  RECORDED_CSS_SHA     = '${hash}'\n" +
                        "  RECORDED_ASSET_TOKEN = '<the token you just set>'") }()
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
