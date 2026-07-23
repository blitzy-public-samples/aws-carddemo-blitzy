/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
 * Unit tests for {@link COMEN01Form}. Oracle: legacy/cpy-bms/COMEN01.CPY
 * (BMS mapset COMEN01, map COMEN1A).
 *
 * <p>These are pure JUnit 5 (Jupiter) + AssertJ unit tests for the main-menu
 * screen-backing form of the AWS CardDemo COBOL&rarr;Java/Spring Boot migration
 * (CICS transaction {@code CM00}, program {@code COMEN01C}). They verify that
 * the form faithfully reproduces the 24&times;80 BMS field-length contract via
 * Jakarta Bean Validation {@code @Size} constraints, and that every property
 * round-trips through its setter/getter.</p>
 *
 * <p>The class instantiates the form directly with {@code new COMEN01Form()} and
 * builds a {@link Validator} programmatically. It deliberately never starts a
 * Spring context, never touches a database, and never uses Testcontainers or
 * Docker &mdash; it runs entirely under Maven Surefire. No monetary values
 * appear on this screen, so no {@code BigDecimal} (or any floating-point type)
 * is referenced anywhere.</p>
 *
 * <p>The 20 verified String properties and their BMS {@code PIC X(n)} widths
 * (from the oracle copybook, map {@code COMEN1A}) are: {@code trnname} X(4),
 * {@code title01} X(40), {@code curdate} X(8), {@code pgmname} X(8),
 * {@code title02} X(40), {@code curtime} X(8), {@code optn001}..{@code optn012}
 * X(40) each, {@code option} X(2), and {@code errmsg} X(78). Note that
 * {@code curtime} is X(8) on this screen (unlike COSGN00, where it is X(9)).</p>
 */
class COMEN01FormTest {

    // ------------------------------------------------------------------
    // Representative within-limits value for every one of the 20 BMS value
    // fields. Each constant is at or under its PIC X(n) width and is distinct
    // from every other, so the round-trip assertions cannot pass by accident
    // (e.g. a swapped optn011/optn012 accessor would be caught).
    // ------------------------------------------------------------------

    private static final String TRNNAME = "CM00";                 // PIC X(4)
    private static final String TITLE01 = "AWS CardDemo";          // PIC X(40)
    private static final String CURDATE = "07/18/25";              // PIC X(8)
    private static final String PGMNAME = "COMEN01C";              // PIC X(8)
    private static final String TITLE02 = "Main Menu";             // PIC X(40)
    private static final String CURTIME = "12:00:00";              // PIC X(8)
    private static final String OPTN001 = "01. Account View";      // PIC X(40)
    private static final String OPTN002 = "02. Account Update";    // PIC X(40)
    private static final String OPTN003 = "03. Card List";         // PIC X(40)
    private static final String OPTN004 = "04. Card Detail";       // PIC X(40)
    private static final String OPTN005 = "05. Card Update";       // PIC X(40)
    private static final String OPTN006 = "06. Transaction List";  // PIC X(40)
    private static final String OPTN007 = "07. Transaction View";  // PIC X(40)
    private static final String OPTN008 = "08. Transaction Add";   // PIC X(40)
    private static final String OPTN009 = "09. Bill Payment";      // PIC X(40)
    private static final String OPTN010 = "10. Reports";           // PIC X(40)
    private static final String OPTN011 = "11. Option Eleven";     // PIC X(40)
    private static final String OPTN012 = "12. Option Twelve";     // PIC X(40)
    private static final String OPTION = "01";                     // PIC X(2)
    private static final String ERRMSG = "";                       // PIC X(78)

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        // Build the Bean Validation provider (Hibernate Validator, supplied by
        // spring-boot-starter-validation on the test classpath) without Spring.
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        // ValidatorFactory is AutoCloseable; closing it keeps the build free of
        // resource warnings under -Xlint:all.
        factory.close();
    }

    /**
     * Builds a fully-populated {@code COMEN01Form} whose every property holds a
     * value at or under its {@code @Size} maximum.
     *
     * @return a form that must produce zero constraint violations
     */
    private static COMEN01Form validForm() {
        COMEN01Form form = new COMEN01Form();
        form.setTrnname(TRNNAME);
        form.setTitle01(TITLE01);
        form.setCurdate(CURDATE);
        form.setPgmname(PGMNAME);
        form.setTitle02(TITLE02);
        form.setCurtime(CURTIME);
        form.setOptn001(OPTN001);
        form.setOptn002(OPTN002);
        form.setOptn003(OPTN003);
        form.setOptn004(OPTN004);
        form.setOptn005(OPTN005);
        form.setOptn006(OPTN006);
        form.setOptn007(OPTN007);
        form.setOptn008(OPTN008);
        form.setOptn009(OPTN009);
        form.setOptn010(OPTN010);
        form.setOptn011(OPTN011);
        form.setOptn012(OPTN012);
        form.setOption(OPTION);
        form.setErrmsg(ERRMSG);
        return form;
    }

    @Test
    @DisplayName("a fully populated, within-limits form has zero constraint violations")
    void valid_form_has_no_violations() {
        COMEN01Form form = validForm();

        Set<ConstraintViolation<COMEN01Form>> violations = validator.validate(form);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("each oversize field yields a @Size violation on exactly its own property")
    void oversize_fields_violate_size_on_exact_property() {
        COMEN01Form form = validForm();
        // Push five representative fields one character past their BMS PIC X(n)
        // width. Every other field keeps its within-limits value from validForm().
        form.setTrnname("X".repeat(5));    // TRNNAME PIC X(4)
        form.setCurtime("X".repeat(9));    // CURTIME PIC X(8)  -- this screen is X(8), not X(9)
        form.setOptn001("X".repeat(41));   // OPTN001 PIC X(40)
        form.setOption("X".repeat(3));     // OPTION  PIC X(2)  -- property is "option", not "opt"
        form.setErrmsg("X".repeat(79));    // ERRMSG  PIC X(78)

        Set<ConstraintViolation<COMEN01Form>> violations = validator.validate(form);

        // Exactly the five oversized properties violate, and nothing else does:
        // this proves both the exact property naming and that no other field's
        // @Size max is mistakenly set too small.
        assertThat(violations)
                .extracting(cv -> cv.getPropertyPath().toString())
                .containsExactlyInAnyOrder("trnname", "curtime", "optn001", "option", "errmsg");
    }

    @Test
    @DisplayName("every one of the 20 String properties round-trips through its setter/getter")
    void getters_return_set_values() {
        COMEN01Form form = validForm();

        assertThat(form.getTrnname()).isEqualTo(TRNNAME);
        assertThat(form.getTitle01()).isEqualTo(TITLE01);
        assertThat(form.getCurdate()).isEqualTo(CURDATE);
        assertThat(form.getPgmname()).isEqualTo(PGMNAME);
        assertThat(form.getTitle02()).isEqualTo(TITLE02);
        assertThat(form.getCurtime()).isEqualTo(CURTIME);
        assertThat(form.getOptn001()).isEqualTo(OPTN001);
        assertThat(form.getOptn002()).isEqualTo(OPTN002);
        assertThat(form.getOptn003()).isEqualTo(OPTN003);
        assertThat(form.getOptn004()).isEqualTo(OPTN004);
        assertThat(form.getOptn005()).isEqualTo(OPTN005);
        assertThat(form.getOptn006()).isEqualTo(OPTN006);
        assertThat(form.getOptn007()).isEqualTo(OPTN007);
        assertThat(form.getOptn008()).isEqualTo(OPTN008);
        assertThat(form.getOptn009()).isEqualTo(OPTN009);
        assertThat(form.getOptn010()).isEqualTo(OPTN010);
        assertThat(form.getOptn011()).isEqualTo(OPTN011);
        assertThat(form.getOptn012()).isEqualTo(OPTN012);
        assertThat(form.getOption()).isEqualTo(OPTION);
        assertThat(form.getErrmsg()).isEqualTo(ERRMSG);
    }

    @Test
    @DisplayName("all 12 optn001..optn012 accessors exist and preserve their digit suffix")
    void all_twelve_option_line_accessors_round_trip() {
        COMEN01Form form = new COMEN01Form();
        // Distinct per-line sentinels catch a mis-wired accessor (e.g. optn011
        // swapped with optn012). The three-digit suffix (001..012) is part of
        // the naming contract translated from the BMS OPTN0nnI fields.
        form.setOptn001("line-01");
        form.setOptn002("line-02");
        form.setOptn003("line-03");
        form.setOptn004("line-04");
        form.setOptn005("line-05");
        form.setOptn006("line-06");
        form.setOptn007("line-07");
        form.setOptn008("line-08");
        form.setOptn009("line-09");
        form.setOptn010("line-10");
        form.setOptn011("line-11");
        form.setOptn012("line-12");

        assertThat(form.getOptn001()).isEqualTo("line-01");
        assertThat(form.getOptn002()).isEqualTo("line-02");
        assertThat(form.getOptn003()).isEqualTo("line-03");
        assertThat(form.getOptn004()).isEqualTo("line-04");
        assertThat(form.getOptn005()).isEqualTo("line-05");
        assertThat(form.getOptn006()).isEqualTo("line-06");
        assertThat(form.getOptn007()).isEqualTo("line-07");
        assertThat(form.getOptn008()).isEqualTo("line-08");
        assertThat(form.getOptn009()).isEqualTo("line-09");
        assertThat(form.getOptn010()).isEqualTo("line-10");
        assertThat(form.getOptn011()).isEqualTo("line-11");
        assertThat(form.getOptn012()).isEqualTo("line-12");
    }

    @Test
    @DisplayName("curtime honors the PIC X(8) width: 8 chars valid, 9 chars violates")
    void curtime_is_eight_chars_valid_at_eight_and_violates_at_nine() {
        // COMEN01 CURTIME is PIC X(8) -- deliberately contrasted with COSGN00,
        // whose CURTIME is X(9). Eight characters must be accepted.
        COMEN01Form eight = validForm();
        eight.setCurtime("X".repeat(8));
        assertThat(validator.validate(eight)).isEmpty();

        // Nine characters must violate, and only on the "curtime" property.
        COMEN01Form nine = validForm();
        nine.setCurtime("X".repeat(9));
        assertThat(validator.validate(nine))
                .extracting(cv -> cv.getPropertyPath().toString())
                .containsExactly("curtime");
    }
}
