package com.aws.carddemo.dto.screen;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link COUSR02Form}. Oracle: legacy/cpy-bms/COUSR02.CPY
 * (BMS mapset COUSR02, map COUSR2A).
 *
 * <p><strong>What is verified.</strong> {@code COUSR02Form} is the Spring MVC form-backing
 * bean for the user-update (admin) screen (CICS transaction {@code CU02}, program
 * {@code COUSR02C}). These tests lock down the migrated field contract: the Jakarta
 * {@code @Size(max = ...)} upper bounds that reproduce the legacy BMS field-length edits, the
 * getter/setter round-trip of every one of the 12 {@code String} properties, and the
 * security-hygiene guarantee that the cleartext {@code passwd} never leaks through
 * {@link COUSR02Form#toString()} (review finding F9 / CWE-532).</p>
 *
 * <p><strong>COBOL field oracle</strong> (symbolic input map {@code COUSR2AI}); the production
 * {@code @Size(max)} values below were verified against both the copybook and the production
 * form, and all three agree:</p>
 * <pre>
 *   BMS field   PIC     Java property   @Size(max)
 *   TRNNAME     X(4)    trnname          4
 *   TITLE01     X(40)   title01         40
 *   CURDATE     X(8)    curdate          8
 *   PGMNAME     X(8)    pgmname          8
 *   TITLE02     X(40)   title02         40
 *   CURTIME     X(8)    curtime          8
 *   USRIDIN     X(8)    usridin          8   (user id to update - the search key)
 *   FNAME       X(20)   fname           20
 *   LNAME       X(20)   lname           20
 *   PASSWD      X(8)    passwd           8   (cleartext - masked in toString())
 *   USRTYPE     X(1)    usrtype          1
 *   ERRMSG      X(78)   errmsg          78
 * </pre>
 *
 * <p><strong>Pure unit test.</strong> The Jakarta {@link Validator} is built programmatically
 * (no Spring context, no database, no Testcontainers, no Docker); the class ends in
 * {@code Test} so it runs under Maven Surefire as a fast, isolated unit test. The class under
 * test is instantiated directly via {@code new COUSR02Form()}. There are no monetary fields,
 * so no {@code BigDecimal}/floating-point assertions apply here.</p>
 */
class COUSR02FormTest {

    /**
     * Known cleartext secret used to prove {@link COUSR02Form#toString()} never leaks the
     * password. Deliberately 9 characters (one over the {@code passwd} max of 8): the setter
     * performs no validation, and this test does not validate the form, so the length is
     * irrelevant here - it only has to be a distinctive, greppable token.
     */
    private static final String SECRET_PASSWORD = "UPDSECRET";

    /**
     * Known search-key user id (BMS {@code USRIDINI}). On this screen the user-id property is
     * {@code usridin} (the id being updated), NOT {@code userid} - this distinguishes
     * COUSR02 (user update) from COUSR01 (user add).
     */
    private static final String SEARCH_KEY_USRIDIN = "UPDUSER1";

    /** Shared validator factory; closed in {@link #closeValidator()}. */
    private static ValidatorFactory factory;

    /** Jakarta Bean Validation validator used to exercise the {@code @Size} constraints. */
    private static Validator validator;

    /**
     * Builds the default Jakarta Bean Validation factory and validator once for the class.
     * This uses the Hibernate Validator provider on the test classpath; no Spring context is
     * involved.
     */
    @BeforeAll
    static void buildValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    /** Releases the validator factory after all tests in the class have run. */
    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    /**
     * A form whose every property is populated at exactly its {@code @Size(max)} length is
     * valid: the boundary length is allowed, so validation yields no violations.
     */
    @Test
    @DisplayName("form with all 12 fields at their max length has no @Size violations")
    void valid_form_has_no_violations() {
        assertThat(validator.validate(validForm()))
                .as("all-at-max form must produce zero @Size violations")
                .isEmpty();
    }

    /**
     * A value one character over a field's {@code @Size(max)} must raise a violation on
     * exactly that property (and no other). Each of the six data-entry fields is exercised
     * from an otherwise-valid form so the single reported violation is unambiguous, covering
     * the parity-critical {@code usridin} search key and the cleartext {@code passwd}.
     */
    @Test
    @DisplayName("one char over @Size(max) violates exactly that property")
    void oversize_fields_violate_size_on_exact_property() {
        COUSR02Form usridinOver = validForm();
        usridinOver.setUsridin("X".repeat(9));
        assertThat(singleViolationProperty(usridinOver)).isEqualTo("usridin");

        COUSR02Form fnameOver = validForm();
        fnameOver.setFname("X".repeat(21));
        assertThat(singleViolationProperty(fnameOver)).isEqualTo("fname");

        COUSR02Form lnameOver = validForm();
        lnameOver.setLname("X".repeat(21));
        assertThat(singleViolationProperty(lnameOver)).isEqualTo("lname");

        COUSR02Form passwdOver = validForm();
        passwdOver.setPasswd("X".repeat(9));
        assertThat(singleViolationProperty(passwdOver)).isEqualTo("passwd");

        COUSR02Form usrtypeOver = validForm();
        usrtypeOver.setUsrtype("X".repeat(2));
        assertThat(singleViolationProperty(usrtypeOver)).isEqualTo("usrtype");

        COUSR02Form errmsgOver = validForm();
        errmsgOver.setErrmsg("X".repeat(79));
        assertThat(singleViolationProperty(errmsgOver)).isEqualTo("errmsg");
    }

    /**
     * Every one of the 12 properties round-trips through its setter and getter. Distinct
     * values are used per field so a copy/paste swap between accessors would be caught.
     */
    @Test
    @DisplayName("all 12 properties round-trip through setters and getters")
    void getters_return_set_values() {
        COUSR02Form form = new COUSR02Form();

        form.setTrnname("CU02");
        form.setTitle01("User Update Screen Title One");
        form.setCurdate("07/18/22");
        form.setPgmname("COUSR02C");
        form.setTitle02("User Update Screen Title Two");
        form.setCurtime("09:05:03");
        form.setUsridin(SEARCH_KEY_USRIDIN);
        form.setFname("Firstname");
        form.setLname("Lastname");
        form.setPasswd("PW345678");
        form.setUsrtype("A");
        form.setErrmsg("Sample error message line");

        assertThat(form.getTrnname()).isEqualTo("CU02");
        assertThat(form.getTitle01()).isEqualTo("User Update Screen Title One");
        assertThat(form.getCurdate()).isEqualTo("07/18/22");
        assertThat(form.getPgmname()).isEqualTo("COUSR02C");
        assertThat(form.getTitle02()).isEqualTo("User Update Screen Title Two");
        assertThat(form.getCurtime()).isEqualTo("09:05:03");
        assertThat(form.getUsridin()).isEqualTo(SEARCH_KEY_USRIDIN);
        assertThat(form.getFname()).isEqualTo("Firstname");
        assertThat(form.getLname()).isEqualTo("Lastname");
        assertThat(form.getPasswd()).isEqualTo("PW345678");
        assertThat(form.getUsrtype()).isEqualTo("A");
        assertThat(form.getErrmsg()).isEqualTo("Sample error message line");
    }

    /**
     * {@link COUSR02Form#toString()} must never leak the cleartext password (CWE-532 / review
     * finding F9). The form is populated with a known password and search-key user id, then its
     * diagnostic string is checked.
     *
     * <p>PRODUCTION FORM WINS: the production {@code toString()} is the hardened identity-only
     * form ({@code "COUSR02Form@" + identity hash}) and deliberately emits no field values at
     * all. This test therefore locks that actual behavior - and, unlike the agent-prompt's
     * suggested (recommended-only) checks, it does NOT assert {@code .contains("***")} nor that
     * the {@code usridin} value appears in {@code toString()}, because production omits every
     * field. The stronger security property (the raw password never appears) is asserted
     * directly, and the {@code usridin} search-key round-trip is verified via its getter.</p>
     */
    @Test
    @DisplayName("toString() never leaks the cleartext password (identity-only form)")
    void toString_masks_password() {
        COUSR02Form form = new COUSR02Form();
        form.setUsridin(SEARCH_KEY_USRIDIN);
        form.setPasswd(SECRET_PASSWORD);

        String rendered = form.toString();

        assertThat(rendered).as("toString() must never be null").isNotNull();
        assertThat(rendered)
                .as("toString() must NOT contain the cleartext password")
                .doesNotContain(SECRET_PASSWORD);
        assertThat(rendered)
                .as("toString() is the safe identity form for COUSR02Form")
                .startsWith("COUSR02Form@");

        assertThat(form.getUsridin())
                .as("search-key property is usridin (BMS USRIDINI), not userid")
                .isEqualTo(SEARCH_KEY_USRIDIN);
    }

    /**
     * Builds a fully-populated, valid {@link COUSR02Form}: every property is set to a string of
     * exactly its {@code @Size(max)} length (and {@code usrtype} to {@code "A"}, its max of 1),
     * so the form has zero violations and serves as the base for the oversize tests.
     *
     * @return a form with all 12 properties at their maximum permitted length
     */
    private COUSR02Form validForm() {
        COUSR02Form form = new COUSR02Form();
        form.setTrnname("X".repeat(4));
        form.setTitle01("X".repeat(40));
        form.setCurdate("X".repeat(8));
        form.setPgmname("X".repeat(8));
        form.setTitle02("X".repeat(40));
        form.setCurtime("X".repeat(8));
        form.setUsridin("X".repeat(8));
        form.setFname("X".repeat(20));
        form.setLname("X".repeat(20));
        form.setPasswd("X".repeat(8));
        form.setUsrtype("A");
        form.setErrmsg("X".repeat(78));
        return form;
    }

    /**
     * Validates the form, asserts that exactly one {@code @Size} violation was raised, and
     * returns the property path of that single violation.
     *
     * @param form the form to validate (expected to breach exactly one constraint)
     * @return the property name of the single reported violation
     */
    private String singleViolationProperty(COUSR02Form form) {
        Set<ConstraintViolation<COUSR02Form>> violations = validator.validate(form);
        assertThat(violations)
                .as("exactly one @Size violation expected")
                .hasSize(1);
        return violations.iterator().next().getPropertyPath().toString();
    }
}
