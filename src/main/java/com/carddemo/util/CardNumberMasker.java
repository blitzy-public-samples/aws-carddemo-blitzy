package com.carddemo.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cross-cutting utility for masking Primary Account Numbers (PAN / card numbers) before they
 * are written to logs or embedded in client-facing messages.
 *
 * <p>The original CardDemo COBOL programs freely {@code DISPLAY} and embed full 16-digit card
 * numbers in diagnostic text. In the REST/observability world that is a sensitive-data
 * exposure: card numbers are PAN-class data and must never appear in clear in logs or API
 * error payloads. This utility centralizes the masking policy so that every producer of a
 * log line or exception message can sanitize PAN values consistently.</p>
 *
 * <h2>Masking policy</h2>
 * <ul>
 *   <li>{@link #mask(String)} masks a single card-number value, revealing only the last four
 *       characters (e.g. {@code "4111111111111111"} &rarr; {@code "************1111"}). Values
 *       too short to reveal a safe suffix are masked in full; {@code null} yields a fixed
 *       {@code "****"} placeholder so callers never have to null-check.</li>
 *   <li>{@link #maskInMessage(String)} scans free-text (a log line or exception message) for
 *       embedded PAN-like runs &mdash; a maximal run of 13&ndash;19 digits, the ISO/IEC 7812
 *       account-number length range &mdash; and replaces each with its {@link #mask(String)}
 *       form. Shorter numeric tokens (account ids of 11 digits, codes, amounts, timestamps)
 *       are left untouched, and text containing no PAN-like run is returned unchanged, so
 *       legitimate business messages (for example "You have nothing to pay") are preserved
 *       verbatim.</li>
 * </ul>
 *
 * <p>This is a stateless, side-effect-free utility class with a private constructor (it is
 * never instantiated); it has zero dependencies beyond {@code java.util.regex}.</p>
 *
 * @see com.carddemo.controller.advice.GlobalExceptionHandler
 * @since 1.0
 */
public final class CardNumberMasker {

    /** Number of trailing characters left visible by {@link #mask(String)}. */
    private static final int VISIBLE_TRAILING = 4;

    /** Placeholder returned by {@link #mask(String)} for {@code null}/blank input. */
    private static final String NULL_MASK = "****";

    /**
     * Matches a maximal run of 13&ndash;19 digits not adjacent to another digit. The
     * lookaround boundaries ({@code (?<!\d)} / {@code (?!\d)}) ensure a longer digit run is
     * not partially matched, and the 13-digit lower bound deliberately excludes shorter
     * numeric tokens such as 11-digit account ids, so only true PAN-length values are masked.
     */
    private static final Pattern PAN_PATTERN = Pattern.compile("(?<!\\d)\\d{13,19}(?!\\d)");

    private CardNumberMasker() {
        throw new AssertionError("CardNumberMasker is a utility class and must not be instantiated");
    }

    /**
     * Masks a card number, revealing only the last {@value #VISIBLE_TRAILING} characters.
     *
     * <p>Examples: {@code "4111111111111111"} &rarr; {@code "************1111"};
     * {@code "1234"} &rarr; {@code "****"} (too short to reveal a safe suffix);
     * {@code null} &rarr; {@code "****"}.</p>
     *
     * @param cardNumber the raw card number (may be {@code null})
     * @return the masked value; never {@code null}
     */
    public static String mask(String cardNumber) {
        if (cardNumber == null) {
            return NULL_MASK;
        }
        String value = cardNumber.trim();
        int len = value.length();
        if (len == 0) {
            return NULL_MASK;
        }
        if (len <= VISIBLE_TRAILING) {
            // Too short to reveal a suffix without exposing (nearly) the whole value: mask all.
            return "*".repeat(len);
        }
        String last = value.substring(len - VISIBLE_TRAILING);
        return "*".repeat(len - VISIBLE_TRAILING) + last;
    }

    /**
     * Returns a copy of {@code message} with every embedded PAN-like run (13&ndash;19 digits)
     * replaced by its {@link #mask(String)} form, leaving all other text unchanged.
     *
     * <p>Use this to sanitize free-text destined for logs or client-facing error payloads
     * when the text may have been built by concatenating a card number into a sentence.
     * Messages with no PAN-like run are returned unchanged (reference-equal is not
     * guaranteed, but the content is identical), so legitimate business messages are
     * preserved verbatim.</p>
     *
     * @param message the free-text message (may be {@code null})
     * @return the sanitized message, or {@code null} if {@code message} was {@code null}
     */
    public static String maskInMessage(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = PAN_PATTERN.matcher(message);
        StringBuilder sb = new StringBuilder(message.length());
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(mask(matcher.group())));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
