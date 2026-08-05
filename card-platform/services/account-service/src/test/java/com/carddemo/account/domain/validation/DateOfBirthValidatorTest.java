package com.carddemo.account.domain.validation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DateOfBirthValidator}, the date of birth reasonableness check.
 *
 * <p>The validator realises paragraph {@code EDIT-DATE-OF-BIRTH} at
 * app/cpy/CSUTLDPY.cpy:L341-L369, whose exit paragraph sits at app/cpy/CSUTLDPY.cpy:L370. One call
 * site reaches the paragraph, at app/cbl/COACTUPC.cbl:L1540, and app/cbl/COACTUPC.cbl:L1533
 * supplies the label {@code 'Date of Birth'} that call site passes.</p>
 *
 * <p>The comparison at app/cpy/CSUTLDPY.cpy:L350 is strictly greater, and app/cpy/CSUTLDPY.cpy:L354
 * holds {@code CONTINUE} on its true limb. Today's day count must exceed the day count of the
 * supplied value for the check to pass.</p>
 *
 * <p>Yesterday, today, and tomorrow all carry weight here, and together they pin the boundary to
 * one day. The three commented-out {@code FUNCTION FIND-DURATION} statements at
 * app/cpy/CSUTLDPY.cpy:L351-L353 reach no verdict.</p>
 *
 * <p>Calendar rules are out of scope here. app/cbl/COACTUPC.cbl:L1539 gates the paragraph on
 * app/cbl/COACTUPC.cbl:L1536 having accepted the value. Every date supplied is therefore well
 * formed and lands inside the span those field edits admit.
 * {@code CalendarDateValidatorTest} owns the field edits, and
 * {@code com.carddemo.cobol.CobolDateValidatorTest} owns the day-count conversions of
 * app/cpy/CSUTLDPY.cpy:L345-L348. {@code domain/AccountUpdateService} owns that orchestration
 * gate.</p>
 *
 * <p>One message reaches the caller. app/cpy/CSUTLDPY.cpy:L361-L365 builds it from
 * {@code FUNCTION TRIM} over the {@code PIC X(25)} label field at app/cbl/COACTUPC.cbl:L53, joined
 * to the text at app/cpy/CSUTLDPY.cpy:L363. The guard at app/cpy/CSUTLDPY.cpy:L360 tests
 * {@code WS-RETURN-MSG PIC X(75)} at app/cbl/COACTUPC.cbl:L479 for spaces. Its condition name sits
 * at app/cbl/COACTUPC.cbl:L480, and app/cbl/COACTUPC.cbl:L876 clears the slot once per pass.</p>
 *
 * <p>app/cpy/CSUTLDPY.cpy:L356-L359 also sets the input-error state and all three field flag bytes
 * on the failure path. {@link EditResult} carries a verdict and at most one message, and no flag
 * byte reaches it.</p>
 *
 * <p>No Spring context, no container and no database take part.</p>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("DateOfBirthValidator, the reasonableness check that rejects today's own date")
class DateOfBirthValidatorTest {

    /** The label app/cbl/COACTUPC.cbl:L1533 supplies to the one call site. */
    private static final String CALL_SITE_LABEL = "Date of Birth";

    /** Declared width of {@code WS-EDIT-VARIABLE-NAME} at app/cbl/COACTUPC.cbl:L53. */
    private static final int LABEL_FIELD_WIDTH = 25;

    /** {@link #CALL_SITE_LABEL} padded with spaces to the width of the label field. */
    private static final String PADDED_CALL_SITE_LABEL =
            CALL_SITE_LABEL + " ".repeat(LABEL_FIELD_WIDTH - CALL_SITE_LABEL.length());

    /** A label field holding only spaces, which {@code FUNCTION TRIM} reduces to no characters. */
    private static final String ALL_SPACES_LABEL = " ".repeat(LABEL_FIELD_WIDTH);

    /**
     * The text at app/cpy/CSUTLDPY.cpy:L363, carried character for character. The text opens with a
     * colon and no space, holds a lower-case {@code cannot}, and closes with one space.
     */
    private static final String FUTURE_DATE_MESSAGE = ":cannot be in the future ";

    /** Character count of the text at app/cpy/CSUTLDPY.cpy:L363. */
    private static final int FUTURE_DATE_MESSAGE_LENGTH = 25;

    /** The whole message a rejected date produces for {@link #CALL_SITE_LABEL}. */
    private static final String CALL_SITE_FUTURE_DATE_MESSAGE =
            "Date of Birth:cannot be in the future ";

    /**
     * The fixed day the comparison at app/cpy/CSUTLDPY.cpy:L350 runs against: Sunday, 2 August 2026.
     *
     * <p>Every case below builds its input from this date and hands the same date to the production
     * overload, so no verdict depends on when the suite runs.
     */
    private static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 8, 2);

    /** Day offset of the reference date, the equality boundary of app/cpy/CSUTLDPY.cpy:L350. */
    private static final long TODAY = 0L;

    /** Day offset of the day app/cpy/CSUTLDPY.cpy:L350 admits nearest the boundary. */
    private static final long YESTERDAY = -1L;

    /** Day offset of the day app/cpy/CSUTLDPY.cpy:L350 rejects nearest the boundary. */
    private static final long TOMORROW = 1L;

    /** Years back for a date of birth well under the app/cpy/CSUTLDPY.cpy:L350 boundary. */
    private static final int YEARS_BEFORE_TODAY = 40;

    /** Seven day offsets app/cpy/CSUTLDPY.cpy:L350 admits, one day back to forty years back. */
    private static final List<Long> ACCEPTED_DAY_OFFSETS =
            List.of(-14600L, -3650L, -365L, -30L, -7L, -2L, -1L);

    /** Seven day offsets app/cpy/CSUTLDPY.cpy:L350 rejects, opening at the equality boundary. */
    private static final List<Long> REJECTED_DAY_OFFSETS =
            List.of(0L, 1L, 2L, 7L, 30L, 365L, 1825L);

    /** Two adjacent spaces. A trimmed app/cbl/COACTUPC.cbl:L53 field never emits this pair. */
    private static final String TWO_SPACES = "  ";

    /** A space ahead of the colon. A trimmed app/cbl/COACTUPC.cbl:L53 field never emits it. */
    private static final String SPACE_BEFORE_COLON = " :";

    /** A space after the colon, which app/cpy/CSUTLDPY.cpy:L363 does not carry. */
    private static final String COLON_AND_SPACE = ": ";

    /** An upper-case {@code Cannot}, which app/cpy/CSUTLDPY.cpy:L363 does not carry. */
    private static final String CAPITALISED_CANNOT = "Cannot";

    /** The lower-case {@code cannot} that app/cpy/CSUTLDPY.cpy:L363 carries. */
    private static final String LOWER_CASE_CANNOT = "cannot";

    /** The colon and the first character of the text at app/cpy/CSUTLDPY.cpy:L363. */
    private static final String COLON_AND_FIRST_CHARACTER = ":c";

    /** One space, the character app/cpy/CSUTLDPY.cpy:L363 closes on. */
    private static final String ONE_SPACE = " ";

    /** A full stop, which app/cpy/CSUTLDPY.cpy:L363 does not carry. */
    private static final String FULL_STOP = ".";

    /**
     * Covers the passing limb of app/cpy/CSUTLDPY.cpy:L350, where today's day count exceeds the
     * day count of the supplied value and app/cpy/CSUTLDPY.cpy:L354 reaches {@code CONTINUE}.
     */
    @Test
    @DisplayName("A date forty years before today passes and leaves the message slot empty")
    void pastDateOfBirthPasses() {
        EditResult result =
                DateOfBirthValidator.validate(CALL_SITE_LABEL,
                        dateYearsBeforeReferenceDate(YEARS_BEFORE_TODAY), REFERENCE_DATE);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
    }

    /**
     * Holds the equality boundary of app/cpy/CSUTLDPY.cpy:L350. The comparison is strictly greater,
     * so an equal day count leaves app/cpy/CSUTLDPY.cpy:L354 unreached, falls to the {@code ELSE}
     * at app/cpy/CSUTLDPY.cpy:L355, and composes the text at app/cpy/CSUTLDPY.cpy:L363.
     */
    @Test
    @DisplayName("Today's own date fails, so the equality boundary is a rejection")
    void todayIsRejectedAtTheEqualityBoundary() {
        EditResult result = DateOfBirthValidator.validate(CALL_SITE_LABEL,
                        dateOffsetFromReferenceDate(TODAY), REFERENCE_DATE);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(CALL_SITE_FUTURE_DATE_MESSAGE);
        assertThat(result.message()).endsWith(FUTURE_DATE_MESSAGE);
    }

    /**
     * Covers the limb one day over the boundary. app/cpy/CSUTLDPY.cpy:L338 records a date of birth
     * in the future as unacceptable.
     */
    @Test
    @DisplayName("Tomorrow fails and carries the one message today carries")
    void tomorrowIsRejected() {
        EditResult result = DateOfBirthValidator.validate(CALL_SITE_LABEL,
                        dateOffsetFromReferenceDate(TOMORROW), REFERENCE_DATE);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(CALL_SITE_FUTURE_DATE_MESSAGE);
    }

    /** Covers the limb one day under the boundary, the nearest date app/cpy/CSUTLDPY.cpy:L350 admits. */
    @Test
    @DisplayName("Yesterday passes, one day under the equality boundary")
    void yesterdayPasses() {
        EditResult result = DateOfBirthValidator.validate(CALL_SITE_LABEL,
                        dateOffsetFromReferenceDate(YESTERDAY), REFERENCE_DATE);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
    }

    /**
     * Reads the three adjacent days in one method, which leaves the boundary of
     * app/cpy/CSUTLDPY.cpy:L350 one day wide and admits no off-by-one.
     */
    @Test
    @DisplayName("Yesterday passes while today and tomorrow both fail, which pins the boundary to one day")
    void boundarySpansOneSingleDay() {
        EditResult dayBefore =
                DateOfBirthValidator.validate(CALL_SITE_LABEL,
                        dateOffsetFromReferenceDate(YESTERDAY), REFERENCE_DATE);
        EditResult sameDay = DateOfBirthValidator.validate(CALL_SITE_LABEL,
                        dateOffsetFromReferenceDate(TODAY), REFERENCE_DATE);
        EditResult dayAfter =
                DateOfBirthValidator.validate(CALL_SITE_LABEL,
                        dateOffsetFromReferenceDate(TOMORROW), REFERENCE_DATE);

        assertThat(dayBefore.valid()).isTrue();
        assertThat(dayBefore.message()).isNull();

        assertThat(sameDay.valid()).isFalse();
        assertThat(sameDay.message()).isEqualTo(CALL_SITE_FUTURE_DATE_MESSAGE);

        assertThat(dayAfter.valid()).isFalse();
        assertThat(dayAfter.message()).isEqualTo(CALL_SITE_FUTURE_DATE_MESSAGE);
    }

    /**
     * Covers the accepted span, one day back to forty years back. Every offset lands inside the
     * span the field edits at app/cbl/COACTUPC.cbl:L1536 admit.
     *
     * @param dayOffset days before today, supplied by the value source
     */
    @ParameterizedTest(name = "the date {0} days from today passes")
    @DisplayName("Every date before today passes across the accepted span")
    @ValueSource(longs = {-14600L, -3650L, -365L, -30L, -7L, -2L, -1L})
    void datesBeforeTodayPass(long dayOffset) {
        EditResult result = DateOfBirthValidator.validate(CALL_SITE_LABEL,
                        dateOffsetFromReferenceDate(dayOffset), REFERENCE_DATE);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
    }

    /**
     * Covers the rejected span, opening at the equality boundary of app/cpy/CSUTLDPY.cpy:L350 and
     * running five years out. Offset zero is today, which fails alongside every later date.
     *
     * @param dayOffset days from today onward, supplied by the value source
     */
    @ParameterizedTest(name = "the date {0} days from today fails")
    @DisplayName("Today and every later date fail across the rejected span")
    @ValueSource(longs = {0L, 1L, 2L, 7L, 30L, 365L, 1825L})
    void todayAndEveryLaterDateFail(long dayOffset) {
        EditResult result = DateOfBirthValidator.validate(CALL_SITE_LABEL,
                        dateOffsetFromReferenceDate(dayOffset), REFERENCE_DATE);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(CALL_SITE_FUTURE_DATE_MESSAGE);
    }

    /**
     * Holds the text of app/cpy/CSUTLDPY.cpy:L363 to the character. A label field of only spaces
     * trims to no characters, which leaves the text alone in the slot.
     */
    @Test
    @DisplayName("The failure message carries the source text character for character")
    void failureMessageCarriesSourceTextExactly() {
        EditResult result = DateOfBirthValidator.validate(ALL_SPACES_LABEL,
                        dateOffsetFromReferenceDate(TODAY), REFERENCE_DATE);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(":cannot be in the future ");
        assertThat(result.message()).isEqualTo(FUTURE_DATE_MESSAGE);
        assertThat(result.message()).hasSize(FUTURE_DATE_MESSAGE_LENGTH);
        assertThat(result.message()).startsWith(COLON_AND_FIRST_CHARACTER);
        assertThat(result.message()).doesNotStartWith(COLON_AND_SPACE);
        assertThat(result.message()).endsWith(ONE_SPACE);
        assertThat(result.message()).doesNotEndWith(FULL_STOP);
        assertThat(result.message()).contains(LOWER_CASE_CANNOT);
        assertThat(result.message()).doesNotContain(CAPITALISED_CANNOT);
        assertThat(result.message()).doesNotContain(TWO_SPACES);
    }

    /**
     * Applies {@code FUNCTION TRIM} of app/cpy/CSUTLDPY.cpy:L362 to a label field padded to the
     * width declared at app/cbl/COACTUPC.cbl:L53. The padding reaches no part of the message.
     */
    @Test
    @DisplayName("The trimmed label prefixes the message and its padding does not leak")
    void paddedLabelIsTrimmedBeforeTheMessage() {
        assertThat(PADDED_CALL_SITE_LABEL).hasSize(LABEL_FIELD_WIDTH).endsWith(ONE_SPACE);

        EditResult result =
                DateOfBirthValidator.validate(PADDED_CALL_SITE_LABEL,
                        dateOffsetFromReferenceDate(TOMORROW), REFERENCE_DATE);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(CALL_SITE_FUTURE_DATE_MESSAGE);
        assertThat(result.message()).startsWith(CALL_SITE_LABEL + ":");
        assertThat(result.message()).doesNotContain(TWO_SPACES);
        assertThat(result.message()).doesNotContain(SPACE_BEFORE_COLON);
        assertThat(result.message()).hasSize(CALL_SITE_LABEL.length() + FUTURE_DATE_MESSAGE_LENGTH);
    }

    /**
     * Proves the single reachable message of app/cpy/CSUTLDPY.cpy:L361-L365. Seven rejected dates
     * yield one distinct text, and seven accepted dates yield none.
     */
    @Test
    @DisplayName("Every rejected date yields the one message the paragraph builds")
    void everyRejectedDateYieldsTheOneMessage() {
        Set<String> distinctMessages = new LinkedHashSet<>();
        for (long dayOffset : REJECTED_DAY_OFFSETS) {
            EditResult result =
                    DateOfBirthValidator.validate(CALL_SITE_LABEL,
                        dateOffsetFromReferenceDate(dayOffset), REFERENCE_DATE);

            assertThat(result.valid()).isFalse();
            assertThat(result.hasMessage()).isTrue();
            distinctMessages.add(result.message());
        }

        assertThat(distinctMessages).hasSize(1).containsExactly(CALL_SITE_FUTURE_DATE_MESSAGE);

        for (long dayOffset : ACCEPTED_DAY_OFFSETS) {
            EditResult result =
                    DateOfBirthValidator.validate(CALL_SITE_LABEL,
                        dateOffsetFromReferenceDate(dayOffset), REFERENCE_DATE);

            assertThat(result.valid()).isTrue();
            assertThat(result.message()).isNull();
            assertThat(result.hasMessage()).isFalse();
        }
    }

    /**
     * Asserts the two entry points the one call site at app/cbl/COACTUPC.cbl:L1540 needs are
     * declared, each reachable with no instance and each returning a verdict.
     *
     * <p>Only the callable contract is asserted. A compatible addition, such as a further overload,
     * is not a behaviour change and does not belong in a test.
     */
    @Test
    @DisplayName("Both validate overloads are static and return a verdict")
    void validatorDeclaresItsRequiredEntryPoints() throws NoSuchMethodException {
        Method clockReading = DateOfBirthValidator.class.getMethod("validate", String.class,
                String.class);
        Method referenceDated = DateOfBirthValidator.class.getMethod("validate", String.class,
                String.class, LocalDate.class);

        for (Method entryPoint : List.of(clockReading, referenceDated)) {
            assertThat(Modifier.isStatic(entryPoint.getModifiers())).isTrue();
            assertThat(Modifier.isPublic(entryPoint.getModifiers())).isTrue();
            assertThat(entryPoint.getReturnType()).isEqualTo(EditResult.class);
        }
    }

    /**
     * Renders a date a given number of days from {@link #REFERENCE_DATE} in the eight characters
     * {@code WS-EDIT-DATE-CCYYMMDD} holds, declared at app/cpy/CSUTLDWY.cpy:L4.
     *
     * <p>Every test in this class passes {@link #REFERENCE_DATE} to the production overload as well,
     * so the value this method builds and the day the comparison runs against are one value. Reading
     * the clock twice can flip a verdict at midnight, and nothing here reads it at all.</p>
     *
     * @param dayOffset days to add to {@link #REFERENCE_DATE}; a negative value moves back
     * @return exactly eight characters of century, year, month, and day
     */
    private static String dateOffsetFromReferenceDate(long dayOffset) {
        return REFERENCE_DATE.plusDays(dayOffset).format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    /**
     * Renders a date a given number of years before {@link #REFERENCE_DATE} in the eight characters
     * {@code WS-EDIT-DATE-CCYYMMDD} holds at app/cpy/CSUTLDWY.cpy:L4.
     *
     * @param years years to subtract from {@link #REFERENCE_DATE}
     * @return exactly eight characters of century, year, month, and day
     */
    private static String dateYearsBeforeReferenceDate(int years) {
        return REFERENCE_DATE.minusYears(years).format(DateTimeFormatter.BASIC_ISO_DATE);
    }
}
