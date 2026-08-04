package com.carddemo.authorization.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Machine-readable error body of every failed authorization call.
 *
 * <p>ADDITIVE. The source has no error body to reproduce. It moves one text into
 * {@code WS-MESSAGE} and redisplays the screen, as {@code app/cbl/COTRN02C.cbl:L254-L320} does eleven
 * times over. This record carries those same texts to a caller that has no screen.
 *
 * <p>Four components and nothing else. No component echoes the request path, the query string or any
 * header, so a card number sent in the wrong position cannot be reflected back or written to a log
 * through this body. That omission is deliberate and is what separates this record from a framework
 * default error body.
 *
 * <p>{@link #messages()} carries the verbatim source texts, one per failing field, so a caller reads
 * exactly what a terminal operator would have read.
 *
 * @param status    the Hypertext Transfer Protocol status code of the response
 * @param error     a short, fixed phrase naming the class of failure. The value comes from a constant
 *                  of this record and never from caller-supplied text
 * @param messages  one text per failing field, each reproduced from the source paragraph that emits
 *                  it. Never {@code null}, and never empty
 * @param timestamp the moment the response was built
 */
public record ApiErrorResponse(int status, String error, List<String> messages, Instant timestamp) {

    /** The {@link #error()} phrase of a request whose fields failed validation. */
    public static final String VALIDATION_FAILED = "Validation failed";

    /** The {@link #error()} phrase of a request the service could not process. */
    public static final String UNPROCESSABLE = "Unprocessable request";

    /** The {@link #error()} phrase of a fault inside the service. */
    public static final String INTERNAL_FAILURE = "Internal failure";

    /**
     * Copies the message list and rejects an incomplete body.
     *
     * @throws NullPointerException     when any component is {@code null}
     * @throws IllegalArgumentException when {@code messages} is empty
     */
    public ApiErrorResponse {
        Objects.requireNonNull(error, "error must be present");
        Objects.requireNonNull(messages, "messages must be present");
        Objects.requireNonNull(timestamp, "timestamp must be present");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages carries one text per failing field and is "
                    + "never empty");
        }
        messages = List.copyOf(messages);
    }

    /**
     * Builds an error body carrying one or more field texts.
     *
     * @param status   the status code of the response
     * @param error    one of the three phrases this record declares
     * @param messages one text per failing field
     * @return the error body, stamped with the current moment
     */
    public static ApiErrorResponse of(int status, String error, List<String> messages) {
        return new ApiErrorResponse(status, error, messages, Instant.now());
    }

    /**
     * Builds an error body carrying one text.
     *
     * @param status  the status code of the response
     * @param error   one of the three phrases this record declares
     * @param message the one text
     * @return the error body, stamped with the current moment
     */
    public static ApiErrorResponse of(int status, String error, String message) {
        return of(status, error, List.of(message));
    }
}
