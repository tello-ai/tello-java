package ai.tello.errors;

/** Gateway-side internal error ({@code internalError}). */
public class TelloServerException extends TelloException {
    public TelloServerException(String message) {
        super(message);
    }

    public TelloServerException(String message, String code) {
        super(message, code);
    }
}
