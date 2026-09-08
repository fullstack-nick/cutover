package dev.cutover.core;

import dev.cutover.platform.Access;
import java.time.Clock;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/internal/v1/sites/{site}/messaging/shadow-observations")
final class ShadowRecoveryController {
    private final ShadowRecovery recovery;
    ShadowRecoveryController(DSLContext database,Clock clock){recovery=new ShadowRecovery(database,clock);}
    @GetMapping JsonNode status(@PathVariable String site,@AuthenticationPrincipal Jwt jwt){
        Access.role(jwt,"operator","supervisor","platform-admin","service");return recovery.status(Access.site(jwt,site));
    }
    @PostMapping("/{id}/replay") JsonNode replay(@PathVariable String site,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode body,@AuthenticationPrincipal Jwt jwt){
        Access.role(jwt,"supervisor");return recovery.replay(jwt.getSubject(),Access.site(jwt,site),id,key,body);
    }
}
