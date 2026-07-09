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
            case "toRequired":
            case "agentIdRequired":
                return new ValidationException(message);
            case "callAlreadyActive":
                return new CallAlreadyActiveException(message);
            case "noActiveCall":
                return new NoActiveCallException(message);
            case "callRejected":
                return new CallRejectedException(message, question);
            case "internalError":
            default:
                return new TelloServerException(message);
        }
    }
}
