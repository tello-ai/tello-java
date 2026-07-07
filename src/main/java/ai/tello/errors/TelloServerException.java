package ai.tello.errors;

/** Gateway-side internal error ({@code internal_error}). */
public class TelloServerException extends TelloException {
    public TelloServerException(String message) {
        super(message);
    }
}
