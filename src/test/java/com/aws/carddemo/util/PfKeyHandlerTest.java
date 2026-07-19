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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aws.carddemo.dto.CardWorkArea.PfKey;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Parity-oracle unit tests for {@link PfKeyHandler}, the CICS attention-identifier (AID)
 * to program-function-key mapper.
 *
 * <p><strong>Oracles.</strong> These tests lock the behavior translated from two COBOL
 * copybooks retained read-only under {@code legacy/}:</p>
 * <ul>
 *   <li>{@code legacy/cpy/CSSTRPFY.cpy} &mdash; the {@code YYYY-STORE-PFKEY} procedure
 *       fragment whose {@code EVALUATE TRUE} over the CICS {@code EIBAID} field maps each
 *       recognized 3270 key to a {@code CCARD-AID-*} condition. Crucially, that
 *       {@code EVALUATE} explicitly folds the second bank of function keys
 *       ({@code DFHPF13}&ndash;{@code DFHPF24}) back onto {@code CCARD-AID-PFK01}&ndash;
 *       {@code CCARD-AID-PFK12}; this "wrap" is a deliberately preserved legacy quirk
 *       (AAP &sect;0.2.2, no behavioral changes), not a defect to be corrected.</li>
 *   <li>{@code legacy/cpy/CVCRD01Y.cpy} &mdash; the {@code CC-WORK-AREAS} working-storage
 *       group whose {@code CCARD-AID PIC X(5)} item carries the 88-level condition names
 *       ({@code CCARD-AID-ENTER}, {@code CCARD-AID-CLEAR}, {@code CCARD-AID-PA1},
 *       {@code CCARD-AID-PA2} and {@code CCARD-AID-PFK01}&ndash;{@code CCARD-AID-PFK12}).
 *       Those condition names are the constant set reproduced by the {@link PfKey}
 *       enumeration under test here.</li>
 * </ul>
 *
 * <p><strong>Enum ownership.</strong> Per AAP &sect;0.4.1 the {@link PfKey} enumeration is
 * owned by production {@code com.aws.carddemo.dto.CardWorkArea} (the migration of
 * {@code CVCRD01Y}); it is imported as {@code com.aws.carddemo.dto.CardWorkArea.PfKey} and
 * never redeclared locally, so this test also guards that published cross-package contract.</p>
 *
 * <p><strong>Scope.</strong> This is a pure, fast, deterministic JUnit&nbsp;5 unit test: it
 * bootstraps no Spring context, touches no database, uses no Testcontainers, and never uses
 * {@code float}/{@code double}. It exercises only the static surface of {@link PfKeyHandler}
 * ({@link PfKeyHandler#fromPfNumber(int)}, {@link PfKeyHandler#fromAid(String)} and the
 * {@code isEnter}/{@code isPf3}/{@code isPf7}/{@code isPf8} navigation predicates that back
 * the BMS PF-key contract of AAP &sect;0.3.4: PF3 = back, PF7 = page up, PF8 = page down,
 * ENTER = submit).</p>
 *
 * <p><strong>Wrap invariant (the crux).</strong> The modulo mapping
 * {@code ((n - 1) % 12)} is the single most important behavior proven here:
 * {@code 13 -> PFK01}, {@code 20 -> PFK08}, {@code 24 -> PFK12}. If a future refactor ever
 * breaks the wrap, {@link #pfNumberWrapsFrom13To24()} and {@link #aidPf13WrapsToPfk01()}
 * MUST fail.</p>
 */
@DisplayName("PfKeyHandler — CICS AID / PF-number to PfKey parity (legacy/cpy/CSSTRPFY.cpy)")
class PfKeyHandlerTest {

    // ------------------------------------------------------------------
    // fromPfNumber(int): base range 1..12, wrap 13..24, out-of-range
    // ------------------------------------------------------------------

    /**
     * PF numbers 1..12 map one-to-one to {@link PfKey#PFK01}..{@link PfKey#PFK12}. The three
     * folder-required anchors (1, 7, 12) are asserted explicitly, then the full first bank is
     * swept so every one-to-one mapping is covered.
     */
    @Test
    void pfNumberBaseRangeMapsDirectly() {
        assertEquals(PfKey.PFK01, PfKeyHandler.fromPfNumber(1));
        assertEquals(PfKey.PFK07, PfKeyHandler.fromPfNumber(7));
        assertEquals(PfKey.PFK12, PfKeyHandler.fromPfNumber(12));

        // Full first-bank sweep: PF n -> PFKnn for n in 1..12.
        for (int n = 1; n <= 12; n++) {
            PfKey expected = PfKey.valueOf(String.format("PFK%02d", n));
            assertEquals(expected, PfKeyHandler.fromPfNumber(n), "PF" + n + " should map to " + expected);
        }
    }

    /**
     * PF numbers 13..24 wrap back onto the first bank via {@code ((n - 1) % 12)}
     * (the preserved {@code CSSTRPFY} quirk): 13 -> PFK01, 20 -> PFK08, 24 -> PFK12. The three
     * folder-required anchors are asserted explicitly, then the whole second bank is swept.
     */
    @Test
    void pfNumberWrapsFrom13To24() {
        assertEquals(PfKey.PFK01, PfKeyHandler.fromPfNumber(13));
        assertEquals(PfKey.PFK08, PfKeyHandler.fromPfNumber(20)); // (20-1)%12 = 7 -> PFK08
        assertEquals(PfKey.PFK12, PfKeyHandler.fromPfNumber(24));

        // Full second-bank sweep: PF n (13..24) -> PFK((n-1)%12 + 1).
        for (int n = 13; n <= 24; n++) {
            int bankIndex = ((n - 1) % 12) + 1;
            PfKey expected = PfKey.valueOf(String.format("PFK%02d", bankIndex));
            assertEquals(expected, PfKeyHandler.fromPfNumber(n), "PF" + n + " should wrap to " + expected);
        }
    }

    /**
     * Anything outside the inclusive range 1..24 yields {@link PfKey#OTHER}, and the method
     * never throws for extreme values (documented contract; the COBOL {@code EVALUATE} has no
     * {@code WHEN OTHER}, so an unmatched key simply leaves every condition {@code false}).
     */
    @Test
    void pfNumberOutOfRangeIsOther() {
        assertEquals(PfKey.OTHER, PfKeyHandler.fromPfNumber(0));
        assertEquals(PfKey.OTHER, PfKeyHandler.fromPfNumber(25));
        assertEquals(PfKey.OTHER, PfKeyHandler.fromPfNumber(-1));
        assertEquals(PfKey.OTHER, PfKeyHandler.fromPfNumber(Integer.MAX_VALUE));
        assertEquals(PfKey.OTHER, PfKeyHandler.fromPfNumber(Integer.MIN_VALUE));
    }

    // ------------------------------------------------------------------
    // fromAid(String): folder-required cases
    // ------------------------------------------------------------------

    /** {@code "DFHPF13"} routes through the wrap and resolves to {@link PfKey#PFK01}. */
    @Test
    void aidPf13WrapsToPfk01() {
        assertEquals(PfKey.PFK01, PfKeyHandler.fromAid("DFHPF13"));
    }

    /** {@code "DFHPF24"} routes through the wrap and resolves to {@link PfKey#PFK12}. */
    @Test
    void aidPf24WrapsToPfk12() {
        assertEquals(PfKey.PFK12, PfKeyHandler.fromAid("DFHPF24"));
    }

    /**
     * A {@code null} AID is null-safe and yields {@link PfKey#OTHER} rather than throwing a
     * {@link NullPointerException} (the call inside {@code assertEquals} would surface any NPE).
     */
    @Test
    void aidNullIsOther() {
        assertEquals(PfKey.OTHER, PfKeyHandler.fromAid(null));
    }

    /** An unrecognized token yields {@link PfKey#OTHER}. */
    @Test
    void aidBogusIsOther() {
        assertEquals(PfKey.OTHER, PfKeyHandler.fromAid("BOGUS"));
    }

    // ------------------------------------------------------------------
    // fromAid(String): additional coverage aligned to the production AID table
    // ------------------------------------------------------------------

    /**
     * The four fixed keys and representative program-function keys map exactly as the
     * {@code CSSTRPFY} {@code EVALUATE EIBAID} dictates. AID spellings match the production
     * table ({@code DFHENTER}, {@code DFHCLEAR}, {@code DFHPA1}, {@code DFHPA2},
     * {@code DFHPFn}); the base-range PF anchors {@code DFHPF1}/{@code DFHPF3}/{@code DFHPF7}/
     * {@code DFHPF8}/{@code DFHPF12} confirm one-to-one mapping before any wrap applies.
     */
    @Test
    void aidBaseKeysMapCorrectly() {
        assertEquals(PfKey.ENTER, PfKeyHandler.fromAid("DFHENTER"));
        assertEquals(PfKey.CLEAR, PfKeyHandler.fromAid("DFHCLEAR"));
        assertEquals(PfKey.PA1, PfKeyHandler.fromAid("DFHPA1"));
        assertEquals(PfKey.PA2, PfKeyHandler.fromAid("DFHPA2"));
        assertEquals(PfKey.PFK01, PfKeyHandler.fromAid("DFHPF1"));
        assertEquals(PfKey.PFK03, PfKeyHandler.fromAid("DFHPF3"));
        assertEquals(PfKey.PFK07, PfKeyHandler.fromAid("DFHPF7"));
        assertEquals(PfKey.PFK08, PfKeyHandler.fromAid("DFHPF8"));
        assertEquals(PfKey.PFK12, PfKeyHandler.fromAid("DFHPF12"));
    }

    /**
     * AID resolution is case-insensitive: the token is upper-cased with the root locale before
     * matching, so lower- and mixed-case mnemonics resolve identically to their canonical form.
     */
    @Test
    void aidIsCaseInsensitive() {
        assertEquals(PfKey.PFK01, PfKeyHandler.fromAid("dfhpf13")); // lower-case still wraps
        assertEquals(PfKey.PFK07, PfKeyHandler.fromAid("DfHpF7"));  // mixed-case resolves
        assertEquals(PfKey.ENTER, PfKeyHandler.fromAid("dfhenter"));
    }

    /**
     * A blank or empty AID is treated as unset and yields {@link PfKey#OTHER}; this covers the
     * empty-after-trim guard in the production mapper.
     */
    @Test
    void aidBlankIsOther() {
        assertEquals(PfKey.OTHER, PfKeyHandler.fromAid(""));
        assertEquals(PfKey.OTHER, PfKeyHandler.fromAid("     "));
    }

    /**
     * Surrounding blanks are trimmed before matching, mirroring the copybook's blank-padded
     * tokens (for example {@code CCARD-AID-PA1 VALUE 'PA1  '}); a padded AID still resolves.
     */
    @Test
    void aidSurroundingBlanksResolve() {
        assertEquals(PfKey.PFK08, PfKeyHandler.fromAid("  DFHPF8  "));
        assertEquals(PfKey.ENTER, PfKeyHandler.fromAid(" DFHENTER "));
    }

    /**
     * A token that begins with the {@code DFHPF} prefix but is not a well-formed
     * {@code DFHPFnn} in range 1..24 (missing digits, non-numeric suffix, or an out-of-range
     * number) yields {@link PfKey#OTHER} and never throws.
     */
    @Test
    void aidMalformedPfIsOther() {
        assertEquals(PfKey.OTHER, PfKeyHandler.fromAid("DFHPF"));   // no digits
        assertEquals(PfKey.OTHER, PfKeyHandler.fromAid("DFHPFX"));  // non-numeric suffix
        assertEquals(PfKey.OTHER, PfKeyHandler.fromAid("DFHPF1A")); // partially numeric suffix
        assertEquals(PfKey.OTHER, PfKeyHandler.fromAid("DFHPF0"));  // below range -> OTHER
        assertEquals(PfKey.OTHER, PfKeyHandler.fromAid("DFHPF25")); // above range -> OTHER
    }

    // ------------------------------------------------------------------
    // PfKey enum guard (light; the enum is owned by dto.CardWorkArea)
    // ------------------------------------------------------------------

    /**
     * The shared {@link PfKey} enumeration exposes exactly the 17 constants the migration
     * contract requires (16 real {@code CCARD-AID} 88-levels plus the synthetic
     * {@link PfKey#OTHER}). The count plus a few key members are asserted; brittle ordinal
     * checks are intentionally avoided because the enum is owned by {@code dto.CardWorkArea}.
     */
    @Test
    void pfKeyEnumHasSeventeenConstants() {
        assertEquals(17, PfKey.values().length);
        assertEquals(PfKey.ENTER, PfKey.valueOf("ENTER"));
        assertEquals(PfKey.CLEAR, PfKey.valueOf("CLEAR"));
        assertEquals(PfKey.PA1, PfKey.valueOf("PA1"));
        assertEquals(PfKey.PA2, PfKey.valueOf("PA2"));
        assertEquals(PfKey.PFK01, PfKey.valueOf("PFK01"));
        assertEquals(PfKey.PFK12, PfKey.valueOf("PFK12"));
        assertEquals(PfKey.OTHER, PfKey.valueOf("OTHER"));
    }

    // ------------------------------------------------------------------
    // Navigation predicates backing the BMS PF-key contract (AAP 0.3.4)
    // ------------------------------------------------------------------

    /**
     * The {@code isEnter}/{@code isPf3}/{@code isPf7}/{@code isPf8} predicates recognize exactly
     * their target key (ENTER = submit, PF3 = back, PF7 = page up, PF8 = page down), reject the
     * others, and are null-safe (a {@code null} key is never any navigation action).
     */
    @Test
    void helperPredicatesMatchBmsActions() {
        assertTrue(PfKeyHandler.isEnter(PfKey.ENTER));
        assertFalse(PfKeyHandler.isEnter(PfKey.PFK03));
        assertFalse(PfKeyHandler.isEnter(PfKey.OTHER));
        assertFalse(PfKeyHandler.isEnter(null));

        assertTrue(PfKeyHandler.isPf3(PfKey.PFK03));
        assertFalse(PfKeyHandler.isPf3(PfKey.ENTER));
        assertFalse(PfKeyHandler.isPf3(null));

        assertTrue(PfKeyHandler.isPf7(PfKey.PFK07));
        assertFalse(PfKeyHandler.isPf7(PfKey.PFK08));
        assertFalse(PfKeyHandler.isPf7(null));

        assertTrue(PfKeyHandler.isPf8(PfKey.PFK08));
        assertFalse(PfKeyHandler.isPf8(PfKey.PFK07));
        assertFalse(PfKeyHandler.isPf8(null));
    }

    /**
     * The predicates compose with {@link PfKeyHandler#fromAid(String)} end-to-end, proving the
     * full "AID mnemonic -> PfKey -> navigation action" path a controller would take: PF3 back,
     * PF7 page up, PF8 page down, ENTER submit.
     */
    @Test
    void helpersComposeWithFromAid() {
        assertTrue(PfKeyHandler.isPf3(PfKeyHandler.fromAid("DFHPF3")));
        assertTrue(PfKeyHandler.isPf7(PfKeyHandler.fromAid("DFHPF7")));
        assertTrue(PfKeyHandler.isPf8(PfKeyHandler.fromAid("DFHPF8")));
        assertTrue(PfKeyHandler.isEnter(PfKeyHandler.fromAid("DFHENTER")));
    }

    // ------------------------------------------------------------------
    // Utility-class contract
    // ------------------------------------------------------------------

    /**
     * {@link PfKeyHandler} is a non-instantiable static utility: its sole constructor is private
     * and throws {@link AssertionError}. Reflectively invoking it surfaces that error wrapped in
     * an {@link InvocationTargetException}, locking the "no instances" contract.
     *
     * @throws NoSuchMethodException never; the no-arg constructor is declared on the class
     */
    @Test
    void utilityClassIsNotInstantiable() throws NoSuchMethodException {
        Constructor<PfKeyHandler> constructor = PfKeyHandler.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        InvocationTargetException thrown =
                assertThrows(InvocationTargetException.class, () -> constructor.newInstance());
        assertInstanceOf(AssertionError.class, thrown.getCause());
        assertEquals("No instances", thrown.getCause().getMessage());
    }
}
