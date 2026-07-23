package com.aws.carddemo.dto.screen;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link COSGN00Form}. Oracle: legacy/cpy-bms/COSGN00.CPY
 * (BMS mapset COSGN00, map COSGN0A).
 *
 * <p>This is a pure JUnit 5 (Jupiter) + AssertJ unit test in the COBOL-to-Java /
 * Spring Boot migration of AWS CardDemo. It exercises the plain Spring MVC
 * form-backing bean {@link COSGN00Form} - the translation of the sign-on BMS map
 * {@code COSGN0A} - with no Spring context, no database, and no Testcontainers.
 * A Jakarta Bean Validation {@link Validator} is built programmatically (Hibernate
 * Validator, supplied transitively by {@code spring-boot-starter-validation}) so
 * the {@code @Size} field-length edits that reproduce the legacy 24x80 BMS field
 * contract can be verified in isolation.</p>
 *
 * <p>The behaviors locked down here, in order of importance, are:</p>
 * <ol>
 *   <li>A fully populated, in-bounds form produces zero constraint violations.</li>
 *   <li>Each over-length value raises a {@code @Size} violation on the exact
 *       offending property path (including the {@code passwd} entry field).</li>
 *   <li>Every one of the 11 String properties round-trips through its
 *       getter/setter pair.</li>
 *   <li>The {@code curtime} field uniquely accepts nine characters
 *       ({@code CURTIMEI PIC X(9)}), unlike the eight-character header fields on
 *       the other screens - the tenth character violates the bound.</li>
 *   <li>{@link COSGN00Form#toString()} masks the cleartext password so it can
 *       never leak into logs or diagnostics.</li>
 * </ol>
 *
 * <p>All expectations assert what the production {@link COSGN00Form} class
 * actually declares (property names and {@code @Size} maxima); the BMS copybook
 * is the oracle, but where the two could differ the production form is
 * authoritative.</p>
 */
@DisplayName("COSGN00Form DTO (COSGN00.CPY / map COSGN0A)")
class COSGN00FormTest {

    /** Bean Validation factory, built once for the class and closed in {@link #tearDownValidator()}. */
    private static ValidatorFactory factory;

    /** Validator obtained from {@link #factory}; used by every constraint assertion. */
    private static Validator validator;

    /**
     * Builds the default (Hibernate) validator factory once for the whole class.
     * Must be {@code static} because the suite runs with the default per-method
     * test-instance lifecycle.
     */
    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    /**
     * Releases the validator factory after all tests in the class have run.
     */
    @AfterAll
    static void tearDownValidator() {
        factory.close();
    }

    /**
     * Creates a fully populated form whose 11 properties are all at or under their
     * {@code @Size} maxima, so it is expected to produce no constraint violations.
     * Individual tests mutate a single field to drive the specific behavior under
     * test.
     *
     * @return a valid {@link COSGN00Form} instance
     */
    private COSGN00Form validForm() {
        COSGN00Form form = new COSGN00Form();
        form.setTrnname("CC00");                 // <= 4
        form.setTitle01("AWS CardDemo Sign On");  // <= 40
        form.setCurdate("01/23/24");             // <= 8
        form.setPgmname("COSGN00C");             // <= 8
        form.setTitle02("Sign On Screen");        // <= 40
        form.setCurtime("12:00:00");             // <= 9 (8 chars; max is 9)
        form.setApplid("CICSAWS1");              // <= 8
        form.setSysid("AWS1");                   // <= 8
        form.setUserid("USER0001");              // <= 8
        form.setPasswd("secret12");              // <= 8
        form.setErrmsg("");                      // <= 78
        return form;
    }

    // ---------------------------------------------------------------------------------
    // 1 - A valid, fully populated form has no violations.
    // ---------------------------------------------------------------------------------

    @Test
    void valid_form_has_no_violations() {
        assertThat(validator.validate(validForm())).isEmpty();
    }

    // ---------------------------------------------------------------------------------
    // 2 - Over-length values raise @Size violations on the exact offending property.
    // ---------------------------------------------------------------------------------

    @Test
    void oversize_fields_violate_size_on_exact_property() {
        COSGN00Form form = validForm();
        form.setTrnname("CC000");            // 5 > max 4
        form.setUserid("USER00012");         // 9 > max 8
        form.setPasswd("secret123");         // 9 > max 8
        form.setErrmsg("X".repeat(79));      // 79 > max 78
        form.setCurtime("123456789A");       // 10 > max 9 (screen-specific X(9) bound)

        Set<ConstraintViolation<COSGN00Form>> violations = validator.validate(form);

        assertThat(violations).anyMatch(cv -> cv.getPropertyPath().toString().equals("trnname"));
        assertThat(violations).anyMatch(cv -> cv.getPropertyPath().toString().equals("userid"));
        assertThat(violations).anyMatch(cv -> cv.getPropertyPath().toString().equals("passwd"));
        assertThat(violations).anyMatch(cv -> cv.getPropertyPath().toString().equals("errmsg"));
        assertThat(violations).anyMatch(cv -> cv.getPropertyPath().toString().equals("curtime"));
    }

    // ---------------------------------------------------------------------------------
    // 3 - Every property round-trips through its getter/setter pair (all 11 fields).
    // ---------------------------------------------------------------------------------

    @Test
    void getters_return_set_values() {
        COSGN00Form form = new COSGN00Form();
        form.setTrnname("CC00");
        form.setTitle01("Title One");
        form.setCurdate("01/23/24");
        form.setPgmname("COSGN00C");
        form.setTitle02("Title Two");
        form.setCurtime("12:00:00");
        form.setApplid("CICSAWS1");
        form.setSysid("AWS1");
        form.setUserid("USER0001");
        form.setPasswd("secret12");
        form.setErrmsg("No errors");

        assertThat(form.getTrnname()).isEqualTo("CC00");
        assertThat(form.getTitle01()).isEqualTo("Title One");
        assertThat(form.getCurdate()).isEqualTo("01/23/24");
        assertThat(form.getPgmname()).isEqualTo("COSGN00C");
        assertThat(form.getTitle02()).isEqualTo("Title Two");
        assertThat(form.getCurtime()).isEqualTo("12:00:00");
        assertThat(form.getApplid()).isEqualTo("CICSAWS1");
        assertThat(form.getSysid()).isEqualTo("AWS1");
        assertThat(form.getUserid()).isEqualTo("USER0001");
        assertThat(form.getPasswd()).isEqualTo("secret12");
        assertThat(form.getErrmsg()).isEqualTo("No errors");
    }

    // ---------------------------------------------------------------------------------
    // 5a - curtime is X(9) on this screen only: 9 chars accepted, 10 chars rejected.
    // ---------------------------------------------------------------------------------

    @Test
    void curtime_accepts_nine_chars() {
        COSGN00Form form = validForm();

        form.setCurtime("123456789");   // exactly 9 chars -> allowed by @Size(max = 9)
        assertThat(validator.validate(form))
                .noneMatch(cv -> cv.getPropertyPath().toString().equals("curtime"));

        form.setCurtime("1234567890");  // 10 chars -> violates @Size(max = 9)
        assertThat(validator.validate(form))
                .anyMatch(cv -> cv.getPropertyPath().toString().equals("curtime"));
    }

    // ---------------------------------------------------------------------------------
    // 5b - toString() masks the cleartext password but keeps other fields visible.
    // ---------------------------------------------------------------------------------

    @Test
    void toString_masks_password() {
        COSGN00Form form = validForm();
        form.setUserid("USER0001");
        form.setPasswd("TOPSECRET");

        String rendered = form.toString();

        assertThat(rendered).doesNotContain("TOPSECRET");
        assertThat(rendered).contains("***");
        assertThat(rendered).contains("USER0001");
    }
}
