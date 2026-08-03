package com.carddemo.account.domain.validation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AccountIdValidator}, the field edit that realises paragraph
 * {@code 1210-EDIT-ACCOUNT} at app/cbl/COACTUPC.cbl:L1783-L1820. One call site performs the
 * paragraph as a range, at app/cbl/COACTUPC.cbl:L1435-L1436.
 *
 * <p>The paragraph reaches two failing branches and one passing branch. Lines 1787 and 1788
 * test the field for low values or for spaces, and line 1792 sets condition name
 * {@code WS-PROMPT-FOR-ACCT} from app/cbl/COACTUPC.cbl:L483-L484. Lines 1802 and 1803 test the
 * field for numeric-ness and for eleven zeros. The {@code STRING} at lines 1806 to 1810 joins
 * the two literals on lines 1807 and 1808. Line 1816 marks the field valid.</p>
 *
 * <p>The field is {@code CC-ACCT-ID PIC X(11)} at app/cpy/CVCRD01Y.cpy:L34-L35, and the numeric
 * redefine {@code CC-ACCT-ID-N PIC 9(11)} at app/cpy/CVCRD01Y.cpy:L36 covers the same eleven
 * bytes. Lines 1802 and 1803 carry no length limb of their own. The methods below assert a pass
 * for an eleven-digit non-zero field whatever the width of the value it holds. A field of some
 * other width fails through the numeric limb and carries the same message as a field holding a
 * letter. app/cbl/COACTUPC.cbl:L1056 pads the fixed-width field on the right with spaces, and a
 * space is not a digit.</p>
 *
 * <p>The paragraph also writes {@code CDEMO-ACCT-ID} and {@code ACUP-NEW-ACCT-ID}, at lines
 * 1794, 1795, 1801, 1812 and 1815. {@code AccountIdValidator} writes neither field and keeps no
 * state, so no method below reads either one. Decision record:
 * card-platform/docs/decision-log.md. Source findings for paragraph 1210:
 * card-platform/docs/business-rule-flags.md.</p>
 *
 * <p>Each method builds every input it uses. The methods need no Spring context, no broker and
 * no database, so {@code mvn test} runs them on a machine carrying no container runtime.</p>
 */
@DisplayName("AccountIdValidator, the account number edit of paragraph 1210")
class AccountIdValidatorTest {

    /** Text of {@code WS-PROMPT-FOR-ACCT} at app/cbl/COACTUPC.cbl:L483-L484, set at line 1792. */
    private static final String NOT_PROVIDED = "Account number not provided";

    /** Length of {@code NOT_PROVIDED}, counted on app/cbl/COACTUPC.cbl:L484. */
    private static final int NOT_PROVIDED_LENGTH = 27;

    /** First literal of the {@code STRING} statement, at app/cbl/COACTUPC.cbl:L1807. */
    private static final String FIRST_LITERAL = "Account Number if supplied must be a 11 digit";

    /** Length of the first literal, counted on app/cbl/COACTUPC.cbl:L1807. */
    private static final int FIRST_LITERAL_LENGTH = 45;

    /** Second literal, at app/cbl/COACTUPC.cbl:L1808, opening with a space. */
    private static final String SECOND_LITERAL = " Non-Zero Number";

    /** Length of the second literal, counted on app/cbl/COACTUPC.cbl:L1808. */
    private static final int SECOND_LITERAL_LENGTH = 16;

    /**
     * Text the {@code STRING} at app/cbl/COACTUPC.cbl:L1806-L1810 builds. {@code DELIMITED BY
     * SIZE} on line 1809 joins both literals whole.
     */
    private static final String SUPPLIED_NUMBER_RULE = FIRST_LITERAL + SECOND_LITERAL;

    /** A field of eleven digits holding a non-zero value, the one passing shape. */
    private static final String VALID_ACCOUNT_ID = "12345678901";

    /** The value {@code CC-ACCT-ID-N EQUAL ZEROS} catches at app/cbl/COACTUPC.cbl:L1803. */
    private static final String ELEVEN_ZEROS = "00000000000";

    /** A field of eleven spaces, the initial value declared at app/cpy/CVCRD01Y.cpy:L35. */
    private static final String ELEVEN_SPACES = "           ";

    @Test
    @DisplayName("A field of spaces, an empty field and a missing field all report the account number not provided")
    void notSuppliedFieldReportsTheAccountNumberNotProvided() {
        List<String> notSupplied = List.of("", " ", "   ", ELEVEN_SPACES);

        for (String field : notSupplied) {
            EditResult result = AccountIdValidator.validate(field);

            assertThat(result.valid()).as("field of width %d", field.length()).isFalse();
            assertThat(result.message()).as("field of width %d", field.length()).isEqualTo(NOT_PROVIDED);
            assertThat(result.hasMessage()).as("field of width %d", field.length()).isTrue();
        }

        EditResult missing = AccountIdValidator.validate(null);

        assertThat(missing.valid()).isFalse();
        assertThat(missing.message()).isEqualTo(NOT_PROVIDED);
        assertThat(missing.hasMessage()).isTrue();
    }

    @Test
    @DisplayName("The not provided message matches its source text exactly, with no field label and no closing full stop")
    void notProvidedMessageMatchesItsSourceTextExactly() {
        String message = AccountIdValidator.validate(ELEVEN_SPACES).message();

        assertThat(message)
                .isEqualTo("Account number not provided")
                .isEqualTo(NOT_PROVIDED)
                .hasSize(NOT_PROVIDED_LENGTH)
                .startsWith("Account number")
                .doesNotEndWith(".")
                .doesNotContain(":")
                .doesNotContain("ACCTSID")
                .doesNotContain("Account Number");
    }

    @Test
    @DisplayName("A field holding a letter and a field of eleven zeros both report the supplied number rule")
    void notNumericAndAllZeroFieldsReportTheSuppliedNumberRule() {
        List<String> rejected = List.of("1234567890A", "A2345678901", "1234-678901", "1234567890 ", ELEVEN_ZEROS);

        for (String field : rejected) {
            EditResult result = AccountIdValidator.validate(field);

            assertThat(result.valid()).as("field '%s'", field).isFalse();
            assertThat(result.message()).as("field '%s'", field).isEqualTo(SUPPLIED_NUMBER_RULE);
            assertThat(result.hasMessage()).as("field '%s'", field).isTrue();
        }
    }

    @Test
    @DisplayName("The supplied number message joins both source literals character for character")
    void suppliedNumberMessageJoinsBothSourceLiterals() {
        assertThat(FIRST_LITERAL)
                .isEqualTo("Account Number if supplied must be a 11 digit")
                .hasSize(FIRST_LITERAL_LENGTH)
                .doesNotEndWith(".");
        assertThat(SECOND_LITERAL)
                .isEqualTo(" Non-Zero Number")
                .hasSize(SECOND_LITERAL_LENGTH)
                .startsWith(" ")
                .doesNotEndWith(".");

        String message = AccountIdValidator.validate(ELEVEN_ZEROS).message();

        assertThat(message)
                .isEqualTo("Account Number if supplied must be a 11 digit Non-Zero Number")
                .isEqualTo(FIRST_LITERAL + SECOND_LITERAL)
                .hasSize(FIRST_LITERAL_LENGTH + SECOND_LITERAL_LENGTH)
                .startsWith("Account Number")
                .endsWith("Non-Zero Number")
                .contains("must be a 11 digit Non-Zero Number")
                .doesNotEndWith(".")
                .doesNotContain(":")
                .doesNotContain("  ")
                .doesNotContain("an 11");
    }

    @Test
    @DisplayName("A field of eleven digits holding a non zero value passes and leaves the message slot empty")
    void elevenDigitNonZeroFieldPasses() {
        EditResult result = AccountIdValidator.validate(VALID_ACCOUNT_ID);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
        assertThat(result).isEqualTo(EditResult.ok());
    }

    @Test
    @DisplayName("An eleven digit non zero field passes whatever the width of the value it holds")
    void elevenDigitFieldPassesWhateverWidthOfValueItHolds() {
        List<String> passing = List.of("00000000001", "00000000042", "00000012345", "00999999999", "10000000000");

        for (String field : passing) {
            EditResult result = AccountIdValidator.validate(field);

            assertThat(result.valid()).as("field '%s'", field).isTrue();
            assertThat(result.message()).as("field '%s'", field).isNull();
            assertThat(result.hasMessage()).as("field '%s'", field).isFalse();
        }
    }

    @Test
    @DisplayName("A field of fewer or more than eleven digits carries the one supplied number message")
    void fieldOfOtherThanElevenDigitsCarriesTheOneSuppliedNumberMessage() {
        List<String> otherWidths = List.of("1", "12345", "1234567890", "123456789012", "123456789012345");

        for (String field : otherWidths) {
            EditResult result = AccountIdValidator.validate(field);

            assertThat(result.valid()).as("field of width %d", field.length()).isFalse();
            assertThat(result.message()).as("field of width %d", field.length()).isEqualTo(SUPPLIED_NUMBER_RULE);
        }
    }

    @Test
    @DisplayName("Exactly two messages are reachable, and no field produces a third")
    void exactlyTwoMessagesAreReachable() {
        List<String> everyShape = List.of(
                "", " ", "   ", ELEVEN_SPACES,
                "1", "12345", "1234567890", "123456789012", "123456789012345",
                VALID_ACCOUNT_ID, "00000000001", "10000000000",
                "1234567890A", "A2345678901", "0000000000A", "1234-678901",
                "1234567890 ", " 1234567890", "1.234567890", "-1234567890", "+1234567890",
                ELEVEN_ZEROS);

        Set<String> reachable = new LinkedHashSet<>();

        for (String field : everyShape) {
            EditResult result = AccountIdValidator.validate(field);

            if (result.valid()) {
                assertThat(result.message()).as("field '%s'", field).isNull();
            } else {
                reachable.add(result.message());
            }
        }
        reachable.add(AccountIdValidator.validate(null).message());

        assertThat(reachable).containsExactlyInAnyOrder(NOT_PROVIDED, SUPPLIED_NUMBER_RULE);
        assertThat(reachable).hasSize(2);
    }

    @Test
    @DisplayName("AccountIdValidator exposes one static edit and no instance")
    void validatorExposesOneStaticEditAndNoInstance() throws NoSuchMethodException {
        assertThat(Modifier.isFinal(AccountIdValidator.class.getModifiers())).isTrue();

        Constructor<?>[] constructors = AccountIdValidator.class.getDeclaredConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(Modifier.isPrivate(constructors[0].getModifiers())).isTrue();
        assertThat(constructors[0].getParameterCount()).isZero();

        List<Method> publicMethods = Arrays.stream(AccountIdValidator.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();
        Method edit = AccountIdValidator.class.getMethod("validate", String.class);

        assertThat(publicMethods).hasSize(1);
        assertThat(publicMethods.getFirst().getName()).isEqualTo("validate");
        assertThat(Modifier.isStatic(edit.getModifiers())).isTrue();
        assertThat(edit.getReturnType()).isEqualTo(EditResult.class);
    }

    @Test
    @DisplayName("Repeated calls on one field agree, and a call on another field leaves the verdict alone")
    void repeatedCallsOnOneFieldAgree() {
        EditResult first = AccountIdValidator.validate(VALID_ACCOUNT_ID);

        assertThat(AccountIdValidator.validate(ELEVEN_ZEROS)).isEqualTo(EditResult.failure(SUPPLIED_NUMBER_RULE));
        assertThat(AccountIdValidator.validate(ELEVEN_SPACES)).isEqualTo(EditResult.failure(NOT_PROVIDED));

        EditResult second = AccountIdValidator.validate(VALID_ACCOUNT_ID);

        assertThat(second).isEqualTo(first).isEqualTo(EditResult.ok());
    }
}
