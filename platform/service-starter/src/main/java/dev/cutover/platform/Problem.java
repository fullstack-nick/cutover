package dev.cutover.platform;

public final class Problem extends RuntimeException {
    private final int status;
    private final String code;
    public Problem(int status, String code, String detail) { super(detail); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
    public static Problem conflict(String code, String detail) { return new Problem(409, code, detail); }
    public static Problem missing() { return new Problem(404, "RESOURCE_NOT_FOUND", "The requested resource is unavailable."); }
    public static Problem invalid(String detail) { return new Problem(422, "INVALID_REQUEST", detail); }
}
