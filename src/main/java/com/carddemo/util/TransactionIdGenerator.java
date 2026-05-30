package com.carddemo.util;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * Spring-managed generator that produces the 16-character batch transaction
 * IDs used by the interest-calculation batch flow, preserving the exact format
 * emitted by COBOL {@code CBACT04C} paragraph {@code 1300-B-WRITE-TX}
 * [app/cbl/CBACT04C.cbl L473-L500].
 *
 * <h2>ID format</h2>
 * <p>Every generated ID is exactly 16 characters, composed of two
 * fixed-width parts concatenated in order (mirroring the COBOL
 * {@code STRING ... DELIMITED BY SIZE INTO TRAN-ID} statement):
 * <ol>
 *   <li><b>parmDate</b> — 10 characters — the batch parameter date supplied as
 *       the JCL {@code PARM='2022071800'} value in {@code INTCALC.jcl}
 *       (COBOL {@code PARM-DATE PIC X(10)}).</li>
 *   <li><b>suffix</b> — 6 characters — a zero-padded sequential counter
 *       (COBOL {@code WS-TRANID-SUFFIX PIC 9(06) VALUE 0}).</li>
 * </ol>
 * <p>Thus {@code 10 + 6 = 16}, matching {@code TRAN-ID PIC X(16)} and
 * satisfying rule <b>PR-10</b> ("16 characters total — first 10 from
 * {@code PARM-DATE}, last 6 from a sequential suffix counter starting at
 * {@code 000001}").
 *
 * <h2>Counter semantics (COBOL parity)</h2>
 * <p>The COBOL field initializes to {@code 0} and is <em>pre-incremented</em>
 * ({@code ADD 1 TO WS-TRANID-SUFFIX}) immediately before each ID is formed, so
 * the first ID produced after a reset carries suffix {@code 000001}. This class
 * reproduces that pre-increment behaviour exactly via
 * {@link AtomicLong#incrementAndGet()}.
 *
 * <h2>Batch mode vs. online mode</h2>
 * <p>This component implements <b>only</b> the batch-mode counter (per
 * AAP §0.6.10). Online transaction creation (the {@code POST /api/transactions}
 * endpoint) instead relies on the PostgreSQL sequence
 * {@code transaction_id_seq}, applied by JPA at INSERT time through the
 * {@code Transaction} entity's {@code @SequenceGenerator} annotation — it does
 * <em>not</em> use this class. Keeping the two paths separate guarantees unique
 * IDs across concurrent online requests while preserving deterministic,
 * restartable suffixes for batch parity.
 *
 * <h2>Thread safety</h2>
 * <p>The backing counter is an {@link AtomicLong}, so {@link #nextBatchId(String)},
 * {@link #resetCounter()} and {@link #currentCounter()} are safe to call from
 * multiple Spring Batch worker threads concurrently (for example, partitioned
 * or multi-threaded steps driven by a {@code TaskExecutor}).
 * {@link AtomicLong#incrementAndGet()} provides a lock-free atomic
 * read-modify-write, ensuring each concurrent caller receives a distinct
 * sequential suffix.
 *
 * <h2>Lifecycle</h2>
 * <p>The bean is a singleton, so its counter is shared for the lifetime of the
 * application context. Because a fresh job execution must restart suffixes at
 * {@code 000001}, callers reset the counter at job start — typically from a
 * Spring Batch {@code JobExecutionListener#beforeJob} hook invoking
 * {@link #resetCounter()}.
 *
 * @see java.util.concurrent.atomic.AtomicLong
 */
@Component
public class TransactionIdGenerator {

    /**
     * Required length of the {@code parmDate} prefix, matching the COBOL
     * {@code PARM-DATE PIC X(10)} field width.
     */
    private static final int PARM_DATE_LENGTH = 10;

    /**
     * Maximum value the 6-digit suffix can represent, matching the COBOL
     * {@code WS-TRANID-SUFFIX PIC 9(06)} field (range {@code 000000}-{@code 999999}).
     * Exceeding this would force a 7th digit and break the fixed 16-character
     * {@code TRAN-ID} width (PR-10).
     */
    private static final long MAX_SUFFIX = 999_999L;

    /**
     * Format string yielding {@code parmDate} (as-is) followed by the counter
     * rendered as a 6-digit, zero-padded decimal (e.g. {@code 000001}).
     */
    private static final String ID_FORMAT = "%s%06d";

    /**
     * Lock-free sequential suffix source. Initialized to {@code 0} to mirror the
     * COBOL working-storage default {@code 05 WS-TRANID-SUFFIX PIC 9(06) VALUE 0};
     * pre-incremented on every {@link #nextBatchId(String)} call so the first ID
     * after construction (or {@link #resetCounter()}) uses suffix {@code 000001}.
     */
    private final AtomicLong batchCounter = new AtomicLong(0);

    /**
     * Generates the next 16-character transaction ID for batch processing,
     * preserving the exact format from COBOL {@code CBACT04C} paragraph
     * {@code 1300-B-WRITE-TX} [app/cbl/CBACT04C.cbl L473-L480].
     *
     * <p>The returned ID concatenates:
     * <ol>
     *   <li>{@code parmDate} — the 10-character batch parameter date (e.g.,
     *       {@code "2022071800"}) passed as the JCL {@code PARM='2022071800'}
     *       value in {@code INTCALC.jcl}</li>
     *   <li>a 6-character zero-padded suffix derived from {@link #batchCounter}
     *       (e.g., {@code "000001"} for the first call after a reset)</li>
     * </ol>
     *
     * <p>The counter is incremented atomically via
     * {@link AtomicLong#incrementAndGet()} before formatting, so the first
     * generated ID after a reset is {@code parmDate + "000001"} (the suffix
     * starts at 1, not 0).
     *
     * <p>Example: {@code nextBatchId("2022071800")} returns
     * {@code "2022071800000001"} on the first call, {@code "2022071800000002"}
     * on the second, and so on.
     *
     * <p>COBOL equivalent:
     * <pre>
     *   ADD 1 TO WS-TRANID-SUFFIX
     *   STRING PARM-DATE, WS-TRANID-SUFFIX
     *     DELIMITED BY SIZE
     *     INTO TRAN-ID
     *   END-STRING
     * </pre>
     *
     * @param parmDate the 10-character batch date prefix (MUST be exactly 10 chars)
     * @return a 16-character transaction ID
     * @throws IllegalArgumentException if {@code parmDate} is {@code null} or not
     *         exactly 10 characters
     * @throws IllegalStateException if the counter would produce a suffix longer
     *         than 6 digits (i.e., more than 999,999 IDs generated since the last
     *         reset)
     */
    public String nextBatchId(String parmDate) {
        if (parmDate == null || parmDate.length() != PARM_DATE_LENGTH) {
            throw new IllegalArgumentException(
                "parmDate must be exactly " + PARM_DATE_LENGTH + " characters but was: "
                + (parmDate == null
                    ? "null"
                    : "'" + parmDate + "' (length " + parmDate.length() + ")"));
        }
        long suffix = batchCounter.incrementAndGet();
        if (suffix > MAX_SUFFIX) {
            throw new IllegalStateException(
                "Transaction ID suffix overflow: counter exceeded " + MAX_SUFFIX
                + ". Call resetCounter() between batch jobs.");
        }
        return ID_FORMAT.formatted(parmDate, suffix);
    }

    /**
     * Resets the batch counter to zero. Typically invoked by a Spring Batch
     * {@code JobExecutionListener#beforeJob} hook so that each job execution
     * starts with suffix {@code 000001}.
     *
     * <p>Mirrors the COBOL working-storage default value
     * {@code 05 WS-TRANID-SUFFIX PIC 9(06) VALUE 0}.
     *
     * <p>This method is thread-safe via {@link AtomicLong#set(long)}.
     */
    public void resetCounter() {
        batchCounter.set(0);
    }

    /**
     * Returns the current counter value without incrementing it.
     *
     * <p>Useful for diagnostic logging and Spring Batch step listeners that
     * report the number of IDs generated during a job execution.
     *
     * @return the current counter value ({@code 0} immediately after construction
     *         or a {@link #resetCounter()} call; {@code n} after {@code n}
     *         successful {@link #nextBatchId(String)} invocations)
     */
    public long currentCounter() {
        return batchCounter.get();
    }
}
