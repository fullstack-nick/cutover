package dev.cutover.platform.messaging;

import dev.cutover.platform.Access;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/internal/v1/sites/{site}/messaging")
public class MessagingController {
    private final MessagingOperations operations;
    public MessagingController(MessagingOperations operations) { this.operations = operations; }
    @GetMapping JsonNode status(@PathVariable String site, @AuthenticationPrincipal Jwt jwt) {
        Access.role(jwt, "operator", "supervisor", "platform-admin", "service");
        return operations.status(Access.site(jwt, site));
    }
    @PostMapping("/{kind}/{id}/replay") JsonNode replay(@PathVariable String site, @PathVariable String kind, @PathVariable UUID id,
                                                      @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode request, @AuthenticationPrincipal Jwt jwt) {
        Access.role(jwt, "supervisor");
        return operations.recover(jwt.getSubject(), Access.site(jwt, site), kind, id, key, request);
    }
}
