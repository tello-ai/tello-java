package ai.tello.errors;

/** A command was rejected as invalid (for example {@code toRequired}). */
public class ValidationException extends TelloException {
    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, String code) {
        super(message, code);
    }
}
