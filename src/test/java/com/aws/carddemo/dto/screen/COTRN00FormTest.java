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
 * Unit tests for {@link COTRN00Form}. Oracle: legacy/cpy-bms/COTRN00.CPY (BMS
 * mapset COTRN00, map COTRN0A).
 *
 * <p>Pure JUnit 5 (Jupiter) + AssertJ unit test in the COBOL&#8594;Java/Spring
 * Boot migration of AWS CardDemo. It exercises the transaction-list screen
 * form-backing bean {@link COTRN00Form} with no Spring context, no database and
 * no Testcontainers; the Jakarta Bean Validation {@link Validator} is built
 * programmatically so the {@code @Size} constraints can be checked in isolation.</p>
 *
 * <p>The behaviors locked down here are:</p>
 * <ol>
 *   <li>A fully populated form whose every field is exactly at its {@code @Size}
 *       maximum produces no constraint violations (the upper bound is
 *       inclusive).</li>
 *   <li>A single over-length field yields exactly one violation whose property
 *       path is that field, verified across every field-width family.</li>
 *   <li>All 59 String properties round-trip through their explicit
 *       getters/setters across the ten transaction rows.</li>
 *   <li>The ten monetary amount fields are carried verbatim as {@code String}
 *       (never parsed to a floating-point or decimal numeric type), preserving
 *       the fixed-width BMS display formatting.</li>
 *   <li>The deliberately mixed numeric-suffix widths of the row properties
 *       ({@code sel} 4-digit, {@code trnid}/{@code tdate}/{@code tdesc} 2-digit,
 *       {@code tamt} 3-digit) are pinned to guard against accidental
 *       normalization.</li>
 * </ol>
 */
@DisplayName("COTRN00Form screen DTO (COTRN00.CPY / map COTRN0A)")
class COTRN00FormTest {

    /** Shared validator factory; closed in {@link #closeValidatorFactory()}. */
    private static ValidatorFactory factory;

    /** Bean Validation validator built from {@link #factory}. */
    private static Validator validator;

    /**
     * Builds the programmatic Jakarta Bean Validation {@link Validator} once for
     * the whole test class. No Spring context is involved.
     */
    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    /**
     * Releases the {@link ValidatorFactory} created in {@link #initValidator()}.
     */
    @AfterAll
    static void closeValidatorFactory() {
        factory.close();
    }

    // ------------------------------------------------------------------------
    // 1. Valid form
    // ------------------------------------------------------------------------

    /**
     * A form whose every property is exactly at its {@code @Size} maximum is
     * valid: the {@code @Size} upper bound is inclusive, so the maximum length
     * itself must not violate.
     */
    @Test
    @DisplayName("valid form (all fields at max length) has no violations")
    void valid_form_has_no_violations() {
        assertThat(validator.validate(validForm())).isEmpty();
    }

    // ------------------------------------------------------------------------
    // 2. Oversize -> exactly one violation on the exact property
    // ------------------------------------------------------------------------

    /**
     * One character over the maximum on a single field yields exactly one
     * violation whose property path is that field. The eight fields span every
     * distinct width family of the screen: the header/paging fields
     * ({@code pagenum}, {@code trnidin}), the 4-digit {@code sel} index, the
     * 2-digit {@code trnid}/{@code tdate}/{@code tdesc} indexes, the 3-digit
     * {@code tamt} index, and the trailer {@code errmsg}.
     */
    @Test
    @DisplayName("one over-max field violates @Size on that exact property")
    void oversize_fields_violate_size_on_exact_property() {
        COTRN00Form pagenumForm = validForm();
        pagenumForm.setPagenum(maxLen(9));
        assertSingleSizeViolation(pagenumForm, "pagenum");

        COTRN00Form trnidinForm = validForm();
        trnidinForm.setTrnidin(maxLen(17));
        assertSingleSizeViolation(trnidinForm, "trnidin");

        COTRN00Form selForm = validForm();
        selForm.setSel0001(maxLen(2));
        assertSingleSizeViolation(selForm, "sel0001");

        COTRN00Form trnidForm = validForm();
        trnidForm.setTrnid01(maxLen(17));
        assertSingleSizeViolation(trnidForm, "trnid01");

        COTRN00Form tdateForm = validForm();
        tdateForm.setTdate05(maxLen(9));
        assertSingleSizeViolation(tdateForm, "tdate05");

        COTRN00Form tdescForm = validForm();
        tdescForm.setTdesc10(maxLen(27));
        assertSingleSizeViolation(tdescForm, "tdesc10");

        COTRN00Form tamtForm = validForm();
        tamtForm.setTamt001(maxLen(13));
        assertSingleSizeViolation(tamtForm, "tamt001");

        COTRN00Form errmsgForm = validForm();
        errmsgForm.setErrmsg(maxLen(79));
        assertSingleSizeViolation(errmsgForm, "errmsg");
    }

    // ------------------------------------------------------------------------
    // 3. Round-trip getters/setters for all 59 properties
    // ------------------------------------------------------------------------

    /**
     * Every one of the 59 String properties round-trips through its explicit
     * getter/setter. Distinct values per field (and per row) ensure a mis-wired
     * accessor would be detected. All ten transaction rows and each of the five
     * per-row column families are exercised.
     */
    @Test
    @DisplayName("all 59 properties round-trip through their accessors")
    void getters_return_set_values() {
        COTRN00Form form = new COTRN00Form();

        // Header + paging (8 fields)
        form.setTrnname("CT00");
        form.setTitle01("Tran List Title One");
        form.setCurdate("01/31/25");
        form.setPgmname("COTRN00C");
        form.setTitle02("Tran List Title Two");
        form.setCurtime("12:34:56");
        form.setPagenum("PAGE0001");
        form.setTrnidin("TRNIN0000000001");

        // Ten rows, five column families each (sel/trnid/tdate/tdesc/tamt)
        form.setSel0001("A");
        form.setTrnid01("TRNID-01");
        form.setTdate01("DT-01");
        form.setTdesc01("DESC-ROW-01");
        form.setTamt001("AMT-001");

        form.setSel0002("B");
        form.setTrnid02("TRNID-02");
        form.setTdate02("DT-02");
        form.setTdesc02("DESC-ROW-02");
        form.setTamt002("AMT-002");

        form.setSel0003("C");
        form.setTrnid03("TRNID-03");
        form.setTdate03("DT-03");
        form.setTdesc03("DESC-ROW-03");
        form.setTamt003("AMT-003");

        form.setSel0004("D");
        form.setTrnid04("TRNID-04");
        form.setTdate04("DT-04");
        form.setTdesc04("DESC-ROW-04");
        form.setTamt004("AMT-004");

        form.setSel0005("E");
        form.setTrnid05("TRNID-05");
        form.setTdate05("DT-05");
        form.setTdesc05("DESC-ROW-05");
        form.setTamt005("AMT-005");

        form.setSel0006("F");
        form.setTrnid06("TRNID-06");
        form.setTdate06("DT-06");
        form.setTdesc06("DESC-ROW-06");
        form.setTamt006("AMT-006");

        form.setSel0007("G");
        form.setTrnid07("TRNID-07");
        form.setTdate07("DT-07");
        form.setTdesc07("DESC-ROW-07");
        form.setTamt007("AMT-007");

        form.setSel0008("H");
        form.setTrnid08("TRNID-08");
        form.setTdate08("DT-08");
        form.setTdesc08("DESC-ROW-08");
        form.setTamt008("AMT-008");

        form.setSel0009("I");
        form.setTrnid09("TRNID-09");
        form.setTdate09("DT-09");
        form.setTdesc09("DESC-ROW-09");
        form.setTamt009("AMT-009");

        form.setSel0010("J");
        form.setTrnid10("TRNID-10");
        form.setTdate10("DT-10");
        form.setTdesc10("DESC-ROW-10");
        form.setTamt010("AMT-010");

        // Trailer (1 field)
        form.setErrmsg("Sample error message");

        // Header + paging assertions
        assertThat(form.getTrnname()).isEqualTo("CT00");
        assertThat(form.getTitle01()).isEqualTo("Tran List Title One");
        assertThat(form.getCurdate()).isEqualTo("01/31/25");
        assertThat(form.getPgmname()).isEqualTo("COTRN00C");
        assertThat(form.getTitle02()).isEqualTo("Tran List Title Two");
        assertThat(form.getCurtime()).isEqualTo("12:34:56");
        assertThat(form.getPagenum()).isEqualTo("PAGE0001");
        assertThat(form.getTrnidin()).isEqualTo("TRNIN0000000001");

        // Row assertions (row 1)
        assertThat(form.getSel0001()).isEqualTo("A");
        assertThat(form.getTrnid01()).isEqualTo("TRNID-01");
        assertThat(form.getTdate01()).isEqualTo("DT-01");
        assertThat(form.getTdesc01()).isEqualTo("DESC-ROW-01");
        assertThat(form.getTamt001()).isEqualTo("AMT-001");

        // Row 2
        assertThat(form.getSel0002()).isEqualTo("B");
        assertThat(form.getTrnid02()).isEqualTo("TRNID-02");
        assertThat(form.getTdate02()).isEqualTo("DT-02");
        assertThat(form.getTdesc02()).isEqualTo("DESC-ROW-02");
        assertThat(form.getTamt002()).isEqualTo("AMT-002");

        // Row 3
        assertThat(form.getSel0003()).isEqualTo("C");
        assertThat(form.getTrnid03()).isEqualTo("TRNID-03");
        assertThat(form.getTdate03()).isEqualTo("DT-03");
        assertThat(form.getTdesc03()).isEqualTo("DESC-ROW-03");
        assertThat(form.getTamt003()).isEqualTo("AMT-003");

        // Row 4
        assertThat(form.getSel0004()).isEqualTo("D");
        assertThat(form.getTrnid04()).isEqualTo("TRNID-04");
        assertThat(form.getTdate04()).isEqualTo("DT-04");
        assertThat(form.getTdesc04()).isEqualTo("DESC-ROW-04");
        assertThat(form.getTamt004()).isEqualTo("AMT-004");

        // Row 5
        assertThat(form.getSel0005()).isEqualTo("E");
        assertThat(form.getTrnid05()).isEqualTo("TRNID-05");
        assertThat(form.getTdate05()).isEqualTo("DT-05");
        assertThat(form.getTdesc05()).isEqualTo("DESC-ROW-05");
        assertThat(form.getTamt005()).isEqualTo("AMT-005");

        // Row 6
        assertThat(form.getSel0006()).isEqualTo("F");
        assertThat(form.getTrnid06()).isEqualTo("TRNID-06");
        assertThat(form.getTdate06()).isEqualTo("DT-06");
        assertThat(form.getTdesc06()).isEqualTo("DESC-ROW-06");
        assertThat(form.getTamt006()).isEqualTo("AMT-006");

        // Row 7
        assertThat(form.getSel0007()).isEqualTo("G");
        assertThat(form.getTrnid07()).isEqualTo("TRNID-07");
        assertThat(form.getTdate07()).isEqualTo("DT-07");
        assertThat(form.getTdesc07()).isEqualTo("DESC-ROW-07");
        assertThat(form.getTamt007()).isEqualTo("AMT-007");

        // Row 8
        assertThat(form.getSel0008()).isEqualTo("H");
        assertThat(form.getTrnid08()).isEqualTo("TRNID-08");
        assertThat(form.getTdate08()).isEqualTo("DT-08");
        assertThat(form.getTdesc08()).isEqualTo("DESC-ROW-08");
        assertThat(form.getTamt008()).isEqualTo("AMT-008");

        // Row 9
        assertThat(form.getSel0009()).isEqualTo("I");
        assertThat(form.getTrnid09()).isEqualTo("TRNID-09");
        assertThat(form.getTdate09()).isEqualTo("DT-09");
        assertThat(form.getTdesc09()).isEqualTo("DESC-ROW-09");
        assertThat(form.getTamt009()).isEqualTo("AMT-009");

        // Row 10
        assertThat(form.getSel0010()).isEqualTo("J");
        assertThat(form.getTrnid10()).isEqualTo("TRNID-10");
        assertThat(form.getTdate10()).isEqualTo("DT-10");
        assertThat(form.getTdesc10()).isEqualTo("DESC-ROW-10");
        assertThat(form.getTamt010()).isEqualTo("AMT-010");

        // Trailer assertion
        assertThat(form.getErrmsg()).isEqualTo("Sample error message");
    }

    // ------------------------------------------------------------------------
    // 4. Monetary amounts are carried as String (never a numeric type)
    // ------------------------------------------------------------------------

    /**
     * The ten {@code tamt00N} amount fields hold the money value as a display
     * {@code String} and return it verbatim. This deliberately uses signed,
     * zero-padded, fixed-decimal display strings (as produced by the COBOL edit
     * mask) to prove no numeric parsing/normalization occurs in the DTO. This
     * test performs no Bean Validation, so display strings longer than the
     * {@code @Size} maximum are acceptable here; only the raw accessor
     * round-trip is asserted.
     */
    @Test
    @DisplayName("tamt fields carry money verbatim as String")
    void tamt_fields_are_carried_as_string() {
        COTRN00Form form = new COTRN00Form();
        form.setTamt001("-000000123.45");
        form.setTamt005("+000000000.00");
        form.setTamt010("000009999.99-");

        assertThat(form.getTamt001()).isEqualTo("-000000123.45");
        assertThat(form.getTamt005()).isEqualTo("+000000000.00");
        assertThat(form.getTamt010()).isEqualTo("000009999.99-");
    }

    // ------------------------------------------------------------------------
    // 5. Mixed digit-width property spellings
    // ------------------------------------------------------------------------

    /**
     * Pins the deliberately mixed numeric-suffix widths of the repeated row
     * properties so a future edit cannot silently normalize them.
     */
    @Test
    @DisplayName("mixed digit-width row property spellings are preserved")
    void mixed_digit_widths_property_names() {
        // The BMS row fields use DELIBERATELY MIXED numeric-suffix widths, and the
        // production form preserves those exact spellings:
        //   - sel   uses a 4-digit index: sel0001 .. sel0010
        //   - trnid uses a 2-digit index: trnid01 .. trnid10
        //   - tdate uses a 2-digit index: tdate01 .. tdate10
        //   - tdesc uses a 2-digit index: tdesc01 .. tdesc10
        //   - tamt  uses a 3-digit index: tamt001 .. tamt010
        COTRN00Form form = new COTRN00Form();
        form.setSel0001("Y");
        form.setTrnid01("TRNID-0000000001");
        form.setTamt001("-000000123.45");

        assertThat(form.getSel0001()).isEqualTo("Y");
        assertThat(form.getTrnid01()).isEqualTo("TRNID-0000000001");
        assertThat(form.getTamt001()).isEqualTo("-000000123.45");
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /**
     * Builds a form whose every one of the 59 properties is populated with a
     * value exactly at that property's {@code @Size} maximum, so the form is
     * valid (the inclusive upper bound is not exceeded).
     *
     * @return a fully populated, valid {@link COTRN00Form}
     */
    private COTRN00Form validForm() {
        COTRN00Form form = new COTRN00Form();

        // Header + paging
        form.setTrnname(maxLen(4));
        form.setTitle01(maxLen(40));
        form.setCurdate(maxLen(8));
        form.setPgmname(maxLen(8));
        form.setTitle02(maxLen(40));
        form.setCurtime(maxLen(8));
        form.setPagenum(maxLen(8));
        form.setTrnidin(maxLen(16));

        // Row 1
        form.setSel0001(maxLen(1));
        form.setTrnid01(maxLen(16));
        form.setTdate01(maxLen(8));
        form.setTdesc01(maxLen(26));
        form.setTamt001(maxLen(12));

        // Row 2
        form.setSel0002(maxLen(1));
        form.setTrnid02(maxLen(16));
        form.setTdate02(maxLen(8));
        form.setTdesc02(maxLen(26));
        form.setTamt002(maxLen(12));

        // Row 3
        form.setSel0003(maxLen(1));
        form.setTrnid03(maxLen(16));
        form.setTdate03(maxLen(8));
        form.setTdesc03(maxLen(26));
        form.setTamt003(maxLen(12));

        // Row 4
        form.setSel0004(maxLen(1));
        form.setTrnid04(maxLen(16));
        form.setTdate04(maxLen(8));
        form.setTdesc04(maxLen(26));
        form.setTamt004(maxLen(12));

        // Row 5
        form.setSel0005(maxLen(1));
        form.setTrnid05(maxLen(16));
        form.setTdate05(maxLen(8));
        form.setTdesc05(maxLen(26));
        form.setTamt005(maxLen(12));

        // Row 6
        form.setSel0006(maxLen(1));
        form.setTrnid06(maxLen(16));
        form.setTdate06(maxLen(8));
        form.setTdesc06(maxLen(26));
        form.setTamt006(maxLen(12));

        // Row 7
        form.setSel0007(maxLen(1));
        form.setTrnid07(maxLen(16));
        form.setTdate07(maxLen(8));
        form.setTdesc07(maxLen(26));
        form.setTamt007(maxLen(12));

        // Row 8
        form.setSel0008(maxLen(1));
        form.setTrnid08(maxLen(16));
        form.setTdate08(maxLen(8));
        form.setTdesc08(maxLen(26));
        form.setTamt008(maxLen(12));

        // Row 9
        form.setSel0009(maxLen(1));
        form.setTrnid09(maxLen(16));
        form.setTdate09(maxLen(8));
        form.setTdesc09(maxLen(26));
        form.setTamt009(maxLen(12));

        // Row 10
        form.setSel0010(maxLen(1));
        form.setTrnid10(maxLen(16));
        form.setTdate10(maxLen(8));
        form.setTdesc10(maxLen(26));
        form.setTamt010(maxLen(12));

        // Trailer
        form.setErrmsg(maxLen(78));

        return form;
    }

    /**
     * Returns a String of exactly {@code length} {@code 'X'} characters, used to
     * populate a field at (or, for the oversize tests, just beyond) its
     * {@code @Size} maximum.
     *
     * @param length the desired length; must be non-negative
     * @return a String of {@code length} {@code 'X'} characters
     */
    private static String maxLen(int length) {
        return "X".repeat(length);
    }

    /**
     * Asserts that validating {@code form} produces exactly one constraint
     * violation and that the violation's property path equals
     * {@code expectedProperty}.
     *
     * @param form             the form to validate
     * @param expectedProperty the property expected to be the sole violation
     */
    private static void assertSingleSizeViolation(COTRN00Form form, String expectedProperty) {
        Set<ConstraintViolation<COTRN00Form>> violations = validator.validate(form);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString())
                .isEqualTo(expectedProperty);
    }
}
