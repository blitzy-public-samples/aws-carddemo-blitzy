package com.carddemo.common.exception;

/**
 * :purpose: Models a CBTRN02C daily-transaction posting validation reject. Carries the
 *  numeric reject reason code (100-103) and its exact description, as captured by the
 *  legacy ``WS-VALIDATION-FAIL-REASON`` and ``WS-VALIDATION-FAIL-REASON-DESC`` fields.
 *  Rejected records are written elsewhere as a 350B transaction plus an 80B trailer (430B).
 */
public class TransactionRejectException extends CardDemoException {

    /** :purpose: Serialization version identifier for this exception type. */
    private static final long serialVersionUID = 1L;

    /** :purpose: Reject reason code for a card cross-reference lookup miss. */
    public static final int INVALID_CARD_NUMBER = 100;

    /** :purpose: Reject reason code for an account lookup miss. */
    public static final int ACCOUNT_NOT_FOUND = 101;

    /** :purpose: Reject reason code for a transaction that exceeds the credit limit. */
    public static final int OVER_LIMIT = 102;

    /** :purpose: Reject reason code for a transaction received after account expiration. */
    public static final int ACCOUNT_EXPIRED = 103;

    /** :purpose: Exact reject description for reason code 100. */
    public static final String MSG_INVALID_CARD_NUMBER = "INVALID CARD NUMBER FOUND";

    /** :purpose: Exact reject description for reason code 101. */
    public static final String MSG_ACCOUNT_NOT_FOUND = "ACCOUNT RECORD NOT FOUND";

    /** :purpose: Exact reject description for reason code 102. */
    public static final String MSG_OVER_LIMIT = "OVERLIMIT TRANSACTION";

    /** :purpose: Exact reject description for reason code 103. */
    public static final String MSG_ACCOUNT_EXPIRED = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    /** :purpose: Numeric reject reason code (100-103) carried by this exception. */
    private final int rejectCode;

    /**
     * :purpose: Construct a reject with a numeric reason code and its exact description.
     * :param rejectCode: the numeric reject reason code (100-103).
     * :param message: the exact reject description.
     */
    public TransactionRejectException(int rejectCode, String message) {
        super(Integer.toString(rejectCode), message);
        this.rejectCode = rejectCode;
    }

    /**
     * :purpose: Construct a reject with a numeric reason code, description, and underlying cause.
     * :param rejectCode: the numeric reject reason code (100-103).
     * :param message: the exact reject description.
     * :param cause: the underlying throwable that caused this reject.
     */
    public TransactionRejectException(int rejectCode, String message, Throwable cause) {
        super(Integer.toString(rejectCode), message, cause);
        this.rejectCode = rejectCode;
    }

    /**
     * :purpose: Expose the numeric reject reason code carried by this exception.
     * :returns: the numeric reject reason code (100-103).
     */
    public int getRejectCode() {
        return rejectCode;
    }

    /**
     * :purpose: Build a reject for a card cross-reference lookup miss (code 100).
     * :returns: a reject carrying code 100 and its exact description.
     */
    public static TransactionRejectException invalidCardNumber() {
        return new TransactionRejectException(INVALID_CARD_NUMBER, MSG_INVALID_CARD_NUMBER);
    }

    /**
     * :purpose: Build a reject for an account lookup miss (code 101).
     * :returns: a reject carrying code 101 and its exact description.
     */
    public static TransactionRejectException accountNotFound() {
        return new TransactionRejectException(ACCOUNT_NOT_FOUND, MSG_ACCOUNT_NOT_FOUND);
    }
}
