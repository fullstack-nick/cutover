package dev.cutover.platform.control;

import dev.cutover.platform.ClientCredentials;
import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.ServiceHttp;
import java.net.URI;
import java.util.Map;

/** Explicit bounded identity probe. Credentials stay in the calling application pod. */
public final class AdapterProbeMain {
    private AdapterProbeMain(){}
    public static void main(String[] ignored){
        if(!"true".equals(System.getenv("CUTOVER_TEST_CONTROLS_ENABLED")))throw new IllegalStateException("Local verification controls are disabled.");
        try{
            byte[] input=System.in.readNBytes(65537);if(input.length>65536)throw new IllegalArgumentException("Probe exceeds its limit.");
            var request=JsonSupport.MAPPER.readTree(input);String path=request.path("path").asString(),method=request.path("method").asString();
            if(!(method.equals("PUT") && path.matches("/internal/v1/sites/site-a/commands/[a-f0-9-]{36}"))
                    && !(method.equals("POST") && path.equals("/internal/v1/sites/site-a/allocations")))throw new IllegalArgumentException("Unknown probe operation.");
            var credentials=new ClientCredentials(URI.create(System.getenv("CUTOVER_TOKEN_URI")),System.getenv("CUTOVER_CLIENT_ID"),System.getenv("CUTOVER_CLIENT_SECRET"));
            var reply=new ServiceHttp(URI.create(System.getenv("CUTOVER_ADAPTER_URL")),credentials).request(method,path,request.path("body"));
            System.out.println(JsonSupport.write(Map.of("status",reply.status(),"code",reply.body().path("code").asString(""),"client",System.getenv("CUTOVER_CLIENT_ID"),"shadowMode",System.getenv().getOrDefault("CUTOVER_SHADOW_MODE","false"))));
        }catch(Exception failure){System.out.println(JsonSupport.write(Map.of("result","PROBE_FAILED","failureType",failure.getClass().getSimpleName())));System.exit(2);}
    }
}
