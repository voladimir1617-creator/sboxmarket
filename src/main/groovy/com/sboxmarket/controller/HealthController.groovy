package com.sboxmarket.controller

import org.springframework.beans.factory.annotation.Value
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
 * Deliberately simple: no DB ping, no bean graph. If Spring started up
 * enough to handle HTTP requests, the process is up. Deeper readiness
 * checks should be their own authed endpoint.
 *
 * The companion /api/version endpoint surfaces the Gradle project version
 * so the UI footer can show "v1.0.0". Kept on this controller to avoid a
 * whole new file; public by design (same safety profile as /api/health).
 */
@RestController
class HealthController {

    @Value('${info.app.version:1.0.0}')
    String appVersion

    @GetMapping('/api/health')
    ResponseEntity<Map> health() {
        ResponseEntity.ok([status: 'UP'])
    }

    @GetMapping('/api/version')
    ResponseEntity<Map> version() {
        ResponseEntity.ok([version: appVersion])
    }
}
