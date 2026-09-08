package dev.cutover.platform;

import java.util.UUID;
import org.jooq.DSLContext;
import tools.jackson.databind.JsonNode;

/** Read-only technical audit projection; always uses the calling service's own database. */
public final class AuditLog {
    private AuditLog() {}
    public static JsonNode page(DSLContext database,String site,String owner,int limit,UUID cursor,String resource) {
        if(limit<1||limit>100)throw Problem.invalid("Audit page size must be between 1 and 100.");
        if(resource!=null&&resource.length()>128)throw Problem.invalid("Audit resource reference exceeds 128 characters.");
        var rows=database.fetch("""
                SELECT audit_id,jsonb_build_object('id',audit_id,'actor',actor,'action',action,'resourceId',resource_id,
                    'reason',reason,'beforeVersion',before_version,'afterVersion',after_version,'outcome',outcome,'occurredAt',occurred_at)::text AS item
                FROM audit WHERE site_id=? AND (?::text IS NULL OR resource_id=?::text)
                    AND (?::uuid IS NULL OR (occurred_at,audit_id)<(SELECT occurred_at,audit_id FROM audit WHERE site_id=? AND audit_id=?::uuid))
                ORDER BY occurred_at DESC,audit_id DESC LIMIT ?
                """,site,resource,resource,cursor,site,cursor,limit+1);
        var result=JsonSupport.MAPPER.createObjectNode();result.put("owner",owner);result.put("observedAt",java.time.Instant.now().toString());
        var items=result.putArray("items");for(int index=0;index<Math.min(rows.size(),limit);index++)items.add(JsonSupport.read(rows.get(index).get("item",String.class)));
        if(rows.size()>limit)result.put("nextCursor",rows.get(limit-1).get("audit_id",UUID.class).toString());else result.putNull("nextCursor");
        return result;
    }
}
