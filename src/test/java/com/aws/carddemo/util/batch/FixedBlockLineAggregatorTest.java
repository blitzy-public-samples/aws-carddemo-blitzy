package com.aws.carddemo.util.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.file.transform.LineAggregator;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Independent, source-derived adversarial unit suite for the undelimited fixed-block
 * ({@code RECFM=FB}) <em>output</em> framing contract (review findings&nbsp;#17 and&nbsp;#27).
 *
 * <p>{@link FixedBlockLineAggregator} is the write-side guarantor that every emitted record is
 * exactly {@code recordLength} bytes so that a {@code FlatFileItemWriter} configured with an empty
 * line separator produces a byte-for-byte fixed-block image (no delimiter between records). Before
 * this suite the contract was exercised only indirectly through {@code PostTransactionJobConfigIT};
 * these tests pin it directly, including the negative cases that would silently corrupt every
 * subsequent record boundary if the guard were removed.</p>
 *
 * <p><strong>Origin / parity:</strong> net-new verification infrastructure supporting the
 * {@code RECFM=FB} reject/report datasets of the legacy batch programs retained read-only under
 * {@code legacy/**} (e.g. {@code CBTRN02C.cbl} rejects, {@code CBTRN03C.cbl} report lines).</p>
 */
@DisplayName("FixedBlockLineAggregator: exact-length, undelimited RECFM=FB output framing (#17)")
class FixedBlockLineAggregatorTest {

    /** A trivial delegate that returns a preset line regardless of the (ignored) item. */
    private static LineAggregator<Object> delegateReturning(String line) {
        return item -> line;
    }

    @Test
    @DisplayName("an exactly-sized line passes through unchanged with no delimiter appended")
    void exactLengthLinePassesThroughUnchanged() {
        FixedBlockLineAggregator<Object> aggregator =
                new FixedBlockLineAggregator<>(delegateReturning("ABCDE"), 5, StandardCharsets.ISO_8859_1);

        String result = aggregator.aggregate(new Object());

        assertThat(result).isEqualTo("ABCDE");
        // The undelimited contract: the emitted record is exactly recordLength bytes, with no
        // trailing newline or any other separator that would shift the next record boundary.
        assertThat(result.getBytes(StandardCharsets.ISO_8859_1)).hasSize(5);
        assertThat(result).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    @DisplayName("a short line is rejected (a delimiter-shortened record would corrupt framing)")
    void shortLineIsRejected() {
        FixedBlockLineAggregator<Object> aggregator =
                new FixedBlockLineAggregator<>(delegateReturning("ABC"), 5, StandardCharsets.ISO_8859_1);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> aggregator.aggregate(new Object()))
                .withMessageContaining("must be exactly 5 bytes")
                .withMessageContaining("was 3");
    }

    @Test
    @DisplayName("a long line is rejected (an over-length record would corrupt framing)")
    void longLineIsRejected() {
        FixedBlockLineAggregator<Object> aggregator =
                new FixedBlockLineAggregator<>(delegateReturning("ABCDEF"), 5, StandardCharsets.ISO_8859_1);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> aggregator.aggregate(new Object()))
                .withMessageContaining("must be exactly 5 bytes")
                .withMessageContaining("was 6");
    }

    @Test
    @DisplayName("length is measured in bytes, not chars (multi-byte charset)")
    void lengthIsMeasuredInBytesNotChars() {
        // "é" is one char but two bytes in UTF-8. A char-count check would wrongly accept it at
        // recordLength 1; the byte-accurate check must reject it and accept it only at length 2.
        Charset utf8 = StandardCharsets.UTF_8;

        FixedBlockLineAggregator<Object> tooTight =
                new FixedBlockLineAggregator<>(delegateReturning("\u00e9"), 1, utf8);
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> tooTight.aggregate(new Object()))
                .withMessageContaining("was 2");

        FixedBlockLineAggregator<Object> exact =
                new FixedBlockLineAggregator<>(delegateReturning("\u00e9"), 2, utf8);
        assertThat(exact.aggregate(new Object()).getBytes(utf8)).hasSize(2);
    }

    @Test
    @DisplayName("constructor rejects a non-positive record length and null collaborators")
    void constructorValidatesArguments() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FixedBlockLineAggregator<>(delegateReturning("x"), 0,
                        StandardCharsets.ISO_8859_1));
        assertThatNullPointerException()
                .isThrownBy(() -> new FixedBlockLineAggregator<>(null, 5, StandardCharsets.ISO_8859_1));
        assertThatNullPointerException()
                .isThrownBy(() -> new FixedBlockLineAggregator<>(delegateReturning("x"), 5, null));
    }
}
