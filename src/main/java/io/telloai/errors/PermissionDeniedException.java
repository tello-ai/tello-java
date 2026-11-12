package io.telloai.errors;

/**
 * The server key lacks the scope a command needs (gateway code {@code insufficientScope}).
 * Only that command is refused; the connection stays open. Retrying never helps: give
 * the key the scope in {@link #requiredScope}.
 */
public class PermissionDeniedException extends TelloException {

    /** The scope the command needs, e.g. {@code calls:write}; null when the frame had none. */
    public final String requiredScope;

    public PermissionDeniedException(String message, String code, String requiredScope) {
        super(message, code);
        this.requiredScope = requiredScope;
    }
}
