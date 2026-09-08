package dev.cutover.core;

import dev.cutover.platform.Access;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
class CoreController {
    private final OrderService orders;private final LegacyScheduler scheduler;private final OrderCancellations cancellations;
    CoreController(OrderService orders,LegacyScheduler scheduler,OrderCancellations cancellations){this.orders=orders;this.scheduler=scheduler;this.cancellations=cancellations;}
    @PostMapping("/api/v1/sites/{site}/orders/{id}/cancellation") JsonNode cancel(@PathVariable String site,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode body,@AuthenticationPrincipal Jwt jwt) {
        Access.role(jwt,"supervisor");return cancellations.cancel(jwt.getSubject(),Access.site(jwt,site),id,key,body);
    }
    @PostMapping("/api/v1/sites/{site}/orders/{id}/cancellations/{cancellationId}/retry") JsonNode retryCancellation(@PathVariable String site,@PathVariable UUID id,@PathVariable UUID cancellationId,@RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode body,@AuthenticationPrincipal Jwt jwt) {
        Access.role(jwt,"supervisor");return cancellations.retry(jwt.getSubject(),Access.site(jwt,site),id,cancellationId,key,body);
    }
    @PostMapping("/api/v1/sites/{site}/orders") ResponseEntity<JsonNode> create(@PathVariable String site,@RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode request,@AuthenticationPrincipal Jwt jwt){
        Access.role(jwt,"scenario","service");var accepted=orders.accept(jwt.getSubject(),Access.site(jwt,site),key,request);
        return ResponseEntity.accepted().location(URI.create(accepted.required("statusUrl").asString())).body(accepted);
    }
    @GetMapping("/api/v1/sites/{site}/orders/{id}") JsonNode get(@PathVariable String site,@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){
        Access.role(jwt,"operator","supervisor","scenario","service");return orders.get(Access.site(jwt,site),id);
    }
    @GetMapping("/api/v1/sites/{site}/orders") JsonNode list(@PathVariable String site,@RequestParam(required=false) UUID cursor,@RequestParam(defaultValue="25") int limit,@RequestParam(required=false) String reference,@RequestParam(defaultValue="false") boolean shortagesOnly,@AuthenticationPrincipal Jwt jwt){
        Access.role(jwt,"operator","supervisor","scenario","service");return orders.list(Access.site(jwt,site),cursor,limit,reference,shortagesOnly);
    }
    @GetMapping("/api/v1/sites/{site}/tasks") JsonNode tasks(@PathVariable String site,@AuthenticationPrincipal Jwt jwt){
        Access.role(jwt,"operator","supervisor","service");return scheduler.tasks(Access.site(jwt,site));
    }
    @PostMapping("/api/v1/sites/{site}/tasks/{id}/recovery") JsonNode recover(@PathVariable String site,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode body,@AuthenticationPrincipal Jwt jwt){
        Access.role(jwt,"supervisor");return scheduler.resume(jwt.getSubject(),Access.site(jwt,site),id,key,body);
    }
}
