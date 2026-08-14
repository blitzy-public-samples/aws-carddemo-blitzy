package com.carddemo.cobol.reference;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The 240 United States state-and-zip-prefix combinations that CardDemo accepts, read from
 * {@code app/cpy/CSLKPCDY.cpy}.
 *
 * <p>The group item {@code 01 US-STATE-ZIPCODE-TO-EDIT} at L1071 holds two children. The first is
 * {@code 02 US-STATE-AND-FIRST-ZIP2 PIC X(4)} at L1072. The second is
 * {@code 02 LAST-3-OF-ZIP PIC X(3)} at L1314, which completes the seven-character group and
 * carries no value list.</p>
 *
 * <p>An 88-level condition name is a COBOL named test over a field value. The condition name
 * {@code VALID-US-STATE-ZIP-CD2-COMBO} at L1073 lists the 240 accepted values at L1074-L1313.</p>
 *
 * <p>The four-character key is the two-character state code followed by the first two digits of the
 * zip code. {@code app/cbl/COACTUPC.cbl:L2537-L2540} builds the key with a STRING statement and
 * tests it at L2542. The comment at L2535 names the United States Postal Service website as the
 * data origin.</p>
 *
 * <p>The account service owns the failure message and the branch at
 * {@code app/cbl/COACTUPC.cbl:L2544-L2555}.</p>
 */
public final class UsStateZipPrefixes {

    /**
     * The 240 values listed under {@code VALID-US-STATE-ZIP-CD2-COMBO} at
     * {@code app/cpy/CSLKPCDY.cpy:L1074-L1313}, transcribed in copybook order. The set preserves
     * that order on iteration, and {@link #validCombinations} exposes it unmodifiable.
     */
    private static final Set<String> VALID_STATE_ZIP_CD2_COMBINATIONS = orderedSetOf(
            "AA34", "AE90", "AE91", "AE92", "AE93", "AE94", "AE95", "AE96",
            "AE97", "AE98", "AK99", "AL35", "AL36", "AP96", "AR71", "AR72",
            "AS96", "AZ85", "AZ86", "CA90", "CA91", "CA92", "CA93", "CA94",
            "CA95", "CA96", "CO80", "CO81", "CT60", "CT61", "CT62", "CT63",
            "CT64", "CT65", "CT66", "CT67", "CT68", "CT69", "DC20", "DC56",
            "DC88", "DE19", "FL32", "FL33", "FL34", "FM96", "GA30", "GA31",
            "GA39", "GU96", "HI96", "IA50", "IA51", "IA52", "ID83", "IL60",
            "IL61", "IL62", "IN46", "IN47", "KS66", "KS67", "KY40", "KY41",
            "KY42", "LA70", "LA71", "MA10", "MA11", "MA12", "MA13", "MA14",
            "MA15", "MA16", "MA17", "MA18", "MA19", "MA20", "MA21", "MA22",
            "MA23", "MA24", "MA25", "MA26", "MA27", "MA55", "MD20", "MD21",
            "ME39", "ME40", "ME41", "ME42", "ME43", "ME44", "ME45", "ME46",
            "ME47", "ME48", "ME49", "MH96", "MI48", "MI49", "MN55", "MN56",
            "MO63", "MO64", "MO65", "MO72", "MP96", "MS38", "MS39", "MT59",
            "NC27", "NC28", "ND58", "NE68", "NE69", "NH30", "NH31", "NH32",
            "NH33", "NH34", "NH35", "NH36", "NH37", "NH38", "NJ70", "NJ71",
            "NJ72", "NJ73", "NJ74", "NJ75", "NJ76", "NJ77", "NJ78", "NJ79",
            "NJ80", "NJ81", "NJ82", "NJ83", "NJ84", "NJ85", "NJ86", "NJ87",
            "NJ88", "NJ89", "NM87", "NM88", "NV88", "NV89", "NY50", "NY54",
            "NY63", "NY10", "NY11", "NY12", "NY13", "NY14", "OH43", "OH44",
            "OH45", "OK73", "OK74", "OR97", "PA15", "PA16", "PA17", "PA18",
            "PA19", "PR60", "PR61", "PR62", "PR63", "PR64", "PR65", "PR66",
            "PR67", "PR68", "PR69", "PR70", "PR71", "PR72", "PR73", "PR74",
            "PR75", "PR76", "PR77", "PR78", "PR79", "PR90", "PR91", "PR92",
            "PR93", "PR94", "PR95", "PR96", "PR97", "PR98", "PW96", "RI28",
            "RI29", "SC29", "SD57", "TN37", "TN38", "TX73", "TX75", "TX76",
            "TX77", "TX78", "TX79", "TX88", "UT84", "VA20", "VA22", "VA23",
            "VA24", "VI80", "VI82", "VI83", "VI84", "VI85", "VT50", "VT51",
            "VT52", "VT53", "VT54", "VT56", "VT57", "VT58", "VT59", "WA98",
            "WA99", "WI53", "WI54", "WV24", "WV25", "WV26", "WY82", "WY83");

    private UsStateZipPrefixes() {
    }

    /**
     * Collects the listed values into a set that iterates in the order they arrive.
     *
     * @param combinations the copybook values, in copybook order
     * @return an unmodifiable set holding those values in that order
     */
    private static Set<String> orderedSetOf(String... combinations) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(combinations)));
    }

    /**
     * Returns true for the 240 combinations that {@code VALID-US-STATE-ZIP-CD2-COMBO} lists at
     * {@code app/cpy/CSLKPCDY.cpy:L1074-L1313}. The COBOL source runs the same test at
     * {@code app/cbl/COACTUPC.cbl:L2542}.
     *
     * <p>The match is exact, as it is in the copybook. A null argument returns false. A value of
     * any other length returns false, and so does a lower-case value.</p>
     *
     * @param stateCodeAndFirstTwoZipDigits the two-character state code followed by the first two
     *                                      digits of the zip code, for example {@code TX75}
     * @return true when the argument is one of the 240 listed combinations
     */
    public static boolean isValidUsStateZipCd2Combo(String stateCodeAndFirstTwoZipDigits) {
        return stateCodeAndFirstTwoZipDigits != null
                && VALID_STATE_ZIP_CD2_COMBINATIONS.contains(stateCodeAndFirstTwoZipDigits);
    }

    /**
     * Returns the 240 combinations of {@code app/cpy/CSLKPCDY.cpy:L1074-L1313} as an unmodifiable
     * set that iterates in copybook order. A call to {@code add} on the returned set throws
     * {@link UnsupportedOperationException}.
     *
     * @return the 240 four-character combinations in copybook order, unmodifiable
     */
    public static Set<String> validCombinations() {
        return VALID_STATE_ZIP_CD2_COMBINATIONS;
    }
}
