/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.dto;

/**
 * Shared, type-safe representation of the CICS 3270 Attention Identifier (AID)
 * &mdash; the key a terminal operator pressed to transmit a screen &mdash; used
 * as the {@code action} field of every online request DTO in this package.
 *
 * <p>This enum is the single canonical Java translation of the COBOL shared
 * copybook {@code CSSTRPFY.cpy} (paragraph {@code YYYY-STORE-PFKEY}), which was
 * {@code COPY}-ed into the online programs to convert the raw {@code EIBAID}
 * byte into a {@code CCARD-AID-*} condition name held in the COMMAREA. Per the
 * migration design, that shared copybook becomes one shared type rather than
 * being duplicated across consumers, and the rendered 3270 key becomes an
 * explicit, transport-neutral action value rather than a terminal keystroke.
 *
 * <p><strong>Source-of-truth mapping</strong> (COBOL {@code EVALUATE TRUE} over
 * {@code EIBAID}):
 * <ul>
 *   <li>{@code DFHENTER} &rarr; {@link #ENTER}</li>
 *   <li>{@code DFHCLEAR} &rarr; {@link #CLEAR}</li>
 *   <li>{@code DFHPA1} &rarr; {@link #PA1}, {@code DFHPA2} &rarr; {@link #PA2}</li>
 *   <li>{@code DFHPF1}&hellip;{@code DFHPF12} &rarr; {@link #PF1}&hellip;{@link #PF12}</li>
 *   <li>{@code DFHPF13}&hellip;{@code DFHPF24} &rarr; wrap back onto
 *       {@link #PF1}&hellip;{@link #PF12} (PF13 aliases PF1, &hellip;, PF24
 *       aliases PF12)</li>
 * </ul>
 * The physical PF13&ndash;PF24 range therefore has no distinct constant: it is
 * folded onto PF1&ndash;PF12 exactly as the legacy copybook did. The
 * {@link #fromAid(int)} helper reproduces that fold for callers that receive a
 * raw PF-key number.
 *
 * <p><strong>Scope.</strong> This type carries no business logic and encodes no
 * screen-specific routing. The conventional meanings noted on individual
 * constants are documentation only; the actual behavior for a given key on a
 * given screen is decided by the owning controller and service (the Java
 * equivalents of each online program's paragraph logic).
 */
public enum PfKeyAction {

    /**
     * {@code DFHENTER} &mdash; the Enter key. Across the online screens this is
     * the primary submit/continue action (process, fetch, search, sign-on, add,
     * confirm), depending on the owning screen.
     */
    ENTER,

    /**
     * {@code DFHCLEAR} &mdash; the Clear key, which blanked the 3270 screen
     * buffer. Preserved so the full AID contract is representable; screens that
     * observed Clear handle it in their controller logic.
     */
    CLEAR,

    /**
     * {@code DFHPA1} &mdash; Program Attention key 1. A non-data-transmitting
     * attention key; retained for completeness of the AID contract.
     */
    PA1,

    /**
     * {@code DFHPA2} &mdash; Program Attention key 2. A non-data-transmitting
     * attention key; retained for completeness of the AID contract.
     */
    PA2,

    /**
     * {@code DFHPF1} (and, via the legacy wrap, {@code DFHPF13}) &mdash; Program
     * Function key 1. Conventionally a help/action key on the screens that use
     * it; behavior is screen-specific.
     */
    PF1,

    /**
     * {@code DFHPF2} (and {@code DFHPF14}) &mdash; Program Function key 2;
     * screen-specific action.
     */
    PF2,

    /**
     * {@code DFHPF3} (and {@code DFHPF15}) &mdash; Program Function key 3.
     * Conventionally the back/exit key that returns to the prior screen or menu.
     */
    PF3,

    /**
     * {@code DFHPF4} (and {@code DFHPF16}) &mdash; Program Function key 4.
     * Conventionally the clear-form action on screens that offer it.
     */
    PF4,

    /**
     * {@code DFHPF5} (and {@code DFHPF17}) &mdash; Program Function key 5.
     * Conventionally a save/delete/copy/browse action, depending on the screen.
     */
    PF5,

    /**
     * {@code DFHPF6} (and {@code DFHPF18}) &mdash; Program Function key 6;
     * screen-specific action.
     */
    PF6,

    /**
     * {@code DFHPF7} (and {@code DFHPF19}) &mdash; Program Function key 7.
     * Conventionally the page-backward action on paged/list screens.
     */
    PF7,

    /**
     * {@code DFHPF8} (and {@code DFHPF20}) &mdash; Program Function key 8.
     * Conventionally the page-forward action on paged/list screens.
     */
    PF8,

    /**
     * {@code DFHPF9} (and {@code DFHPF21}) &mdash; Program Function key 9;
     * screen-specific action.
     */
    PF9,

    /**
     * {@code DFHPF10} (and {@code DFHPF22}) &mdash; Program Function key 10;
     * screen-specific action.
     */
    PF10,

    /**
     * {@code DFHPF11} (and {@code DFHPF23}) &mdash; Program Function key 11;
     * screen-specific action.
     */
    PF11,

    /**
     * {@code DFHPF12} (and {@code DFHPF24}) &mdash; Program Function key 12.
     * Conventionally the cancel/exit action on screens that offer it.
     */
    PF12;

    /**
     * The lowest physical 3270 Program-Function-key number (inclusive) accepted
     * by {@link #fromAid(int)}.
     */
    private static final int MIN_PF_NUMBER = 1;

    /**
     * The highest physical 3270 Program-Function-key number (inclusive) accepted
     * by {@link #fromAid(int)}. The mainframe defined PF1 through PF24.
     */
    private static final int MAX_PF_NUMBER = 24;

    /**
     * The number of distinct Program-Function-key actions this enum models
     * (PF1&ndash;PF12); the physical PF13&ndash;PF24 range wraps onto this span.
     */
    private static final int PF_KEY_SPAN = 12;

    /**
     * Resolves a raw 3270 Program-Function-key number to its canonical
     * {@link PfKeyAction}, reproducing the legacy {@code CSSTRPFY.cpy} fold in
     * which {@code DFHPF13}&hellip;{@code DFHPF24} map back onto
     * {@code CCARD-AID-PFK01}&hellip;{@code CCARD-AID-PFK12}.
     *
     * <p>The mapping is: PF1&ndash;PF12 map to {@link #PF1}&ndash;{@link #PF12}
     * directly, and PF13&ndash;PF24 wrap so that, for example, PF13 resolves to
     * {@link #PF1} and PF24 resolves to {@link #PF12}. This mirrors the COBOL
     * {@code EVALUATE TRUE} exactly. Non-Program-Function attention keys
     * ({@link #ENTER}, {@link #CLEAR}, {@link #PA1}, {@link #PA2}) are not
     * numbered and are therefore outside the domain of this helper; obtain them
     * directly by constant reference or via {@link #valueOf(String)}.
     *
     * <p>This is a pure function: it has no side effects, holds no state, and
     * depends on nothing outside the JDK.
     *
     * @param pfNumber the physical PF-key number as transmitted by the terminal,
     *                 in the inclusive range {@code 1}&ndash;{@code 24}
     * @return the canonical action ({@link #PF1}&ndash;{@link #PF12}) for the key
     * @throws IllegalArgumentException if {@code pfNumber} is outside 1&ndash;24
     */
    public static PfKeyAction fromAid(int pfNumber) {
        if (pfNumber < MIN_PF_NUMBER || pfNumber > MAX_PF_NUMBER) {
            throw new IllegalArgumentException(
                    "Program Function key number out of range (expected "
                            + MIN_PF_NUMBER + ".." + MAX_PF_NUMBER + "): " + pfNumber);
        }
        // Fold PF13..PF24 back onto PF1..PF12, exactly as CSSTRPFY.cpy does.
        int normalized = ((pfNumber - MIN_PF_NUMBER) % PF_KEY_SPAN) + MIN_PF_NUMBER;
        return valueOf("PF" + normalized);
    }
}
