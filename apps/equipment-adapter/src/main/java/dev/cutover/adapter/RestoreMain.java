package dev.cutover.adapter;

import dev.cutover.platform.*;
import java.net.URI;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/** Explicit local recovery process. It has only the adapter's ordinary database and equipment credentials. */
public final class RestoreMain {
    private RestoreMain() {}
    public static void main(String[] args) {
        try {
            if(!"true".equals(System.getenv("CUTOVER_RESTORATION_MODE")) || args.length!=1)throw new IllegalArgumentException("Use the explicit local restoration procedure.");
            byte[] input=System.in.readNBytes(8193);if(input.length>8192)throw new IllegalArgumentException("Recovery request exceeds its bound.");
            var request=JsonSupport.MAPPER.readTree(input);
            try(var connection=DriverManager.getConnection(required("CUTOVER_DATABASE_URL"),required("CUTOVER_DATABASE_USER"),required("CUTOVER_DATABASE_PASSWORD"))) {
                var sql=DSL.using(connection,SQLDialect.POSTGRES);
                if(!"cutover_adapter_runtime".equals(sql.fetchOne("SELECT current_user").get(0,String.class)))throw new IllegalArgumentException("Recovery must use the adapter runtime role.");
                var equipment=new MutualTlsEquipmentClient(URI.create(required("CUTOVER_EQUIPMENT_URL")),Path.of(required("CUTOVER_ADAPTER_KEY_STORE")),Path.of(required("CUTOVER_EQUIPMENT_TRUST_STORE")),required("CUTOVER_EQUIPMENT_STORE_PASSWORD"));
                var recovery=new RestoreReconciliation(sql,equipment,equipment::history,equipment::recoveryInventory,Clock.systemUTC());
                UUID id=Database.uuid(request,"restoreId");
                var result=switch(args[0]) {
                    case "begin" -> recovery.begin(JsonSupport.MAPPER.treeToValue(request,RestoreReconciliation.Start.class));
                    case "scan" -> recovery.scan(id);
                    case "inventory" -> recovery.scanInventory(id);
                    case "absence" -> recovery.proveAbsence(id);
                    case "verify" -> recovery.verify(id);
                    case "release" -> recovery.release(id,request.path("expectedVersion").asLong(-1),request.path("actor").asString(),request.path("reason").asString());
                    case "get" -> recovery.get(id);
                    default -> throw new IllegalArgumentException("Unknown recovery step.");
                };
                System.out.println("CUTOVER_RESTORE_RESULT="+JsonSupport.write(result));
            }
        }catch(Exception failure){
            System.err.println("CUTOVER_RESTORE_RESULT="+JsonSupport.write(Map.of("result","FAILED","code",failure instanceof Problem problem?problem.code():failure.getClass().getSimpleName())));
            System.exit(2); // Driver diagnostics can include credentials; never print raw exceptions here.
        }
    }
    private static String required(String name){String value=System.getenv(name);if(value==null || value.isBlank())throw new IllegalArgumentException("Missing recovery setting: "+name);return value;}
}
