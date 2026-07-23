package com.aws.carddemo.dto.screen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link COCRDUPForm}, the card-update screen-form DTO of the AWS
 * CardDemo COBOL&rarr;Java/Spring Boot migration.
 *
 * <p><strong>Oracle:</strong> {@code legacy/cpy-bms/COCRDUP.CPY} (BMS mapset
 * {@code COCRDUP}, map {@code CCRDUPA}). Every modeled property carries a Jakarta
 * {@link jakarta.validation.constraints.Size} upper bound equal to its BMS
 * {@code PIC X(n)} length, preserving the legacy 24&times;80 fixed-width field
 * contract. These tests assert those {@code @Size} bounds, exercise every
 * getter/setter round-trip, and pin the screen-specific field set.</p>
 *
 * <p><strong>Card-update variance.</strong> This screen's error line is
 * {@code ERRMSGI PIC X(80)} &mdash; an 80-byte field, wider than the 78-byte error
 * line found on some other CardDemo screens; {@link #errmsg_accepts_eighty_chars()}
 * proves that boundary. The card-update map also carries fields absent from the
 * card-view map: an expiry day ({@code expday}) and a two-part PF-key legend
 * ({@code fkeys}, {@code fkeysc}); {@link #update_specific_fields_expday_fkeys_fkeysc()}
 * pins their presence and bounds.</p>
 *
 * <p><strong>No money fields.</strong> The card-update screen has no monetary values,
 * so there is deliberately no {@code BigDecimal}/money-as-{@code String} assertion here.</p>
 *
 * <p>This is a plain JUnit 5 (Jupiter) + AssertJ unit test with a programmatically built
 * {@link Validator}: no Spring context, no database, and no Testcontainers. It exercises
 * {@code new COCRDUPForm()} directly.</p>
 */
class COCRDUPFormTest {

    /** Bean-validation factory, created once for the class and closed after all tests. */
    private static ValidatorFactory factory;

    /** Validator obtained from {@link #factory}; shared by every constraint assertion. */
    private static Validator validator;

    /**
     * Builds the shared {@link Validator} from the default provider (Hibernate Validator
     * on the test classpath). No Spring context is involved.
     */
    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    /**
     * Releases the {@link ValidatorFactory} once every test in the class has run.
     */
    @AfterAll
    static void tearDownValidator() {
        factory.close();
    }

    /**
     * Builds a form whose every property is exactly at, or below, its {@code @Size}
     * maximum. Values are deliberately distinct per field so that a mis-wired
     * getter/setter would surface elsewhere in this suite.
     *
     * @return a valid {@link COCRDUPForm} instance
     */
    private static COCRDUPForm validForm() {
        COCRDUPForm form = new COCRDUPForm();
        form.setTrnname("CCUP");                            // X(4)
        form.setTitle01("AWS CardDemo");                    // X(40)
        form.setCurdate("07/18/25");                        // X(8)
        form.setPgmname("COCRDUPC");                        // X(8)
        form.setTitle02("Card Update");                     // X(40)
        form.setCurtime("14:30:00");                        // X(8)
        form.setAcctsid("00000000011");                     // X(11)
        form.setCardsid("4111111111111111");                // X(16)
        form.setCrdname("JOHN Q PUBLIC");                   // X(50)
        form.setCrdstcd("Y");                               // X(1)
        form.setExpmon("12");                               // X(2)
        form.setExpyear("2027");                            // X(4)
        form.setExpday("31");                               // X(2)
        form.setInfomsg("Enter changes and press ENTER");   // X(40)
        form.setErrmsg("Account ID must be numeric");       // X(80)
        form.setFkeys("F3=Back F5=Save");                   // X(21)
        form.setFkeysc("F12=Cancel");                       // X(18)
        return form;
    }

    /**
     * Asserts that validating {@code form} yields exactly one {@code @Size} violation and
     * that it is reported against the {@code property} bean-property path.
     *
     * @param form     the form under test
     * @param property the exact bean-property name expected to be in violation
     */
    private static void assertSingleSizeViolationOn(COCRDUPForm form, String property) {
        Set<ConstraintViolation<COCRDUPForm>> violations = validator.validate(form);
        assertThat(violations)
                .as("exactly one @Size violation expected on property '%s'", property)
                .hasSize(1)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly(property);
    }

    /**
     * A form with every field at or under its maximum length produces no constraint
     * violations.
     */
    @Test
    void valid_form_has_no_violations() {
        assertThat(validator.validate(validForm())).isEmpty();
    }

    /**
     * Overrunning any single field's {@code @Size} maximum by exactly one character
     * produces exactly one violation, reported against that field's own property. A
     * representative field from each length class is exercised, including the wide
     * {@code errmsg} at 81 characters (one past its X(80) maximum).
     */
    @Test
    void oversize_fields_violate_size_on_exact_property() {
        COCRDUPForm acctOver = validForm();
        acctOver.setAcctsid("x".repeat(12));    // max 11
        assertSingleSizeViolationOn(acctOver, "acctsid");

        COCRDUPForm cardOver = validForm();
        cardOver.setCardsid("x".repeat(17));    // max 16
        assertSingleSizeViolationOn(cardOver, "cardsid");

        COCRDUPForm dayOver = validForm();
        dayOver.setExpday("x".repeat(3));       // max 2
        assertSingleSizeViolationOn(dayOver, "expday");

        COCRDUPForm fkeysOver = validForm();
        fkeysOver.setFkeys("x".repeat(22));     // max 21
        assertSingleSizeViolationOn(fkeysOver, "fkeys");

        COCRDUPForm fkeyscOver = validForm();
        fkeyscOver.setFkeysc("x".repeat(19));   // max 18
        assertSingleSizeViolationOn(fkeyscOver, "fkeysc");

        COCRDUPForm errOver = validForm();
        errOver.setErrmsg("x".repeat(81));      // max 80 (X(80) variance)
        assertSingleSizeViolationOn(errOver, "errmsg");
    }

    /**
     * Every one of the 17 modeled properties returns, through its getter, the exact value
     * placed through its setter. Distinct values guard against an accessor accidentally
     * wired to the wrong backing field.
     */
    @Test
    void getters_return_set_values() {
        COCRDUPForm form = new COCRDUPForm();

        form.setTrnname("CCUP");
        form.setTitle01("CardDemo Title One");
        form.setCurdate("07/18/25");
        form.setPgmname("COCRDUPC");
        form.setTitle02("CardDemo Title Two");
        form.setCurtime("14:30:00");
        form.setAcctsid("00000000011");
        form.setCardsid("4111111111111111");
        form.setCrdname("JOHN Q PUBLIC");
        form.setCrdstcd("Y");
        form.setExpmon("12");
        form.setExpyear("2027");
        form.setExpday("31");
        form.setInfomsg("Informational line");
        form.setErrmsg("Error line");
        form.setFkeys("F3=Back F5=Save");
        form.setFkeysc("F12=Cancel");

        assertThat(form.getTrnname()).isEqualTo("CCUP");
        assertThat(form.getTitle01()).isEqualTo("CardDemo Title One");
        assertThat(form.getCurdate()).isEqualTo("07/18/25");
        assertThat(form.getPgmname()).isEqualTo("COCRDUPC");
        assertThat(form.getTitle02()).isEqualTo("CardDemo Title Two");
        assertThat(form.getCurtime()).isEqualTo("14:30:00");
        assertThat(form.getAcctsid()).isEqualTo("00000000011");
        assertThat(form.getCardsid()).isEqualTo("4111111111111111");
        assertThat(form.getCrdname()).isEqualTo("JOHN Q PUBLIC");
        assertThat(form.getCrdstcd()).isEqualTo("Y");
        assertThat(form.getExpmon()).isEqualTo("12");
        assertThat(form.getExpyear()).isEqualTo("2027");
        assertThat(form.getExpday()).isEqualTo("31");
        assertThat(form.getInfomsg()).isEqualTo("Informational line");
        assertThat(form.getErrmsg()).isEqualTo("Error line");
        assertThat(form.getFkeys()).isEqualTo("F3=Back F5=Save");
        assertThat(form.getFkeysc()).isEqualTo("F12=Cancel");
    }

    /**
     * The card-update error line accepts a full 80 characters ({@code ERRMSGI PIC X(80)}):
     * an 80-character value is valid, while 81 characters is rejected with a single
     * violation on {@code errmsg}. This pins the X(80) variance that distinguishes this
     * screen from the 78-byte error line used elsewhere in CardDemo.
     */
    @Test
    void errmsg_accepts_eighty_chars() {
        COCRDUPForm atMax = validForm();
        atMax.setErrmsg("x".repeat(80));
        assertThat(validator.validate(atMax))
                .as("errmsg of exactly 80 chars must be valid (X(80))")
                .isEmpty();

        COCRDUPForm overMax = validForm();
        overMax.setErrmsg("x".repeat(81));
        assertSingleSizeViolationOn(overMax, "errmsg");
    }

    /**
     * Pins the fields that distinguish the card-update screen from the card-view screen:
     * the expiry day ({@code expday}, X(2)) and the two-part PF-key legend
     * ({@code fkeys}, X(21); {@code fkeysc}, X(18)). Each must exist, round-trip through
     * its accessor, and accept a value of exactly its maximum length without violation.
     */
    @Test
    void update_specific_fields_expday_fkeys_fkeysc() {
        COCRDUPForm form = new COCRDUPForm();
        form.setExpday("31");
        form.setFkeys("F3=Back  F5=Save");
        form.setFkeysc("F12=Cancel");

        assertThat(form.getExpday()).isEqualTo("31");
        assertThat(form.getFkeys()).isEqualTo("F3=Back  F5=Save");
        assertThat(form.getFkeysc()).isEqualTo("F12=Cancel");

        COCRDUPForm atMax = validForm();
        atMax.setExpday("x".repeat(2));     // X(2)
        atMax.setFkeys("x".repeat(21));     // X(21)
        atMax.setFkeysc("x".repeat(18));    // X(18)
        assertThat(validator.validate(atMax))
                .as("expday(2)/fkeys(21)/fkeysc(18) at their maxima must all be valid")
                .isEmpty();
    }
}
