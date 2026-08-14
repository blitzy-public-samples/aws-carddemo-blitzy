package com.carddemo.account.domain.validation;

import com.carddemo.cobol.CobolDateValidator;
import java.time.LocalDate;

/**
 * Checks that a date of birth is strictly earlier than today. Today's own date fails.
 *
 * <p>Realises paragraph {@code EDIT-DATE-OF-BIRTH} at {@code app/cpy/CSUTLDPY.cpy:L341}, whose exit
 * paragraph sits at {@code app/cpy/CSUTLDPY.cpy:L370}. The comparison at
 * {@code app/cpy/CSUTLDPY.cpy:L350} is strictly greater: today's day count must exceed the day
 * count of the supplied value.
 *
 * <p>One call site reaches the paragraph, at {@code app/cbl/COACTUPC.cbl:L1540}, and
 * {@code app/cbl/COACTUPC.cbl:L1533} supplies the label {@code 'Date of Birth'}. The gate at
 * {@code app/cbl/COACTUPC.cbl:L1539} runs {@code EDIT-DATE-CCYYMMDD} first and admits this check
 * only when that paragraph accepted the value. The caller owns that gate.
 *
 * <p>{@link CobolDateValidator} carries the comparison and the one message text.
 *
 * <p>Two overloads exist. {@link #validate(String, String)} reads today from the clock, and
 * {@link #validate(String, String, java.time.LocalDate)} takes the day the comparison runs
 * against, so a caller can state it.
 */
public final class DateOfBirthValidator {

    private DateOfBirthValidator() {
    }

    /**
     * Applies the reasonableness check of {@code app/cpy/CSUTLDPY.cpy:L343-L368} to one submitted
     * date of birth.
     *
     * <p>A value earlier than today passes and carries no message. A value equal to today and a
     * value later than today both fail, carrying the one message
     * {@code app/cpy/CSUTLDPY.cpy:L361-L365} composes: the trimmed label joined to the text at
     * {@code app/cpy/CSUTLDPY.cpy:L363}. {@code FUNCTION TRIM} at {@code app/cpy/CSUTLDPY.cpy:L362}
     * removes the spaces of the {@code PIC X(25)} label field at {@code app/cbl/COACTUPC.cbl:L53}.
     * A wider label holds its first twenty-five characters, as a {@code MOVE} into that field does.
     *
     * <p>A value that {@code EDIT-DATE-CCYYMMDD} rejects passes here and carries no message. The
     * gate at {@code app/cbl/COACTUPC.cbl:L1539} keeps such a value away from this check.
     *
     * <p>No argument value raises an exception. This method modifies no argument, so two calls with
     * equal arguments yield equal verdicts.
     *
     * @param fieldLabel  the label the caller supplies, {@code 'Date of Birth'} at
     *                    {@code app/cbl/COACTUPC.cbl:L1533}; a {@code null} label reads as the
     *                    all-space field it models and contributes no text
     * @param dateOfBirth the eight characters of century, year, month, and day held by
     *                    {@code WS-EDIT-DATE-CCYYMMDD}; may be {@code null}
     * @return a passing verdict for a value earlier than today, otherwise a failing verdict
     *         carrying the one message the paragraph composes
     */
    public static EditResult validate(String fieldLabel, String dateOfBirth) {
        return validate(fieldLabel, dateOfBirth, LocalDate.now());
    }

    /**
     * Applies the same check against a reference date the caller supplies.
     *
     * <p>{@code FUNCTION CURRENT-DATE} at {@code app/cpy/CSUTLDPY.cpy:L343} reads the date the
     * two-argument overload reads. This overload takes that date as an argument, so a caller that
     * needs a fixed verdict states which day the comparison runs against. The verdict is otherwise
     * identical: {@link #validate(String, String)} calls this method with today's date.
     *
     * @param fieldLabel    the label the caller supplies, {@code 'Date of Birth'} at
     *                      {@code app/cbl/COACTUPC.cbl:L1533}; a {@code null} label reads as the
     *                      all-space field it models and contributes no text
     * @param dateOfBirth   the eight characters of century, year, month, and day held by
     *                      {@code WS-EDIT-DATE-CCYYMMDD}; may be {@code null}
     * @param referenceDate the day the comparison at {@code app/cpy/CSUTLDPY.cpy:L350} runs against
     * @return a passing verdict for a value earlier than {@code referenceDate}, otherwise a failing
     *         verdict carrying the one message the paragraph composes
     * @throws NullPointerException when {@code referenceDate} is {@code null}
     */
    public static EditResult validate(String fieldLabel, String dateOfBirth,
            LocalDate referenceDate) {
        CobolDateValidator.FieldEditResult outcome =
                CobolDateValidator.editDateOfBirth(dateOfBirth, fieldLabel, referenceDate);
        if (isReasonablenessRejection(outcome)) {
            return EditResult.failure(outcome.firstReturnMessage());
        }
        return EditResult.ok();
    }

    /**
     * Reports whether an outcome carries the rejection of
     * {@code app/cpy/CSUTLDPY.cpy:L355-L367} and not a field-edit rejection.
     *
     * <p>{@link CobolDateValidator#editDateOfBirth(String, String)} runs the field edits of
     * {@code app/cpy/CSUTLDPY.cpy:L18-L279} ahead of the comparison, matching the sequence at
     * {@code app/cbl/COACTUPC.cbl:L1536-L1543}, and returns the field-edit outcome unchanged when
     * those edits rejected the value. Those edits reach the strict-policy call of
     * {@code app/cpy/CSUTLDPY.cpy:L293-L296} only once all three flag bytes hold a valid state. An
     * accepted strict-policy outcome marks a value those edits passed.
     *
     * @param outcome the outcome the library produced
     * @return true when the field edits accepted the value and the comparison at
     *         {@code app/cpy/CSUTLDPY.cpy:L350} rejected it
     */
    private static boolean isReasonablenessRejection(CobolDateValidator.FieldEditResult outcome) {
        CobolDateValidator.DateValidationResult fieldEdits = outcome.dateValidation();
        return !outcome.accepted()
                && fieldEdits != null
                && fieldEdits.acceptedByStrictPolicy();
    }
}
