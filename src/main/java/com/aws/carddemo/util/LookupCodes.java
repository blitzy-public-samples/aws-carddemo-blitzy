/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */

package com.aws.carddemo.util;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable lookup-code tables transcribed verbatim from the COBOL copybook {@code CSLKPCDY.cpy} of
 * the legacy AWS CardDemo application. Each constant reproduces, byte-for-byte and in original
 * declaration order, one {@code 88}-level condition-name {@code VALUE} list from that copybook, so
 * that field-validation behavior matches the mainframe exactly for golden-file parity tests.
 *
 * <p>These tables back the same checks the COBOL account-update program (COACTUPC) performed:
 *
 * <ul>
 *   <li>phone area-code validation uses {@link #VALID_GENERAL_PURPOSE_AREA_CODES};
 *   <li>state-code validation uses {@link #VALID_US_STATE_CODES};
 *   <li>state/ZIP consistency uses {@link #VALID_US_STATE_ZIP2_COMBOS} (the state code followed by
 *       the first two digits of the ZIP code).
 * </ul>
 *
 * <p>The COBOL {@code 88}-level checks compare fixed-width fields for exact equality. Accordingly
 * the validation helpers here perform an exact membership test and intentionally do <em>not</em>
 * trim, pad, upper-case, or otherwise normalize their argument. Supplying a key of the correct
 * width (three characters for an area code, two for a state code, four for the state/ZIP combo) is
 * the caller's responsibility, mirroring the {@code PIC} clause of the corresponding COBOL field.
 *
 * <p>All five sets are unmodifiable and preserve insertion (copybook) order; any attempt to mutate
 * one throws {@link UnsupportedOperationException}.
 *
 * <p>This is a non-instantiable utility class.
 */
public final class LookupCodes {

  /**
   * Valid North American Numbering Plan area codes accepted for phone-number entry. Equal to {@link
   * #VALID_GENERAL_PURPOSE_AREA_CODES} followed by {@link #VALID_EASY_RECOGNIZABLE_AREA_CODES}, but
   * defined explicitly here for parity.
   *
   * <p>Source: {@code CSLKPCDY.cpy}, {@code 88}-level {@code VALID-PHONE-AREA-CODE} (PIC XXX),
   * copybook lines 30-520; 490 entries preserved in original declaration order.
   */
  public static final Set<String> VALID_PHONE_AREA_CODES =
      Collections.unmodifiableSet(
          new LinkedHashSet<>(
              List.of(
                  "201", "202", "203", "204", "205", "206", "207", "208", "209", "210", "212",
                  "213", "214", "215", "216", "217", "218", "219", "220", "223", "224", "225",
                  "226", "228", "229", "231", "234", "236", "239", "240", "242", "246", "248",
                  "249", "250", "251", "252", "253", "254", "256", "260", "262", "264", "267",
                  "268", "269", "270", "272", "276", "279", "281", "284", "289", "301", "302",
                  "303", "304", "305", "306", "307", "308", "309", "310", "312", "313", "314",
                  "315", "316", "317", "318", "319", "320", "321", "323", "325", "326", "330",
                  "331", "332", "334", "336", "337", "339", "340", "341", "343", "345", "346",
                  "347", "351", "352", "360", "361", "364", "365", "367", "368", "380", "385",
                  "386", "401", "402", "403", "404", "405", "406", "407", "408", "409", "410",
                  "412", "413", "414", "415", "416", "417", "418", "419", "423", "424", "425",
                  "430", "431", "432", "434", "435", "437", "438", "440", "441", "442", "443",
                  "445", "447", "448", "450", "458", "463", "464", "469", "470", "473", "474",
                  "475", "478", "479", "480", "484", "501", "502", "503", "504", "505", "506",
                  "507", "508", "509", "510", "512", "513", "514", "515", "516", "517", "518",
                  "519", "520", "530", "531", "534", "539", "540", "541", "548", "551", "559",
                  "561", "562", "563", "564", "567", "570", "571", "572", "573", "574", "575",
                  "579", "580", "581", "582", "585", "586", "587", "601", "602", "603", "604",
                  "605", "606", "607", "608", "609", "610", "612", "613", "614", "615", "616",
                  "617", "618", "619", "620", "623", "626", "628", "629", "630", "631", "636",
                  "639", "640", "641", "646", "647", "649", "650", "651", "656", "657", "658",
                  "659", "660", "661", "662", "664", "667", "669", "670", "671", "672", "678",
                  "680", "681", "682", "683", "684", "689", "701", "702", "703", "704", "705",
                  "706", "707", "708", "709", "712", "713", "714", "715", "716", "717", "718",
                  "719", "720", "721", "724", "725", "726", "727", "731", "732", "734", "737",
                  "740", "742", "743", "747", "753", "754", "757", "758", "760", "762", "763",
                  "765", "767", "769", "770", "771", "772", "773", "774", "775", "778", "779",
                  "780", "781", "782", "784", "785", "786", "787", "801", "802", "803", "804",
                  "805", "806", "807", "808", "809", "810", "812", "813", "814", "815", "816",
                  "817", "818", "819", "820", "825", "826", "828", "829", "830", "831", "832",
                  "838", "839", "840", "843", "845", "847", "848", "849", "850", "854", "856",
                  "857", "858", "859", "860", "862", "863", "864", "865", "867", "868", "869",
                  "870", "872", "873", "876", "878", "901", "902", "903", "904", "905", "906",
                  "907", "908", "909", "910", "912", "913", "914", "915", "916", "917", "918",
                  "919", "920", "925", "928", "929", "930", "931", "934", "936", "937", "938",
                  "939", "940", "941", "943", "945", "947", "948", "949", "951", "952", "954",
                  "956", "959", "970", "971", "972", "973", "978", "979", "980", "983", "984",
                  "985", "986", "989", "200", "211", "222", "233", "244", "255", "266", "277",
                  "288", "299", "300", "311", "322", "333", "344", "355", "366", "377", "388",
                  "399", "400", "411", "422", "433", "444", "455", "466", "477", "488", "499",
                  "500", "511", "522", "533", "544", "555", "566", "577", "588", "599", "600",
                  "611", "622", "633", "644", "655", "666", "677", "688", "699", "700", "711",
                  "722", "733", "744", "755", "766", "777", "788", "799", "800", "811", "822",
                  "833", "844", "855", "866", "877", "888", "899", "900", "911", "922", "933",
                  "944", "955", "966", "977", "988", "999")));

  /**
   * Valid general-purpose (geographic) North American area codes. This is the set actually used by
   * COACTUPC to validate a customer phone area code.
   *
   * <p>Source: {@code CSLKPCDY.cpy}, {@code 88}-level {@code VALID-GENERAL-PURP-CODE} (PIC XXX),
   * copybook lines 521-930; 410 entries preserved in original declaration order.
   */
  public static final Set<String> VALID_GENERAL_PURPOSE_AREA_CODES =
      Collections.unmodifiableSet(
          new LinkedHashSet<>(
              List.of(
                  "201", "202", "203", "204", "205", "206", "207", "208", "209", "210", "212",
                  "213", "214", "215", "216", "217", "218", "219", "220", "223", "224", "225",
                  "226", "228", "229", "231", "234", "236", "239", "240", "242", "246", "248",
                  "249", "250", "251", "252", "253", "254", "256", "260", "262", "264", "267",
                  "268", "269", "270", "272", "276", "279", "281", "284", "289", "301", "302",
                  "303", "304", "305", "306", "307", "308", "309", "310", "312", "313", "314",
                  "315", "316", "317", "318", "319", "320", "321", "323", "325", "326", "330",
                  "331", "332", "334", "336", "337", "339", "340", "341", "343", "345", "346",
                  "347", "351", "352", "360", "361", "364", "365", "367", "368", "380", "385",
                  "386", "401", "402", "403", "404", "405", "406", "407", "408", "409", "410",
                  "412", "413", "414", "415", "416", "417", "418", "419", "423", "424", "425",
                  "430", "431", "432", "434", "435", "437", "438", "440", "441", "442", "443",
                  "445", "447", "448", "450", "458", "463", "464", "469", "470", "473", "474",
                  "475", "478", "479", "480", "484", "501", "502", "503", "504", "505", "506",
                  "507", "508", "509", "510", "512", "513", "514", "515", "516", "517", "518",
                  "519", "520", "530", "531", "534", "539", "540", "541", "548", "551", "559",
                  "561", "562", "563", "564", "567", "570", "571", "572", "573", "574", "575",
                  "579", "580", "581", "582", "585", "586", "587", "601", "602", "603", "604",
                  "605", "606", "607", "608", "609", "610", "612", "613", "614", "615", "616",
                  "617", "618", "619", "620", "623", "626", "628", "629", "630", "631", "636",
                  "639", "640", "641", "646", "647", "649", "650", "651", "656", "657", "658",
                  "659", "660", "661", "662", "664", "667", "669", "670", "671", "672", "678",
                  "680", "681", "682", "683", "684", "689", "701", "702", "703", "704", "705",
                  "706", "707", "708", "709", "712", "713", "714", "715", "716", "717", "718",
                  "719", "720", "721", "724", "725", "726", "727", "731", "732", "734", "737",
                  "740", "742", "743", "747", "753", "754", "757", "758", "760", "762", "763",
                  "765", "767", "769", "770", "771", "772", "773", "774", "775", "778", "779",
                  "780", "781", "782", "784", "785", "786", "787", "801", "802", "803", "804",
                  "805", "806", "807", "808", "809", "810", "812", "813", "814", "815", "816",
                  "817", "818", "819", "820", "825", "826", "828", "829", "830", "831", "832",
                  "838", "839", "840", "843", "845", "847", "848", "849", "850", "854", "856",
                  "857", "858", "859", "860", "862", "863", "864", "865", "867", "868", "869",
                  "870", "872", "873", "876", "878", "901", "902", "903", "904", "905", "906",
                  "907", "908", "909", "910", "912", "913", "914", "915", "916", "917", "918",
                  "919", "920", "925", "928", "929", "930", "931", "934", "936", "937", "938",
                  "939", "940", "941", "943", "945", "947", "948", "949", "951", "952", "954",
                  "956", "959", "970", "971", "972", "973", "978", "979", "980", "983", "984",
                  "985", "986", "989")));

  /**
   * Valid easily-recognizable area codes (N11 and repeated-digit codes such as {@code 200}, {@code
   * 211}, and {@code 999}).
   *
   * <p>Source: {@code CSLKPCDY.cpy}, {@code 88}-level {@code VALID-EASY-RECOG-AREA-CODE} (PIC XXX),
   * copybook lines 931-1010; 80 entries preserved in original declaration order.
   */
  public static final Set<String> VALID_EASY_RECOGNIZABLE_AREA_CODES =
      Collections.unmodifiableSet(
          new LinkedHashSet<>(
              List.of(
                  "200", "211", "222", "233", "244", "255", "266", "277", "288", "299", "300",
                  "311", "322", "333", "344", "355", "366", "377", "388", "399", "400", "411",
                  "422", "433", "444", "455", "466", "477", "488", "499", "500", "511", "522",
                  "533", "544", "555", "566", "577", "588", "599", "600", "611", "622", "633",
                  "644", "655", "666", "677", "688", "699", "700", "711", "722", "733", "744",
                  "755", "766", "777", "788", "799", "800", "811", "822", "833", "844", "855",
                  "866", "877", "888", "899", "900", "911", "922", "933", "944", "955", "966",
                  "977", "988", "999")));

  /**
   * Valid United States state, territory, and military (APO/FPO) postal codes.
   *
   * <p>Source: {@code CSLKPCDY.cpy}, {@code 88}-level {@code VALID-US-STATE-CODE} (PIC X(2)),
   * copybook lines 1013-1069; 56 entries preserved in original declaration order.
   */
  public static final Set<String> VALID_US_STATE_CODES =
      Collections.unmodifiableSet(
          new LinkedHashSet<>(
              List.of(
                  "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA", "HI", "ID", "IL",
                  "IN", "IA", "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT",
                  "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI",
                  "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY", "DC", "AS",
                  "GU", "MP", "PR", "VI")));

  /**
   * Valid combinations of a state code and the first two digits of its ZIP code, used to confirm a
   * ZIP prefix is consistent with the entered state. Each key is the two-character state code
   * immediately followed by the two leading ZIP digits.
   *
   * <p>Source: {@code CSLKPCDY.cpy}, {@code 88}-level {@code VALID-US-STATE-ZIP-CD2-COMBO} (PIC
   * X(4)), copybook lines 1073-1313; 240 entries preserved in original declaration order.
   */
  public static final Set<String> VALID_US_STATE_ZIP2_COMBOS =
      Collections.unmodifiableSet(
          new LinkedHashSet<>(
              List.of(
                  "AA34", "AE90", "AE91", "AE92", "AE93", "AE94", "AE95", "AE96", "AE97", "AE98",
                  "AK99", "AL35", "AL36", "AP96", "AR71", "AR72", "AS96", "AZ85", "AZ86", "CA90",
                  "CA91", "CA92", "CA93", "CA94", "CA95", "CA96", "CO80", "CO81", "CT60", "CT61",
                  "CT62", "CT63", "CT64", "CT65", "CT66", "CT67", "CT68", "CT69", "DC20", "DC56",
                  "DC88", "DE19", "FL32", "FL33", "FL34", "FM96", "GA30", "GA31", "GA39", "GU96",
                  "HI96", "IA50", "IA51", "IA52", "ID83", "IL60", "IL61", "IL62", "IN46", "IN47",
                  "KS66", "KS67", "KY40", "KY41", "KY42", "LA70", "LA71", "MA10", "MA11", "MA12",
                  "MA13", "MA14", "MA15", "MA16", "MA17", "MA18", "MA19", "MA20", "MA21", "MA22",
                  "MA23", "MA24", "MA25", "MA26", "MA27", "MA55", "MD20", "MD21", "ME39", "ME40",
                  "ME41", "ME42", "ME43", "ME44", "ME45", "ME46", "ME47", "ME48", "ME49", "MH96",
                  "MI48", "MI49", "MN55", "MN56", "MO63", "MO64", "MO65", "MO72", "MP96", "MS38",
                  "MS39", "MT59", "NC27", "NC28", "ND58", "NE68", "NE69", "NH30", "NH31", "NH32",
                  "NH33", "NH34", "NH35", "NH36", "NH37", "NH38", "NJ70", "NJ71", "NJ72", "NJ73",
                  "NJ74", "NJ75", "NJ76", "NJ77", "NJ78", "NJ79", "NJ80", "NJ81", "NJ82", "NJ83",
                  "NJ84", "NJ85", "NJ86", "NJ87", "NJ88", "NJ89", "NM87", "NM88", "NV88", "NV89",
                  "NY50", "NY54", "NY63", "NY10", "NY11", "NY12", "NY13", "NY14", "OH43", "OH44",
                  "OH45", "OK73", "OK74", "OR97", "PA15", "PA16", "PA17", "PA18", "PA19", "PR60",
                  "PR61", "PR62", "PR63", "PR64", "PR65", "PR66", "PR67", "PR68", "PR69", "PR70",
                  "PR71", "PR72", "PR73", "PR74", "PR75", "PR76", "PR77", "PR78", "PR79", "PR90",
                  "PR91", "PR92", "PR93", "PR94", "PR95", "PR96", "PR97", "PR98", "PW96", "RI28",
                  "RI29", "SC29", "SD57", "TN37", "TN38", "TX73", "TX75", "TX76", "TX77", "TX78",
                  "TX79", "TX88", "UT84", "VA20", "VA22", "VA23", "VA24", "VI80", "VI82", "VI83",
                  "VI84", "VI85", "VT50", "VT51", "VT52", "VT53", "VT54", "VT56", "VT57", "VT58",
                  "VT59", "WA98", "WA99", "WI53", "WI54", "WV24", "WV25", "WV26", "WY82", "WY83")));

  private LookupCodes() {
    // utility class
    throw new AssertionError();
  }

  /**
   * Tests whether the supplied area code is a valid phone area code.
   *
   * <p>Membership is an exact match: the argument is not trimmed, padded, or case-folded, mirroring
   * the fixed-width COBOL {@code 88}-level comparison. A {@code null} argument yields {@code
   * false}.
   *
   * @param code the 3-character area code to validate (caller-supplied exact width)
   * @return {@code true} if {@code code} is a member of {@link #VALID_PHONE_AREA_CODES}, otherwise
   *     {@code false}
   */
  public static boolean isValidPhoneAreaCode(String code) {
    return code != null && VALID_PHONE_AREA_CODES.contains(code);
  }

  /**
   * Tests whether the supplied area code is a valid general-purpose (geographic) area code. This is
   * the check COACTUPC applies to a customer phone area code.
   *
   * <p>Membership is an exact match: the argument is not trimmed, padded, or case-folded, mirroring
   * the fixed-width COBOL {@code 88}-level comparison. A {@code null} argument yields {@code
   * false}.
   *
   * @param code the 3-character area code to validate (caller-supplied exact width)
   * @return {@code true} if {@code code} is a member of {@link #VALID_GENERAL_PURPOSE_AREA_CODES},
   *     otherwise {@code false}
   */
  public static boolean isValidGeneralPurposeAreaCode(String code) {
    return code != null && VALID_GENERAL_PURPOSE_AREA_CODES.contains(code);
  }

  /**
   * Tests whether the supplied area code is a valid easily-recognizable area code.
   *
   * <p>Membership is an exact match: the argument is not trimmed, padded, or case-folded, mirroring
   * the fixed-width COBOL {@code 88}-level comparison. A {@code null} argument yields {@code
   * false}.
   *
   * @param code the 3-character area code to validate (caller-supplied exact width)
   * @return {@code true} if {@code code} is a member of {@link
   *     #VALID_EASY_RECOGNIZABLE_AREA_CODES}, otherwise {@code false}
   */
  public static boolean isValidEasyRecognizableAreaCode(String code) {
    return code != null && VALID_EASY_RECOGNIZABLE_AREA_CODES.contains(code);
  }

  /**
   * Tests whether the supplied code is a valid US state/territory code.
   *
   * <p>Membership is an exact match: the argument is not trimmed, padded, or case-folded, mirroring
   * the fixed-width COBOL {@code 88}-level comparison. A {@code null} argument yields {@code
   * false}.
   *
   * @param code the 2-character state code to validate (caller-supplied exact width)
   * @return {@code true} if {@code code} is a member of {@link #VALID_US_STATE_CODES}, otherwise
   *     {@code false}
   */
  public static boolean isValidUsStateCode(String code) {
    return code != null && VALID_US_STATE_CODES.contains(code);
  }

  /**
   * Tests whether the supplied state-plus-ZIP-prefix key is a valid state/ZIP combination.
   *
   * <p>Membership is an exact match: the argument is not trimmed, padded, or case-folded, mirroring
   * the fixed-width COBOL {@code 88}-level comparison. A {@code null} argument yields {@code
   * false}.
   *
   * @param statePlusFirstTwoZip the 4-character key formed by the 2-character state code followed
   *     by the first two digits of the ZIP code (caller-supplied exact width)
   * @return {@code true} if {@code statePlusFirstTwoZip} is a member of {@link
   *     #VALID_US_STATE_ZIP2_COMBOS}, otherwise {@code false}
   */
  public static boolean isValidStateZip2Combo(String statePlusFirstTwoZip) {
    return statePlusFirstTwoZip != null
        && VALID_US_STATE_ZIP2_COMBOS.contains(statePlusFirstTwoZip);
  }
}
