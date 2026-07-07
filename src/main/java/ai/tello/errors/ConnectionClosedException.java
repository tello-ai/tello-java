package ai.tello.errors;

/** The WebSocket connection is closed or was never established. */
public class ConnectionClosedException extends TelloException {
    public ConnectionClosedException(String message) {
        super(message);
    }
}
