package io.telloai.errors;

/** The connection was displaced by another session (gateway close 4429). */
public class SessionReplacedException extends TelloException {
    public SessionReplacedException(String message) {
        super(message);
    }

    public SessionReplacedException(String message, String code) {
        super(message, code);
    }
}
