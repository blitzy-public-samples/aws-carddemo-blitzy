package com.carddemo.account.domain.validation;

/**
 * Edits a United States Social Security Number supplied as three separate parts.
 *
 * <p>Realises paragraph {@code 1265-EDIT-US-SSN} at app/cbl/COACTUPC.cbl:L2431, whose exit
 * paragraph sits at app/cbl/COACTUPC.cbl:L2489. Each part reaches
 * {@link NumericRequiredValidator} under its own label and stored width, at
 * app/cbl/COACTUPC.cbl:L2442, app/cbl/COACTUPC.cbl:L2472, and app/cbl/COACTUPC.cbl:L2484. Part one
 * carries one further test, the condition {@code INVALID-SSN-PART1} at
 * app/cbl/COACTUPC.cbl:L121-L123.</p>
 *
 * <p>The paragraph opens three {@code IF} statements, at app/cbl/COACTUPC.cbl:L2448,
 * app/cbl/COACTUPC.cbl:L2450 and app/cbl/COACTUPC.cbl:L2454, and closes two, at
 * app/cbl/COACTUPC.cbl:L2463 and app/cbl/COACTUPC.cbl:L2464. The period at
 * app/cbl/COACTUPC.cbl:L2488 closes the first. Part two and part three sit inside that gate, so
 * they are edited only once part one has cleared its numeric edit.</p>
 *
 * <p>Part two and part three each carry the numeric edit alone. The body of the paragraph holds no
 * separate range test for either one. A part one of zeros fails the not-zero check of that same
 * numeric edit, which closes the gate ahead of the excluded-value test. The verdict carries at most
 * one message, and no message repeats a supplied digit.</p>
 */
public final class UsSocialSecurityNumberValidator {

    /** Stored width of {@code WS-EDIT-US-SSN-PART1 PIC X(3)} at app/cbl/COACTUPC.cbl:L118. */
    private static final int PART1_WIDTH = 3;

    /** Stored width of {@code WS-EDIT-US-SSN-PART2 PIC X(2)} at app/cbl/COACTUPC.cbl:L124. */
    private static final int PART2_WIDTH = 2;

    /** Stored width of {@code WS-EDIT-US-SSN-PART3 PIC X(4)} at app/cbl/COACTUPC.cbl:L127. */
    private static final int PART3_WIDTH = 4;

    /** Label literal at app/cbl/COACTUPC.cbl:L2439, which opens every part one message. */
    private static final String PART1_LABEL = "SSN: First 3 chars";

    /** Label literal at app/cbl/COACTUPC.cbl:L2469, carrying an ampersand. */
    private static final String PART2_LABEL = "SSN 4th & 5th chars";

    /** Label literal at app/cbl/COACTUPC.cbl:L2481. */
    private static final String PART3_LABEL = "SSN Last 4 chars";

    /**
     * Literal at app/cbl/COACTUPC.cbl:L2457, forty-eight characters wide, carrying its leading
     * colon and space and no trailing period. app/cbl/COACTUPC.cbl:L2456 places the trimmed part
     * one label ahead of it, so the emitted text holds two colons.
     */
    private static final String EXCLUDED_PART1_MESSAGE =
            ": should not be 000, 666, or between 900 and 999";

    /**
     * First excluded value of {@code INVALID-SSN-PART1} at app/cbl/COACTUPC.cbl:L121. The
     * condition names it {@code 0}, and the redefine reads three digits, so it selects
     * {@code 000}. The not-zero check of the numeric edit at app/cbl/COACTUPC.cbl:L2156 rejects
     * {@code 000} first and closes the gate, leaving this arm unreachable from the one call site at
     * app/cbl/COACTUPC.cbl:L1530.
     */
    private static final int EXCLUDED_ZERO = 0;

    /** Second excluded value of {@code INVALID-SSN-PART1} at app/cbl/COACTUPC.cbl:L122. */
    private static final int EXCLUDED_SIX_HUNDRED_SIXTY_SIX = 666;

    /** Lower bound of the excluded band {@code 900 THRU 999} at app/cbl/COACTUPC.cbl:L123. */
    private static final int EXCLUDED_BAND_LOWEST = 900;

    /** Upper bound of the excluded band {@code 900 THRU 999} at app/cbl/COACTUPC.cbl:L123. */
    private static final int EXCLUDED_BAND_HIGHEST = 999;

    /** Lowest character the {@code PIC 9(3)} redefine at app/cbl/COACTUPC.cbl:L120 reads. */
    private static final char ZERO_DIGIT = '0';

    /** Highest character the {@code PIC 9(3)} redefine at app/cbl/COACTUPC.cbl:L120 reads. */
    private static final char NINE_DIGIT = '9';

    /** Place value the redefine applies to each digit position. */
    private static final int DECIMAL_BASE = 10;

    /** The character a COBOL {@code MOVE} pads a short alphanumeric item with. */
    private static final char SPACE = ' ';

    /** This class holds no state and is never instantiated. */
    private UsSocialSecurityNumberValidator() {
    }

    /**
     * Applies the edit to one Social Security Number.
     *
     * <p>Part one is edited first. A failing part one closes the gate at
     * app/cbl/COACTUPC.cbl:L2448, which leaves part two and part three unedited. Once the gate
     * opens, the excluded-value test and both remaining numeric edits all run, and the first
     * failing test of the four supplies the message. No argument is modified and no input throws.
     * </p>
     *
     * @param part1 the first three characters, held in {@code ACUP-NEW-CUST-SSN-1 PIC X(03)} at
     *              app/cbl/COACTUPC.cbl:L831; may be {@code null}
     * @param part2 the fourth and fifth characters, held in {@code ACUP-NEW-CUST-SSN-2 PIC X(02)}
     *              at app/cbl/COACTUPC.cbl:L832; may be {@code null}
     * @param part3 the last four characters, held in {@code ACUP-NEW-CUST-SSN-3 PIC X(04)} at
     *              app/cbl/COACTUPC.cbl:L833; may be {@code null}
     * @return {@link EditResult#ok()} when every test clears, otherwise a failure verdict carrying
     *         one message
     */
    public static EditResult validate(String part1, String part2, String part3) {

        // app/cbl/COACTUPC.cbl:L2439-L2445 edits part one and copies the resulting flag.
        EditResult part1Verdict =
                NumericRequiredValidator.validate(PART1_LABEL, part1, PART1_WIDTH);

        // app/cbl/COACTUPC.cbl:L2448 admits the rest of the paragraph only on a valid part one, and
        // the period at app/cbl/COACTUPC.cbl:L2488 closes that IF, so a failing part one ends here.
        if (!part1Verdict.valid()) {
            return part1Verdict;
        }

        // app/cbl/COACTUPC.cbl:L2449-L2464 tests the excluded values, and its END-IF precedes the
        // remaining parts, so app/cbl/COACTUPC.cbl:L2469-L2487 edits both of them either way.
        EditResult exclusionVerdict = editExcludedPart1(part1);
        EditResult part2Verdict =
                NumericRequiredValidator.validate(PART2_LABEL, part2, PART2_WIDTH);
        EditResult part3Verdict =
                NumericRequiredValidator.validate(PART3_LABEL, part3, PART3_WIDTH);

        // app/cbl/COACTUPC.cbl:L2454 writes the message only while the slot at
        // app/cbl/COACTUPC.cbl:L480 holds spaces, so the earliest failing test supplies the text.
        if (!exclusionVerdict.valid()) {
            return exclusionVerdict;
        }
        if (!part2Verdict.valid()) {
            return part2Verdict;
        }
        if (!part3Verdict.valid()) {
            return part3Verdict;
        }

        return EditResult.ok();
    }

    /**
     * Applies the excluded-value test of app/cbl/COACTUPC.cbl:L2450.
     *
     * <p>app/cbl/COACTUPC.cbl:L2449 moves the submitted characters into
     * {@code WS-EDIT-US-SSN-PART1}, and {@code INVALID-SSN-PART1} at
     * app/cbl/COACTUPC.cbl:L121-L123 reads them through the {@code PIC 9(3)} redefine at
     * app/cbl/COACTUPC.cbl:L120. The numeric edit has already cleared, so all three positions hold
     * digits by the time this test runs.</p>
     *
     * @param part1 the submitted first part; may be {@code null}
     * @return {@link EditResult#ok()} when the value sits outside every excluded value, otherwise
     *         a failure verdict carrying the app/cbl/COACTUPC.cbl:L2457 message
     */
    private static EditResult editExcludedPart1(String part1) {
        String stored = storedAs(part1, PART1_WIDTH);

        if (!isAllDigits(stored)) {
            return EditResult.ok();
        }

        int value = digitsValue(stored);

        if (isExcluded(value)) {
            // app/cbl/COACTUPC.cbl:L2455-L2460 builds the message from the trimmed label.
            return EditResult.failure(trimSpaces(PART1_LABEL) + EXCLUDED_PART1_MESSAGE);
        }

        return EditResult.ok();
    }

    /**
     * Reports whether a three digit value matches {@code INVALID-SSN-PART1} at
     * app/cbl/COACTUPC.cbl:L121-L123. The condition lists two single values and one band, and any
     * one of the three selects the value.
     *
     * @param value the value the redefine reads, zero through 999
     * @return {@code true} when the value is excluded
     */
    private static boolean isExcluded(int value) {
        return value == EXCLUDED_ZERO
                || value == EXCLUDED_SIX_HUNDRED_SIXTY_SIX
                || (value >= EXCLUDED_BAND_LOWEST && value <= EXCLUDED_BAND_HIGHEST);
    }

    /**
     * Builds the content an item of the given width holds after a COBOL {@code MOVE}, as at
     * app/cbl/COACTUPC.cbl:L2449. The {@code MOVE} left justifies, pads on the right with spaces,
     * and drops any character past the item width.
     *
     * @param value the submitted characters, which this method never changes; may be {@code null}
     * @param width the stored width of the receiving item, one or above
     * @return the stored content, exactly {@code width} characters wide
     */
    private static String storedAs(String value, int width) {
        String moved = value == null ? "" : value;

        if (moved.length() >= width) {
            return moved.substring(0, width);
        }

        return moved + String.valueOf(SPACE).repeat(width - moved.length());
    }

    /**
     * Reports whether every position of the stored content holds one of the ten characters the
     * {@code PIC 9(3)} redefine at app/cbl/COACTUPC.cbl:L120 reads as a digit.
     *
     * @param stored the stored content, never {@code null}
     * @return {@code true} when every position holds a digit
     */
    private static boolean isAllDigits(String stored) {
        for (int index = 0; index < stored.length(); index++) {
            char character = stored.charAt(index);

            if (character < ZERO_DIGIT || character > NINE_DIGIT) {
                return false;
            }
        }

        return true;
    }

    /**
     * Reads the stored content as the unsigned value the {@code PIC 9(3)} redefine at
     * app/cbl/COACTUPC.cbl:L120 carries. Each position contributes its own digit, so the value
     * follows from character positions alone.
     *
     * @param stored the stored content, every position already a digit
     * @return the value the redefine carries
     */
    private static int digitsValue(String stored) {
        int value = 0;

        for (int index = 0; index < stored.length(); index++) {
            value = value * DECIMAL_BASE + (stored.charAt(index) - ZERO_DIGIT);
        }

        return value;
    }

    /**
     * Removes leading and trailing spaces, reproducing {@code FUNCTION TRIM} at
     * app/cbl/COACTUPC.cbl:L2456. The space character alone is removed, and every other character
     * survives.
     *
     * @param text the characters to trim, never {@code null}
     * @return the trimmed text
     */
    private static String trimSpaces(String text) {
        int start = 0;
        int end = text.length();

        while (start < end && text.charAt(start) == SPACE) {
            start++;
        }
        while (end > start && text.charAt(end - 1) == SPACE) {
            end--;
        }

        return text.substring(start, end);
    }
}
