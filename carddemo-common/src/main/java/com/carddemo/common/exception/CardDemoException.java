package com.carddemo.common.exception;

/**
 * :purpose: Root of the framework-light CardDemo exception hierarchy. Represents
 *  legacy COBOL RESP codes, VSAM file-status outcomes, and batch reject codes as
 *  unchecked domain exceptions, and carries an optional domain error code alongside
 *  the standard detail message.
 */
public class CardDemoException extends RuntimeException {

    /** :purpose: Serialization version identifier for this exception type. */
    private static final long serialVersionUID = 1L;

    /**
     * :purpose: Optional domain or legacy error code for this exception; ``null``
     *  when the failure carries no discrete code.
     */
    private final String errorCode;

    /**
     * :purpose: Construct an exception with a detail message and no error code or cause.
     * :param message: the detail message.
     */
    public CardDemoException(String message) {
        this(null, message, null);
    }

    /**
     * :purpose: Construct an exception with a detail message and an underlying cause.
     * :param message: the detail message.
     * :param cause: the underlying throwable that caused this exception.
     */
    public CardDemoException(String message, Throwable cause) {
        this(null, message, cause);
    }

    /**
     * :purpose: Construct an exception with a domain error code and a detail message.
     * :param errorCode: the optional domain or legacy error code.
     * :param message: the detail message.
     */
    public CardDemoException(String errorCode, String message) {
        this(errorCode, message, null);
    }

    /**
     * :purpose: Canonical constructor; construct an exception with a domain error
     *  code, a detail message, and an underlying cause.
     * :param errorCode: the optional domain or legacy error code.
     * :param message: the detail message.
     * :param cause: the underlying throwable that caused this exception.
     */
    public CardDemoException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * :purpose: Expose the optional domain error code carried by this exception.
     * :returns: the domain error code, or ``null`` when not set.
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * :purpose: Name the request field this failure is about, so the API answer says
     *  WHICH field was refused and the screen can mark and cursor to that one control
     *  instead of only restating the message. It is the transport form of the legacy
     *  ``MOVE -1 TO <field>L`` the screen programs perform beside every edit failure.
     * :returns: the request-payload property name, or ``null`` when the failure names
     *  no single field (a whole-submission refusal, or a state conflict).
     */
    public String getField() {
        return field;
    }

    /**
     * :purpose: Return this failure named against a request field. The exception type,
     *  message, code and cause are preserved exactly, so attaching a field can never
     *  change the status or the line-23 text a screen already publishes.
     * :param fieldName: the request-payload property name the failure is about.
     * :returns: a copy of this exception carrying the field name.
     */
    public CardDemoException onField(String fieldName) {
        CardDemoException named = new CardDemoException(errorCode, getMessage(), getCause());
        named.field = fieldName;
        named.setStackTrace(getStackTrace());
        return named;
    }

    /**
     * :purpose: Request field this failure is about; ``null`` when it names none.
     */
    private String field;
}
