package dev.cutover.platform.control;

import dev.cutover.platform.Access;
import dev.cutover.platform.Problem;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/internal/v1/sites/{site}/test-controls")
public class TestControlController {
    private final RuntimeControls controls;
    public TestControlController(RuntimeControls controls) { this.controls=controls; }
    private static String allowed(Jwt jwt,String site) {
        Access.role(jwt,"test-control");
        if (!"scenario-driver".equals(Access.client(jwt))) throw new Problem(403,"TEST_IDENTITY_REQUIRED","Only the dedicated scenario identity can control local faults.");
        return Access.site(jwt,site);
    }
    @GetMapping JsonNode status(@PathVariable String site,@AuthenticationPrincipal Jwt jwt) { return controls.status(allowed(jwt,site)); }
    @PostMapping JsonNode change(@PathVariable String site,@RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode body,@AuthenticationPrincipal Jwt jwt) {
        return controls.change(jwt.getSubject(),allowed(jwt,site),key,body);
    }
    @GetMapping("/faults") JsonNode faults(@PathVariable String site,@AuthenticationPrincipal Jwt jwt) { return controls.faults(allowed(jwt,site)); }
    @PostMapping("/faults") JsonNode arm(@PathVariable String site,@RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode body,@AuthenticationPrincipal Jwt jwt) {
        return controls.arm(jwt.getSubject(),allowed(jwt,site),key,body);
    }
    @PostMapping("/faults/{id}/clear") JsonNode clear(@PathVariable String site,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@RequestBody JsonNode body,@AuthenticationPrincipal Jwt jwt) {
        return controls.clear(jwt.getSubject(),allowed(jwt,site),id,key,body);
    }
}
