package ai.tello.errors;

/** Connection auth failed (gateway code {@code unauthenticated} / close 4401). */
public class AuthenticationException extends TelloException {
    public AuthenticationException(String message) {
        super(message);
    }

    public AuthenticationException(String message, String code) {
        super(message, code);
    }
}
