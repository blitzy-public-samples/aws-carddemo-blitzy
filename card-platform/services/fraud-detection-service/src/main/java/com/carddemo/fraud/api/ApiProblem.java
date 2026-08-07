package com.carddemo.fraud.api;

import java.util.Objects;

/**
 * Error body of every failed call to this service, as a problem document.
 *
 * <p>ADDITIVE. No program under {@code app/cbl/} scores risk, so this service has no source error
 * body to reproduce and no source text to carry.
 *
 * <p>The four members are the ones RFC 9457 defines, and they are spelled exactly as
 * {@code config/SecurityConfig} spells them when it answers {@code 401} and {@code 403}. One service
 * answering two shapes of error would make a client parse both, so every failing path of this
 * service now writes this shape under {@code application/problem+json}.
 * {@code src/main/resources/openapi.yaml} declares the same four members and closes the set.
 *
 * <p>No member echoes a request value. Nothing carries the rejected value, the resolved path, the
 * query string or a header, so an account identifier or a transaction identifier sent in the wrong
 * position cannot be reflected back to the caller or copied into an access log through this body.
 * That omission is the difference between this record and a framework default error body, which
 * carries the resolved path in a {@code path} or an {@code instance} member.
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

    /** Title of a fault inside this service. */
    public static final String INTERNAL_SERVER_ERROR = "Internal Server Error";

    /**
     * Detail of a request whose path or query value missed the constraint declared for it.
     *
     * <p>One text covers every such refusal, so no rejected value reaches the body: an identifier of
     * the wrong width, a page below zero and a page size outside its bounds all read alike.
     */
    public static final String INVALID_REQUEST_CONTENT = "Invalid request content.";

    /** Detail of a read this service could not complete. */
    public static final String ASSESSMENT_NOT_READ = "The assessment could not be read.";

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
