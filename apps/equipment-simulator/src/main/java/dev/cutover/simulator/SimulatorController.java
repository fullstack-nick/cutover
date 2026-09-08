package dev.cutover.simulator;

import dev.cutover.platform.JsonSupport;
import dev.cutover.platform.Problem;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
class SimulatorController {
    private final SimulatorEngine engine;
    private final boolean controls;
    SimulatorController(SimulatorEngine engine, @Value("${cutover.simulator.test-controls-enabled:false}") boolean controls) { this.engine=engine; this.controls=controls; }
    @PutMapping("/sim/v1/commands/{id}") JsonNode accept(@PathVariable UUID id, @RequestBody JsonNode body) throws InterruptedException {
        SimulatorEngine.Acceptance acceptance;
        try { acceptance=engine.accept(id,body); }
        catch (SimulatorEngine.DisconnectBeforeAcceptance fault) {
            throw new Problem(503,"SIMULATED_DISCONNECT","The transport failed before durable acceptance; investigate the stable command ID.");
        }
        if (acceptance.responseDelayMillis()>0) Thread.sleep(acceptance.responseDelayMillis());
        return acceptance.result();
    }
    @GetMapping("/sim/v1/commands/{id}") ResponseEntity<JsonNode> status(@PathVariable UUID id) {
        try { return ResponseEntity.ok(engine.observedStatus(id)); }
        catch (Problem absent) {
            if (absent.status()!=404) throw absent;
            return ResponseEntity.status(404).body(engine.absence(id));
        }
    }
    @GetMapping("/sim/v1/equipment") JsonNode equipment() { return engine.equipment(); }
    @GetMapping("/sim/v1/history") JsonNode history(@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="100") int limit) { return engine.history(after,limit); }
    @GetMapping("/sim/v1/recovery-inventory") JsonNode recoveryInventory(@RequestParam(required=false) UUID after,@RequestParam(defaultValue="32") int limit) { return engine.recoveryInventory(after,limit); }
    record Fault(String kind, UUID commandId, int count, int delayMillis) {}
    @PostMapping("/sim/v1/test-controls/faults") JsonNode fault(@RequestBody Fault fault) {
        requireControls(); UUID id=engine.fault(fault.kind(),fault.commandId(),fault.count(),fault.delayMillis());
        return JsonSupport.MAPPER.valueToTree(java.util.Map.of("configured",true,"faultId",id));
    }
    @GetMapping("/sim/v1/test-controls/faults") JsonNode faults() { requireControls(); return engine.faults(); }
    @DeleteMapping("/sim/v1/test-controls/faults/{id}") JsonNode clear(@PathVariable UUID id) {
        requireControls(); engine.clearFault(id); return JsonSupport.MAPPER.valueToTree(java.util.Map.of("faultId",id,"cleared",true));
    }
    record Lane(String siteId, String laneId, boolean blocked) {}
    @PostMapping("/sim/v1/test-controls/lanes") JsonNode lane(@RequestBody Lane lane) {
        requireControls(); engine.blockLane(lane.siteId(),lane.laneId(),lane.blocked()); return engine.equipment();
    }
    private void requireControls() { if (!controls) throw Problem.missing(); }
}
