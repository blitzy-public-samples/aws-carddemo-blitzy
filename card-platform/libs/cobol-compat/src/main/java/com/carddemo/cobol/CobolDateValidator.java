package com.carddemo.cobol;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Reproduces the CardDemo date validation rules that one called program and two copybooks carry.
 *
 * <p>The source holds two acceptance policies over the same called program, and the two disagree.
 * The tolerant policy accepts a date when the severity text is {@code '0000'} or when the message
 * number text is {@code '2513'}. Four call sites read it: {@code app/cbl/COTRN02C.cbl:L397-L400},
 * {@code app/cbl/COTRN02C.cbl:L417-L420}, {@code app/cbl/CORPT00C.cbl:L396-L399}, and
 * {@code app/cbl/CORPT00C.cbl:L416-L419}. The source carries no comment explaining the second
 * condition.
 *
 * <p>The strict policy accepts a date only when the numeric severity is zero. One call site reads
 * it, at {@code app/cpy/CSUTLDPY.cpy:L298}, and that paragraph holds no {@code '2513'} branch.
 * {@link #isAcceptedByTolerantPolicy(String, String)} and
 * {@link #isAcceptedByStrictPolicy(String, String)} carry one policy each.
 *
 * <p>The two policies differ in two further ways. The tolerant policy compares
 * {@code CSUTLDTC-RESULT-SEV-CD PIC X(04)} as text, declared at
 * {@code app/cbl/COTRN02C.cbl:L66}. The strict policy compares the numeric redefine
 * {@code WS-SEVERITY-N PIC 9(4)}, declared at {@code app/cpy/CSUTLDWY.cpy:L62-L63}.
 * {@link DateValidationResult} carries both the four-character text and the integer.
 *
 * <p>The masks differ too. The tolerant policy passes the ten-character
 * {@value #TOLERANT_POLICY_DATE_MASK} from {@code app/cbl/COTRN02C.cbl:L60} and
 * {@code app/cbl/CORPT00C.cbl:L72}. The strict policy passes the eight-character
 * {@value #STRICT_POLICY_DATE_MASK} from {@code app/cpy/CSUTLDPY.cpy:L291}. Every method here
 * takes the mask as an argument and defaults none.
 *
 * <p>{@link FeedbackCondition#DATE_IS_VALID} carries the name {@code FC-INVALID-DATE} in the
 * source, at {@code app/cbl/CSUTLDTC.cbl:L62}. That condition tests the all-zeros feedback token,
 * and {@code app/cbl/CSUTLDTC.cbl:L129-L130} moves {@code 'Date is valid'} when it holds, so the
 * name here states what the condition means.
 *
 * <p>{@code app/cbl/CSUTLDTC.cbl:L116-L120} calls {@code CEEDAYS}, the date service of IBM
 * Language Environment. No equivalent service exists away from the mainframe.
 * {@link #validateDate(String, String)} reimplements the semantics and calls nothing.
 *
 * <p>{@link #editDateCcyymmdd(String, String)} carries the field-level rules of
 * {@code app/cpy/CSUTLDPY.cpy}, which one program includes: {@code app/cbl/COACTUPC.cbl:L166}
 * copies the working storage and {@code app/cbl/COACTUPC.cbl:L4232} copies the paragraphs.

 */
public final class CobolDateValidator {

    // Date masks. app/cbl/CSUTLDTC.cbl:L85 declares the parameter as LS-DATE-FORMAT PIC X(10),
    // and the two policies fill it with different values.

    /**
     * The mask the strict policy passes, {@code WS-DATE-FORMAT PIC X(08) VALUE 'YYYYMMDD'} at
     * {@code app/cpy/CSUTLDWY.cpy:L58-L59}. {@code app/cpy/CSUTLDPY.cpy:L291} moves the same
     * eight characters ahead of the call.
     */
    public static final String STRICT_POLICY_DATE_MASK = "YYYYMMDD";

    /**
     * The mask the tolerant policy passes, {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'} at
     * {@code app/cbl/COTRN02C.cbl:L60} and {@code app/cbl/CORPT00C.cbl:L72}.
     */
    public static final String TOLERANT_POLICY_DATE_MASK = "YYYY-MM-DD";

    // Acceptance literals. The tolerant policy compares text; the strict policy compares an
    // integer. Both forms appear here.

    /**
     * The severity text both policies accept, compared at {@code app/cbl/COTRN02C.cbl:L397} and
     * {@code app/cbl/CORPT00C.cbl:L396}.
     */
    public static final String ACCEPTED_SEVERITY_TEXT = "0000";

    /**
     * The severity integer the strict policy accepts, compared at
     * {@code app/cpy/CSUTLDPY.cpy:L298}.
     */
    public static final int ACCEPTED_SEVERITY_NUMBER = 0;

    /**
     * The message number text the tolerant policy accepts on top of a zero severity, compared at
     * {@code app/cbl/COTRN02C.cbl:L400}, {@code app/cbl/COTRN02C.cbl:L420},
     * {@code app/cbl/CORPT00C.cbl:L399}, and {@code app/cbl/CORPT00C.cbl:L419}. Those four lines
     * are the only occurrences of the value anywhere under {@code app/}.
     */
    public static final String TOLERATED_MESSAGE_NUMBER_TEXT = "2513";

    /**
     * The message number the tolerant policy accepts, as an integer. The value belongs to
     * {@link FeedbackCondition#UNSUPPORTED_RANGE}, whose feedback token at
     * {@code app/cbl/CSUTLDTC.cbl:L66} carries {@code X'09D1'} in its message number halfword.
     */
    public static final int TOLERATED_MESSAGE_NUMBER = 2513;

    // The 80-byte result layout. app/cbl/CSUTLDTC.cbl:L42-L57 and app/cpy/CSUTLDWY.cpy:L60-L85
    // declare it byte for byte alike. The widths below sum to 80.

    /** Width of {@code WS-SEVERITY PIC X(04)} at {@code app/cbl/CSUTLDTC.cbl:L43}. */
    public static final int SEVERITY_WIDTH = 4;

    /** Width of {@code WS-MSG-NO PIC X(04)} at {@code app/cbl/CSUTLDTC.cbl:L46}. */
    public static final int MESSAGE_NUMBER_WIDTH = 4;

    /**
     * Width of {@code WS-RESULT PIC X(15)} at {@code app/cbl/CSUTLDTC.cbl:L49}. The author's own
     * ruler comment at {@code app/cbl/CSUTLDTC.cbl:L126-L127} counts the same fifteen columns.
     */
    public static final int RESULT_TEXT_WIDTH = 15;

    /** Width of {@code WS-DATE PIC X(10)} at {@code app/cbl/CSUTLDTC.cbl:L52}. */
    public static final int TESTED_DATE_WIDTH = 10;

    /** Width of {@code WS-DATE-FMT PIC X(10)} at {@code app/cbl/CSUTLDTC.cbl:L55}. */
    public static final int DATE_MASK_WIDTH = 10;

    /**
     * Width of {@code LS-RESULT PIC X(80)} at {@code app/cbl/CSUTLDTC.cbl:L86}, which
     * {@code app/cbl/CSUTLDTC.cbl:L97} fills from {@code WS-MESSAGE}.
     * {@link DateValidationResult#renderedResult()} produces exactly this many characters.
     */
    public static final int RENDERED_RESULT_WIDTH = 80;

    // Field-edit widths, from app/cpy/CSUTLDWY.cpy and its one consumer app/cbl/COACTUPC.cbl.

    /**
     * Width of {@code WS-EDIT-DATE-CCYYMMDD} at {@code app/cpy/CSUTLDWY.cpy:L4-L27}, whose four
     * subordinate fields hold two characters of century, two of year, two of month, and two of day.
     */
    public static final int EDIT_DATE_WIDTH = 8;

    /**
     * Width of {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at {@code app/cbl/COACTUPC.cbl:L53}. Every
     * message in {@code app/cpy/CSUTLDPY.cpy} opens with
     * {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)}.
     */
    public static final int EDIT_VARIABLE_NAME_WIDTH = 25;

    /**
     * Width of {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COACTUPC.cbl:L479}. A composed
     * message longer than this is cut to this width, which the {@code STRING} statements at
     * {@code app/cpy/CSUTLDPY.cpy:L306-L313} and elsewhere do on overflow.
     */
    public static final int RETURN_MESSAGE_WIDTH = 75;

    // Century bounds. app/cpy/CSUTLDWY.cpy:L9-L10 names the only two values the year edit
    // accepts. The source comment at app/cpy/CSUTLDPY.cpy:L66-L68 records the same limit.

    /** {@code 88 THIS-CENTURY VALUE 20} at {@code app/cpy/CSUTLDWY.cpy:L9}. */
    public static final int THIS_CENTURY = 20;

    /** {@code 88 LAST-CENTURY VALUE 19} at {@code app/cpy/CSUTLDWY.cpy:L10}. */
    public static final int LAST_CENTURY = 19;

    // Supported date range. CEEDAYS numbers days from the start of the Gregorian calendar, and
    // reports FeedbackCondition.UNSUPPORTED_RANGE for a date outside these two bounds.

    /**
     * The earliest date {@link #validateDate(String, String)} accepts. Day one of the day count
     * that {@code OUTPUT-LILLIAN PIC S9(9) BINARY} at {@code app/cbl/CSUTLDTC.cbl:L41} receives.
     */
    public static final LocalDate EARLIEST_SUPPORTED_DATE = LocalDate.of(1582, 10, 15);

    /**
     * The latest date {@link #validateDate(String, String)} accepts. A four-digit year mask
     * expresses no later date.
     */
    public static final LocalDate LATEST_SUPPORTED_DATE = LocalDate.of(9999, 12, 31);

    // The thirteen message literals of app/cpy/CSUTLDPY.cpy, character for character. Spacing,
    // casing, and punctuation are copied from the source and are not normalised.

    /** {@code app/cpy/CSUTLDPY.cpy:L37}. */
    public static final String YEAR_NOT_SUPPLIED_MESSAGE = " : Year must be supplied.";

    /** {@code app/cpy/CSUTLDPY.cpy:L54}. */
    public static final String YEAR_NOT_FOUR_DIGITS_MESSAGE = " must be 4 digit number.";

    /** {@code app/cpy/CSUTLDPY.cpy:L79}. */
    public static final String CENTURY_NOT_VALID_MESSAGE = " : Century is not valid.";

    /** {@code app/cpy/CSUTLDPY.cpy:L101}. */
    public static final String MONTH_NOT_SUPPLIED_MESSAGE = " : Month must be supplied.";

    /**
     * {@code app/cpy/CSUTLDPY.cpy:L119} and {@code app/cpy/CSUTLDPY.cpy:L136} hold the same
     * literal. The range test and the numeric gate report identical text.
     */
    public static final String MONTH_OUT_OF_RANGE_MESSAGE =
            ": Month must be a number between 1 and 12.";

    /** {@code app/cpy/CSUTLDPY.cpy:L161}. */
    public static final String DAY_NOT_SUPPLIED_MESSAGE = " : Day must be supplied.";

    /**
     * {@code app/cpy/CSUTLDPY.cpy:L180} and {@code app/cpy/CSUTLDPY.cpy:L195} hold the same
     * literal. The word {@code day} is lower case and no space follows the colon.
     */
    public static final String DAY_OUT_OF_RANGE_MESSAGE =
            ":day must be a number between 1 and 31.";

    /** {@code app/cpy/CSUTLDPY.cpy:L221}. */
    public static final String DAY_31_NOT_IN_MONTH_MESSAGE =
            ":Cannot have 31 days in this month.";

    /** {@code app/cpy/CSUTLDPY.cpy:L236}. */
    public static final String DAY_30_NOT_IN_MONTH_MESSAGE =
            ":Cannot have 30 days in this month.";

    /** {@code app/cpy/CSUTLDPY.cpy:L266}. No space follows the first full stop. */
    public static final String NOT_A_LEAP_YEAR_MESSAGE =
            ":Not a leap year.Cannot have 29 days in this month.";

    /**
     * The first of the two literals the strict policy composes on rejection, at
     * {@code app/cpy/CSUTLDPY.cpy:L308}. The four-character severity follows it.
     */
    public static final String SEVERITY_CODE_MESSAGE = " validation error Sev code: ";

    /**
     * The second of the two literals the strict policy composes on rejection, at
     * {@code app/cpy/CSUTLDPY.cpy:L310}. The four-character message number follows it.
     */
    public static final String MESSAGE_CODE_MESSAGE = " Message code: ";

    /** {@code app/cpy/CSUTLDPY.cpy:L363}. The literal ends with a space. */
    public static final String FUTURE_DATE_MESSAGE = ":cannot be in the future ";

    /**
     * The text {@code app/cbl/CSUTLDTC.cbl:L148} moves on its {@code WHEN OTHER} branch, reached
     * when a feedback token matches none of the nine named conditions.
     * {@link #resultTextForFeedbackToken(String)} returns it in that case.
     */
    public static final String UNRECOGNISED_FEEDBACK_RESULT_TEXT = "Date is invalid";

    /**
     * Text that stands in for a tested date in a diagnostic rendering. No COBOL ancestor.
     * {@link DateValidationResult#toString()} carries it in place of the date.
     */
    public static final String REDACTED = "<redacted>";

    // Private constants.

    /** {@code FILLER PIC X(11) VALUE 'Mesg Code:'} at {@code app/cbl/CSUTLDTC.cbl:L45}. */
    private static final String MESSAGE_CODE_FILLER = "Mesg Code: ";

    /** {@code FILLER PIC X(09) VALUE 'TstDate:'} at {@code app/cbl/CSUTLDTC.cbl:L51}. */
    private static final String TESTED_DATE_FILLER = "TstDate: ";

    /** {@code FILLER PIC X(10) VALUE 'Mask used:'} at {@code app/cbl/CSUTLDTC.cbl:L54}. */
    private static final String DATE_MASK_FILLER = "Mask used:";

    /** {@code FILLER PIC X(01) VALUE SPACE} at {@code app/cbl/CSUTLDTC.cbl:L48}. */
    private static final String ONE_SPACE = " ";

    /** {@code FILLER PIC X(03) VALUE SPACES} at {@code app/cbl/CSUTLDTC.cbl:L57}. */
    private static final String THREE_SPACES = "   ";

    /** The day before {@link #EARLIEST_SUPPORTED_DATE}, which the day count numbers zero. */
    private static final LocalDate DAY_COUNT_ORIGIN = LocalDate.of(1582, 10, 14);

    /** The value {@code OUTPUT-LILLIAN} holds when the call reports a failure. */
    private static final int NO_DAY_COUNT = 0;

    /** The result of reading a field whose characters carry no digit value. */
    private static final int UNREADABLE_DIGITS = -1;

    /** The mask token that consumes four characters of year. */
    private static final String YEAR_TOKEN = "YYYY";

    /** The mask token that consumes two characters of month. */
    private static final String MONTH_TOKEN = "MM";

    /** The mask token that consumes two characters of day. */
    private static final String DAY_TOKEN = "DD";

    /** The one separator character the two source masks use. */
    private static final char MASK_SEPARATOR = '-';

    /** The character a COBOL {@code LOW-VALUES} figurative constant fills a field with. */
    private static final char LOW_VALUE = '\u0000';

    /** The character a COBOL {@code SPACES} figurative constant fills a field with. */
    private static final char SPACE = ' ';

    /** The mask that {@code low four bits} takes from a character to read a zoned digit. */
    private static final int ZONED_DIGIT_MASK = 0x0F;

    /** The highest value a single zoned digit holds. */
    private static final int HIGHEST_DIGIT = 9;

    /** The divisor the leap-year rule uses when the two-digit year reads zero. */
    private static final int CENTURY_LEAP_DIVISOR = 400;

    /** The divisor the leap-year rule uses for every other two-digit year. */
    private static final int ORDINARY_LEAP_DIVISOR = 4;

    /** The lowest month value {@code 88 WS-VALID-MONTH} accepts. */
    private static final int LOWEST_MONTH = 1;

    /** The highest month value {@code 88 WS-VALID-MONTH} accepts. */
    private static final int HIGHEST_MONTH = 12;

    /** The lowest day value {@code 88 WS-VALID-DAY} accepts. */
    private static final int LOWEST_DAY = 1;

    /** The highest day value {@code 88 WS-VALID-DAY} accepts. */
    private static final int HIGHEST_DAY = 31;

    /** {@code 88 WS-DAY-31 VALUE 31} at {@code app/cpy/CSUTLDWY.cpy:L30}. */
    private static final int DAY_31 = 31;

    /** {@code 88 WS-DAY-30 VALUE 30} at {@code app/cpy/CSUTLDWY.cpy:L31}. */
    private static final int DAY_30 = 30;

    /** {@code 88 WS-DAY-29 VALUE 29} at {@code app/cpy/CSUTLDWY.cpy:L32}. */
    private static final int DAY_29 = 29;

    /** {@code 88 WS-FEBRUARY VALUE 2} at {@code app/cpy/CSUTLDWY.cpy:L24}. */
    private static final int FEBRUARY = 2;

    /** The seven values of {@code 88 WS-31-DAY-MONTH} at {@code app/cpy/CSUTLDWY.cpy:L21-L23}. */
    private static final int[] MONTHS_WITH_31_DAYS = {1, 3, 5, 7, 8, 10, 12};

    /** An empty {@code WS-RETURN-MSG}, which {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} tests. */
    private static final String NO_RETURN_MESSAGE = "";

    /** The offset of {@code WS-EDIT-DATE-CC PIC X(2)} at {@code app/cpy/CSUTLDWY.cpy:L6}. */
    private static final int CENTURY_OFFSET = 0;

    /** Width of {@code WS-EDIT-DATE-CC PIC X(2)} at {@code app/cpy/CSUTLDWY.cpy:L6}. */
    private static final int CENTURY_WIDTH = 2;

    /** The offset of {@code WS-EDIT-DATE-YY PIC X(2)} at {@code app/cpy/CSUTLDWY.cpy:L11}. */
    private static final int TWO_DIGIT_YEAR_OFFSET = 2;

    /** Width of {@code WS-EDIT-DATE-YY PIC X(2)} at {@code app/cpy/CSUTLDWY.cpy:L11}. */
    private static final int TWO_DIGIT_YEAR_WIDTH = 2;

    /** The offset of {@code WS-EDIT-DATE-MM PIC X(2)} at {@code app/cpy/CSUTLDWY.cpy:L16}. */
    private static final int MONTH_OFFSET = 4;

    /** Width of {@code WS-EDIT-DATE-MM PIC X(2)} at {@code app/cpy/CSUTLDWY.cpy:L16}. */
    private static final int MONTH_WIDTH = 2;

    /** The offset of {@code WS-EDIT-DATE-DD PIC X(2)} at {@code app/cpy/CSUTLDWY.cpy:L25}. */
    private static final int DAY_OFFSET = 6;

    /** Width of {@code WS-EDIT-DATE-DD PIC X(2)} at {@code app/cpy/CSUTLDWY.cpy:L25}. */
    private static final int DAY_WIDTH = 2;

    /** The offset a mask layout carries when the mask names no such token. */
    private static final int TOKEN_ABSENT = -1;

    /** The lowest of the ten characters a numeric class test accepts. */
    private static final char LOWEST_DIGIT_CHARACTER = '0';

    /** The highest of the ten characters a numeric class test accepts. */
    private static final char HIGHEST_DIGIT_CHARACTER = '9';

    /** The radix of every field this class reads as digits. */
    private static final int DECIMAL_RADIX = 10;

    private CobolDateValidator() {
    }

    // Nested types.

    /**
     * The ten conditions {@code app/cbl/CSUTLDTC.cbl:L62-L70} names over the feedback token that
     * {@code CEEDAYS} returns.
     *
     * <p>{@code app/cbl/CSUTLDTC.cbl:L71-L80} lays the token out as four fields: a severity
     * halfword, a message number halfword, a one-character severity control byte, and a
     * three-character facility identifier. Every {@link #severity()} and
     * {@link #messageNumber()} below is read from the token of its own constant.
     *
     * <p>{@link #DATE_IS_VALID} carries the name {@code FC-INVALID-DATE} in the source, at
     * {@code app/cbl/CSUTLDTC.cbl:L62}. The token is all zeros and
     * {@code app/cbl/CSUTLDTC.cbl:L129-L130} moves {@code 'Date is valid'} when it holds.
     *
     * <p>{@link #UNSUPPORTED_RANGE} carries message number
     * {@value CobolDateValidator#TOLERATED_MESSAGE_NUMBER}, which is the value the tolerant policy
     * accepts at {@code app/cbl/COTRN02C.cbl:L400}.
     */
    public enum FeedbackCondition {

        /**
         * {@code 88 FC-INVALID-DATE VALUE X'0000000000000000'} at
         * {@code app/cbl/CSUTLDTC.cbl:L62}. The condition holds when the date is valid.
         */
        DATE_IS_VALID("0000000000000000", "Date is valid"),

        /**
         * {@code 88 FC-INSUFFICIENT-DATA VALUE X'000309CB59C3C5C5'} at
         * {@code app/cbl/CSUTLDTC.cbl:L63}.
         */
        INSUFFICIENT_DATA("000309CB59C3C5C5", "Insufficient"),

        /**
         * {@code 88 FC-BAD-DATE-VALUE VALUE X'000309CC59C3C5C5'} at
         * {@code app/cbl/CSUTLDTC.cbl:L64}.
         */
        BAD_DATE_VALUE("000309CC59C3C5C5", "Datevalue error"),

        /**
         * {@code 88 FC-INVALID-ERA VALUE X'000309CD59C3C5C5'} at {@code app/cbl/CSUTLDTC.cbl:L65}.
         * Neither source mask names an era, and {@link CobolDateValidator#validateDate(String,
         * String)} returns no result carrying this condition.
         */
        INVALID_ERA("000309CD59C3C5C5", "Invalid Era    "),

        /**
         * {@code 88 FC-UNSUPP-RANGE VALUE X'000309D159C3C5C5'} at
         * {@code app/cbl/CSUTLDTC.cbl:L66}. The condition holds for a well-formed date outside
         * {@link CobolDateValidator#EARLIEST_SUPPORTED_DATE} through
         * {@link CobolDateValidator#LATEST_SUPPORTED_DATE}.
         */
        UNSUPPORTED_RANGE("000309D159C3C5C5", "Unsupp. Range  "),

        /**
         * {@code 88 FC-INVALID-MONTH VALUE X'000309D559C3C5C5'} at
         * {@code app/cbl/CSUTLDTC.cbl:L67}.
         */
        INVALID_MONTH("000309D559C3C5C5", "Invalid month  "),

        /**
         * {@code 88 FC-BAD-PIC-STRING VALUE X'000309D659C3C5C5'} at
         * {@code app/cbl/CSUTLDTC.cbl:L68}. The condition holds when the mask itself is unreadable.
         */
        BAD_PICTURE_STRING("000309D659C3C5C5", "Bad Pic String "),

        /**
         * {@code 88 FC-NON-NUMERIC-DATA VALUE X'000309D859C3C5C5'} at
         * {@code app/cbl/CSUTLDTC.cbl:L69}.
         */
        NON_NUMERIC_DATA("000309D859C3C5C5", "Nonnumeric data"),

        /**
         * {@code 88 FC-YEAR-IN-ERA-ZERO VALUE X'000309D959C3C5C5'} at
         * {@code app/cbl/CSUTLDTC.cbl:L70}.
         */
        YEAR_IN_ERA_ZERO("000309D959C3C5C5", "YearInEra is 0 ");

        /** The number of hexadecimal digits in one feedback token. */
        private static final int TOKEN_HEX_DIGITS = 16;

        /** The hexadecimal digits of the severity halfword within the token. */
        private static final int SEVERITY_HEX_END = 4;

        /** The hexadecimal digits of the message number halfword within the token. */
        private static final int MESSAGE_NUMBER_HEX_END = 8;

        /** The radix of the token text. */
        private static final int HEX_RADIX = 16;

        /** The sixteen hexadecimal digits of {@code VALUE X'...'}. */
        private final String feedbackToken;

        /** The severity halfword, read from {@link #feedbackToken}. */
        private final int severity;

        /** The message number halfword, read from {@link #feedbackToken}. */
        private final int messageNumber;

        /** The text {@code app/cbl/CSUTLDTC.cbl:L128-L149} moves for this condition. */
        private final String resultText;

        /**
         * Reads the severity and the message number out of the token.
         *
         * @param feedbackToken the sixteen hexadecimal digits of the source {@code VALUE X'...'}
         * @param resultText    the literal the source moves into {@code WS-RESULT PIC X(15)},
         *                      carrying every trailing space the source literal carries
         */
        FeedbackCondition(String feedbackToken, String resultText) {
            this.feedbackToken = feedbackToken;
            this.severity =
                    Integer.parseInt(feedbackToken.substring(0, SEVERITY_HEX_END), HEX_RADIX);
            this.messageNumber = Integer.parseInt(
                    feedbackToken.substring(SEVERITY_HEX_END, MESSAGE_NUMBER_HEX_END), HEX_RADIX);
            this.resultText = padToWidth(resultText, RESULT_TEXT_WIDTH);
        }

        /**
         * Returns the feedback token of this condition.
         *
         * @return sixteen upper-case hexadecimal digits, matching the source
         *         {@code VALUE X'...'} clause
         */
        public String feedbackToken() {
            return feedbackToken;
        }

        /**
         * Returns the severity halfword of the token.
         *
         * @return {@value CobolDateValidator#ACCEPTED_SEVERITY_NUMBER} for
         *         {@link #DATE_IS_VALID}, and 3 for every other condition
         */
        public int severity() {
            return severity;
        }

        /**
         * Returns the message number halfword of the token.
         *
         * @return 0 for {@link #DATE_IS_VALID}, and the Language Environment message number for
         *         every other condition
         */
        public int messageNumber() {
            return messageNumber;
        }

        /**
         * Returns the result text of this condition.
         *
         * @return exactly {@value CobolDateValidator#RESULT_TEXT_WIDTH} characters, matching
         *         {@code WS-RESULT PIC X(15)}
         */
        public String resultText() {
            return resultText;
        }

        /**
         * Finds the condition a feedback token names.
         *
         * <p>Eight of the ten branches at {@code app/cbl/CSUTLDTC.cbl:L128-L149} test a named
         * token, the ninth tests the all-zeros token, and the tenth is {@code WHEN OTHER}. This
         * method covers the first nine.
         *
         * @param feedbackToken sixteen hexadecimal digits; may be {@code null} and is matched
         *                      without regard to case
         * @return the matching condition, or {@code null} when no condition names the token
         */
        public static FeedbackCondition fromFeedbackToken(String feedbackToken) {
            if (feedbackToken == null || feedbackToken.length() != TOKEN_HEX_DIGITS) {
                return null;
            }
            for (FeedbackCondition candidate : values()) {
                if (candidate.feedbackToken.equalsIgnoreCase(feedbackToken)) {
                    return candidate;
                }
            }
            return null;
        }
    }

    /**
     * One outcome of {@link CobolDateValidator#validateDate(String, String)}, holding the fields
     * that {@code WS-MESSAGE} carries at {@code app/cbl/CSUTLDTC.cbl:L42-L57}.
     *
     * <p>The severity and the message number each arrive twice. {@link #severityText()} and
     * {@link #messageNumberText()} are four zero-padded characters, matching the alphanumeric
     * fields the tolerant policy compares. {@link #severityNumber()} and {@link #messageNumber()}
     * are integers, matching the numeric redefines the strict policy compares. No caller slices
     * {@link #renderedResult()} to read either value.
     *
     * @param condition         the condition the call reported
     * @param severityText      {@code WS-SEVERITY PIC X(04)}, four zero-padded characters
     * @param severityNumber    {@code WS-SEVERITY-N PIC 9(4)}, which
     *                          {@code app/cbl/CSUTLDTC.cbl:L98} also moves into the return code
     * @param messageNumberText {@code WS-MSG-NO PIC X(04)}, four zero-padded characters
     * @param messageNumber     {@code WS-MSG-NO-N PIC 9(4)}
     * @param resultText        {@code WS-RESULT PIC X(15)}, exactly fifteen characters
     * @param testedDate        {@code WS-DATE PIC X(10)}, exactly ten characters
     * @param dateMask          {@code WS-DATE-FMT PIC X(10)}, exactly ten characters
     * @param dayCount          the value {@code OUTPUT-LILLIAN PIC S9(9) BINARY} receives, which
     *                          numbers {@link CobolDateValidator#EARLIEST_SUPPORTED_DATE} one.
     *                          {@code app/cbl/CSUTLDTC.cbl:L114} zeroes it ahead of the call, and
     *                          it stays zero for every condition other than
     *                          {@link FeedbackCondition#DATE_IS_VALID}
     */
    public record DateValidationResult(
            FeedbackCondition condition,
            String severityText,
            int severityNumber,
            String messageNumberText,
            int messageNumber,
            String resultText,
            String testedDate,
            String dateMask,
            int dayCount) {

        /** Holds every field to the width of its source declaration. */
        public DateValidationResult {
            Objects.requireNonNull(condition, "condition");
            severityText = padToWidth(severityText, SEVERITY_WIDTH);
            messageNumberText = padToWidth(messageNumberText, MESSAGE_NUMBER_WIDTH);
            resultText = padToWidth(resultText, RESULT_TEXT_WIDTH);
            testedDate = padToWidth(testedDate, TESTED_DATE_WIDTH);
            dateMask = padToWidth(dateMask, DATE_MASK_WIDTH);
        }

        /**
         * Reports whether the tolerant policy accepts this outcome.
         *
         * <p>Accepts when {@link #severityText()} is {@value CobolDateValidator#ACCEPTED_SEVERITY_TEXT}
         * or when {@link #messageNumberText()} is
         * {@value CobolDateValidator#TOLERATED_MESSAGE_NUMBER_TEXT}. Both comparisons are textual,
         * matching {@code app/cbl/COTRN02C.cbl:L397-L400} and
         * {@code app/cbl/CORPT00C.cbl:L396-L399}. The source carries no comment explaining the
         * second condition.
         *
         * @return {@code true} when the tolerant policy accepts the date
         */
        public boolean acceptedByTolerantPolicy() {
            return ACCEPTED_SEVERITY_TEXT.equals(severityText)
                    || TOLERATED_MESSAGE_NUMBER_TEXT.equals(messageNumberText);
        }

        /**
         * Reports whether the strict policy accepts this outcome.
         *
         * <p>Accepts only when {@link #severityNumber()} is
         * {@value CobolDateValidator#ACCEPTED_SEVERITY_NUMBER}, matching the numeric comparison at
         * {@code app/cpy/CSUTLDPY.cpy:L298}. That paragraph holds no
         * {@value CobolDateValidator#TOLERATED_MESSAGE_NUMBER_TEXT} branch.
         *
         * @return {@code true} when the strict policy accepts the date
         */
        public boolean acceptedByStrictPolicy() {
            return severityNumber == ACCEPTED_SEVERITY_NUMBER;
        }

        /**
         * Renders the eighty-character form of {@code WS-MESSAGE}.
         *
         * <p>{@code app/cbl/CSUTLDTC.cbl:L97} moves this form into
         * {@code LS-RESULT PIC X(80)}. The five filler literals sit at
         * {@code app/cbl/CSUTLDTC.cbl:L45}, {@code app/cbl/CSUTLDTC.cbl:L48},
         * {@code app/cbl/CSUTLDTC.cbl:L51}, {@code app/cbl/CSUTLDTC.cbl:L54}, and
         * {@code app/cbl/CSUTLDTC.cbl:L57}.
         *
         * @return exactly {@value CobolDateValidator#RENDERED_RESULT_WIDTH} characters
         */
        public String renderedResult() {
            return severityText
                    + MESSAGE_CODE_FILLER
                    + messageNumberText
                    + ONE_SPACE
                    + resultText
                    + ONE_SPACE
                    + TESTED_DATE_FILLER
                    + testedDate
                    + ONE_SPACE
                    + DATE_MASK_FILLER
                    + dateMask
                    + ONE_SPACE
                    + THREE_SPACES;
        }

        /**
         * Renders this outcome for a log line or an exception message, with the tested date left
         * out.
         *
         * <p>{@link #testedDate()} can hold a date of birth, so this rendering carries the literal
         * {@value CobolDateValidator#REDACTED} in its place and names the width the field holds.
         * {@link #renderedResult()} still carries the date, matching {@code WS-MESSAGE} at
         * {@code app/cbl/CSUTLDTC.cbl:L42-L57}.
         *
         * @return the condition, the severity, the message number, the result text, the mask, the
         *         day count, and the width of the tested date
         */
        @Override
        public String toString() {
            return "DateValidationResult[condition=" + condition
                    + ", severityText=" + severityText
                    + ", severityNumber=" + severityNumber
                    + ", messageNumberText=" + messageNumberText
                    + ", messageNumber=" + messageNumber
                    + ", resultText=" + resultText
                    + ", testedDate=" + REDACTED + "(" + testedDate.length() + " characters)"
                    + ", dateMask=" + dateMask
                    + ", dayCount=" + dayCount
                    + "]";
        }
    }

    /**
     * The three values one byte of {@code WS-EDIT-DATE-FLGS} holds, from
     * {@code app/cpy/CSUTLDWY.cpy:L43-L57}.
     *
     * <p>The group is three bytes wide and holds one byte each for year, month, and day.
     * {@code app/cpy/CSUTLDWY.cpy:L44} names the whole group valid when all three bytes hold
     * {@code LOW-VALUES}, and {@code app/cpy/CSUTLDWY.cpy:L45} names it invalid when all three
     * hold the character zero.
     */
    public enum FieldEditFlag {

        /**
         * {@code 88 FLG-YEAR-ISVALID VALUE LOW-VALUES} at {@code app/cpy/CSUTLDWY.cpy:L47}, with
         * matching entries for month at {@code app/cpy/CSUTLDWY.cpy:L51} and day at
         * {@code app/cpy/CSUTLDWY.cpy:L55}.
         */
        IS_VALID(LOW_VALUE),

        /**
         * {@code 88 FLG-YEAR-NOT-OK VALUE '0'} at {@code app/cpy/CSUTLDWY.cpy:L48}, with matching
         * entries at {@code app/cpy/CSUTLDWY.cpy:L52} and {@code app/cpy/CSUTLDWY.cpy:L56}.
         */
        NOT_OK('0'),

        /**
         * {@code 88 FLG-YEAR-BLANK VALUE 'B'} at {@code app/cpy/CSUTLDWY.cpy:L49}, with matching
         * entries at {@code app/cpy/CSUTLDWY.cpy:L53} and {@code app/cpy/CSUTLDWY.cpy:L57}. The
         * field edits set this value when a field arrives empty.
         */
        BLANK('B');

        /** The character the source stores in the flag byte. */
        private final char flagCharacter;

        /**
         * Records the stored character.
         *
         * @param flagCharacter the byte value the source {@code 88} clause names
         */
        FieldEditFlag(char flagCharacter) {
            this.flagCharacter = flagCharacter;
        }

        /**
         * Returns the character the source stores for this value.
         *
         * @return the byte value of the flag, which is a low value for {@link #IS_VALID}
         */
        public char flagCharacter() {
            return flagCharacter;
        }
    }

    /**
     * One outcome of {@link CobolDateValidator#editDateCcyymmdd(String, String)} or
     * {@link CobolDateValidator#editDateOfBirth(String, String)}.
     *
     * <p>{@link #firstReturnMessage()} holds at most one message. Every message in
     * {@code app/cpy/CSUTLDPY.cpy} is composed inside {@code IF WS-RETURN-MSG-OFF}, which tests
     * {@code WS-RETURN-MSG PIC X(75)} for spaces at {@code app/cbl/COACTUPC.cbl:L479-L480}. A
     * second failure in the same pass composes nothing, so the first message of a pass is the one
     * a caller reads. The component name states that contract.
     *
     * <p>The generated rendering of this record carries the four flag and message components, then
     * the rendering of {@link DateValidationResult}, which leaves the tested date out.
     *
     * @param inputError         the state of {@code 88 INPUT-ERROR VALUE '1'} at
     *                           {@code app/cbl/COACTUPC.cbl:L173} after the pass
     * @param yearFlag           the byte {@code WS-EDIT-YEAR-FLG} holds after the pass
     * @param monthFlag          the byte {@code WS-EDIT-MONTH} holds after the pass
     * @param dayFlag            the byte {@code WS-EDIT-DAY} holds after the pass
     * @param firstReturnMessage the one message the pass composed, cut to
     *                           {@value CobolDateValidator#RETURN_MESSAGE_WIDTH} characters, or
     *                           an empty string when the pass composed none
     * @param dateValidation     the outcome of the strict-policy call at
     *                           {@code app/cpy/CSUTLDPY.cpy:L293-L296}, or {@code null} when the
     *                           pass rejected the date ahead of that call
     */
    public record FieldEditResult(
            boolean inputError,
            FieldEditFlag yearFlag,
            FieldEditFlag monthFlag,
            FieldEditFlag dayFlag,
            String firstReturnMessage,
            DateValidationResult dateValidation) {

        /** Rejects a missing flag and holds the message to its declared width. */
        public FieldEditResult {
            Objects.requireNonNull(yearFlag, "yearFlag");
            Objects.requireNonNull(monthFlag, "monthFlag");
            Objects.requireNonNull(dayFlag, "dayFlag");
            firstReturnMessage = firstReturnMessage == null
                    ? NO_RETURN_MESSAGE
                    : cutToWidth(firstReturnMessage, RETURN_MESSAGE_WIDTH);
        }

        /**
         * Reports whether the pass accepted the date.
         *
         * <p>Reads two things: the input-error state, and the severity of the strict-policy call.
         * No flag of the three-byte group takes part. {@code app/cpy/CSUTLDPY.cpy:L324} holds an
         * {@code EXIT} sentence and {@code app/cpy/CSUTLDPY.cpy:L327} sets the group flag valid
         * after it. Whether that statement runs depends on the compiler.
         *
         * @return {@code true} when no field edit failed and the strict-policy call reported
         *         severity {@value CobolDateValidator#ACCEPTED_SEVERITY_NUMBER}
         */
        public boolean accepted() {
            return !inputError
                    && dateValidation != null
                    && dateValidation.acceptedByStrictPolicy();
        }

        /**
         * Reports whether the pass composed a message.
         *
         * <p>Tests {@link #firstReturnMessage()} for spaces, matching
         * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} at {@code app/cbl/COACTUPC.cbl:L480}.
         *
         * @return {@code true} when the pass composed a message
         */
        public boolean hasReturnMessage() {
            return !firstReturnMessage.isBlank();
        }
    }

    /**
     * Validates a date against a mask and reports what {@code app/cbl/CSUTLDTC.cbl} reports.
     *
     * <p>{@code app/cbl/CSUTLDTC.cbl:L116-L120} calls {@code CEEDAYS}. This method reimplements
     * what that call reports.</p>
     *
     * <p>The mask names three tokens: {@value #YEAR_TOKEN} for four characters of year,
     * {@value #MONTH_TOKEN} for two of month, and {@value #DAY_TOKEN} for two of day. Each token
     * appears once, and a {@code -} between two tokens must sit at the same offset in the date.
     *
     * <p>Trailing spaces on either argument are discarded. The two masks in the source are
     * {@value #STRICT_POLICY_DATE_MASK} and {@value #TOLERANT_POLICY_DATE_MASK}.
     *
     * <p>The checks run in this order, and the first failure is the one reported. An unreadable
     * mask yields {@link FeedbackCondition#BAD_PICTURE_STRING}. A date shorter than the mask
     * yields {@link FeedbackCondition#INSUFFICIENT_DATA}. A separator mismatch yields
     * {@link FeedbackCondition#BAD_DATE_VALUE}. A non-digit at a token offset yields
     * {@link FeedbackCondition#NON_NUMERIC_DATA}.
     *
     * <p>Field checks follow. A year of zero yields {@link FeedbackCondition#YEAR_IN_ERA_ZERO}. A
     * month outside 1 through 12 yields {@link FeedbackCondition#INVALID_MONTH}. A day outside the
     * length of that month yields {@link FeedbackCondition#BAD_DATE_VALUE}. A date outside
     * {@link #EARLIEST_SUPPORTED_DATE} through {@link #LATEST_SUPPORTED_DATE} yields
     * {@link FeedbackCondition#UNSUPPORTED_RANGE}, whose message number is
     * {@value #TOLERATED_MESSAGE_NUMBER}.
     *
     * <p>This method calls no mainframe service and throws nothing. Every argument, including
     * {@code null}, produces a result.
     *
     * @param date     the date to test, moved into {@code LS-DATE PIC X(10)} at
     *                 {@code app/cbl/CSUTLDTC.cbl:L84}; may be {@code null}, which is read as
     *                 spaces
     * @param dateMask the mask, moved into {@code LS-DATE-FORMAT PIC X(10)} at
     *                 {@code app/cbl/CSUTLDTC.cbl:L85}; may be {@code null}, which is read as
     *                 spaces
     * @return the severity, the message number, the result text, and the day count of the call
     */
    public static DateValidationResult validateDate(String date, String dateMask) {
        String testedDate = padToWidth(date, TESTED_DATE_WIDTH);
        String testedMask = padToWidth(dateMask, DATE_MASK_WIDTH);

        MaskLayout layout = parseMask(testedMask);
        if (layout == null) {
            return outcome(FeedbackCondition.BAD_PICTURE_STRING, testedDate, testedMask);
        }

        String supplied = stripTrailingSpaces(testedDate);
        if (supplied.length() < layout.width()) {
            return outcome(FeedbackCondition.INSUFFICIENT_DATA, testedDate, testedMask);
        }

        String scanned = supplied.substring(0, layout.width());
        if (!separatorsMatch(scanned, layout.pattern())) {
            return outcome(FeedbackCondition.BAD_DATE_VALUE, testedDate, testedMask);
        }
        if (!tokenCharactersAreDigits(scanned, layout.pattern())) {
            return outcome(FeedbackCondition.NON_NUMERIC_DATA, testedDate, testedMask);
        }

        int year = readDigits(scanned, layout.yearOffset(), YEAR_TOKEN.length());
        int month = readDigits(scanned, layout.monthOffset(), MONTH_TOKEN.length());
        int day = readDigits(scanned, layout.dayOffset(), DAY_TOKEN.length());

        if (year == 0) {
            return outcome(FeedbackCondition.YEAR_IN_ERA_ZERO, testedDate, testedMask);
        }
        if (month < LOWEST_MONTH || month > HIGHEST_MONTH) {
            return outcome(FeedbackCondition.INVALID_MONTH, testedDate, testedMask);
        }

        LocalDate composed;
        try {
            composed = LocalDate.of(year, month, day);
        } catch (DateTimeException dayOutsideMonth) {
            return outcome(FeedbackCondition.BAD_DATE_VALUE, testedDate, testedMask);
        }
        if (composed.isBefore(EARLIEST_SUPPORTED_DATE)
                || composed.isAfter(LATEST_SUPPORTED_DATE)) {
            return outcome(FeedbackCondition.UNSUPPORTED_RANGE, testedDate, testedMask);
        }

        int dayCount = (int) (composed.toEpochDay() - DAY_COUNT_ORIGIN.toEpochDay());
        return outcome(FeedbackCondition.DATE_IS_VALID, testedDate, testedMask, dayCount);
    }

    /**
     * Returns the result text a feedback token selects.
     *
     * <p>Reproduces the ten branches of {@code app/cbl/CSUTLDTC.cbl:L128-L149}. Nine branches name
     * a token, and the tenth is {@code WHEN OTHER} at {@code app/cbl/CSUTLDTC.cbl:L148}, which
     * moves {@value #UNRECOGNISED_FEEDBACK_RESULT_TEXT}.
     *
     * <p>Eight of the ten source literals already fill
     * {@value #RESULT_TEXT_WIDTH} characters. The other two are shorter and the source
     * {@code MOVE} pads them. This method pads the same two.
     *
     * @param feedbackToken sixteen hexadecimal digits; may be {@code null}
     * @return exactly {@value #RESULT_TEXT_WIDTH} characters
     */
    public static String resultTextForFeedbackToken(String feedbackToken) {
        FeedbackCondition condition = FeedbackCondition.fromFeedbackToken(feedbackToken);
        if (condition == null) {
            return padToWidth(UNRECOGNISED_FEEDBACK_RESULT_TEXT, RESULT_TEXT_WIDTH);
        }
        return condition.resultText();
    }

    // The two acceptance policies. Each call site in the source reads one of them, and each
    // predicate below carries one.

    /**
     * Reports whether the tolerant policy accepts a date.
     *
     * <p>Accepts when the severity text is {@value #ACCEPTED_SEVERITY_TEXT} or when the message
     * number text is {@value #TOLERATED_MESSAGE_NUMBER_TEXT}. Both comparisons are textual over
     * {@code PIC X(04)} fields, declared at {@code app/cbl/COTRN02C.cbl:L66} and
     * {@code app/cbl/COTRN02C.cbl:L68}.
     *
     * <p>Four call sites read this policy: {@code app/cbl/COTRN02C.cbl:L397-L400} for an origin
     * date, {@code app/cbl/COTRN02C.cbl:L417-L420} for a process date,
     * {@code app/cbl/CORPT00C.cbl:L396-L399} for a start date, and
     * {@code app/cbl/CORPT00C.cbl:L416-L419} for an end date. The source carries no comment
     * explaining the {@value #TOLERATED_MESSAGE_NUMBER_TEXT} condition.
     *
     * <p>Those four sites pass {@value #TOLERANT_POLICY_DATE_MASK}.
     *
     * @param date     the date to test; may be {@code null}
     * @param dateMask the mask to test it against; may be {@code null}
     * @return {@code true} when the tolerant policy accepts the date
     */
    public static boolean isAcceptedByTolerantPolicy(String date, String dateMask) {
        return validateDate(date, dateMask).acceptedByTolerantPolicy();
    }

    /**
     * Reports whether the tolerant policy accepts an outcome already computed.
     *
     * @param result an outcome of {@link #validateDate(String, String)}
     * @return {@code true} when the tolerant policy accepts the date the outcome describes
     * @throws NullPointerException when {@code result} is {@code null}
     */
    public static boolean isAcceptedByTolerantPolicy(DateValidationResult result) {
        Objects.requireNonNull(result, "result");
        return result.acceptedByTolerantPolicy();
    }

    /**
     * Reports whether the strict policy accepts a date.
     *
     * <p>Accepts only when the numeric severity is {@value #ACCEPTED_SEVERITY_NUMBER}, comparing
     * the redefine {@code WS-SEVERITY-N PIC 9(4)} declared at
     * {@code app/cpy/CSUTLDWY.cpy:L62-L63}. One call site reads this policy, at
     * {@code app/cpy/CSUTLDPY.cpy:L298}, and that paragraph holds no
     * {@value #TOLERATED_MESSAGE_NUMBER_TEXT} branch.
     *
     * <p>That site passes {@value #STRICT_POLICY_DATE_MASK}, moved at
     * {@code app/cpy/CSUTLDPY.cpy:L291}.
     *
     * @param date     the date to test; may be {@code null}
     * @param dateMask the mask to test it against; may be {@code null}
     * @return {@code true} when the strict policy accepts the date
     */
    public static boolean isAcceptedByStrictPolicy(String date, String dateMask) {
        return validateDate(date, dateMask).acceptedByStrictPolicy();
    }

    /**
     * Reports whether the strict policy accepts an outcome already computed.
     *
     * @param result an outcome of {@link #validateDate(String, String)}
     * @return {@code true} when the strict policy accepts the date the outcome describes
     * @throws NullPointerException when {@code result} is {@code null}
     */
    public static boolean isAcceptedByStrictPolicy(DateValidationResult result) {
        Objects.requireNonNull(result, "result");
        return result.acceptedByStrictPolicy();
    }

    // The field edits of app/cpy/CSUTLDPY.cpy. app/cbl/COACTUPC.cbl:L1480-L1481 performs the
    // whole run as PERFORM EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT.

    /**
     * Runs the five field-edit paragraphs of {@code app/cpy/CSUTLDPY.cpy} over an eight-character
     * date.
     *
     * <p>The five run in source order. The year edit sits at
     * {@code app/cpy/CSUTLDPY.cpy:L25-L87}, the month edit at
     * {@code app/cpy/CSUTLDPY.cpy:L91-L144}, and the day edit at
     * {@code app/cpy/CSUTLDPY.cpy:L150-L204}. The combination edit sits at
     * {@code app/cpy/CSUTLDPY.cpy:L209-L279} and the strict-policy call at
     * {@code app/cpy/CSUTLDPY.cpy:L284-L320}.
     *
     * <p>The year edit accepts century {@value #THIS_CENTURY} and century
     * {@value #LAST_CENTURY} and no other, at {@code app/cpy/CSUTLDPY.cpy:L70-L71}. The month edit
     * tests the range at {@code app/cpy/CSUTLDPY.cpy:L111} and then the numeric gate at
     * {@code app/cpy/CSUTLDPY.cpy:L126}. The day edit tests the gate at
     * {@code app/cpy/CSUTLDPY.cpy:L170} and then the range at
     * {@code app/cpy/CSUTLDPY.cpy:L187}. The two orders are opposite in the source, and each
     * paragraph keeps its own order.
     *
     * <p>The combination edit rejects day 31 in a month that holds 30 or fewer, rejects day 30 in
     * February, and tests February day 29 with the divide-and-remainder expression at
     * {@code app/cpy/CSUTLDPY.cpy:L243-L256}. The divisor is
     * {@value #CENTURY_LEAP_DIVISOR} when the two-digit year reads zero and
     * {@value #ORDINARY_LEAP_DIVISOR} otherwise.
     *
     * <p>The result holds one message at most. {@link FieldEditResult#accepted()} reads the
     * input-error state and the severity of the strict-policy call.
     *
     * @param ccyymmdd         eight characters of century, year, month, and day, moved into
     *                         {@code WS-EDIT-DATE-CCYYMMDD} at
     *                         {@code app/cpy/CSUTLDWY.cpy:L4}. A shorter argument is padded with
     *                         spaces and a longer one is cut to eight. {@code null} is read as
     *                         {@code LOW-VALUES}, which
     *                         {@code app/cpy/CSUTLDPY.cpy:L30} tests for
     * @param editVariableName the field name every message opens with, moved into
     *                         {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at
     *                         {@code app/cbl/COACTUPC.cbl:L53} and trimmed of its spaces; may be
     *                         {@code null}
     * @return the input-error state, the three flag bytes, the one message, and the outcome of the
     *         strict-policy call
     */
    public static FieldEditResult editDateCcyymmdd(String ccyymmdd, String editVariableName) {
        String field = editDateField(ccyymmdd);
        EditState state =
                new EditState(trimSpaces(padToWidth(editVariableName, EDIT_VARIABLE_NAME_WIDTH)));

        // app/cpy/CSUTLDPY.cpy:L19 SET WS-EDIT-DATE-IS-INVALID TO TRUE. The three flag bytes of
        // WS-EDIT-DATE-FLGS each take the character zero, per app/cpy/CSUTLDWY.cpy:L45.
        state.yearFlag = FieldEditFlag.NOT_OK;
        state.monthFlag = FieldEditFlag.NOT_OK;
        state.dayFlag = FieldEditFlag.NOT_OK;

        String century = field.substring(CENTURY_OFFSET, CENTURY_OFFSET + CENTURY_WIDTH);
        String twoDigitYear =
                field.substring(TWO_DIGIT_YEAR_OFFSET, TWO_DIGIT_YEAR_OFFSET + TWO_DIGIT_YEAR_WIDTH);
        String monthField = field.substring(MONTH_OFFSET, MONTH_OFFSET + MONTH_WIDTH);
        String dayField = field.substring(DAY_OFFSET, DAY_OFFSET + DAY_WIDTH);

        // The year is never converted, so every reading of WS-EDIT-DATE-CC-N,
        // WS-EDIT-DATE-YY-N, and WS-EDIT-DATE-CCYY-N takes the characters as supplied.
        state.centuryValue = zonedDecimalValue(century);
        state.twoDigitYearValue = zonedDecimalValue(twoDigitYear);
        state.fourDigitYearValue = zonedDecimalValue(century + twoDigitYear);

        editYear(state, century, twoDigitYear);
        editMonth(state, monthField);
        editDay(state, dayField);

        if (!editDayMonthYear(state)) {
            return state.toResult();
        }

        // app/cpy/CSUTLDPY.cpy:L127-L128 and app/cpy/CSUTLDPY.cpy:L171-L172 write the converted
        // month and day back over the two-character fields, so the strict-policy call at
        // app/cpy/CSUTLDPY.cpy:L293-L296 reads eight digits.
        editDateLe(state, century + twoDigitYear
                + zeroPad(state.monthValue, MONTH_WIDTH)
                + zeroPad(state.dayValue, DAY_WIDTH));
        return state.toResult();
    }

    /**
     * Runs the field edits and then the reasonableness check of
     * {@code app/cpy/CSUTLDPY.cpy:L341-L368} against today's date.
     *
     * @param ccyymmdd         eight characters of century, year, month, and day; may be
     *                         {@code null}
     * @param editVariableName the field name every message opens with; may be {@code null}
     * @return the outcome of the field edits, carrying the future-date rejection when it applies
     */
    public static FieldEditResult editDateOfBirth(String ccyymmdd, String editVariableName) {
        return editDateOfBirth(ccyymmdd, editVariableName, LocalDate.now());
    }

    /**
     * Runs the field edits and then the reasonableness check of
     * {@code app/cpy/CSUTLDPY.cpy:L341-L368} against a supplied date.
     *
     * <p>{@code app/cbl/COACTUPC.cbl:L1536-L1543} performs the field edits first and performs the
     * reasonableness check only when they accepted the date. This method follows that sequence, so
     * a rejected date returns the field-edit outcome unchanged.
     *
     * <p>The check accepts only when today is strictly later than the supplied date, at
     * {@code app/cpy/CSUTLDPY.cpy:L350}. Today's own date is a rejection. The rejection sets all
     * three flag bytes to {@link FieldEditFlag#NOT_OK} and composes
     * {@value #FUTURE_DATE_MESSAGE}, which ends with a space.
     *
     * @param ccyymmdd         eight characters of century, year, month, and day; may be
     *                         {@code null}
     * @param editVariableName the field name every message opens with; may be {@code null}
     * @param today            the date the check compares against, standing in for
     *                         {@code FUNCTION CURRENT-DATE} at
     *                         {@code app/cpy/CSUTLDPY.cpy:L343}
     * @return the outcome of the field edits, carrying the future-date rejection when it applies
     * @throws NullPointerException when {@code today} is {@code null}
     */
    public static FieldEditResult editDateOfBirth(String ccyymmdd, String editVariableName,
                                                  LocalDate today) {
        Objects.requireNonNull(today, "today");

        FieldEditResult edits = editDateCcyymmdd(ccyymmdd, editVariableName);
        if (!edits.accepted()) {
            return edits;
        }

        // app/cpy/CSUTLDPY.cpy:L345-L348 turns both dates into a day count, and
        // app/cpy/CSUTLDPY.cpy:L350 compares the two counts.
        LocalDate suppliedDate = DAY_COUNT_ORIGIN.plusDays(edits.dateValidation().dayCount());
        if (today.isAfter(suppliedDate)) {
            return edits;
        }

        String composed = edits.hasReturnMessage()
                ? edits.firstReturnMessage()
                : cutToWidth(
                        trimSpaces(padToWidth(editVariableName, EDIT_VARIABLE_NAME_WIDTH))
                                + FUTURE_DATE_MESSAGE,
                        RETURN_MESSAGE_WIDTH);
        return new FieldEditResult(true, FieldEditFlag.NOT_OK, FieldEditFlag.NOT_OK,
                FieldEditFlag.NOT_OK, composed, edits.dateValidation());
    }

    // One private method per source paragraph.

    /**
     * Runs {@code EDIT-YEAR-CCYY} at {@code app/cpy/CSUTLDPY.cpy:L25-L87}.
     *
     * <p>{@code app/cpy/CSUTLDPY.cpy:L27} opens by setting the year flag to
     * {@link FieldEditFlag#NOT_OK}. Three checks follow, each leaving the paragraph on failure:
     * the year is supplied, the year is four digits, and the century is
     * {@value #THIS_CENTURY} or {@value #LAST_CENTURY}.
     *
     * @param state        the flags and the message the pass accumulates
     * @param century      the two characters of {@code WS-EDIT-DATE-CC}
     * @param twoDigitYear the two characters of {@code WS-EDIT-DATE-YY}
     */
    private static void editYear(EditState state, String century, String twoDigitYear) {
        state.yearFlag = FieldEditFlag.NOT_OK;
        String fourDigitYear = century + twoDigitYear;

        if (isLowValuesOrSpaces(fourDigitYear)) {
            state.inputError = true;
            state.yearFlag = FieldEditFlag.BLANK;
            state.compose(YEAR_NOT_SUPPLIED_MESSAGE);
            return;
        }
        if (!isNumericClass(fourDigitYear)) {
            state.inputError = true;
            state.yearFlag = FieldEditFlag.NOT_OK;
            state.compose(YEAR_NOT_FOUR_DIGITS_MESSAGE);
            return;
        }
        if (state.centuryValue != THIS_CENTURY && state.centuryValue != LAST_CENTURY) {
            state.inputError = true;
            state.yearFlag = FieldEditFlag.NOT_OK;
            state.compose(CENTURY_NOT_VALID_MESSAGE);
            return;
        }
        state.yearFlag = FieldEditFlag.IS_VALID;
    }

    /**
     * Runs {@code EDIT-MONTH} at {@code app/cpy/CSUTLDPY.cpy:L91-L144}.
     *
     * <p>{@code app/cpy/CSUTLDPY.cpy:L92} opens by setting the month flag to
     * {@link FieldEditFlag#NOT_OK}. The range test at {@code app/cpy/CSUTLDPY.cpy:L111} runs
     * ahead of the numeric gate at {@code app/cpy/CSUTLDPY.cpy:L126}, so the range test reads
     * {@code WS-EDIT-DATE-MM-N} while it still holds the characters as supplied. The gate at
     * {@code app/cpy/CSUTLDPY.cpy:L126} and the conversion at
     * {@code app/cpy/CSUTLDPY.cpy:L127-L128} both use the plain numeric function, not the
     * currency-tolerant one.
     *
     * <p>Both failure paths compose {@value #MONTH_OUT_OF_RANGE_MESSAGE}, from
     * {@code app/cpy/CSUTLDPY.cpy:L119} and {@code app/cpy/CSUTLDPY.cpy:L136}.
     *
     * @param state      the flags and the message the pass accumulates
     * @param monthField the two characters of {@code WS-EDIT-DATE-MM}
     */
    private static void editMonth(EditState state, String monthField) {
        state.monthFlag = FieldEditFlag.NOT_OK;
        state.monthValue = zonedDecimalValue(monthField);

        if (isLowValuesOrSpaces(monthField)) {
            state.inputError = true;
            state.monthFlag = FieldEditFlag.BLANK;
            state.compose(MONTH_NOT_SUPPLIED_MESSAGE);
            return;
        }
        if (state.monthValue < LOWEST_MONTH || state.monthValue > HIGHEST_MONTH) {
            state.inputError = true;
            state.monthFlag = FieldEditFlag.NOT_OK;
            state.compose(MONTH_OUT_OF_RANGE_MESSAGE);
            return;
        }
        if (NumvalParser.isValidNumval(monthField)) {
            state.monthValue = NumvalParser.numval(monthField).intValue();
        } else {
            state.inputError = true;
            state.monthFlag = FieldEditFlag.NOT_OK;
            state.compose(MONTH_OUT_OF_RANGE_MESSAGE);
            return;
        }
        state.monthFlag = FieldEditFlag.IS_VALID;
    }

    /**
     * Runs {@code EDIT-DAY} at {@code app/cpy/CSUTLDPY.cpy:L150-L204}.
     *
     * <p>{@code app/cpy/CSUTLDPY.cpy:L152} opens by setting the day flag to
     * {@link FieldEditFlag#IS_VALID}, which is the opposite polarity to the two paragraphs above.
     * The numeric gate at {@code app/cpy/CSUTLDPY.cpy:L170} runs ahead of the range test at
     * {@code app/cpy/CSUTLDPY.cpy:L187}, so the range test reads a converted value. Both use the
     * plain numeric function.
     *
     * <p>{@code app/cpy/CSUTLDPY.cpy:L203} sets the day flag valid a second time, and
     * {@code app/cpy/CSUTLDPY.cpy:L152} already set it. Every failure path leaves the paragraph
     * ahead of that statement, so the second set changes nothing.
     *
     * @param state    the flags and the message the pass accumulates
     * @param dayField the two characters of {@code WS-EDIT-DATE-DD}
     */
    private static void editDay(EditState state, String dayField) {
        state.dayFlag = FieldEditFlag.IS_VALID;
        state.dayValue = zonedDecimalValue(dayField);

        if (isLowValuesOrSpaces(dayField)) {
            state.inputError = true;
            state.dayFlag = FieldEditFlag.BLANK;
            state.compose(DAY_NOT_SUPPLIED_MESSAGE);
            return;
        }
        if (NumvalParser.isValidNumval(dayField)) {
            state.dayValue = NumvalParser.numval(dayField).intValue();
        } else {
            state.inputError = true;
            state.dayFlag = FieldEditFlag.NOT_OK;
            state.compose(DAY_OUT_OF_RANGE_MESSAGE);
            return;
        }
        if (state.dayValue < LOWEST_DAY || state.dayValue > HIGHEST_DAY) {
            state.inputError = true;
            state.dayFlag = FieldEditFlag.NOT_OK;
            state.compose(DAY_OUT_OF_RANGE_MESSAGE);
            return;
        }
        state.dayFlag = FieldEditFlag.IS_VALID;
    }

    /**
     * Runs {@code EDIT-DAY-MONTH-YEAR} at {@code app/cpy/CSUTLDPY.cpy:L209-L279}.
     *
     * <p>Three combination checks run. Day 31 in a month holding fewer days is rejected at
     * {@code app/cpy/CSUTLDPY.cpy:L213-L214}. Day 30 in February is rejected at
     * {@code app/cpy/CSUTLDPY.cpy:L228-L229}. Day 29 in February is tested by dividing the
     * four-digit year and reading the remainder, at
     * {@code app/cpy/CSUTLDPY.cpy:L243-L256}.
     *
     * <p>The closing gate at {@code app/cpy/CSUTLDPY.cpy:L274-L278} tests
     * {@code WS-EDIT-DATE-IS-VALID}, which holds when all three flag bytes hold low values.
     *
     * @param state the flags and the message the pass accumulates
     * @return {@code true} when the pass reaches the strict-policy call
     */
    private static boolean editDayMonthYear(EditState state) {
        if (!isMonthWith31Days(state.monthValue) && state.dayValue == DAY_31) {
            state.inputError = true;
            state.dayFlag = FieldEditFlag.NOT_OK;
            state.monthFlag = FieldEditFlag.NOT_OK;
            state.compose(DAY_31_NOT_IN_MONTH_MESSAGE);
            return false;
        }
        if (state.monthValue == FEBRUARY && state.dayValue == DAY_30) {
            state.inputError = true;
            state.dayFlag = FieldEditFlag.NOT_OK;
            state.monthFlag = FieldEditFlag.NOT_OK;
            state.compose(DAY_30_NOT_IN_MONTH_MESSAGE);
            return false;
        }
        if (state.monthValue == FEBRUARY && state.dayValue == DAY_29) {
            int divisor = state.twoDigitYearValue == 0
                    ? CENTURY_LEAP_DIVISOR
                    : ORDINARY_LEAP_DIVISOR;
            int remainder = state.fourDigitYearValue == UNREADABLE_DIGITS
                    ? UNREADABLE_DIGITS
                    : state.fourDigitYearValue % divisor;
            if (remainder != 0) {
                state.inputError = true;
                state.dayFlag = FieldEditFlag.NOT_OK;
                state.monthFlag = FieldEditFlag.NOT_OK;
                state.yearFlag = FieldEditFlag.NOT_OK;
                state.compose(NOT_A_LEAP_YEAR_MESSAGE);
                return false;
            }
        }
        return state.allFlagsValid();
    }

    /**
     * Runs {@code EDIT-DATE-LE} at {@code app/cpy/CSUTLDPY.cpy:L284-L320}.
     *
     * <p>The paragraph calls the date service and reads the numeric severity at
     * {@code app/cpy/CSUTLDPY.cpy:L298}, holding no
     * {@value #TOLERATED_MESSAGE_NUMBER_TEXT} branch. On rejection it sets the input-error state
     * and all three flag bytes, at {@code app/cpy/CSUTLDPY.cpy:L301-L304}, and composes the
     * severity and the message number, at {@code app/cpy/CSUTLDPY.cpy:L306-L313}.
     *
     * <p>On acceptance {@code app/cpy/CSUTLDPY.cpy:L318-L320} sets the day flag valid and sets
     * neither the month flag nor the year flag. This method reproduces that asymmetry.
     *
     * @param state    the flags and the message the pass accumulates
     * @param editDate the eight digits of {@code WS-EDIT-DATE-CCYYMMDD} after conversion
     */
    private static void editDateLe(EditState state, String editDate) {
        DateValidationResult result = validateDate(editDate, STRICT_POLICY_DATE_MASK);
        state.dateValidation = result;

        if (!result.acceptedByStrictPolicy()) {
            state.inputError = true;
            state.dayFlag = FieldEditFlag.NOT_OK;
            state.monthFlag = FieldEditFlag.NOT_OK;
            state.yearFlag = FieldEditFlag.NOT_OK;
            state.compose(SEVERITY_CODE_MESSAGE + result.severityText()
                    + MESSAGE_CODE_MESSAGE + result.messageNumberText());
            return;
        }
        if (!state.inputError) {
            state.dayFlag = FieldEditFlag.IS_VALID;
        }
    }

    /**
     * Builds one outcome carrying no day count.
     *
     * @param condition  the condition the call reported
     * @param testedDate the ten characters {@code WS-DATE} holds
     * @param dateMask   the ten characters {@code WS-DATE-FMT} holds
     * @return the outcome, whose day count is {@value #NO_DAY_COUNT}
     */
    private static DateValidationResult outcome(FeedbackCondition condition, String testedDate,
                                                String dateMask) {
        return outcome(condition, testedDate, dateMask, NO_DAY_COUNT);
    }

    /**
     * Builds one outcome, deriving both representations of the severity and the message number
     * from the feedback token of the condition.
     *
     * @param condition  the condition the call reported
     * @param testedDate the ten characters {@code WS-DATE} holds
     * @param dateMask   the ten characters {@code WS-DATE-FMT} holds
     * @param dayCount   the value {@code OUTPUT-LILLIAN} receives
     * @return the outcome
     */
    private static DateValidationResult outcome(FeedbackCondition condition, String testedDate,
                                                String dateMask, int dayCount) {
        return new DateValidationResult(
                condition,
                zeroPad(condition.severity(), SEVERITY_WIDTH),
                condition.severity(),
                zeroPad(condition.messageNumber(), MESSAGE_NUMBER_WIDTH),
                condition.messageNumber(),
                condition.resultText(),
                testedDate,
                dateMask,
                dayCount);
    }

    /**
     * Reads a mask into the offsets of its three tokens.
     *
     * <p>Each of {@value #YEAR_TOKEN}, {@value #MONTH_TOKEN}, and {@value #DAY_TOKEN} appears once,
     * and a {@code -} may sit between two tokens. Trailing spaces are discarded, which is what a
     * shorter mask field moved into {@code LS-DATE-FORMAT PIC X(10)} leaves behind.
     *
     * @param dateMask the ten characters {@code LS-DATE-FORMAT} holds
     * @return the layout, or {@code null} when the mask names no readable layout
     */
    private static MaskLayout parseMask(String dateMask) {
        String pattern = stripTrailingSpaces(dateMask);
        int yearOffset = TOKEN_ABSENT;
        int monthOffset = TOKEN_ABSENT;
        int dayOffset = TOKEN_ABSENT;

        int at = 0;
        while (at < pattern.length()) {
            if (yearOffset == TOKEN_ABSENT && pattern.startsWith(YEAR_TOKEN, at)) {
                yearOffset = at;
                at += YEAR_TOKEN.length();
            } else if (monthOffset == TOKEN_ABSENT && pattern.startsWith(MONTH_TOKEN, at)) {
                monthOffset = at;
                at += MONTH_TOKEN.length();
            } else if (dayOffset == TOKEN_ABSENT && pattern.startsWith(DAY_TOKEN, at)) {
                dayOffset = at;
                at += DAY_TOKEN.length();
            } else if (pattern.charAt(at) == MASK_SEPARATOR) {
                at++;
            } else {
                return null;
            }
        }
        if (yearOffset == TOKEN_ABSENT || monthOffset == TOKEN_ABSENT
                || dayOffset == TOKEN_ABSENT) {
            return null;
        }
        return new MaskLayout(pattern, yearOffset, monthOffset, dayOffset);
    }

    /**
     * Reports whether every separator of the mask sits at the same offset in the date.
     *
     * @param scanned the leading characters of the date, as wide as the mask
     * @param pattern the mask with its trailing spaces discarded
     * @return {@code true} when every separator matches
     */
    private static boolean separatorsMatch(String scanned, String pattern) {
        for (int at = 0; at < pattern.length(); at++) {
            if (pattern.charAt(at) == MASK_SEPARATOR && scanned.charAt(at) != MASK_SEPARATOR) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether every token offset of the date holds a digit.
     *
     * @param scanned the leading characters of the date, as wide as the mask
     * @param pattern the mask with its trailing spaces discarded
     * @return {@code true} when every token offset holds a digit
     */
    private static boolean tokenCharactersAreDigits(String scanned, String pattern) {
        for (int at = 0; at < pattern.length(); at++) {
            if (pattern.charAt(at) == MASK_SEPARATOR) {
                continue;
            }
            char character = scanned.charAt(at);
            if (character < LOWEST_DIGIT_CHARACTER || character > HIGHEST_DIGIT_CHARACTER) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads a run of digits as an integer.
     *
     * @param scanned the leading characters of the date, as wide as the mask
     * @param offset  the first character of the run
     * @param width   the count of characters in the run
     * @return the value the run holds
     */
    private static int readDigits(String scanned, int offset, int width) {
        int value = 0;
        for (int at = offset; at < offset + width; at++) {
            value = value * DECIMAL_RADIX + (scanned.charAt(at) - LOWEST_DIGIT_CHARACTER);
        }
        return value;
    }

    /**
     * Reads a field the way a {@code PIC 9(n)} redefine of a {@code PIC X(n)} field reads it.
     *
     * <p>A zoned decimal digit carries its value in the low four bits of its byte, so the redefine
     * takes those four bits from each character. {@code app/cpy/CSUTLDWY.cpy:L17-L18} declares one
     * such redefine over the month field, and {@code app/cpy/CSUTLDPY.cpy:L111} reads it ahead of
     * any conversion.
     *
     * @param field the characters the {@code PIC X(n)} field holds
     * @return the value the redefine reads, or {@value #UNREADABLE_DIGITS} when a character
     *         carries no digit value
     */
    private static int zonedDecimalValue(String field) {
        int value = 0;
        for (int at = 0; at < field.length(); at++) {
            int digit = field.charAt(at) & ZONED_DIGIT_MASK;
            if (digit > HIGHEST_DIGIT) {
                return UNREADABLE_DIGITS;
            }
            value = value * DECIMAL_RADIX + digit;
        }
        return value;
    }

    /**
     * Reports whether a field passes a COBOL numeric class test.
     *
     * <p>{@code app/cpy/CSUTLDPY.cpy:L48} applies the test to the four characters of the year. An
     * unsigned display field passes when every character is a digit.
     *
     * @param field the characters the field holds
     * @return {@code true} when every character is a digit and the field holds at least one
     */
    private static boolean isNumericClass(String field) {
        for (int at = 0; at < field.length(); at++) {
            char character = field.charAt(at);
            if (character < LOWEST_DIGIT_CHARACTER || character > HIGHEST_DIGIT_CHARACTER) {
                return false;
            }
        }
        return !field.isEmpty();
    }

    /**
     * Reports whether a field holds only low values or only spaces.
     *
     * <p>{@code app/cpy/CSUTLDPY.cpy:L30-L31}, {@code app/cpy/CSUTLDPY.cpy:L94-L95}, and
     * {@code app/cpy/CSUTLDPY.cpy:L154-L155} each apply both tests to one field.
     *
     * @param field the characters the field holds
     * @return {@code true} when the field is filled with one of the two values
     */
    private static boolean isLowValuesOrSpaces(String field) {
        return isFilledWith(field, LOW_VALUE) || isFilledWith(field, SPACE);
    }

    /**
     * Reports whether every character of a field is one given character.
     *
     * @param field     the characters the field holds
     * @param character the character to test for
     * @return {@code true} when the field holds at least one character and every one matches
     */
    private static boolean isFilledWith(String field, char character) {
        for (int at = 0; at < field.length(); at++) {
            if (field.charAt(at) != character) {
                return false;
            }
        }
        return !field.isEmpty();
    }

    /**
     * Reports whether a month holds 31 days.
     *
     * <p>{@code app/cpy/CSUTLDWY.cpy:L21-L23} names the seven values of
     * {@code 88 WS-31-DAY-MONTH}.
     *
     * @param month the month value the field holds
     * @return {@code true} when the month is one of the seven
     */
    private static boolean isMonthWith31Days(int month) {
        for (int candidate : MONTHS_WITH_31_DAYS) {
            if (candidate == month) {
                return true;
            }
        }
        return false;
    }

    /**
     * Holds an argument to the width of {@code WS-EDIT-DATE-CCYYMMDD}.
     *
     * @param ccyymmdd the argument; {@code null} is read as {@code LOW-VALUES}
     * @return exactly {@value #EDIT_DATE_WIDTH} characters
     */
    private static String editDateField(String ccyymmdd) {
        if (ccyymmdd == null) {
            return String.valueOf(LOW_VALUE).repeat(EDIT_DATE_WIDTH);
        }
        return padToWidth(ccyymmdd, EDIT_DATE_WIDTH);
    }

    /**
     * Removes the leading and trailing spaces of a field.
     *
     * <p>Reproduces {@code FUNCTION TRIM}, which every message in
     * {@code app/cpy/CSUTLDPY.cpy} applies to {@code WS-EDIT-VARIABLE-NAME}. Only the space
     * character is removed.
     *
     * @param text the characters the field holds
     * @return the characters between the leading and trailing spaces
     */
    private static String trimSpaces(String text) {
        int from = 0;
        int to = text.length();
        while (from < to && text.charAt(from) == SPACE) {
            from++;
        }
        while (to > from && text.charAt(to - 1) == SPACE) {
            to--;
        }
        return text.substring(from, to);
    }

    /**
     * Removes the trailing spaces of a field.
     *
     * @param text the characters the field holds
     * @return the characters ahead of the trailing spaces
     */
    private static String stripTrailingSpaces(String text) {
        int to = text.length();
        while (to > 0 && text.charAt(to - 1) == SPACE) {
            to--;
        }
        return text.substring(0, to);
    }

    /**
     * Holds a value to the width of a {@code PIC X(n)} field.
     *
     * <p>Reproduces a COBOL {@code MOVE} into an alphanumeric field: a short value is padded on
     * the right with spaces and a long one is cut on the right.
     *
     * @param text  the value to hold; {@code null} is read as spaces
     * @param width the width of the field
     * @return exactly {@code width} characters
     */
    private static String padToWidth(String text, int width) {
        if (text == null) {
            return String.valueOf(SPACE).repeat(width);
        }
        if (text.length() == width) {
            return text;
        }
        if (text.length() > width) {
            return text.substring(0, width);
        }
        return text + String.valueOf(SPACE).repeat(width - text.length());
    }

    /**
     * Cuts a value to a width and pads nothing.
     *
     * <p>Reproduces what a {@code STRING} statement leaves in a receiving field it overflows, as
     * the composition at {@code app/cpy/CSUTLDPY.cpy:L306-L313} can do to
     * {@code WS-RETURN-MSG PIC X(75)}.
     *
     * @param text  the value to cut
     * @param width the width of the field
     * @return at most {@code width} characters
     */
    private static String cutToWidth(String text, int width) {
        return text.length() <= width ? text : text.substring(0, width);
    }

    /**
     * Holds a value to the width of a {@code PIC 9(n)} field.
     *
     * <p>Reproduces a COBOL {@code MOVE} into an unsigned numeric field: a short value is padded
     * on the left with zeros and a long one loses its high-order digits.
     *
     * @param value the value to hold
     * @param width the width of the field
     * @return exactly {@code width} digits
     */
    private static String zeroPad(int value, int width) {
        String digits = Integer.toString(Math.abs(value));
        if (digits.length() >= width) {
            return digits.substring(digits.length() - width);
        }
        return String.valueOf(LOWEST_DIGIT_CHARACTER).repeat(width - digits.length()) + digits;
    }

    // Private nested types.

    /**
     * The offsets a mask names.
     *
     * @param pattern     the mask with its trailing spaces discarded
     * @param yearOffset  the offset of the four characters of year
     * @param monthOffset the offset of the two characters of month
     * @param dayOffset   the offset of the two characters of day
     */
    private record MaskLayout(String pattern, int yearOffset, int monthOffset, int dayOffset) {

        /**
         * Returns the count of characters the mask consumes.
         *
         * @return the width of the pattern
         */
        int width() {
            return pattern.length();
        }
    }

    /**
     * The working storage one field-edit pass mutates.
     *
     * <p>Carries the three flag bytes of {@code WS-EDIT-DATE-FLGS}, the state of
     * {@code 88 INPUT-ERROR}, the one message of {@code WS-RETURN-MSG}, and the values the
     * {@code PIC 9(2)} redefines hold. One instance serves one pass, so no state is shared
     * between calls.
     */
    private static final class EditState {

        /** {@code FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)}, which every message opens with. */
        private final String variableName;

        /** The state of {@code 88 INPUT-ERROR VALUE '1'}. */
        private boolean inputError;

        /** The byte {@code WS-EDIT-YEAR-FLG} holds. */
        private FieldEditFlag yearFlag = FieldEditFlag.NOT_OK;

        /** The byte {@code WS-EDIT-MONTH} holds. */
        private FieldEditFlag monthFlag = FieldEditFlag.NOT_OK;

        /** The byte {@code WS-EDIT-DAY} holds. */
        private FieldEditFlag dayFlag = FieldEditFlag.NOT_OK;

        /** The characters {@code WS-RETURN-MSG PIC X(75)} holds. */
        private String returnMessage = NO_RETURN_MESSAGE;

        /** The outcome of the strict-policy call, once the pass reaches it. */
        private DateValidationResult dateValidation;

        /** The value {@code WS-EDIT-DATE-CC-N} reads. */
        private int centuryValue;

        /** The value {@code WS-EDIT-DATE-YY-N} reads. */
        private int twoDigitYearValue;

        /** The value {@code WS-EDIT-DATE-CCYY-N} reads. */
        private int fourDigitYearValue;

        /** The value {@code WS-EDIT-DATE-MM-N} holds, before and after conversion. */
        private int monthValue;

        /** The value {@code WS-EDIT-DATE-DD-N} holds, before and after conversion. */
        private int dayValue;

        /**
         * Opens one pass.
         *
         * @param variableName the trimmed field name every message opens with
         */
        EditState(String variableName) {
            this.variableName = variableName;
        }

        /**
         * Composes a message when the pass has composed none.
         *
         * <p>Reproduces {@code IF WS-RETURN-MSG-OFF}, the gate every message in
         * {@code app/cpy/CSUTLDPY.cpy} sits inside. The gate tests
         * {@code WS-RETURN-MSG PIC X(75)} for spaces at {@code app/cbl/COACTUPC.cbl:L480}, so the
         * first message of a pass is the one that survives.
         *
         * @param literal the message literal to append to the field name
         */
        void compose(String literal) {
            if (!returnMessage.isBlank()) {
                return;
            }
            returnMessage = cutToWidth(variableName + literal, RETURN_MESSAGE_WIDTH);
        }

        /**
         * Reports the state of {@code 88 WS-EDIT-DATE-IS-VALID VALUE LOW-VALUES}.
         *
         * <p>{@code app/cpy/CSUTLDWY.cpy:L44} names the three-byte group valid when every byte
         * holds a low value. {@code app/cpy/CSUTLDPY.cpy:L274} reads the group as the closing
         * gate of the combination edit.
         *
         * @return {@code true} when all three flag bytes hold a low value
         */
        boolean allFlagsValid() {
            return yearFlag == FieldEditFlag.IS_VALID
                    && monthFlag == FieldEditFlag.IS_VALID
                    && dayFlag == FieldEditFlag.IS_VALID;
        }

        /**
         * Freezes the pass into its outcome.
         *
         * @return the input-error state, the three flag bytes, the one message, and the outcome of
         *         the strict-policy call
         */
        FieldEditResult toResult() {
            return new FieldEditResult(inputError, yearFlag, monthFlag, dayFlag, returnMessage,
                    dateValidation);
        }
    }
}

