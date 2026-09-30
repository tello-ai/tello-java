package io.telloai.errors;

/** Maps gateway error codes to SDK exceptions 1:1 (see {@code docs/errors/errors.v1.json}). */
public final class Errors {

    private Errors() {}

    public static TelloException exceptionFor(String code, String message, String question) {
        if (code == null) {
            return new TelloServerException(message);
        }
        switch (code) {
            case "unauthenticated":
                return new AuthenticationException(message, code);
            case "toRequired":
            case "callIdRequired":
            case "dtmfDigitsRequired":
            case "dtmfDigitsInvalid":
            case "callNotFound":
            case "callNotCompleted":
                return new ValidationException(message, code);
            case "callAlreadyActive":
                return new CallAlreadyActiveException(message, code);
            case "noActiveCall":
                return new NoActiveCallException(message, code);
            case "callRejected":
                return new CallRejectedException(message, question, code);
            case "insufficientCredit":
            case "concurrentLimitExceeded":
            case "callerNotVerified":
            case "noRepresentativeNumber":
                return new CallRefusedException(message, code);
            case "callProviderUnauthorized":
            case "callProviderDraining":
            case "callProviderUnavailable":
            case "callSetupFailed":
                return new CallProviderException(message, code);
            case "internalError":
            default:
                return new TelloServerException(message, code);
        }
    }
}
