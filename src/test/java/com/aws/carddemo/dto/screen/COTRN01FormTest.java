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
 * Unit tests for {@link COTRN01Form}. Oracle: legacy/cpy-bms/COTRN01.CPY
 * (BMS mapset COTRN01, map COTRN1A).
 *
 * <p>Pure JUnit 5 (Jupiter) + AssertJ unit test: no Spring context, no database, and no
 * Testcontainers. A Jakarta Bean Validation {@link jakarta.validation.Validator} is built
 * programmatically so the form's {@code @Size} field-length edits (each mirroring a BMS
 * {@code PIC X(n)} field) can be exercised in isolation via {@code new COTRN01Form()}.</p>
 *
 * <p>The screen carries 21 {@code String} value fields. The monetary field {@code trnamt} is a
 * display-edited {@code String} (never {@code BigDecimal}/{@code float}/{@code double}); the
 * authoritative amount lives on the {@code Transaction} domain entity and is formatted into this
 * display String by the controller. These tests assert the production contract exactly; where the
 * production class and any external table could disagree, the production form is authoritative.</p>
 */
class COTRN01FormTest {

    /** Bean Validation factory, built once for the whole class and closed in {@link #tearDownValidator()}. */
    private static ValidatorFactory factory;

    /** Validator obtained from {@link #factory}; used by every {@code @Size} assertion below. */
    private static Validator validator;

    /**
     * Builds the default Jakarta Bean Validation factory and validator programmatically (no Spring).
     */
    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    /**
     * Releases the validator factory to avoid leaking its resources.
     */
    @AfterAll
    static void tearDownValidator() {
        if (factory != null) {
            factory.close();
        }
    }

    // ------------------------------------------------------------------------------------------
    // 1) Valid form produces no constraint violations
    // ------------------------------------------------------------------------------------------

    /**
     * A form whose 21 fields are all at or under their BMS {@code PIC X(n)} maxima yields no
     * {@code @Size} violations.
     */
    @Test
    void valid_form_has_no_violations() {
        Set<ConstraintViolation<COTRN01Form>> violations = validator.validate(validForm());
        assertThat(violations).isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // 2) Oversize values violate @Size on the exact offending property
    // ------------------------------------------------------------------------------------------

    /**
     * For a representative set of fields, a value one character over the declared maximum produces
     * exactly one {@code @Size} violation, reported on that field's own property path. The amount
     * field {@code trnamt} is included to prove its {@code @Size(max = 12)} edit is enforced.
     */
    @Test
    void oversize_fields_violate_size_on_exact_property() {
        COTRN01Form overTrnid = validForm();
        overTrnid.setTrnid("A".repeat(17));
        assertSingleViolationProperty(overTrnid, "trnid");

        COTRN01Form overCardnum = validForm();
        overCardnum.setCardnum("4".repeat(17));
        assertSingleViolationProperty(overCardnum, "cardnum");

        COTRN01Form overTdesc = validForm();
        overTdesc.setTdesc("D".repeat(61));
        assertSingleViolationProperty(overTdesc, "tdesc");

        COTRN01Form overTrnamt = validForm();
        overTrnamt.setTrnamt("9".repeat(13));
        assertSingleViolationProperty(overTrnamt, "trnamt");

        COTRN01Form overMname = validForm();
        overMname.setMname("M".repeat(31));
        assertSingleViolationProperty(overMname, "mname");

        COTRN01Form overErrmsg = validForm();
        overErrmsg.setErrmsg("E".repeat(79));
        assertSingleViolationProperty(overErrmsg, "errmsg");
    }

    // ------------------------------------------------------------------------------------------
    // 3) Every property round-trips through its setter/getter
    // ------------------------------------------------------------------------------------------

    /**
     * Each of the 21 {@code String} properties returns exactly the value that was set. Distinct
     * values are used throughout (in particular {@code trnidin} vs {@code trnid}) so a silent
     * field cross-wiring during translation would fail the test.
     */
    @Test
    void getters_return_set_values() {
        COTRN01Form form = new COTRN01Form();
        form.setTrnname("CT01");
        form.setTitle01("AWS CardDemo");
        form.setCurdate("07/18/26");
        form.setPgmname("COTRN01C");
        form.setTitle02("View Txn");
        form.setCurtime("09:05:03");
        form.setTrnidin("1111111111111111");
        form.setTrnid("2222222222222222");
        form.setCardnum("4111111111111111");
        form.setTtypcd("01");
        form.setTcatcd("0005");
        form.setTrnsrc("POS");
        form.setTdesc("GROCERY PURCHASE");
        form.setTrnamt("0000123.45");
        form.setTorigdt("2026-07-18");
        form.setTprocdt("2026-07-19");
        form.setMid("123456789");
        form.setMname("ACME FOODS");
        form.setMcity("SEATTLE");
        form.setMzip("98101");
        form.setErrmsg("NO ERRORS");

        assertThat(form.getTrnname()).isEqualTo("CT01");
        assertThat(form.getTitle01()).isEqualTo("AWS CardDemo");
        assertThat(form.getCurdate()).isEqualTo("07/18/26");
        assertThat(form.getPgmname()).isEqualTo("COTRN01C");
        assertThat(form.getTitle02()).isEqualTo("View Txn");
        assertThat(form.getCurtime()).isEqualTo("09:05:03");
        assertThat(form.getTrnidin()).isEqualTo("1111111111111111");
        assertThat(form.getTrnid()).isEqualTo("2222222222222222");
        assertThat(form.getCardnum()).isEqualTo("4111111111111111");
        assertThat(form.getTtypcd()).isEqualTo("01");
        assertThat(form.getTcatcd()).isEqualTo("0005");
        assertThat(form.getTrnsrc()).isEqualTo("POS");
        assertThat(form.getTdesc()).isEqualTo("GROCERY PURCHASE");
        assertThat(form.getTrnamt()).isEqualTo("0000123.45");
        assertThat(form.getTorigdt()).isEqualTo("2026-07-18");
        assertThat(form.getTprocdt()).isEqualTo("2026-07-19");
        assertThat(form.getMid()).isEqualTo("123456789");
        assertThat(form.getMname()).isEqualTo("ACME FOODS");
        assertThat(form.getMcity()).isEqualTo("SEATTLE");
        assertThat(form.getMzip()).isEqualTo("98101");
        assertThat(form.getErrmsg()).isEqualTo("NO ERRORS");
    }

    // ------------------------------------------------------------------------------------------
    // 4) trnamt is carried as a display String, never a numeric/money type
    // ------------------------------------------------------------------------------------------

    /**
     * The amount field {@code trnamt} holds a display-edited {@code String} verbatim, including a
     * grouping comma that no numeric type ({@code BigDecimal}/{@code double}) could round-trip.
     * The accessor's return type is locked to {@code String} to guard against a money-typed
     * regression. This test deliberately imports and uses no {@code java.math.BigDecimal}.
     *
     * @throws NoSuchMethodException never; {@code getTrnamt()} is a declared accessor
     */
    @Test
    void trnamt_is_carried_as_string() throws NoSuchMethodException {
        String displayAmount = "1,234.56";
        COTRN01Form form = new COTRN01Form();
        form.setTrnamt(displayAmount);

        assertThat(form.getTrnamt()).isEqualTo(displayAmount);
        assertThat(COTRN01Form.class.getMethod("getTrnamt").getReturnType()).isEqualTo(String.class);
    }

    // ------------------------------------------------------------------------------------------
    // 5) Per-form specifics: distinct trnidin/trnid, and ttypcd/tcatcd maxima
    // ------------------------------------------------------------------------------------------

    /**
     * {@code trnidin} (the transaction-id search input) and {@code trnid} (the displayed
     * transaction id) are distinct declared properties that round-trip independently: setting one
     * never alters the other.
     */
    @Test
    void trnidin_and_trnid_are_distinct_properties_and_round_trip() {
        String searchInput = "0000000000000042";
        String displayed = "0000000000000099";

        COTRN01Form form = new COTRN01Form();
        form.setTrnidin(searchInput);
        form.setTrnid(displayed);

        assertThat(form.getTrnidin()).isEqualTo(searchInput);
        assertThat(form.getTrnid()).isEqualTo(displayed);
        assertThat(form.getTrnidin()).isNotEqualTo(form.getTrnid());

        assertThat(COTRN01Form.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .contains("trnidin", "trnid");
    }

    /**
     * {@code ttypcd} enforces {@code @Size(max = 2)} and {@code tcatcd} enforces
     * {@code @Size(max = 4)}: at-max values are accepted, while a single character over the max
     * raises a violation on that exact property.
     */
    @Test
    void ttypcd_and_tcatcd_enforce_their_max_lengths() {
        COTRN01Form atMax = validForm();
        atMax.setTtypcd("AB");
        atMax.setTcatcd("ABCD");
        assertThat(validator.validate(atMax)).isEmpty();

        COTRN01Form overTtypcd = validForm();
        overTtypcd.setTtypcd("ABC");
        assertSingleViolationProperty(overTtypcd, "ttypcd");

        COTRN01Form overTcatcd = validForm();
        overTcatcd.setTcatcd("ABCDE");
        assertSingleViolationProperty(overTcatcd, "tcatcd");
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /**
     * Builds a form whose 21 fields are all populated at or under their declared {@code @Size}
     * maxima, so that {@link #validator} reports no violations for the unmodified instance.
     *
     * @return a fully populated, valid {@link COTRN01Form}
     */
    private static COTRN01Form validForm() {
        COTRN01Form form = new COTRN01Form();
        form.setTrnname("CT01");
        form.setTitle01("AWS CardDemo - Transaction View");
        form.setCurdate("07/18/26");
        form.setPgmname("COTRN01C");
        form.setTitle02("Transaction Detail");
        form.setCurtime("09:05:03");
        form.setTrnidin("0000000000000001");
        form.setTrnid("0000000000000001");
        form.setCardnum("4111111111111111");
        form.setTtypcd("01");
        form.setTcatcd("0005");
        form.setTrnsrc("POS");
        form.setTdesc("GROCERY PURCHASE");
        form.setTrnamt("0000123.45");
        form.setTorigdt("2026-07-18");
        form.setTprocdt("2026-07-18");
        form.setMid("123456789");
        form.setMname("ACME FOODS");
        form.setMcity("SEATTLE");
        form.setMzip("98101");
        form.setErrmsg("");
        return form;
    }

    /**
     * Validates {@code form} and asserts it yields exactly one violation, reported on the property
     * named {@code expectedProperty}.
     *
     * @param form             the form instance to validate
     * @param expectedProperty the property path expected to carry the sole violation
     */
    private static void assertSingleViolationProperty(COTRN01Form form, String expectedProperty) {
        Set<ConstraintViolation<COTRN01Form>> violations = validator.validate(form);
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly(expectedProperty);
    }
}
