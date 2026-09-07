package dev.cutover.adapter;

import dev.cutover.platform.Access;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
class AdapterController {
    private final Allocations allocations;
    private final CommandJournal journal;
    private final EquipmentObservations observations;
    private final CancellationGate cancellations;
    AdapterController(Allocations allocations,CommandJournal journal,EquipmentObservations observations,CancellationGate cancellations) { this.allocations=allocations;this.journal=journal;this.observations=observations;this.cancellations=cancellations; }
    @PostMapping("/internal/v1/sites/{site}/cancellations") JsonNode cancel(@PathVariable String site,@RequestBody JsonNode body,@AuthenticationPrincipal Jwt jwt) {
        return cancellations.fence(Access.site(jwt,site),Access.client(jwt),body);
    }
    @PostMapping("/internal/v1/sites/{site}/allocations") JsonNode allocate(@PathVariable String site,@RequestBody JsonNode body,@AuthenticationPrincipal Jwt jwt) {
        return allocations.register(Access.site(jwt,site),Access.client(jwt),body);
    }
    record ContextRequest(java.util.List<UUID> movementIds) {}
    @PostMapping("/internal/v1/sites/{site}/zones/{zone}/scheduling-context") JsonNode context(@PathVariable String site,@PathVariable String zone,@RequestBody ContextRequest body,@AuthenticationPrincipal Jwt jwt) {
        Access.role(jwt,"service");return allocations.schedulingContext(Access.site(jwt,site),zone,body.movementIds(),observations);
    }
    @GetMapping("/internal/v1/sites/{site}/allocations/{movement}") JsonNode allocation(@PathVariable String site,@PathVariable UUID movement,@AuthenticationPrincipal Jwt jwt) {
        return allocations.get(Access.site(jwt,site),movement);
    }
    record Dispatch(UUID allocationId,long epoch,String laneId,JsonNode movement) {}
    @PutMapping("/internal/v1/sites/{site}/commands/{movementId}") JsonNode dispatch(@PathVariable String site,@PathVariable UUID movementId,@RequestBody Dispatch command,@AuthenticationPrincipal Jwt jwt) {
        return journal.record(Access.site(jwt,site),Access.client(jwt),movementId,command.allocationId(),command.epoch(),command.laneId(),command.movement());
    }
    @GetMapping({"/internal/v1/sites/{site}/commands/{id}","/api/v1/sites/{site}/commands/{id}"}) JsonNode command(@PathVariable String site,@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt) {
        Access.role(jwt,"service","operator","supervisor");return journal.get(Access.site(jwt,site),id);
    }
    @GetMapping({"/internal/v1/sites/{site}/equipment","/api/v1/sites/{site}/equipment"}) JsonNode equipment(@PathVariable String site,@AuthenticationPrincipal Jwt jwt) {
        Access.role(jwt,"service","operator","supervisor");return observations.forSite(Access.site(jwt,site));
    }
    @GetMapping("/api/v1/sites/{site}/zones") JsonNode zones(@PathVariable String site,@AuthenticationPrincipal Jwt jwt) {
        Access.role(jwt,"operator","supervisor");return allocations.routes(Access.site(jwt,site));
    }
    @GetMapping("/api/v1/sites/{site}/commands") JsonNode commands(@PathVariable String site,@AuthenticationPrincipal Jwt jwt) {
        Access.role(jwt,"operator","supervisor");return journal.recoverable(Access.site(jwt,site));
    }
    @PostMapping("/api/v1/sites/{site}/commands/{id}/reconciliation") JsonNode reconcile(@PathVariable String site,@PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode body,@AuthenticationPrincipal Jwt jwt) {
        Access.role(jwt,"supervisor");return journal.reconcile(jwt.getSubject(),Access.site(jwt,site),id,key,body);
    }
}
