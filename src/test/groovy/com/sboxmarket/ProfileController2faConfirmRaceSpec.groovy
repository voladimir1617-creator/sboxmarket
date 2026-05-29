package com.sboxmarket

import com.sboxmarket.controller.ProfileController
import com.sboxmarket.controller.SteamAuthController
import com.sboxmarket.exception.BadRequestException
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.EmailService
import com.sboxmarket.service.TextSanitizer
import com.sboxmarket.service.TotpService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import spock.lang.Specification
import spock.lang.Subject

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Setup-token re-enroll race on /2fa/confirm.
 *
 * Two concurrent POSTs to /2fa/confirm reach the controller before either
 * has committed. They share:
 *   - the same staged secret (emailVerificationToken = "totp_pending:S")
 *   - the same valid 6-digit code (the user typed it once but the client
 *     double-fired the submit button, or an attacker with a stolen session
 *     races a legit confirm)
 *
 * Pre-fix behaviour: both transactions read totpSecret == null, both
 * verify() succeeds against the staged secret, both call
 * totpService.generateBackupCodes() (non-deterministic — distinct codes
 * each call), and both `save(user)` win. The user is shown the FIRST
 * response's recovery codes, but the SECOND save overwrites the stored
 * hashes with the second response's codes. The user has now copied
 * codes they will never be able to use — their recovery path is gone
 * before they ever needed it.
 *
 * Post-fix behaviour: the controller delegates the commit to a CAS-style
 * @Modifying UPDATE on SteamUserRepository (commit2faEnrollment) whose
 * WHERE clause requires totpSecret IS NULL AND emailVerificationToken =
 * <staged>. At the DB level only ONE concurrent UPDATE matches and
 * returns rowCount == 1; every loser sees rowCount == 0 and the
 * controller maps that to a BadRequestException("NOT_ENROLLING"). The
 * recovery codes the user copied are guaranteed to be the ones the
 * stored hashes match.
 *
 * The spec mocks commit2faEnrollment so it returns 1 exactly once per
 * (uid, expectedStaging) tuple — mirroring the real CAS semantics — and
 * runs N parallel confirms. Exactly one must return a 200 with codes;
 * every other must throw NOT_ENROLLING.
 */
class ProfileController2faConfirmRaceSpec extends Specification {

    SteamUserRepository steamUserRepository = Mock()
    EmailService        emailService        = Mock()
    TotpService         totpService         = new TotpService()
    TextSanitizer       textSanitizer       = new TextSanitizer()

    @Subject
    ProfileController controller = new ProfileController(
        steamUserRepository: steamUserRepository,
        totpService:         totpService,
        emailService:        emailService,
        textSanitizer:       textSanitizer
    )

    HttpServletRequest req = Mock()
    HttpSession        ses = Mock()

    private void authedSession(long uid = 200L) {
        req.session >> ses
        ses.getAttribute(SteamAuthController.SESSION_USER_ID) >> uid
    }

    /** Pure helper — compute the current live TOTP code for a Base32
     *  secret so the test never flakes on a 30s window boundary. */
    private String currentTotpCode(String secretBase32) {
        def m = TotpService.getDeclaredMethod('unbase32', String)
        m.setAccessible(true)
        byte[] decoded = m.invoke(null, secretBase32) as byte[]
        long step = ((System.currentTimeMillis() / 1000L) / 30L) as long
        totpService.codeFor(decoded, step)
    }

    def "concurrent /2fa/confirm with the same staged secret + same code commits exactly once"() {
        given: 'a user mid-enrollment — staged secret only, no live totpSecret yet'
        authedSession(200L)
        def secret = totpService.generateSecret()
        def staged = "totp_pending:${secret}".toString()
        // Each parallel request gets its OWN SteamUser instance — mirrors
        // a real Hibernate session where two concurrent @Transactional
        // methods both load the same row into their own managed entity.
        // Without this, Spock's same-instance return would let the mutation
        // applied by request A be visible to request B's controller code,
        // which is NOT what JPA's read-committed isolation gives us.
        steamUserRepository.findById(200L) >> {
            Optional.of(new SteamUser(
                id: 200L, steamId64: '2', email: 'e@x.com',
                emailVerificationToken: staged,
                totpSecret: null, lastTotpStep: null,
                totpRecoveryCodes: null, sessionEpoch: 0L))
        }
        // CAS contract for the JPQL UPDATE: at most one call per
        // (uid, expectedStaging) can return 1. Every subsequent call
        // for the same tuple returns 0. The real DB enforces this via
        // the WHERE totpSecret IS NULL clause — once one transaction
        // commits the secret, no row matches the predicate.
        def committedFor = new ConcurrentHashMap<String, Boolean>()
        steamUserRepository.commit2faEnrollment(_ as Long, _ as String, _ as String, _ as Long, _ as String) >>
            { Long id, String expected, String sec, Long step, String codes ->
                def key = "${id}|${expected}".toString()
                committedFor.putIfAbsent(key, Boolean.TRUE) == null ? 1 : 0
            }
        def liveCode = currentTotpCode(secret)
        int parallel = 8
        def start = new CountDownLatch(1)
        def done  = new CountDownLatch(parallel)
        def successes = new AtomicInteger(0)
        def notEnrolling = new AtomicInteger(0)
        def otherFailures = new AtomicInteger(0)
        def successResponses = Collections.synchronizedList([])

        when: 'eight threads race the confirm with the same code'
        parallel.times {
            Thread.start {
                try {
                    start.await()
                    def resp = controller.confirm2fa([code: liveCode], req)
                    successes.incrementAndGet()
                    successResponses << resp.body
                } catch (BadRequestException e) {
                    if (e.code == 'NOT_ENROLLING') notEnrolling.incrementAndGet()
                    else otherFailures.incrementAndGet()
                } catch (Throwable t) {
                    otherFailures.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        done.await()

        then: 'exactly one confirm wins — every loser sees NOT_ENROLLING, not a stale 200'
        successes.get() == 1
        notEnrolling.get() == parallel - 1
        otherFailures.get() == 0
        // The winning response contained the exact recovery-code set the
        // CAS update committed — backup codes the user copied are
        // guaranteed to match the stored hashes.
        successResponses.size() == 1
        (successResponses[0] as Map).enabled == true
        ((successResponses[0] as Map).recoveryCodes as List).size() == TotpService.BACKUP_CODE_COUNT
    }
}
