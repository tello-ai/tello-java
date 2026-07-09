package ai.tello.errors;

/** A call is already active on this connection ({@code callAlreadyActive}). */
public class CallAlreadyActiveException extends TelloException {
    public CallAlreadyActiveException(String message) {
        super(message);
    }
}
