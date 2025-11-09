package com.carddemo.constants;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Lookup code repository defining validation enums for North American phone area codes,
 * US state codes, and state-zip code combination validation patterns.
 * 
 * This class replaces COBOL 88-level condition validations from CSLKPCDY.cpy copybook,
 * providing efficient O(1) lookup performance using HashSet and HashMap data structures.
 * 
 * <p>Data Sources:</p>
 * <ul>
 *   <li>North American phone area codes: North American Numbering Plan Administrator (NANPA)
 *       https://nationalnanpa.com/nanp1/npa_report.csv</li>
 *   <li>US state codes: Official USPS two-letter state abbreviations</li>
 *   <li>State-ZIP combinations: USPS ZIP code assignment patterns</li>
 * </ul>
 * 
 * <p>Performance Characteristics:</p>
 * All validation methods provide O(1) average time complexity for lookups,
 * matching the efficiency of COBOL 88-level condition evaluations.
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
public final class LookupCode {

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private LookupCode() {
        throw new UnsupportedOperationException("Utility class - do not instantiate");
    }

    /**
     * Immutable set of valid North American phone area codes.
     * Contains 440+ valid area codes from NANPA registry including both
     * geographic and easily recognizable codes.
     * 
     * <p>This set is immutable and thread-safe.</p>
     */
    public static final Set<String> VALID_PHONE_AREA_CODES;

    /**
     * Immutable set of valid US state codes.
     * Contains all 50 US states, District of Columbia (DC), and US territories
     * (AS, GU, MP, PR, VI) using official USPS two-letter abbreviations.
     * 
     * <p>This set is immutable and thread-safe.</p>
     */
    public static final Set<String> VALID_STATE_CODES;

    /**
     * Immutable map of valid state-ZIP code prefix combinations.
     * Maps each state code to a set of valid two-digit ZIP code prefixes
     * for that state, enabling efficient validation of state-ZIP combinations.
     * 
     * <p>This map is immutable and thread-safe.</p>
     */
    public static final Map<String, Set<String>> VALID_STATE_ZIP_COMBOS;

    static {
        // Initialize phone area codes (440+ valid codes from NANPA)
        Set<String> areaCodes = new HashSet<>();
        
        // General purpose area codes
        areaCodes.addAll(Arrays.asList(
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
            "401", "402", "403", "404", "405", "406", "407", "408", "409", "410"
        ));
        
        areaCodes.addAll(Arrays.asList(
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
        ));
        
        // Easily recognizable area codes (reserved/special purpose)
        areaCodes.addAll(Arrays.asList(
            "200", "211", "222", "233", "244", "255", "266", "277", "288", "299",
            "300", "311", "322", "333", "344", "355", "366", "377", "388", "399",
            "400", "411", "422", "433", "444", "455", "466", "477", "488", "499",
            "500", "511", "522", "533", "544", "555", "566", "577", "588", "599",
            "600", "611", "622", "633", "644", "655", "666", "677", "688", "699",
            "700", "711", "722", "733", "744", "755", "766", "777", "788", "799",
            "800", "811", "822", "833", "844", "855", "866", "877", "888", "899",
            "900", "911", "922", "933", "944", "955", "966", "977", "988", "999"
        ));
        
        VALID_PHONE_AREA_CODES = Collections.unmodifiableSet(areaCodes);
        
        // Initialize US state codes (50 states + DC + 5 territories)
        Set<String> stateCodes = new HashSet<>();
        stateCodes.addAll(Arrays.asList(
            // 50 US States
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA",
            "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
            "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ",
            "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
            "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY",
            // District of Columbia
            "DC",
            // US Territories
            "AS", "GU", "MP", "PR", "VI"
        ));
        
        VALID_STATE_CODES = Collections.unmodifiableSet(stateCodes);
        
        // Initialize state-ZIP code prefix combinations (240+ valid combinations)
        Map<String, Set<String>> stateZipMap = new HashMap<>();
        
        // Helper method to add state-zip combinations
        addStateZipCombo(stateZipMap, "AA", "34");
        addStateZipCombo(stateZipMap, "AE", "90", "91", "92", "93", "94", "95", "96", "97", "98");
        addStateZipCombo(stateZipMap, "AK", "99");
        addStateZipCombo(stateZipMap, "AL", "35", "36");
        addStateZipCombo(stateZipMap, "AP", "96");
        addStateZipCombo(stateZipMap, "AR", "71", "72");
        addStateZipCombo(stateZipMap, "AS", "96");
        addStateZipCombo(stateZipMap, "AZ", "85", "86");
        addStateZipCombo(stateZipMap, "CA", "90", "91", "92", "93", "94", "95", "96");
        addStateZipCombo(stateZipMap, "CO", "80", "81");
        addStateZipCombo(stateZipMap, "CT", "60", "61", "62", "63", "64", "65", "66", "67", "68", "69");
        addStateZipCombo(stateZipMap, "DC", "20", "56", "88");
        addStateZipCombo(stateZipMap, "DE", "19");
        addStateZipCombo(stateZipMap, "FL", "32", "33", "34");
        addStateZipCombo(stateZipMap, "FM", "96");
        addStateZipCombo(stateZipMap, "GA", "30", "31", "39");
        addStateZipCombo(stateZipMap, "GU", "96");
        addStateZipCombo(stateZipMap, "HI", "96");
        addStateZipCombo(stateZipMap, "IA", "50", "51", "52");
        addStateZipCombo(stateZipMap, "ID", "83");
        addStateZipCombo(stateZipMap, "IL", "60", "61", "62");
        addStateZipCombo(stateZipMap, "IN", "46", "47");
        addStateZipCombo(stateZipMap, "KS", "66", "67");
        addStateZipCombo(stateZipMap, "KY", "40", "41", "42");
        addStateZipCombo(stateZipMap, "LA", "70", "71");
        addStateZipCombo(stateZipMap, "MA", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19",
                                            "20", "21", "22", "23", "24", "25", "26", "27", "55");
        addStateZipCombo(stateZipMap, "MD", "20", "21");
        addStateZipCombo(stateZipMap, "ME", "39", "40", "41", "42", "43", "44", "45", "46", "47", "48", "49");
        addStateZipCombo(stateZipMap, "MH", "96");
        addStateZipCombo(stateZipMap, "MI", "48", "49");
        addStateZipCombo(stateZipMap, "MN", "55", "56");
        addStateZipCombo(stateZipMap, "MO", "63", "64", "65", "72");
        addStateZipCombo(stateZipMap, "MP", "96");
        addStateZipCombo(stateZipMap, "MS", "38", "39");
        addStateZipCombo(stateZipMap, "MT", "59");
        addStateZipCombo(stateZipMap, "NC", "27", "28");
        addStateZipCombo(stateZipMap, "ND", "58");
        addStateZipCombo(stateZipMap, "NE", "68", "69");
        addStateZipCombo(stateZipMap, "NH", "30", "31", "32", "33", "34", "35", "36", "37", "38");
        addStateZipCombo(stateZipMap, "NJ", "70", "71", "72", "73", "74", "75", "76", "77", "78", "79",
                                            "80", "81", "82", "83", "84", "85", "86", "87", "88", "89");
        addStateZipCombo(stateZipMap, "NM", "87", "88");
        addStateZipCombo(stateZipMap, "NV", "88", "89");
        addStateZipCombo(stateZipMap, "NY", "50", "54", "63", "10", "11", "12", "13", "14");
        addStateZipCombo(stateZipMap, "OH", "43", "44", "45");
        addStateZipCombo(stateZipMap, "OK", "73", "74");
        addStateZipCombo(stateZipMap, "OR", "97");
        addStateZipCombo(stateZipMap, "PA", "15", "16", "17", "18", "19");
        addStateZipCombo(stateZipMap, "PR", "60", "61", "62", "63", "64", "65", "66", "67", "68", "69",
                                            "70", "71", "72", "73", "74", "75", "76", "77", "78", "79",
                                            "90", "91", "92", "93", "94", "95", "96", "97", "98");
        addStateZipCombo(stateZipMap, "PW", "96");
        addStateZipCombo(stateZipMap, "RI", "28", "29");
        addStateZipCombo(stateZipMap, "SC", "29");
        addStateZipCombo(stateZipMap, "SD", "57");
        addStateZipCombo(stateZipMap, "TN", "37", "38");
        addStateZipCombo(stateZipMap, "TX", "73", "75", "76", "77", "78", "79", "88");
        addStateZipCombo(stateZipMap, "UT", "84");
        addStateZipCombo(stateZipMap, "VA", "20", "22", "23", "24");
        addStateZipCombo(stateZipMap, "VI", "80", "82", "83", "84", "85");
        addStateZipCombo(stateZipMap, "VT", "50", "51", "52", "53", "54", "56", "57", "58", "59");
        addStateZipCombo(stateZipMap, "WA", "98", "99");
        addStateZipCombo(stateZipMap, "WI", "53", "54");
        addStateZipCombo(stateZipMap, "WV", "24", "25", "26");
        addStateZipCombo(stateZipMap, "WY", "82", "83");
        
        // Make the map immutable
        Map<String, Set<String>> immutableMap = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : stateZipMap.entrySet()) {
            immutableMap.put(entry.getKey(), Collections.unmodifiableSet(entry.getValue()));
        }
        VALID_STATE_ZIP_COMBOS = Collections.unmodifiableMap(immutableMap);
    }
    
    /**
     * Helper method to add state-ZIP prefix combinations to the map.
     * 
     * @param map The map to add combinations to
     * @param stateCode The two-letter state code
     * @param zipPrefixes Variable number of two-digit ZIP code prefixes
     */
    private static void addStateZipCombo(Map<String, Set<String>> map, String stateCode, String... zipPrefixes) {
        Set<String> zipSet = map.computeIfAbsent(stateCode, k -> new HashSet<>());
        zipSet.addAll(Arrays.asList(zipPrefixes));
    }
    
    /**
     * Validates if a given phone area code is valid according to NANPA standards.
     * 
     * <p>This method provides O(1) average time complexity for validation,
     * equivalent to COBOL 88-level condition evaluation.</p>
     * 
     * @param areaCode The three-digit phone area code to validate (e.g., "201", "555")
     * @return true if the area code is valid according to NANPA registry, false otherwise
     * @throws IllegalArgumentException if areaCode is null
     */
    public static boolean isValidPhoneAreaCode(String areaCode) {
        if (areaCode == null) {
            throw new IllegalArgumentException("Area code cannot be null");
        }
        return VALID_PHONE_AREA_CODES.contains(areaCode);
    }
    
    /**
     * Validates if a given US state code is valid.
     * 
     * <p>This method provides O(1) average time complexity for validation,
     * equivalent to COBOL 88-level condition evaluation.</p>
     * 
     * <p>Validates against all 50 US states, District of Columbia, and
     * US territories (AS, GU, MP, PR, VI) using official USPS abbreviations.</p>
     * 
     * @param stateCode The two-letter state code to validate (e.g., "CA", "NY", "PR")
     * @return true if the state code is valid, false otherwise
     * @throws IllegalArgumentException if stateCode is null
     */
    public static boolean isValidStateCode(String stateCode) {
        if (stateCode == null) {
            throw new IllegalArgumentException("State code cannot be null");
        }
        return VALID_STATE_CODES.contains(stateCode.toUpperCase());
    }
    
    /**
     * Validates if a given state code and ZIP code prefix combination is valid.
     * 
     * <p>This method provides O(1) average time complexity for validation,
     * equivalent to COBOL 88-level condition evaluation.</p>
     * 
     * <p>Validates that the two-digit ZIP code prefix is valid for the given state
     * according to USPS ZIP code assignment patterns.</p>
     * 
     * @param stateCode The two-letter state code (e.g., "CA", "NY")
     * @param zipPrefix The first two digits of the ZIP code (e.g., "90", "10")
     * @return true if the state-ZIP combination is valid, false otherwise
     * @throws IllegalArgumentException if stateCode or zipPrefix is null
     */
    public static boolean isValidStateZipCombo(String stateCode, String zipPrefix) {
        if (stateCode == null) {
            throw new IllegalArgumentException("State code cannot be null");
        }
        if (zipPrefix == null) {
            throw new IllegalArgumentException("ZIP prefix cannot be null");
        }
        
        String normalizedStateCode = stateCode.toUpperCase();
        Set<String> validZipPrefixes = VALID_STATE_ZIP_COMBOS.get(normalizedStateCode);
        
        if (validZipPrefixes == null) {
            return false;
        }
        
        return validZipPrefixes.contains(zipPrefix);
    }
}

