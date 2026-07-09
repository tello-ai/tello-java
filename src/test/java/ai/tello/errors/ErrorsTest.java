package ai.tello.errors;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

class ErrorsTest {

    @Test
    void mapsToRequiredToValidationException() {
        assertInstanceOf(ValidationException.class, Errors.exceptionFor("to_required", "to is required", null));
    }
}
