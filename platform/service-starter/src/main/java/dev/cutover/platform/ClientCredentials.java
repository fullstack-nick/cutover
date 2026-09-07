package dev.cutover.platform;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.function.Supplier;

/** In-memory, short-lived service tokens; secrets never enter request URLs or logs. */
public final class ClientCredentials implements Supplier<String> {
    private final URI tokenEndpoint;
    private final String clientId,secret;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private String token;private Instant expires=Instant.EPOCH;
    public ClientCredentials(URI tokenEndpoint,String clientId,String secret) { this.tokenEndpoint=tokenEndpoint;this.clientId=clientId;this.secret=secret; }
    @Override public synchronized String get() {
        if(token!=null && expires.isAfter(Instant.now().plusSeconds(20)))return token;
        try {
            String body="grant_type=client_credentials&client_id="+encode(clientId)+"&client_secret="+encode(secret);
            var request=HttpRequest.newBuilder(tokenEndpoint).timeout(Duration.ofSeconds(3)).header("Content-Type","application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new ServiceHttp.Unavailable("Service authentication is unavailable.");
            var payload=JsonSupport.read(response.body());token=payload.required("access_token").asString();expires=Instant.now().plusSeconds(payload.required("expires_in").asLong());
            return token;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ServiceHttp.Unavailable("Service authentication was interrupted.");}
        catch(ServiceHttp.Unavailable unavailable){throw unavailable;}
        catch(Exception failure){throw new ServiceHttp.Unavailable("Service authentication is unavailable.");}
    }
    private String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
}
