package com.carddemo.account.domain.validation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.carddemo.cobol.reference.UsPhoneAreaCodes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Tests for {@link UsPhoneNumberValidator}, which realises paragraph {@code 1260-EDIT-US-PHONE-NUM}
 * at app/cbl/COACTUPC.cbl:L2225 together with the three paragraphs it falls through to. Those three
 * are {@code EDIT-AREA-CODE} at app/cbl/COACTUPC.cbl:L2246, {@code EDIT-US-PHONE-PREFIX} at
 * app/cbl/COACTUPC.cbl:L2316, and {@code EDIT-US-PHONE-LINENUM} at app/cbl/COACTUPC.cbl:L2370. Two
 * call sites reach the paragraph. They sit at app/cbl/COACTUPC.cbl:L1635 under the label
 * {@code 'Phone Number 1'} and at app/cbl/COACTUPC.cbl:L1643 under the label
 * {@code 'Phone Number 2'}.
 *
 * <p>app/cpy/CSLKPCDY.cpy declares three condition names over one host item,
 * {@code 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX} at app/cpy/CSLKPCDY.cpy:L24.
 * {@code VALID-PHONE-AREA-CODE} at app/cpy/CSLKPCDY.cpy:L30 holds 490 codes,
 * {@code VALID-GENERAL-PURP-CODE} at app/cpy/CSLKPCDY.cpy:L521 holds 410, and
 * {@code VALID-EASY-RECOG-AREA-CODE} at app/cpy/CSLKPCDY.cpy:L931 holds 80. The three bands hold
 * 980 literals over 490 distinct codes. The two smaller bands share no code, and together they
 * cover the wider band exactly.</p>
 *
 * <p>app/cbl/COACTUPC.cbl:L2296 trims the area code and app/cbl/COACTUPC.cbl:L2298 tests
 * {@code VALID-GENERAL-PURP-CODE}. A census over the 28 members of app/cbl finds one reference to
 * that condition name, at app/cbl/COACTUPC.cbl:L2298, and no reference to the other two. The 410
 * code band is therefore the accepted set, and the 80 codes app/cpy/CSLKPCDY.cpy:L521 omits are
 * refused with the message at app/cbl/COACTUPC.cbl:L2306. Binding the wider 490 code band fails
 * this class.</p>
 *
 * <p>The source always edits the area code, prefix, and line number. A failure jumps to the next
 * part, but the 75-character message slot keeps the first message. {@link EditResult} therefore
 * exposes one overall verdict and one message, not per-part flags. The jumps sit at
 * app/cbl/COACTUPC.cbl:L2259, L2277, L2291, L2311, L2330, L2348 and L2362. The message slot is
 * {@code WS-RETURN-MSG PIC X(75)} at app/cbl/COACTUPC.cbl:L479 under the guard
 * {@code WS-RETURN-MSG-OFF} at app/cbl/COACTUPC.cbl:L480.</p>
 *
 * <p>app/cbl/COACTUPC.cbl:L2232 marks the number invalid on entry, and app/cbl/COACTUPC.cbl:L2234
 * to L2239 then joins three clauses with {@code AND}. The third clause tests the area code for
 * spaces at app/cbl/COACTUPC.cbl:L2238 and the line number for low values at
 * app/cbl/COACTUPC.cbl:L2239. A blank area code, a blank prefix and a populated line number reach
 * the escape at app/cbl/COACTUPC.cbl:L2240 to L2241. The escape marks the number valid and jumps to
 * the shared exit at app/cbl/COACTUPC.cbl:L2424.</p>
 *
 * <p>The stored form is described at app/cbl/COACTUPC.cbl:L2227 to L2230, over the host group at
 * app/cbl/COACTUPC.cbl:L82 to L100. That comment names a date where the field carries a phone
 * number, and its ruler runs to thirteen characters under a {@code PIC X(15)} field. The separator
 * bytes at app/cbl/COACTUPC.cbl:L85, L90 and L95 carry no digit, and the class under test takes the
 * three parts at app/cbl/COACTUPC.cbl:L87, L92 and L97 as separate arguments.</p>
 *
 * <p>Every input is a literal written in this class. The tests need no Spring context, no
 * container and no database.</p>
 */
@DisplayName("UsPhoneNumberValidator, the three part North American telephone number edit")
class UsPhoneNumberValidatorTest {

    /** Label the first call site moves at app/cbl/COACTUPC.cbl:L1632, ahead of the call at L1635. */
    private static final String LABEL = "Phone Number 1";

    /** Label the second call site moves at app/cbl/COACTUPC.cbl:L1640, ahead of the call at L1643. */
    private static final String SECOND_LABEL = "Phone Number 2";

    /** Declared width of {@code WS-EDIT-VARIABLE-NAME} at app/cbl/COACTUPC.cbl:L53. */
    private static final int SOURCE_LABEL_WIDTH = 25;

    /** {@link #LABEL} padded with spaces at both ends to {@link #SOURCE_LABEL_WIDTH}. */
    private static final String PADDED_LABEL = "  Phone Number 1         ";

    /** A synthetic area code inside the accepted band {@code VALID-GENERAL-PURP-CODE}. */
    private static final String ACCEPTED_AREA_CODE = "212";

    /** A synthetic prefix, three digits above zero. */
    private static final String ACCEPTED_PREFIX = "555";

    /** A synthetic line number, four digits above zero. */
    private static final String ACCEPTED_LINE_NUMBER = "0100";

    /** A {@code PIC X(3)} area code holding spaces, the state app/cbl/COACTUPC.cbl:L2247 tests. */
    private static final String BLANK_AREA_CODE = "   ";

    /** A {@code PIC X(3)} prefix holding spaces, the state app/cbl/COACTUPC.cbl:L2318 tests. */
    private static final String BLANK_PREFIX = "   ";

    /** A {@code PIC X(4)} line number holding spaces, the state app/cbl/COACTUPC.cbl:L2371 tests. */
    private static final String BLANK_LINE_NUMBER = "    ";

    /** An area code holding three zero digits, the state app/cbl/COACTUPC.cbl:L2280 tests. */
    private static final String ZERO_AREA_CODE = "000";

    /** A prefix holding three zero digits, the state app/cbl/COACTUPC.cbl:L2351 tests. */
    private static final String ZERO_PREFIX = "000";

    /** A line number holding four zero digits, the state app/cbl/COACTUPC.cbl:L2404 tests. */
    private static final String ZERO_LINE_NUMBER = "0000";

    /** An area code carrying a letter, which fails the class test at app/cbl/COACTUPC.cbl:L2264. */
    private static final String NON_NUMERIC_AREA_CODE = "20A";

    /** A prefix carrying a letter, which fails the class test at app/cbl/COACTUPC.cbl:L2335. */
    private static final String NON_NUMERIC_PREFIX = "1A1";

    /** A line number carrying a letter, which fails the class test at app/cbl/COACTUPC.cbl:L2388. */
    private static final String NON_NUMERIC_LINE_NUMBER = "83A0";

    /** Head of the 80 codes app/cpy/CSLKPCDY.cpy:L30 holds and app/cpy/CSLKPCDY.cpy:L521 omits. */
    private static final String REFUSED_HEAD_CODE = "200";

    /** Tail of the 80 codes app/cpy/CSLKPCDY.cpy:L30 holds and app/cpy/CSLKPCDY.cpy:L521 omits. */
    private static final String REFUSED_TAIL_CODE = "999";

    /** Literal at app/cbl/COACTUPC.cbl:L2254, closing with a period. */
    private static final String AREA_CODE_BLANK = ": Area code must be supplied.";

    /** Literal at app/cbl/COACTUPC.cbl:L2272, closing with a period and carrying a capital A. */
    private static final String AREA_CODE_NOT_NUMERIC = ": Area code must be A 3 digit number.";

    /** Literal at app/cbl/COACTUPC.cbl:L2286, closing without a period. */
    private static final String AREA_CODE_ZERO = ": Area code cannot be zero";

    /** Literal at app/cbl/COACTUPC.cbl:L2306, closing without a period. */
    private static final String AREA_CODE_NOT_IN_BAND =
            ": Not valid North America general purpose area code";

    /** Literal at app/cbl/COACTUPC.cbl:L2325, closing with a period. */
    private static final String PREFIX_BLANK = ": Prefix code must be supplied.";

    /** Literal at app/cbl/COACTUPC.cbl:L2343, closing with a period and carrying a capital A. */
    private static final String PREFIX_NOT_NUMERIC = ": Prefix code must be A 3 digit number.";

    /** Literal at app/cbl/COACTUPC.cbl:L2357, closing without a period. */
    private static final String PREFIX_ZERO = ": Prefix code cannot be zero";

    /** Literal at app/cbl/COACTUPC.cbl:L2378, closing with a period. */
    private static final String LINE_NUMBER_BLANK = ": Line number code must be supplied.";

    /** Literal at app/cbl/COACTUPC.cbl:L2396, closing with a period and carrying a capital A. */
    private static final String LINE_NUMBER_NOT_NUMERIC =
            ": Line number code must be A 4 digit number.";

    /** Literal at app/cbl/COACTUPC.cbl:L2410, closing without a period. */
    private static final String LINE_NUMBER_ZERO = ": Line number code cannot be zero";

    /** Code count {@code VALID-GENERAL-PURP-CODE} declares at app/cpy/CSLKPCDY.cpy:L521 to L930. */
    private static final int GENERAL_PURPOSE_BAND_SIZE = 410;

    /** Code count {@code VALID-PHONE-AREA-CODE} declares at app/cpy/CSLKPCDY.cpy:L30 to L520. */
    private static final int PHONE_AREA_BAND_SIZE = 490;

    /** Code count {@code VALID-EASY-RECOG-AREA-CODE} declares at app/cpy/CSLKPCDY.cpy:L931 to L1010. */
    private static final int EASILY_RECOGNISABLE_BAND_SIZE = 80;

    /** Literal count across the three bands of app/cpy/CSLKPCDY.cpy:L30 to L1010. */
    private static final int BAND_LITERAL_COUNT = 980;

    /** Width of {@code WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX} at app/cpy/CSLKPCDY.cpy:L24. */
    private static final int AREA_CODE_WIDTH = 3;

    /** Width of {@code WS-EDIT-US-PHONE-NUMB}, the prefix part of the stored number. */
    private static final int PREFIX_WIDTH = 3;

    /** Width of {@code WS-EDIT-US-PHONE-NUMC}, the line-number part of the stored number. */
    private static final int LINE_NUMBER_WIDTH = 4;

    /**
     * One character of the figurative constant {@code LOW-VALUES}, which a COBOL comparison tests
     * for one position at a time.
     */
    private static final String LOW_VALUE = "\0";

    /** The character a message closes with when app/cbl/COACTUPC.cbl spells out a period. */
    private static final String CLOSING_PERIOD = ".";

    /** Two adjacent spaces, which no message carries once the label is trimmed. */
    private static final String TWO_SPACES = "  ";

    /**
     * The 490 literals app/cpy/CSLKPCDY.cpy:L30 through L520 declare under
     * {@code VALID-PHONE-AREA-CODE}, in copybook declaration order.
     */
    private static final List<String> DECLARED_PHONE_AREA_CODES = List.of(
            "201", "202", "203", "204", "205", "206", "207", "208", "209", "210",
            "212", "213", "214", "215", "216", "217", "218", "219", "220", "223",
            "224", "225", "226", "228", "229", "231", "234", "236", "239", "240",
            "242", "246", "248", "249", "250", "251", "252", "253", "254", "256",
            "260", "262", "264", "267", "268", "269", "270", "272", "276", "279",
            "281", "284", "289", "301", "302", "303", "304", "305", "306", "307",
            "308", "309", "310", "312", "313", "314", "315", "316", "317", "318",
            "319", "320", "321", "323", "325", "326", "330", "331", "332", "334",
            "336", "337", "339", "340", "341", "343", "345", "346", "347", "351",
            "352", "360", "361", "364", "365", "367", "368", "380", "385", "386",
            "401", "402", "403", "404", "405", "406", "407", "408", "409", "410",
            "412", "413", "414", "415", "416", "417", "418", "419", "423", "424",
            "425", "430", "431", "432", "434", "435", "437", "438", "440", "441",
            "442", "443", "445", "447", "448", "450", "458", "463", "464", "469",
            "470", "473", "474", "475", "478", "479", "480", "484", "501", "502",
            "503", "504", "505", "506", "507", "508", "509", "510", "512", "513",
            "514", "515", "516", "517", "518", "519", "520", "530", "531", "534",
            "539", "540", "541", "548", "551", "559", "561", "562", "563", "564",
            "567", "570", "571", "572", "573", "574", "575", "579", "580", "581",
            "582", "585", "586", "587", "601", "602", "603", "604", "605", "606",
            "607", "608", "609", "610", "612", "613", "614", "615", "616", "617",
            "618", "619", "620", "623", "626", "628", "629", "630", "631", "636",
            "639", "640", "641", "646", "647", "649", "650", "651", "656", "657",
            "658", "659", "660", "661", "662", "664", "667", "669", "670", "671",
            "672", "678", "680", "681", "682", "683", "684", "689", "701", "702",
            "703", "704", "705", "706", "707", "708", "709", "712", "713", "714",
            "715", "716", "717", "718", "719", "720", "721", "724", "725", "726",
            "727", "731", "732", "734", "737", "740", "742", "743", "747", "753",
            "754", "757", "758", "760", "762", "763", "765", "767", "769", "770",
            "771", "772", "773", "774", "775", "778", "779", "780", "781", "782",
            "784", "785", "786", "787", "801", "802", "803", "804", "805", "806",
            "807", "808", "809", "810", "812", "813", "814", "815", "816", "817",
            "818", "819", "820", "825", "826", "828", "829", "830", "831", "832",
            "838", "839", "840", "843", "845", "847", "848", "849", "850", "854",
            "856", "857", "858", "859", "860", "862", "863", "864", "865", "867",
            "868", "869", "870", "872", "873", "876", "878", "901", "902", "903",
            "904", "905", "906", "907", "908", "909", "910", "912", "913", "914",
            "915", "916", "917", "918", "919", "920", "925", "928", "929", "930",
            "931", "934", "936", "937", "938", "939", "940", "941", "943", "945",
            "947", "948", "949", "951", "952", "954", "956", "959", "970", "971",
            "972", "973", "978", "979", "980", "983", "984", "985", "986", "989",
            "200", "211", "222", "233", "244", "255", "266", "277", "288", "299",
            "300", "311", "322", "333", "344", "355", "366", "377", "388", "399",
            "400", "411", "422", "433", "444", "455", "466", "477", "488", "499",
            "500", "511", "522", "533", "544", "555", "566", "577", "588", "599",
            "600", "611", "622", "633", "644", "655", "666", "677", "688", "699",
            "700", "711", "722", "733", "744", "755", "766", "777", "788", "799",
            "800", "811", "822", "833", "844", "855", "866", "877", "888", "899",
            "900", "911", "922", "933", "944", "955", "966", "977", "988", "999");

    /**
     * The 410 literals app/cpy/CSLKPCDY.cpy:L521 through L930 declare under
     * {@code VALID-GENERAL-PURP-CODE}, in copybook declaration order. app/cbl/COACTUPC.cbl:L2298
     * tests this band and no other, so it is the accepted set.
     */
    private static final List<String> DECLARED_GENERAL_PURPOSE_CODES = List.of(
            "201", "202", "203", "204", "205", "206", "207", "208", "209", "210",
            "212", "213", "214", "215", "216", "217", "218", "219", "220", "223",
            "224", "225", "226", "228", "229", "231", "234", "236", "239", "240",
            "242", "246", "248", "249", "250", "251", "252", "253", "254", "256",
            "260", "262", "264", "267", "268", "269", "270", "272", "276", "279",
            "281", "284", "289", "301", "302", "303", "304", "305", "306", "307",
            "308", "309", "310", "312", "313", "314", "315", "316", "317", "318",
            "319", "320", "321", "323", "325", "326", "330", "331", "332", "334",
            "336", "337", "339", "340", "341", "343", "345", "346", "347", "351",
            "352", "360", "361", "364", "365", "367", "368", "380", "385", "386",
            "401", "402", "403", "404", "405", "406", "407", "408", "409", "410",
            "412", "413", "414", "415", "416", "417", "418", "419", "423", "424",
            "425", "430", "431", "432", "434", "435", "437", "438", "440", "441",
            "442", "443", "445", "447", "448", "450", "458", "463", "464", "469",
            "470", "473", "474", "475", "478", "479", "480", "484", "501", "502",
            "503", "504", "505", "506", "507", "508", "509", "510", "512", "513",
            "514", "515", "516", "517", "518", "519", "520", "530", "531", "534",
            "539", "540", "541", "548", "551", "559", "561", "562", "563", "564",
            "567", "570", "571", "572", "573", "574", "575", "579", "580", "581",
            "582", "585", "586", "587", "601", "602", "603", "604", "605", "606",
            "607", "608", "609", "610", "612", "613", "614", "615", "616", "617",
            "618", "619", "620", "623", "626", "628", "629", "630", "631", "636",
            "639", "640", "641", "646", "647", "649", "650", "651", "656", "657",
            "658", "659", "660", "661", "662", "664", "667", "669", "670", "671",
            "672", "678", "680", "681", "682", "683", "684", "689", "701", "702",
            "703", "704", "705", "706", "707", "708", "709", "712", "713", "714",
            "715", "716", "717", "718", "719", "720", "721", "724", "725", "726",
            "727", "731", "732", "734", "737", "740", "742", "743", "747", "753",
            "754", "757", "758", "760", "762", "763", "765", "767", "769", "770",
            "771", "772", "773", "774", "775", "778", "779", "780", "781", "782",
            "784", "785", "786", "787", "801", "802", "803", "804", "805", "806",
            "807", "808", "809", "810", "812", "813", "814", "815", "816", "817",
            "818", "819", "820", "825", "826", "828", "829", "830", "831", "832",
            "838", "839", "840", "843", "845", "847", "848", "849", "850", "854",
            "856", "857", "858", "859", "860", "862", "863", "864", "865", "867",
            "868", "869", "870", "872", "873", "876", "878", "901", "902", "903",
            "904", "905", "906", "907", "908", "909", "910", "912", "913", "914",
            "915", "916", "917", "918", "919", "920", "925", "928", "929", "930",
            "931", "934", "936", "937", "938", "939", "940", "941", "943", "945",
            "947", "948", "949", "951", "952", "954", "956", "959", "970", "971",
            "972", "973", "978", "979", "980", "983", "984", "985", "986", "989");

    /**
     * The 80 literals app/cpy/CSLKPCDY.cpy:L931 through L1010 declare under
     * {@code VALID-EASY-RECOG-AREA-CODE}, in copybook declaration order. No member of app/cbl
     * references that condition name, and the band is the set the wider band holds and
     * {@code VALID-GENERAL-PURP-CODE} omits.
     */
    private static final List<String> DECLARED_EASILY_RECOGNISABLE_CODES = List.of(
            "200", "211", "222", "233", "244", "255", "266", "277", "288", "299",
            "300", "311", "322", "333", "344", "355", "366", "377", "388", "399",
            "400", "411", "422", "433", "444", "455", "466", "477", "488", "499",
            "500", "511", "522", "533", "544", "555", "566", "577", "588", "599",
            "600", "611", "622", "633", "644", "655", "666", "677", "688", "699",
            "700", "711", "722", "733", "744", "755", "766", "777", "788", "799",
            "800", "811", "822", "833", "844", "855", "866", "877", "888", "899",
            "900", "911", "922", "933", "944", "955", "966", "977", "988", "999");

    @Test
    @DisplayName("Every code the 490 code band holds and the 410 code band omits is refused, so binding the wider band fails here")
    void refusesEveryCodeTheGeneralPurposeBandOmits() {
        Set<String> refusedCodes = codesTheGeneralPurposeBandOmits();

        assertThat(refusedCodes).hasSize(EASILY_RECOGNISABLE_BAND_SIZE);

        for (String areaCode : refusedCodes) {
            // app/cpy/CSLKPCDY.cpy:L30 holds the code, and app/cbl/COACTUPC.cbl:L2298 refuses it.
            assertThat(UsPhoneAreaCodes.isValidPhoneAreaCode(areaCode))
                    .as("app/cpy/CSLKPCDY.cpy:L30 holds %s", areaCode)
                    .isTrue();

            EditResult result = validateAreaCode(areaCode);

            assertThat(result.valid()).as("area code %s", areaCode).isFalse();
            assertThat(result.message())
                    .as("area code %s", areaCode)
                    .isEqualTo(LABEL + AREA_CODE_NOT_IN_BAND);
        }
    }

    @Test
    @DisplayName("Every code the 410 code band holds passes")
    void acceptsEveryCodeTheGeneralPurposeBandHolds() {
        Set<String> acceptedCodes = UsPhoneAreaCodes.generalPurposeCodes();

        assertThat(acceptedCodes).hasSize(GENERAL_PURPOSE_BAND_SIZE);

        for (String areaCode : acceptedCodes) {
            EditResult result = validateAreaCode(areaCode);

            assertThat(result.valid()).as("area code %s", areaCode).isTrue();
            assertThat(result.message()).as("area code %s", areaCode).isNull();
        }
    }

    @Test
    @DisplayName("The accepted set counts 410 codes, and 980 counts literals across the three bands")
    void theAcceptedSetCountsFourHundredAndTenCodes() {
        // app/cbl/COACTUPC.cbl:L2298 tests VALID-GENERAL-PURP-CODE, declared at
        // app/cpy/CSLKPCDY.cpy:L521. The wider band at app/cpy/CSLKPCDY.cpy:L30 holds 490 codes.
        assertThat(UsPhoneAreaCodes.generalPurposeCodes().size())
                .isEqualTo(GENERAL_PURPOSE_BAND_SIZE)
                .isNotEqualTo(PHONE_AREA_BAND_SIZE)
                .isNotEqualTo(BAND_LITERAL_COUNT);
        assertThat(UsPhoneAreaCodes.phoneAreaCodes()).hasSize(PHONE_AREA_BAND_SIZE);
        assertThat(UsPhoneAreaCodes.easilyRecognisableAreaCodes())
                .hasSize(EASILY_RECOGNISABLE_BAND_SIZE);

        int literalCount = UsPhoneAreaCodes.phoneAreaCodes().size()
                + UsPhoneAreaCodes.generalPurposeCodes().size()
                + UsPhoneAreaCodes.easilyRecognisableAreaCodes().size();

        assertThat(literalCount).isEqualTo(BAND_LITERAL_COUNT);
    }

    @Test
    @DisplayName("Each of the three production bands equals the band app/cpy/CSLKPCDY.cpy lists, "
            + "value for value")
    void everyProductionBandEqualsTheBandTheCopybookLists() {
        assertThat(UsPhoneAreaCodes.phoneAreaCodes())
                .as("VALID-PHONE-AREA-CODE at app/cpy/CSLKPCDY.cpy:L30")
                .containsExactlyElementsOf(DECLARED_PHONE_AREA_CODES);

        assertThat(UsPhoneAreaCodes.generalPurposeCodes())
                .as("VALID-GENERAL-PURP-CODE at app/cpy/CSLKPCDY.cpy:L521")
                .containsExactlyElementsOf(DECLARED_GENERAL_PURPOSE_CODES);

        assertThat(UsPhoneAreaCodes.easilyRecognisableAreaCodes())
                .as("VALID-EASY-RECOG-AREA-CODE at app/cpy/CSLKPCDY.cpy:L931")
                .containsExactlyElementsOf(DECLARED_EASILY_RECOGNISABLE_CODES);
    }

    @Test
    @DisplayName("The three declared bands hold 490, 410 and 80 codes, all three characters wide, "
            + "and the two narrower bands partition the wider one")
    void theDeclaredBandsHoldTheCountedCodes() {
        assertThat(DECLARED_PHONE_AREA_CODES).hasSize(PHONE_AREA_BAND_SIZE);
        assertThat(DECLARED_GENERAL_PURPOSE_CODES).hasSize(GENERAL_PURPOSE_BAND_SIZE);
        assertThat(DECLARED_EASILY_RECOGNISABLE_CODES).hasSize(EASILY_RECOGNISABLE_BAND_SIZE);

        for (String areaCode : DECLARED_PHONE_AREA_CODES) {
            assertThat(areaCode).as("copybook literal %s", areaCode).hasSize(AREA_CODE_WIDTH);
        }

        Set<String> partition = new LinkedHashSet<>(DECLARED_GENERAL_PURPOSE_CODES);
        partition.addAll(DECLARED_EASILY_RECOGNISABLE_CODES);

        assertThat(partition)
                .as("VALID-GENERAL-PURP-CODE and VALID-EASY-RECOG-AREA-CODE together")
                .containsExactlyInAnyOrderElementsOf(DECLARED_PHONE_AREA_CODES);
        assertThat(GENERAL_PURPOSE_BAND_SIZE + EASILY_RECOGNISABLE_BAND_SIZE)
                .as("the two narrower bands are disjoint")
                .isEqualTo(PHONE_AREA_BAND_SIZE);
    }

    @Test
    @DisplayName("Every code app/cpy/CSLKPCDY.cpy:L521 lists passes the edit and every code it "
            + "omits from that band fails")
    void theCopybookDecidesWhichCodesPassTheEdit() {
        for (String areaCode : DECLARED_GENERAL_PURPOSE_CODES) {
            assertThat(validateAreaCode(areaCode).valid())
                    .as("VALID-GENERAL-PURP-CODE lists %s", areaCode)
                    .isTrue();
        }

        Set<String> omitted = new LinkedHashSet<>(DECLARED_PHONE_AREA_CODES);
        omitted.removeAll(DECLARED_GENERAL_PURPOSE_CODES);

        assertThat(omitted).hasSize(EASILY_RECOGNISABLE_BAND_SIZE);
        for (String areaCode : omitted) {
            EditResult result = validateAreaCode(areaCode);

            assertThat(result.valid())
                    .as("VALID-GENERAL-PURP-CODE omits %s", areaCode)
                    .isFalse();
            assertThat(result.message())
                    .as("VALID-GENERAL-PURP-CODE omits %s", areaCode)
                    .isEqualTo(LABEL + AREA_CODE_NOT_IN_BAND);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"200", "211", "311", "500", "600", "888", "999"})
    @DisplayName("An area code the 490 code band holds and the 410 code band omits yields the message at app/cbl/COACTUPC.cbl:L2306")
    void codeOutsideTheGeneralPurposeBandYieldsTheBandMessage(String areaCode) {
        assertThat(UsPhoneAreaCodes.isValidPhoneAreaCode(areaCode))
                .as("app/cpy/CSLKPCDY.cpy:L30 holds %s", areaCode)
                .isTrue();
        assertThat(UsPhoneAreaCodes.isValidGeneralPurposeCode(areaCode))
                .as("app/cpy/CSLKPCDY.cpy:L521 omits %s", areaCode)
                .isFalse();

        EditResult result = validateAreaCode(areaCode);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(LABEL + AREA_CODE_NOT_IN_BAND);
        assertThat(result.message()).doesNotEndWith(CLOSING_PERIOD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"201", "202", "203", "204", "205", "206", "207", "208", "908"})
    @DisplayName("An area code the 410 code band holds passes with no message")
    void codeInsideTheGeneralPurposeBandPasses(String areaCode) {
        assertThat(UsPhoneAreaCodes.isValidGeneralPurposeCode(areaCode))
                .as("app/cpy/CSLKPCDY.cpy:L521 holds %s", areaCode)
                .isTrue();

        EditResult result = validateAreaCode(areaCode);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
    }

    @Test
    @DisplayName("An area code no band holds yields the message at app/cbl/COACTUPC.cbl:L2306")
    void codeNoBandHoldsYieldsTheBandMessage() {
        // A synthetic three-digit code that no band in app/cpy/CSLKPCDY.cpy holds.
        String unlistedCode = "373";

        assertThat(UsPhoneAreaCodes.isValidPhoneAreaCode(unlistedCode)).isFalse();
        assertThat(UsPhoneAreaCodes.isValidGeneralPurposeCode(unlistedCode)).isFalse();

        EditResult result = validateAreaCode(unlistedCode);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(LABEL + AREA_CODE_NOT_IN_BAND);
    }

    @Test
    @DisplayName("A blank area code yields the message at app/cbl/COACTUPC.cbl:L2254, closing with a period")
    void blankAreaCodeYieldsTheSuppliedMessage() {
        // app/cbl/COACTUPC.cbl:L2247 to L2248 tests spaces and low values, with no trim clause.
        EditResult result = validateAreaCode(BLANK_AREA_CODE);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(LABEL + AREA_CODE_BLANK);
        assertThat(result.message()).endsWith(CLOSING_PERIOD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "12", "20A", "2 1", "-01", "a b"})
    @DisplayName("An area code that is not three digits yields the message at app/cbl/COACTUPC.cbl:L2272, closing with a period")
    void nonNumericAreaCodeYieldsTheDigitCountMessage(String areaCode) {
        // app/cbl/COACTUPC.cbl:L2264 runs the class test over the PIC X(3) field at
        // app/cbl/COACTUPC.cbl:L87, so a short value carries a trailing space and fails.
        EditResult result = validateAreaCode(areaCode);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(LABEL + AREA_CODE_NOT_NUMERIC);
        assertThat(result.message()).endsWith(CLOSING_PERIOD);
    }

    @Test
    @DisplayName("An all zero area code yields the message at app/cbl/COACTUPC.cbl:L2286, closing without a period")
    void zeroAreaCodeYieldsTheZeroMessage() {
        // app/cbl/COACTUPC.cbl:L2280 reads the PIC 9(3) redefine at app/cbl/COACTUPC.cbl:L88 to L89.
        EditResult result = validateAreaCode(ZERO_AREA_CODE);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(LABEL + AREA_CODE_ZERO);
        assertThat(result.message()).doesNotEndWith(CLOSING_PERIOD);
    }

    @Test
    @DisplayName("The four area code checks fire in the order of app/cbl/COACTUPC.cbl:L2247, L2264, L2280 and L2298")
    void theFourAreaCodeChecksFireInSourceOrder() {
        // A blank field is also not numeric, and app/cbl/COACTUPC.cbl:L2247 runs first.
        assertThat(validateAreaCode(BLANK_AREA_CODE).message()).isEqualTo(LABEL + AREA_CODE_BLANK);

        // "00A" is not numeric and is not all zeros, and app/cbl/COACTUPC.cbl:L2264 runs next.
        assertThat(validateAreaCode("00A").message()).isEqualTo(LABEL + AREA_CODE_NOT_NUMERIC);

        // "000" is numeric, and app/cpy/CSLKPCDY.cpy:L521 omits it, and L2280 runs ahead of L2298.
        assertThat(UsPhoneAreaCodes.isValidGeneralPurposeCode(ZERO_AREA_CODE)).isFalse();
        assertThat(validateAreaCode(ZERO_AREA_CODE).message()).isEqualTo(LABEL + AREA_CODE_ZERO);

        // app/cbl/COACTUPC.cbl:L2298 runs last, and reaches a value the three earlier checks pass.
        assertThat(validateAreaCode(REFUSED_HEAD_CODE).message())
                .isEqualTo(LABEL + AREA_CODE_NOT_IN_BAND);
    }

    @Test
    @DisplayName("A four character area code fails the digit-count check and never reaches the band check")
    void fourCharacterAreaCodeFailsTheDigitCountCheck() {
        // app/cbl/COACTUPC.cbl:L87 declares the field PIC X(3), so a MOVE keeps three characters.
        // The value it moves comes from a screen field of that exact width, so the MOVE drops
        // nothing but padding.
        assertThat(UsPhoneAreaCodes.isValidGeneralPurposeCode("123")).isFalse();

        // ADDITIVE. A caller of this edit can supply a wider value, and the edit refuses one that
        // carries a character the field would not hold. The message is the source literal at
        // app/cbl/COACTUPC.cbl:L2272, which reads that the area code must be a three digit number.
        EditResult fourDigits = validateAreaCode("1234");
        EditResult threeDigitsAndAletter = validateAreaCode("410X");
        EditResult threeDigitsAndPadding = validateAreaCode("410  ");

        assertThat(fourDigits.valid()).isFalse();
        assertThat(fourDigits.message()).isEqualTo(LABEL + AREA_CODE_NOT_NUMERIC);
        assertThat(threeDigitsAndAletter.valid()).isFalse();
        assertThat(threeDigitsAndAletter.message()).isEqualTo(LABEL + AREA_CODE_NOT_NUMERIC);

        // Trailing spaces past the width are the padding the source MOVE itself drops, so a padded
        // area code still reaches the band check and passes it.
        assertThat(threeDigitsAndPadding.valid()).isTrue();
        assertThat(threeDigitsAndPadding.message()).isNull();
    }

    @Test
    @DisplayName("A prefix or line number wider than its field fails the digit-count check")
    void widerPrefixAndLineNumberFailTheDigitCountCheck() {
        // ADDITIVE. Neither part can be wider in the source: app/cbl/COACTUPC.cbl:L92 declares the
        // prefix PIC X(3) and L97 declares the line number PIC X(4). The messages are the source
        // literals at app/cbl/COACTUPC.cbl:L2343 and L2396.
        EditResult widePrefix = validatePrefix("1234");
        EditResult wideLineNumber = validateLineNumber("12345");
        EditResult paddedPrefix = validatePrefix("410  ");

        assertThat(widePrefix.valid()).isFalse();
        assertThat(widePrefix.message()).isEqualTo(LABEL + PREFIX_NOT_NUMERIC);
        assertThat(wideLineNumber.valid()).isFalse();
        assertThat(wideLineNumber.message()).isEqualTo(LABEL + LINE_NUMBER_NOT_NUMERIC);
        assertThat(paddedPrefix.valid()).isTrue();
        assertThat(paddedPrefix.message()).isNull();
    }

    @Test
    @DisplayName("A blank prefix yields the message at app/cbl/COACTUPC.cbl:L2325, closing with a period")
    void blankPrefixYieldsTheSuppliedMessage() {
        // app/cbl/COACTUPC.cbl:L2318 to L2319 tests spaces and low values on the part at L92.
        EditResult result = validatePrefix(BLANK_PREFIX);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(LABEL + PREFIX_BLANK);
        assertThat(result.message()).endsWith(CLOSING_PERIOD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "11", "1A1", "1 1", "-11"})
    @DisplayName("A prefix that is not three digits yields the message at app/cbl/COACTUPC.cbl:L2343, closing with a period")
    void nonNumericPrefixYieldsTheDigitCountMessage(String prefix) {
        // app/cbl/COACTUPC.cbl:L2335 runs the class test over the PIC X(3) field at L92.
        EditResult result = validatePrefix(prefix);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(LABEL + PREFIX_NOT_NUMERIC);
        assertThat(result.message()).endsWith(CLOSING_PERIOD);
    }

    @Test
    @DisplayName("An all zero prefix yields the message at app/cbl/COACTUPC.cbl:L2357, closing without a period")
    void zeroPrefixYieldsTheZeroMessage() {
        // app/cbl/COACTUPC.cbl:L2351 reads the PIC 9(3) redefine at app/cbl/COACTUPC.cbl:L93 to L94.
        EditResult result = validatePrefix(ZERO_PREFIX);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(LABEL + PREFIX_ZERO);
        assertThat(result.message()).doesNotEndWith(CLOSING_PERIOD);
    }

    @Test
    @DisplayName("The three prefix checks fire in the order of app/cbl/COACTUPC.cbl:L2318, L2335 and L2351")
    void theThreePrefixChecksFireInSourceOrder() {
        assertThat(validatePrefix(BLANK_PREFIX).message()).isEqualTo(LABEL + PREFIX_BLANK);
        assertThat(validatePrefix("00A").message()).isEqualTo(LABEL + PREFIX_NOT_NUMERIC);
        assertThat(validatePrefix(ZERO_PREFIX).message()).isEqualTo(LABEL + PREFIX_ZERO);
    }

    @Test
    @DisplayName("A blank line number yields the message at app/cbl/COACTUPC.cbl:L2378, closing with a period")
    void blankLineNumberYieldsTheSuppliedMessage() {
        // app/cbl/COACTUPC.cbl:L2371 to L2372 tests spaces and low values on the part at L97.
        EditResult result = validateLineNumber(BLANK_LINE_NUMBER);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(LABEL + LINE_NUMBER_BLANK);
        assertThat(result.message()).endsWith(CLOSING_PERIOD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "831", "83A0", "83 0", "-310"})
    @DisplayName("A line number that is not four digits yields the message at app/cbl/COACTUPC.cbl:L2396, closing with a period")
    void nonNumericLineNumberYieldsTheDigitCountMessage(String lineNumber) {
        // app/cbl/COACTUPC.cbl:L2388 runs the class test over the PIC X(4) field at L97.
        EditResult result = validateLineNumber(lineNumber);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(LABEL + LINE_NUMBER_NOT_NUMERIC);
        assertThat(result.message()).endsWith(CLOSING_PERIOD);
    }

    @Test
    @DisplayName("An all zero line number yields the message at app/cbl/COACTUPC.cbl:L2410, closing without a period")
    void zeroLineNumberYieldsTheZeroMessage() {
        // app/cbl/COACTUPC.cbl:L2404 reads the PIC 9(4) redefine at app/cbl/COACTUPC.cbl:L98 to L99.
        EditResult result = validateLineNumber(ZERO_LINE_NUMBER);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(LABEL + LINE_NUMBER_ZERO);
        assertThat(result.message()).doesNotEndWith(CLOSING_PERIOD);
    }

    @Test
    @DisplayName("The three line number checks fire in the order of app/cbl/COACTUPC.cbl:L2371, L2388 and L2404")
    void theThreeLineNumberChecksFireInSourceOrder() {
        assertThat(validateLineNumber(BLANK_LINE_NUMBER).message())
                .isEqualTo(LABEL + LINE_NUMBER_BLANK);
        assertThat(validateLineNumber("000A").message()).isEqualTo(LABEL + LINE_NUMBER_NOT_NUMERIC);
        assertThat(validateLineNumber(ZERO_LINE_NUMBER).message()).isEqualTo(LABEL + LINE_NUMBER_ZERO);
    }

    @Test
    @DisplayName("The three digit count messages carry a capital A")
    void theThreeDigitCountMessagesCarryACapitalA() {
        // Literals at app/cbl/COACTUPC.cbl:L2272, L2343 and L2396.
        assertThat(validateAreaCode(NON_NUMERIC_AREA_CODE).message())
                .contains("must be A 3 digit number.")
                .doesNotContain("must be a 3 digit number");
        assertThat(validatePrefix(NON_NUMERIC_PREFIX).message())
                .contains("must be A 3 digit number.")
                .doesNotContain("must be a 3 digit number");
        assertThat(validateLineNumber(NON_NUMERIC_LINE_NUMBER).message())
                .contains("must be A 4 digit number.")
                .doesNotContain("must be a 4 digit number");
    }

    @Test
    @DisplayName("A number whose three parts all pass yields no message")
    void theAcceptedPhoneNumberPasses() {
        EditResult result = UsPhoneNumberValidator.validate(
                LABEL, ACCEPTED_AREA_CODE, ACCEPTED_PREFIX, ACCEPTED_LINE_NUMBER);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
        assertThat(result).isEqualTo(EditResult.ok());
    }

    @Test
    @DisplayName("A bad prefix and a bad line number together yield exactly one message, the earliest")
    void aBadPrefixAndABadLineNumberYieldExactlyOneMessage() {
        // Each part fails on its own: app/cbl/COACTUPC.cbl:L2335 and app/cbl/COACTUPC.cbl:L2404.
        assertThat(validatePrefix(NON_NUMERIC_PREFIX).message()).isEqualTo(LABEL + PREFIX_NOT_NUMERIC);
        assertThat(validateLineNumber(ZERO_LINE_NUMBER).message()).isEqualTo(LABEL + LINE_NUMBER_ZERO);

        EditResult result = UsPhoneNumberValidator.validate(
                LABEL, ACCEPTED_AREA_CODE, NON_NUMERIC_PREFIX, ZERO_LINE_NUMBER);

        // app/cbl/COACTUPC.cbl:L2348 reaches the line number, which is edited and fails as well.
        // app/cbl/COACTUPC.cbl:L480 leaves one slot, so the prefix message is the one that survives.
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(LABEL + PREFIX_NOT_NUMERIC);
        assertThat(result.message()).doesNotContain(LINE_NUMBER_ZERO);

        // Verdicts per part sit at app/cbl/COACTUPC.cbl:L2367 and L2421. EditResult carries one
        // verdict and one message, and no flag is asserted here.
    }

    @Test
    @DisplayName("A bad area code and a bad prefix together yield the area code message only")
    void aBadAreaCodeAndABadPrefixYieldTheAreaCodeMessageOnly() {
        assertThat(validateAreaCode(REFUSED_HEAD_CODE).message())
                .isEqualTo(LABEL + AREA_CODE_NOT_IN_BAND);
        assertThat(validatePrefix(BLANK_PREFIX).message()).isEqualTo(LABEL + PREFIX_BLANK);

        EditResult result = UsPhoneNumberValidator.validate(
                LABEL, REFUSED_HEAD_CODE, BLANK_PREFIX, ACCEPTED_LINE_NUMBER);

        // app/cbl/COACTUPC.cbl:L2311 reaches the prefix, which is edited and fails as well.
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(LABEL + AREA_CODE_NOT_IN_BAND);
        assertThat(result.message()).doesNotContain(PREFIX_BLANK);
    }

    @Test
    @DisplayName("Three failing parts yield the area code message only")
    void threeFailingPartsYieldTheAreaCodeMessageOnly() {
        EditResult result = UsPhoneNumberValidator.validate(
                LABEL, ZERO_AREA_CODE, ZERO_PREFIX, ZERO_LINE_NUMBER);

        assertThat(result.valid()).isFalse();
        assertThat(result.message())
                .isEqualTo(LABEL + AREA_CODE_ZERO)
                .doesNotContain(PREFIX_ZERO)
                .doesNotContain(LINE_NUMBER_ZERO);
    }

    @Test
    @DisplayName("A blank area code and a blank prefix with a populated line number pass")
    void aBlankAreaCodeAndABlankPrefixWithAPopulatedLineNumberPass() {
        // Each blank part fails on its own: app/cbl/COACTUPC.cbl:L2247 and L2318.
        assertThat(validateAreaCode(BLANK_AREA_CODE).message()).isEqualTo(LABEL + AREA_CODE_BLANK);
        assertThat(validatePrefix(BLANK_PREFIX).message()).isEqualTo(LABEL + PREFIX_BLANK);

        EditResult result = UsPhoneNumberValidator.validate(
                LABEL, BLANK_AREA_CODE, BLANK_PREFIX, ACCEPTED_LINE_NUMBER);

        // app/cbl/COACTUPC.cbl:L2238 tests the area code for spaces, where app/cbl/COACTUPC.cbl:L2239
        // tests the line number for low values. All three clauses hold, and the escape at
        // app/cbl/COACTUPC.cbl:L2240 to L2241 marks the number valid.
        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
    }

    @Test
    @DisplayName("A number blank in all three parts passes")
    void aNumberBlankInAllThreePartsPasses() {
        // The three clauses at app/cbl/COACTUPC.cbl:L2234 to L2239 all hold.
        EditResult result = UsPhoneNumberValidator.validate(
                LABEL, BLANK_AREA_CODE, BLANK_PREFIX, BLANK_LINE_NUMBER);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
        assertThat(result.hasMessage()).isFalse();
    }

    @Test
    @DisplayName("Low values in the area code and the prefix with a populated line number reach the area code check")
    void lowValuesInTheAreaCodeAndThePrefixWithAPopulatedLineNumberReachTheAreaCodeCheck() {
        EditResult result = UsPhoneNumberValidator.validate(LABEL, null, null, ACCEPTED_LINE_NUMBER);

        // app/cbl/COACTUPC.cbl:L2238 names spaces on the area code, and a low values area code
        // leaves that clause unsatisfied. app/cbl/COACTUPC.cbl:L2239 names low values on the line
        // number. A populated line number leaves that clause unsatisfied as well. The pass reaches
        // app/cbl/COACTUPC.cbl:L2247 and the message at app/cbl/COACTUPC.cbl:L2254.
        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(LABEL + AREA_CODE_BLANK);
    }

    @Test
    @DisplayName("Low values in all three parts pass")
    void lowValuesInAllThreePartsPass() {
        EditResult result = UsPhoneNumberValidator.validate(LABEL, null, null, null);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
    }

    @Test
    @DisplayName("Three parts filled with LOW-VALUES at their declared widths pass, the second "
            + "clause of each pair at app/cbl/COACTUPC.cbl:L2235 to L2239")
    void everyPartFilledWithLowValuesAtItsDeclaredWidthPasses() {
        EditResult result = UsPhoneNumberValidator.validate(LABEL,
                LOW_VALUE.repeat(AREA_CODE_WIDTH),
                LOW_VALUE.repeat(PREFIX_WIDTH),
                LOW_VALUE.repeat(LINE_NUMBER_WIDTH));

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).isNull();
    }

    @Test
    @DisplayName("A part filled with LOW-VALUES narrower than its declared width still reads as "
            + "LOW-VALUES. A COBOL comparison tests every position it holds")
    void aPartOfLowValuesNarrowerThanItsWidthStillReadsAsLowValues() {
        for (int supplied : new int[] {1, 2}) {
            EditResult result = UsPhoneNumberValidator.validate(LABEL,
                    LOW_VALUE.repeat(supplied), LOW_VALUE.repeat(supplied),
                    LOW_VALUE.repeat(supplied));

            assertThat(result.valid()).as("%d characters supplied", supplied).isTrue();
            assertThat(result.message()).as("%d characters supplied", supplied).isNull();
        }
    }

    @Test
    @DisplayName("An area code of LOW-VALUES beside a populated line number reaches the area code "
            + "check and reports the blank area code")
    void anAreaCodeOfLowValuesBesideAPopulatedLineNumberReportsTheBlankAreaCode() {
        EditResult result = UsPhoneNumberValidator.validate(LABEL,
                LOW_VALUE.repeat(AREA_CODE_WIDTH), LOW_VALUE.repeat(PREFIX_WIDTH),
                ACCEPTED_LINE_NUMBER);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo(LABEL + AREA_CODE_BLANK);
    }

    @Test
    @DisplayName("A part carrying a digit followed by LOW-VALUES fails its numeric class test")
    void aPartCarryingADigitFollowedByLowValuesFailsItsNumericClassTest() {
        EditResult areaCode = UsPhoneNumberValidator.validate(LABEL,
                "2" + LOW_VALUE.repeat(AREA_CODE_WIDTH - 1), ACCEPTED_PREFIX,
                ACCEPTED_LINE_NUMBER);

        assertThat(areaCode.valid()).isFalse();
        assertThat(areaCode.message()).isEqualTo(LABEL + AREA_CODE_NOT_NUMERIC);

        EditResult prefix = UsPhoneNumberValidator.validate(LABEL, ACCEPTED_AREA_CODE,
                "5" + LOW_VALUE.repeat(PREFIX_WIDTH - 1), ACCEPTED_LINE_NUMBER);

        assertThat(prefix.valid()).isFalse();
        assertThat(prefix.message()).isEqualTo(LABEL + PREFIX_NOT_NUMERIC);

        EditResult lineNumber = UsPhoneNumberValidator.validate(LABEL, ACCEPTED_AREA_CODE,
                ACCEPTED_PREFIX, "1" + LOW_VALUE.repeat(LINE_NUMBER_WIDTH - 1));

        assertThat(lineNumber.valid()).isFalse();
        assertThat(lineNumber.message()).isEqualTo(LABEL + LINE_NUMBER_NOT_NUMERIC);
    }

    @ParameterizedTest
    @MethodSource("everyMessage")
    @DisplayName("Each of the ten messages matches its source literal character for character")
    void eachOfTheTenMessagesMatchesItsSourceLiteral(
            String areaCode, String prefix, String lineNumber, String message) {
        EditResult result = UsPhoneNumberValidator.validate(LABEL, areaCode, prefix, lineNumber);

        assertThat(result.valid()).isFalse();
        assertThat(result.hasMessage()).isTrue();
        assertThat(result.message()).isEqualTo(LABEL + message);
        assertThat(result.message()).startsWith(LABEL + ":");
    }

    @ParameterizedTest
    @MethodSource("everyMessage")
    @DisplayName("A padded field label loses its surrounding spaces in each of the ten messages")
    void aPaddedFieldLabelLosesItsSurroundingSpaces(
            String areaCode, String prefix, String lineNumber, String message) {
        // FUNCTION TRIM sits ahead of every literal, at app/cbl/COACTUPC.cbl:L2253 and its nine peers.
        assertThat(PADDED_LABEL).hasSize(SOURCE_LABEL_WIDTH);

        EditResult padded = UsPhoneNumberValidator.validate(PADDED_LABEL, areaCode, prefix, lineNumber);

        assertThat(padded.message()).isEqualTo(LABEL + message);
        assertThat(padded.message()).doesNotContain(TWO_SPACES);
        assertThat(padded.message())
                .isEqualTo(UsPhoneNumberValidator.validate(LABEL, areaCode, prefix, lineNumber)
                        .message());
    }

    @Test
    @DisplayName("The label of each call site opens its own message")
    void theLabelOfEachCallSiteOpensItsOwnMessage() {
        // app/cbl/COACTUPC.cbl:L1635 carries 'Phone Number 1' and L1643 carries 'Phone Number 2'.
        assertThat(validateAreaCode(REFUSED_HEAD_CODE).message())
                .isEqualTo(LABEL + AREA_CODE_NOT_IN_BAND);
        assertThat(UsPhoneNumberValidator.validate(
                        SECOND_LABEL, REFUSED_TAIL_CODE, ACCEPTED_PREFIX, ACCEPTED_LINE_NUMBER)
                .message())
                .isEqualTo(SECOND_LABEL + AREA_CODE_NOT_IN_BAND);
    }

    /**
     * The ten messages app/cbl/COACTUPC.cbl:L2225 to L2422 produces, each paired with an input that
     * reaches it.
     *
     * <p>Order follows the source. The four area code checks sit at app/cbl/COACTUPC.cbl:L2247,
     * L2264, L2280 and L2298. The three prefix checks sit at app/cbl/COACTUPC.cbl:L2318, L2335 and
     * L2351. The three line number checks sit at app/cbl/COACTUPC.cbl:L2371, L2388 and L2404.</p>
     *
     * @return one argument set per message: area code, prefix, line number, and the message text
     */
    private static Stream<Arguments> everyMessage() {
        return Stream.of(
                arguments(BLANK_AREA_CODE, ACCEPTED_PREFIX, ACCEPTED_LINE_NUMBER, AREA_CODE_BLANK),
                arguments(NON_NUMERIC_AREA_CODE, ACCEPTED_PREFIX, ACCEPTED_LINE_NUMBER,
                        AREA_CODE_NOT_NUMERIC),
                arguments(ZERO_AREA_CODE, ACCEPTED_PREFIX, ACCEPTED_LINE_NUMBER, AREA_CODE_ZERO),
                arguments(REFUSED_HEAD_CODE, ACCEPTED_PREFIX, ACCEPTED_LINE_NUMBER,
                        AREA_CODE_NOT_IN_BAND),
                arguments(ACCEPTED_AREA_CODE, BLANK_PREFIX, ACCEPTED_LINE_NUMBER, PREFIX_BLANK),
                arguments(ACCEPTED_AREA_CODE, NON_NUMERIC_PREFIX, ACCEPTED_LINE_NUMBER,
                        PREFIX_NOT_NUMERIC),
                arguments(ACCEPTED_AREA_CODE, ZERO_PREFIX, ACCEPTED_LINE_NUMBER, PREFIX_ZERO),
                arguments(ACCEPTED_AREA_CODE, ACCEPTED_PREFIX, BLANK_LINE_NUMBER, LINE_NUMBER_BLANK),
                arguments(ACCEPTED_AREA_CODE, ACCEPTED_PREFIX, NON_NUMERIC_LINE_NUMBER,
                        LINE_NUMBER_NOT_NUMERIC),
                arguments(ACCEPTED_AREA_CODE, ACCEPTED_PREFIX, ZERO_LINE_NUMBER, LINE_NUMBER_ZERO));
    }

    /**
     * The codes app/cpy/CSLKPCDY.cpy:L30 holds and app/cpy/CSLKPCDY.cpy:L521 omits, in copybook
     * order.
     *
     * @return the set difference over the two band views the reference class exposes
     */
    private static Set<String> codesTheGeneralPurposeBandOmits() {
        Set<String> difference = new LinkedHashSet<>(UsPhoneAreaCodes.phoneAreaCodes());
        difference.removeAll(UsPhoneAreaCodes.generalPurposeCodes());
        return difference;
    }

    /**
     * Edits one area code against a prefix and a line number that both pass.
     *
     * @param areaCode the area code under test
     * @return the verdict for the whole number
     */
    private static EditResult validateAreaCode(String areaCode) {
        return UsPhoneNumberValidator.validate(LABEL, areaCode, ACCEPTED_PREFIX, ACCEPTED_LINE_NUMBER);
    }

    /**
     * Edits one prefix against an area code and a line number that both pass.
     *
     * @param prefix the prefix under test
     * @return the verdict for the whole number
     */
    private static EditResult validatePrefix(String prefix) {
        return UsPhoneNumberValidator.validate(LABEL, ACCEPTED_AREA_CODE, prefix, ACCEPTED_LINE_NUMBER);
    }

    /**
     * Edits one line number against an area code and a prefix that both pass.
     *
     * @param lineNumber the line number under test
     * @return the verdict for the whole number
     */
    private static EditResult validateLineNumber(String lineNumber) {
        return UsPhoneNumberValidator.validate(LABEL, ACCEPTED_AREA_CODE, ACCEPTED_PREFIX, lineNumber);
    }
}
