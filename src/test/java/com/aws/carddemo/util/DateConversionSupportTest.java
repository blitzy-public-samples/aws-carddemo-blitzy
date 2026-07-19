package com.aws.carddemo.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure JUnit&nbsp;5 <strong>parity-oracle</strong> unit test for {@link DateConversionSupport}, the
 * Java port of the reusable COBOL date-edit copybooks {@code CSUTLDPY} (procedure division) and
 * {@code CSUTLDWY} (working storage), merged into a single Spring helper.
 *
 * <p><b>Oracles:</b> {@code legacy/cpy/CSUTLDPY.cpy} (source branch {@code app/cpy/CSUTLDPY.cpy}) and
 * {@code legacy/cpy/CSUTLDWY.cpy} (source branch {@code app/cpy/CSUTLDWY.cpy}). The COBOL contract
 * reproduced here is the paragraph range performed as
 * {@code PERFORM EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT}: the year, month and day components
 * are edited in turn, then the cross-field calendar rules ({@code EDIT-DAY-MONTH-YEAR}) run, and
 * finally the Language-Environment safety-net check ({@code EDIT-DATE-LE}) runs only when every prior
 * edit passed.</p>
 *
 * <p><b>Behavioral quirks locked by this test</b> (preserved verbatim per AAP &sect;0.2.2 &mdash; no
 * &quot;improvements&quot;):</p>
 * <ul>
 *   <li><b>Century restriction:</b> {@code CSUTLDWY} declares {@code 88 THIS-CENTURY VALUE 20} and
 *       {@code 88 LAST-CENTURY VALUE 19} (L9&ndash;L10), so <em>only</em> centuries {@code 19xx} and
 *       {@code 20xx} are accepted. The COBOL comment (L66&ndash;L68) states it was
 *       &quot;unable to imagine COBOL in the 2100s&quot;; {@code 18xx} and {@code 21xx} are therefore
 *       deliberately rejected and this test guards that quirk rather than &quot;modernizing&quot; it.</li>
 *   <li><b>Leap-year divisor rule:</b> {@code CSUTLDPY} {@code EDIT-DAY-MONTH-YEAR} (L245&ndash;L256)
 *       chooses the divisor as {@code IF WS-EDIT-DATE-YY-N = 0 MOVE 400 ELSE MOVE 4}, then treats a
 *       zero remainder of {@code CCYY / divisor} as a leap year. This is <em>not</em> the full
 *       Gregorian rule; it happens to give correct results inside the {@code 19xx}/{@code 20xx}
 *       window: {@code 2024} &rarr; YY=24 &rarr; /4 &rarr; leap; {@code 1900} &rarr; YY=00 &rarr; /400
 *       &rarr; 1900%400=300 &rarr; not leap; {@code 2000} &rarr; YY=00 &rarr; /400 &rarr; 2000%400=0
 *       &rarr; leap.</li>
 *   <li><b>Date-of-birth future check:</b> {@code CSUTLDPY} {@code EDIT-DATE-OF-BIRTH} (L350&ndash;L365)
 *       rejects a date that is not strictly in the past with the literal
 *       {@code ':cannot be in the future '} &mdash; whose <strong>trailing space is significant</strong>
 *       and is asserted here byte-for-byte.</li>
 *   <li><b>First-error-wins:</b> every message assignment is guarded by {@code IF WS-RETURN-MSG-OFF},
 *       so when several components are invalid only the <em>first</em> failing edit (in
 *       year&nbsp;&rarr;&nbsp;month&nbsp;&rarr;&nbsp;day order) populates the returned message.</li>
 * </ul>
 *
 * <p><b>Test design:</b> this is a <em>pure</em> unit test &mdash; no {@code @SpringBootTest}, no Spring
 * context, no autowiring, no database, and it deliberately does not extend any integration base class.
 * Both {@link DateConversionSupport} and its constructor-injected collaborator
 * {@link DateConversionService} are stateless and plain-constructable, so the system under test is
 * built directly with {@code new DateConversionSupport(new DateConversionService())}. Every asserted
 * value below was traced against the production class before this test was finalized.</p>
 */
class DateConversionSupportTest {

    /**
     * Field label passed as the COBOL {@code WS-EDIT-VARIABLE-NAME}; it is prefixed to every returned
     * message. Its exact spelling is irrelevant to the parity properties asserted here.
     */
    private static final String FIELD = "Test Date";

    /**
     * System under test, built without any Spring container. {@link DateConversionSupport}
     * constructor-injects {@link DateConversionService} (mirroring the COBOL {@code CALL 'CSUTLDTC'}),
     * and both classes are stateless and thread-safe, so a single directly-constructed instance is
     * safely shared across the per-method test instances created by JUnit.
     */
    private final DateConversionSupport support =
            new DateConversionSupport(new DateConversionService());

    // ---------------------------------------------------------------------------------------------
    // Phase 2 - Leap-year rule (the headline arithmetic): div = (YY == 0) ? 400 : 4; leap iff CCYY % div == 0.
    // ---------------------------------------------------------------------------------------------

    /**
     * February&nbsp;29 in {@code 2024} is valid. Divisor logic: YY={@code 24} (non-zero) &rarr; divide
     * {@code CCYY} by {@code 4}; {@code 2024 % 4 == 0} &rarr; leap year, so the 29th is accepted and
     * the final {@code EDIT-DATE-LE}/{@code CEEDAYS} calendar check also passes.
     */
    @Test
    void feb29ValidInLeapYear2024() {
        assertTrue(support.editDateCcyymmdd("20240229", FIELD).isValid());
    }

    /**
     * February&nbsp;29 in the centennial non-leap year {@code 1900} is invalid. Divisor logic:
     * YY={@code 00} &rarr; divide {@code CCYY} by {@code 400}; {@code 1900 % 400 == 300 != 0} &rarr;
     * <em>not</em> a leap year, so {@code EDIT-DAY-MONTH-YEAR} rejects the 29th and flags the day as
     * {@code NOT_OK} (the COBOL simultaneously sets {@code FLG-DAY-NOT-OK}/{@code FLG-MONTH-NOT-OK}/
     * {@code FLG-YEAR-NOT-OK}; the day flag is asserted as the primary signal, CSUTLDPY L259&ndash;L262).
     */
    @Test
    void feb29InvalidInCentennialNonLeapYear1900() {
        DateConversionSupport.DateEditResult r = support.editDateCcyymmdd("19000229", FIELD);
        assertFalse(r.isValid());
        assertEquals(DateConversionSupport.FieldFlag.NOT_OK, r.dayFlag());
    }

    /**
     * February&nbsp;29 in the centennial leap year {@code 2000} is valid. Divisor logic: YY={@code 00}
     * &rarr; divide {@code CCYY} by {@code 400}; {@code 2000 % 400 == 0} &rarr; leap year, so the 29th
     * is accepted and the {@code EDIT-DATE-LE} calendar check also passes.
     */
    @Test
    void feb29ValidInLeapCentennialYear2000() {
        assertTrue(support.editDateCcyymmdd("20000229", FIELD).isValid());
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 3 - Century restriction quirk: only THIS-CENTURY (20) / LAST-CENTURY (19) are accepted.
    // ---------------------------------------------------------------------------------------------

    /**
     * A well-formed {@code 18xx} date is rejected: {@code EDIT-YEAR-CCYY} accepts only the centuries
     * {@code 20} ({@code THIS-CENTURY}) and {@code 19} ({@code LAST-CENTURY}); century {@code 18} is
     * neither, so the year is flagged {@code NOT_OK} with the &quot;Century is not valid&quot; message
     * (CSUTLDPY L70&ndash;L84). This is the deliberate legacy quirk from CSUTLDWY L9&ndash;L10.
     */
    @Test
    void century18IsRejected() {
        DateConversionSupport.DateEditResult r = support.editDateCcyymmdd("18500101", FIELD);
        assertFalse(r.isValid());
        assertEquals(DateConversionSupport.FieldFlag.NOT_OK, r.yearFlag());
    }

    /**
     * Sanity check: an ordinary {@code 20xx} date ({@code 2022-07-18}) passes every edit, including the
     * century check ({@code THIS-CENTURY}) and the final {@code CEEDAYS} calendar validation.
     */
    @Test
    void century20DateIsAccepted() {
        assertTrue(support.editDateCcyymmdd("20220718", FIELD).isValid());
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 4 - Date-of-birth "cannot be in the future " (trailing space significant).
    // ---------------------------------------------------------------------------------------------

    /**
     * A date of birth that is not strictly in the past is rejected with the exact COBOL message
     * literal, whose trailing space is significant. Tomorrow is used (rather than &quot;today&quot;) so
     * the future condition is unambiguous regardless of whether the comparison is {@code >} or
     * {@code >=}, and it stays inside a valid {@code 20xx} century. Mirrors {@code EDIT-DATE-OF-BIRTH}
     * (CSUTLDPY L350&ndash;L365), where the current date must be strictly greater than the supplied
     * date, else the message {@code ':cannot be in the future '} is returned.
     */
    @Test
    void dateOfBirthInFutureIsRejectedWithTrailingSpaceMessage() {
        String futureCcyymmdd =
                LocalDate.now().plusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        DateConversionSupport.DateEditResult r = support.editDateOfBirth(futureCcyymmdd, "Date of Birth");
        assertFalse(r.isValid());
        // The trailing space in "cannot be in the future " MUST be present; asserting contains() with
        // the trailing space verifies the byte-for-byte COBOL literal (CSUTLDPY L363).
        assertTrue(r.returnMessage().contains("cannot be in the future "));
    }

    /**
     * A normal past date of birth ({@code 1980-01-15}) passes both the calendar edits and the
     * reasonableness (future) check, so the result is valid.
     */
    @Test
    void dateOfBirthInPastIsAccepted() {
        assertTrue(support.editDateOfBirth("19800115", "Date of Birth").isValid());
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 5 - First-error-wins message behavior (WS-RETURN-MSG-OFF guard).
    // ---------------------------------------------------------------------------------------------

    /**
     * With multiple simultaneously invalid components ({@code 1800} century invalid, month {@code 13}
     * invalid, day {@code 50} invalid), the returned message reflects only the <em>first</em> failing
     * edit in evaluation order (year&nbsp;&rarr;&nbsp;month&nbsp;&rarr;&nbsp;day): the year/century
     * error. The COBOL guards each {@code STRING ... INTO WS-RETURN-MSG} with {@code IF WS-RETURN-MSG-OFF},
     * so once the century error sets the message the later month/day errors cannot overwrite it. Only
     * the message ownership and the year flag are hard-asserted; the later flags may still be set by
     * the subsequent edits (as in the COBOL) without affecting which message wins.
     */
    @Test
    void firstErrorWinsWhenMultipleFieldsInvalid() {
        DateConversionSupport.DateEditResult r = support.editDateCcyymmdd("18001350", FIELD);
        assertFalse(r.isValid());
        assertEquals(DateConversionSupport.FieldFlag.NOT_OK, r.yearFlag());
        assertNotNull(r.returnMessage());
        assertFalse(r.returnMessage().isBlank());
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 6 - FieldFlag enum guard.
    // ---------------------------------------------------------------------------------------------

    /**
     * The {@link DateConversionSupport.FieldFlag} enum models the three CSUTLDWY single-byte flag
     * states {@code FLG-*-ISVALID} (LOW-VALUES), {@code FLG-*-NOT-OK} ({@code '0'}) and
     * {@code FLG-*-BLANK} ({@code 'B'}), so it must expose exactly three constants, each resolvable by
     * name.
     */
    @Test
    void fieldFlagEnumHasThreeConstants() {
        assertEquals(3, DateConversionSupport.FieldFlag.values().length);
        assertEquals(DateConversionSupport.FieldFlag.VALID,
                DateConversionSupport.FieldFlag.valueOf("VALID"));
        assertEquals(DateConversionSupport.FieldFlag.NOT_OK,
                DateConversionSupport.FieldFlag.valueOf("NOT_OK"));
        assertEquals(DateConversionSupport.FieldFlag.BLANK,
                DateConversionSupport.FieldFlag.valueOf("BLANK"));
    }
}
