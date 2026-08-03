package com.carddemo.account.domain.validation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.carddemo.cobol.reference.UsStateZipPrefixes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for {@link UsStateZipPrefixValidator}, the one cross-field edit in this package.
 *
 * <p>The subject realises {@code 1280-EDIT-US-STATE-ZIP-CD} at app/cbl/COACTUPC.cbl:L2536-L2557.
 * app/cbl/COACTUPC.cbl:L2537-L2540 builds a four-character key from the two-character state code
 * and {@code ACUP-NEW-CUST-ADDR-ZIP(1:2)}. Only the first two characters of the zip code reach the
 * key. app/cbl/COACTUPC.cbl:L2542 runs one test over the key, and app/cbl/COACTUPC.cbl:L2550
 * carries the one message a failure produces. That message is unprefixed: it holds no field name
 * and no trailing period.</p>
 *
 * <p>The accepted keys are the 240 values listed under {@code VALID-US-STATE-ZIP-CD2-COMBO} at
 * app/cpy/CSLKPCDY.cpy:L1073-L1313. The sibling item {@code 02 LAST-3-OF-ZIP PIC X(3)} at
 * app/cpy/CSLKPCDY.cpy:L1314 carries no value list, so the zip characters past the second reach no
 * test at all. The methods below pin both boundaries: the key width of four, and the silence of the
 * trailing three.</p>
 *
 * <p>One failure marks two fields in the source. app/cbl/COACTUPC.cbl:L2546 sets
 * {@code FLG-STATE-NOT-OK} and app/cbl/COACTUPC.cbl:L2547 sets {@code FLG-ZIPCODE-NOT-OK} on the
 * same branch. The subject answers one call with one {@link EditResult}, so the pair of source
 * flags surfaces as a single failing verdict. No method below asserts a flag.</p>
 *
 * <p>app/cbl/COACTUPC.cbl:L1665-L1666 gates the single call site at app/cbl/COACTUPC.cbl:L1667 on
 * the state code and the zip code each having passed its own edit. That gate is orchestration and
 * sits outside this class. Every input below is a literal written into this file, so the suite
 * needs no container, no database, and no substitute for {@link UsStateZipPrefixes}.</p>
 */
@DisplayName("US state and zip prefix combination edit, COBOL paragraph 1280")
class UsStateZipPrefixValidatorTest {

    /** The message at app/cbl/COACTUPC.cbl:L2550, written out here character for character. */
    private static final String EXPECTED_MESSAGE = "Invalid zip code for state";

    /** Character count of the message at app/cbl/COACTUPC.cbl:L2550. */
    private static final int EXPECTED_MESSAGE_LENGTH = 26;

    /** Declared width of {@code US-STATE-AND-FIRST-ZIP2} at app/cpy/CSLKPCDY.cpy:L1072. */
    private static final int KEY_WIDTH = 4;

    /** Width of the reference {@code ACUP-NEW-CUST-ADDR-ZIP(1:2)} at app/cbl/COACTUPC.cbl:L2538. */
    private static final int ZIP_PREFIX_WIDTH = 2;

    /** Count of the values listed at app/cpy/CSLKPCDY.cpy:L1074-L1313. */
    private static final int BAND_SIZE = 240;

    /** First value listed at app/cpy/CSLKPCDY.cpy:L1074, and the lowest in sorted order. */
    private static final String FIRST_LISTED_KEY = "AA34";

    /** Last value listed at app/cpy/CSLKPCDY.cpy:L1313, and the highest in sorted order. */
    private static final String LAST_LISTED_KEY = "WY83";

    /** State code of {@link #FIRST_LISTED_KEY}. */
    private static final String FIRST_LISTED_STATE_CODE = "AA";

    /** State code of {@link #LAST_LISTED_KEY}. */
    private static final String LAST_LISTED_STATE_CODE = "WY";

    /**
     * State code carried by record 1 of app/data/ASCII/custdata.txt at offsets 235 to 236. The two
     * keys listed for the code at app/cpy/CSLKPCDY.cpy:L1074-L1313 are {@code NC27} and
     * {@code NC28}.
     */
    private static final String SEEDED_STATE_CODE = "NC";

    /** Zip code carried by record 1 of app/data/ASCII/custdata.txt from offset 240. */
    private static final String SEEDED_ZIP_CODE = "12546";

    /** First two characters of {@link #SEEDED_ZIP_CODE}, the part the key takes. */
    private static final String SEEDED_ZIP_PREFIX = "12";

    /** Key formed by {@link #SEEDED_STATE_CODE} with {@link #SEEDED_ZIP_CODE}. */
    private static final String SEEDED_COMBINATION_KEY = "NC12";

    /** Zip code sharing the prefix of {@code NC27}, listed at app/cpy/CSLKPCDY.cpy. */
    private static final String NC_ACCEPTED_ZIP_CODE = "27601";

    /** First two characters of {@link #NC_ACCEPTED_ZIP_CODE}. */
    private static final String NC_ACCEPTED_PREFIX = "27";

    /** Key formed by {@link #SEEDED_STATE_CODE} with {@link #NC_ACCEPTED_ZIP_CODE}. */
    private static final String NC_ACCEPTED_KEY = "NC27";

    /** The second key listed for {@link #SEEDED_STATE_CODE} at app/cpy/CSLKPCDY.cpy:L1074-L1313. */
    private static final String NC_SECOND_ACCEPTED_KEY = "NC28";

    /**
     * State code holding key {@code NY12}, which pairs with {@link #SEEDED_ZIP_CODE}. Prefix
     * {@code 12} is listed for {@code MA} and for {@code NY} at
     * app/cpy/CSLKPCDY.cpy:L1074-L1313, and for no other state code.
     */
    private static final String STATE_CODE_ACCEPTING_SEEDED_ZIP = "NY";

    /**
     * Zip code whose prefix {@code 83} is listed for {@code ID}, {@code NJ}, {@code VI} and
     * {@link #LAST_LISTED_STATE_CODE} at app/cpy/CSLKPCDY.cpy:L1074-L1313, and for no other state
     * code.
     */
    private static final String OTHER_STATE_ZIP_CODE = "83001";

    /**
     * Zip code whose prefix {@code 34} is listed for {@link #FIRST_LISTED_STATE_CODE}, {@code FL}
     * and {@code NH} at app/cpy/CSLKPCDY.cpy:L1074-L1313, and for no other state code.
     */
    private static final String FIRST_STATE_ZIP_CODE = "34001";

    /**
     * Zip code at the declared width of {@code ACUP-NEW-CUST-ADDR-ZIP PIC X(10)} at
     * app/cbl/COACTUPC.cbl:L809, sharing only its first two characters with
     * {@link #NC_ACCEPTED_ZIP_CODE}.
     */
    private static final String LONG_NC_ACCEPTED_ZIP_CODE = "2799999999";

    /**
     * Zip code at the declared width of app/cbl/COACTUPC.cbl:L809, sharing only its first two
     * characters with {@link #SEEDED_ZIP_CODE}.
     */
    private static final String LONG_SEEDED_ZIP_CODE = "1200000000";

    /**
     * Width of the five-character zip codes above: the two characters the key takes, plus the three
     * of {@code LAST-3-OF-ZIP} at app/cpy/CSLKPCDY.cpy:L1314.
     */
    private static final int SHORT_ZIP_WIDTH = 5;

    /** Declared width of {@code ACUP-NEW-CUST-ADDR-ZIP} at app/cbl/COACTUPC.cbl:L809. */
    private static final int SOURCE_ZIP_WIDTH = 10;

    /** Count of two-digit prefixes, from {@code 00} through {@code 99}. */
    private static final int PREFIX_VALUE_COUNT = 100;

    /** Width of the state code inside one combination key. */
    private static final int STATE_CODE_WIDTH = 2;

    /**
     * Width of {@code WS-US-STATE-ZIP-CD2-COMBO} at app/cpy/CSLKPCDY.cpy:L1071: two characters of
     * state code and two of postal prefix.
     */
    private static final int COMBINATION_KEY_WIDTH = 4;

    /** Count of two-letter state codes, from {@code AA} through {@code ZZ}. */
    private static final int STATE_CODE_COMBINATION_COUNT = 676;

    /** Count of keys the sweep visits: every state code paired with every prefix. */
    private static final int SWEPT_KEY_COUNT = STATE_CODE_COMBINATION_COUNT * PREFIX_VALUE_COUNT;

    /**
     * Count of values of {@code LAST-3-OF-ZIP} at app/cpy/CSLKPCDY.cpy:L1314, from {@code 000}
     * through {@code 999}.
     */
    private static final int TRAILING_THREE_VALUE_COUNT = 1000;

    /** Value the sweep supplies for {@code LAST-3-OF-ZIP} at app/cpy/CSLKPCDY.cpy:L1314. */
    private static final String TRAILING_THREE_FILLER = "000";

    /**
     * Separator a prefixed message carries. app/cbl/COACTUPC.cbl:L2522 supplies the field name and
     * app/cbl/COACTUPC.cbl:L2523 opens with the separator. The message at
     * app/cbl/COACTUPC.cbl:L2550 has neither.
     */
    private static final String PREFIX_SEPARATOR = ":";

    /**
     * Terminator the message at app/cbl/COACTUPC.cbl:L1841 carries. The message at
     * app/cbl/COACTUPC.cbl:L2550 ends without one.
     */
    private static final String SENTENCE_TERMINATOR = ".";

    @ParameterizedTest(name = "state {0} with zip {1} forms key {2} and passes")
    @CsvSource({
        "AA, 34001, AA34",
        "NC, 27601, NC27",
        "NC, 28001, NC28",
        "WY, 83001, WY83",
        "TX, 75201, TX75",
        "CA, 90210, CA90",
        "NY, 12546, NY12",
        "MA, 12345, MA12",
        "FL, 34101, FL34",
        "ID, 83201, ID83"
    })
    @DisplayName("A combination listed at CSLKPCDY.cpy L1074 to L1313 passes and carries no message")
    void listedCombinationPasses(String stateCode, String zipCode, String expectedKey) {
        EditResult result = UsStateZipPrefixValidator.validate(stateCode, zipCode);

        assertThat(UsStateZipPrefixValidator.combinationKey(stateCode, zipCode)).isEqualTo(expectedKey);
        assertThat(UsStateZipPrefixes.isValidUsStateZipCd2Combo(expectedKey)).isTrue();
        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
    }

    @ParameterizedTest(name = "state {0} with zip {1} forms key {2} and fails")
    @CsvSource({
        "NC, 12546, NC12",
        "NC, 83001, NC83",
        "NC, 34001, NC34",
        "NC, 99999, NC99",
        "MA, 02101, MA02",
        "NY, 27601, NY27",
        "WY, 27601, WY27",
        "AA, 12546, AA12",
        "ZZ, 99999, ZZ99"
    })
    @DisplayName("A combination absent from the listed values fails with the message at L2550")
    void unlistedCombinationFails(String stateCode, String zipCode, String expectedKey) {
        EditResult result = UsStateZipPrefixValidator.validate(stateCode, zipCode);

        assertThat(UsStateZipPrefixValidator.combinationKey(stateCode, zipCode)).isEqualTo(expectedKey);
        assertThat(UsStateZipPrefixes.isValidUsStateZipCd2Combo(expectedKey)).isFalse();
        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(EXPECTED_MESSAGE);
    }

    @Test
    @DisplayName("The failure message carries no field name and no trailing period")
    void failureMessageCarriesNoFieldNameAndNoTrailingPeriod() {
        EditResult result = UsStateZipPrefixValidator.validate(SEEDED_STATE_CODE, SEEDED_ZIP_CODE);

        assertThat(result.message())
                .isEqualTo(EXPECTED_MESSAGE)
                .hasSize(EXPECTED_MESSAGE_LENGTH)
                .startsWith("Invalid")
                .endsWith("state")
                .doesNotContain(PREFIX_SEPARATOR)
                .doesNotEndWith(SENTENCE_TERMINATOR)
                .isEqualTo(result.message().strip());
        assertThat(UsStateZipPrefixValidator.INVALID_ZIP_FOR_STATE_MESSAGE).isEqualTo(EXPECTED_MESSAGE);
    }

    @Test
    @DisplayName("The pairing decides the verdict, and neither field decides it alone")
    void pairingDecidesTheVerdict() {
        EditResult seededPairing =
                UsStateZipPrefixValidator.validate(SEEDED_STATE_CODE, SEEDED_ZIP_CODE);
        EditResult sameStateWithListedZip =
                UsStateZipPrefixValidator.validate(SEEDED_STATE_CODE, NC_ACCEPTED_ZIP_CODE);
        EditResult sameZipWithListedState =
                UsStateZipPrefixValidator.validate(STATE_CODE_ACCEPTING_SEEDED_ZIP, SEEDED_ZIP_CODE);

        assertThat(seededPairing.valid())
                .as("state %s with zip %s forms key %s", SEEDED_STATE_CODE, SEEDED_ZIP_CODE,
                        SEEDED_COMBINATION_KEY)
                .isFalse();
        assertThat(seededPairing.message()).isEqualTo(EXPECTED_MESSAGE);

        assertThat(sameStateWithListedZip.valid())
                .as("the same state code reaches key %s", NC_ACCEPTED_KEY)
                .isTrue();
        assertThat(sameZipWithListedState.valid())
                .as("the same zip code reaches key %s%s", STATE_CODE_ACCEPTING_SEEDED_ZIP,
                        SEEDED_ZIP_PREFIX)
                .isTrue();
    }

    @Test
    @DisplayName("Two zip codes sharing their first two characters give the same verdict")
    void zipCodesSharingTheFirstTwoCharactersGiveTheSameVerdict() {
        EditResult shortListedZip =
                UsStateZipPrefixValidator.validate(SEEDED_STATE_CODE, NC_ACCEPTED_ZIP_CODE);
        EditResult longListedZip =
                UsStateZipPrefixValidator.validate(SEEDED_STATE_CODE, LONG_NC_ACCEPTED_ZIP_CODE);
        EditResult shortUnlistedZip =
                UsStateZipPrefixValidator.validate(SEEDED_STATE_CODE, SEEDED_ZIP_CODE);
        EditResult longUnlistedZip =
                UsStateZipPrefixValidator.validate(SEEDED_STATE_CODE, LONG_SEEDED_ZIP_CODE);

        assertThat(NC_ACCEPTED_ZIP_CODE).hasSize(SHORT_ZIP_WIDTH);
        assertThat(LONG_NC_ACCEPTED_ZIP_CODE).hasSize(SOURCE_ZIP_WIDTH);
        assertThat(NC_ACCEPTED_ZIP_CODE.substring(0, ZIP_PREFIX_WIDTH))
                .isEqualTo(LONG_NC_ACCEPTED_ZIP_CODE.substring(0, ZIP_PREFIX_WIDTH));
        assertThat(NC_ACCEPTED_ZIP_CODE.substring(ZIP_PREFIX_WIDTH))
                .isNotEqualTo(LONG_NC_ACCEPTED_ZIP_CODE.substring(ZIP_PREFIX_WIDTH));

        assertThat(longListedZip).isEqualTo(shortListedZip);
        assertThat(shortListedZip.valid()).isTrue();
        assertThat(longListedZip.valid()).isTrue();

        assertThat(longUnlistedZip).isEqualTo(shortUnlistedZip);
        assertThat(shortUnlistedZip.valid()).isFalse();
        assertThat(longUnlistedZip.message()).isEqualTo(EXPECTED_MESSAGE);

        assertThat(UsStateZipPrefixValidator.validate(SEEDED_STATE_CODE, NC_ACCEPTED_PREFIX).valid())
                .as("a zip code of only the two characters the key takes")
                .isTrue();
        assertThat(UsStateZipPrefixValidator.validate(SEEDED_STATE_CODE, SEEDED_ZIP_PREFIX).valid())
                .isFalse();
    }

    @Test
    @DisplayName("The key takes two characters from the state code and two from the zip code")
    void combinationKeyTakesTwoCharactersFromEachOperand() {
        assertThat(UsStateZipPrefixValidator.combinationKey(SEEDED_STATE_CODE, SEEDED_ZIP_CODE))
                .isEqualTo(SEEDED_COMBINATION_KEY)
                .hasSize(KEY_WIDTH);
        assertThat(UsStateZipPrefixValidator.combinationKey(LAST_LISTED_STATE_CODE, OTHER_STATE_ZIP_CODE))
                .isEqualTo(LAST_LISTED_KEY);
        assertThat(UsStateZipPrefixValidator.combinationKey(FIRST_LISTED_STATE_CODE, FIRST_STATE_ZIP_CODE))
                .isEqualTo(FIRST_LISTED_KEY);
        assertThat(UsStateZipPrefixValidator.combinationKey(SEEDED_STATE_CODE, NC_ACCEPTED_ZIP_CODE))
                .isEqualTo(NC_ACCEPTED_KEY)
                .isEqualTo(UsStateZipPrefixValidator.combinationKey(SEEDED_STATE_CODE,
                        LONG_NC_ACCEPTED_ZIP_CODE));
        assertThat(UsStateZipPrefixValidator.combinationKey(null, null))
                .hasSize(KEY_WIDTH)
                .isBlank();
    }

    @Test
    @DisplayName("A prefix listed for another state fails with the seeded state code")
    void prefixListedForAnotherStateFails() {
        assertThat(UsStateZipPrefixValidator.validate(SEEDED_STATE_CODE, OTHER_STATE_ZIP_CODE).valid())
                .as("state %s with the prefix of key %s", SEEDED_STATE_CODE, LAST_LISTED_KEY)
                .isFalse();
        assertThat(UsStateZipPrefixValidator.validate(LAST_LISTED_STATE_CODE, OTHER_STATE_ZIP_CODE).valid())
                .as("the state code that key %s lists", LAST_LISTED_KEY)
                .isTrue();

        assertThat(UsStateZipPrefixValidator.validate(SEEDED_STATE_CODE, FIRST_STATE_ZIP_CODE).valid())
                .as("state %s with the prefix of key %s", SEEDED_STATE_CODE, FIRST_LISTED_KEY)
                .isFalse();
        assertThat(UsStateZipPrefixValidator.validate(FIRST_LISTED_STATE_CODE, FIRST_STATE_ZIP_CODE).valid())
                .as("the state code that key %s lists", FIRST_LISTED_KEY)
                .isTrue();
    }

    @Test
    @DisplayName("The last three characters of the zip code reach no test")
    void lastThreeZipCharactersReachNoTest() {
        for (int trailing = 0; trailing < TRAILING_THREE_VALUE_COUNT; trailing++) {
            String trailingThree = "%03d".formatted(trailing);

            assertThat(UsStateZipPrefixValidator
                    .validate(SEEDED_STATE_CODE, NC_ACCEPTED_PREFIX + trailingThree).valid())
                    .as("key %s with trailing %s", NC_ACCEPTED_KEY, trailingThree)
                    .isTrue();
            assertThat(UsStateZipPrefixValidator
                    .validate(SEEDED_STATE_CODE, SEEDED_ZIP_PREFIX + trailingThree).valid())
                    .as("key %s with trailing %s", SEEDED_COMBINATION_KEY, trailingThree)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("One message is reachable, across every state code paired with every zip prefix")
    void oneMessageIsReachableAcrossEveryKey() {
        SweepOutcome outcome = sweepEveryStateCodeAndPrefix();

        assertThat(outcome.messages()).containsExactly(EXPECTED_MESSAGE);
        assertThat(outcome.failureCount()).isEqualTo(SWEPT_KEY_COUNT - BAND_SIZE);
        assertThat(outcome.passCount()).isEqualTo(BAND_SIZE);
    }

    @Test
    @DisplayName("The sweep passes exactly the combinations listed at CSLKPCDY.cpy L1074 to L1313")
    void sweepPassesExactlyTheListedCombinations() {
        SweepOutcome outcome = sweepEveryStateCodeAndPrefix();

        assertThat(outcome.passedKeys())
                .hasSize(BAND_SIZE)
                .containsExactlyInAnyOrderElementsOf(CslkpcdyCopybookOracle.stateZipCombinations());
    }

    @Test
    @DisplayName("The production band equals the band app/cpy/CSLKPCDY.cpy:L1073 lists, value for "
            + "value, and every listed combination is four characters wide")
    void theProductionBandEqualsTheBandTheCopybookLists() {
        // UsStateZipPrefixes publishes the band as an unordered set and claims no order, so the
        // comparison is by membership.
        assertThat(UsStateZipPrefixes.validCombinations())
                .as("VALID-US-STATE-ZIP-CD2-COMBO at app/cpy/CSLKPCDY.cpy:L1073")
                .containsExactlyInAnyOrderElementsOf(
                        CslkpcdyCopybookOracle.stateZipCombinations());

        assertThat(CslkpcdyCopybookOracle.stateZipCombinations()).hasSize(BAND_SIZE);
        for (String combination : CslkpcdyCopybookOracle.stateZipCombinations()) {
            assertThat(combination)
                    .as("copybook literal %s", combination)
                    .hasSize(COMBINATION_KEY_WIDTH);
        }
    }

    @Test
    @DisplayName("Every combination app/cpy/CSLKPCDY.cpy:L1073 lists passes the edit, with the "
            + "copybook supplying the set")
    void theCopybookDecidesWhichCombinationsPassTheEdit() {
        for (String combination : CslkpcdyCopybookOracle.stateZipCombinations()) {
            String stateCode = combination.substring(0, STATE_CODE_WIDTH);
            String zipCode = combination.substring(STATE_CODE_WIDTH) + TRAILING_THREE_FILLER;

            assertThat(UsStateZipPrefixValidator.validate(stateCode, zipCode).valid())
                    .as("VALID-US-STATE-ZIP-CD2-COMBO lists %s", combination)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("The listed band holds 240 combinations, running from AA34 to WY83")
    void listedBandHoldsTheCountedCombinations() {
        Set<String> listed = UsStateZipPrefixes.validCombinations();
        TreeSet<String> sorted = new TreeSet<>(listed);

        assertThat(listed).hasSize(BAND_SIZE);
        assertThat(sorted.first()).isEqualTo(FIRST_LISTED_KEY);
        assertThat(sorted.last()).isEqualTo(LAST_LISTED_KEY);
        assertThat(listed)
                .contains(NC_ACCEPTED_KEY, NC_SECOND_ACCEPTED_KEY)
                .doesNotContain(SEEDED_COMBINATION_KEY);
        assertThat(listed.stream().filter(key -> key.startsWith(SEEDED_STATE_CODE)).toList())
                .as("keys listed for state %s", SEEDED_STATE_CODE)
                .containsExactlyInAnyOrder(NC_ACCEPTED_KEY, NC_SECOND_ACCEPTED_KEY);
    }

    @Test
    @DisplayName("A null, empty or short input fails and throws nothing")
    void nullEmptyAndShortInputsFailWithoutThrowing() {
        assertThatCode(() -> UsStateZipPrefixValidator.validate(null, null))
                .doesNotThrowAnyException();

        assertThat(UsStateZipPrefixValidator.validate(null, null).valid()).isFalse();
        assertThat(UsStateZipPrefixValidator.validate(null, null).message()).isEqualTo(EXPECTED_MESSAGE);
        assertThat(UsStateZipPrefixValidator.validate(SEEDED_STATE_CODE, null).valid()).isFalse();
        assertThat(UsStateZipPrefixValidator.validate(null, NC_ACCEPTED_ZIP_CODE).valid()).isFalse();
        assertThat(UsStateZipPrefixValidator.validate("", "").valid()).isFalse();
        assertThat(UsStateZipPrefixValidator.validate(SEEDED_STATE_CODE, "2").valid()).isFalse();
        assertThat(UsStateZipPrefixValidator.validate("N", NC_ACCEPTED_ZIP_CODE).valid()).isFalse();
    }

    @Test
    @DisplayName("The subject is a final class holding one private constructor and one public method")
    void subjectHoldsOnePublicMethodReturningAVerdict() {
        Constructor<?>[] constructors = UsStateZipPrefixValidator.class.getDeclaredConstructors();
        List<Method> publicMethods =
                Arrays.stream(UsStateZipPrefixValidator.class.getDeclaredMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .filter(method -> !method.isSynthetic())
                        .toList();

        assertThat(Modifier.isFinal(UsStateZipPrefixValidator.class.getModifiers())).isTrue();
        assertThat(constructors).hasSize(1);
        assertThat(Modifier.isPrivate(constructors[0].getModifiers())).isTrue();
        assertThat(publicMethods).hasSize(1);
        assertThat(publicMethods.getFirst().getName()).isEqualTo("validate");
        assertThat(publicMethods.getFirst().getReturnType()).isEqualTo(EditResult.class);
    }

    /**
     * Result of one pass over every two-letter state code paired with every two-digit zip prefix.
     *
     * @param passedKeys   the keys that passed, one entry per distinct key
     * @param messages     the distinct messages the failures carried, in first-seen order
     * @param passCount    count of calls that passed
     * @param failureCount count of calls that failed
     */
    private record SweepOutcome(Set<String> passedKeys, Set<String> messages, int passCount,
                                int failureCount) {
    }

    /**
     * Calls the subject once for each of the {@link #SWEPT_KEY_COUNT} keys formed by a two-letter
     * state code and a two-digit prefix. The trailing three characters of every zip code hold
     * {@link #TRAILING_THREE_FILLER}, matching {@code LAST-3-OF-ZIP} at
     * app/cpy/CSLKPCDY.cpy:L1314.
     *
     * @return the keys that passed, the distinct messages, and both counts
     */
    private static SweepOutcome sweepEveryStateCodeAndPrefix() {
        Set<String> passedKeys = new TreeSet<>();
        Set<String> messages = new LinkedHashSet<>();
        int passCount = 0;
        int failureCount = 0;

        for (char first = 'A'; first <= 'Z'; first++) {
            for (char second = 'A'; second <= 'Z'; second++) {
                String stateCode = String.valueOf(new char[] {first, second});
                for (int prefix = 0; prefix < PREFIX_VALUE_COUNT; prefix++) {
                    String zipCode = "%02d".formatted(prefix) + TRAILING_THREE_FILLER;
                    EditResult result = UsStateZipPrefixValidator.validate(stateCode, zipCode);
                    if (result.valid()) {
                        passCount++;
                        passedKeys.add(UsStateZipPrefixValidator.combinationKey(stateCode, zipCode));
                    } else {
                        failureCount++;
                        messages.add(result.message());
                    }
                }
            }
        }

        return new SweepOutcome(passedKeys, messages, passCount, failureCount);
    }
}
