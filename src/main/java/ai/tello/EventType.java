package ai.tello;

/**
 * Inbound event {@code type} string constants.
 *
 * <p>The first group matches the gateway 1:1. {@link #DISCONNECTED} is an
 * SDK-local pseudo-event emitted when the WS connection ends (never sent by the
 * gateway).
 */
public final class EventType {

    public static final String USER_TURN = "user.turn";
    public static final String AGENT_TURN = "agent.turn";
    public static final String CALL_STATUS_CHANGED = "call.status_changed";
    public static final String CALL_COMPLETED = "call.completed";
    public static final String CALL_NO_ANSWER = "call.no_answer";
    public static final String CALL_FAILED = "call.failed";
    public static final String ERROR = "error";
    public static final String DISCONNECTED = "disconnected";

    private EventType() {}
}
