package com.sboxmarket.controller

import com.sboxmarket.exception.UnauthorizedException
import com.sboxmarket.model.ApiKey
import com.sboxmarket.service.ApiKeyService
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/api-keys")
@Slf4j
class ApiKeyController {

    @Autowired ApiKeyService apiKeyService

    private Long requireUser(HttpServletRequest req) {
        def uid = req.session.getAttribute(SteamAuthController.SESSION_USER_ID) as Long
        if (uid == null) throw new UnauthorizedException()
        uid
    }

    @GetMapping
    ResponseEntity<List<ApiKey>> list(HttpServletRequest req) {
        ResponseEntity.ok(apiKeyService.listForUser(requireUser(req)))
    }

    @PostMapping
    ResponseEntity<Map> create(@RequestBody(required = false) Map body, HttpServletRequest req) {
        def uid = requireUser(req)
        def label = body?.label as String
        def result = apiKeyService.create(uid, label)
        ResponseEntity.ok([
            id:           result.key.id,
            publicPrefix: result.key.publicPrefix,
            label:        result.key.label,
            token:        result.token,        // returned ONCE
            createdAt:    result.key.createdAt
        ])
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Map> revoke(@PathVariable Long id, HttpServletRequest req) {
        def key = apiKeyService.revoke(requireUser(req), id)
        ResponseEntity.ok([id: key.id, revoked: key.revoked])
    }
}
