package com.aws.carddemo.util.constants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit 5 parity unit test for {@link ScreenTitles}.
 *
 * <p>Verifies that the migrated Java constants holder faithfully reproduces the COBOL screen-title
 * copybook {@code 01 CCDA-SCREEN-TITLE}: each {@code PIC X(40)} banner literal is preserved as a
 * fixed 40-column string whose trimmed logical text matches the copybook {@code VALUE}, and the
 * copybook's commented-out alternative title is deliberately NOT migrated.</p>
 *
 * <p>Parity oracle: legacy/cpy/COTTL01Y.cpy (01 CCDA-SCREEN-TITLE) — source-branch
 * app/cpy/COTTL01Y.cpy.</p>
 *
 * <p>This is a pure unit test: no Spring application context, no database connection, and no
 * container startup. It executes in milliseconds and is picked up by the Surefire {@code *Test}
 * pattern (never Failsafe).</p>
 */
@DisplayName("ScreenTitles parity with COBOL copybook COTTL01Y (01 CCDA-SCREEN-TITLE)")
class ScreenTitlesTest {

    /**
     * The COBOL commented-out ({@code *}) alternative for CCDA-TITLE02 on line 21 of the copybook,
     * {@code '  Credit Card Demo Application (CCDA)   '}, must never surface as a migrated constant.
     * A distinctive substring of that comment is used as the negative-guard needle.
     */
    private static final String FORBIDDEN_COMMENTED_TEXT = "Credit Card Demo Application";

    // ---------------------------------------------------------------------
    // Phase 2 — logical-text (trimmed) parity assertions
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("CCDA_TITLE01 trimmed text matches the COBOL literal")
    void title01MatchesCobolLiteral() {
        // COTTL01Y.cpy line 19: '      AWS Mainframe Modernization       '
        assertEquals("AWS Mainframe Modernization", ScreenTitles.CCDA_TITLE01.trim());
    }

    @Test
    @DisplayName("CCDA_TITLE02 is the live 'CardDemo' value, not the commented-out alternative")
    void title02IsCardDemoNotTheCommentedAlternative() {
        // COTTL01Y.cpy line 22 (live value): '              CardDemo                  '
        // Line 21 is a COBOL '*' comment and must NOT be the migrated value.
        assertEquals("CardDemo", ScreenTitles.CCDA_TITLE02.trim());
    }

    @Test
    @DisplayName("CCDA_THANK_YOU banner matches the 'CCDA application...' logical text")
    void thankYouTitleStartsWithExpectedText() {
        // COTTL01Y.cpy line 24: 'Thank you for using CCDA application... '
        // NOTE: this oracle (COTTL01Y) is the *CCDA* application banner. It is intentionally
        // distinct from Messages.CCDA_MSG_THANK_YOU (copybook CSMSG01Y) which says *CardDemo*
        // application. Do not "correct" one to match the other — they are different copybooks.
        assertTrue(ScreenTitles.CCDA_THANK_YOU.trim().startsWith("Thank you for using CCDA application"));
        // Full trimmed logical text; keep the "..." as three ASCII periods, exactly as the copybook.
        assertEquals("Thank you for using CCDA application...", ScreenTitles.CCDA_THANK_YOU.trim());
    }

    // ---------------------------------------------------------------------
    // Phase 3 — fixed-width (PIC X(40)) contract + negative guard
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("All three titles preserve the fixed PIC X(40) 40-column width")
    void titlesArePicX40FixedWidth() {
        // The 3270/BMS banner contract requires the exact 40-column width (leading/trailing
        // spaces preserved), independent of the trimmed logical text asserted above.
        assertEquals(40, ScreenTitles.CCDA_TITLE01.length());
        assertEquals(40, ScreenTitles.CCDA_TITLE02.length());
        assertEquals(40, ScreenTitles.CCDA_THANK_YOU.length());
    }

    @Test
    @DisplayName("Commented-out COBOL title is not migrated into any constant")
    void commentedOutTitleIsNotMigrated() throws IllegalAccessException {
        // Negative guard: reflectively scan every public static final String field of ScreenTitles
        // and assert none carries the commented-out COBOL alternative (COTTL01Y.cpy line 21).
        // Public fields are readable via Field#get without setAccessible.
        for (Field f : ScreenTitles.class.getDeclaredFields()) {
            if (f.getType() == String.class && Modifier.isStatic(f.getModifiers())) {
                String value = (String) f.get(null);
                assertFalse(value.contains(FORBIDDEN_COMMENTED_TEXT),
                        "Commented-out COBOL literal must not be migrated: " + f.getName());
            }
        }
    }

    // ---------------------------------------------------------------------
    // Phase 4 — TITLE_LENGTH width constant (present in production class)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("TITLE_LENGTH constant equals the PIC X(40) width")
    void titleLengthConstantIs40() {
        assertEquals(40, ScreenTitles.TITLE_LENGTH);
    }

    // ---------------------------------------------------------------------
    // Phase 5 — class-shape sanity (mirror the non-instantiable holder design)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("ScreenTitles is a final constants holder")
    void screenTitlesHolderIsFinal() {
        // Mirrors the production design: a final, non-instantiable constants holder.
        assertTrue(Modifier.isFinal(ScreenTitles.class.getModifiers()));
    }
}
