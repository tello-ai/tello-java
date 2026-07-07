package ai.tello.errors;

/** Maps gateway error codes to SDK exceptions 1:1 (see {@code docs/errors/errors.v1.json}). */
public final class Errors {

    private Errors() {}

    public static TelloException exceptionFor(String code, String message, String question) {
        if (code == null) {
            return new TelloServerException(message);
        }
        switch (code) {
            case "unauthenticated":
                return new AuthenticationException(message);
            case "agent_id_required":
                return new ValidationException(message);
            case "call_already_active":
                return new CallAlreadyActiveException(message);
            case "no_active_call":
                return new NoActiveCallException(message);
            case "call_rejected":
                return new CallRejectedException(message, question);
            case "internal_error":
            default:
                return new TelloServerException(message);
        }
    }
}
