package dev.cutover.platform.messaging;

import dev.cutover.platform.Access;
import dev.cutover.platform.StorageBudget;
import org.jooq.DSLContext;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.UUID;

@RestController
public final class DiagnosticController {
    private final DSLContext database;
    private final QuarantineOperations quarantine;
    public DiagnosticController(DSLContext database,QuarantineOperations quarantine){this.database=database;this.quarantine=quarantine;}
    @GetMapping("/internal/v1/platform/storage") JsonNode storage(@AuthenticationPrincipal Jwt jwt){Access.role(jwt,"platform-admin");return StorageBudget.status(database);}
    @GetMapping("/internal/v1/platform/untrusted-deliveries") JsonNode untrusted(@AuthenticationPrincipal Jwt jwt){Access.role(jwt,"platform-admin");return quarantine.untrustedDiagnostics();}
    @GetMapping("/internal/v1/sites/{site}/messaging/quarantine") JsonNode list(@PathVariable String site,@AuthenticationPrincipal Jwt jwt){Access.role(jwt,"operator","supervisor");return quarantine.status(Access.site(jwt,site));}
    @PostMapping("/internal/v1/sites/{site}/messaging/quarantine/{id}/reprocess") JsonNode reprocess(@PathVariable String site,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode request,@AuthenticationPrincipal Jwt jwt){Access.role(jwt,"supervisor");return quarantine.reprocess(jwt.getSubject(),Access.site(jwt,site),id,key,request);}
}
