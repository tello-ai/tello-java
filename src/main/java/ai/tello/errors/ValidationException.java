package ai.tello.errors;

/** A command was rejected as invalid (gateway code {@code agent_id_required}). */
public class ValidationException extends TelloException {
    public ValidationException(String message) {
        super(message);
    }
}
