package ai.tello;

/**
 * Inbound event {@code type} string constants.
 *
 * <p>The first group matches the gateway 1:1. {@link #DISCONNECTED} is an
 * SDK-local pseudo-event emitted when the WS connection ends (never sent by the
 * gateway). {@link #AUTH_OK} is the server acknowledgement of the {@code auth}
 * handshake, consumed internally by connect and not re-emitted to subscribers.
 */
public final class EventType {

    /** Server acknowledgement of the {@code auth} handshake. Handled internally. */
    public static final String AUTH_OK = "auth.ok";
    public static final String USER_TURN = "user.turn";
    public static final String AGENT_TURN = "agent.turn";
    public static final String CALL_SUMMARY = "call.summary";
    public static final String SMS_SENT = "sms.sent";
    public static final String ANSWER_ACCEPTED = "answer.accepted";
    public static final String DTMF_ACCEPTED = "dtmf.accepted";
    public static final String CALL_CREATED = "call.created";
    public static final String CALL_STATUS_CHANGED = "call.statusChanged";
    public static final String CALL_COMPLETED = "call.completed";
    public static final String CALL_NO_ANSWER = "call.noAnswer";
    public static final String CALL_FAILED = "call.failed";
    public static final String ERROR = "error";
    public static final String DISCONNECTED = "disconnected";

    private EventType() {}
}
