package com.carddemo.account.domain.validation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link YesNoFlagValidator}, which realises {@code 1220-EDIT-YESNO} at
 * app/cbl/COACTUPC.cbl:L1856 and ends at app/cbl/COACTUPC.cbl:L1894.
 *
 * <p>The paragraph edits {@code WS-EDIT-YES-NO PIC X(1) VALUE 'N'} at
 * app/cbl/COACTUPC.cbl:L76-L77. One byte carries the value and the flag, under three condition
 * names at app/cbl/COACTUPC.cbl:L78-L80. The dual-purpose byte is recorded in
 * card-platform/docs/business-rule-flags.md.</p>
 *
 * <p>Two tests run in order, and each failing test ends the paragraph. The presence test at
 * app/cbl/COACTUPC.cbl:L1861-L1863 reads {@code LOW-VALUES}, {@code SPACES} and {@code ZEROS}.
 * It carries no trim-length clause, and the presence test of {@code 1215-EDIT-MANDATORY} at
 * app/cbl/COACTUPC.cbl:L1829-L1834 carries one. A value of {@code 0} therefore reaches the
 * message at app/cbl/COACTUPC.cbl:L1869. The character-class test at app/cbl/COACTUPC.cbl:L1878
 * reaches the message at app/cbl/COACTUPC.cbl:L1886.</p>
 *
 * <p>Two source facts shape the assertions below. The entry {@code SET} at
 * app/cbl/COACTUPC.cbl:L1858 is commented out. The callers copy the edited byte back out at
 * app/cbl/COACTUPC.cbl:L1476 and app/cbl/COACTUPC.cbl:L1662, and the target drops that
 * write-back. Both facts are recorded in card-platform/docs/decision-log.md.</p>
 *
 * <p>Every input below is built in the test. The class reads plain strings, and {@code mvn test}
 * runs it with no database, no broker and no container runtime.</p>
 */
@DisplayName("YesNoFlagValidator, the yes or no field edit")
class YesNoFlagValidatorTest {

    /** Label the first call site moves in at app/cbl/COACTUPC.cbl:L1472. */
    private static final String ACCOUNT_STATUS_LABEL = "Account Status";

    /** Label the second call site moves in at app/cbl/COACTUPC.cbl:L1657. */
    private static final String PRIMARY_CARD_HOLDER_LABEL = "Primary Card Holder";

    /** Declared width of {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at app/cbl/COACTUPC.cbl:L53. */
    private static final int SOURCE_LABEL_WIDTH = 25;

    /**
     * Message literal copied from app/cbl/COACTUPC.cbl:L1869. Eighteen characters, opening with
     * one space and closing with one period.
     */
    private static final String NOT_SUPPLIED_LITERAL = " must be supplied.";

    /**
     * Message literal copied from app/cbl/COACTUPC.cbl:L1886. Sixteen characters, opening with
     * one space and closing with one period.
     */
    private static final String NOT_YES_OR_NO_LITERAL = " must be Y or N.";

    /** The first call site's label as the 25-character host holds it, padded with spaces. */
    private static final String PADDED_ACCOUNT_STATUS_LABEL =
            ACCOUNT_STATUS_LABEL + " ".repeat(SOURCE_LABEL_WIDTH - ACCOUNT_STATUS_LABEL.length());

    /** The one character {@code LOW-VALUES} moves into a {@code PIC X} field. */
    private static final String LOW_VALUES_VALUE = Character.toString('\0');

    /** Values wider than the one-character source field, read by the reachable-message sweep. */
    private static final List<String> WIDER_THAN_SOURCE_VALUES =
            List.of("YY", "NN", "YN", "Y ", " N", "0X", "X0", "yes", "no", "  0", "B0", "000");

    @Test
    @DisplayName("The two message literals match the source literals character for character")
    void messageLiteralsMatchTheSourceLiterals() {
        // app/cbl/COACTUPC.cbl:L1869 and app/cbl/COACTUPC.cbl:L1886.
        assertThat(YesNoFlagValidator.NOT_SUPPLIED_MESSAGE_SUFFIX).isEqualTo(NOT_SUPPLIED_LITERAL);
        assertThat(YesNoFlagValidator.NOT_YES_OR_NO_MESSAGE_SUFFIX).isEqualTo(NOT_YES_OR_NO_LITERAL);

        assertThat(NOT_SUPPLIED_LITERAL).hasSize(18).startsWith(" ").endsWith(".");
        assertThat(NOT_YES_OR_NO_LITERAL).hasSize(16).startsWith(" ").endsWith(".");
        assertThat(NOT_SUPPLIED_LITERAL).isNotEqualTo(NOT_YES_OR_NO_LITERAL);

        // app/cbl/COACTUPC.cbl:L78 lists the two accepted values in upper case.
        assertThat(YesNoFlagValidator.YES).isEqualTo("Y");
        assertThat(YesNoFlagValidator.NO).isEqualTo("N");
    }

    @Test
    @DisplayName("A value of Y passes and a value of N passes, each with no message")
    void yesAndNoPassWithNoMessage() {
        // app/cbl/COACTUPC.cbl:L78 - 88 FLG-YES-NO-ISVALID VALUES 'Y', 'N'.
        EditResult yes = YesNoFlagValidator.validate(ACCOUNT_STATUS_LABEL, "Y");
        EditResult no = YesNoFlagValidator.validate(ACCOUNT_STATUS_LABEL, "N");

        assertThat(yes.valid()).isTrue();
        assertThat(yes.message()).isNull();
        assertThat(yes.hasMessage()).isFalse();

        assertThat(no.valid()).isTrue();
        assertThat(no.message()).isNull();
        assertThat(no.hasMessage()).isFalse();
    }

    @Test
    @DisplayName("The passing verdict carries no flag and no message, and repeats without drift")
    void passingVerdictCarriesNoFlagAndNoMessage() {
        // app/cbl/COACTUPC.cbl:L1878-L1879 - IF FLG-YES-NO-ISVALID / CONTINUE.
        assertThat(YesNoFlagValidator.validate(ACCOUNT_STATUS_LABEL, "Y")).isEqualTo(EditResult.ok());
        assertThat(YesNoFlagValidator.validate(PRIMARY_CARD_HOLDER_LABEL, "N"))
                .isEqualTo(EditResult.ok());

        EditResult first = YesNoFlagValidator.validate(ACCOUNT_STATUS_LABEL, "Y");
        EditResult second = YesNoFlagValidator.validate(ACCOUNT_STATUS_LABEL, "Y");
        EditResult afterFailure = YesNoFlagValidator.validate(ACCOUNT_STATUS_LABEL, "X");
        EditResult third = YesNoFlagValidator.validate(ACCOUNT_STATUS_LABEL, "Y");

        assertThat(first).isEqualTo(second).isEqualTo(third);
        assertThat(afterFailure.valid()).isFalse();
        assertThat(third.message()).isNull();
    }

    @Test
    @DisplayName("A value of zero reports the supplied message and not the Y or N message")
    void zeroReportsTheNotSuppliedMessage() {
        // app/cbl/COACTUPC.cbl:L1863 - OR WS-EDIT-YES-NO EQUAL ZEROS, with no trim-length clause.
        EditResult result = YesNoFlagValidator.validate(ACCOUNT_STATUS_LABEL, "0");

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(ACCOUNT_STATUS_LABEL + NOT_SUPPLIED_LITERAL);

        // The character-class message at app/cbl/COACTUPC.cbl:L1886 is not produced here.
        assertThat(result.message()).isNotEqualTo(ACCOUNT_STATUS_LABEL + NOT_YES_OR_NO_LITERAL);
        assertThat(result.message()).doesNotContain("Y or N");

        // ZEROS fills the whole field, so a wider run of zeros lands on the same branch.
        assertThat(YesNoFlagValidator.validate(PRIMARY_CARD_HOLDER_LABEL, "00").message())
                .isEqualTo(PRIMARY_CARD_HOLDER_LABEL + NOT_SUPPLIED_LITERAL);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "0", "00", "000"})
    @DisplayName("A value that was not supplied reports the supplied message")
    void valuesThatWereNotSuppliedReportTheNotSuppliedMessage(String value) {
        // app/cbl/COACTUPC.cbl:L1861-L1863 and the message at app/cbl/COACTUPC.cbl:L1869.
        EditResult result = YesNoFlagValidator.validate(ACCOUNT_STATUS_LABEL, value);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(ACCOUNT_STATUS_LABEL + NOT_SUPPLIED_LITERAL);
    }

    @Test
    @DisplayName("A value of low values reports the supplied message")
    void lowValuesReportsTheNotSuppliedMessage() {
        // app/cbl/COACTUPC.cbl:L1861 - IF WS-EDIT-YES-NO EQUAL LOW-VALUES.
        EditResult oneByte = YesNoFlagValidator.validate(ACCOUNT_STATUS_LABEL, LOW_VALUES_VALUE);
        EditResult twoBytes =
                YesNoFlagValidator.validate(ACCOUNT_STATUS_LABEL, LOW_VALUES_VALUE.repeat(2));

        assertThat(oneByte.valid()).isFalse();
        assertThat(oneByte.message()).isEqualTo(ACCOUNT_STATUS_LABEL + NOT_SUPPLIED_LITERAL);
        assertThat(twoBytes.message()).isEqualTo(ACCOUNT_STATUS_LABEL + NOT_SUPPLIED_LITERAL);
    }

    /**
     * Reads values the character-class test at app/cbl/COACTUPC.cbl:L1878 rejects. {@code B} is
     * among them, and app/cbl/COACTUPC.cbl:L80 names {@code B} as the blank flag byte.
     *
     * @param value the value to edit
     */
    @ParameterizedTest
    @ValueSource(strings = {"X", "B", "Z", "1", "9", "-", "*", "YN", "Y ", " N", "0X"})
    @DisplayName("Any other value reports the Y or N message")
    void otherValuesReportTheYesOrNoMessage(String value) {
        // app/cbl/COACTUPC.cbl:L1886 - ' must be Y or N.'
        EditResult result = YesNoFlagValidator.validate(ACCOUNT_STATUS_LABEL, value);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(ACCOUNT_STATUS_LABEL + NOT_YES_OR_NO_LITERAL);
    }

    @Test
    @DisplayName("Lower case y and lower case n report the Y or N message")
    void lowerCaseYesAndNoReportTheYesOrNoMessage() {
        // app/cbl/COACTUPC.cbl:L78 lists 'Y' and 'N' alone, and the comparison is case sensitive.
        EditResult lowerYes = YesNoFlagValidator.validate(ACCOUNT_STATUS_LABEL, "y");
        EditResult lowerNo = YesNoFlagValidator.validate(PRIMARY_CARD_HOLDER_LABEL, "n");

        assertThat(lowerYes.valid()).isFalse();
        assertThat(lowerYes.message()).isEqualTo(ACCOUNT_STATUS_LABEL + NOT_YES_OR_NO_LITERAL);

        assertThat(lowerNo.valid()).isFalse();
        assertThat(lowerNo.message()).isEqualTo(PRIMARY_CARD_HOLDER_LABEL + NOT_YES_OR_NO_LITERAL);
    }

    @Test
    @DisplayName("Both messages open with the trimmed label, and the padding stays out")
    void bothMessagesOpenWithTheTrimmedLabel() {
        // app/cbl/COACTUPC.cbl:L1868 and app/cbl/COACTUPC.cbl:L1885 - FUNCTION TRIM.
        assertThat(PADDED_ACCOUNT_STATUS_LABEL).hasSize(SOURCE_LABEL_WIDTH).endsWith(" ");

        EditResult notSupplied = YesNoFlagValidator.validate(PADDED_ACCOUNT_STATUS_LABEL, " ");
        EditResult notYesOrNo = YesNoFlagValidator.validate(PADDED_ACCOUNT_STATUS_LABEL, "X");

        assertThat(notSupplied.message())
                .isEqualTo(ACCOUNT_STATUS_LABEL + NOT_SUPPLIED_LITERAL)
                .hasSize(ACCOUNT_STATUS_LABEL.length() + NOT_SUPPLIED_LITERAL.length())
                .doesNotContain("  ");
        assertThat(notYesOrNo.message())
                .isEqualTo(ACCOUNT_STATUS_LABEL + NOT_YES_OR_NO_LITERAL)
                .hasSize(ACCOUNT_STATUS_LABEL.length() + NOT_YES_OR_NO_LITERAL.length())
                .doesNotContain("  ");

        String paddedBothSides = "   " + ACCOUNT_STATUS_LABEL + "   ";

        assertThat(YesNoFlagValidator.validate(paddedBothSides, "X").message())
                .isEqualTo(ACCOUNT_STATUS_LABEL + NOT_YES_OR_NO_LITERAL)
                .startsWith("A");

        // A label holding nothing leaves the literal's own leading space in place.
        assertThat(YesNoFlagValidator.validate(null, "X").message()).isEqualTo(NOT_YES_OR_NO_LITERAL);
        assertThat(YesNoFlagValidator.validate("     ", "").message()).isEqualTo(NOT_SUPPLIED_LITERAL);
    }

    @Test
    @DisplayName("The two call site labels produce the two source messages")
    void theTwoCallSiteLabelsProduceTheSourceMessages() {
        // app/cbl/COACTUPC.cbl:L1472-L1474 - 'Account Status'.
        assertThat(YesNoFlagValidator.validate(ACCOUNT_STATUS_LABEL, "").message())
                .isEqualTo("Account Status must be supplied.");
        assertThat(YesNoFlagValidator.validate(ACCOUNT_STATUS_LABEL, "X").message())
                .isEqualTo("Account Status must be Y or N.");

        // app/cbl/COACTUPC.cbl:L1657-L1660 - 'Primary Card Holder'.
        assertThat(YesNoFlagValidator.validate(PRIMARY_CARD_HOLDER_LABEL, "").message())
                .isEqualTo("Primary Card Holder must be supplied.");
        assertThat(YesNoFlagValidator.validate(PRIMARY_CARD_HOLDER_LABEL, "X").message())
                .isEqualTo("Primary Card Holder must be Y or N.");
    }

    @Test
    @DisplayName("Exactly two distinct messages are reachable, and Y and N are the only passing values")
    void exactlyTwoDistinctMessagesAreReachable() {
        Set<String> passingValues = new LinkedHashSet<>();
        Set<String> messages = new LinkedHashSet<>();

        for (int character = Character.MIN_VALUE; character <= Character.MAX_VALUE; character++) {
            collectOutcome(String.valueOf((char) character), passingValues, messages);
        }
        for (String value : WIDER_THAN_SOURCE_VALUES) {
            collectOutcome(value, passingValues, messages);
        }
        collectOutcome(null, passingValues, messages);

        // app/cbl/COACTUPC.cbl:L1869 and app/cbl/COACTUPC.cbl:L1886 are the whole message set.
        assertThat(messages).containsExactlyInAnyOrder(
                ACCOUNT_STATUS_LABEL + NOT_SUPPLIED_LITERAL,
                ACCOUNT_STATUS_LABEL + NOT_YES_OR_NO_LITERAL);
        assertThat(passingValues)
                .containsExactlyInAnyOrder(YesNoFlagValidator.YES, YesNoFlagValidator.NO);
    }

    /**
     * Reads both arguments after the call. The source writes a flag into the value byte at
     * app/cbl/COACTUPC.cbl:L1865 and app/cbl/COACTUPC.cbl:L1882, and the callers copy that byte
     * back out at app/cbl/COACTUPC.cbl:L1476 and app/cbl/COACTUPC.cbl:L1662. The target carries
     * its verdict in the return value.
     */
    @Test
    @DisplayName("The call leaves the label and the value it was handed unchanged")
    void validateLeavesItsArgumentsUnchanged() {
        String label = new StringBuilder(PADDED_ACCOUNT_STATUS_LABEL).toString();
        String zeroValue = new StringBuilder("0").toString();
        char[] labelBefore = label.toCharArray();
        char[] zeroBefore = zeroValue.toCharArray();

        EditResult notSupplied = YesNoFlagValidator.validate(label, zeroValue);

        assertThat(notSupplied.message()).isEqualTo(ACCOUNT_STATUS_LABEL + NOT_SUPPLIED_LITERAL);
        assertThat(label.toCharArray()).containsExactly(labelBefore);
        assertThat(label)
                .isEqualTo(PADDED_ACCOUNT_STATUS_LABEL)
                .hasSize(SOURCE_LABEL_WIDTH)
                .endsWith(" ");
        assertThat(zeroValue.toCharArray()).containsExactly(zeroBefore);
        assertThat(zeroValue).isEqualTo("0");

        String flagValue = new StringBuilder("X").toString();
        char[] flagBefore = flagValue.toCharArray();

        EditResult notYesOrNo = YesNoFlagValidator.validate(label, flagValue);

        assertThat(notYesOrNo.message()).isEqualTo(ACCOUNT_STATUS_LABEL + NOT_YES_OR_NO_LITERAL);
        assertThat(flagValue.toCharArray()).containsExactly(flagBefore);
        assertThat(flagValue).isEqualTo("X");
        assertThat(label).isEqualTo(PADDED_ACCOUNT_STATUS_LABEL);
    }

    /**
     * Reads the class surface. The entry {@code SET} at app/cbl/COACTUPC.cbl:L1858 is commented
     * out, and the flag writes at app/cbl/COACTUPC.cbl:L1865 and app/cbl/COACTUPC.cbl:L1882 have
     * no target equivalent. One static method, returning a verdict, is the whole surface.
     */
    @Test
    @DisplayName("The validator exposes one static entry point and no mutating overload")
    void theValidatorExposesOneStaticEntryPoint() {
        assertThat(Modifier.isFinal(YesNoFlagValidator.class.getModifiers())).isTrue();

        Constructor<?>[] constructors = YesNoFlagValidator.class.getDeclaredConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(Modifier.isPrivate(constructors[0].getModifiers())).isTrue();
        assertThat(constructors[0].getParameterCount()).isZero();

        List<Method> publicMethods = new ArrayList<>();
        for (Method method : YesNoFlagValidator.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                publicMethods.add(method);
            }
        }

        // One method, returning a verdict. A void overload writing a flag back would fail here.
        assertThat(publicMethods).hasSize(1);

        Method entryPoint = publicMethods.get(0);

        assertThat(entryPoint.getName()).isEqualTo("validate");
        assertThat(Modifier.isStatic(entryPoint.getModifiers())).isTrue();
        assertThat(entryPoint.getReturnType()).isEqualTo(EditResult.class);
        assertThat(entryPoint.getParameterTypes()).containsExactly(String.class, String.class);
    }

    /**
     * Edits one value with the first call site's label and files the outcome. A passing verdict
     * adds the value to {@code passingValues}, and a failing verdict adds its message to
     * {@code messages}.
     *
     * @param value         the value to edit, which may be null
     * @param passingValues the values that passed the edit
     * @param messages      the distinct messages the edit produced
     */
    private static void collectOutcome(String value, Set<String> passingValues,
            Set<String> messages) {
        EditResult result = YesNoFlagValidator.validate(ACCOUNT_STATUS_LABEL, value);
        if (result.valid()) {
            passingValues.add(value);
        } else {
            messages.add(result.message());
        }
    }
}
