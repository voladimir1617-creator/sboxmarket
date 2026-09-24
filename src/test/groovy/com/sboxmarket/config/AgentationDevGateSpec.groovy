package com.sboxmarket.config

import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Environment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import spock.lang.Specification
import spock.lang.Unroll

/**
 * The gate's own truth table.
 *
 * The case this spec exists for is
 * {@link #"a SPRING PROPERTY of the same name does not open the gate"}. The
 * sibling repo has a measured incident where
 * {@code app-core/config/local/application.properties} — a file nobody
 * remembered was on the config-data search path — was loaded by the TEST task
 * as well as {@code bootRun} and silently armed a security feature inside ~50
 * Spring contexts. A property-backed or profile-backed gate has exactly that
 * hole. A process-environment gate does not, and this spec is what pins the
 * difference rather than leaving it to a comment.
 */
class AgentationDevGateSpec extends Specification {

    /** An Environment whose PROCESS-environment source is exactly `processEnv`
     *  and which also carries `otherSources` from somewhere that is not the
     *  process environment (a yml file, a mounted properties file, -D flags). */
    private static ConfigurableEnvironment envWith(Map<String, Object> processEnv,
                                                   Map<String, Object> otherSources = [:]) {
        ConfigurableEnvironment env = new StandardEnvironment()
        env.propertySources.replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, processEnv))
        if (otherSources) {
            env.propertySources.addFirst(new MapPropertySource('a-committed-config-file', otherSources))
        }
        env
    }

    @Unroll
    def "process environment #value.inspect() opens the gate: #expected"() {
        expect:
        AgentationDevGate.enabled(envWith(value == null ? [:] : [(AgentationDevGate.OPT_IN_ENV_VAR): value])) == expected

        where:
        value     || expected
        'true'    || true
        'TRUE'    || true
        'True'    || true
        '  true ' || true
        'false'   || false
        'yes'     || false
        '1'       || false
        'on'      || false
        ''        || false
        '   '     || false
        'truthy'  || false
        null      || false
    }

    def "a SPRING PROPERTY of the same name does not open the gate"() {
        given: 'the opt-in set everywhere EXCEPT the process environment — a committed yml, a mounted properties file, a -D flag'
        ConfigurableEnvironment env = envWith([:], [(AgentationDevGate.OPT_IN_ENV_VAR): 'true'])

        expect: 'the merged Spring view says true...'
        env.getProperty(AgentationDevGate.OPT_IN_ENV_VAR) == 'true'

        and: '...and the gate still says no, because it reads one property source and that is not it'
        !AgentationDevGate.enabled(env)
    }

    def "an active profile cannot open the gate — including test"() {
        given:
        ConfigurableEnvironment env = envWith([:])
        env.setActiveProfiles('test', 'dev', 'local', 'prod')

        expect:
        !AgentationDevGate.enabled(env)
    }

    def "every unreadable Environment is a shut gate"() {
        expect:
        !AgentationDevGate.enabled(null)
        !AgentationDevGate.enabled(Stub(Environment))   // not a ConfigurableEnvironment
    }

    def "an Environment whose property sources THROW is a shut gate, not a stack trace"() {
        given:
        def sources = Stub(org.springframework.core.env.MutablePropertySources) {
            get(_) >> { throw new IllegalStateException('half-initialised property source') }
        }
        ConfigurableEnvironment env = Stub(ConfigurableEnvironment) {
            getPropertySources() >> sources
        }

        expect:
        !AgentationDevGate.enabled(env)
    }

    def "the Condition answers false when the context itself is broken"() {
        given:
        def condition = new AgentationDevGate.Enabled()

        expect:
        !condition.matches(null, null)
    }

    def "the opt-in variable is not committed to any config the app ships with"() {
        given:
        def root = new File('.')
        def shipped = [
            'src/main/resources/application.yml',
            'src/main/resources/application-prod.yml',
            'docker-compose.yml',
            'Dockerfile',
        ].collect { new File(root, it) }.findAll { it.exists() }

        expect: 'at least one of those files really exists, so a rename cannot make this spec vacuous'
        !shipped.isEmpty()

        and:
        shipped.every { File f ->
            // Comment-stripped, so documenting the variable in a comment is fine
            // and only an actual assignment fails.
            !f.readLines()
              .collect { it.replaceAll(/#.*$/, '') }
              .any { it.contains(AgentationDevGate.OPT_IN_ENV_VAR) }
        }
    }
}
