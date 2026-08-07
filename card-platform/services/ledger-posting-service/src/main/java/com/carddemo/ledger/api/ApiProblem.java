package com.carddemo.ledger.api;

import java.util.Objects;

/**
 * Error body of every failed call to this service, as a problem document.
 *
 * <p>ADDITIVE. {@code app/cbl/CBTRN02C.cbl} answers a failure by abending at
 * {@code app/cbl/CBTRN02C.cbl:L707-L711}, which writes a message to the job log and returns nothing
 * to a caller, because a batch program has none. A synchronous route has one, so this record is the
 * shape it reads.
 *
 * <p>The four members are the ones RFC 9457 defines, and they are spelled exactly as
 * {@code config/SecurityConfig} spells them when it answers {@code 401} and {@code 403}. One service
 * answering two shapes of error would make a client parse both, so every failing path of this
 * service now writes this shape under {@code application/problem+json}.
 *
 * <p>No member echoes a request value. Nothing carries the rejected value, the resolved path, the
 * query string or a header. That omission is the difference between this record and the framework
 * default error body this service answered before it: that body carries the resolved path in a
 * {@code path} member, so a caller naming {@code /balances/abcdefghijk} read its own value back, and
 * a caller naming a real account identifier read that identifier back into its own access log.
 *
 * @param type   the problem type. Always {@link #ABOUT_BLANK}: the status code and the title carry
 *               the whole meaning, and a dereferenceable type document would be a promise to serve
 *               it
 * @param title  a short, fixed phrase naming the class of failure. Every value comes from a constant
 *               of this record and never from caller-supplied text
 * @param status the Hypertext Transfer Protocol status code of the response, repeated in the body
 * @param detail the explanation. Fixed text, naming no identifier, no route, no table, no column and
 *               no exception class
 */
public record ApiProblem(String type, String title, int status, String detail) {

    /** The one problem type this service writes. */
    public static final String ABOUT_BLANK = "about:blank";

    /** Title of a request that missed a constraint declared for one of its values. */
    public static final String BAD_REQUEST = "Bad Request";

    /** Title of a dependency of this service being unreachable. */
    public static final String SERVICE_UNAVAILABLE = "Service Unavailable";

    /** Title of a fault inside this service. */
    public static final String INTERNAL_SERVER_ERROR = "Internal Server Error";

    /**
     * Detail of a request whose path value missed the constraint declared for it.
     *
     * <p>The route reads one value, so one text covers every refusal of it. The text names the shape
     * the route accepts, {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}, and never the
     * value submitted.
     */
    public static final String INVALID_ACCOUNT_ID =
            "Account identifier must be eleven digits.";

    /**
     * Detail of a read this service could not attempt because its datastore was unreachable.
     *
     * <p>Separate from {@link #BALANCE_NOT_READ} because the two call for different actions. A
     * dependency being away is worth retrying and says nothing about the request; a fault inside this
     * service is not, and retrying it repeats it.
     */
    public static final String BALANCE_DEPENDENCY_UNAVAILABLE =
            "The balance store is unavailable. Retry shortly.";

    /** Detail of a read this service could not complete. */
    public static final String BALANCE_NOT_READ = "The balance could not be read.";

    /**
     * Checks that every member holds a value.
     *
     * @throws NullPointerException when any text member is {@code null}
     */
    public ApiProblem {
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(title, "title is required");
        Objects.requireNonNull(detail, "detail is required");
    }

    /**
     * Builds a problem document carrying the standard type.
     *
     * @param title  the fixed phrase for this class of failure
     * @param status the status code of the response
     * @param detail the fixed explanation
     * @return the document
     */
    public static ApiProblem of(String title, int status, String detail) {
        return new ApiProblem(ABOUT_BLANK, title, status, detail);
    }
}
