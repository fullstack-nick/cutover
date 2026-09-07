package dev.cutover.simulator;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @ConditionalOnProperty(name="cutover.workers-enabled",havingValue="true",matchIfMissing=true)
class SimulatorWorker {
    private final SimulatorEngine engine;
    SimulatorWorker(SimulatorEngine engine) { this.engine=engine; }
    @Scheduled(fixedDelayString="${cutover.simulator.tick-ms:100}") void tick() { engine.advance(); }
}
