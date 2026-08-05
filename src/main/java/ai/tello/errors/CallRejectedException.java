package ai.tello.errors;

/** The call was rejected by intent validation ({@code callRejected}). */
public class CallRejectedException extends TelloException {

    /** Nullable clarifying question returned by the gateway. */
    public final String question;

    public CallRejectedException(String message, String question) {
        this(message, question, null);
    }

    public CallRejectedException(String message, String question, String code) {
        super(message, code);
        this.question = question;
    }
}
