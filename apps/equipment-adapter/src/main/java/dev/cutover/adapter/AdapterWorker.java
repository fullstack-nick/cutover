package dev.cutover.adapter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @ConditionalOnProperty(name="cutover.workers-enabled",havingValue="true",matchIfMissing=true)
class AdapterWorker {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(AdapterWorker.class);
    private final EquipmentObservations observations;
    private final CommandJournal journal;
    AdapterWorker(EquipmentObservations observations,CommandJournal journal) { this.observations=observations;this.journal=journal; }
    @Scheduled(fixedDelay=1000) void observe() {
        try { observations.refresh(); } catch(EquipmentPort.Unavailable unavailable) { LOG.warn("Equipment observations are unavailable; retained timestamps indicate staleness."); }
    }
    @Scheduled(fixedDelay=150) void dispatch() { journal.work(); }
}
