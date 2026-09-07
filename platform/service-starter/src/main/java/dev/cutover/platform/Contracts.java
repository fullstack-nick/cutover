package dev.cutover.platform;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Only committed, classpath schemas can be selected. Remote schema fetching is disabled. */
public final class Contracts {
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaLoader(loader -> loader.fetchRemoteResources(false)));
    private static final Map<String, Schema> CACHE = new ConcurrentHashMap<>();
    private Contracts() {}

    public static void validate(String name, String json) {
        if (!name.matches("[a-z-]+\\.v[1-9][0-9]*")) throw new IllegalArgumentException("Invalid schema name");
        Schema schema = CACHE.computeIfAbsent(name, key -> {
            try (var stream = Contracts.class.getResourceAsStream("/schemas/" + key + ".json")) {
                if (stream == null) throw new IllegalArgumentException("Unknown schema: " + key);
                return REGISTRY.getSchema(stream, InputFormat.JSON);
            } catch (IOException ex) { throw new IllegalStateException("Cannot load schema " + key, ex); }
        });
        var errors = schema.validate(json, InputFormat.JSON,
                context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)));
        if (!errors.isEmpty()) throw new IllegalArgumentException("Contract violation: " + errors.getFirst().getMessage());
    }
}
