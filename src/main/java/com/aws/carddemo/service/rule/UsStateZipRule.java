/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.service.rule;

import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Cross-field state/zip-code edit rule &mdash; the Java re-platform of the CardDemo COBOL
 * edit paragraph {@code 1280-EDIT-US-STATE-ZIP-CD} (source {@code legacy/cbl/COACTUPC.cbl},
 * formerly {@code app/cbl/COACTUPC.cbl}, at {@code L2536-L2558}), together with the
 * {@code VALID-US-STATE-ZIP-CD2-COMBO} lookup set it consults (from
 * {@code legacy/cpy/CSLKPCDY.cpy}, formerly {@code app/cpy/CSLKPCDY.cpy}, at
 * {@code L1073-L1313}). It reproduces, with no feature expansion, the caller-visible outcome
 * of that "crude zip code edit based on data from the USPS web site". Detailed rationale lives
 * in the decision log.
 *
 * <h2>Legacy contract reproduced</h2>
 * The COBOL paragraph builds a four-character key by concatenating the two-character state code
 * with the <em>first two</em> characters of the ten-character zip code, then tests that key
 * against the {@code VALID-US-STATE-ZIP-CD2-COMBO} 88-level condition:
 * <pre>
 *     STRING ACUP-NEW-CUST-ADDR-STATE-CD
 *            ACUP-NEW-CUST-ADDR-ZIP(1:2)
 *       DELIMITED BY SIZE
 *       INTO US-STATE-AND-FIRST-ZIP2
 *     IF VALID-US-STATE-ZIP-CD2-COMBO
 *         CONTINUE
 *     ELSE
 *         SET INPUT-ERROR        TO TRUE
 *         SET FLG-STATE-NOT-OK   TO TRUE
 *         SET FLG-ZIPCODE-NOT-OK TO TRUE
 *         IF WS-RETURN-MSG-OFF
 *            STRING 'Invalid zip code for state' ... INTO WS-RETURN-MSG
 *         END-IF
 *     END-IF
 * </pre>
 * {@code ACUP-NEW-CUST-ADDR-STATE-CD} is {@code PIC X(02)} ({@code legacy/cbl/COACTUPC.cbl:L807})
 * so the state code is exactly two characters; {@code ACUP-NEW-CUST-ADDR-ZIP} is {@code PIC X(10)}
 * ({@code L809}) and only its first two characters ({@code (1:2)}) participate in the key. When
 * the assembled key is <em>not</em> a member of the valid set, the paragraph raises the input
 * error and latches the fixed screen message {@code "Invalid zip code for state"} &mdash; a
 * literal with no field-label prefix and no trailing period.
 *
 * <h2>Cross-field placement</h2>
 * This is a composite (multi-argument) edit: it validates the state code and the zip code
 * <em>together</em>. In the legacy flow it runs after the individual state-code edit
 * ({@code 1270-EDIT-US-STATE-CD}) and the zip edits have already executed
 * ({@code PERFORM 1280-EDIT-US-STATE-ZIP-CD} at {@code legacy/cbl/COACTUPC.cbl:L1667-L1668}),
 * so by the time it runs the state is a valid two-character code and the zip has at least two
 * characters. Because its signature accepts two fields rather than a single
 * {@code (fieldName, value)} pair, it deliberately does <strong>not</strong> implement
 * {@link ValidationRule} (whose contract documents this composite family explicitly); it simply
 * returns a {@link ValidationResult}. The operative check is the four-character combination set,
 * which subsumes state validity for these prefixes, so this rule is independent of
 * {@code UsStateCodeRule} and injects nothing.
 *
 * <h2>First-message-wins latching</h2>
 * The COBOL {@code IF WS-RETURN-MSG-OFF} guard that latches only the first screen message is the
 * responsibility of the calling service (which inspects a sequence of {@link ValidationResult}s
 * and keeps the first invalid one), exactly as described on {@link ValidationResult}. This rule
 * therefore always returns the fixed message on failure and never performs the latching itself.
 *
 * <p>The component is stateless, side-effect free, and thread-safe (its only state is the
 * immutable {@link #VALID_STATE_ZIP2_COMBOS} set), so it is safe to share as a singleton Spring
 * bean. It never throws to signal a validation failure: null or too-short inputs are treated as
 * invalid and yield the same fixed message, so the rule can be called defensively without a
 * surrounding guard.</p>
 */
@Component
public final class UsStateZipRule {

    /**
     * The fixed screen message raised when the state/zip combination is not recognized &mdash;
     * the verbatim COBOL literal {@code 'Invalid zip code for state'} latched into
     * {@code WS-RETURN-MSG} at {@code legacy/cbl/COACTUPC.cbl:L2550}. It carries no field-label
     * prefix and no trailing period, exactly as in the legacy program.
     */
    private static final String INVALID_MESSAGE = "Invalid zip code for state";

    /**
     * The set of valid two-character-state + first-two-zip-digit combinations &mdash; the Java
     * form of the COBOL {@code VALID-US-STATE-ZIP-CD2-COMBO} 88-level condition
     * ({@code legacy/cpy/CSLKPCDY.cpy:L1073-L1313}). All 240 entries are reproduced verbatim, in
     * copybook order, and every key is upper-case (both the state codes and the seeded
     * combinations are upper-case, so the assembled key is compared without case folding). The
     * immutable {@link Set#of(Object...)} view gives constant-time membership testing and rejects
     * accidental duplicates at class-load time.
     */
    private static final Set<String> VALID_STATE_ZIP2_COMBOS = Set.of(
        "AA34", "AE90", "AE91", "AE92", "AE93", "AE94", "AE95", "AE96", "AE97", "AE98", "AK99", "AL35", "AL36", "AP96", "AR71", "AR72", "AS96",
        "AZ85", "AZ86", "CA90", "CA91", "CA92", "CA93", "CA94", "CA95", "CA96", "CO80", "CO81", "CT60", "CT61", "CT62", "CT63", "CT64", "CT65",
        "CT66", "CT67", "CT68", "CT69", "DC20", "DC56", "DC88", "DE19", "FL32", "FL33", "FL34", "FM96", "GA30", "GA31", "GA39", "GU96", "HI96",
        "IA50", "IA51", "IA52", "ID83", "IL60", "IL61", "IL62", "IN46", "IN47", "KS66", "KS67", "KY40", "KY41", "KY42", "LA70", "LA71", "MA10",
        "MA11", "MA12", "MA13", "MA14", "MA15", "MA16", "MA17", "MA18", "MA19", "MA20", "MA21", "MA22", "MA23", "MA24", "MA25", "MA26", "MA27",
        "MA55", "MD20", "MD21", "ME39", "ME40", "ME41", "ME42", "ME43", "ME44", "ME45", "ME46", "ME47", "ME48", "ME49", "MH96", "MI48", "MI49",
        "MN55", "MN56", "MO63", "MO64", "MO65", "MO72", "MP96", "MS38", "MS39", "MT59", "NC27", "NC28", "ND58", "NE68", "NE69", "NH30", "NH31",
        "NH32", "NH33", "NH34", "NH35", "NH36", "NH37", "NH38", "NJ70", "NJ71", "NJ72", "NJ73", "NJ74", "NJ75", "NJ76", "NJ77", "NJ78", "NJ79",
        "NJ80", "NJ81", "NJ82", "NJ83", "NJ84", "NJ85", "NJ86", "NJ87", "NJ88", "NJ89", "NM87", "NM88", "NV88", "NV89", "NY50", "NY54", "NY63",
        "NY10", "NY11", "NY12", "NY13", "NY14", "OH43", "OH44", "OH45", "OK73", "OK74", "OR97", "PA15", "PA16", "PA17", "PA18", "PA19", "PR60",
        "PR61", "PR62", "PR63", "PR64", "PR65", "PR66", "PR67", "PR68", "PR69", "PR70", "PR71", "PR72", "PR73", "PR74", "PR75", "PR76", "PR77",
        "PR78", "PR79", "PR90", "PR91", "PR92", "PR93", "PR94", "PR95", "PR96", "PR97", "PR98", "PW96", "RI28", "RI29", "SC29", "SD57", "TN37",
        "TN38", "TX73", "TX75", "TX76", "TX77", "TX78", "TX79", "TX88", "UT84", "VA20", "VA22", "VA23", "VA24", "VI80", "VI82", "VI83", "VI84",
        "VI85", "VT50", "VT51", "VT52", "VT53", "VT54", "VT56", "VT57", "VT58", "VT59", "WA98", "WA99", "WI53", "WI54", "WV24", "WV25", "WV26",
        "WY82", "WY83");

    /**
     * Creates the stateless rule. Spring instantiates a single shared bean through this public
     * no-argument constructor; the rule has no injected collaborators (the four-character
     * combination set it consults is a private, immutable class constant).
     */
    public UsStateZipRule() {
        // No dependencies to inject; the rule is stateless and thread-safe.
    }

    /**
     * Validates a state code together with a zip code, reproducing COBOL paragraph
     * {@code 1280-EDIT-US-STATE-ZIP-CD}.
     *
     * <p>The four-character lookup key is the two-character state code concatenated with the
     * first two characters of the zip code, mirroring the COBOL
     * {@code STRING state ZIP(1:2) DELIMITED BY SIZE INTO US-STATE-AND-FIRST-ZIP2}. The legacy
     * program reads the fixed-width fields directly (state is {@code PIC X(02)}, {@code ZIP(1:2)}
     * is the first two positions), so taking {@link String#substring(int, int) substring(0, 2)}
     * of the stripped inputs reproduces that. The comparison is case-sensitive against the
     * upper-case seed set; the inputs are not lower-cased.</p>
     *
     * <p>Although the legacy paragraph runs only after the state and zip have individually
     * passed their own edits (so both are well-formed by then), this method guards defensively:
     * a {@code null} input, or a state or zip whose stripped length is fewer than two characters,
     * is treated as an invalid combination and yields the same fixed message rather than throwing.
     * This lets the rule be invoked safely in any order.</p>
     *
     * @param stateCode the two-character US state/territory code (COBOL
     *                  {@code ACUP-NEW-CUST-ADDR-STATE-CD}); may be {@code null} or short, in
     *                  which case the result is invalid
     * @param zipCode   the zip code whose first two characters are used (COBOL
     *                  {@code ACUP-NEW-CUST-ADDR-ZIP}); may be {@code null} or short, in which
     *                  case the result is invalid
     * @return {@link ValidationResult#valid()} when the assembled state/zip key is a recognized
     *         combination; otherwise {@link ValidationResult#invalid(String)} carrying the fixed
     *         message {@code "Invalid zip code for state"}
     */
    public ValidationResult validate(String stateCode, String zipCode) {
        if (stateCode == null || zipCode == null
                || stateCode.strip().length() < 2 || zipCode.strip().length() < 2) {
            return ValidationResult.invalid(INVALID_MESSAGE);
        }
        String key = stateCode.strip().substring(0, 2) + zipCode.strip().substring(0, 2);
        if (!VALID_STATE_ZIP2_COMBOS.contains(key)) {
            return ValidationResult.invalid(INVALID_MESSAGE);
        }
        return ValidationResult.valid();
    }
}
