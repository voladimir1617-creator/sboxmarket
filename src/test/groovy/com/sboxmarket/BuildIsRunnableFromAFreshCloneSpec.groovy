package com.sboxmarket

import spock.lang.Specification
import spock.lang.Unroll

import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * <b>A clean checkout of this repository must be able to run its own tests.</b>
 *
 * <h3>The measurement that produced this file</h3>
 *
 * A fresh worktree could not run {@code ./gradlew} at all. Every invocation
 * died with
 *
 * <pre>Error: Could not find or load main class org.gradle.wrapper.GradleWrapperMain</pre>
 *
 * because {@code gradle/wrapper/gradle-wrapper.jar} was not committed. An agent
 * had to hand-copy the jar in from another checkout to get a control run at all.
 *
 * <h3>Why it was missing, and why that was an accident rather than a decision</h3>
 *
 * <ul>
 *   <li>{@code gradlew}, {@code gradlew.bat} and
 *       {@code gradle/wrapper/gradle-wrapper.properties} were ALL committed in
 *       the initial commit. A tree that deliberately omitted the wrapper would
 *       not ship the three files whose only purpose is to launch it.</li>
 *   <li>{@code *.jar} entered {@code .gitignore} in that <i>same</i> commit,
 *       under the heading {@code # Build}, beside {@code build/},
 *       {@code .gradle/}, {@code out/}, {@code *.class} and {@code *.war} —
 *       every one of which is something the build PRODUCES. The pattern was
 *       written to keep build output out of the repo and it swallowed the one
 *       jar that is a build INPUT.</li>
 *   <li>The jar had never been committed on any ref
 *       ({@code git log --all -- gradle/wrapper/gradle-wrapper.jar} was
 *       empty), and no README, runbook or contributing note anywhere told a
 *       fresh clone to regenerate it. There was no compensating instruction,
 *       so there was no decision.</li>
 * </ul>
 *
 * <h3>Why this matters more than the bug it hid</h3>
 *
 * A repo whose tests cannot run from a clean clone <b>has no working control</b>.
 * "Reproduced at pristine HEAD" is the sentence that separates a real defect
 * from a local accident, and every such claim here rested on somebody noticing
 * the wrapper was broken and working around it by hand. An unnoticed workaround
 * is an unrecorded difference between the tree that was measured and the tree
 * that is committed.
 *
 * <h3>What this spec can and cannot do</h3>
 *
 * It cannot fail in the situation it describes: with the jar missing the build
 * does not start, so no spec runs — a missing result, which this repo has
 * repeatedly seen read as zero failures. It is a <b>regression</b> guard. It
 * fails in a tree where the build DOES work but the thing that makes a clone
 * work has been removed — the negation deleted from {@code .gitignore}, the jar
 * untracked, the wrapper scripts dropped, or the committed binary swapped for a
 * different one. That is the state that was silently true for the whole life of
 * the repository until now.
 */
class BuildIsRunnableFromAFreshCloneSpec extends Specification {

    /**
     * The four files a clone needs before it can run anything. The set is only
     * useful complete: the three text files are a launcher for the jar, and the
     * jar is unreachable without them.
     */
    static final List<String> WRAPPER_FILES = [
        'gradlew',
        'gradlew.bat',
        'gradle/wrapper/gradle-wrapper.properties',
        'gradle/wrapper/gradle-wrapper.jar',
    ].asImmutable()

    /**
     * sha256 of the committed {@code gradle-wrapper.jar}.
     *
     * A jar that executes on every single build is the highest-trust binary in
     * the tree, and "we looked at it once" is a claim in a commit message rather
     * than a control. Recording the digest turns a swap into a test failure.
     * The bytes behind this digest were inspected: 33 entries, nothing outside
     * {@code META-INF/} and {@code org/}, no nested archive, manifest
     * {@code Implementation-Title: Gradle Wrapper} — asserted structurally below
     * as well, so the digest alone is not the whole argument.
     *
     * Compare against the checksum Gradle publishes for the 8.5 wrapper at
     * https://gradle.org/release-checksums/ before ever changing this line.
     */
    static final String WRAPPER_JAR_SHA256 =
        'd3b261c2820e9e3d8d639ed084900f11f4a86050a8f83342ade7b6bc9b0d2bdd'

    /** Entry prefixes a Gradle wrapper jar is allowed to contain. */
    static final List<String> ALLOWED_JAR_ROOTS = ['META-INF/', 'org/gradle/'].asImmutable()

    /** Run git and report failure AS failure — never a partial answer a caller
     *  could read as an empty-and-therefore-fine result. */
    private static Map git(List<String> args) {
        try {
            def pb = new ProcessBuilder(['git'] + args)
            pb.directory(new File('.').getCanonicalFile())
            Process p = pb.start()
            def out = new StringBuilder(), err = new StringBuilder()
            // Keep the reader threads and join them: waitFor() alone can return
            // before git's output has been copied, so a caller saw empty output.
            Thread outReader = p.consumeProcessOutputStream(out)
            Thread errReader = p.consumeProcessErrorStream(err)
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return [ok: false, why: "`git ${args.join(' ')}` did not finish within 120s"]
            }
            outReader.join(10_000)
            errReader.join(10_000)
            [ok: p.exitValue() == 0, rc: p.exitValue(), out: out.toString(),
             why: p.exitValue() == 0 ? null
                 : "`git ${args.join(' ')}` exited ${p.exitValue()}: ${err.toString().trim()}"]
        } catch (Exception e) {
            [ok: false, why: "could not run `git ${args.join(' ')}`: ${e}"]
        }
    }

    /** The repository root, via git rather than via whatever directory this JVM
     *  happens to sit in — an isolated or flat build dir must not redirect it. */
    private static File repoRoot() {
        def top = git(['rev-parse', '--show-toplevel'])
        assert top.ok, "cannot locate the repository: ${top.why}"
        def f = new File(top.out.trim())
        assert f.isDirectory(), "git reported toplevel `${f}`, which is not a directory"
        f.getCanonicalFile()
    }

    @Unroll
    def "a clone receives #path — it is TRACKED, not merely present on disk"() {
        // `new File(path).exists()` would have passed for the whole life of this
        // defect: the jar was present in every working checkout and absent from
        // every clone. Tracked-ness is the property a clone actually inherits,
        // so tracked-ness is what is asserted.
        given:
        def root = repoRoot()
        def tracked = git(['ls-files', '--error-unmatch', '--', path])

        expect: 'git has it in the index, so `git clone` hands it over'
        tracked.ok
        tracked.out.trim().replace('\\', '/') == path

        and: 'and it is on disk in this checkout too, at a non-zero size'
        def f = new File(root, path)
        f.isFile()
        f.length() > 0

        where:
        path << WRAPPER_FILES
    }

    def "the wrapper jar is not excluded by .gitignore"() {
        // Once tracked, an ignore rule no longer hides a file — but deleting the
        // negation is how the next person reintroduces this defect, because the
        // file then disappears from the repo the moment anyone runs a
        // `git rm --cached` sweep or re-adds the tree. Pin the negation.
        given:
        def ignored = git(['check-ignore', '--no-index', '--', 'gradle/wrapper/gradle-wrapper.jar'])

        expect: '`git check-ignore` finds no rule that excludes it (exit 1 = not ignored)'
        // A blanket `*.jar` under "# Build" is what swallowed it. The negation
        // `!gradle/wrapper/gradle-wrapper.jar` in .gitignore is the repair.
        ignored.rc == 1

        and: 'the negation is actually written down, not merely absent by luck'
        new File(repoRoot(), '.gitignore').getText('UTF-8')
                .readLines().any { it.trim() == '!gradle/wrapper/gradle-wrapper.jar' }
    }

    def "the committed wrapper jar is the reviewed one, byte for byte"() {
        given:
        def jar = new File(repoRoot(), 'gradle/wrapper/gradle-wrapper.jar')
        def digest = java.security.MessageDigest.getInstance('SHA-256')
                .digest(jar.bytes).encodeHex().toString()

        expect:
        digest == WRAPPER_JAR_SHA256 ||
                { throw new AssertionError(
                        "gradle/wrapper/gradle-wrapper.jar is not the reviewed binary.\n" +
                        "  recorded sha256 : ${WRAPPER_JAR_SHA256}\n" +
                        "  on disk         : ${digest}\n\n" +
                        "This jar executes on EVERY build. Do not update this line to match\n" +
                        "whatever is on disk. Check the digest against the checksum Gradle\n" +
                        "publishes for the wrapper at https://gradle.org/release-checksums/\n" +
                        "for the version in gradle/wrapper/gradle-wrapper.properties first.") }()
    }

    def "the committed wrapper jar really is a Gradle wrapper and nothing else"() {
        // The digest says "unchanged since someone looked". This says what was
        // seen when they looked, so the record does not depend on trusting a
        // sentence in a commit message.
        given:
        def jar = new File(repoRoot(), 'gradle/wrapper/gradle-wrapper.jar')
        def zip = new ZipFile(jar)
        def names = zip.entries().collect { it.name }
        def manifest = zip.getEntry('META-INF/MANIFEST.MF')

        expect: 'it is the wrapper launcher'
        names.contains('org/gradle/wrapper/GradleWrapperMain.class')
        manifest != null
        zip.getInputStream(manifest).getText('UTF-8').contains('Gradle Wrapper')

        and: 'and carries nothing outside the two package roots a wrapper needs'
        names.findAll { n -> !ALLOWED_JAR_ROOTS.any { n.startsWith(it) } } == []

        and: 'and embeds no nested archive'
        names.findAll { it.toLowerCase() ==~ /.*\.(jar|zip|dll|so|exe|dylib)$/ } == []

        cleanup:
        zip?.close()
    }

    def "the distribution the wrapper will download is pinned to a version, over https"() {
        given:
        def props = new Properties()
        new File(repoRoot(), 'gradle/wrapper/gradle-wrapper.properties')
                .withInputStream { props.load(it) }
        String url = props.getProperty('distributionUrl')

        expect: 'a clone resolves a specific Gradle build, not "latest", and not over plain http'
        url != null
        url.startsWith('https\\://') || url.startsWith('https://')
        url ==~ /.*gradle-\d+(\.\d+)+(-[a-z0-9]+)?-(bin|all)\.zip$/

        and: 'and the wrapper is told to validate that URL'
        props.getProperty('validateDistributionUrl') == 'true'
    }
}
