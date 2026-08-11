package com.carddemo.common.exception;

/**
 * :purpose: Models the CBTRN02C daily-transaction posting reject for a transaction
 *  received after the account expiration date (the legacy ``ACCT-EXPIRAION-DATE``
 *  ELSE branch; source spelling preserved). Carries the inherited
 *  ``ACCOUNT_EXPIRED`` reject code and ``MSG_ACCOUNT_EXPIRED`` description from
 *  {@link TransactionRejectException}.
 */
public class AccountExpiredException extends TransactionRejectException {

    /** :purpose: Serialization version identifier for this exception type. */
    private static final long serialVersionUID = 1L;

    /**
     * :purpose: Construct the account-expired reject carrying the inherited
     *  ``ACCOUNT_EXPIRED`` code and ``MSG_ACCOUNT_EXPIRED`` description.
     */
    public AccountExpiredException() {
        super(ACCOUNT_EXPIRED, MSG_ACCOUNT_EXPIRED);
    }

    /**
     * :purpose: Construct the account-expired reject carrying the inherited
     *  ``ACCOUNT_EXPIRED`` code and ``MSG_ACCOUNT_EXPIRED`` description, with an
     *  underlying cause.
     * :param cause: the underlying throwable that triggered this reject.
     */
    public AccountExpiredException(Throwable cause) {
        super(ACCOUNT_EXPIRED, MSG_ACCOUNT_EXPIRED, cause);
    }
}
