package com.sboxmarket

import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * Real Spring {@link org.springframework.core.env.Environment}s for specs,
 * with the PROCESS ENVIRONMENT under the spec's control.
 *
 * <h3>Why not a Stub</h3>
 *
 * {@link com.sboxmarket.config.DevLoginGate} deliberately reads the
 * {@code systemEnvironment} property source rather than the merged property
 * view, because that source is the only one that cannot be committed to the
 * repository. A {@code Stub(Environment)} cannot express that distinction at
 * all — it answers {@code getProperty} and nothing else — so a spec built on
 * one would be testing a different question than the one the gate asks.
 *
 * <h3>Why the real source is REPLACED and not merely added to</h3>
 *
 * The default {@link StandardEnvironment} carries the developer's actual
 * {@code System.getenv()}. A spec built on that would change its answer
 * depending on whose shell ran it: a machine that happens to export
 * {@code SBOX_DEV_LOGIN_ENABLED=true} would see "door open" cases pass for the
 * wrong reason and never notice. Every environment built here starts from an
 * EMPTY process environment and contains exactly what the case asked for.
 */
class SpecEnvs {

    /**
     * @param profiles   active Spring profiles ({@code []} for the bare default)
     * @param processEnv the ENTIRE process environment this deployment can see
     * @param props      ordinary resolved properties — the yml/config-file view
     */
    static ConfigurableEnvironment env(List<String> profiles = [],
                                       Map<String, Object> processEnv = [:],
                                       Map<String, Object> props = [:]) {
        def e = new StandardEnvironment()
        if (profiles) e.setActiveProfiles(profiles as String[])
        e.propertySources.replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new LinkedHashMap<String, Object>(processEnv)))
        if (props) e.propertySources.addFirst(new MapPropertySource('spec', props))
        e
    }

    /** The ordinary local-dev deployment WITH the dev-login opt-in granted:
     *  no Stripe key (so {@code MoneyMode.SIMULATED}) and the one variable set. */
    static ConfigurableEnvironment optedIn(List<String> profiles = [],
                                           Map<String, Object> extraProcessEnv = [:]) {
        env(profiles,
            [(com.sboxmarket.config.DevLoginGate.OPT_IN_ENV_VAR):
                 com.sboxmarket.config.DevLoginGate.OPT_IN_VALUE] + extraProcessEnv)
    }
}
