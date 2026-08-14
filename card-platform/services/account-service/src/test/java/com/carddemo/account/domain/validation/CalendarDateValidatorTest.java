package com.carddemo.account.domain.validation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.carddemo.cobol.CobolDateValidator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Tests for {@link CalendarDateValidator}, the single calendar-date gate of the account service.
 *
 * <p>The subject realises paragraph {@code EDIT-DATE-CCYYMMDD} at app/cpy/CSUTLDPY.cpy:L18, whose
 * exit paragraph sits at app/cpy/CSUTLDPY.cpy:L329. The range closes with {@code EDIT-DATE-LE} at
 * app/cpy/CSUTLDPY.cpy:L284, which calls the date-service program held at app/cbl/CSUTLDTC.cbl.
 * That program calls {@code CEEDAYS} at app/cbl/CSUTLDTC.cbl:L116-L120 and reports a severity and a
 * message number, which app/cbl/CSUTLDTC.cbl:L123-L124 move into its message record.</p>
 *
 * <p>Four call sites reach the range, each supplying its own label. They sit at
 * app/cbl/COACTUPC.cbl:L1480 under {@code 'Open Date'}, app/cbl/COACTUPC.cbl:L1492 under
 * {@code 'Expiry Date'}, app/cbl/COACTUPC.cbl:L1505 under {@code 'Reissue Date'}, and
 * app/cbl/COACTUPC.cbl:L1536 under {@code 'Date of Birth'}. All four pass an eight-character value
 * and read one verdict.</p>
 *
 * <p>The verdict keys off the numeric severity test at app/cpy/CSUTLDPY.cpy:L298 together with the
 * input-error state that app/cpy/CSUTLDPY.cpy:L301 sets. It does not read
 * {@code WS-EDIT-DATE-IS-VALID}, which app/cpy/CSUTLDPY.cpy:L327 sets after the {@code EXIT}
 * sentence at app/cpy/CSUTLDPY.cpy:L324 and its terminating period at
 * app/cpy/CSUTLDPY.cpy:L325.</p>
 *
 * <p>The subject exposes three things: the eight-character mask binding of
 * app/cpy/CSUTLDPY.cpy:L291, the trimmed label of app/cpy/CSUTLDPY.cpy:L307, and the mapping of the
 * underlying outcome onto an {@link EditResult}.</p>
 *
 * <p>{@code CobolDateValidatorTest} in the cobol-compat module owns the rest. Its subjects are the
 * century bound at app/cpy/CSUTLDPY.cpy:L70-L71 and the
 * leap-year expression at app/cpy/CSUTLDPY.cpy:L243-L256. They also cover the month range test at
 * app/cpy/CSUTLDPY.cpy:L111, the day numeric gate at app/cpy/CSUTLDPY.cpy:L170, and the thirteen
 * message literals.</p>
 *
 * <p>The {@code STRING} statement at app/cpy/CSUTLDPY.cpy:L306 is closed by the {@code END-IF} at
 * app/cpy/CSUTLDPY.cpy:L314, and all 375 lines of the file hold no {@code END-STRING}.</p>
 *
 * <p>The third finding is a flag asymmetry. The rejection path at app/cpy/CSUTLDPY.cpy:L301-L304
 * sets the input-error state and all three field flags, while the acceptance path at
 * app/cpy/CSUTLDPY.cpy:L318-L320 sets the day flag alone. The subject returns an
 * {@link EditResult} carrying a verdict and at most one message, so the three flag bytes reach no
 * target field.</p>
 *
 * <p>No Spring context, no container and no database take part.</p>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("CalendarDateValidator, the eight-character calendar date gate")
class CalendarDateValidatorTest {

    /** The label app/cbl/COACTUPC.cbl:L1478 supplies to the call site at L1480. */
    private static final String OPEN_DATE_LABEL = "Open Date";

    /** The label app/cbl/COACTUPC.cbl:L1490 supplies to the call site at L1492. */
    private static final String EXPIRY_DATE_LABEL = "Expiry Date";

    /** The label app/cbl/COACTUPC.cbl:L1503 supplies to the call site at L1505. */
    private static final String REISSUE_DATE_LABEL = "Reissue Date";

    /** The label app/cbl/COACTUPC.cbl:L1533 supplies to the call site at L1536. */
    private static final String DATE_OF_BIRTH_LABEL = "Date of Birth";

    /** Declared width of {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at app/cbl/COACTUPC.cbl:L53. */
    private static final int LABEL_FIELD_WIDTH = 25;

    /** Declared width of {@code WS-EDIT-DATE-CCYYMMDD} at app/cpy/CSUTLDWY.cpy:L4. */
    private static final int EDIT_DATE_WIDTH = 8;

    /** {@link #DATE_OF_BIRTH_LABEL} padded with spaces to fill the label field. */
    private static final String PADDED_DATE_OF_BIRTH_LABEL = DATE_OF_BIRTH_LABEL
            + " ".repeat(LABEL_FIELD_WIDTH - DATE_OF_BIRTH_LABEL.length());

    /** {@link #OPEN_DATE_LABEL} carrying spaces at both ends. */
    private static final String SPACE_WRAPPED_OPEN_DATE_LABEL = "   " + OPEN_DATE_LABEL + "   ";

    /** A label field holding only spaces, which the trim at app/cpy/CSUTLDPY.cpy:L307 empties. */
    private static final String ALL_SPACES_LABEL = " ".repeat(LABEL_FIELD_WIDTH);

    /** An eight-character value every one of the four call sites accepts. */
    private static final String WELL_FORMED_DATE = "20240115";

    /**
     * Month 13 and day 32 together, a pairing no calendar holds. The value carries eight numeric
     * characters, so it reaches the field edits as a well-formed field.
     */
    private static final String DATE_OUTSIDE_THE_CALENDAR = "20241332";

    /** Month 00 and day 00 together, a second pairing no calendar holds. */
    private static final String SECOND_DATE_OUTSIDE_THE_CALENDAR = "20240000";

    /**
     * A letter where the third character of the year belongs, with a month and a day that both
     * clear their own edits. The value fails the range, and the day flag of
     * app/cpy/CSUTLDPY.cpy:L319 carries no part of that verdict.
     */
    private static final String NON_NUMERIC_YEAR_DATE = "20A40101";

    /**
     * The message the subject produces for {@link #DATE_OUTSIDE_THE_CALENDAR} under
     * {@link #OPEN_DATE_LABEL}, read from the subject and held character for character.
     */
    private static final String OUTSIDE_THE_CALENDAR_MESSAGE =
            "Open Date: Month must be a number between 1 and 12.";

    /**
     * The first literal the rejection path composes, at app/cpy/CSUTLDPY.cpy:L308. One space opens
     * it and one space closes it.
     */
    private static final String SEVERITY_CODE_LITERAL = " validation error Sev code: ";

    /** Character count of the literal at app/cpy/CSUTLDPY.cpy:L308. */
    private static final int SEVERITY_CODE_LITERAL_LENGTH = 28;

    /**
     * The second literal the rejection path composes, at app/cpy/CSUTLDPY.cpy:L310. One space opens
     * it and one space closes it.
     */
    private static final String MESSAGE_CODE_LITERAL = " Message code: ";

    /** Character count of the literal at app/cpy/CSUTLDPY.cpy:L310. */
    private static final int MESSAGE_CODE_LITERAL_LENGTH = 15;

    /** The eight characters app/cpy/CSUTLDPY.cpy:L291 moves ahead of the call. */
    private static final String EIGHT_CHARACTER_MASK = "YYYYMMDD";

    /** Character count of the ten-character mask app/cbl/COTRN02C.cbl:L60 declares. */
    private static final int TEN_CHARACTER_MASK_LENGTH = 10;

    /** A date written with separators, which fills ten characters and not eight. */
    private static final String SEPARATED_DATE = "2024-01-15";

    /** Call count for the repeated-call assertion. */
    private static final int REPEATED_CALL_COUNT = 5;

    /** Two adjacent spaces. A trimmed label never emits this pair. */
    private static final String TWO_SPACES = "  ";

    /** One space ahead of a colon. A trimmed label never emits this pair. */
    private static final String SPACE_BEFORE_COLON = " :";

    /** The value the call site at app/cbl/COACTUPC.cbl:L1480 receives in this file. */
    private static final String OPEN_DATE_VALUE = "19850101";

    /** The value the call site at app/cbl/COACTUPC.cbl:L1492 receives in this file. */
    private static final String EXPIRY_DATE_VALUE = "20301231";

    /** The value the call site at app/cbl/COACTUPC.cbl:L1505 receives in this file. */
    private static final String REISSUE_DATE_VALUE = "20240630";

    /** The value the call site at app/cbl/COACTUPC.cbl:L1536 receives in this file. */
    private static final String DATE_OF_BIRTH_VALUE = "19780315";

    /** Every value the subject accepts in this file, each eight characters wide. */
    private static final List<String> ACCEPTED_DATES = List.of(
            WELL_FORMED_DATE,
            OPEN_DATE_VALUE,
            EXPIRY_DATE_VALUE,
            REISSUE_DATE_VALUE,
            DATE_OF_BIRTH_VALUE);

    /** Values the subject rejects, spanning five separate shapes of malformed input. */
    private static final List<String> REJECTED_DATES = List.of(
            DATE_OUTSIDE_THE_CALENDAR,
            SECOND_DATE_OUTSIDE_THE_CALENDAR,
            NON_NUMERIC_YEAR_DATE,
            SEPARATED_DATE,
            " ".repeat(EDIT_DATE_WIDTH));

    /**
     * Supplies one label and one value for each of the four call sites of app/cbl/COACTUPC.cbl.
     * Each label carries the text its own {@code MOVE} statement holds, and no method restates that
     * text.
     *
     * @return four label and value pairs, one per call site
     */
    private static Stream<Arguments> callSiteLabelsAndValues() {
        return Stream.of(
                arguments(OPEN_DATE_LABEL, OPEN_DATE_VALUE),
                arguments(EXPIRY_DATE_LABEL, EXPIRY_DATE_VALUE),
                arguments(REISSUE_DATE_LABEL, REISSUE_DATE_VALUE),
                arguments(DATE_OF_BIRTH_LABEL, DATE_OF_BIRTH_VALUE));
    }

    /**
     * Runs the range under each of the four labels app/cbl/COACTUPC.cbl supplies. A value that
     * clears every edit reaches app/cpy/CSUTLDPY.cpy:L299, whose {@code CONTINUE} leaves the
     * input-error state untouched, and the subject reports a passing verdict carrying no message.
     *
     * @param fieldLabel the label one of the four call sites supplies
     * @param date       an eight-character value that clears every edit
     */
    @ParameterizedTest(name = "label {0} with date {1} passes")
    @DisplayName("A well-formed eight-character date passes under every call-site label")
    @MethodSource("callSiteLabelsAndValues")
    void wellFormedDatePassesUnderEveryCallSiteLabel(String fieldLabel, String date) {
        assertThat(date).hasSize(EDIT_DATE_WIDTH);

        EditResult result = CalendarDateValidator.validate(fieldLabel, date);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
    }

    /**
     * Holds the one message a malformed value produces. Every message of
     * app/cpy/CSUTLDPY.cpy opens with the trimmed label of app/cpy/CSUTLDPY.cpy:L307, so a message
     * carrying the label once carries one message and no more.
     *
     * <p>The second block asserts the mapping. The subject reads the one message the range
     * composed and hands it to {@link EditResult#failure(String)} with no edit of its own.</p>
     */
    @Test
    @DisplayName("A malformed date fails carrying exactly one message")
    void malformedDateFailsCarryingExactlyOneMessage() {
        EditResult result =
                CalendarDateValidator.validate(OPEN_DATE_LABEL, DATE_OUTSIDE_THE_CALENDAR);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(OUTSIDE_THE_CALENDAR_MESSAGE);
        assertThat(result.message()).startsWith(OPEN_DATE_LABEL);
        assertThat(result.message()).containsOnlyOnce(OPEN_DATE_LABEL);
        assertThat(result.message()).doesNotContain(TWO_SPACES);

        String composedByTheRange = CobolDateValidator
                .editDateCcyymmdd(DATE_OUTSIDE_THE_CALENDAR, OPEN_DATE_LABEL)
                .firstReturnMessage();

        assertThat(result.message()).isEqualTo(composedByTheRange);
    }

    /**
     * Holds the two literals of the rejection path character for character. The {@code STRING}
     * statement at app/cpy/CSUTLDPY.cpy:L306-L313 joins five parts in order. The first three are
     * the trimmed label of app/cpy/CSUTLDPY.cpy:L307, the literal of app/cpy/CSUTLDPY.cpy:L308, and
     * the four-character severity of app/cpy/CSUTLDPY.cpy:L309. The last two are the literal of
     * app/cpy/CSUTLDPY.cpy:L310 and the four-character message number of
     * app/cpy/CSUTLDPY.cpy:L311. Each literal keeps one leading space and one trailing space.
     *
     * <p>The subject edits no message of its own. Five rejected values reach it below, and each
     * message arrives character for character as the range composed it. The call sits last in the
     * range, after the year, month, day, and combination edits of
     * app/cpy/CSUTLDPY.cpy:L25-L279.</p>
     */
    @Test
    @DisplayName("The two rejection literals keep their leading and trailing spaces")
    void rejectionLiteralsKeepTheirLeadingAndTrailingSpaces() {
        assertThat(CobolDateValidator.SEVERITY_CODE_MESSAGE)
                .isEqualTo(" validation error Sev code: ")
                .isEqualTo(SEVERITY_CODE_LITERAL)
                .hasSize(SEVERITY_CODE_LITERAL_LENGTH)
                .startsWith(" ")
                .endsWith(" ");

        assertThat(CobolDateValidator.MESSAGE_CODE_MESSAGE)
                .isEqualTo(" Message code: ")
                .isEqualTo(MESSAGE_CODE_LITERAL)
                .hasSize(MESSAGE_CODE_LITERAL_LENGTH)
                .startsWith(" ")
                .endsWith(" ");

        for (String date : REJECTED_DATES) {
            EditResult result = CalendarDateValidator.validate(OPEN_DATE_LABEL, date);
            String composedByTheRange = CobolDateValidator
                    .editDateCcyymmdd(date, OPEN_DATE_LABEL)
                    .firstReturnMessage();

            assertThat(result.valid()).isFalse();
            assertThat(result.hasMessage()).isTrue();
            assertThat(result.message()).isEqualTo(composedByTheRange);
        }
    }

    /**
     * Applies the trim of app/cpy/CSUTLDPY.cpy:L307 to a label field filled to the width declared
     * at app/cbl/COACTUPC.cbl:L53. None of the padding reaches the message.
     *
     * <p>The closing block reads a label field of only spaces. The trim empties it, and the
     * message that reaches the caller is the labelled message with the label removed.</p>
     */
    @Test
    @DisplayName("The label is trimmed and its padding does not leak into the message")
    void paddedLabelIsTrimmedAheadOfTheMessage() {
        assertThat(PADDED_DATE_OF_BIRTH_LABEL).hasSize(LABEL_FIELD_WIDTH).endsWith(" ");

        EditResult padded = CalendarDateValidator
                .validate(PADDED_DATE_OF_BIRTH_LABEL, DATE_OUTSIDE_THE_CALENDAR);

        assertThat(padded.valid()).isFalse();
        assertThat(padded.message()).startsWith(DATE_OF_BIRTH_LABEL);
        assertThat(padded.message()).doesNotContain(TWO_SPACES);
        assertThat(padded.message()).doesNotContain(SPACE_BEFORE_COLON);
        assertThat(padded.message()).doesNotEndWith(" ");

        EditResult spaceWrapped = CalendarDateValidator
                .validate(SPACE_WRAPPED_OPEN_DATE_LABEL, DATE_OUTSIDE_THE_CALENDAR);

        assertThat(spaceWrapped.message()).isEqualTo(OUTSIDE_THE_CALENDAR_MESSAGE);
        assertThat(spaceWrapped.message()).doesNotStartWith(" ");

        EditResult labelled =
                CalendarDateValidator.validate(OPEN_DATE_LABEL, DATE_OUTSIDE_THE_CALENDAR);
        EditResult allSpaces =
                CalendarDateValidator.validate(ALL_SPACES_LABEL, DATE_OUTSIDE_THE_CALENDAR);

        assertThat(allSpaces.message())
                .isEqualTo(labelled.message().substring(OPEN_DATE_LABEL.length()));
    }

    /**
     * Fixes the mask the subject supplies. app/cpy/CSUTLDPY.cpy:L291 moves eight characters into
     * the mask field ahead of the call at app/cpy/CSUTLDPY.cpy:L293. Every acceptance predicate of
     * {@link CobolDateValidator} takes the mask as an argument and defaults none.
     *
     * <p>The binding is observable. Each value below clears the strict policy under the
     * eight-character mask and fails it under the ten-character mask of app/cbl/COTRN02C.cbl:L60,
     * and the subject passes each one. A date written with separators fills ten characters and the
     * subject rejects it, which the closing block asserts.</p>
     */
    @Test
    @DisplayName("The eight-character mask is bound at the call to the date service")
    void eightCharacterMaskIsBoundAtTheCall() {
        assertThat(CobolDateValidator.STRICT_POLICY_DATE_MASK)
                .isEqualTo(EIGHT_CHARACTER_MASK)
                .hasSize(EDIT_DATE_WIDTH);
        assertThat(CobolDateValidator.TOLERANT_POLICY_DATE_MASK)
                .hasSize(TEN_CHARACTER_MASK_LENGTH);

        for (String date : ACCEPTED_DATES) {
            assertThat(date).hasSize(EDIT_DATE_WIDTH);
            assertThat(CobolDateValidator.isAcceptedByStrictPolicy(
                    date, CobolDateValidator.STRICT_POLICY_DATE_MASK)).isTrue();
            assertThat(CobolDateValidator.isAcceptedByStrictPolicy(
                    date, CobolDateValidator.TOLERANT_POLICY_DATE_MASK)).isFalse();
            assertThat(CalendarDateValidator.validate(OPEN_DATE_LABEL, date).valid()).isTrue();
        }

        assertThat(SEPARATED_DATE).hasSize(TEN_CHARACTER_MASK_LENGTH);

        EditResult separated = CalendarDateValidator.validate(OPEN_DATE_LABEL, SEPARATED_DATE);

        assertThat(separated.valid()).isFalse();
        assertThat(separated.hasMessage()).isTrue();
    }

    /**
     * Reads the verdict repeatedly for one accepted value and one rejected value. The verdict keys
     * off the numeric severity test at app/cpy/CSUTLDPY.cpy:L298 and the input-error state of
     * app/cpy/CSUTLDPY.cpy:L301, both of which the subject computes afresh on every call.
     *
     * <p>No assertion here reads {@code WS-EDIT-DATE-IS-VALID}. app/cpy/CSUTLDPY.cpy:L327 sets that
     * flag after the {@code EXIT} sentence at app/cpy/CSUTLDPY.cpy:L324 and its terminating period
     * at app/cpy/CSUTLDPY.cpy:L325. The rejection path at app/cpy/CSUTLDPY.cpy:L315 passes the
     * statement by.</p>
     */
    @Test
    @DisplayName("Repeated calls report the same verdict for the same value")
    void repeatedCallsReportTheSameVerdict() {
        Set<EditResult> acceptedVerdicts = new LinkedHashSet<>();
        Set<EditResult> rejectedVerdicts = new LinkedHashSet<>();

        for (int call = 0; call < REPEATED_CALL_COUNT; call++) {
            acceptedVerdicts.add(CalendarDateValidator.validate(OPEN_DATE_LABEL, WELL_FORMED_DATE));
            rejectedVerdicts.add(
                    CalendarDateValidator.validate(OPEN_DATE_LABEL, DATE_OUTSIDE_THE_CALENDAR));
        }

        assertThat(acceptedVerdicts).hasSize(1).containsExactly(EditResult.ok());
        assertThat(rejectedVerdicts).hasSize(1)
                .containsExactly(EditResult.failure(OUTSIDE_THE_CALENDAR_MESSAGE));
    }

    /**
     * Rejects one value that sits outside the calendar. Month 00 and day 00 name no month and no
     * day, and the value still fills the eight characters app/cpy/CSUTLDWY.cpy:L4 declares.
     *
     * <p>No assertion here names the edit that rejects the value.
     * {@code CobolDateValidatorTest} in the cobol-compat module owns every edit the class comment
     * above lists.</p>
     */
    @Test
    @DisplayName("A date outside the calendar fails and carries one message")
    void dateOutsideTheCalendarFails() {
        assertThat(SECOND_DATE_OUTSIDE_THE_CALENDAR).hasSize(EDIT_DATE_WIDTH);

        EditResult result =
                CalendarDateValidator.validate(OPEN_DATE_LABEL, SECOND_DATE_OUTSIDE_THE_CALENDAR);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).startsWith(OPEN_DATE_LABEL);
        assertThat(result.message()).containsOnlyOnce(OPEN_DATE_LABEL);
    }

    /**
     * Reads both arguments as {@code null}. app/cpy/CSUTLDPY.cpy:L30 tests the year field for
     * {@code LOW-VALUES}, so an absent value carries a verdict of its own. The gate serves four
     * call sites and raises nothing at any of them.
     */
    @Test
    @DisplayName("Null arguments produce a failing verdict and raise nothing")
    void nullArgumentsProduceAFailingVerdict() {
        assertThat(catchThrowable(() -> CalendarDateValidator.validate(null, null))).isNull();

        EditResult nullDate = CalendarDateValidator.validate(OPEN_DATE_LABEL, null);

        assertThat(nullDate.valid()).isFalse();
        assertThat(nullDate.hasMessage()).isTrue();
        assertThat(nullDate.message()).startsWith(OPEN_DATE_LABEL);

        EditResult nullLabel = CalendarDateValidator.validate(null, DATE_OUTSIDE_THE_CALENDAR);

        assertThat(nullLabel.valid()).isFalse();
        assertThat(nullLabel.message())
                .isEqualTo(OUTSIDE_THE_CALENDAR_MESSAGE.substring(OPEN_DATE_LABEL.length()));
    }

    /**
     * Reads the entry point the four call sites at app/cbl/COACTUPC.cbl:L1480,
     * app/cbl/COACTUPC.cbl:L1492, app/cbl/COACTUPC.cbl:L1505, and app/cbl/COACTUPC.cbl:L1536 need:
     * a static call taking a label and a date, answering with a verdict.
     */
    @Test
    @DisplayName("The validator declares a static label-and-date entry point returning a verdict")
    void validatorExposesOneStaticEntryPoint() throws NoSuchMethodException {
        Method entryPoint =
                CalendarDateValidator.class.getMethod("validate", String.class, String.class);

        assertThat(Modifier.isPublic(entryPoint.getModifiers())).isTrue();
        assertThat(Modifier.isStatic(entryPoint.getModifiers())).isTrue();
        assertThat(entryPoint.getReturnType()).isEqualTo(EditResult.class);
        assertThat(entryPoint.getParameterTypes()).containsExactly(String.class, String.class);
    }
}
