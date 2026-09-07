package dev.cutover.core;

import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import dev.cutover.platform.ServiceHttp;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class AdapterHttpClient implements DispatchPort {
    private final ServiceHttp http;
    public AdapterHttpClient(ServiceHttp http){this.http=http;}
    @Override public JsonNode allocate(String site,JsonNode movement){return checked(http.request("POST",prefix(site)+"/allocations",movement),false);}
    @Override public JsonNode command(String site,UUID movement){return checked(http.request("GET",prefix(site)+"/commands/"+movement,null),true);}
    @Override public JsonNode equipment(String site){return checked(http.request("GET",prefix(site)+"/equipment",null),false);}
    @Override public JsonNode dispatch(String site,UUID movement,UUID allocation,long epoch,String lane,JsonNode payload){
        return checked(http.request("PUT",prefix(site)+"/commands/"+movement,JsonSupport.MAPPER.valueToTree(Map.of("allocationId",allocation,"epoch",epoch,"laneId",lane,"movement",payload))),false);
    }
    private JsonNode checked(ServiceHttp.Reply reply,boolean missingAllowed){
        if(reply.status()>=200 && reply.status()<300)return reply.body();
        if(missingAllowed && reply.status()==404)return null;
        if(reply.status()==409)throw Problem.conflict(reply.body().path("code").asString("DISPATCH_CONFLICT"),"Adapter dispatch requires a compatible route and available lane.");
        throw new ServiceHttp.Unavailable("The adapter cannot currently complete this request.");
    }
    private String prefix(String site){return "/internal/v1/sites/"+site;}
}
