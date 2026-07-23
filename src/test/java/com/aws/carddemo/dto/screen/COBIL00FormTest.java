package com.aws.carddemo.dto.screen;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link COBIL00Form}. Oracle: legacy/cpy-bms/COBIL00.CPY (BMS mapset COBIL00, map COBIL0A).
 *
 * <p>This is a pure JUnit 5 (Jupiter) unit test in the COBOL&#8594;Java/Spring Boot migration of
 * AWS CardDemo. It exercises the bill-payment screen-form DTO
 * {@link com.aws.carddemo.dto.screen.COBIL00Form} &mdash; the plain-Java translation of the BMS
 * symbolic map copybook {@code COBIL00.CPY} (map {@code COBIL0A}) used by CICS transaction
 * {@code CB00} / program {@code COBIL00C}. There is deliberately <em>no</em> database, <em>no</em>
 * Spring application context and <em>no</em> Testcontainers here: the class name ends in
 * {@code Test}, so it runs under the Maven Surefire plugin as a fast, isolated unit test, and the
 * Jakarta Bean Validation {@link Validator} is built programmatically rather than injected.</p>
 *
 * <p>The behaviors locked down here, in order of importance, are:</p>
 * <ol>
 *   <li>The 10 BMS value fields of map {@code COBIL0A} are all present as {@code String}
 *       properties, each carrying a Jakarta {@code @Size(max = n)} matching the BMS
 *       {@code PIC X(n)} width, and an over-length value violates {@code @Size} on the exact
 *       offending property.</li>
 *   <li>The current-balance field {@code curbal} is carried as a display-formatted
 *       {@link String} (never {@code BigDecimal}, {@code double} or {@code float}), so the
 *       on-screen presentation is preserved byte-for-byte; {@code java.math.BigDecimal} is
 *       intentionally not imported or referenced anywhere in this test.</li>
 *   <li>Every property round-trips through its getter/setter, and the bill-payment screen (unlike
 *       the signon map {@code COSGN00}) declares no password/credential field.</li>
 * </ol>
 *
 * <p>Field table verified against the production form and the BMS oracle (they agree):</p>
 * <pre>
 *   BMS field   PIC       Java property   {@code @Size(max)}
 *   TRNNAME     X(4)      trnname          4
 *   TITLE01     X(40)     title01         40
 *   CURDATE     X(8)      curdate          8
 *   PGMNAME     X(8)      pgmname          8
 *   TITLE02     X(40)     title02         40
 *   CURTIME     X(8)      curtime          8
 *   ACTIDIN     X(11)     actidin         11
 *   CURBAL      X(14)     curbal          14  (display String, NOT BigDecimal)
 *   CONFIRM     X(1)      confirm          1
 *   ERRMSG      X(78)     errmsg          78
 * </pre>
 */
@DisplayName("COBIL00Form DTO (COBIL00.CPY / map COBIL0A)")
class COBIL00FormTest {

    /** Bean Validation factory, created once for the whole class (no Spring). */
    private static ValidatorFactory factory;

    /** Validator obtained from {@link #factory}; used to evaluate the {@code @Size} constraints. */
    private static Validator validator;

    /**
     * Builds the Jakarta Bean Validation {@link Validator} programmatically. The provider
     * (Hibernate Validator, on the test classpath transitively via
     * {@code spring-boot-starter-validation}) is discovered through the standard service loader, so
     * no Spring context is required.
     */
    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    /** Releases the validator factory allocated in {@link #setUpValidator()}. */
    @AfterAll
    static void tearDownValidator() {
        if (factory != null) {
            factory.close();
        }
    }

    // ---------------------------------------------------------------------------------
    // 1. A fully populated, within-limits form produces no constraint violations.
    // ---------------------------------------------------------------------------------

    @Test
    void valid_form_has_no_violations() {
        assertThat(validator.validate(validForm())).isEmpty();
    }

    // ---------------------------------------------------------------------------------
    // 2. One character over the BMS width violates @Size on the exact offending property.
    //    A fresh, otherwise-valid form is used per field so exactly one violation is expected.
    // ---------------------------------------------------------------------------------

    @Test
    void oversize_fields_violate_size_on_exact_property() {
        // ACTIDIN PIC X(11): 12 characters is one over the limit.
        COBIL00Form overActidin = validForm();
        overActidin.setActidin("9".repeat(12));
        assertSingleSizeViolationOn(overActidin, "actidin");

        // CURBAL PIC X(14): 15 characters is one over the limit.
        COBIL00Form overCurbal = validForm();
        overCurbal.setCurbal("9".repeat(15));
        assertSingleSizeViolationOn(overCurbal, "curbal");

        // CONFIRM PIC X(1): 2 characters is one over the limit.
        COBIL00Form overConfirm = validForm();
        overConfirm.setConfirm("99");
        assertSingleSizeViolationOn(overConfirm, "confirm");

        // ERRMSG PIC X(78): 79 characters is one over the limit.
        COBIL00Form overErrmsg = validForm();
        overErrmsg.setErrmsg("9".repeat(79));
        assertSingleSizeViolationOn(overErrmsg, "errmsg");
    }

    // ---------------------------------------------------------------------------------
    // 3. Every property round-trips through its getter/setter (all 10 fields, distinct values).
    // ---------------------------------------------------------------------------------

    @Test
    void getters_return_set_values() {
        COBIL00Form form = new COBIL00Form();

        form.setTrnname("CB00");
        form.setTitle01("First Title Line Value");
        form.setCurdate("01/02/03");
        form.setPgmname("COBIL00C");
        form.setTitle02("Second Title Line Val");
        form.setCurtime("23:59:59");
        form.setActidin("00000000042");
        form.setCurbal("0000001234.56");
        form.setConfirm("N");
        form.setErrmsg("Insufficient funds");

        assertThat(form.getTrnname()).isEqualTo("CB00");
        assertThat(form.getTitle01()).isEqualTo("First Title Line Value");
        assertThat(form.getCurdate()).isEqualTo("01/02/03");
        assertThat(form.getPgmname()).isEqualTo("COBIL00C");
        assertThat(form.getTitle02()).isEqualTo("Second Title Line Val");
        assertThat(form.getCurtime()).isEqualTo("23:59:59");
        assertThat(form.getActidin()).isEqualTo("00000000042");
        assertThat(form.getCurbal()).isEqualTo("0000001234.56");
        assertThat(form.getConfirm()).isEqualTo("N");
        assertThat(form.getErrmsg()).isEqualTo("Insufficient funds");
    }

    // ---------------------------------------------------------------------------------
    // 4. CURBAL is carried as a display String, never a numeric type.
    //    The value below contains a currency symbol, a grouping comma and a decimal point;
    //    a BigDecimal/double/float could not hold that presentation verbatim. This test
    //    deliberately references no numeric money type (no java.math.BigDecimal import).
    // ---------------------------------------------------------------------------------

    @Test
    void curbal_is_carried_as_string() {
        COBIL00Form form = new COBIL00Form();

        String displayBalance = "$1,234.56";
        form.setCurbal(displayBalance);

        // The setter stored and the getter returns the exact display string, proving the money
        // field is a String at the form layer (not a parsed/normalized numeric value).
        assertThat(form.getCurbal()).isEqualTo(displayBalance);
        assertThat(form.getCurbal()).isEqualTo("$1,234.56");
    }

    // ---------------------------------------------------------------------------------
    // 5. Per-form specifics: CONFIRM is a single character (violates at 2); the bill-pay
    //    screen declares no password/credential field.
    // ---------------------------------------------------------------------------------

    @Test
    void confirm_max_is_one() {
        // A single character (the Y/N flag) is valid.
        COBIL00Form oneChar = validForm();
        oneChar.setConfirm("Y");
        assertThat(validator.validate(oneChar)).isEmpty();

        // Two characters breach the PIC X(1) width.
        COBIL00Form twoChars = validForm();
        twoChars.setConfirm("NO");
        assertSingleSizeViolationOn(twoChars, "confirm");
    }

    @Test
    void form_declares_the_ten_bms_fields_and_no_password_field() {
        List<String> fieldNames = new ArrayList<>();
        for (Field field : COBIL00Form.class.getDeclaredFields()) {
            // Skip synthetic members such as the JaCoCo {@code $jacocoData} field injected during
            // coverage runs, so the assertion is stable under instrumentation.
            if (field.isSynthetic()) {
                continue;
            }
            fieldNames.add(field.getName());
        }

        // The hidden single-use confirmation nonce (review finding F12) is a deliberate non-BMS
        // control field (it restores the BMS protected-confirmation-field contract over HTTP), not
        // part of the COBIL0A map. Assert it is present, then exclude it from the BMS-field check.
        assertThat(fieldNames).contains("confirmToken");
        List<String> bmsFieldNames = new ArrayList<>(fieldNames);
        bmsFieldNames.remove("confirmToken");

        // The ERRMSG colour attribute {@code errmsgColor} is the migration of the BMS ERRMSGC colour
        // byte (review finding #11) - a render-only sibling of {@code errmsg}, not one of the ten
        // BMS value fields (it carries no PIC width / @Size). Assert it is present, then exclude it.
        assertThat(fieldNames).contains("errmsgColor");
        bmsFieldNames.remove("errmsgColor");

        // Field presence: exactly the 10 BMS value fields of map COBIL0A, no more, no fewer.
        assertThat(bmsFieldNames).containsExactlyInAnyOrder(
                "trnname", "title01", "curdate", "pgmname", "title02",
                "curtime", "actidin", "curbal", "confirm", "errmsg");

        // The bill-payment screen (unlike the signon map COSGN00) carries no credential input;
        // assert the absence of any password-style field explicitly.
        assertThat(fieldNames).doesNotContain("password", "passwd", "pwd", "secret");
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    /**
     * Builds a fully populated {@link COBIL00Form} whose every property is at or under its BMS
     * {@code @Size(max)} width, so that a pristine instance yields zero constraint violations.
     *
     * @return a valid, within-limits bill-payment form
     */
    private COBIL00Form validForm() {
        COBIL00Form form = new COBIL00Form();
        form.setTrnname("CB00");            // <= 4
        form.setTitle01("AWS CardDemo");    // <= 40
        form.setCurdate("07/19/26");        // == 8
        form.setPgmname("COBIL00C");        // == 8
        form.setTitle02("Bill Payment");    // <= 40
        form.setCurtime("12:34:56");        // == 8
        form.setActidin("00000000001");     // == 11
        form.setCurbal("0000000100.00");    // 13 <= 14 (display-formatted balance)
        form.setConfirm("Y");               // == 1
        form.setErrmsg("");                 // 0 <= 78 (no error)
        return form;
    }

    /**
     * Asserts that validating {@code form} yields exactly one constraint violation and that the
     * violation is reported against {@code expectedProperty}.
     *
     * @param form             the form to validate
     * @param expectedProperty the exact property name expected to carry the single violation
     */
    private void assertSingleSizeViolationOn(COBIL00Form form, String expectedProperty) {
        Set<ConstraintViolation<COBIL00Form>> violations = validator.validate(form);
        assertThat(violations).hasSize(1);

        ConstraintViolation<COBIL00Form> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo(expectedProperty);
    }
}
