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

import com.aws.carddemo.dto.CardWorkArea.PfKey;
import java.util.Locale;

/**
 * Origin: legacy/cpy/CSSTRPFY.cpy ({@code YYYY-STORE-PFKEY}) &mdash; CICS attention-identifier
 * (AID) to program-function-key mapper.
 *
 * <p>The COBOL copybook {@code CSSTRPFY} is a procedure fragment {@code COPY}'d near the top of
 * the pseudo-conversational online programs. It runs a single {@code EVALUATE TRUE} over the CICS
 * execute-interface AID field {@code EIBAID} and, for each recognized 3270 key, sets the matching
 * {@code CCARD-AID-*} condition-name (88-level) held in the {@code CC-WORK-AREAS} structure
 * ({@code CVCRD01Y}). It is the mechanism by which every screen program learns which key the
 * operator pressed (ENTER, CLEAR, PA1/PA2, or PF1&ndash;PF24) so it can drive navigation.</p>
 *
 * <p>In the Spring Boot migration the 3270 keyboard AID no longer arrives in {@code EIBAID};
 * instead the web tier reports which button / PF-key the user activated. This utility centralizes
 * the translation from a CICS AID mnemonic (for example {@code "DFHENTER"} or {@code "DFHPF7"}) to
 * the shared {@link PfKey} enumeration, so that every controller and service resolves PF-keys
 * identically and reproduces the COBOL {@code EVALUATE EIBAID} exactly. It implements the PF-key
 * handling item in AAP &sect;0.4.1 ({@code util/PfKeyHandler} &larr; {@code CSSTRPFY.cpy}) and
 * supports the BMS interaction contract described in AAP &sect;0.3.4 (PF3 = back, PF7 = page up,
 * PF8 = page down, ENTER = submit).</p>
 *
 * <p><strong>Verified AID mapping (preserved one-for-one from {@code CSSTRPFY}).</strong></p>
 * <ul>
 *   <li>{@code DFHENTER} &rarr; {@link PfKey#ENTER}</li>
 *   <li>{@code DFHCLEAR} &rarr; {@link PfKey#CLEAR}</li>
 *   <li>{@code DFHPA1} &rarr; {@link PfKey#PA1}; {@code DFHPA2} &rarr; {@link PfKey#PA2}</li>
 *   <li>{@code DFHPF1}&ndash;{@code DFHPF12} &rarr; {@link PfKey#PFK01}&ndash;{@link PfKey#PFK12}
 *       (one-to-one)</li>
 *   <li>{@code DFHPF13}&ndash;{@code DFHPF24} &rarr; {@link PfKey#PFK01}&ndash;{@link PfKey#PFK12}
 *       (<em>wrap</em>: PF13&rarr;PFK01, PF14&rarr;PFK02, &hellip;, PF24&rarr;PFK12)</li>
 * </ul>
 *
 * <p><strong>Wrap quirk is intentional.</strong> The legacy {@code EVALUATE} deliberately folds
 * the second bank of function keys (PF13&ndash;PF24) back onto PFK01&ndash;PFK12. This behavior is
 * reproduced verbatim and is <em>not</em> "corrected" &mdash; the migration preserves behavior,
 * including quirks, exactly (AAP &sect;0.2.2, no behavioral changes).</p>
 *
 * <p><strong>Unmatched input.</strong> The COBOL {@code EVALUATE} has no {@code WHEN OTHER} branch,
 * so an unrecognized AID simply leaves every {@code CCARD-AID-*} condition {@code false}. Here that
 * "invalid key" path is represented by returning {@link PfKey#OTHER}: {@code null}, blank and
 * unrecognized tokens all yield {@link PfKey#OTHER} and this class never throws for such input, so
 * callers can treat {@link PfKey#OTHER} like the COBOL unset-indicator case.</p>
 *
 * <p><strong>Enum ownership.</strong> Per AAP &sect;0.4.1 the PF-key enumeration is owned by
 * {@code com.aws.carddemo.dto.CardWorkArea} (the migration of {@code CVCRD01Y.cpy}'s
 * {@code CC-WORK-AREAS}, whose {@code CCARD-AID} 88-levels are the constant set reproduced by
 * {@link PfKey}). This utility imports and returns that shared type
 * ({@code com.aws.carddemo.dto.CardWorkArea.PfKey}); a package-local fallback enum was specified as
 * a contingency should the shared type be unresolvable, but it is intentionally unnecessary because
 * the shared enum resolves cleanly. The {@code util} &rarr; {@code dto} reference introduces no
 * dependency cycle, since {@code dto} does not depend on {@code util} for this type.</p>
 *
 * <p>This is a stateless, side-effect-free utility mirroring a stateless COBOL procedure fragment;
 * it is never instantiated and needs no Spring wiring, so it can be called from any layer.</p>
 */
public final class PfKeyHandler {

    /**
     * Prefix shared by every CICS program-function-key AID mnemonic
     * ({@code DFHPF1}&hellip;{@code DFHPF24}).
     */
    private static final String DFHPF_PREFIX = "DFHPF";

    /** Lowest program-function-key number recognized by {@code CSSTRPFY} ({@code DFHPF1}). */
    private static final int MIN_PF_NUMBER = 1;

    /** Highest program-function-key number recognized by {@code CSSTRPFY} ({@code DFHPF24}). */
    private static final int MAX_PF_NUMBER = 24;

    /**
     * Number of distinct PF-key indicators ({@link PfKey#PFK01}&ndash;{@link PfKey#PFK12}). The
     * second key bank (PF13&ndash;PF24) wraps modulo this value onto the first bank.
     */
    private static final int PF_KEYS_PER_BANK = 12;

    /**
     * Zero-based lookup of the twelve PF-key indicators in ascending order, so that
     * {@code PFK_BY_INDEX[k]} is {@code PFKnn} for {@code k = nn - 1}. Used by
     * {@link #fromPfNumber(int)} to resolve both the one-to-one (PF1&ndash;PF12) and the wrapped
     * (PF13&ndash;PF24) cases with a single modulo index. The array is {@code private} and never
     * exposed, so its mutability is not observable outside this class.
     */
    private static final PfKey[] PFK_BY_INDEX = {
        PfKey.PFK01, PfKey.PFK02, PfKey.PFK03, PfKey.PFK04,
        PfKey.PFK05, PfKey.PFK06, PfKey.PFK07, PfKey.PFK08,
        PfKey.PFK09, PfKey.PFK10, PfKey.PFK11, PfKey.PFK12
    };

    /**
     * Non-instantiable utility class.
     *
     * @throws AssertionError always; this class exposes only static helpers.
     */
    private PfKeyHandler() {
        throw new AssertionError("No instances");
    }

    /**
     * Maps a CICS attention-identifier mnemonic to its {@link PfKey}, reproducing the
     * {@code EVALUATE EIBAID} of {@code CSSTRPFY}.
     *
     * <p>The lookup is case-insensitive (the token is upper-cased with {@link Locale#ROOT} so the
     * result never varies with the default locale) and tolerant of surrounding blanks. The four
     * fixed keys are matched directly; any {@code DFHPFnn} form is routed through
     * {@link #fromPfNumber(int)} so the PF13&ndash;PF24 wrap is applied consistently.</p>
     *
     * <p>Consistent with the COBOL fragment&apos;s missing {@code WHEN OTHER} branch, a
     * {@code null}, blank or unrecognized mnemonic yields {@link PfKey#OTHER}; this method never
     * throws for unrecognized input.</p>
     *
     * @param aid the CICS AID mnemonic (for example {@code "DFHENTER"}, {@code "DFHCLEAR"},
     *            {@code "DFHPA1"}, {@code "DFHPF7"}); may be {@code null} or blank-padded
     * @return the mapped {@link PfKey}, or {@link PfKey#OTHER} when {@code aid} is {@code null},
     *         blank or does not correspond to a recognized 3270 key
     */
    public static PfKey fromAid(String aid) {
        if (aid == null) {
            return PfKey.OTHER;
        }
        String token = aid.trim().toUpperCase(Locale.ROOT);
        if (token.isEmpty()) {
            return PfKey.OTHER;
        }
        return switch (token) {
            case "DFHENTER" -> PfKey.ENTER;
            case "DFHCLEAR" -> PfKey.CLEAR;
            case "DFHPA1" -> PfKey.PA1;
            case "DFHPA2" -> PfKey.PA2;
            default -> fromProgramFunctionAid(token);
        };
    }

    /**
     * Maps a numeric program-function-key number to its {@link PfKey}, applying the
     * {@code CSSTRPFY} PF13&ndash;PF24 wrap.
     *
     * <p>Numbers {@code 1}&ndash;{@code 12} map one-to-one to {@link PfKey#PFK01}&ndash;
     * {@link PfKey#PFK12}; numbers {@code 13}&ndash;{@code 24} wrap back onto the same twelve
     * indicators via {@code (pfNumber - 1) % 12} (so {@code 13} &rarr; {@link PfKey#PFK01} and
     * {@code 24} &rarr; {@link PfKey#PFK12}). Any value outside {@code 1}&ndash;{@code 24} yields
     * {@link PfKey#OTHER}. This method never throws.</p>
     *
     * @param pfNumber the PF-key number, expected in the inclusive range {@code 1}&ndash;{@code 24}
     * @return the mapped {@link PfKey}, or {@link PfKey#OTHER} when {@code pfNumber} is out of range
     */
    public static PfKey fromPfNumber(int pfNumber) {
        if (pfNumber < MIN_PF_NUMBER || pfNumber > MAX_PF_NUMBER) {
            return PfKey.OTHER;
        }
        return PFK_BY_INDEX[(pfNumber - 1) % PF_KEYS_PER_BANK];
    }

    /**
     * Resolves the {@code DFHPFnn} family of AID mnemonics to a {@link PfKey}.
     *
     * <p>The {@code DFHPF} prefix is stripped and the remaining characters are parsed as an
     * integer, which is then routed through {@link #fromPfNumber(int)} so the wrap semantics are
     * applied in one place. A token that is not a {@code DFHPFnn} form, or whose suffix is not a
     * parseable integer, yields {@link PfKey#OTHER}; the {@link NumberFormatException} is caught so
     * the method never throws.</p>
     *
     * @param token the already trimmed and upper-cased AID token
     * @return the mapped {@link PfKey}, or {@link PfKey#OTHER} when {@code token} is not a
     *         recognized {@code DFHPFnn} mnemonic
     */
    private static PfKey fromProgramFunctionAid(String token) {
        if (!token.startsWith(DFHPF_PREFIX)) {
            return PfKey.OTHER;
        }
        String digits = token.substring(DFHPF_PREFIX.length());
        if (digits.isEmpty()) {
            return PfKey.OTHER;
        }
        try {
            return fromPfNumber(Integer.parseInt(digits));
        } catch (NumberFormatException ex) {
            return PfKey.OTHER;
        }
    }

    /**
     * Indicates whether the given key is the ENTER key (the BMS "submit" action, AAP &sect;0.3.4).
     *
     * @param key the resolved PF-key (may be {@code null})
     * @return {@code true} when {@code key} is {@link PfKey#ENTER}
     */
    public static boolean isEnter(PfKey key) {
        return key == PfKey.ENTER;
    }

    /**
     * Indicates whether the given key is PF3 (the BMS "back" action, AAP &sect;0.3.4).
     *
     * @param key the resolved PF-key (may be {@code null})
     * @return {@code true} when {@code key} is {@link PfKey#PFK03}
     */
    public static boolean isPf3(PfKey key) {
        return key == PfKey.PFK03;
    }

    /**
     * Indicates whether the given key is PF7 (the BMS "page up" action, AAP &sect;0.3.4).
     *
     * @param key the resolved PF-key (may be {@code null})
     * @return {@code true} when {@code key} is {@link PfKey#PFK07}
     */
    public static boolean isPf7(PfKey key) {
        return key == PfKey.PFK07;
    }

    /**
     * Indicates whether the given key is PF8 (the BMS "page down" action, AAP &sect;0.3.4).
     *
     * @param key the resolved PF-key (may be {@code null})
     * @return {@code true} when {@code key} is {@link PfKey#PFK08}
     */
    public static boolean isPf8(PfKey key) {
        return key == PfKey.PFK08;
    }
}
