package dev.cutover.adapter;

import dev.cutover.platform.Access;
import dev.cutover.platform.Database;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
class MigrationController {
    private final MigrationSessions migrations;
    MigrationController(MigrationSessions migrations) { this.migrations = migrations; }

    @PostMapping("/api/v1/sites/{site}/zones/{zone}/migrations")
    ResponseEntity<JsonNode> start(@PathVariable String site, @PathVariable String zone,
                                  @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body,
                                  @AuthenticationPrincipal Jwt jwt) {
        Access.role(jwt, "supervisor");
        var result = migrations.start(jwt.getSubject(), Access.site(jwt, site), zone, key, body);
        return ResponseEntity.accepted().location(URI.create("/api/v1/sites/" + site + "/migrations/" + Database.uuid(result, "sessionId"))).body(result);
    }

    @GetMapping("/api/v1/sites/{site}/migrations")
    JsonNode list(@PathVariable String site, @AuthenticationPrincipal Jwt jwt) {
        Access.role(jwt, "operator", "supervisor", "service"); return migrations.list(Access.site(jwt, site));
    }

    @GetMapping("/api/v1/sites/{site}/migrations/{id}")
    JsonNode get(@PathVariable String site, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        Access.role(jwt, "operator", "supervisor", "service"); return migrations.get(Access.site(jwt, site), id);
    }

    @PostMapping("/api/v1/sites/{site}/migrations/{id}/recovery")
    JsonNode recover(@PathVariable String site, @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                     @RequestBody JsonNode body, @AuthenticationPrincipal Jwt jwt) {
        Access.role(jwt, "supervisor"); return migrations.resume(jwt.getSubject(), Access.site(jwt, site), id, key, body);
    }

    @PostMapping("/api/v1/sites/{site}/migrations/{id}/cancellation")
    JsonNode cancel(@PathVariable String site, @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                    @RequestBody JsonNode body, @AuthenticationPrincipal Jwt jwt) {
        Access.role(jwt, "supervisor"); return migrations.cancel(jwt.getSubject(), Access.site(jwt, site), id, key, body);
    }
}
