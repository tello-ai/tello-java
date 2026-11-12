package io.telloai.errors;

/**
 * Connection auth failed: the key was refused ({@code unauthenticated}), the connection's
 * address is not on the key's allowed IP list ({@code ipNotAllowed}), or close 4401.
 */
public class AuthenticationException extends TelloException {

    /**
     * For {@code ipNotAllowed}, the connection's address as the gateway's edge proved it;
     * null when it proved none, and for every other cause.
     */
    public final String ip;

    public AuthenticationException(String message) {
        this(message, null, null);
    }

    public AuthenticationException(String message, String code) {
        this(message, code, null);
    }

    public AuthenticationException(String message, String code, String ip) {
        super(message, code);
        this.ip = ip;
    }
}
