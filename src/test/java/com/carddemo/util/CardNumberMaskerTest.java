package com.carddemo.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CardNumberMasker}.
 *
 * <p>Verifies the PAN masking policy used by F8/F9/F11 sensitive-data-exposure remediation:
 * single-value masking ({@link CardNumberMasker#mask(String)}) and free-text masking
 * ({@link CardNumberMasker#maskInMessage(String)}), including null/short/blank handling,
 * embedded PANs, multiple PANs, non-PAN numeric tokens left intact, and legitimate business
 * messages preserved verbatim.</p>
 */
class CardNumberMaskerTest {

    @Nested
    @DisplayName("mask(String)")
    class Mask {

        @Test
        @DisplayName("masks a full 16-digit PAN revealing only the last four digits")
        void masksFullPan() {
            assertThat(CardNumberMasker.mask("4111111111111111")).isEqualTo("************1111");
        }

        @Test
        @DisplayName("reveals exactly the last four characters for arbitrary length")
        void revealsLastFour() {
            // 16-digit card: 12 stars + last 4
            String masked = CardNumberMasker.mask("1234567890123456");
            assertThat(masked).hasSize(16);
            assertThat(masked).endsWith("3456");
            assertThat(masked).startsWith("************");
            assertThat(masked).doesNotContain("1234567890");
        }

        @Test
        @DisplayName("masks a short value (<= 4 chars) in full, revealing nothing")
        void masksShortValueInFull() {
            assertThat(CardNumberMasker.mask("1234")).isEqualTo("****");
            assertThat(CardNumberMasker.mask("12")).isEqualTo("**");
        }

        @Test
        @DisplayName("returns the fixed placeholder for null")
        void masksNull() {
            assertThat(CardNumberMasker.mask(null)).isEqualTo("****");
        }

        @Test
        @DisplayName("returns the fixed placeholder for blank/empty input")
        void masksBlank() {
            assertThat(CardNumberMasker.mask("")).isEqualTo("****");
            assertThat(CardNumberMasker.mask("   ")).isEqualTo("****");
        }

        @Test
        @DisplayName("trims surrounding whitespace before masking")
        void trimsBeforeMasking() {
            assertThat(CardNumberMasker.mask("  4111111111111111  ")).isEqualTo("************1111");
        }
    }

    @Nested
    @DisplayName("maskInMessage(String)")
    class MaskInMessage {

        @Test
        @DisplayName("masks a PAN embedded in free text")
        void masksEmbeddedPan() {
            String in = "No customer found for xref card 4111111111111111 during statement build";
            String out = CardNumberMasker.maskInMessage(in);
            assertThat(out).isEqualTo(
                    "No customer found for xref card ************1111 during statement build");
            assertThat(out).doesNotContain("4111111111111111");
        }

        @Test
        @DisplayName("masks multiple PANs in a single message")
        void masksMultiplePans() {
            String in = "card 4111111111111111 and card 5500000000000004 mismatch";
            String out = CardNumberMasker.maskInMessage(in);
            assertThat(out).isEqualTo("card ************1111 and card ************0004 mismatch");
        }

        @Test
        @DisplayName("leaves non-PAN numeric tokens (account ids, codes, amounts) untouched")
        void leavesShortNumbersUntouched() {
            // 11-digit account id, a short code, and a money amount must NOT be masked.
            String in = "Account 00000000011 code 102 amount 1234.56 rejected";
            String out = CardNumberMasker.maskInMessage(in);
            assertThat(out).isEqualTo(in);
        }

        @Test
        @DisplayName("returns a legitimate business message verbatim when no PAN is present")
        void preservesBusinessMessage() {
            String in = "You have nothing to pay...";
            assertThat(CardNumberMasker.maskInMessage(in)).isEqualTo(in);
        }

        @Test
        @DisplayName("does not partially mask an over-long digit run (20+ digits)")
        void doesNotMaskOverlongRun() {
            // 22-digit run is outside the 13-19 PAN range and must be left intact.
            String in = "ref 1234567890123456789012 end";
            assertThat(CardNumberMasker.maskInMessage(in)).isEqualTo(in);
        }

        @Test
        @DisplayName("returns null for null input")
        void returnsNullForNull() {
            assertThat(CardNumberMasker.maskInMessage(null)).isNull();
        }
    }

    @Nested
    @DisplayName("class contract")
    class ClassContract {

        @Test
        @DisplayName("cannot be instantiated via reflection")
        void cannotInstantiate() throws NoSuchMethodException {
            Constructor<CardNumberMasker> ctor = CardNumberMasker.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            InvocationTargetException ex =
                    assertThrows(InvocationTargetException.class, ctor::newInstance);
            assertThat(ex.getCause()).isInstanceOf(AssertionError.class);
        }
    }
}
