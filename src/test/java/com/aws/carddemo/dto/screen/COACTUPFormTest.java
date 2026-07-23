package com.aws.carddemo.dto.screen;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link COACTUPForm}. Oracle: legacy/cpy-bms/COACTUP.CPY (BMS mapset COACTUP, map CACTUPA).
 *
 * <p>These are pure Surefire unit tests: no database, no Spring context, and no Testcontainers.
 * A Jakarta Bean Validation {@link Validator} is built programmatically so the {@code @Size}
 * upper bounds carried by the form (which preserve the legacy BMS {@code PIC X(n)} field-length
 * contract) can be exercised in isolation. The account-update screen splits dates into
 * year/month/day parts, the SSN into three parts, and each phone number into area/prefix/line
 * parts, and it carries three PF-key legend buffers; every one of the 54 {@code String}
 * properties is covered below.</p>
 *
 * <p>The five display-edited amount fields (credit limit, cash limit, current balance, cycle
 * credit, and cycle debit) are asserted to be carried as {@code String}: this file never imports
 * or references {@code java.math.BigDecimal} or any floating-point type.</p>
 */
class COACTUPFormTest {

    /** Validation factory shared by every test; closed in {@link #tearDownValidator()}. */
    private static ValidatorFactory factory;

    /** Bean Validation validator resolved from {@link #factory}. */
    private static Validator validator;

    /**
     * Builds a standalone Bean Validation validator (Hibernate Validator via the default provider)
     * without bootstrapping Spring.
     */
    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    /**
     * Releases the validation factory allocated in {@link #setUpValidator()}.
     */
    @AfterAll
    static void tearDownValidator() {
        factory.close();
    }

    /**
     * A fully populated form whose every property is at or under its {@code @Size} maximum must
     * produce no constraint violations.
     */
    @Test
    void valid_form_has_no_violations() {
        assertThat(validator.validate(validForm())).isEmpty();
    }

    /**
     * A value one character over a field's maximum must raise exactly one {@code @Size} violation,
     * reported against that exact property. The sample spans every field family: the plain account
     * id, a money field, both the short and long split SSN parts, a split date part, a split phone
     * part, and all three PF-key legend buffers, plus the error line.
     */
    @Test
    void oversize_fields_violate_size_on_exact_property() {
        COACTUPForm form;

        form = validForm();
        form.setAcctsid("x".repeat(12));
        assertExactlyOneSizeViolationOn(form, "acctsid");

        form = validForm();
        form.setAcrdlim("x".repeat(16));
        assertExactlyOneSizeViolationOn(form, "acrdlim");

        form = validForm();
        form.setActssn1("x".repeat(4));
        assertExactlyOneSizeViolationOn(form, "actssn1");

        form = validForm();
        form.setActssn3("x".repeat(5));
        assertExactlyOneSizeViolationOn(form, "actssn3");

        form = validForm();
        form.setOpnyear("x".repeat(5));
        assertExactlyOneSizeViolationOn(form, "opnyear");

        form = validForm();
        form.setAcsph1c("x".repeat(5));
        assertExactlyOneSizeViolationOn(form, "acsph1c");

        form = validForm();
        form.setFkeys("x".repeat(22));
        assertExactlyOneSizeViolationOn(form, "fkeys");

        form = validForm();
        form.setFkey05("x".repeat(8));
        assertExactlyOneSizeViolationOn(form, "fkey05");

        form = validForm();
        form.setFkey12("x".repeat(11));
        assertExactlyOneSizeViolationOn(form, "fkey12");

        form = validForm();
        form.setErrmsg("x".repeat(79));
        assertExactlyOneSizeViolationOn(form, "errmsg");
    }

    /**
     * Every one of the 54 {@code String} properties round-trips through its setter and getter,
     * including all split date, SSN, and phone parts and the three PF-key legend buffers.
     */
    @Test
    void getters_return_set_values() {
        COACTUPForm form = new COACTUPForm();

        form.setTrnname("CAUP");
        assertThat(form.getTrnname()).isEqualTo("CAUP");

        form.setTitle01("Account Update Screen");
        assertThat(form.getTitle01()).isEqualTo("Account Update Screen");

        form.setCurdate("01/02/26");
        assertThat(form.getCurdate()).isEqualTo("01/02/26");

        form.setPgmname("COACTUPC");
        assertThat(form.getPgmname()).isEqualTo("COACTUPC");

        form.setTitle02("AWS CardDemo");
        assertThat(form.getTitle02()).isEqualTo("AWS CardDemo");

        form.setCurtime("12:00:00");
        assertThat(form.getCurtime()).isEqualTo("12:00:00");

        form.setAcctsid("00000000001");
        assertThat(form.getAcctsid()).isEqualTo("00000000001");

        form.setAcsttus("Y");
        assertThat(form.getAcsttus()).isEqualTo("Y");

        form.setOpnyear("2020");
        assertThat(form.getOpnyear()).isEqualTo("2020");

        form.setOpnmon("01");
        assertThat(form.getOpnmon()).isEqualTo("01");

        form.setOpnday("15");
        assertThat(form.getOpnday()).isEqualTo("15");

        form.setAcrdlim("5000.00");
        assertThat(form.getAcrdlim()).isEqualTo("5000.00");

        form.setExpyear("2025");
        assertThat(form.getExpyear()).isEqualTo("2025");

        form.setExpmon("12");
        assertThat(form.getExpmon()).isEqualTo("12");

        form.setExpday("31");
        assertThat(form.getExpday()).isEqualTo("31");

        form.setAcshlim("1000.00");
        assertThat(form.getAcshlim()).isEqualTo("1000.00");

        form.setRisyear("2021");
        assertThat(form.getRisyear()).isEqualTo("2021");

        form.setRismon("06");
        assertThat(form.getRismon()).isEqualTo("06");

        form.setRisday("10");
        assertThat(form.getRisday()).isEqualTo("10");

        form.setAcurbal("1234.56");
        assertThat(form.getAcurbal()).isEqualTo("1234.56");

        form.setAcrcycr("10.00");
        assertThat(form.getAcrcycr()).isEqualTo("10.00");

        form.setAaddgrp("GRP0000001");
        assertThat(form.getAaddgrp()).isEqualTo("GRP0000001");

        form.setAcrcydb("20.00");
        assertThat(form.getAcrcydb()).isEqualTo("20.00");

        form.setAcstnum("000000001");
        assertThat(form.getAcstnum()).isEqualTo("000000001");

        form.setActssn1("123");
        assertThat(form.getActssn1()).isEqualTo("123");

        form.setActssn2("45");
        assertThat(form.getActssn2()).isEqualTo("45");

        form.setActssn3("6789");
        assertThat(form.getActssn3()).isEqualTo("6789");

        form.setDobyear("1980");
        assertThat(form.getDobyear()).isEqualTo("1980");

        form.setDobmon("07");
        assertThat(form.getDobmon()).isEqualTo("07");

        form.setDobday("04");
        assertThat(form.getDobday()).isEqualTo("04");

        form.setAcstfco("750");
        assertThat(form.getAcstfco()).isEqualTo("750");

        form.setAcsfnam("John");
        assertThat(form.getAcsfnam()).isEqualTo("John");

        form.setAcsmnam("Q");
        assertThat(form.getAcsmnam()).isEqualTo("Q");

        form.setAcslnam("Public");
        assertThat(form.getAcslnam()).isEqualTo("Public");

        form.setAcsadl1("123 Main Street");
        assertThat(form.getAcsadl1()).isEqualTo("123 Main Street");

        form.setAcsstte("VA");
        assertThat(form.getAcsstte()).isEqualTo("VA");

        form.setAcsadl2("Suite 100");
        assertThat(form.getAcsadl2()).isEqualTo("Suite 100");

        form.setAcszipc("20001");
        assertThat(form.getAcszipc()).isEqualTo("20001");

        form.setAcscity("Arlington");
        assertThat(form.getAcscity()).isEqualTo("Arlington");

        form.setAcsctry("USA");
        assertThat(form.getAcsctry()).isEqualTo("USA");

        form.setAcsph1a("703");
        assertThat(form.getAcsph1a()).isEqualTo("703");

        form.setAcsph1b("555");
        assertThat(form.getAcsph1b()).isEqualTo("555");

        form.setAcsph1c("0100");
        assertThat(form.getAcsph1c()).isEqualTo("0100");

        form.setAcsgovt("GOVT-ID-0001");
        assertThat(form.getAcsgovt()).isEqualTo("GOVT-ID-0001");

        form.setAcsph2a("202");
        assertThat(form.getAcsph2a()).isEqualTo("202");

        form.setAcsph2b("555");
        assertThat(form.getAcsph2b()).isEqualTo("555");

        form.setAcsph2c("0199");
        assertThat(form.getAcsph2c()).isEqualTo("0199");

        form.setAcseftc("EFT0000001");
        assertThat(form.getAcseftc()).isEqualTo("EFT0000001");

        form.setAcspflg("Y");
        assertThat(form.getAcspflg()).isEqualTo("Y");

        form.setInfomsg("Informational message");
        assertThat(form.getInfomsg()).isEqualTo("Informational message");

        form.setErrmsg("Error message line");
        assertThat(form.getErrmsg()).isEqualTo("Error message line");

        form.setFkeys("F3=Back F5=Copy");
        assertThat(form.getFkeys()).isEqualTo("F3=Back F5=Copy");

        form.setFkey05("F5=Copy");
        assertThat(form.getFkey05()).isEqualTo("F5=Copy");

        form.setFkey12("F12=Cancel");
        assertThat(form.getFkey12()).isEqualTo("F12=Cancel");
    }

    /**
     * The five monetary display fields are carried verbatim as {@code String} (grouping commas,
     * decimal point, and a leading minus are all retained). This proves the screen form never
     * parses these amounts into a numeric type; {@code java.math.BigDecimal} and floating-point
     * types are intentionally absent from this test.
     */
    @Test
    void money_fields_are_carried_as_string() {
        COACTUPForm form = new COACTUPForm();

        form.setAcrdlim("9,999,999.99");
        form.setAcshlim("1,234.56");
        form.setAcurbal("-2,000.00");
        form.setAcrcycr("0.00");
        form.setAcrcydb("12,345.67");

        assertThat(form.getAcrdlim()).isEqualTo("9,999,999.99");
        assertThat(form.getAcshlim()).isEqualTo("1,234.56");
        assertThat(form.getAcurbal()).isEqualTo("-2,000.00");
        assertThat(form.getAcrcycr()).isEqualTo("0.00");
        assertThat(form.getAcrcydb()).isEqualTo("12,345.67");
    }

    /**
     * The three split SSN parts must never leak through {@code toString()}.
     *
     * <p>Discrepancy note (PRODUCTION FORM WINS): the agent-prompt guidance recommends that
     * {@code toString()} render a masked SSN ("***") and still expose a non-PII field. The
     * production {@link COACTUPForm#toString()} does neither: per review finding F9 / CWE-532 it
     * emits ONLY the class name plus an opaque identity token and renders no field values at all.
     * These assertions therefore verify the actual, stronger guarantee - that the representation
     * is structurally just the class name and a hexadecimal identity, so no field (SSN or
     * otherwise) can appear.</p>
     *
     * <p>The SSN sentinels below each contain the letter {@code Z}, which cannot occur in either
     * the fixed {@code "COACTUPForm@"} prefix or the lowercase-hexadecimal identity token, so the
     * absence checks cannot be defeated by an incidental substring of the identity hash. Each
     * sentinel also stays within its {@code @Size} bound.</p>
     */
    @Test
    void toString_masks_ssn_parts() {
        COACTUPForm form = new COACTUPForm();
        form.setActssn1("9Z9");
        form.setActssn2("Z8");
        form.setActssn3("7Z7Z");
        form.setAcctsid("00000000001");

        String repr = form.toString();

        assertThat(repr).startsWith("COACTUPForm@");
        assertThat(repr).matches("COACTUPForm@[0-9a-f]+");
        assertThat(repr).doesNotContain("9Z9", "Z8", "7Z7Z");
        assertThat(repr).doesNotContain("00000000001");

        // The split-part SSN properties exist and round-trip, documenting the update screen's
        // split-field structure (three SSN parts) versus the read-only account-view screen.
        assertThat(form.getActssn1()).isEqualTo("9Z9");
        assertThat(form.getActssn2()).isEqualTo("Z8");
        assertThat(form.getActssn3()).isEqualTo("7Z7Z");
    }

    /**
     * Builds a form whose every one of the 54 properties is populated at or under its
     * {@code @Size} maximum, so the form as a whole is valid.
     *
     * @return a fully populated, constraint-valid {@link COACTUPForm}
     */
    private static COACTUPForm validForm() {
        COACTUPForm form = new COACTUPForm();
        form.setTrnname("CAUP");
        form.setTitle01("Account Update Screen");
        form.setCurdate("01/02/26");
        form.setPgmname("COACTUPC");
        form.setTitle02("AWS CardDemo");
        form.setCurtime("12:00:00");
        form.setAcctsid("00000000001");
        form.setAcsttus("Y");
        form.setOpnyear("2020");
        form.setOpnmon("01");
        form.setOpnday("15");
        form.setAcrdlim("5000.00");
        form.setExpyear("2025");
        form.setExpmon("12");
        form.setExpday("31");
        form.setAcshlim("1000.00");
        form.setRisyear("2021");
        form.setRismon("06");
        form.setRisday("10");
        form.setAcurbal("1234.56");
        form.setAcrcycr("10.00");
        form.setAaddgrp("GRP0000001");
        form.setAcrcydb("20.00");
        form.setAcstnum("000000001");
        form.setActssn1("123");
        form.setActssn2("45");
        form.setActssn3("6789");
        form.setDobyear("1980");
        form.setDobmon("07");
        form.setDobday("04");
        form.setAcstfco("750");
        form.setAcsfnam("John");
        form.setAcsmnam("Q");
        form.setAcslnam("Public");
        form.setAcsadl1("123 Main Street");
        form.setAcsstte("VA");
        form.setAcsadl2("Suite 100");
        form.setAcszipc("20001");
        form.setAcscity("Arlington");
        form.setAcsctry("USA");
        form.setAcsph1a("703");
        form.setAcsph1b("555");
        form.setAcsph1c("0100");
        form.setAcsgovt("GOVT-ID-0001");
        form.setAcsph2a("202");
        form.setAcsph2b("555");
        form.setAcsph2c("0199");
        form.setAcseftc("EFT0000001");
        form.setAcspflg("Y");
        form.setInfomsg("Informational message");
        form.setErrmsg("Error message line");
        form.setFkeys("F3=Back F5=Copy");
        form.setFkey05("F5=Copy");
        form.setFkey12("F12=Cancel");
        return form;
    }

    /**
     * Asserts that validating {@code form} yields exactly one violation and that it is reported
     * against the given property.
     *
     * @param form     the form to validate
     * @param property the property path expected to carry the sole violation
     */
    private static void assertExactlyOneSizeViolationOn(COACTUPForm form, String property) {
        Set<ConstraintViolation<COACTUPForm>> violations = validator.validate(form);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo(property);
    }
}
