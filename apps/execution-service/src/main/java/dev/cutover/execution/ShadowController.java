package dev.cutover.execution;

import dev.cutover.platform.Access;
import dev.cutover.platform.Database;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController @ConditionalOnProperty(name="cutover.shadow-mode",havingValue="true")
final class ShadowController {
    private final DSLContext database;
    ShadowController(DSLContext database){this.database=database;}
    @GetMapping("/api/v1/sites/{site}/shadow-comparisons") JsonNode comparisons(@PathVariable String site,@AuthenticationPrincipal Jwt jwt){
        Access.role(jwt,"operator","supervisor","service");Access.site(jwt,site);
        return Database.json(database,"SELECT jsonb_build_object('observedAt',now(),'compared',(SELECT count(*) FROM shadow_comparisons WHERE site_id=?),'mismatches',(SELECT count(*) FROM shadow_comparisons WHERE site_id=? AND NOT matches),'items',COALESCE((SELECT jsonb_agg(jsonb_build_object('roundId',round_id,'inputHash',input_hash,'matches',matches,'ruleVersion',rule_version,'comparedAt',compared_at) ORDER BY compared_at DESC,round_id) FROM (SELECT * FROM shadow_comparisons WHERE site_id=? ORDER BY compared_at DESC,round_id LIMIT 50) r),'[]'::jsonb))",site,site,site);
    }
    @GetMapping("/api/v1/sites/{site}/shadow-comparisons/{id}") JsonNode comparison(@PathVariable String site,@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){
        Access.role(jwt,"operator","supervisor","service");return Database.json(database,"SELECT jsonb_build_object('roundId',round_id,'inputHash',input_hash,'input',input,'legacyProposal',legacy_proposal,'executionProposal',execution_proposal,'matches',matches,'comparedAt',compared_at) FROM shadow_comparisons WHERE site_id=? AND round_id=?",Access.site(jwt,site),id);
    }
}
