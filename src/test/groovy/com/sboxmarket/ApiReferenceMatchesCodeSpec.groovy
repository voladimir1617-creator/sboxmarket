package com.sboxmarket

import com.fasterxml.jackson.databind.ObjectMapper
import com.sboxmarket.config.ApiKeyAuthFilter
import com.sboxmarket.model.SteamUser
import com.sboxmarket.repository.SteamUserRepository
import com.sboxmarket.service.ApiKeyService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import spock.lang.Shared
import spock.lang.Specification

/**
 * /api-reference.html must describe the API as the code serves it: every
 * route it lists exists, is open to API keys, and carries the scope the
 * filter really enforces; the "website only" list is the filter's own
 * refusal list; the error codes are the ones the filters write.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiReferenceMatchesCodeSpec extends Specification {

    static final String PAGE = 'src/main/resources/static/api-reference.html'

    @Autowired MockMvc mockMvc
    @Autowired ObjectMapper objectMapper
    @Autowired SteamUserRepository userRepo
    @Autowired ApiKeyService apiKeyService
    @Autowired @Qualifier('requestMappingHandlerMapping') RequestMappingHandlerMapping handlerMapping

    @Shared String html = new File(PAGE).getText('UTF-8')

    /** [verb, path, scope] for every documented route row. */
    @Shared List<List<String>> rows = {
        def out = []
        def m = (new File(PAGE).getText('UTF-8') =~ /(?s)<tr data-route="(\w+) ([^"]+)">.*?<td>(RO|RW)<\/td><\/tr>/)
        m.each { all, verb, path, scope -> out << [verb as String, path as String, scope as String] }
        out
    }()

    private String mint(String scope) {
        String tail = String.valueOf(System.nanoTime()).reverse().take(9).reverse()
        SteamUser u = userRepo.save(new SteamUser(steamId64: '7656119' + tail.padLeft(10, '0'),
            displayName: "api-ref-${tail}", role: 'USER'))
        apiKeyService.create(u.id, 'api-ref', scope).token as String
    }

    private Map send(String verb, String path, String token) {
        def r = mockMvc.perform(MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(verb), path.replaceAll(/\{[^}]+\}/, '999999'))
            .header('Authorization', 'Bearer ' + token)
            .contentType('application/json').content('{}')).andReturn()
        String body = r.response.contentAsString
        Map parsed = [:]
        try { def o = objectMapper.readValue(body ?: '{}', Object); if (o instanceof Map) parsed = o as Map } catch (Exception ignored) {}
        [status: r.response.status, code: parsed.code]
    }

    def "the page lists routes in every group"() {
        expect:
        rows.size() >= 30
        rows*.get(0).toSet() == ['GET', 'POST', 'PUT', 'DELETE'] as Set
    }

    def "every documented route is a real controller mapping"() {
        given:
        def mapped = [] as Set
        handlerMapping.handlerMethods.keySet().each { info ->
            def patterns = info.pathPatternsCondition?.patternValues ?: info.patternValues
            def verbs = info.methodsCondition.methods*.name()
            patterns.each { p -> verbs.each { v -> mapped << "${v} ${p}".toString() } }
        }

        expect:
        def missing = rows.findAll { r -> !mapped.contains("${r[0]} ${r[1]}".toString()) }
        missing.isEmpty()
    }

    def "no documented route is one the filter refuses to keys, and the Key column matches the RO write gate"() {
        expect:
        rows.every { r -> !ApiKeyAuthFilter.forbiddenForApiKey(r[1], r[0] != 'GET') }
        rows.every { r -> r[2] == (r[0] == 'GET' ? 'RO' : 'RW') }
    }

    def "a read-only key gets through on every documented read"() {
        given:
        String ro = mint('RO')

        expect:
        rows.findAll { it[0] == 'GET' }.every { r ->
            Map res = send('GET', r[1], ro)
            assert res.status != 401 && !(res.code in ['INVALID_API_KEY', 'RO_KEY_WRITE_FORBIDDEN', 'API_KEY_FORBIDDEN']), "${r} -> ${res}"
            true
        }
    }

    def "a read-only key is refused on every documented write, before the controller runs"() {
        given:
        String ro = mint('RO')

        expect:
        rows.findAll { it[0] != 'GET' }.every { r ->
            Map res = send(r[0], r[1], ro)
            assert res.status == 403 && res.code == 'RO_KEY_WRITE_FORBIDDEN', "${r} -> ${res}"
            true
        }
    }

    def "the website-only list is the filter's own refusal list"() {
        given:
        def any = (html =~ /(?s)<li data-forbidden="any">(.*?)<\/li>/)[0][1] as String
        def write = (html =~ /(?s)<li data-forbidden="write">(.*?)<\/li>/)[0][1] as String
        def codes = { String s -> (s =~ /<code>(\/api[^<]*)<\/code>/).collect { it[1] as String }.toSet() }

        expect:
        codes(any) == ApiKeyAuthFilter.STAFF_PREFIXES.toSet()
        codes(write) == ApiKeyAuthFilter.ACCOUNT_WRITE_PREFIXES.toSet()
    }

    def "the error table names the codes the filters write"() {
        given:
        String filter = new File('src/main/groovy/com/sboxmarket/config/ApiKeyAuthFilter.groovy').getText('UTF-8')
        String limiter = new File('src/main/groovy/com/sboxmarket/config/RateLimitFilter.groovy').getText('UTF-8')
        def documented = (html =~ /<tr data-error="(\d{3}) (\w+)">/).collect { "${it[1]} ${it[2]}".toString() }

        expect:
        documented == ['401 INVALID_API_KEY', '403 RO_KEY_WRITE_FORBIDDEN', '403 API_KEY_FORBIDDEN', '429 RATE_LIMITED']
        ['INVALID_API_KEY', 'RO_KEY_WRITE_FORBIDDEN', 'API_KEY_FORBIDDEN'].every { filter.contains("\"code\":\"${it}\"") }
        limiter.contains('"code":"RATE_LIMITED"') && limiter.contains("'Retry-After'")
    }

    def "the page says limits exist without quoting a number"() {
        expect:
        html.contains('<p>Requests are rate limited.</p>')
        !(html =~ /(?i)\d+\s*(requests?|calls?)\s*(per|\/|a|every)/)
        !html.contains(String.valueOf(ApiKeyService.MAX_ACTIVE_PER_USER) + ' active')
    }

    def "the page is served, with and without the extension"() {
        expect:
        mockMvc.perform(MockMvcRequestBuilders.get(path)).andReturn().response.status == 200

        where:
        path << ['/api-reference.html', '/api-reference']
    }
}
