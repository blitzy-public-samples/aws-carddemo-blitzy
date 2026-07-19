package com.aws.carddemo.dto.screen;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link COCRDSLForm}. Oracle: legacy/cpy-bms/COCRDSL.CPY
 * (BMS mapset COCRDSL, map CCRDSLA).
 *
 * <p>These are pure JUnit 5 (Jupiter) + AssertJ unit tests for the server-side
 * form-backing bean of the AWS CardDemo card-detail / view screen (COBOL&rarr;Java
 * migration). They verify the Jakarta Bean Validation {@code @Size} field-length
 * edits that reproduce each BMS {@code PIC X(n)} contract, plus getter/setter
 * round-trips over all 15 {@code String} properties. There is deliberately
 * <strong>no</strong> Spring context, database, or Testcontainers here: the
 * {@link Validator} is built programmatically and the class under test is
 * instantiated with {@code new COCRDSLForm()}.</p>
 *
 * <p><strong>ERRMSG X(80) variance.</strong> On this screen the error-message field
 * is {@code ERRMSGI PIC X(80)} (verified against the BMS oracle at
 * legacy/cpy-bms/COCRDSL.CPY, map {@code CCRDSLA}), and the production form declares
 * {@code errmsg} as {@code @Size(max = 80)}. This is a documented variance shared
 * only with the card-update screen (COCRDUP); most other CardDemo screens use
 * {@code X(78)}. The production form and the AWS CardDemo copybook agree here, so no
 * discrepancy exists; {@link #errmsg_accepts_eighty_chars()} locks the 80-character
 * boundary. Should production and the copybook ever disagree, these tests assert what
 * the production class declares (production wins).</p>
 */
class COCRDSLFormTest {

    /** Programmatic validator factory (no Spring); closed in {@link #tearDownValidator()}. */
    private static ValidatorFactory factory;

    /** Bean Validation validator used by every test. */
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        factory.close();
    }

    // ------------------------------------------------------------------
    // Helper: a fully-populated form whose every property is at or under its
    // @Size(max). Because @Size treats null as valid, populating all 15 fields
    // (rather than leaving them null) makes "valid" a meaningful, exercised state.
    // ------------------------------------------------------------------
    private COCRDSLForm validForm() {
        COCRDSLForm form = new COCRDSLForm();
        form.setTrnname("CDVW");                             // X(4)
        form.setTitle01("AWS CARDDEMO - VIEW CARD DETAILS"); // X(40)
        form.setCurdate("07/18/25");                         // X(8)
        form.setPgmname("COCRDSLC");                         // X(8)
        form.setTitle02("CARD SERVICES");                    // X(40)
        form.setCurtime("14:30:59");                         // X(8)
        form.setAcctsid("00000000011");                      // X(11)
        form.setCardsid("4111111111111111");                 // X(16)
        form.setCrdname("JOHN Q PUBLIC");                    // X(50)
        form.setCrdstcd("Y");                                // X(1)
        form.setExpmon("12");                                // X(2)
        form.setExpyear("2029");                             // X(4)
        form.setInfomsg("PRESS ENTER TO CONTINUE");          // X(40)
        form.setErrmsg("NO ERRORS DETECTED");                // X(80) variance
        form.setFkeys("ENTER=CONT F3=BACK");                 // X(75)
        return form;
    }

    // ==================================================================
    // 1. Valid form => no violations
    // ==================================================================
    @Test
    @DisplayName("A fully-populated, at/under-max form has no constraint violations")
    void valid_form_has_no_violations() {
        Set<ConstraintViolation<COCRDSLForm>> violations = validator.validate(validForm());
        assertThat(violations).isEmpty();
    }

    // ==================================================================
    // 2. One character over @Size(max) => violation on the exact property
    // ==================================================================
    @Test
    @DisplayName("One character over @Size(max) violates on the exact property")
    void oversize_fields_violate_size_on_exact_property() {
        COCRDSLForm form = validForm();
        form.setAcctsid("x".repeat(12)); // max 11
        form.setCardsid("x".repeat(17)); // max 16
        form.setCrdname("x".repeat(51)); // max 50
        form.setFkeys("x".repeat(76));   // max 75
        form.setErrmsg("x".repeat(81));  // max 80 (X(80) variance)

        Set<ConstraintViolation<COCRDSLForm>> violations = validator.validate(form);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("acctsid", "cardsid", "crdname", "fkeys", "errmsg");
    }

    // ==================================================================
    // 3. Getter/setter round-trip over all 15 properties
    // ==================================================================
    @Test
    @DisplayName("Every getter returns the value set through its setter (all 15 properties)")
    void getters_return_set_values() {
        COCRDSLForm form = new COCRDSLForm();
        form.setTrnname("CDVW");
        form.setTitle01("TITLE ONE");
        form.setCurdate("07/18/25");
        form.setPgmname("COCRDSLC");
        form.setTitle02("TITLE TWO");
        form.setCurtime("14:30:59");
        form.setAcctsid("00000000011");
        form.setCardsid("4111111111111111");
        form.setCrdname("JOHN Q PUBLIC");
        form.setCrdstcd("Y");
        form.setExpmon("12");
        form.setExpyear("2029");
        form.setInfomsg("INFO LINE");
        form.setErrmsg("ERROR LINE");
        form.setFkeys("ENTER=CONT F3=BACK");

        assertThat(form.getTrnname()).isEqualTo("CDVW");
        assertThat(form.getTitle01()).isEqualTo("TITLE ONE");
        assertThat(form.getCurdate()).isEqualTo("07/18/25");
        assertThat(form.getPgmname()).isEqualTo("COCRDSLC");
        assertThat(form.getTitle02()).isEqualTo("TITLE TWO");
        assertThat(form.getCurtime()).isEqualTo("14:30:59");
        assertThat(form.getAcctsid()).isEqualTo("00000000011");
        assertThat(form.getCardsid()).isEqualTo("4111111111111111");
        assertThat(form.getCrdname()).isEqualTo("JOHN Q PUBLIC");
        assertThat(form.getCrdstcd()).isEqualTo("Y");
        assertThat(form.getExpmon()).isEqualTo("12");
        assertThat(form.getExpyear()).isEqualTo("2029");
        assertThat(form.getInfomsg()).isEqualTo("INFO LINE");
        assertThat(form.getErrmsg()).isEqualTo("ERROR LINE");
        assertThat(form.getFkeys()).isEqualTo("ENTER=CONT F3=BACK");
    }

    // ==================================================================
    // 4. money-as-String: N/A for this screen (no monetary fields).
    // ==================================================================

    // ==================================================================
    // 5. Per-form specific: errmsg X(80) variance boundary
    //    80 chars accepted; 81 chars rejected. Locks the X(80) contract
    //    (shared only with COCRDUP; other screens use X(78)).
    // ==================================================================
    @Test
    @DisplayName("errmsg accepts exactly 80 chars (X(80) variance) and rejects 81")
    void errmsg_accepts_eighty_chars() {
        COCRDSLForm form = validForm();

        form.setErrmsg("x".repeat(80));
        Set<ConstraintViolation<COCRDSLForm>> at80 = validator.validate(form);
        assertThat(at80)
                .extracting(v -> v.getPropertyPath().toString())
                .doesNotContain("errmsg");

        form.setErrmsg("x".repeat(81));
        Set<ConstraintViolation<COCRDSLForm>> at81 = validator.validate(form);
        assertThat(at81)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("errmsg");
    }
}
