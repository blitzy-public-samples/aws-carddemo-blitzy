package com.carddemo.account.domain.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Runs one of the field edits of {@code app/cbl/COACTUPC.cbl} against the annotated component.
 *
 * <p>The mechanism has no COBOL ancestor; the behaviour does. Every rule this annotation reaches
 * already lives in a validator class of this package, translated from paragraphs {@code
 * 1205-EDIT-*} through {@code 1280-EDIT-*} at {@code app/cbl/COACTUPC.cbl:L1820-L2560}. What was
 * missing was the wiring: a validator that nothing calls edits nothing. Bean Validation runs a
 * constraint only where one is declared, so a data-transfer object carrying width bounds alone was
 * bounded and unedited.
 *
 * <p>One annotation covers every single-component edit, selected by {@link #value()}. The
 * alternative, one annotation type and one validator class per edit, would have added twenty files
 * of plumbing that say nothing a reader cannot already see in the enum.
 *
 * <p>The message a failure carries is the message the underlying validator produced, character for
 * character from the source paragraph that writes it. This annotation declares no message text of its
 * own, so no source text is duplicated here and none can drift.
 *
 * <p>Three edits of the source read more than one component and therefore cannot be reached from a
 * component annotation: the telephone number, whose area code, prefix and line number are edited
 * together; the social security number, whose three parts are edited together; and the postal code,
 * which is edited against the state code. Each of those is declared on its owning record as an
 * {@code AssertTrue} method that calls the same validator class.
 *
 * @see DomainEditValidator
 */
@Documented
@Constraint(validatedBy = DomainEditValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
        ElementType.RECORD_COMPONENT, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DomainEdit {

    /**
     * The edit to run.
     *
     * @return one member of {@link Edit}
     */
    Edit value();

    /**
     * The field label the edit writes into its message, exactly as the source screen names it.
     *
     * <p>{@code app/cbl/COACTUPC.cbl} builds each message by concatenating a label with a reason, so
     * the label is part of the text a caller reads. The literal at
     * {@code app/cbl/COACTUPC.cbl:L1545} names the credit score {@code 'FICO Score'}, for one
     * example, and a different label would produce a different message from the same rule.
     *
     * @return the label, defaulting to the empty string for the edits that write no label
     */
    String label() default "";

    /**
     * The width the edit inspects, from the Picture clause of the source field.
     *
     * <p>Only the edits that take a width read this member. A value wider than the width is refused
     * rather than edited on its first characters.
     *
     * @return the width, defaulting to zero for the edits that take none
     */
    int width() default 0;

    /**
     * The constraint groups this edit belongs to.
     *
     * @return the groups, empty by default
     */
    Class<?>[] groups() default {};

    /**
     * The payload Bean Validation carries.
     *
     * @return the payload types, empty by default
     */
    Class<? extends Payload>[] payload() default {};

    /**
     * The message template, which a failure replaces with the text the underlying validator produced.
     *
     * <p>This default is reached only when a validator fails and supplies no message of its own,
     * which no validator of this package does. It names no field and carries no value.
     *
     * @return the fallback template
     */
    String message() default "The value supplied for this field failed its edit.";

    /**
     * The single-component edits of {@code app/cbl/COACTUPC.cbl} a component may declare.
     *
     * <p>Each member names the validator class it runs and the source paragraph that validator
     * translates. Adding an edit means adding a member here and one branch in
     * {@link DomainEditValidator}, and nothing else.
     */
    enum Edit {

        /**
         * The value must be supplied and must hold digits only.
         *
         * <p>{@link NumericRequiredValidator}, from {@code 1205-EDIT-ACCOUNT} and its siblings.
         */
        NUMERIC_REQUIRED,

        /**
         * The value must be supplied and must hold letters and spaces only.
         *
         * <p>{@link AlphabeticRequiredValidator}, from the alphabetic edit that reads
         * {@code LIT-ALL-ALPHA} at {@code app/cbl/COACTUPC.cbl:L255}.
         */
        ALPHABETIC_REQUIRED,

        /**
         * The value may be absent, and holds letters and spaces only when it is present.
         *
         * <p>{@link AlphabeticOptionalValidator}, from the same alphabetic edit under the
         * low-values test at {@code app/cbl/COACTUPC.cbl:L2018}.
         */
        ALPHABETIC_OPTIONAL,

        /**
         * The value must be supplied and must hold letters, digits and spaces only.
         *
         * <p>{@link AlphanumericRequiredValidator}.
         */
        ALPHANUMERIC_REQUIRED,

        /**
         * The value may be absent, and holds letters, digits and spaces only when present.
         *
         * <p>{@link AlphanumericOptionalValidator}.
         */
        ALPHANUMERIC_OPTIONAL,

        /**
         * The value must be supplied, whatever it holds.
         *
         * <p>{@link MandatoryFieldValidator}, from the not-ok flag at
         * {@code app/cbl/COACTUPC.cbl:L1826}.
         */
        MANDATORY,

        /**
         * The value must name one of the fifty-six state codes.
         *
         * <p>{@link UsStateCodeValidator}, from the table lookup at
         * {@code app/cbl/COACTUPC.cbl:L2494} over the literals of {@code app/cpy/CSLKPCDY.cpy}.
         */
        US_STATE_CODE,

        /**
         * The value must be {@code Y} or {@code N}.
         *
         * <p>{@link YesNoFlagValidator}, from {@code 88 FLG-YES-NO-VALID} and the tests at
         * {@code app/cbl/COACTUPC.cbl:L1861-L1863}.
         */
        YES_NO_FLAG,

        /**
         * The value must be a calendar date that is not in the future.
         *
         * <p>{@link DateOfBirthValidator}, which adds the reasonableness test the source applies to
         * a date of birth.
         */
        DATE_OF_BIRTH,

        /**
         * The value must fall between three hundred and eight hundred and fifty inclusive.
         *
         * <p>{@link CreditScoreRangeValidator}, from condition {@code FICO-RANGE-IS-VALID} at
         * {@code app/cbl/COACTUPC.cbl:L848-L849}.
         */
        CREDIT_SCORE_RANGE,

        /**
         * The value must be a calendar date.
         *
         * <p>{@link CalendarDateValidator}, which reaches the same date validator the source calls
         * through {@code CSUTLDTC} and keeps its message-2513 tolerance.
         */
        CALENDAR_DATE,

        /**
         * The value must be an account identifier.
         *
         * <p>{@link AccountIdValidator}, from the account edit at
         * {@code app/cbl/COACTUPC.cbl:L1820-L1850}.
         */
        ACCOUNT_ID,

        /**
         * The value must be a signed decimal amount.
         *
         * <p>{@link SignedDecimalValidator}, which reads the currency-tolerant numeric grammar the
         * source reaches through {@code FUNCTION NUMVAL-C}.
         */
        SIGNED_DECIMAL
    }
}
