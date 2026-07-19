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
 * Unit tests for {@link COADM01Form}. Oracle: legacy/cpy-bms/COADM01.CPY
 * (BMS mapset COADM01, map COADM1A).
 *
 * <p>These tests lock the admin-menu screen form against the fixed-width field
 * contract of its BMS symbolic map. Each of the 20 {@code PIC X(n)} value fields
 * maps to a {@code String} property carrying a {@code @Size(max = n)} constraint;
 * the tests verify that a form filled at or under every maximum is valid, that a
 * single character over any maximum trips {@code @Size} on the exact property,
 * and that all getters/setters round-trip. The admin menu is structurally
 * identical to the main menu (COMEN01): 20 fields, no password field, and no
 * monetary field.</p>
 *
 * <p>This is a pure unit test: it builds a standalone Jakarta Bean Validation
 * {@link jakarta.validation.Validator} and instantiates {@link COADM01Form}
 * directly, touching no database, Spring context, or Testcontainers.</p>
 */
class COADM01FormTest {

    /** Factory backing the standalone validator; released in {@link #tearDownValidator()}. */
    private static ValidatorFactory factory;

    /** Bean Validation validator used to exercise the {@code @Size} constraints. */
    private static Validator validator;

    /**
     * Builds a standalone Jakarta Bean Validation {@link Validator} once for the
     * whole class. No Spring context is involved: the Hibernate Validator engine
     * is resolved from the classpath contributed by {@code spring-boot-starter-validation}.
     */
    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    /**
     * Releases the {@link ValidatorFactory} resources created for this test class.
     */
    @AfterAll
    static void tearDownValidator() {
        factory.close();
    }

    /**
     * Builds a fully-populated form whose every field is at or under its
     * {@code @Size} maximum, so a fresh instance yields zero constraint
     * violations. Each option line is filled to the 40-character maximum to
     * exercise the upper boundary.
     *
     * @return a valid {@link COADM01Form} instance
     */
    private COADM01Form validForm() {
        COADM01Form form = new COADM01Form();
        form.setTrnname("CA00");            // TRNNAME PIC X(4)
        form.setTitle01("X".repeat(40));    // TITLE01 PIC X(40)
        form.setCurdate("07/18/25");        // CURDATE PIC X(8)
        form.setPgmname("COADM01C");        // PGMNAME PIC X(8)
        form.setTitle02("X".repeat(40));    // TITLE02 PIC X(40)
        form.setCurtime("12:34:56");        // CURTIME PIC X(8)
        form.setOptn001("X".repeat(40));    // OPTN001 PIC X(40)
        form.setOptn002("X".repeat(40));    // OPTN002 PIC X(40)
        form.setOptn003("X".repeat(40));    // OPTN003 PIC X(40)
        form.setOptn004("X".repeat(40));    // OPTN004 PIC X(40)
        form.setOptn005("X".repeat(40));    // OPTN005 PIC X(40)
        form.setOptn006("X".repeat(40));    // OPTN006 PIC X(40)
        form.setOptn007("X".repeat(40));    // OPTN007 PIC X(40)
        form.setOptn008("X".repeat(40));    // OPTN008 PIC X(40)
        form.setOptn009("X".repeat(40));    // OPTN009 PIC X(40)
        form.setOptn010("X".repeat(40));    // OPTN010 PIC X(40)
        form.setOptn011("X".repeat(40));    // OPTN011 PIC X(40)
        form.setOptn012("X".repeat(40));    // OPTN012 PIC X(40)
        form.setOption("01");               // OPTION  PIC X(2)
        form.setErrmsg("X".repeat(78));     // ERRMSG  PIC X(78)
        return form;
    }

    /**
     * A form populated at or under every {@code @Size} maximum produces no
     * Bean Validation violations.
     */
    @Test
    void valid_form_has_no_violations() {
        assertThat(validator.validate(validForm())).isEmpty();
    }

    /**
     * A single character over the declared maximum trips the {@code @Size}
     * constraint, and the violation is reported against the exact property.
     * Representative fields of every distinct width are checked: TRNNAME X(4),
     * CURTIME X(8), OPTN012 X(40), OPTION X(2), and ERRMSG X(78).
     */
    @Test
    void oversize_fields_violate_size_on_exact_property() {
        COADM01Form form = validForm();
        form.setTrnname("X".repeat(5));     // TRNNAME PIC X(4): 5 chars is one over max.
        form.setCurtime("X".repeat(9));     // CURTIME PIC X(8): 9 chars is one over max.
        form.setOptn012("X".repeat(41));    // OPTN012 PIC X(40): 41 chars is one over max.
        form.setOption("X".repeat(3));      // OPTION  PIC X(2): 3 chars is one over max.
        form.setErrmsg("X".repeat(79));     // ERRMSG  PIC X(78): 79 chars is one over max.

        Set<ConstraintViolation<COADM01Form>> violations = validator.validate(form);

        // Exactly the five oversize fields violate - nothing else in the form does.
        assertThat(violations).hasSize(5);
        assertThat(violations).anyMatch(cv -> cv.getPropertyPath().toString().equals("trnname"));
        assertThat(violations).anyMatch(cv -> cv.getPropertyPath().toString().equals("curtime"));
        assertThat(violations).anyMatch(cv -> cv.getPropertyPath().toString().equals("optn012"));
        assertThat(violations).anyMatch(cv -> cv.getPropertyPath().toString().equals("option"));
        assertThat(violations).anyMatch(cv -> cv.getPropertyPath().toString().equals("errmsg"));
    }

    /**
     * Every property's setter stores the value returned by its getter. All 20
     * BMS value fields are exercised with distinct values so a mis-wired
     * accessor is detected.
     */
    @Test
    void getters_return_set_values() {
        COADM01Form form = new COADM01Form();
        form.setTrnname("CA00");
        form.setTitle01("first title line");
        form.setCurdate("07/18/25");
        form.setPgmname("COADM01C");
        form.setTitle02("second title line");
        form.setCurtime("12:34:56");
        form.setOptn001("option line 01");
        form.setOptn002("option line 02");
        form.setOptn003("option line 03");
        form.setOptn004("option line 04");
        form.setOptn005("option line 05");
        form.setOptn006("option line 06");
        form.setOptn007("option line 07");
        form.setOptn008("option line 08");
        form.setOptn009("option line 09");
        form.setOptn010("option line 10");
        form.setOptn011("option line 11");
        form.setOptn012("option line 12");
        form.setOption("05");
        form.setErrmsg("error message line");

        assertThat(form.getTrnname()).isEqualTo("CA00");
        assertThat(form.getTitle01()).isEqualTo("first title line");
        assertThat(form.getCurdate()).isEqualTo("07/18/25");
        assertThat(form.getPgmname()).isEqualTo("COADM01C");
        assertThat(form.getTitle02()).isEqualTo("second title line");
        assertThat(form.getCurtime()).isEqualTo("12:34:56");
        assertThat(form.getOptn001()).isEqualTo("option line 01");
        assertThat(form.getOptn002()).isEqualTo("option line 02");
        assertThat(form.getOptn003()).isEqualTo("option line 03");
        assertThat(form.getOptn004()).isEqualTo("option line 04");
        assertThat(form.getOptn005()).isEqualTo("option line 05");
        assertThat(form.getOptn006()).isEqualTo("option line 06");
        assertThat(form.getOptn007()).isEqualTo("option line 07");
        assertThat(form.getOptn008()).isEqualTo("option line 08");
        assertThat(form.getOptn009()).isEqualTo("option line 09");
        assertThat(form.getOptn010()).isEqualTo("option line 10");
        assertThat(form.getOptn011()).isEqualTo("option line 11");
        assertThat(form.getOptn012()).isEqualTo("option line 12");
        assertThat(form.getOption()).isEqualTo("05");
        assertThat(form.getErrmsg()).isEqualTo("error message line");
    }

    /**
     * The twelve admin-menu option lines (OPTN001..OPTN012, each PIC X(40)) are
     * independent accessors. Distinct sentinel values confirm none is
     * cross-wired to a neighbouring option field.
     */
    @Test
    void all_twelve_option_line_accessors_round_trip_without_cross_wiring() {
        COADM01Form form = new COADM01Form();
        form.setOptn001("v01");
        form.setOptn002("v02");
        form.setOptn003("v03");
        form.setOptn004("v04");
        form.setOptn005("v05");
        form.setOptn006("v06");
        form.setOptn007("v07");
        form.setOptn008("v08");
        form.setOptn009("v09");
        form.setOptn010("v10");
        form.setOptn011("v11");
        form.setOptn012("v12");

        assertThat(form.getOptn001()).isEqualTo("v01");
        assertThat(form.getOptn002()).isEqualTo("v02");
        assertThat(form.getOptn003()).isEqualTo("v03");
        assertThat(form.getOptn004()).isEqualTo("v04");
        assertThat(form.getOptn005()).isEqualTo("v05");
        assertThat(form.getOptn006()).isEqualTo("v06");
        assertThat(form.getOptn007()).isEqualTo("v07");
        assertThat(form.getOptn008()).isEqualTo("v08");
        assertThat(form.getOptn009()).isEqualTo("v09");
        assertThat(form.getOptn010()).isEqualTo("v10");
        assertThat(form.getOptn011()).isEqualTo("v11");
        assertThat(form.getOptn012()).isEqualTo("v12");
    }

    /**
     * The user's selection is carried by the OPTION field (PIC X(2)), exposed as
     * {@code getOption()}/{@code setOption(String)} - the property is
     * {@code option}, not {@code opt}. This admin-menu screen has no password
     * field and no monetary field (the money-as-String category is therefore not
     * applicable), so {@link COADM01Form#toString()} exposes no sensitive data.
     */
    @Test
    void option_is_selection_property_and_form_has_no_password_field() {
        COADM01Form form = new COADM01Form();
        form.setOption("07");

        assertThat(form.getOption()).isEqualTo("07");
        // toString() renders the selection value and, with no password field
        // present, discloses nothing sensitive.
        assertThat(form.toString()).contains("option='07'");
    }

    /**
     * CURTIME is PIC X(8): a value of exactly eight characters is valid, while a
     * ninth character trips {@code @Size} on the {@code curtime} property.
     */
    @Test
    void curtime_field_is_eight_char_boundary() {
        COADM01Form form = validForm();

        form.setCurtime("X".repeat(8));
        assertThat(validator.validate(form)).isEmpty();

        form.setCurtime("X".repeat(9));
        assertThat(validator.validate(form))
                .anyMatch(cv -> cv.getPropertyPath().toString().equals("curtime"));
    }
}
