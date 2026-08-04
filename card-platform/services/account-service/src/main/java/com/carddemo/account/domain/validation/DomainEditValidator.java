package com.carddemo.account.domain.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Runs the edit a {@link DomainEdit} names and reports the message that edit produced.
 *
 * <p>ADDITIVE as a mechanism. Every rule reached from here already lives in a validator class of
 * this package, translated from the field edits of {@code app/cbl/COACTUPC.cbl}. This class adds no
 * rule and changes none: it is the wire that makes Bean Validation run them, which is what
 * {@code CustomerDataRequest} and {@code AccountDataRequest} previously lacked.
 *
 * <p>A failure replaces the default template with the text the validator returned, so the caller
 * reads the source's own wording. {@code app/cbl/COACTUPC.cbl} builds each message from a field label
 * and a reason, and the label arrives through {@link DomainEdit#label()}.
 *
 * <p>Two properties of the reported message matter for a service that answers over the network. The
 * text comes from the validator and therefore names a field and a reason, never the value the caller
 * sent. And the template is escaped before it is reported, so a value that reached the message by way
 * of a validator could not be read back as an expression: Bean Validation interpolates
 * {@code ${...}} in a message template, and a template assembled from an untrusted value would be an
 * expression-language injection. Escaping the braces closes that path.
 *
 * <p>An absent value is handed to the validator rather than passed over. That is deliberate: several
 * edits of the source distinguish a value that was not supplied from one that was supplied and
 * failed, and {@link MandatoryFieldValidator} exists only to reject the first. A Bean Validation
 * convention of treating {@code null} as valid would silence it.
 */
public final class DomainEditValidator implements ConstraintValidator<DomainEdit, String> {

    /** The edit this instance runs, read from the annotation once. */
    private DomainEdit.Edit edit;

    /** The field label the edit writes into its message. */
    private String label;

    /** The width the edit inspects, zero for the edits that take none. */
    private int width;

    /**
     * Reads the three members of the annotation.
     *
     * @param annotation the declaration on the component
     */
    @Override
    public void initialize(DomainEdit annotation) {
        this.edit = annotation.value();
        this.label = annotation.label();
        this.width = annotation.width();
    }

    /**
     * Runs the edit and reports its verdict.
     *
     * @param value   the component value, which may be {@code null}
     * @param context the context a failure reports through
     * @return {@code true} when the edit passes
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        EditResult outcome = run(value);
        if (outcome.valid()) {
            return true;
        }
        if (outcome.hasMessage()) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(escapeTemplate(outcome.message()))
                    .addConstraintViolation();
        }
        return false;
    }

    /**
     * Dispatches to the validator class the edit names.
     *
     * @param value the component value, which may be {@code null}
     * @return the verdict that validator returned
     */
    private EditResult run(String value) {
        return switch (edit) {
            case NUMERIC_REQUIRED -> NumericRequiredValidator.validate(label, value, width);
            case ALPHABETIC_REQUIRED -> AlphabeticRequiredValidator.validate(label, value, width);
            case ALPHABETIC_OPTIONAL -> AlphabeticOptionalValidator.validate(label, value, width);
            case ALPHANUMERIC_REQUIRED ->
                    AlphanumericRequiredValidator.validate(label, value, width);
            case ALPHANUMERIC_OPTIONAL ->
                    AlphanumericOptionalValidator.validate(label, value, width);
            case MANDATORY -> MandatoryFieldValidator.validate(label, value, width);
            case US_STATE_CODE -> UsStateCodeValidator.validate(label, value);
            case YES_NO_FLAG -> YesNoFlagValidator.validate(label, value);
            case DATE_OF_BIRTH -> DateOfBirthValidator.validate(label, value);
            case CREDIT_SCORE_RANGE -> CreditScoreRangeValidator.validate(label, value);
            case CALENDAR_DATE -> CalendarDateValidator.validate(label, value);
            case ACCOUNT_ID -> AccountIdValidator.validate(value);
            case SIGNED_DECIMAL -> SignedDecimalValidator.validate(label, value);
        };
    }

    /**
     * Escapes the two characters Bean Validation reads as expression syntax in a message template.
     *
     * <p>A template is interpolated, so {@code ${1 + 1}} in one would be evaluated rather than
     * printed. Every message this class reports is built by a validator of this package from a fixed
     * label and a fixed reason, so none carries either character today. Escaping them anyway means a
     * later edit that did include part of a caller's value could not turn that value into an
     * expression.
     *
     * @param message the text the validator produced
     * @return the same text with each brace escaped
     */
    private static String escapeTemplate(String message) {
        return message.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}")
                .replace("$", "\\$");
    }
}
