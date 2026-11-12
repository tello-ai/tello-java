package io.telloai.errors;

import java.util.concurrent.TimeoutException;

/**
 * {@code crews().generateAndWait} gave up while the crew generation job was still
 * running. The job keeps running on the server: follow it with
 * {@code crewGenerations().get(generationId)}.
 */
public class CrewGenerationTimeoutException extends TimeoutException {

    /** The id of the job that was still running. */
    public final String generationId;

    public CrewGenerationTimeoutException(String generationId, String message) {
        super(message);
        this.generationId = generationId;
    }
}
