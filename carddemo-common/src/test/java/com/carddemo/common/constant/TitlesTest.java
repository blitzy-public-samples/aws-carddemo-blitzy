package com.carddemo.common.constant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spec-literal verification of the CardDemo screen-title constants.
 *
 * :purpose: Verify that the verbatim, fixed-width ``Titles`` screen-title
 *     constants transformed from COBOL copybook ``COTTL01Y`` (group
 *     ``01 CCDA-SCREEN-TITLE``) preserve their original ``PIC X(40)`` values and
 *     their exact 40-character width.
 * :output: JUnit 5 / AssertJ assertions only; the class holds no state and touches
 *     no database, Spring context, or other external resource (pure-logic test).
 */
final class TitlesTest {

    @Test
    @DisplayName("CCDA_TITLE01 is the verbatim 40-character AWS Mainframe Modernization banner")
    void ccdaTitle01IsVerbatim() {
        assertThat(Titles.CCDA_TITLE01)
                .isEqualTo("      AWS Mainframe Modernization       ")
                .hasSize(40);
    }

    @Test
    @DisplayName("CCDA_TITLE02 is the active 40-character CardDemo literal")
    void ccdaTitle02IsActiveCardDemoLiteral() {
        assertThat(Titles.CCDA_TITLE02)
                .isEqualTo("              CardDemo                  ")
                .hasSize(40);
    }

    @Test
    @DisplayName("CCDA_TITLE02 uses the active literal, not the COBOL-commented alternate")
    void ccdaTitle02IgnoresCommentedAlternate() {
        assertThat(Titles.CCDA_TITLE02).doesNotContain("Credit Card Demo Application");
    }

    @Test
    @DisplayName("CCDA_THANK_YOU is the verbatim 40-character sign-off line")
    void ccdaThankYouIsVerbatim() {
        assertThat(Titles.CCDA_THANK_YOU)
                .isEqualTo("Thank you for using CCDA application... ")
                .hasSize(40);
    }
}
