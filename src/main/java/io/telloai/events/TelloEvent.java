package io.telloai.events;

/** Common supertype for everything delivered to {@code TelloClient.on(...)} handlers. */
public interface TelloEvent {
    /** The event {@code type} discriminator (e.g. {@code "user.turn"}, {@code "error"}). */
    String type();
}
