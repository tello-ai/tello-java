package ai.tello.errors;

/** No active call for the attempted command ({@code no_active_call}). */
public class NoActiveCallException extends TelloException {
    public NoActiveCallException(String message) {
        super(message);
    }
}
