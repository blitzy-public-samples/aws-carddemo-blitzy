package com.carddemo.cobol.reference;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * North American telephone area codes read from {@code app/cpy/CSLKPCDY.cpy}.
 *
 * <p>The copybook declares the host field {@code 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX} at
 * line 24 and three {@code 88} level condition names over it. Each condition name becomes one
 * predicate and one set view here.</p>
 *
 * <ul>
 *   <li>{@code VALID-GENERAL-PURP-CODE}, lines 521 to 930, 410 codes.</li>
 *   <li>{@code VALID-EASY-RECOG-AREA-CODE}, lines 931 to 1010, 80 codes.</li>
 *   <li>{@code VALID-PHONE-AREA-CODE}, lines 30 to 520, 490 codes: the general purpose band in
 *       copybook order followed by the easily recognisable band in copybook order.</li>
 * </ul>
 *
 * <p>The three bands hold 980 literals and 490 distinct codes. The two smaller bands are
 * disjoint, and both sit inside {@code VALID-PHONE-AREA-CODE}.</p>
 *
 * <p>{@code app/cbl/COACTUPC.cbl} includes the copybook at line 602 and tests
 * {@code VALID-GENERAL-PURP-CODE} at line 2298, inside paragraph
 * {@code 1260-EDIT-US-PHONE-NUM} at line 2225. No program in {@code app/cbl} tests the other
 * two condition names.</p>
 *
 * <p>The codes come from the North American Numbering Plan Administrator report cited at
 * {@code app/cpy/CSLKPCDY.cpy} lines 26 to 28.</p>
 *
 * <p>Decisions behind this class: {@code card-platform/docs/decision-log.md}.</p>
 */
public final class UsPhoneAreaCodes {

    /** Width of every area code literal in the copybook, from {@code PIC XXX} at line 24. */
    private static final int CODE_LENGTH = 3;

    /** Code count {@code VALID-GENERAL-PURP-CODE} declares at lines 521 to 930. */
    private static final int GENERAL_PURPOSE_COUNT = 410;

    /** Code count {@code VALID-EASY-RECOG-AREA-CODE} declares at lines 931 to 1010. */
    private static final int EASILY_RECOGNISABLE_COUNT = 80;

    /** Code count {@code VALID-PHONE-AREA-CODE} declares at lines 30 to 520. */
    private static final int PHONE_AREA_COUNT = 490;

    private static final Set<String> GENERAL_PURPOSE_CODES;

    private static final Set<String> EASILY_RECOGNISABLE_CODES;

    private static final Set<String> PHONE_AREA_CODES;

    static {
        Set<String> generalPurpose = checkedBand(
                "VALID-GENERAL-PURP-CODE", generalPurposeLiterals(), GENERAL_PURPOSE_COUNT);
        Set<String> easilyRecognisable = checkedBand(
                "VALID-EASY-RECOG-AREA-CODE", easilyRecognisableLiterals(), EASILY_RECOGNISABLE_COUNT);

        if (!Collections.disjoint(generalPurpose, easilyRecognisable)) {
            throw new IllegalStateException(
                    "VALID-GENERAL-PURP-CODE and VALID-EASY-RECOG-AREA-CODE share a code. "
                            + "The copybook declares no code in both bands.");
        }

        Set<String> phoneArea = new LinkedHashSet<>(generalPurpose);
        phoneArea.addAll(easilyRecognisable);
        if (phoneArea.size() != PHONE_AREA_COUNT) {
            throw new IllegalStateException(
                    "VALID-PHONE-AREA-CODE holds " + phoneArea.size() + " codes. The copybook declares "
                            + PHONE_AREA_COUNT + " at lines 30 to 520.");
        }

        GENERAL_PURPOSE_CODES = Collections.unmodifiableSet(generalPurpose);
        EASILY_RECOGNISABLE_CODES = Collections.unmodifiableSet(easilyRecognisable);
        PHONE_AREA_CODES = Collections.unmodifiableSet(phoneArea);
    }

    private UsPhoneAreaCodes() {
    }

    /**
     * Returns {@code true} for the 410 general purpose area codes declared at
     * {@code app/cpy/CSLKPCDY.cpy} lines 521 to 930 as {@code VALID-GENERAL-PURP-CODE}.
     * {@code app/cbl/COACTUPC.cbl} tests this condition at line 2298.
     *
     * @param areaCode an area code, with or without surrounding spaces
     * @return {@code true} when the value, once trimmed of spaces, is one of the 410 codes
     */
    public static boolean isValidGeneralPurposeCode(String areaCode) {
        return areaCode != null && GENERAL_PURPOSE_CODES.contains(trimSpaces(areaCode));
    }

    /**
     * Returns {@code true} for the 80 easily recognisable area codes declared at
     * {@code app/cpy/CSLKPCDY.cpy} lines 931 to 1010 as {@code VALID-EASY-RECOG-AREA-CODE}.
     * No program in {@code app/cbl} tests this condition.
     *
     * @param areaCode an area code, with or without surrounding spaces
     * @return {@code true} when the value, once trimmed of spaces, is one of the 80 codes
     */
    public static boolean isValidEasilyRecognisableAreaCode(String areaCode) {
        return areaCode != null && EASILY_RECOGNISABLE_CODES.contains(trimSpaces(areaCode));
    }

    /**
     * Returns {@code true} for the 490 area codes declared at {@code app/cpy/CSLKPCDY.cpy}
     * lines 30 to 520 as {@code VALID-PHONE-AREA-CODE}. Those 490 cover the 410 general purpose
     * codes and the 80 easily recognisable codes. No program in {@code app/cbl} tests this
     * condition.
     *
     * @param areaCode an area code, with or without surrounding spaces
     * @return {@code true} when the value, once trimmed of spaces, is one of the 490 codes
     */
    public static boolean isValidPhoneAreaCode(String areaCode) {
        return areaCode != null && PHONE_AREA_CODES.contains(trimSpaces(areaCode));
    }

    /**
     * The 410 {@code VALID-GENERAL-PURP-CODE} codes in copybook order, from
     * {@code app/cpy/CSLKPCDY.cpy} lines 521 to 930.
     *
     * @return an unmodifiable view that rejects every modification
     */
    public static Set<String> generalPurposeCodes() {
        return GENERAL_PURPOSE_CODES;
    }

    /**
     * The 80 {@code VALID-EASY-RECOG-AREA-CODE} codes in copybook order, from
     * {@code app/cpy/CSLKPCDY.cpy} lines 931 to 1010.
     *
     * @return an unmodifiable view that rejects every modification
     */
    public static Set<String> easilyRecognisableAreaCodes() {
        return EASILY_RECOGNISABLE_CODES;
    }

    /**
     * The 490 {@code VALID-PHONE-AREA-CODE} codes in copybook order, from
     * {@code app/cpy/CSLKPCDY.cpy} lines 30 to 520.
     *
     * @return an unmodifiable view that rejects every modification
     */
    public static Set<String> phoneAreaCodes() {
        return PHONE_AREA_CODES;
    }

    /**
     * Builds one band as an insertion ordered set and checks it against the copybook.
     *
     * @param conditionName the {@code 88} level condition name the band reproduces
     * @param literals      the band's codes in copybook order
     * @param expectedSize  the code count the copybook declares for the band
     * @return the band as an insertion ordered set
     * @throws IllegalStateException when a code is not three characters wide, when a code
     *                               repeats, or when the count differs from {@code expectedSize}
     */
    private static Set<String> checkedBand(String conditionName, String[] literals, int expectedSize) {
        Set<String> band = LinkedHashSet.newLinkedHashSet(literals.length);
        for (String code : literals) {
            if (code.length() != CODE_LENGTH) {
                throw new IllegalStateException(conditionName + " holds the code " + code
                        + ", which is not " + CODE_LENGTH + " characters wide.");
            }
            if (!band.add(code)) {
                throw new IllegalStateException(conditionName + " holds the code " + code + " twice.");
            }
        }
        if (band.size() != expectedSize) {
            throw new IllegalStateException(conditionName + " holds " + band.size()
                    + " codes. The copybook declares " + expectedSize + ".");
        }
        return band;
    }

    /**
     * Removes leading and trailing space characters, reproducing {@code FUNCTION TRIM} as
     * applied at {@code app/cbl/COACTUPC.cbl} line 2296. Tabs and every other whitespace
     * character stay in place.
     *
     * @param value the caller's area code
     * @return the value without its leading and trailing spaces
     */
    private static String trimSpaces(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * The 410 codes of {@code VALID-GENERAL-PURP-CODE}, transcribed in copybook order from
     * {@code app/cpy/CSLKPCDY.cpy} lines 521 to 930.
     *
     * @return the band's codes in copybook order
     */
    private static String[] generalPurposeLiterals() {
        return new String[] {
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
            "972", "973", "978", "979", "980", "983", "984", "985", "986", "989"
        };
    }

    /**
     * The 80 codes of {@code VALID-EASY-RECOG-AREA-CODE}, transcribed in copybook order from
     * {@code app/cpy/CSLKPCDY.cpy} lines 931 to 1010.
     *
     * @return the band's codes in copybook order
     */
    private static String[] easilyRecognisableLiterals() {
        return new String[] {
            "200", "211", "222", "233", "244", "255", "266", "277", "288", "299",
            "300", "311", "322", "333", "344", "355", "366", "377", "388", "399",
            "400", "411", "422", "433", "444", "455", "466", "477", "488", "499",
            "500", "511", "522", "533", "544", "555", "566", "577", "588", "599",
            "600", "611", "622", "633", "644", "655", "666", "677", "688", "699",
            "700", "711", "722", "733", "744", "755", "766", "777", "788", "799",
            "800", "811", "822", "833", "844", "855", "866", "877", "888", "899",
            "900", "911", "922", "933", "944", "955", "966", "977", "988", "999"
        };
    }
}
