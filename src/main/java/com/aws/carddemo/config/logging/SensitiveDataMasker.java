package com.aws.carddemo.config.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts Primary Account Numbers (PANs) and other long digit sequences from already-rendered log
 * text, so no full card number can reach a log sink.
 *
 * <p><b>Origin (net-new observability infrastructure).</b> AWS CardDemo was an IBM z/OS
 * COBOL/CICS/VSAM/JCL/BMS system with no logging framework; this class has no legacy COBOL
 * counterpart. It exists to satisfy the Observability rule requirement for
 * &ldquo;sanitized structured logs without record PII/PAN/CVV/password content&rdquo; and closes QA
 * finding <b>F-1</b>: on a malformed daily-transaction record the Spring Batch framework raises
 * {@code org.springframework.batch.item.file.FlatFileParseException} whose message embeds the entire
 * raw 350-byte record &mdash; and that raw record carries the 16-digit card number
 * ({@code DALYTRAN-CARD-NUM}, bytes 263-278). {@code AbstractStep} then logs that exception at
 * {@code ERROR}, so the full unmasked PAN would otherwise be written to both the {@code message} and
 * the {@code error.stack_trace} of the structured log entry (CWE-532). The application's own code
 * never emits raw record content, so masking at the logging sink is the single cross-cutting place
 * that neutralizes the framework-generated message without altering any functional behaviour.
 * The rationale for choosing sink masking over a skip-listener / reader exception-handler (which
 * would risk changing the verified malformed-record RC8 behaviour) is recorded in
 * {@code docs/decision-log.md}.</p>
 *
 * <h2>Masking contract</h2>
 * <p>Every maximal run of {@value #MIN_RUN_LENGTH} or more consecutive ASCII digits is replaced with
 * a PCI-DSS&nbsp;3.4-compliant excerpt that keeps only the first {@value #KEEP_PREFIX} and the last
 * {@value #KEEP_SUFFIX} digits of the run, replacing every digit in between with
 * {@code '}{@value #MASK_CHAR}{@code '}. The result preserves the run length so the structure of the
 * text (and the byte length of a fixed-width diagnostic excerpt) is visually retained while the
 * middle digits are unreadable.</p>
 *
 * <h2>Why a run threshold of {@value #MIN_RUN_LENGTH} and an unbounded upper end</h2>
 * <ul>
 *   <li><b>Lower bound {@value #MIN_RUN_LENGTH}.</b> 13 is the PCI-DSS PAN floor. Every non-PAN
 *       identifier this system can log is shorter: account id {@code 9(11)} (11 digits), customer id
 *       {@code 9(9)}, merchant id {@code 9(9)}, transaction amount {@code S9(9)V99} (&le; 11 digits),
 *       CVV (3-4) and SSN (9). None reach 13, so they are never masked and stay fully readable.</li>
 *   <li><b>No upper bound.</b> In a fixed-width {@code DALYTRAN} record the 16-digit card field is
 *       immediately followed by the origin-timestamp year, producing a 20-digit run such as
 *       {@code 41112222333344442022}; the id+type+category prefix likewise forms a 22-digit run.
 *       A bounded {@code \d{13,19}} pattern would fail to match a 20+ digit run and would leak the
 *       embedded PAN, so the pattern is deliberately {@code \d{13,}} (greedy, unbounded). Keeping the
 *       first six and last four of the <em>whole</em> run is safe in every case: whichever position
 *       a PAN occupies inside a concatenated run, at least ten of its digits fall in the masked
 *       middle, so no complete card number ever survives.</li>
 * </ul>
 *
 * <p><b>Stateless and thread-safe.</b> All state is a single immutable, pre-compiled {@link Pattern};
 * the class holds no mutable fields and is safe to call concurrently from every logging thread.</p>
 */
public final class SensitiveDataMasker {

    /**
     * Minimum length of a consecutive-digit run that is treated as a candidate PAN. Equal to the
     * PCI-DSS Primary Account Number floor (13 digits); shorter runs are left untouched.
     */
    static final int MIN_RUN_LENGTH = 13;

    /** Number of leading digits (the issuer identification number / BIN) kept in clear. */
    static final int KEEP_PREFIX = 6;

    /** Number of trailing digits kept in clear. */
    static final int KEEP_SUFFIX = 4;

    /** Character substituted for every masked (middle) digit of a run. */
    static final char MASK_CHAR = '*';

    /**
     * Matches a maximal run of {@value #MIN_RUN_LENGTH} or more ASCII digits. Unbounded on the upper
     * end so a PAN embedded in a longer concatenated numeric run is still fully neutralized.
     */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{" + MIN_RUN_LENGTH + ",}");

    private SensitiveDataMasker() {
        throw new AssertionError("SensitiveDataMasker is a static utility and must not be instantiated");
    }

    /**
     * Returns {@code text} with every run of {@value #MIN_RUN_LENGTH}+ consecutive digits masked to a
     * first-{@value #KEEP_PREFIX}/last-{@value #KEEP_SUFFIX} excerpt.
     *
     * <p>When {@code text} is {@code null}, empty, or contains no qualifying run, the <em>same</em>
     * instance is returned unchanged; callers may rely on reference equality to detect the
     * &ldquo;nothing masked&rdquo; case and skip further work (the {@link MaskingLogbackEncoder} uses
     * this to avoid re-encoding an untouched log line).</p>
     *
     * @param text the rendered text to sanitize; may be {@code null}
     * @return the sanitized text, or the original reference when nothing needed masking
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty() || !containsLongDigitRun(text)) {
            return text;
        }
        Matcher matcher = LONG_DIGIT_RUN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(maskRun(matcher.group())));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Masks a single all-digit run, preserving the first {@value #KEEP_PREFIX} and last
     * {@value #KEEP_SUFFIX} characters. Runs no longer than {@value #KEEP_PREFIX}+{@value #KEEP_SUFFIX}
     * are masked in full (defensive; unreachable for callers going through the {@code \d{13,}}
     * pattern, whose matches are always at least {@value #MIN_RUN_LENGTH} long).
     *
     * @param run a string of consecutive digits
     * @return the masked run, of identical length to {@code run}
     */
    private static String maskRun(String run) {
        int length = run.length();
        if (length <= KEEP_PREFIX + KEEP_SUFFIX) {
            return String.valueOf(MASK_CHAR).repeat(length);
        }
        int maskedLength = length - KEEP_PREFIX - KEEP_SUFFIX;
        return run.substring(0, KEEP_PREFIX)
                + String.valueOf(MASK_CHAR).repeat(maskedLength)
                + run.substring(length - KEEP_SUFFIX);
    }

    /**
     * Fast single-pass pre-scan reporting whether {@code text} holds any run of at least
     * {@value #MIN_RUN_LENGTH} consecutive ASCII digits. Used to short-circuit the common case (a log
     * line with no long digit run) without allocating a {@link Matcher} or a {@link StringBuilder}.
     *
     * @param text the text to scan
     * @return {@code true} if a qualifying digit run exists
     */
    private static boolean containsLongDigitRun(String text) {
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                if (++run >= MIN_RUN_LENGTH) {
                    return true;
                }
            } else {
                run = 0;
            }
        }
        return false;
    }
}
