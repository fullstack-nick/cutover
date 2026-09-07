package dev.cutover.platform.messaging;

/** Stable, payload-free diagnostic codes are safe to retain in operational status. */
public final class DeliveryFailure extends RuntimeException {
    private final boolean retryable;
    public DeliveryFailure(String code, boolean retryable) { super(code); this.retryable = retryable; }
    public boolean retryable() { return retryable; }
    public static DeliveryFailure permanent(String code) { return new DeliveryFailure(code, false); }
    public static DeliveryFailure pending(String code) { return new DeliveryFailure(code, true); }
}
