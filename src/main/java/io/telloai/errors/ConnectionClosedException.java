package io.telloai.errors;

/** The WebSocket connection is closed or was never established. */
public class ConnectionClosedException extends TelloException {
    public ConnectionClosedException(String message) {
        super(message);
    }

    public ConnectionClosedException(String message, String code) {
        super(message, code);
    }
}
