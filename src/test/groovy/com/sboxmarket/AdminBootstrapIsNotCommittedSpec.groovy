package com.sboxmarket

import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.AuditService
import spock.lang.Specification
import spock.lang.Unroll

import java.util.concurrent.TimeUnit

/**
 * <b>Admin must be a property of the DEPLOYMENT, never of the source.</b>
 *
 * {@code application.yml} shipped a {@code bootstrap-steam-ids} default holding
 * a committed Steam ID that is auto-promoted to ADMIN on login with no env-var
 * setup at all. Anyone who can log in as that account is an admin of every
 * deployment built from this tree.
 *
 * <h3>Why the two original guards did not cover it</h3>
 *
 * They were installed on opposite sides of the same switch:
 *
 * <ul>
 *   <li>{@link com.sboxmarket.config.ProdConfigValidator} lists
 *       {@code ADMIN_BOOTSTRAP_STEAM_IDS} as REQUIRED — but it is
 *       {@code @Profile('prod')}, so it only runs when the prod profile is
 *       active.</li>
 *   <li>The committed default only applied when the prod profile was
 *       <i>not</i> active, because {@code application-prod.yml} overrides the
 *       key with a bare mandatory reference.</li>
 * </ul>
 *
 * So a single lost {@code SPRING_PROFILES_ACTIVE} silenced the validator AND
 * switched the hardcoded admin on, in one move.
 *
 * <h2>2026-09-25 — THE GUARD'S OWN SCOPE WAS THE BLIND SPOT</h2>
 *
 * Every case below used to resolve its paths against {@code new File('.')} —
 * the working directory of whatever JVM happened to run it. A guard that
 * inspects only its own surroundings cannot detect a compromised surrounding,
 * and this one did not:
 *
 * <ul>
 *   <li>A worktree registered to this repository at
 *       {@code .claude/worktrees/agent-a476ed47e53e7f5af}, pinned at the
 *       initial-era commit {@code d5b4c7e}, still carried the committed admin
 *       default in {@code application.yml}, in {@code deploy/skinbox.env.example}
 *       and — a second identity again — in {@code docker-compose.yml}. Its
 *       {@code build/resources/main/application.yml} carried it too, so the
 *       breach was not merely checked out, it had been <i>compiled</i>.</li>
 *   <li>Run from the good tree, this spec never saw it: {@code .claude} was in
 *       {@code PRUNED_DIRS}, so the walk stopped at the door.</li>
 *   <li>Run from inside that worktree, this spec did not exist — it postdates
 *       {@code d5b4c7e}. A full build there produces <b>no result file for it
 *       at all</b>, and a missing result reads as zero failures. That is the
 *       purest form of the defect: absence reported as success.</li>
 * </ul>
 *
 * <h3>Why the known-good-reference option was rejected</h3>
 *
 * The obvious repair is to stop trusting the working tree and assert against a
 * committed reference — the {@code origin/main} blob. That would have been
 * <b>worse than useless here, because it inverts the guard</b>: measured
 * 2026-09-25, {@code origin/main} is itself at {@code d5b4c7e} and <i>carries
 * the committed admin default</i>; {@code refs/heads/main} carries it in three
 * files including {@code deploy/run-local.sh}. The repair lives only on
 * unmerged work branches. Asserting "the tree matches origin/main" would have
 * declared the breach the reference and the repair the deviation.
 *
 * <b>A known-good reference is only known-good if something checks it.</b>
 * Nothing did. So the reference leg below is a ref-sweep that measures which
 * refs are contaminated and requires the repo's own documentation to name
 * exactly that set — not an assumption that any particular ref is clean.
 *
 * <h3>What replaced the single-tree walk</h3>
 *
 * <ol>
 *   <li><b>Every worktree registered to the repository</b> is swept, resolved
 *       through {@code git worktree list --porcelain} rather than through the
 *       filesystem this JVM sits in. Verified: that command enumerates all
 *       siblings <i>from inside any one of them</i>, so the sweep is RED in a
 *       compromised tree as well as about one.</li>
 *   <li><b>Every ref</b> is swept, and the contaminated set must equal the set
 *       the RUNBOOK records.</li>
 *   <li><b>"I could not tell" fails.</b> If git cannot be run, lists nothing,
 *       or does not place this JVM inside one of the trees it named, the
 *       census case below goes RED. It never degrades to a pass.</li>
 * </ol>
 *
 * <h3>What this spec does NOT claim</h3>
 *
 * Removing the default demotes nobody. {@code STEAM_USERS.ROLE} is persisted,
 * so an account already promoted stays ADMIN until {@code revokeAdmin} runs
 * against it. That is asserted below so the limitation is on the record.
 *
 * It also cannot defend a tree that predates it. A checkout old enough not to
 * contain this file runs a suite without it; only removing or updating such a
 * worktree closes that, which is why the census treats every registered
 * worktree as in scope rather than only the one it is standing in.
 */
class AdminBootstrapIsNotCommittedSpec extends Specification {

    /**
     * The literal that was baked into {@code application.yml}.
     *
     * <b>This is a burned identity.</b> It is public on {@code origin/main} and
     * must never be used as an admin identity again. It is named here — and in
     * {@code deploy/RUNBOOK.md} — so that a reader recognises it, and nowhere
     * on the launch surface, so that grepping the launch surface for a bare
     * Steam ID stays a decisive check. See the burn-record cases below.
     */
    static final String COMMITTED_ID = '76561199839805014'

    /** The second identity the same defect produced, as the {@code :-} fallback
     *  of the compose assignment. Equally burned. */
    static final String COMPOSE_ID = '76561197960287930'

    // ── locating the trees, decidably ───────────────────────────────

    /**
     * Generated output, vendored code and scratch state — never a deployment's
     * launch configuration. Pruned by NAME at any depth.
     *
     * {@code .claude} is deliberately NOT pruned any more: the compromised
     * worktree lived at {@code .claude/worktrees/}, and pruning that directory
     * is what let the sweep walk past it.
     */
    static final List<String> PRUNED_DIRS = [
        '.git', '.gradle', '.idea', 'build', 'node_modules',
        'data', '.tmp', '.playwright-mcp', '.ship-blocks', 'out', 'bin',
    ].asImmutable()

    /** A Steam ID 64. Every real one begins 7656119 and runs 17 digits. */
    static final java.util.regex.Pattern BARE_STEAM_ID = ~/7656119\d{10}/

    /** Any assignment of the bootstrap variable, in any of the syntaxes the
     *  launch surface uses: {@code KEY: value} (compose/yml),
     *  {@code KEY=value} (env files, {@code docker run -e}),
     *  {@code bootstrap-steam-ids: value} (Spring). Captures the value. */
    static final java.util.regex.Pattern ASSIGNS_BOOTSTRAP =
        ~/(?m)^.*?(?:ADMIN_BOOTSTRAP_STEAM_IDS|bootstrap-steam-ids)\s*[:=]\s*(.*)$/

    /** A run of digits long enough to be an identity rather than a port, a
     *  timeout or a version. Applied to the VALUE of an assignment, so it
     *  catches any ID format, not only Steam's. */
    static final java.util.regex.Pattern EMBEDDED_IDENTITY = ~/\d{8,}/

    /** The config paths from which a ref can hand an identity to a container. */
    static final List<String> LAUNCH_PATHS = [
        'src/main/resources/application.yml',
        'src/main/resources/application-prod.yml',
        'deploy/skinbox.env.example',
        'docker-compose.yml',
        'deploy/run-local.sh',
    ].asImmutable()

    /** Run git and report failure AS failure. Never returns a partial answer
     *  that a caller could mistake for an empty-and-therefore-clean result. */
    private static Map git(List<String> args) {
        try {
            def pb = new ProcessBuilder(['git'] + args)
            pb.directory(new File('.').getCanonicalFile())
            Process p = pb.start()
            def out = new StringBuilder(), err = new StringBuilder()
            p.consumeProcessOutput(out, err)
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return [ok: false, why: ("`git " + args.join(' ') + "` did not finish within 120s")]
            }
            int rc = p.exitValue()
            if (rc != 0) {
                return [ok: false, why: ("`git " + args.join(' ') + "` exited " + rc + ': ' + err.toString().trim())]
            }
            [ok: true, out: out.toString()]
        } catch (Exception e) {
            return [ok: false, why: ("could not run `git " + args.join(' ') + "`: " + e)]
        }
    }

    /**
     * Every worktree registered to THIS repository, plus the one this JVM is
     * standing in, resolved through git rather than through the filesystem.
     *
     * Returns {@code [ok:false, why:...]} rather than an empty list whenever
     * the question cannot be answered. Callers assert {@code ok} first, so an
     * unanswerable census is RED.
     */
    private static Map census() {
        def listed = git(['worktree', 'list', '--porcelain'])
        if (!listed.ok) return listed

        List<File> present = []
        List<String> absent = []
        listed.out.readLines().each { String line ->
            if (!line.startsWith('worktree ')) return
            def f = new File(line.substring('worktree '.length()).trim())
            if (f.isDirectory()) present << f.getCanonicalFile() else absent << f.path
        }
        present = present.unique()
        if (present.isEmpty()) {
            return [ok: false, why: 'git registered no worktree that exists on disk — the sweep ' +
                                    'would have nothing to walk and would pass for free']
        }

        def top = git(['rev-parse', '--show-toplevel'])
        if (!top.ok) return top
        def here = new File(top.out.trim())
        if (!here.isDirectory()) {
            return [ok: false, why: ('git reported toplevel `' + here + '`, which is not a directory')]
        }
        here = here.getCanonicalFile()
        if (!present.contains(here)) {
            return [ok: false, why: ('this spec is running in `' + here + '`, which is NOT among the ' +
                                     'worktrees git registered (' + present.join(', ') + '). The tree ' +
                                     'under test cannot be identified, so no finding here means ' +
                                     '"unknown", not "clean".')]
        }
        [ok: true, roots: present, here: here, absent: absent]
    }

    private static boolean isLaunchSurface(File f) {
        String n = f.name.toLowerCase()
        if (n.startsWith('dockerfile')) return true
        if (n.startsWith('.env') || n.contains('.env.')) return true
        ['.yml', '.yaml', '.sh', '.ps1', '.bat', '.cmd', '.env',
         '.service', '.properties', '.conf', '.vbs'].any { n.endsWith(it) }
    }

    /** Every launch-surface file under one root, found by walking rather than
     *  by listing. Loop-safe: a directory is visited at most once by canonical
     *  path, so a link back up the tree cannot hang the sweep. */
    private static List<File> launchSurface(File root) {
        List<File> found = []
        Set<String> seen = new HashSet<String>()
        List<File> queue = [root.getCanonicalFile()]
        while (!queue.isEmpty()) {
            File dir = queue.remove(0)
            if (!seen.add(dir.path)) continue
            File[] kids = dir.listFiles()
            if (kids == null) continue
            for (File k : kids) {
                if (k.isDirectory()) {
                    if (!(k.name in PRUNED_DIRS)) queue << k.getCanonicalFile()
                } else if (isLaunchSurface(k)) {
                    found << k
                }
            }
        }
        found
    }

    /** Every launch-surface file across every registered worktree, deduplicated
     *  by canonical path so a worktree nested inside another is reported once. */
    private static List<File> launchSurfaceEverywhere(List<File> roots) {
        Map<String, File> byPath = [:]
        roots.each { File r -> launchSurface(r).each { byPath.putIfAbsent(it.canonicalPath, it) } }
        new ArrayList<File>(byPath.values())
    }

    private static String show(File f) { f.path.replace('\\', '/') }

    // ── the census must be decidable ────────────────────────────────

    def "the worktree census is decidable — 'I could not tell' is a FAILURE, not a pass"() {
        // The whole defect in one case. Before this, the sweep resolved paths
        // against `.` and reported clean from anywhere it could not see. Now
        // the set of trees under test is established through git, and if that
        // cannot be established the guard goes RED rather than quiet.
        given:
        def c = census()

        expect: 'git could be consulted, named at least one real tree, and placed us inside one'
        c.ok
        c.why == null

        and: 'every tree it named that exists on disk is a checkout we can actually inspect'
        c.roots.findAll { !new File(it, 'src/main/resources/application.yml').exists() }
               .collect { show(it) } == []
    }

    def "the sweep reaches real files in EVERY registered worktree, not just the one it runs in"() {
        // Positive control. A sweep that matches nothing passes for free, and a
        // sweep that silently covers one tree out of nine passes for almost
        // free. Assert per root, so a walk that breaks in one tree — a prune
        // that eats too much, an unreadable directory, a wrong root — cannot
        // hide behind the others.
        given:
        def c = census()
        assert c.ok, 'census failed: ' + c.why
        def thin = c.roots.collect { [show(it), launchSurface(it).size()] }.findAll { it[1] < 5 }

        expect: 'no registered tree is swept vacuously'
        thin == []

        and: 'and the tree this JVM is standing in is fully in scope'
        def hereFiles = launchSurface(c.here).collect { show(it) }
        ['src/main/resources/application.yml',
         'src/main/resources/application-prod.yml',
         'deploy/skinbox.env.example',
         'docker-compose.yml',
         'Dockerfile'].findAll { String p -> !hereFiles.any { it.endsWith('/' + p) } } == []

        and: 'and the whole sweep is a real sweep, not a handful of lucky hits'
        launchSurfaceEverywhere(c.roots).size() >= 15
    }

    // ── the decisive checks, across every tree ──────────────────────

    def "no launch file in ANY worktree registered to this repository carries a bare Steam ID"() {
        // The check application.yml's own comment asks for — "grepping the
        // config for a bare Steam ID stays a decisive check" — widened from
        // `.` to every tree someone could build from. This is the case that
        // goes RED inside a compromised worktree, because `git worktree list`
        // enumerates every sibling from inside any one of them.
        given:
        def c = census()
        assert c.ok, 'census failed: ' + c.why

        def offenders = launchSurfaceEverywhere(c.roots).collectMany { File f ->
            def m = BARE_STEAM_ID.matcher(f.text)
            Set<String> ids = new LinkedHashSet<String>()
            while (m.find()) ids.add(m.group())
            ids.collect { show(f) + ' carries Steam ID ' + it }
        }.toSorted()

        expect: 'admin is a property of the DEPLOYMENT, never of the source — in every tree, not just this one'
        offenders == []
    }

    def "no launch file in any registered worktree assigns the bootstrap variable an identity"() {
        // Belt to the case above, and independent of Steam's ID format: the
        // VALUE handed to ADMIN_BOOTSTRAP_STEAM_IDS must be a variable
        // reference, a placeholder or an abort — never a concrete account.
        given:
        def c = census()
        assert c.ok, 'census failed: ' + c.why
        def files = launchSurfaceEverywhere(c.roots)

        def offenders = files.collectMany { File f ->
            def m = ASSIGNS_BOOTSTRAP.matcher(f.text)
            def bad = []
            while (m.find()) {
                def v = m.group(1)
                if (EMBEDDED_IDENTITY.matcher(v).find()) bad << (show(f) + ' -> ' + v.trim())
            }
            bad
        }.toSorted()

        expect: 'no assignment anywhere carries a hardcoded identity'
        offenders == []

        and: 'and the matcher is not dead — it still finds the assignments it is meant to police'
        files.count { ASSIGNS_BOOTSTRAP.matcher(it.text).find() } >= 3
    }

    // ── the config in THIS tree, by name ────────────────────────────

    /** Resolved against the tree git says we are in, not against `.`, so an
     *  isolated or flat build directory cannot redirect these reads. */
    private static String readOrFail(String path) {
        def c = census()
        assert c.ok, 'cannot resolve ' + path + ': ' + c.why
        def f = new File(c.here, path)
        assert f.exists(), 'expected ' + f + ' to exist — cannot verify the bootstrap default without it'
        f.getText('UTF-8')
    }

    /** The `bootstrap-steam-ids:` value from a yaml file, or null if absent. */
    private static String bootstrapValue(String yaml) {
        def m = yaml =~ /(?m)^\s*bootstrap-steam-ids:\s*(\S.*?)\s*$/
        m.find() ? m.group(1) : null
    }

    def "application.yml declares NO default admin — the fallback is empty"() {
        given:
        def value = bootstrapValue(readOrFail('src/main/resources/application.yml'))

        expect: 'the key is still present (an absent key would break prod resolution)'
        value != null

        and: 'and its default is empty, not a committed Steam ID'
        value == '${ADMIN_BOOTSTRAP_STEAM_IDS:}'
    }

    def "application-prod.yml keeps the env var MANDATORY — no default at all"() {
        given:
        def value = bootstrapValue(readOrFail('src/main/resources/application-prod.yml'))

        expect: 'a bare reference with no `:` fallback, so prod cannot resolve it silently'
        value == '${ADMIN_BOOTSTRAP_STEAM_IDS}'
    }

    def "the deploy example no longer advertises a baked-in default"() {
        given:
        def example = readOrFail('deploy/skinbox.env.example')

        expect: 'the operator is not told a default exists, and is not handed the old ID'
        !example.contains(COMMITTED_ID)
        example.contains('ADMIN_BOOTSTRAP_STEAM_IDS=')
    }

    // ── the burn record: the id is public, and must stay named ──────

    /**
     * {@code 76561199839805014} is published on {@code origin/main}. It cannot
     * be unpublished — rewriting that history is not on the table — so the only
     * remaining control is that <b>a person setting up admin access is told not
     * to reuse it.</b> That knowledge lived in a report. These cases move it
     * into the repo and pin it there.
     *
     * The digits live in {@code deploy/RUNBOOK.md}, which is not on the launch
     * surface, so the sweep above stays a decisive grep. The two files an
     * operator actually edits carry a pointer with no digits in it.
     */
    static final String BURN_MARKER = 'BURNED ADMIN IDENTITY'

    def "the RUNBOOK records the burned identities by their digits, and says never to reuse them"() {
        given:
        def runbook = readOrFail('deploy/RUNBOOK.md')

        expect: 'the record exists as a HEADING a reader can navigate to, not just a phrase somewhere'
        // Same lesson as the operator-file case: a bare `contains` survives
        // deletion of the heading, because the marker recurs in prose. Require
        // the markdown heading itself.
        runbook.readLines().any { it =~ /^#{1,3}\s+.*BURNED ADMIN IDENTITY/ }

        and: 'and it states the instruction, not only the title'
        runbook.contains(BURN_SENTENCE)

        and: 'it names both identities the defect published, so a reader can recognise them'
        runbook.contains(COMMITTED_ID)
        runbook.contains(COMPOSE_ID)

        and: 'and states the consequence in words, not by implication'
        runbook.toLowerCase().contains('never be used as an admin identity again')
    }

    /** The sentence that carries the actual instruction. A warning can be
     *  gutted down to its heading and still contain the marker, so the marker
     *  alone is not the pin — this is. (Found by mutation: deleting the heading
     *  left both files green, because the marker also appears in the body
     *  pointing at the RUNBOOK. One token is not a control.) */
    static final String BURN_SENTENCE = 'must never be used as an admin identity again'

    @Unroll
    def "the file an operator edits to set up admin access warns that the old id is burned: #path"() {
        given:
        def text = readOrFail(path)
        def lines = text.readLines()
        // The line where the decision is actually made — the assignment itself,
        // not a comment mentioning it.
        def decisionAt = lines.findIndexOf { String l ->
            !l.trim().startsWith('#') && (l =~ /(?:ADMIN_BOOTSTRAP_STEAM_IDS|bootstrap-steam-ids)\s*[:=]/)
        }

        expect: 'the assignment this warning is about is actually in the file'
        decisionAt >= 0

        and: 'the warning is present, exactly once, as a heading'
        // Exactly once is the assertion, and it is load-bearing. While the
        // uppercase marker also appeared in the body (pointing at the RUNBOOK
        // heading of the same name), DELETING THE HEADING LEFT THIS GREEN — the
        // body occurrence satisfied a `contains` and even the adjacency window.
        // The body references were lowercased so that the uppercase marker is
        // the heading and nothing else, which makes this count decisive.
        text.count(BURN_MARKER) == 1

        and: 'and it says the thing that matters, not just the heading'
        text.contains(BURN_SENTENCE)

        and: 'it sends the reader to the record that holds the digits'
        text.contains('deploy/RUNBOOK.md')

        and: 'and the whole warning sits with the decision, not buried elsewhere in the file'
        // Adjacency is the point. A note 200 lines away from the line you edit
        // is a note nobody setting up admin access will read.
        def window = lines[Math.max(0, decisionAt - 30)..<decisionAt].join('\n')
        window.contains(BURN_MARKER)
        window.contains(BURN_SENTENCE)
        window.contains('deploy/RUNBOOK.md')

        and: 'and it does NOT repeat the digits, so the launch-surface grep stays decisive'
        !BARE_STEAM_ID.matcher(text).find()

        where:
        path << ['deploy/skinbox.env.example', 'src/main/resources/application.yml']
    }

    def "every ref that still carries a burned identity is named in the RUNBOOK"() {
        // The reference-blob trap, turned into a measurement. origin/main is
        // NOT a clean reference — it is where the id was published — and local
        // `main` carries it in three files. Neither can be repaired from here
        // (no push, no history rewrite), so the control is that the
        // contaminated set is WRITTEN DOWN and matches reality: a newly
        // contaminated ref goes RED immediately, and a ref cleaned up without
        // updating the record goes RED too, so the note cannot rot.
        given:
        def refs = git(['for-each-ref', '--format=%(refname)', 'refs/heads', 'refs/remotes', 'refs/tags'])
        assert refs.ok, 'could not enumerate refs: ' + refs.why

        def contaminated = refs.out.readLines().findAll { it.trim() }.findAll { String ref ->
            LAUNCH_PATHS.any { String p ->
                def blob = git(['show', ref + ':' + p])
                blob.ok && BARE_STEAM_ID.matcher(blob.out).find()
            }
        }.toSorted()

        def runbook = readOrFail('deploy/RUNBOOK.md')
        def block = (runbook =~ /(?s)<!-- BURNED-ADMIN-REFS:BEGIN -->(.*?)<!-- BURNED-ADMIN-REFS:END -->/)
        assert block.find(), 'deploy/RUNBOOK.md has no BURNED-ADMIN-REFS block — ' +
                             'the set of contaminated refs is not recorded anywhere'
        def recorded = (block.group(1) =~ /`([^`]+)`/).collect { it[1] }.toSorted()

        expect: 'the refs that carry it are exactly the refs the RUNBOOK says carry it'
        contaminated == recorded
    }

    // ── the every-login promotion, now audited ──────────────────────

    SteamUserRepository steamUserRepository = Mock()
    AuditService auditService = Mock()

    private AdminService svc(String ids) {
        new AdminService(
            steamUserRepository: steamUserRepository,
            auditService:        auditService,
            bootstrapIds:        ids
        )
    }

    def "an empty bootstrap list promotes nobody — the state after removing the default"() {
        given:
        def user = new SteamUser(id: 33L, steamId64: COMMITTED_ID, role: 'USER')

        when:
        svc('').promoteBootstrapAdmin(user)

        then:
        user.role == 'USER'
        0 * steamUserRepository.save(_)
        0 * auditService.log(*_)
    }

    def "a config grant writes an ADMIN_GRANTED audit row with a NULL actor"() {
        // This path is the ONLY way STEAM_USERS.ROLE reaches ADMIN with no
        // admin in the loop, and it used to write nothing but a log line — so
        // a persisted ADMIN role was indistinguishable from a deliberate
        // grant. A null actor is the honest record: no person approved it.
        given:
        def user = new SteamUser(id: 33L, steamId64: COMMITTED_ID, role: 'USER')

        when:
        svc(COMMITTED_ID).promoteBootstrapAdmin(user)

        then:
        user.role == 'ADMIN'
        1 * steamUserRepository.save(user)
        1 * auditService.log(AuditService.ADMIN_GRANTED, null, 33L, null, { String s ->
            s.contains(COMMITTED_ID) && s.contains('bootstrap')
        })
    }

    def "re-login by an already-ADMIN bootstrap user writes NOTHING — the audit log must not be spammed"() {
        // promoteBootstrapAdmin runs on EVERY login. Auditing the no-op would
        // bury the one event that matters under a row per sign-in.
        given:
        def user = new SteamUser(id: 33L, steamId64: COMMITTED_ID, role: 'ADMIN')

        when:
        svc(COMMITTED_ID).promoteBootstrapAdmin(user)

        then:
        0 * steamUserRepository.save(_)
        0 * auditService.log(*_)
    }

    def "a failing audit write does not break the login"() {
        // AuditService.log runs reads on the caller's transaction; a transient
        // failure there must not roll back a sign-in.
        given:
        def user = new SteamUser(id: 33L, steamId64: COMMITTED_ID, role: 'USER')
        auditService.log(*_) >> { throw new RuntimeException('audit sink down') }

        when:
        svc(COMMITTED_ID).promoteBootstrapAdmin(user)

        then:
        noExceptionThrown()
        user.role == 'ADMIN'
        1 * steamUserRepository.save(user)
    }

    def "removing the default does NOT demote an existing admin — only revokeAdmin does"() {
        // The honest limitation. User 33 is ADMIN in the database; emptying the
        // config leaves that row exactly as it was. Anyone reading the fix as
        // "the hardcoded admin is gone" is reading it wrong.
        given: 'a user already persisted as ADMIN, and an empty bootstrap list'
        def user = new SteamUser(id: 33L, steamId64: COMMITTED_ID, role: 'ADMIN')

        when:
        svc('').promoteBootstrapAdmin(user)

        then: 'still ADMIN — the empty default is not a demotion mechanism'
        user.role == 'ADMIN'
        0 * steamUserRepository.save(_)
    }
}
