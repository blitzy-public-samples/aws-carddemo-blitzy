/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.blitzy.carddemo.interest.support;

import java.time.LocalDateTime;

/**
 * Supplies a 26-character DB2-format timestamp in the layout
 * {@code YYYY-MM-DD-HH.MM.SS.<hundredths(2)>0000}.
 *
 * <p><strong>Ported from</strong> CBACT04C {@code Z-GET-DB2-FORMAT-TIMESTAMP}
 * (<code>app/cbl/CBACT04C.cbl:L613-626</code>) together with the
 * {@code COBOL-TS} / {@code DB2-FORMAT-TS} working-storage layouts
 * (<code>app/cbl/CBACT04C.cbl:L140-165</code>). The COBOL header comment at
 * L140 documents the target shape as {@code EEEE-MM-DD-UU.MM.SS.HH0000}.</p>
 *
 * <p>This is the <strong>single dependency-injection seam</strong> of the whole
 * interest-calculation module: it isolates the only non-deterministic input in
 * CBACT04C &mdash; {@code MOVE FUNCTION CURRENT-DATE TO COBOL-TS}
 * (<code>app/cbl/CBACT04C.cbl:L614</code>) &mdash; so that the golden-master test
 * can inject a fixed value and obtain byte-exact output. This implements
 * business rule <strong>BR-15</strong> (AAP &sect;0.6.3): {@code TRAN-ORIG-TS}
 * equals {@code TRAN-PROC-TS} because the same supplied value is used for both
 * timestamps of a generated transaction.</p>
 *
 * <p><strong>Injection contract.</strong> Because this is a
 * {@link FunctionalInterface}, a test can supply a fixed lambda, for example:</p>
 * <pre>{@code
 *   Db2TimestampSupplier ts = () -> "2022-07-18-00.00.00.000000"; // exactly 26 chars
 * }</pre>
 * <p>The production entry point ({@code InterestCalculator.main}) uses
 * {@link #systemDefault()}; the injectable overload ({@code run(args, ts)}) and
 * {@code InterestCalculationService} accept the injected supplier.</p>
 *
 * <p><strong>Field layout of {@code DB2-FORMAT-TS} (widths sum to 26):</strong>
 * {@code YYYY(4) '-' MM(2) '-' DD(2) '-' HH(2) '.' MIN(2) '.' SS(2) '.' MIL(2) '0000'(4)}.
 * There are exactly three {@code '-'} separators (between year-month, month-day,
 * and <em>day-hour</em>; the date&harr;time separator mirrors {@code DB2-STREEP-3}
 * and is a hyphen, <em>not</em> a space) and three {@code '.'} separators
 * (between hour-minute, minute-second, and second-fraction). {@code DB2-MIL} is
 * the 2-digit hundredths-of-a-second field ({@code COB-MIL}); the trailing
 * {@code '0000'} ({@code DB2-REST}) pads the remaining four of DB2's six
 * fractional digits.</p>
 *
 * <p>The type is a pure functional interface with no instance or mutable static
 * state; all helper methods operate solely on their arguments, so it is
 * inherently thread-safe.</p>
 *
 * @see #systemDefault()
 * @see #format(LocalDateTime)
 */
@FunctionalInterface
public interface Db2TimestampSupplier {

    /**
     * Returns a 26-character DB2-format timestamp string in the layout
     * {@code YYYY-MM-DD-HH.MM.SS.<hundredths(2)>0000}.
     *
     * <p>This is the single abstract method of the functional interface, which
     * is why a no-argument lambda returning a {@code String} (e.g.
     * {@code () -> "2022-07-18-00.00.00.000000"}) is assignable to
     * {@code Db2TimestampSupplier}.</p>
     *
     * @return a 26-character DB2-format timestamp string
     */
    String get();

    /**
     * Creates the production supplier, deriving the timestamp from the wall
     * clock on each call.
     *
     * <p>This is the closest Java analogue to CBACT04C's
     * {@code MOVE FUNCTION CURRENT-DATE TO COBOL-TS}
     * (<code>app/cbl/CBACT04C.cbl:L614</code>): {@link LocalDateTime#now()} reads
     * the current date-time in the system default zone, mirroring the COBOL
     * intrinsic's local-time semantics. Each invocation of the returned
     * supplier's {@link #get()} recomputes the timestamp, exactly as the COBOL
     * paragraph is re-performed per transaction.</p>
     *
     * @return a supplier whose {@link #get()} formats the current wall-clock
     *         {@link LocalDateTime} into a 26-character DB2-format timestamp
     */
    static Db2TimestampSupplier systemDefault() {
        // Recomputed per call, mirroring the per-transaction PERFORM of
        // Z-GET-DB2-FORMAT-TIMESTAMP (app/cbl/CBACT04C.cbl:L613-626).
        return () -> format(LocalDateTime.now());
    }

    /**
     * Formats a {@link LocalDateTime} into the 26-character DB2-format timestamp,
     * performing the field-for-field mapping of CBACT04C
     * {@code Z-GET-DB2-FORMAT-TIMESTAMP} (<code>app/cbl/CBACT04C.cbl:L615-624</code>)
     * from {@code COBOL-TS} into the redefined {@code DB2-FORMAT-TS}
     * (<code>app/cbl/CBACT04C.cbl:L150-165</code>).
     *
     * <p>All numeric components are zero-padded with ASCII digits via
     * {@link #pad(int, int)} so the output is locale-independent and byte-exact.
     * The width of the result is always
     * {@code 4 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 2 + 4 = 26}.</p>
     *
     * <p>Example: {@code format(LocalDateTime.of(2022, 7, 18, 0, 0, 0, 0))}
     * yields {@code "2022-07-18-00.00.00.000000"}.</p>
     *
     * @param dt the date-time to format; must not be {@code null}
     * @return a 26-character DB2-format timestamp string
     */
    static String format(LocalDateTime dt) {
        // COB-MIL: hundredths-of-a-second (0..99). COBOL-TS derives this from
        // FUNCTION CURRENT-DATE positions 15-16 (app/cbl/CBACT04C.cbl:L148);
        // getNano() is 0..999_999_999, so dividing by 10_000_000 yields 0..99.
        int hundredths = dt.getNano() / 10_000_000;

        // Build DB2-FORMAT-TS (X(26)) field by field, mirroring the MOVEs at
        // app/cbl/CBACT04C.cbl:L615-624. Each append is annotated with its
        // target DB2-* field and originating source line.
        StringBuilder sb = new StringBuilder(26);
        sb.append(pad(dt.getYear(), 4)).append('-')        // DB2-YYYY (L615) / DB2-STREEP-1 '-' (L623)
          .append(pad(dt.getMonthValue(), 2)).append('-')  // DB2-MM   (L616) / DB2-STREEP-2 '-' (L623)
          .append(pad(dt.getDayOfMonth(), 2)).append('-')  // DB2-DD   (L617) / DB2-STREEP-3 '-' (L623)
          .append(pad(dt.getHour(), 2)).append('.')        // DB2-HH   (L618) / DB2-DOT-1   '.' (L624)
          .append(pad(dt.getMinute(), 2)).append('.')      // DB2-MIN  (L619) / DB2-DOT-2   '.' (L624)
          .append(pad(dt.getSecond(), 2)).append('.')      // DB2-SS   (L620) / DB2-DOT-3   '.' (L624)
          .append(pad(hundredths, 2)).append("0000");      // DB2-MIL  (L621) / DB2-REST '0000' (L622)
        return sb.toString();
    }

    /**
     * Left-pads the decimal representation of {@code v} with ASCII {@code '0'}
     * characters to the requested {@code width}, without truncating values whose
     * representation already meets or exceeds {@code width}.
     *
     * <p>ASCII, locale-independent padding is used deliberately (rather than a
     * locale-default {@link String#format}) so that the emitted digits are always
     * {@code 0-9} and the output remains byte-exact for golden-master assertions.</p>
     *
     * @param v     the non-negative component value to render (year, month, day,
     *              hour, minute, second, or hundredths)
     * @param width the minimum number of characters in the result
     * @return the zero-padded decimal string
     */
    private static String pad(int v, int width) {
        String s = Integer.toString(v);
        return s.length() >= width ? s : "0".repeat(width - s.length()) + s;
    }
}
