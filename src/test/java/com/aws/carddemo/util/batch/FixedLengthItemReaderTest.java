package com.aws.carddemo.util.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Independent, source-derived adversarial unit suite for the undelimited fixed-block
 * ({@code RECFM=FB}) <em>input</em> framing contract (review findings&nbsp;#17 and&nbsp;#27).
 *
 * <p>{@link FixedLengthItemReader} frames a delimiter-free byte image into contiguous
 * {@code recordLength}-byte records, returning {@code null} at a clean record-boundary EOF and
 * failing fast on a partial trailing block &mdash; the classic symptom of feeding line-delimited
 * text to a reader that expects a native undelimited {@code RECFM=FB} dataset. Before this suite the
 * framing was exercised only indirectly through {@code PostTransactionJobConfigIT}; these tests pin
 * the contract directly, including the negative cases.</p>
 *
 * <p><strong>Origin / parity:</strong> net-new verification infrastructure supporting the fixed-block
 * feed/reject datasets of the legacy batch programs retained read-only under {@code legacy/**} (e.g.
 * the {@code DALYTRAN}/{@code DALYREJS} fixed-length records of {@code CBTRN02C.cbl}).</p>
 */
@DisplayName("FixedLengthItemReader: undelimited RECFM=FB input framing (#17)")
class FixedLengthItemReaderTest {

    private static final Function<byte[], String> ISO_MAPPER =
            record -> new String(record, StandardCharsets.ISO_8859_1);

    private static FixedLengthItemReader<String> reader(byte[] image, int recordLength) {
        return new FixedLengthItemReader<>("test-reader",
                new ByteArrayResource(image), recordLength, ISO_MAPPER);
    }

    private static List<String> drain(FixedLengthItemReader<String> reader) throws Exception {
        List<String> items = new ArrayList<>();
        reader.open(new ExecutionContext());
        try {
            String item;
            while ((item = reader.read()) != null) {
                items.add(item);
            }
        } finally {
            reader.close();
        }
        return items;
    }

    @Test
    @DisplayName("splits a delimiter-free image into exact fixed-length records, EOF at the boundary")
    void splitsUndelimitedImageIntoExactRecords() throws Exception {
        // Fifteen bytes, three 5-byte records, and crucially NO delimiter of any kind between them.
        byte[] image = "AAAAABBBBBCCCCC".getBytes(StandardCharsets.ISO_8859_1);

        List<String> records = drain(reader(image, 5));

        assertThat(records).containsExactly("AAAAA", "BBBBB", "CCCCC");
        // Every record handed to the mapper is exactly recordLength bytes (full fixed block).
        assertThat(records).allSatisfy(r ->
                assertThat(r.getBytes(StandardCharsets.ISO_8859_1)).hasSize(5));
    }

    @Test
    @DisplayName("an empty image yields no records (immediate clean EOF)")
    void emptyImageYieldsNoRecords() throws Exception {
        assertThat(drain(reader(new byte[0], 5))).isEmpty();
    }

    @Test
    @DisplayName("a partial trailing block is rejected as a malformed / line-delimited image")
    void rejectsPartialTrailingBlock() {
        // A 5-char line plus a newline is 6 bytes: the reader consumes the first 5-byte record, then
        // sees a 1-byte remainder -- exactly the line-delimited-text-vs-undelimited-FB mistake.
        byte[] delimitedText = "ABCDE\n".getBytes(StandardCharsets.ISO_8859_1);
        FixedLengthItemReader<String> reader = reader(delimitedText, 5);

        assertThatExceptionOfType(ItemStreamException.class)
                .isThrownBy(() -> drain(reader))
                .withMessageContaining("trailing partial record of 1 byte")
                .withMessageContaining("line-delimited");
    }

    @Test
    @DisplayName("strict mode fails fast when the input resource is missing (COBOL OPEN failure)")
    void strictMissingResourceFailsOnOpen(@TempDir Path tempDir) {
        Resource missing = new FileSystemResource(tempDir.resolve("does-not-exist.dat"));
        FixedLengthItemReader<String> reader = new FixedLengthItemReader<>(
                "test-reader", missing, 5, ISO_MAPPER);

        // AbstractItemCountingItemStreamItemReader.open() wraps doOpen()'s failure as
        // ItemStreamException("Failed to initialize the reader", cause), so the RECFM=FB diagnostic
        // is carried on the cause -- the reader still fails fast on a missing strict resource.
        assertThatExceptionOfType(ItemStreamException.class)
                .isThrownBy(() -> reader.open(new ExecutionContext()))
                .havingCause()
                .withMessageContaining("must exist and be readable");
    }

    @Test
    @DisplayName("non-strict mode opens an empty reader when the input resource is missing")
    void nonStrictMissingResourceOpensEmpty(@TempDir Path tempDir) throws Exception {
        Resource missing = new FileSystemResource(tempDir.resolve("does-not-exist.dat"));
        FixedLengthItemReader<String> reader = new FixedLengthItemReader<>(
                "test-reader", missing, 5, ISO_MAPPER);
        reader.setStrict(false);

        reader.open(new ExecutionContext());
        try {
            assertThat(reader.read()).isNull();
        } finally {
            reader.close();
        }
    }

    @Test
    @DisplayName("constructor rejects a non-positive record length")
    void constructorRejectsNonPositiveRecordLength() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FixedLengthItemReader<>(
                        "test-reader", new ByteArrayResource(new byte[0]), 0, ISO_MAPPER))
                .withMessageContaining("recordLength must be positive");
    }
}
