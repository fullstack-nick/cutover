package dev.cutover.returns;

import dev.cutover.platform.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class AdapterHttpClient implements DispatchPort {
    private final ServiceHttp http;
    public AdapterHttpClient(ServiceHttp http){this.http=http;}
    public JsonNode context(String site,List<UUID> movements){return checked(http.request("POST","/internal/v1/sites/"+site+"/zones/returns/scheduling-context",JsonSupport.MAPPER.valueToTree(Map.of("movementIds",movements))));}
    public JsonNode dispatch(String site,UUID movement,UUID allocation,long epoch,String lane,JsonNode intent){return checked(http.request("PUT","/internal/v1/sites/"+site+"/commands/"+movement,JsonSupport.MAPPER.valueToTree(Map.of("allocationId",allocation,"epoch",epoch,"laneId",lane,"movement",intent))));}
    private JsonNode checked(ServiceHttp.Reply reply){
        if(reply.status()>=200 && reply.status()<300)return reply.body();
        if(reply.status()==409 || reply.status()==403)throw new Problem(reply.status(),reply.body().path("code").asString("RETURN_DISPATCH_CONFLICT"),"The adapter did not authorize this returns dispatch.");
        throw new ServiceHttp.Unavailable("The adapter could not complete the returns request.");
    }
}
