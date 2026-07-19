package com.aws.carddemo.util;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

    // ---------------------------------------------------------------------------------------------
    // M16 - exact CSUTLDTC linkage / result / feedback contract.
    //
    // Every expected value below is derived from the INDEPENDENT legacy oracle legacy/cbl/CSUTLDTC.cbl,
    // NOT copied from the production enum:
    //   * linkage LS-DATE PIC X(10), LS-DATE-FORMAT PIC X(10), LS-RESULT PIC X(80)  (CSUTLDTC.cbl L84-L86)
    //   * WS-RESULT / LS-RESULT field layout                                        (CSUTLDTC.cbl L43-L57)
    //   * feedback 88-levels FC-* with CEE feedback-code hex tokens                 (CSUTLDTC.cbl L62-L70)
    //   * WS-RESULT message literals                                                (CSUTLDTC.cbl L129-L148)
    // CEE message numbers are decoded from the low-order half-word of each feedback token
    // (e.g. X'0003 09CB' -> severity 3, msgNo 0x09CB = 2507 ; ... ; X'0003 09D9' -> 0x09D9 = 2521),
    // so these assertions are a true parity oracle for the authorized DateConversionService.
    // ---------------------------------------------------------------------------------------------

    /**
     * A missing date (blank or {@code null} LS-DATE) must be reported invalid without abending, mapping
     * to {@code FC-INSUFFICIENT-DATA} (CEE msgNo {@code 2507}, severity {@code 3}). {@code CSUTLDTC}
     * returns a feedback code rather than raising a condition, so the service must never throw for
     * missing input and must still render the full {@code LS-RESULT PIC X(80)} record.
     */
    @Test
    void missingDateIsInsufficientData_noThrow() {
        var blank = assertDoesNotThrow(() -> service.validateDate("        ", "YYYYMMDD"),
                "CSUTLDTC returns a feedback code for missing input; it must never throw");
        assertFalse(blank.isValid(), "a blank date must be invalid");
        assertEquals(3, blank.severity(), "insufficient data => CEE severity 3");
        assertEquals(2507, blank.msgNo(),
                "FC-INSUFFICIENT-DATA => CEE msgNo 2507 (CSUTLDTC.cbl L63 token X'0003 09CB')");
        assertEquals(DateConversionService.ValidationOutcome.FC_INSUFFICIENT_DATA, blank.outcome());
        assertEquals("Insufficient", blank.message(), "WS-RESULT literal (CSUTLDTC.cbl L132)");
        assertFalse(blank.epochDay().isPresent(), "no epoch day on the invalid path");
        assertEquals(80, blank.formattedResult().length(), "LS-RESULT stays PIC X(80)");

        var nul = assertDoesNotThrow(() -> service.validateDate(null),
                "a null LS-DATE is treated as blank, not an exception");
        assertFalse(nul.isValid(), "a null date must be invalid");
        assertEquals(2507, nul.msgNo(), "a null date is also insufficient data (CEE msgNo 2507)");
    }

    /**
     * An unsupported picture mask must fail picture validation first: {@code '.'} is not a
     * {@code CEEDAYS} separator ({@code '-'} / {@code '/'} only), so even a well-formed date yields
     * {@code FC-BAD-PIC-STRING} (CEE msgNo {@code 2518}).
     */
    @Test
    void unrecognizedMaskIsBadPicString() {
        var r = service.validateDate("2022-07-18", "YYYY.MM.DD");

        assertFalse(r.isValid(), "an unrecognized picture mask must be rejected");
        assertEquals(3, r.severity());
        assertEquals(2518, r.msgNo(),
                "FC-BAD-PIC-STRING => CEE msgNo 2518 (CSUTLDTC.cbl L68 token X'0003 09D6')");
        assertEquals(DateConversionService.ValidationOutcome.FC_BAD_PIC_STRING, r.outcome());
        assertEquals("Bad Pic String", r.message(), "WS-RESULT literal (CSUTLDTC.cbl L142)");
        assertFalse(r.epochDay().isPresent());
    }

    /**
     * {@code java.time}'s proleptic {@code uuuu} calendar would accept year {@code 0000}; {@code CEEDAYS}
     * rejects it as {@code FC-YEAR-IN-ERA-ZERO} (CEE msgNo {@code 2521}).
     */
    @Test
    void yearZeroIsYearInEraZero() {
        var r = service.validateDate("00000101", "YYYYMMDD");

        assertFalse(r.isValid(), "year 0000 must be rejected");
        assertEquals(3, r.severity());
        assertEquals(2521, r.msgNo(),
                "FC-YEAR-IN-ERA-ZERO => CEE msgNo 2521 (CSUTLDTC.cbl L70 token X'0003 09D9')");
        assertEquals(DateConversionService.ValidationOutcome.FC_YEAR_IN_ERA_ZERO, r.outcome());
        assertEquals("YearInEra is 0", r.message(), "WS-RESULT literal (CSUTLDTC.cbl L146)");
    }

    /**
     * {@code CEEDAYS}' first supported day is Lillian day 1 = {@code 1582-10-15}. The day before must map
     * to {@code FC-UNSUPP-RANGE} (CEE msgNo {@code 2513} - the exact number {@code CORPT00C} branches on),
     * while the boundary day itself is valid.
     */
    @Test
    void dateBeforeLillianStartIsUnsuppRange_boundaryIsValid() {
        var before = service.validateDate("15821014", "YYYYMMDD");
        assertFalse(before.isValid(), "1582-10-14 is before the CEEDAYS lower bound");
        assertEquals(3, before.severity());
        assertEquals(2513, before.msgNo(),
                "FC-UNSUPP-RANGE => CEE msgNo 2513 (CSUTLDTC.cbl L66 token X'0003 09D1'; CORPT00C branch key)");
        assertEquals(DateConversionService.ValidationOutcome.FC_UNSUPP_RANGE, before.outcome());
        assertEquals("Unsupp. Range", before.message(), "WS-RESULT literal (CSUTLDTC.cbl L138)");

        var boundary = service.validateDate("15821015", "YYYYMMDD");
        assertTrue(boundary.isValid(), "1582-10-15 (Lillian day 1) is the first valid CEEDAYS date");
        assertEquals(0, boundary.severity());
    }

    /**
     * A value that violates the mask literal (a {@code '/'} where the mask expects {@code '-'}) and an
     * impossible calendar day (Feb 30) both map to {@code FC-BAD-DATE-VALUE} (CEE msgNo {@code 2508}).
     */
    @Test
    void separatorMismatchAndImpossibleDayAreBadDateValue() {
        var sep = service.validateDate("2022/07/18", "YYYY-MM-DD");
        assertFalse(sep.isValid(), "a separator that does not match the mask literal is invalid");
        assertEquals(2508, sep.msgNo(),
                "FC-BAD-DATE-VALUE => CEE msgNo 2508 (CSUTLDTC.cbl L64 token X'0003 09CC')");
        assertEquals(DateConversionService.ValidationOutcome.FC_BAD_DATE_VALUE, sep.outcome());

        var feb30 = service.validateDate("2022-02-30", "YYYY-MM-DD");
        assertFalse(feb30.isValid(), "Feb 30 is not a real date");
        assertEquals(2508, feb30.msgNo(), "an impossible day => FC-BAD-DATE-VALUE msgNo 2508");
        assertEquals("Datevalue error", feb30.message(), "WS-RESULT literal (CSUTLDTC.cbl L134)");
    }

    /**
     * A month outside {@code 1..12} maps to {@code FC-INVALID-MONTH} (CEE msgNo {@code 2517}); a non-digit
     * where a digit is expected maps to {@code FC-NON-NUMERIC-DATA} (CEE msgNo {@code 2520}).
     */
    @Test
    void invalidMonthAndNonNumericCarryTheirExactMsgNo() {
        var month = service.validateDate("20221318", "YYYYMMDD");
        assertFalse(month.isValid(), "month 13 is out of range");
        assertEquals(2517, month.msgNo(),
                "FC-INVALID-MONTH => CEE msgNo 2517 (CSUTLDTC.cbl L67 token X'0003 09D5')");
        assertEquals("Invalid month", month.message(), "WS-RESULT literal (CSUTLDTC.cbl L140)");

        var nonNumeric = service.validateDate("20XX0718", "YYYYMMDD");
        assertFalse(nonNumeric.isValid(), "a non-digit where a digit is expected is invalid");
        assertEquals(2520, nonNumeric.msgNo(),
                "FC-NON-NUMERIC-DATA => CEE msgNo 2520 (CSUTLDTC.cbl L69 token X'0003 09D8')");
        assertEquals("Nonnumeric data", nonNumeric.message(), "WS-RESULT literal (CSUTLDTC.cbl L144)");
    }

    /**
     * Exhaustively locks the {@code severity}/{@code msgNo}/{@code message} triple of every feedback
     * outcome against the CEEDAYS 88-levels, including {@code FC-INVALID-ERA} which is retained for
     * 88-level parity but is unreachable under the era-less {@code YYYYMMDD} / {@code YYYY-MM-DD} masks
     * CardDemo uses (documented in {@code docs/decision-log.md}).
     */
    @Test
    void everyOutcomeCarriesItsExactCeedaysMsgNoAndMessage() {
        assertOutcome(DateConversionService.ValidationOutcome.FC_INVALID_DATE, 0, 0, "Date is valid");
        assertOutcome(DateConversionService.ValidationOutcome.FC_INSUFFICIENT_DATA, 3, 2507, "Insufficient");
        assertOutcome(DateConversionService.ValidationOutcome.FC_BAD_DATE_VALUE, 3, 2508, "Datevalue error");
        assertOutcome(DateConversionService.ValidationOutcome.FC_INVALID_ERA, 3, 2509, "Invalid Era");
        assertOutcome(DateConversionService.ValidationOutcome.FC_UNSUPP_RANGE, 3, 2513, "Unsupp. Range");
        assertOutcome(DateConversionService.ValidationOutcome.FC_INVALID_MONTH, 3, 2517, "Invalid month");
        assertOutcome(DateConversionService.ValidationOutcome.FC_BAD_PIC_STRING, 3, 2518, "Bad Pic String");
        assertOutcome(DateConversionService.ValidationOutcome.FC_NON_NUMERIC_DATA, 3, 2520, "Nonnumeric data");
        assertOutcome(DateConversionService.ValidationOutcome.FC_YEAR_IN_ERA_ZERO, 3, 2521, "YearInEra is 0");
    }

    private static void assertOutcome(DateConversionService.ValidationOutcome outcome,
            int expectedSeverity, int expectedMsgNo, String expectedMessage) {
        assertEquals(expectedSeverity, outcome.severity(), outcome + " severity");
        assertEquals(expectedMsgNo, outcome.msgNo(), outcome + " CEE message number");
        assertEquals(expectedMessage, outcome.message(), outcome + " WS-RESULT message");
    }

    /**
     * COBOL passes {@code LS-DATE} and {@code LS-DATE-FORMAT} as {@code PIC X(10)}, right-space-padded.
     * The service trims that padding, so a padded 8-character date/mask in a 10-character field still
     * validates, exactly like a full 10-character ISO date.
     */
    @Test
    void tenCharacterFixedWidthInputsAreAccepted() {
        var padded = service.validateDate("20220718  ", "YYYYMMDD  ");
        assertTrue(padded.isValid(), "space-padded PIC X(10) LS-DATE / LS-DATE-FORMAT must validate");
        assertEquals(0, padded.severity());

        var iso = service.validateDate("2022-07-18", "YYYY-MM-DD");
        assertTrue(iso.isValid(), "a full 10-character YYYY-MM-DD date validates");
        assertEquals(LocalDate.of(2022, 7, 18).toEpochDay(), iso.epochDay().getAsLong(),
                "the valid path exposes the parsed epoch day");
    }

    /**
     * Re-slices the 80-byte {@code LS-RESULT} at the exact copybook offsets (CSUTLDTC.cbl L43-L57):
     * {@code [0,4) WS-SEVERITY | [4,15) 'Mesg Code: ' | [15,19) WS-MSG-NO | [19] sp | [20,35) WS-RESULT |
     * [35] sp | [36,45) 'TstDate: ' | [45,55) WS-DATE | [55] sp | [56,66) 'Mask used:' | [66,76)
     * WS-DATE-FMT | [76] sp | [77,80) spaces}. Locks byte-level {@code LS-RESULT PIC X(80)} fidelity.
     */
    @Test
    void lsResultEightyByteLayoutIsByteExact() {
        String r = service.validateDate("15821014", "YYYYMMDD").formattedResult();

        assertEquals(80, r.length(), "LS-RESULT is PIC X(80)");
        assertEquals("0003", r.substring(0, 4), "WS-SEVERITY 9(4): severity 3 zero-padded");
        assertEquals("Mesg Code: ", r.substring(4, 15), "FILLER 'Mesg Code:' padded to X(11)");
        assertEquals("2513", r.substring(15, 19), "WS-MSG-NO 9(4): FC-UNSUPP-RANGE = 2513");
        assertEquals("Unsupp. Range  ", r.substring(20, 35), "WS-RESULT X(15) message, right-padded");
        assertEquals("TstDate: ", r.substring(36, 45), "FILLER 'TstDate:' padded to X(9)");
        assertEquals("15821014  ", r.substring(45, 55), "WS-DATE X(10): echoed input date, right-padded");
        assertEquals("Mask used:", r.substring(56, 66), "FILLER 'Mask used:' X(10)");
        assertEquals("YYYYMMDD  ", r.substring(66, 76), "WS-DATE-FMT X(10): echoed input mask, right-padded");

        String ok = service.validateDate("20220718", "YYYYMMDD").formattedResult();
        assertEquals("0000", ok.substring(0, 4), "valid => WS-SEVERITY 0000");
        assertEquals("0000", ok.substring(15, 19), "valid => WS-MSG-NO 0000");
    }

    /**
     * Confirms the result-level accessors agree with the outcome on both paths: a valid date exposes
     * severity {@code 0} / msgNo {@code 0} and an epoch day; an invalid date exposes severity {@code 3} /
     * its feedback msgNo and no epoch day.
     */
    @Test
    void resultLevelFieldsMatchOutcomeOnValidAndInvalidPaths() {
        var valid = service.validateDate("2022-07-18", "YYYY-MM-DD");
        assertEquals(0, valid.severity(), "valid => severity 0");
        assertEquals(0, valid.msgNo(), "valid => msgNo 0");
        assertEquals("Date is valid", valid.message());
        assertTrue(valid.epochDay().isPresent(), "valid => epoch day present");

        var invalid = service.validateDate("2022-02-30", "YYYY-MM-DD");
        assertEquals(3, invalid.severity(), "invalid => severity 3");
        assertEquals(2508, invalid.msgNo(), "Feb 30 => FC-BAD-DATE-VALUE msgNo 2508");
        assertFalse(invalid.epochDay().isPresent(), "invalid => no epoch day");
    }
}
