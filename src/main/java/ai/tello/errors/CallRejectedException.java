package ai.tello.errors;

/** The call was rejected by intent validation ({@code call_rejected}). */
public class CallRejectedException extends TelloException {

    /** Nullable clarifying question returned by the gateway. */
    public final String question;

    public CallRejectedException(String message, String question) {
        super(message);
        this.question = question;
    }
}
