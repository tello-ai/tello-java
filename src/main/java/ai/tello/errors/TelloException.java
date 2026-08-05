package ai.tello.errors;

/** Base class for all Tello SDK errors (unchecked, so it is lambda/handler friendly). */
public class TelloException extends RuntimeException {

    /**
     * The gateway error code this was built from, or {@code null} when the error
     * did not come from an error frame. Branch on this rather than on the
     * message: the message is display text the gateway may reword, the code is
     * the contract ({@code docs/errors/errors.v1.json}).
     */
    public final String code;

    public TelloException(String message) {
        this(message, null);
    }

    public TelloException(String message, String code) {
        super(message);
        this.code = code;
    }
}
