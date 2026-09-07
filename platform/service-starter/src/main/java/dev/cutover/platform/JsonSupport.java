package dev.cutover.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Wire serialization and deterministic payload fingerprints; contains no business models. */
public final class JsonSupport {
    public static final JsonMapper MAPPER = JsonMapper.builder().build();
    private JsonSupport() {}

    public static String write(Object value) { return MAPPER.writeValueAsString(value); }
    public static JsonNode read(String json) { return MAPPER.readTree(json); }
    public static String hash(Object value) {
        try {
            JsonNode tree = value instanceof JsonNode node ? node : MAPPER.valueToTree(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(write(canonical(tree)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            node.properties().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(entry -> sorted.set(entry.getKey(), canonical(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = MAPPER.createArrayNode();
            node.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return node;
    }
}
