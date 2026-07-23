package com.aws.carddemo.dto.screen;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CORPT00Form}. Oracle: legacy/cpy-bms/CORPT00.CPY (BMS mapset CORPT00, map CORPT0A).
 *
 * <p>These are pure-unit tests: no Spring context, no database, no Testcontainers. A Jakarta Bean
 * Validation {@link Validator} is built programmatically so the {@code @Size(max = ...)} constraints
 * that preserve each BMS {@code PIC X(n)} field length can be exercised in isolation under
 * Surefire.</p>
 *
 * <p>The form models the transaction-report-submit screen (CICS transaction {@code CR00}, COBOL
 * program {@code CORPT00C}). It carries six header fields, three report-type selection flags
 * ({@code monthly}, {@code yearly}, {@code custom}), the start-date and end-date parts
 * (MM / DD / YYYY), a confirm flag, and an error line. Every property is a {@link String}; there are
 * no password fields and no monetary fields on this screen.</p>
 */
class CORPT00FormTest {

    /** Bean Validation factory, created once for the whole test class. */
    private static ValidatorFactory factory;

    /** Validator obtained from {@link #factory}; used by every constraint assertion. */
    private static Validator validator;

    /**
     * Builds the {@link ValidatorFactory} and {@link Validator} once before any test runs. Built
     * programmatically (not via Spring) to keep these tests context-free.
     */
    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    /**
     * Closes the {@link ValidatorFactory} after all tests have run to release its resources.
     */
    @AfterAll
    static void tearDownValidator() {
        factory.close();
    }

    /**
     * A fully-populated form whose every property is at or under its {@code @Size(max)} limit must
     * produce no constraint violations.
     */
    @Test
    void valid_form_has_no_violations() {
        assertThat(validator.validate(validForm())).isEmpty();
    }

    /**
     * A single field one character over its maximum must raise exactly one {@code @Size} violation,
     * reported on that exact property. This exercises the 1-width flags ({@code monthly},
     * {@code confirm}), a 2-width date part ({@code sdtmm}), both 4-width year parts
     * ({@code sdtyyyy}, {@code edtyyyy}), and the wide error line ({@code errmsg}).
     */
    @Test
    void oversize_fields_violate_size_on_exact_property() {
        CORPT00Form monthlyForm = validForm();
        monthlyForm.setMonthly("YY");
        assertSizeViolationOn(monthlyForm, "monthly");

        CORPT00Form sdtmmForm = validForm();
        sdtmmForm.setSdtmm("123");
        assertSizeViolationOn(sdtmmForm, "sdtmm");

        CORPT00Form sdtyyyyForm = validForm();
        sdtyyyyForm.setSdtyyyy("20244");
        assertSizeViolationOn(sdtyyyyForm, "sdtyyyy");

        CORPT00Form edtyyyyForm = validForm();
        edtyyyyForm.setEdtyyyy("20255");
        assertSizeViolationOn(edtyyyyForm, "edtyyyy");

        CORPT00Form confirmForm = validForm();
        confirmForm.setConfirm("YN");
        assertSizeViolationOn(confirmForm, "confirm");

        CORPT00Form errmsgForm = validForm();
        errmsgForm.setErrmsg("X".repeat(79));
        assertSizeViolationOn(errmsgForm, "errmsg");
    }

    /**
     * Every setter stores the value that its matching getter later returns, for all 17 properties.
     */
    @Test
    void getters_return_set_values() {
        CORPT00Form form = new CORPT00Form();
        form.setTrnname("CR00");
        form.setTitle01("Title One");
        form.setCurdate("07/19/24");
        form.setPgmname("CORPT00C");
        form.setTitle02("Title Two");
        form.setCurtime("12:34:56");
        form.setMonthly("Y");
        form.setYearly("N");
        form.setCustom("C");
        form.setSdtmm("03");
        form.setSdtdd("14");
        form.setSdtyyyy("2023");
        form.setEdtmm("06");
        form.setEdtdd("28");
        form.setEdtyyyy("2025");
        form.setConfirm("N");
        form.setErrmsg("No errors detected");

        assertThat(form.getTrnname()).isEqualTo("CR00");
        assertThat(form.getTitle01()).isEqualTo("Title One");
        assertThat(form.getCurdate()).isEqualTo("07/19/24");
        assertThat(form.getPgmname()).isEqualTo("CORPT00C");
        assertThat(form.getTitle02()).isEqualTo("Title Two");
        assertThat(form.getCurtime()).isEqualTo("12:34:56");
        assertThat(form.getMonthly()).isEqualTo("Y");
        assertThat(form.getYearly()).isEqualTo("N");
        assertThat(form.getCustom()).isEqualTo("C");
        assertThat(form.getSdtmm()).isEqualTo("03");
        assertThat(form.getSdtdd()).isEqualTo("14");
        assertThat(form.getSdtyyyy()).isEqualTo("2023");
        assertThat(form.getEdtmm()).isEqualTo("06");
        assertThat(form.getEdtdd()).isEqualTo("28");
        assertThat(form.getEdtyyyy()).isEqualTo("2025");
        assertThat(form.getConfirm()).isEqualTo("N");
        assertThat(form.getErrmsg()).isEqualTo("No errors detected");
    }

    /**
     * Per-form specifics: the three report-type flags are each a single character (max 1), the MM
     * and DD date parts are two characters (max 2), and the YYYY date parts are four characters
     * (max 4). Each width is proven behaviourally: a value at the maximum passes, and a value one
     * character over raises a {@code @Size} violation on that exact property.
     */
    @Test
    void report_type_flags_and_date_parts_have_expected_widths() {
        assertWidth("monthly", 1);
        assertWidth("yearly", 1);
        assertWidth("custom", 1);

        assertWidth("sdtmm", 2);
        assertWidth("sdtdd", 2);
        assertWidth("edtmm", 2);
        assertWidth("edtdd", 2);

        assertWidth("sdtyyyy", 4);
        assertWidth("edtyyyy", 4);
    }

    /**
     * Builds a form whose every property is at or under its {@code @Size(max)} limit.
     *
     * @return a valid, fully-populated {@link CORPT00Form}
     */
    private static CORPT00Form validForm() {
        CORPT00Form form = new CORPT00Form();
        form.setTrnname("CR00");
        form.setTitle01("AWS CardDemo - Transaction Reports");
        form.setCurdate("07/19/24");
        form.setPgmname("CORPT00C");
        form.setTitle02("Report Submission");
        form.setCurtime("12:00:00");
        form.setMonthly("Y");
        form.setYearly("N");
        form.setCustom("N");
        form.setSdtmm("01");
        form.setSdtdd("15");
        form.setSdtyyyy("2024");
        form.setEdtmm("12");
        form.setEdtdd("31");
        form.setEdtyyyy("2024");
        form.setConfirm("Y");
        form.setErrmsg("");
        return form;
    }

    /**
     * Returns a valid form with a single named property overridden with the supplied value.
     *
     * @param property the JavaBean property name to override
     * @param value    the value to assign to that property
     * @return a {@link CORPT00Form} valid in every field except the overridden one
     */
    private CORPT00Form formWith(String property, String value) {
        CORPT00Form form = validForm();
        switch (property) {
            case "monthly" -> form.setMonthly(value);
            case "yearly" -> form.setYearly(value);
            case "custom" -> form.setCustom(value);
            case "sdtmm" -> form.setSdtmm(value);
            case "sdtdd" -> form.setSdtdd(value);
            case "edtmm" -> form.setEdtmm(value);
            case "edtdd" -> form.setEdtdd(value);
            case "sdtyyyy" -> form.setSdtyyyy(value);
            case "edtyyyy" -> form.setEdtyyyy(value);
            default -> throw new IllegalArgumentException("Unknown property: " + property);
        }
        return form;
    }

    /**
     * Asserts that a property accepts a value exactly at {@code max} characters and rejects a value
     * of {@code max + 1} characters with a {@code @Size} violation on that property.
     *
     * @param property the JavaBean property name under test
     * @param max      the maximum allowed length for the property
     */
    private void assertWidth(String property, int max) {
        assertNoViolation(formWith(property, "A".repeat(max)));
        assertSizeViolationOn(formWith(property, "A".repeat(max + 1)), property);
    }

    /**
     * Asserts that the supplied form produces no constraint violations.
     *
     * @param form the form to validate
     */
    private void assertNoViolation(CORPT00Form form) {
        assertThat(validator.validate(form)).isEmpty();
    }

    /**
     * Asserts that the supplied form produces exactly one constraint violation, reported on the
     * given property.
     *
     * @param form     the form to validate
     * @param property the property the single violation is expected on
     */
    private void assertSizeViolationOn(CORPT00Form form, String property) {
        Set<ConstraintViolation<CORPT00Form>> violations = validator.validate(form);
        assertThat(violations).hasSize(1);
        ConstraintViolation<CORPT00Form> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo(property);
    }
}
