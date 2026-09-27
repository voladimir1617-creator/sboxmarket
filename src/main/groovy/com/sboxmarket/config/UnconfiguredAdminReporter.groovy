package com.sboxmarket.config

import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.AuditLogRepository
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.AuditService
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component

/**
 * <b>An ADMIN row that outlives the config that created it must SAY SO.</b>
 *
 * <h3>The measurement this exists for (2026-09-02, live)</h3>
 *
 * {@code STEAM_USERS} held 7 rows, exactly one with {@code ROLE <> 'USER'}:
 * id 33, whose {@code STEAM_ID64} is the literal
 * {@link com.sboxmarket.AdminBootstrapIsNotCommittedSpec#COMMITTED_ID} that
 * used to be baked into {@code application.yml}. The default was removed from
 * config, and — exactly as that spec's last test states — removing it demoted
 * nobody. The row is still ADMIN.
 *
 * <p>Worse, it was ADMIN <i>silently</i>. The audit cross-check below found
 * <b>zero</b> {@code ADMIN_GRANTED} rows for user 33: the promotion happened
 * before {@code AdminService.promoteBootstrapAdmin} learned to write one, so
 * the only trace of the most privileged row in the database was the row
 * itself. A grant that outlives its config and is visible nowhere is this
 * project's signature defect — the same shape as a committed Steam ID whose
 * validator was switched off by the same variable, and as a fix that shipped
 * without being called.</p>
 *
 * <h3>What this reports, and what it deliberately does NOT do</h3>
 *
 * It does <b>not</b> refuse to start, and it does <b>not</b> demote anyone.
 * A persisted admin is very often correct — on this deployment it is the
 * operator's own account, designated by him in the initial commit. Turning a
 * legitimate steady state into a boot failure would train the operator to
 * ignore the signal, and demoting on a config read would hand every lost
 * environment variable the power to lock the operator out of his own admin
 * surface. The defect was never "an admin exists"; it was "an admin exists
 * and nothing anywhere says so". So this is a REPORT, and the whole of its
 * job is to be impossible to miss:
 *
 * <ul>
 *   <li>a WARN banner on {@link ApplicationReadyEvent}, every boot, for as
 *       long as the condition holds;</li>
 *   <li>{@code GET /api/admin/security/admin-grants} for an on-demand read.</li>
 * </ul>
 *
 * <h3>Why not the public health endpoint</h3>
 *
 * {@code /api/health} is anonymous by design and {@code HealthController}
 * already gates {@code startupAt} out of the public body as a recon
 * fingerprint. A list of which Steam IDs hold ADMIN on this deployment is a
 * strictly worse leak — it names the accounts worth attacking. The report is
 * therefore admin-authed, and the loud channel is the boot log, which is where
 * an operator whose watchdog restarts the app every two minutes actually
 * looks.
 *
 * <h3>The finding that makes this actionable</h3>
 *
 * {@link #assess} also reports {@code revocableViaApi}. With ONE admin in the
 * table, {@code AdminService.revokeAdmin} cannot run at all — it is blocked
 * twice over, by {@code CANT_REVOKE_SELF} and then by the {@code LAST_ADMIN}
 * lockout guard. An operator told only "you have an unconfigured admin" would
 * discover that at the moment he tried to act. Reporting it up front is the
 * difference between a warning and a warning you can do something about; the
 * revoke sequence is in {@code deploy/RUNBOOK.md}, "Revoking a persisted
 * admin".
 */
@Component
@Slf4j
class UnconfiguredAdminReporter {

    static final String ADMIN_ROLE = 'ADMIN'

    @Value('${admin.bootstrap-steam-ids:}') String bootstrapIds

    @Autowired(required = false) SteamUserRepository steamUserRepository
    @Autowired(required = false) AuditLogRepository auditLogRepository

    /**
     * The configured bootstrap Steam IDs, parsed exactly the way
     * {@code AdminService.promoteBootstrapAdmin} parses them.
     *
     * Split/trim/drop-empty is duplicated rather than shared because the two
     * call sites must not be able to drift apart silently: if the promotion
     * path ever changes how it reads the list, this reporter's disagreement
     * with it is itself the bug worth seeing.
     */
    static List<String> parseBootstrapIds(String raw) {
        if (!raw) return []
        raw.split(',').collect { it.trim() }.findAll { it }
    }

    /**
     * The whole decision as a pure function, so it is unit-testable with no
     * Spring context and no database — the shape {@code H2CredentialGuard.violation}
     * and {@code LiveMoneyGuard.isRealMoney} already use here.
     *
     * @param admins          one map per persisted ADMIN row, with keys
     *                        {@code userId}, {@code steamId64},
     *                        {@code displayName} and {@code grantAudited}.
     * @param rawBootstrapIds the resolved {@code admin.bootstrap-steam-ids} value.
     */
    static Map assess(List<Map> admins, String rawBootstrapIds) {
        List<Map> rows = admins ?: []
        List<String> configured = parseBootstrapIds(rawBootstrapIds)

        // An admin whose Steam ID is not in the bootstrap list. On a
        // deployment that never set the env var, that is EVERY admin — which
        // is the correct answer, not a bug: none of them is explained by the
        // configuration this process is running.
        List<Map> unconfigured = rows.findAll { !(it.steamId64 in configured) }

        // Separately: an admin with no ADMIN_GRANTED audit row at all. A
        // deliberate grant writes one (grantAdmin), and so does a config grant
        // (with a null actor). Neither wrote one before 2026-09-01, so this
        // flags precisely the grants that predate the audit and cannot be
        // attributed to anyone.
        List<Map> unaudited = rows.findAll { !it.grantAudited }

        [
            adminCount:      rows.size(),
            configuredIds:   configured,
            unconfigured:    unconfigured,
            unaudited:       unaudited,
            // revokeAdmin refuses when countByRole('ADMIN') <= 1 (LAST_ADMIN),
            // and refuses a self-revoke besides. With one admin there is no
            // caller who can run it: the only admin is necessarily the target.
            revocableViaApi: rows.size() > 1,
            clean:           unconfigured.isEmpty() && unaudited.isEmpty()
        ]
    }

    /**
     * Read the persisted admins and cross-check each against the audit log.
     *
     * Best-effort by construction: a reporter that can abort a boot is a gate
     * wearing a reporter's name, and this deployment has already been bitten
     * by a control that turned a diagnostic into an outage. A repository that
     * is missing or throwing yields an empty report and a logged warning, not
     * a failed startup.
     */
    Map currentReport() {
        if (steamUserRepository == null) return assess([], bootstrapIds)
        List<SteamUser> admins
        try {
            admins = steamUserRepository.findByRole(ADMIN_ROLE) ?: []
        } catch (Exception e) {
            log.warn("Admin-grant report could not read STEAM_USERS: ${e.class.name}: ${e.message}")
            return assess([], bootstrapIds)
        }
        def rows = admins.collect { SteamUser u ->
            [userId:       u.id,
             steamId64:    u.steamId64,
             displayName:  u.displayName,
             grantAudited: hasGrantAudit(u.id)]
        }
        assess(rows, bootstrapIds)
    }

    /** True when at least one ADMIN_GRANTED row names this user as subject. */
    private boolean hasGrantAudit(Long userId) {
        if (auditLogRepository == null || userId == null) return false
        try {
            return !auditLogRepository.byUserAndEvent(
                userId, AuditService.ADMIN_GRANTED, PageRequest.of(0, 1)).isEmpty()
        } catch (Exception e) {
            log.warn("Admin-grant report could not read AUDIT_LOG for user ${userId}: ${e.message}")
            // Unknown is reported as "not audited" on purpose. The failure
            // mode this whole class exists to stop is an absence read as a
            // pass; an unreadable audit log must not certify a grant as
            // explained. Over-reporting is recoverable, under-reporting is
            // the defect.
            return false
        }
    }

    @EventListener(ApplicationReadyEvent)
    void reportOnStartup() {
        Map report
        try {
            report = currentReport()
        } catch (Exception e) {
            log.warn("Admin-grant report failed to run: ${e.class.name}: ${e.message}")
            return
        }
        if (report.clean) {
            // Say something even when clean, so a silent boot log is not
            // ambiguous between "nothing to report" and "the reporter never ran".
            log.info("Admin-grant check: ${report.adminCount} ADMIN row(s), all explained by " +
                     'admin.bootstrap-steam-ids and all carrying an ADMIN_GRANTED audit row.')
            return
        }
        log.warn(banner(report))
    }

    /** The startup banner. Split out so a spec can pin the wording. */
    static String banner(Map report) {
        def lines = []
        lines << ''
        lines << '=================================================================='
        lines << ' ADMIN GRANT REPORT — a persisted ADMIN is not explained by config'
        lines << '=================================================================='
        lines << " ADMIN rows in STEAM_USERS : ${report.adminCount}"
        lines << " admin.bootstrap-steam-ids : ${report.configuredIds ? report.configuredIds.join(', ') : '(empty)'}"
        report.unconfigured.each { Map a ->
            lines << " NOT IN BOOTSTRAP LIST     : user id=${a.userId} steamId64=${a.steamId64} name=${a.displayName}"
        }
        report.unaudited.each { Map a ->
            lines << " NO ADMIN_GRANTED AUDIT    : user id=${a.userId} steamId64=${a.steamId64}" +
                     ' — nothing records who granted this, or when'
        }
        lines << ''
        lines << ' STEAM_USERS.ROLE is persisted. Emptying the bootstrap list does not'
        lines << ' demote anyone; only revokeAdmin does. This is a report, not a gate.'
        if (!report.revocableViaApi) {
            lines << ''
            lines << ' REVOKE IS BLOCKED RIGHT NOW. With a single ADMIN row, revokeAdmin'
            lines << ' refuses twice: CANT_REVOKE_SELF, then LAST_ADMIN. Promote a second'
            lines << ' admin first, or go through the database. See deploy/RUNBOOK.md,'
            lines << ' "Revoking a persisted admin".'
        }
        lines << '=================================================================='
        lines.join('\n')
    }
}
