package ai.tello.errors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class ErrorsTest {

    /** Every code in docs/errors/errors.v1.json, in the order the contract lists them. */
    private static final List<Mapping> CONTRACT = List.of(
            new Mapping("unauthenticated", AuthenticationException.class),
            new Mapping("callAlreadyActive", CallAlreadyActiveException.class),
            new Mapping("toRequired", ValidationException.class),
            new Mapping("callIdRequired", ValidationException.class),
            new Mapping("callNotFound", ValidationException.class),
            new Mapping("callNotCompleted", ValidationException.class),
            new Mapping("noActiveCall", NoActiveCallException.class),
            new Mapping("dtmfDigitsRequired", ValidationException.class),
            new Mapping("dtmfDigitsInvalid", ValidationException.class),
            new Mapping("callRejected", CallRejectedException.class),
            new Mapping("insufficientCredit", CallRefusedException.class),
            new Mapping("concurrentLimitExceeded", CallRefusedException.class),
            new Mapping("callerNotVerified", CallRefusedException.class),
            new Mapping("noRepresentativeNumber", CallRefusedException.class),
            new Mapping("callProviderUnauthorized", CallProviderException.class),
            new Mapping("callProviderDraining", CallProviderException.class),
            new Mapping("callProviderUnavailable", CallProviderException.class),
            new Mapping("callSetupFailed", CallProviderException.class),
            new Mapping("internalError", TelloServerException.class));

    private record Mapping(String code, Class<? extends TelloException> expected) {}

    @TestFactory
    List<DynamicTest> mapsEveryContractCode() {
        return CONTRACT.stream()
                .map(mapping -> DynamicTest.dynamicTest(mapping.code(), () -> {
                    TelloException ex = Errors.exceptionFor(mapping.code(), "message", null);
                    assertInstanceOf(mapping.expected(), ex);
                    // Callers branch on code, never on message: the gateway may
                    // reword the message, the code is the contract.
                    assertEquals(mapping.code(), ex.code);
                }))
                .toList();
    }

    @Test
    void coversEveryCodeTheContractDefines() {
        assertEquals(19, CONTRACT.size());
    }

    @Test
    void unknownCodeFallsBackToServerException() {
        TelloException ex = Errors.exceptionFor("somethingNewUpstream", "boom", null);

        assertInstanceOf(TelloServerException.class, ex);
        assertEquals("somethingNewUpstream", ex.code);
    }

    @Test
    void nullCodeFallsBackToServerExceptionWithoutACode() {
        TelloException ex = Errors.exceptionFor(null, "boom", null);

        assertInstanceOf(TelloServerException.class, ex);
        assertNull(ex.code);
    }

    @Test
    void callRejectedPreservesQuestion() {
        TelloException ex = Errors.exceptionFor("callRejected", "Call rejected", "why?");

        assertInstanceOf(CallRejectedException.class, ex);
        assertEquals("why?", ((CallRejectedException) ex).question);
        assertEquals("callRejected", ex.code);
    }
}
