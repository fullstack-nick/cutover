package dev.cutover.adapter;

import dev.cutover.platform.Access;
import dev.cutover.platform.Problem;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@ConditionalOnProperty(name="cutover.test-controls-enabled", havingValue="true")
class MigrationFaultController {
    private final MigrationProcessFaults faults;
    MigrationFaultController(MigrationProcessFaults faults) { this.faults = faults; }
    private String site(Jwt jwt, String site) {
        Access.role(jwt, "test-control");
        if (!Access.client(jwt).equals("scenario-driver")) throw new Problem(403, "TEST_CONTROL_IDENTITY", "Only the explicit local scenario client can use a phase fault.");
        return Access.site(jwt, site);
    }
    @PostMapping("/internal/v1/sites/{site}/test-controls/migration-faults")
    JsonNode arm(@PathVariable String site, @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body, @AuthenticationPrincipal Jwt jwt) {
        return faults.arm(jwt.getSubject(), site(jwt, site), key, body);
    }
    @GetMapping("/internal/v1/sites/{site}/test-controls/migration-faults")
    JsonNode list(@PathVariable String site, @AuthenticationPrincipal Jwt jwt) { return faults.list(site(jwt, site)); }
    @PostMapping("/internal/v1/sites/{site}/test-controls/migration-faults/{id}/clear")
    JsonNode clear(@PathVariable String site, @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                   @RequestBody JsonNode body, @AuthenticationPrincipal Jwt jwt) {
        return faults.clear(jwt.getSubject(), site(jwt, site), id, key, body);
    }
}
