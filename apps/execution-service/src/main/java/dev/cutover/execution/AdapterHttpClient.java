package dev.cutover.execution;

import dev.cutover.platform.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class AdapterHttpClient implements DispatchPort {
    private final ServiceHttp http;
    public AdapterHttpClient(ServiceHttp http){this.http=http;}
    @Override public JsonNode context(String site,String zone,List<UUID> movements){return checked(http.request("POST","/internal/v1/sites/"+site+"/zones/"+zone+"/scheduling-context",JsonSupport.MAPPER.valueToTree(Map.of("movementIds",movements))));}
    @Override public JsonNode dispatch(String site,UUID movement,UUID allocation,long epoch,String lane,JsonNode payload){return checked(http.request("PUT","/internal/v1/sites/"+site+"/commands/"+movement,JsonSupport.MAPPER.valueToTree(Map.of("allocationId",allocation,"epoch",epoch,"laneId",lane,"movement",payload))));}
    private JsonNode checked(ServiceHttp.Reply reply){
        if(reply.status()>=200 && reply.status()<300)return reply.body();
        if(reply.status()==409 || reply.status()==403)throw new Problem(reply.status(),reply.body().path("code").asString("DISPATCH_CONFLICT"),"The adapter did not authorize the requested dispatch.");
        throw new ServiceHttp.Unavailable("The adapter cannot currently complete this request.");
    }
}
