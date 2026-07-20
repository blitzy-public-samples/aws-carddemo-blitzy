/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.common.util;

/**
 * Masks a Primary Account Number (PAN, i.e. a card number) for safe emission to
 * <strong>operational logs</strong>, following the PCI-DSS display convention of revealing at most the
 * first six and the last four digits and masking everything in between.
 *
 * <h2>Scope of use</h2>
 * <p>This helper is for <em>operational/diagnostic log output only</em> &mdash; including the
 * card, cross-reference, and customer master-print jobs, whose writers emit human-readable
 * {@code LOGGER} lines (a SYSOUT diagnostic, not a machine-parsed record). It must
 * <strong>not</strong> be applied to values written into the fixed-width <em>external file
 * contracts</em> (the DALYREJS reject image, the SYSTRAN interest transactions, the transaction
 * backup), which reproduce the legacy record layouts byte-for-byte for behavioral parity; masking a
 * PAN in those file contracts would be a parity regression. The distinction is deliberate: a
 * diagnostic log line has no downstream fixed-width consumer, so masking the PAN there is an
 * additive security improvement (consistent with the CVV and SSN/GOVT-ID/DOB masking those same
 * master-print jobs already apply), whereas the fixed-width files are consumed positionally and must
 * stay verbatim. The scope boundary is recorded in the decision log (D25 sanctioned PAN log-masking
 * as an additive improvement, D34 records this helper, and QA finding F-P6-B extended the masking to
 * the master-print SYSOUT logs).</p>
 *
 * <h2>Masking rule</h2>
 * <ul>
 *   <li>A {@code null} input yields the literal token {@value #NULL_TOKEN} so that a log line never
 *       throws and never contains card material.</li>
 *   <li>An input shorter than {@value #MIN_LENGTH_TO_REVEAL} characters is masked in full (every
 *       character replaced by {@value #MASK_CHAR_STRING}), because revealing six leading and four
 *       trailing characters of such a short value would expose the entire value.</li>
 *   <li>Otherwise the first {@value #VISIBLE_PREFIX} and last {@value #VISIBLE_SUFFIX} characters are
 *       preserved and every character between them is replaced by {@value #MASK_CHAR_STRING}. A canonical
 *       16-digit PAN {@code 9999999999999999} becomes {@code 999999******9999}.</li>
 * </ul>
 *
 * <p>The class is stateless, final, and not instantiable.</p>
 */
public final class PanMasker {

    /** The masking character used in place of hidden digits. */
    static final char MASK_CHAR = '*';

    /** String form of {@link #MASK_CHAR} for Javadoc {@code @value} references. */
    private static final String MASK_CHAR_STRING = "*";

    /** Number of leading characters left visible (PCI-DSS display maximum). */
    static final int VISIBLE_PREFIX = 6;

    /** Number of trailing characters left visible (PCI-DSS display maximum). */
    static final int VISIBLE_SUFFIX = 4;

    /**
     * Minimum length required before any character is revealed. At exactly
     * {@code VISIBLE_PREFIX + VISIBLE_SUFFIX} characters the two visible windows would cover the whole
     * value, so at least one masked character (one more than that sum) is required to reveal anything.
     */
    static final int MIN_LENGTH_TO_REVEAL = VISIBLE_PREFIX + VISIBLE_SUFFIX + 1;

    /** Token substituted for a {@code null} card number so log lines stay null-safe. */
    static final String NULL_TOKEN = "null";

    private PanMasker() {
        // Utility class; not instantiable.
    }

    /**
     * Masks the supplied card number for safe logging per the {@linkplain PanMasker class-level rule}.
     *
     * <p>This method never throws: a {@code null} argument returns {@value #NULL_TOKEN}, and any other
     * input is handled purely by length. It performs no validation of the digits themselves.</p>
     *
     * @param pan the raw card number to mask; may be {@code null}
     * @return the masked representation safe for operational logs
     */
    public static String mask(String pan) {
        if (pan == null) {
            return NULL_TOKEN;
        }
        final int length = pan.length();
        if (length < MIN_LENGTH_TO_REVEAL) {
            // Too short to safely reveal any window: mask every character.
            return String.valueOf(MASK_CHAR).repeat(length);
        }
        final int maskedCount = length - VISIBLE_PREFIX - VISIBLE_SUFFIX;
        final StringBuilder masked = new StringBuilder(length);
        masked.append(pan, 0, VISIBLE_PREFIX);
        masked.append(String.valueOf(MASK_CHAR).repeat(maskedCount));
        masked.append(pan, length - VISIBLE_SUFFIX, length);
        return masked.toString();
    }
}
