package com.carddemo.common.constant;

/**
 * Verbatim CardDemo common-message constants (from ``CSMSG01Y``, group
 * ``CCDA-COMMON-MESSAGES``) and abend-diagnostic field widths (from
 * ``CSMSG02Y``, group ``ABEND-DATA``).
 *
 * :purpose: Provide a framework-light, non-instantiable holder for the frozen
 *           display messages and the fixed-width abend-diagnostic area shared
 *           across the CardDemo services.
 * :output:  ``public static final`` message strings preserved byte-identically
 *           to their COBOL source literals, plus abend field-width and
 *           blank-value constants.
 */
public final class Messages {

    /**
     * Non-instantiable constant holder.
     *
     * :purpose: Prevent instantiation of this utility class.
     */
    private Messages() {
    }

    // ---------------------------------------------------------------------
    // Common messages (source: CSMSG01Y, group CCDA-COMMON-MESSAGES)
    // ---------------------------------------------------------------------

    /**
     * Sign-off / thank-you message shown on the 3270-equivalent screens.
     *
     * :output: Byte-identical copy of ``CCDA-MSG-THANK-YOU``. The source field
     *          is declared ``PIC X(50)``; the source literal is 49 characters
     *          (trailing spaces preserved exactly, not padded to 50).
     */
    public static final String CCDA_MSG_THANK_YOU =
            "Thank you for using CardDemo application...      ";

    /**
     * Invalid-key banner shown when an unsupported key is pressed.
     *
     * :output: Byte-identical copy of ``CCDA-MSG-INVALID-KEY``. The source
     *          field is declared ``PIC X(50)``; the source literal is 49
     *          characters (trailing spaces preserved exactly, not padded to
     *          50).
     */
    public static final String CCDA_MSG_INVALID_KEY =
            "Invalid key pressed. Please see below...         ";

    // ---------------------------------------------------------------------
    // Abend-diagnostic area (source: CSMSG02Y, group ABEND-DATA)
    //
    // :note: The ABEND-DATA fields are declared VALUE SPACES; only their fixed
    //        field widths carry a contract. The width constants and the
    //        blank-initialized defaults below mirror that layout so downstream
    //        diagnostic code can assemble a fixed-width abend record.
    // ---------------------------------------------------------------------

    /**
     * Width of ``ABEND-CODE`` (``PIC X(4)``).
     */
    public static final int ABEND_CODE_LENGTH = 4;

    /**
     * Width of ``ABEND-CULPRIT`` (``PIC X(8)``).
     */
    public static final int ABEND_CULPRIT_LENGTH = 8;

    /**
     * Width of ``ABEND-REASON`` (``PIC X(50)``).
     */
    public static final int ABEND_REASON_LENGTH = 50;

    /**
     * Width of ``ABEND-MSG`` (``PIC X(72)``).
     */
    public static final int ABEND_MSG_LENGTH = 72;

    /**
     * Blank ``ABEND-CODE`` default (``VALUE SPACES``).
     *
     * :output: A string of ``ABEND_CODE_LENGTH`` spaces.
     */
    public static final String ABEND_CODE = " ".repeat(ABEND_CODE_LENGTH);

    /**
     * Blank ``ABEND-CULPRIT`` default (``VALUE SPACES``).
     *
     * :output: A string of ``ABEND_CULPRIT_LENGTH`` spaces.
     */
    public static final String ABEND_CULPRIT = " ".repeat(ABEND_CULPRIT_LENGTH);

    /**
     * Blank ``ABEND-REASON`` default (``VALUE SPACES``).
     *
     * :output: A string of ``ABEND_REASON_LENGTH`` spaces.
     */
    public static final String ABEND_REASON = " ".repeat(ABEND_REASON_LENGTH);

    /**
     * Blank ``ABEND-MSG`` default (``VALUE SPACES``).
     *
     * :output: A string of ``ABEND_MSG_LENGTH`` spaces.
     */
    public static final String ABEND_MSG = " ".repeat(ABEND_MSG_LENGTH);
}
