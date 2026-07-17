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

/**
 * Stateless translator from a CICS {@code RESP}/{@code RESP2} outcome to the
 * CardDemo typed-exception hierarchy, preserving the caller-visible outcome the
 * legacy online COBOL programs produced.
 *
 * <h2>What this replaces</h2>
 * Every online CardDemo program issues its file/terminal work through
 * {@code EXEC CICS ... RESP(WS-RESP-CD) RESP2(WS-REAS-CD)} and then branches on
 * the response with a repeated
 * {@code EVALUATE WS-RESP-CD WHEN DFHRESP(...)} block — for example
 * {@code legacy/cbl/COTRN01C.cbl} (L281 {@code WHEN DFHRESP(NORMAL)}, L283
 * {@code WHEN DFHRESP(NOTFND)}), {@code legacy/cbl/COUSR01C.cbl} (L251
 * {@code NORMAL}, L260 {@code DFHRESP(DUPKEY)}, L261 {@code DFHRESP(DUPREC)}),
 * and {@code legacy/cbl/COACTUPC.cbl} (repeated {@code NORMAL}/{@code NOTFND}
 * branches plus {@code IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL)}). This class
 * centralizes that {@code EVALUATE} into one place so that every migrated online
 * service reproduces the same not-found / duplicate / error semantics the COBOL
 * code had, rather than re-implementing the branch per call site.
 *
 * <h2>Mapping (the closed contract)</h2>
 * <table border="1">
 *   <caption>CICS {@code RESP} to Java outcome</caption>
 *   <tr><th>CICS condition ({@code DFHRESP})</th><th>Value</th><th>Outcome</th></tr>
 *   <tr><td>{@code NORMAL}</td><td>0</td><td>success — no exception (return)</td></tr>
 *   <tr><td>{@code ENDFILE}</td><td>20</td><td>normal end-of-browse — no exception (return)</td></tr>
 *   <tr><td>{@code NOTFND}</td><td>13</td><td>throw {@link RecordNotFoundException} (file status {@code "23"})</td></tr>
 *   <tr><td>{@code DUPKEY}</td><td>14</td><td>throw {@link DuplicateKeyException} (file status {@code "22"})</td></tr>
 *   <tr><td>{@code DUPREC}</td><td>15</td><td>throw {@link DuplicateKeyException} (file status {@code "22"})</td></tr>
 *   <tr><td>any other value</td><td>&mdash;</td><td>throw {@link FileStatusException} (generic I/O error)</td></tr>
 * </table>
 *
 * <p><strong>End-of-file is not an error.</strong> {@code ENDFILE} is the normal
 * terminal condition of a {@code STARTBR}/{@code READNEXT}/{@code READPREV}
 * browse loop, so it deliberately does <em>not</em> throw — matching the COBOL,
 * where reaching end-of-browse is ordinary loop termination. Browse callers can
 * test for it with {@link #isEndOfFile(int)} and stop paging without raising an
 * exception. {@code NORMAL} likewise returns normally.</p>
 *
 * <h2>Utility shape (deliberately not Spring-managed)</h2>
 * This is a {@code final} utility class with a {@code private} constructor and
 * only {@code static} members; it is never instantiated. It is intentionally
 * <em>not</em> a Spring bean and has no Spring or Jakarta dependency, which keeps
 * the {@code exception} package foundational and free of framework coupling. The
 * only Spring-managed member of this package is {@code GlobalExceptionHandler},
 * which maps the exceptions raised here to HTTP status codes (online) or batch
 * return codes (batch): {@link RecordNotFoundException} becomes HTTP 404,
 * {@link DuplicateKeyException} becomes HTTP 409, and {@link FileStatusException}
 * becomes HTTP 500 / batch return code 8.
 *
 * <h2>Sensitive data</h2>
 * <strong>Never pass a card CVV or a password as {@code recordType} or
 * {@code key}.</strong> The {@code key} is rendered into the thrown exception's
 * message, which may be logged or surfaced to a caller; only non-sensitive
 * identifiers (an account id, card number, transaction id, user id, and so on)
 * are permitted. The secondary reason code {@code RESP2}, when supplied, is a
 * numeric diagnostic and is preserved in the message for troubleshooting.
 *
 * <p>The rationale for this typed-exception design is recorded in
 * {@code docs/decision-log.md}; this class intentionally keeps only factual,
 * concise commentary.</p>
 *
 * @see FileStatusException
 * @see RecordNotFoundException
 * @see DuplicateKeyException
 */
public final class CicsRespMapper {

    /**
     * Utility class: prevents instantiation. All behavior is exposed through
     * {@code static} members, so an instance would carry no state and serve no
     * purpose.
     */
    private CicsRespMapper() {
    }

    /**
     * The subset of standard CICS {@code DFHRESP} condition names actually
     * observed across the online CardDemo programs ({@code app/cbl/CO*.cbl}),
     * each paired with its compile-time {@code DFHRESP} numeric value.
     *
     * <p>These five constants are the complete, closed set for this application;
     * no other {@code RESP} condition is referenced by the migrated online logic,
     * so no other constant is defined here. The numeric values are the
     * well-known CICS {@code DFHRESP} codes and are treated as a fixed contract.</p>
     */
    public enum CicsResp {

        /** {@code DFHRESP(NORMAL)} — the command completed successfully. */
        NORMAL(0),

        /** {@code DFHRESP(NOTFND)} — the requested record was not found. */
        NOTFND(13),

        /** {@code DFHRESP(DUPKEY)} — a duplicate alternate-index key was detected. */
        DUPKEY(14),

        /** {@code DFHRESP(DUPREC)} — an attempt was made to write a duplicate record. */
        DUPREC(15),

        /** {@code DFHRESP(ENDFILE)} — end of a browse; a normal terminal condition. */
        ENDFILE(20);

        /** The CICS {@code DFHRESP} numeric value for this condition. */
        private final int value;

        /**
         * Binds a condition name to its CICS {@code DFHRESP} numeric value.
         *
         * @param value the CICS {@code DFHRESP} numeric value
         */
        CicsResp(int value) {
            this.value = value;
        }

        /**
         * Returns the CICS {@code DFHRESP} numeric value carried by this
         * condition.
         *
         * @return the {@code DFHRESP} numeric value
         */
        public int getValue() {
            return value;
        }

        /**
         * Resolves a raw CICS {@code RESP} code to its matching condition
         * constant.
         *
         * <p>This is a strict lookup: an unrecognized code raises
         * {@link IllegalArgumentException}. Callers that may legitimately observe
         * response codes outside this closed set (any real CICS command can) must
         * use {@link CicsRespMapper#raiseFor(int, String, Object)} instead, which
         * treats an unknown code as a generic I/O error rather than a programming
         * error.</p>
         *
         * @param respCode the raw CICS {@code RESP} code to resolve
         * @return the matching {@code CicsResp} constant
         * @throws IllegalArgumentException if {@code respCode} does not correspond
         *                                  to any constant in this enum
         */
        public static CicsResp fromCode(int respCode) {
            for (CicsResp resp : values()) {
                if (resp.value == respCode) {
                    return resp;
                }
            }
            throw new IllegalArgumentException("Unknown CICS RESP code: " + respCode);
        }
    }

    /**
     * Translates a CICS {@code RESP} outcome into the matching typed exception,
     * or returns normally when the outcome is not an error.
     *
     * <p>This is the primary entry point, mirroring the COBOL
     * {@code EVALUATE WS-RESP-CD} that follows an {@code EXEC CICS} file command:</p>
     * <ul>
     *   <li>{@link CicsResp#NORMAL} (0) or {@link CicsResp#ENDFILE} (20) — returns
     *       normally; {@code ENDFILE} is a normal end-of-browse condition, not an
     *       error.</li>
     *   <li>{@link CicsResp#NOTFND} (13) — throws {@link RecordNotFoundException}.</li>
     *   <li>{@link CicsResp#DUPKEY} (14) or {@link CicsResp#DUPREC} (15) — throws
     *       {@link DuplicateKeyException}.</li>
     *   <li>any other value — throws {@link FileStatusException}, carrying the raw
     *       response code as its file-status string.</li>
     * </ul>
     *
     * @param respCode   the raw CICS {@code RESP} code captured from
     *                   {@code RESP(WS-RESP-CD)}
     * @param recordType a short, non-sensitive description of the record or entity
     *                   the command acted on (for example {@code "Transaction"} or
     *                   {@code "Account"}); must never be a CVV or password
     * @param key        the non-sensitive record key/id involved (for example a
     *                   transaction id or account id); rendered null-safely into
     *                   the exception message and must never be a CVV or password
     * @throws RecordNotFoundException if {@code respCode} is {@code NOTFND}
     * @throws DuplicateKeyException   if {@code respCode} is {@code DUPKEY} or
     *                                 {@code DUPREC}
     * @throws FileStatusException     if {@code respCode} is any other non-normal
     *                                 value
     */
    public static void raiseFor(int respCode, String recordType, Object key) {
        raiseInternal(respCode, null, recordType, key);
    }

    /**
     * Translates a CICS {@code RESP} outcome into the matching typed exception,
     * additionally preserving the secondary {@code RESP2} reason code in the
     * thrown message for diagnostics.
     *
     * <p>The classification is identical to
     * {@link #raiseFor(int, String, Object)}; the only difference is that a
     * {@code RESP2} value observed in programs such as
     * {@code legacy/cbl/COACTUPC.cbl} (for example {@code RESP2(WS-REAS-CD)}) is
     * appended to the diagnostic message. {@code RESP2} is a numeric code, never
     * sensitive data.</p>
     *
     * @param respCode   the raw CICS {@code RESP} code captured from
     *                   {@code RESP(WS-RESP-CD)}
     * @param resp2      the secondary CICS {@code RESP2} reason code captured from
     *                   {@code RESP2(WS-REAS-CD)}; preserved in the message for
     *                   troubleshooting
     * @param recordType a short, non-sensitive description of the record or entity
     *                   the command acted on; must never be a CVV or password
     * @param key        the non-sensitive record key/id involved; rendered
     *                   null-safely into the exception message and must never be a
     *                   CVV or password
     * @throws RecordNotFoundException if {@code respCode} is {@code NOTFND}
     * @throws DuplicateKeyException   if {@code respCode} is {@code DUPKEY} or
     *                                 {@code DUPREC}
     * @throws FileStatusException     if {@code respCode} is any other non-normal
     *                                 value
     */
    public static void raiseFor(int respCode, int resp2, String recordType, Object key) {
        raiseInternal(respCode, resp2, recordType, key);
    }

    /**
     * Shared classification used by both {@code raiseFor} overloads.
     *
     * <p>A {@code null} {@code resp2} means "no secondary reason code was
     * supplied" (the three-argument overload); a non-{@code null} value is
     * appended to the produced message (the four-argument overload). Keeping the
     * decision in one place guarantees both overloads stay in lock-step and
     * reproduce the identical COBOL {@code EVALUATE} outcome.</p>
     *
     * @param respCode   the raw CICS {@code RESP} code
     * @param resp2      the secondary {@code RESP2} reason code, or {@code null}
     *                   when none was supplied
     * @param recordType the non-sensitive record/entity description
     * @param key        the non-sensitive record key/id
     */
    private static void raiseInternal(int respCode, Integer resp2, String recordType, Object key) {
        // NORMAL and ENDFILE are non-error outcomes: the COBOL EVALUATE either
        // continued (NORMAL) or terminated a browse loop (ENDFILE) without
        // signalling a failure, so neither raises an exception here.
        if (respCode == CicsResp.NORMAL.getValue() || respCode == CicsResp.ENDFILE.getValue()) {
            return;
        }

        // RESP2, when present, is a numeric diagnostic appended to the message.
        String reason = (resp2 == null) ? "" : " (RESP2=" + resp2 + ")";

        if (respCode == CicsResp.NOTFND.getValue()) {
            throw new RecordNotFoundException(recordType + " not found: " + String.valueOf(key) + reason);
        }
        if (respCode == CicsResp.DUPKEY.getValue() || respCode == CicsResp.DUPREC.getValue()) {
            throw new DuplicateKeyException(recordType + " already exists: " + String.valueOf(key) + reason);
        }

        // Any other non-normal RESP is a generic I/O error. The raw code becomes
        // the file-status string so downstream handlers can inspect it, mirroring
        // how the COBOL programs escalated an unexpected RESP to an abend.
        String detail = (resp2 == null)
                ? "CICS error on " + recordType + " (RESP=" + respCode + ")"
                : "CICS error on " + recordType + " (RESP=" + respCode + ", RESP2=" + resp2 + ")";
        throw new FileStatusException(String.valueOf(respCode), detail);
    }

    /**
     * Tests whether a raw CICS {@code RESP} code is {@link CicsResp#NORMAL} (0).
     *
     * <p>Provided so a caller can guard the success path symbolically instead of
     * comparing against a magic number, reproducing
     * {@code IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL)} readably.</p>
     *
     * @param respCode the raw CICS {@code RESP} code to test
     * @return {@code true} if {@code respCode} equals {@link CicsResp#NORMAL}
     */
    public static boolean isNormal(int respCode) {
        return respCode == CicsResp.NORMAL.getValue();
    }

    /**
     * Tests whether a raw CICS {@code RESP} code is {@link CicsResp#ENDFILE} (20).
     *
     * <p>Browse loops (the migrated {@code STARTBR}/{@code READNEXT}/
     * {@code READPREV} paths) use this to detect normal end-of-browse and stop
     * paging without raising an exception — matching the COBOL, where end-of-file
     * on a browse is ordinary loop termination rather than an error.</p>
     *
     * @param respCode the raw CICS {@code RESP} code to test
     * @return {@code true} if {@code respCode} equals {@link CicsResp#ENDFILE}
     */
    public static boolean isEndOfFile(int respCode) {
        return respCode == CicsResp.ENDFILE.getValue();
    }
}
