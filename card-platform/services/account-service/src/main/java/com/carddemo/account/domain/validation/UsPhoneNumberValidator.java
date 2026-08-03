package com.carddemo.account.domain.validation;

import com.carddemo.cobol.reference.UsPhoneAreaCodes;

/**
 * Edits a three part North American telephone number: area code, prefix, and line number.
 *
 * <p>Implements paragraph {@code 1260-EDIT-US-PHONE-NUM} at app/cbl/COACTUPC.cbl:L2225 together
 * with the three paragraphs it falls through to: {@code EDIT-AREA-CODE} at
 * app/cbl/COACTUPC.cbl:L2246, {@code EDIT-US-PHONE-PREFIX} at app/cbl/COACTUPC.cbl:L2316, and
 * {@code EDIT-US-PHONE-LINENUM} at app/cbl/COACTUPC.cbl:L2370.</p>
 *
 * <p>Each part carries three checks in source order: a blank test, the {@code IS NUMERIC} class
 * test on the fixed width field, and a zero test on the numeric redefine. A failing check skips
 * the rest of that part and moves to the next part. Every part is therefore edited, and every part
 * carries its own verdict.</p>
 *
 * <p>The area code carries a fourth check against {@code VALID-GENERAL-PURP-CODE}, the 410 code
 * band declared at app/cpy/CSLKPCDY.cpy:L521 and tested at app/cbl/COACTUPC.cbl:L2298.</p>
 *
 * <p>{@code WS-RETURN-MSG} at app/cbl/COACTUPC.cbl:L479 holds one message per validation pass, so
 * this class returns the first message its checks produce. app/cbl/COACTUPC.cbl:L2233 records that
 * a phone number is optional.</p>
 *
 * <p>Column and key mappings for the customer phone fields: card-platform/docs/data-model.md.
 * Decisions behind this class: card-platform/docs/decision-log.md.</p>
 */
public final class UsPhoneNumberValidator {

    /** Stored width of {@code WS-EDIT-US-PHONE-NUMA PIC X(3)} at app/cbl/COACTUPC.cbl:L87. */
    private static final int AREA_CODE_WIDTH = 3;

    /** Stored width of {@code WS-EDIT-US-PHONE-NUMB PIC X(3)} at app/cbl/COACTUPC.cbl:L92. */
    private static final int PREFIX_WIDTH = 3;

    /** Stored width of {@code WS-EDIT-US-PHONE-NUMC PIC X(4)} at app/cbl/COACTUPC.cbl:L97. */
    private static final int LINE_NUMBER_WIDTH = 4;

    /** Literal at app/cbl/COACTUPC.cbl:L2254. */
    private static final String AREA_CODE_BLANK = ": Area code must be supplied.";

    /** Literal at app/cbl/COACTUPC.cbl:L2272. */
    private static final String AREA_CODE_NOT_NUMERIC = ": Area code must be A 3 digit number.";

    /** Literal at app/cbl/COACTUPC.cbl:L2286. */
    private static final String AREA_CODE_ZERO = ": Area code cannot be zero";

    /** Literal at app/cbl/COACTUPC.cbl:L2306. */
    private static final String AREA_CODE_NOT_IN_BAND =
            ": Not valid North America general purpose area code";

    /** Literal at app/cbl/COACTUPC.cbl:L2325. */
    private static final String PREFIX_BLANK = ": Prefix code must be supplied.";

    /** Literal at app/cbl/COACTUPC.cbl:L2343. */
    private static final String PREFIX_NOT_NUMERIC = ": Prefix code must be A 3 digit number.";

    /** Literal at app/cbl/COACTUPC.cbl:L2357. */
    private static final String PREFIX_ZERO = ": Prefix code cannot be zero";

    /** Literal at app/cbl/COACTUPC.cbl:L2378. */
    private static final String LINE_NUMBER_BLANK = ": Line number code must be supplied.";

    /** Literal at app/cbl/COACTUPC.cbl:L2396. */
    private static final String LINE_NUMBER_NOT_NUMERIC =
            ": Line number code must be A 4 digit number.";

    /** Literal at app/cbl/COACTUPC.cbl:L2410. */
    private static final String LINE_NUMBER_ZERO = ": Line number code cannot be zero";

    /** This class holds no state and is never instantiated. */
    private UsPhoneNumberValidator() {
    }

    /**
     * Edits one telephone number supplied as three parts.
     *
     * <p>app/cbl/COACTUPC.cbl:L2232 marks all three parts failed on entry. A part turns valid only
     * when its own checks pass, at app/cbl/COACTUPC.cbl:L2314, app/cbl/COACTUPC.cbl:L2367, and
     * app/cbl/COACTUPC.cbl:L2421.</p>
     *
     * @param fieldLabel the caller's name for the field, from {@code WS-EDIT-VARIABLE-NAME} at
     *                   app/cbl/COACTUPC.cbl:L53. Surrounding spaces are trimmed, matching
     *                   {@code FUNCTION TRIM} at every message site
     * @param areaCode   the three digit area code, {@code ACUP-NEW-CUST-PHONE-NUM-1A} at
     *                   app/cbl/COACTUPC.cbl:L814
     * @param prefix     the three digit prefix, {@code ACUP-NEW-CUST-PHONE-NUM-1B} at
     *                   app/cbl/COACTUPC.cbl:L816
     * @param lineNumber the four digit line number, {@code ACUP-NEW-CUST-PHONE-NUM-1C} at
     *                   app/cbl/COACTUPC.cbl:L818
     * @return a passing verdict when every part passes, otherwise a failing verdict carrying the
     *         first message the checks produced. No argument is modified
     */
    public static EditResult validate(
            String fieldLabel, String areaCode, String prefix, String lineNumber) {

        String label = trimSpaces(fieldLabel);

        if (isNotSupplied(areaCode, prefix, lineNumber)) {
            return EditResult.ok();
        }

        // app/cbl/COACTUPC.cbl:L2259, L2277, L2291 and L2311 jump to EDIT-US-PHONE-PREFIX, and
        // L2330, L2348 and L2362 jump to EDIT-US-PHONE-LINENUM. Every part is edited on every pass.
        EditResult areaCodeVerdict = editAreaCode(label, areaCode);
        EditResult prefixVerdict = editPrefix(label, prefix);
        EditResult lineNumberVerdict = editLineNumber(label, lineNumber);

        // app/cbl/COACTUPC.cbl:L480 makes the message slot available only while it holds spaces, so
        // the first failing part supplies the message and later failing parts supply none.
        if (!areaCodeVerdict.valid()) {
            return areaCodeVerdict;
        }
        if (!prefixVerdict.valid()) {
            return prefixVerdict;
        }
        if (!lineNumberVerdict.valid()) {
            return lineNumberVerdict;
        }
        return EditResult.ok();
    }

    /**
     * Reports the not supplied case that app/cbl/COACTUPC.cbl:L2234 to L2239 tests, ahead of every
     * other check.
     *
     * <p>Three clauses join with {@code AND}. The first tests the area code at
     * app/cbl/COACTUPC.cbl:L2234 to L2235 and the second tests the prefix at
     * app/cbl/COACTUPC.cbl:L2236 to L2237. The third clause tests the area code for spaces a
     * second time at app/cbl/COACTUPC.cbl:L2238, then the line number for low values at
     * app/cbl/COACTUPC.cbl:L2239. The line number is never tested for spaces. A blank area code, a
     * blank prefix, and a populated line number therefore reach app/cbl/COACTUPC.cbl:L2240, which
     * marks all three parts valid.</p>
     *
     * <p>This condition is registered in card-platform/docs/business-rule-flags.md and a correction
     * is proposed in card-platform/docs/suggested-next-tasks.md.</p>
     *
     * @param areaCode   the area code as supplied
     * @param prefix     the prefix as supplied
     * @param lineNumber the line number as supplied
     * @return true when the three clauses all hold
     */
    private static boolean isNotSupplied(String areaCode, String prefix, String lineNumber) {
        return (isSpaces(areaCode) || isLowValues(areaCode))
                && (isSpaces(prefix) || isLowValues(prefix))
                && (isSpaces(areaCode) || isLowValues(lineNumber));
    }

    /**
     * Runs the four area code checks of {@code EDIT-AREA-CODE} at app/cbl/COACTUPC.cbl:L2246 to
     * L2315, in source order.
     *
     * @param label    the trimmed field label
     * @param areaCode the area code as supplied
     * @return a passing verdict when all four checks pass, otherwise the failing check's message
     */
    private static EditResult editAreaCode(String label, String areaCode) {
        String stored = storedAs(areaCode, AREA_CODE_WIDTH);

        // app/cbl/COACTUPC.cbl:L2247 to L2248.
        if (isSpaces(areaCode) || isLowValues(areaCode)) {
            return EditResult.failure(label + AREA_CODE_BLANK);
        }
        // app/cbl/COACTUPC.cbl:L2264.
        if (!isNumeric(stored)) {
            return EditResult.failure(label + AREA_CODE_NOT_NUMERIC);
        }
        // app/cbl/COACTUPC.cbl:L2280, on the PIC 9(3) redefine at app/cbl/COACTUPC.cbl:L88 to L89.
        if (isZero(stored)) {
            return EditResult.failure(label + AREA_CODE_ZERO);
        }
        // app/cbl/COACTUPC.cbl:L2296 trims the value, then app/cbl/COACTUPC.cbl:L2298 tests
        // VALID-GENERAL-PURP-CODE, the 410 code band at app/cpy/CSLKPCDY.cpy:L521 to L930.
        if (!UsPhoneAreaCodes.isValidGeneralPurposeCode(trimSpaces(stored))) {
            return EditResult.failure(label + AREA_CODE_NOT_IN_BAND);
        }
        // app/cbl/COACTUPC.cbl:L2314.
        return EditResult.ok();
    }

    /**
     * Runs the three prefix checks of {@code EDIT-US-PHONE-PREFIX} at app/cbl/COACTUPC.cbl:L2316
     * to L2368, in source order.
     *
     * @param label  the trimmed field label
     * @param prefix the prefix as supplied
     * @return a passing verdict when all three checks pass, otherwise the failing check's message
     */
    private static EditResult editPrefix(String label, String prefix) {
        String stored = storedAs(prefix, PREFIX_WIDTH);

        // app/cbl/COACTUPC.cbl:L2318 to L2319.
        if (isSpaces(prefix) || isLowValues(prefix)) {
            return EditResult.failure(label + PREFIX_BLANK);
        }
        // app/cbl/COACTUPC.cbl:L2335.
        if (!isNumeric(stored)) {
            return EditResult.failure(label + PREFIX_NOT_NUMERIC);
        }
        // app/cbl/COACTUPC.cbl:L2351, on the PIC 9(3) redefine at app/cbl/COACTUPC.cbl:L93 to L94.
        if (isZero(stored)) {
            return EditResult.failure(label + PREFIX_ZERO);
        }
        // app/cbl/COACTUPC.cbl:L2367.
        return EditResult.ok();
    }

    /**
     * Runs the three line number checks of {@code EDIT-US-PHONE-LINENUM} at
     * app/cbl/COACTUPC.cbl:L2370 to L2422, in source order.
     *
     * @param label      the trimmed field label
     * @param lineNumber the line number as supplied
     * @return a passing verdict when all three checks pass, otherwise the failing check's message
     */
    private static EditResult editLineNumber(String label, String lineNumber) {
        String stored = storedAs(lineNumber, LINE_NUMBER_WIDTH);

        // app/cbl/COACTUPC.cbl:L2371 to L2372.
        if (isSpaces(lineNumber) || isLowValues(lineNumber)) {
            return EditResult.failure(label + LINE_NUMBER_BLANK);
        }
        // app/cbl/COACTUPC.cbl:L2388.
        if (!isNumeric(stored)) {
            return EditResult.failure(label + LINE_NUMBER_NOT_NUMERIC);
        }
        // app/cbl/COACTUPC.cbl:L2404, on the PIC 9(4) redefine at app/cbl/COACTUPC.cbl:L98 to L99.
        if (isZero(stored)) {
            return EditResult.failure(label + LINE_NUMBER_ZERO);
        }
        // app/cbl/COACTUPC.cbl:L2421.
        return EditResult.ok();
    }

    /**
     * Returns the value as a {@code PIC X(n)} field of the given width holds it. A shorter value
     * gains trailing spaces and a longer value loses its tail, which is what a {@code MOVE} into
     * the field at app/cbl/COACTUPC.cbl:L87, L92 or L97 stores.
     *
     * <p>A short value therefore fails the {@code IS NUMERIC} class test, since a trailing space is
     * not a digit.</p>
     *
     * @param value the value as supplied, possibly null
     * @param width the declared width of the field
     * @return exactly {@code width} characters
     */
    private static String storedAs(String value, int width) {
        String supplied = value == null ? "" : value;
        if (supplied.length() >= width) {
            return supplied.substring(0, width);
        }
        return supplied + " ".repeat(width - supplied.length());
    }

    /**
     * Reports the {@code EQUAL SPACES} test. A field holding at least one character, all of them
     * spaces, matches.
     *
     * @param value the value as supplied, possibly null
     * @return true when the value holds only spaces
     */
    private static boolean isSpaces(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int position = 0; position < value.length(); position++) {
            if (value.charAt(position) != ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports the {@code EQUAL LOW-VALUES} test. A null value and an empty value both match, since
     * neither carries a character the field could hold.
     *
     * @param value the value as supplied, possibly null
     * @return true when the value carries no character
     */
    private static boolean isLowValues(String value) {
        return value == null || value.isEmpty();
    }

    /**
     * Reports the {@code IS NUMERIC} class test over a stored field. Every character must be a
     * digit.
     *
     * @param stored the field contents, already at its declared width
     * @return true when every character is a digit
     */
    private static boolean isNumeric(String stored) {
        for (int position = 0; position < stored.length(); position++) {
            char character = stored.charAt(position);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether a numeric redefine holds zero. The digits are compared character by
     * character, so the check runs on the stored field with no conversion.
     *
     * @param stored the field contents, already passed by {@link #isNumeric(String)}
     * @return true when every digit is zero
     */
    private static boolean isZero(String stored) {
        for (int position = 0; position < stored.length(); position++) {
            if (stored.charAt(position) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes leading and trailing spaces, matching {@code FUNCTION TRIM}. Only the space character
     * is removed, and the value keeps its full length otherwise.
     *
     * @param value the value as supplied, possibly null
     * @return the value without surrounding spaces, or an empty string when the value is null
     */
    private static String trimSpaces(String value) {
        if (value == null) {
            return "";
        }
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(start, end);
    }
}
