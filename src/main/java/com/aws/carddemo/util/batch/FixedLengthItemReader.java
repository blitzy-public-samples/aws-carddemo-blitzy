package com.aws.carddemo.util.batch;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.support.AbstractItemCountingItemStreamItemReader;
import org.springframework.core.io.Resource;

/**
 * Restart-safe {@link org.springframework.batch.item.ItemReader} for <em>undelimited</em>,
 * fixed-block ({@code RECFM=FB}) datasets &mdash; the record framing every legacy AWS CardDemo
 * sequential file actually uses.
 *
 * <p><b>Why this exists (review finding&nbsp;#17).</b> A mainframe {@code RECFM=FB} dataset is a
 * flat image of <em>N&nbsp;&times;&nbsp;recordLength</em> bytes with <strong>no</strong> record
 * delimiter: there is no line feed, carriage return, or length prefix between records. The native
 * EBCDIC datasets retained under {@code legacy/data/EBCDIC/*.PS} are exactly this &mdash; for
 * example {@code AWS.M2.CARDDEMO.DALYTRAN.PS} is {@code 105000 = 300 &times; 350} bytes with zero
 * line-feed bytes. Spring Batch's {@link org.springframework.batch.item.file.FlatFileItemReader}
 * instead splits input on a {@code RecordSeparatorPolicy} (line feeds), which silently assumes a
 * text framing the real datasets do not have. This reader consumes the raw fixed-block image
 * directly, so the daily-transaction feed and every other flat file are parsed byte-for-byte as the
 * COBOL {@code READ ... INTO} did (AAP &sect;0.6.4 external-interface parity).</p>
 *
 * <p><b>Framing contract.</b> The input is read as contiguous {@code recordLength}-byte blocks. A
 * clean end-of-file at a record boundary ends the stream (mirroring COBOL {@code FILE STATUS '10'}).
 * A partial trailing block (a non-zero number of bytes that is fewer than {@code recordLength}) is a
 * malformed FB image and raises {@link ItemStreamException}; the message hints at the common cause
 * (a line-delimited text file supplied where an undelimited FB image is required). No delimiter is
 * ever stripped, so a legitimate {@code 0x0A}/{@code 0x0D} byte occurring inside a packed-decimal or
 * text field is preserved rather than being misread as a record boundary.</p>
 *
 * <p><b>Encoding.</b> This reader is byte-oriented: it hands raw {@code byte[]} records to the
 * supplied {@code recordMapper}. The mapper (typically backed by a
 * {@link com.aws.carddemo.util.FixedWidthRecordMapper}) owns any character decoding, which for
 * CardDemo is ISO-8859-1 so that every byte round-trips exactly.</p>
 *
 * <p><b>Restartability (review finding&nbsp;#19).</b> The reader extends
 * {@link AbstractItemCountingItemStreamItemReader}, so the number of items already read is persisted
 * in the step {@link ExecutionContext} (when {@link #setSaveState(boolean) saveState} is
 * {@code true}, the default). On restart {@link #jumpToItem(int)} repositions the freshly opened
 * stream to the exact byte offset of the next unread record ({@code itemIndex &times; recordLength}),
 * so a restarted step resumes precisely where the failed execution stopped without re-reading or
 * skipping records.</p>
 *
 * <p><b>Step scoping (CGLIB target-class proxy).</b> This reader is wired as a {@code @StepScope}
 * bean (see {@link com.aws.carddemo.batch.PostTransactionJobConfig}); Spring creates a step-scoped
 * proxy so that {@code @Value("#{jobParameters['...']}")} paths resolve lazily per step execution.
 * {@code @StepScope} uses {@code ScopedProxyMode.TARGET_CLASS}, so the proxy is a CGLIB subclass of
 * the <em>declared bean type</em>. Accordingly this class is <em>not</em> declared {@code final} so
 * CGLIB can subclass it; only its <em>fields</em> are {@code final} (the reader's configuration is
 * immutable), and Spring instantiates the proxy via Objenesis, so no no-arg constructor is needed.
 * The {@code @StepScope} factory method therefore declares the <em>concrete</em>
 * {@code FixedLengthItemReader} return type: this lets Spring Batch inspect the bean for listener
 * annotations, avoiding the startup warning an interface-typed bean would otherwise emit
 * (&quot;{@code org.springframework.batch.item.ItemStreamReader is an interface. The implementing
 * class will not be queried for annotation based listener configurations...}&quot;, review
 * finding&nbsp;#35), and the CGLIB proxy still inherits
 * {@link org.springframework.batch.item.ItemStream ItemStream} so the step registers it for
 * open/close/update and restart bookkeeping.</p>
 *
 * @param <T> the mapped item type produced from each fixed-length record
 */
public class FixedLengthItemReader<T> extends AbstractItemCountingItemStreamItemReader<T> {

    private final Resource resource;
    private final int recordLength;
    private final transient Function<byte[], T> recordMapper;
    private boolean strict = true;

    private transient InputStream inputStream;

    /**
     * Creates a fixed-block reader.
     *
     * @param name         the reader name; also the {@link ExecutionContext} key prefix used for
     *                     restart bookkeeping (must be unique within a step). Must not be
     *                     {@code null}.
     * @param resource     the {@code RECFM=FB} resource to read. Must not be {@code null}.
     * @param recordLength the exact fixed record length in bytes. Must be positive.
     * @param recordMapper maps one raw {@code recordLength}-byte record to an item of type
     *                     {@code T}. Must not be {@code null}.
     * @throws IllegalArgumentException if {@code recordLength} is not positive
     * @throws NullPointerException     if any reference argument is {@code null}
     */
    // FixedLengthItemReader is intentionally NON-FINAL so Spring can build a CGLIB
    // target-class proxy for the @StepScope reader bean (see #35 and
    // PostTransactionJobConfig#dailyTransactionReader); a final class would force an
    // interface-typed bean and emit the "@StepScope ... should return the implementing
    // class" warning under -Werror.
    //
    // The lone super.setName(...) below is the only call on the partially-constructed
    // instance and is provably escape-free: (1) it is a NON-VIRTUAL super invocation, so no
    // subclass override can observe partial state (and no subclass exists); and (2)
    // AbstractItemCountingItemStreamItemReader.setName only stores the name in an internal
    // ExecutionContextUserSupport field, invoking no overridable method on 'this'. javac's
    // conservative this-escape lint cannot verify (2) across the compiled library boundary,
    // so we suppress it narrowly on this constructor. Relocating setName out of the
    // constructor was rejected because it would weaken this reusable reader's
    // name-required-at-construction contract (restart bookkeeping depends on the name).
    @SuppressWarnings("this-escape")
    public FixedLengthItemReader(String name, Resource resource, int recordLength,
            Function<byte[], T> recordMapper) {
        this.resource = Objects.requireNonNull(resource, "resource must not be null");
        if (recordLength <= 0) {
            throw new IllegalArgumentException("recordLength must be positive but was " + recordLength);
        }
        this.recordLength = recordLength;
        this.recordMapper = Objects.requireNonNull(recordMapper, "recordMapper must not be null");
        // Bind directly to the superclass implementation (non-virtual) to set the reader
        // name / ExecutionContext key prefix used for restart bookkeeping.
        super.setName(Objects.requireNonNull(name, "name must not be null"));
    }

    /**
     * Controls whether a missing input resource is an error. When {@code true} (the default), opening
     * a non-existent or unreadable resource throws {@link ItemStreamException}, mirroring a COBOL
     * {@code OPEN} failure; when {@code false}, a missing resource yields an immediately exhausted
     * reader (no records).
     *
     * @param strict {@code true} to fail on a missing/unreadable resource
     */
    public void setStrict(boolean strict) {
        this.strict = strict;
    }

    /**
     * Opens the underlying resource stream. A missing or unreadable resource fails fast when
     * {@link #setStrict(boolean) strict} (the default); otherwise the reader opens empty.
     *
     * @throws ItemStreamException if the resource is required but cannot be opened
     */
    @Override
    protected void doOpen() {
        if (!resource.exists() || !resource.isReadable()) {
            if (strict) {
                throw new ItemStreamException(
                        "Input resource must exist and be readable (RECFM=FB): "
                                + resource.getDescription());
            }
            this.inputStream = null;
            return;
        }
        try {
            this.inputStream = new BufferedInputStream(resource.getInputStream());
        } catch (IOException ex) {
            throw new ItemStreamException(
                    "Failed to open input resource: " + resource.getDescription(), ex);
        }
    }

    /**
     * Reads exactly one fixed-length record.
     *
     * @return the mapped item, or {@code null} at a clean end-of-file (record boundary)
     * @throws Exception            if the mapper fails
     * @throws ItemStreamException  if a partial trailing record is encountered (malformed FB image)
     */
    @Override
    protected T doRead() throws Exception {
        if (inputStream == null) {
            return null;
        }
        byte[] record = new byte[recordLength];
        int filled = readFully(inputStream, record);
        if (filled == 0) {
            return null; // clean EOF at a record boundary (COBOL FILE STATUS '10')
        }
        if (filled < recordLength) {
            throw new ItemStreamException("Malformed fixed-block input " + resource.getDescription()
                    + ": trailing partial record of " + filled + " byte(s) is not the expected "
                    + recordLength + "-byte record length (is the input line-delimited text rather "
                    + "than an undelimited RECFM=FB image?)");
        }
        return recordMapper.apply(record);
    }

    /**
     * Closes the underlying resource stream.
     *
     * @throws ItemStreamException if the stream cannot be closed
     */
    @Override
    protected void doClose() {
        InputStream toClose = this.inputStream;
        this.inputStream = null;
        if (toClose != null) {
            try {
                toClose.close();
            } catch (IOException ex) {
                throw new ItemStreamException(
                        "Failed to close input resource: " + resource.getDescription(), ex);
            }
        }
    }

    /**
     * Repositions the freshly opened stream to the start of record {@code itemIndex} on restart by
     * skipping exactly {@code itemIndex &times; recordLength} bytes. Overriding the default (which
     * re-reads and discards items) keeps restart positioning byte-exact and avoids re-invoking the
     * record mapper.
     *
     * @param itemIndex the zero-based index of the next record to read
     * @throws Exception if the stream cannot be advanced by the required number of bytes
     */
    @Override
    protected void jumpToItem(int itemIndex) throws Exception {
        if (inputStream == null || itemIndex <= 0) {
            return;
        }
        long toSkip = (long) itemIndex * recordLength;
        while (toSkip > 0) {
            long skipped = inputStream.skip(toSkip);
            if (skipped <= 0) {
                // skip() made no progress; fall back to reading bytes to advance the position.
                if (inputStream.read() < 0) {
                    throw new ItemStreamException("Unable to restart " + getName() + ": input "
                            + resource.getDescription() + " ended before record index " + itemIndex);
                }
                skipped = 1;
            }
            toSkip -= skipped;
        }
    }

    /**
     * Reads bytes into {@code buffer} until it is full or the stream ends.
     *
     * @param in     the source stream
     * @param buffer the destination buffer
     * @return the number of bytes read (equal to {@code buffer.length} on a full record, {@code 0}
     *         at a clean EOF, or a value in between for a partial trailing record)
     * @throws IOException if the underlying read fails
     */
    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        return offset;
    }
}
