package dev.cutover.platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

public final class ServiceHttp {
    public record Reply(int status,JsonNode body) {}
    public static final class Unavailable extends RuntimeException { public Unavailable(String message){super(message);} }
    private final URI base;private final Supplier<String> tokens;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public ServiceHttp(URI base,Supplier<String> tokens){this.base=base;this.tokens=tokens;}
    public Reply request(String method,String path,JsonNode payload){
        try{
            var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(3)).header("Authorization","Bearer "+tokens.get()).header("Accept","application/json");
            if(payload==null)builder.method(method,HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(JsonSupport.write(payload)));
            var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(),response.body().isBlank()?JsonSupport.read("{}"):JsonSupport.read(response.body()));
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new Unavailable("Service operation interrupted.");}
        catch(Unavailable unavailable){throw unavailable;}
        catch(Exception failed){throw new Unavailable("Service response is unavailable; durable state must be queried before retry.");}
    }
}
