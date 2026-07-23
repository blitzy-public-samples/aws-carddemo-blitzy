package com.aws.carddemo.dto.screen;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link COUSR00Form}. Oracle: legacy/cpy-bms/COUSR00.CPY (BMS mapset
 * COUSR00, map COUSR0A).
 *
 * <p>{@code COUSR00Form} is the Spring MVC form-backing bean for the administrative
 * user-<em>list</em> screen (CICS transaction {@code CU00}, program {@code COUSR00C}).
 * It carries eight header/paging fields, ten repeated user rows of five columns each,
 * and one trailer error line, for a total of fifty-nine {@code String} properties. Each
 * property preserves the original BMS {@code PIC X(n)} field length via a Jakarta Bean
 * Validation {@link jakarta.validation.constraints.Size} upper bound, reproducing the
 * legacy 24x80 field-length edits during request binding.</p>
 *
 * <p>The distinguishing characteristic of this map is its <strong>mixed row-index digit
 * widths</strong>: the selection flags use a four-digit index
 * ({@code sel0001}-{@code sel0010}), whereas the user id, first name, last name, and user
 * type columns use a two-digit index ({@code usrid01}-{@code usrid10},
 * {@code fname01}-{@code fname10}, {@code lname01}-{@code lname10},
 * {@code utype01}-{@code utype10}). These tests deliberately lock those exact spellings
 * and must never normalize them. Because this is a user <em>list</em>, there is
 * intentionally <strong>no password column</strong> (unlike the single-user add/update
 * screens).</p>
 *
 * <p>This is a pure unit test: it constructs the form directly with
 * {@code new COUSR00Form()} and builds a Bean Validation {@link Validator} programmatically.
 * It never starts a Spring context, never touches a database, and never uses
 * Testcontainers or Docker. The screen carries no monetary fields, so the
 * money-as-String assertion category does not apply here.</p>
 */
class COUSR00FormTest {

    /** Bean Validation factory built once for the whole class; closed in {@link #closeValidator()}. */
    private static ValidatorFactory factory;

    /** Reference Bean Validation validator shared by every test method. */
    private static Validator validator;

    /**
     * Builds the standalone Bean Validation validator. No Spring context is involved: the
     * default provider (Hibernate Validator) is discovered from the test classpath.
     */
    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    /** Releases the validator factory resources after all tests have run. */
    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    /**
     * A fully populated, entirely valid form produces zero constraint violations. Several
     * fields are set to exactly their {@code @Size} maximum length to prove the inclusive
     * upper bound is accepted.
     */
    @Test
    void valid_form_has_no_violations() {
        assertThat(validator.validate(validForm())).isEmpty();
    }

    /**
     * A single field that exceeds its {@code @Size} maximum by exactly one character yields
     * exactly one violation reported on that precise property. The assertions span every
     * width family, including the four-digit selection index ({@code sel0001}), a two-digit
     * user-id index ({@code usrid01}), and a two-digit user-type index ({@code utype01}).
     */
    @Test
    void oversize_fields_violate_size_on_exact_property() {
        // Header / paging families (max 8).
        COUSR00Form pagenumForm = validForm();
        pagenumForm.setPagenum("X".repeat(9)); // max 8 -> one char over
        assertSingleSizeViolationOn(pagenumForm, "pagenum");

        COUSR00Form usridinForm = validForm();
        usridinForm.setUsridin("X".repeat(9)); // max 8 -> one char over
        assertSingleSizeViolationOn(usridinForm, "usridin");

        // Row selection flag: four-digit index, max 1.
        COUSR00Form selForm = validForm();
        selForm.setSel0001("X".repeat(2)); // max 1 -> one char over
        assertSingleSizeViolationOn(selForm, "sel0001");

        // Row user id: two-digit index, max 8.
        COUSR00Form usridForm = validForm();
        usridForm.setUsrid01("X".repeat(9)); // max 8 -> one char over
        assertSingleSizeViolationOn(usridForm, "usrid01");

        // Row first name: two-digit index, max 20.
        COUSR00Form fnameForm = validForm();
        fnameForm.setFname05("X".repeat(21)); // max 20 -> one char over
        assertSingleSizeViolationOn(fnameForm, "fname05");

        // Row last name: two-digit index, max 20.
        COUSR00Form lnameForm = validForm();
        lnameForm.setLname10("X".repeat(21)); // max 20 -> one char over
        assertSingleSizeViolationOn(lnameForm, "lname10");

        // Row user type: two-digit index, max 1.
        COUSR00Form utypeForm = validForm();
        utypeForm.setUtype01("X".repeat(2)); // max 1 -> one char over
        assertSingleSizeViolationOn(utypeForm, "utype01");

        // Trailer error line, max 78.
        COUSR00Form errmsgForm = validForm();
        errmsgForm.setErrmsg("X".repeat(79)); // max 78 -> one char over
        assertSingleSizeViolationOn(errmsgForm, "errmsg");
    }

    /**
     * Every one of the fifty-nine properties round-trips through its setter and getter,
     * exercising all eight header/paging fields, each of the ten user rows across every
     * column family ({@code sel000N}, {@code usridNN}, {@code fnameNN}, {@code lnameNN},
     * {@code utypeNN}), and the trailer error line. Distinct values are used per field so a
     * cross-wired accessor would be detected.
     */
    @Test
    void getters_return_set_values() {
        COUSR00Form form = new COUSR00Form();

        // Header and paging (8 fields).
        form.setTrnname("CU00");
        form.setTitle01("List Users Title One");
        form.setCurdate("01/02/25");
        form.setPgmname("COUSR00C");
        form.setTitle02("User List Title Two");
        form.setCurtime("12:34:56");
        form.setPagenum("PAGE0001");
        form.setUsridin("SRCHUSER");
        assertThat(form.getTrnname()).isEqualTo("CU00");
        assertThat(form.getTitle01()).isEqualTo("List Users Title One");
        assertThat(form.getCurdate()).isEqualTo("01/02/25");
        assertThat(form.getPgmname()).isEqualTo("COUSR00C");
        assertThat(form.getTitle02()).isEqualTo("User List Title Two");
        assertThat(form.getCurtime()).isEqualTo("12:34:56");
        assertThat(form.getPagenum()).isEqualTo("PAGE0001");
        assertThat(form.getUsridin()).isEqualTo("SRCHUSER");

        // Row 01.
        form.setSel0001("a");
        form.setUsrid01("USRID001");
        form.setFname01("FirstName01");
        form.setLname01("LastName01");
        form.setUtype01("1");
        assertThat(form.getSel0001()).isEqualTo("a");
        assertThat(form.getUsrid01()).isEqualTo("USRID001");
        assertThat(form.getFname01()).isEqualTo("FirstName01");
        assertThat(form.getLname01()).isEqualTo("LastName01");
        assertThat(form.getUtype01()).isEqualTo("1");

        // Row 02.
        form.setSel0002("b");
        form.setUsrid02("USRID002");
        form.setFname02("FirstName02");
        form.setLname02("LastName02");
        form.setUtype02("2");
        assertThat(form.getSel0002()).isEqualTo("b");
        assertThat(form.getUsrid02()).isEqualTo("USRID002");
        assertThat(form.getFname02()).isEqualTo("FirstName02");
        assertThat(form.getLname02()).isEqualTo("LastName02");
        assertThat(form.getUtype02()).isEqualTo("2");

        // Row 03.
        form.setSel0003("c");
        form.setUsrid03("USRID003");
        form.setFname03("FirstName03");
        form.setLname03("LastName03");
        form.setUtype03("3");
        assertThat(form.getSel0003()).isEqualTo("c");
        assertThat(form.getUsrid03()).isEqualTo("USRID003");
        assertThat(form.getFname03()).isEqualTo("FirstName03");
        assertThat(form.getLname03()).isEqualTo("LastName03");
        assertThat(form.getUtype03()).isEqualTo("3");

        // Row 04.
        form.setSel0004("d");
        form.setUsrid04("USRID004");
        form.setFname04("FirstName04");
        form.setLname04("LastName04");
        form.setUtype04("4");
        assertThat(form.getSel0004()).isEqualTo("d");
        assertThat(form.getUsrid04()).isEqualTo("USRID004");
        assertThat(form.getFname04()).isEqualTo("FirstName04");
        assertThat(form.getLname04()).isEqualTo("LastName04");
        assertThat(form.getUtype04()).isEqualTo("4");

        // Row 05.
        form.setSel0005("e");
        form.setUsrid05("USRID005");
        form.setFname05("FirstName05");
        form.setLname05("LastName05");
        form.setUtype05("5");
        assertThat(form.getSel0005()).isEqualTo("e");
        assertThat(form.getUsrid05()).isEqualTo("USRID005");
        assertThat(form.getFname05()).isEqualTo("FirstName05");
        assertThat(form.getLname05()).isEqualTo("LastName05");
        assertThat(form.getUtype05()).isEqualTo("5");

        // Row 06.
        form.setSel0006("f");
        form.setUsrid06("USRID006");
        form.setFname06("FirstName06");
        form.setLname06("LastName06");
        form.setUtype06("6");
        assertThat(form.getSel0006()).isEqualTo("f");
        assertThat(form.getUsrid06()).isEqualTo("USRID006");
        assertThat(form.getFname06()).isEqualTo("FirstName06");
        assertThat(form.getLname06()).isEqualTo("LastName06");
        assertThat(form.getUtype06()).isEqualTo("6");

        // Row 07.
        form.setSel0007("g");
        form.setUsrid07("USRID007");
        form.setFname07("FirstName07");
        form.setLname07("LastName07");
        form.setUtype07("7");
        assertThat(form.getSel0007()).isEqualTo("g");
        assertThat(form.getUsrid07()).isEqualTo("USRID007");
        assertThat(form.getFname07()).isEqualTo("FirstName07");
        assertThat(form.getLname07()).isEqualTo("LastName07");
        assertThat(form.getUtype07()).isEqualTo("7");

        // Row 08.
        form.setSel0008("h");
        form.setUsrid08("USRID008");
        form.setFname08("FirstName08");
        form.setLname08("LastName08");
        form.setUtype08("8");
        assertThat(form.getSel0008()).isEqualTo("h");
        assertThat(form.getUsrid08()).isEqualTo("USRID008");
        assertThat(form.getFname08()).isEqualTo("FirstName08");
        assertThat(form.getLname08()).isEqualTo("LastName08");
        assertThat(form.getUtype08()).isEqualTo("8");

        // Row 09.
        form.setSel0009("i");
        form.setUsrid09("USRID009");
        form.setFname09("FirstName09");
        form.setLname09("LastName09");
        form.setUtype09("9");
        assertThat(form.getSel0009()).isEqualTo("i");
        assertThat(form.getUsrid09()).isEqualTo("USRID009");
        assertThat(form.getFname09()).isEqualTo("FirstName09");
        assertThat(form.getLname09()).isEqualTo("LastName09");
        assertThat(form.getUtype09()).isEqualTo("9");

        // Row 10.
        form.setSel0010("j");
        form.setUsrid10("USRID010");
        form.setFname10("FirstName10");
        form.setLname10("LastName10");
        form.setUtype10("0");
        assertThat(form.getSel0010()).isEqualTo("j");
        assertThat(form.getUsrid10()).isEqualTo("USRID010");
        assertThat(form.getFname10()).isEqualTo("FirstName10");
        assertThat(form.getLname10()).isEqualTo("LastName10");
        assertThat(form.getUtype10()).isEqualTo("0");

        // Trailer (1 field).
        form.setErrmsg("No errors reported on this screen.");
        assertThat(form.getErrmsg()).isEqualTo("No errors reported on this screen.");
    }

    /**
     * Locks the mixed row-index digit-width spellings that make {@code COUSR0A} distinctive:
     * the selection column uses a <strong>four-digit</strong> index ({@code sel0001}), while
     * the user id, first name, last name, and user type columns use a
     * <strong>two-digit</strong> index ({@code usrid01}, {@code fname01}, {@code lname01},
     * {@code utype01}). This test exercises the four-digit {@code getSel0001}, a two-digit
     * {@code getUsrid01}, and the two-digit last-row {@code getUtype10} to guard those exact
     * accessor names against accidental normalization.
     *
     * <p>This user-list screen has no password column, so there is no password accessor to
     * reference here.</p>
     */
    @Test
    void mixed_digit_widths_property_names() {
        COUSR00Form form = new COUSR00Form();

        // sel000N uses a FOUR-digit index; usridNN / fnameNN / lnameNN / utypeNN use a
        // TWO-digit index. The digit widths are intentional and must not be normalized.
        form.setSel0001("Y");        // four-digit selection index
        form.setUsrid01("USER0001"); // two-digit user-id index
        form.setUtype10("A");        // two-digit user-type index, last row

        assertThat(form.getSel0001()).isEqualTo("Y");
        assertThat(form.getUsrid01()).isEqualTo("USER0001");
        assertThat(form.getUtype10()).isEqualTo("A");
    }

    /**
     * Validates the given form and asserts it produces exactly one constraint violation,
     * reported on the supplied property name.
     *
     * @param form     the form to validate
     * @param property the fully qualified bean property expected to carry the single violation
     */
    private static void assertSingleSizeViolationOn(COUSR00Form form, String property) {
        Set<ConstraintViolation<COUSR00Form>> violations = validator.validate(form);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo(property);
    }

    /**
     * Builds a fully populated form whose every property is at or under its {@code @Size}
     * maximum, so validating it yields no violations. Header/paging fields, the name columns,
     * and the trailer are set to exactly their maximum length to exercise the inclusive
     * upper bound; single-character selection/user-type flags and the row user ids are set to
     * valid short values.
     *
     * @return a valid, fully populated {@link COUSR00Form}
     */
    private static COUSR00Form validForm() {
        COUSR00Form form = new COUSR00Form();

        // Header and paging (8 fields), at/under max.
        form.setTrnname("CU00");         // max 4 (exact)
        form.setTitle01("X".repeat(40)); // max 40 (exact)
        form.setCurdate("01/02/25");     // max 8
        form.setPgmname("COUSR00C");     // max 8 (exact)
        form.setTitle02("X".repeat(40)); // max 40 (exact)
        form.setCurtime("12:34:56");     // max 8 (exact)
        form.setPagenum("00000001");     // max 8 (exact)
        form.setUsridin("SRCHUSER");     // max 8 (exact)

        // Row 01.
        form.setSel0001("S");
        form.setUsrid01("USER0001");
        form.setFname01("X".repeat(20));
        form.setLname01("X".repeat(20));
        form.setUtype01("U");

        // Row 02.
        form.setSel0002("S");
        form.setUsrid02("USER0002");
        form.setFname02("X".repeat(20));
        form.setLname02("X".repeat(20));
        form.setUtype02("U");

        // Row 03.
        form.setSel0003("S");
        form.setUsrid03("USER0003");
        form.setFname03("X".repeat(20));
        form.setLname03("X".repeat(20));
        form.setUtype03("U");

        // Row 04.
        form.setSel0004("S");
        form.setUsrid04("USER0004");
        form.setFname04("X".repeat(20));
        form.setLname04("X".repeat(20));
        form.setUtype04("U");

        // Row 05.
        form.setSel0005("S");
        form.setUsrid05("USER0005");
        form.setFname05("X".repeat(20));
        form.setLname05("X".repeat(20));
        form.setUtype05("U");

        // Row 06.
        form.setSel0006("S");
        form.setUsrid06("USER0006");
        form.setFname06("X".repeat(20));
        form.setLname06("X".repeat(20));
        form.setUtype06("U");

        // Row 07.
        form.setSel0007("S");
        form.setUsrid07("USER0007");
        form.setFname07("X".repeat(20));
        form.setLname07("X".repeat(20));
        form.setUtype07("U");

        // Row 08.
        form.setSel0008("S");
        form.setUsrid08("USER0008");
        form.setFname08("X".repeat(20));
        form.setLname08("X".repeat(20));
        form.setUtype08("U");

        // Row 09.
        form.setSel0009("S");
        form.setUsrid09("USER0009");
        form.setFname09("X".repeat(20));
        form.setLname09("X".repeat(20));
        form.setUtype09("U");

        // Row 10.
        form.setSel0010("S");
        form.setUsrid10("USER0010");
        form.setFname10("X".repeat(20));
        form.setLname10("X".repeat(20));
        form.setUtype10("U");

        // Trailer (1 field).
        form.setErrmsg("X".repeat(78)); // max 78 (exact)

        return form;
    }
}
