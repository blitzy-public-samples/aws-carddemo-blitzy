package com.carddemo.common.exception;

/**
 * :purpose: Models the COBOL read-snapshot-compare-rewrite conflict
 *  (``DATA-WAS-CHANGED-BEFORE-UPDATE``), now surfaced from JPA ``@Version``
 *  optimistic locking; carries the exact legacy conflict message.
 */
public class OptimisticLockConflictException extends CardDemoException {

    /** :purpose: Serialization version identifier for this exception type. */
    private static final long serialVersionUID = 1L;

    /**
     * :purpose: Exact legacy conflict message from the ``COACTUPC``
     *  ``DATA-WAS-CHANGED-BEFORE-UPDATE`` 88-level condition, preserved as the
     *  single source of truth for callers and tests.
     */
    public static final String MESSAGE = "Record changed by some one else. Please review";

    /**
     * :purpose: Construct the conflict exception carrying the exact legacy
     *  conflict message.
     */
    public OptimisticLockConflictException() {
        super(MESSAGE);
    }

    /**
     * :purpose: Construct the conflict exception carrying the exact legacy
     *  conflict message and wrapping the underlying optimistic-lock failure.
     * :param cause: the underlying optimistic-lock throwable that triggered
     *  this conflict.
     */
    public OptimisticLockConflictException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
