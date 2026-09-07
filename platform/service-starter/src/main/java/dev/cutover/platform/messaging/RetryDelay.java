package dev.cutover.platform.messaging;

import java.time.Duration;
import java.util.UUID;

public final class RetryDelay {
    public static final int MAX_ATTEMPTS = 6; // Initial delivery plus five delayed retries.
    private RetryDelay() {}
    public static Duration after(UUID id, int failure) {
        long base = 1000L << Math.min(Math.max(failure - 1, 0), 4);
        long jitter = Math.floorMod(id.getLeastSignificantBits() ^ failure, base / 5 + 1);
        return Duration.ofMillis(base + jitter);
    }
}
