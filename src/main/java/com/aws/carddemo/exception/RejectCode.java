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
package com.aws.carddemo.exception;

import java.util.Optional;

/**
 * Authoritative, closed enumeration of the batch daily-transaction posting
 * <strong>reject reason codes</strong> produced by the legacy COBOL program
 * {@code CBTRN02C} while validating each record read from the daily-transaction
 * file before it is posted.
 *
 * <h2>Source of truth</h2>
 * The codes are moved into the working-storage field
 * {@code WS-VALIDATION-FAIL-REASON} ({@code PIC 9(04)}, CBTRN02C L181) and the
 * accompanying human-readable text into {@code WS-VALIDATION-FAIL-REASON-DESC}
 * ({@code PIC X(76)}, CBTRN02C L182) inside paragraph
 * {@code 1500-VALIDATE-TRAN} and its sub-paragraphs
 * {@code 1500-A-LOOKUP-XREF} and {@code 1500-B-LOOKUP-ACCT}. A record that fails
 * validation is written to the reject file (the {@code DALYREJS} DD) and the
 * running {@code WS-REJECT-COUNT} counter is incremented. The {@code code} and
 * {@code description} carried by each constant are copied verbatim from the
 * COBOL literals and are a behavioral-parity contract: they must not be
 * paraphrased, reordered, added to, or removed.
 *
 * <h2>Closed set — exactly {@code {100, 101, 102, 103}}</h2>
 * These four constants are the complete and closed set of <em>pre-post
 * validation</em> reject reasons. The set is frozen; {@link #fromCode(int)}
 * rejects any value outside it, which makes the closed contract
 * self-enforcing.
 *
 * <h2>Evaluation order (enforced by the batch posting service, documented here)</h2>
 * This enum is a passive value/reference type: it carries the code and
 * description and offers lookup only. It intentionally contains <em>no</em>
 * validation branching logic. The ordering below lives in the batch posting
 * service that consumes this enum; it is recorded here so that downstream logic
 * reproduces {@code CBTRN02C} exactly.
 * <ol>
 *   <li>{@code 1500-A-LOOKUP-XREF} runs first and may set {@code 100}
 *       (CBTRN02C L371, L385).</li>
 *   <li>{@code 1500-B-LOOKUP-ACCT} runs <em>only</em> when the fail reason is
 *       still zero — {@code IF WS-VALIDATION-FAIL-REASON = 0} (CBTRN02C L372).
 *       Consequently a cross-reference miss ({@code 100}) short-circuits the
 *       account lookup, so <strong>{@code 100} precludes {@code 101},
 *       {@code 102}, and {@code 103}</strong>.</li>
 *   <li>Within the account-found branch ({@code NOT INVALID KEY}) the
 *       credit-limit check (may set {@code 102}) and the expiration check (may
 *       set {@code 103}) execute as two <em>sequential, independent</em>
 *       {@code IF} statements (CBTRN02C L407-L420). If both fail, the
 *       {@code MOVE 103} at L417 runs last and overwrites the {@code MOVE 102}
 *       at L410 — <strong>last-writer-wins: {@code 103} beats {@code 102}</strong>
 *       when both conditions trip for the same record.</li>
 * </ol>
 *
 * <h2>Scope note — code {@code 109} is intentionally excluded</h2>
 * {@code CBTRN02C} also performs {@code MOVE 109} (L556) inside
 * {@code 2800-UPDATE-ACCOUNT-REC} when the account {@code REWRITE} returns
 * {@code INVALID KEY}. That {@code 109} is a <em>posting-phase I/O failure</em>,
 * not a pre-post validation reject reason: it happens after validation has
 * already passed, during the balance update, and (unlike a reject) it aborts
 * processing rather than producing a reject row. It therefore maps to a
 * {@code FileStatusException} / batch abend (return code {@code 8}), <em>not</em>
 * to a reject record, and is deliberately <strong>not</strong> a constant here.
 * Do not add {@code 109} (or any other value) to this enum.
 *
 * <h2>Return-code context (owned by the batch layer)</h2>
 * The presence of rejects influences the batch job return code:
 * {@code RC 0} when no records were rejected, {@code RC 4} when at least one was
 * rejected ({@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE}, CBTRN02C
 * L229-L231), and {@code RC 8} on an abend. Return-code mapping is a separate
 * concern that belongs to the batch layer, by design; it is described here only
 * for context and is deliberately outside the responsibility of this enum.
 *
 * @see #fromCode(int)
 * @see #findByCode(int)
 */
public enum RejectCode {

    /**
     * Reject {@code 100} — the card cross-reference lookup missed. Set in
     * {@code 1500-A-LOOKUP-XREF} when {@code READ XREF-FILE ... INVALID KEY}
     * (CBTRN02C L385-L387). Because {@code 1500-VALIDATE-TRAN} performs the
     * account lookup only while the fail reason is still zero (L372), this code
     * short-circuits validation and precludes {@link #ACCOUNT_NOT_FOUND},
     * {@link #OVER_CREDIT_LIMIT}, and {@link #ACCOUNT_EXPIRED}.
     */
    INVALID_CARD_NUMBER(100, "INVALID CARD NUMBER FOUND"),

    /**
     * Reject {@code 101} — the account record was not found. Set in
     * {@code 1500-B-LOOKUP-ACCT} when {@code READ ACCOUNT-FILE ... INVALID KEY}
     * (CBTRN02C L397-L399), using the account id resolved from the
     * cross-reference.
     */
    ACCOUNT_NOT_FOUND(101, "ACCOUNT RECORD NOT FOUND"),

    /**
     * Reject {@code 102} — the transaction would exceed the account credit
     * limit. Set in the {@code ELSE} of {@code IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL}
     * (CBTRN02C L407-L412), where
     * {@code WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT}
     * (L403-L405). May be overwritten by {@link #ACCOUNT_EXPIRED} when both the
     * credit-limit and expiration checks fail for the same record (see the
     * class-level evaluation-order notes).
     */
    OVER_CREDIT_LIMIT(102, "OVERLIMIT TRANSACTION"),

    /**
     * Reject {@code 103} — the transaction was received after the account
     * expiration date. Set in the {@code ELSE} of
     * {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)}
     * (CBTRN02C L414-L419). This check runs after, and independently of, the
     * credit-limit check, so when both fail this code is written last and wins
     * (last-writer-wins over {@link #OVER_CREDIT_LIMIT}).
     */
    ACCOUNT_EXPIRED(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION");

    /**
     * The numeric reject reason code exactly as moved into
     * {@code WS-VALIDATION-FAIL-REASON} ({@code PIC 9(04)}). An {@code int}
     * losslessly represents the {@code 100}-{@code 103} range.
     */
    private final int code;

    /**
     * The reject reason description text, copied verbatim from the COBOL literal
     * moved into {@code WS-VALIDATION-FAIL-REASON-DESC} ({@code PIC X(76)}).
     */
    private final String description;

    /**
     * Binds a constant to its parity-critical numeric code and description text.
     *
     * @param code        the numeric reject reason code (100-103)
     * @param description the exact COBOL reject description literal
     */
    RejectCode(int code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Returns the numeric reject reason code (one of {@code 100}, {@code 101},
     * {@code 102}, {@code 103}) as moved into {@code WS-VALIDATION-FAIL-REASON}.
     *
     * @return the numeric reject reason code
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns the reject reason description exactly as it appears in the COBOL
     * source (for example {@code "OVERLIMIT TRANSACTION"}). The text is
     * preserved verbatim, including spelling, so it can be written to the reject
     * file without alteration.
     *
     * @return the exact COBOL reject description text
     */
    public String getDescription() {
        return description;
    }

    /**
     * Reverse lookup that resolves a numeric reject code to its constant,
     * throwing if the value is not part of the closed set. Use this when the
     * caller requires the code to be a known reject reason and treats anything
     * else as a programming error.
     *
     * @param code the numeric reject code to resolve
     * @return the matching {@link RejectCode}
     * @throws IllegalArgumentException if {@code code} is not one of
     *                                  {@code 100}, {@code 101}, {@code 102}, or
     *                                  {@code 103} (for example {@code 109},
     *                                  {@code 0}, or {@code 999})
     */
    public static RejectCode fromCode(int code) {
        return findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("Unknown reject code: " + code));
    }

    /**
     * Non-throwing reverse lookup that resolves a numeric reject code to its
     * constant if it belongs to the closed set. Use this when a code may
     * legitimately be absent (for example when re-reading an external reject
     * file) and the caller wants to branch on presence rather than catch an
     * exception.
     *
     * @param code the numeric reject code to resolve
     * @return an {@link Optional} containing the matching {@link RejectCode}, or
     *         an empty {@link Optional} if {@code code} is not a known reject
     *         reason
     */
    public static Optional<RejectCode> findByCode(int code) {
        for (RejectCode rejectCode : values()) {
            if (rejectCode.code == code) {
                return Optional.of(rejectCode);
            }
        }
        return Optional.empty();
    }
}
