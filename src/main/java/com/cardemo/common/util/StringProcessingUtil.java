package com.cardemo.common.util;

import java.util.Map;

/**
 * Translated from CSSTRPFY.cpy — CICS AID key mapping and string processing utilities.
 *
 * <p>This utility class provides:
 * <ul>
 *   <li>AID (Attention Identifier) key mapping — translates CICS DFH constant names
 *       (DFHENTER, DFHCLEAR, DFHPA1, DFHPA2, DFHPF1–DFHPF24) to {@link AidKey} enum
 *       values. This is a 1:1 translation of the EVALUATE TRUE block in CSSTRPFY.cpy
 *       paragraph YYYY-STORE-PFKEY.</li>
 *   <li>String manipulation utilities matching COBOL semantics — trim and pad operations
 *       that preserve the fixed-width field behavior of COBOL MOVE and FUNCTION TRIM.</li>
 * </ul>
 *
 * <p><strong>COBOL Traceability:</strong> {@code app/cpy/CSSTRPFY.cpy} paragraph
 * {@code YYYY-STORE-PFKEY} (lines 17–82).
 *
 * @see AidKey
 */
public final class StringProcessingUtil {

    /**
     * Maps CICS DFH constant names to their corresponding {@link AidKey} values.
     *
     * <p>This map contains 28 entries covering all standard CICS AID byte constants:
     * <ul>
     *   <li>DFHENTER, DFHCLEAR — special action keys</li>
     *   <li>DFHPA1, DFHPA2 — program attention keys</li>
     *   <li>DFHPF1–DFHPF12 — primary PF keys → PFK01–PFK12</li>
     *   <li>DFHPF13–DFHPF24 — extended PF keys, wrap-around mapped to PFK01–PFK12
     *       per the COBOL EVALUATE TRUE block in CSSTRPFY.cpy</li>
     * </ul>
     *
     * <p>Uses {@link Map#ofEntries(Map.Entry[])} because the map has 28 entries,
     * which exceeds the 10-entry limit of {@link Map#of(Object, Object)}.
     */
    private static final Map<String, AidKey> DFH_AID_MAP = Map.ofEntries(
            // Special action keys — CICS AID bytes for ENTER and CLEAR
            Map.entry("DFHENTER", AidKey.ENTER),
            Map.entry("DFHCLEAR", AidKey.CLEAR),

            // Program attention keys — CICS PA1 and PA2
            Map.entry("DFHPA1", AidKey.PA1),
            Map.entry("DFHPA2", AidKey.PA2),

            // Primary PF keys: DFHPF1–DFHPF12 → PFK01–PFK12
            Map.entry("DFHPF1", AidKey.PFK01),
            Map.entry("DFHPF2", AidKey.PFK02),
            Map.entry("DFHPF3", AidKey.PFK03),
            Map.entry("DFHPF4", AidKey.PFK04),
            Map.entry("DFHPF5", AidKey.PFK05),
            Map.entry("DFHPF6", AidKey.PFK06),
            Map.entry("DFHPF7", AidKey.PFK07),
            Map.entry("DFHPF8", AidKey.PFK08),
            Map.entry("DFHPF9", AidKey.PFK09),
            Map.entry("DFHPF10", AidKey.PFK10),
            Map.entry("DFHPF11", AidKey.PFK11),
            Map.entry("DFHPF12", AidKey.PFK12),

            // PF13-24 mapped to PFK01-12 per COBOL EVALUATE TRUE block
            // (CICS extended PF keys wrap-around: PF13→PFK01, PF14→PFK02, etc.)
            Map.entry("DFHPF13", AidKey.PFK01),
            Map.entry("DFHPF14", AidKey.PFK02),
            Map.entry("DFHPF15", AidKey.PFK03),
            Map.entry("DFHPF16", AidKey.PFK04),
            Map.entry("DFHPF17", AidKey.PFK05),
            Map.entry("DFHPF18", AidKey.PFK06),
            Map.entry("DFHPF19", AidKey.PFK07),
            Map.entry("DFHPF20", AidKey.PFK08),
            Map.entry("DFHPF21", AidKey.PFK09),
            Map.entry("DFHPF22", AidKey.PFK10),
            Map.entry("DFHPF23", AidKey.PFK11),
            Map.entry("DFHPF24", AidKey.PFK12)
    );

    /** Private constructor prevents instantiation of this utility class. */
    private StringProcessingUtil() {
        // Utility class — no instances allowed
    }

    /**
     * Maps a CICS DFH AID constant name to the corresponding {@link AidKey}.
     *
     * <p>This is the 1:1 translation of the EVALUATE TRUE block from CSSTRPFY.cpy
     * paragraph {@code YYYY-STORE-PFKEY}. The COBOL paragraph maps EIBAID values to
     * CCARD-AID-* 88-level condition flags in the COMMAREA; this method maps DFH
     * constant name strings to the equivalent Java {@link AidKey} enum values.
     *
     * <p>Notable behavior inherited from the COBOL source:
     * <ul>
     *   <li>PF keys 13–24 wrap around to PFK01–PFK12 (e.g., DFHPF13 → PFK01)</li>
     *   <li>Unrecognized constants return {@code null}</li>
     * </ul>
     *
     * @param dfhAidConstant the CICS DFH constant name (e.g., {@code "DFHENTER"},
     *                       {@code "DFHPF1"}, {@code "DFHPF13"})
     * @return the corresponding {@link AidKey}, or {@code null} if the constant is
     *         not recognized or the input is {@code null}
     */
    public static AidKey mapAidToKey(String dfhAidConstant) {
        if (dfhAidConstant == null) {
            return null;
        }
        return DFH_AID_MAP.get(dfhAidConstant);
    }

    /**
     * Removes trailing spaces from the input string.
     *
     * <p>Equivalent to COBOL {@code FUNCTION TRIM(field TRAILING)}. In COBOL,
     * fixed-width fields are right-padded with spaces; this method strips those
     * trailing spaces to produce a variable-length Java string.
     *
     * @param input the string to trim; may be {@code null}
     * @return the input with trailing spaces removed, or an empty string if
     *         input is {@code null}
     */
    public static String trimRight(String input) {
        if (input == null) {
            return "";
        }
        int end = input.length();
        while (end > 0 && input.charAt(end - 1) == ' ') {
            end--;
        }
        return input.substring(0, end);
    }

    /**
     * Removes leading spaces from the input string.
     *
     * <p>Equivalent to COBOL {@code FUNCTION TRIM(field LEADING)}. This is used
     * when processing user input that may have been left-padded in a fixed-width
     * field.
     *
     * @param input the string to trim; may be {@code null}
     * @return the input with leading spaces removed, or an empty string if
     *         input is {@code null}
     */
    public static String trimLeft(String input) {
        if (input == null) {
            return "";
        }
        int start = 0;
        while (start < input.length() && input.charAt(start) == ' ') {
            start++;
        }
        return input.substring(start);
    }

    /**
     * Right-pads the input with spaces to the specified length.
     *
     * <p>Equivalent to COBOL {@code MOVE field TO larger-field} semantics, where
     * the target field is space-filled on the right. If the input is longer than
     * the specified length, it is truncated to that length (matching COBOL
     * truncation behavior for alphanumeric MOVE).
     *
     * @param input  the string to pad; may be {@code null}
     * @param length the desired total length; must be non-negative
     * @return the right-padded (or truncated) string of exactly {@code length}
     *         characters, or a string of spaces if input is {@code null}
     * @throws IllegalArgumentException if {@code length} is negative
     */
    public static String padRight(String input, int length) {
        return padRight(input, length, ' ');
    }

    /**
     * Left-pads the input with spaces to the specified length.
     *
     * <p>Used for right-justified numeric display fields in COBOL. If the input
     * is longer than the specified length, the leftmost characters are truncated
     * (keeping the rightmost characters, matching COBOL numeric MOVE behavior).
     *
     * @param input  the string to pad; may be {@code null}
     * @param length the desired total length; must be non-negative
     * @return the left-padded (or truncated) string of exactly {@code length}
     *         characters, or a string of spaces if input is {@code null}
     * @throws IllegalArgumentException if {@code length} is negative
     */
    public static String padLeft(String input, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("Length must not be negative: " + length);
        }
        if (input == null) {
            return " ".repeat(length);
        }
        int inputLen = input.length();
        if (inputLen >= length) {
            // Truncate from the left — keep the rightmost 'length' characters
            return input.substring(inputLen - length);
        }
        // Left-pad with spaces
        return " ".repeat(length - inputLen) + input;
    }

    /**
     * Right-pads the input with the specified character to the desired length.
     *
     * <p>This overload supports custom pad characters, enabling zero-padding of
     * numeric fields (e.g., {@code padRight("42", 5, '0')} yields {@code "42000"}).
     * If the input is longer than the specified length, it is truncated to that
     * length (matching COBOL truncation behavior for alphanumeric MOVE).
     *
     * @param input   the string to pad; may be {@code null}
     * @param length  the desired total length; must be non-negative
     * @param padChar the character to use for padding
     * @return the right-padded (or truncated) string of exactly {@code length}
     *         characters, or a string of {@code padChar} if input is {@code null}
     * @throws IllegalArgumentException if {@code length} is negative
     */
    public static String padRight(String input, int length, char padChar) {
        if (length < 0) {
            throw new IllegalArgumentException("Length must not be negative: " + length);
        }
        if (input == null) {
            return String.valueOf(padChar).repeat(length);
        }
        int inputLen = input.length();
        if (inputLen >= length) {
            // Truncate to length — COBOL truncation behavior
            return input.substring(0, length);
        }
        // Right-pad with padChar
        return input + String.valueOf(padChar).repeat(length - inputLen);
    }

    /**
     * Enumerates the CICS Attention Identifier (AID) keys supported by CardDemo.
     *
     * <p>Each constant corresponds to a CCARD-AID-* 88-level condition in the
     * COBOL COMMAREA (defined in COCOM01Y.cpy). The enum faithfully reproduces
     * the AID mapping from CSSTRPFY.cpy paragraph {@code YYYY-STORE-PFKEY}.
     *
     * <p>Constants:
     * <ul>
     *   <li>{@link #ENTER}, {@link #CLEAR} — special action keys</li>
     *   <li>{@link #PA1}, {@link #PA2} — program attention keys</li>
     *   <li>{@link #PFK01} through {@link #PFK12} — program function keys
     *       (PF13–PF24 wrap around to PFK01–PFK12)</li>
     * </ul>
     */
    public enum AidKey {

        /** CICS ENTER key — DFHENTER AID byte. Maps to CCARD-AID-ENTER. */
        ENTER("ENTER"),

        /** CICS CLEAR key — DFHCLEAR AID byte. Maps to CCARD-AID-CLEAR. */
        CLEAR("CLEAR"),

        /** CICS PA1 (Program Attention 1) — DFHPA1 AID byte. Maps to CCARD-AID-PA1. */
        PA1("PA1"),

        /** CICS PA2 (Program Attention 2) — DFHPA2 AID byte. Maps to CCARD-AID-PA2. */
        PA2("PA2"),

        /** PF key 1 (also PF13 via wrap-around). Maps to CCARD-AID-PFK01. */
        PFK01("PFK01"),

        /** PF key 2 (also PF14 via wrap-around). Maps to CCARD-AID-PFK02. */
        PFK02("PFK02"),

        /** PF key 3 (also PF15 via wrap-around). Maps to CCARD-AID-PFK03. */
        PFK03("PFK03"),

        /** PF key 4 (also PF16 via wrap-around). Maps to CCARD-AID-PFK04. */
        PFK04("PFK04"),

        /** PF key 5 (also PF17 via wrap-around). Maps to CCARD-AID-PFK05. */
        PFK05("PFK05"),

        /** PF key 6 (also PF18 via wrap-around). Maps to CCARD-AID-PFK06. */
        PFK06("PFK06"),

        /** PF key 7 (also PF19 via wrap-around). Maps to CCARD-AID-PFK07. */
        PFK07("PFK07"),

        /** PF key 8 (also PF20 via wrap-around). Maps to CCARD-AID-PFK08. */
        PFK08("PFK08"),

        /** PF key 9 (also PF21 via wrap-around). Maps to CCARD-AID-PFK09. */
        PFK09("PFK09"),

        /** PF key 10 (also PF22 via wrap-around). Maps to CCARD-AID-PFK10. */
        PFK10("PFK10"),

        /** PF key 11 (also PF23 via wrap-around). Maps to CCARD-AID-PFK11. */
        PFK11("PFK11"),

        /** PF key 12 (also PF24 via wrap-around). Maps to CCARD-AID-PFK12. */
        PFK12("PFK12");

        private final String code;

        AidKey(String code) {
            this.code = code;
        }

        /**
         * Returns the string code for this AID key (e.g., {@code "ENTER"}, {@code "PFK01"}).
         *
         * <p>The code string matches the suffix of the corresponding CCARD-AID-*
         * 88-level condition name from COCOM01Y.cpy.
         *
         * @return the AID key code string
         */
        public String getCode() {
            return code;
        }
    }
}
