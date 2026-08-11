package com.carddemo.common.constant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Executable specification of the CardDemo common-message and abend-diagnostic constants.
 *
 * :purpose: Lock the byte-identical values exposed by
 *     ``com.carddemo.common.constant.Messages`` for the verbatim common messages
 *     (COBOL ``CSMSG01Y`` group ``CCDA-COMMON-MESSAGES``) and the fixed-width abend
 *     diagnostic area (COBOL ``CSMSG02Y`` group ``ABEND-DATA``, all ``VALUE SPACES``).
 * :output: JUnit 5 / AssertJ assertions only; the class holds no state and touches no
 *     database, Spring context, or other external resource (pure JDK logic).
 */
final class MessagesTest {

    // The visible message text carried by each ``PIC X(50) VALUE`` literal, together with
    // the exact trailing-space run recorded between the single quotes in CSMSG01Y.cpy. Each
    // source literal is 49 characters (one shorter than the 50-byte field); COBOL left-
    // justifies the literal and right-pads it with one space to the declared field width,
    // so the effective field value is 50 characters.
    private static final String THANK_YOU_TEXT = "Thank you for using CardDemo application...";
    private static final String INVALID_KEY_TEXT = "Invalid key pressed. Please see below...";

    /** 49-char source literal of ``CCDA-MSG-THANK-YOU`` (CSMSG01Y.cpy line 19): 43-char text + 6 spaces. */
    private static final String THANK_YOU_LITERAL_49 = THANK_YOU_TEXT + " ".repeat(6);

    /** 49-char source literal of ``CCDA-MSG-INVALID-KEY`` (CSMSG01Y.cpy line 21): 40-char text + 9 spaces. */
    private static final String INVALID_KEY_LITERAL_49 = INVALID_KEY_TEXT + " ".repeat(9);

    /** Effective 50-byte ``PIC X(50)`` field value: the 49-char literal right-padded with one space. */
    private static final String THANK_YOU_FIELD_50 = THANK_YOU_LITERAL_49 + " ";
    private static final String INVALID_KEY_FIELD_50 = INVALID_KEY_LITERAL_49 + " ";

    // ---------------------------------------------------------------------
    // Common messages (source: CSMSG01Y, group CCDA-COMMON-MESSAGES)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("COMMON_MESSAGE_LENGTH is the 50-byte PIC X(50) common-message field width")
    void commonMessageWidthConstantIsFifty() {
        assertThat(Messages.COMMON_MESSAGE_LENGTH).isEqualTo(50);
    }

    @Test
    @DisplayName("CCDA_MSG_THANK_YOU is the 50-byte field value (49-char literal + one pad space)")
    void thankYouMessageMatchesCobolFieldValue() {
        assertThat(Messages.CCDA_MSG_THANK_YOU)
                .hasSize(50)
                .hasSize(Messages.COMMON_MESSAGE_LENGTH)
                .isEqualTo(THANK_YOU_FIELD_50)
                .startsWith(THANK_YOU_LITERAL_49)
                .startsWith(THANK_YOU_TEXT)
                .endsWith(" ");
    }

    @Test
    @DisplayName("CCDA_MSG_INVALID_KEY is the 50-byte field value (49-char literal + one pad space)")
    void invalidKeyMessageMatchesCobolFieldValue() {
        assertThat(Messages.CCDA_MSG_INVALID_KEY)
                .hasSize(50)
                .hasSize(Messages.COMMON_MESSAGE_LENGTH)
                .isEqualTo(INVALID_KEY_FIELD_50)
                .startsWith(INVALID_KEY_LITERAL_49)
                .startsWith(INVALID_KEY_TEXT)
                .endsWith(" ");
    }

    @Test
    @DisplayName("The two common messages are distinct verbatim banners")
    void commonMessagesAreDistinct() {
        assertThat(Messages.CCDA_MSG_THANK_YOU).isNotEqualTo(Messages.CCDA_MSG_INVALID_KEY);
    }

    // ---------------------------------------------------------------------
    // Abend-diagnostic area (source: CSMSG02Y, group ABEND-DATA)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("ABEND-DATA width constants equal the CSMSG02Y PIC clauses (4 / 8 / 50 / 72)")
    void abendWidthConstantsMatchCopybook() {
        assertThat(Messages.ABEND_CODE_LENGTH).isEqualTo(4);
        assertThat(Messages.ABEND_CULPRIT_LENGTH).isEqualTo(8);
        assertThat(Messages.ABEND_REASON_LENGTH).isEqualTo(50);
        assertThat(Messages.ABEND_MSG_LENGTH).isEqualTo(72);
    }

    @Test
    @DisplayName("ABEND-DATA defaults are blank strings of their declared width (VALUE SPACES)")
    void abendDefaultsAreBlankStringsOfDeclaredWidth() {
        assertThat(Messages.ABEND_CODE)
                .hasSize(4)
                .hasSize(Messages.ABEND_CODE_LENGTH)
                .isEqualTo(" ".repeat(4))
                .containsOnlyWhitespaces();
        assertThat(Messages.ABEND_CULPRIT)
                .hasSize(8)
                .hasSize(Messages.ABEND_CULPRIT_LENGTH)
                .isEqualTo(" ".repeat(8))
                .containsOnlyWhitespaces();
        assertThat(Messages.ABEND_REASON)
                .hasSize(50)
                .hasSize(Messages.ABEND_REASON_LENGTH)
                .isEqualTo(" ".repeat(50))
                .containsOnlyWhitespaces();
        assertThat(Messages.ABEND_MSG)
                .hasSize(72)
                .hasSize(Messages.ABEND_MSG_LENGTH)
                .isEqualTo(" ".repeat(72))
                .containsOnlyWhitespaces();
    }

    // ---------------------------------------------------------------------
    // Fixed-width serializer that produces the padded common-message values
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("padToWidth left-justifies and space-pads (or truncates) to the fixed field width")
    void padToWidthMirrorsCobolFixedWidthMove() {
        // Shorter than the width -> right-padded with spaces to exactly the width.
        assertThat(Messages.padToWidth("AB", 5)).isEqualTo("AB   ").hasSize(5);
        // Exactly the width -> returned unchanged.
        assertThat(Messages.padToWidth("ABCDE", 5)).isEqualTo("ABCDE").hasSize(5);
        // Longer than the width -> truncated on the right to the width.
        assertThat(Messages.padToWidth("ABCDEFG", 5)).isEqualTo("ABCDE").hasSize(5);
        // Null value -> a run of ``width`` spaces.
        assertThat(Messages.padToWidth(null, 3)).isEqualTo("   ").hasSize(3);
        // Padding the 49-char thank-you literal to the field width reproduces the exposed constant.
        assertThat(Messages.padToWidth(THANK_YOU_LITERAL_49, Messages.COMMON_MESSAGE_LENGTH))
                .isEqualTo(Messages.CCDA_MSG_THANK_YOU);
    }
}
