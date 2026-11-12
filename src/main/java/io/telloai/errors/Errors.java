package io.telloai.errors;

import io.telloai.events.ErrorEvent;

/** Maps gateway error codes to SDK exceptions 1:1 (see {@code docs/errors/errors.v1.json}). */
public final class Errors {

    private Errors() {}

    /**
     * The exception for an error frame, with every extra field it carries
     * ({@code question}, {@code requiredScope}, {@code ip}).
     */
    public static TelloException exceptionFor(ErrorEvent err) {
        return exceptionFor(err.code, err.message, err.question, err.requiredScope, err.ip);
    }

    /** The exception for {@code code}; {@code requiredScope} and {@code ip} are left null. */
    public static TelloException exceptionFor(String code, String message, String question) {
        return exceptionFor(code, message, question, null, null);
    }

    private static TelloException exceptionFor(String code, String message, String question,
                                               String requiredScope, String ip) {
        if (code == null) {
            return new TelloServerException(message);
        }
        switch (code) {
            case "unauthenticated":
                return new AuthenticationException(message, code);
            case "ipNotAllowed":
                return new AuthenticationException(message, code, ip);
            case "insufficientScope":
                return new PermissionDeniedException(message, code, requiredScope);
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
