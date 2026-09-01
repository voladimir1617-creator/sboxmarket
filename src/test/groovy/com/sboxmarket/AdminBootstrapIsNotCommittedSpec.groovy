package com.sboxmarket

import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.AdminService
import com.sboxmarket.service.AuditService
import spock.lang.Specification

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
