package ai.tello.errors;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

class ErrorsTest {

    @Test
    void mapsToRequiredToValidationException() {
        assertInstanceOf(ValidationException.class, Errors.exceptionFor("toRequired", "to is required", null));
    }

    @Test
    void mapsCamelCaseCodes() {
        assertInstanceOf(ValidationException.class, Errors.exceptionFor("agentIdRequired", "agentId is required", null));
        assertInstanceOf(CallAlreadyActiveException.class, Errors.exceptionFor("callAlreadyActive", "A call is already active", null));
        assertInstanceOf(NoActiveCallException.class, Errors.exceptionFor("noActiveCall", "No active call", null));
        assertInstanceOf(CallRejectedException.class, Errors.exceptionFor("callRejected", "Call rejected", "why?"));
        assertInstanceOf(TelloServerException.class, Errors.exceptionFor("internalError", "Internal error", null));
        assertInstanceOf(AuthenticationException.class, Errors.exceptionFor("unauthenticated", "Authentication required", null));
    }

    @Test
    void unknownCodeFallsBackToServerException() {
        assertInstanceOf(TelloServerException.class, Errors.exceptionFor("future_code", "boom", null));
    }
}
