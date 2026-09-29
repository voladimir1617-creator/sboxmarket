package com.sboxmarket

import com.sboxmarket.config.LiveMoneyGuard
import com.sboxmarket.controller.ProfileController
import com.sboxmarket.service.EmailService
import spock.lang.Specification

/**
 * Without SMTP the verification token is echoed in the JSON so local dev can
 * verify an address. On a real-money deployment that echo let anyone set a
 * mailbox they don't own and "verify" it, clearing the verified-email gate
 * on withdrawals.
 */
class EmailTokenEchoSpec extends Specification {

    /** A real guard over a real Environment: the prod profile always
     *  counts as real money, no profile and no Stripe key is SIMULATED. */
    private static LiveMoneyGuard guard(boolean prodProfile) {
        def env = new org.springframework.core.env.StandardEnvironment()
        if (prodProfile) env.setActiveProfiles('prod')
        new LiveMoneyGuard(environment: env)
    }

    EmailService noSmtp = Stub(EmailService)

    def "no SMTP and a real-money deployment: the token is not echoed"() {
        expect:
        !new ProfileController(emailService: noSmtp, liveMoneyGuard: guard(true)).echoVerificationToken()
    }

    def "no SMTP on a test or dev deployment: the token is still echoed"() {
        expect:
        new ProfileController(emailService: noSmtp, liveMoneyGuard: guard(false)).echoVerificationToken()
        new ProfileController(emailService: noSmtp).echoVerificationToken()
    }
}
