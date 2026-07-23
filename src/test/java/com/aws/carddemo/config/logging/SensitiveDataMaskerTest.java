package com.aws.carddemo.config.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link SensitiveDataMasker} &mdash; the log-sink PAN redactor that closes QA
 * finding F-1 (a 16-digit card number embedded in a framework {@code FlatFileParseException} message
 * leaking into ERROR logs).
 *
 * <p>The tests pin the masking contract (mask every run of &ge;13 digits to first-6/last-4, leave
 * shorter identifiers untouched), the real-world fixture shapes observed during the F-1 runtime
 * reproduction (a 16-digit card immediately followed by the timestamp year forming a 20-digit run,
 * and the 22-digit id+type+category prefix), the &ldquo;nothing changed&rdquo; reference-equality
 * fast path relied on by {@link MaskingLogbackEncoder}, and the invariant that no complete PAN can
 * survive masking.</p>
 */
class SensitiveDataMaskerTest {

    /** The exact card PAN used in the F-1 runtime reproduction fixture. */
    private static final String PAN = "4111222233334444";

    @Test
    @DisplayName("A bounded 16-digit PAN is masked to first-6 + last-4 with the middle redacted")
    void masksBoundedSixteenDigitPan() {
        String masked = SensitiveDataMasker.mask("card=" + PAN + " end");

        assertThat(masked).isEqualTo("card=411122******4444 end");
        assertThat(masked).doesNotContain(PAN);
    }

    @Test
    @DisplayName("A 20-digit run (card + timestamp year, as in a real DALYTRAN record) is fully "
            + "neutralized so the embedded PAN cannot be reconstructed")
    void masksConcatenatedCardAndYearRun() {
        // In dailytran.txt the 16-digit card is immediately followed by the origTs year 2022.
        String masked = SensitiveDataMasker.mask("input=[..72112     " + PAN + "2022-06-10 19:27]");

        assertThat(masked).isEqualTo("input=[..72112     411122**********2022-06-10 19:27]");
        assertThat(masked).doesNotContain(PAN);
        // The PAN's own last four (4444) fall inside the masked middle of the 20-run, so only the
        // BIN (411122) is ever visible.
        assertThat(masked).doesNotContain("4444");
    }

    @Test
    @DisplayName("The 22-digit id+type+category prefix of a raw record is masked to first-6/last-4")
    void masksLongIdPrefixRun() {
        String masked = SensitiveDataMasker.mask("0000000000683580010001POS TERM");

        assertThat(masked).isEqualTo("000000************0001POS TERM");
    }

    @Test
    @DisplayName("A run of exactly the 13-digit PAN floor keeps 6+4 and masks the remaining 3")
    void masksThirteenDigitFloorRun() {
        String masked = SensitiveDataMasker.mask("x1234567890123y");

        // 13 digits -> keep 123456 + *** + 0123
        assertThat(masked).isEqualTo("x123456***0123y");
        assertThat(masked).hasSameSizeAs("x1234567890123y");
    }

    @Test
    @DisplayName("Masking preserves the run length (count-preserving) for a long concatenated run")
    void maskingPreservesRunLength() {
        String digits = "1".repeat(40);
        String masked = SensitiveDataMasker.mask(digits);

        assertThat(masked).hasSize(40);
        assertThat(masked).startsWith("111111");
        assertThat(masked).endsWith("1111");
        assertThat(masked.chars().filter(c -> c == '*').count()).isEqualTo(30L);
    }

    @ParameterizedTest
    @DisplayName("Identifiers shorter than 13 digits (account id, merchant id, amount, CVV, SSN, "
            + "12-digit run) are never masked")
    @ValueSource(strings = {
            "00000000001",      // account id 9(11) -> 11 digits
            "123456789",        // customer / merchant id 9(9) -> 9 digits
            "12345678901",      // 11-digit amount digits
            "123",              // CVV
            "987654321",        // SSN-length 9 digits
            "123456789012"      // 12 digits -> one below the 13 floor
    })
    void leavesShortIdentifiersUntouched(String shortNumber) {
        String input = "id=" + shortNumber + ";";
        assertThat(SensitiveDataMasker.mask(input)).isEqualTo(input);
    }

    @Test
    @DisplayName("Multiple qualifying runs in one line are all masked")
    void masksEveryRunInTheLine() {
        String masked = SensitiveDataMasker.mask(PAN + " and 9876543210987654");

        assertThat(masked).isEqualTo("411122******4444 and 987654******7654");
        assertThat(masked).doesNotContain(PAN);
        assertThat(masked).doesNotContain("9876543210987654");
    }

    @Test
    @DisplayName("Text with no long digit run is returned as the SAME instance (fast path)")
    void returnsSameInstanceWhenNothingMasked() {
        String input = "Encountered an error executing step postTransactionStep (acct 12345)";
        assertThat(SensitiveDataMasker.mask(input)).isSameAs(input);
    }

    @Test
    @DisplayName("Null and empty inputs are returned unchanged")
    void handlesNullAndEmpty() {
        assertThat(SensitiveDataMasker.mask(null)).isNull();
        String empty = "";
        assertThat(SensitiveDataMasker.mask(empty)).isSameAs(empty);
    }

    @Test
    @DisplayName("Non-ASCII / multi-byte characters around a PAN are preserved verbatim")
    void preservesNonAsciiCharactersAroundPan() {
        String input = "\u20ac\u00e9 " + PAN + " \u00fc\u732b";
        String masked = SensitiveDataMasker.mask(input);

        assertThat(masked).isEqualTo("\u20ac\u00e9 411122******4444 \u00fc\u732b");
        assertThat(masked).doesNotContain(PAN);
    }

    @Test
    @DisplayName("After masking, no run of 13+ consecutive digits remains anywhere in the output")
    void noLongDigitRunRemainsAfterMasking() {
        String rawEcsMessage = "Parsing error at line: 1 in resource=[file [/tmp/x.txt]], "
                + "input=[0000000000683580010001POS TERM  ....72112     " + PAN + "2022-06-10 19:27:53]";

        String masked = SensitiveDataMasker.mask(rawEcsMessage);

        Matcher longRun = Pattern.compile("\\d{13,}").matcher(masked);
        assertThat(longRun.find())
                .as("a >=13-digit run survived masking: %s", masked)
                .isFalse();
        assertThat(masked).doesNotContain(PAN);
    }
}
