package dev.cutover.returns;

import dev.cutover.platform.Access;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
class ReturnsController {
    private final ReceiptService receipts;private final ReturnsCoordinator coordinator;
    ReturnsController(ReceiptService receipts,ReturnsCoordinator coordinator){this.receipts=receipts;this.coordinator=coordinator;}
    @PostMapping("/api/v1/sites/{site}/return-receipts") ResponseEntity<JsonNode> register(@PathVariable String site,@RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode body,@AuthenticationPrincipal Jwt jwt){
        Access.role(jwt,"scenario","service");var accepted=receipts.register(jwt.getSubject(),Access.site(jwt,site),key,body);return ResponseEntity.accepted().location(URI.create(accepted.required("statusUrl").asString())).body(accepted);
    }
    @GetMapping("/api/v1/sites/{site}/return-receipts") JsonNode list(@PathVariable String site,@RequestParam(required=false) UUID cursor,@RequestParam(defaultValue="25") int limit,@AuthenticationPrincipal Jwt jwt){Access.role(jwt,"operator","supervisor","scenario","service");return receipts.list(Access.site(jwt,site),cursor,limit);}
    @GetMapping("/api/v1/sites/{site}/return-receipts/{id}") JsonNode get(@PathVariable String site,@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){Access.role(jwt,"operator","supervisor","scenario","service");return receipts.get(Access.site(jwt,site),id);}
    @GetMapping("/api/v1/sites/{site}/return-counters") JsonNode counters(@PathVariable String site,@AuthenticationPrincipal Jwt jwt){Access.role(jwt,"operator","supervisor","scenario","service");return receipts.counters(Access.site(jwt,site));}
    @GetMapping("/api/v1/sites/{site}/return-tasks") JsonNode tasks(@PathVariable String site,@AuthenticationPrincipal Jwt jwt){Access.role(jwt,"operator","supervisor","scenario","service");return coordinator.tasks(Access.site(jwt,site));}
    @PostMapping("/api/v1/sites/{site}/return-tasks/{id}/recovery") JsonNode recover(@PathVariable String site,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode body,@AuthenticationPrincipal Jwt jwt){Access.role(jwt,"supervisor");return coordinator.resume(jwt.getSubject(),Access.site(jwt,site),id,key,body);}
    @GetMapping("/api/v1/sites/{site}/return-tasks/{id}") JsonNode task(@PathVariable String site,@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){Access.role(jwt,"operator","supervisor","service");return coordinator.task(Access.site(jwt,site),id);}
}
