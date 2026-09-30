package io.telloai.errors;

/**
 * {@code createCall} was refused by an account policy gate before any call
 * existed: no {@code call.created}, no call id, no charge.
 *
 * <p>The account owner can act on {@code insufficientCredit},
 * {@code callerNotVerified} and {@code noRepresentativeNumber}; only
 * {@code concurrentLimitExceeded} can succeed on a later attempt. The gateway
 * never retries, so any retry policy is the caller's. Branch on {@link #code}.
 */
public class CallRefusedException extends TelloException {
    public CallRefusedException(String message) {
        super(message);
    }

    public CallRefusedException(String message, String code) {
        super(message, code);
    }
}
