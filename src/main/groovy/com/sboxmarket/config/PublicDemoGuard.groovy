package com.sboxmarket.config

import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

import jakarta.annotation.PostConstruct

/**
 * Startup check for the PUBLIC DEMO profile ({@code application-demo.yml}).
 *
 * The demo is the one deployment a stranger can reach that is not the money
 * deployment, so every developer door has to be provably shut on it, not
 * merely defaulted off. This runs while the context is still being built:
 * a violation fails boot before Tomcat ever accepts a request, which on a
 * PaaS host means the previous (safe) instance keeps serving.
 *
 * Refuses when:
 * <ul>
 *   <li>{@code prod} is active too. Demo and money in one process is exactly
 *       the mix this profile exists to prevent.</li>
 *   <li>Any Stripe secret is configured that is not blank or a TEST key.
 *       Live keys wait for the owner's "go live" and the {@code prod}
 *       profile.</li>
 *   <li>dev-login, dev-credit, the money reset, or the Agentation overlay is
 *       opted in through the process environment.</li>
 *   <li>The H2 console or Swagger is switched on.</li>
 * </ul>
 */
@Component
@Profile('demo')
@Slf4j
class PublicDemoGuard {

    @Autowired
    Environment environment

    @PostConstruct
    void refuseUnsafeDemo() {
        List<String> violations = findViolations(environment)
        if (!violations.isEmpty()) {
            String msg = 'Public demo refused to start:\n  - ' + violations.join('\n  - ')
            log.error(msg)
            throw new IllegalStateException(msg)
        }
        log.info('Public demo checks passed: no live money, dev sign-in off, admin/CSR/payment routes shut.')
    }

    static List<String> findViolations(Environment env) {
        List<String> v = []
        if (env == null) return ['no Environment to check']

        if (env.activeProfiles?.toList()?.contains(MoneyMode.PROD_PROFILE)) {
            v << "the 'prod' profile is active alongside 'demo'"
        }

        for (String name : ['stripe.secret-key', 'STRIPE_SECRET_KEY']) {
            String key = env.getProperty(name)?.trim()
            if (key && !MoneyMode.TEST_PREFIXES.any { key.startsWith(it) }) {
                v << "${name} is set to something other than a Stripe TEST key".toString()
            }
        }

        for (String optIn : [DevLoginGate.OPT_IN_ENV_VAR, DevCreditGate.OPT_IN_ENV_VAR,
                             MoneyResetGate.OPT_IN_ENV_VAR, AgentationDevGate.OPT_IN_ENV_VAR]) {
            String raw = DevLoginGate.processEnvValue(env, optIn) ?: env.getProperty(optIn)
            if (raw != null && raw.trim() && !raw.trim().equalsIgnoreCase('false')) {
                v << "${optIn} is set (must be absent on a public demo)".toString()
            }
        }

        if (env.getProperty('spring.h2.console.enabled')?.trim()?.equalsIgnoreCase('true')) {
            v << 'the H2 console is enabled'
        }
        if (env.getProperty('springdoc.swagger-ui.enabled')?.trim()?.equalsIgnoreCase('true') ||
            env.getProperty('springdoc.api-docs.enabled')?.trim()?.equalsIgnoreCase('true')) {
            v << 'Swagger / api-docs is enabled'
        }
        if (!env.getProperty('sbox.public-demo.enabled')?.trim()?.equalsIgnoreCase('true')) {
            v << 'sbox.public-demo.enabled is not true, so the payment/admin route block would be off'
        }
        return v
    }
}
