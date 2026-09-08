package dev.cutover.adapter;

import dev.cutover.platform.JsonSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.UUID;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import tools.jackson.databind.JsonNode;

public final class MutualTlsEquipmentClient implements EquipmentPort {
    private final URI base;
    private final HttpClient client;
    public MutualTlsEquipmentClient(URI base,Path keyStore,Path trustStore,String password) {
        if (!"https".equals(base.getScheme()) || base.getUserInfo()!=null) throw new IllegalArgumentException("Equipment requires HTTPS without URL credentials.");
        this.base=base;
        try {
            char[] secret=password.toCharArray();
            try {
                var keys=KeyStore.getInstance("PKCS12"); try(var stream=Files.newInputStream(keyStore)){keys.load(stream,secret);}
                var trusts=KeyStore.getInstance("PKCS12"); try(var stream=Files.newInputStream(trustStore)){trusts.load(stream,secret);}
                var keyManagers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keyManagers.init(keys,secret);
                var trustManagers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trustManagers.init(trusts);
                var tls=SSLContext.getInstance("TLS");tls.init(keyManagers.getKeyManagers(),trustManagers.getTrustManagers(),null);
                client=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
            } finally { java.util.Arrays.fill(secret,'\0'); }
        } catch(Exception failure) { throw new IllegalStateException("Equipment TLS configuration could not be loaded.",failure); }
    }
    @Override public JsonNode equipment() {
        Reply reply=request("GET","/sim/v1/equipment",null);
        if(reply.status()!=200) throw new Unavailable("Equipment observation is unavailable.");
        return reply.body();
    }
    @Override public Reply command(UUID id) { return request("GET","/sim/v1/commands/"+id,null); }
    public JsonNode history(long after,int limit) {
        if(after<0 || limit<1 || limit>100)throw new IllegalArgumentException("Invalid bounded history cursor.");
        Reply reply=request("GET","/sim/v1/history?after="+after+"&limit="+limit,null);
        if(reply.status()!=200)throw new Unavailable("Physical history is unavailable.");return reply.body();
    }
    public JsonNode recoveryInventory(UUID after,int limit) {
        if(limit<1 || limit>32)throw new IllegalArgumentException("Invalid recovery inventory page size.");
        Reply reply=request("GET","/sim/v1/recovery-inventory?limit="+limit+(after==null?"":"&after="+after),null);
        if(reply.status()!=200)throw new Unavailable("Physical command inventory is unavailable.");return reply.body();
    }
    @Override public Reply send(UUID id,JsonNode command) { return request("PUT","/sim/v1/commands/"+id,command); }
    private Reply request(String method,String path,JsonNode body) {
        try {
            var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofMillis(900)).header("Accept","application/json");
            if(body==null) builder.GET();else builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(JsonSupport.write(body)));
            var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            if(response.body().length()>262144) throw new Unavailable("Equipment response exceeded the protocol bound.");
            return new Reply(response.statusCode(),JsonSupport.read(response.body()));
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new Unavailable("Equipment operation interrupted.",interrupted); }
        catch(Unavailable unavailable) { throw unavailable; }
        catch(Exception transport) { throw new Unavailable("Equipment transport outcome is unknown.",transport); }
    }
}
