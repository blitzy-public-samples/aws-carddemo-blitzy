package com.aws.carddemo.dto.screen;

import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link COACTVWForm}. Oracle: legacy/cpy-bms/COACTVW.CPY (BMS mapset COACTVW, map CACTVWA).
 *
 * <p>Pure JUnit 5 (Jupiter) + AssertJ unit test in the COBOL&#8594;Java/Spring Boot migration
 * of AWS CardDemo. It exercises the account-view screen-form DTO
 * {@link com.aws.carddemo.dto.screen.COACTVWForm} - the server-side binding bean for the BMS
 * symbolic map {@code CACTVWAI} (CICS transaction {@code CAVW}, program {@code COACTVWC}) -
 * with no Spring context, no database and no Testcontainers. A Jakarta Bean Validation
 * {@link Validator} is built programmatically so the {@code Size} field-length bounds can be
 * checked in isolation.</p>
 *
 * <p>The behaviors locked down here, in order of importance, are:</p>
 * <ol>
 *   <li>The 37 {@code Size} field-length constraints reproduce the BMS {@code PIC} edits
 *       exactly: a form populated at the boundary has no violations, and a single
 *       one-character overflow raises a violation on precisely that property.</li>
 *   <li>The five display-edited amount fields ({@code acrdlim}, {@code acshlim},
 *       {@code acurbal}, {@code acrcycr}, {@code acrcydb}) are carried as pre-formatted
 *       {@code String} values, never as {@code BigDecimal}/{@code float}/{@code double}, so
 *       the sign, leading zeros and fixed scale survive a round-trip verbatim.</li>
 *   <li>All 37 JavaBean getter/setter pairs round-trip their values.</li>
 *   <li>{@link COACTVWForm#toString()} never leaks the Social Security Number (or any other
 *       field state); it returns only a non-sensitive identity token (CWE-532).</li>
 * </ol>
 *
 * <p>Where the production form and the historical field table disagree, the production form is
 * authoritative: {@code acctsid} is a numeric-edited BMS field ({@code PIC 9(11)}) that is
 * intentionally modeled as a {@code String}, and {@code toString()} is identity-only rather
 * than a {@code ***}-masked rendering. Both are asserted exactly as the class declares them.</p>
 */
class COACTVWFormTest {

    /** Bean Validation factory, created once for the class (no Spring container). */
    private static ValidatorFactory factory;

    /** Validator drawn from {@link #factory}; validates {@code Size} bounds in isolation. */
    private static Validator validator;

    /**
     * Builds a standalone Jakarta Bean Validation {@link Validator} programmatically. This uses
     * the default provider (Hibernate Validator) discovered on the test classpath and does not
     * involve Spring, a database or Testcontainers.
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
    static void closeValidator() {
        factory.close();
    }

    /**
     * Builds a fully populated, valid {@link COACTVWForm} with every one of the 37 properties set
     * to a value of exactly its {@code Size} maximum length. Populating at the boundary lets the
     * "no violations" test serve as proof that each maximum is inclusive, and gives the oversize
     * test a clean baseline in which flipping a single field to {@code max + 1} produces exactly
     * one violation.
     *
     * @return a valid, boundary-populated form
     */
    private COACTVWForm validForm() {
        COACTVWForm form = new COACTVWForm();
        form.setTrnname("X".repeat(4));
        form.setTitle01("X".repeat(40));
        form.setCurdate("X".repeat(8));
        form.setPgmname("X".repeat(8));
        form.setTitle02("X".repeat(40));
        form.setCurtime("X".repeat(8));
        form.setAcctsid("X".repeat(11));
        form.setAcsttus("X".repeat(1));
        form.setAdtopen("X".repeat(10));
        form.setAcrdlim("X".repeat(15));
        form.setAexpdt("X".repeat(10));
        form.setAcshlim("X".repeat(15));
        form.setAreisdt("X".repeat(10));
        form.setAcurbal("X".repeat(15));
        form.setAcrcycr("X".repeat(15));
        form.setAaddgrp("X".repeat(10));
        form.setAcrcydb("X".repeat(15));
        form.setAcstnum("X".repeat(9));
        form.setAcstssn("X".repeat(12));
        form.setAcstdob("X".repeat(10));
        form.setAcstfco("X".repeat(3));
        form.setAcsfnam("X".repeat(25));
        form.setAcsmnam("X".repeat(25));
        form.setAcslnam("X".repeat(25));
        form.setAcsadl1("X".repeat(50));
        form.setAcsstte("X".repeat(2));
        form.setAcsadl2("X".repeat(50));
        form.setAcszipc("X".repeat(5));
        form.setAcscity("X".repeat(50));
        form.setAcsctry("X".repeat(3));
        form.setAcsphn1("X".repeat(13));
        form.setAcsgovt("X".repeat(20));
        form.setAcsphn2("X".repeat(13));
        form.setAcseftc("X".repeat(10));
        form.setAcspflg("X".repeat(1));
        form.setInfomsg("X".repeat(45));
        form.setErrmsg("X".repeat(78));
        return form;
    }

    /**
     * Validates the given form and asserts that it produces exactly one constraint violation,
     * reported on the expected property. The baseline {@link #validForm()} is fully valid, so a
     * single oversize field yields a single {@code Size} violation on precisely that property.
     *
     * @param form     the form to validate (all fields valid except one deliberately oversized)
     * @param property the JavaBean property name expected to carry the violation
     */
    private void assertOnlyViolation(COACTVWForm form, String property) {
        Set<ConstraintViolation<COACTVWForm>> violations = validator.validate(form);
        assertThat(violations).hasSize(1);
        ConstraintViolation<COACTVWForm> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo(property);
    }

    @Test
    void valid_form_has_no_violations() {
        assertThat(validator.validate(validForm())).isEmpty();
    }

    @Test
    void oversize_fields_violate_size_on_exact_property() {
        // One character over the maximum on a representative field from each family: the numeric-
        // edited account id, a money String, the current-balance money String, the PII SSN, a
        // name, an address line, and the two message lines. Each starts from a fully valid form so
        // the sole violation must be on the field that was overflowed.
        COACTVWForm acctsid = validForm();
        acctsid.setAcctsid("X".repeat(12));
        assertOnlyViolation(acctsid, "acctsid");

        COACTVWForm acrdlim = validForm();
        acrdlim.setAcrdlim("X".repeat(16));
        assertOnlyViolation(acrdlim, "acrdlim");

        COACTVWForm acurbal = validForm();
        acurbal.setAcurbal("X".repeat(16));
        assertOnlyViolation(acurbal, "acurbal");

        COACTVWForm acstssn = validForm();
        acstssn.setAcstssn("X".repeat(13));
        assertOnlyViolation(acstssn, "acstssn");

        COACTVWForm acsfnam = validForm();
        acsfnam.setAcsfnam("X".repeat(26));
        assertOnlyViolation(acsfnam, "acsfnam");

        COACTVWForm acsadl1 = validForm();
        acsadl1.setAcsadl1("X".repeat(51));
        assertOnlyViolation(acsadl1, "acsadl1");

        COACTVWForm infomsg = validForm();
        infomsg.setInfomsg("X".repeat(46));
        assertOnlyViolation(infomsg, "infomsg");

        COACTVWForm errmsg = validForm();
        errmsg.setErrmsg("X".repeat(79));
        assertOnlyViolation(errmsg, "errmsg");
    }

    @Test
    void getters_return_set_values() {
        // A distinct sentinel is set on each of the 37 properties (all within the field maximum) so
        // any cross-wired accessor would be caught. This is the bulk of the getter/setter coverage.
        COACTVWForm form = new COACTVWForm();
        form.setTrnname("CAVW");
        form.setTitle01("Account View - Title Line One");
        form.setCurdate("07/19/26");
        form.setPgmname("COACTVWC");
        form.setTitle02("Account View - Title Line Two");
        form.setCurtime("14:30:00");
        form.setAcctsid("00000000123");
        form.setAcsttus("Y");
        form.setAdtopen("2020-01-15");
        form.setAcrdlim("+000005000.00");
        form.setAexpdt("2027-01-31");
        form.setAcshlim("+000001000.00");
        form.setAreisdt("2024-06-30");
        form.setAcurbal("+000001234.56");
        form.setAcrcycr("+000000250.00");
        form.setAaddgrp("GRP0000001");
        form.setAcrcydb("+000000075.25");
        form.setAcstnum("000000042");
        form.setAcstssn("SSN000000012");
        form.setAcstdob("1980-12-25");
        form.setAcstfco("750");
        form.setAcsfnam("JOHNATHAN");
        form.setAcsmnam("MICHAEL");
        form.setAcslnam("SMITHERSON");
        form.setAcsadl1("123 MAIN STREET APT 4B");
        form.setAcsstte("NY");
        form.setAcsadl2("BUILDING C SUITE 200");
        form.setAcszipc("10001");
        form.setAcscity("NEW YORK CITY");
        form.setAcsctry("USA");
        form.setAcsphn1("(212)555-0101");
        form.setAcsgovt("GOVT-ID-0000000123");
        form.setAcsphn2("(212)555-0202");
        form.setAcseftc("EFT0000001");
        form.setAcspflg("P");
        form.setInfomsg("Account retrieved successfully.");
        form.setErrmsg("No errors were detected on this account view screen.");

        assertThat(form.getTrnname()).isEqualTo("CAVW");
        assertThat(form.getTitle01()).isEqualTo("Account View - Title Line One");
        assertThat(form.getCurdate()).isEqualTo("07/19/26");
        assertThat(form.getPgmname()).isEqualTo("COACTVWC");
        assertThat(form.getTitle02()).isEqualTo("Account View - Title Line Two");
        assertThat(form.getCurtime()).isEqualTo("14:30:00");
        assertThat(form.getAcctsid()).isEqualTo("00000000123");
        assertThat(form.getAcsttus()).isEqualTo("Y");
        assertThat(form.getAdtopen()).isEqualTo("2020-01-15");
        assertThat(form.getAcrdlim()).isEqualTo("+000005000.00");
        assertThat(form.getAexpdt()).isEqualTo("2027-01-31");
        assertThat(form.getAcshlim()).isEqualTo("+000001000.00");
        assertThat(form.getAreisdt()).isEqualTo("2024-06-30");
        assertThat(form.getAcurbal()).isEqualTo("+000001234.56");
        assertThat(form.getAcrcycr()).isEqualTo("+000000250.00");
        assertThat(form.getAaddgrp()).isEqualTo("GRP0000001");
        assertThat(form.getAcrcydb()).isEqualTo("+000000075.25");
        assertThat(form.getAcstnum()).isEqualTo("000000042");
        assertThat(form.getAcstssn()).isEqualTo("SSN000000012");
        assertThat(form.getAcstdob()).isEqualTo("1980-12-25");
        assertThat(form.getAcstfco()).isEqualTo("750");
        assertThat(form.getAcsfnam()).isEqualTo("JOHNATHAN");
        assertThat(form.getAcsmnam()).isEqualTo("MICHAEL");
        assertThat(form.getAcslnam()).isEqualTo("SMITHERSON");
        assertThat(form.getAcsadl1()).isEqualTo("123 MAIN STREET APT 4B");
        assertThat(form.getAcsstte()).isEqualTo("NY");
        assertThat(form.getAcsadl2()).isEqualTo("BUILDING C SUITE 200");
        assertThat(form.getAcszipc()).isEqualTo("10001");
        assertThat(form.getAcscity()).isEqualTo("NEW YORK CITY");
        assertThat(form.getAcsctry()).isEqualTo("USA");
        assertThat(form.getAcsphn1()).isEqualTo("(212)555-0101");
        assertThat(form.getAcsgovt()).isEqualTo("GOVT-ID-0000000123");
        assertThat(form.getAcsphn2()).isEqualTo("(212)555-0202");
        assertThat(form.getAcseftc()).isEqualTo("EFT0000001");
        assertThat(form.getAcspflg()).isEqualTo("P");
        assertThat(form.getInfomsg()).isEqualTo("Account retrieved successfully.");
        assertThat(form.getErrmsg()).isEqualTo("No errors were detected on this account view screen.");
    }

    @Test
    void money_fields_are_carried_as_string() {
        // The five amount fields are pre-formatted display Strings, never BigDecimal/float/double.
        // A String store returns the value byte-for-byte; a numeric type would normalize away the
        // sign, leading zeros and fixed scale. Verbatim equality proves the String carriage.
        COACTVWForm form = new COACTVWForm();
        form.setAcrdlim("-00000001234.56");
        form.setAcshlim("+00000005000.00");
        form.setAcurbal("-00000000042.99");
        form.setAcrcycr("00000000250.00");
        form.setAcrcydb("00000000075.25");

        assertThat(form.getAcrdlim()).isEqualTo("-00000001234.56");
        assertThat(form.getAcshlim()).isEqualTo("+00000005000.00");
        assertThat(form.getAcurbal()).isEqualTo("-00000000042.99");
        assertThat(form.getAcrcycr()).isEqualTo("00000000250.00");
        assertThat(form.getAcrcydb()).isEqualTo("00000000075.25");
    }

    @Test
    void toString_masks_ssn() {
        COACTVWForm form = new COACTVWForm();
        form.setAcstssn("123456789012");
        form.setAcsfnam("CONFIDENTIALNAME");
        form.setAcstdob("1980-12-25");
        form.setAcctsid("00000000123");

        String rendered = form.toString();

        // Production COACTVWForm.toString() is identity-only ("COACTVWForm@" + hex): it renders no
        // field state at all (CWE-532 / review finding F9), which is a stronger guarantee than a
        // "***"-masked rendering. Per the binding constraint the production form is authoritative,
        // so the agent-prompt's suggested ".contains(\"***\")" and "acctsid still appears in
        // toString()" checks are intentionally NOT asserted - they would contradict the class.
        assertThat(rendered).doesNotContain("123456789012");
        assertThat(rendered).doesNotContain("CONFIDENTIALNAME");
        assertThat(rendered).doesNotContain("1980-12-25");
        assertThat(rendered).contains("COACTVWForm");

        // acctsid is a numeric-edited BMS field (PIC 9(11)) modeled as a String; verify it
        // round-trips verbatim through the String getter (independently of toString()).
        assertThat(form.getAcctsid()).isEqualTo("00000000123");
    }
}
