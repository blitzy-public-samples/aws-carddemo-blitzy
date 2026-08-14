package com.carddemo.account.domain.validation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DeclaredWidthValidator}, the width gate a submitted value passes before any edit.
 *
 * <p>The subject has no COBOL ancestor, and what it stands in for is the map area itself. Every
 * field it guards was read from a fixed-width area of {@code app/bms/COACTUP.bms}, so a wider value
 * could not be keyed; a Representational State Transfer body carries one freely. The tests below
 * therefore assert two properties rather than a source paragraph: the wording matches the width
 * message the three edits of this package already write, and a trailing space past the width is
 * padding rather than content, which is how {@link NumericRequiredValidator} reads the same
 * position.</p>
 *
 * <p>The message must name the field and the width and no character of the value, because the
 * fields this gate guards include a government identifier, the three parts of a Social Security
 * Number and five monetary figures.</p>
 */
@DisplayName("DeclaredWidthValidator, the width a field can hold")
class DeclaredWidthValidatorTest {

    /** Width of {@code WS-EDIT-SIGNED-NUMBER-9V2-X} at {@code app/cbl/COACTUPC.cbl:L55}. */
    private static final int MONEY_WIDTH = 15;

    /** The label the five monetary call sites carry the longest of. */
    private static final String LABEL = "Current Balance";

    @Nested
    @DisplayName("A value the field can hold")
    class ValuesTheFieldHolds {

        @Test
        @DisplayName("passes and carries no message")
        void aValueInsideTheWidthPasses() {
            EditResult verdict = DeclaredWidthValidator.validate(LABEL, "-1234567890.12",
                    MONEY_WIDTH);

            assertThat(verdict.valid()).as("fourteen characters fit a fifteen-character field")
                    .isTrue();
            assertThat(verdict.hasMessage()).as("a passing verdict carries nothing").isFalse();
        }

        @Test
        @DisplayName("passes at exactly the declared width")
        void aValueAtTheWidthPasses() {
            String atWidth = "$12,345,678.90";

            assertThat(atWidth.length()).isLessThanOrEqualTo(MONEY_WIDTH);
            assertThat(DeclaredWidthValidator.validate(LABEL, "1".repeat(MONEY_WIDTH), MONEY_WIDTH)
                    .valid()).as("fifteen characters fit a fifteen-character field").isTrue();
        }

        @Test
        @DisplayName("passes when every character past the width is a space, because that is padding")
        void trailingSpacesPastTheWidthArePadding() {
            EditResult verdict = DeclaredWidthValidator.validate(LABEL,
                    "492.00" + " ".repeat(20), MONEY_WIDTH);

            assertThat(verdict.valid())
                    .as("the source moves a whole fixed-width field into its edit area, so the "
                            + "padding it carries is dropped by the move and inspected by nothing")
                    .isTrue();
        }

        @ParameterizedTest(name = "an absent value passes: [{0}]")
        @ValueSource(strings = {"", " ", "\u0000"})
        @DisplayName("passes for a value that carries nothing")
        void anAbsentValuePasses(String absent) {
            assertThat(DeclaredWidthValidator.validate(LABEL, absent, MONEY_WIDTH).valid())
                    .as("presence is another edit's question")
                    .isTrue();
        }

        @Test
        @DisplayName("passes for null, which is the component a body omitted")
        void nullPasses() {
            assertThat(DeclaredWidthValidator.validate(LABEL, null, MONEY_WIDTH).valid()).isTrue();
        }

        @Test
        @DisplayName("passes for any value when the width declares no field")
        void anUndeclaredWidthInspectsNothing() {
            assertThat(DeclaredWidthValidator.validate(LABEL, "x".repeat(64), 0).valid())
                    .as("a width of zero declares no field, so it refuses nothing")
                    .isTrue();
            assertThat(DeclaredWidthValidator.validate(LABEL, "x".repeat(64), -1).valid()).isTrue();
        }
    }

    @Nested
    @DisplayName("A value wider than the field")
    class ValuesWiderThanTheField {

        @Test
        @DisplayName("fails and names the field and the width")
        void aValuePastTheWidthFails() {
            EditResult verdict = DeclaredWidthValidator.validate(LABEL, "12345678901234.56",
                    MONEY_WIDTH);

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.message())
                    .as("the wording NumericRequiredValidator, AlphabeticRequiredValidator and "
                            + "AlphanumericRequiredValidator already write for the same failure")
                    .isEqualTo("Current Balance must be no longer than 15 characters.");
        }

        @Test
        @DisplayName("fails on one character past the width")
        void oneCharacterPastTheWidthFails() {
            assertThat(DeclaredWidthValidator.validate(LABEL, "1".repeat(MONEY_WIDTH + 1),
                    MONEY_WIDTH).valid())
                    .as("the field holds fifteen characters and the value carries sixteen")
                    .isFalse();
        }

        @Test
        @DisplayName("fails when content sits past the width behind padding")
        void contentBehindPaddingPastTheWidthFails() {
            EditResult verdict = DeclaredWidthValidator.validate(LABEL,
                    "492.00" + " ".repeat(12) + "9", MONEY_WIDTH);

            assertThat(verdict.valid())
                    .as("a character other than a space past the width is content the field cannot "
                            + "hold, wherever it sits")
                    .isFalse();
        }

        @Test
        @DisplayName("names the field and the width and no character of the value")
        void theMessageCarriesNoValue() {
            String secret = "999999999999999999999";
            EditResult verdict =
                    DeclaredWidthValidator.validate("Government Issued Id Ref", secret, 20);

            assertThat(verdict.message())
                    .isEqualTo("Government Issued Id Ref must be no longer than 20 characters.")
                    .doesNotContain(secret);
        }

        @ParameterizedTest(name = "{0} at width {1} reads: {2}")
        @CsvSource({
            "Open Date,8,Open Date must be no longer than 8 characters.",
            "Account Group,10,Account Group must be no longer than 10 characters.",
            "Phone Number 1,15,Phone Number 1 must be no longer than 15 characters.",
            "SSN: First 3 chars,3,SSN: First 3 chars must be no longer than 3 characters.",
            "Address Line 2,50,Address Line 2 must be no longer than 50 characters."})
        @DisplayName("composes one sentence per field, whatever the label and the width")
        void theMessageNamesEveryFieldTheGateGuards(String label, int width, String expected) {
            EditResult verdict =
                    DeclaredWidthValidator.validate(label, "x".repeat(width + 1), width);

            assertThat(verdict.message()).isEqualTo(expected);
        }

        @Test
        @DisplayName("trims the label as the source trims WS-EDIT-VARIABLE-NAME")
        void theLabelIsTrimmed() {
            EditResult verdict = DeclaredWidthValidator.validate("  Reissue Date          ",
                    "202303090", 8);

            assertThat(verdict.message())
                    .isEqualTo("Reissue Date must be no longer than 8 characters.");
        }

        @Test
        @DisplayName("reads an absent label as an empty one rather than failing")
        void anAbsentLabelReadsEmpty() {
            assertThat(DeclaredWidthValidator.validate(null, "123456789", 8).message())
                    .isEqualTo(" must be no longer than 8 characters.");
        }
    }

    @Test
    @DisplayName("the class holds static members alone, as every other edit of this package does")
    void theClassHoldsStaticMembersAlone() {
        assertThat(Modifier.isFinal(DeclaredWidthValidator.class.getModifiers()))
                .as("no edit of this package is extended").isTrue();

        Constructor<?>[] constructors = DeclaredWidthValidator.class.getDeclaredConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(Modifier.isPrivate(constructors[0].getModifiers()))
                .as("no instance of an edit exists").isTrue();
    }
}
