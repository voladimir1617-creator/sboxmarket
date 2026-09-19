package com.sboxmarket

import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.AuditService
import spock.lang.Specification
import spock.lang.Unroll

/**
 * <b>Admin must be a property of the DEPLOYMENT, never of the source.</b>
 *
 * {@code application.yml} shipped
 * {@code bootstrap-steam-ids: ${ADMIN_BOOTSTRAP_STEAM_IDS:76561199839805014}} —
 * a committed Steam ID that is auto-promoted to ADMIN on login with no env-var
 * setup at all. Anyone who can log in as that account is an admin of every
 * deployment built from this tree.
 *
 * <h3>Why the two existing guards did not cover it</h3>
 *
 * They were installed on opposite sides of the same switch:
 *
 * <ul>
 *   <li>{@link com.sboxmarket.config.ProdConfigValidator} lists
 *       {@code ADMIN_BOOTSTRAP_STEAM_IDS} as REQUIRED — but it is
 *       {@code @Profile('prod')}, so it only runs when the prod profile is
 *       active.</li>
 *   <li>The committed default only applies when the prod profile is
 *       <i>not</i> active, because {@code application-prod.yml} overrides the
 *       key with a bare {@code ${ADMIN_BOOTSTRAP_STEAM_IDS}}.</li>
 * </ul>
 *
 * So a single lost {@code SPRING_PROFILES_ACTIVE} silenced the validator AND
 * switched the hardcoded admin on, in one move. Two controls that look like
 * defence in depth but fail together are one control. The fix is to remove the
 * hazard rather than add a third guard: with an empty default, losing the
 * profile yields NO admin instead of a committed one.
 *
 * <h3>What this spec does NOT claim</h3>
 *
 * Removing the default demotes nobody. {@code STEAM_USERS.ROLE} is persisted,
 * so an account already promoted stays ADMIN until {@code revokeAdmin} runs
 * against it. That is asserted below so the limitation is on the record rather
 * than assumed.
 */
class AdminBootstrapIsNotCommittedSpec extends Specification {

    /** The literal that used to be baked into application.yml. Named here so
     *  re-introducing it anywhere in the default config turns this spec RED. */
    static final String COMMITTED_ID = '76561199839805014'

    private static String readOrFail(String path) {
        def f = new File(path)
        assert f.exists(), "expected ${path} to exist — cannot verify the bootstrap default without it"
        f.getText('UTF-8')
    }

    /** The `bootstrap-steam-ids:` value from a yaml file, or null if absent. */
    private static String bootstrapValue(String yaml) {
        def m = yaml =~ /(?m)^\s*bootstrap-steam-ids:\s*(\S.*?)\s*$/
        m.find() ? m.group(1) : null
    }

    // ── the config itself ───────────────────────────────────────────

    def "application.yml declares NO default admin — the fallback is empty"() {
        given:
        def value = bootstrapValue(readOrFail('src/main/resources/application.yml'))

        expect: 'the key is still present (an absent key would break prod resolution)'
        value != null

        and: 'and its default is empty, not a committed Steam ID'
        value == '${ADMIN_BOOTSTRAP_STEAM_IDS:}'
    }

    def "no Steam ID is committed as an admin default anywhere in the shipped config"() {
        expect: 'the ID that used to be baked in appears in neither config file'
        !readOrFail('src/main/resources/application.yml').contains(COMMITTED_ID)
        !readOrFail('src/main/resources/application-prod.yml').contains(COMMITTED_ID)
    }

    def "application-prod.yml keeps the env var MANDATORY — no default at all"() {
        given:
        def value = bootstrapValue(readOrFail('src/main/resources/application-prod.yml'))

        expect: 'a bare ${VAR} with no `:` fallback, so prod cannot resolve it silently'
        value == '${ADMIN_BOOTSTRAP_STEAM_IDS}'
    }

    def "the deploy example no longer advertises a baked-in default"() {
        given:
        def example = readOrFail('deploy/skinbox.env.example')

        expect: 'the operator is not told a default exists, and is not handed the old ID'
        !example.contains(COMMITTED_ID)
        example.contains('ADMIN_BOOTSTRAP_STEAM_IDS=')
    }

    // ── EVERY LAUNCH SURFACE, NOT THE THREE FILES WE REMEMBERED ─────

    /**
     * The four cases above name three files by hand. That is the whole
     * weakness: they pin the places the committed ID <i>used</i> to live, and
     * say nothing about the place it moves to next.
     *
     * It moved. On 2026-09-17 the ID was gone from all three, every one of
     * those cases was green, and {@code docker-compose.yml} — the file that
     * actually starts production, {@code SPRING_PROFILES_ACTIVE: prod} on the
     * line above — read:
     *
     * <pre>ADMIN_BOOTSTRAP_STEAM_IDS: ${ADMIN_BOOTSTRAP_STEAM_IDS:-76561197960287930}</pre>
     *
     * and {@code deploy/run-local.sh}, which is what serves skinbox.market
     * through the tunnel, read {@code -e ADMIN_BOOTSTRAP_STEAM_IDS=76561199839805014}
     * — the literal this spec's own {@code COMMITTED_ID} constant names.
     *
     * <h3>It is the same defect, not a cousin</h3>
     *
     * {@code application-prod.yml} resolves {@code ${ADMIN_BOOTSTRAP_STEAM_IDS}}
     * with no fallback of its own, so whatever compose substitutes IS the
     * admin list. Absent variable → compose supplies an identity → the app
     * grants ADMIN to it on login, on every login. An absence resolved to a
     * grant, which is the one thing this file exists to forbid.
     *
     * And the two controls failed together again, the other way round from
     * last time: {@link com.sboxmarket.config.ProdConfigValidator} requires
     * {@code ADMIN_BOOTSTRAP_STEAM_IDS} to be present and non-blank, and the
     * compose default SATISFIES that check. One line both supplied the
     * hardcoded admin and silenced the validator that was supposed to catch a
     * missing one.
     *
     * <h3>Why these cases are shaped differently</h3>
     *
     * A named-file list cannot catch a relocation, so these two walk the tree
     * and match on file NAME SHAPE — every compose file, Dockerfile, env file,
     * unit file, shell/PowerShell launcher and Spring yml, wherever it lives.
     * A launcher added tomorrow in a directory nobody has thought of yet is
     * covered on the day it lands, without anyone remembering to extend a list.
     */

    /** Generated output, vendored code and scratch state — never a
     *  deployment's launch configuration. Pruned by NAME at any depth. */
    static final List<String> PRUNED_DIRS = [
        '.git', '.gradle', '.idea', 'build', 'node_modules', '.claude',
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

    private static boolean isLaunchSurface(File f) {
        String n = f.name.toLowerCase()
        if (n.startsWith('dockerfile')) return true
        if (n.startsWith('.env') || n.contains('.env.')) return true
        ['.yml', '.yaml', '.sh', '.ps1', '.bat', '.cmd', '.env',
         '.service', '.properties', '.conf', '.vbs'].any { n.endsWith(it) }
    }

    /** Every file on the launch surface, found by walking rather than by
     *  listing. Returns absolute-free relative paths for readable failures. */
    private static List<File> launchSurface() {
        List<File> found = []
        List<File> queue = [new File('.')]
        while (!queue.isEmpty()) {
            File dir = queue.remove(0)
            File[] kids = dir.listFiles()
            if (kids == null) continue
            for (File k : kids) {
                if (k.isDirectory()) {
                    if (!(k.name in PRUNED_DIRS)) queue << k
                } else if (isLaunchSurface(k)) {
                    found << k
                }
            }
        }
        found
    }

    private static String rel(File f) {
        f.path.replace('\\', '/').replaceFirst(/^\.\//, '')
    }

    def "the launch-surface walk actually finds the files it claims to check"() {
        // A sweep that matches nothing passes for free. This is the positive
        // control for the two cases below: if the walk breaks — wrong working
        // directory, a prune that eats too much, an extension list that stops
        // matching — they go vacuously green and the next committed admin
        // ships. Assert the walk reaches the files whose contents the
        // hand-written cases above already depend on.
        given:
        def paths = launchSurface().collect { rel(it) } as Set

        expect: 'the two files that actually start this platform are in scope'
        'docker-compose.yml' in paths
        'deploy/run-local.sh' in paths

        and: 'so are the Spring configs, the image build and the env template'
        'src/main/resources/application.yml' in paths
        'src/main/resources/application-prod.yml' in paths
        'Dockerfile' in paths
        'deploy/skinbox.env.example' in paths

        and: 'and the surface is a real sweep, not a handful of lucky hits'
        paths.size() >= 15
    }

    def "no launch file anywhere in the tree carries a bare Steam ID"() {
        // The decisive check application.yml's own comment asks for: "the ID is
        // deliberately not repeated here even as prose, so that grepping the
        // config for a bare Steam ID stays a decisive check". This is that grep,
        // over every file that can hand a value to a running container.
        given:
        def offenders = launchSurface().findAll { BARE_STEAM_ID.matcher(it.text).find() }
            .collect { File f ->
                def m = BARE_STEAM_ID.matcher(f.text)
                m.find()
                "${rel(f)} contains Steam ID ${m.group()}".toString()
            }

        expect: 'admin is a property of the DEPLOYMENT, never of the source'
        offenders == []
    }

    @Unroll
    def "no launch file assigns the bootstrap variable an identity: #path"() {
        // Belt to the case above, and independent of Steam's ID format: the
        // VALUE handed to ADMIN_BOOTSTRAP_STEAM_IDS must be a variable
        // reference, a placeholder, or nothing — never a concrete account.
        // `${ADMIN_BOOTSTRAP_STEAM_IDS:-76561197960287930}` is an assignment
        // whose value embeds an identity, and that is what this rejects.
        given:
        def m = ASSIGNS_BOOTSTRAP.matcher(new File(path).text)
        def values = []
        while (m.find()) values << m.group(1)

        expect: 'at least one assignment, so a renamed key cannot make this vacuous'
        !values.isEmpty()

        and: 'and no assignment carries a hardcoded identity'
        values.findAll { EMBEDDED_IDENTITY.matcher(it).find() } == []

        where:
        path << [
            'docker-compose.yml',
            'deploy/run-local.sh',
            'src/main/resources/application.yml',
            'src/main/resources/application-prod.yml',
            'deploy/skinbox.env.example',
        ]
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
