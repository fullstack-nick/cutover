package dev.cutover.execution;

import dev.cutover.platform.Access;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController @ConditionalOnProperty(name="cutover.shadow-mode",havingValue="false",matchIfMissing=true)
class ExecutionController {
    private final ExecutionScheduler scheduler;
    ExecutionController(ExecutionScheduler scheduler){this.scheduler=scheduler;}
    @GetMapping("/api/v1/sites/{site}/execution-tasks") JsonNode tasks(@PathVariable String site,@AuthenticationPrincipal Jwt jwt){Access.role(jwt,"operator","supervisor","service");return scheduler.tasks(Access.site(jwt,site));}
    @PostMapping("/api/v1/sites/{site}/execution-tasks/{id}/recovery") JsonNode recover(@PathVariable String site,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode body,@AuthenticationPrincipal Jwt jwt){Access.role(jwt,"supervisor");return scheduler.resume(jwt.getSubject(),Access.site(jwt,site),id,key,body);}
}
