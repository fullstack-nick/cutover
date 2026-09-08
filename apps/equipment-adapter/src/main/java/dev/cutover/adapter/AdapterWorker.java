package dev.cutover.adapter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import dev.cutover.platform.Problem;

@Component @ConditionalOnProperty(name="cutover.workers-enabled",havingValue="true",matchIfMissing=true)
class AdapterWorker {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(AdapterWorker.class);
    private final EquipmentObservations observations;
    private final CommandJournal journal;
    private final Allocations allocations;
    AdapterWorker(EquipmentObservations observations,CommandJournal journal,Allocations allocations) { this.observations=observations;this.journal=journal;this.allocations=allocations; }
    @Scheduled(fixedDelay=1000) void observe() {
        try { observations.refresh(); } catch(EquipmentPort.Unavailable unavailable) { LOG.warn("Equipment observations are unavailable; retained timestamps indicate staleness."); }
    }
    @Scheduled(fixedDelay=150) void dispatch() { journal.work(); }
    @Scheduled(fixedDelay=200) void assignPending() {
        try { allocations.releasePending(); }
        catch(Problem paused) { if(paused.status()!=503)throw paused; }
    }
}
