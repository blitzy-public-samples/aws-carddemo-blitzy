package com.carddemo.account.domain.validation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link MandatoryFieldValidator}, the presence edit of one mandatory field.
 *
 * <p>The subject realises paragraph {@code 1215-EDIT-MANDATORY} at app/cbl/COACTUPC.cbl:L1824,
 * whose exit paragraph sits at app/cbl/COACTUPC.cbl:L1852. The not-supplied test at
 * app/cbl/COACTUPC.cbl:L1829-L1834 holds three alternatives over the reference-modified substring
 * {@code WS-EDIT-ALPHANUM-ONLY(1:WS-EDIT-ALPHANUM-LENGTH)}. The substring equals
 * {@code LOW-VALUES}, or it equals {@code SPACES}, or its trimmed length is zero. The methods below
 * cover one alternative each.</p>
 *
 * <p>One call site performs the paragraph. app/cbl/COACTUPC.cbl:L1584-L1586 moves the label
 * {@code 'Address Line 1'}, the submitted value, and the length 50 into working storage, and
 * app/cbl/COACTUPC.cbl:L1587 then performs the paragraph. Those three moves fix the three-argument
 * order every method below uses, and the last method pins that order.</p>
 *
 * <p>Host widths the constants below carry: {@code WS-EDIT-VARIABLE-NAME PIC X(25)} at
 * app/cbl/COACTUPC.cbl:L53, {@code WS-EDIT-ALPHANUM-ONLY PIC X(256)} at app/cbl/COACTUPC.cbl:L61,
 * and {@code WS-EDIT-ALPHANUM-LENGTH PIC S9(4) COMP-3} at app/cbl/COACTUPC.cbl:L62. A {@code MOVE}
 * into the 256-byte field pads on the right with spaces, so a value shorter than its declared
 * length arrives padded.</p>
 *
 * <p>The message build at app/cbl/COACTUPC.cbl:L1839-L1844 produces the one message this paragraph
 * can produce, and app/cbl/COACTUPC.cbl:L479 declares the one slot that holds it. Character class,
 * numeric class, and maximum length each belong to another paragraph, and their own test classes
 * cover them.</p>
 *
 * <p>app/cbl/COACTUPC.cbl:L1613-L1614 leave the {@code 'Address Line 2'} label move commented out,
 * so no call site validates address line 2, and app/cbl/COACTUPC.cbl:L1615-L1616 apply the label
 * {@code 'City'} to the field {@code ACUP-NEW-CUST-ADDR-LINE-3}.</p>
 */
@DisplayName("MandatoryFieldValidator, the presence edit of paragraph 1215-EDIT-MANDATORY")
class MandatoryFieldValidatorTest {

    /** Label the one call site moves at app/cbl/COACTUPC.cbl:L1584. */
    private static final String FIELD_LABEL = "Address Line 1";

    /** Declared length the one call site moves at app/cbl/COACTUPC.cbl:L1586. */
    private static final int FIELD_LENGTH = 50;

    /**
     * Literal the message build appends at app/cbl/COACTUPC.cbl:L1841, opening with one space
     * and closing with a period.
     */
    private static final String NOT_SUPPLIED_LITERAL = " must be supplied.";

    /** Width of the literal at app/cbl/COACTUPC.cbl:L1841, counting the space and the period. */
    private static final int LITERAL_WIDTH = 18;

    /** The one message paragraph 1215 produces for the label of app/cbl/COACTUPC.cbl:L1584. */
    private static final String NOT_SUPPLIED_MESSAGE = "Address Line 1 must be supplied.";

    /** Declared width of {@code WS-EDIT-VARIABLE-NAME} at app/cbl/COACTUPC.cbl:L53. */
    private static final int LABEL_WIDTH = 25;

    /** Declared width of {@code WS-EDIT-ALPHANUM-ONLY} at app/cbl/COACTUPC.cbl:L61. */
    private static final int HOST_FIELD_WIDTH = 256;

    /** The label at its {@code PIC X(25)} host width, carrying eleven trailing spaces. */
    private static final String PADDED_LABEL =
            FIELD_LABEL + " ".repeat(LABEL_WIDTH - FIELD_LABEL.length());

    /** A value the figurative constant {@code LOW-VALUES} matches, at the declared length. */
    private static final String LOW_VALUES = "\u0000".repeat(FIELD_LENGTH);

    /** A value the figurative constant {@code SPACES} matches, at the declared length. */
    private static final String SPACES = " ".repeat(FIELD_LENGTH);

    /** A supplied address, shorter than the declared length. */
    private static final String SUPPLIED_VALUE = "123 Main Street";

    @Test
    @DisplayName("A null value, an empty value, and a value of low values all fail")
    void nullEmptyAndLowValuesYieldNotSupplied() {
        // app/cbl/COACTUPC.cbl:L1829-L1830, the LOW-VALUES alternative. A null value and an
        // empty value both reach it.
        assertNotSupplied(MandatoryFieldValidator.validate(FIELD_LABEL, null, FIELD_LENGTH));
        assertNotSupplied(MandatoryFieldValidator.validate(FIELD_LABEL, "", FIELD_LENGTH));
        assertNotSupplied(MandatoryFieldValidator.validate(FIELD_LABEL, LOW_VALUES, FIELD_LENGTH));
    }

    @Test
    @DisplayName("A value of spaces at the declared length fails")
    void allSpacesValueYieldsNotSupplied() {
        // app/cbl/COACTUPC.cbl:L1831-L1832, the SPACES alternative.
        assertNotSupplied(MandatoryFieldValidator.validate(FIELD_LABEL, SPACES, FIELD_LENGTH));
        assertNotSupplied(MandatoryFieldValidator.validate(
                FIELD_LABEL, " ".repeat(HOST_FIELD_WIDTH), HOST_FIELD_WIDTH));
    }

    @Test
    @DisplayName("A value shorter than the declared length that trims to nothing fails")
    void valueTrimmingToNothingYieldsNotSupplied() {
        // app/cbl/COACTUPC.cbl:L1833-L1834, the trimmed-length-zero alternative. The move at
        // app/cbl/COACTUPC.cbl:L1585 pads a one-character value out to the declared length.
        assertNotSupplied(MandatoryFieldValidator.validate(FIELD_LABEL, " ", FIELD_LENGTH));
        assertNotSupplied(MandatoryFieldValidator.validate(FIELD_LABEL, "   ", FIELD_LENGTH));
        assertNotSupplied(MandatoryFieldValidator.validate(FIELD_LABEL, "  ", HOST_FIELD_WIDTH));
    }

    @Test
    @DisplayName("The failure message is the trimmed label followed by the source literal")
    void failureMessageMatchesSourceLiteral() {
        EditResult result = MandatoryFieldValidator.validate(FIELD_LABEL, SPACES, FIELD_LENGTH);

        // app/cbl/COACTUPC.cbl:L1839-L1844 strings the trimmed label and the literal into the
        // slot, each delimited by size, so no separator falls between them.
        assertThat(result.message()).isEqualTo(NOT_SUPPLIED_MESSAGE);
        assertThat(result.message()).isEqualTo(FIELD_LABEL + NOT_SUPPLIED_LITERAL);
        assertThat(result.message()).hasSize(FIELD_LABEL.length() + LITERAL_WIDTH);

        // The characters after the label are the literal of app/cbl/COACTUPC.cbl:L1841, with
        // its leading space and its trailing period.
        String literalPortion = result.message().substring(FIELD_LABEL.length());

        assertThat(literalPortion).isEqualTo(" must be supplied.");
        assertThat(literalPortion).hasSize(LITERAL_WIDTH);
        assertThat(literalPortion).startsWith(" ");
        assertThat(literalPortion).endsWith(".");
    }

    @Test
    @DisplayName("A label padded to its host width loses the padding before the literal")
    void labelIsTrimmedBeforeTheLiteral() {
        // app/cbl/COACTUPC.cbl:L1840 applies FUNCTION TRIM to WS-EDIT-VARIABLE-NAME, which
        // holds the label at the PIC X(25) width of app/cbl/COACTUPC.cbl:L53.
        assertThat(PADDED_LABEL).hasSize(LABEL_WIDTH);

        EditResult trailingSpaces =
                MandatoryFieldValidator.validate(PADDED_LABEL, SPACES, FIELD_LENGTH);

        assertThat(trailingSpaces.message()).isEqualTo(NOT_SUPPLIED_MESSAGE);
        assertThat(trailingSpaces.message()).hasSize(NOT_SUPPLIED_MESSAGE.length());
        assertThat(trailingSpaces.message()).doesNotContain("  ");

        // The FUNCTION TRIM of app/cbl/COACTUPC.cbl:L1840 removes leading spaces as well.
        EditResult leadingSpaces =
                MandatoryFieldValidator.validate("   " + FIELD_LABEL, SPACES, FIELD_LENGTH);

        assertThat(leadingSpaces.message()).isEqualTo(NOT_SUPPLIED_MESSAGE);
        assertThat(leadingSpaces.message()).doesNotStartWith(" ");
    }

    @Test
    @DisplayName("A supplied value passes and leaves the message slot empty")
    void suppliedValuePasses() {
        // app/cbl/COACTUPC.cbl:L1850 sets FLG-MANDATORY-ISVALID once all three alternatives of
        // app/cbl/COACTUPC.cbl:L1829-L1834 test false.
        EditResult result =
                MandatoryFieldValidator.validate(FIELD_LABEL, SUPPLIED_VALUE, FIELD_LENGTH);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
    }

    @Test
    @DisplayName("A short value that holds a character passes once padding fills the length")
    void shortValuePassesAfterSpacePadding() {
        // The move at app/cbl/COACTUPC.cbl:L1585 pads on the right with spaces, and the test at
        // app/cbl/COACTUPC.cbl:L1829-L1834 rejects emptiness alone.
        assertThat(MandatoryFieldValidator.validate(FIELD_LABEL, "A", FIELD_LENGTH).valid())
                .isTrue();
        assertThat(MandatoryFieldValidator.validate(FIELD_LABEL, "7", HOST_FIELD_WIDTH).valid())
                .isTrue();
        assertThat(MandatoryFieldValidator.validate(FIELD_LABEL, " A ", FIELD_LENGTH).valid())
                .isTrue();
        assertThat(MandatoryFieldValidator.validate(FIELD_LABEL, "A", FIELD_LENGTH).message())
                .isNull();
    }

    @Test
    @DisplayName("The presence test reads the declared length only and ignores what follows")
    void referenceModificationLimitsTestToDeclaredLength() {
        // app/cbl/COACTUPC.cbl:L1829 tests WS-EDIT-ALPHANUM-ONLY(1:WS-EDIT-ALPHANUM-LENGTH),
        // so characters past the declared length take no part in the verdict.
        String blankThenText = SPACES + "Second Street";
        String textThenBlank = SUPPLIED_VALUE + " ".repeat(HOST_FIELD_WIDTH);

        assertNotSupplied(
                MandatoryFieldValidator.validate(FIELD_LABEL, blankThenText, FIELD_LENGTH));
        assertThat(MandatoryFieldValidator.validate(FIELD_LABEL, textThenBlank, FIELD_LENGTH)
                .valid()).isTrue();
    }

    @Test
    @DisplayName("A failing verdict carries exactly one message")
    void failureCarriesExactlyOneMessage() {
        // app/cbl/COACTUPC.cbl:L1839-L1844 is the one message build in the paragraph, and
        // app/cbl/COACTUPC.cbl:L479 declares the one slot WS-RETURN-MSG that receives it.
        EditResult result = MandatoryFieldValidator.validate(FIELD_LABEL, SPACES, FIELD_LENGTH);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).containsOnlyOnce(FIELD_LABEL);
        assertThat(result.message()).containsOnlyOnce(NOT_SUPPLIED_LITERAL);
        assertThat(result.message()).isEqualTo(NOT_SUPPLIED_MESSAGE);
    }

    @Test
    @DisplayName("A declared length of zero or below fails and carries the same message")
    void nonPositiveLengthYieldsNotSupplied() {
        // The subject guards the reference modification of app/cbl/COACTUPC.cbl:L1829 with a
        // length test. Every call site moves a positive length, as app/cbl/COACTUPC.cbl:L1586
        // does with 50.
        assertNotSupplied(MandatoryFieldValidator.validate(FIELD_LABEL, SUPPLIED_VALUE, 0));
        assertNotSupplied(MandatoryFieldValidator.validate(FIELD_LABEL, SUPPLIED_VALUE, -1));
    }

    @Test
    @DisplayName("The subject exposes one static three-argument method on a final class")
    void subjectExposesOneStaticThreeArgumentMethod() throws NoSuchMethodException {
        // app/cbl/COACTUPC.cbl:L1584-L1586 moves a label, then a value, then a length, and the
        // parameter order follows those three moves. A reordered signature, an added overload,
        // or a visible constructor fails one of these assertions.
        assertThat(Modifier.isFinal(MandatoryFieldValidator.class.getModifiers())).isTrue();
        assertThat(MandatoryFieldValidator.class.getDeclaredMethods())
                .filteredOn(method -> Modifier.isPublic(method.getModifiers()))
                .hasSize(1);

        Method validate = MandatoryFieldValidator.class
                .getMethod("validate", String.class, String.class, int.class);

        assertThat(Modifier.isStatic(validate.getModifiers())).isTrue();
        assertThat(validate.getReturnType()).isEqualTo(EditResult.class);
        assertThat(validate.getParameters()[0].getName()).isEqualTo("fieldLabel");
        assertThat(validate.getParameters()[1].getName()).isEqualTo("value");
        assertThat(validate.getParameters()[2].getName()).isEqualTo("length");

        Constructor<?>[] constructors = MandatoryFieldValidator.class.getDeclaredConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(Modifier.isPrivate(constructors[0].getModifiers())).isTrue();
    }

    /**
     * Asserts the failing verdict paragraph 1215 sets at app/cbl/COACTUPC.cbl:L1836-L1837 and
     * the one message it builds at app/cbl/COACTUPC.cbl:L1839-L1844.
     *
     * @param result the verdict under test
     */
    private static void assertNotSupplied(EditResult result) {
        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(NOT_SUPPLIED_MESSAGE);
        assertThat(result.message()).containsOnlyOnce(NOT_SUPPLIED_LITERAL);
    }
}
