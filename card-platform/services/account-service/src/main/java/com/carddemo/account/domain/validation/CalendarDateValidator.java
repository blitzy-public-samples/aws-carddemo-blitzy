package com.carddemo.account.domain.validation;

import com.carddemo.cobol.CobolDateValidator;
import com.carddemo.cobol.CobolDateValidator.FieldEditResult;

/**
 * Checks that eight characters hold a calendar date in {@code CCYYMMDD} order.
 *
 * <p>Realises paragraph {@code EDIT-DATE-CCYYMMDD} at {@code app/cpy/CSUTLDPY.cpy:L18} through its
 * exit at {@code app/cpy/CSUTLDPY.cpy:L329}. The range closes with {@code EDIT-DATE-LE} at
 * {@code app/cpy/CSUTLDPY.cpy:L284}. That paragraph passes the mask
 * {@value CobolDateValidator#STRICT_POLICY_DATE_MASK}, moved at
 * {@code app/cpy/CSUTLDPY.cpy:L291}, and accepts the date only when the numeric severity at
 * {@code app/cpy/CSUTLDPY.cpy:L298} reads {@value CobolDateValidator#ACCEPTED_SEVERITY_NUMBER}.
 *
 * <p>{@link CobolDateValidator#editDateCcyymmdd(String, String)} carries the year, month, day, and
 * combination edits together with that closing call. This class supplies the label, reads the
 * verdict, and returns the one message the range composed.
 *
 * <p>One gate serves four call sites: {@code app/cbl/COACTUPC.cbl:L1480} for
 * {@code 'Open Date'}, {@code app/cbl/COACTUPC.cbl:L1492} for {@code 'Expiry Date'},
 * {@code app/cbl/COACTUPC.cbl:L1505} for {@code 'Reissue Date'}, and
 * {@code app/cbl/COACTUPC.cbl:L1536} for {@code 'Date of Birth'}.
 */
public final class CalendarDateValidator {

    private CalendarDateValidator() {
    }

    /**
     * Applies the date edits to one submitted value.
     *
     * <p>A value that clears every edit passes and carries no message. Any other value yields the
     * first message the range composed, opening with the trimmed label, as
     * {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)} at {@code app/cpy/CSUTLDPY.cpy:L307} writes it.
     * The label keeps its full length. A {@code null} value reads as {@code LOW-VALUES}, which
     * {@code app/cpy/CSUTLDPY.cpy:L30} tests for.
     *
     * <p>The verdict reads the input-error state and the severity of the closing call, by way of
     * {@link FieldEditResult#accepted()}. No argument value raises an exception, and this method
     * modifies no argument, so two calls with equal arguments yield equal verdicts.
     *
     * @param fieldLabel the label the caller supplies, moved into
     *                   {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at
     *                   {@code app/cbl/COACTUPC.cbl:L53}; may be {@code null}
     * @param date       the eight characters held by {@code WS-EDIT-DATE-CCYYMMDD}, moved at
     *                   {@code app/cbl/COACTUPC.cbl:L1479}; may be {@code null}
     * @return a passing verdict for a value that cleared every edit, otherwise a failing verdict
     *         carrying the message the range composed
     */
    public static EditResult validate(String fieldLabel, String date) {
        // app/cpy/CSUTLDPY.cpy:L291 moves the mask STRICT_POLICY_DATE_MASK and
        // app/cpy/CSUTLDPY.cpy:L298 reads the numeric severity. The call below binds both.
        FieldEditResult strictPolicyEdits = CobolDateValidator.editDateCcyymmdd(date, fieldLabel);
        if (strictPolicyEdits.accepted()) {
            return EditResult.ok();
        }
        return EditResult.failure(strictPolicyEdits.firstReturnMessage());
    }
}
