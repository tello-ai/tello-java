package ai.tello.errors;

/** No active call for the attempted command ({@code noActiveCall}). */
public class NoActiveCallException extends TelloException {
    public NoActiveCallException(String message) {
        super(message);
    }
}
