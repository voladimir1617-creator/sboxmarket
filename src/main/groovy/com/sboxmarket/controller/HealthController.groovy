package com.sboxmarket.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * App-controlled liveness endpoint. Replaces `/actuator/health`, which is
 * disabled at the actuator level to avoid leaking the framework version to
 * scanners. This one just returns a fixed JSON body so Docker's
 * HEALTHCHECK / k8s readiness probes / load balancer health checks have a
 * 2xx target.
 *
 * Deliberately simple: no DB ping, no bean graph, no git commit sha. If
 * Spring started up enough to handle HTTP requests, the process is up.
 * Deeper readiness checks should be their own authed endpoint.
 */
@RestController
@RequestMapping("/api/health")
class HealthController {

    @GetMapping
    ResponseEntity<Map> health() {
        ResponseEntity.ok([status: 'UP'])
    }
}
