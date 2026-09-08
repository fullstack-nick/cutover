package dev.cutover.adapter;

import dev.cutover.platform.JsonSupport;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Compares retained owner and physical evidence for a finite, settled inventory page. */
final class MigrationProofs {
    static final class Pending extends RuntimeException {
        final String code;
        final String movementId;
        Pending(String code, String movementId) {
            super(code);
            this.code = code;
            this.movementId = movementId;
        }
    }

    private MigrationProofs() {}

    static JsonNode compare(JsonNode inventory, JsonNode core, JsonNode execution, JsonNode world) {
        if (!world.path("completeHistory").asBoolean()) throw new Pending("PHYSICAL_HISTORY_INCOMPLETE", inventory.get(0).path("movementId").asString());
        var coreItems = index(core, "legacy-core", inventory);
        var executionItems = execution == null ? Map.<String, JsonNode>of() : index(execution, "execution-service", inventory);
        var proof = JsonSupport.MAPPER.createArrayNode();
        for (var allocation : inventory) {
            String id = allocation.path("movementId").asString();
            var owned = coreItems.get(id);
            if (owned == null || owned.path("missing").asBoolean()) throw new Pending("CORE_CONTEXT_MISSING", id);
            if (!JsonSupport.hash(owned.path("movement")).equals(allocation.path("payloadHash").asString()))
                throw new Pending("CORE_INTENT_MISMATCH", id);
            var task = allocation.path("owner").asString().equals("legacy-core") ? owned.path("task")
                : executionItems.getOrDefault(id, JsonSupport.MAPPER.nullNode()).path("task");
            String state = allocation.path("state").asString();
            if (!state.equals(owned.path("state").asString())) throw new Pending("INVENTORY_EFFECT_PENDING", id);
            if (owned.path("reservation").path("quantity").asInt() != allocation.path("movement").path("quantity").asInt())
                throw new Pending("RESERVATION_QUANTITY_MISMATCH", id);
            if (task.isMissingNode() || task.isNull()) {
                // A cancellation may win before the assignment event creates any task. Its retained
                // never-submitted certificate and released reservation are still required below.
                if (!state.equals("CANCELLED")) throw new Pending("OWNER_TASK_MISSING", id);
            } else if (!id.equals(task.path("movementId").asString())
                || !allocation.path("allocationId").equals(task.path("allocationId"))
                || !allocation.path("owner").equals(task.path("owner"))
                || !task.path("epoch").isIntegralNumber() || task.path("epoch").asLong() != allocation.path("epoch").asLong()
                || !state.equals(task.path("state").asString())) throw new Pending("OWNER_TASK_NOT_RECONCILED", id);
            var command = allocation.path("command");
            if (state.equals("COMPLETED")) {
                if (!owned.path("reservation").path("state").asString().equals("CONSUMED")) throw new Pending("INVENTORY_EFFECT_PENDING", id);
                var effect = owned.path("inventoryEffect");
                var physical = command.path("evidence");
                if (!command.path("state").asString().equals("COMPLETED") || !command.path("acceptedEver").asBoolean()
                    || !id.equals(command.path("commandId").asString()) || !id.equals(physical.path("commandId").asString())
                    || !allocation.path("allocationId").equals(command.path("allocationId"))
                    || !allocation.path("owner").equals(command.path("owner"))
                    || allocation.path("epoch").asLong() != command.path("epoch").asLong(-1)
                    || !allocation.path("siteId").equals(physical.path("siteId")) || !id.equals(physical.path("movementId").asString())
                    || !physical.path("state").asString().equals("COMPLETED") || physical.path("executionSequence").asLong() < 1
                    || physical.path("executionSequence").asLong() > world.path("journalHighWater").asLong()
                    || !physical.path("worldId").equals(world.path("worldId"))
                    || !physical.path("journalGeneration").equals(world.path("journalGeneration"))) throw new Pending("PHYSICAL_PROOF_MISMATCH", id);
                for (String field : Set.of("movementId", "siteId", "loadId", "source", "destination", "zoneId", "quantity"))
                    if (!allocation.path("movement").path(field).equals(command.path("payload").path(field)))
                        throw new Pending("PHYSICAL_PAYLOAD_MISMATCH", id);
                if (!id.equals(effect.path("movementId").asString()) || !id.equals(effect.path("commandId").asString())
                    || effect.path("quantity").asInt() != allocation.path("movement").path("quantity").asInt()
                    || effect.path("executionSequence").asLong() != physical.path("executionSequence").asLong()
                    || !effect.path("worldId").equals(physical.path("worldId"))) throw new Pending("INVENTORY_EFFECT_MISMATCH", id);
            } else if (state.equals("CANCELLED")) {
                var certificate = allocation.path("cancellation");
                boolean found = false;
                for (var item : certificate.path("movements")) if (item.path("movementId").equals(allocation.path("movementId"))
                    && item.path("allocationId").equals(allocation.path("allocationId")) && item.path("proof").asString().equals("NEVER_SUBMITTED")) found = true;
                if (!found || !certificate.path("state").asString().equals("FENCED")
                    || !certificate.path("worldId").equals(world.path("worldId"))
                    || !certificate.path("journalGeneration").equals(world.path("journalGeneration"))
                    || !owned.path("reservation").path("state").asString().equals("RELEASED") || owned.hasNonNull("inventoryEffect"))
                    throw new Pending("CANCELLATION_PROOF_MISSING", id);
                if (!command.isNull() && (!command.path("state").asString().equals("REJECTED_BEFORE_EXECUTION")
                    || command.path("attempts").asInt() != 0 || command.path("acceptedEver").asBoolean() || command.hasNonNull("evidence")))
                    throw new Pending("CANCELLED_COMMAND_HAS_ACCEPTANCE", id);
            } else throw new Pending("ALLOCATION_NOT_TERMINAL", id);
            var checked = proof.addObject().put("movementId", id);
            checked.set("core", owned);
            checked.set("task", task.isMissingNode() ? JsonSupport.MAPPER.nullNode() : task);
            checked.set("physical", state.equals("COMPLETED") ? command.path("evidence") : allocation.path("cancellation"));
        }
        return proof;
    }

    private static Map<String, JsonNode> index(JsonNode response, String service, JsonNode inventory) {
        String site = inventory.get(0).path("siteId").asString(), zone = inventory.get(0).path("zoneId").asString();
        if (!response.path("service").asString().equals(service) || !response.path("siteId").asString().equals(site)
            || !response.path("zoneId").asString().equals(zone) || !response.path("items").isArray())
            throw new Pending("OWNER_EVIDENCE_IDENTITY", inventory.get(0).path("movementId").asString());
        if (service.equals("legacy-core") && !response.path("assignmentBoundary").asBoolean())
            throw new Pending("TASK_CREATION_BOUNDARY_REQUIRED", inventory.get(0).path("movementId").asString());
        var expected = new HashSet<String>();
        for (var item : inventory) if (service.equals("legacy-core") || item.path("owner").asString().equals(service))
            expected.add(item.path("movementId").asString());
        var result = new HashMap<String, JsonNode>();
        for (var item : response.path("items")) {
            String id = item.path("movementId").asString();
            if (!expected.contains(id) || result.put(id, item) != null) throw new Pending("OWNER_EVIDENCE_IDENTITY", id);
        }
        if (!result.keySet().equals(expected)) throw new Pending("OWNER_EVIDENCE_INCOMPLETE", inventory.get(0).path("movementId").asString());
        return result;
    }
}
