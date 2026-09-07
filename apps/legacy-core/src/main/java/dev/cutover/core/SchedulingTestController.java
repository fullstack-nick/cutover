package dev.cutover.core;

import dev.cutover.platform.Access;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** Synthetic observation input never creates a business task or grants command authority. */
@RestController @ConditionalOnProperty(name="cutover.test-controls-enabled",havingValue="true")
final class SchedulingTestController {
    private final ShadowObservations observations;
    SchedulingTestController(ShadowObservations observations){this.observations=observations;}
    @PostMapping("/internal/v1/sites/{site}/test-controls/scheduling-rounds")
    JsonNode record(@PathVariable String site,@RequestBody JsonNode body,@AuthenticationPrincipal Jwt jwt){
        Access.role(jwt,"test-control");Access.site(jwt,site);
        if(!Access.client(jwt).equals("scenario-driver"))throw new Problem(403,"TEST_CONTROL_CLIENT","Only the scenario identity may supply synthetic scheduling observations.");
        if(!site.equals(body.path("siteId").asString()))throw Problem.missing();
        var result=observations.capture(body,"SEEDED_TEST");
        if(!result.retained())throw new Problem(503,"SHADOW_CAPACITY","Observation capacity is unavailable; business dispatch has a separate budget.");
        return JsonSupport.MAPPER.createObjectNode().put("roundId",result.id().toString()).put("inputHash",JsonSupport.hash(body));
    }
}
