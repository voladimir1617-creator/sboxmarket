package com.sboxmarket.config

import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * <b>May this process serve the Agentation visual-feedback toolbar?</b>
 *
 * <h3>What Agentation is, and why it must never ship</h3>
 *
 * Agentation is a browser dev tool: you click an element, type a note, and it
 * emits structured markdown (CSS selector, DOM path, bounding box, computed
 * styles) to paste to a coding agent. It is ~890 KB of vendored React +
 * toolbar that instruments every element on the page and writes annotations
 * into {@code localStorage}. It is for the developer's own browser and nobody
 * else's — a visitor who loads it gets a floating toolbar over a marketplace.
 *
 * <h3>The gate is the same shape as {@link DevLoginGate}, deliberately</h3>
 *
 * The opt-in is a <b>PROCESS ENVIRONMENT VARIABLE</b>, read through
 * {@link DevLoginGate#processEnvValue} — the {@code systemEnvironment}
 * property source and nothing else. Not a Spring property, not a profile, not
 * a {@code -D} flag.
 *
 * That choice is not stylistic. A resolved Spring property can come from
 * {@code application.yml}, {@code application-<profile>.yml}, or any properties
 * file on Spring's config-data search path — which includes
 * {@code optional:file:./config/} and every directory under it, resolved
 * against the JVM's WORKING DIRECTORY, not the classpath.
 *
 * The sibling repo has a <b>measured</b> incident of
 * exactly that: {@code rusty-royale/app-core/config/local/application.properties}
 * is loaded by {@code :app-core:test} as well as {@code bootRun}, and it
 * silently turned a security feature ON inside ~50 test contexts. A profile
 * gate has the same hole: {@code test} is a profile, and so is anything a
 * future harness invents.
 *
 * A variable in the process environment cannot be committed and cannot be
 * inherited by a deployment that never re-decided it. Absence reads as NO,
 * which is the only safe reading of "nobody configured this box yet".
 *
 * <h3>"I cannot tell" is CLOSED, in every direction</h3>
 *
 * A null {@link Environment}, a non-{@code ConfigurableEnvironment}, a missing
 * {@code systemEnvironment} source, a source that throws, an absent variable, a
 * blank one, and any value that is not literally {@code true} ({@code yes},
 * {@code 1}, {@code on}, a typo) all resolve to CLOSED.
 *
 * <h3>How the gate is enforced</h3>
 *
 * {@link Enabled} is a Spring {@link Condition} on {@link AgentationDevConfig}.
 * When it answers false the whole configuration class is skipped, so
 * {@link AgentationAssetFilter} and {@link AgentationInjectionFilter} are never
 * instantiated and no bean of either type exists in the context. There is no
 * "registered but disabled" middle state to get wrong.
 *
 * Pinned by {@code AgentationIsAbsentByDefaultSpec} (HTTP-level: no script tag,
 * no mount node, {@code /__agentation/**} is 404, no beans) and
 * {@code AgentationDevGateSpec} (the gate's own truth table, including that a
 * Spring <i>property</i> of the same name does NOT open it).
 *
 * @see DevLoginGate
 * @see AgentationDevConfig
 */
class AgentationDevGate {

    /**
     * The one name that can turn the toolbar on, read from the PROCESS
     * ENVIRONMENT only.
     *
     * <pre>
     *   # PowerShell
     *   $env:SBOX_AGENTATION = "true"; .\gradlew.bat bootRun --no-daemon
     *   # bash
     *   SBOX_AGENTATION=true ./gradlew bootRun --no-daemon
     * </pre>
     *
     * {@code --no-daemon} matters: with a Gradle daemon alive, {@code bootRun}
     * forks the app from the DAEMON's environment, which predates the variable
     * being set, and the toolbar silently does not appear.
     *
     * It must NOT be added to {@code application.yml}, {@code docker-compose.yml},
     * the {@code Dockerfile} or {@code deploy/skinbox.env.example}.
     */
    static final String OPT_IN_ENV_VAR = 'SBOX_AGENTATION'

    /** The only value that authorises. {@code yes}, {@code 1}, {@code on} and
     *  blank are all refusals — same rule as {@link DevLoginGate#OPT_IN_VALUE}. */
    static final String OPT_IN_VALUE = 'true'

    /** URL prefix the vendored assets are served under while the gate is open,
     *  and which answers 404 in every other process. */
    static final String ASSET_PREFIX = '/__agentation/'

    /** The id of the mount node the toolbar renders into. Named here so the
     *  absence spec asserts on the same string the page would carry. */
    static final String MOUNT_ID = 'agentation-dev-root'

    /**
     * The decision. True only when {@link #OPT_IN_ENV_VAR} is exactly
     * {@code true} (trimmed, case-insensitive) in the PROCESS environment.
     *
     * Delegates the "what counts as the process environment" question to
     * {@link DevLoginGate#processEnvValue} rather than carrying a second copy
     * of it. Two implementations of that rule would be two chances to disagree
     * about it, which is the "correct logic nobody calls" failure this repo
     * keeps paying for.
     */
    static boolean enabled(Environment env) {
        String raw = DevLoginGate.processEnvValue(env, OPT_IN_ENV_VAR)
        raw != null && raw.trim().equalsIgnoreCase(OPT_IN_VALUE)
    }

    /**
     * Spring {@link Condition} form of {@link #enabled}, so the configuration
     * class it guards is not merely disabled but <b>never registered</b>.
     */
    static class Enabled implements Condition {
        @Override
        boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            try {
                return AgentationDevGate.enabled(context?.environment)
            } catch (Throwable unreadable) {
                // A context that throws is the strongest form of "I cannot
                // tell", so it gets the strongest answer: shut.
                return false
            }
        }
    }
}
