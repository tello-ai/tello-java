package ai.tello.errors;

/** Base class for all Tello SDK errors (unchecked, so it is lambda/handler friendly). */
public class TelloException extends RuntimeException {
    public TelloException(String message) {
        super(message);
    }
}
