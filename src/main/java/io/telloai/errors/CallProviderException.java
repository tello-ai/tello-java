package io.telloai.errors;

/**
 * {@code createCall} was refused by a condition on the service side. The caller
 * did not cause it and cannot fix it.
 *
 * <p>{@code callProviderDraining} and {@code callProviderUnavailable} may
 * succeed later; the other two will not.
 */
public class CallProviderException extends TelloException {
    public CallProviderException(String message) {
        super(message);
    }

    public CallProviderException(String message, String code) {
        super(message, code);
    }
}
