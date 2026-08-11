package com.carddemo.common.exception;

/**
 * :purpose: Signals that a keyed record lookup found no matching record, modeling
 *  the legacy CICS ``RESP(NOTFND)`` / VSAM not-found outcome for account, customer,
 *  card, cross-reference, and transaction reads. Thrown by the service and
 *  repository layers when a keyed lookup returns no result; the caller supplies the
 *  detail message and any optional error code, so this type defines no message text
 *  of its own.
 */
public class RecordNotFoundException extends CardDemoException {

    /** :purpose: Serialization version identifier for this exception type. */
    private static final long serialVersionUID = 1L;

    /**
     * :purpose: Construct a not-found exception with a detail message.
     * :param message: the detail message describing the missing record.
     */
    public RecordNotFoundException(String message) {
        super(message);
    }

    /**
     * :purpose: Construct a not-found exception with a detail message and an
     *  underlying cause.
     * :param message: the detail message describing the missing record.
     * :param cause: the underlying throwable that triggered this lookup failure.
     */
    public RecordNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * :purpose: Construct a not-found exception with a domain error code and a
     *  detail message.
     * :param errorCode: the optional domain or legacy error code.
     * :param message: the detail message describing the missing record.
     */
    public RecordNotFoundException(String errorCode, String message) {
        super(errorCode, message);
    }
}
