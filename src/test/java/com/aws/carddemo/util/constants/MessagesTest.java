package com.aws.carddemo.util.constants;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure JUnit 5 unit test asserting that the migrated Java constants holder
 * {@link Messages} faithfully reproduces the two COBOL message copybooks it was
 * derived from (behavioral-parity oracle for the AWS CardDemo migration).
 *
 * <p>Parity oracles: {@code legacy/cpy/CSMSG01Y.cpy} (01 CCDA-COMMON-MESSAGES),
 * {@code legacy/cpy/CSMSG02Y.cpy} (01 ABEND-DATA / CABENDD.CPY).</p>
 *
 * <p>Two parity contracts are verified:</p>
 * <ul>
 *   <li>The {@code CCDA_MSG_*} strings carry the copybook logical message text
 *       ({@code CSMSG01Y}) and are space-padded to the declared {@code PIC X(50)}
 *       width. Text assertions are padding-robust: they compare the trimmed
 *       logical text, while the fixed-width contract is checked separately via
 *       {@code String.length()}.</li>
 *   <li>The {@code ABEND_*_LENGTH} integers match the {@code PIC X(n)} field
 *       widths of the abend work area ({@code CSMSG02Y} / {@code CABENDD}).</li>
 * </ul>
 *
 * <p>This is a pure unit test: it wires up no application context, no persistence
 * layer, and no external fixtures. It exercises only {@code public static final}
 * members of {@link Messages}, so it runs in milliseconds under Maven Surefire
 * (class name ends in {@code Test}).</p>
 */
@DisplayName("Messages constants parity (CSMSG01Y / CSMSG02Y)")
class MessagesTest {

    /**
     * Verifies the thank-you message reproduces the {@code CCDA-MSG-THANK-YOU}
     * literal from {@code legacy/cpy/CSMSG01Y.cpy}. Note this is the
     * <strong>CardDemo</strong> application message, distinct from
     * {@code ScreenTitles.CCDA_THANK_YOU} which references the <strong>CCDA</strong>
     * application. The comparison trims the {@code PIC X(50)} space padding so it
     * asserts the logical text rather than the brittle raw literal.
     */
    @Test
    @DisplayName("CCDA_MSG_THANK_YOU carries the CardDemo thank-you text")
    void thankYouMessageStartsWithExpectedText() {
        assertTrue(Messages.CCDA_MSG_THANK_YOU.trim()
                .startsWith("Thank you for using CardDemo application"));
        // Full trimmed logical text, ending in three ASCII periods.
        assertEquals("Thank you for using CardDemo application...",
                Messages.CCDA_MSG_THANK_YOU.trim());
    }

    /**
     * Verifies the invalid-key message reproduces the {@code CCDA-MSG-INVALID-KEY}
     * literal from {@code legacy/cpy/CSMSG01Y.cpy}. This is the standard 3270
     * message-line text shown when an unmapped PF / AID key is pressed; the period
     * after {@code pressed} is part of the on-screen contract and is preserved.
     */
    @Test
    @DisplayName("CCDA_MSG_INVALID_KEY carries the invalid-key text")
    void invalidKeyMessageStartsWithExpectedText() {
        assertTrue(Messages.CCDA_MSG_INVALID_KEY.trim()
                .startsWith("Invalid key pressed. Please see below"));
        // Full trimmed logical text, ending in three ASCII periods.
        assertEquals("Invalid key pressed. Please see below...",
                Messages.CCDA_MSG_INVALID_KEY.trim());
    }

    /**
     * Verifies the abend work-area field-length constants match the
     * {@code PIC X(n)} widths declared in {@code legacy/cpy/CSMSG02Y.cpy}
     * (01 ABEND-DATA / CABENDD.CPY). These widths are the byte contract of the
     * abend record and are independent of any message-string padding.
     */
    @Test
    @DisplayName("ABEND_*_LENGTH constants match CSMSG02Y PIC widths (4/8/50/72)")
    void abendFieldLengthsMatchCopybookPicWidths() {
        assertEquals(4, Messages.ABEND_CODE_LENGTH);     // ABEND-CODE    PIC X(4)
        assertEquals(8, Messages.ABEND_CULPRIT_LENGTH);  // ABEND-CULPRIT PIC X(8)
        assertEquals(50, Messages.ABEND_REASON_LENGTH);  // ABEND-REASON  PIC X(50)
        assertEquals(72, Messages.ABEND_MSG_LENGTH);     // ABEND-MSG     PIC X(72)
    }

    /**
     * Verifies the shared declared width of the {@code CCDA-COMMON-MESSAGES}
     * fields ({@code PIC X(50)}) is exposed as {@code MESSAGE_LENGTH == 50}.
     */
    @Test
    @DisplayName("MESSAGE_LENGTH equals the CSMSG01Y PIC X(50) width")
    void messageLengthConstantIs50() {
        assertEquals(50, Messages.MESSAGE_LENGTH);
    }

    /**
     * Verifies both common-message strings preserve the fixed {@code PIC X(50)}
     * width: each is space-padded to exactly {@link Messages#MESSAGE_LENGTH}
     * characters, matching the byte layout of the COBOL {@code X(50)} fields.
     */
    @Test
    @DisplayName("CCDA_MSG_* strings are padded to the declared X(50) width")
    void messageStringsArePaddedToDeclaredWidth() {
        assertEquals(Messages.MESSAGE_LENGTH, Messages.CCDA_MSG_THANK_YOU.length());
        assertEquals(Messages.MESSAGE_LENGTH, Messages.CCDA_MSG_INVALID_KEY.length());
        // Explicit literal width for readability; MESSAGE_LENGTH is 50 (PIC X(50)).
        assertEquals(50, Messages.CCDA_MSG_THANK_YOU.length());
        assertEquals(50, Messages.CCDA_MSG_INVALID_KEY.length());
    }
}
