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
 * Unit tests for {@link COCRDLIForm}. Oracle: legacy/cpy-bms/COCRDLI.CPY
 * (BMS mapset {@code COCRDLI}, map {@code CCRDLIA}); source-branch
 * {@code app/cpy-bms/COCRDLI.CPY}.
 *
 * <p>These tests lock the card-list screen form's bindable JavaBean contract: the
 * 45 {@code String} value properties, their Jakarta {@link jakarta.validation.constraints.Size @Size}
 * length edits (one per BMS {@code PIC X(n)} field), and full getter/setter round-trips.</p>
 *
 * <p><strong>Row-1 asymmetry (the point of this suite):</strong> the screen renders seven
 * repeated card rows, but row&nbsp;1 has <em>no</em> status-protect field. Only
 * {@code crdstp2} through {@code crdstp7} exist; there is intentionally <em>no</em>
 * {@code crdstp1} property. This mirrors map {@code CCRDLIA} exactly (it declares
 * {@code CRDSEL1I}/{@code ACCTNO1I}/{@code CRDNUM1I}/{@code CRDSTS1I} for row&nbsp;1 but no
 * {@code CRDSTP1I}). The {@link #row_one_has_no_status_protect()} test proves this and never
 * invokes a row-1 status-protect accessor, as none is declared.</p>
 *
 * <p>Pure unit test: no Spring context, no database, no Testcontainers. The {@link Validator}
 * is built programmatically and the form is instantiated directly via {@code new COCRDLIForm()};
 * every assertion is made against public return values. All 45 modeled fields are fixed-width
 * text ({@code PIC X(n)}); this screen carries no monetary fields.</p>
 */
class COCRDLIFormTest {

    /** Programmatic (non-Spring) validation factory shared by all tests. */
    private static ValidatorFactory factory;

    /** Bean-validation engine derived from {@link #factory}. */
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        if (factory != null) {
            factory.close();
        }
    }

    // ==================================================================
    // 1. Valid form -> no constraint violations
    // ==================================================================

    @Test
    @DisplayName("A form with every field at or under its BMS length yields zero violations")
    void valid_form_has_no_violations() {
        assertThat(validator.validate(validForm())).isEmpty();
    }

    // ==================================================================
    // 2. Over-length value -> @Size violation on exactly the offending property.
    //    Cases deliberately span the header, filter, repeated-row and trailer
    //    field families so a mis-wired constraint on any family is caught.
    // ==================================================================

    @Test
    @DisplayName("An over-length value violates @Size on exactly the offending property")
    void oversize_fields_violate_size_on_exact_property() {
        // pageno: @Size(max = 3) -> 4 chars
        COCRDLIForm pagenoOver = validForm();
        pagenoOver.setPageno("X".repeat(4));
        assertSingleSizeViolation(pagenoOver, "pageno");

        // acctsid (filter): @Size(max = 11) -> 12 chars
        COCRDLIForm acctsidOver = validForm();
        acctsidOver.setAcctsid("X".repeat(12));
        assertSingleSizeViolation(acctsidOver, "acctsid");

        // crdnum1 (row 1): @Size(max = 16) -> 17 chars
        COCRDLIForm crdnum1Over = validForm();
        crdnum1Over.setCrdnum1("X".repeat(17));
        assertSingleSizeViolation(crdnum1Over, "crdnum1");

        // acctno2 (row 2): @Size(max = 11) -> 12 chars
        COCRDLIForm acctno2Over = validForm();
        acctno2Over.setAcctno2("X".repeat(12));
        assertSingleSizeViolation(acctno2Over, "acctno2");

        // crdsts7 (row 7): @Size(max = 1) -> 2 chars
        COCRDLIForm crdsts7Over = validForm();
        crdsts7Over.setCrdsts7("X".repeat(2));
        assertSingleSizeViolation(crdsts7Over, "crdsts7");

        // infomsg (trailer): @Size(max = 45) -> 46 chars
        COCRDLIForm infomsgOver = validForm();
        infomsgOver.setInfomsg("X".repeat(46));
        assertSingleSizeViolation(infomsgOver, "infomsg");

        // errmsg (trailer): @Size(max = 78) -> 79 chars
        COCRDLIForm errmsgOver = validForm();
        errmsgOver.setErrmsg("X".repeat(79));
        assertSingleSizeViolation(errmsgOver, "errmsg");
    }

    // ==================================================================
    // 3. Getter/setter round-trip for ALL 45 properties. Each field is given a
    //    distinct value so a cross-wired accessor (e.g. getCrdsel3 returning
    //    crdsel2) is detected.
    // ==================================================================

    @Test
    @DisplayName("Every one of the 45 properties round-trips through its getter/setter")
    void getters_return_set_values() {
        COCRDLIForm form = new COCRDLIForm();

        // header / filter (9)
        form.setTrnname("TR01");
        assertThat(form.getTrnname()).isEqualTo("TR01");
        form.setTitle01("title01-value");
        assertThat(form.getTitle01()).isEqualTo("title01-value");
        form.setCurdate("20260719");
        assertThat(form.getCurdate()).isEqualTo("20260719");
        form.setPgmname("PGMNAME1");
        assertThat(form.getPgmname()).isEqualTo("PGMNAME1");
        form.setTitle02("title02-value");
        assertThat(form.getTitle02()).isEqualTo("title02-value");
        form.setCurtime("12:00:59");
        assertThat(form.getCurtime()).isEqualTo("12:00:59");
        form.setPageno("042");
        assertThat(form.getPageno()).isEqualTo("042");
        form.setAcctsid("ACCTSID0001");
        assertThat(form.getAcctsid()).isEqualTo("ACCTSID0001");
        form.setCardsid("CARDSID000000001");
        assertThat(form.getCardsid()).isEqualTo("CARDSID000000001");

        // row 1 (4 fields - NO crdstp1)
        form.setCrdsel1("a");
        assertThat(form.getCrdsel1()).isEqualTo("a");
        form.setAcctno1("ACCTNO10001");
        assertThat(form.getAcctno1()).isEqualTo("ACCTNO10001");
        form.setCrdnum1("CRDNUM1000000001");
        assertThat(form.getCrdnum1()).isEqualTo("CRDNUM1000000001");
        form.setCrdsts1("b");
        assertThat(form.getCrdsts1()).isEqualTo("b");

        // row 2 (5 fields)
        form.setCrdsel2("c");
        assertThat(form.getCrdsel2()).isEqualTo("c");
        form.setCrdstp2("d");
        assertThat(form.getCrdstp2()).isEqualTo("d");
        form.setAcctno2("ACCTNO20002");
        assertThat(form.getAcctno2()).isEqualTo("ACCTNO20002");
        form.setCrdnum2("CRDNUM2000000002");
        assertThat(form.getCrdnum2()).isEqualTo("CRDNUM2000000002");
        form.setCrdsts2("e");
        assertThat(form.getCrdsts2()).isEqualTo("e");

        // row 3 (5 fields)
        form.setCrdsel3("f");
        assertThat(form.getCrdsel3()).isEqualTo("f");
        form.setCrdstp3("g");
        assertThat(form.getCrdstp3()).isEqualTo("g");
        form.setAcctno3("ACCTNO30003");
        assertThat(form.getAcctno3()).isEqualTo("ACCTNO30003");
        form.setCrdnum3("CRDNUM3000000003");
        assertThat(form.getCrdnum3()).isEqualTo("CRDNUM3000000003");
        form.setCrdsts3("h");
        assertThat(form.getCrdsts3()).isEqualTo("h");

        // row 4 (5 fields)
        form.setCrdsel4("i");
        assertThat(form.getCrdsel4()).isEqualTo("i");
        form.setCrdstp4("j");
        assertThat(form.getCrdstp4()).isEqualTo("j");
        form.setAcctno4("ACCTNO40004");
        assertThat(form.getAcctno4()).isEqualTo("ACCTNO40004");
        form.setCrdnum4("CRDNUM4000000004");
        assertThat(form.getCrdnum4()).isEqualTo("CRDNUM4000000004");
        form.setCrdsts4("k");
        assertThat(form.getCrdsts4()).isEqualTo("k");

        // row 5 (5 fields)
        form.setCrdsel5("l");
        assertThat(form.getCrdsel5()).isEqualTo("l");
        form.setCrdstp5("m");
        assertThat(form.getCrdstp5()).isEqualTo("m");
        form.setAcctno5("ACCTNO50005");
        assertThat(form.getAcctno5()).isEqualTo("ACCTNO50005");
        form.setCrdnum5("CRDNUM5000000005");
        assertThat(form.getCrdnum5()).isEqualTo("CRDNUM5000000005");
        form.setCrdsts5("n");
        assertThat(form.getCrdsts5()).isEqualTo("n");

        // row 6 (5 fields)
        form.setCrdsel6("o");
        assertThat(form.getCrdsel6()).isEqualTo("o");
        form.setCrdstp6("p");
        assertThat(form.getCrdstp6()).isEqualTo("p");
        form.setAcctno6("ACCTNO60006");
        assertThat(form.getAcctno6()).isEqualTo("ACCTNO60006");
        form.setCrdnum6("CRDNUM6000000006");
        assertThat(form.getCrdnum6()).isEqualTo("CRDNUM6000000006");
        form.setCrdsts6("q");
        assertThat(form.getCrdsts6()).isEqualTo("q");

        // row 7 (5 fields)
        form.setCrdsel7("r");
        assertThat(form.getCrdsel7()).isEqualTo("r");
        form.setCrdstp7("s");
        assertThat(form.getCrdstp7()).isEqualTo("s");
        form.setAcctno7("ACCTNO70007");
        assertThat(form.getAcctno7()).isEqualTo("ACCTNO70007");
        form.setCrdnum7("CRDNUM7000000007");
        assertThat(form.getCrdnum7()).isEqualTo("CRDNUM7000000007");
        form.setCrdsts7("t");
        assertThat(form.getCrdsts7()).isEqualTo("t");

        // trailer (2)
        form.setInfomsg("info-message");
        assertThat(form.getInfomsg()).isEqualTo("info-message");
        form.setErrmsg("error-message");
        assertThat(form.getErrmsg()).isEqualTo("error-message");
    }

    // ==================================================================
    // 5. Per-form specific: row-1 asymmetry. (Category 4, money-as-String, is N/A:
    //    this screen has no monetary fields.) Rows 2-7 expose a crdstpN property and
    //    must round-trip; row 1 has NO crdstp1 accessor/field, which is locked here.
    // ==================================================================

    @Test
    @DisplayName("Rows 2-7 expose crdstpN (round-trip); row 1 has no crdstp1 (asymmetry locked)")
    void row_one_has_no_status_protect() {
        COCRDLIForm form = new COCRDLIForm();

        // Rows 2-7 each declare a status-protect property; verify each exists and round-trips.
        form.setCrdstp2("2");
        assertThat(form.getCrdstp2()).isEqualTo("2");
        form.setCrdstp3("3");
        assertThat(form.getCrdstp3()).isEqualTo("3");
        form.setCrdstp4("4");
        assertThat(form.getCrdstp4()).isEqualTo("4");
        form.setCrdstp5("5");
        assertThat(form.getCrdstp5()).isEqualTo("5");
        form.setCrdstp6("6");
        assertThat(form.getCrdstp6()).isEqualTo("6");
        form.setCrdstp7("7");
        assertThat(form.getCrdstp7()).isEqualTo("7");

        // Row 1 intentionally has NO crdstp1 property: map CCRDLIA declares no CRDSTP1
        // field. We deliberately never invoke a row-1 status-protect accessor (none is
        // declared, so any such call would not compile). Lock the asymmetry via reflection:
        // no field named "crdstp1" may be declared on the form, while every crdstp2..crdstp7 must be.
        assertThat(COCRDLIForm.class.getDeclaredFields())
                .noneMatch(field -> field.getName().equals("crdstp1"));
        assertThat(COCRDLIForm.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .contains("crdstp2", "crdstp3", "crdstp4", "crdstp5", "crdstp6", "crdstp7");
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    /**
     * Asserts that validating {@code form} produces exactly one constraint violation and that it
     * is reported against {@code expectedProperty}. Using {@code containsExactly} proves both that
     * the offending property violated {@code @Size} and that no other property did.
     *
     * @param form             the form to validate
     * @param expectedProperty the single property expected to carry the violation
     */
    private void assertSingleSizeViolation(COCRDLIForm form, String expectedProperty) {
        Set<ConstraintViolation<COCRDLIForm>> violations = validator.validate(form);
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly(expectedProperty);
    }

    /**
     * Builds a card-list form whose 45 properties are all populated at or under their BMS
     * {@code PIC X(n)} lengths, so a pristine instance yields zero constraint violations. The
     * over-length tests start from this baseline and push a single field past its limit.
     *
     * @return a fully populated, constraint-clean {@link COCRDLIForm}
     */
    private COCRDLIForm validForm() {
        COCRDLIForm form = new COCRDLIForm();

        // header / filter (9)
        form.setTrnname("CCLI");
        form.setTitle01("Card List");
        form.setCurdate("07/19/26");
        form.setPgmname("COCRDLIC");
        form.setTitle02("AWS CardDemo");
        form.setCurtime("12:34:56");
        form.setPageno("001");
        form.setAcctsid("10000000000");
        form.setCardsid("4000000000000000");

        // row 1 (4 fields - NO crdstp1)
        form.setCrdsel1("S");
        form.setAcctno1("10000000001");
        form.setCrdnum1("4000000000000001");
        form.setCrdsts1("Y");

        // row 2 (5 fields)
        form.setCrdsel2("S");
        form.setCrdstp2("P");
        form.setAcctno2("10000000002");
        form.setCrdnum2("4000000000000002");
        form.setCrdsts2("Y");

        // row 3 (5 fields)
        form.setCrdsel3("S");
        form.setCrdstp3("P");
        form.setAcctno3("10000000003");
        form.setCrdnum3("4000000000000003");
        form.setCrdsts3("Y");

        // row 4 (5 fields)
        form.setCrdsel4("S");
        form.setCrdstp4("P");
        form.setAcctno4("10000000004");
        form.setCrdnum4("4000000000000004");
        form.setCrdsts4("Y");

        // row 5 (5 fields)
        form.setCrdsel5("S");
        form.setCrdstp5("P");
        form.setAcctno5("10000000005");
        form.setCrdnum5("4000000000000005");
        form.setCrdsts5("Y");

        // row 6 (5 fields)
        form.setCrdsel6("S");
        form.setCrdstp6("P");
        form.setAcctno6("10000000006");
        form.setCrdnum6("4000000000000006");
        form.setCrdsts6("Y");

        // row 7 (5 fields)
        form.setCrdsel7("S");
        form.setCrdstp7("P");
        form.setAcctno7("10000000007");
        form.setCrdnum7("4000000000000007");
        form.setCrdsts7("Y");

        // trailer (2)
        form.setInfomsg("Cards displayed");
        form.setErrmsg("No errors");

        return form;
    }
}
