package com.aws.carddemo.util.constants;

/**
 * Common message strings and abend-work-area field widths for the AWS CardDemo
 * application, migrated from COBOL to Java 25 / Spring Boot.
 *
 * <p>This constants holder consolidates TWO COBOL message copybooks into a single
 * Java class; the consolidation decision is recorded in {@code docs/decision-log.md}.</p>
 *
 * <ul>
 *   <li>Origin: {@code legacy/cpy/CSMSG01Y.cpy} (01 CCDA-COMMON-MESSAGES) &mdash; the
 *       user-facing common message strings shown on the 3270 / BMS message line.</li>
 *   <li>Origin: {@code legacy/cpy/CSMSG02Y.cpy} (01 ABEND-DATA / CABENDD.CPY) &mdash;
 *       the abend-routine work-area field-width layout.</li>
 * </ul>
 *
 * <p>The {@code CCDA_MSG_*} strings are byte-exact {@code PIC X(50)} values: the COBOL
 * literals are left-justified and space-padded to exactly {@link #MESSAGE_LENGTH} (50)
 * characters, preserving the on-screen byte layout. The {@code ABEND_*_LENGTH} constants
 * capture the fixed field widths of the abend record (every field is {@code VALUE SPACES},
 * so only the widths carry meaning); the mutable abend record itself, if ever needed,
 * belongs to a DTO rather than this constants-only holder.</p>
 *
 * <p>Intentionally dependency-free (no imports, no Spring) so it is usable from every
 * layer of the application.</p>
 */
public final class Messages {

    /**
     * Shared declared width of the {@code CCDA-COMMON-MESSAGES} fields ({@code PIC X(50)}).
     * Each {@code CCDA_MSG_*} constant is space-padded to exactly this many characters.
     */
    public static final int MESSAGE_LENGTH = 50;

    /**
     * CCDA-MSG-THANK-YOU, PIC X(50). Byte-exact runtime value: the 43-character message
     * text, left-justified and space-padded to {@link #MESSAGE_LENGTH} (50) characters.
     */
    public static final String CCDA_MSG_THANK_YOU = "Thank you for using CardDemo application...       ";

    /**
     * CCDA-MSG-INVALID-KEY, PIC X(50). Byte-exact runtime value: the 40-character message
     * text, left-justified and space-padded to {@link #MESSAGE_LENGTH} (50) characters.
     * Shown by the online programs when an unmapped PF / AID key is pressed.
     */
    public static final String CCDA_MSG_INVALID_KEY = "Invalid key pressed. Please see below...          ";

    /** ABEND-CODE, PIC X(4): fixed width of the abend code field. */
    public static final int ABEND_CODE_LENGTH = 4;

    /** ABEND-CULPRIT, PIC X(8): fixed width of the abend culprit (program) field. */
    public static final int ABEND_CULPRIT_LENGTH = 8;

    /** ABEND-REASON, PIC X(50): fixed width of the abend reason field. */
    public static final int ABEND_REASON_LENGTH = 50;

    /** ABEND-MSG, PIC X(72): fixed width of the abend message field. */
    public static final int ABEND_MSG_LENGTH = 72;

    /**
     * Prevents instantiation of this constants-only holder.
     */
    private Messages() {
        throw new AssertionError("No instances");
    }
}
