package ai.tello;

/** Public call status vocabulary sent by the gateway (see the WS protocol contract). */
public enum PublicStatus {
    QUEUED("queued"),
    DIALING("dialing"),
    RINGING("ringing"),
    IN_PROGRESS("in_progress"),
    TRANSFERRING("transferring"),
    COMPLETED("completed"),
    NO_ANSWER("no_answer"),
    FAILED("failed"),
    CANCELLED("cancelled");

    public static final String PROTOCOL_VERSION = "1.0";

    private final String wire;

    PublicStatus(String wire) {
        this.wire = wire;
    }

    /** The on-the-wire string value. */
    public String wire() {
        return wire;
    }

    /** Resolve a wire string to its enum constant, or {@code null} if unknown. */
    public static PublicStatus fromWire(String value) {
        for (PublicStatus status : values()) {
            if (status.wire.equals(value)) {
                return status;
            }
        }
        return null;
    }
}
