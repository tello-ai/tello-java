package ai.tello.errors;

/** A command was rejected as invalid (for example {@code to_required}). */
public class ValidationException extends TelloException {
    public ValidationException(String message) {
        super(message);
    }
}
