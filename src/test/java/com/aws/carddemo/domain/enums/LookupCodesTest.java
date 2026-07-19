package com.aws.carddemo.domain.enums;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LookupCodes}, the reference-code lookup utility translated from the
 * COBOL copybook {@code legacy/cpy/CSLKPCDY.cpy} (AAP section 0.6.10 traceability).
 *
 * <p>Validates the {@code LookupCodes} reference-code contract: the phone-area-code table and its
 * general-purpose / easy-recognizable decomposition, the US state/territory code set, and the
 * state + first-two-of-ZIP combination set. All of these were originally expressed as COBOL
 * level-88 VALUE lists and are now realized as unmodifiable {@link java.util.Set} membership
 * tables with null-safe, whitespace-tolerant, case-insensitive validators.</p>
 *
 * <p>The assertions deliberately lock in set sizes, set-algebra invariants (the phone table is the
 * exact, disjoint union of its two subsets), immutability, and representative spot-check members so
 * that any future drift in the copybook decomposition is caught immediately. This is a pure unit
 * test: no Spring context, no database, and no Testcontainers are involved.</p>
 */
class LookupCodesTest {

    /**
     * The five lookup tables must retain their exact, copybook-verified cardinalities. These fixed
     * sizes are the primary guard against a silent addition or removal of a reference code.
     */
    @Test
    void allLookupSetsHaveExpectedSizes() {
        assertThat(LookupCodes.VALID_PHONE_AREA_CODES).hasSize(490);
        assertThat(LookupCodes.VALID_GENERAL_PURPOSE_CODES).hasSize(410);
        assertThat(LookupCodes.VALID_EASY_RECOGNIZABLE_AREA_CODES).hasSize(80);
        assertThat(LookupCodes.VALID_US_STATE_CODES).hasSize(56);
        assertThat(LookupCodes.VALID_US_STATE_ZIP_CD2_COMBOS).hasSize(240);
    }

    /**
     * The full phone-area-code table must equal the union of the general-purpose and
     * easy-recognizable subsets, and those two subsets must be disjoint. This locks the copybook
     * decomposition: 410 general-purpose + 80 easy-recognizable = 490 total, with no overlap.
     */
    @Test
    void phoneAreaCodesAreExactUnionOfGeneralAndEasyRecognizable() {
        Set<String> union = new HashSet<>(LookupCodes.VALID_GENERAL_PURPOSE_CODES);
        union.addAll(LookupCodes.VALID_EASY_RECOGNIZABLE_AREA_CODES);

        // Union of the two subsets reproduces the full phone-area-code table exactly.
        assertThat(union).containsExactlyInAnyOrderElementsOf(LookupCodes.VALID_PHONE_AREA_CODES);

        // The two subsets are disjoint (410 + 80 = 490, no overlap).
        assertThat(LookupCodes.VALID_GENERAL_PURPOSE_CODES)
                .doesNotContainAnyElementsOf(LookupCodes.VALID_EASY_RECOGNIZABLE_AREA_CODES);

        // Both subsets are fully contained in the parent set.
        assertThat(LookupCodes.VALID_PHONE_AREA_CODES)
                .containsAll(LookupCodes.VALID_GENERAL_PURPOSE_CODES)
                .containsAll(LookupCodes.VALID_EASY_RECOGNIZABLE_AREA_CODES);
    }

    /**
     * Every lookup table is built with {@code Set.of(...)}, which returns an immutable set;
     * attempting to mutate any of them must throw {@link UnsupportedOperationException}.
     */
    @Test
    void allLookupSetsAreUnmodifiable() {
        assertThatThrownBy(() -> LookupCodes.VALID_PHONE_AREA_CODES.add("XXX"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> LookupCodes.VALID_GENERAL_PURPOSE_CODES.add("XXX"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> LookupCodes.VALID_EASY_RECOGNIZABLE_AREA_CODES.add("XXX"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> LookupCodes.VALID_US_STATE_CODES.add("XX"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> LookupCodes.VALID_US_STATE_ZIP_CD2_COMBOS.add("XX99"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * The state-code validator mirrors the COBOL level-88 membership test while adding the
     * shared normalization behavior: it is case-insensitive (Locale.ROOT), trims surrounding
     * whitespace, and is null-safe.
     */
    @Test
    void isValidUsStateCodeHandlesCaseWhitespaceAndUnknownValues() {
        assertThat(LookupCodes.isValidUsStateCode("CA")).isTrue();
        assertThat(LookupCodes.isValidUsStateCode("ca")).isTrue();     // case-insensitive (Locale.ROOT)
        assertThat(LookupCodes.isValidUsStateCode(" CA ")).isTrue();   // trimmed
        assertThat(LookupCodes.isValidUsStateCode("ZZ")).isFalse();
        assertThat(LookupCodes.isValidUsStateCode(null)).isFalse();    // null-safe
    }

    /**
     * The phone-area-code validator accepts codes present anywhere in the full NANPA table
     * (geographic and easy-recognizable alike) and rejects unknown codes and null.
     */
    @Test
    void isValidPhoneAreaCodeAcceptsKnownCodesAndRejectsOthers() {
        assertThat(LookupCodes.isValidPhoneAreaCode("201")).isTrue();
        assertThat(LookupCodes.isValidPhoneAreaCode("555")).isTrue();
        assertThat(LookupCodes.isValidPhoneAreaCode("999")).isTrue();
        assertThat(LookupCodes.isValidPhoneAreaCode("100")).isFalse();
        assertThat(LookupCodes.isValidPhoneAreaCode(null)).isFalse();
    }

    /**
     * The easy-recognizable validator matches only the easy-recognizable subset; a general-purpose
     * code such as "201" must be rejected by it.
     */
    @Test
    void isValidEasyRecognizableAreaCodeMatchesEasySubsetOnly() {
        assertThat(LookupCodes.isValidEasyRecognizableAreaCode("200")).isTrue();
        assertThat(LookupCodes.isValidEasyRecognizableAreaCode("201")).isFalse(); // 201 is general-purpose, not easy
    }

    /**
     * The general-purpose validator matches only the general-purpose subset; an easy-recognizable
     * code such as "200" must be rejected by it.
     */
    @Test
    void isValidGeneralPurposeCodeMatchesGeneralSubsetOnly() {
        assertThat(LookupCodes.isValidGeneralPurposeCode("201")).isTrue();
        assertThat(LookupCodes.isValidGeneralPurposeCode("200")).isFalse(); // 200 is easy-recognizable, not general
    }

    /**
     * The state + first-two-of-ZIP combination validator accepts known combinations (including the
     * military APO/FPO prefixes preserved verbatim from the copybook) and rejects unknown
     * combinations and null.
     */
    @Test
    void isValidStateZipComboAcceptsKnownCombosAndRejectsOthers() {
        assertThat(LookupCodes.isValidStateZipCombo("AA34")).isTrue();  // military APO/FPO prefix
        assertThat(LookupCodes.isValidStateZipCombo("AE90")).isTrue();
        assertThat(LookupCodes.isValidStateZipCombo("AL34")).isFalse(); // AL valid ZIP prefixes are 35/36, not 34
        assertThat(LookupCodes.isValidStateZipCombo(null)).isFalse();
    }

    /**
     * Documents and enforces the utility-class shape: {@code LookupCodes} is a final,
     * non-instantiable constants holder with a single private no-argument constructor. Invoking
     * the private constructor reflectively also exercises it for coverage.
     */
    @Test
    void classIsFinalAndConstructorIsPrivate() throws Exception {
        assertThat(Modifier.isFinal(LookupCodes.class.getModifiers())).isTrue();
        Constructor<LookupCodes> ctor = LookupCodes.class.getDeclaredConstructor();
        assertThat(Modifier.isPrivate(ctor.getModifiers())).isTrue();
        ctor.setAccessible(true);
        assertThat(ctor.newInstance()).isNotNull(); // exercises the private ctor for coverage
    }
}
