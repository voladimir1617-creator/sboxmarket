package com.sboxmarket

import com.sboxmarket.config.UnconfiguredAdminReporter
import com.sboxmarket.controller.AdminController
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import spock.lang.Specification
import spock.lang.Unroll

/**
 * <b>An ADMIN row that no config explains must be REPORTED, on every boot.</b>
 *
 * <h3>The gap this closes</h3>
 *
 * {@link AdminBootstrapIsNotCommittedSpec} removed the committed Steam ID from
 * the shipped config and states its own limitation in its last test: removing
 * the default <i>does not demote an existing admin</i>. That limitation was
 * live. Measured against the running database on 2026-09-02, {@code STEAM_USERS}
 * held 7 rows and exactly one with {@code ROLE <> 'USER'} — id 33, carrying
 * {@link AdminBootstrapIsNotCommittedSpec#COMMITTED_ID}, with
 * {@code admin.bootstrap-steam-ids} resolved empty and
 * {@code ADMIN_BOOTSTRAP_STEAM_IDS} set in neither the User nor the Machine
 * environment. The grant had outlived the configuration that created it.
 *
 * <p>And it was invisible. The audit log held <b>zero</b> {@code ADMIN_GRANTED}
 * rows for user 33 — only {@code USER_SIGN_IN} — because the promotion predates
 * the audit write. The most privileged row in the database was attested by
 * nothing but itself.</p>
 *
 * <h3>What is asserted here</h3>
 *
 * Not that the admin is wrong — on this deployment it is the operator's own
 * account. That the situation is <b>reported</b>: on the config axis, on the
 * audit axis, and — the part that makes it actionable — that the revoke path
 * is blocked while it is the only admin.
 *
 * <p>Verified RED by mutation, not by assumption: see the mutations recorded in
 * this file's companion notes in {@code deploy/RUNBOOK.md}. Each assertion
 * below fails by name when the corresponding branch of
 * {@link UnconfiguredAdminReporter} is weakened.</p>
 */
class PersistedAdminIsReportedSpec extends Specification {

    /** The literal that used to be baked into application.yml. Taken FROM the
     *  existing spec rather than restated, so the two cannot drift apart. */
    static final String COMMITTED_ID = AdminBootstrapIsNotCommittedSpec.COMMITTED_ID

    private static Map admin(Map overrides = [:]) {
        [userId: 33L, steamId64: COMMITTED_ID, displayName: 'operator', grantAudited: true] + overrides
    }

    // ── the live shape, reproduced exactly ──────────────────────────

    def "the measured live state is reported on BOTH axes at once"() {
        given: '''one ADMIN row carrying the formerly-committed ID, an empty
                  bootstrap list, and no ADMIN_GRANTED audit row — the state
                  read out of the running database on 2026-09-02.'''
        def report = UnconfiguredAdminReporter.assess(
            [admin(grantAudited: false)], '')

        expect: 'the config axis: nothing in the running configuration explains this row'
        report.unconfigured*.userId == [33L]

        and: 'the audit axis: nothing records who granted it, or when'
        report.unaudited*.userId == [33L]

        and: 'so the report is not clean, and the banner will fire'
        !report.clean
    }

    // ── the config axis ─────────────────────────────────────────────

    def "an admin whose Steam ID IS in the bootstrap list is not flagged as unconfigured"() {
        given: 'the standing config grant — explained by the configuration in force'
        def report = UnconfiguredAdminReporter.assess([admin()], COMMITTED_ID)

        expect:
        report.unconfigured.isEmpty()
        report.clean
    }

    def "emptying the bootstrap list turns a previously-explained admin into a reported one"() {
        // This is the transition the whole class exists for: the config
        // changed, the row did not, and nothing used to say so.
        given:
        def explained   = UnconfiguredAdminReporter.assess([admin()], COMMITTED_ID)
        def orphaned    = UnconfiguredAdminReporter.assess([admin()], '')

        expect:
        explained.unconfigured.isEmpty()
        orphaned.unconfigured*.steamId64 == [COMMITTED_ID]
    }

    def "only the unexplained admin is named — a configured one is not swept up with it"() {
        given: 'two admins, one in the list and one not'
        def report = UnconfiguredAdminReporter.assess(
            [admin(userId: 33L, steamId64: COMMITTED_ID),
             admin(userId: 44L, steamId64: '76561199000000009')],
            COMMITTED_ID)

        expect: 'exactly the one the config does not account for'
        report.unconfigured*.userId == [44L]
    }

    // ── the audit axis ──────────────────────────────────────────────

    def "an admin with no ADMIN_GRANTED row is reported even when the config DOES explain it"() {
        given: '''in the bootstrap list, so the config axis is satisfied — but
                  still attributable to nobody. The two axes are independent and
                  a pass on one must not mask the other.'''
        def report = UnconfiguredAdminReporter.assess(
            [admin(grantAudited: false)], COMMITTED_ID)

        expect:
        report.unconfigured.isEmpty()
        report.unaudited*.userId == [33L]
        !report.clean
    }

    def "an audited, configured admin is clean — the reporter is not permanently noisy"() {
        // A signal that fires forever is one the operator stops reading.
        expect:
        UnconfiguredAdminReporter.assess([admin()], COMMITTED_ID).clean
    }

    def "no admins at all is clean"() {
        expect:
        UnconfiguredAdminReporter.assess([], '').clean
        UnconfiguredAdminReporter.assess(null, '').adminCount == 0
    }

    // ── the part that makes it actionable ───────────────────────────

    def "a single ADMIN row is reported as NOT revocable through the API"() {
        // AdminService.revokeAdmin refuses twice over when there is one admin:
        // CANT_REVOKE_SELF (the only admin is necessarily the target) and then
        // LAST_ADMIN (countByRole('ADMIN') <= 1). An operator told only "you
        // have an unconfigured admin" would find that out at the moment he
        // tried to act on it.
        expect:
        !UnconfiguredAdminReporter.assess([admin()], '').revocableViaApi
    }

    def "two ADMIN rows restore the revoke path"() {
        expect:
        UnconfiguredAdminReporter.assess(
            [admin(userId: 33L), admin(userId: 44L, steamId64: '76561199000000009')],
            '').revocableViaApi
    }

    // ── the banner an operator actually reads ───────────────────────

    def "the banner names the user, the Steam ID, and both axes"() {
        given:
        String text = UnconfiguredAdminReporter.banner(
            UnconfiguredAdminReporter.assess([admin(grantAudited: false)], ''))

        expect: 'the row is identified precisely enough to act on'
        text.contains('id=33')
        text.contains(COMMITTED_ID)

        and: 'both findings are stated, not just the first one'
        text.contains('NOT IN BOOTSTRAP LIST')
        text.contains('NO ADMIN_GRANTED AUDIT')

        and: 'and the empty config is shown as empty rather than omitted'
        text.contains('(empty)')
    }

    def "the banner tells the operator the revoke is BLOCKED while there is one admin"() {
        given:
        String text = UnconfiguredAdminReporter.banner(
            UnconfiguredAdminReporter.assess([admin(grantAudited: false)], ''))

        expect: 'both refusal codes by name, so the 400 is not a surprise'
        text.contains('CANT_REVOKE_SELF')
        text.contains('LAST_ADMIN')

        and: 'and where the sequence lives'
        text.contains('deploy/RUNBOOK.md')
    }

    def "the banner does NOT carry the revoke-blocked warning once a second admin exists"() {
        given:
        String text = UnconfiguredAdminReporter.banner(
            UnconfiguredAdminReporter.assess(
                [admin(userId: 33L, grantAudited: false),
                 admin(userId: 44L, steamId64: '76561199000000009', grantAudited: false)],
                ''))

        expect: 'stale advice is worse than none — the block is gone, so the text is too'
        !text.contains('REVOKE IS BLOCKED RIGHT NOW')
    }

    // ── list parsing, shared with the promotion path ────────────────

    @Unroll
    def "bootstrap list #label parses to #expected"() {
        expect:
        UnconfiguredAdminReporter.parseBootstrapIds(raw) == expected

        where:
        label                | raw                    | expected
        'null'               | null                   | []
        'empty'              | ''                     | []
        'a lone comma'       | ','                    | []
        'one id'             | '111'                  | ['111']
        'two ids'            | '111,222'              | ['111', '222']
        'padded'             | ' 111 , 222 '          | ['111', '222']
        'a trailing comma'   | '111,'                 | ['111']
    }

    def "a padded config entry still EXPLAINS the matching admin"() {
        // Whitespace in an env var must not make a legitimate admin look
        // orphaned — a false alarm here is what teaches an operator to ignore
        // the real one.
        expect:
        UnconfiguredAdminReporter.assess([admin()], "  ${COMMITTED_ID}  ").unconfigured.isEmpty()
    }

    // ── the registration, not merely the code ───────────────────────

    def "the reporter is a @Component — this repo has shipped correct code nothing loaded"() {
        expect: '''component-scanned from com.sboxmarket, same as ProdConfigValidator.
                   Without the annotation the class is inert and every assertion
                   above is about a bean that never exists.'''
        UnconfiguredAdminReporter.class.isAnnotationPresent(Component)
    }

    def "the startup hook is an @EventListener on ApplicationReadyEvent"() {
        given:
        def m = UnconfiguredAdminReporter.getDeclaredMethod('reportOnStartup')

        expect: 'annotated, so Spring actually calls it'
        m.isAnnotationPresent(org.springframework.context.event.EventListener)

        and: 'and bound to the event that fires after the context is usable'
        m.getAnnotation(org.springframework.context.event.EventListener)
         .value().contains(org.springframework.boot.context.event.ApplicationReadyEvent)
    }

    def "the on-demand endpoint is MAPPED, not merely written"() {
        given: 'reflection over the controller, so a missing annotation fails here'
        def m = AdminController.getDeclaredMethod(
            'adminGrants', jakarta.servlet.http.HttpServletRequest)

        expect:
        m.isAnnotationPresent(GetMapping)
        m.getAnnotation(GetMapping).value().toList() == ['/security/admin-grants']
    }

    def "the endpoint sits under the admin-authed /api/admin prefix, not a public path"() {
        given: '''naming which Steam IDs hold ADMIN tells an attacker exactly which
                  accounts are worth taking. HealthController already withholds a
                  far weaker fingerprint (startupAt) from anonymous callers.'''
        def mapping = AdminController.class.getAnnotation(
            org.springframework.web.bind.annotation.RequestMapping)

        expect:
        mapping.value().toList() == ['/api/admin']
    }
}
