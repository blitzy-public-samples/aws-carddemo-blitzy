package com.carddemo.common.exception;

/**
 * :purpose: Signals that a request could not be completed because a collaborating
 *  service could not be REACHED at all -- the call never landed, so nothing was
 *  attempted downstream and the caller may retry the identical request. It
 *  separates "the peer is down" from "the peer answered and refused", which the
 *  base {@link CardDemoException} (reported as a client error) cannot express.
 *  Thrown by the service-to-service clients, for example the report hand-off that
 *  re-platforms the ``CORPT00C`` TDQ/JES submission when ``batch-service`` cannot
 *  be reached.
 * :note: The detail message stays the caller's own -- for a legacy-derived path it
 *  is the frozen legacy literal -- so only the reported HTTP status changes
 *  (``503`` rather than ``400``); the observable text is unaffected.
 */
public class UpstreamUnavailableException extends CardDemoException {

    /** :purpose: Serialization version identifier for this exception type. */
    private static final long serialVersionUID = 1L;

    /**
     * :purpose: Construct an upstream-unavailable exception with a detail message.
     * :param message: the detail message reported to the caller.
     */
    public UpstreamUnavailableException(String message) {
        super(message);
    }

    /**
     * :purpose: Construct an upstream-unavailable exception with a detail message
     *  and the transport failure that caused it.
     * :param message: the detail message reported to the caller.
     * :param cause: the transport failure raised while attempting the call.
     */
    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
