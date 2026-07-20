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

import java.util.Locale;

/**
 * Foundational, dependency-free helper that reproduces the COBOL transaction-id
 * generation of {@code legacy/cbl/COTRN02C.cbl} (paragraph {@code ADD-TRANSACTION},
 * lines 444&ndash;451).
 *
 * <p>The legacy program derives the next transaction id with a <em>reverse browse from
 * {@code HIGH-VALUES}</em> over the {@code TRANSACT} VSAM KSDS: it positions the browse
 * at the end of the file ({@code STARTBR}), reads the greatest existing key
 * ({@code READPREV}), ends the browse ({@code ENDBR}), copies the key into the numeric
 * work field {@code WS-TRAN-ID-N} ({@code PIC 9(16)}), adds {@code 1}, and moves the
 * result back into {@code TRAN-ID} ({@code PIC X(16)}):</p>
 *
 * <pre>
 *     MOVE HIGH-VALUES TO TRAN-ID
 *     PERFORM STARTBR-TRANSACT-FILE
 *     PERFORM READPREV-TRANSACT-FILE
 *     PERFORM ENDBR-TRANSACT-FILE
 *     MOVE TRAN-ID     TO WS-TRAN-ID-N
 *     ADD 1 TO WS-TRAN-ID-N
 *     MOVE WS-TRAN-ID-N TO TRAN-ID
 * </pre>
 *
 * <p>When the file is empty the {@code READPREV} {@code ENDFILE} path executes
 * {@code MOVE ZEROS TO TRAN-ID} (lines 688&ndash;689), so the "current maximum" is
 * treated as {@code 0} and the first generated id becomes {@code 1}, i.e.
 * {@code "0000000000000001"}.</p>
 *
 * <p>Per the migration mapping (AAP &sect;0.7.1 H5) the reverse-browse-from-high-values
 * becomes a repository <em>max-key lookup plus one</em>. The max-key lookup and the
 * subsequent insert are the responsibility of the consumer
 * ({@code service.TransactionService}, the migration of {@code COTRN02C}); this class
 * implements only the pure <em>"plus one" and 16-digit zero-padded formatting</em>
 * parity, given the current maximum. Keeping the {@code max(...)} query out of this
 * class means {@code common.util} imports nothing from {@code repository} and remains a
 * dependency-free foundational leaf.</p>
 *
 * <p><strong>Format.</strong> A transaction id is exactly {@value #TRAN_ID_LENGTH}
 * characters: a zero-padded, unsigned decimal string (COBOL {@code PIC 9(16)}).
 * Formatting is performed with {@link Locale#ROOT} so the output is always ASCII digits
 * regardless of the host locale.</p>
 *
 * <p><strong>Concurrency.</strong> Every method is pure and stateless, so this class is
 * inherently thread-safe. However, "max + 1" is only collision-free if the consumer
 * serializes the read-max&rarr;insert sequence: {@code service.TransactionService} MUST
 * query {@code max(tran_id)} and insert the new row within a single
 * {@code @Transactional} boundary so that two concurrent adds cannot both observe the
 * same maximum. A {@code UNIQUE} constraint on {@code transaction.tran_id} is
 * recommended as a database backstop (owned by the Flyway schema and entity migrations,
 * not this class) so that a residual race fails with a constraint violation rather than
 * silently duplicating an id. This class deliberately holds no static counter or
 * {@code AtomicLong}: the single source of truth is the database-derived maximum
 * supplied by the caller, which preserves parity across restarts and across multiple
 * application instances.</p>
 *
 * <p>All members are {@code static}; the class is stateless and cannot be
 * instantiated.</p>
 */
public final class IdGenerator {

    /**
     * Fixed width of a transaction id, in characters, matching COBOL
     * {@code TRAN-ID PIC X(16)} and {@code WS-TRAN-ID-N PIC 9(16)}.
     */
    public static final int TRAN_ID_LENGTH = 16;

    /**
     * Largest value representable in {@value #TRAN_ID_LENGTH} decimal digits
     * ({@code 9_999_999_999_999_999L}, i.e. sixteen nines). This fits comfortably within
     * a {@code long} (well below {@link Long#MAX_VALUE}), so transaction-id arithmetic
     * never overflows a {@code long}; only the decimal width has to be guarded.
     */
    private static final long MAX_TRAN_ID = 9_999_999_999_999_999L;

    /**
     * Zero-padding conversion for {@link String#format(Locale, String, Object...)},
     * derived from {@link #TRAN_ID_LENGTH} so the width and the constant can never drift
     * apart (evaluates to {@code "%016d"}).
     */
    private static final String ID_FORMAT = "%0" + TRAN_ID_LENGTH + "d";

    /**
     * Non-instantiable stateless utility.
     *
     * @throws AssertionError always &mdash; this class exposes only {@code static} members.
     */
    private IdGenerator() {
        throw new AssertionError("utility class");
    }

    /**
     * Formats a numeric id as the canonical {@value #TRAN_ID_LENGTH}-character,
     * zero-padded, unsigned decimal transaction id, reproducing the COBOL
     * {@code MOVE WS-TRAN-ID-N TO TRAN-ID} step ({@code PIC 9(16)} to {@code PIC X(16)}).
     *
     * @param id the numeric id, in the inclusive range
     *           {@code 0 .. 9_999_999_999_999_999}
     * @return the id rendered as a {@value #TRAN_ID_LENGTH}-digit ASCII string
     * @throws IllegalArgumentException if {@code id} is negative or does not fit within
     *                                  {@value #TRAN_ID_LENGTH} digits
     */
    public static String format(long id) {
        if (id < 0L) {
            throw new IllegalArgumentException("transaction id must be non-negative, but was " + id);
        }
        if (id > MAX_TRAN_ID) {
            throw new IllegalArgumentException(
                    "transaction id " + id + " does not fit in " + TRAN_ID_LENGTH + " digits");
        }
        return String.format(Locale.ROOT, ID_FORMAT, id);
    }

    /**
     * Returns the next transaction id given the current maximum id as a number,
     * reproducing the COBOL {@code ADD 1 TO WS-TRAN-ID-N} increment
     * ({@code legacy/cbl/COTRN02C.cbl} lines 448&ndash;451).
     *
     * <p>Pass {@code 0} when the transaction table is empty, mirroring the COBOL
     * {@code READPREV} {@code ENDFILE} path that moves {@code ZEROS} into {@code TRAN-ID}
     * (lines 688&ndash;689); the result is then {@code "0000000000000001"}.</p>
     *
     * <p>See the class-level concurrency note: the caller must perform the
     * {@code max(tran_id)} lookup and the subsequent insert within a single
     * {@code @Transactional} boundary.</p>
     *
     * @param currentMax the current maximum transaction id, or {@code 0} if none exists
     * @return the next id, zero-padded to {@value #TRAN_ID_LENGTH} digits
     * @throws IllegalArgumentException if {@code currentMax} is negative
     * @throws IllegalStateException    if the id space is exhausted (no
     *                                  {@value #TRAN_ID_LENGTH}-digit successor exists)
     */
    public static String nextTransactionId(long currentMax) {
        if (currentMax < 0L) {
            throw new IllegalArgumentException(
                    "current maximum transaction id must be non-negative, but was " + currentMax);
        }
        if (currentMax >= MAX_TRAN_ID) {
            throw new IllegalStateException("transaction id space exhausted");
        }
        return format(currentMax + 1L);
    }

    /**
     * Returns the next transaction id given the current maximum id as a string &mdash;
     * the form most convenient when the consumer's {@code max(tran_id)} query returns the
     * key column value directly.
     *
     * <p>Empty-table semantics reproduce the COBOL {@code ENDFILE}&rarr;{@code ZEROS}
     * behavior: a {@code null}, blank / all-spaces, or all-zeros {@code currentMaxId} is
     * treated as a current maximum of {@code 0}, yielding {@code "0000000000000001"}.
     * All-zeros needs no special case &mdash; it simply parses to {@code 0}.</p>
     *
     * <p>Any other value must be an unsigned decimal string of at most
     * {@value #TRAN_ID_LENGTH} ASCII digits, after trimming surrounding whitespace (a
     * fixed-width {@code PIC X(16)} column may be space-padded). The VSAM
     * {@code HIGH-VALUES} browse sentinel is intentionally <em>not</em> accepted: it is a
     * file-access mechanism with no analog in the relational target, where the caller
     * passes the actual maximum (or {@code null}/blank when there are no rows).</p>
     *
     * @param currentMaxId the current maximum transaction id, or {@code null}/blank if
     *                     none exists
     * @return the next id, zero-padded to {@value #TRAN_ID_LENGTH} digits
     * @throws IllegalArgumentException if {@code currentMaxId} is non-numeric or wider
     *                                  than {@value #TRAN_ID_LENGTH} digits
     * @throws IllegalStateException    if the id space is exhausted
     */
    public static String nextTransactionId(String currentMaxId) {
        if (currentMaxId == null) {
            return nextTransactionId(0L);
        }
        String trimmed = currentMaxId.strip();
        if (trimmed.isEmpty()) {
            return nextTransactionId(0L);
        }
        if (trimmed.length() > TRAN_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "transaction id \"" + currentMaxId + "\" exceeds " + TRAN_ID_LENGTH + " digits");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char digit = trimmed.charAt(i);
            if (digit < '0' || digit > '9') {
                throw new IllegalArgumentException(
                        "transaction id \"" + currentMaxId + "\" is not numeric");
            }
        }
        return nextTransactionId(Long.parseLong(trimmed));
    }
}
