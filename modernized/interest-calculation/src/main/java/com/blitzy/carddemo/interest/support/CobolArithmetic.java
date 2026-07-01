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

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Ported COBOL monthly-interest arithmetic.
 *
 * <p>This is a faithful, behavior-preserving Java port of the interest
 * {@code COMPUTE} in {@code CBACT04C}. It is a stateless, side-effect-free
 * utility: a single pure static function with no I/O, no accumulation, and no
 * mutable state. It implements business rule <strong>BR-09</strong> and the
 * "decimal fidelity / truncation" transformation rule of the migration.</p>
 *
 * <h2>Source paragraph ported ({@code CBACT04C} {@code 1300-COMPUTE-INTEREST},
 * {@code app/cbl/CBACT04C.cbl:L462-470})</h2>
 * <pre>
 * 1300-COMPUTE-INTEREST.
 *     COMPUTE WS-MONTHLY-INT
 *      = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200      *&gt; L464-465  (NO ROUNDED clause)
 *     ADD WS-MONTHLY-INT  TO WS-TOTAL-INT           *&gt; L467 (accumulation — done in the service, NOT here)
 *     PERFORM 1300-B-WRITE-TX.                       *&gt; L468 (write — done in io/service, NOT here)
 *     EXIT.
 * </pre>
 *
 * <p>The receiving field is {@code WS-MONTHLY-INT PIC S9(09)V99}
 * ({@code app/cbl/CBACT04C.cbl:L168}), i.e. a signed value with an implied
 * {@code V99} decimal point &rarr; <strong>scale 2</strong>. The {@code COMPUTE}
 * carries <strong>no {@code ROUNDED} clause</strong>, so COBOL <em>truncates</em>
 * the exact quotient to the receiving field's scale (it drops — it does not
 * round — the third and later fractional digits). The divisor {@code 1200}
 * = 12 months &times; 100, because {@code DIS-INT-RATE} is an annual percentage
 * (for example {@code 12.50} meaning 12.5%).</p>
 *
 * <p><strong>Scope boundary:</strong> only the {@code COMPUTE} at L464-465 lives
 * here. The {@code ADD WS-MONTHLY-INT TO WS-TOTAL-INT} accumulation (L467) is the
 * service layer's responsibility, and the transaction {@code WRITE} (L468 &rarr;
 * {@code 1300-B-WRITE-TX}) belongs to the io/service layer. This class does
 * neither; it is intentionally pure so the business logic is unit-testable in
 * isolation.</p>
 *
 * <h2>Truncation, not rounding — the single most consequential porting rule</h2>
 * <p>Worked boundary example (asserted by {@code support/CobolArithmeticTest}):
 * with {@code balance = 100.00} and {@code rate = 12.55},
 * {@code 100.00 × 12.55 = 1255.0000} and {@code 1255.0000 / 1200 = 1.04583…}.
 * Truncating {@link RoundingMode#DOWN} at scale 2 yields <strong>{@code 1.04}</strong>.
 * {@link RoundingMode#HALF_UP}/{@link RoundingMode#HALF_EVEN} would yield
 * {@code 1.05} — which would be <em>wrong</em> for this port.</p>
 *
 * <p>The function is idempotent and thread-safe: it holds no mutable state and
 * the sole constant is immutable ({@link BigDecimal} instances are immutable).</p>
 *
 * <p>Source lineage: ported from {@code app/cbl/CBACT04C.cbl}
 * {@code 1300-COMPUTE-INTEREST} (L462-470), receiving field
 * {@code WS-MONTHLY-INT PIC S9(09)V99} (L168). Strictly additive; modifies
 * nothing under {@code app/}.</p>
 */
public final class CobolArithmetic {

    /**
     * The COBOL divisor {@code 1200} from {@code CBACT04C:L465}
     * ({@code ... / 1200}) = 12 months &times; 100 (the rate is an annual
     * percentage). Held as an immutable {@link BigDecimal} constant so the
     * divide is exact-input and allocation-free per call.
     */
    private static final BigDecimal HUNDRED_TWELVE_MONTHS = new BigDecimal("1200");

    /**
     * Non-instantiable: this is a pure static utility holding no state.
     */
    private CobolArithmetic() {
        // Intentionally empty — never instantiated.
    }

    /**
     * Computes one month's interest exactly as {@code CBACT04C}
     * {@code 1300-COMPUTE-INTEREST} does.
     *
     * <p>Ported from {@code app/cbl/CBACT04C.cbl:L464-465}:</p>
     * <pre>
     *   COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
     * </pre>
     * <p>The receiving field {@code WS-MONTHLY-INT} is
     * {@code PIC S9(09)V99} ({@code app/cbl/CBACT04C.cbl:L168}) &rarr; scale 2.
     * Because the {@code COMPUTE} has <strong>no {@code ROUNDED} clause</strong>,
     * this must <strong>truncate</strong> ({@link RoundingMode#DOWN}) to cents —
     * <strong>not</strong> {@link RoundingMode#HALF_EVEN} and not
     * {@link RoundingMode#HALF_UP}. Truncation toward zero is COBOL's behavior
     * for a no-{@code ROUNDED} {@code COMPUTE} into a scale-2 field.</p>
     *
     * <p>The multiply happens first (mirroring
     * {@code ( TRAN-CAT-BAL * DIS-INT-RATE)}) and produces an exact product;
     * only the final divide truncates. The three-argument
     * {@link BigDecimal#divide(BigDecimal, int, RoundingMode)} form is used so
     * the result scale is set to exactly {@code 2} and the rounding mode is
     * applied in a single step — this also avoids the non-terminating-expansion
     * {@link ArithmeticException} that an exact (two-argument) divide could
     * throw for quotients such as {@code 1.04583…}.</p>
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code monthlyInterest(100.00, 12.55)} &rarr; {@code 1.04}
     *       (1.04583… truncated DOWN; HALF_UP/HALF_EVEN would give 1.05).</li>
     *   <li>{@code monthlyInterest(0.00, 15.00)} &rarr; {@code 0.00}
     *       (zero balance with a non-zero rate still yields 0.00 at scale 2;
     *       feeds BR-07's "rate≠0 still writes a 0.00 transaction").</li>
     * </ul>
     *
     * @param balance the transaction-category balance interest is computed on
     *                 ({@code TRAN-CAT-BAL}, {@code PIC S9(09)V99}); must not be
     *                 {@code null}
     * @param rate    the annual percentage disclosure-group rate
     *                ({@code DIS-INT-RATE}, {@code PIC S9(04)V99}, e.g.
     *                {@code 12.50} = 12.5%); must not be {@code null}
     * @return the monthly interest amount, always at scale 2, truncated toward
     *         zero — equivalent to the COBOL {@code WS-MONTHLY-INT} value
     * @throws NullPointerException if {@code balance} or {@code rate} is
     *                              {@code null} (raised by {@link BigDecimal}
     *                              operations on a null operand)
     */
    public static BigDecimal monthlyInterest(final BigDecimal balance, final BigDecimal rate) {
        // CBACT04C 1300-COMPUTE-INTEREST L464-465:
        //   COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
        // WS-MONTHLY-INT is PIC S9(09)V99 (scale 2, L168) and the COMPUTE has no
        // ROUNDED clause -> truncate (RoundingMode.DOWN), NOT HALF_EVEN.
        return balance.multiply(rate)                                    // exact product (scales add)
                      .divide(HUNDRED_TWELVE_MONTHS, 2, RoundingMode.DOWN); // truncate to cents
    }
}
