package com.carddemo.account.api;

import com.carddemo.events.EventEnvelope;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

/**
 * Error body of every failed call to this service, as a problem document.
 *
 * <p>ADDITIVE. The source has no error body to reproduce. {@code app/cbl/COACTUPC.cbl} moves one text
 * into {@code WS-RETURN-MSG} and redisplays the screen, which it does from more than forty places.
 * This record carries those same texts to a caller that has no screen.
 *
 * <p>The four standard members are the ones RFC 9457 defines, and they are spelled exactly as
 * {@code config/SecurityConfig} spells them when it answers 401 and 403. One service answering two
 * shapes of error would make a client parse both, so both paths write the same shape and the same
 * media type, {@code application/problem+json}.
 *
 * <p>{@link #messages()} is the one extension member, and it is what makes this document useful here.
 * The validation pass at {@code app/cbl/COACTUPC.cbl:L1470-L1676} produces one text per failing
 * field, reproduced character for character, so a caller reads exactly what a terminal operator would
 * have read. It is omitted from the wire form when a failure produces no field text.
 *
 * <p>No member echoes a request value. Nothing carries the rejected value, the path, the query string
 * or a header, so a Social Security number or a date of birth sent in the wrong position cannot be
 * reflected back to the caller or written to an access log through this body. That omission is
 * deliberate and is the difference between this record and a framework default error body.
 *
 * @param type      the problem type. Always {@link #ABOUT_BLANK}, because the status code and the
 *                  title carry the whole meaning and no dereferenceable type document exists
 * @param title     a short, fixed phrase naming the class of failure. Every value comes from a
 *                  constant of this record and never from caller-supplied text
 * @param status    the Hypertext Transfer Protocol status code of the response
 * @param detail    the explanation. Fixed text, naming no identifier and no route
 * @param messages  one text per failing field, each reproduced from the source paragraph that emits
 *                  it, or {@code null} when the failure produced no field text
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiProblem(String type, String title, int status, String detail,
        List<String> messages) {

    /**
     * The one problem type this service writes.
     *
     * <p>RFC 9457 names this value the default, and it means the status code carries the semantics. A
     * dereferenceable type document would be a promise to serve it, which a demo does not keep.
     */
    public static final String ABOUT_BLANK = "about:blank";

    /** Title of a request whose fields failed the edits the source performs. */
    public static final String VALIDATION_FAILED = "Validation failed";

    /** Title of a request naming a row this service does not hold. */
    public static final String NOT_FOUND = "Not found";

    /** Title of a request that lost a race against another writer. */
    public static final String CONFLICT = "Conflict";

    /** Title of a request whose body could not be read at all. */
    public static final String MALFORMED_REQUEST = "Malformed request";

    /** Title of a fault inside this service. */
    public static final String INTERNAL_FAILURE = "Internal failure";

    /** Detail of a request whose fields failed the edits the source performs. */
    public static final String VALIDATION_FAILED_DETAIL =
            "One or more submitted fields failed an edit. The messages member carries one text per"
                    + " failing field.";

    /** Detail of a request naming a row this service does not hold. */
    public static final String NOT_FOUND_DETAIL = "This service holds no record for that request.";

    /** Detail of a request whose body could not be read at all. */
    public static final String MALFORMED_REQUEST_DETAIL =
            "The request body holds one account update in JavaScript Object Notation (JSON) and"
                    + " could not be read as one.";

    /** Detail of a fault inside this service. */
    public static final String INTERNAL_FAILURE_DETAIL =
            "This request could not be completed. Nothing was changed.";

    /**
     * Copies the message list and rejects an incomplete document.
     *
     * <p>An empty list is rejected rather than stored. A document carrying an empty
     * {@code messages} member says a field failed and declines to say which, which is worse than
     * omitting the member, so a caller that has no field text passes {@code null}.
     *
     * @throws NullPointerException     when {@code type}, {@code title} or {@code detail} is
     *                                  {@code null}
     * @throws IllegalArgumentException when {@code messages} is present and empty
     */
    public ApiProblem {
        Objects.requireNonNull(type, "type must be present");
        Objects.requireNonNull(title, "title must be present");
        Objects.requireNonNull(detail, "detail must be present");
        if (messages != null) {
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("messages carries one text per failing field, so"
                        + " a failure with no field text omits the member rather than sending it"
                        + " empty");
            }
            messages = List.copyOf(messages);
        }
    }

    /**
     * Builds a document carrying one text per failing field.
     *
     * @param status   the status code of the response
     * @param title    one of the five titles this record declares
     * @param detail   one of the details this record declares
     * @param messages one text per failing field, at least one
     * @return the document
     */
    public static ApiProblem of(int status, String title, String detail, List<String> messages) {
        return new ApiProblem(ABOUT_BLANK, title, status, detail, messages);
    }

    /**
     * Builds a document carrying no field text.
     *
     * @param status the status code of the response
     * @param title  one of the five titles this record declares
     * @param detail one of the details this record declares
     * @return the document
     */
    public static ApiProblem of(int status, String title, String detail) {
        return new ApiProblem(ABOUT_BLANK, title, status, detail, null);
    }

    /**
     * Names all five members and withholds the message texts.
     *
     * <p>This override replaces the representation the compiler generates for a record. That
     * generated form prints every message, and a message produced by an edit can quote the label of
     * a field a caller supplied.
     *
     * <p>The type, the title, the status and the detail stay, because all four are constants of this
     * record rather than values a caller supplies, and a reader diagnosing a rejection needs them.
     * The message count stays for the same reason and the messages themselves do not.
     *
     * @return a rendering that names all five members and discloses no message text, never
     *         {@code null}
     */
    @Override
    public String toString() {
        return "ApiProblem[type=" + type + ", title=" + title + ", status=" + status + ", detail="
                + detail + ", messages=" + (messages == null ? "<absent>"
                        : messages.size() + " " + EventEnvelope.WITHHELD) + "]";
    }
}
