package com.blitzy.carddemo.interest.io;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.blitzy.carddemo.interest.model.TransactionRecord;

/**
 * Sequential writer for the CBACT04C interest-transaction output file (TRANSACT).
 *
 * <p>Ports CBACT04C TRANSACT output: {@code 0400-TRANFILE-OPEN} (OPEN OUTPUT,
 * {@code app/cbl/CBACT04C.cbl:L307-323}), {@code 1300-B-WRITE-TX}
 * ({@code WRITE FD-TRANFILE-REC FROM TRAN-RECORD}, {@code L500}), and
 * {@code 9400-TRANFILE-CLOSE} ({@code CLOSE TRANSACT-FILE}, {@code L595-611}). Each emitted
 * record is a {@code CVTRA05Y} {@code TRAN-RECORD} framed to exactly <strong>350</strong> bytes
 * (RECLN 350, {@code app/cpy/CVTRA05Y.cpy}) followed by a single {@code '\n'} line terminator.
 *
 * <h2>Responsibility boundary (minimal-change, AAP &sect;0.7)</h2>
 * <p>This class is a <strong>pure framing + I/O adapter</strong>. It does <em>not</em> compute or
 * default any field value: the {@link TransactionRecord} arrives fully populated from the service
 * layer, which owns TRAN-ID assembly (PARM-DATE + suffix), the {@code '01'}/{@code '05'}/
 * {@code 'System'}/{@code 'Int. for a/c '} literals, the {@code "000000000"} merchant id, the
 * space-filled merchant fields, and the DB2 timestamps ({@code 1300-B-WRITE-TX} L474-498). This
 * writer only lays those values out at the copybook offsets and writes the bytes, mirroring the
 * COBOL {@code WRITE ... FROM TRAN-RECORD} that copies the assembled record group into the FD.
 *
 * <h2>Record layout (CVTRA05Y TRAN-RECORD, 350 bytes, exact field order)</h2>
 * <p>Fourteen segments, framed left-to-right; zero-based byte offsets shown for verification. Only
 * the signed {@code TRAN-AMT} ({@code S9(09)V99}) is a zoned-decimal field and is the single field
 * routed through {@code FixedWidthCodec.encodeNumeric}/{@code ZonedDecimal}; every other field is
 * alphanumeric (or numeric-as-fixed-String already formatted by the service) and is framed with
 * {@code alpha(...)}; the trailing {@code FILLER X(20)} is 20 blanks.</p>
 * <pre>
 *  # off  wid field               COBOL / PIC                     framing
 *  1   0   16  tranId             TRAN-ID            X(16)         alpha
 *  2  16    2  typeCd             TRAN-TYPE-CD       X(02) ='01'   alpha
 *  3  18    4  catCd              TRAN-CAT-CD        9(04) ='0005' alpha
 *  4  22   10  source             TRAN-SOURCE        X(10) ='System' alpha
 *  5  32  100  description        TRAN-DESC          X(100)        alpha
 *  6 132   11  amount             TRAN-AMT           S9(09)V99     numeric (zoned-decimal)
 *  7 143    9  merchantId         TRAN-MERCHANT-ID   9(09) ='000000000' alpha
 *  8 152   50  merchantName       TRAN-MERCHANT-NAME X(50) spaces  alpha
 *  9 202   50  merchantCity       TRAN-MERCHANT-CITY X(50) spaces  alpha
 * 10 252   10  merchantZip        TRAN-MERCHANT-ZIP  X(10) spaces  alpha
 * 11 262   16  cardNum            TRAN-CARD-NUM      X(16)         alpha
 * 12 278   26  origTs             TRAN-ORIG-TS       X(26)         alpha
 * 13 304   26  procTs             TRAN-PROC-TS       X(26) ==origTs alpha (BR-15)
 * 14 330   20  FILLER             FILLER             X(20)         20 spaces
 *                                                    total = 350
 * </pre>
 *
 * <h2>Byte convention (golden-master stability)</h2>
 * <p>Records are written in US-ASCII (1 byte == 1 char) at their exact 350-byte width, each
 * terminated by a single {@code '\n'} (LF, never CRLF). {@code BufferedWriter.newLine()} and
 * {@code System.lineSeparator()} are deliberately avoided because they can emit CRLF on some
 * platforms; the in-repo fixtures are LF-only and byte-exact golden-master assertions require
 * {@code '\n'}. Writes occur immediately in service-emit order (no reordering).</p>
 *
 * <h2>Error semantics</h2>
 * <p>In CBACT04C an open/write/close failure displays an I/O status and performs
 * {@code 9999-ABEND-PROGRAM} ({@code CALL 'CEE3ABD'}), a fatal abend. This port mirrors that by
 * surfacing every {@link IOException} as an unchecked {@link UncheckedIOException}, so an I/O
 * failure is fatal and unrecoverable rather than silently ignored.</p>
 *
 * <p>Recommended usage is try-with-resources (the writer implements {@link Closeable}); the service
 * closes it after the final transaction is emitted. Reverse-engineered strictly from
 * {@code app/cbl/CBACT04C.cbl} and {@code app/cpy/CVTRA05Y.cpy}; strictly additive, modifies nothing
 * under {@code app/} (AAP &sect;0.7 traceability). Depends only on the JDK,
 * {@code model.TransactionRecord}, and same-package {@code FixedWidthCodec}.</p>
 */
public final class TransactionWriter implements Closeable {

    /**
     * Exact byte length of a {@code CVTRA05Y} {@code TRAN-RECORD} (RECLN 350). Every framed record
     * is asserted to this width before it is written.
     */
    private static final int TRAN_RECORD_LENGTH = 350;

    /** Field widths (bytes) of the 14 {@code TRAN-RECORD} segments, in copybook order (sum = 350). */
    private static final int W_TRAN_ID = 16;          // TRAN-ID            X(16)
    private static final int W_TYPE_CD = 2;           // TRAN-TYPE-CD       X(02)
    private static final int W_CAT_CD = 4;            // TRAN-CAT-CD        9(04)
    private static final int W_SOURCE = 10;           // TRAN-SOURCE        X(10)
    private static final int W_DESC = 100;            // TRAN-DESC          X(100)
    private static final int W_AMT_DIGITS = 11;       // TRAN-AMT           S9(09)V99 (11 stored digits)
    private static final int W_AMT_SCALE = 2;         // TRAN-AMT           implied V99 -> scale 2
    private static final int W_MERCHANT_ID = 9;       // TRAN-MERCHANT-ID   9(09)
    private static final int W_MERCHANT_NAME = 50;    // TRAN-MERCHANT-NAME X(50)
    private static final int W_MERCHANT_CITY = 50;    // TRAN-MERCHANT-CITY X(50)
    private static final int W_MERCHANT_ZIP = 10;     // TRAN-MERCHANT-ZIP  X(10)
    private static final int W_CARD_NUM = 16;         // TRAN-CARD-NUM      X(16)
    private static final int W_ORIG_TS = 26;          // TRAN-ORIG-TS       X(26)
    private static final int W_PROC_TS = 26;          // TRAN-PROC-TS       X(26)
    private static final int W_FILLER = 20;           // FILLER             X(20)

    /** Single line terminator; a literal LF to guarantee byte-exact, CR-free output. */
    private static final String RECORD_TERMINATOR = "\n";

    /**
     * Underlying character sink for the TRANSACT file, layered over the exclusively claimed channel.
     * Opened once in the constructor and closed by {@link #close()}; never reassigned (the COBOL FD is
     * opened once, written many times, closed once). Closing it closes that channel, which releases the
     * exclusive claim taken in the constructor.
     */
    private final BufferedWriter writer;

    /**
     * Opens the interest-transaction output file for writing, truncating any existing content.
     *
     * <p>Ports {@code 0400-TRANFILE-OPEN} &mdash; {@code OPEN OUTPUT TRANSACT-FILE}
     * ({@code app/cbl/CBACT04C.cbl:L309}). {@code OPEN OUTPUT} creates the dataset or replaces its
     * contents, which is realized here as {@link StandardOpenOption#CREATE} +
     * {@link StandardOpenOption#WRITE} followed by an explicit truncation, with the US-ASCII charset.
     * A failure to open is fatal in COBOL ({@code 9999-ABEND-PROGRAM}, L318-321), so the
     * {@link IOException} is re-thrown as an {@link UncheckedIOException}.</p>
     *
     * <p><strong>The opened file is claimed exclusively, and only then truncated.</strong> The
     * {@code INTCALC} job gives TRANSACT and ACCTFILE separate DD statements, so one physical file can
     * never back both; on the Java side both output writers open a caller-supplied path, and a path can
     * be made to alias another one <em>after</em> the CLI has validated it (a symlink retargeted between
     * validation and open). Comparing path names therefore cannot be the last line of defense. This
     * constructor takes an exclusive {@link FileLock} on the <em>handle it just opened</em> and keeps it
     * for the writer's lifetime, so the identity that is checked is the identity that is written: if the
     * run's other output later opens the same physical file &mdash; through any spelling, symlink or hard
     * link &mdash; its own lock attempt fails and it abends instead of interleaving 300-byte records into
     * this 350-byte stream. Truncation happens only after the lock is held, so a rejected collision never
     * destroys the bytes of the file it collided with.</p>
     *
     * @param path the filesystem path of the TRANSACT output file; must be non-null
     * @throws IllegalArgumentException if {@code path} is null
     * @throws UncheckedIOException     if the file cannot be opened for output, is already held as
     *                                  another output of this run, or is locked by another process
     *                                  (fatal, mirrors the COBOL open-error abend at L318-321)
     */
    public TransactionWriter(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("output path must be non-null");
        }
        FileChannel opened = null;
        try {
            // OPEN OUTPUT TRANSACT-FILE (L309): create-or-replace. The channel is opened WITHOUT
            // TRUNCATE_EXISTING so the exclusive claim below runs BEFORE any content is destroyed.
            opened = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            // Bind this run's TRANSACT identity to this very handle (see the constructor javadoc).
            claimExclusively(opened, path);
            if (opened.size() > 0L) {
                // Now that the file is provably ours, realize OPEN OUTPUT's replace-contents semantics.
                opened.truncate(0L);
            }
            this.writer = new BufferedWriter(
                    new OutputStreamWriter(Channels.newOutputStream(opened), StandardCharsets.US_ASCII));
        } catch (IOException e) {
            // Mirrors 'ERROR OPENING TRANSACTION FILE' -> 9999-ABEND-PROGRAM (L318-321): fatal.
            final UncheckedIOException failure =
                    new UncheckedIOException("Failed to open TRANSACT output file: " + path, e);
            releaseOnFailure(opened, failure);
            throw failure;
        } catch (RuntimeException e) {
            // A refused claim (collision / foreign lock) is already a fatal diagnostic; do not mask it,
            // but release the handle this constructor opened before letting it propagate.
            releaseOnFailure(opened, e);
            throw e;
        }
    }

    /**
     * Takes an exclusive whole-file {@link FileLock} on an <em>already opened</em> output channel, so the
     * file this writer will write is provably not the file another output of the same run is writing.
     *
     * <p>The lock is held on behalf of the whole JVM and the JVM's lock table is keyed by the underlying
     * <em>physical file</em>, not by the path spelling. A second attempt from this process to lock the
     * same file therefore fails with {@link OverlappingFileLockException} no matter how it was addressed
     * &mdash; a different string, a symlink, a symlink chain, a symlinked parent, or a hard link &mdash;
     * and, crucially, no matter what happened to those names after the CLI validated them. That is what
     * makes this check immune to the validate-then-open gap that pure path comparison leaves open.</p>
     *
     * <p>The returned lock is deliberately not retained: {@link FileLock} stays held until the channel
     * that acquired it is closed, and that channel's lifetime is exactly this writer's lifetime, so
     * {@link #close()} releases it.</p>
     *
     * @param channel the freshly opened output channel to claim; must be open and writable
     * @param path    the path the channel was opened from, for diagnostics only
     * @throws UncheckedIOException if this run already holds the same physical file as an output, or
     *                              another process holds a conflicting lock on it
     * @throws IOException          if the lock cannot be attempted at all
     */
    private static void claimExclusively(FileChannel channel, Path path) throws IOException {
        final FileLock claim;
        try {
            claim = channel.tryLock();
        } catch (OverlappingFileLockException alreadyOursForSomethingElse) {
            throw new UncheckedIOException(
                    "Refusing to write the TRANSACT output: " + path + " is the same physical file as "
                            + "another output of this run; TRANSACT (350-byte records) and the updated "
                            + "ACCTFILE (300-byte records) must be separate datasets",
                    new FileSystemException(
                            path.toString(), null, "already claimed as another output of this run"));
        }
        if (claim == null) {
            throw new UncheckedIOException(
                    "Refusing to write the TRANSACT output: " + path + " is locked by another process",
                    new FileSystemException(path.toString(), null, "locked by another process"));
        }
    }

    /**
     * Closes a channel that a failed constructor had already opened, recording any close failure as a
     * suppressed exception on the failure being propagated so no diagnostic is lost and no handle leaks.
     *
     * @param channel the channel to release; may be {@code null} if the open itself failed
     * @param failure the exception about to be thrown, which collects any close failure
     */
    private static void releaseOnFailure(FileChannel channel, RuntimeException failure) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /**
     * Frames the given transaction as a 350-byte {@code CVTRA05Y} {@code TRAN-RECORD} and writes it,
     * followed by a single {@code '\n'}.
     *
     * <p>Ports {@code 1300-B-WRITE-TX} &mdash; {@code WRITE FD-TRANFILE-REC FROM TRAN-RECORD}
     * ({@code app/cbl/CBACT04C.cbl:L500}). The 14 copybook segments are appended in exact field
     * order (offsets: tranId&nbsp;0, typeCd&nbsp;16, catCd&nbsp;18, source&nbsp;22, description&nbsp;32,
     * amount&nbsp;132, merchantId&nbsp;143, merchantName&nbsp;152, merchantCity&nbsp;202,
     * merchantZip&nbsp;252, cardNum&nbsp;262, origTs&nbsp;278, procTs&nbsp;304, FILLER&nbsp;330), and
     * {@link com.blitzy.carddemo.interest.io.FixedWidthCodec.RecordBuilder#build(int) build(350)}
     * asserts the total width. Only {@code TRAN-AMT} ({@code S9(09)V99}) is a zoned-decimal field and
     * is the single field routed through {@code numeric(...)} &rarr; {@code encodeNumeric(...)} &rarr;
     * {@code ZonedDecimal.encode}; all other fields are already-formatted alphanumerics framed with
     * {@code alpha(...)}, and the trailing {@code FILLER X(20)} is 20 blanks.</p>
     *
     * <p>Sequential write order equals service-emit order: the record is written immediately, with no
     * buffering reorder. A write failure is fatal in COBOL (L510-513), so the {@link IOException} is
     * re-thrown as an {@link UncheckedIOException}.</p>
     *
     * @param r the fully-populated transaction record to emit; must be non-null
     * @throws IllegalArgumentException if {@code r} is null
     * @throws IllegalStateException    if the framed record is not exactly 350 characters (off-by-one
     *                                  framing guard, propagated from {@code RecordBuilder.build(350)})
     * @throws UncheckedIOException     if the record cannot be written (fatal, mirrors the COBOL
     *                                  write-error abend at L510-513)
     */
    public void write(TransactionRecord r) {
        if (r == null) {
            throw new IllegalArgumentException("transaction record must be non-null");
        }

        // TRAN-AMT S9(09)V99 (L490) is the ONLY zoned-decimal field in TRAN-RECORD; every other
        // segment is an already-formatted alphanumeric / numeric-as-String supplied by the service.
        final BigDecimal amount = r.amount();

        // Frame CVTRA05Y TRAN-RECORD in exact field order; build(350) asserts the copybook width.
        final String record = FixedWidthCodec.builder()
                .alpha(r.tranId(), W_TRAN_ID)             // 1  TRAN-ID            X(16)  @0
                .alpha(r.typeCd(), W_TYPE_CD)             // 2  TRAN-TYPE-CD       X(02)  @16
                .alpha(r.catCd(), W_CAT_CD)               // 3  TRAN-CAT-CD        9(04)  @18
                .alpha(r.source(), W_SOURCE)              // 4  TRAN-SOURCE        X(10)  @22
                .alpha(r.description(), W_DESC)           // 5  TRAN-DESC          X(100) @32
                .numeric(amount, W_AMT_DIGITS, W_AMT_SCALE) // 6 TRAN-AMT S9(09)V99 @132 (zoned-decimal)
                .alpha(r.merchantId(), W_MERCHANT_ID)     // 7  TRAN-MERCHANT-ID   9(09)  @143
                .alpha(r.merchantName(), W_MERCHANT_NAME) // 8  TRAN-MERCHANT-NAME X(50)  @152
                .alpha(r.merchantCity(), W_MERCHANT_CITY) // 9  TRAN-MERCHANT-CITY X(50)  @202
                .alpha(r.merchantZip(), W_MERCHANT_ZIP)   // 10 TRAN-MERCHANT-ZIP  X(10)  @252
                .alpha(r.cardNum(), W_CARD_NUM)           // 11 TRAN-CARD-NUM      X(16)  @262
                .alpha(r.origTs(), W_ORIG_TS)             // 12 TRAN-ORIG-TS       X(26)  @278
                .alpha(r.procTs(), W_PROC_TS)             // 13 TRAN-PROC-TS       X(26)  @304 (==origTs)
                .spaces(W_FILLER)                         // 14 FILLER             X(20)  @330
                .build(TRAN_RECORD_LENGTH);

        try {
            // WRITE FD-TRANFILE-REC FROM TRAN-RECORD (L500): emit the 350 bytes + single LF, in order.
            writer.write(record);
            writer.write(RECORD_TERMINATOR);
        } catch (IOException e) {
            // Mirrors 'ERROR WRITING TRANSACTION RECORD' -> 9999-ABEND-PROGRAM (L510-513): fatal.
            throw new UncheckedIOException("Failed to write TRANSACT record", e);
        }
    }

    /**
     * Flushes and closes the underlying output file.
     *
     * <p>Ports {@code 9400-TRANFILE-CLOSE} &mdash; {@code CLOSE TRANSACT-FILE}
     * ({@code app/cbl/CBACT04C.cbl:L597}). Closing the {@link BufferedWriter} flushes any buffered
     * bytes before releasing the file handle. A close failure is fatal in COBOL (L606-609), so the
     * {@link IOException} is re-thrown as an {@link UncheckedIOException}.</p>
     *
     * @throws UncheckedIOException if the underlying file cannot be flushed/closed (fatal, mirrors the
     *                              COBOL close-error abend at L606-609)
     */
    @Override
    public void close() {
        try {
            // CLOSE TRANSACT-FILE (L597): flush buffered output then release the handle.
            writer.close();
        } catch (IOException e) {
            // Mirrors 'ERROR CLOSING TRANSACTION FILE' -> 9999-ABEND-PROGRAM (L606-609): fatal.
            throw new UncheckedIOException("Failed to close TRANSACT output file", e);
        }
    }
}
