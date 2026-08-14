package com.carddemo.account.domain.validation;

/**
 * Edits one submitted account identifier. A valid identifier holds eleven digits and is not zero.
 *
 * <p>Realises paragraph {@code 1210-EDIT-ACCOUNT} at {@code app/cbl/COACTUPC.cbl:L1783}, whose exit
 * paragraph {@code 1210-EDIT-ACCOUNT-EXIT} sits at line 1820. The field is
 * {@code CC-ACCT-ID PIC X(11)}, and the numeric redefine {@code CC-ACCT-ID-N PIC 9(11)} covers the
 * same eleven bytes, both at {@code app/cpy/CVCRD01Y.cpy:L34-L36}.</p>
 *
 * <p>Two branches return a failure. Lines 1787 and 1788 catch a field of low values or of spaces.
 * Lines 1802 and 1803 catch a field that is not eleven digits, and a field of eleven zeros. Neither
 * message carries a field-label prefix.</p>
 *
 * <p>The one call site performs the paragraph as a range, at
 * {@code app/cbl/COACTUPC.cbl:L1435-L1436}. Each {@code GO TO 1210-EDIT-ACCOUNT-EXIT}, at lines
 * 1796 and 1813, returns a verdict from {@link #validate(String)}.</p>
 *
 * <p>The source paragraph also writes {@code CDEMO-ACCT-ID} and {@code ACUP-NEW-ACCT-ID}, at lines
 * 1794, 1795, 1801, 1812 and 1815. This class writes neither field and keeps no state.</p>
 */
public final class AccountIdValidator {

    /** Width of {@code CC-ACCT-ID PIC X(11)} at {@code app/cpy/CVCRD01Y.cpy:L34-L36}. */
    private static final int ACCOUNT_ID_LENGTH = 11;

    /**
     * The lowest character the numeric class test at {@code app/cbl/COACTUPC.cbl:L1802} accepts,
     * and the character {@code CC-ACCT-ID-N EQUAL ZEROS} at line 1803 looks for.
     */
    private static final char DIGIT_ZERO = '0';

    /**
     * The highest character the numeric class test at {@code app/cbl/COACTUPC.cbl:L1802} accepts.
     */
    private static final char DIGIT_NINE = '9';

    /**
     * Text of condition name {@code WS-PROMPT-FOR-ACCT}, declared at
     * {@code app/cbl/COACTUPC.cbl:L483-L484} and set at line 1792. Twenty-seven characters, copied
     * character for character, with no prefix and no closing full stop.
     */
    private static final String ACCOUNT_NUMBER_NOT_PROVIDED = "Account number not provided";

    /**
     * Text built by the {@code STRING} statement at
     * {@code app/cbl/COACTUPC.cbl:L1806-L1810}. The two literals sit on lines 1807 and
     * 1808, the second one opening with a space, and {@code DELIMITED BY SIZE} joins
     * them whole into sixty-one characters. {@code a 11} is the source wording.
     */
    private static final String ACCOUNT_NUMBER_NOT_ELEVEN_DIGITS =
            "Account Number if supplied must be a 11 digit" + " Non-Zero Number";

    private AccountIdValidator() {
    }

    /**
     * Checks one submitted account identifier and returns the verdict.
     *
     * <p>The verdict passes when the argument holds eleven digits and at least one
     * digit is not zero. Otherwise the verdict fails and carries the text of the
     * branch that caught the value. This method throws nothing, writes nothing, and
     * leaves the argument untouched, so the same argument always yields the same
     * verdict.</p>
     *
     * <p>An argument of fewer than eleven characters fails.
     * {@code app/cbl/COACTUPC.cbl:L1056} moves the screen field into
     * {@code CC-ACCT-ID PIC X(11)}, which pads on the right with spaces, and a space
     * is not a digit. An argument of more than eleven characters fails the same
     * test.</p>
     *
     * @param accountId the submitted account identifier; may be {@code null}
     * @return a passing verdict, or a failing verdict carrying one message
     */
    public static EditResult validate(String accountId) {
        // app/cbl/COACTUPC.cbl:L1787-L1788, with the message set at line 1792.
        if (isNotSupplied(accountId)) {
            return EditResult.failure(ACCOUNT_NUMBER_NOT_PROVIDED);
        }

        String field = padToFieldWidth(accountId);

        // app/cbl/COACTUPC.cbl:L1802-L1803, with the message built at lines 1806 to 1810.
        if (!holdsElevenDigits(field) || holdsOnlyZeros(field)) {
            return EditResult.failure(ACCOUNT_NUMBER_NOT_ELEVEN_DIGITS);
        }

        // app/cbl/COACTUPC.cbl:L1816.
        return EditResult.ok();
    }

    /**
     * Reports whether the field carries no value. Covers both operands of
     * {@code app/cbl/COACTUPC.cbl:L1787-L1788}: {@code null} and the empty string
     * stand for {@code LOW-VALUES}, and a field of nothing but spaces stands for
     * {@code SPACES}.
     *
     * @param accountId the submitted account identifier; may be {@code null}
     */
    private static boolean isNotSupplied(String accountId) {
        return accountId == null || accountId.chars().allMatch(character -> character == ' ');
    }

    /**
     * Pads a copy of the value on the right with spaces to the width of
     * {@code CC-ACCT-ID PIC X(11)}, matching the fixed-width move at
     * {@code app/cbl/COACTUPC.cbl:L1056}. A value already eleven characters wide, or
     * wider, is returned as it arrived.
     *
     * @param accountId the submitted account identifier, never {@code null}
     */
    private static String padToFieldWidth(String accountId) {
        if (accountId.length() >= ACCOUNT_ID_LENGTH) {
            return accountId;
        }
        return accountId + " ".repeat(ACCOUNT_ID_LENGTH - accountId.length());
    }

    /**
     * Applies the numeric class test at {@code app/cbl/COACTUPC.cbl:L1802} to the
     * padded field. The test passes only when the field is exactly eleven characters
     * wide and every character falls between {@code '0'} and {@code '9'}.
     *
     * @param field the padded field, never {@code null}
     */
    private static boolean holdsElevenDigits(String field) {
        if (field.length() != ACCOUNT_ID_LENGTH) {
            return false;
        }
        for (int position = 0; position < ACCOUNT_ID_LENGTH; position++) {
            char character = field.charAt(position);
            if (character < DIGIT_ZERO || character > DIGIT_NINE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Applies {@code CC-ACCT-ID-N EQUAL ZEROS} at
     * {@code app/cbl/COACTUPC.cbl:L1803} as a character test over the same eleven
     * bytes.
     *
     * @param field the padded field, never {@code null}
     */
    private static boolean holdsOnlyZeros(String field) {
        return field.chars().allMatch(character -> character == DIGIT_ZERO);
    }
}
