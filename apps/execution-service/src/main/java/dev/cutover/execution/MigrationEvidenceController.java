package dev.cutover.execution;

import dev.cutover.platform.Access;
import dev.cutover.platform.Problem;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@ConditionalOnProperty(name="cutover.shadow-mode", havingValue="false", matchIfMissing=true)
class MigrationEvidenceController {
    private final MigrationEvidence evidence;
    MigrationEvidenceController(MigrationEvidence evidence) { this.evidence = evidence; }

    @PostMapping("/internal/v1/sites/{site}/zones/{zone}/migration-evidence")
    JsonNode read(@PathVariable String site, @PathVariable String zone, @RequestBody JsonNode body,
                  @AuthenticationPrincipal Jwt jwt) {
        Access.role(jwt, "service");
        if (!Access.client(jwt).equals("equipment-adapter"))
            throw new Problem(403, "MIGRATION_EVIDENCE_IDENTITY", "Only the migration authority can request these records.");
        return evidence.read(Access.site(jwt, site), zone, body);
    }
}
