package dev.cutover.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.cutover.platform.*;
import java.net.URI;
import java.util.Map;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/** Explicit owner maintenance command; never started by the application worker or a scheduler. */
public final class LegacyBoundaryMain {
    private LegacyBoundaryMain(){}
    public static void main(String[] ignored){
        if(!"true".equals(System.getenv("CUTOVER_BOUNDARY_REGISTRATION")))throw new IllegalStateException("Explicit boundary registration was not requested.");
        try{
            byte[] input=System.in.readNBytes(2049);if(input.length>2048)throw Problem.invalid("Registration request exceeds its bound.");
            var request=JsonSupport.MAPPER.readTree(input);
            if(!request.path("adapterControlVersion").isIntegralNumber())throw Problem.invalid("The observed adapter control version is required.");
            var configuration=new HikariConfig();configuration.setJdbcUrl(System.getenv("CUTOVER_DATABASE_URL"));configuration.setUsername(System.getenv("CUTOVER_DATABASE_USER"));configuration.setPassword(System.getenv("CUTOVER_DATABASE_PASSWORD"));configuration.setMaximumPoolSize(2);configuration.setMinimumIdle(0);
            var http=new ServiceHttp(URI.create(System.getenv("CUTOVER_ADAPTER_URL")),new ClientCredentials(URI.create(System.getenv("CUTOVER_TOKEN_URI")),"legacy-core",System.getenv("CUTOVER_CLIENT_SECRET")));
            try(var source=new HikariDataSource(configuration)){
                var sql=DSL.using(source,SQLDialect.POSTGRES);
                var identity=sql.fetchOne("SELECT current_user,current_database()");
                if(!identity.get(0,String.class).equals("cutover_core_runtime") || !identity.get(1,String.class).equals("cutover_core"))throw new IllegalStateException("The baseline recorder requires its own runtime identity and database.");
                var recorder=new LegacyBoundaryRegistration(sql,(site,body)->{
                    var reply=http.request("POST","/internal/v1/sites/"+site+"/legacy-registrations",body);
                    if(reply.status()!=200)throw new Problem(reply.status(),reply.body().path("code").asString("REGISTRATION_UNAVAILABLE"),"The adapter could not attest this baseline batch.");
                    return reply.body();
                });
                System.out.println(JsonSupport.write(recorder.register("site-a",Database.uuid(request,"registrationId"),request.path("adapterControlVersion").asLong(-1),request.path("reason").asString())));
            }
        }catch(Exception failure){System.out.println(JsonSupport.write(Map.of("state","REGISTRATION_FAILED","code",failure instanceof Problem problem?problem.code():failure.getClass().getSimpleName())));System.exit(2);}
    }
}
