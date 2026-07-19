package com.aws.carddemo.util;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure JUnit&nbsp;5 <strong>parity-oracle</strong> unit test for {@link DateConversionService}, the
 * Java replacement for the COBOL program {@code CSUTLDTC} (which wrapped the IBM Language Environment
 * callable service {@code CEEDAYS}).
 *
 * <p><b>Oracle:</b> {@code legacy/cbl/CSUTLDTC.cbl} (source branch {@code app/cbl/CSUTLDTC.cbl}).
 * The COBOL contract reproduced here is
 * {@code PROCEDURE DIVISION USING LS-DATE PIC X(10), LS-DATE-FORMAT PIC X(10), LS-RESULT PIC X(80)}:
 * the program calls {@code CEEDAYS}, runs an {@code EVALUATE TRUE} over the {@code CEEDAYS} feedback
 * 88-levels (L62&ndash;L70) to select a 15-character result message, then moves the CEE severity to
 * {@code RETURN-CODE} via {@code MOVE WS-SEVERITY-N TO RETURN-CODE} (L98), where {@code 0} means
 * success.</p>
 *
 * <p><b>Crown-jewel quirk locked by this test:</b> {@code CSUTLDTC.cbl} L62 declares
 * {@code 88 FC-INVALID-DATE VALUE X'0000000000000000'}. That all-binary-zeros feedback token is the
 * {@code CEE000} <em>success</em> code &mdash; so the paradoxically named 88-level {@code FC-INVALID-DATE}
 * actually denotes a <strong>valid</strong> date, and its {@code EVALUATE} arm (L129&ndash;L130) moves
 * the literal {@code 'Date is valid'} into {@code WS-RESULT}. The Java port preserves the misleading
 * COBOL name verbatim: the enum constant
 * {@link DateConversionService.ValidationOutcome#FC_INVALID_DATE} is the success outcome
 * (severity&nbsp;0, message {@code "Date is valid"}). This test asserts that quirk from both directions:
 * (a) a genuinely valid date reports {@code FC_INVALID_DATE}, and (b) the enum constant itself is
 * severity&nbsp;0 / {@code "Date is valid"}.</p>
 *
 * <p><b>Other parity properties verified:</b></p>
 * <ul>
 *   <li>The {@code java.time} resolver is {@link java.time.format.ResolverStyle#STRICT} over a proleptic
 *       {@code uuuu} year, so an impossible calendar date (Feb&nbsp;30) and an out-of-range month
 *       (month&nbsp;13) are rejected rather than leniently rolled forward.</li>
 *   <li>{@code formattedResult} mirrors {@code LS-RESULT PIC X(80)} and is therefore
 *       <strong>always exactly 80 characters</strong> (space-padded) on every code path.</li>
 * </ul>
 *
 * <p><b>Test design:</b> this is a <em>pure</em> unit test &mdash; no {@code @SpringBootTest}, no Spring
 * context, no autowiring, no database, and it deliberately does not extend any integration base class.
 * {@link DateConversionService} is stateless and thread-safe, so it is instantiated directly with its
 * no-argument constructor. Every asserted value below was verified against the compiled production
 * class before this test was finalized.</p>
 */
class DateConversionServiceTest {

    /**
     * The system under test. {@link DateConversionService} is stateless (it holds only immutable
     * {@link java.time.format.DateTimeFormatter}s), so a single directly-constructed instance is safe
     * to share across the per-method test instances created by JUnit.
     */
    private final DateConversionService service = new DateConversionService();

    // ---------------------------------------------------------------------------------------------
    // Phase 2 - Valid date: severity 0, success outcome (the FC_INVALID_DATE quirk in action).
    // ---------------------------------------------------------------------------------------------

    /**
     * A well-formed, real calendar date validated against an explicit {@code YYYY-MM-DD} mask must be
     * reported valid with CEE severity 0 and the success outcome. This mirrors the COBOL path where
     * {@code CEEDAYS} returns the all-zero {@code FC-INVALID-DATE} feedback token and the
     * {@code EVALUATE} moves {@code 'Date is valid'} into {@code WS-RESULT}
     * (see {@code CSUTLDTC.cbl} L129&ndash;L130, L98).
     */
    @Test
    void validDatePassesWithSeverityZero() {
        var r = service.validateDate("2022-07-18", "YYYY-MM-DD");

        assertTrue(r.isValid(), "a real calendar date must be valid");
        assertEquals(0, r.severity(), "valid date => WS-SEVERITY-N (RETURN-CODE) == 0");
        // The success quirk: CEE000 maps to the misleadingly named FC-INVALID-DATE 88-level.
        assertEquals(DateConversionService.ValidationOutcome.FC_INVALID_DATE, r.outcome(),
                "success is reported via the FC_INVALID_DATE outcome (CSUTLDTC.cbl L62/L129)");
        assertEquals("Date is valid", r.message(),
                "WS-RESULT message for the success arm (CSUTLDTC.cbl L130)");
    }

    /**
     * The single-argument overload defaults the picture mask to {@code YYYYMMDD} (the {@code CSUTLDWY}
     * working-storage default used by the {@code EDIT-DATE-LE} path). A packed {@code 8}-digit date
     * must validate under STRICT adjacent-value parsing of the translated {@code uuuuMMdd} pattern.
     */
    @Test
    void defaultMaskOverloadValidatesYyyymmdd() {
        var r = service.validateDate("20220718");

        assertTrue(r.isValid(), "20220718 is a valid YYYYMMDD date");
        assertEquals(0, r.severity(), "valid date => severity 0");
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 3 - The FC_INVALID_DATE == success quirk asserted directly on the enum constant.
    // ---------------------------------------------------------------------------------------------

    /**
     * Locks the quirk on the enum constant itself, independently of any date input. This preserves
     * {@code CSUTLDTC.cbl} L62 {@code 88 FC-INVALID-DATE VALUE X'0000000000000000'} which paradoxically
     * denotes success (CEE000): the constant is intentionally NOT renamed, and it must carry
     * severity&nbsp;0 and the {@code "Date is valid"} message.
     */
    @Test
    void fcInvalidDateEnumConstantIsTheSuccessCase() {
        // Preserves COBOL CSUTLDTC.cbl L62: 88 FC-INVALID-DATE VALUE X'0000000000000000' -> CEE000
        // success. The all-zero feedback token is the LE "no error" code, so despite the name this
        // constant is the SUCCESS outcome. The name is a faithful COBOL artifact and is not "fixed".
        assertEquals(0, DateConversionService.ValidationOutcome.FC_INVALID_DATE.severity(),
                "FC_INVALID_DATE is CEE000 success => severity 0");
        assertEquals("Date is valid", DateConversionService.ValidationOutcome.FC_INVALID_DATE.message(),
                "FC_INVALID_DATE carries the 'Date is valid' success message (CSUTLDTC.cbl L130)");
    }

    /**
     * Every genuine failure 88-level maps to the exact CEE severity encoded in the {@code CSUTLDTC}
     * feedback token. The failure tokens ({@code CSUTLDTC.cbl} L63-L70) all begin {@code X'0003...'},
     * i.e. severity&nbsp;3 (the CEEDAYS "severe error" severity), while the success token
     * {@code FC-INVALID-DATE} ({@code X'0000...'}, L62) is severity&nbsp;0. The COBOL branches only on
     * {@code WS-SEVERITY-N = 0} vs non-zero, so what this test locks is that failures carry the
     * oracle-exact non-zero severity 3 and are distinct from the success outcome.
     */
    @Test
    void failureOutcomesHaveSeverityThree() {
        assertEquals(3, DateConversionService.ValidationOutcome.FC_BAD_DATE_VALUE.severity(),
                "FC_BAD_DATE_VALUE token X'000309CC...' => severity 3");
        assertEquals(3, DateConversionService.ValidationOutcome.FC_NON_NUMERIC_DATA.severity(),
                "FC_NON_NUMERIC_DATA token X'000309D8...' => severity 3");
        assertNotEquals(DateConversionService.ValidationOutcome.FC_INVALID_DATE,
                DateConversionService.ValidationOutcome.FC_BAD_DATE_VALUE,
                "a failure outcome must never be the FC_INVALID_DATE success outcome");
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 4 - Invalid inputs rejected under the STRICT resolver.
    // ---------------------------------------------------------------------------------------------

    /**
     * A syntactically well-formed but non-existent calendar date (February&nbsp;30) must be rejected.
     * This proves the resolver is {@link java.time.format.ResolverStyle#STRICT}: a lenient resolver
     * would silently roll Feb&nbsp;30 forward to Mar&nbsp;2, which would break parity with
     * {@code CEEDAYS}, which reports the date invalid.
     */
    @Test
    void invalidCalendarDateIsRejected_feb30() {
        var r = service.validateDate("2022-02-30", "YYYY-MM-DD");

        // STRICT ResolverStyle must reject Feb 30 outright rather than rolling it to Mar 2.
        assertFalse(r.isValid(), "Feb 30 is not a real date and must be rejected");
        assertTrue(r.severity() > 0, "an invalid date => non-zero severity (12)");
        assertNotEquals(DateConversionService.ValidationOutcome.FC_INVALID_DATE, r.outcome(),
                "an invalid date must NOT report the FC_INVALID_DATE success outcome");
    }

    /**
     * A month component outside 1&ndash;12 (month&nbsp;13 in {@code 20221318}) must be rejected under
     * the default {@code YYYYMMDD} mask.
     */
    @Test
    void invalidMonthIsRejected() {
        var r = service.validateDate("20221318");

        assertFalse(r.isValid(), "month 13 is out of range and must be rejected");
    }

    /**
     * A non-numeric character where a digit is expected ({@code 20XX0718}) must be rejected. Per the
     * agent contract the primary assertions here are simply {@code !isValid()} and the exact
     * {@code LS-RESULT} width; the specific failure outcome ({@code FC_NON_NUMERIC_DATA}) is asserted
     * too because it was verified against the production mapping and is the parity-correct outcome.
     */
    @Test
    void nonNumericIsRejected() {
        var r = service.validateDate("20XX0718");

        assertFalse(r.isValid(), "non-numeric content must be rejected");
        assertEquals(80, r.formattedResult().length(),
                "LS-RESULT is PIC X(80) even on the failure path");
        // Confirmed against the compiled production classifier: a non-digit where a digit is expected
        // maps to the FC-NON-NUMERIC-DATA feedback 88-level (CSUTLDTC.cbl L69).
        assertEquals(DateConversionService.ValidationOutcome.FC_NON_NUMERIC_DATA, r.outcome(),
                "non-numeric input maps to the FC_NON_NUMERIC_DATA outcome");
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 5 - LS-RESULT PIC X(80) width is exact on both the valid and invalid paths.
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code formattedResult} reproduces the 80-byte {@code LS-RESULT} ({@code PIC X(80)}) record
     * byte-for-byte, so it must be exactly 80 characters regardless of outcome. Consumers such as
     * {@code CORPT00C} re-slice this record at fixed offsets, so the width is a hard contract.
     */
    @Test
    void formattedResultIsAlwaysEightyChars() {
        // Valid path (explicit mask).
        assertEquals(80, service.validateDate("2022-07-18", "YYYY-MM-DD").formattedResult().length(),
                "valid-path LS-RESULT width");
        // Invalid path (explicit mask).
        assertEquals(80, service.validateDate("2022-02-30", "YYYY-MM-DD").formattedResult().length(),
                "invalid-path LS-RESULT width");
        // Default-mask overload path.
        assertEquals(80, service.validateDate("20220718").formattedResult().length(),
                "default-mask LS-RESULT width");
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 6 - epochDay exposes the Java LocalDate.toEpochDay() value on the valid path.
    // ---------------------------------------------------------------------------------------------

    /**
     * On the valid path the production service populates {@code epochDay} with
     * {@link LocalDate#toEpochDay()} of the parsed date (verified in the production source:
     * {@code OptionalLong.of(parsed.toEpochDay())}). Because the field is an
     * {@link java.util.OptionalLong}, the value is read via {@code getAsLong()} &mdash; safe here since
     * the date is valid and the optional is therefore present.
     */
    @Test
    void validDateExposesEpochDay() {
        var r = service.validateDate("2022-07-18", "YYYY-MM-DD");

        assertTrue(r.epochDay().isPresent(), "a valid date must expose its epoch day");
        assertEquals(LocalDate.of(2022, 7, 18).toEpochDay(), r.epochDay().getAsLong(),
                "epochDay must equal LocalDate.toEpochDay() of the parsed date");
    }
}
