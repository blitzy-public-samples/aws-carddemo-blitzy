package com.carddemo.common.exception;

/**
 * :purpose: Models the CBTRN02C over-limit posting reject — a daily transaction
 *  whose computed cycle balance would exceed the account credit limit.
 */
public class OverLimitException extends TransactionRejectException {

    /** :purpose: Serialization version identifier for this exception type. */
    private static final long serialVersionUID = 1L;

    /**
     * :purpose: Construct the over-limit reject carrying the inherited reject
     *  code and its exact description.
     */
    public OverLimitException() {
        super(OVER_LIMIT, MSG_OVER_LIMIT);
    }

    /**
     * :purpose: Construct the over-limit reject carrying the inherited reject
     *  code and its exact description, wrapping the underlying cause.
     * :param cause: the underlying throwable that triggered this reject.
     */
    public OverLimitException(Throwable cause) {
        super(OVER_LIMIT, MSG_OVER_LIMIT, cause);
    }
}
