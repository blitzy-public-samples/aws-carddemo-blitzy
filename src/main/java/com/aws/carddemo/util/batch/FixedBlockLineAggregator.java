package com.aws.carddemo.util.batch;

import java.nio.charset.Charset;
import java.util.Objects;

import org.springframework.batch.item.file.transform.LineAggregator;

/**
 * A {@link LineAggregator} decorator that enforces the exact fixed record length of an
 * <em>undelimited</em> ({@code RECFM=FB}) output dataset (review finding&nbsp;#17).
 *
 * <p>When a Spring Batch {@link org.springframework.batch.item.file.FlatFileItemWriter} is
 * configured with an empty line separator ({@code lineSeparator("")}), it emits each aggregated line
 * back-to-back with no delimiter &mdash; exactly the fixed-block framing of a mainframe
 * {@code RECFM=FB} dataset. For the resulting file to match the native EBCDIC image byte-for-byte,
 * every aggregated record <strong>must</strong> be exactly {@code recordLength} bytes in the writer's
 * charset. This decorator wraps the real aggregator and validates that invariant on every record,
 * failing fast (rather than silently emitting a short or long block that would corrupt all
 * subsequent record boundaries) if a caller produces a mis-sized line.</p>
 *
 * <p>The length is measured in <em>bytes</em> using the same {@link Charset} the writer uses, so the
 * check is exact even though the underlying {@link String} length (char count) can differ from the
 * byte count in a multi-byte charset. CardDemo writes ISO-8859-1, where the two coincide, but the
 * byte-accurate check is kept general and correct.</p>
 *
 * @param <T> the item type aggregated into a fixed-width record
 */
public final class FixedBlockLineAggregator<T> implements LineAggregator<T> {

    private final LineAggregator<T> delegate;
    private final int recordLength;
    private final Charset charset;

    /**
     * Creates a length-validating aggregator.
     *
     * @param delegate     the wrapped aggregator that renders an item to its fixed-width line; must
     *                     not be {@code null}
     * @param recordLength the required exact record length in bytes; must be positive
     * @param charset      the charset the writer uses to encode the line to bytes; must not be
     *                     {@code null}
     * @throws IllegalArgumentException if {@code recordLength} is not positive
     * @throws NullPointerException     if {@code delegate} or {@code charset} is {@code null}
     */
    public FixedBlockLineAggregator(LineAggregator<T> delegate, int recordLength, Charset charset) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        if (recordLength <= 0) {
            throw new IllegalArgumentException("recordLength must be positive but was " + recordLength);
        }
        this.recordLength = recordLength;
        this.charset = Objects.requireNonNull(charset, "charset must not be null");
    }

    /**
     * Aggregates {@code item} via the delegate and verifies the result is exactly the configured
     * record length in bytes.
     *
     * @param item the item to render
     * @return the delegate's fixed-width line (guaranteed to be exactly {@code recordLength} bytes)
     * @throws IllegalStateException if the delegate produces a line whose byte length is not exactly
     *                               {@code recordLength}
     */
    @Override
    public String aggregate(T item) {
        String line = delegate.aggregate(item);
        int actual = line.getBytes(charset).length;
        if (actual != recordLength) {
            throw new IllegalStateException("Fixed-block record must be exactly " + recordLength
                    + " bytes but was " + actual + " (undelimited RECFM=FB framing would be corrupted)");
        }
        return line;
    }
}
