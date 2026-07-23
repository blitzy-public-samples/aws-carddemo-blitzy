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
 * Unit tests for {@link COTRN02Form}. Oracle: legacy/cpy-bms/COTRN02.CPY (BMS mapset COTRN02, map COTRN2A).
 *
 * <p>These are pure JUnit&nbsp;5 (Jupiter) + AssertJ unit tests for the transaction-add
 * screen-form DTO of the AWS CardDemo COBOL&rarr;Java/Spring Boot migration. They exercise the
 * form in complete isolation: no Spring context, no database, no Testcontainers and no Docker.
 * A Jakarta Bean&nbsp;Validation {@link Validator} is built programmatically (never injected) so
 * the {@code @Size} constraints translated from the BMS {@code PIC X(n)} field widths can be
 * asserted without booting the application.</p>
 *
 * <p>Five behaviours are verified against the production form (which is authoritative &mdash; where
 * the class and the copybook table would disagree, the class wins):</p>
 * <ol>
 *   <li>a fully populated, in-range form yields zero constraint violations;</li>
 *   <li>a field one character over its {@code @Size(max)} yields exactly one violation reported on
 *       that exact property (spot-checked across {@code actidin}, {@code cardnin}, {@code tdesc},
 *       {@code trnamt}, {@code confirm} and {@code errmsg});</li>
 *   <li>every one of the 21 properties round-trips through its setter/getter pair;</li>
 *   <li>the monetary field {@code trnamt} is carried as a display {@link String} (never a
 *       {@code BigDecimal}, {@code double} or {@code float}) so the on-screen amount is preserved
 *       byte-for-byte, sign and leading zeros included;</li>
 *   <li>the add screen exposes the {@code actidin}/{@code cardnin} inputs and a single-character
 *       {@code confirm} flag &mdash; distinguishing it from the transaction-view form {@code COTRN01Form}
 *       (which instead carries {@code trnidin}/{@code trnid}).</li>
 * </ol>
 */
class COTRN02FormTest {

    // ------------------------------------------------------------------
    // Programmatically built Jakarta Bean Validation validator (no Spring).
    // ------------------------------------------------------------------

    private static ValidatorFactory factory;
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
    // Representative in-range values, one per BMS field. Each value is at or
    // under its @Size(max) length (see legacy/cpy-bms/COTRN02.CPY) and every
    // value is pairwise-distinct so an accidental setter/getter field-aliasing
    // bug in the production form would fail the round-trip assertions.
    // ------------------------------------------------------------------

    private static final String TRNNAME = "CT02";                 // X(4)  header
    private static final String TITLE01 = "AWS CardDemo";          // X(40) header
    private static final String CURDATE = "07/18/25";              // X(8)  header
    private static final String PGMNAME = "COTRN02C";              // X(8)  header
    private static final String TITLE02 = "Add Transaction";       // X(40) header
    private static final String CURTIME = "09:05:03";              // X(8)  header
    private static final String ACTIDIN = "00000000001";           // X(11) account-id input
    private static final String CARDNIN = "4111111111111111";      // X(16) card-number input
    private static final String TTYPCD = "01";                     // X(2)  tran type code
    private static final String TCATCD = "0005";                   // X(4)  tran category code
    private static final String TRNSRC = "POS";                    // X(10) tran source
    private static final String TDESC = "GROCERY PURCHASE";        // X(60) tran description
    private static final String TRNAMT = "-00000123.45";           // X(12) amount (display String)
    private static final String TORIGDT = "2025-07-18";            // X(10) original date
    private static final String TPROCDT = "2025-07-19";            // X(10) processing date
    private static final String MID = "123456789";                 // X(9)  merchant id
    private static final String MNAME = "ACME FOODS";              // X(30) merchant name
    private static final String MCITY = "SEATTLE";                 // X(25) merchant city
    private static final String MZIP = "98101-0000";               // X(10) merchant zip
    private static final String CONFIRM = "Y";                     // X(1)  confirm add (Y/N)
    private static final String ERRMSG = "STATUS OK";              // X(78) error / status line

    /**
     * Builds a fully populated form whose every property is at or under its {@code @Size(max)},
     * so {@code validator.validate(validForm())} is expected to be empty. Callers that want to
     * exercise a single over-max field start from a fresh {@code validForm()} and mutate one setter.
     *
     * @return a valid, fully populated {@link COTRN02Form}
     */
    private static COTRN02Form validForm() {
        COTRN02Form form = new COTRN02Form();
        form.setTrnname(TRNNAME);
        form.setTitle01(TITLE01);
        form.setCurdate(CURDATE);
        form.setPgmname(PGMNAME);
        form.setTitle02(TITLE02);
        form.setCurtime(CURTIME);
        form.setActidin(ACTIDIN);
        form.setCardnin(CARDNIN);
        form.setTtypcd(TTYPCD);
        form.setTcatcd(TCATCD);
        form.setTrnsrc(TRNSRC);
        form.setTdesc(TDESC);
        form.setTrnamt(TRNAMT);
        form.setTorigdt(TORIGDT);
        form.setTprocdt(TPROCDT);
        form.setMid(MID);
        form.setMname(MNAME);
        form.setMcity(MCITY);
        form.setMzip(MZIP);
        form.setConfirm(CONFIRM);
        form.setErrmsg(ERRMSG);
        return form;
    }

    /**
     * Validates the supplied form, asserts that it produced exactly one constraint violation, and
     * returns the property path of that single violation. Used by the over-max spot checks to prove
     * the violation is reported on the intended property and nowhere else.
     *
     * @param form the form to validate (expected to have exactly one violation)
     * @return the dotted property path of the single violation (e.g. {@code "trnamt"})
     */
    private static String singleViolationProperty(COTRN02Form form) {
        Set<ConstraintViolation<COTRN02Form>> violations = validator.validate(form);
        assertThat(violations).hasSize(1);
        return violations.iterator().next().getPropertyPath().toString();
    }

    // ==================================================================
    // 1. Happy path - a fully populated, in-range form is valid.
    // ==================================================================

    @Test
    @DisplayName("a fully populated in-range form produces no constraint violations")
    void valid_form_has_no_violations() {
        assertThat(validator.validate(validForm())).isEmpty();
    }

    // ==================================================================
    // 2. @Size boundaries - one char over max violates on the exact property.
    // ==================================================================

    @Test
    @DisplayName("each field one char over @Size(max) yields one violation on that exact property")
    void oversize_fields_violate_size_on_exact_property() {
        // actidin: PIC X(11) -> 12 chars is over the limit.
        COTRN02Form overActidin = validForm();
        overActidin.setActidin("1".repeat(12));
        assertThat(singleViolationProperty(overActidin)).isEqualTo("actidin");

        // cardnin: PIC X(16) -> 17 chars is over the limit.
        COTRN02Form overCardnin = validForm();
        overCardnin.setCardnin("4".repeat(17));
        assertThat(singleViolationProperty(overCardnin)).isEqualTo("cardnin");

        // tdesc: PIC X(60) -> 61 chars is over the limit.
        COTRN02Form overTdesc = validForm();
        overTdesc.setTdesc("D".repeat(61));
        assertThat(singleViolationProperty(overTdesc)).isEqualTo("tdesc");

        // trnamt: PIC X(12) -> 13 chars is over the limit (still a String field).
        COTRN02Form overTrnamt = validForm();
        overTrnamt.setTrnamt("9".repeat(13));
        assertThat(singleViolationProperty(overTrnamt)).isEqualTo("trnamt");

        // confirm: PIC X(1) -> 2 chars is over the limit.
        COTRN02Form overConfirm = validForm();
        overConfirm.setConfirm("YN");
        assertThat(singleViolationProperty(overConfirm)).isEqualTo("confirm");

        // errmsg: PIC X(78) -> 79 chars is over the limit.
        COTRN02Form overErrmsg = validForm();
        overErrmsg.setErrmsg("E".repeat(79));
        assertThat(singleViolationProperty(overErrmsg)).isEqualTo("errmsg");
    }

    // ==================================================================
    // 3. Full round-trip - every one of the 21 properties.
    // ==================================================================

    @Test
    @DisplayName("all 21 properties round-trip through their setter/getter pairs")
    void getters_return_set_values() {
        COTRN02Form form = validForm();

        assertThat(form.getTrnname()).isEqualTo(TRNNAME);
        assertThat(form.getTitle01()).isEqualTo(TITLE01);
        assertThat(form.getCurdate()).isEqualTo(CURDATE);
        assertThat(form.getPgmname()).isEqualTo(PGMNAME);
        assertThat(form.getTitle02()).isEqualTo(TITLE02);
        assertThat(form.getCurtime()).isEqualTo(CURTIME);
        assertThat(form.getActidin()).isEqualTo(ACTIDIN);
        assertThat(form.getCardnin()).isEqualTo(CARDNIN);
        assertThat(form.getTtypcd()).isEqualTo(TTYPCD);
        assertThat(form.getTcatcd()).isEqualTo(TCATCD);
        assertThat(form.getTrnsrc()).isEqualTo(TRNSRC);
        assertThat(form.getTdesc()).isEqualTo(TDESC);
        assertThat(form.getTrnamt()).isEqualTo(TRNAMT);
        assertThat(form.getTorigdt()).isEqualTo(TORIGDT);
        assertThat(form.getTprocdt()).isEqualTo(TPROCDT);
        assertThat(form.getMid()).isEqualTo(MID);
        assertThat(form.getMname()).isEqualTo(MNAME);
        assertThat(form.getMcity()).isEqualTo(MCITY);
        assertThat(form.getMzip()).isEqualTo(MZIP);
        assertThat(form.getConfirm()).isEqualTo(CONFIRM);
        assertThat(form.getErrmsg()).isEqualTo(ERRMSG);
    }

    // ==================================================================
    // 4. Money-as-String parity - trnamt is a display String, never numeric.
    // ==================================================================

    @Test
    @DisplayName("trnamt is carried as a display String preserving sign, leading zeros and decimal point")
    void trnamt_is_carried_as_string() throws NoSuchMethodException {
        COTRN02Form form = new COTRN02Form();

        // A display amount that a numeric type (BigDecimal/double/float) could not preserve
        // byte-for-byte: it keeps the leading minus and the leading zeros exactly as typed on the
        // 3270 screen. A plain setter is a field write and performs no validation, so this value
        // (13 chars, which would exceed @Size(max = 12) under the validator) is stored verbatim -
        // demonstrating the field is an unconstrained String container, not a parsed number.
        String display = "-000000123.45";
        form.setTrnamt(display);
        assertThat(form.getTrnamt()).isEqualTo(display);

        // Type-level proof: the accessor returns java.lang.String, never a numeric type. If the
        // production form ever changed trnamt to BigDecimal/double/float this lookup would still
        // resolve but the asserted return type would differ, failing the test.
        assertThat(COTRN02Form.class.getDeclaredMethod("getTrnamt").getReturnType())
                .isEqualTo(String.class);
    }

    // ==================================================================
    // 5. Per-form specifics - add-screen inputs distinguish it from COTRN01.
    // ==================================================================

    @Test
    @DisplayName("add screen exposes actidin/cardnin inputs and a 1-char confirm, unlike COTRN01's trnidin/trnid")
    void add_screen_specific_fields_round_trip() {
        COTRN02Form form = new COTRN02Form();
        form.setActidin(ACTIDIN);
        form.setCardnin(CARDNIN);
        form.setConfirm(CONFIRM);

        // The account-id and card-number inputs are the entry fields unique to the add screen.
        assertThat(form.getActidin()).isEqualTo(ACTIDIN);
        assertThat(form.getCardnin()).isEqualTo(CARDNIN);
        assertThat(form.getConfirm()).isEqualTo(CONFIRM);

        // The transaction-add form must NOT carry COTRN01Form's trnidin/trnid accessors - that is
        // the transaction-view/lookup contract, a different screen. Assert their absence directly.
        boolean hasViewOnlyAccessors = java.util.Arrays.stream(COTRN02Form.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .anyMatch(name -> name.equals("getTrnidin") || name.equals("getTrnid"));
        assertThat(hasViewOnlyAccessors)
                .as("COTRN02Form (add) must not expose COTRN01Form's trnidin/trnid accessors")
                .isFalse();

        // confirm is a single-character Y/N flag: a 2-character value violates @Size(max = 1).
        COTRN02Form overConfirm = validForm();
        overConfirm.setConfirm("YN");
        assertThat(singleViolationProperty(overConfirm)).isEqualTo("confirm");
    }
}
