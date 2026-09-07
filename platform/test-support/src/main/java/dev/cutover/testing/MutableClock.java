package dev.cutover.testing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

public final class MutableClock extends Clock {
    private final AtomicReference<Instant> instant;
    public MutableClock(Instant initial) { instant=new AtomicReference<>(initial); }
    public void advance(Duration duration) { instant.updateAndGet(value->value.plus(duration)); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(),zone); }
    @Override public Instant instant() { return instant.get(); }
}
