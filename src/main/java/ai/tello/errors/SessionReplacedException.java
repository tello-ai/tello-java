package ai.tello.errors;

/** The connection was displaced by another session (gateway close 4429). */
public class SessionReplacedException extends TelloException {
    public SessionReplacedException(String message) {
        super(message);
    }
}
