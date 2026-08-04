package com.carddemo.account.domain.validation;

import com.carddemo.cobol.reference.UsStateZipPrefixes;

/**
 * Checks that a state code and the first two characters of a zip code form a combination CardDemo
 * accepts.
 *
 * <p>Realises {@code 1280-EDIT-US-STATE-ZIP-CD} at app/cbl/COACTUPC.cbl:L2536. The key is four
 * characters: the two-character state code followed by the first two characters of the zip code.
 * app/cbl/COACTUPC.cbl:L2537-L2540 builds that key with a STRING statement qualified
 * {@code DELIMITED BY SIZE}. Each operand therefore contributes its full declared width into
 * {@code US-STATE-AND-FIRST-ZIP2}, declared {@code PIC X(4)}.</p>
 *
 * <p>The accepted keys are the 240 values listed under {@code VALID-US-STATE-ZIP-CD2-COMBO} at
 * app/cpy/CSLKPCDY.cpy:L1073. app/cbl/COACTUPC.cbl:L2542 runs the test once. A failing verdict
 * marks the state code and the zip code together, matching app/cbl/COACTUPC.cbl:L2546-L2547. It
 * carries the message at app/cbl/COACTUPC.cbl:L2550.</p>
 *
 * <p>app/cbl/COACTUPC.cbl:L1665-L1666 gates the single call site. Each of the state code and the
 * zip code must have passed its own edit, and the caller owns that gate. This class tests the
 * combination, and the characters of the zip code past the second take no part in the key.</p>
 */
public final class UsStateZipPrefixValidator {

    /**
     * The message at app/cbl/COACTUPC.cbl:L2550, 26 characters, carried with no prefix and no field
     * name.
     */
    public static final String INVALID_ZIP_FOR_STATE_MESSAGE = "Invalid zip code for state";

    /**
     * Identifier for the state code, which a failing verdict marks. Reproduces
     * {@code SET FLG-STATE-NOT-OK TO TRUE} at app/cbl/COACTUPC.cbl:L2546. The source field is
     * {@code ACUP-NEW-CUST-ADDR-STATE-CD} at app/cbl/COACTUPC.cbl:L807.
     */
    public static final String MARKED_FIELD_STATE_CODE = "addressStateCode";

    /**
     * Identifier for the zip code, which the same failing verdict marks. Reproduces
     * {@code SET FLG-ZIPCODE-NOT-OK TO TRUE} at app/cbl/COACTUPC.cbl:L2547. The source field is
     * {@code ACUP-NEW-CUST-ADDR-ZIP} at app/cbl/COACTUPC.cbl:L809.
     */
    public static final String MARKED_FIELD_ZIP_CODE = "addressZipCode";

    /**
     * Declared width of {@code ACUP-NEW-CUST-ADDR-STATE-CD PIC X(02)} at
     * app/cbl/COACTUPC.cbl:L807.
     */
    private static final int STATE_CODE_WIDTH = 2;

    /**
     * Width of the reference {@code ACUP-NEW-CUST-ADDR-ZIP(1:2)} at app/cbl/COACTUPC.cbl:L2538.
     */
    private static final int ZIP_PREFIX_WIDTH = 2;

    /**
     * Width of the whole zip field, {@code ACUP-NEW-CUST-ADDR-ZIP PIC X(10)} at
     * app/cbl/COACTUPC.cbl:L809. The combination key takes the first two characters, and this
     * width bounds what the field can hold.
     */
    private static final int ZIP_FIELD_WIDTH = 10;

    private UsStateZipPrefixValidator() {
    }

    /**
     * Runs the combination test of app/cbl/COACTUPC.cbl:L2542.
     *
     * <p>A key listed at app/cpy/CSLKPCDY.cpy:L1074-L1313 gives a passing verdict. Any other key
     * gives a failing verdict carrying {@link #INVALID_ZIP_FOR_STATE_MESSAGE}. A failing verdict
     * marks both {@link #MARKED_FIELD_STATE_CODE} and {@link #MARKED_FIELD_ZIP_CODE}.</p>
     *
     * <p>Neither argument is modified. A null argument counts as spaces, and no input throws.</p>
     *
     * @param stateCode the two-character state code, {@code ACUP-NEW-CUST-ADDR-STATE-CD} at
     *                  app/cbl/COACTUPC.cbl:L807
     * @param zipCode   the full zip code, {@code ACUP-NEW-CUST-ADDR-ZIP} declared
     *                  {@code PIC X(10)} at app/cbl/COACTUPC.cbl:L809. This method takes its first
     *                  two characters
     * @return a passing verdict, or a failing verdict carrying the message at
     *         app/cbl/COACTUPC.cbl:L2550
     */
    public static EditResult validate(String stateCode, String zipCode) {
        // ADDITIVE. A value wider than its source field forms no listed combination.
        if (exceedsDeclaredWidth(stateCode, STATE_CODE_WIDTH)
                || exceedsDeclaredWidth(zipCode, ZIP_FIELD_WIDTH)) {
            return EditResult.failure(INVALID_ZIP_FOR_STATE_MESSAGE);
        }

        String stateAndFirstZip2 = combinationKey(stateCode, zipCode);
        if (UsStateZipPrefixes.isValidUsStateZipCd2Combo(stateAndFirstZip2)) {
            return EditResult.ok();
        }
        return EditResult.failure(INVALID_ZIP_FOR_STATE_MESSAGE);
    }

    /**
     * Builds the four-character key of app/cbl/COACTUPC.cbl:L2537-L2540: the state code followed by
     * the first two characters of the zip code.
     *
     * @param stateCode the state code, null counts as spaces
     * @param zipCode   the full zip code, null counts as spaces
     * @return a key of exactly four characters
     */
    static String combinationKey(String stateCode, String zipCode) {
        return fixedWidth(stateCode, STATE_CODE_WIDTH) + fixedWidth(zipCode, ZIP_PREFIX_WIDTH);
    }

    /**
     * Returns {@code value} at exactly {@code width} characters. A longer value is cut to width,
     * and a shorter or null value is padded on the right with spaces. This is the fixed-width
     * operand that {@code DELIMITED BY SIZE} at app/cbl/COACTUPC.cbl:L2539 contributes in full.
     *
     * <p>The zip operand is a reference modification of the source field:
     * {@code ACUP-NEW-CUST-ADDR-ZIP(1:2)} at app/cbl/COACTUPC.cbl:L2538 names the first two
     * characters of a {@code PIC X(10)} field. Cutting to width is therefore the source
     * behaviour for the zip, and {@link #exceedsDeclaredWidth(String, int)} covers the case the
     * source cannot reach.</p>
     *
     * @param value the value to size, null counts as spaces
     * @param width the declared width in characters
     * @return a string of exactly {@code width} characters
     */
    private static String fixedWidth(String value, int width) {
        if (value == null) {
            return " ".repeat(width);
        }
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Reports whether a value holds more characters than its source field declares.
     *
     * <p>ADDITIVE. The state code arrives from {@code PIC X(02)} at app/cbl/COACTUPC.cbl:L807 and
     * the zip code from {@code PIC X(10)} at app/cbl/COACTUPC.cbl:L809. A wider value forms no
     * combination the list at app/cpy/CSLKPCDY.cpy:L1074-L1313 holds.</p>
     *
     * <p>Trailing spaces past the declared width are the padding the source field itself holds, so
     * they are not content and this test reports false for them.</p>
     *
     * @param value         the submitted value, null counts as spaces
     * @param declaredWidth the width of the source field
     * @return true when a character other than a space sits past the declared width
     */
    private static boolean exceedsDeclaredWidth(String value, int declaredWidth) {
        if (value == null || value.length() <= declaredWidth) {
            return false;
        }

        for (int position = declaredWidth; position < value.length(); position++) {
            if (value.charAt(position) != ' ') {
                return true;
            }
        }

        return false;
    }
}
