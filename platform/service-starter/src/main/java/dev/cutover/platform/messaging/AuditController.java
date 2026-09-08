package dev.cutover.platform.messaging;

import dev.cutover.platform.Access;
import dev.cutover.platform.AuditLog;
import dev.cutover.platform.Problem;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
public final class AuditController {
    private final DSLContext database;
    private final String service;
    public AuditController(DSLContext database,@Value("${spring.application.name}") String service){this.database=database;this.service=service;}
    @GetMapping("/api/v1/sites/{site}/audit/{owner}")
    JsonNode list(@PathVariable String site,@PathVariable String owner,@RequestParam(defaultValue="25") int limit,
                  @RequestParam(required=false) UUID cursor,@RequestParam(required=false) String resource,@AuthenticationPrincipal Jwt jwt){
        Access.role(jwt,"operator","supervisor");site=Access.site(jwt,site);
        if(!service.equals(Map.of("core","legacy-core","adapter","equipment-adapter","execution","execution-service","returns","returns-service").get(owner)))throw Problem.missing();
        return AuditLog.page(database,site,owner,limit,cursor,resource);
    }
}
