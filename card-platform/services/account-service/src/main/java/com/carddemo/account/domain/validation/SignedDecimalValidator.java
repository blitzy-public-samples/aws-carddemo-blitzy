package com.carddemo.account.domain.validation;

import com.carddemo.cobol.NumvalParser;

/**
 * Edits a signed decimal amount field.
 *
 * <p>Translates paragraph {@code 1250-EDIT-SIGNED-9V2} at
 * {@code app/cbl/COACTUPC.cbl:L2180}, whose exit paragraph sits on line 2221. The edit
 * fails when the field arrives with no content. It fails again when
 * {@code FUNCTION TEST-NUMVAL-C} at {@code app/cbl/COACTUPC.cbl:L2201} reports the
 * content invalid. A field that clears both tests passes.</p>
 *
 * <p>{@code FUNCTION TEST-NUMVAL-C} accepts a currency sign and grouping commas, so
 * {@code $1,234.56} clears the second test.
 * {@link NumvalParser#isValidNumvalCurrency(String)} carries that grammar and reports
 * the same verdict. The paragraph checks no scale and no precision. {@code 9V2} names
 * the {@code S9(nn)V99} format the account record stores.</p>
 *
 * <p>The value field is {@code WS-EDIT-SIGNED-NUMBER-9V2-X PIC X(15)} at
 * {@code app/cbl/COACTUPC.cbl:L55}. Lines 2184, 2185 and 2201 read it whole, and no line
 * of the paragraph slices it. The label field is {@code WS-EDIT-VARIABLE-NAME PIC X(25)}
 * at {@code app/cbl/COACTUPC.cbl:L53}, so the caller's assignment sets the label
 * width.</p>
 *
 * <p>Five call sites reach the edit, on lines 1486, 1499, 1511, 1518 and 1525. They
 * carry the credit limit, the cash credit limit, the current balance, the current cycle
 * credit and the current cycle debit.</p>
 *
 * <p>The guard {@code IF WS-RETURN-MSG-OFF} on lines 2188 and 2206 keeps the first
 * message of a validation pass. {@code EditResult} carries one message, and the caller
 * that collects results applies the guard.</p>
 *
 * <p>Rationale for every choice in this class lives in
 * {@code card-platform/docs/decision-log.md}.</p>
 */
public final class SignedDecimalValidator {

    /**
     * Message literal at {@code app/cbl/COACTUPC.cbl:L2191}, eighteen characters wide,
     * carrying a leading space and a trailing period.
     */
    private static final String NOT_SUPPLIED_MESSAGE = " must be supplied.";

    /**
     * Message literal at {@code app/cbl/COACTUPC.cbl:L2209}, thirteen characters wide,
     * carrying a leading space and no trailing period.
     */
    private static final String NOT_VALID_MESSAGE = " is not valid";

    /** The character the {@code EQUAL SPACES} arm on line 2185 tests for. */
    private static final char SPACE = ' ';

    /** This class holds static members only. */
    private SignedDecimalValidator() {
    }

    /**
     * Applies the edit to one field.
     *
     * <p>Line 2181 opens the paragraph with a failing verdict, and line 2218 sets the
     * passing verdict once both tests clear. Each
     * {@code GO TO 1250-EDIT-SIGNED-9V2-EXIT}, on lines 2196 and 2213, returns here with
     * a message.</p>
     *
     * @param fieldLabel the field name the message opens with, trimmed as
     *                   {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} does on lines 2190
     *                   and 2208
     * @param value      the submitted text, read and never changed
     * @return a passing verdict, or a failing verdict carrying one message
     */
    public static EditResult validate(String fieldLabel, String value) {
        if (isNotSupplied(value)) {
            return EditResult.failure(trimmedLabel(fieldLabel) + NOT_SUPPLIED_MESSAGE);
        }

        if (!NumvalParser.isValidNumvalCurrency(value)) {
            return EditResult.failure(trimmedLabel(fieldLabel) + NOT_VALID_MESSAGE);
        }

        return EditResult.ok();
    }

    /**
     * Applies the two-arm not-supplied test at {@code app/cbl/COACTUPC.cbl:L2184-L2185}.
     *
     * <p>A null value and an empty value hold the place of {@code EQUAL LOW-VALUES} on
     * line 2184. A value of spaces answers {@code EQUAL SPACES} on line 2185. A value
     * carrying any other character reaches the gate on line 2201, including a value of
     * one tab.</p>
     *
     * @param value the submitted text
     * @return true when the field arrived with no content
     */
    private static boolean isNotSupplied(String value) {
        return value == null || value.isEmpty() || isAllSpaces(value);
    }

    /**
     * Answers the {@code EQUAL SPACES} arm on line 2185.
     *
     * @param text the field content to test
     * @return true when the argument holds no character other than a space
     */
    private static boolean isAllSpaces(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) != SPACE) {
                return false;
            }
        }

        return true;
    }

    /**
     * Trims the field label, as {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} does on
     * lines 2190 and 2208. This method returns the label at the width it arrives, and
     * shortens no label.
     *
     * @param fieldLabel the field name, null tolerated
     * @return the label with leading and trailing white space removed
     */
    private static String trimmedLabel(String fieldLabel) {
        return fieldLabel == null ? "" : fieldLabel.trim();
    }
}
